package com.bitvoid.network.backend

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.bitvoid.network.BuildConfig
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

enum class BitVoidBackendType {
    CAMPUS,
    CLOUD,
    OFFLINE
}

enum class BitVoidBackendPhase {
    DISCOVERING,
    CONNECTED,
    RECONNECTING,
    OFFLINE
}

data class BitVoidBackendConfig(
    val localServerUrl: String,
    val cloudServerUrl: String
)

data class BitVoidBackendState(
    val type: BitVoidBackendType = BitVoidBackendType.OFFLINE,
    val phase: BitVoidBackendPhase = BitVoidBackendPhase.DISCOVERING,
    val baseUrl: String? = null,
    val statusLabel: String = "Finding the best BitVoid server…",
    val retryAttempt: Int = 0,
    val lastCheckedAtMs: Long = 0L
) {
    val isConnected: Boolean
        get() = phase == BitVoidBackendPhase.CONNECTED && !baseUrl.isNullOrBlank()
}

object BitVoidBackendPrefs {
    private const val PREFS_NAME = "bitvoid_backend_preferences"
    private const val KEY_LOCAL_URL = "campus_server_url"
    private const val KEY_CLOUD_URL = "cloud_server_url"

    const val DEFAULT_LOCAL_URL = "http://bitvoid.local"
    const val DEFAULT_CLOUD_URL = "https://bitvoid-cloud-api.onrender.com"

