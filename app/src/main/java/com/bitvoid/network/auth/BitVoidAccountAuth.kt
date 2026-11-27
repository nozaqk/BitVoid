package com.bitvoid.network.auth

import android.content.Context
import android.os.Build
import android.util.Base64
import com.bitvoid.network.BuildConfig
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
    val masterPassphrase: String? = null,
    val refreshToken: String? = null,
    val deviceId: String? = null,
    val expiresAt: String? = null,
    val refreshExpiresAt: String? = null
)

/**
 * Username/password account client for BitVoid Server API v1.1.
 *
 * Access and refresh credentials are encrypted with an Android Keystore AES-GCM key.
 * The recovery key is retained only when explicitly returned during registration,
 * recovery-key rotation, or account recovery.
 */
class BitVoidAccountAuth(private val context: Context) {
    private val client = OkHttpClient()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun register(baseUrl: String, username: String, password: String): BitVoidAuthResult =
        request(baseUrl, "/api/v1/account/register", accountPayload(username, password))

    fun login(baseUrl: String, username: String, password: String): BitVoidAuthResult =
        request(baseUrl, "/api/v1/account/login", accountPayload(username, password))

    fun recover(baseUrl: String, masterPassphrase: String, newPassword: String): BitVoidAuthResult =
        request(
            baseUrl,
            "/api/v1/account/recover",
            JSONObject()
                .put("masterPassphrase", masterPassphrase)
                .put("newPassword", newPassword)
                .put("device", devicePayload())
        )

    /** Rotate the short-lived access token and refresh token pair. */
    fun refresh(baseUrl: String, refreshToken: String): BitVoidAuthResult {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/v1/auth/refresh")
            .post(JSONObject().put("refreshToken", refreshToken).toString().toRequestBody(jsonType))
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val root = runCatching { JSONObject(body) }.getOrNull()
                ?: throw IllegalStateException("The server returned an invalid refresh response.")
            if (!response.isSuccessful) {
                throw IllegalStateException(errorMessage(root, response.code))
            }
            val data = root.optJSONObject("data") ?: root
            val account = data.optJSONObject("account")
            val username = account?.optString("username")?.takeIf { it.isNotBlank() }
                ?: savedUsername()
                ?: throw IllegalStateException("The refresh response is missing the account username.")
            val token = data.optString("token")
            val nextRefreshToken = data.optString("refreshToken")
            if (token.isBlank() || nextRefreshToken.isBlank()) {
                throw IllegalStateException("The refresh response is missing rotated credentials.")
            }
            return BitVoidAuthResult(
                username = username,
                token = token,
                refreshToken = nextRefreshToken,
                deviceId = data.optString("deviceId").takeIf { it.isNotBlank() }
                    ?: savedDeviceId(),
                expiresAt = data.optString("expiresAt").takeIf { it.isNotBlank() },
                refreshExpiresAt = data.optString("refreshExpiresAt").takeIf { it.isNotBlank() }
            )
        }
    }

    fun refreshSavedSession(baseUrl: String): BitVoidAuthResult? {
        val refresh = savedRefreshToken() ?: return null
        return refresh(baseUrl, refresh).also(::saveSession)
    }

    private fun accountPayload(username: String, password: String): JSONObject =
        JSONObject()
            .put("username", username)
            .put("password", password)
            .put("device", devicePayload())

    private fun devicePayload(): JSONObject = JSONObject()
        .put("displayName", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
        .put("platform", "android")
        .put("appVersion", BuildConfig.VERSION_NAME)

    private fun request(baseUrl: String, path: String, payload: JSONObject): BitVoidAuthResult {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + path)
            .post(payload.toString().toRequestBody(jsonType))
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val responseJson = runCatching { JSONObject(body) }.getOrNull()
            if (!response.isSuccessful) {
                throw IllegalStateException(
                    responseJson?.let { errorMessage(it, response.code) }
                        ?: "Account request failed (HTTP ${response.code})."
                )
            }
            val root = responseJson
                ?: throw IllegalStateException("The server returned an invalid account response.")
            val data = root.optJSONObject("data") ?: root
            val account = data.optJSONObject("account")
                ?: throw IllegalStateException("The server returned an invalid account response.")
            val username = account.optString("username")
            val token = data.optString("token")
            if (username.isBlank() || token.isBlank()) {
                throw IllegalStateException("The server response is missing the account session.")
            }
            return BitVoidAuthResult(
                username = username,
                token = token,
                masterPassphrase = data.optString("masterPassphrase")
                    .takeIf { it.matches(Regex("[0-9a-fA-F]{44}")) },
                refreshToken = data.optString("refreshToken").takeIf { it.isNotBlank() },
                deviceId = data.optJSONObject("device")?.optString("id")
                    ?.takeIf { it.isNotBlank() },
                expiresAt = data.optString("expiresAt").takeIf { it.isNotBlank() },
                refreshExpiresAt = data.optString("refreshExpiresAt").takeIf { it.isNotBlank() }
            )
        }
    }

    private fun errorMessage(root: JSONObject, statusCode: Int): String {
        val error = root.optJSONObject("error")
        return error?.optString("message")?.takeIf { it.isNotBlank() && it != "null" }
            ?: root.optString("message").takeIf { it.isNotBlank() && it != "null" }
            ?: "Account request failed (HTTP $statusCode)."
    }

    fun saveSession(
        username: String,
        token: String,
        master: String?,
        refreshToken: String? = null,
        deviceId: String? = null
    ) {
        val prefs = context.getSharedPreferences("bitvoid_secure_account", Context.MODE_PRIVATE)
        val previousUsername = savedUsername()
        val editor = prefs.edit()
            .putString("username_enc", encrypt(username))
            .putString("token_enc", encrypt(token))
        if (previousUsername != null && !previousUsername.equals(username, ignoreCase = true)) {
            // Never carry one account's recovery secret into another account's local session.
            editor.remove("master_enc")
        }
        if (!master.isNullOrBlank()) editor.putString("master_enc", encrypt(master))
        if (!refreshToken.isNullOrBlank()) editor.putString("refresh_token_enc", encrypt(refreshToken))
        if (!deviceId.isNullOrBlank()) editor.putString("device_id_enc", encrypt(deviceId))
        editor.apply()
    }

    fun saveSession(result: BitVoidAuthResult) = saveSession(
        username = result.username,
        token = result.token,
        master = result.masterPassphrase,
        refreshToken = result.refreshToken,
        deviceId = result.deviceId
    )

    fun savedUsername(): String? =
        decrypt(context.getSharedPreferences("bitvoid_secure_account", 0).getString("username_enc", null))

    fun savedToken(): String? =
        decrypt(context.getSharedPreferences("bitvoid_secure_account", 0).getString("token_enc", null))

    fun savedRefreshToken(): String? =
        decrypt(context.getSharedPreferences("bitvoid_secure_account", 0).getString("refresh_token_enc", null))

    fun savedDeviceId(): String? =
        decrypt(context.getSharedPreferences("bitvoid_secure_account", 0).getString("device_id_enc", null))

    fun savedMasterPassphrase(): String? =
        decrypt(context.getSharedPreferences("bitvoid_secure_account", 0).getString("master_enc", null))

    fun clearSession() {
        // Logout removes every locally retained account secret, including refresh and recovery keys.
        context.getSharedPreferences("bitvoid_secure_account", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String? = runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 12)
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
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "bitvoid_account_storage_v1"
    }
}
