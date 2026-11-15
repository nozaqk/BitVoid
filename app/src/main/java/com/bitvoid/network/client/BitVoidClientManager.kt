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
import com.bitvoid.network.model.ClientConnectionStatus
import com.bitvoid.network.model.DiscoveredNetwork
import com.bitvoid.network.service.BitVoidNodeStateHolder
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BitVoidClientManager(private val context: Context) {

    private data class CurrentWifiLink(
        val network: Network,
        val ssid: String?,
        val localIpv4: String,
        val gatewayHosts: List<String>,
        val signalDbm: Int,
        val frequencyMhz: Int
    )

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var scanReceiver: BroadcastReceiver? = null
    private val discoveryMutex = Mutex()

    /** Lists nearby Wi-Fi networks only. It never asks Android to join or switch networks. */
    fun scanNearbyNetworks() {
        val hasLocation = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasLocation) {
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
     * Links BitVoid to a gateway already reachable through the phone's current Wi-Fi.
     * This deliberately never uses WifiNetworkSpecifier and never changes the device Wi-Fi network.
     */
    suspend fun connectToNode(selectedSsid: String) {
        val link = resolveCurrentWifiLink()
        if (link == null) {
            BitVoidNodeStateHolder.updateClientState {
                it.copy(
                    connectionStatus = ClientConnectionStatus.ERROR,
                    currentWifiSsid = null,
                    connectedSsid = null,
                    errorMessage = "Connect the phone to Wi-Fi first. BitVoid will use that existing connection."
                )
            }
            return
        }

        val cleanSelection = selectedSsid.trim()
        if (cleanSelection.isNotEmpty() &&
            !link.ssid.isNullOrBlank() &&
            !cleanSelection.equals(link.ssid, ignoreCase = true)
        ) {
            BitVoidNodeStateHolder.updateClientState {
                it.copy(
                    connectionStatus = ClientConnectionStatus.ERROR,
                    currentWifiSsid = link.ssid,
                    connectedSsid = null,
                    errorMessage = "'$cleanSelection' is not the phone's current Wi-Fi. Join it in Android Wi-Fi settings first."
                )
            }
            return
        }

        val activeSsid = link.ssid ?: cleanSelection.ifBlank { "Current Wi-Fi" }
        BitVoidNodeStateHolder.updateClientState {
            it.copy(
                connectionStatus = ClientConnectionStatus.CONNECTING,
                currentWifiSsid = link.ssid,
                connectedSsid = null,
                isBrowserOpen = false,
                statusMessage = "Finding a BitVoid gateway on $activeSsid…",
                errorMessage = null
            )
        }

        val foundUrl = discoverNodeOnLink(link, preferredPortalUrl = null)
        if (foundUrl == null) {
            BitVoidNodeStateHolder.updateClientState {
                it.copy(
                    connectionStatus = ClientConnectionStatus.ERROR,
                    currentWifiSsid = link.ssid,
                    connectedSsid = null,
                    errorMessage = "No BitVoid node was found on $activeSsid. Start the Host gateway on another phone connected to this Wi-Fi."
                )
            }
            return
        }

        // Route this app through the already-connected Wi-Fi, even when Android prefers mobile data.
        connectivityManager.bindProcessToNetwork(link.network)
        publishLinkedNode(link, foundUrl, silent = false)
    }

    /** Finds and links a BitVoid host without exposing discovery progress in the UI. */
    suspend fun discoverAndLinkSilently(): String? {
        val state = BitVoidNodeStateHolder.clientState.value
        if (state.connectionStatus == ClientConnectionStatus.CONNECTING || state.isScanning) return null

        val link = resolveCurrentWifiLink()
        if (link == null) {
            if (state.connectedSsid != null) {
                connectivityManager.bindProcessToNetwork(null)
                BitVoidNodeStateHolder.updateClientState {
                    it.copy(
                        connectionStatus = ClientConnectionStatus.DISCONNECTED,
                        currentWifiSsid = null,
                        connectedSsid = null,
                        statusMessage = "Waiting for the phone's Wi-Fi connection.",
                        errorMessage = null
                    )
                }
            }
            return null
        }

        val sameWifi = state.connectedSsid != null &&
            (link.ssid == null || state.connectedSsid.equals(link.ssid, ignoreCase = true))
        if (state.connectionStatus == ClientConnectionStatus.CONNECTED && sameWifi) {
            val existing = normalizeLocalPortalUrl(state.targetPortalUrl)
            if (existing != null && probeBitVoidNode(link.network, existing)) {
                return existing
            }
        } else if (state.connectedSsid != null && !sameWifi) {
            connectivityManager.bindProcessToNetwork(null)
            BitVoidNodeStateHolder.updateClientState {
                it.copy(
                    connectionStatus = ClientConnectionStatus.DISCONNECTED,
                    currentWifiSsid = link.ssid,
                    connectedSsid = null,
                    statusMessage = "Wi-Fi changed. Ready on ${link.ssid ?: "the current network"}.",
                    errorMessage = null
                )
            }
        }

        val foundUrl = discoverNodeOnLink(link, state.targetPortalUrl) ?: return null
        connectivityManager.bindProcessToNetwork(link.network)
        publishLinkedNode(link, foundUrl, silent = true)
        return foundUrl
    }

    private fun publishLinkedNode(link: CurrentWifiLink, foundUrl: String, silent: Boolean) {
        val activeSsid = link.ssid ?: "Current Wi-Fi"
        BitVoidNodeStateHolder.updateClientState {
            it.copy(
                connectionStatus = ClientConnectionStatus.CONNECTED,
                currentWifiSsid = link.ssid,
                connectedSsid = activeSsid,
                targetPortalUrl = foundUrl,
                isBrowserOpen = false,
                statusMessage = if (silent) {
                    "Linked to a BitVoid node on $activeSsid."
                } else {
                    "Gateway linked through $activeSsid. Open Hub to access it."
                },
                errorMessage = null
            )
        }
    }

    private suspend fun discoverNodeOnLink(
        link: CurrentWifiLink,
        preferredPortalUrl: String?
    ): String? = discoveryMutex.withLock {
        withContext(Dispatchers.IO) {
            val candidates = buildList {
                normalizeLocalPortalUrl(preferredPortalUrl.orEmpty())?.let { preferred ->
                    val host = android.net.Uri.parse(preferred).host
                    if (host != null && host != "127.0.0.1" && host != "localhost") add(preferred.trimEnd('/'))
                }
                buildSubnetCandidateHosts(link.localIpv4, link.gatewayHosts).forEach { host ->
                    add("http://$host:8080")
                }
            }.distinct()

            for (batch in candidates.chunked(DISCOVERY_BATCH_SIZE)) {
                val matches = coroutineScope {
                    batch.map { candidate ->
                        async { candidate.takeIf { probeBitVoidNode(link.network, it) } }
                    }.awaitAll()
                }
                matches.firstOrNull { it != null }?.let { return@withContext it }
            }
            null
        }
    }

    private fun probeBitVoidNode(network: Network, baseUrl: String): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val endpoint = URL("${baseUrl.trimEnd('/')}/api/node")
            connection = network.openConnection(endpoint) as HttpURLConnection
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = DISCOVERY_CONNECT_TIMEOUT_MS
            connection.readTimeout = DISCOVERY_READ_TIMEOUT_MS
            connection.setRequestProperty("X-BitVoid-Discovery", "1")
            connection.setRequestProperty("User-Agent", "BitVoid-Discovery/1.0")
            connection.responseCode == HttpURLConnection.HTTP_OK &&
                connection.getHeaderField("X-BitVoid-Node") == "1"
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    fun disconnectFromNode() {
        connectivityManager.bindProcessToNetwork(null)
        BitVoidNodeStateHolder.updateClientState {
            it.copy(
                connectionStatus = ClientConnectionStatus.DISCONNECTED,
                connectedSsid = null,
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

    fun release() {
        unregisterScanReceiver()
        disconnectFromNode()
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

        fun resolvePortalUrlAfterConnect(
            normalizedRequestedUrl: String,
            wasDefaultPlaceholder: Boolean,
            linkGatewayIp: String?
        ): String {
            val cleanGw = linkGatewayIp?.trim()?.takeIf { it.isNotEmpty() && isPrivateLocalHost(it) }
            return if (cleanGw != null && wasDefaultPlaceholder) {
                "http://$cleanGw:8080"
            } else {
                normalizedRequestedUrl
            }
        }

        fun normalizeLocalPortalUrl(rawInput: String): String? {
            val trimmed = rawInput.trim()
            if (trimmed.isEmpty()) return "http://192.168.43.1:8080"
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
