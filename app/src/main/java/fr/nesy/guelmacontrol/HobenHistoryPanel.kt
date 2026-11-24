package fr.nesy.guelmacontrol

import androidx.compose.foundation.Canvas
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

internal data class HobenObservedStats(val coveredMinutes:Double,val poweredMinutes:Double,val starts:Int,val cooling:Double?)
internal fun hobenStats(rows:List<HobenMeasure>):HobenObservedStats{
    var total=0.0;var heated=0.0;var starts=0
    val slopes=mutableListOf<Double>()
    for((a,b) in rows.sortedBy{it.time}.zipWithNext()){
        val gap=(b.time-a.time)/60000.0
        if(gap !in 2.0..45.0||a.power==null||b.power==null)continue
        total+=gap
        if(a.power>0&&b.power>0)heated+=gap
        if(a.power==0&&b.power>0)starts++
        if(a.power==0&&b.power==0&&!a.overrideActive&&!b.overrideActive&&a.target!=null&&a.target==b.target&&a.ambient!=null&&b.ambient!=null&&a.ambient>b.ambient){
            slopes.add((a.ambient-b.ambient)*60/gap)
        }
    }
    val sorted=slopes.sorted()
    return HobenObservedStats(total,heated,starts,if(sorted.size>=8)sorted[sorted.size/2]else null)
}
@Composable
internal fun HobenHistoryPanel(enabled:Boolean,setEnabled:(Boolean)->Unit,auto:Boolean,setAuto:(Boolean)->Unit,saved:Boolean,stamp:Long){
    val c=LocalContext.current.applicationContext
    val scope=rememberCoroutineScope()
    val clipboard=LocalClipboardManager.current
    var expanded by remember{mutableStateOf(false)}
    var days by remember{mutableStateOf(7)}
    var rows by remember{mutableStateOf(emptyList<HobenMeasure>())}
    var log by remember{mutableStateOf(emptyList<String>())}
    var confirm by remember{mutableStateOf(false)}
    var reload by remember{mutableStateOf(0)}
    var exportStatus by remember{mutableStateOf("")}
    var exportRows by remember{mutableStateOf<List<HobenMeasure>>(emptyList())}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->
        if(uri!=null){val data=exportRows;scope.launch{exportStatus=withContext(Dispatchers.IO){try{c.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use{it.write(hobenCsv(data))}?:error("No stream");"Export terminé."}catch(_:Exception){"Export impossible."}}}};exportRows=emptyList()
    }
    LaunchedEffect(expanded,days,stamp,reload,saved){
        if(expanded)while(true){
            rows=withContext(Dispatchers.IO){runCatching{HobenObservation.rows(c,days)}.getOrDefault(emptyList())}
            log=withContext(Dispatchers.IO){CollectionLog.lines(c).filter{it.contains("Hoben ·")}}
            delay(15000)
        }
    }
    Card(Modifier.fillMaxWidth()){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("Observation et historique",style=MaterialTheme.typography.titleLarge)
            Row(verticalAlignment=Alignment.CenterVertically){Text("Actualisation à l’écran · 2 min",Modifier.weight(1f));Switch(checked=auto,onCheckedChange=setAuto)}
            Row(verticalAlignment=Alignment.CenterVertically){Text("Observation en arrière-plan",Modifier.weight(1f));Switch(checked=enabled,onCheckedChange=setEnabled,enabled=saved)}
            Text("Collecte facultative toutes les 30 min environ, selon Android. Données locales, 90 jours. Aucun ordre automatique.",style=MaterialTheme.typography.bodySmall)
            HobenPredictionPanel(saved,stamp,reload)
            OutlinedButton(onClick={expanded=!expanded}){Text(if(expanded)"Masquer l’historique"else "Afficher l’historique")}
            if(expanded){
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){listOf(1,7,30,90).forEach{d->FilterChip(selected=days==d,onClick={days=d},label={Text("${d}j")})}}
                Text("${rows.size} relevés")
                if(rows.size>=2){
                    val color=MaterialTheme.colorScheme.primary
                    val targetColor=Color(0xFFE58A43)
                    Text("Température ambiante (bleu) · consigne (orange)")
                    Canvas(Modifier.fillMaxWidth().height(140.dp)){
                        val start=rows.first().time;val end=rows.last().time
                        val all=rows.flatMap{listOfNotNull(it.ambient,it.target)}
                        val low=(all.minOrNull()?:0.0)-0.5;val high=(all.maxOrNull()?:1.0)+0.5
                        fun point(t:Long,v:Double)=Offset(((t-start).toDouble()/(end-start).coerceAtLeast(1)*size.width).toFloat(),(size.height*(1-(v-low)/(high-low))).toFloat())
                        for((a,b) in rows.zipWithNext()){
                            if(b.time-a.time>2700000L)continue
                            if(a.ambient!=null&&b.ambient!=null)drawLine(color,point(a.time,a.ambient),point(b.time,b.ambient),strokeWidth=3f)
                            if(a.target!=null&&b.target!=null)drawLine(targetColor,point(a.time,a.target),point(b.time,b.target),strokeWidth=2f)
                        }
                    }
                    val stats=hobenStats(rows)
                    Text("Transitions observées vers une puissance positive : ${stats.starts}")
                    Text("Intervalles avec puissance positive aux deux extrémités : ${stats.poweredMinutes.toInt()} min sur ${stats.coveredMinutes.toInt()} min exploitables")
                    Text(stats.cooling?.let{String.format(java.util.Locale.FRANCE,"Refroidissement observé médian : %.2f °C/h",it)}?:"Refroidissement : données encore insuffisantes")
                    Text("Estimations ponctuelles, pas de durée de combustion ni de consommation de granulés mesurée.",style=MaterialTheme.typography.bodySmall)
                }else Text("Activez l’observation pour construire l’historique.")
                OutlinedButton(onClick={exportRows=rows.toList();export.launch("hoben-${days}j.csv")},enabled=rows.isNotEmpty()){Text("Exporter les relevés CSV")}
                if(exportStatus.isNotBlank())Text(exportStatus)
                Text("Journal Hoben · ${log.size} événements",style=MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick={clipboard.setText(AnnotatedString(log.joinToString("\n")))},enabled=log.isNotEmpty()){Text("Copier le journal")}
                log.take(10).forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
                TextButton(onClick={confirm=true}){Text("Effacer l’historique Hoben")}
            }
        }
    }
    if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Effacer les relevés Hoben ?")},text={Text("L’observation Hoben sera arrêtée. Le ballon et les favoris ne sont pas modifiés.")},confirmButton={TextButton(onClick={setEnabled(false);confirm=false;scope.launch{withContext(Dispatchers.IO){HobenHistoryStore(c).use{it.clear()}};reload++}}){Text("Effacer")}},dismissButton={TextButton(onClick={confirm=false}){Text("Annuler")}})
}
