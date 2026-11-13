package com.bitvoid.network

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update


data class DiscoveredBitVoidNetwork(
    val ssid: String,
    val signalLevel: Int,
    val secured: Boolean,
    val isBitVoidNamed: Boolean,
)

data class BitVoidClientState(
    val isScanning: Boolean = false,
    val isConnecting: Boolean = false,
    val isConnected: Boolean = false,
    val connectedSsid: String? = null,
    val portalUrl: String = "http://${BitVoidNodeState.DEFAULT_GATEWAY_IP}:${BitVoidLocalServer.DEFAULT_PORT}",
    val networks: List<DiscoveredBitVoidNetwork> = emptyList(),
    val errorMessage: String? = null,
)

/** Discovers nearby peers and binds this app to a selected local-only Wi-Fi network. */
class BitVoidClientManager(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(BitVoidClientState())
    val state: StateFlow<BitVoidClientState> = _state.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var legacyNetworkId: Int? = null
    private var requestedSsid: String? = null
    private var receiverRegistered = false

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                publishScanResults()
            }
        }
    }

    init {
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Wi-Fi scan completion is a protected system broadcast. EXPORTED is
            // required on some vendor builds because it originates outside this app.
            appContext.registerReceiver(scanReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(scanReceiver, filter)
        }
        receiverRegistered = true
    }

    @SuppressLint("MissingPermission")
    fun scan() {
        if (!hasScanPermission()) {
            updateState {
                it.copy(
                    isScanning = false,
                    errorMessage = "Nearby Wi-Fi and Location permission are required to scan.",
                )
            }
            return
        }
        updateState { it.copy(isScanning = true, errorMessage = null) }
        try {
            val accepted = wifiManager.startScan()
            if (!accepted) {
                // Android throttles active scans. Cached results are still useful and
                // avoid repeatedly probing the radio.
                publishScanResults("Android throttled the scan; showing cached networks.")
            }
        } catch (error: SecurityException) {
            updateState { it.copy(isScanning = false, errorMessage = "Wi-Fi scan permission was denied.") }
        }
    }

    fun connect(ssid: String, passphrase: String) {
        val cleanSsid = ssid.trim()
        if (cleanSsid.isBlank()) {
            updateState { it.copy(errorMessage = "Enter the host SSID before connecting.") }
            return
        }
        if (!hasConnectPermission()) {
            updateState { it.copy(errorMessage = "Nearby Wi-Fi permission is required to connect.") }
            return
        }

        disconnect(clearMessage = false)
        requestedSsid = cleanSsid
        updateState {
            it.copy(
                isConnecting = true,
                isConnected = false,
                connectedSsid = null,
                errorMessage = null,
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectWithSpecifier(cleanSsid, passphrase)
        } else {
            connectLegacy(cleanSsid, passphrase)
        }
    }

    fun disconnect(clearMessage: Boolean = true) {
        mainHandler.removeCallbacksAndMessages(null)
        networkCallback?.let { callback ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        connectivityManager.bindProcessToNetwork(null)
        legacyNetworkId?.let { id ->
            @Suppress("DEPRECATION")
            runCatching {
                wifiManager.disableNetwork(id)
                wifiManager.removeNetwork(id)
            }
        }
        legacyNetworkId = null
        requestedSsid = null
        updateState {
            BitVoidClientState(
                networks = it.networks,
                errorMessage = if (clearMessage) null else it.errorMessage,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectWithSpecifier(ssid: String, passphrase: String) {
        try {
            val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (passphrase.isNotEmpty()) specifierBuilder.setWpa2Passphrase(passphrase)
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifierBuilder.build())
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (networkCallback !== this) return
                    connectivityManager.bindProcessToNetwork(network)
                    val portal = portalUrl(resolveGateway(network))
                    updateState {
                        it.copy(
                            isConnecting = false,
                            isConnected = true,
                            connectedSsid = ssid,
                            portalUrl = portal,
                            errorMessage = null,
                        )
                    }
                }

                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    if (networkCallback !== this) return
                    val portal = portalUrl(resolveGateway(linkProperties))
                    updateState { it.copy(portalUrl = portal) }
                }

                override fun onLost(network: Network) {
                    if (networkCallback !== this) return
                    connectivityManager.bindProcessToNetwork(null)
                    updateState {
                        it.copy(
                            isConnecting = false,
                            isConnected = false,
                            connectedSsid = null,
                            errorMessage = "The BitVoid host connection ended.",
                        )
                    }
                }

                override fun onUnavailable() {
                    if (networkCallback !== this) return
                    updateState {
                        it.copy(
                            isConnecting = false,
                            isConnected = false,
                            connectedSsid = null,
                            errorMessage = "The network request was declined or timed out.",
                        )
                    }
                }
            }
            networkCallback = callback
            connectivityManager.requestNetwork(request, callback, CONNECTION_TIMEOUT_MS)
        } catch (error: IllegalArgumentException) {
            updateState {
                it.copy(
                    isConnecting = false,
                    errorMessage = error.message ?: "The SSID or passphrase is invalid.",
                )
            }
        } catch (error: SecurityException) {
            updateState {
                it.copy(isConnecting = false, errorMessage = "Android denied the Wi-Fi connection request.")
            }
        }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun connectLegacy(ssid: String, passphrase: String) {
        try {
            val configuration = WifiConfiguration().apply {
                SSID = quoteWifiValue(ssid)
                if (passphrase.isBlank()) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = quoteWifiValue(passphrase)
                }
            }
            val networkId = wifiManager.addNetwork(configuration)
            if (networkId < 0 || !wifiManager.enableNetwork(networkId, true) || !wifiManager.reconnect()) {
                if (networkId >= 0) wifiManager.removeNetwork(networkId)
                updateState {
                    it.copy(isConnecting = false, errorMessage = "Android could not join the selected network.")
                }
                return
            }
            legacyNetworkId = networkId
            mainHandler.postDelayed({
                if (legacyNetworkId == networkId && requestedSsid == ssid) {
                    updateState {
                        it.copy(
                            isConnecting = false,
                            isConnected = true,
                            connectedSsid = ssid,
                            portalUrl = portalUrl(resolveLegacyGateway()),
                            errorMessage = null,
                        )
                    }
                }
            }, LEGACY_CONNECT_DELAY_MS)
        } catch (error: SecurityException) {
            updateState {
                it.copy(isConnecting = false, errorMessage = "Android denied the Wi-Fi connection request.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun publishScanResults(message: String? = null) {
        try {
            val networks = wifiManager.scanResults
                .asSequence()
                .filter { scan -> scan.networkName().isNotBlank() }
                .groupBy { scan -> scan.networkName() }
                .mapNotNull { (ssid, matching) ->
                    val strongest = matching.maxByOrNull { it.level } ?: return@mapNotNull null
                    DiscoveredBitVoidNetwork(
                        ssid = ssid,
                        signalLevel = WifiManager.calculateSignalLevel(strongest.level, 4),
                        secured = listOf("WEP", "WPA", "SAE", "EAP", "OWE")
                            .any { strongest.capabilities.contains(it, ignoreCase = true) },
                        isBitVoidNamed = ssid.contains("BitVoid", ignoreCase = true),
                    )
                }
                .sortedWith(
                    compareByDescending<DiscoveredBitVoidNetwork> { it.isBitVoidNamed }
                        .thenByDescending { it.signalLevel },
                )
            updateState {
                it.copy(
                    isScanning = false,
                    networks = networks,
                    errorMessage = message,
                )
            }
        } catch (error: SecurityException) {
            updateState { it.copy(isScanning = false, errorMessage = "Wi-Fi scan results are unavailable.") }
        }
    }

    private fun resolveGateway(network: Network): String {
        val properties = connectivityManager.getLinkProperties(network)
        return if (properties == null) resolveLegacyGateway() else resolveGateway(properties)
    }

    private fun resolveGateway(properties: LinkProperties): String {
        return properties.routes
            .firstOrNull { route -> route.gateway?.hostAddress?.contains('.') == true }
            ?.gateway
            ?.hostAddress
            ?: resolveLegacyGateway()
    }

    @Suppress("DEPRECATION")
    private fun resolveLegacyGateway(): String {
        val address = wifiManager.dhcpInfo?.gateway ?: 0
        if (address == 0) return BitVoidNodeState.DEFAULT_GATEWAY_IP
        return listOf(
            address and 0xff,
            address shr 8 and 0xff,
            address shr 16 and 0xff,
            address shr 24 and 0xff,
        ).joinToString(".")
    }

    private fun hasScanPermission(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val nearby = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED
        return fineLocation && nearby
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED

    private fun portalUrl(gateway: String): String =
        "http://$gateway:${BitVoidLocalServer.DEFAULT_PORT}"

    private fun quoteWifiValue(value: String): String =
        "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun updateState(transform: (BitVoidClientState) -> BitVoidClientState) {
        _state.update(transform)
    }

    override fun close() {
        disconnect()
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(scanReceiver) }
            receiverRegistered = false
        }
    }

    @Suppress("DEPRECATION")
    private fun ScanResult.networkName(): String = SSID.orEmpty().trim('"')

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 30_000
        private const val LEGACY_CONNECT_DELAY_MS = 2_500L
    }
}
