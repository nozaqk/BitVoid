package com.bitvoid.network.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Keystore-backed storage for the PC-issued device token and local profile. */
class BitVoidLocalCredentials(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun deviceId(): String {
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = "android-${UUID.randomUUID()}"
        prefs.edit().putString(KEY_DEVICE_ID, generated).commit()
        return generated
    }

    fun publicKeyBase64(): String {
        val store = keyStore()
        val existing = store.getCertificate(SIGNING_KEY_ALIAS)?.publicKey
        val publicKey = existing ?: run {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                "AndroidKeyStore"
            )
            generator.initialize(
                KeyGenParameterSpec.Builder(
                    SIGNING_KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generator.generateKeyPair().public
        }
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    fun saveDeviceToken(token: String, serverUrl: String) {
        require(token.isNotBlank()) { "Device token cannot be empty." }
        prefs.edit()
            .putString(KEY_TOKEN, encrypt(token))
            .putString(KEY_SERVER_URL, encrypt(serverUrl.trimEnd('/')))
            .commit()
    }

    fun deviceToken(): String? = decrypt(prefs.getString(KEY_TOKEN, null))

    fun authorizedServerUrl(): String? = decrypt(prefs.getString(KEY_SERVER_URL, null))

    fun saveUsername(username: String) {
        prefs.edit().putString(KEY_USERNAME, encrypt(username)).commit()
    }

    fun username(): String? = decrypt(prefs.getString(KEY_USERNAME, null))

    fun clearAuthorization() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_SERVER_URL).remove(KEY_USERNAME).commit()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        return Base64.encodeToString(
            cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
    }

    private fun decrypt(value: String?): String? = runCatching {
        val encoded = value?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > GCM_IV_SIZE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            encryptionKey(),
            GCMParameterSpec(128, bytes.copyOfRange(0, GCM_IV_SIZE))
        )
        String(cipher.doFinal(bytes.copyOfRange(GCM_IV_SIZE, bytes.size)), Charsets.UTF_8)
    }.getOrNull()

    private fun encryptionKey(): SecretKey {
        val store = keyStore()
        (store.getKey(ENCRYPTION_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ENCRYPTION_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        private const val PREFS_NAME = "bitvoid_local_credentials_v1"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_TOKEN = "device_token_enc"
        private const val KEY_SERVER_URL = "server_url_enc"
        private const val KEY_USERNAME = "username_enc"
        private const val ENCRYPTION_KEY_ALIAS = "bitvoid_local_credentials_aes_v1"
        private const val SIGNING_KEY_ALIAS = "bitvoid_local_device_ec_v1"
        private const val GCM_IV_SIZE = 12
    }
}
