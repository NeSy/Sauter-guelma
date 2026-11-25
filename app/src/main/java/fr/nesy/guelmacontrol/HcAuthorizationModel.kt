package fr.nesy.guelmacontrol

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.json.JSONObject

class HcAuthorizationModel(application:Application):AndroidViewModel(application){
    private val c=application
    private val draft=CredentialVault(c,"homeconnect_pending")
    var saved by mutableStateOf(HomeConnect.saved(c));private set
    var client by mutableStateOf("")
    var secret by mutableStateOf("")
    internal var grant by mutableStateOf<HcDeviceGrant?>(null);private set
    var pending by mutableStateOf(false);private set
    var exchangeInFlight by mutableStateOf(false);private set
    var message by mutableStateOf("");private set
    var diagnostic by mutableStateOf("");private set
    private var job:Job?=null
    init{
        if(saved)draft.clear()
        else{
            val raw=draft.load()
            if(raw!=null)runCatching{
                val j=JSONObject(raw.second)
                client=raw.first;secret=j.optString("secret")
                val expiresWall=j.optLong("expiresWall",0)
                val expiresAt=j.optLong("expiresAt",0)
                val remaining=expiresWall-System.currentTimeMillis()
                if(remaining in 1..1800000&&expiresAt-SystemClock.elapsedRealtime() in 1..1800000){
                    val uri=j.optString("uri")
                    val complete=j.optString("complete").takeIf(::hcSafeVerificationUri)
                    if(hcSafeVerificationUri(uri)){
                        val g=HcDeviceGrant(j.getString("code"),j.getString("user"),uri,j.optInt("interval",5).coerceAtLeast(5),minOf(expiresAt,SystemClock.elapsedRealtime()+remaining),expiresWall,complete)
                        grant=g;poll(g,client,secret)
                    }
                }
            }.onFailure{draft.clear()}
        }
    }
    private fun persist(id:String,s:String,g:HcDeviceGrant?){
        val j=JSONObject().put("secret",s)
        if(g!=null)j.put("code",g.code).put("user",g.userCode).put("uri",g.uri).put("complete",g.completeUri.orEmpty()).put("interval",g.interval).put("expiresAt",g.expiresAt).put("expiresWall",g.expiresWall)
        draft.save(id,j.toString())
    }
    fun begin(){
        if(pending||grant!=null||!hcClientValid(client))return
        val id=client;val s=secret
        pending=true;message="Demande de code…";diagnostic=""
        job=viewModelScope.launch{
            try{
                val g=withContext(Dispatchers.IO){
                    persist(id,s,null)
                    val response=HomeConnect.begin(id)
                    persist(id,s,response)
                    response
                }
                grant=g;pending=false;poll(g,id,s)
            }catch(e:CancellationException){throw e}
            catch(e:HcException){message=hcError(e);diagnostic="Demande de code · HTTP "+e.code;pending=false}
            catch(e:Exception){message="Impossible de préparer l’autorisation.";diagnostic=hcExceptionKind(e);pending=false}
        }
    }
    private fun poll(g:HcDeviceGrant,id:String,s:String){
        job=viewModelScope.launch{
            var interval=g.interval.toLong()
            message="En attente de votre autorisation dans Chrome."
            while(SystemClock.elapsedRealtime()<g.expiresAt&&System.currentTimeMillis()<g.expiresWall){
                delay(interval.coerceAtMost(1800)*1000L)
                if(SystemClock.elapsedRealtime()>=g.expiresAt||System.currentTimeMillis()>=g.expiresWall)break
                exchangeInFlight=true
                try{
                    withContext(Dispatchers.IO){HomeConnect.poll(c,g,id,s)}
                    saved=true;grant=null;message="Autorisation reçue et enregistrée.";diagnostic=""
                    client="";secret=""
                    withContext(Dispatchers.IO){draft.clear();HomeConnect.refresh(c,true)}
                    return@launch
                }catch(e:CancellationException){throw e}
                catch(e:HcException){
                    diagnostic="Récupération du jeton · HTTP "+e.code+" · "+e.reason.takeIf{it.matches(Regex("[A-Za-z0-9_.-]{1,120}"))}.orEmpty()
                    when{
                        e.reason=="authorization_pending"->{message="En attente de l’acceptation dans Chrome.";continue}
                        e.reason=="slow_down"||e.code==429->{interval=maxOf(interval+5,e.retrySeconds);message="Serveur occupé : nouvelle vérification espacée.";continue}
                        e.code==503||e.code==502||e.code==504->{interval=maxOf(interval*2,e.retrySeconds);message="Service temporairement indisponible. Autorisation conservée.";continue}
                        else->{message=hcError(e);grant=null;withContext(Dispatchers.IO){runCatching{persist(id,s,null)}};return@launch}
                    }
                }catch(e:java.io.IOException){
                    interval=(interval*2).coerceAtMost(120)
                    message="Réseau temporairement indisponible : autorisation conservée."
                    diagnostic=hcExceptionKind(e)
                }catch(e:Exception){
                    message="Échec de récupération du jeton.";diagnostic=hcExceptionKind(e)
                    grant=null;withContext(Dispatchers.IO){runCatching{persist(id,s,null)}};return@launch
                }finally{exchangeInFlight=false}
            }
            grant=null;message="Code expiré. Demandez un nouveau code.";withContext(Dispatchers.IO){runCatching{persist(id,s,null)}}
        }
    }
    fun cancel(){
        if(exchangeInFlight)return
        job?.cancel();grant=null;pending=false
        message="Autorisation annulée.";diagnostic=""
        runCatching{persist(client,secret,null)}
    }
    fun forget():Boolean{
        if(exchangeInFlight||pending||grant!=null)return false
        if(!HomeConnect.forget(c))return false
        draft.clear();saved=false;client="";secret="";message="Connexion supprimée.";diagnostic=""
        return true
    }
}
internal fun hcExceptionKind(e:Exception):String=when(e){
    is java.net.SocketTimeoutException->"Délai réseau dépassé."
    is java.net.UnknownHostException->"Résolution DNS impossible."
    is javax.net.ssl.SSLException->"Erreur TLS ou certificat."
    is java.io.IOException->"Erreur d’entrée/sortie réseau."
    is org.json.JSONException->"Structure JSON inattendue."
    is java.security.GeneralSecurityException->"Erreur du coffre Android Keystore."
    is IllegalStateException->"État local inattendu."
    is IllegalArgumentException->"Paramètre ou réponse non valide."
    else->"Erreur interne ("+e.javaClass.simpleName.take(60)+")."
}
