package fr.nesy.guelmacontrol

import android.os.Bundle
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[DiagnosticModel::class.java]
        setContent {
            val colors = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF72D6F9), background = Color(0xFF0B1423), surface = Color(0xFF18283B))
                else lightColorScheme(primary = Color(0xFF00678A), background = Color(0xFFF4F9FC), surface = Color(0xFFFFFFFF))
            MaterialTheme(colorScheme = colors) { DiagnosticScreen(model) }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    delay(120000)
                    if (model.connected && !model.demo && !model.busy && !model.needsRefresh) model.refresh()
                }
            }
        }
    }
}

class DiagnosticModel(application: Application) : AndroidViewModel(application) {
    private val vault = CredentialVault(application)
    private val api = CozytouchApi()
    private val historyContext = application
    var historyEnabled by mutableStateOf(HistorySettings.enabled(application)); private set
    var devices by mutableStateOf<List<Device>>(emptyList()); private set
    var lastSync by mutableStateOf(0L); private set
    var actionMessage by mutableStateOf(""); private set
    var needsRefresh by mutableStateOf(false); private set
    var summary by mutableStateOf(""); private set
    var connected by mutableStateOf(false); private set
    var demo by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf(""); private set
    var remembered by mutableStateOf(vault.hasCredentials()); private set
    init { if (remembered) reconnect() }

