package fr.nesy.guelmacontrol

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

data class Capability(val id: Int, val value: String)
data class Device(val model: Int, val product: Int, val available: Boolean?, val capabilities: List<Capability>)
internal class ApiException(message: String): Exception(message)

internal class CozytouchApi {
    private val base = "https://apis.groupe-atlantic.com"
    // Publicly documented mobile-client identifier, reference: gduteil/cozytouch.
    private val client = "Q3RfMUpWeVRtSUxYOEllZkE3YVVOQmpGblpVYToyRWNORHpfZHkzNDJVSnFvMlo3cFNKTnZVdjBh"
    private var token: String? = null
    private var expiresAt = 0L
    private var blockedUntil = 0L

    fun logout() { token = null; expiresAt = 0L }
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
    fun readDevices(): List<Device> {
        val access = token ?: throw ApiException("Reconnectez-vous.")
        if (SystemClock.elapsedRealtime() >= expiresAt) { logout(); throw ApiException("Session expirée. Reconnectez-vous.") }
        val setup = JSONArray(request("/magellan/cozytouch/setupviewv2", "Bearer " + access))
        return buildList {
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
    }

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun request(path: String, auth: String, form: String? = null): String {
        if (SystemClock.elapsedRealtime() < blockedUntil) throw ApiException("Limite de requêtes atteinte. Réessayez plus tard.")
        val connection = URL(base + path).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20000
            connection.readTimeout = 20000
            connection.setRequestProperty("Authorization", auth)
            connection.setRequestProperty("Accept", "application/json")
            if (form != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            if (status == 429) {
                val delay = connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 300
                blockedUntil = SystemClock.elapsedRealtime() + delay.coerceIn(60, 86400) * 1000
                throw ApiException("Limite de requêtes atteinte. Réessayez plus tard.")
            }
            if (status == 401) { logout(); throw ApiException("Authentification refusée ou session expirée (401).") }
            if (status == 400 && form != null) throw ApiException("Connexion refusée (400). Vérifiez les identifiants et la compatibilité.")
            if (status !in 200..299) throw ApiException("Réponse Cozytouch : HTTP " + status)
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { connection.disconnect() }
    }
}
internal fun diagnosticValue(value: Any?): String {
    val raw = value?.toString() ?: return "non renseigné"
    if (raw == "null") return "non renseigné"
    if (raw == "true" || raw == "false") return raw
    if (raw.length <= 14 && Regex("^-?[0-9]+(\\.[0-9]+)?$").matches(raw)) return raw
    return "masqué (texte ou structure)"
}
internal fun diagnosticReport(devices: List<Device>, demo: Boolean): String = buildString {
    appendLine("Guelma Control 0.1 — " + if (demo) "DÉMO" else "diagnostic")
    devices.forEachIndexed { index, device ->
        appendLine("Appareil " + (index + 1) + " | modèle=" + device.model + " | produit=" + device.product + " | disponible=" + device.available)
        device.capabilities.sortedBy { it.id }.forEach { appendLine("capacité " + it.id + " = " + it.value) }
    }
}
