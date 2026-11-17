package com.bitvoid.network.connection

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class BitVoidConnectionPhase {
    STOPPED,
    WAITING_FOR_WIFI,
    BINDING,
    DISCOVERING,
    READY,
    ERROR
}

enum class BitVoidDiscoverySource {
    ANDROID_MDNS,
    MULTICAST_DNS,
    NSD_HTTP_SERVICE,
    CAPTIVE_PORTAL_REDIRECT,
    GATEWAY_FALLBACK
}

data class BitVoidNodeEndpoint(
    val hostname: String = BitVoidConnectionManager.NODE_HOSTNAME,
    val address: Inet4Address,
    val port: Int = BitVoidConnectionManager.NODE_PORT,
    val source: BitVoidDiscoverySource
) {
    val portalUrl: String
        get() = if (port == 80) "http://$hostname" else "http://$hostname:$port"

    /** Direct transport URL for clients that cannot resolve multicast DNS themselves. */
    val directUrl: String
        get() = if (port == 80) "http://${address.hostAddress}" else "http://${address.hostAddress}:$port"

    val webSocketBaseUrl: String
        get() = "ws://$hostname:$port"
}

data class BitVoidConnectionState(
    val phase: BitVoidConnectionPhase = BitVoidConnectionPhase.STOPPED,
    val wifiSsid: String? = null,
    val isExpectedNodeWifi: Boolean = false,
    val isProcessBound: Boolean = false,
    val networkHandle: Long? = null,
    val gatewayAddress: String? = null,
    val endpoint: BitVoidNodeEndpoint? = null,
    val captivePortalRedirectUrl: String? = null,
    val statusMessage: String = "ESP node integration is stopped.",
    val errorMessage: String? = null
) {
    val isReady: Boolean
        get() = phase == BitVoidConnectionPhase.READY && endpoint != null && isProcessBound

    val dashboardUrl: String
        get() = captivePortalRedirectUrl
            ?.takeIf { BitVoidConnectionManager.isAllowedLocalUrl(it) }
            ?: endpoint?.portalUrl
            ?: BitVoidConnectionManager.NODE_URL

    val dashboardFallbackUrl: String
        get() = endpoint?.directUrl ?: BitVoidConnectionManager.GATEWAY_URL
}

/**
 * Owns the zero-configuration link to the standalone ESP32 / ESP-12E BitVoid node.
 *
 * The manager only binds the Android process when the phone is attached to [NODE_WIFI_SSID]
 * (or when Android redacts the SSID but DHCP reports [NODE_GATEWAY]). This makes every socket,
 * WebView request and native API call stay on the isolated Wi-Fi link instead of escaping over
 * cellular data. Call [start] once from an application-scoped owner and [close] on shutdown.
 */