    fun login(email: String, password: String, rememberConnection: Boolean) {
        if (busy) return
        actionMessage = ""; needsRefresh = false
        runRequest {
            val result = api.login(email, password)
            if (rememberConnection) {
                try { vault.save(email.trim(), password) }
                catch (_: Exception) { vault.clear() }
            } else { HistorySettings.configure(historyContext, false); vault.clear() }
            result
        }
    }
    fun reconnect() {
        if (busy) return
        runRequest {
            val saved = vault.load() ?: throw ApiException("Identifiants indisponibles. Reconnectez-vous manuellement.")
            api.login(saved.first, saved.second)
        }
    }
    fun forget() { if (!busy) { configureHistory(false); vault.clear(); remembered = false } }
    fun configureHistory(enabled: Boolean) {
        if (enabled && !vault.hasCredentials()) return
        HistorySettings.configure(historyContext, enabled)
        historyEnabled = enabled
        if (enabled) viewModelScope.launch(Dispatchers.IO) { loggedRecord(historyContext, devices, "Activation") }
    }
    fun clearHistory() {
        configureHistory(false)
        viewModelScope.launch(Dispatchers.IO) { HistoryStore(historyContext).use { it.clearAll() } }
    }
    fun refresh() {
        if (connected && !demo) runRequest {
            if (api.sessionExpired() && vault.hasCredentials()) {
                val saved = vault.load() ?: throw ApiException("Reconnectez-vous.")
                api.login(saved.first, saved.second)
            } else api.readDevices()
        }
    }
    private fun runRequest(action: () -> List<Device>) {
        if (busy) return
        busy = true; error = ""; demo = false
        viewModelScope.launch {
            try {
                devices = withContext(Dispatchers.IO) { CloudGate.mutex.withLock { action().also { rows -> runCatching { loggedRecord(historyContext, rows, "Application") } } } }
                summary = api.discoverySummary
                if (devices.isNotEmpty()) lastSync = System.currentTimeMillis()
                needsRefresh = false
                connected = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                api.logout(); connected = false; devices = emptyList()
                error = if (e is ApiException) e.message.orEmpty() else "Connexion impossible ou réponse inattendue. Vérifiez votre réseau."
            } finally { remembered = vault.hasCredentials(); historyEnabled = HistorySettings.enabled(historyContext); busy = false }
        }
    }
    fun send(command: String, value: Any) {
        if (busy || demo || !connected || needsRefresh) return
        busy = true; needsRefresh = true; actionMessage = ""
        viewModelScope.launch {
            try {
                actionMessage = withContext(Dispatchers.IO) { CloudGate.mutex.withLock { api.send(command, value) } }
                for (wait in listOf(5000L, 12000L, 20000L)) {
                    actionMessage = "Ordre envoyé ; vérification du retour d’état…"
                    delay(wait)
                    val rows = withContext(Dispatchers.IO) {
                        CloudGate.mutex.withLock {
                            api.readDevices().also { data -> runCatching { loggedRecord(historyContext, data, "Vérification commande") } }
                        }
                    }
                    devices = rows
                    summary = api.discoverySummary
                    if (rows.isNotEmpty()) lastSync = System.currentTimeMillis()
                    val heater = rows.singleOrNull { it.type == HEATER_TYPE }
                    if (heater != null && heater.available == true && commandMatches(heater, command, value)) {
                        actionMessage = "Commande confirmée par le retour d’état Cozytouch."
                        needsRefresh = false
                        break
                    }
                }
                if (needsRefresh) actionMessage = "Ordre envoyé, mais état non confirmé. Actualisez avant toute nouvelle commande."
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { actionMessage = (if (e is ApiException) e.message.orEmpty() else "Résultat de l’ordre inconnu.") + " Actualisez avant de réessayer." }
            finally { busy = false }
        }
    }
    fun showDemo() {
        if (busy) return
        api.logout(); summary = ""; actionMessage = ""; needsRefresh = false; demo = true; connected = true; error = ""
        devices = listOf(Device(0, 0, true, listOf(Capability(1, "75"), Capability(2, "true"), Capability(3, "2"))))
    }
    fun logout() { if (!busy) { if (!demo) forget(); api.logout(); summary = ""; actionMessage = ""; needsRefresh = false; connected = false; demo = false; devices = emptyList(); error = "" } }
    override fun onCleared() { api.logout(); super.onCleared() }
}

@Composable
fun DiagnosticScreen(model: DiagnosticModel = viewModel()) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var rememberConnection by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    val clipboard = LocalClipboardManager.current
    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Guelma Control", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Text("Sauter Guelma 2 MP · 65 L · V0.4.0")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (model.demo) "DÉMONSTRATION" else if (model.connected) "Session Cozytouch active" else "Connexion Cozytouch", style = MaterialTheme.typography.titleLarge)
                    Text(if (model.demo) "Données fictives. Aucun appareil contacté." else "Pilotage expérimental. Confirmation avant chaque ordre.")
                }
            }
            if (!model.connected) {
                OutlinedTextField(email, { email = it }, label = { Text("E-mail Cozytouch") }, singleLine = true, enabled = !model.busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Mot de passe") }, singleLine = true, enabled = !model.busy, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = rememberConnection, onCheckedChange = { rememberConnection = it }, enabled = !model.busy)
                    Text("Mémoriser ma connexion")
                }
                if (model.remembered) {
                    OutlinedButton(onClick = { model.reconnect() }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("Utiliser ma connexion enregistrée") }
                    TextButton(onClick = { model.forget() }, enabled = !model.busy) { Text("Oublier les identifiants") }
                }
                Button(onClick = { model.login(email, password, rememberConnection); password = ""; copied = false }, enabled = !model.busy && email.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Connecter mon chauffe-eau") }
                OutlinedButton(onClick = { password = ""; email = ""; model.showDemo(); copied = false }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("Essayer le mode démo") }
                Text("Si la mémorisation est activée, les identifiants sont chiffrés sur ce téléphone avec Android Keystore. Ils ne sont transmis qu’à Cozytouch via HTTPS.", style = MaterialTheme.typography.bodySmall)
            } else {
                TabRow(selectedTabIndex = tab) {
                    listOf("Pilotage", "Analyse", "Réglages").forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                    }
                }
                if (!model.demo && model.lastSync > 0) Text("Dernière synchronisation : " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(model.lastSync)), style = MaterialTheme.typography.bodySmall)
                if (tab == 2) {
                    Text(model.devices.size.toString() + " appareil(s) détecté(s)", style = MaterialTheme.typography.titleLarge)
                    if (!model.demo && model.summary.isNotBlank()) Text(model.summary, style = MaterialTheme.typography.bodySmall)
                }
                if (model.devices.isEmpty()) Text("Aucun appareil détecté. Copiez le diagnostic pour examiner les étapes de connexion.")
                val heater = model.devices.singleOrNull { it.type == HEATER_TYPE }
                if (heater != null && tab == 0) HeaterDashboard(heater, model)
                if (tab == 1) {
                    if (heater != null && !model.demo) HistoryPanel(heater, model)
                    else Text("Connectez le chauffe-eau réel pour accéder à l’analyse.")
                }
                if (model.actionMessage.isNotBlank()) Text(model.actionMessage, color = MaterialTheme.colorScheme.primary)
                if (tab == 2) CollectionLogPanel()
                if (tab == 2) model.devices.forEachIndexed { index, device ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Appareil " + (index + 1), style = MaterialTheme.typography.titleMedium)
                            Text(device.protocol + " · " + if (device.protocol == "Magellan") "Modèle : " + device.model + " · Produit : " + device.product else device.type)
                            device.states.forEach { state -> Text(state.first + " = " + state.second) }
                            if (device.commands.isNotEmpty()) Text("Commandes déclarées : " + device.commands.joinToString(", "))
                            Text("Disponibilité déclarée : " + when(device.available) { true -> "oui"; false -> "non"; null -> "inconnue" })
                            Text(device.capabilities.size.toString() + " capacités déclarées")
                            HorizontalDivider()
                            device.capabilities.sortedBy { it.id }.forEach { c ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("ID " + c.id, modifier = Modifier.weight(1f))
                                    Text(c.value, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                if (!model.demo) Button(onClick = { model.refresh(); copied = false }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("Actualiser") }
                if (tab == 2) Text("Le rapport ne contient pas les noms, adresses, jetons ou numéros de série. Relisez les valeurs numériques avant de partager.", style = MaterialTheme.typography.bodySmall)
                if (tab == 2) OutlinedButton(onClick = { clipboard.setText(AnnotatedString(diagnosticReport(model.devices, model.demo, model.summary))); copied = true }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text(if (copied) "Rapport copié" else "Copier le diagnostic") }
                if (tab == 2 && model.remembered && !model.demo) Text("Connexion mémorisée sur cet appareil.", style = MaterialTheme.typography.bodySmall)
                if (tab == 2 || model.demo) TextButton(onClick = { model.logout(); tab = 0; email = ""; password = ""; copied = false }, enabled = !model.busy) { Text(if (model.demo) "Quitter la démo" else "Déconnexion et oubli des identifiants") }
            }
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (model.error.isNotBlank()) Text(model.error, color = MaterialTheme.colorScheme.error)
            Text("Application indépendante, non affiliée à Sauter ou Atlantic.", style = MaterialTheme.typography.bodySmall)
        }
    }
}


