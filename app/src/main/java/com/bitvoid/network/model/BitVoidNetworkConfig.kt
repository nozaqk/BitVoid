package com.bitvoid.network.model

import android.content.Context

/**
 * Immutable password-protected host Wi-Fi configuration.
 * BitVoid only supports a visible WPA2 network and rejects hidden SSIDs.
 */
data class BitVoidNetworkConfig(
    val ssid: String = DEFAULT_VISIBLE_SSID,
    val passphrase: String = DEFAULT_WPA2_PASSPHRASE,
    val isHiddenSsid: Boolean = ENFORCED_VISIBLE_SSID
) {
    init {
        require(!isHiddenSsid) {
            "BitVoid forbids hidden SSIDs; isHiddenSsid must always be false."
        }
        require(passphrase.length in 8..63) {
            "BitVoid Wi-Fi passphrase must be between 8 and 63 characters."
        }
    }

    companion object {
        const val DEFAULT_VISIBLE_SSID = "BitVoid_Net"
        const val DEFAULT_WPA2_PASSPHRASE = "BitVoid2026"
        const val ENFORCED_VISIBLE_SSID = false
        const val WPA2_SECURITY_LABEL = "SECURITY: WPA2-PSK (VISIBLE SSID)"
        const val LOCAL_GATEWAY_HOSTNAME = "bitvoid.local"

        private val DIRECT_PREFIX_REGEX = Regex("^DIRECT-[0-9A-Za-z]{2}.*")

        /**
         * Formats a user-configured SSID for Android's WifiP2pConfig.Builder.setNetworkName(),
         * which requires a "DIRECT-xy" prefix (2 alphanumeric chars) and 9..32 UTF-8 bytes.
         */
        fun formatWifiDirectNetworkName(rawSsid: String): String {
            val clean = rawSsid.trim()
                .filter { !it.isISOControl() }
                .ifEmpty { DEFAULT_VISIBLE_SSID }
            val candidate = if (DIRECT_PREFIX_REGEX.matches(clean)) {
                clean
            } else {
                "DIRECT-BV-$clean"
            }
            return truncateUtf8Bytes(candidate, 32)
        }

        private fun truncateUtf8Bytes(value: String, maxBytes: Int): String {
            val sb = StringBuilder()
            var used = 0
            for (ch in value) {
                val charBytes = ch.toString().toByteArray(Charsets.UTF_8).size
                if (used + charBytes > maxBytes) break
                sb.append(ch)
                used += charBytes
            }
            return sb.toString()
        }
    }
}

object BitVoidNetworkPrefs {
    private const val PREFS_NAME = "bitvoid_network_prefs"
    private const val KEY_SSID = "broadcast_ssid"
    private const val KEY_PASSPHRASE = "broadcast_passphrase"
    private const val LEGACY_KEY_SECURITY_MODE = "security_mode"

    fun load(context: Context): BitVoidNetworkConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawSsid = prefs.getString(KEY_SSID, null)?.trim()
        val rawPass = prefs.getString(KEY_PASSPHRASE, null)?.trim()
        val passphrase = rawPass
            ?.takeIf { it.length in 8..63 }
            ?: BitVoidNetworkConfig.DEFAULT_WPA2_PASSPHRASE
        // Remove the legacy dual-mode preference so obsolete security choices cannot return.
        if (prefs.contains(LEGACY_KEY_SECURITY_MODE)) {
            prefs.edit().remove(LEGACY_KEY_SECURITY_MODE).apply()
        }
        return BitVoidNetworkConfig(
            ssid = rawSsid?.takeIf { it.isNotEmpty() } ?: BitVoidNetworkConfig.DEFAULT_VISIBLE_SSID,
            passphrase = passphrase,
            isHiddenSsid = BitVoidNetworkConfig.ENFORCED_VISIBLE_SSID
        )
    }

    fun saveBroadcastCredentials(context: Context, ssid: String, passphrase: String) {
        val cleanSsid = ssid.trim().ifEmpty { BitVoidNetworkConfig.DEFAULT_VISIBLE_SSID }
        val cleanPass = passphrase.trim().ifEmpty { BitVoidNetworkConfig.DEFAULT_WPA2_PASSPHRASE }
        require(cleanPass.length in 8..63) {
            "BitVoid Wi-Fi passphrase must be between 8 and 63 characters."
        }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SSID, cleanSsid)
            .putString(KEY_PASSPHRASE, cleanPass)
            .remove(LEGACY_KEY_SECURITY_MODE)
            .apply()
    }
}
