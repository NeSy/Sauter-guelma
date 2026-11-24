package fr.nesy.guelmacontrol

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@Composable
internal fun PredictivePanel(heater:Device,lastSync:Long,observationEnabled:Boolean) {
    val context=LocalContext.current.applicationContext
    val prefs=remember{context.getSharedPreferences("prediction",android.content.Context.MODE_PRIVATE)}
    var margin by remember{mutableStateOf(prefs.getInt("margin",1).coerceIn(0,3))}
    var rows by remember(heater.historyKey){mutableStateOf(emptyList<Sample>())}
    var now by remember{mutableStateOf(ZonedDateTime.now())}
    var readError by remember{mutableStateOf(false)}
    val clipboard=LocalClipboardManager.current
    LaunchedEffect(heater.historyKey,observationEnabled){
        while(true){
            val result=withContext(Dispatchers.IO){runCatching{HistoryStore(context).use{it.recent(heater.historyKey,31)}}}
            if(result.isSuccess){rows=result.getOrDefault(emptyList());readError=false}else readError=true
            now=ZonedDateTime.now()
            delay(60000)
        }
    }
    val max=heater.state("core:MaximalShowerManualModeState")?.toIntOrNull()?:0
    val plan=remember(rows,now,margin,max){createPlan(rows,now,max,margin)}
    val fmt=remember{DateTimeFormatter.ofPattern("EEE dd/MM HH:mm",java.util.Locale.FRANCE)}
    Card(Modifier.fillMaxWidth()){
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("Optimisation prédictive",style=MaterialTheme.typography.titleLarge)
            Text("Mode conseil · aucun ordre envoyé automatiquement.",color=MaterialTheme.colorScheme.primary)
            if(readError)Text("Lecture de l’historique indisponible.")
            if(!observationEnabled)Text("Observation en pause. Le modèle utilise uniquement les données déjà enregistrées.")
            Text("Couverture sur les 30 derniers jours complets : "+(plan.profile.coverage*100).roundToInt()+" %")
            LinearProgressIndicator(progress={plan.profile.coverage.toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
            Text("Jours bien couverts : "+plan.profile.completeDays+" / 14 minimum")
            val v=plan.profile.validation
            Text("Validation sur les 7 derniers jours : "+v.hours+" heures, "+v.eventHours+" heures de soutirage probable")
            if(v.brier!=null && v.baseline!=null){
                Text(String.format(java.util.Locale.FRANCE,"Erreur probabiliste : %.3f · référence %.3f (plus bas = mieux)",v.brier,v.baseline))
            }
            Text(if(v.passed)"Validation historique : modèle meilleur que la référence"else "Validation historique encore insuffisante")
            Text("Marge de confort : "+margin+" douche(s) estimée(s)")
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                OutlinedButton(onClick={margin--;prefs.edit().putInt("margin",margin).apply()},enabled=margin>0){Text("−")}
                OutlinedButton(onClick={margin++;prefs.edit().putInt("margin",margin).apply()},enabled=margin<3){Text("+")}
            }
            val rec=plan.recovery
            Text(rec.minutesPerShower?.let{"Recharge prudente observée : "+it.roundToInt()+" min par douche estimée"}?:"Temps de recharge à apprendre ("+rec.intervals+" intervalles candidats).")
            HorizontalDivider()
            Text("Prochaines 24 heures",style=MaterialTheme.typography.titleMedium)
            if(plan.reason.isNotBlank())Text(plan.reason)
            val fresh=lastSync>0 && System.currentTimeMillis()-lastSync in 0..300000
            val available=heater.state("core:NumberOfShowerRemainingState")?.toIntOrNull()
            if(!fresh)Text("État du ballon trop ancien pour comparer la réserve actuelle.")
            plan.windows.forEachIndexed{index,w->
                Text(w.at.format(fmt)+" · "+(w.probability*100).roundToInt()+" % (estimation)",style=MaterialTheme.typography.titleSmall)
                Text("Objectif de réserve : "+w.reserve+" douche(s) · "+w.count+" jours observés")
                if(w.leadMinutes!=null){
                    Text("Départ prudent indicatif depuis réserve vide : "+w.at.minusMinutes(w.leadMinutes.toLong()).format(fmt))
                }
                if(index==0 && fresh && available!=null){
                    val deficit=(w.reserve-available).coerceAtLeast(0)
                    Text(if(deficit==0)"Réserve actuelle suffisante pour ce créneau pris isolément."else "Écart actuel : "+deficit+" douche(s) estimée(s). Vérifier le temps de recharge.")
                }
                HorizontalDivider()
            }
            if(plan.windows.isNotEmpty())OutlinedButton(onClick={clipboard.setText(AnnotatedString(planText(plan)))}){Text("Copier le plan conseillé")}
            Text("Les besoins peuvent se cumuler entre deux créneaux. Le plan ne réduit ni la consigne ni les protections sanitaires. Aucun gain ou confort futur n’est garanti.",style=MaterialTheme.typography.bodySmall)
        }
    }
}
