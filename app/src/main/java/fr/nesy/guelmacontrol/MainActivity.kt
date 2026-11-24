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
            GuelmaTheme { GuelmaScreen(model) }
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
                devices = withContext(Dispatchers.IO) { CloudGate.mutex.withLock { loggedFetch(historyContext, "Application", action) } }
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
                            loggedFetch(historyContext, "Vérification commande") { api.readDevices() }
                        }
                    }
                    devices = rows
                    summary = api.discoverySummary
                    if (rows.isNotEmpty()) lastSync = System.currentTimeMillis()
                    val heater = rows.singleOrNull { it.type == HEATER_TYPE }
                    if (heater != null && heater.available == true && commandMatches(heater, command, value)) {
                        actionMessage = if (command == "setAbsenceMode" && value is Int) "Mode absence confirmé ; vérifiez la date de retour dans Cozytouch." else "Commande confirmée par le retour d’état Cozytouch."
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
        devices = demoDevices()
    }
    fun logout() { if (!busy) { if (!demo) forget(); api.logout(); summary = ""; actionMessage = ""; needsRefresh = false; connected = false; demo = false; devices = emptyList(); error = "" } }
    override fun onCleared() { api.logout(); super.onCleared() }
}

