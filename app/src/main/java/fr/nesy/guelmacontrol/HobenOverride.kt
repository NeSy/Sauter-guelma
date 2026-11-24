package fr.nesy.guelmacontrol

import android.content.Context
import java.io.DataInputStream
import javax.net.ssl.SSLSocket
import kotlin.math.abs

internal sealed interface HobenOverride{
    data class Start(val halfDegrees:Int,val minutes:Int):HobenOverride
    data object Cancel:HobenOverride
}
internal data class HobenOverrideOutcome(val snapshot:HobenSnapshot?,val message:String)

internal fun hobenOverrideFrame(order:HobenOverride):ByteArray{
    val address:Int
    val values:IntArray
    when(order){
        is HobenOverride.Start->{
            require(order.halfDegrees in 30..50 && order.minutes in 15..480 && order.minutes%15==0)
            address=1281
            values=intArrayOf(order.halfDegrees*5,0,order.minutes)
        }
        HobenOverride.Cancel->{address=1282;values=intArrayOf(0,0)}
    }
    val data=values.flatMap{listOf((it ushr 8).toByte(),it.toByte())}.toByteArray()
    return byteArrayOf(13,-1,-16,0,0,0,(7+data.size).toByte(),1,16,(address ushr 8).toByte(),address.toByte(),0,values.size.toByte(),data.size.toByte())+data
}
internal fun hobenOverrideBlock(s:HobenSnapshot,order:HobenOverride):String?{
    if(order==HobenOverride.Cancel)return if(!s.activeOverride&&!s.scheduledOverride)"Aucune dérogation à annuler."else null
    if(s.onOff!=1)return "Poêle non autorisé à chauffer. Vérifiez MyHOBEN."
    if(s.mode!=0)return "Activation limitée au mode Habitation."
    if(s.faults!=0||s.info and (1 or 4 or 128 or 2048)!=0)return "Défaut, vacances, hors gel, arrêt externe ou fenêtre ouverte : commande bloquée."
    if(s.ambient==null||s.target==null)return "Températures non valides : commande bloquée."
    if(s.activeOverride||s.scheduledOverride)return "Une dérogation existe déjà. Annulez-la d’abord."
    return null
}
internal fun hobenOverrideMatches(s:HobenSnapshot,order:HobenOverride):Boolean=when(order){
    HobenOverride.Cancel->!s.activeOverride&&!s.scheduledOverride
    is HobenOverride.Start->s.activeOverride&&!s.scheduledOverride&&s.overrideTemp?.let{abs(it-order.halfDegrees/2.0)<0.05}==true&&s.overrideMinutes in (order.minutes-2)..order.minutes
}

internal fun applyHobenOverride(socket:SSLSocket,input:DataInputStream,before:HobenSnapshot,order:HobenOverride):HobenOverrideOutcome{
    val frame=hobenOverrideFrame(order)
    hobenOverrideBlock(before,order)?.let{return HobenOverrideOutcome(before,it+" Aucun ordre envoyé.")}
    // A command is written exactly once. Never retry an uncertain write.
    try{
        socket.soTimeout=6000
        socket.outputStream.write(frame);socket.outputStream.flush()
        val expected=byteArrayOf(16,frame[9],frame[10],frame[11],frame[12])
        var ack=false
        for(i in 0..5){
            when(val header=input.readUnsignedByte()){
                10->{socket.outputStream.write(11);socket.outputStream.flush()}
                7,14,27->{
                    if(header==27)input.readFully(ByteArray(4))
                    val tx=input.readUnsignedShort();val protocol=input.readUnsignedShort();val len=input.readUnsignedShort()
                    if(protocol!=0||len !in 2..254)throw HobenReadException("Enveloppe inconnue.")
                    val unit=input.readUnsignedByte();val pdu=ByteArray(len-1);input.readFully(pdu)
                    if(header!=27&&tx==65520&&unit==1){
                        if(pdu.size==2&&(pdu[0].toInt() and 255)==144)return HobenOverrideOutcome(null,"Commande refusée (Modbus "+(pdu[1].toInt() and 255)+"). Relisez le poêle.")
                        if(!pdu.contentEquals(expected))throw HobenReadException("Accusé inattendu.")
                        ack=true;break
                    }
                }
                else->throw HobenReadException("Réponse inattendue : "+header)
            }
        }
        if(!ack)return HobenOverrideOutcome(null,"Ordre envoyé, accusé absent. Ne pas répéter : relisez le poêle.")
        repeat(3){
            Thread.sleep(if(it==0)1200L else 2000L)
            val actual=readHobenSnapshot(socket,input)
            if(hobenOverrideMatches(actual,order))return HobenOverrideOutcome(actual,if(order==HobenOverride.Cancel)"Annulation confirmée par le poêle."else "Dérogation confirmée par le poêle.")
        }
        return HobenOverrideOutcome(null,"Ordre acquitté, mais état non confirmé. Relisez le poêle ; aucun renvoi automatique.")
    }catch(_:Exception){
        return HobenOverrideOutcome(null,"Résultat de la commande incertain. Relisez le poêle ; aucun renvoi automatique.")
    }
}
internal object HobenOverrideGateway{
    private val gate=java.util.concurrent.locks.ReentrantLock()
    private fun prefs(c:Context)=c.getSharedPreferences("hoben_command_guard",Context.MODE_PRIVATE)
    fun needsRead(c:Context)=prefs(c).getBoolean("pending",false)
    fun noteRead(c:Context,snapshot:HobenSnapshot){
        if(!gate.tryLock())return
        try{
            val p=prefs(c)
            if(snapshot.timestamp>=p.getLong("since",Long.MAX_VALUE))p.edit().remove("pending").remove("since").commit()
        }finally{gate.unlock()}
    }
    fun read(context:Context):HobenPairResult{
        if(!gate.tryLock())return HobenPairResult.Failed("Commande en cours : lecture reportée.")
        try{
            val result=HobenPairSession(context).use{it.open(null,true)}
            (result as? HobenPairResult.Opened)?.state?.let{noteRead(context,it)}
            return result
        }finally{gate.unlock()}
    }
    fun execute(context:Context,order:HobenOverride):HobenPairResult{
        if(!gate.tryLock())return HobenPairResult.Failed("Une commande est déjà en cours. Aucun nouvel ordre envoyé.")
        try{
            val p=prefs(context)
            if(needsRead(context))return HobenPairResult.Failed("Résultat précédent à vérifier. Ouvrez Hoben puis Lire mon poêle.")
            if(!p.edit().putBoolean("pending",true).putLong("since",System.currentTimeMillis()).commit())
                return HobenPairResult.Failed("Journal local indisponible. Aucun ordre envoyé.")
            val result=HobenPairSession(context).use{it.open(null,true,order)}
            if(result !is HobenPairResult.Opened || result.state!=null){
                p.edit().remove("pending").remove("since").commit()
            }else{
                p.edit().putLong("since",System.currentTimeMillis()).commit()
            }
            (result as? HobenPairResult.Opened)?.state?.let{HobenObservation.accept(context,it,"Hoben · commande")}
            return result
        }catch(_:Exception){
            prefs(context).edit().putLong("since",System.currentTimeMillis()).commit()
            return HobenPairResult.Failed("Résultat incertain. Relisez le poêle avant toute nouvelle commande.")
        }finally{gate.unlock()}
    }
}
