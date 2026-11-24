package fr.nesy.guelmacontrol

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class CredentialVault(context: Context) {
    private val prefs = context.getSharedPreferences("cozytouch_encrypted", Context.MODE_PRIVATE)
    private val alias = "guelma.cozytouch.credentials.v1"

    fun hasCredentials() = prefs.contains("payload") && prefs.contains("iv")

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null) as? SecretKey
        if (existing != null) return existing
        check(create) { "Missing local key" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
            generateKey()
        }
    }

    fun save(email: String, password: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        val plain = JSONObject().put("email", email).put("password", password).toString().toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        check(prefs.edit()
            .putString("payload", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).commit())
    }

    fun load(): Pair<String, String>? {
        if (!hasCredentials()) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)))
            val plain = cipher.doFinal(Base64.decode(prefs.getString("payload", ""), Base64.NO_WRAP))
            try {
                val obj = JSONObject(String(plain, Charsets.UTF_8))
                obj.getString("email") to obj.getString("password")
            } finally { plain.fill(0) }
        } catch (_: Exception) { clear(); null }
    }

    fun clear() {
        prefs.edit().clear().commit()
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) } catch (_: Exception) {}
    }
}
