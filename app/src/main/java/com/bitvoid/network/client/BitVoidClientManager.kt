package com.bitvoid.network.client

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.ContextCompat
import com.bitvoid.network.mesh.BitVoidMeshIdentity
import com.bitvoid.network.mesh.BitVoidMeshNodeDescriptor
import com.bitvoid.network.mesh.BitVoidMeshProtocol
import com.bitvoid.network.model.BitVoidNetworkConfig
import com.bitvoid.network.model.ClientConnectionStatus
import com.bitvoid.network.model.DiscoveredNetwork
import com.bitvoid.network.model.NodeConnectionEventType
import com.bitvoid.network.service.BitVoidNodeStateHolder
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ClientNodeConnectResult {
    CONNECTED,
    NO_WIFI,
    WRONG_WIFI,
    NO_NODE
}

class BitVoidClientManager(private val context: Context) {

    private data class CurrentWifiLink(
        val network: Network,
        val ssid: String?,
        val localIpv4: String,
        val gatewayHosts: List<String>,
        val signalDbm: Int,
        val frequencyMhz: Int
    )

    private data class DiscoveredMeshNode(
        val baseUrl: String,
        val descriptor: BitVoidMeshNodeDescriptor
    )

    private data class MeshDiscoveryResult(
        val gatewayUrls: List<String>,
        val coordinatorUrls: List<String>,
        val nodeCount: Int
    )

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var scanReceiver: BroadcastReceiver? = null
    private val discoveryMutex = Mutex()
    private var lastMeshSignature: String? = null
    private var lastSelectedGateway: String? = null
    private val loggedCoordinators = mutableSetOf<String>()

    private fun log(
        type: NodeConnectionEventType,
        title: String,
        detail: String,
        isError: Boolean = type == NodeConnectionEventType.ERROR
    ) {
        BitVoidNodeStateHolder.appendNodeConnectionLog(type, title, detail, isError)
    }

    /** Lists nearby Wi-Fi networks only. It never asks Android to join or switch networks. */
    fun scanNearbyNetworks() {
        val hasLocation = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocation) {
            log(
                NodeConnectionEventType.ERROR,
                "Wi-Fi scan blocked",
                "Fine Location permission is required to read nearby Wi-Fi names."
            )
            BitVoidNodeStateHolder.updateClientState {
                it.copy(
                    isScanning = false,
                    connectionStatus = ClientConnectionStatus.ERROR,
                    errorMessage = "Fine Location permission is required by Android to read nearby Wi-Fi names."
                )
            }
            return
        }

        val currentLink = resolveCurrentWifiLink()
        log(
            NodeConnectionEventType.SCAN,
            "Wi-Fi scan started",
            "Current network: ${currentLink?.ssid ?: "none"}"
        )
        BitVoidNodeStateHolder.updateClientState {
            it.copy(
                isScanning = true,
                currentWifiSsid = currentLink?.ssid,
                connectionStatus = ClientConnectionStatus.SCANNING,
                statusMessage = "Scanning nearby Wi-Fi networks…",
                errorMessage = null
            )
        }

        unregisterScanReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                unregisterScanReceiver()
                publishScanResults()
            }
        }
        scanReceiver = receiver

        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }

        @Suppress("DEPRECATION")
        val started = try {
            wifiManager.startScan()
        } catch (_: SecurityException) {
            false
        }
        if (!started) {
            // Android can throttle scans; cached results still provide a useful list.
            unregisterScanReceiver()
            publishScanResults()
        }
    }

    @SuppressLint("MissingPermission")
    private fun publishScanResults() {
        val currentLink = resolveCurrentWifiLink()
        val rawResults: List<ScanResult> = try {
            wifiManager.scanResults ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }

        val mapped = rawResults
            .asSequence()
            .filter { extractSsid(it).isNotBlank() }
            .distinctBy { extractSsid(it).lowercase() }
            .map { result ->
                val ssid = extractSsid(result)
                val caps = result.capabilities ?: ""
                DiscoveredNetwork(
                    ssid = ssid,
                    signalDbm = result.level,
                    frequencyMhz = result.frequency,
                    securityLabel = when {
                        caps.contains("WPA3") -> "WPA3"
                        caps.contains("WPA2") -> "WPA2"
                        caps.contains("WPA") -> "WPA"
                        else -> "OPEN"
                    },
                    isBitVoidCandidate = ssid.contains("BitVoid", ignoreCase = true)
                )
            }
            .sortedWith(
                compareByDescending<DiscoveredNetwork> {
                    it.ssid.equals(currentLink?.ssid, ignoreCase = true)
                }.thenByDescending { it.isBitVoidCandidate }
                    .thenByDescending { it.signalDbm }
            )
            .toMutableList()

        val currentSsid = currentLink?.ssid
        if (!currentSsid.isNullOrBlank() && mapped.none { it.ssid.equals(currentSsid, ignoreCase = true) }) {
            mapped.add(
                0,
                DiscoveredNetwork(
                    ssid = currentSsid,
                    signalDbm = currentLink.signalDbm,
                    frequencyMhz = currentLink.frequencyMhz,
                    securityLabel = "CONNECTED",
                    isBitVoidCandidate = currentSsid.contains("BitVoid", ignoreCase = true)
                )
            )
        }

        log(
            NodeConnectionEventType.SCAN,
            "Wi-Fi scan completed",
            "${mapped.size} network(s) visible · connected: ${currentSsid ?: "none"}"
        )
        BitVoidNodeStateHolder.updateClientState { state ->
            state.copy(
                isScanning = false,
                currentWifiSsid = currentSsid,
                connectionStatus = if (state.connectedSsid != null) {
                    ClientConnectionStatus.CONNECTED
                } else {
                    ClientConnectionStatus.DISCONNECTED
                },
                discoveredNetworks = mapped,
                statusMessage = when {
                    currentSsid == null -> "Connect this phone to a Wi-Fi network, then scan again."
                    mapped.isEmpty() -> "Connected to $currentSsid. No additional Wi-Fi names were reported."
                    else -> "Connected to $currentSsid · ${mapped.size} Wi-Fi network(s) visible."
                },
                errorMessage = null
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun extractSsid(result: ScanResult): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.wifiSsid?.toString()?.removeSurrounding("\"") ?: ""
        } else {
            result.SSID?.removeSurrounding("\"") ?: ""
        }
    }

    /**
     * Links every BitVoid node reachable through the phone's current Wi-Fi. Android still owns
     * Wi-Fi association: BitVoid never switches networks or asks for the selected Wi-Fi password.
     */
    suspend fun connectToNode(
        selectedSsid: String,
        reportMissingNode: Boolean = true
    ): ClientNodeConnectResult {
        val link = resolveCurrentWifiLink()
        if (link == null) {
            log(
                NodeConnectionEventType.ERROR,
                "No Wi-Fi link",
                "Android is not connected to a local Wi-Fi gateway."
            )
            BitVoidNodeStateHolder.updateClientState {
                it.copy(
                    connectionStatus = ClientConnectionStatus.ERROR,
                    currentWifiSsid = null,
                    connectedSsid = null,
                    linkedNodeUrls = emptyList(),
                    coordinatorUrls = emptyList(),
                    meshNodeCount = 0,
                    errorMessage = "Connect the phone to Wi-Fi first. BitVoid shares the connection Android already joined."
                )
            }
            return ClientNodeConnectResult.NO_WIFI
        }

        val cleanSelection = selectedSsid.trim()
        if (cleanSelection.isNotEmpty() &&
            !link.ssid.isNullOrBlank() &&
            !cleanSelection.equals(link.ssid, ignoreCase = true)
        ) {
            log(
                NodeConnectionEventType.ERROR,
                "Selected Wi-Fi is not active",
                "Selected $cleanSelection · Android is connected to ${link.ssid}."
            )
            BitVoidNodeStateHolder.updateClientState {
                it.copy(
                    connectionStatus = ClientConnectionStatus.ERROR,
                    currentWifiSsid = link.ssid,
                    connectedSsid = null,
                    linkedNodeUrls = emptyList(),
                    coordinatorUrls = emptyList(),
                    meshNodeCount = 0,
                    errorMessage = "'$cleanSelection' is not the phone's current Wi-Fi. Join it in Android Wi-Fi settings first."
                )
            }
            return ClientNodeConnectResult.WRONG_WIFI
        }

        val activeSsid = link.ssid ?: cleanSelection.ifBlank { "Current Wi-Fi" }
        log(
            NodeConnectionEventType.DISCOVERY,
            "Mesh discovery started",
            "Scanning $activeSsid (${link.localIpv4}) for BitVoid identities."
        )
        BitVoidNodeStateHolder.updateClientState {
            it.copy(
                connectionStatus = ClientConnectionStatus.CONNECTING,
                currentWifiSsid = link.ssid ?: cleanSelection.takeIf { it.isNotBlank() },
                connectedSsid = null,
                linkedNodeUrls = emptyList(),
                coordinatorUrls = emptyList(),
                meshNodeCount = 0,
                isBrowserOpen = false,
                statusMessage = "Finding every BitVoid node on $activeSsid…",
                errorMessage = null
            )
        }

        val discovery = discoverNodesOnLink(link, preferredPortalUrl = null)
        if (discovery.gatewayUrls.isEmpty()) {
            log(
                if (reportMissingNode) NodeConnectionEventType.ERROR else NodeConnectionEventType.RETRY,
                "No app gateway found",
                "${discovery.coordinatorUrls.size} coordinator(s) and ${discovery.nodeCount} total node(s) answered on $activeSsid."
            )
            BitVoidNodeStateHolder.updateClientState {
                it.copy(
                    connectionStatus = if (reportMissingNode) {
                        ClientConnectionStatus.ERROR
                    } else {
                        ClientConnectionStatus.CONNECTING
                    },
                    currentWifiSsid = link.ssid ?: cleanSelection.takeIf { it.isNotBlank() },
                    connectedSsid = null,
                    linkedNodeUrls = emptyList(),
                    coordinatorUrls = discovery.coordinatorUrls,
                    meshNodeCount = discovery.nodeCount,
                    statusMessage = "Preparing a shared BitVoid gateway on $activeSsid…",
                    errorMessage = if (reportMissingNode) {
                        "Unable to start or find a BitVoid app gateway on $activeSsid."
                    } else {
                        null
                    }
                )
            }
            return ClientNodeConnectResult.NO_NODE
        }

        // Route this app through the already-connected Wi-Fi, even when Android prefers mobile data.
        connectivityManager.bindProcessToNetwork(link.network)
        publishLinkedNodes(link, activeSsid, discovery, silent = false)
        return ClientNodeConnectResult.CONNECTED
    }

    /** Finds all BitVoid peers without exposing discovery progress in the UI. */
    suspend fun discoverAndLinkSilently(): String? {
        val state = BitVoidNodeStateHolder.clientState.value
        if (state.connectionStatus == ClientConnectionStatus.CONNECTING || state.isScanning) return null

        val link = resolveCurrentWifiLink()
        if (link == null) {
            if (state.connectedSsid != null || state.linkedNodeUrls.isNotEmpty()) {
                log(
                    NodeConnectionEventType.DISCONNECTED,
                    "Wi-Fi link lost",
                    "Mesh routing was released because Android has no active Wi-Fi."
                )
                connectivityManager.bindProcessToNetwork(null)
                BitVoidNodeStateHolder.updateClientState {
                    it.copy(
                        connectionStatus = ClientConnectionStatus.DISCONNECTED,
                        currentWifiSsid = null,
                        connectedSsid = null,
                        linkedNodeUrls = emptyList(),
                        coordinatorUrls = emptyList(),
                        meshNodeCount = 0,
                        statusMessage = "Waiting for the phone's Wi-Fi connection.",
                        errorMessage = null
                    )
                }
            }
            return null
        }

        val sameWifi = state.connectedSsid != null &&
            (link.ssid == null || state.connectedSsid.equals(link.ssid, ignoreCase = true))
        if (state.connectedSsid != null && !sameWifi) {
            log(
                NodeConnectionEventType.WIFI,
                "Wi-Fi gateway changed",
                "${state.connectedSsid} → ${link.ssid ?: "unknown Wi-Fi"}; rediscovery scheduled."
            )
            connectivityManager.bindProcessToNetwork(null)
            BitVoidNodeStateHolder.updateClientState {
                it.copy(
                    connectionStatus = ClientConnectionStatus.DISCONNECTED,
                    currentWifiSsid = link.ssid,
                    connectedSsid = null,
                    linkedNodeUrls = emptyList(),
                    coordinatorUrls = emptyList(),
                    meshNodeCount = 0,
                    statusMessage = "Wi-Fi changed. Ready on ${link.ssid ?: "the current network"}.",
                    errorMessage = null
                )
            }
        }

        val discovery = discoverNodesOnLink(link, state.targetPortalUrl)
        if (discovery.gatewayUrls.isEmpty()) {
            if (discovery.coordinatorUrls.isNotEmpty()) {
                BitVoidNodeStateHolder.updateClientState {
                    it.copy(
                        currentWifiSsid = link.ssid,
                        coordinatorUrls = discovery.coordinatorUrls,
                        meshNodeCount = discovery.nodeCount
                    )
                }
            }
            return null
        }
        val activeSsid = link.ssid ?: state.currentWifiSsid ?: "Current Wi-Fi"
        connectivityManager.bindProcessToNetwork(link.network)
        publishLinkedNodes(link, activeSsid, discovery, silent = true)
        return discovery.gatewayUrls.first()
    }

    private fun publishLinkedNodes(
        link: CurrentWifiLink,
        activeSsid: String,
        discovery: MeshDiscoveryResult,
        silent: Boolean
    ) {
        val orderedUrls = orderNodeUrlsForSharedGateway(discovery.gatewayUrls)
        val sharedGatewayUrl = orderedUrls.first()
        val signature = (orderedUrls + discovery.coordinatorUrls).sorted().joinToString("|")
        val meshChanged = signature != lastMeshSignature
        if (meshChanged) {
            log(
                NodeConnectionEventType.IDENTITY,
                "BitVoid mesh identities verified",
                "${discovery.nodeCount} node(s) · ${discovery.coordinatorUrls.size} coordinator(s) · ${orderedUrls.size} app gateway(s)."
            )
            lastMeshSignature = signature
        }
        val gatewayChanged = sharedGatewayUrl != lastSelectedGateway
        if (gatewayChanged) {
            log(
                NodeConnectionEventType.GATEWAY,
                "Shared gateway selected",
                "$sharedGatewayUrl on $activeSsid"
            )
            lastSelectedGateway = sharedGatewayUrl
        }
        if (!silent || meshChanged || gatewayChanged) {
            log(
                NodeConnectionEventType.CONNECTED,
                if (silent) "Mesh link refreshed" else "Mesh connected",
                "Gateway $sharedGatewayUrl · ${discovery.nodeCount} node(s) reachable."
            )
        }
        BitVoidNodeStateHolder.updateClientState {
            it.copy(
                connectionStatus = ClientConnectionStatus.CONNECTED,
                currentWifiSsid = link.ssid ?: activeSsid.takeUnless { name -> name == "Current Wi-Fi" },
                connectedSsid = activeSsid,
                linkedNodeUrls = orderedUrls,
                coordinatorUrls = discovery.coordinatorUrls,
                meshNodeCount = discovery.nodeCount,
                targetPortalUrl = sharedGatewayUrl,
                isBrowserOpen = false,
                statusMessage = if (silent) {
                    "${discovery.nodeCount} BitVoid mesh node(s) sharing $activeSsid."
                } else {
                    "${discovery.nodeCount} node(s) linked through $activeSsid · shared gateway $sharedGatewayUrl"
                },
                errorMessage = null
            )
        }
    }

    private suspend fun discoverNodesOnLink(
        link: CurrentWifiLink,
        preferredPortalUrl: String?
    ): MeshDiscoveryResult = discoveryMutex.withLock {
        withContext(Dispatchers.IO) {
            val candidates = buildList {
                add(BitVoidClientRepository.DEFAULT_GATEWAY_URL)
                normalizeLocalPortalUrl(preferredPortalUrl.orEmpty())?.let { preferred ->
                    val host = android.net.Uri.parse(preferred).host
                    if (host != null && host != "127.0.0.1" && host != "localhost") {
                        add(preferred.trimEnd('/'))
                    }
                }
                buildSubnetCandidateHosts(link.localIpv4, link.gatewayHosts).forEach { host ->
                    add("http://$host:8080")
                }
                if (BitVoidNodeStateHolder.hostState.value.isServerRunning) {
                    add("http://${link.localIpv4}:8080")
                }
            }.distinct()

            val discoveredById = linkedMapOf<String, DiscoveredMeshNode>()
            for (batch in candidates.chunked(DISCOVERY_BATCH_SIZE)) {
                val matches = coroutineScope {
                    batch.map { candidate ->
                        async { probeBitVoidNode(link.network, candidate) }
                    }.awaitAll()
                }
                matches.filterNotNull().forEach { node ->
                    discoveredById[node.descriptor.nodeId] = node
                }
            }

            // Coordinators and Android gateways gossip known peers. Every advertised URL is
            // probed again before it can become a selected gateway.
            val registryCandidates = discoveredById.values
                .flatMap { fetchMeshRegistry(link.network, it.baseUrl) }
                .mapNotNull { descriptor ->
                    descriptor.directPortalUrl
                        ?.let(::normalizeMeshCandidateUrl)
                        ?.trimEnd('/')
                }
                .distinct()
                .take(MAX_GOSSIP_CANDIDATES)
                .filterNot { candidate ->
                    discoveredById.values.any { it.baseUrl.equals(candidate, ignoreCase = true) }
                }
            for (batch in registryCandidates.chunked(DISCOVERY_BATCH_SIZE)) {
                val matches = coroutineScope {
                    batch.map { candidate ->
                        async { probeBitVoidNode(link.network, candidate) }
                    }.awaitAll()
                }
                matches.filterNotNull().forEach { node ->
                    discoveredById[node.descriptor.nodeId] = node
                }
            }

            val coordinators = discoveredById.values.filter { it.descriptor.coordinator }
            coordinators.forEach { coordinator ->
                registerWithCoordinator(link, coordinator.baseUrl)
            }

            val gatewayUrls = discoveredById.values
                .filter { it.descriptor.portalAvailable && !it.descriptor.coordinator }
                .mapNotNull(::reachablePortalUrl)
                .let(::orderNodeUrlsForSharedGateway)
            val coordinatorUrls = coordinators
                .map { it.baseUrl.trimEnd('/') }
                .distinct()
                .sorted()

            MeshDiscoveryResult(
                gatewayUrls = gatewayUrls,
                coordinatorUrls = coordinatorUrls,
                nodeCount = discoveredById.size
            )
        }
    }

    private fun reachablePortalUrl(node: DiscoveredMeshNode): String? {
        val advertised = node.descriptor.directPortalUrl
            ?.let(::normalizeMeshCandidateUrl)
            ?.trimEnd('/')
        return advertised ?: normalizeMeshCandidateUrl(node.baseUrl)?.trimEnd('/')
    }

    private fun normalizeMeshCandidateUrl(rawUrl: String): String? {
        val normalized = normalizeLocalPortalUrl(rawUrl) ?: return null
        val host = android.net.Uri.parse(normalized).host?.lowercase() ?: return null
        if (host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0") return null
        return normalized
    }

    private fun probeBitVoidNode(network: Network, baseUrl: String): DiscoveredMeshNode? {
        var connection: HttpURLConnection? = null
        return try {
            val cleanBase = baseUrl.trimEnd('/')
            val endpoint = URL("$cleanBase/api/node")
            connection = network.openConnection(endpoint) as HttpURLConnection
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = DISCOVERY_CONNECT_TIMEOUT_MS
            connection.readTimeout = DISCOVERY_READ_TIMEOUT_MS
            connection.setRequestProperty("X-BitVoid-Discovery", "1")
            connection.setRequestProperty("User-Agent", "BitVoid-Mesh-Discovery/2.0")
            if (connection.responseCode != HttpURLConnection.HTTP_OK ||
                connection.getHeaderField(BitVoidMeshProtocol.HEADER_NODE) != "1"
            ) return null
            val headerVersion = connection.getHeaderField(BitVoidMeshProtocol.HEADER_PROTOCOL)
                ?.toIntOrNull() ?: 1
            if (headerVersion !in 1..BitVoidMeshProtocol.PROTOCOL_VERSION) return null
            if (headerVersion >= 2 &&
                connection.getHeaderField(BitVoidMeshProtocol.HEADER_IDENTITY) !=
                BitVoidMeshProtocol.IDENTITY_MODE
            ) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val descriptor = BitVoidMeshNodeDescriptor.fromJson(JSONObject(body), cleanBase)
                ?: return null
            DiscoveredMeshNode(cleanBase, descriptor)
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchMeshRegistry(
        network: Network,
        baseUrl: String
    ): List<BitVoidMeshNodeDescriptor> {
        var connection: HttpURLConnection? = null
        return try {
            connection = network.openConnection(
                URL("${baseUrl.trimEnd('/')}/api/mesh/nodes")
            ) as HttpURLConnection
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = REGISTRY_CONNECT_TIMEOUT_MS
            connection.readTimeout = REGISTRY_READ_TIMEOUT_MS
            connection.setRequestProperty("X-BitVoid-Discovery", "1")
            if (connection.responseCode != HttpURLConnection.HTTP_OK ||
                connection.getHeaderField(BitVoidMeshProtocol.HEADER_NODE) != "1"
            ) return emptyList()
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val nodes = root.optJSONArray("nodes") ?: JSONArray()
            buildList {
                for (index in 0 until nodes.length().coerceAtMost(MAX_GOSSIP_CANDIDATES)) {
                    val entry = nodes.optJSONObject(index) ?: continue
                    BitVoidMeshNodeDescriptor.fromJson(entry)?.let { add(it) }
                }
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            connection?.disconnect()
        }
    }

    private fun registerWithCoordinator(link: CurrentWifiLink, coordinatorUrl: String) {
        var connection: HttpURLConnection? = null
        try {
            val hostState = BitVoidNodeStateHolder.hostState.value
            val payload = JSONObject().apply {
                put("service", BitVoidMeshProtocol.SERVICE)
                put("nodeId", BitVoidMeshIdentity.getOrCreateNodeId(appContext))
                put(
                    "nodeType",
                    if (hostState.isServerRunning) {
                        BitVoidMeshProtocol.TYPE_ANDROID_GATEWAY
                    } else {
                        BitVoidMeshProtocol.TYPE_ANDROID_CLIENT
                    }
                )
                put("protocolVersion", BitVoidMeshProtocol.PROTOCOL_VERSION)
                put("identityMode", BitVoidMeshProtocol.IDENTITY_MODE)
                put("coordinator", false)
                put("portalAvailable", hostState.isServerRunning)
                put("gatewayPriority", 100)
                put("address", link.localIpv4)
                put(
                    "directPortalUrl",
                    if (hostState.isServerRunning) "http://${link.localIpv4}:8080" else JSONObject.NULL
                )
            }.toString().toByteArray(Charsets.UTF_8)
            connection = link.network.openConnection(
                URL("${coordinatorUrl.trimEnd('/')}/api/mesh/register")
            ) as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.connectTimeout = REGISTRY_CONNECT_TIMEOUT_MS
            connection.readTimeout = REGISTRY_READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty(BitVoidMeshProtocol.HEADER_IDENTITY, BitVoidMeshProtocol.IDENTITY_MODE)
            connection.setFixedLengthStreamingMode(payload.size)
            connection.outputStream.use { it.write(payload) }
            if (connection.responseCode in 200..299) {
                val cleanCoordinator = coordinatorUrl.trimEnd('/')
                if (loggedCoordinators.add(cleanCoordinator)) {
                    log(
                        NodeConnectionEventType.COORDINATOR,
                        "Coordinator heartbeat accepted",
                        cleanCoordinator
                    )
                }
            }
        } catch (_: Exception) {
            // Discovery remains peer-to-peer when a coordinator cannot accept registration.
        } finally {
            connection?.disconnect()
        }
    }

    fun disconnectFromNode() {
        log(
            NodeConnectionEventType.DISCONNECTED,
            "Mesh link cleared",
            "BitVoid routing was released; Android remains in control of Wi-Fi."
        )
        connectivityManager.bindProcessToNetwork(null)
        BitVoidNodeStateHolder.updateClientState {
            it.copy(
                connectionStatus = ClientConnectionStatus.DISCONNECTED,
                connectedSsid = null,
                linkedNodeUrls = emptyList(),
                coordinatorUrls = emptyList(),
                meshNodeCount = 0,
                isBrowserOpen = false,
                statusMessage = "Node link cleared. The phone remains connected to Wi-Fi.",
                errorMessage = null
            )
        }
    }

    fun openSystemWifiSettings() {
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    fun release(preserveNetworkBinding: Boolean = false) {
        unregisterScanReceiver()
        if (!preserveNetworkBinding) {
            disconnectFromNode()
        }
    }

    private fun unregisterScanReceiver() {
        scanReceiver?.let { receiver ->
            try {
                appContext.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
        scanReceiver = null
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private fun resolveCurrentWifiLink(): CurrentWifiLink? {
        val active = connectivityManager.activeNetwork
        val orderedNetworks = buildList {
            if (active != null) add(active)
            connectivityManager.allNetworks.forEach { network ->
                if (network != active) add(network)
            }
        }

        for (network in orderedNetworks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
            val linkProperties = connectivityManager.getLinkProperties(network) ?: continue
            val localIpv4 = linkProperties.linkAddresses.firstOrNull { linkAddress ->
                linkAddress.address is Inet4Address &&
                    !linkAddress.address.isLoopbackAddress &&
                    !linkAddress.address.isLinkLocalAddress
            }?.address?.hostAddress ?: continue

            val wifiInfo = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    capabilities.transportInfo as? WifiInfo
                } else {
                    wifiManager.connectionInfo
                }
            } catch (_: SecurityException) {
                null
            }
            val ssid = wifiInfo?.ssid
                ?.removeSurrounding("\"")
                ?.takeUnless { it.isBlank() || it.equals(WifiManager.UNKNOWN_SSID, ignoreCase = true) }
            val gateways = buildList {
                linkProperties.routes.forEach { route ->
                    val gateway = route.gateway as? Inet4Address
                    val host = gateway?.hostAddress
                    if (!host.isNullOrBlank() && !gateway.isAnyLocalAddress) add(host)
                }
                val dhcp = (linkProperties.dhcpServerAddress as? Inet4Address)?.hostAddress
                if (!dhcp.isNullOrBlank() && dhcp != "0.0.0.0") add(dhcp)
            }.distinct()

            return CurrentWifiLink(
                network = network,
                ssid = ssid,
                localIpv4 = localIpv4,
                gatewayHosts = gateways,
                signalDbm = wifiInfo?.rssi ?: -127,
                frequencyMhz = wifiInfo?.frequency ?: 0
            )
        }
        return null
    }

    companion object {
        private const val SANITIZED_USER_AGENT = "BitVoid/1.0 (Isolated)"
        private const val DISCOVERY_BATCH_SIZE = 48
        private const val DISCOVERY_CONNECT_TIMEOUT_MS = 350
        private const val DISCOVERY_READ_TIMEOUT_MS = 450
        private const val REGISTRY_CONNECT_TIMEOUT_MS = 700
        private const val REGISTRY_READ_TIMEOUT_MS = 900
        private const val MAX_GOSSIP_CANDIDATES = 128

        /** Gateway addresses are tried first, followed by every peer in the current /24 LAN. */
        fun buildSubnetCandidateHosts(
            localIpv4: String,
            gatewayHosts: List<String>
        ): List<String> {
            val octets = localIpv4.split('.').mapNotNull { it.toIntOrNull() }
            if (octets.size != 4 || octets.any { it !in 0..255 }) return emptyList()
            val prefix = "${octets[0]}.${octets[1]}.${octets[2]}"
            return buildList {
                gatewayHosts.forEach { gateway ->
                    val clean = gateway.trim()
                    if (clean != localIpv4 && isPrivateLocalHost(clean)) add(clean)
                }
                for (lastOctet in 1..254) {
                    val candidate = "$prefix.$lastOctet"
                    if (candidate != localIpv4) add(candidate)
                }
            }.distinct()
        }

        /** All peers independently choose the same lowest local IPv4 as their shared gateway. */
        fun orderNodeUrlsForSharedGateway(urls: List<String>): List<String> {
            fun urlKey(url: String): Pair<Int, Long> {
                val host = runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()
                if (host.equals(BitVoidNetworkConfig.LOCAL_GATEWAY_HOSTNAME, ignoreCase = true)) {
                    return 0 to 0L
                }
                val octets = host.split('.').mapNotNull { it.toIntOrNull() }
                if (octets.size != 4 || octets.any { it !in 0..255 }) return 2 to Long.MAX_VALUE
                val ipv4 = octets.fold(0L) { acc, octet -> (acc shl 8) or octet.toLong() }
                return 1 to ipv4
            }
            return urls
                .map { it.trim().trimEnd('/') }
                .filter { it.isNotEmpty() }
                .distinct()
                .sortedWith(compareBy<String> { urlKey(it).first }.thenBy { urlKey(it).second }.thenBy { it })
        }

        fun resolvePortalUrlAfterConnect(
            normalizedRequestedUrl: String,
            wasDefaultPlaceholder: Boolean,
            linkGatewayIp: String?
        ): String {
            val cleanGw = linkGatewayIp?.trim()?.takeIf { it.isNotEmpty() && isPrivateLocalHost(it) }
            return if (
                cleanGw != null &&
                wasDefaultPlaceholder &&
                !normalizedRequestedUrl.equals(BitVoidClientRepository.DEFAULT_GATEWAY_URL, ignoreCase = true)
            ) {
                "http://$cleanGw:8080"
            } else {
                normalizedRequestedUrl
            }
        }

        fun normalizeLocalPortalUrl(rawInput: String): String? {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) return "http://${BitVoidNetworkConfig.LOCAL_GATEWAY_HOSTNAME}"
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "http://$trimmed"
            }
            val parsed = android.net.Uri.parse(withScheme)
            val host = parsed.host ?: return null
            return if (isPrivateLocalHost(host)) withScheme else null
        }

        fun isPrivateLocalHost(host: String): Boolean {
            val lower = host.lowercase()
            if (lower == BitVoidNetworkConfig.LOCAL_GATEWAY_HOSTNAME) return true
            if (lower == "localhost" || lower == "127.0.0.1") return true
            if (lower.startsWith("192.168.") || lower.startsWith("10.")) return true
            if (lower.startsWith("172.")) {
                val secondOctet = lower.removePrefix("172.").substringBefore('.').toIntOrNull()
                if (secondOctet != null && secondOctet in 16..31) return true
            }
            return try {
                val addr = InetAddress.getByName(lower)
                addr.isSiteLocalAddress || addr.isLoopbackAddress
            } catch (_: Exception) {
                false
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        fun configureSandboxedWebView(
            webView: WebView,
            onRequestEvaluated: (allowed: Boolean) -> Unit,
            onPageLoaded: (url: String) -> Unit
        ) {
            // 1. Disable all cookies & third-party cookies globally for this WebView
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(false)
            cookieManager.setAcceptThirdPartyCookies(webView, false)
            cookieManager.removeAllCookies(null)
            cookieManager.removeSessionCookies(null)
            cookieManager.flush()

            // 2. Clear any residual storage and enforce memory-only ephemeral mode
            WebStorage.getInstance().deleteAllData()
            webView.clearCache(true)
            webView.clearHistory()
            webView.clearFormData()

            webView.settings.apply {
                // Strip device model / OS / WebView build fingerprints
                userAgentString = SANITIZED_USER_AGENT

                // Enable JS strictly for local RFC1918 portal 3s auto-refresh while killing WebRTC
                // local IP leak APIs on every page load.
                javaScriptEnabled = true
                javaScriptCanOpenWindowsAutomatically = false

                // Memory-only / zero disk persistence
                cacheMode = WebSettings.LOAD_NO_CACHE
                domStorageEnabled = false
                databaseEnabled = false
                setGeolocationEnabled(false)
                allowFileAccess = false
                allowContentAccess = false
                @Suppress("DEPRECATION")
                saveFormData = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = false
                }
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = false
                builtInZoomControls = true
                displayZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView?,
                    url: String?,
                    favicon: android.graphics.Bitmap?
                ) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(
                        """
                        (function(){
                          try {
                            window.RTCPeerConnection = undefined;
                            window.webkitRTCPeerConnection = undefined;
                            window.mozRTCPeerConnection = undefined;
                            if (navigator.mediaDevices) {
                              Object.defineProperty(navigator, 'mediaDevices', { value: undefined });
                            }
                          } catch(e) {}
                        })();
                        """.trimIndent(),
                        null
                    )
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val host = request?.url?.host.orEmpty()
                    val allowed = isPrivateLocalHost(host)
                    onRequestEvaluated(allowed)
                    return !allowed
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val host = request?.url?.host.orEmpty()
                    val allowed = isPrivateLocalHost(host)
                    onRequestEvaluated(allowed)
                    return if (allowed) {
                        null // Proceed over bound local network
                    } else {
                        WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            403,
                            "Blocked by BitVoid Zero-Internet Sandbox",
                            emptyMap(),
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null) {
                        onPageLoaded(url)
                    }
                }
            }
        }
    }
}
