package com.bitvoid.network

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale


enum class NodeStatus {
    STOPPED,
    STARTING,
    WAITING_FOR_HOTSPOT,
    RUNNING,
    ERROR,
}

enum class NodeNetworkMode {
    LOCAL_ONLY_HOTSPOT,
    SYSTEM_OPEN_HOTSPOT,
}

data class BitVoidNodeState(
    val status: NodeStatus = NodeStatus.STOPPED,
    val ssid: String = BitVoidNetworkConfig.DEFAULT_SSID,
    val passphrase: String = NO_PASSWORD_LABEL,
    val gatewayIp: String = DEFAULT_GATEWAY_IP,
    val portalUrl: String = "http://$DEFAULT_GATEWAY_IP:${BitVoidLocalServer.DEFAULT_PORT}",
    val serverOnline: Boolean = false,
    val sharedFileCount: Int = 0,
    val networkMode: NodeNetworkMode = NodeNetworkMode.SYSTEM_OPEN_HOTSPOT,
    val securityMode: HotspotSecurityMode = HotspotSecurityMode.OPEN_VISIBLE,
    val networkNotice: String? = null,
    val errorMessage: String? = null,
) {
    val isActive: Boolean
        get() = status == NodeStatus.STARTING ||
            status == NodeStatus.WAITING_FOR_HOTSPOT ||
            status == NodeStatus.RUNNING

    companion object {
        const val DEFAULT_GATEWAY_IP = "192.168.43.1"
        const val NO_PASSWORD_LABEL = "NO PASSWORD REQUIRED"
    }
}

/**
 * Runs either Android's visible WPA2 Local Only Hotspot or a visible system-managed
 * Open hotspot. Open mode never attempts to create or retain a password-protected AP.
 */
class BitVoidForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var wifiManager: WifiManager
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var localServer: BitVoidLocalServer? = null
    private var startupJob: Job? = null
    private var interfaceMonitorJob: Job? = null
    private var openHotspotMonitorJob: Job? = null
    private var nodeRequested = false
    private var hotspotRequestInFlight = false
    private var generation = 0L
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        BitVoidNetworkConfig.initialize(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START_NODE) {
            ACTION_START_NODE -> {
                nodeRequested = true
                if (!startForegroundSafely()) return START_NOT_STICKY
                startNodeIfNeeded()
            }

            ACTION_REFRESH_FILES -> {
                val files = SharedFileStore.refresh(applicationContext)
                val videos = BitVoidMediaManager.synchronize(applicationContext, files)
                updateState { it.copy(sharedFileCount = videos.size) }
                if (foregroundStarted) updateNotification()
            }

            ACTION_STOP_NODE -> {
                nodeRequested = false
                stopNode(updateStoppedState = true)
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
                stopSelf()
            }
        }
        return if (nodeRequested) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        nodeRequested = false
        stopNode(updateStoppedState = nodeState.value.status != NodeStatus.ERROR)
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startNodeIfNeeded() {
        if (hotspotReservation != null || hotspotRequestInFlight || localServer != null ||
            nodeState.value.isActive) {
            return
        }

        generation += 1
        val currentGeneration = generation
        val selectedMode = BitVoidNetworkConfig.state.value.securityMode
        val files = SharedFileStore.refresh(applicationContext)
        val videos = BitVoidMediaManager.synchronize(applicationContext, files)
        updateState {
            BitVoidNodeState(
                status = NodeStatus.STARTING,
                sharedFileCount = videos.size,
                securityMode = selectedMode,
                networkMode = if (selectedMode == HotspotSecurityMode.WPA2_VISIBLE) {
                    NodeNetworkMode.LOCAL_ONLY_HOTSPOT
                } else {
                    NodeNetworkMode.SYSTEM_OPEN_HOTSPOT
                },
            )
        }
        updateNotification()

        when (selectedMode) {
            HotspotSecurityMode.WPA2_VISIBLE -> startWpa2LocalOnlyHotspot(currentGeneration)
            HotspotSecurityMode.OPEN_VISIBLE -> waitForOpenSystemHotspot(
                startupGeneration = currentGeneration,
                notice = OPEN_HOTSPOT_SETUP_NOTICE,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun startWpa2LocalOnlyHotspot(startupGeneration: Long) {
        hotspotRequestInFlight = true
        try {
            wifiManager.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        hotspotRequestInFlight = false
                        if (!nodeRequested || startupGeneration != generation) {
                            reservation.close()
                            return
                        }
                        val credentials = readHotspotCredentials(reservation)
                        if (credentials.isHiddenSsid) {
                            failStartup("Android generated a hidden hotspot. BitVoid requires a visible SSID and closed it.")
                            reservation.close()
                            return
                        }
                        if (credentials.passphrase.isBlank()) {
                            failStartup("Android did not provide the WPA2 passphrase for the visible hotspot.")
                            reservation.close()
                            return
                        }
                        hotspotReservation = reservation
                        beginServerStartup(
                            ssid = credentials.ssid,
                            passphrase = credentials.passphrase,
                            securityMode = HotspotSecurityMode.WPA2_VISIBLE,
                            startupGeneration = startupGeneration,
                            networkMode = NodeNetworkMode.LOCAL_ONLY_HOTSPOT,
                            preferredGatewayIp = null,
                            notice = "Visible Android Local Only Hotspot active with a generated WPA2 passphrase.",
                        )
                    }

                    override fun onStopped() {
                        hotspotRequestInFlight = false
                        hotspotReservation = null
                        if (nodeRequested && startupGeneration == generation) {
                            failStartup("The Android WPA2 Local Only Hotspot stopped.")
                        }
                    }

                    override fun onFailed(reason: Int) {
                        hotspotRequestInFlight = false
                        hotspotReservation = null
                        if (nodeRequested && startupGeneration == generation) {
                            failStartup(hotspotFailureMessage(reason))
                        }
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (_: SecurityException) {
            hotspotRequestInFlight = false
            failStartup("Android denied permission to create the visible WPA2 Local Only Hotspot.")
        } catch (error: IllegalStateException) {
            hotspotRequestInFlight = false
            failStartup(error.message ?: "The visible WPA2 Local Only Hotspot is unavailable.")
        }
    }

    private fun waitForOpenSystemHotspot(startupGeneration: Long, notice: String) {
        openHotspotMonitorJob?.cancel()
        openHotspotMonitorJob = null
        if (!nodeRequested || startupGeneration != generation) return
        updateState {
            it.copy(
                status = NodeStatus.WAITING_FOR_HOTSPOT,
                ssid = BitVoidNetworkConfig.DEFAULT_SSID,
                passphrase = BitVoidNodeState.NO_PASSWORD_LABEL,
                gatewayIp = BitVoidNodeState.DEFAULT_GATEWAY_IP,
                portalUrl = BitVoidLocalServer.DEFAULT_PORTAL_URL,
                serverOnline = localServer != null,
                networkMode = NodeNetworkMode.SYSTEM_OPEN_HOTSPOT,
                securityMode = HotspotSecurityMode.OPEN_VISIBLE,
                networkNotice = notice,
                errorMessage = null,
            )
        }
        updateNotification()
        openHotspotMonitorJob = serviceScope.launch {
            if (localServer == null) {
                try {
                    val waitingServer = BitVoidLocalServer(
                        context = applicationContext,
                        bindAddress = BitVoidLocalServer.WILDCARD_ADDRESS,
                        initialPortalUrl = BitVoidLocalServer.DEFAULT_PORTAL_URL,
                    ).also { it.startServer() }
                    if (!nodeRequested || startupGeneration != generation) {
                        waitingServer.stop()
                        return@launch
                    }
                    localServer = waitingServer
                    updateState { it.copy(serverOnline = true) }
                    updateNotification()
                } catch (error: Exception) {
                    failStartup(
                        "The local portal could not wait for the Open hotspot: ${error.message ?: "unknown server error"}",
                    )
                    return@launch
                }
            }
            while (nodeRequested && startupGeneration == generation) {
                val activeInterface = findActiveHotspotInterface()
                if (activeInterface != null) {
                    val metadata = readSystemHotspotMetadata()
                    val invalidReason = when {
                        metadata?.isHiddenSsid == true ->
                            "The active system hotspot is hidden. Turn Hidden Network Off."
                        metadata?.isOpen == false ->
                            "The active system hotspot is password protected. Set Security to None/Open."
                        else -> null
                    }
                    if (invalidReason != null) {
                        updateState { it.copy(networkNotice = invalidReason) }
                    } else {
                        val ssid = metadata?.ssid
                            ?.takeIf { it.isNotBlank() }
                            ?: BitVoidNetworkConfig.DEFAULT_SSID
                        val verificationNotice = if (metadata == null) {
                            "Detected ${activeInterface.interfaceName} at ${activeInterface.address}. Android did not expose hotspot security metadata; keep Security None/Open and Hidden Network Off."
                        } else {
                            "Detected visible Open hotspot $ssid on ${activeInterface.interfaceName}."
                        }
                        openHotspotMonitorJob = null
                        beginServerStartup(
                            ssid = ssid,
                            passphrase = BitVoidNodeState.NO_PASSWORD_LABEL,
                            securityMode = HotspotSecurityMode.OPEN_VISIBLE,
                            startupGeneration = startupGeneration,
                            networkMode = NodeNetworkMode.SYSTEM_OPEN_HOTSPOT,
                            preferredGatewayIp = activeInterface.address,
                            notice = verificationNotice,
                        )
                        return@launch
                    }
                }
                delay(OPEN_HOTSPOT_SCAN_INTERVAL_MS)
            }
        }
    }

    private fun beginServerStartup(
        ssid: String,
        passphrase: String,
        securityMode: HotspotSecurityMode,
        startupGeneration: Long,
        networkMode: NodeNetworkMode,
        preferredGatewayIp: String?,
        notice: String?,
    ) {
        startupJob?.cancel()
        startupJob = serviceScope.launch {
            val gatewayIp = preferredGatewayIp ?: resolveHotspotAddress()
            if (!nodeRequested || startupGeneration != generation) return@launch

            val portalUrl = "http://$gatewayIp:${BitVoidLocalServer.DEFAULT_PORT}"
            updateState {
                it.copy(
                    status = NodeStatus.STARTING,
                    ssid = ssid,
                    passphrase = passphrase,
                    gatewayIp = gatewayIp,
                    portalUrl = portalUrl,
                    networkMode = networkMode,
                    securityMode = securityMode,
                    networkNotice = notice,
                    errorMessage = null,
                )
            }
            updateNotification()

            try {
                val existingServer = localServer
                val server = existingServer ?: BitVoidLocalServer(
                    context = applicationContext,
                    bindAddress = BitVoidLocalServer.WILDCARD_ADDRESS,
                    initialPortalUrl = portalUrl,
                ).also { it.startServer() }
                server.updatePortalUrl(portalUrl)
                if (!nodeRequested || startupGeneration != generation) {
                    if (existingServer == null) server.stop()
                    return@launch
                }
                localServer = server
                val files = SharedFileStore.refresh(applicationContext)
                val videos = BitVoidMediaManager.synchronize(applicationContext, files)
                updateState {
                    it.copy(
                        status = NodeStatus.RUNNING,
                        serverOnline = true,
                        sharedFileCount = videos.size,
                        networkMode = networkMode,
                        securityMode = securityMode,
                        networkNotice = notice,
                        errorMessage = null,
                    )
                }
                startInterfaceMonitor(startupGeneration, networkMode)
                updateNotification()
            } catch (error: Exception) {
                failStartup(
                    if (error.message?.contains("Address already in use", ignoreCase = true) == true) {
                        "Port ${BitVoidLocalServer.DEFAULT_PORT} is already in use."
                    } else {
                        "The local portal could not start: ${error.message ?: "unknown server error"}"
                    },
                )
            }
        }
    }

    private fun startInterfaceMonitor(
        startupGeneration: Long,
        networkMode: NodeNetworkMode,
    ) {
        interfaceMonitorJob?.cancel()
        interfaceMonitorJob = serviceScope.launch {
            while (nodeRequested && startupGeneration == generation) {
                if (networkMode == NodeNetworkMode.SYSTEM_OPEN_HOTSPOT) {
                    val activeInterface = findActiveHotspotInterface()
                    if (activeInterface == null) {
                        interfaceMonitorJob = null
                        waitForOpenSystemHotspot(
                            startupGeneration,
                            "The Open hotspot interface disappeared. Turn the visible system hotspot back on.",
                        )
                        return@launch
                    }
                    updateGatewayIfChanged(activeInterface.address)
                } else {
                    findBestLocalAddress()?.let(::updateGatewayIfChanged)
                }
                delay(INTERFACE_REFRESH_MS)
            }
        }
    }

    private fun updateGatewayIfChanged(address: String) {
        if (nodeState.value.gatewayIp == address) return
        val portalUrl = "http://$address:${BitVoidLocalServer.DEFAULT_PORT}"
        updateState { current ->
            current.copy(
                gatewayIp = address,
                portalUrl = portalUrl,
            )
        }
        localServer?.updatePortalUrl(portalUrl)
        updateNotification()
    }

    private fun stopNode(updateStoppedState: Boolean) {
        generation += 1
        hotspotRequestInFlight = false
        startupJob?.cancel()
        startupJob = null
        interfaceMonitorJob?.cancel()
        interfaceMonitorJob = null
        openHotspotMonitorJob?.cancel()
        openHotspotMonitorJob = null
        stopServerOnly()
        BitVoidRoomManager.clearAll()
        val reservation = hotspotReservation
        hotspotReservation = null
        runCatching { reservation?.close() }
        if (updateStoppedState) {
            val files = SharedFileStore.refresh(applicationContext)
            val videos = BitVoidMediaManager.synchronize(applicationContext, files)
            val selectedMode = BitVoidNetworkConfig.state.value.securityMode
            _nodeState.value = BitVoidNodeState(
                status = NodeStatus.STOPPED,
                ssid = if (selectedMode == HotspotSecurityMode.OPEN_VISIBLE) {
                    BitVoidNetworkConfig.DEFAULT_SSID
                } else {
                    "ANDROID GENERATED ON START"
                },
                passphrase = if (selectedMode == HotspotSecurityMode.OPEN_VISIBLE) {
                    BitVoidNodeState.NO_PASSWORD_LABEL
                } else {
                    "ANDROID GENERATED ON START"
                },
                sharedFileCount = videos.size,
                securityMode = selectedMode,
                networkMode = if (selectedMode == HotspotSecurityMode.WPA2_VISIBLE) {
                    NodeNetworkMode.LOCAL_ONLY_HOTSPOT
                } else {
                    NodeNetworkMode.SYSTEM_OPEN_HOTSPOT
                },
            )
        }
    }

    private fun stopServerOnly() {
        val server = localServer
        localServer = null
        runCatching { server?.stop() }
        updateState { it.copy(serverOnline = false) }
    }

    private fun failStartup(message: String) {
        nodeRequested = false
        hotspotRequestInFlight = false
        startupJob?.cancel()
        startupJob = null
        interfaceMonitorJob?.cancel()
        interfaceMonitorJob = null
        openHotspotMonitorJob?.cancel()
        openHotspotMonitorJob = null
        stopServerOnly()
        val reservation = hotspotReservation
        hotspotReservation = null
        runCatching { reservation?.close() }
        updateState {
            it.copy(
                status = NodeStatus.ERROR,
                serverOnline = false,
                errorMessage = message,
            )
        }
        updateNotification()
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun readHotspotCredentials(
        reservation: WifiManager.LocalOnlyHotspotReservation,
    ): HotspotCredentials {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val configuration = reservation.softApConfiguration
            HotspotCredentials(
                ssid = configuration.ssid ?: "Android Local Hotspot",
                passphrase = configuration.passphrase.orEmpty(),
                isHiddenSsid = configuration.isHiddenSsid,
            )
        } else {
            val configuration = reservation.wifiConfiguration
            HotspotCredentials(
                ssid = configuration.SSID.orEmpty().trim('"').ifBlank { "Android Local Hotspot" },
                passphrase = configuration.preSharedKey.orEmpty().trim('"'),
                isHiddenSsid = configuration.hiddenSSID,
            )
        }
    }

    @SuppressLint("PrivateApi", "SoonBlockedPrivateApi")
    private fun readSystemHotspotMetadata(): SystemHotspotMetadata? = runCatching {
        val method = WifiManager::class.java.getMethod("getSoftApConfiguration")
        val configuration = method.invoke(wifiManager) ?: return@runCatching null
        val configurationClass = configuration.javaClass
        val ssid = configurationClass.getMethod("getSsid").invoke(configuration) as? String
        val securityType = configurationClass.getMethod("getSecurityType")
            .invoke(configuration) as Int
        val isHiddenSsid = configurationClass.getMethod("isHiddenSsid")
            .invoke(configuration) as Boolean
        SystemHotspotMetadata(
            ssid = ssid,
            isOpen = securityType == SOFT_AP_SECURITY_TYPE_OPEN,
            isHiddenSsid = isHiddenSsid,
        )
    }.getOrNull()

    private suspend fun resolveHotspotAddress(): String {
        repeat(12) {
            findBestLocalAddress()?.let { return it }
            delay(250)
        }
        return BitVoidNodeState.DEFAULT_GATEWAY_IP
    }

    private fun findActiveHotspotInterface(): ActiveHotspotInterface? = runCatching {
        localIpv4Interfaces()
            .mapNotNull { (network, address) ->
                val name = network.name.lowercase(Locale.US)
                if (name.contains("rmnet") || name.contains("tun") || name.contains("p2p")) {
                    return@mapNotNull null
                }
                val apNamed = name.contains("ap") ||
                    name.contains("soft") ||
                    name.contains("tether") ||
                    name.contains("swlan")
                val gatewayShaped = address.endsWith(".1") &&
                    (name.contains("wlan") || name.contains("wifi") || apNamed)
                if (!apNamed && !gatewayShaped) return@mapNotNull null
                val score = (if (apNamed) 100 else 0) +
                    (if (address.endsWith(".1")) 40 else 0) +
                    (if (name.contains("wlan") || name.contains("wifi")) 10 else 0)
                score to ActiveHotspotInterface(network.name, address)
            }
            .maxByOrNull { it.first }
            ?.second
    }.getOrNull()

    private fun findBestLocalAddress(): String? = runCatching {
        localIpv4Interfaces()
            .map { (network, address) ->
                val interfaceName = network.name.lowercase(Locale.US)
                var score = 0
                if (interfaceName.contains("ap") || interfaceName.contains("soft")) score += 10
                if (interfaceName.contains("wlan") || interfaceName.contains("wifi")) score += 5
                if (address.endsWith(".1")) score += 4
                score to address
            }
            .maxByOrNull { it.first }
            ?.second
    }.getOrNull()

    private fun localIpv4Interfaces(): List<Pair<NetworkInterface, String>> =
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { network ->
                Collections.list(network.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                    .mapNotNull { address -> address.hostAddress?.let { network to it } }
            }

    private fun hotspotFailureMessage(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
            "No Wi-Fi channel is available. Disable another hotspot or Wi-Fi Direct session."

        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "The current Wi-Fi mode is incompatible with a WPA2 Local Only Hotspot."

        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
            "This device policy does not allow a Local Only Hotspot."

        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC ->
            "Android could not create the visible WPA2 Local Only Hotspot."

        else -> "Local Only Hotspot failed with system code $reason."
    }

    private fun startForegroundSafely(): Boolean {
        if (foregroundStarted) return true
        return try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
            true
        } catch (_: SecurityException) {
            _nodeState.value = BitVoidNodeState(
                status = NodeStatus.ERROR,
                securityMode = BitVoidNetworkConfig.state.value.securityMode,
                errorMessage = "Android blocked the connected-device service. Check BitVoid permissions.",
            )
            stopSelf()
            false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "BitVoid local node",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the BitVoid hotspot and local portal active"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val state = nodeState.value
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BitVoidForegroundService::class.java).setAction(ACTION_STOP_NODE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = when (state.status) {
            NodeStatus.RUNNING -> "${state.securityMode.name.replace('_', ' ')} · ${state.portalUrl}"
            NodeStatus.STARTING -> "Starting the selected visible hotspot mode…"
            NodeStatus.WAITING_FOR_HOTSPOT -> "Open Android hotspot settings to continue"
            NodeStatus.ERROR -> state.errorMessage ?: "Local node needs attention"
            NodeStatus.STOPPED -> "Local node is idle"
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("BitVoid · ${state.status.name}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppIntent)
            .addAction(0, "Stop node", stopIntent)
            .setOngoing(state.isActive)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    private fun updateNotification() {
        if (!foregroundStarted) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun updateState(transform: (BitVoidNodeState) -> BitVoidNodeState) {
        _nodeState.update(transform)
    }

    private data class HotspotCredentials(
        val ssid: String,
        val passphrase: String,
        val isHiddenSsid: Boolean,
    )

    private data class SystemHotspotMetadata(
        val ssid: String?,
        val isOpen: Boolean,
        val isHiddenSsid: Boolean,
    )

    private data class ActiveHotspotInterface(
        val interfaceName: String,
        val address: String,
    )

    companion object {
        const val ACTION_START_NODE = "com.bitvoid.network.action.START_NODE"
        const val ACTION_STOP_NODE = "com.bitvoid.network.action.STOP_NODE"
        const val ACTION_REFRESH_FILES = "com.bitvoid.network.action.REFRESH_FILES"

        private const val NOTIFICATION_CHANNEL_ID = "bitvoid_node"
        private const val NOTIFICATION_ID = 8080
        private const val INTERFACE_REFRESH_MS = 2_000L
        private const val OPEN_HOTSPOT_SCAN_INTERVAL_MS = 1_500L
        private const val SOFT_AP_SECURITY_TYPE_OPEN = 0
        private const val OPEN_HOTSPOT_SETUP_NOTICE =
            "Turn on the Android hotspot with Security None/Open and Hidden Network Off. BitVoid will detect the AP interface automatically."

        private val _nodeState = MutableStateFlow(BitVoidNodeState())
        val nodeState: StateFlow<BitVoidNodeState> = _nodeState.asStateFlow()

        fun updateIdleNetworkMode(mode: HotspotSecurityMode) {
            if (_nodeState.value.isActive) return
            _nodeState.update { current ->
                current.copy(
                    securityMode = mode,
                    networkMode = if (mode == HotspotSecurityMode.WPA2_VISIBLE) {
                        NodeNetworkMode.LOCAL_ONLY_HOTSPOT
                    } else {
                        NodeNetworkMode.SYSTEM_OPEN_HOTSPOT
                    },
                    ssid = if (mode == HotspotSecurityMode.OPEN_VISIBLE) {
                        BitVoidNetworkConfig.DEFAULT_SSID
                    } else {
                        "ANDROID GENERATED ON START"
                    },
                    passphrase = if (mode == HotspotSecurityMode.OPEN_VISIBLE) {
                        BitVoidNodeState.NO_PASSWORD_LABEL
                    } else {
                        "ANDROID GENERATED ON START"
                    },
                )
            }
        }
    }
}
