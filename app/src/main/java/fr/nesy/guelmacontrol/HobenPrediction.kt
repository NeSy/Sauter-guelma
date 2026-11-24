package fr.nesy.guelmacontrol

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ceil

internal data class HobenThermalModel(val coveredHours:Double,val goodDays:Int,val train:Int,val validation:Int,val rate:Double?,val mae:Double?,val baseline:Double?,val usable:Boolean)
private data class ThermalPair(val start:Long,val end:Long,val rise:Double,val hours:Double)
internal fun hobenThermalModel(rows:List<HobenMeasure>,now:Long,zone:ZoneId=ZoneId.systemDefault()):HobenThermalModel{
    val cutoff=now-30L*86400000
    val split=now-7L*86400000
    val sorted=rows.filter{it.time in cutoff..now}.sortedBy{it.time}
    val coverage=mutableMapOf<java.time.LocalDate,Double>()
    val pairs=mutableListOf<ThermalPair>()
    for((a,b) in sorted.zipWithNext()){
        val gap=b.time-a.time
        if(gap !in 120000L..2700000L||a.ambient==null||b.ambient==null||a.power==null||b.power==null)continue
        var cursor=a.time
        while(cursor<b.time){
            val day=Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
            val midnight=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val stop=minOf(b.time,midnight)
            coverage[day]=(coverage[day]?:0.0)+(stop-cursor)/3600000.0
            cursor=stop
        }
        if(a.power>0&&b.power>0&&!a.overrideActive&&!b.overrideActive&&a.target!=null&&a.target==b.target&&a.target>a.ambient&&b.ambient<=a.target+0.3){
            val rise=b.ambient-a.ambient
            if(abs(rise)<=3.0)pairs+=ThermalPair(a.time,b.time,rise,gap/3600000.0)
        }
    }
    val hours=coverage.values.sum()
    val days=coverage.count{it.value>=12}
    val train=pairs.filter{it.end<split}
    val validation=pairs.filter{it.start>=split}
    val rates=train.map{it.rise/it.hours}.sorted()
    val rate=if(rates.size>=12)rates[rates.size/2].takeIf{it in 0.1..5.0}else null
    val mae=if(rate!=null&&validation.size>=8)validation.map{abs(it.rise-rate*it.hours)}.average()else null
    val baseline=if(validation.size>=8)validation.map{abs(it.rise)}.average()else null
    val usable=hours>=432&&days>=14&&rate!=null&&mae!=null&&baseline!=null&&mae<baseline&&mae<=0.35
    return HobenThermalModel(hours,days,train.size,validation.size,rate,mae,baseline,usable)
}
internal fun hobenWarmupMinutes(model:HobenThermalModel,ambient:Double,target:Double):Int?{
    val rate=model.rate?:return null
    val diff=target-ambient
    if(!model.usable||diff !in 0.1..2.0)return null
    val minutes=ceil(diff/rate*60/15).toInt()*15
    return minutes.takeIf{it in 15..180}
}
internal fun hobenCsv(rows:List<HobenMeasure>):String=buildString{
    appendLine("timestamp_iso,ambient_c,target_c,power_percent,state,override_active,outdoor_model_c")
    rows.forEach{r->appendLine(listOf(Instant.ofEpochMilli(r.time).toString(),r.ambient?.toString().orEmpty(),r.target?.toString().orEmpty(),r.power?.toString().orEmpty(),r.state.toString(),if(r.overrideActive)"1"else"0",r.outdoor?.toString().orEmpty()).joinToString(","))}
}
@Composable
internal fun HobenPredictionPanel(saved:Boolean,stamp:Long,reload:Int){
    val c=LocalContext.current.applicationContext
    var expanded by remember{mutableStateOf(false)}
    var model by remember{mutableStateOf<HobenThermalModel?>(null)}
    var goal by remember{mutableStateOf(22f)}
    val current by HobenObservation.live.collectAsState()
    LaunchedEffect(expanded,saved,stamp,reload){
        if(expanded&&saved)model=withContext(Dispatchers.IO){runCatching{hobenThermalModel(HobenObservation.rows(c,30),System.currentTimeMillis())}.getOrNull()}
        else model=null
    }
    OutlinedButton(onClick={expanded=!expanded}){Text(if(expanded)"Masquer les prévisions"else "Prévisions de chauffe")}
    if(expanded){
        Text("Apprentissage thermique · 30 jours",style=MaterialTheme.typography.titleMedium)
        val m=model
        if(m==null)Text("En attente de relevés exploitables.")
        else{
            Text("Couverture : ${m.coveredHours.toInt()} h / 720 h · ${m.goodDays} jours bien couverts")
            LinearProgressIndicator(progress={ (m.coveredHours/432.0).toFloat().coerceIn(0f,1f) },modifier=Modifier.fillMaxWidth())
            Text("Apprentissage : ${m.train} intervalles · validation : ${m.validation}")
            if(m.usable)Text("Modèle validé sur les 7 derniers jours.")else Text("Prévisions verrouillées : couverture ou validation insuffisante.")
            m.mae?.let{Text(String.format(java.util.Locale.FRANCE,"Erreur moyenne de validation : %.2f °C",it))}
            m.rate?.takeIf{m.usable}?.let{Text(String.format(java.util.Locale.FRANCE,"Montée observée : %.2f °C/h",it))}
            val ambient=current?.takeIf{System.currentTimeMillis()-it.timestamp in 0..300000}?.ambient
            Text("Objectif : ${goal} °C")
            Slider(goal,{goal=it},valueRange=18f..25f,steps=13)
            val minutes=ambient?.let{hobenWarmupMinutes(m,it,goal.toDouble())}
            Text(if(minutes!=null)"Ordre de grandeur pour atteindre l’objectif : ${minutes} min."else "Estimation indisponible pour cette situation.")
            Text("Sous conditions similaires aux relevés en chauffe. Pas de prise en compte météo ; pas de garantie ni de commande automatique.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