class BitVoidConnectionManager(
    context: Context,
    private val externalScope: CoroutineScope? = null
) : Closeable {

    companion object {
        const val NODE_WIFI_SSID = "BitVoid_Node"
        const val NODE_HOSTNAME = "bitvoid.local"
        const val NODE_GATEWAY = "192.168.4.1"
        const val NODE_PORT = 80
        const val NODE_URL = "http://$NODE_HOSTNAME"
        const val GATEWAY_URL = "http://$NODE_GATEWAY"
        const val CHAT_SERVICE_TYPE = "_http._tcp."

        private const val CAPTIVE_PROBE_PATH = "/generate_204"
        private const val MDNS_PORT = 5353
        private const val DISCOVERY_TIMEOUT_MS = 2_500L
        private val MDNS_GROUP: InetAddress = InetAddress.getByName("224.0.0.251")

        internal fun isAllowedLocalUrl(rawUrl: String): Boolean {
            val parsed = runCatching { android.net.Uri.parse(rawUrl) }.getOrNull() ?: return false
            if (!parsed.scheme.equals("http", ignoreCase = true) &&
                !parsed.scheme.equals("https", ignoreCase = true)
            ) return false
            val host = parsed.host?.lowercase().orEmpty()
            if (host == NODE_HOSTNAME || host == NODE_GATEWAY || host == "localhost" || host == "127.0.0.1") {
                return true
            }
            val octets = host.split('.').mapNotNull { it.toIntOrNull() }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return false
            return octets[0] == 10 ||
                (octets[0] == 192 && octets[1] == 168) ||
                (octets[0] == 172 && octets[1] in 16..31)
        }
    }

    private data class NodeWifiLink(
        val network: Network,
        val ssid: String?,
        val linkProperties: LinkProperties,
        val gateway: Inet4Address?,
        val expected: Boolean
    )

    private data class CaptiveProbeResult(
        val responseCode: Int?,
        val redirectUrl: String?,
        val nodeResponded: Boolean
    )

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val ownedJob = SupervisorJob()
    private val scope = externalScope ?: CoroutineScope(ownedJob + Dispatchers.IO)
    private val inspectionMutex = Mutex()

    private val _state = MutableStateFlow(BitVoidConnectionState())
    val state: StateFlow<BitVoidConnectionState> = _state.asStateFlow()

    @Volatile
    private var started = false

    @Volatile
    private var selectedNetwork: Network? = null

    @Volatile
    private var selectedAddress: Inet4Address? = null

    private var inspectionJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = inspectAsync(network)

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                inspectAsync(network)
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            inspectAsync(network)
        }

        override fun onLost(network: Network) {
            if (selectedNetwork == network) {
                inspectionJob?.cancel()
                selectedNetwork = null
                selectedAddress = null
                connectivityManager.bindProcessToNetwork(null)
                _state.value = BitVoidConnectionState(
                    phase = BitVoidConnectionPhase.WAITING_FOR_WIFI,
                    statusMessage = "BitVoid_Node disconnected. Waiting for the ESP node Wi-Fi."
                )
            }
        }
    }

    @Synchronized
    fun start() {
        if (started) {
            refresh()
            return
        }
        started = true
        _state.value = BitVoidConnectionState(
            phase = BitVoidConnectionPhase.WAITING_FOR_WIFI,
            statusMessage = "Waiting for $NODE_WIFI_SSID…"
        )
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        refresh()
    }

    fun refresh() {
        if (!started) return
        val wifiNetworks = connectivityManager.allNetworks.filter { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (wifiNetworks.isEmpty()) {
            _state.value = BitVoidConnectionState(
                phase = BitVoidConnectionPhase.WAITING_FOR_WIFI,
                statusMessage = "Connect Android to $NODE_WIFI_SSID in Wi-Fi settings."
            )
        } else {
            wifiNetworks.forEach(::inspectAsync)
        }
    }

    /** The network used by [BitVoidNodeClient]. It is intentionally not exposed outside the module. */
    internal fun currentNetwork(): Network? = selectedNetwork

    internal fun currentAddress(): Inet4Address? = selectedAddress

    private fun inspectAsync(network: Network) {
        if (!started) return
        inspectionJob?.cancel()
        inspectionJob = scope.launch {
            inspectionMutex.withLock { inspectNetwork(network) }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun inspectNetwork(network: Network) {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return
        val ssid = resolveSsid(capabilities)
        val gateway = linkProperties.routes
            .asSequence()
            .mapNotNull { it.gateway as? Inet4Address }
            .firstOrNull { !it.isAnyLocalAddress }
        val ssidMatches = ssid.equals(NODE_WIFI_SSID, ignoreCase = true)
        val ssidRedacted = ssid.isNullOrBlank() || ssid == WifiManager.UNKNOWN_SSID
        val gatewayMatches = gateway?.hostAddress == NODE_GATEWAY
        val expected = ssidMatches || (ssidRedacted && gatewayMatches)
        val link = NodeWifiLink(network, ssid, linkProperties, gateway, expected)

        if (!link.expected) {
            if (selectedNetwork == network) {
                selectedNetwork = null
                selectedAddress = null
                connectivityManager.bindProcessToNetwork(null)
            }
            if (selectedNetwork == null) {
                _state.value = BitVoidConnectionState(
                    phase = BitVoidConnectionPhase.WAITING_FOR_WIFI,
                    wifiSsid = ssid,
                    statusMessage = "Current Wi-Fi is ${ssid ?: "unknown"}; connect to $NODE_WIFI_SSID."
                )
            }
            return
        }

        _state.value = BitVoidConnectionState(
            phase = BitVoidConnectionPhase.BINDING,
            wifiSsid = ssid ?: NODE_WIFI_SSID,
            isExpectedNodeWifi = true,
            gatewayAddress = gateway?.hostAddress ?: NODE_GATEWAY,
            statusMessage = "Binding all BitVoid traffic to the isolated ESP Wi-Fi…"
        )

        val bound = connectivityManager.bindProcessToNetwork(network)
        if (!bound) {
            _state.value = _state.value.copy(
                phase = BitVoidConnectionPhase.ERROR,
                isProcessBound = false,
                errorMessage = "Android refused to bind traffic to $NODE_WIFI_SSID.",
                statusMessage = "ESP Wi-Fi detected, but safe local routing failed."
            )
            return
        }
        selectedNetwork = network

        _state.value = _state.value.copy(
            phase = BitVoidConnectionPhase.DISCOVERING,
            isProcessBound = true,
            networkHandle = network.networkHandle,
            statusMessage = "Resolving $NODE_HOSTNAME over multicast DNS…",
            errorMessage = null
        )

        val captiveProbe = probeCaptivePortal(network)
        val androidAddress = resolveWithAndroidDns(network)
        val multicastAddress = androidAddress ?: resolveWithMulticastDns(network, linkProperties)
        val nsdEndpoint = if (multicastAddress == null) discoverNsdHttpService() else null

        val endpoint = when {
            androidAddress != null -> BitVoidNodeEndpoint(
                address = androidAddress,
                source = BitVoidDiscoverySource.ANDROID_MDNS
            )
            multicastAddress != null -> BitVoidNodeEndpoint(
                address = multicastAddress,
                source = BitVoidDiscoverySource.MULTICAST_DNS
            )
            nsdEndpoint != null -> nsdEndpoint
            captiveProbe.redirectUrl?.let(::isAllowedLocalUrl) == true -> BitVoidNodeEndpoint(
                address = gateway ?: InetAddress.getByName(NODE_GATEWAY) as Inet4Address,
                source = BitVoidDiscoverySource.CAPTIVE_PORTAL_REDIRECT
            )
            captiveProbe.nodeResponded -> BitVoidNodeEndpoint(
                address = gateway ?: InetAddress.getByName(NODE_GATEWAY) as Inet4Address,
                source = BitVoidDiscoverySource.GATEWAY_FALLBACK
            )
            else -> null
        }

        if (selectedNetwork != network) return
        selectedAddress = endpoint?.address
        _state.value = if (endpoint != null) {
            _state.value.copy(
                phase = BitVoidConnectionPhase.READY,
                isProcessBound = true,
                endpoint = endpoint,
                captivePortalRedirectUrl = captiveProbe.redirectUrl,
                statusMessage = "$NODE_HOSTNAME ready on ${endpoint.address.hostAddress}:${endpoint.port}",
                errorMessage = null
            )
        } else {
            _state.value.copy(
                phase = BitVoidConnectionPhase.ERROR,
                endpoint = null,
                captivePortalRedirectUrl = captiveProbe.redirectUrl,
                statusMessage = "$NODE_WIFI_SSID is connected, but the hardware node did not answer.",
                errorMessage = "No mDNS A record, DNS-SD HTTP service, captive redirect, or gateway response was received."
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveSsid(capabilities: NetworkCapabilities): String? {
        val hasWifiPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.NEARBY_WIFI_DEVICES) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
        if (!hasWifiPermission) return null
        val fromCapabilities = (capabilities.transportInfo as? WifiInfo)?.ssid
        val raw = fromCapabilities ?: runCatching { wifiManager.connectionInfo?.ssid }.getOrNull()
        return raw?.removeSurrounding("\"")?.takeUnless { it == WifiManager.UNKNOWN_SSID }
    }

    private suspend fun probeCaptivePortal(network: Network): CaptiveProbeResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = network.openConnection(URL("$GATEWAY_URL$CAPTIVE_PROBE_PATH")) as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.connectTimeout = 1_500
            connection.readTimeout = 1_500
            connection.setRequestProperty("User-Agent", "BitVoid-ESP-Discovery/1.0")
            connection.setRequestProperty("Connection", "close")
            val code = connection.responseCode
            val redirect = connection.getHeaderField("Location")
                ?.let { raw -> runCatching { URL(URL(GATEWAY_URL), raw).toString() }.getOrNull() }
                ?.takeIf(::isAllowedLocalUrl)
            CaptiveProbeResult(
                responseCode = code,
                redirectUrl = redirect,
                nodeResponded = code in 200..399
            )
        } catch (_: Exception) {
            probeNodeRoot(network)
        } finally {
            connection?.disconnect()
        }
    }

    private fun probeNodeRoot(network: Network): CaptiveProbeResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = network.openConnection(URL(GATEWAY_URL)) as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 1_200
            connection.readTimeout = 1_200
            val code = connection.responseCode
            val redirect = connection.getHeaderField("Location")?.takeIf(::isAllowedLocalUrl)
            CaptiveProbeResult(code, redirect, code in 200..399)
        } catch (_: Exception) {
            CaptiveProbeResult(null, null, false)
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun resolveWithAndroidDns(network: Network): Inet4Address? {
        return withTimeoutOrNull(1_500L) {
            withContext(Dispatchers.IO) {
                runCatching {
                    network.getAllByName(NODE_HOSTNAME)
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull { !it.isLoopbackAddress && !it.isAnyLocalAddress }
                }.getOrNull()
            }
        }
    }

    private suspend fun resolveWithMulticastDns(
        network: Network,
        linkProperties: LinkProperties
    ): Inet4Address? = withContext(Dispatchers.IO) {
        val multicastLock = wifiManager.createMulticastLock("BitVoid-ESP-mDNS").apply {
            setReferenceCounted(false)
        }
        var socket: MulticastSocket? = null
        try {
            multicastLock.acquire()
            val activeSocket = MulticastSocket(null)
            socket = activeSocket
            network.bindSocket(activeSocket)
            activeSocket.reuseAddress = true
            activeSocket.bind(InetSocketAddress(MDNS_PORT))
            activeSocket.soTimeout = 700
            val networkInterface = linkProperties.interfaceName
                ?.let { NetworkInterface.getByName(it) }
            if (networkInterface != null) {
                activeSocket.networkInterface = networkInterface
                activeSocket.joinGroup(InetSocketAddress(MDNS_GROUP, MDNS_PORT), networkInterface)
            } else {
                @Suppress("DEPRECATION")
                activeSocket.joinGroup(MDNS_GROUP)
            }

            val query = BitVoidMdnsCodec.buildAQuery(NODE_HOSTNAME)
            val outgoing = DatagramPacket(query, query.size, MDNS_GROUP, MDNS_PORT)
            repeat(2) {
                activeSocket.send(outgoing)
                val deadline = System.currentTimeMillis() + 800L
                while (System.currentTimeMillis() < deadline) {
                    val bytes = ByteArray(1_500)
                    val incoming = DatagramPacket(bytes, bytes.size)
                    try {
                        activeSocket.receive(incoming)
                    } catch (_: java.net.SocketTimeoutException) {
                        break
                    }
                    BitVoidMdnsCodec.findARecord(incoming.data, incoming.length, NODE_HOSTNAME)
                        ?.let { return@withContext it }
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { socket?.close() }
            runCatching { if (multicastLock.isHeld) multicastLock.release() }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun discoverNsdHttpService(): BitVoidNodeEndpoint? {
        return withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                val finished = AtomicBoolean(false)
                val resolving = AtomicBoolean(false)
                lateinit var listener: NsdManager.DiscoveryListener

                fun stopDiscovery() {
                    runCatching { nsdManager.stopServiceDiscovery(listener) }
                }

                fun finish(endpoint: BitVoidNodeEndpoint?) {
                    if (finished.compareAndSet(false, true)) {
                        stopDiscovery()
                        if (continuation.isActive) continuation.resume(endpoint)
                    }
                }

                listener = object : NsdManager.DiscoveryListener {
                    override fun onDiscoveryStarted(serviceType: String) = Unit

                    override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                        val candidate = serviceInfo.serviceName.contains("bitvoid", ignoreCase = true) ||
                            serviceInfo.serviceType.contains("_http._tcp", ignoreCase = true)
                        if (!candidate || !resolving.compareAndSet(false, true)) return
                        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                resolving.set(false)
                            }

                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                val address = resolved.host as? Inet4Address
                                val hostMatches = resolved.host?.hostName
                                    ?.trimEnd('.')
                                    ?.equals(NODE_HOSTNAME, ignoreCase = true) == true
                                val nameMatches = resolved.serviceName.contains("bitvoid", ignoreCase = true)
                                if (address != null && (hostMatches || nameMatches)) {
                                    finish(
                                        BitVoidNodeEndpoint(
                                            address = address,
                                            port = resolved.port.takeIf { it > 0 } ?: NODE_PORT,
                                            source = BitVoidDiscoverySource.NSD_HTTP_SERVICE
                                        )
                                    )
                                } else {
                                    resolving.set(false)
                                }
                            }
                        })
                    }

                    override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                    override fun onDiscoveryStopped(serviceType: String) {
                        if (!finished.get()) finish(null)
                    }

                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                        finish(null)
                    }

                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                        if (!finished.get()) finish(null)
                    }
                }

                continuation.invokeOnCancellation {
                    if (finished.compareAndSet(false, true)) stopDiscovery()
                }
                runCatching {
                    nsdManager.discoverServices(CHAT_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
                }.onFailure { finish(null) }
            }
        }
    }

    @Synchronized
    override fun close() {
        if (!started) return
        started = false
        inspectionJob?.cancel()
        inspectionJob = null
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
        if (selectedNetwork != null) connectivityManager.bindProcessToNetwork(null)
        selectedNetwork = null
        selectedAddress = null
        _state.value = BitVoidConnectionState()
        if (externalScope == null) scope.cancel()
    }
}

