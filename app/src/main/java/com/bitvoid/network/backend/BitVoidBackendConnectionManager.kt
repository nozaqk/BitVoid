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
import kotlinx.coroutines.delay
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

    fun load(context: Context): BitVoidBackendConfig {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val local = normalizeConfiguredUrl(
            prefs.getString(KEY_LOCAL_URL, DEFAULT_LOCAL_URL).orEmpty(),
            allowCleartext = true
        ) ?: DEFAULT_LOCAL_URL
        val buildCloud = BuildConfig.BITVOID_CLOUD_BASE_URL.trim()
        val cloud = normalizeConfiguredUrl(
            prefs.getString(KEY_CLOUD_URL, buildCloud).orEmpty(),
            allowCleartext = false
        ).orEmpty()
        return BitVoidBackendConfig(localServerUrl = local, cloudServerUrl = cloud)
    }

    fun save(context: Context, localServerUrl: String, cloudServerUrl: String): Result<BitVoidBackendConfig> {
        val local = normalizeConfiguredUrl(localServerUrl, allowCleartext = true)
            ?: return Result.failure(
                IllegalArgumentException("Enter a valid campus URL, such as http://bitvoid.local.")
            )
        val cleanCloud = cloudServerUrl.trim()
        val cloud = if (cleanCloud.isEmpty()) {
            ""
        } else {
            normalizeConfiguredUrl(cleanCloud, allowCleartext = false)
                ?: return Result.failure(
                    IllegalArgumentException("The cloud backend must use a valid HTTPS URL.")
                )
        }
        val config = BitVoidBackendConfig(localServerUrl = local, cloudServerUrl = cloud)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOCAL_URL, local)
            .putString(KEY_CLOUD_URL, cloud)
            .apply()
        return Result.success(config)
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
 * Selects the campus backend first, then the configured Render/cloud backend, without blocking UI.
 * A network callback wakes discovery immediately after Wi-Fi/mobile changes. Connected backends are
 * health-checked periodically; failures use capped exponential backoff and never show a setup gate.
 */
class BitVoidBackendConnectionManager(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val refreshSignal = Channel<Unit>(Channel.CONFLATED)
    private val started = AtomicBoolean(false)
    private var monitorJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
                            "Finding campus or cloud…"
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

                val hasInternet = hasInternetCapability()
                val cloudHealthy = currentConfig.cloudServerUrl.isNotBlank() &&
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
                            "Connected to campus server"
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
                        statusLabel = if (currentConfig.cloudServerUrl.isBlank()) {
                            "Offline · cloud URL is not configured"
                        } else {
                            "Offline · messages will send when a server returns"
                        },
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

    fun updateConfig(localServerUrl: String, cloudServerUrl: String): Result<BitVoidBackendConfig> {
        return BitVoidBackendPrefs.save(appContext, localServerUrl, cloudServerUrl)
            .onSuccess {
                _config.value = it
                requestRefresh()
            }
    }

    fun requestRefresh() {
        refreshSignal.trySend(Unit)
    }

    fun close() {
        monitorJob?.cancel()
        monitorJob = null
        networkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
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
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = requestRefresh()
            override fun onLost(network: Network) = requestRefresh()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                requestRefresh()
        }
        networkCallback = callback
        runCatching {
            connectivityManager.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback
            )
        }.onFailure {
            runCatching { connectivityManager.registerDefaultNetworkCallback(callback) }
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
                    connection.connectTimeout = if (requireBitVoidIdentity) 900 else 3_500
                    connection.readTimeout = if (requireBitVoidIdentity) 1_100 else 4_500
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