private data class PendingOrder(val command: String, val value: Any, val label: String)

@Composable
private fun HeaterDashboard(heater: Device, model: DiagnosticModel) {
    fun state(name: String) = heater.state(name)
    fun display(name: String, suffix: String = "") = state(name)?.takeUnless { it.startsWith("masqué") || it == "non renseigné" }?.let { it + suffix } ?: "—"
    val remaining = state("core:NumberOfShowerRemainingState")?.toIntOrNull()
    val maximum = state("core:MaximalShowerManualModeState")?.toIntOrNull()
    val target = state("core:TargetDHWTemperatureState")?.toDoubleOrNull()?.toInt()
    val desired = state("core:ExpectedNumberOfShowerState")?.toIntOrNull()
    val minTemp = state("core:MinimalTemperatureManualModeState")?.toDoubleOrNull()?.toInt()
    val maxTemp = state("core:MaximalTemperatureManualModeState")?.toDoubleOrNull()?.toInt()
    val minShower = state("core:MinimalShowerManualModeState")?.toIntOrNull()
    val mode = state("modbuslink:DHWModeState")
    val boost = state("modbuslink:DHWBoostModeState")
    val absence = state("modbuslink:DHWAbsenceModeState")
    var pending by remember { mutableStateOf<PendingOrder?>(null) }
    val enabled = !model.busy && !model.needsRefresh && !model.demo
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Eau chaude disponible", style = MaterialTheme.typography.titleMedium)
            Text(remaining?.toString() ?: "—", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.displayLarge)
            Text("Douches restantes estimées")
            if (remaining != null && maximum != null && maximum > 0) {
                LinearProgressIndicator(progress = { (remaining.toFloat()/maximum).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("Capacité de référence : " + maximum + " douches")
            }
            HorizontalDivider()
            Text("Sonde milieu : " + display("modbuslink:MiddleWaterTemperatureState", " °C"))
            Text("Autre sonde : " + display("core:MiddleWaterTemperatureInState", " °C"))
            Text("Consigne : " + display("core:TargetDHWTemperatureState", " °C"))
            Text("Puissance électrique : " + display("modbuslink:PowerHeatElectricalState", " W"))
            Text("Chauffe : " + when(state("core:HeatingStatusState")) { "off" -> "en veille"; "on" -> "active"; else -> "état non interprété" })
            Text("Mode : " + when(mode) { "autoMode" -> "Auto"; "manualEcoActive" -> "Manuel éco"; "manualEcoInactive" -> "Manuel"; else -> "inconnu" })
            Text("Boost : " + (boost ?: "—") + " · Absence : " + (absence ?: "—"))
            Text("Disponibilité : " + if (heater.available == true) "en ligne" else "non confirmée")
        }
    }
    val energy = model.devices.singleOrNull { it.type == "modbuslink:DHWCumulatedElectricalEnergyConsumptionMBLSystemDeviceSensor" }
    if (energy != null) Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Compteurs énergétiques", style = MaterialTheme.typography.titleMedium)
            Text("Total : " + (energy.state("core:ElectricEnergyConsumptionState") ?: "—"))
            Text("Tarif 1 : " + (energy.state("core:ConsumptionTariff1State") ?: "—"))
            Text("Tarif 2 : " + (energy.state("core:ConsumptionTariff2State") ?: "—"))
            Text("Index bruts ; unité et correspondance tarifaire à confirmer.", style = MaterialTheme.typography.bodySmall)
        }
    }
    Text("Pilotage expérimental", style = MaterialTheme.typography.titleLarge)
    Text("Un ordre à la fois. Le retour d’état est vérifié automatiquement.", style = MaterialTheme.typography.bodySmall)
    listOf("Auto" to "autoMode", "Manuel éco" to "manualEcoActive", "Manuel" to "manualEcoInactive").forEach { (label, value) ->
        OutlinedButton(onClick = { pending = PendingOrder("setDHWMode", value, label) },
            enabled = enabled && mode != value && validCommand(heater, "setDHWMode", value), modifier = Modifier.fillMaxWidth()) { Text(if (mode == value) label + " (actif)" else label) }
    }
    if (boost == "off" || boost == "on" || boost == "prog") {
        val value = if (boost == "off") "on" else "off"
        val label = if (value == "on") "Activer le Boost" else "Arrêter le Boost"
        Button(onClick = { pending = PendingOrder("setBoostMode", value, label) },
            enabled = enabled && validCommand(heater, "setBoostMode", value), modifier = Modifier.fillMaxWidth()) { Text(label) }
    }
    if (desired != null && minShower != null && maximum != null && minShower in 1..maximum && maximum <= 20) {
        var selected by remember(desired) { mutableStateOf(desired.coerceIn(minShower, maximum)) }
        Text("Douches demandées : " + selected)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { selected-- }, enabled = selected > minShower && enabled) { Text("−") }
            OutlinedButton(onClick = { selected++ }, enabled = selected < maximum && enabled) { Text("+") }
            Button(onClick = { pending = PendingOrder("setExpectedNumberOfShower", selected, selected.toString() + " douches") },
                enabled = enabled && selected != desired && validCommand(heater, "setExpectedNumberOfShower", selected)) { Text("Appliquer") }
        }
    }
    if (target != null && minTemp != null && maxTemp != null && minTemp in 40..maxTemp && maxTemp <= 80) {
        var selected by remember(target) { mutableStateOf(target.coerceIn(minTemp, maxTemp)) }
        Text("Consigne : " + selected + " °C")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { selected-- }, enabled = selected > minTemp && enabled) { Text("−") }
            OutlinedButton(onClick = { selected++ }, enabled = selected < maxTemp && enabled) { Text("+") }
            Button(onClick = { pending = PendingOrder("setTargetDHWTemperature", selected, "Consigne " + selected + " °C") },
                enabled = enabled && selected != target && validCommand(heater, "setTargetDHWTemperature", selected)) { Text("Appliquer") }
        }
        Text("Réglage limité aux bornes déclarées par le ballon. Attention aux brûlures à température élevée.", style = MaterialTheme.typography.bodySmall)
    }
    if (pending != null) {
        val order = pending!!
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Confirmer la commande") },
            text = { Text(order.label + " — envoyer cet ordre au chauffe-eau ?") },
            confirmButton = { TextButton(onClick = { pending = null; model.send(order.command, order.value) }, enabled = enabled) { Text("Envoyer") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Annuler") } })
    }
}