/** Small DNS codec kept independent from Android callbacks so packet handling can be unit tested. */
internal object BitVoidMdnsCodec {
    private data class DecodedName(val name: String, val nextOffset: Int)

    fun buildAQuery(hostname: String): ByteArray {
        val output = ByteArrayOutputStream(64)
        DataOutputStream(output).use { dns ->
            dns.writeShort(0)
            dns.writeShort(0)
            dns.writeShort(1)
            dns.writeShort(0)
            dns.writeShort(0)
            dns.writeShort(0)
            hostname.trimEnd('.').split('.').forEach { label ->
                val encoded = label.toByteArray(StandardCharsets.US_ASCII)
                require(encoded.size in 1..63) { "Invalid DNS label" }
                dns.writeByte(encoded.size)
                dns.write(encoded)
            }
            dns.writeByte(0)
            dns.writeShort(1)
            dns.writeShort(1)
        }
        return output.toByteArray()
    }

    fun findARecord(data: ByteArray, length: Int, hostname: String): Inet4Address? {
        if (length < 12 || length > data.size || (data[2].toInt() and 0x80) == 0) return null
        val questionCount = readU16(data, 4)
        val recordCount = readU16(data, 6) + readU16(data, 8) + readU16(data, 10)
        var cursor = 12
        repeat(questionCount) {
            val question = decodeName(data, length, cursor) ?: return null
            cursor = question.nextOffset
            if (cursor + 4 > length) return null
            cursor += 4
        }
        repeat(recordCount) {
            val recordName = decodeName(data, length, cursor) ?: return null
            cursor = recordName.nextOffset
            if (cursor + 10 > length) return null
            val type = readU16(data, cursor)
            val dnsClass = readU16(data, cursor + 2) and 0x7FFF
            val dataLength = readU16(data, cursor + 8)
            cursor += 10
            if (cursor + dataLength > length) return null
            if (type == 1 && dnsClass == 1 && dataLength == 4 &&
                recordName.name.equals(hostname.trimEnd('.'), ignoreCase = true)
            ) {
                return InetAddress.getByAddress(data.copyOfRange(cursor, cursor + 4)) as? Inet4Address
            }
            cursor += dataLength
        }
        return null
    }

