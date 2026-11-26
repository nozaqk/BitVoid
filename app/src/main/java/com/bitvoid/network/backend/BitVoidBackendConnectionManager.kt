package com.bitvoid.network.backend

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    /** Retained as an empty compatibility field for older UI code. Cloud is never selected. */
    val cloudServerUrl: String = ""
)

data class BitVoidBackendState(
    val type: BitVoidBackendType = BitVoidBackendType.OFFLINE,
    val phase: BitVoidBackendPhase = BitVoidBackendPhase.DISCOVERING,
    val baseUrl: String? = null,
    val statusLabel: String = "Finding the local BitVoid PC server…",
    val retryAttempt: Int = 0,
    val lastCheckedAtMs: Long = 0L
) {
    val isConnected: Boolean
        get() = type == BitVoidBackendType.CAMPUS &&
            phase == BitVoidBackendPhase.CONNECTED &&
            !baseUrl.isNullOrBlank()
}

object BitVoidBackendPrefs {
    private const val PREFS_NAME = "bitvoid_backend_preferences"
    private const val KEY_LOCAL_URL = "local_pc_server_url"
    private const val LEGACY_LOCAL_URL = "campus_server_url"
    private const val LEGACY_CLOUD_URL = "cloud_server_url"

    const val DEFAULT_LOCAL_URL = "http://bitvoid.local:8787"

    fun load(context: Context): BitVoidBackendConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LOCAL_URL, null)
            ?: prefs.getString(LEGACY_LOCAL_URL, null)
        val local = saved?.let { normalizeConfiguredUrl(it, allowCleartext = true) }
            ?: DEFAULT_LOCAL_URL
        prefs.edit()
            .putString(KEY_LOCAL_URL, local)
            .remove(LEGACY_LOCAL_URL)
            .remove(LEGACY_CLOUD_URL)
            .apply()
        return BitVoidBackendConfig(localServerUrl = local)
    }

    fun saveLocalServerUrl(context: Context, raw: String): String? {
        val normalized = normalizeConfiguredUrl(raw, allowCleartext = true)
            ?.removeSuffix("/api/v1")
            ?.removeSuffix("/api")
            ?.trimEnd('/')
            ?: return null
        if (!isAllowedLocalServer(normalized)) return null
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCAL_URL, normalized)
            .apply()
        return normalized
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
            val port = if (uri.port > 0) uri.port else 8787
            val rawPath = uri.path.orEmpty().trimEnd('/')
            val path = when (rawPath) {
                "", "/", "/api", "/api/v1" -> ""
                else -> return null
            }
            "$scheme://$host:$port$path"
        } catch (_: Exception) {
            null
        }
    }

    fun isAllowedLocalServer(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "http" && uri.scheme != "https") return false
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return false
        if (uri.port != 8787 || uri.path.orEmpty() !in setOf("", "/")) return false
        val host = uri.host?.lowercase().orEmpty()
        if (host == "bitvoid.local" || host == "localhost" || host == "127.0.0.1") return true
        val parts = host.split('.').mapNotNull(String::toIntOrNull)
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 ||
            (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 169 && parts[1] == 254)
    }
}

/**
 * Monitors only the local PC server. Internet capability is intentionally irrelevant: local-only
 * Wi-Fi wakes discovery and loss of internet never redirects requests to a hosted service.
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
                _state.value = _state.value.copy(
                    phase = if (firstPass) BitVoidBackendPhase.DISCOVERING
                    else BitVoidBackendPhase.RECONNECTING,
                    statusLabel = if (firstPass) "Discovering the local BitVoid PC server…"
                    else "Reconnecting to the local PC server…",
                    retryAttempt = attempt
                )

                val localUrl = runCatching {
                    discoverCampusBackend(_config.value.localServerUrl)
                }.getOrNull()?.trimEnd('/')
                val now = System.currentTimeMillis()
                if (!localUrl.isNullOrBlank()) {
                    attempt = 0
                    _state.value = BitVoidBackendState(
                        type = BitVoidBackendType.CAMPUS,
                        phase = BitVoidBackendPhase.CONNECTED,
                        baseUrl = localUrl,
                        statusLabel = "Connected to local PC server",
                        lastCheckedAtMs = now
                    )
                    onBackendSelected(BitVoidBackendType.CAMPUS, localUrl)
                    firstPass = false
                    waitForRefreshOrTimeout(CONNECTED_HEALTH_INTERVAL_MS)
                } else {
                    attempt++
                    _state.value = BitVoidBackendState(
                        type = BitVoidBackendType.OFFLINE,
                        phase = BitVoidBackendPhase.OFFLINE,
                        baseUrl = null,
                        statusLabel = "Local PC server unavailable · retrying on Wi-Fi",
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

    fun setLocalServerUrl(raw: String): Boolean {
        val normalized = BitVoidBackendPrefs.saveLocalServerUrl(appContext, raw) ?: return false
        _config.value = BitVoidBackendConfig(localServerUrl = normalized)
        requestRefresh()
        return true
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

    private fun registerNetworkCallback() {
        fun callback() = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = requestRefresh()
            override fun onLost(network: Network) = requestRefresh()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                requestRefresh()
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

    companion object {
        private const val CONNECTED_HEALTH_INTERVAL_MS = 10_000L
        private const val MAX_RETRY_DELAY_MS = 60_000L

        fun retryDelayMs(attempt: Int): Long {
            val exponent = (attempt - 1).coerceIn(0, 5)
            return (2_000L shl exponent).coerceAtMost(MAX_RETRY_DELAY_MS)
        }

        fun selectBackend(
            campusUrl: String?,
            cloudUrl: String = "",
            hasInternet: Boolean = false,
            cloudHealthy: Boolean = false
        ): Pair<BitVoidBackendType, String?> = if (!campusUrl.isNullOrBlank()) {
            BitVoidBackendType.CAMPUS to campusUrl.trimEnd('/')
        } else {
            BitVoidBackendType.OFFLINE to null
        }
    }
}
