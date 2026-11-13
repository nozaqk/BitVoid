package com.bitvoid.network

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Host-selectable Wi-Fi security. Hidden SSIDs are intentionally unsupported. */
enum class HotspotSecurityMode {
    OPEN_VISIBLE,
    WPA2_VISIBLE,
}

data class BitVoidNetworkConfigState(
    val securityMode: HotspotSecurityMode = HotspotSecurityMode.OPEN_VISIBLE,
) {
    val isHiddenSsid: Boolean = false
}

/** Process-wide, persisted pre-launch network selection. */
object BitVoidNetworkConfig {
    const val DEFAULT_SSID = "BitVoid_Net"

    private const val PREFERENCES_NAME = "bitvoid_network_config"
    private const val SECURITY_MODE_KEY = "hotspot_security_mode"
    private val lock = Any()
    private val _state = MutableStateFlow(BitVoidNetworkConfigState())
    val state: StateFlow<BitVoidNetworkConfigState> = _state.asStateFlow()

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) = synchronized(lock) {
        val applicationContext = context.applicationContext
        appContext = applicationContext
        val savedName = applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(SECURITY_MODE_KEY, null)
        val savedMode = savedName
            ?.let { runCatching { HotspotSecurityMode.valueOf(it) }.getOrNull() }
            ?: HotspotSecurityMode.OPEN_VISIBLE
        _state.value = BitVoidNetworkConfigState(
            securityMode = savedMode,
        )
        BitVoidForegroundService.updateIdleNetworkMode(savedMode)
    }

    fun setSecurityMode(mode: HotspotSecurityMode): Boolean = synchronized(lock) {
        if (BitVoidForegroundService.nodeState.value.isActive) return@synchronized false
        _state.value = BitVoidNetworkConfigState(
            securityMode = mode,
        )
        appContext?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(SECURITY_MODE_KEY, mode.name)
            ?.apply()
        BitVoidForegroundService.updateIdleNetworkMode(mode)
        true
    }
}