    private fun decodeName(
        data: ByteArray,
        length: Int,
        startOffset: Int,
        depth: Int = 0
    ): DecodedName? {
        if (depth > 8 || startOffset !in 0 until length) return null
        val labels = mutableListOf<String>()
        var cursor = startOffset
        var nextOffset = -1
        while (cursor < length) {
            val labelLength = data[cursor].toInt() and 0xFF
            when {
                labelLength == 0 -> {
                    val end = if (nextOffset >= 0) nextOffset else cursor + 1
                    return DecodedName(labels.joinToString("."), end)
                }
                labelLength and 0xC0 == 0xC0 -> {
                    if (cursor + 1 >= length) return null
                    val pointer = ((labelLength and 0x3F) shl 8) or
                        (data[cursor + 1].toInt() and 0xFF)
                    if (nextOffset < 0) nextOffset = cursor + 2
                    val suffix = decodeName(data, length, pointer, depth + 1) ?: return null
                    if (suffix.name.isNotEmpty()) labels += suffix.name
                    return DecodedName(labels.joinToString("."), nextOffset)
                }
                labelLength > 63 || cursor + 1 + labelLength > length -> return null
                else -> {
                    labels += String(data, cursor + 1, labelLength, StandardCharsets.US_ASCII)
                    cursor += labelLength + 1
                }
            }
        }
        return null
    }

    private fun readU16(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }
}
