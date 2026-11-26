package com.bitvoid.network.backend

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume

data class BitVoidLocalNetworkState(
    val wifiConnected: Boolean = false,
    val internetAvailable: Boolean = false,
    val pcServerOnline: Boolean = false,
    val pcServerUrl: String? = null,
    val status: String = "Waiting for local Wi-Fi"
)

/** Discovers the standalone BitVoid application Server on the current Wi-Fi network. */
class BitVoidLocalDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _state = MutableStateFlow(BitVoidLocalNetworkState())
    val state: StateFlow<BitVoidLocalNetworkState> = _state.asStateFlow()

    suspend fun discover(configuredUrl: String): String? = withContext(Dispatchers.IO) {
        val network = currentWifiNetwork()
        if (network == null) {
            connectivityManager.bindProcessToNetwork(null)
            _state.value = BitVoidLocalNetworkState(status = "Wi-Fi is not connected")
            return@withContext null
        }
        val caps = connectivityManager.getNetworkCapabilities(network)
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val candidates = linkedSetOf<String>()
        BitVoidBackendPrefs.normalizeConfiguredUrl(configuredUrl, allowCleartext = true)
            ?.removeSuffix("/api/v1")
            ?.trimEnd('/')
            ?.takeIf(BitVoidBackendPrefs::isAllowedLocalServer)
            ?.let(candidates::add)
        candidates += BitVoidBackendPrefs.DEFAULT_LOCAL_URL
        resolveMdns(network)?.let(candidates::add)

        var selected = candidates.firstOrNull { candidate ->
            probeHealth(network, candidate, SERVER_HEALTH_PATHS)
        }
        if (selected == null) {
            discoverNsd()?.let { nsdUrl ->
                if (BitVoidBackendPrefs.isAllowedLocalServer(nsdUrl) &&
                    probeHealth(network, nsdUrl, SERVER_HEALTH_PATHS)
                ) {
                    selected = nsdUrl
                }
            }
        }

        if (selected != null) {
            // A local-only Wi-Fi may not be Android's default network. Pin app sockets to Wi-Fi so
            // REST and WebSocket traffic cannot silently escape through mobile data.
            connectivityManager.bindProcessToNetwork(network)
        }
        _state.value = BitVoidLocalNetworkState(
            wifiConnected = true,
            internetAvailable = internet,
            pcServerOnline = selected != null,
            pcServerUrl = selected,
            status = when {
                selected != null -> "Local server online"
                else -> "Wi-Fi connected · waiting for local Server"
            }
        )
        selected
    }

    fun release() {
        connectivityManager.bindProcessToNetwork(null)
    }

    private fun currentWifiNetwork(): Network? {
        val active = connectivityManager.activeNetwork
        if (active != null && connectivityManager.getNetworkCapabilities(active)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        ) return active
        return connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun resolveMdns(network: Network): String? = runCatching {
        val address = network.getAllByName(PC_MDNS_HOST)
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
            ?: return@runCatching null
        "http://${address.hostAddress}:$PC_SERVER_PORT"
    }.getOrNull()

    private fun probeHealth(network: Network, baseUrl: String, paths: List<String>): Boolean {
        return paths.any { path ->
            var connection: HttpURLConnection? = null
            try {
                connection = network.openConnection(URL(baseUrl.trimEnd('/') + path)) as HttpURLConnection
                connection.requestMethod = "GET"
                connection.instanceFollowRedirects = false
                connection.connectTimeout = PROBE_CONNECT_TIMEOUT_MS
                connection.readTimeout = PROBE_READ_TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/json")
                if (connection.responseCode !in 200..299) return@any false
                val body = connection.inputStream.bufferedReader().use { it.readText() }.trim()
                if (body.isBlank()) return@any true
                val json = runCatching { JSONObject(body) }.getOrNull() ?: return@any false
                json.optBoolean("ok", false) ||
                    json.optString("status").equals("ok", true) ||
                    json.optString("status").equals("ready", true) ||
                    json.optString("service").contains("bitvoid", true)
            } catch (_: Exception) {
                false
            } finally {
                connection?.disconnect()
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun discoverNsd(): String? = withTimeoutOrNull(NSD_TIMEOUT_MS) {
        suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            val resolving = AtomicBoolean(false)
            lateinit var listener: NsdManager.DiscoveryListener

            fun stop() = runCatching { nsdManager.stopServiceDiscovery(listener) }
            fun finish(value: String?) {
                if (finished.compareAndSet(false, true)) {
                    stop()
                    if (continuation.isActive) continuation.resume(value)
                }
            }

            listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit
                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
                override fun onDiscoveryStopped(serviceType: String) {
                    if (!finished.get()) finish(null)
                }
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = finish(null)
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = finish(null)

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (!serviceInfo.serviceName.contains("bitvoid", ignoreCase = true) ||
                        !resolving.compareAndSet(false, true)
                    ) return
                    runCatching {
                        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                resolving.set(false)
                            }

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                val address = info.host as? Inet4Address
                                val port = info.port.takeIf { it > 0 } ?: PC_SERVER_PORT
                                if (address != null && port == PC_SERVER_PORT) {
                                    finish("http://${address.hostAddress}:$port")
                                } else {
                                    resolving.set(false)
                                }
                            }
                        })
                    }.onFailure { resolving.set(false) }
                }
            }
            continuation.invokeOnCancellation { if (finished.compareAndSet(false, true)) stop() }
            runCatching {
                nsdManager.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure { finish(null) }
        }
    }

    companion object {
        const val PC_MDNS_HOST = "bitvoid.local"
        const val PC_SERVER_PORT = 8787
        private const val NSD_SERVICE_TYPE = "_http._tcp."
        private const val NSD_TIMEOUT_MS = 2_500L
        private const val PROBE_CONNECT_TIMEOUT_MS = 1_200
        private const val PROBE_READ_TIMEOUT_MS = 1_500
        private val SERVER_HEALTH_PATHS = listOf("/api/v1/health", "/health")
    }
}
