package fr.nesy.guelmacontrol

import android.content.Context
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import java.util.Locale

internal fun normalizeHobenId(raw:String):String? =
    raw.trim().replace("-","").takeIf { it.matches(Regex("[0-9a-fA-F]{32}")) }?.lowercase(Locale.ROOT)

internal fun hobenOpenFrame(userId:String,clientId:String,metadata:String):ByteArray {
    require(normalizeHobenId(userId)==userId && normalizeHobenId(clientId)==clientId)
    require(metadata.length in 1..160 && metadata.all { it.code in 32..126 })
    // Client open: type 03, user GUID, client kind/version bytes, build LE,
    // installation GUID, device description. No register write.
    return byteArrayOf(3) + userId.toByteArray(Charsets.UTF_8) +
        byteArrayOf(1,0,34,0) + clientId.toByteArray(Charsets.UTF_8) + metadata.toByteArray(Charsets.UTF_8)
}
internal fun hobenPinFrame(pin:Int):ByteArray {
    require(pin in 0..65534)
    return byteArrayOf(48,(pin and 255).toByte(),(pin ushr 8).toByte())
}

internal data class HobenIdentity(val type:Int,val revision:Int,val major:Int,val minor:Int)
internal sealed interface HobenPairResult {
    data class Opened(val identity:HobenIdentity,val state:HobenSnapshot?=null,val readStatus:String=""):HobenPairResult
    data object PinRequired:HobenPairResult
    data class Failed(val message:String):HobenPairResult
}
internal class HobenPairSession(context:Context):AutoCloseable {
    private val vault=CredentialVault(context.applicationContext,"hoben")
    private var socket:SSLSocket?=null
    private var userId:String?=null
    private var challengeTime=0L
    fun hasSaved()=vault.hasCredentials()
    fun forget(){close();vault.clear()}
    @Synchronized fun open(raw:String?,remember:Boolean):HobenPairResult {
        close()
        val saved=vault.load()
        val id=if(raw==null)saved?.first else normalizeHobenId(raw)
        if(id==null)return HobenPairResult.Failed("Identifiant MyHOBEN invalide ou non mémorisé.")
        val deviceId=saved?.takeIf{it.first==id}?.second?.let{normalizeHobenId(it)}
            ?:UUID.randomUUID().toString().replace("-","")
        return try {
            val tcp=Socket()
            try {
                tcp.connect(InetSocketAddress("myhoben.fr",465),5000)
                tcp.soTimeout=10000
                val factory=SSLSocketFactory.getDefault() as SSLSocketFactory
                val tls=factory.createSocket(tcp,"myhoben.fr",465,true) as SSLSocket
                socket=tls
                tls.soTimeout=10000
                val params=tls.sslParameters
                params.endpointIdentificationAlgorithm="HTTPS"
                tls.sslParameters=params
                tls.startHandshake()
                userId=id
                val frame=hobenOpenFrame(id,deviceId,"Android/Home Energy Control/Phone/Android/1/0/0/1/0/0,0")
                tls.outputStream.write(frame);tls.outputStream.flush()
                val result=readReply(remember)
                if(result==HobenPairResult.PinRequired)challengeTime=android.os.SystemClock.elapsedRealtime()
                else close()
                result
            } catch(e:Exception){tcp.close();throw e}
        } catch(e:Exception){close();failure(e)}
    }
    @Synchronized fun authenticate(pin:Int,remember:Boolean):HobenPairResult {
        val tls=socket
        if(tls==null||challengeTime==0L||android.os.SystemClock.elapsedRealtime()-challengeTime>55000){
            close();return HobenPairResult.Failed("Demande expirée. Relancez la connexion.")
        }
        return try {
            tls.outputStream.write(hobenPinFrame(pin));tls.outputStream.flush()
            val result=readReply(remember)
            if(result==HobenPairResult.PinRequired)HobenPairResult.Failed("Nouvelle demande d’autorisation. Relancez la connexion.")else result
        }catch(e:Exception){failure(e)}finally{close()}
    }
    private fun readReply(remember:Boolean):HobenPairResult {
        val input=DataInputStream(socket!!.inputStream)
        return when(val header=input.readUnsignedByte()){
            47 -> HobenPairResult.PinRequired
            5 -> HobenPairResult.Failed(when(input.readUnsignedByte()){
                2->"Identifiant refusé par Hoben."
                3->"Poêle non connecté au serveur."
                4->"Autorisation refusée par le poêle."
                5->"Autorisation expirée."
                6->"Serveur en maintenance."
                else->"Connexion refusée par Hoben."
            })
            4 -> {
                val frame=ByteArray(48)
                frame[0]=4
                input.readFully(frame,1,47)
                fun unsigned(index:Int)=frame[index].toInt() and 255
                val guid=normalizeHobenId(String(frame,14,32,Charsets.US_ASCII))
                val id=userId
                if(guid==null||id==null)return HobenPairResult.Failed("Réponse d’identification incomplète ou non reconnue.")
                if(remember)vault.save(id,guid)
                val identity=HobenIdentity(unsigned(8),unsigned(7),unsigned(10),unsigned(9))
                if(identity.type!=5||identity.revision!=0||identity.major!=8) {
                    HobenPairResult.Opened(identity,readStatus="Variante non validée pour la lecture.")
                }else{
                    try {
                        val snapshot=readHobenSnapshot(socket!!,input)
                        HobenPairResult.Opened(identity,snapshot,"Lecture réussie.")
                    } catch(e:Exception){
                        HobenPairResult.Opened(identity,readStatus=when(e){
                            is java.net.SocketTimeoutException->"Délai de lecture dépassé."
                            is java.io.EOFException->"Connexion fermée pendant la lecture."
                            is HobenReadException->e.message.orEmpty()
                            else->"Lecture impossible."
                        })
                    }
                }
            }
            else->HobenPairResult.Failed("Réponse non reconnue : type "+header+". Aucun ordre envoyé.")
        }
    }
    private fun failure(e:Exception)=HobenPairResult.Failed(when(e){
        is javax.net.ssl.SSLException->"Certificat ou transport TLS non validé."
        is java.net.SocketTimeoutException->"Réponse du serveur trop lente."
        is java.io.EOFException->"Connexion fermée par le serveur."
        else->"Connexion ou enregistrement local indisponible."
    })
    override fun close(){try{socket?.close()}catch(_:Exception){};socket=null;userId=null;challengeTime=0}
}
