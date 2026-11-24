package com.bitvoid.network.model

/**
 * Stable local-server protocol metadata retained for backward-compatible node responses.
 * The Android client does not create Wi-Fi networks or persist broadcast credentials.
 */
object BitVoidNetworkConfig {
    const val DEFAULT_VISIBLE_SSID = "BitVoid_Net"
    const val DEFAULT_WPA2_PASSPHRASE = "BitVoid2026"
    const val ENFORCED_VISIBLE_SSID = false
    const val WPA2_SECURITY_LABEL = "SECURITY: WPA2-PSK (VISIBLE SSID)"
    const val LOCAL_GATEWAY_HOSTNAME = "bitvoid.local"
}
