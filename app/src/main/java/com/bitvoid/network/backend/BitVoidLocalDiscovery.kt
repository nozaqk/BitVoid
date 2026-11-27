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
    val serverId: String? = null,
    val serverName: String? = null,
    val serverVersion: String? = null,
    val tlsFingerprint256: String? = null,
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

        var selected: String? = null
        var identity: ServerIdentity? = null
        for (candidate in candidates) {
            if (!BitVoidBackendPrefs.isAllowedLocalServer(candidate)) continue
            if (!probeHealth(network, candidate, SERVER_HEALTH_PATHS)) continue
            val verified = fetchServerIdentity(network, candidate) ?: continue
            selected = candidate
            identity = verified
            break
        }
        if (selected == null) {
            discoverNsd(NSD_PRIMARY_SERVICE_TYPE)
                ?.takeIf(BitVoidBackendPrefs::isAllowedLocalServer)
                ?.let { nsdUrl ->
                    if (probeHealth(network, nsdUrl, SERVER_HEALTH_PATHS)) {
                        fetchServerIdentity(network, nsdUrl)?.let { verified ->
                            selected = nsdUrl
                            identity = verified
                        }
                    }
                }
        }
        if (selected == null) {
            discoverNsd(NSD_COMPAT_SERVICE_TYPE)
                ?.takeIf(BitVoidBackendPrefs::isAllowedLocalServer)
                ?.let { nsdUrl ->
                    if (probeHealth(network, nsdUrl, SERVER_HEALTH_PATHS)) {
                        fetchServerIdentity(network, nsdUrl)?.let { verified ->
                            selected = nsdUrl
                            identity = verified
                        }
                    }
                }
        }

        if (selected != null) {
            // A local-only Wi-Fi may not be Android's default network. Pin app sockets to Wi-Fi so
            // REST and WebSocket traffic cannot silently escape through mobile data.
            connectivityManager.bindProcessToNetwork(network)
        } else {
            connectivityManager.bindProcessToNetwork(null)
        }
        _state.value = BitVoidLocalNetworkState(
            wifiConnected = true,
            internetAvailable = internet,
            pcServerOnline = selected != null,
            pcServerUrl = selected,
            serverId = identity?.serverId,
            serverName = identity?.displayName,
            serverVersion = identity?.serverVersion,
            tlsFingerprint256 = identity?.tlsFingerprint256,
            status = when {
                selected != null -> "BitVoid Server found · API identity available"
                else -> "Wi-Fi connected · no compatible BitVoid Server found"
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

    private data class ServerIdentity(
        val serverId: String,
        val displayName: String?,
        val serverVersion: String?,
        val tlsFingerprint256: String?
    )

    /**
     * Discovery records are untrusted hints. Accept an endpoint only after its public identity
     * endpoint returns a stable Server ID and a compatible v1 API declaration. This is API
     * compatibility validation, not cryptographic trust or TLS certificate pinning.
     */
    private fun fetchServerIdentity(network: Network, baseUrl: String): ServerIdentity? {
        var connection: HttpURLConnection? = null
        return try {
            connection = network.openConnection(
                URL(baseUrl.trimEnd('/') + "/api/v1/server/info")
            ) as HttpURLConnection
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = PROBE_CONNECT_TIMEOUT_MS
            connection.readTimeout = PROBE_READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) return null
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val data = root.optJSONObject("data") ?: return null
            val serverId = data.optString("serverId").takeIf {
                it.matches(Regex("[A-Za-z0-9_-]{8,128}"))
            } ?: return null
            val apiVersion = data.optString("apiVersion")
            if (!apiVersion.startsWith("1.")) return null
            val tls = data.optJSONObject("tls")
            val fingerprint = tls?.optString("fingerprint256")
                ?.takeIf { it.matches(Regex("(?i)[0-9a-f]{64}")) }
            ServerIdentity(
                serverId = serverId,
                displayName = data.optString("displayName").takeIf { it.isNotBlank() },
                serverVersion = data.optString("serverVersion").takeIf { it.isNotBlank() },
                tlsFingerprint256 = fingerprint
            )
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

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
    private suspend fun discoverNsd(serviceType: String): String? = withTimeoutOrNull(NSD_TIMEOUT_MS) {
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
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure { finish(null) }
        }
    }

    companion object {
        const val PC_MDNS_HOST = "bitvoid-server.local"
        const val PC_SERVER_PORT = 8787
        private const val NSD_PRIMARY_SERVICE_TYPE = "_bitvoid-server._tcp."
        private const val NSD_COMPAT_SERVICE_TYPE = "_http._tcp."
        private const val NSD_TIMEOUT_MS = 2_500L
        private const val PROBE_CONNECT_TIMEOUT_MS = 1_200
        private const val PROBE_READ_TIMEOUT_MS = 1_500
        private val SERVER_HEALTH_PATHS = listOf("/api/v1/health", "/health")
    }
}
