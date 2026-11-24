package fr.nesy.guelmacontrol

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

data class Capability(val id: Int, val value: String)
data class Device(val model: Int, val product: Int, val available: Boolean?, val capabilities: List<Capability>, val protocol: String = "Magellan", val type: String = "", val states: List<Pair<String, String>> = emptyList(), val commands: List<String> = emptyList(), val historyKey: String = "")
internal class ApiException(message: String): Exception(message)

internal class CozytouchApi {
    private val base = "https://apis.groupe-atlantic.com"
    // Publicly documented mobile-client identifier, reference: gduteil/cozytouch.
    private val client = "Q3RfMUpWeVRtSUxYOEllZkE3YVVOQmpGblpVYToyRWNORHpfZHkzNDJVSnFvMlo3cFNKTnZVdjBh"
    private var token: String? = null
    private var expiresAt = 0L
    private var blockedUntil = 0L

    private var heaterUrl: String? = null
    private var heaterSnapshot: Device? = null
    private var overkizCookie = ""
    private val overkizBase = "https://ha110-1.overkiz.com/enduser-mobile-web/enduserAPI"
    var discoverySummary = ""
        private set
    fun logout() { token = null; expiresAt = 0L; overkizCookie = ""; heaterUrl = null; heaterSnapshot = null; discoverySummary = "" }
    fun login(email: String, password: String): List<Device> {
        logout()
        val fields = mapOf("grant_type" to "password", "scope" to "openid",
            "username" to "GA-PRIVATEPERSON/" + email.trim(), "password" to password)
        val form = fields.entries.joinToString("&") { encode(it.key) + "=" + encode(it.value) }
        val response = JSONObject(request("/users/token", "Basic " + client, form))
        val access = response.optString("access_token")
        if (access.isBlank()) throw ApiException("Jeton absent. Vérifiez la compatibilité de la connexion.")
        token = access
        expiresAt = SystemClock.elapsedRealtime() + response.optLong("expires_in", 3600).coerceIn(60, 86400) * 1000 - 30000
        return try { readDevices() } catch (e: Exception) { logout(); throw e }
    }
    fun sessionExpired(): Boolean = token == null || SystemClock.elapsedRealtime() >= expiresAt
    fun readDevices(): List<Device> {
        val access = token ?: throw ApiException("Reconnectez-vous.")
        if (SystemClock.elapsedRealtime() >= expiresAt) { logout(); throw ApiException("Session expirée. Reconnectez-vous.") }
        heaterUrl = null; heaterSnapshot = null
        discoverySummary = "Authentification Atlantic : OK"
        val setup = JSONArray(request("/magellan/cozytouch/setupviewv2", "Bearer " + access))
        val magellan = buildList {
            for (i in 0 until setup.length()) {
                val devices = setup.optJSONObject(i)?.optJSONArray("devices") ?: continue
                for (j in 0 until devices.length()) {
                    val d = devices.optJSONObject(j) ?: continue
                    val caps = d.optJSONArray("capabilities") ?: JSONArray()
                    val values = buildList {
                        for (k in 0 until caps.length()) {
                            val c = caps.optJSONObject(k) ?: continue
                            val id = c.optInt("capabilityId", -1)
                            if (id >= 0) add(Capability(id, diagnosticValue(c.opt("value"))))
                        }
                    }
                    add(Device(d.optInt("modelId", -1), d.optInt("productId", -1),
                        if (d.has("isAvailable") && !d.isNull("isAvailable")) d.optBoolean("isAvailable") else null, values))
                }
            }
        }
        discoverySummary += "\nMagellan : " + setup.length() + " installation(s), " + magellan.size + " appareil(s)"
        if (magellan.isNotEmpty()) return magellan
        return try {
            val devices = readOverkiz(access)
            discoverySummary += "\nOverkiz : " + devices.size + " appareil(s)"
            devices
        } catch (e: Exception) {
            discoverySummary += "\nOverkiz : " + (if (e is ApiException) e.message.orEmpty() else "réponse inattendue ou erreur réseau")
            emptyList()
        }
    }

