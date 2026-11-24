package fr.nesy.guelmacontrol

import android.content.Context
import java.io.DataInputStream
import javax.net.ssl.SSLSocket
import kotlin.math.abs

internal sealed interface HobenOverride{
    data class Start(val halfDegrees:Int,val minutes:Int,val delay:Int=0):HobenOverride
    data class Power(val on:Boolean):HobenOverride
    data class Vent(val mode:Int):HobenOverride
    data class Mode(val mode:Int):HobenOverride
    data class Manual(val halfDegrees:Int):HobenOverride
    data class VacationConfig(val end:java.time.LocalDateTime,val halfDegrees:Int):HobenOverride
    data class Vacation(val enabled:Boolean):HobenOverride
    data class Schedule(val day:Int,val before:List<Int>,val after:List<Int>):HobenOverride
    data object Cancel:HobenOverride
}
internal data class HobenOverrideOutcome(val snapshot:HobenSnapshot?,val message:String)

internal fun hobenOverrideFrame(order:HobenOverride):ByteArray{
    val address:Int
    val values:IntArray
    fun single(a:Int,v:Int)=byteArrayOf(13,-1,-16,0,0,0,6,1,6,(a ushr 8).toByte(),a.toByte(),(v ushr 8).toByte(),v.toByte())
    when(order){
        is HobenOverride.Start->{
            require(order.halfDegrees in 30..50 && order.minutes in 15..480 && order.minutes%15==0 && order.delay in 0..480 && order.delay%15==0)
            address=1281
            values=intArrayOf(order.halfDegrees*5,order.delay,order.minutes)
        }
        HobenOverride.Cancel->{address=1282;values=intArrayOf(0,0)}
        is HobenOverride.Power->return single(1280,if(order.on)1 else 0)
        is HobenOverride.Vent->{require(order.mode in 0..2);return single(1284,order.mode)}
        is HobenOverride.Mode->{require(order.mode in listOf(0,2));return single(1792,order.mode)}
        else->error("Commande de configuration : trame dédiée")
    }
    val data=values.flatMap{listOf((it ushr 8).toByte(),it.toByte())}.toByteArray()
    return byteArrayOf(13,-1,-16,0,0,0,(7+data.size).toByte(),1,16,(address ushr 8).toByte(),address.toByte(),0,values.size.toByte(),data.size.toByte())+data
}
internal fun hobenOverrideBlock(s:HobenSnapshot,order:HobenOverride):String?{
    if(order is HobenOverride.Power&&!order.on)return if(s.onOff==0)"Arrêt déjà demandé."else if(s.onOff!=1)"État marche/arrêt inconnu."else null
    if(order==HobenOverride.Cancel)return if(!s.activeOverride&&!s.scheduledOverride)"Aucune dérogation à annuler."else null
    if(order is HobenOverride.Power&&s.onOff==1)return "Marche déjà autorisée."
    if(order is HobenOverride.Mode&&s.mode==order.mode)return "Mode déjà sélectionné."
    if(s.onOff!=1&&order !is HobenOverride.Power)return "Poêle non autorisé à chauffer. Vérifiez MyHOBEN."
    if(order is HobenOverride.Start&&s.mode!=0)return "Activation limitée au mode Habitation."
    if(s.mode !in listOf(0,2))return "Mode non compatible."
    if(s.faults!=0||s.info and (1 or 4 or 128 or 2048)!=0)return "Défaut, vacances, hors gel, arrêt externe ou fenêtre ouverte : commande bloquée."
    if(s.ambient==null||s.target==null)return "Températures non valides : commande bloquée."
    if(order is HobenOverride.Vent)return if(s.vent==order.mode)"Ventilation déjà sélectionnée."else null
    if(s.activeOverride||s.scheduledOverride)return "Une dérogation existe déjà. Annulez-la d’abord."
    return null
}
internal fun hobenOverrideMatches(s:HobenSnapshot,order:HobenOverride):Boolean=when(order){
    HobenOverride.Cancel->!s.activeOverride&&!s.scheduledOverride
    is HobenOverride.Start->(if(order.delay==0)s.activeOverride&&!s.scheduledOverride else s.scheduledOverride&&s.delayMinutes in (order.delay-2)..order.delay)&&s.overrideTemp?.let{abs(it-order.halfDegrees/2.0)<0.05}==true&&s.overrideMinutes in (order.minutes-2)..order.minutes
    is HobenOverride.Power->s.onOff==(if(order.on)1 else 0)
    is HobenOverride.Vent->s.vent==order.mode
    is HobenOverride.Mode->s.mode==order.mode
    else->false
}

internal fun applyHobenOverride(socket:SSLSocket,input:DataInputStream,before:HobenSnapshot,order:HobenOverride):HobenOverrideOutcome{
    if(order is HobenOverride.Manual||order is HobenOverride.VacationConfig||order is HobenOverride.Vacation||order is HobenOverride.Schedule)return applyHobenConfig(socket,input,before,order)
    val frame=hobenOverrideFrame(order)
    hobenOverrideBlock(before,order)?.let{return HobenOverrideOutcome(before,it+" Aucun ordre envoyé.")}
    // A command is written exactly once. Never retry an uncertain write.
    try{
        socket.soTimeout=6000
        socket.outputStream.write(frame);socket.outputStream.flush()
        val expected=byteArrayOf(frame[8],frame[9],frame[10],frame[11],frame[12])
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
                        if(pdu.size==2&&(pdu[0].toInt() and 255)==(frame[8].toInt() or 128))return HobenOverrideOutcome(null,"Commande refusée (Modbus "+(pdu[1].toInt() and 255)+"). Relisez le poêle.")
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
            if(hobenOverrideMatches(actual,order))return HobenOverrideOutcome(actual,when(order){HobenOverride.Cancel->"Annulation confirmée par le poêle.";is HobenOverride.Start->"Dérogation confirmée par le poêle.";else->"Réglage confirmé par le poêle."})
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
