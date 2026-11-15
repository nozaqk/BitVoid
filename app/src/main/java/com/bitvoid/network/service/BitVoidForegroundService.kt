package com.bitvoid.network.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bitvoid.network.MainActivity
import com.bitvoid.network.call.BitVoidSignalingServer
import com.bitvoid.network.channel.BitVoidChannelManager
import com.bitvoid.network.media.BitVoidMediaManager
import com.bitvoid.network.model.BitVoidNetworkConfig
import com.bitvoid.network.model.BitVoidNetworkPrefs
import com.bitvoid.network.model.HostState
import com.bitvoid.network.model.HotspotSecurityMode
import com.bitvoid.network.model.HotspotStatus
import com.bitvoid.network.server.BitVoidLocalServer
import com.bitvoid.network.R
import fi.iki.elonen.NanoHTTPD
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class BitVoidForegroundService : Service() {

    companion object {
        const val ACTION_START_HOST_NODE = "com.bitvoid.network.action.START_HOST_NODE"
        const val ACTION_START_OPEN_BROADCAST = "com.bitvoid.network.action.START_OPEN_BROADCAST"
        const val ACTION_START_SERVER_ONLY = "com.bitvoid.network.action.START_SERVER_ONLY"
        const val ACTION_STOP_NODE = "com.bitvoid.network.action.STOP_NODE"
        const val ACTION_REFRESH_FILES = "com.bitvoid.network.action.REFRESH_FILES"

        const val EXTRA_SECURITY_MODE = "com.bitvoid.network.extra.SECURITY_MODE"

        private const val CHANNEL_ID = "bitvoid_node_channel"
        private const val NOTIFICATION_ID = 7070
        const val DEFAULT_OPEN_SSID = BitVoidNetworkConfig.DEFAULT_VISIBLE_SSID

        fun startHostNode(
            context: Context,
            securityMode: HotspotSecurityMode = BitVoidNodeStateHolder.hostState.value.selectedSecurityMode
        ) {
            val intent = Intent(context, BitVoidForegroundService::class.java).apply {
                action = ACTION_START_HOST_NODE
                putExtra(EXTRA_SECURITY_MODE, securityMode.name)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startOpenBroadcast(context: Context) {
            val intent = Intent(context, BitVoidForegroundService::class.java).apply {
                action = ACTION_START_OPEN_BROADCAST
                putExtra(EXTRA_SECURITY_MODE, HotspotSecurityMode.OPEN_VISIBLE.name)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun startServerOnly(context: Context) {
            val intent = Intent(context, BitVoidForegroundService::class.java).apply {
                action = ACTION_START_SERVER_ONLY
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopNode(context: Context) {
            val intent = Intent(context, BitVoidForegroundService::class.java).apply {
                action = ACTION_STOP_NODE
            }
            context.startService(intent)
        }

        fun refreshSharedFiles(context: Context) {
            val files = BitVoidMediaManager.refreshFromDisk(context.applicationContext)
            BitVoidChannelManager.syncMediaCatalog(files)
            BitVoidNodeStateHolder.updateHostState { it.copy(sharedFiles = files) }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var uptimeJob: Job? = null
    private var ipProbeJob: Job? = null
    private var mediaSyncJob: Job? = null

    private var localServer: BitVoidLocalServer? = null
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var isP2pGroupActive: Boolean = false
    private lateinit var wifiManager: WifiManager
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiP2pManager = applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel()
        BitVoidChannelManager.ensureDefaultChannel()
        refreshFileList()
        observeMediaVisibilityChanges()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_NODE -> {
                shutdownNode()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_REFRESH_FILES -> {
                refreshFileList()
                return START_STICKY
            }

            ACTION_START_OPEN_BROADCAST -> {
                if (!promoteToForeground("Binding open AP / wlan0 gateway (0.0.0.0:8080)...")) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startOpenVisibleHostNode()
                return START_STICKY
            }

            ACTION_START_SERVER_ONLY -> {
                if (!promoteToForeground("Initializing local HTTP vault...")) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startEmbeddedServerOnly()
                return START_STICKY
            }

            ACTION_START_HOST_NODE, null -> {
                val requestedMode = intent?.getStringExtra(EXTRA_SECURITY_MODE)?.let { raw ->
                    runCatching { HotspotSecurityMode.valueOf(raw) }.getOrNull()
                } ?: BitVoidNodeStateHolder.hostState.value.selectedSecurityMode

                if (!promoteToForeground("Initializing BitVoid Host Engine (${requestedMode.name})...")) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startFullHostNode(requestedMode)
                return START_STICKY
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shutdownNode()
        mediaSyncJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeMediaVisibilityChanges() {
        mediaSyncJob?.cancel()
        mediaSyncJob = serviceScope.launch {
            BitVoidMediaManager.mediaItems.collect { items ->
                BitVoidChannelManager.syncMediaCatalog(items)
                BitVoidNodeStateHolder.updateHostState { it.copy(sharedFiles = items) }
            }
        }
    }

    private fun promoteToForeground(statusText: String): Boolean {
        val notification = buildNotification(statusText)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    )
                } catch (_: SecurityException) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    )
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: SecurityException) {
            BitVoidNodeStateHolder.updateHostState {
                it.copy(
                    status = HotspotStatus.ERROR,
                    errorMessage = "Foreground service permission blocked: ${e.localizedMessage}"
                )
            }
            false
        }
    }

    private fun startFullHostNode(mode: HotspotSecurityMode) {
        val prefsCfg = BitVoidNetworkPrefs.load(applicationContext)
        val networkConfig = BitVoidNetworkConfig(
            securityMode = mode,
            ssid = prefsCfg.ssid,
            passphrase = prefsCfg.passphrase,
            isHiddenSsid = BitVoidNetworkConfig.ENFORCED_VISIBLE_SSID
        )
        BitVoidNodeStateHolder.updateHostState {
            it.copy(
                status = HotspotStatus.STARTING,
                selectedSecurityMode = networkConfig.securityMode,
                isHiddenSsid = networkConfig.isHiddenSsid,
                isOpenBroadcastMode = networkConfig.securityMode == HotspotSecurityMode.OPEN_VISIBLE,
                needsOpenHotspotSetupBanner = false,
                securityLabel = networkConfig.securityMode.badgeLabel,
                configuredSsid = networkConfig.ssid,
                configuredPassphrase = networkConfig.passphrase,
                errorMessage = null
            )
        }
        when (networkConfig.securityMode) {
            HotspotSecurityMode.WPA2_VISIBLE -> startLocalOnlyHotspotWpa2()
            HotspotSecurityMode.OPEN_VISIBLE -> startOpenVisibleHostNode()
        }
    }

    /**
     * OPEN_VISIBLE mode:
     * If System Open Hotspot is already active, binds directly to it (zero password).
     * Otherwise broadcasts a discoverable local Wi-Fi AP/Group via WifiP2pManager / LocalOnlyHotspot
     * so nearby phones and PCs immediately see and join the network, while binding 0.0.0.0:8080.
     */
    @Suppress("MissingPermission")
    private fun startOpenVisibleHostNode() {
        releaseHotspotOnly()
        if (!ensureHttpServerRunning()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val prefsCfg = BitVoidNetworkPrefs.load(applicationContext)
        if (isSystemWifiApEnabled()) {
            val detection = detectActiveApAndWlanEndpoints()
            val primaryIp = detection.primaryIp ?: "192.168.43.1"
            val combinedIps = (detection.allEndpoints + "127.0.0.1").distinct()
            val activeSsid = detection.detectedApSsid ?: prefsCfg.ssid.ifBlank { DEFAULT_OPEN_SSID }
            val urlList = combinedIps.map { "http://$it:${BitVoidLocalServer.DEFAULT_PORT}" }

            BitVoidNodeStateHolder.updateHostState {
                it.copy(
                    status = HotspotStatus.ACTIVE,
                    isServerRunning = true,
                    isOpenBroadcastMode = true,
                    selectedSecurityMode = HotspotSecurityMode.OPEN_VISIBLE,
                    isHiddenSsid = BitVoidNetworkConfig.ENFORCED_VISIBLE_SSID,
                    needsOpenHotspotSetupBanner = false,
                    isOpenSecurity = true,
                    securityLabel = HotspotSecurityMode.OPEN_VISIBLE.badgeLabel,
                    configuredSsid = prefsCfg.ssid,
                    configuredPassphrase = prefsCfg.passphrase,
                    ssid = activeSsid,
                    passphrase = "",
                    gatewayIp = primaryIp,
                    boundEndpoints = urlList,
                    uptimeSeconds = 0L,
                    errorMessage = null
                )
            }
            startUptimeTicker()
            probeAssignedGatewayIp()
            updateNotification("OPEN ($activeSsid · No Password) · http://$primaryIp:${BitVoidLocalServer.DEFAULT_PORT}")
            return
        }

        startHardwareBroadcastCascade(
            prefsCfg = prefsCfg,
            requestedOpenMode = true
        )
    }

    private fun startEmbeddedServerOnly() {
        releaseHotspotOnly()
        if (!ensureHttpServerRunning()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val allEndpoints = (resolveAllNetworkIpv4s() + "127.0.0.1").distinct()
        val resolvedIp = allEndpoints.first()
        val activeSsid = resolveConnectedWifiSsid() ?: DEFAULT_OPEN_SSID
        val urlList = allEndpoints.map { "http://$it:${BitVoidLocalServer.DEFAULT_PORT}" }
        BitVoidNodeStateHolder.updateHostState {
            it.copy(
                status = HotspotStatus.SERVER_ONLY,
                isServerRunning = true,
                isOpenBroadcastMode = false,
                needsOpenHotspotSetupBanner = false,
                ssid = activeSsid,
                passphrase = if (resolvedIp == "127.0.0.1") "" else "Managed by Wi-Fi / LAN",
                gatewayIp = resolvedIp,
                boundEndpoints = urlList,
                uptimeSeconds = 0L,
                errorMessage = null
            )
        }
        startUptimeTicker()
        probeAssignedGatewayIp()
        updateNotification("Local Vault Online: http://$resolvedIp:${BitVoidLocalServer.DEFAULT_PORT}")
    }

    private fun ensureHttpServerRunning(): Boolean {
        return try {
            val current = localServer
            if (current == null || !current.isAlive) {
                val created = BitVoidLocalServer(applicationContext, BitVoidLocalServer.DEFAULT_PORT)
                // Timeout = 0 keeps long-lived WebSocket chat frames alive without 5s read timeouts
                created.start(0, false)
                localServer = created
            }
            refreshFileList()
            BitVoidNodeStateHolder.updateHostState {
                it.copy(isServerRunning = true)
            }
            true
        } catch (e: Exception) {
            BitVoidNodeStateHolder.updateHostState {
                it.copy(
                    status = HotspotStatus.ERROR,
                    isServerRunning = false,
                    gatewayIp = "",
                    boundEndpoints = emptyList(),
                    errorMessage = "Failed to bind HTTP server on port ${BitVoidLocalServer.DEFAULT_PORT}: ${e.localizedMessage}"
                )
            }
            false
        }
    }

    private fun hasHotspotPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    @Suppress("MissingPermission")
    private fun startLocalOnlyHotspotWpa2() {
        releaseHotspotOnly()
        if (!ensureHttpServerRunning()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val prefsCfg = BitVoidNetworkPrefs.load(applicationContext)
        startHardwareBroadcastCascade(
            prefsCfg = prefsCfg,
            requestedOpenMode = false
        )
    }

    @Suppress("MissingPermission")
    private fun startHardwareBroadcastCascade(
        prefsCfg: BitVoidNetworkConfig,
        requestedOpenMode: Boolean
    ) {
        if (!hasHotspotPermission()) {
            activateAutonomousFallback(
                prefsCfg = prefsCfg,
                requestedOpenMode = requestedOpenMode,
                radioHint = "Grant Nearby Wi-Fi / Location permission so nearby devices can discover the Wi-Fi broadcast."
            )
            return
        }

        val p2pMgr = wifiP2pManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            p2pMgr != null &&
            wifiManager.isWifiEnabled
        ) {
            try {
                val channel = wifiP2pChannel ?: p2pMgr.initialize(this, Looper.getMainLooper(), null).also {
                    wifiP2pChannel = it
                }
                if (channel != null) {
                    val customNetworkName = BitVoidNetworkConfig.formatWifiDirectNetworkName(prefsCfg.ssid)
                    val customPassphrase = prefsCfg.passphrase.ifBlank { BitVoidNetworkConfig.DEFAULT_WPA2_PASSPHRASE }
                    val config = WifiP2pConfig.Builder()
                        .setNetworkName(customNetworkName)
                        .setPassphrase(customPassphrase)
                        .build()

                    p2pMgr.removeGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            launchConfiguredP2pGroup(
                                p2pMgr = p2pMgr,
                                channel = channel,
                                config = config,
                                prefsCfg = prefsCfg,
                                expectedSsid = customNetworkName,
                                expectedPassphrase = customPassphrase,
                                requestedOpenMode = requestedOpenMode
                            )
                        }

                        override fun onFailure(reason: Int) {
                            launchConfiguredP2pGroup(
                                p2pMgr = p2pMgr,
                                channel = channel,
                                config = config,
                                prefsCfg = prefsCfg,
                                expectedSsid = customNetworkName,
                                expectedPassphrase = customPassphrase,
                                requestedOpenMode = requestedOpenMode
                            )
                        }
                    })
                    return
                }
            } catch (_: Exception) {
            }
        }

        tryStartLocalOnlyHotspot(
            prefsCfg = prefsCfg,
            requestedOpenMode = requestedOpenMode,
            priorHint = if (!wifiManager.isWifiEnabled) {
                "Device Wi-Fi is OFF."
            } else {
                null
            }
        )
    }

    @Suppress("MissingPermission")
    private fun launchConfiguredP2pGroup(
        p2pMgr: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        config: WifiP2pConfig,
        prefsCfg: BitVoidNetworkConfig,
        expectedSsid: String,
        expectedPassphrase: String,
        requestedOpenMode: Boolean
    ) {
        try {
            p2pMgr.createGroup(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    isP2pGroupActive = true
                    if (!ensureHttpServerRunning()) {
                        releaseHotspotOnly()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return
                    }

                    p2pMgr.requestGroupInfo(channel) { group ->
                        val activeSsid = group?.networkName?.takeIf { it.isNotBlank() } ?: expectedSsid
                        val activePass = group?.passphrase?.takeIf { it.isNotBlank() } ?: expectedPassphrase
                        val detection = detectActiveApAndWlanEndpoints()
                        val primaryIp = detection.primaryIp ?: "192.168.49.1"
                        val combinedIps = (detection.allEndpoints + "192.168.49.1" + "127.0.0.1").distinct()
                        publishBroadcastActiveState(
                            prefsCfg = prefsCfg,
                            requestedOpenMode = requestedOpenMode,
                            activeSsid = activeSsid,
                            activePassphrase = activePass,
                            primaryIp = primaryIp,
                            combinedIps = combinedIps,
                            isOpenSecurity = false,
                            customSecurityLabel = if (requestedOpenMode) {
                                "WIFI BROADCAST LIVE (ANDROID P2P · OS KEY SHOWN BELOW)"
                            } else {
                                HotspotSecurityMode.WPA2_VISIBLE.badgeLabel
                            },
                            radioHint = null
                        )
                    }
                }

                override fun onFailure(reason: Int) {
                    isP2pGroupActive = false
                    tryStartLocalOnlyHotspot(
                        prefsCfg = prefsCfg,
                        requestedOpenMode = requestedOpenMode,
                        priorHint = "Wi-Fi Direct code $reason"
                    )
                }
            })
        } catch (_: SecurityException) {
            isP2pGroupActive = false
            tryStartLocalOnlyHotspot(prefsCfg, requestedOpenMode, "Permission denied")
        } catch (_: IllegalArgumentException) {
            isP2pGroupActive = false
            tryStartLocalOnlyHotspot(prefsCfg, requestedOpenMode, "Invalid P2P config")
        }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun tryStartLocalOnlyHotspot(
        prefsCfg: BitVoidNetworkConfig,
        requestedOpenMode: Boolean,
        priorHint: String?
    ) {
        if (!hasHotspotPermission()) {
            activateAutonomousFallback(prefsCfg, requestedOpenMode, priorHint)
            return
        }
        try {
            wifiManager.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                        hotspotReservation = reservation
                        if (!ensureHttpServerRunning()) {
                            releaseHotspotOnly()
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelf()
                            return
                        }

                        var activeSsid = ""
                        var activePass = ""
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val softAp = reservation?.softApConfiguration
                            activeSsid = softAp?.ssid.orEmpty()
                            activePass = softAp?.passphrase.orEmpty()
                        } else {
                            val wifiCfg = reservation?.wifiConfiguration
                            activeSsid = wifiCfg?.SSID?.removeSurrounding("\"").orEmpty()
                            activePass = wifiCfg?.preSharedKey.orEmpty()
                        }

                        val resolvedSsid = activeSsid.ifBlank { prefsCfg.ssid.ifBlank { DEFAULT_OPEN_SSID } }
                        val detection = detectActiveApAndWlanEndpoints()
                        val primaryIp = detection.primaryIp ?: "192.168.43.1"
                        val combinedIps = (detection.allEndpoints + "192.168.43.1" + "127.0.0.1").distinct()
                        val actuallyOpen = activePass.isBlank()

                        publishBroadcastActiveState(
                            prefsCfg = prefsCfg,
                            requestedOpenMode = requestedOpenMode,
                            activeSsid = resolvedSsid,
                            activePassphrase = activePass,
                            primaryIp = primaryIp,
                            combinedIps = combinedIps,
                            isOpenSecurity = actuallyOpen,
                            customSecurityLabel = when {
                                actuallyOpen -> HotspotSecurityMode.OPEN_VISIBLE.badgeLabel
                                requestedOpenMode -> "SOFTAP BROADCAST LIVE (OS KEY SHOWN BELOW)"
                                else -> HotspotSecurityMode.WPA2_VISIBLE.badgeLabel
                            },
                            radioHint = null
                        )
                    }

                    override fun onFailed(reason: Int) {
                        activateAutonomousFallback(
                            prefsCfg = prefsCfg,
                            requestedOpenMode = requestedOpenMode,
                            radioHint = "Enable device Wi-Fi or System Portable Hotspot so nearby devices see the network (code $reason)."
                        )
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            activateAutonomousFallback(
                prefsCfg = prefsCfg,
                requestedOpenMode = requestedOpenMode,
                radioHint = if (!wifiManager.isWifiEnabled) {
                    "Device Wi-Fi is OFF — turn ON Wi-Fi or System Hotspot so other devices see the network."
                } else {
                    "Hotspot radio unavailable (${e.localizedMessage ?: priorHint ?: "unsupported"}). Use LAN URL or System Hotspot."
                }
            )
        }
    }

    private fun activateAutonomousFallback(
        prefsCfg: BitVoidNetworkConfig,
        requestedOpenMode: Boolean,
        radioHint: String?
    ) {
        if (!ensureHttpServerRunning()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val detection = detectActiveApAndWlanEndpoints()
        val primaryIp = detection.primaryIp ?: "127.0.0.1"
        val combinedIps = (detection.allEndpoints + "127.0.0.1").distinct()
        val activeSsid = detection.detectedApSsid ?: prefsCfg.ssid.ifBlank { DEFAULT_OPEN_SSID }
        val configuredPass = if (requestedOpenMode) {
            ""
        } else {
            prefsCfg.passphrase.ifBlank { BitVoidNetworkConfig.DEFAULT_WPA2_PASSPHRASE }
        }
        val effectiveHint = if (detection.hasDedicatedApInterface) null else radioHint

        publishBroadcastActiveState(
            prefsCfg = prefsCfg,
            requestedOpenMode = requestedOpenMode,
            activeSsid = activeSsid,
            activePassphrase = configuredPass,
            primaryIp = primaryIp,
            combinedIps = combinedIps,
            isOpenSecurity = requestedOpenMode,
            customSecurityLabel = if (requestedOpenMode) {
                HotspotSecurityMode.OPEN_VISIBLE.badgeLabel
            } else {
                HotspotSecurityMode.WPA2_VISIBLE.badgeLabel
            },
            radioHint = effectiveHint
        )
    }

    private fun publishBroadcastActiveState(
        prefsCfg: BitVoidNetworkConfig,
        requestedOpenMode: Boolean,
        activeSsid: String,
        activePassphrase: String,
        primaryIp: String,
        combinedIps: List<String>,
        isOpenSecurity: Boolean,
        customSecurityLabel: String,
        radioHint: String?
    ) {
        val selectedMode = if (requestedOpenMode) {
            HotspotSecurityMode.OPEN_VISIBLE
        } else {
            HotspotSecurityMode.WPA2_VISIBLE
        }
        BitVoidNodeStateHolder.updateHostState {
            it.copy(
                status = HotspotStatus.ACTIVE,
                isServerRunning = true,
                isOpenBroadcastMode = requestedOpenMode,
                selectedSecurityMode = selectedMode,
                isHiddenSsid = BitVoidNetworkConfig.ENFORCED_VISIBLE_SSID,
                needsOpenHotspotSetupBanner = false,
                isOpenSecurity = isOpenSecurity,
                securityLabel = customSecurityLabel,
                configuredSsid = prefsCfg.ssid,
                configuredPassphrase = prefsCfg.passphrase,
                ssid = activeSsid,
                passphrase = activePassphrase,
                gatewayIp = primaryIp,
                boundEndpoints = combinedIps.map { ip -> "http://$ip:${BitVoidLocalServer.DEFAULT_PORT}" },
                uptimeSeconds = 0L,
                errorMessage = radioHint
            )
        }
        startUptimeTicker()
        probeAssignedGatewayIp()
        updateNotification("SSID: $activeSsid · http://$primaryIp:${BitVoidLocalServer.DEFAULT_PORT}")
    }

    @Suppress("DEPRECATION")
    private fun resolveConnectedWifiSsid(): String? {
        return try {
            val info = wifiManager.connectionInfo
            val raw = info?.ssid?.removeSurrounding("\"")
            if (raw.isNullOrBlank() || raw == "<unknown ssid>") null else raw
        } catch (_: SecurityException) {
            null
        }
    }

    private data class DetectedApNetwork(
        val hasDedicatedApInterface: Boolean,
        val primaryIp: String?,
        val allEndpoints: List<String>,
        val detectedApSsid: String?
    )

    private fun detectActiveApAndWlanEndpoints(): DetectedApNetwork {
        val candidates = mutableListOf<Triple<Int, String, Boolean>>()
        val apEnabledByWifi = isSystemWifiApEnabled()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                val isP2pIface = name.startsWith("p2p")
                val isApIface = name.startsWith("ap") ||
                    name.startsWith("swlan") ||
                    name.startsWith("softap") ||
                    name.contains("tether")
                val isWlanIface = name.startsWith("wlan")
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val isGateway192 = ip == "192.168.43.1" ||
                            ip == "192.168.49.1" ||
                            (ip.startsWith("192.168.") && ip.endsWith(".1"))
                        val priority = when {
                            ip == "192.168.43.1" -> 0
                            ip == "192.168.49.1" || isP2pIface -> 1
                            ip.startsWith("192.168.43.") -> 2
                            isApIface && isGateway192 -> 2
                            isApIface -> 3
                            ip.startsWith("192.168.49.") -> 3
                            isWlanIface && isGateway192 -> 4
                            isWlanIface -> 5
                            addr.isSiteLocalAddress -> 6
                            else -> 7
                        }
                        val dedicatedAp = apEnabledByWifi ||
                            isApIface ||
                            isP2pIface ||
                            ip == "192.168.43.1" ||
                            ip == "192.168.49.1" ||
                            (isWlanIface && isGateway192)
                        candidates.add(Triple(priority, ip, dedicatedAp))
                    }
                }
            }
        } catch (_: Exception) {
            return DetectedApNetwork(false, null, emptyList(), null)
        }
        val sorted = candidates.sortedBy { it.first }
        val endpoints = sorted.map { it.second }.distinct()
        val hasDedicatedAp = apEnabledByWifi || isP2pGroupActive || sorted.any { it.third }
        val apSsid = if (apEnabledByWifi) resolveSystemHotspotSsid() else null
        return DetectedApNetwork(
            hasDedicatedApInterface = hasDedicatedAp,
            primaryIp = endpoints.firstOrNull(),
            allEndpoints = endpoints,
            detectedApSsid = apSsid
        )
    }

    private fun isSystemWifiApEnabled(): Boolean {
        return try {
            val method = wifiManager.javaClass.getMethod("isWifiApEnabled")
            (method.invoke(wifiManager) as? Boolean) == true
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveSystemHotspotSsid(): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val getSoftAp = wifiManager.javaClass.getMethod("getSoftApConfiguration")
                val cfg = getSoftAp.invoke(wifiManager) as? SoftApConfiguration
                cfg?.ssid?.takeIf { it.isNotBlank() }
            } else {
                val getApCfg = wifiManager.javaClass.getMethod("getWifiApConfiguration")
                val cfg = getApCfg.invoke(wifiManager) as? WifiConfiguration
                cfg?.SSID?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun probeAssignedGatewayIp() {
        ipProbeJob?.cancel()
        ipProbeJob = serviceScope.launch {
            repeat(20) {
                delay(750L)
                val detection = detectActiveApAndWlanEndpoints()
                val resolved = detection.primaryIp ?: "127.0.0.1"
                val combinedIps = (detection.allEndpoints + "127.0.0.1").distinct()
                val current = BitVoidNodeStateHolder.hostState.value
                val urlList = combinedIps.map { "http://$it:${BitVoidLocalServer.DEFAULT_PORT}" }
                val resolvedSsid = if (isP2pGroupActive || hotspotReservation != null) {
                    current.ssid
                } else {
                    detection.detectedApSsid ?: current.ssid
                }
                if ((resolved != current.gatewayIp ||
                        urlList != current.boundEndpoints ||
                        (resolvedSsid.isNotBlank() && resolvedSsid != current.ssid && current.selectedSecurityMode == HotspotSecurityMode.OPEN_VISIBLE)) &&
                    (current.status == HotspotStatus.ACTIVE || current.status == HotspotStatus.SERVER_ONLY)
                ) {
                    BitVoidNodeStateHolder.updateHostState {
                        it.copy(
                            status = HotspotStatus.ACTIVE,
                            ssid = resolvedSsid.ifBlank { it.ssid },
                            needsOpenHotspotSetupBanner = false,
                            gatewayIp = resolved,
                            boundEndpoints = urlList
                        )
                    }
                    updateNotification(
                        "BitVoid Broadcast · http://$resolved:${BitVoidLocalServer.DEFAULT_PORT}"
                    )
                }
            }
        }
    }

    private fun resolveAllNetworkIpv4s(): List<String> {
        return detectActiveApAndWlanEndpoints().allEndpoints
    }

    private fun startUptimeTicker() {
        uptimeJob?.cancel()
        uptimeJob = serviceScope.launch {
            while (isActive) {
                delay(1000L)
                BitVoidNodeStateHolder.updateHostState { state ->
                    if (state.status == HotspotStatus.ACTIVE || state.status == HotspotStatus.SERVER_ONLY) {
                        state.copy(uptimeSeconds = state.uptimeSeconds + 1L)
                    } else {
                        state
                    }
                }
            }
        }
    }

    private fun refreshFileList() {
        val files = BitVoidMediaManager.refreshFromDisk(applicationContext)
        BitVoidChannelManager.syncMediaCatalog(files)
        BitVoidNodeStateHolder.updateHostState { it.copy(sharedFiles = files) }
    }

    @Suppress("MissingPermission")
    private fun releaseHotspotOnly() {
        ipProbeJob?.cancel()
        try {
            hotspotReservation?.close()
        } catch (_: Exception) {
        }
        hotspotReservation = null
        if (isP2pGroupActive) {
            isP2pGroupActive = false
            val p2pMgr = wifiP2pManager
            val ch = wifiP2pChannel
            if (p2pMgr != null && ch != null) {
                try {
                    p2pMgr.removeGroup(ch, null)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun shutdownNode() {
        uptimeJob?.cancel()
        releaseHotspotOnly()
        BitVoidSignalingServer.closeAllRooms("Host node shut down")
        try {
            localServer?.stop()
        } catch (_: Exception) {
        }
        localServer = null

        BitVoidNodeStateHolder.updateHostState { state ->
            state.copy(
                status = HotspotStatus.IDLE,
                isServerRunning = false,
                isOpenBroadcastMode = false,
                isHiddenSsid = BitVoidNetworkConfig.ENFORCED_VISIBLE_SSID,
                needsOpenHotspotSetupBanner = false,
                isOpenSecurity = state.selectedSecurityMode == HotspotSecurityMode.OPEN_VISIBLE,
                securityLabel = state.selectedSecurityMode.badgeLabel,
                ssid = "",
                passphrase = "",
                gatewayIp = "",
                boundEndpoints = emptyList(),
                activeSessionsCount = 0,
                uptimeSeconds = 0L
            )
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun updateNotification(contentText: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, BitVoidForegroundService::class.java).apply {
            action = ACTION_STOP_NODE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bitvoid_notify)
            .setContentTitle("BitVoid Gateway Active")
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_stop_node),
                stopPendingIntent
            )
            .build()
    }
}
