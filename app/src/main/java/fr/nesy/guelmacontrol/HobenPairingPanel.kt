package fr.nesy.guelmacontrol

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun HobenPairingPanel(){
    val c=LocalContext.current.applicationContext
    val session=remember{HobenPairSession(c)}
    val scope=rememberCoroutineScope()
    var id by remember{mutableStateOf("")}
    var pin by remember{mutableStateOf("")}
    var rememberMe by remember{mutableStateOf(true)}
    var saved by remember{mutableStateOf(session.hasSaved())}
    var busy by remember{mutableStateOf(false)}
    var challenge by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("Poêle non identifié")}
    fun handle(result:HobenPairResult){
        challenge=result==HobenPairResult.PinRequired
        status=when(result){
            HobenPairResult.PinRequired->"Vérifiez l’écran du H5 : saisissez le code d’autorisation affiché."
            is HobenPairResult.Opened->"Identification confirmée · type "+result.identity.type+" · révision "+result.identity.revision+" · logiciel "+result.identity.major+"."+result.identity.minor
            is HobenPairResult.Failed->result.message
        }
        if(result is HobenPairResult.Opened)id=""
        saved=session.hasSaved()
    }
    DisposableEffect(session){onDispose{session.close()}}
    LaunchedEffect(challenge){
        if(challenge){delay(55000);if(!busy){session.close();challenge=false;pin="";status="Demande expirée. Relancez la connexion."}}
    }
    Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(20.dp)){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
            Text("Connexion au H5",style=MaterialTheme.typography.titleLarge)
            Text("Appairage expérimental via le serveur MyHOBEN. Aucun ordre de chauffe.")
            if(!challenge){
                if(saved)Button(onClick={busy=true;scope.launch{try{handle(withContext(Dispatchers.IO){session.open(null,true)})}finally{busy=false}}},enabled=!busy,modifier=Modifier.fillMaxWidth()){Text("Reprendre l’appairage mémorisé")}
                OutlinedTextField(value=id,onValueChange={id=it.take(40)},label={Text("Identifiant MyHOBEN")},singleLine=true,visualTransformation=PasswordVisualTransformation(),enabled=!busy,modifier=Modifier.fillMaxWidth())
                Row(verticalAlignment=Alignment.CenterVertically){Checkbox(rememberMe,{rememberMe=it},enabled=!busy);Text("Mémoriser sur ce téléphone")}
                Button(onClick={busy=true;val entered=id;scope.launch{try{handle(withContext(Dispatchers.IO){session.open(entered,rememberMe)})}finally{busy=false}}},enabled=!busy&&normalizeHobenId(id)!=null,modifier=Modifier.fillMaxWidth()){Text("Identifier mon poêle")}
                if(saved)TextButton(onClick={session.forget();saved=false;status="Identifiant local effacé."},enabled=!busy){Text("Oublier l’appairage local")}
            }else{
                OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(5)},label={Text("Code affiché sur le poêle")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),visualTransformation=PasswordVisualTransformation(),enabled=!busy,modifier=Modifier.fillMaxWidth())
                Button(onClick={val entered=pin.toIntOrNull()?:return@Button;pin="";busy=true;scope.launch{try{handle(withContext(Dispatchers.IO){session.authenticate(entered,rememberMe)})}finally{busy=false;challenge=false}}},enabled=!busy&&pin.toIntOrNull()?.let{it in 0..65534}==true,modifier=Modifier.fillMaxWidth()){Text("Valider le code")}
                TextButton(onClick={session.close();challenge=false;pin="";status="Appairage annulé."},enabled=!busy){Text("Annuler")}
            }
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(status,style=MaterialTheme.typography.bodyMedium)
            Text("Ne partagez ni l’identifiant ni le code. L’appairage est enregistré uniquement après une réponse positive du serveur.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
