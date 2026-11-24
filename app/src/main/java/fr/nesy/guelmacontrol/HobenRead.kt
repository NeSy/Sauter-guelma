package fr.nesy.guelmacontrol

import java.io.DataInputStream
import javax.net.ssl.SSLSocket

internal class HobenReadException(message:String):Exception(message)

internal data class HobenSnapshot(
    val ambient:Double?, val target:Double?, val power:Int?, val mode:Int,
    val state:Int,val vent:Int,val onOff:Int,val activeOverride:Boolean,
    val scheduledOverride:Boolean,val overrideTemp:Double?,val overrideMinutes:Int,
    val delayMinutes:Int,val faults:Int,val timestamp:Long
){
    val modeLabel:String get()=when(mode){0->"Habitation";1->"Magasin";2->"Manuel";else->"Mode non reconnu ($mode)"}
    val stateLabel:String get()=when(state){0->"Arrêt";1->"Fin de combustion";2->"Démarrage";3->"Redémarrage après coupure";4->"Stabilisation";5->"Gestion combustion";else->"État non reconnu ($state)"}
    val ventLabel:String get()=when(vent){0->"Normal";1->"Silence";2->"Boost";else->"Non reconnue ($vent)"}
}
// Fixed allowlisted input-register read; no generic register/write API.
internal fun hobenV4ReadFrame():ByteArray=byteArrayOf(13,-1,-1,0,0,0,6,1,4,4,0,0,20)

internal fun decodeHobenV4(registers:IntArray,now:Long):HobenSnapshot{
    require(registers.size==20&&registers.all{it in 0..65535})
    fun temp(index:Int):Double?=(registers[index].toShort().toInt()/10.0).takeIf{it in -30.0..60.0}
    val info=registers[11]
    return HobenSnapshot(
        temp(7),temp(8),(registers[6] ushr 8).takeIf{it in 0..100},
        registers[0] ushr 8,registers[6] and 255,registers[4] and 255,
        registers[0] and 255,(info and 32)!=0,(info and 64)!=0,
        temp(1),registers[3],registers[2],registers[5],now
    )
}
internal fun decodeHobenReadPdu(pdu:ByteArray,now:Long):HobenSnapshot{
    if(pdu.size==2&&(pdu[0].toInt() and 255)==132)throw HobenReadException("Exception Modbus "+(pdu[1].toInt() and 255))
    if(pdu.size!=42||pdu[0].toInt()!=4||pdu[1].toInt()!=40)throw HobenReadException("Réponse de lecture inattendue.")
    val registers=IntArray(20){i->((pdu[2+i*2].toInt() and 255) shl 8) or (pdu[3+i*2].toInt() and 255)}
    return decodeHobenV4(registers,now)
}
internal fun readHobenSnapshot(socket:SSLSocket,input:DataInputStream):HobenSnapshot{
    socket.soTimeout=8000
    socket.outputStream.write(hobenV4ReadFrame())
    socket.outputStream.flush()
    // Read known message envelopes only, with bounded lengths. Unexpected frames stop the exchange.
    repeat(6){
        when(val header=input.readUnsignedByte()){
            10->{socket.outputStream.write(11);socket.outputStream.flush()}
            5->throw HobenReadException("Session fermée par le serveur (code "+input.readUnsignedByte()+").")
            7,14,27->{
                if(header==27){val prefix=ByteArray(4);input.readFully(prefix)}
                val tx=input.readUnsignedShort()
                val protocol=input.readUnsignedShort()
                val length=input.readUnsignedShort()
                if(protocol!=0||length !in 2..254)throw HobenReadException("Enveloppe Modbus inattendue.")
                val unit=input.readUnsignedByte()
                val pdu=ByteArray(length-1);input.readFully(pdu)
                if(header!=27&&tx==65535&&unit==1)return decodeHobenReadPdu(pdu,System.currentTimeMillis())
            }
            else->throw HobenReadException("Message serveur inattendu : type $header.")
        }
    }
    throw HobenReadException("Aucune réponse correspondant à la lecture.")
}