    fun load(context: Context): BitVoidBackendConfig {
        // Remove endpoint overrides written by older app versions. Backend selection is automatic:
        // local Wi-Fi discovery first, then the production cloud URL compiled into this build.
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LOCAL_URL)
            .remove(KEY_CLOUD_URL)
            .apply()
        return BitVoidBackendConfig(
            localServerUrl = DEFAULT_LOCAL_URL,
            cloudServerUrl = BuildConfig.BITVOID_CLOUD_BASE_URL
                .trim()
                .ifBlank { DEFAULT_CLOUD_URL }
        )
    }

    fun normalizeConfiguredUrl(raw: String, allowCleartext: Boolean): String? {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val candidate = if ("://" in trimmed) trimmed else {
            if (allowCleartext) "http://$trimmed" else "https://$trimmed"
        }
        return try {
            val uri = URI(candidate)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
            if (scheme != "https" && !(allowCleartext && scheme == "http")) return null
            if (uri.userInfo != null || uri.fragment != null || uri.query != null) return null
            if (uri.port !in -1..65535 || uri.port == 0) return null
            val port = if (uri.port > 0) ":${uri.port}" else ""
            val path = uri.path.orEmpty().trimEnd('/').takeIf { it.isNotBlank() && it != "/" }.orEmpty()
            "$scheme://$host$port$path"
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Selects a BitVoid backend on the current local Wi-Fi first, then the fixed Render cloud backend.
 * A network callback wakes discovery immediately after Wi-Fi/mobile changes. Connected backends are
 * health-checked periodically; failures use capped exponential backoff while the UI shows a
 * full-screen connection gate.
 */
class BitVoidBackendConnectionManager(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val refreshSignal = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)
    private var monitorJob: Job? = null
    private val networkCallbacks = mutableListOf<ConnectivityManager.NetworkCallback>()

    private val _config = MutableStateFlow(BitVoidBackendPrefs.load(appContext))
    val config: StateFlow<BitVoidBackendConfig> = _config.asStateFlow()

    private val _state = MutableStateFlow(BitVoidBackendState())
    val state: StateFlow<BitVoidBackendState> = _state.asStateFlow()

    fun start(
        scope: CoroutineScope,
        discoverCampusBackend: suspend (configuredLocalUrl: String) -> String?,
        onBackendSelected: suspend (BitVoidBackendType, String?) -> Unit
    ) {
        if (!started.compareAndSet(false, true)) return
        registerNetworkCallback()
        monitorJob = scope.launch {
            var attempt = 0
            var firstPass = true
            while (isActive) {
                if (!_state.value.isConnected) {
                    _state.value = _state.value.copy(
                        phase = if (firstPass) {
                            BitVoidBackendPhase.DISCOVERING
                        } else {
                            BitVoidBackendPhase.RECONNECTING
                        },
                        statusLabel = if (firstPass) {
                            "Checking local Wi-Fi, then cloud…"
                        } else {
                            "Reconnecting in the background…"
                        },
                        retryAttempt = attempt
                    )
                }

                val currentConfig = _config.value
                val localUrl = runCatching {
                    discoverCampusBackend(currentConfig.localServerUrl)
                }.getOrNull()?.trimEnd('/')

                // Do not even probe the cloud until local Wi-Fi discovery has failed. Besides
                // preserving local-first semantics, this avoids delaying a healthy LAN selection
                // while a sleeping Render instance wakes up.
                val shouldTryCloud = localUrl.isNullOrBlank()
                val hasInternet = shouldTryCloud && hasInternetCapability()
                val cloudHealthy = shouldTryCloud &&
                    currentConfig.cloudServerUrl.isNotBlank() &&
                    hasInternet &&
                    probeHealth(currentConfig.cloudServerUrl, requireBitVoidIdentity = false)
                val selection = selectBackend(
                    campusUrl = localUrl,
                    cloudUrl = currentConfig.cloudServerUrl,
                    hasInternet = hasInternet,
                    cloudHealthy = cloudHealthy
                )

                val now = System.currentTimeMillis()
                if (selection.first != BitVoidBackendType.OFFLINE) {
                    attempt = 0
                    _state.value = BitVoidBackendState(
                        type = selection.first,
                        phase = BitVoidBackendPhase.CONNECTED,
                        baseUrl = selection.second,
                        statusLabel = if (selection.first == BitVoidBackendType.CAMPUS) {
                            "Connected through local Wi-Fi"
                        } else {
                            "Connected to cloud"
                        },
                        lastCheckedAtMs = now
                    )
                    onBackendSelected(selection.first, selection.second)
                    firstPass = false
                    waitForRefreshOrTimeout(CONNECTED_HEALTH_INTERVAL_MS)
                } else {
                    attempt++
                    _state.value = BitVoidBackendState(
                        type = BitVoidBackendType.OFFLINE,
                        phase = BitVoidBackendPhase.OFFLINE,
                        baseUrl = null,
                        statusLabel = "Connection lost · local Wi-Fi and cloud are unavailable",
                        retryAttempt = attempt,
                        lastCheckedAtMs = now
                    )
                    onBackendSelected(BitVoidBackendType.OFFLINE, null)
                    firstPass = false
                    waitForRefreshOrTimeout(retryDelayMs(attempt))
                }
            }
        }
    }

    fun requestRefresh() {
        refreshSignal.trySend(Unit)
    }

    fun close() {
        monitorJob?.cancel()
        monitorJob = null
        networkCallbacks.forEach { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        networkCallbacks.clear()
        refreshSignal.close()
    }

    private suspend fun waitForRefreshOrTimeout(timeoutMs: Long) {
        kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { refreshSignal.receive() }
    }

    private fun hasInternetCapability(): Boolean {
        val active = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(active) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerNetworkCallback() {
        fun callback() = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = requestRefresh()
            override fun onLost(network: Network) = requestRefresh()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                requestRefresh()
        }

        // The default callback catches mobile/internet changes. A separate Wi-Fi request also
        // catches local-only Wi-Fi, which deliberately has no INTERNET capability.
        val defaultCallback = callback()
        runCatching {
            connectivityManager.registerDefaultNetworkCallback(defaultCallback)
            networkCallbacks += defaultCallback
        }
        val wifiCallback = callback()
        runCatching {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                wifiCallback
            )
            networkCallbacks += wifiCallback
        }
    }

    private suspend fun probeHealth(baseUrl: String, requireBitVoidIdentity: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val endpoints = listOf("/api/node", "/health")
            endpoints.any { path ->
                var connection: HttpURLConnection? = null
                try {
                    connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = if (requireBitVoidIdentity) 900 else 8_000
                    connection.readTimeout = if (requireBitVoidIdentity) 1_100 else 30_000
                    connection.setRequestProperty("Accept", "application/json")
                    connection.setRequestProperty("User-Agent", "BitVoid-Android/2.0")
                    val healthy = connection.responseCode in 200..299
                    healthy && (!requireBitVoidIdentity ||
                        connection.getHeaderField("X-BitVoid-Node") == "1")
                } catch (_: Exception) {
                    false
                } finally {
                    connection?.disconnect()
                }
            }
        }

    companion object {
        private const val CONNECTED_HEALTH_INTERVAL_MS = 15_000L
        private const val MAX_RETRY_DELAY_MS = 60_000L

        fun retryDelayMs(attempt: Int): Long {
            val exponent = (attempt - 1).coerceIn(0, 6)
            return (2_000L shl exponent).coerceAtMost(MAX_RETRY_DELAY_MS)
        }

        fun selectBackend(
            campusUrl: String?,
            cloudUrl: String,
            hasInternet: Boolean,
            cloudHealthy: Boolean
        ): Pair<BitVoidBackendType, String?> = when {
            !campusUrl.isNullOrBlank() -> BitVoidBackendType.CAMPUS to campusUrl.trimEnd('/')
            cloudUrl.isNotBlank() && hasInternet && cloudHealthy ->
                BitVoidBackendType.CLOUD to cloudUrl.trimEnd('/')
            else -> BitVoidBackendType.OFFLINE to null
        }
    }
}