    private fun readOverkiz(access: String): List<Device> {
        if (overkizCookie.isEmpty()) {
            val raw = request("/magellan/accounts/jwt", "Bearer " + access).trim()
            val jwt = if (raw.startsWith("{")) JSONObject(raw).optString("jwt") else raw.trim('"')
            if (!Regex("^[A-Za-z0-9_-]+[.][A-Za-z0-9_-]+[.][A-Za-z0-9_-]+$").matches(jwt))
                throw ApiException("JWT absent ou invalide")
            discoverySummary += "\nÉchange JWT : OK"
            val login = request("/login", "", "jwt=" + encode(jwt), overkiz = true, captureCookie = true)
            if (login.trim().startsWith("{") && !JSONObject(login).optBoolean("success", true))
                throw ApiException("Connexion Overkiz refusée")
            if (overkizCookie.isBlank()) throw ApiException("Cookie Overkiz absent")
            discoverySummary += "\nSession Overkiz : OK"
        }
        val rows = JSONArray(request("/setup/devices", "", overkiz = true))
        val targets = mutableListOf<Pair<String, Device>>()
        val devices = buildList {
            for (i in 0 until rows.length()) {
                val d = rows.optJSONObject(i) ?: continue
                val definition = d.optJSONObject("definition")
                val type = safeTechnicalName(d.optString("controllableName", definition?.optString("uiClass").orEmpty()))
                val states = d.optJSONArray("states") ?: JSONArray()
                val commands = definition?.optJSONArray("commands") ?: JSONArray()
                val url = d.optString("deviceURL")
                val device = Device(-1, -1, if (d.has("available") && !d.isNull("available")) d.optBoolean("available") else null,
                    emptyList(), "Overkiz", type,
                    buildList {
                        for (j in 0 until states.length()) {
                            val s = states.optJSONObject(j) ?: continue
                            val name = safeTechnicalName(s.optString("name"))
                            if (name.isNotEmpty()) add(name to diagnosticValue(s.opt("value")))
                        }
                    },
                    buildList {
                        for (j in 0 until commands.length()) {
                            val name = safeTechnicalName(commands.optJSONObject(j)?.optString("commandName").orEmpty())
                            if (name.isNotEmpty()) add(name)
                        }
                    }, if (url.startsWith("modbuslink://")) historyKey(url) else "")
                add(device)
                if (device.type == HEATER_TYPE && url.startsWith("modbuslink://")) targets.add(url to device)
            }
        }
        if (targets.size == 1 && devices.count { it.type == HEATER_TYPE } == 1) {
            heaterUrl = targets[0].first
            heaterSnapshot = targets[0].second
        }
        return devices
    }

