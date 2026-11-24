package fr.nesy.guelmacontrol

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable internal fun HobenControls(s:HobenSnapshot?,enabled:Boolean,ask:(HobenOverride,String)->Unit,openConfig:()->Unit,expanded:Boolean){
    Card(Modifier.fillMaxWidth()){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
            Text("Pilotage",style=MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(onClick={ask(HobenOverride.Power(true),"Autoriser le fonctionnement normal du poêle ?")},enabled=enabled&&s?.onOff==0,modifier=Modifier.weight(1f)){Text("Marche")}
                OutlinedButton(onClick={ask(HobenOverride.Power(false),"Demander l’arrêt normal du poêle ?")},enabled=enabled&&s?.onOff==1,modifier=Modifier.weight(1f)){Text("Arrêt")}
            }
            Text("Ventilation")
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                listOf("Normal","Silence","Boost").forEachIndexed{i,label->FilterChip(selected=s?.vent==i,onClick={ask(HobenOverride.Vent(i),"Ventilation : $label ?")},enabled=enabled&&s!=null&&s.vent!=i,label={Text(label)})}
            }
            Text("Mode de fonctionnement")
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                listOf(0 to "Habitation",2 to "Manuel").forEach{(i,label)->FilterChip(selected=s?.mode==i,onClick={ask(HobenOverride.Mode(i),"Passer en mode $label ?")},enabled=enabled&&s!=null&&s.mode!=i,label={Text(label)})}
            }
            OutlinedButton(onClick=openConfig){Text(if(expanded)"Masquer les réglages avancés"else "Programmation, vacances et compteurs")}
            Text("Les nouveaux réglages sont expérimentaux sur le H5. Une relecture est nécessaire après chaque commande.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
private fun hhmm(m:Int)=String.format(java.util.Locale.FRANCE,"%02d:%02d",m/60,m%60)
@Composable internal fun HobenConfigPanel(enabled:Boolean,ask:(HobenOverride,String)->Unit,stamp:Long){
    val c=LocalContext.current.applicationContext
    val scope=rememberCoroutineScope()
    var day by remember{mutableStateOf(1)}
    var details by remember{mutableStateOf<HobenDetails?>(null)}
    var busy by remember{mutableStateOf(false)}
    var msg by remember{mutableStateOf("Lire les réglages du poêle.")}
    var slotIndex by remember{mutableStateOf<Int?>(null)}
    var manual by remember{mutableStateOf(false)}
    var vacation by remember{mutableStateOf(false)}
    LaunchedEffect(stamp){details=null}
    val ready=enabled&&!busy
    Card(Modifier.fillMaxWidth()){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp)){
            Text("Réglages natifs Hoben",style=MaterialTheme.typography.titleLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(5.dp)){
                listOf(1 to "Lun",2 to "Mar",3 to "Mer",4 to "Jeu",5 to "Ven",6 to "Sam",0 to "Dim").forEach{(i,n)->FilterChip(selected=i==day,onClick={day=i;details=null},enabled=!busy,label={Text(n)})}
            }
            OutlinedButton(onClick={val selected=day;busy=true;details=null;scope.launch{
                try{val r=withContext(Dispatchers.IO){HobenPairSession(c).use{it.open(null,true,detailDay=selected)}};details=(r as? HobenPairResult.Opened)?.details;msg=if(details!=null)"Réglages lus."else when(r){is HobenPairResult.Opened->r.readStatus;is HobenPairResult.Failed->r.message;else->"Autorisation requise."}}finally{busy=false}
            }},enabled=ready){Text("Lire la journée et les compteurs")}
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(msg,style=MaterialTheme.typography.bodySmall)
            details?.let{d->
                Text("Programme Habitation · 7 plages",style=MaterialTheme.typography.titleMedium)
                (0..6).forEach{i->
                    val slot=decodeSlot(d.slots,i)
                    Row(horizontalArrangement=Arrangement.SpaceBetween,modifier=Modifier.fillMaxWidth()){
                        Text(if(slot.active)"${hhmm(slot.start)}–${hhmm(slot.end)} · ${slot.temp/10.0}°"else "Plage ${i+1} · inactive",modifier=Modifier.weight(1f))
                        TextButton(onClick={slotIndex=i},enabled=ready){Text("Modifier")}
                    }
                }
                Text("Consigne manuelle : ${d.manualTemp} °C")
                OutlinedButton(onClick={manual=true},enabled=ready){Text("Modifier la consigne manuelle")}
                Text("Vacances : "+if(d.vacationEnabled)"activées"else "désactivées")
                Text("Retour : "+(if(d.noVacationEnd)"Sans date de fin"else d.vacationEnd?.toString()?.replace('T',' ')?:"Non défini")+" · ${d.vacationTemp} °C")
                OutlinedButton(onClick={vacation=true},enabled=ready){Text("Configurer les vacances")}
                OutlinedButton(onClick={ask(HobenOverride.Vacation(!d.vacationEnabled),if(d.vacationEnabled)"Désactiver le mode vacances ?"else "Activer les vacances selon la configuration enregistrée ?")},enabled=ready){Text(if(d.vacationEnabled)"Terminer les vacances"else "Activer les vacances")}
                Text("Compteurs du poêle",style=MaterialTheme.typography.titleMedium)
                d.commissioning?.let{Text("Mise en service : "+it.format(DateTimeFormatter.ofPattern("dd/MM/uuuu")))}
                d.maintenance?.let{Text("Dernière maintenance : "+it.format(DateTimeFormatter.ofPattern("dd/MM/uuuu")))}
                Text("Les compteurs sont lus directement dans le poêle, sans réinitialisation.",style=MaterialTheme.typography.bodySmall)
                if(d.counters.size>=5){listOf("0–40 %","41–60 %","61–80 %","81–100 %").forEachIndexed{i,n->Text("$n : ${d.counters[i]} h")};Text("Allumages : ${d.counters[4]}")}
            }
        }
    }
    val d=details
    slotIndex?.let{i->if(d!=null){
        val slot=decodeSlot(d.slots,i)
        var active by remember(i,d){mutableStateOf(slot.active)}
        var start by remember(i,d){mutableStateOf(hhmm(slot.start))}
        var end by remember(i,d){mutableStateOf(hhmm(slot.end))}
        var temp by remember(i,d){mutableStateOf((slot.temp/5f).roundToInt().coerceIn(30,50))}
        var silence by remember(i,d){mutableStateOf(slot.silence)}
        fun mins(t:String)=t.split(":").takeIf{it.size==2}?.let{p->val h=p[0].toIntOrNull();val m=p[1].toIntOrNull();if(h!=null&&m!=null&&h in 0..24&&m in 0..59&&(h<24||m==0))h*60+m else null}
        val a=mins(start);val b=mins(end)
        AlertDialog(onDismissRequest={slotIndex=null},title={Text("Plage ${i+1}")},text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)){
            Row{Checkbox(active,{active=it});Text("Active")}
            OutlinedTextField(start,{start=it.take(5)},label={Text("Début HH:mm")},singleLine=true)
            OutlinedTextField(end,{end=it.take(5)},label={Text("Fin HH:mm")},singleLine=true)
            Text("${temp/2.0} °C");Slider(temp.toFloat(),{temp=it.roundToInt()},valueRange=30f..50f,steps=19)
            Row{Checkbox(silence,{silence=it});Text("Silence")}
            Text("Puissance maximale conservée : ${slot.maxPower} %")
        }},confirmButton={Button(onClick={if(a!=null&&b!=null){val after=changeSlot(d.slots,i,HobenSlot(active,a,b,silence,temp*5,slot.maxPower));slotIndex=null;ask(HobenOverride.Schedule(d.day,d.slots,after),"Modifier uniquement la plage ${i+1} de la journée choisie ?")}},enabled=ready&&a!=null&&b!=null&&a<b&&a<1440&&b>0&&slot.maxPower in 0..100){Text("Continuer")}},dismissButton={TextButton(onClick={slotIndex=null}){Text("Annuler")}})
    }}
    if(manual&&d!=null){
        var t by remember{mutableStateOf((d.manualTemp*2).roundToInt().coerceIn(30,50))}
        AlertDialog(onDismissRequest={manual=false},title={Text("Consigne manuelle")},text={Column{Text("${t/2.0} °C");Slider(t.toFloat(),{t=it.roundToInt()},valueRange=30f..50f,steps=19)}},confirmButton={Button(onClick={manual=false;ask(HobenOverride.Manual(t),"Enregistrer la consigne manuelle à ${t/2.0} °C ?")},enabled=ready){Text("Continuer")}},dismissButton={TextButton(onClick={manual=false}){Text("Annuler")}})
    }
    if(vacation&&d!=null){
        val fmt=DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
        var date by remember{mutableStateOf(LocalDateTime.now().plusDays(1).format(fmt))}
        var t by remember{mutableStateOf((d.vacationTemp*2).roundToInt().coerceIn(30,50))}
        val end=runCatching{LocalDateTime.parse(date,fmt)}.getOrNull()
        AlertDialog(onDismissRequest={vacation=false},title={Text("Configuration vacances")},text={Column{OutlinedTextField(date,{date=it.take(16)},label={Text("Retour AAAA-MM-JJ HH:mm")});Text("${t/2.0} °C");Slider(t.toFloat(),{t=it.roundToInt()},valueRange=30f..50f,steps=19);Text("L’activation est une action séparée.")}},confirmButton={Button(onClick={if(end!=null){vacation=false;ask(HobenOverride.VacationConfig(end,t),"Enregistrer les vacances jusqu’au ${end.format(fmt)} ?")}},enabled=ready&&end!=null&&end.isAfter(LocalDateTime.now())&&end.isBefore(LocalDateTime.now().plusYears(1))){Text("Continuer")}},dismissButton={TextButton(onClick={vacation=false}){Text("Annuler")}})
    }
}
