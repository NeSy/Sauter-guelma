package fr.nesy.guelmacontrol

import android.os.Bundle
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF72D6F9), background = Color(0xFF0B1423), surface = Color(0xFF18283B))) { DiagnosticScreen() } }
    }
}

class DiagnosticModel : ViewModel() {
    private val api = CozytouchApi()
    var devices by mutableStateOf<List<Device>>(emptyList()); private set
    var summary by mutableStateOf(""); private set
    var connected by mutableStateOf(false); private set
    var demo by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf(""); private set
    fun login(email: String, password: String) = runRequest { api.login(email, password) }
    fun refresh() { if (connected && !demo) runRequest { api.readDevices() } }
    private fun runRequest(action: () -> List<Device>) {
        if (busy) return
        busy = true; error = ""; demo = false
        viewModelScope.launch {
            try {
                devices = withContext(Dispatchers.IO) { action() }
                summary = api.discoverySummary
                connected = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                api.logout(); connected = false; devices = emptyList()
                error = if (e is ApiException) e.message.orEmpty() else "Connexion impossible ou réponse inattendue. Vérifiez votre réseau."
            } finally { busy = false }
        }
    }
    fun showDemo() {
        if (busy) return
        api.logout(); summary = ""; demo = true; connected = true; error = ""
        devices = listOf(Device(0, 0, true, listOf(Capability(1, "75"), Capability(2, "true"), Capability(3, "2"))))
    }
    fun logout() { if (!busy) { api.logout(); summary = ""; connected = false; demo = false; devices = emptyList(); error = "" } }
    override fun onCleared() { api.logout(); super.onCleared() }
}

@Composable
fun DiagnosticScreen(model: DiagnosticModel = viewModel()) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Guelma Control", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
            Text("Sauter Guelma 2 MP · 65 L · V0.1.1")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (model.demo) "DÉMONSTRATION" else if (model.connected) "Session Cozytouch active" else "Diagnostic en lecture seule", style = MaterialTheme.typography.titleLarge)
                    Text(if (model.demo) "Données fictives. Aucun appareil contacté." else "Aucune commande de chauffe n’est envoyée.")
                }
            }
            if (!model.connected) {
                OutlinedTextField(email, { email = it }, label = { Text("E-mail Cozytouch") }, singleLine = true, enabled = !model.busy, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Mot de passe") }, singleLine = true, enabled = !model.busy, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Button(onClick = { model.login(email, password); password = ""; copied = false }, enabled = !model.busy && email.isNotBlank() && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Connecter mon chauffe-eau") }
                OutlinedButton(onClick = { password = ""; email = ""; model.showDemo(); copied = false }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text("Essayer le mode démo") }
                Text("API expérimentale. Mot de passe non enregistré. Les identifiants sont transmis uniquement au service Cozytouch via HTTPS.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(model.devices.size.toString() + " appareil(s) détecté(s)", style = MaterialTheme.typography.titleLarge)
                if (!model.demo && model.summary.isNotBlank()) Text(model.summary, style = MaterialTheme.typography.bodySmall)
                if (model.devices.isEmpty()) Text("Aucun appareil détecté. Copiez le diagnostic pour examiner les étapes de connexion.")
                model.devices.forEachIndexed { index, device ->
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
                Text("Le rapport ne contient pas les noms, adresses, jetons ou numéros de série. Relisez les valeurs numériques avant de partager.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(diagnosticReport(model.devices, model.demo, model.summary))); copied = true }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) { Text(if (copied) "Rapport copié" else "Copier le diagnostic") }
                TextButton(onClick = { model.logout(); email = ""; password = ""; copied = false }, enabled = !model.busy) { Text("Déconnexion") }
            }
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (model.error.isNotBlank()) Text(model.error, color = MaterialTheme.colorScheme.error)
            Text("Application indépendante, non affiliée à Sauter ou Atlantic.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