    fun send(command: String, value: Any): String {
        val url = heaterUrl ?: throw ApiException("Un seul chauffe-eau compatible doit être détecté.")
        val device = heaterSnapshot ?: throw ApiException("Actualisez le chauffe-eau.")
        if (overkizCookie.isBlank() || token == null || SystemClock.elapsedRealtime() >= expiresAt)
            throw ApiException("Session expirée. Reconnectez-vous.")
        if (!validCommand(device, command, value)) throw ApiException("Commande non disponible ou valeur hors limites.")
        val payload = JSONObject().put("label", "Guelma Control").put("actions", JSONArray().put(
            JSONObject().put("deviceURL", url).put("commands", JSONArray().put(
                JSONObject().put("name", command).put("parameters", JSONArray().put(value))))))
        val raw = request("/exec/apply", "", payload.toString(), overkiz = true, json = true)
        if (raw.isBlank()) throw ApiException("Réponse vide : résultat inconnu. Actualisez avant toute nouvelle commande.")
        val reply = JSONObject(raw)
        if (reply.optString("execId").isBlank()) throw ApiException("Exécution non confirmée. Actualisez avant de réessayer.")
        return "Ordre accepté par le cloud. Actualisez pour vérifier l’état réel."
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun request(path: String, auth: String, form: String? = null, overkiz: Boolean = false, captureCookie: Boolean = false, json: Boolean = false): String {
        if (SystemClock.elapsedRealtime() < blockedUntil) throw ApiException("Limite de requêtes atteinte. Réessayez plus tard.")
        val connection = URL((if (overkiz) overkizBase else base) + path).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            if (!overkiz) connection.setRequestProperty("Authorization", auth)
            if (overkiz && overkizCookie.isNotBlank()) connection.setRequestProperty("Cookie", overkizCookie)
            connection.setRequestProperty("Accept", "application/json")
            if (form != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", if (json) "application/json" else "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status == 429) {
                val delay = connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 300
                blockedUntil = SystemClock.elapsedRealtime() + delay.coerceIn(60, 86400) * 1000
                throw ApiException("Limite de requêtes atteinte. Réessayez plus tard.")
            }
            if (status == 401) { if (overkiz) overkizCookie = "" else logout(); throw ApiException("Authentification refusée ou session expirée (401).") }
            if (status == 400 && form != null && !overkiz) throw ApiException("Connexion refusée (400). Vérifiez les identifiants et la compatibilité.")
            if (status !in 200..299) throw ApiException("Réponse Cozytouch : HTTP " + status)
            if (captureCookie && overkiz) {
                overkizCookie = connection.headerFields.entries
                    .filter { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
                    .flatMap { it.value }.map { it.substringBefore(';') }.joinToString("; ")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { connection.disconnect() }
    }
}
internal fun safeTechnicalName(raw: String): String =
    raw.takeIf { it.length in 1..100 && Regex("^[A-Za-z][A-Za-z0-9_:.-]*$").matches(it) }.orEmpty()

internal fun diagnosticValue(value: Any?): String {
    val raw = value?.toString() ?: return "non renseigné"
    if (raw == "null") return "non renseigné"
    if (raw in setOf("true", "false", "prog", "autoMode", "manualEcoActive", "manualEcoInactive", "absence", "relaunch", "on", "off", "auto", "manual", "eco", "comfort", "standby", "boost", "always", "available", "unavailable")) return raw
    if (raw.length <= 14 && Regex("^-?[0-9]+(\\.[0-9]+)?$").matches(raw)) return raw
    return "masqué (texte ou structure)"
}
internal fun diagnosticReport(devices: List<Device>, demo: Boolean, summary: String = ""): String = buildString {
    appendLine("Guelma Control 0.4.0 — " + if (demo) "DÉMO" else "diagnostic")
    if (!demo && summary.isNotBlank()) appendLine(summary)
    appendLine("Appareils détectés : " + devices.size)
    devices.forEachIndexed { index, device ->
        appendLine("Appareil " + (index + 1) + " | " + device.protocol + " | " + device.type + " | modèle=" + device.model + " | produit=" + device.product + " | disponible=" + device.available)
        device.states.sortedBy { it.first }.forEach { appendLine(it.first + " = " + it.second) }
        if (device.commands.isNotEmpty()) appendLine("Commandes déclarées : " + device.commands.sorted().joinToString(", "))
        device.capabilities.sortedBy { it.id }.forEach { appendLine("capacité " + it.id + " = " + it.value) }
    }
}

internal const val HEATER_TYPE = "modbuslink:AtlanticDomesticHotWaterProductionMBLComponent"
internal fun Device.state(name: String): String? = states.firstOrNull { it.first == name }?.second
internal fun validCommand(device: Device, command: String, value: Any): Boolean {
    if (device.type != HEATER_TYPE || device.available != true || command !in device.commands) return false
    val normal = device.state("modbuslink:DHWAbsenceModeState") == "off" && device.state("modbuslink:DHWBoostModeState") == "off"
    return when(command) {
        "setDHWMode" -> normal && value in setOf("autoMode", "manualEcoActive", "manualEcoInactive")
        "setBoostMode" -> device.state("modbuslink:DHWAbsenceModeState") == "off" && value in setOf("on", "off")
        "setExpectedNumberOfShower", "setTargetDHWTemperature" -> {
            if (!normal || device.state("modbuslink:DHWModeState") != "manualEcoInactive" || value !is Int) return false
            val kind = if (command == "setExpectedNumberOfShower") "Shower" else "Temperature"
            val min = device.state("core:Minimal"+kind+"ManualModeState")?.toDoubleOrNull() ?: return false
            val max = device.state("core:Maximal"+kind+"ManualModeState")?.toDoubleOrNull() ?: return false
            value.toDouble() in min..max
        }
        else -> false
    }
}

internal fun historyKey(url: String): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun commandMatches(device: Device, command: String, value: Any): Boolean {
    val state = when (command) {
        "setDHWMode" -> "modbuslink:DHWModeState"
        "setBoostMode" -> "modbuslink:DHWBoostModeState"
        "setExpectedNumberOfShower" -> "core:ExpectedNumberOfShowerState"
        "setTargetDHWTemperature" -> "core:TargetDHWTemperatureState"
        else -> return false
    }
    val actual = device.state(state) ?: return false
    return if (value is Number) actual.toDoubleOrNull() == value.toDouble() else actual == value.toString()
}
