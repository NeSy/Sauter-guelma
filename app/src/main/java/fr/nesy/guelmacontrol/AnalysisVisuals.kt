package fr.nesy.guelmacontrol

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun TankVisual(remaining:Int?, maximum:Int?, heating:Boolean) {
    val fraction=if(remaining!=null && maximum!=null && maximum>0) (remaining.toFloat()/maximum).coerceIn(0f,1f) else 0f
    val level by animateFloatAsState(fraction,tween(800),label="réserve")
    val primary=MaterialTheme.colorScheme.primary; val outline=MaterialTheme.colorScheme.outline
    val background=MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.fillMaxWidth().height(180.dp),contentAlignment=Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w=size.width*0.21f; val h=size.height*0.93f; val top=(size.height-h)/2
            for(i in 0..1) {
                val x=size.width/2-w+(i*w)
                drawRoundRect(background,Offset(x,top),Size(w-3,h),CornerRadius(w*.32f))
                val fillH=(h-12)*level
                if(fillH>0) drawRoundRect(primary.copy(alpha=if(heating) .87f else .68f),Offset(x+5,top+h-6-fillH),Size(w-13,fillH),CornerRadius(w*.20f))
                drawRoundRect(outline,Offset(x,top),Size(w-3,h),CornerRadius(w*.32f),style=Stroke(2f))
            }
        }
        Surface(shape=RoundedCornerShape(22.dp), color=MaterialTheme.colorScheme.surface) {
            Text(remaining?.toString()?:"—",Modifier.padding(horizontal=20.dp,vertical=8.dp),style=MaterialTheme.typography.displayLarge)
        }
    }
    Text("Réserve globale estimée · illustration, pas un niveau d’eau par cuve",style=MaterialTheme.typography.bodySmall)
}
internal data class Tariffs(val unitConfirmed:Boolean,val dual:Boolean,val base:Double?,val t1:Double?,val t2:Double?)
internal fun tariffs(c:Context):Tariffs {
    val p=c.getSharedPreferences("tariffs",Context.MODE_PRIVATE)
    fun rate(key:String)=p.getString(key,"")?.toDoubleOrNull()?.takeIf{it.isFinite() && it in 0.0..5.0}
    return Tariffs(p.getBoolean("wh",false),p.getBoolean("dual",false),rate("base"),rate("t1"),rate("t2"))
}
@Composable
internal fun TariffSettingsPanel() {
    val c=LocalContext.current
    val p=remember{c.getSharedPreferences("tariffs",Context.MODE_PRIVATE)}
    var wh by remember{mutableStateOf(p.getBoolean("wh",false))}
    var dual by remember{mutableStateOf(p.getBoolean("dual",false))}
    var base by remember{mutableStateOf(p.getString("base","")?:"")}
    var t1 by remember{mutableStateOf(p.getString("t1","")?:"")}
    var t2 by remember{mutableStateOf(p.getString("t2","")?:"")}
    var saved by remember{mutableStateOf(false)}
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Tarifs électriques",style=MaterialTheme.typography.titleLarge)
            Text("Vérifiez l’unité du compteur avant de convertir les index bruts.")
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(wh,{wh=it;saved=false});Text("Je confirme que les index sont en Wh")}
            Row(verticalAlignment=Alignment.CenterVertically){Checkbox(dual,{dual=it;saved=false});Text("Deux tarifs (index 1 et 2)")}
            if(!dual) OutlinedTextField(base,{base=it;saved=false},label={Text("Prix TTC €/kWh")},singleLine=true)
            else {
                OutlinedTextField(t1,{t1=it;saved=false},label={Text("Prix index 1 €/kWh")},singleLine=true)
                OutlinedTextField(t2,{t2=it;saved=false},label={Text("Prix index 2 €/kWh")},singleLine=true)
                Text("Vérifiez la correspondance HP/HC.")
            }
            fun valid(v:String)=v.replace(',','.').toDoubleOrNull()?.let{it.isFinite()&&it in 0.0..5.0}==true
            Button(enabled=if(dual) valid(t1)&&valid(t2) else valid(base),onClick={
                p.edit().putBoolean("wh",wh).putBoolean("dual",dual).putString("base",base.replace(',','.')).putString("t1",t1.replace(',','.')).putString("t2",t2.replace(',','.')).apply();saved=true
            }){Text(if(saved)"Enregistré" else "Enregistrer")}
        }
    }
}
@Composable
internal fun VacationControls(heater:Device, enabled:Boolean, confirm:(Any,String)->Unit) {
    var days by remember{mutableStateOf(7)}
    val absent=heater.state("modbuslink:DHWAbsenceModeState")
    Text("Mode absence",style=MaterialTheme.typography.titleMedium)
    if(absent=="on"||absent=="prog"){
        OutlinedButton(onClick={confirm("off","Terminer le mode absence")},enabled=enabled&&validCommand(heater,"setAbsenceMode","off")){Text("Terminer l’absence")}
    } else {
        Text("Absence pendant "+days+" jours · retour prévu "+java.time.LocalDate.now().plusDays(days.toLong()))
        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton(onClick={days--},enabled=days>1&&enabled){Text("−")}
            OutlinedButton(onClick={days++},enabled=days<90&&enabled){Text("+")}
            Button(onClick={confirm(days,"Absence "+days+" jours ; synchroniser l’horloge et programmer les dates")},enabled=enabled&&validCommand(heater,"setAbsenceMode",days)){Text("Programmer")}
        }
        Text("Expérimental : vérifier les dates et le retour dans Cozytouch. Protection sanitaire inchangée.",style=MaterialTheme.typography.bodySmall)
    }
}
@Composable
internal fun AdvancedAnalysis(rows:List<Sample>,key:String) {
    val c=LocalContext.current.applicationContext
    val scope=rememberCoroutineScope()
    var exportStatus by remember{mutableStateOf("")}
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri->
        if(uri!=null) scope.launch {
            exportStatus=withContext(Dispatchers.IO) {
                try { val all=HistoryStore(c).use{it.recent(key,90)}
                    c.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use{it.write(csv(all))} ?: error("flux")
                    "Export enregistré ("+all.size+" mesures)."
                } catch(e:Exception) {"Échec de l’export."}
            }
        }
    }
    val model=remember(rows){thermalModel(rows)}
    var weekend by remember { mutableStateOf(false) }
    val forecast=remember(rows,weekend){demandForecast(rows,weekend)}
    val map=remember(rows){weeklyMap(rows)}
    val energy=remember(rows){energyIntervals(rows)}
    val tariff=tariffs(c)
    var shift by remember{mutableStateOf(0.25f)}
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Courbes et cycles",style=MaterialTheme.typography.titleLarge)
            if(rows.size<2) Text("Les graphiques se construiront avec les premières mesures.")
            else {
                Text("Températures °C · bleu : sonde 1, orange : sonde 2")
                SeriesChart(rows,{it.t1},{it.t2},0.0,80.0)
                Text("Puissance électrique (valeur brute)")
                SeriesChart(rows,{it.power},null,0.0,(rows.mapNotNull{it.power}.maxOrNull()?:1.0).coerceAtLeast(1.0))
                Text("Réserve en douches estimées")
                SeriesChart(rows,{it.showers?.toDouble()},null,0.0,(rows.mapNotNull{it.showers}.maxOrNull()?:5).coerceAtLeast(5).toDouble())
                val starts=rows.zipWithNext().count{(a,b)->b.time-a.time in 120000L..2700000L && a.heating=="off" && b.heating=="on"}
                Text("Débuts de chauffe observés : "+starts+" (minimum, pas tous les cycles).")
                Text("Les interruptions de relevé sont laissées vides.",style=MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Text("Carte hebdomadaire",style=MaterialTheme.typography.titleLarge)
            WeeklyHeatmap(map)
            Text("Gris : aucune observation ; bleu : proportion d’intervalles avec soutirage probable. Ce n’est pas une mesure de débit.",style=MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text("Consommation et coût",style=MaterialTheme.typography.titleLarge)
            val total=energy.sumOf{it.delta}
            Text("Variation cumulée sur "+energy.size+" intervalles : "+total+" unités brutes")
            if(energy.isNotEmpty()) EnergyBars(energy.map{it.delta.toDouble()/(if(tariff.unitConfirmed)1000.0 else 1.0)})
            if(tariff.unitConfirmed && energy.isNotEmpty()) {
                val kwh=total/1000.0
                Text(String.format(java.util.Locale.FRANCE,"%.2f kWh observés",kwh))
                val cost=if(!tariff.dual) tariff.base?.let{it*kwh} else {
                    if(energy.all{it.t1 != null && it.t2 != null && (it.t1 + it.t2) == it.delta} && tariff.t1!=null && tariff.t2!=null)
                        energy.sumOf{it.t1!!}/1000.0*tariff.t1+energy.sumOf{it.t2!!}/1000.0*tariff.t2 else null
                }
                Text(cost?.let{String.format(java.util.Locale.FRANCE,"%.2f € (hors abonnement)",it)}?:"Coût indisponible : tarifs ou répartition incomplets.")
                if(tariff.dual && tariff.t1!=null && tariff.t2!=null && cost!=null) {
                    val expensive=if(tariff.t1>tariff.t2)energy.sumOf{it.t1?:0L} else energy.sumOf{it.t2?:0L}
                    Text("Simulation : déplacer "+(shift*100).roundToInt()+" % de l’énergie du tarif cher vers le moins cher")
                    Slider(value=shift,onValueChange={shift=it})
                    Text(String.format(java.util.Locale.FRANCE,"Potentiel théorique maximal : %.2f €",expensive/1000.0*shift*kotlin.math.abs(tariff.t1-tariff.t2)))
                    Text("Scénario idéal, pas une économie garantie. Sans pertes supplémentaires ni contraintes de chauffe.",style=MaterialTheme.typography.bodySmall)
                }
            } else Text("Conversion kWh et coûts verrouillés tant que l’unité n’est pas confirmée dans Réglages.")
            HorizontalDivider()
            Text("Modèle thermique expérimental",style=MaterialTheme.typography.titleLarge)
            Text(String.format(java.util.Locale.FRANCE,"%.1f h de mesures rapprochées exploitables",model.coveredHours))
            Text("Refroidissement : "+(model.cooling?.let{String.format(java.util.Locale.FRANCE,"%.2f °C/h",it)}?:"en attente")+" ("+model.coolingCount+" intervalles candidats)")
            Text("Réchauffement : "+(model.heating?.let{String.format(java.util.Locale.FRANCE,"%.1f °C/h",it)}?:"en attente")+" ("+model.heatingCount+" intervalles candidats)")
            model.heating?.takeIf{it>0}?.let{Text(String.format(java.util.Locale.FRANCE,"Estimation indicative pour +10 °C aux sondes : %.0f min",600/it))}
            Text("Les températures aux sondes ne représentent pas exactement les 65 L. Ces médianes sont des approximations.")
            Text("Prévision de demande",style=MaterialTheme.typography.titleLarge)
            Text("Jours bien couverts : "+forecast.days+" · couverture horaire "+(forecast.coverage*100).roundToInt()+" %")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                FilterChip(selected=!weekend,onClick={weekend=false},label={Text("Semaine")})
                FilterChip(selected=weekend,onClick={weekend=true},label={Text("Week-end")})
            }
            if(forecast.windows.isEmpty()) Text("Pas encore de fenêtre suffisamment récurrente. Minimum : 14 jours bien couverts et 60 % de couverture sur la période. Essayez une période de 30 jours.")
            forecast.windows.forEach{w->
                Text(String.format(java.util.Locale.FRANCE,"%02dh–%02dh · soutirage probable %d jours sur %d observés",w.hour,(w.hour+1)%24,w.demandDays,w.days))
                Text("Baisse médiane de réserve estimée : "+w.reserveLoss+" douche(s). Ce n’est pas une mesure du nombre de douches.",style=MaterialTheme.typography.bodySmall)
            }
            Text("Optimisation automatique désactivée. Il faudra valider la couverture, la demande et les pertes avant tout pilotage prédictif.")
            Button(onClick={launcher.launch("guelma-historique.csv")},enabled=rows.isNotEmpty()){Text("Exporter les relevés CSV")}
            if(exportStatus.isNotBlank()) Text(exportStatus)
        }
    }
}
@Composable
private fun SeriesChart(rows:List<Sample>, first:(Sample)->Double?, second:((Sample)->Double?)?, low:Double, high:Double){
    val primary=MaterialTheme.colorScheme.primary
    val secondary=Color(0xFFE58B42)
    val outline=MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(112.dp)){
        val duration=(rows.last().time-rows.first().time).coerceAtLeast(1)
        fun point(r:Sample,v:Double)=Offset(((r.time-rows.first().time).toDouble()/duration*size.width).toFloat(), (size.height*(1-(v-low)/(high-low)).coerceIn(0.0,1.0)).toFloat())
        for(i in 0..4){val y=size.height*i/4;drawLine(outline,Offset(0f,y),Offset(size.width,y))}
        fun series(get:(Sample)->Double?,color:Color){
            rows.zipWithNext().forEach{(a,b)->
                val x=get(a);val y=get(b)
                if(x!=null&&y!=null&&b.time-a.time<=2700000L)drawLine(color,point(a,x),point(b,y),strokeWidth=3f)
            }
        }
        series(first,primary);if(second!=null)series(second,secondary)
    }
    Text(String.format(java.util.Locale.FRANCE,"%.0f à %.0f · début → fin de période",low,high),style=MaterialTheme.typography.bodySmall)
}
@Composable
private fun WeeklyHeatmap(grid:List<List<Cell>>){
    val primary=MaterialTheme.colorScheme.primary
    val unknown=MaterialTheme.colorScheme.surfaceVariant
    Row{
        Column(Modifier.width(35.dp),verticalArrangement=Arrangement.spacedBy(1.dp)){
            listOf("Lun","Mar","Mer","Jeu","Ven","Sam","Dim").forEach{Box(Modifier.height(17.dp),contentAlignment=Alignment.CenterStart){Text(it,style=MaterialTheme.typography.labelSmall)}}
        }
        Canvas(Modifier.weight(1f).height(125.dp)){
            val w=size.width/24;val h=size.height/7
            grid.forEachIndexed{d,day->day.forEachIndexed{hr,cell->
                val color=if(cell.observed==0)unknown else primary.copy(alpha=.12f+.88f*cell.draws/cell.observed)
                drawRoundRect(color,Offset(hr*w+1,d*h+1),Size((w-2).coerceAtLeast(1f),(h-2).coerceAtLeast(1f)),CornerRadius(2f))
            }}
        }
    }
    Text("00h     04h     08h     12h     16h     20h",style=MaterialTheme.typography.labelSmall)
}
@Composable
private fun EnergyBars(values:List<Double>){
    val primary=MaterialTheme.colorScheme.primary
    val max=values.maxOrNull()?.coerceAtLeast(.0001)?:1.0
    Canvas(Modifier.fillMaxWidth().height(90.dp)){
        val w=size.width/values.size.coerceAtLeast(1)
        values.forEachIndexed{i,v->val h=(v/max*size.height).toFloat();drawRect(primary,Offset(i*w,size.height-h),Size(w.coerceAtLeast(.5f),h))}
    }
    Text("Variations par intervalle observé (échelle relative)",style=MaterialTheme.typography.bodySmall)
}
