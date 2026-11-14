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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bitvoid.network.MainActivity
import com.bitvoid.network.channel.BitVoidChannelManager
import com.bitvoid.network.media.BitVoidMediaManager
import com.bitvoid.network.model.BitVoidNetworkConfig
import com.bitvoid.network.model.BitVoidNetworkPrefs
import com.bitvoid.network.model.HostState
import com.bitvoid.network.model.HotspotSecurityMode
import com.bitvoid.network.model.HotspotStatus
import com.bitvoid.network.server.BitVoidLocalServer
import com.example.R
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
    private var openHotspotMonitorJob: Job? = null
    private var mediaSyncJob: Job? = null

    private var localServer: BitVoidLocalServer? = null
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private lateinit var wifiManager: WifiManager
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
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
     * OPEN_VISIBLE mode serves an already enabled system hotspot. Third-party apps cannot
     * reliably configure tethering security across Android/OEM versions, so BitVoid keeps
     * the wildcard HTTP server alive and waits for a visible Open AP interface.
     */
    private fun startOpenVisibleHostNode() {
        releaseHotspotOnly()
        if (!ensureHttpServerRunning()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val prefsCfg = BitVoidNetworkPrefs.load(applicationContext)
        publishOpenHotspotOrWait(prefsCfg)
    }

    private fun publishOpenHotspotOrWait(prefsCfg: BitVoidNetworkConfig) {
        openHotspotMonitorJob?.cancel()
        val detection = detectActiveApAndWlanEndpoints()
        val metadata = resolveSystemHotspotMetadata()
        val invalidReason = when {
            metadata?.isHiddenSsid == true ->
                "The system hotspot is hidden. Turn Hidden Network off in Android hotspot settings."
            metadata?.isOpen == false ->
                "The system hotspot is password protected. Set Security to None/Open."
            else -> null
        }

        if (detection.hasDedicatedApInterface && invalidReason == null) {
            val primaryIp = detection.primaryIp ?: "192.168.43.1"
            val combinedIps = (detection.allEndpoints + "127.0.0.1").distinct()
            val activeSsid = metadata?.ssid
                ?.takeIf { it.isNotBlank() }
                ?: detection.detectedApSsid
                ?: prefsCfg.ssid.ifBlank { DEFAULT_OPEN_SSID }
            publishBroadcastActiveState(
                prefsCfg = prefsCfg,
                requestedOpenMode = true,
                activeSsid = activeSsid,
                activePassphrase = "",
                primaryIp = primaryIp,
                combinedIps = combinedIps,
                isOpenSecurity = true,
                customSecurityLabel = HotspotSecurityMode.OPEN_VISIBLE.badgeLabel,
                radioHint = if (metadata == null) {
                    "Android did not expose hotspot security metadata. Keep Security None/Open and Hidden Network off."
                } else {
                    null
                }
            )
            return
        }

        val waitingMessage = invalidReason
            ?: "Turn on Android's visible hotspot with Security None/Open and Hidden Network off."
        val waitingEndpoints = (detection.allEndpoints + "127.0.0.1").distinct()
        BitVoidNodeStateHolder.updateHostState {
            it.copy(
                status = HotspotStatus.SERVER_ONLY,
                isServerRunning = true,
                isOpenBroadcastMode = true,
                selectedSecurityMode = HotspotSecurityMode.OPEN_VISIBLE,
                isHiddenSsid = BitVoidNetworkConfig.ENFORCED_VISIBLE_SSID,
                needsOpenHotspotSetupBanner = true,
                isOpenSecurity = true,
                securityLabel = HotspotSecurityMode.OPEN_VISIBLE.badgeLabel,
                configuredSsid = prefsCfg.ssid,
                configuredPassphrase = prefsCfg.passphrase,
                ssid = prefsCfg.ssid.ifBlank { DEFAULT_OPEN_SSID },
                passphrase = "",
                gatewayIp = detection.primaryIp ?: "192.168.43.1",
                boundEndpoints = waitingEndpoints.map { ip ->
                    "http://$ip:${BitVoidLocalServer.DEFAULT_PORT}"
                },
                uptimeSeconds = 0L,
                errorMessage = waitingMessage
            )
        }
        startUptimeTicker()
        updateNotification("Open hotspot setup required · local portal listening on 0.0.0.0:8080")

        openHotspotMonitorJob = serviceScope.launch {
            while (isActive) {
                delay(1_500L)
                val latest = detectActiveApAndWlanEndpoints()
                val latestMetadata = resolveSystemHotspotMetadata()
                val valid = latest.hasDedicatedApInterface &&
                    latestMetadata?.isHiddenSsid != true &&
                    latestMetadata?.isOpen != false
                if (valid) {
                    val primaryIp = latest.primaryIp ?: "192.168.43.1"
                    val activeSsid = latestMetadata?.ssid
                        ?.takeIf { it.isNotBlank() }
                        ?: latest.detectedApSsid
                        ?: prefsCfg.ssid.ifBlank { DEFAULT_OPEN_SSID }
                    publishBroadcastActiveState(
                        prefsCfg = prefsCfg,
                        requestedOpenMode = true,
                        activeSsid = activeSsid,
                        activePassphrase = "",
                        primaryIp = primaryIp,
                        combinedIps = (latest.allEndpoints + "127.0.0.1").distinct(),
                        isOpenSecurity = true,
                        customSecurityLabel = HotspotSecurityMode.OPEN_VISIBLE.badgeLabel,
                        radioHint = if (latestMetadata == null) {
                            "Android did not expose hotspot security metadata. Keep Security None/Open and Hidden Network off."
                        } else {
                            null
                        }
                    )
                    return@launch
                }
            }
        }
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

    @Suppress("MissingPermission", "DEPRECATION")
    private fun startLocalOnlyHotspotWpa2() {
        releaseHotspotOnly()
        if (!ensureHttpServerRunning()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (!hasHotspotPermission()) {
            failWpa2Hotspot("Grant Nearby Wi-Fi / Location permission to create the WPA2 hotspot.")
            return
        }

        val prefsCfg = BitVoidNetworkPrefs.load(applicationContext)
        try {
            wifiManager.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                        if (reservation == null) {
                            failWpa2Hotspot("Android did not return a hotspot reservation.")
                            return
                        }

                        val activeSsid: String
                        val activePassphrase: String
                        val isHiddenSsid: Boolean
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val softAp = reservation.softApConfiguration
                            activeSsid = softAp.ssid.orEmpty()
                            activePassphrase = softAp.passphrase.orEmpty()
                            isHiddenSsid = softAp.isHiddenSsid
                        } else {
                            val wifiCfg = reservation.wifiConfiguration
                            activeSsid = wifiCfg.SSID?.removeSurrounding("\"").orEmpty()
                            activePassphrase = wifiCfg.preSharedKey.orEmpty().removeSurrounding("\"")
                            isHiddenSsid = wifiCfg.hiddenSSID
                        }

                        if (isHiddenSsid) {
                            reservation.close()
                            failWpa2Hotspot(
                                "Android generated a hidden hotspot. BitVoid requires a visible network."
                            )
                            return
                        }
                        if (activePassphrase.isBlank()) {
                            reservation.close()
                            failWpa2Hotspot("Android did not provide the generated WPA2 passphrase.")
                            return
                        }

                        hotspotReservation = reservation
                        val detection = detectActiveApAndWlanEndpoints()
                        val primaryIp = detection.primaryIp ?: "192.168.43.1"
                        publishBroadcastActiveState(
                            prefsCfg = prefsCfg,
                            requestedOpenMode = false,
                            activeSsid = activeSsid.ifBlank { "Android Local Hotspot" },
                            activePassphrase = activePassphrase,
                            primaryIp = primaryIp,
                            combinedIps = (detection.allEndpoints + "192.168.43.1" + "127.0.0.1").distinct(),
                            isOpenSecurity = false,
                            customSecurityLabel = HotspotSecurityMode.WPA2_VISIBLE.badgeLabel,
                            radioHint = null
                        )
                    }

                    override fun onFailed(reason: Int) {
                        failWpa2Hotspot("Android could not create the visible WPA2 hotspot (code $reason).")
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (error: Exception) {
            failWpa2Hotspot(
                "Android could not create the visible WPA2 hotspot: ${error.localizedMessage ?: "unsupported"}"
            )
        }
    }

    private fun failWpa2Hotspot(message: String) {
        runCatching { hotspotReservation?.close() }
        hotspotReservation = null
        runCatching { localServer?.stop() }
        localServer = null
        BitVoidNodeStateHolder.updateHostState {
            it.copy(
                status = HotspotStatus.ERROR,
                isServerRunning = false,
                needsOpenHotspotSetupBanner = false,
                isOpenSecurity = false,
                ssid = "",
                passphrase = "",
                gatewayIp = "",
                boundEndpoints = emptyList(),
                errorMessage = message
            )
        }
        updateNotification(message)
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
                            ip == "192.168.49.1" -> 1
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
                            ip == "192.168.43.1" ||
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
        val hasDedicatedAp = apEnabledByWifi || sorted.any { it.third }
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

    private data class SystemHotspotMetadata(
        val ssid: String?,
        val isOpen: Boolean,
        val isHiddenSsid: Boolean
    )

    @Suppress("DEPRECATION")
    private fun resolveSystemHotspotMetadata(): SystemHotspotMetadata? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val getSoftAp = wifiManager.javaClass.getMethod("getSoftApConfiguration")
                val cfg = getSoftAp.invoke(wifiManager) as? SoftApConfiguration ?: return null
                SystemHotspotMetadata(
                    ssid = cfg.ssid?.takeIf { it.isNotBlank() },
                    isOpen = cfg.securityType == SoftApConfiguration.SECURITY_TYPE_OPEN,
                    isHiddenSsid = cfg.isHiddenSsid
                )
            } else {
                val getApCfg = wifiManager.javaClass.getMethod("getWifiApConfiguration")
                val cfg = getApCfg.invoke(wifiManager) as? WifiConfiguration ?: return null
                SystemHotspotMetadata(
                    ssid = cfg.SSID?.removeSurrounding("\"")?.takeIf { it.isNotBlank() },
                    isOpen = cfg.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.NONE) &&
                        cfg.preSharedKey.isNullOrBlank(),
                    isHiddenSsid = cfg.hiddenSSID
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveSystemHotspotSsid(): String? = resolveSystemHotspotMetadata()?.ssid

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
                val resolvedSsid = if (hotspotReservation != null) {
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

    private fun releaseHotspotOnly() {
        ipProbeJob?.cancel()
        openHotspotMonitorJob?.cancel()
        openHotspotMonitorJob = null
        runCatching { hotspotReservation?.close() }
        hotspotReservation = null
    }

    private fun shutdownNode() {
        uptimeJob?.cancel()
        releaseHotspotOnly()
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
