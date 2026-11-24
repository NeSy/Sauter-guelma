package fr.nesy.guelmacontrol

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@Composable
internal fun HistoryPanel(heater: Device, model: DiagnosticModel) {
    val context = LocalContext.current.applicationContext
    var rows by remember(heater.historyKey) { mutableStateOf(emptyList<Sample>()) }
    var status by remember { mutableStateOf("") }
    var lastAttempt by remember { mutableStateOf(0L) }
    var clearDialog by remember { mutableStateOf(false) }
    LaunchedEffect(heater.historyKey, model.historyEnabled) {
        while (true) {
            rows = withContext(Dispatchers.IO) {
                runCatching { HistoryStore(context).use { it.recent(heater.historyKey) } }.getOrDefault(emptyList())
            }
            status = HistorySettings.status(context)
            lastAttempt = HistorySettings.lastAttempt(context)
            delay(20000)
        }
    }
    val stats = remember(rows) { analyzeHabits(rows) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Analyse des habitudes", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Observation automatique", Modifier.weight(1f))
                Switch(checked = model.historyEnabled, onCheckedChange = model::configureHistory,
                    enabled = model.remembered && heater.historyKey.isNotEmpty() && !model.busy)
            }
            if (!model.remembered) Text("La collecte en arrière-plan nécessite une connexion mémorisée.")
            Text("Relevés toutes les 30 min environ (selon Android), et lors des actualisations. Historique local, 90 jours maximum. Aucun ordre automatique.", style = MaterialTheme.typography.bodySmall)
            if (model.historyEnabled) {
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (lastAttempt > 0) Text("Dernière tentative : " + DateFormat.getDateTimeInstance().format(Date(lastAttempt)), style = MaterialTheme.typography.bodySmall)
            }
            if (rows.isNotEmpty()) {
                Text("Dernière mesure : " + DateFormat.getDateTimeInstance().format(Date(rows.last().time)), style = MaterialTheme.typography.bodySmall)
                Text("Sur les 14 derniers jours : " + stats.samples + " relevés · " + stats.days + " jours observés")
                Text(stats.likelyEvents.toString() + " intervalles de soutirage probable / " + stats.usablePairs + " intervalles exploitables")
                Text("Ce ne sont pas des nombres de douches mesurés. Les chutes de réserve et de température sont des indices ; une chauffe simultanée peut les masquer.", style = MaterialTheme.typography.bodySmall)
                if (stats.days < 14) Text("Apprentissage en cours : viser au moins 14 jours bien couverts. Pas encore de prévision fiable.")
                HourRows("Semaine", stats.weekday)
                HourRows("Week-end", stats.weekend)
            } else Text("Aucune mesure enregistrée. Activez l’observation pour commencer.")
            OutlinedButton(onClick = { clearDialog = true }) { Text("Effacer l’historique") }
        }
    }
    CollectionLogPanel()
    if (clearDialog) AlertDialog(onDismissRequest = { clearDialog = false }, title = { Text("Effacer tous les relevés ?") },
        text = { Text("L’observation sera désactivée et les relevés locaux supprimés.") },
        confirmButton = { TextButton(onClick = { clearDialog = false; model.clearHistory(); rows = emptyList() }) { Text("Effacer") } },
        dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("Annuler") } })
}

@Composable
private fun HourRows(title: String, hours: List<HourStat>) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    val candidates = hours.filter { it.observed >= 3 && it.likely > 0 }
        .sortedWith(compareByDescending<HourStat> { it.likely.toFloat()/it.observed }.thenByDescending { it.observed }).take(4)
    if (candidates.isEmpty()) Text("Pas encore de créneau suffisamment observé.", style = MaterialTheme.typography.bodySmall)
    candidates.forEach { h ->
        Text(String.format("%02dh–%02dh : %d / %d intervalles", h.hour, (h.hour+1)%24, h.likely, h.observed))
        LinearProgressIndicator(progress = { h.likely.toFloat()/h.observed }, modifier = Modifier.fillMaxWidth())
    }
}
