package com.bitvoid.network.auth

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class BitVoidAuthResult(
    val username: String,
    val token: String,
    val masterPassphrase: String? = null
)

class BitVoidAccountAuth(private val context: Context) {
    private val client = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun register(baseUrl: String, username: String, password: String): BitVoidAuthResult =
        request(baseUrl, "/api/v1/auth/register", JSONObject()
            .put("username", username).put("password", password), false)

    fun login(baseUrl: String, username: String, password: String): BitVoidAuthResult =
        request(baseUrl, "/api/v1/auth/login", JSONObject()
            .put("username", username).put("password", password), false)

    fun recover(baseUrl: String, masterPassphrase: String, newPassword: String): BitVoidAuthResult =
        request(baseUrl, "/api/v1/auth/recover", JSONObject()
            .put("masterPassphrase", masterPassphrase).put("newPassword", newPassword), false)

    private fun request(baseUrl: String, path: String, payload: JSONObject, authenticated: Boolean): BitVoidAuthResult {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val data = runCatching { JSONObject(body) }.getOrNull()
            if (!response.isSuccessful) {
                throw IllegalStateException(data?.optString("message")
                    ?.takeIf { it.isNotBlank() && it != "null" }
                    ?: "Account request failed (HTTP ${response.code}).")
            }
            val account = data?.optJSONObject("account")
                ?: throw IllegalStateException("The server returned an invalid account response.")
            val username = account.optString("username")
            val token = data.optString("token")
            if (username.isBlank() || token.isBlank()) {
                throw IllegalStateException("The server response is missing the account session.")
            }
            return BitVoidAuthResult(
                username = username,
                token = token,
                masterPassphrase = data.optString("masterPassphrase").takeIf { it.matches(Regex("[0-9a-fA-F]{44}")) }
            )
        }
    }

    fun saveSession(username: String, token: String, master: String?) {
        val prefs = context.getSharedPreferences("bitvoid_secure_account", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("username_enc", encrypt(username))
            .putString("token_enc", encrypt(token))
            .apply {
                if (!master.isNullOrBlank()) putString("master_enc", encrypt(master))
            }.apply()
    }

    fun savedUsername(): String? = decrypt(context.getSharedPreferences("bitvoid_secure_account", 0).getString("username_enc", null))
    fun savedToken(): String? = decrypt(context.getSharedPreferences("bitvoid_secure_account", 0).getString("token_enc", null))
    fun savedMasterPassphrase(): String? = decrypt(context.getSharedPreferences("bitvoid_secure_account", 0).getString("master_enc", null))

    fun clearSession() {
        context.getSharedPreferences("bitvoid_secure_account", 0).edit().remove("token_enc").apply()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String? = runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val iv = bytes.copyOfRange(0, 12)
        val encrypted = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }

    companion object { private const val KEY_ALIAS = "bitvoid_account_storage_v1" }
}
