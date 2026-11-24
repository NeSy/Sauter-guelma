package fr.nesy.guelmacontrol

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun timeText(t:Long)=if(t>0)DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(t))else "Aucun relevé"
private fun heatTemp(t:Double?)=t?.let{String.format(Locale.FRANCE,"%.1f °C",it)}?:"—"

@Composable
internal fun HouseOverview(model:DiagnosticModel,heater:Device?,openWater:()->Unit,openHoben:()->Unit){
    val c=LocalContext.current
    val live by HobenObservation.live.collectAsState()
    val status by HobenObservation.status.collectAsState()
    val scope=rememberCoroutineScope()
    var refreshing by remember{mutableStateOf(false)}
    val saved=CredentialVault(c,"hoben").hasCredentials()
    val h=heater?.takeIf{model.connected&&it.available==true}
    Text("Ma maison",style=MaterialTheme.typography.headlineLarge,fontWeight=FontWeight.Bold)
    Text("2 équipements · accès indépendant",color=MaterialTheme.colorScheme.onSurfaceVariant)
    Card(onClick=openWater,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFF143751))){
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
            Text("EAU CHAUDE",color=Color(0xFF7DDCFF),style=MaterialTheme.typography.labelLarge)
            Text("Sauter Guelma · 65 L",color=Color.White,style=MaterialTheme.typography.titleLarge)
            val reserve=h?.state("core:NumberOfShowerRemainingState")?.toIntOrNull()
            val desired=h?.state("core:ExpectedNumberOfShowerState")?.toIntOrNull()
            Text(if(reserve!=null)reserve.toString()+if(desired!=null)" / "+desired else "" else "—",color=Color.White,style=MaterialTheme.typography.displayMedium,fontWeight=FontWeight.Bold)
            Text(if(h!=null)"Douches disponibles · "+when(h.state("core:HeatingStatusState")){"on"->"Chauffe";"off"->"En veille";else->"État inconnu"}else if(model.busy)"Connexion en cours"else "Connexion ou lecture nécessaire",color=Color(0xFFD0E9FA))
            if(h!=null)Text("Sonde 1 "+heatTemp(h.state("modbuslink:MiddleWaterTemperatureState")?.toDoubleOrNull())+" · Sonde 2 "+heatTemp(h.state("core:MiddleWaterTemperatureInState")?.toDoubleOrNull()),color=Color(0xFFD0E9FA),style=MaterialTheme.typography.bodySmall)
            Text(if(model.demo)"Données de démonstration"else if(h!=null)"Relevé "+timeText(model.lastSync)else "Ouvrir pour connecter",color=Color(0xFFD0E9FA),style=MaterialTheme.typography.labelMedium)
        }
    }
    Card(onClick=openHoben,modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=Color(0xFF39291F))){
        Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(7.dp)){
            Text("CHAUFFAGE",color=Color(0xFFFFB980),style=MaterialTheme.typography.labelLarge)
            Text("Hoben H5",color=Color.White,style=MaterialTheme.typography.titleLarge)
            val s=live?.takeIf{saved&&!model.demo}
            Text(heatTemp(s?.ambient),color=Color.White,style=MaterialTheme.typography.displayMedium,fontWeight=FontWeight.Bold)
            Text(if(s!=null)"Consigne "+heatTemp(s.target)+" · "+s.stateLabel else if(saved)"En attente de lecture"else "Connecter le poêle",color=Color(0xFFF3DCCB))
            if(s!=null){
                Text("Puissance "+(s.power?.let{"$it %"}?:"—")+" · "+s.ventLabel,color=Color(0xFFF3DCCB))
                Text(if(s.activeOverride)"Dérogation active · "+heatTemp(s.overrideTemp)else if(s.scheduledOverride)"Dérogation programmée"else s.modeLabel+" · "+if(s.onOff==1)"Marche autorisée"else "Arrêt demandé",color=Color(0xFFFFC28C))
                if(s.vacationActive)Text("Vacances actives",color=Color(0xFFFFC28C))
                if(s.frostActive)Text("Protection hors gel",color=Color(0xFFFFC28C))
                if(s.openWindow||s.externalStop)Text("Chauffe suspendue (état signalé)",color=Color(0xFFFFC28C))
                if(s.faults!=0||s.warnings!=0)Text("Défaut ou avertissement signalé",color=Color(0xFFFFC28C))
                Text("Relevé "+timeText(s.timestamp),color=Color(0xFFF3DCCB),style=MaterialTheme.typography.labelMedium)
            }
            if(status.isNotBlank()&&status!="Lecture réussie."&&!model.demo)Text(status,color=Color(0xFFF3DCCB),style=MaterialTheme.typography.bodySmall)
        }
    }
    OutlinedButton(onClick={
        if(model.connected&&!model.demo)model.refresh() else if(model.remembered&&!model.demo)model.reconnect()
        if(saved&&!model.demo){refreshing=true;scope.launch{try{withContext(Dispatchers.IO){HobenObservation.collect(c.applicationContext,"Hoben · accueil")}}finally{refreshing=false}}}
    },enabled=!model.busy&&!refreshing&&!model.demo&&(saved||model.remembered||model.connected),modifier=Modifier.fillMaxWidth()){Text(if(refreshing)"Actualisation…"else "Actualiser les appareils")}
    HouseWeatherPanel()
    Text("Les cartes affichent les derniers relevés, pas un état garanti en temps réel.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
