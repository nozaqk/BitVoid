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
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
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
import java.net.Inet4Address
import java.net.InetAddress

class BitVoidClientManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var scanReceiver: BroadcastReceiver? = null
    private var activeNetworkCallback: ConnectivityManager.NetworkCallback? = null

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
                    errorMessage = "Fine Location permission is required by Android to read nearby Wi-Fi SSIDs."
                )
            }
            return
        }

        BitVoidNodeStateHolder.updateClientState {
            it.copy(
                isScanning = true,
                connectionStatus = ClientConnectionStatus.SCANNING,
                statusMessage = "Scanning local 2.4 / 5 GHz spectrum for BitVoid nodes...",
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
            // Android throttles background scans; read cached scanResults cleanly
            unregisterScanReceiver()
            publishScanResults()
        }
    }

    @SuppressLint("MissingPermission")
    private fun publishScanResults() {
        val rawResults: List<ScanResult> = try {
            wifiManager.scanResults ?: emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }

        val mapped = rawResults
            .asSequence()
            .filter { result ->
                val ssid = extractSsid(result)
                ssid.isNotBlank()
            }
            .distinctBy { extractSsid(it) }
            .map { result ->
                val ssid = extractSsid(result)
                val caps = result.capabilities ?: ""
                val isCandidate = ssid.contains("BitVoid", ignoreCase = true) ||
                    ssid.startsWith("AndroidShare", ignoreCase = true) ||
                    ssid.startsWith("DIRECT-", ignoreCase = true)
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
                    isBitVoidCandidate = isCandidate
                )
            }
            .sortedWith(
                compareByDescending<DiscoveredNetwork> { it.isBitVoidCandidate }
                    .thenByDescending { it.signalDbm }
            )
            .toList()

        BitVoidNodeStateHolder.updateClientState { state ->
            state.copy(
                isScanning = false,
                connectionStatus = if (state.connectedSsid != null) {
                    ClientConnectionStatus.CONNECTED
                } else {
                    ClientConnectionStatus.DISCONNECTED
                },
                discoveredNetworks = mapped,
                statusMessage = if (mapped.isEmpty()) {
                    "No nearby Wi-Fi beacons reported. Ensure Wi-Fi is enabled or enter SSID manually."
                } else {
                    "Detected ${mapped.size} nearby Wi-Fi network(s)."
                }
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

    fun connectToNode(ssid: String, passphrase: String, gatewayHostPort: String) {
        val cleanSsid = ssid.trim()
        if (cleanSsid.isEmpty()) {
            BitVoidNodeStateHolder.updateClientState {
                it.copy(
                    connectionStatus = ClientConnectionStatus.ERROR,
                    errorMessage = "Enter a valid target SSID to connect."
                )
            }
            return
        }

        val normalizedUrl = normalizeLocalPortalUrl(gatewayHostPort)
        if (normalizedUrl == null) {
            BitVoidNodeStateHolder.updateClientState {
                it.copy(
                    connectionStatus = ClientConnectionStatus.ERROR,
                    errorMessage = "Target portal must be a private RFC1918 or loopback address (e.g., 192.168.43.1:8080)."
                )
            }
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            BitVoidNodeStateHolder.updateClientState {
                it.copy(
                    connectionStatus = ClientConnectionStatus.DISCONNECTED,
                    targetPortalUrl = normalizedUrl,
                    statusMessage = "Android 8-9 requires joining '$cleanSsid' via system Wi-Fi settings, then launching the sandboxed browser."
                )
            }
            openSystemWifiSettings()
            return
        }

        disconnectFromNode()

        BitVoidNodeStateHolder.updateClientState {
            it.copy(
                connectionStatus = ClientConnectionStatus.CONNECTING,
                targetPortalUrl = normalizedUrl,
                statusMessage = "Negotiating isolated Wi-Fi link to $cleanSsid...",
                errorMessage = null
            )
        }

        val isDefaultPortalPlaceholder = gatewayHostPort.isBlank() ||
            normalizedUrl == "http://192.168.43.1:8080" ||
            normalizedUrl == "http://127.0.0.1:8080"

        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(cleanSsid)
        if (passphrase.isNotBlank()) {
            specifierBuilder.setWpa2Passphrase(passphrase)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                connectivityManager.bindProcessToNetwork(network)
                val gatewayIp = resolveGatewayFromLink(network)
                val resolvedUrl = resolvePortalUrlAfterConnect(
                    normalizedRequestedUrl = normalizedUrl,
                    wasDefaultPlaceholder = isDefaultPortalPlaceholder,
                    linkGatewayIp = gatewayIp
                )
                BitVoidNodeStateHolder.updateClientState {
                    it.copy(
                        connectionStatus = ClientConnectionStatus.CONNECTED,
                        connectedSsid = cleanSsid,
                        targetPortalUrl = resolvedUrl,
                        isBrowserOpen = true,
                        statusMessage = "Linked to isolated node '$cleanSsid' (Zero-Internet Route Active).",
                        errorMessage = null
                    )
                }
            }

            override fun onUnavailable() {
                super.onUnavailable()
                connectivityManager.bindProcessToNetwork(null)
                BitVoidNodeStateHolder.updateClientState {
                    it.copy(
                        connectionStatus = ClientConnectionStatus.ERROR,
                        connectedSsid = null,
                        errorMessage = "Connection to '$cleanSsid' was declined or timed out."
                    )
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                connectivityManager.bindProcessToNetwork(null)
                BitVoidNodeStateHolder.updateClientState {
                    it.copy(
                        connectionStatus = ClientConnectionStatus.DISCONNECTED,
                        connectedSsid = null,
                        statusMessage = "Local Wi-Fi link closed."
                    )
                }
            }
        }

        activeNetworkCallback = callback
        try {
            connectivityManager.requestNetwork(request, callback)
        } catch (e: SecurityException) {
            activeNetworkCallback = null
            BitVoidNodeStateHolder.updateClientState {
                it.copy(
                    connectionStatus = ClientConnectionStatus.ERROR,
                    errorMessage = "Permission denied requesting Wi-Fi network: ${e.localizedMessage}"
                )
            }
        }
    }

    fun disconnectFromNode() {
        connectivityManager.bindProcessToNetwork(null)
        activeNetworkCallback?.let { cb ->
            try {
                connectivityManager.unregisterNetworkCallback(cb)
            } catch (_: Exception) {
            }
        }
        activeNetworkCallback = null
        BitVoidNodeStateHolder.updateClientState {
            it.copy(
                connectionStatus = ClientConnectionStatus.DISCONNECTED,
                connectedSsid = null,
                statusMessage = "Disconnected from peer node."
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

    private fun resolveGatewayFromLink(network: Network): String? {
        return try {
            val linkProps = connectivityManager.getLinkProperties(network)
            val defaultRouteGateway = linkProps?.routes?.firstOrNull { route ->
                route.isDefaultRoute &&
                    route.gateway is Inet4Address &&
                    !(route.gateway as Inet4Address).isAnyLocalAddress
            }?.gateway?.hostAddress
            if (!defaultRouteGateway.isNullOrBlank()) return defaultRouteGateway

            val anyRouteGateway = linkProps?.routes?.firstOrNull { route ->
                route.gateway is Inet4Address && !(route.gateway as Inet4Address).isAnyLocalAddress
            }?.gateway?.hostAddress
            if (!anyRouteGateway.isNullOrBlank()) return anyRouteGateway

            val dhcpAddress = (linkProps?.dhcpServerAddress as? Inet4Address)?.hostAddress
            if (!dhcpAddress.isNullOrBlank() && dhcpAddress != "0.0.0.0") return dhcpAddress

            val localV4 = linkProps?.linkAddresses?.firstOrNull { la ->
                la.address is Inet4Address && !la.address.isLoopbackAddress
            }?.address?.hostAddress
            if (!localV4.isNullOrBlank() && isPrivateLocalHost(localV4)) {
                val prefix = localV4.substringBeforeLast('.', "")
                if (prefix.isNotBlank()) return "$prefix.1"
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val SANITIZED_USER_AGENT = "BitVoid/1.0 (Isolated)"

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
