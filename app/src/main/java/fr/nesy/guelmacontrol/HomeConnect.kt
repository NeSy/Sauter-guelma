package fr.nesy.guelmacontrol

import android.content.Context
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.locks.ReentrantLock

internal const val HC_SCOPES="IdentifyAppliance Oven-Monitor Oven-Settings Hob-Monitor Hob-Settings Hood-Monitor Hood-Settings"
private const val HC_BASE="https://api.home-connect.com"
internal data class HcField(val key:String,val value:String,val unit:String,val label:String)
internal data class HcAppliance(val id:String,val name:String,val brand:String,val type:String,val model:String,val connected:Boolean,
    val status:List<HcField> = emptyList(),val settings:List<HcField> = emptyList(),val options:List<HcField> = emptyList(),
    val activeProgram:String?=null,val error:String?=null,val timestamp:Long=0L){
    fun field(key:String)= (status+settings+options).firstOrNull{it.key==key}
    fun value(key:String)=field(key)?.value
    val kind:String get()=hcKind(type)
}
internal fun hcKind(type:String)=when(type){"Oven"->"Four";"Hob","Cooktop"->"Plaques";"Hood"->"Hotte";else->"Autre"}
internal fun hcClientValid(id:String)=id.length==64&&id.all{it.isLetterOrDigit()&&it.code<128}
internal fun hcMinutes(seconds:String?)=seconds?.toLongOrNull()?.takeIf{it in 0..604800}?.let{"${it/3600} h ${(it%3600)/60} min"}
internal fun hcEnum(value:String)=when(value.substringAfterLast('.')){
    "Ready"->"Prêt";"Run"->"En cours";"Pause"->"En pause";"Finished"->"Terminé";"Inactive"->"Inactif";"DelayedStart"->"Départ différé"
    "ActionRequired"->"Action requise";"Error"->"Défaut";"Aborting"->"Arrêt en cours";"On"->"Allumé";"Off"->"Éteint";"Standby"->"Veille";"Open"->"Ouverte";"Closed"->"Fermée";"Locked"->"Verrouillée"
    "FanOff"->"Arrêt";"FanStage01"->"Vitesse 1";"FanStage02"->"Vitesse 2";"FanStage03"->"Vitesse 3";"FanStage04"->"Vitesse 4";"FanStage05"->"Vitesse 5"
    "true"->"Oui";"false"->"Non";else->value.substringAfterLast('.')
}
internal data class HcDeviceGrant(val code:String,val userCode:String,val uri:String,val interval:Int,val expiresAt:Long,val expiresWall:Long=0L,val completeUri:String?=null)
internal data class HcToken(val client:String,val secret:String,val access:String,val refresh:String,val expiry:Long)
internal class HcException(val code:Int,val reason:String,val retrySeconds:Long=0):Exception(reason)
private fun enc(s:String)=URLEncoder.encode(s,"UTF-8").replace("+","%20")
private fun jsonFields(arr:JSONArray?):List<HcField>{
    if(arr==null)return emptyList()
    return (0 until minOf(arr.length(),200)).mapNotNull{i->
        val o=arr.optJSONObject(i)?:return@mapNotNull null
        val key=o.optString("key")
        val v=o.opt("value")
        if(key.isBlank()||v==null||v==JSONObject.NULL||v is JSONObject||v is JSONArray)return@mapNotNull null
        HcField(key,v.toString().take(180),o.optString("unit").take(20),o.optString("name",key.substringAfterLast('.')).take(100))
    }
}
internal object HomeConnect{
    private val gate=ReentrantLock()
    private val state=MutableStateFlow<List<HcAppliance>>(emptyList())
    val appliances=state.asStateFlow()
    private val message=MutableStateFlow("")
    val status=message.asStateFlow()
    private val busyState=MutableStateFlow(false)
    val busy=busyState.asStateFlow()
    private fun vault(c:Context)=CredentialVault(c,"homeconnect")
    private fun prefs(c:Context)=c.getSharedPreferences("homeconnect_limits",Context.MODE_PRIVATE)
    fun saved(c:Context)=vault(c).hasCredentials()
    fun forget(c:Context):Boolean{
        if(!gate.tryLock())return false
        try{vault(c).clear();state.value=emptyList();message.value="Connexion locale supprimée.";return true}finally{gate.unlock()}
    }
    private fun readToken(c:Context):HcToken?{
        val p=vault(c).load()?:return null
        return runCatching{val j=JSONObject(p.second);HcToken(p.first,j.optString("secret"),j.getString("access"),j.optString("refresh"),j.getLong("expiry"))}.getOrNull()
    }
    private fun saveToken(c:Context,t:HcToken){
        vault(c).save(t.client,JSONObject().put("secret",t.secret).put("access",t.access).put("refresh",t.refresh).put("expiry",t.expiry).toString())
    }
    private fun http(path:String,token:String?=null,form:Map<String,String>?=null):JSONObject{
        require(path.startsWith("/security/oauth/")||path.startsWith("/api/homeappliances"))
        val conn=URL(HC_BASE+path).openConnection() as HttpsURLConnection
        conn.connectTimeout=8000;conn.readTimeout=10000;conn.instanceFollowRedirects=false
        conn.setRequestProperty("Accept",if(form==null)"application/vnd.bsh.sdk.v1+json"else "application/json")
        conn.setRequestProperty("Accept-Language","fr-FR")
        if(token!=null)conn.setRequestProperty("Authorization","Bearer "+token)
        try{
            if(form!=null){
                conn.requestMethod="POST";conn.doOutput=true
                conn.setRequestProperty("Content-Type","application/x-www-form-urlencoded")
                val payload=form.entries.joinToString("&"){enc(it.key)+"="+enc(it.value)}.toByteArray(Charsets.UTF_8)
                conn.setFixedLengthStreamingMode(payload.size)
                conn.outputStream.use{it.write(payload)}
            }
            val code=conn.responseCode
            val stream=if(code in 200..299)conn.inputStream else conn.errorStream
            val data=stream?.use{input->val out=java.io.ByteArrayOutputStream();val b=ByteArray(4096);while(true){val n=input.read(b);if(n<0)break;out.write(b,0,n);if(out.size()>1048576)throw HcException(code,"Réponse trop volumineuse.")};out.toString("UTF-8")}?: ""
            val j=runCatching{JSONObject(data)}.getOrElse{if(code in 200..299)throw HcException(code,"invalid_json_response") else JSONObject()}
            if(code !in 200..299){
                val error=j.opt("error")
                val reason=if(error is String)error else if(error is JSONObject)error.optString("key").ifBlank{error.optString("error")}else ""
                throw HcException(code,reason.takeIf{it.matches(Regex("[A-Za-z0-9_.-]{1,120}"))}?:"HTTP $code",conn.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(5,86400)?:60)
            }
            return j
        }finally{conn.disconnect()}
    }
    fun begin(client:String):HcDeviceGrant{
        require(hcClientValid(client))
        val j=http("/security/oauth/device_authorization",form=mapOf("client_id" to client,"scope" to HC_SCOPES))
        val uri=j.optString("verification_uri")
        if(!hcSafeVerificationUri(uri))throw HcException(200,"invalid_verification_uri")
        val complete=j.optString("verification_uri_complete").takeIf(::hcSafeVerificationUri)
        val lifetime=j.optLong("expires_in",300).coerceIn(1,1800)*1000L
        val code=j.optString("device_code");val user=j.optString("user_code")
        if(code.isBlank()||user.isBlank())throw HcException(200,"missing_device_code")
        return HcDeviceGrant(code,user,uri,j.optInt("interval",5).coerceAtLeast(5),android.os.SystemClock.elapsedRealtime()+lifetime,System.currentTimeMillis()+lifetime,complete)
    }
    private fun tokenFrom(j:JSONObject,client:String,secret:String,oldRefresh:String=""):HcToken{
        val access=j.optString("access_token")
        if(access.isBlank()||access=="null")throw HcException(200,"missing_access_token")
        val kind=j.optString("token_type","Bearer")
        if(kind.isNotBlank()&&!kind.equals("Bearer",true))throw HcException(200,"unsupported_token_type")
        return HcToken(client,secret,access,j.optString("refresh_token",oldRefresh).ifBlank{oldRefresh},System.currentTimeMillis()+j.optLong("expires_in",86400L).coerceIn(60,172800)*1000L)
    }
    fun poll(c:Context,grant:HcDeviceGrant,client:String,secret:String){
        if(android.os.SystemClock.elapsedRealtime()>=grant.expiresAt)throw HcException(400,"expired_token")
        val form=mutableMapOf("grant_type" to "device_code","device_code" to grant.code,"client_id" to client)
        if(secret.isNotBlank())form["client_secret"]=secret
        val j=http("/security/oauth/token",form=form)
        val t=tokenFrom(j,client,secret)
        gate.lock()
        try{
            try{saveToken(c,t)}catch(_:Exception){throw HcException(200,"local_token_storage_failed")}
            prefs(c).edit().remove("last_attempt").apply()
            state.value=emptyList();message.value="Autorisation obtenue. Lecture des appareils nécessaire."
        }finally{gate.unlock()}
    }
    private fun refreshToken(c:Context,t:HcToken):HcToken{
        if(t.refresh.isBlank())throw HcException(401,"Autorisation à renouveler.")
        val f=mutableMapOf("grant_type" to "refresh_token","refresh_token" to t.refresh,"client_id" to t.client)
        if(t.secret.isNotBlank())f["client_secret"]=t.secret
        val next=tokenFrom(http("/security/oauth/token",form=f),t.client,t.secret,t.refresh)
        saveToken(c,next);return next
    }
    fun refresh(c:Context,force:Boolean=false){
        if(!gate.tryLock())return
        busyState.value=true
        try{
            val p=prefs(c);val now=System.currentTimeMillis()
            if(now<p.getLong("blocked_until",0)){message.value="Limite Home Connect : réessayez plus tard.";return}
            val last=p.getLong("last_attempt",0)
            if(now-last in 0..29999&&state.value.isNotEmpty())return
            if(!force&&state.value.isNotEmpty()&&now-state.value.maxOf{it.timestamp}<300000)return
            if(now-last in 0..29999){message.value="Attendez 30 secondes entre deux lectures.";return}
            p.edit().putLong("last_attempt",now).apply()
            var token=readToken(c)?:run{message.value="Home Connect non connecté.";return}
            if(token.expiry<now+60000)token=refreshToken(c,token)
            val list=try{http("/api/homeappliances",token.access)}catch(e:HcException){
                if(e.code!=401)throw e
                token=refreshToken(c,token);http("/api/homeappliances",token.access)
            }
            val arr=list.getJSONObject("data").getJSONArray("homeappliances")
            val result=mutableListOf<HcAppliance>()
            for(i in 0 until minOf(arr.length(),50)){
                val o=arr.optJSONObject(i)?:continue
                val type=o.optString("type");val id=o.optString("haId")
                if(type !in listOf("Oven","Hob","Cooktop","Hood")||id.isBlank()||id.length>256)continue
                var a=HcAppliance(id,o.optString("name").ifBlank{hcKind(type)},o.optString("brand"),type,o.optString("enumber"),o.optBoolean("connected"),timestamp=System.currentTimeMillis())
                if(a.connected){
                    val base="/api/homeappliances/"+enc(id)
                    try{
                        val st=jsonFields(http(base+"/status",token.access).getJSONObject("data").optJSONArray("status"))
                        a=a.copy(status=st)
                        val settings=try{jsonFields(http(base+"/settings",token.access).getJSONObject("data").optJSONArray("settings"))}catch(e:HcException){if(e.code in listOf(403,404,409))emptyList()else throw e}
                        a=a.copy(settings=settings)
                        val operation=a.value("BSH.Common.Status.OperationState")?.substringAfterLast('.')
                        if(operation in listOf("Run","Pause","DelayedStart","Finished","ActionRequired")){
                            val prog=try{http(base+"/programs/active",token.access).getJSONObject("data")}catch(e:HcException){if(e.code in listOf(403,404,409))null else throw e}
                            if(prog!=null)a=a.copy(activeProgram=prog.optString("name").ifBlank{prog.optString("key")},options=jsonFields(prog.optJSONArray("options")))
                        }
                    }catch(e:HcException){
                        if(e.code==429||e.code==401)throw e
                        a=a.copy(error="Lecture partielle : HTTP "+e.code)
                    }catch(_:Exception){a=a.copy(error="Lecture partielle ou indisponible.")}
                }
                result.add(a)
            }
            state.value=result
            message.value=if(result.isEmpty())"Aucun four, plaque ou hotte trouvé sur ce compte."else "${result.size} appareil(s) cuisine détecté(s)."
        }catch(e:HcException){
            if(e.code==429)prefs(c).edit().putLong("blocked_until",System.currentTimeMillis()+e.retrySeconds*1000).apply()
            message.value=hcError(e)
        }catch(_:Exception){message.value="Lecture Home Connect indisponible."}
        finally{busyState.value=false;gate.unlock()}
    }
}
internal fun hcSafeVerificationUri(value:String):Boolean=runCatching{
    val uri=java.net.URI(value);val host=uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
    uri.scheme=="https"&&uri.userInfo==null&&(uri.port==-1||uri.port==443)&&(host=="home-connect.com"||host.endsWith(".home-connect.com"))
}.getOrDefault(false)
internal fun hcError(e:HcException)=when(e.reason){
    "missing_access_token"->"HTTP 200 : réponse reçue sans jeton d’accès exploitable."
    "unsupported_token_type"->"HTTP 200 : type de jeton non reconnu."
    "invalid_json_response"->"HTTP "+e.code+" : réponse du serveur non JSON."
    "local_token_storage_failed"->"Autorisation acceptée, mais stockage chiffré du jeton impossible."
    "invalid_verification_uri","missing_device_code"->"Réponse de demande d’autorisation incomplète."
    "temporarily_unavailable"->"Serveur Home Connect temporairement indisponible."
    "access_denied"->"Autorisation refusée."
    "expired_token"->"Code expiré. Recommencez l’autorisation."
    "invalid_grant"->"Autorisation expirée ou révoquée. Reconnectez-vous."
    "unauthorized_client","invalid_client"->"Client non autorisé : vérifiez le Device Flow et le Client ID dans le portail."
    else->when(e.code){401->"Autorisation Home Connect à renouveler.";403->"Accès non autorisé.";429->"Limite de requêtes Home Connect atteinte.";else->"Home Connect : "+e.reason}
}
