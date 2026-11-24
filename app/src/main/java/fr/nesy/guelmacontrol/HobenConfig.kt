package fr.nesy.guelmacontrol

import java.io.DataInputStream
import javax.net.ssl.SSLSocket
import java.time.LocalDateTime
import kotlin.math.abs

internal data class HobenDetails(val day:Int,val config:List<Int>,val slots:List<Int>,val counters:List<Int>){
    val manualTemp get()=config[5].toShort().toInt()/10.0
    val vacationTemp get()=config[3].toShort().toInt()/10.0
    val vacationEnabled get()=config[6] and 1!=0
    val vacationEnd get()=runCatching{LocalDateTime.of(2000+(config[0] and 255),config[1] ushr 8,config[1] and 255,config[2] ushr 8,config[2] and 255)}.getOrNull()
}
internal data class HobenSlot(val active:Boolean,val start:Int,val end:Int,val silence:Boolean,val temp:Int,val maxPower:Int)
internal fun decodeSlot(values:List<Int>,i:Int):HobenSlot{
    require(values.size==21&&i in 0..6)
    val a=values[3*i];val b=values[3*i+1];val c=values[3*i+2]
    return HobenSlot(a and 32768!=0,a and 2047,b and 2047,b and 32768!=0,c and 511,c ushr 9)
}
internal fun changeSlot(values:List<Int>,i:Int,slot:HobenSlot):List<Int>{
    require(values.size==21&&i in 0..6&&slot.start in 0..1439&&slot.end in 1..1440&&slot.start<slot.end&&slot.temp in 150..250&&slot.maxPower in 0..100)
    val v=values.toMutableList()
    v[3*i]=(v[3*i] and 0x7800) or (if(slot.active)32768 else 0) or slot.start
    v[3*i+1]=(v[3*i+1] and 0x7800) or (if(slot.silence)32768 else 0) or slot.end
    v[3*i+2]=(slot.maxPower shl 9) or slot.temp
    return v
}
private fun word(v:Int)=listOf((v ushr 8).toByte(),v.toByte())
private fun packet(tx:Int,pdu:List<Byte>):ByteArray=(listOf<Byte>(13)+word(tx)+word(0)+word(pdu.size+1)+listOf<Byte>(1)+pdu).toByteArray()
private fun request(socket:SSLSocket,input:DataInputStream,tx:Int,pdu:List<Byte>):ByteArray{
    socket.soTimeout=8000
    socket.outputStream.write(packet(tx,pdu));socket.outputStream.flush()
    repeat(6){
        when(val header=input.readUnsignedByte()){
            10->{socket.outputStream.write(11);socket.outputStream.flush()}
            7,14,27->{
                if(header==27)input.readFully(ByteArray(4))
                val id=input.readUnsignedShort();val protocol=input.readUnsignedShort();val len=input.readUnsignedShort()
                if(protocol!=0||len !in 2..254)throw HobenReadException("Enveloppe configuration inattendue.")
                val unit=input.readUnsignedByte();val p=ByteArray(len-1);input.readFully(p)
                if(header!=27&&id==tx&&unit==1){
                    if(p.size==2&&(p[0].toInt() and 128)!=0)throw HobenReadException("Configuration refusée par le poêle.")
                    return p
                }
            }
            else->throw HobenReadException("Message inattendu : $header")
        }
    }
    throw HobenReadException("Réponse configuration absente.")
}
private fun words(p:ByteArray,fn:Int,count:Int):List<Int>{
    if(p.size!=2+count*2||p[0].toInt()!=fn||(p[1].toInt() and 255)!=count*2)throw HobenReadException("Données de configuration inattendues.")
    return List(count){i->((p[2+i*2].toInt() and 255) shl 8) or (p[3+i*2].toInt() and 255)}
}
private fun regs(s:SSLSocket,i:DataInputStream,address:Int,count:Int,tx:Int)=words(request(s,i,tx,listOf<Byte>(4)+word(address)+word(count)),4,count)
internal fun readHobenDetails(s:SSLSocket,i:DataInputStream,day:Int):HobenDetails{
    require(day in 0..6)
    return HobenDetails(day,regs(s,i,1536,7,101),regs(s,i,3072+21*day,21,102),regs(s,i,2560,35,103))
}
internal fun applyHobenConfig(s:SSLSocket,i:DataInputStream,before:HobenSnapshot,order:HobenOverride):HobenOverrideOutcome{
    fun blocked(t:String)=HobenOverrideOutcome(before,t+" Aucun ordre envoyé.")
    if(before.faults!=0||before.mode !in listOf(0,2)||before.activeOverride||before.scheduledOverride)return blocked("État incompatible avec la configuration.")
    try{
        val cfg=regs(s,i,1536,7,111)
        if(order is HobenOverride.Schedule&&cfg[6] and 1!=0)return blocked("Mode vacances actif : terminez les vacances avant de modifier le programme.")
        val readBack:(List<Int>)->Boolean
        val pdu:List<Byte>
        when(order){
            is HobenOverride.Manual->{
                require(order.halfDegrees in 30..50)
                val power=cfg[4] and 255
                if(power !in 1..100)return blocked("Puissance manuelle non reconnue.")
                val values=listOf(order.halfDegrees*5,power)
                pdu=listOf<Byte>(16)+word(1798)+word(2)+listOf<Byte>(4)+values.flatMap(::word)
                readBack={it[5]==values[0]&&(it[4] and 255)==power}
            }
            is HobenOverride.VacationConfig->{
                require(order.halfDegrees in 30..50&&order.end.isAfter(LocalDateTime.now())&&order.end.isBefore(LocalDateTime.now().plusYears(1)))
                val power=cfg[4] ushr 8
                if(power !in 1..100)return blocked("Puissance vacances non reconnue.")
                val d=order.end
                val values=listOf(d.year,(d.monthValue shl 8) or d.dayOfMonth,(d.hour shl 8) or d.minute,order.halfDegrees*5,power)
                pdu=listOf<Byte>(16)+word(1793)+word(5)+listOf<Byte>(10)+values.flatMap(::word)
                readBack={(it[0] and 255)==d.year-2000&&it[1]==values[1]&&it[2]==values[2]&&it[3]==values[3]&&(it[4] ushr 8)==power}
            }
            is HobenOverride.Vacation->{
                val detail=HobenDetails(0,cfg,List(21){0},emptyList())
                if(order.enabled&&(detail.vacationEnd?.isAfter(LocalDateTime.now())!=true||detail.vacationTemp !in 15.0..25.0))return blocked("Configurez une date future et une température de vacances valide.")
                pdu=listOf<Byte>(22)+word(1800)+word(65534)+word(if(order.enabled)1 else 0)
                readBack={(it[6] and 1!=0)==order.enabled}
            }
            is HobenOverride.Schedule->{
                require(order.day in 0..6&&order.before.size==21&&order.after.size==21&&order.after.all{it in 0..65535})
                val addr=3072+21*order.day
                if(regs(s,i,addr,21,112)!=order.before)return blocked("Programme modifié depuis le relevé. Rechargez la journée.")
                val changed=(0..6).filter{j->order.before.subList(j*3,j*3+3)!=order.after.subList(j*3,j*3+3)}
                if(changed.size!=1)return blocked("Modifiez une plage à la fois.")
                val idx=changed.single()
                val slot=decodeSlot(order.after,idx)
                if(changeSlot(order.before,idx,slot)!=order.after||slot.maxPower!=decodeSlot(order.before,idx).maxPower)return blocked("Configuration de plage non valide.")
                val active=(0..6).map{decodeSlot(order.after,it)}.filter{it.active}.sortedBy{it.start}
                if(active.any{it.start !in 0..1439||it.end !in 1..1440||it.start>=it.end}||active.zipWithNext().any{(a,b)->a.end>b.start})return blocked("Plages invalides ou chevauchantes.")
                val data=order.after.flatMap(::word)
                val reply=request(s,i,65520,listOf<Byte>(23)+word(addr)+word(21)+word(3328+21*order.day)+word(21)+listOf<Byte>(42)+data)
                words(reply,23,21)
                Thread.sleep(1200)
                if(regs(s,i,addr,21,113)!=order.after)return HobenOverrideOutcome(null,"Programme non confirmé. Relisez la journée avant de réessayer.")
                return HobenOverrideOutcome(readHobenSnapshot(s,i),"Plage horaire confirmée par relecture.")
            }
            else->return blocked("Ordre non reconnu.")
        }
        val ack=request(s,i,65520,pdu)
        val expected=if(pdu[0].toInt()==22)pdu.toByteArray()else pdu.take(5).toByteArray()
        if(!ack.contentEquals(expected))return HobenOverrideOutcome(null,"Accusé de configuration inattendu. Relisez le poêle.")
        repeat(3){
            Thread.sleep(1500)
            if(readBack(regs(s,i,1536,7,114+it)))return HobenOverrideOutcome(readHobenSnapshot(s,i),"Configuration confirmée par relecture.")
        }
        return HobenOverrideOutcome(null,"Configuration non confirmée. Relisez le poêle.")
    }catch(_:Exception){return HobenOverrideOutcome(null,"Résultat configuration incertain. Aucun renvoi automatique.")}
}
