package fr.nesy.guelmacontrol

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun HcAppliance.summary():String{
    val state=value("BSH.Common.Status.OperationState")?.let(::hcEnum)
    val power=value("BSH.Common.Setting.PowerState")?.let(::hcEnum)
    return if(!connected)"Hors ligne"else listOfNotNull(state,power).distinct().joinToString(" · ").ifBlank{"Connecté"}
}
private fun HcAppliance.metrics():List<String>{
    fun metric(k:String,label:String):String?=field(k)?.let{"$label : "+if(it.unit.isNotBlank())it.value+" "+it.unit else hcEnum(it.value)}
    return listOfNotNull(
        metric("Cooking.Oven.Status.CurrentCavityTemperature","Température relevée"),
        metric("Cooking.Oven.Option.SetpointTemperature","Consigne"),
        value("BSH.Common.Option.RemainingProgramTime")?.let(::hcMinutes)?.let{"Temps restant : $it"},
        metric("Cooking.Common.Option.Hood.VentingLevel","Ventilation"),
        metric("Cooking.Common.Option.Hood.IntensiveLevel","Intensif"),
        metric("BSH.Common.Status.DoorState","Porte")
    )
}
@Composable internal fun HomeConnectOverview(){
    val c=LocalContext.current
    val list by HomeConnect.appliances.collectAsState()
    val status by HomeConnect.status.collectAsState()
    val busy by HomeConnect.busy.collectAsState()
    val scope=rememberCoroutineScope()
    val saved=HomeConnect.saved(c)
    LaunchedEffect(saved){if(saved)withContext(Dispatchers.IO){HomeConnect.refresh(c.applicationContext)}}
    Text("Cuisine · Home Connect",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
    for(kind in listOf("Four","Plaques","Hotte")){
        val found=list.filter{it.kind==kind}
        if(found.isEmpty())HcCard(null,kind){c.startActivity(Intent(c,HomeConnectActivity::class.java))}
        else found.forEach{a->HcCard(a,kind){c.startActivity(Intent(c,HomeConnectActivity::class.java).putExtra("appliance",a.id))}}
    }
    if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    if(status.isNotBlank())Text(status,style=MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
        OutlinedButton(onClick={c.startActivity(Intent(c,HomeConnectActivity::class.java))}){Text(if(saved)"Compte Home Connect"else "Connecter Siemens")}
        if(saved)TextButton(onClick={scope.launch{withContext(Dispatchers.IO){HomeConnect.refresh(c.applicationContext,true)}}},enabled=!busy){Text("Actualiser")}
    }
}
@Composable private fun HcCard(a:HcAppliance?,kind:String,onClick:()->Unit){
    val color=when(kind){"Four"->Color(0xFF4A322B);"Plaques"->Color(0xFF303444);else->Color(0xFF22433D)}
    Card(onClick=onClick,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=color)){
        Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
            Text(kind.uppercase(java.util.Locale.FRANCE),color=Color(0xFFD2E5DE),style=MaterialTheme.typography.labelLarge)
            Text(a?.let{listOf(it.brand,it.name).filter{v->v.isNotBlank()}.distinct().joinToString(" · ")}?:"Siemens · $kind",color=Color.White,style=MaterialTheme.typography.titleLarge)
            Text(a?.summary()?:"Non identifié",color=Color.White,style=MaterialTheme.typography.headlineSmall)
            if(a!=null){
                a.metrics().forEach{Text(it,color=Color(0xFFE3EAE7),style=MaterialTheme.typography.bodyMedium)}
                a.activeProgram?.let{Text("Programme : "+hcEnum(it),color=Color(0xFFE3EAE7))}
                a.error?.let{Text(it,color=Color(0xFFFFC28C))}
                Text("Relevé : "+java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(a.timestamp)),color=Color(0xFFE3EAE7),style=MaterialTheme.typography.labelSmall)
            }else Text("Ouvrir pour connecter Home Connect",color=Color(0xFFE3EAE7))
        }
    }
}
class HomeConnectActivity:ComponentActivity(){
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val selected=intent.getStringExtra("appliance")
        setContent{GuelmaTheme{HomeConnectView(selected){finish()}}}
    }
}
@Composable private fun HomeConnectView(initial:String?,back:()->Unit){
    val c=LocalContext.current
    val app=c.applicationContext
    val scope=rememberCoroutineScope()
    val clip=LocalClipboardManager.current
    val list by HomeConnect.appliances.collectAsState()
    val status by HomeConnect.status.collectAsState()
    val reading by HomeConnect.busy.collectAsState()
    var saved by remember{mutableStateOf(HomeConnect.saved(app))}
    var client by remember{mutableStateOf("")}
    var secret by remember{mutableStateOf("")}
    var grant by remember{mutableStateOf<HcDeviceGrant?>(null)}
    var pending by remember{mutableStateOf(false)}
    var message by remember{mutableStateOf("")}
    var selected by remember{mutableStateOf(initial)}
    var account by remember{mutableStateOf(!saved)}
    var confirmForget by remember{mutableStateOf(false)}
    LaunchedEffect(saved){if(saved)withContext(Dispatchers.IO){HomeConnect.refresh(app)}}
    LaunchedEffect(grant){
        val g=grant?:return@LaunchedEffect
        val id=client;val s=secret
        var interval=g.interval
        message="Autorisez l’accès dans le navigateur."
        while(android.os.SystemClock.elapsedRealtime()<g.expiresAt){
            delay(interval*1000L)
            if(android.os.SystemClock.elapsedRealtime()>=g.expiresAt)break
            try{
                withContext(Dispatchers.IO){HomeConnect.poll(app,g,id,s)}
                saved=true;client="";secret="";grant=null;account=false;message="Autorisation réussie."
                withContext(Dispatchers.IO){HomeConnect.refresh(app,true)}
                return@LaunchedEffect
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(e:HcException){
                if(e.reason=="authorization_pending")continue
                if(e.reason=="slow_down"||e.code==429){interval=maxOf(interval+5,e.retrySeconds.toInt().coerceAtMost(120));continue}
                message=hcError(e);grant=null;return@LaunchedEffect
            }catch(_:Exception){message="Connexion interrompue. Recommencez l’autorisation.";grant=null;return@LaunchedEffect}
        }
        message="Code expiré. Recommencez l’autorisation.";grant=null
    }
    Scaffold{padding->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            TextButton(onClick=back){Text("‹  Ma maison")}
            Text("Cuisine Siemens",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
            Text("Home Connect · API officielle · lecture seule")
            if(saved){
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    Button(onClick={scope.launch{withContext(Dispatchers.IO){HomeConnect.refresh(app,true)}}},enabled=!reading){Text("Actualiser")}
                    OutlinedButton(onClick={account=!account}){Text("Compte")}
                }
                if(reading)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(status.isNotBlank())Text(status)
                val chosen=list.firstOrNull{it.id==selected}
                if(chosen!=null){
                    HcCard(chosen,chosen.kind){}
                    TextButton(onClick={selected=null}){Text("Tous les appareils de cuisine")}
                    HcDetails(chosen)
                }else list.forEach{a->HcCard(a,a.kind){selected=a.id}}
            }
            if(account||!saved){
                Card(Modifier.fillMaxWidth()){
                    Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                        Text("Autorisation Home Connect",style=MaterialTheme.typography.titleLarge)
                        Text("Enregistrez une application personnelle dans le portail développeur. Choisissez OAuth Device Flow.")
                        TextButton(onClick={c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://developer.home-connect.com/")))}){Text("Ouvrir le portail développeur")}
                        if(grant==null){
                            OutlinedTextField(client,{client=it.trim().take(64)},label={Text("Client ID")},singleLine=true,enabled=!pending,modifier=Modifier.fillMaxWidth())
                            OutlinedTextField(secret,{secret=it.trim().take(256)},label={Text("Client Secret (si fourni)")},singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!pending,modifier=Modifier.fillMaxWidth())
                            Button(onClick={pending=true;val id=client;scope.launch{try{grant=withContext(Dispatchers.IO){HomeConnect.begin(id)}}catch(e:HcException){message=hcError(e)}catch(_:Exception){message="Demande d’autorisation impossible."}finally{pending=false}}},enabled=!pending&&hcClientValid(client)){Text("Connecter Home Connect")}
                        }else{
                            val g=grant!!
                            Text("Code à saisir dans le navigateur",style=MaterialTheme.typography.titleMedium)
                            Text(g.userCode,style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
                            Button(onClick={clip.setText(AnnotatedString(g.userCode));c.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(g.uri)))}){Text("Copier le code et autoriser")}
                            Text("Revenez ici après avoir accepté les permissions.")
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            TextButton(onClick={grant=null;message="Autorisation annulée."}){Text("Annuler")}
                        }
                        if(pending)LinearProgressIndicator(Modifier.fillMaxWidth())
                        if(message.isNotBlank())Text(message)
                        if(saved)TextButton(onClick={confirmForget=true},enabled=!pending&&grant==null&&!reading){Text("Supprimer la connexion locale")}
                        Text("Les jetons et identifiants client sont chiffrés sur ce téléphone. Le mot de passe Home Connect est saisi uniquement dans le navigateur officiel.",style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Le pilotage des appareils sera ajouté après validation de leurs capacités réelles. Aucun ordre de cuisson n’est envoyé.",style=MaterialTheme.typography.bodySmall)
        }
    }
    if(confirmForget)AlertDialog(onDismissRequest={confirmForget=false},title={Text("Déconnecter Home Connect ?")},text={Text("Supprime les jetons locaux. Les appareils restent associés à Home Connect.")},confirmButton={TextButton(onClick={confirmForget=false;if(HomeConnect.forget(app)){saved=false;account=true;selected=null}}){Text("Déconnecter")}},dismissButton={TextButton(onClick={confirmForget=false}){Text("Annuler")}})
}
@Composable private fun HcDetails(a:HcAppliance){
    Card(Modifier.fillMaxWidth()){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
            Text("Informations réelles",style=MaterialTheme.typography.titleLarge)
            if(a.model.isNotBlank())Text("Modèle : "+a.model)
            val rows=a.status+a.settings+a.options
            if(rows.isEmpty())Text("Aucune donnée détaillée disponible.")
            rows.forEach{f->
                Text(f.label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if(f.unit.isNotBlank())f.value+" "+f.unit else hcEnum(f.value))
            }
            Text("Autorisation distante : "+(a.value("BSH.Common.Status.RemoteControlActive")?.let(::hcEnum)?:"Non remontée"))
            Text("Démarrage distant : "+(a.value("BSH.Common.Status.RemoteControlStartAllowed")?.let(::hcEnum)?:"Non remonté"))
        }
    }
}
