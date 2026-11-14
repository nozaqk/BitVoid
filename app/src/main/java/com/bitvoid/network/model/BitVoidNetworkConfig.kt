package com.bitvoid.network.model

import android.content.Context

enum class HotspotSecurityMode(
    val title: String,
    val description: String,
    val badgeLabel: String
) {
    OPEN_VISIBLE(
        title = "Open Network (Visible, No Password)",
        description = "Anyone nearby sees the network name and joins with zero password.",
        badgeLabel = "SECURITY: OPEN (NO PASSWORD REQUIRED)"
    ),
    WPA2_VISIBLE(
        title = "WPA2 Network (Visible, Password Protected)",
        description = "Anyone nearby sees the network name and enters the configured WPA2 passphrase.",
        badgeLabel = "SECURITY: WPA2-PSK (VISIBLE SSID)"
    )
}

/**
 * Immutable host Wi-Fi network configuration.
 * Explicitly enforces [isHiddenSsid] = false so BitVoid SSIDs are always visible and discoverable.
 */
data class BitVoidNetworkConfig(
    val securityMode: HotspotSecurityMode = HotspotSecurityMode.OPEN_VISIBLE,
    val ssid: String = DEFAULT_VISIBLE_SSID,
    val passphrase: String = DEFAULT_WPA2_PASSPHRASE,
    val isHiddenSsid: Boolean = ENFORCED_VISIBLE_SSID
) {
    init {
        require(!isHiddenSsid) {
            "BitVoid forbids hidden SSIDs; isHiddenSsid must always be false."
        }
    }

    fun withSecurityMode(mode: HotspotSecurityMode): BitVoidNetworkConfig {
        return copy(
            securityMode = mode,
            isHiddenSsid = ENFORCED_VISIBLE_SSID
        )
    }

    companion object {
        const val DEFAULT_VISIBLE_SSID = "BitVoid_Net"
        const val DEFAULT_WPA2_PASSPHRASE = "BitVoid2026"
        const val ENFORCED_VISIBLE_SSID = false

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
    private const val KEY_SECURITY_MODE = "security_mode"

    fun load(context: Context): BitVoidNetworkConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawSsid = prefs.getString(KEY_SSID, null)?.trim()
        val rawPass = prefs.getString(KEY_PASSPHRASE, null)
        val rawMode = prefs.getString(KEY_SECURITY_MODE, null)
        val mode = rawMode?.let { runCatching { HotspotSecurityMode.valueOf(it) }.getOrNull() }
            ?: HotspotSecurityMode.OPEN_VISIBLE
        return BitVoidNetworkConfig(
            securityMode = mode,
            ssid = rawSsid?.takeIf { it.isNotEmpty() } ?: BitVoidNetworkConfig.DEFAULT_VISIBLE_SSID,
            passphrase = rawPass?.takeIf { it.isNotBlank() } ?: BitVoidNetworkConfig.DEFAULT_WPA2_PASSPHRASE,
            isHiddenSsid = BitVoidNetworkConfig.ENFORCED_VISIBLE_SSID
        )
    }

    fun saveBroadcastCredentials(context: Context, ssid: String, passphrase: String) {
        val cleanSsid = ssid.trim().ifEmpty { BitVoidNetworkConfig.DEFAULT_VISIBLE_SSID }
        val cleanPass = passphrase.trim().ifEmpty { BitVoidNetworkConfig.DEFAULT_WPA2_PASSPHRASE }
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SSID, cleanSsid)
            .putString(KEY_PASSPHRASE, cleanPass)
            .apply()
    }

    fun saveSecurityMode(context: Context, mode: HotspotSecurityMode) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SECURITY_MODE, mode.name)
            .apply()
    }
}
