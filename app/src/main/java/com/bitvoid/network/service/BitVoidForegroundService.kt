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
import com.bitvoid.network.model.HotspotStatus
import com.bitvoid.network.server.BitVoidLocalServer
import com.bitvoid.network.server.BitVoidTlsManager
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
        const val ACTION_START_SERVER_ONLY = "com.bitvoid.network.action.START_SERVER_ONLY"
        const val ACTION_STOP_NODE = "com.bitvoid.network.action.STOP_NODE"
        const val ACTION_REFRESH_FILES = "com.bitvoid.network.action.REFRESH_FILES"


        private const val CHANNEL_ID = "bitvoid_node_channel"
        private const val NOTIFICATION_ID = 7070
        const val DEFAULT_SSID = BitVoidNetworkConfig.DEFAULT_VISIBLE_SSID

        fun startHostNode(context: Context) {
            val intent = Intent(context, BitVoidForegroundService::class.java).apply {
                action = ACTION_START_HOST_NODE
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
    private var secureLocalServer: BitVoidLocalServer? = null
    private var secureCertificateHosts: Set<String> = emptySet()
    private var secureServerError: String? = null
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

            ACTION_START_SERVER_ONLY -> {
                if (!promoteToForeground("Initializing local HTTP vault...")) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startEmbeddedServerOnly()
                return START_STICKY
            }

            ACTION_START_HOST_NODE, null -> {
                if (!promoteToForeground("Initializing password-protected BitVoid Host Engine...")) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                startFullHostNode()
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

    private fun startFullHostNode() {
        val networkConfig = BitVoidNetworkPrefs.load(applicationContext)
        BitVoidNodeStateHolder.updateHostState {
            it.copy(
                status = HotspotStatus.STARTING,
                isHiddenSsid = networkConfig.isHiddenSsid,
                securityLabel = BitVoidNetworkConfig.WPA2_SECURITY_LABEL,
                configuredSsid = networkConfig.ssid,
                configuredPassphrase = networkConfig.passphrase,
                errorMessage = null
            )
        }
        startLocalOnlyHotspotWpa2()
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
        val activeSsid = resolveConnectedWifiSsid() ?: DEFAULT_SSID
        val urlList = allEndpoints.map { "http://$it:${BitVoidLocalServer.DEFAULT_PORT}" }
        val secureReady = ensureSecureServerRunning(allEndpoints)
        BitVoidNodeStateHolder.updateHostState {
            it.copy(
                status = HotspotStatus.SERVER_ONLY,
                isServerRunning = true,
                ssid = activeSsid,
                passphrase = if (resolvedIp == "127.0.0.1") "" else "Managed by Wi-Fi / LAN",
                gatewayIp = resolvedIp,
                boundEndpoints = urlList,
                isSecureServerRunning = secureReady,
                uptimeSeconds = 0L,
                errorMessage = secureServerError
            )
        }
        startUptimeTicker()
        probeAssignedGatewayIp()
        updateNotification("Local Vault Online: http://$resolvedIp:${BitVoidLocalServer.DEFAULT_PORT} · HTTPS :${BitVoidTlsManager.SECURE_PORT}")
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

    @Synchronized
    private fun ensureSecureServerRunning(ipv4Hosts: Collection<String>): Boolean {
        val normalizedHosts = ipv4Hosts.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val current = secureLocalServer
        if (current != null && current.isAlive && secureCertificateHosts.containsAll(normalizedHosts)) {
            return true
        }
        return try {
            try {
                current?.stop()
            } catch (_: Exception) {
            }
            val material = BitVoidTlsManager.createServerMaterial(applicationContext, normalizedHosts)
            val created = BitVoidLocalServer(applicationContext, BitVoidTlsManager.SECURE_PORT)
            created.makeSecure(material.socketFactory, null)
            created.start(0, false)
            secureLocalServer = created
            secureCertificateHosts = material.certificateHosts
            secureServerError = null
            true
        } catch (e: Exception) {
            secureLocalServer = null
            secureCertificateHosts = emptySet()
            secureServerError =
                "Secure browser video portal could not start: ${e.localizedMessage ?: "TLS error"}"
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
        startHardwareBroadcastCascade(prefsCfg)
    }

    @Suppress("MissingPermission")
    private fun startHardwareBroadcastCascade(prefsCfg: BitVoidNetworkConfig) {
        if (!hasHotspotPermission()) {
            activateAutonomousFallback(
                prefsCfg = prefsCfg,
                radioHint = "Grant Nearby Wi-Fi / Location permission to create the password-protected Wi-Fi broadcast."
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
                    val customPassphrase = prefsCfg.passphrase
                    val config = WifiP2pConfig.Builder()
                        .setNetworkName(customNetworkName)
                        .setPassphrase(customPassphrase)
                        .build()

                    val launchGroup = {
                        launchConfiguredP2pGroup(
                            p2pMgr = p2pMgr,
                            channel = channel,
                            config = config,
                            prefsCfg = prefsCfg,
                            expectedSsid = customNetworkName,
                            expectedPassphrase = customPassphrase
                        )
                    }
                    p2pMgr.removeGroup(channel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = launchGroup()
                        override fun onFailure(reason: Int) = launchGroup()
                    })
                    return
                }
            } catch (_: Exception) {
            }
        }

        tryStartLocalOnlyHotspot(
            prefsCfg = prefsCfg,
            priorHint = if (!wifiManager.isWifiEnabled) "Device Wi-Fi is OFF." else null
        )
    }

    @Suppress("MissingPermission")
    private fun launchConfiguredP2pGroup(
        p2pMgr: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        config: WifiP2pConfig,
        prefsCfg: BitVoidNetworkConfig,
        expectedSsid: String,
        expectedPassphrase: String
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
                        if (activePass.isBlank()) {
                            releaseHotspotOnly()
                            activateAutonomousFallback(
                                prefsCfg,
                                "Android returned an unsecured Wi-Fi Direct group; BitVoid refused to publish it."
                            )
                            return@requestGroupInfo
                        }
                        val detection = detectActiveApAndWlanEndpoints()
                        val primaryIp = detection.primaryIp ?: "192.168.49.1"
                        val combinedIps = (detection.allEndpoints + "192.168.49.1" + "127.0.0.1").distinct()
                        publishBroadcastActiveState(
                            prefsCfg = prefsCfg,
                            activeSsid = activeSsid,
                            activePassphrase = activePass,
                            primaryIp = primaryIp,
                            combinedIps = combinedIps,
                            radioHint = null
                        )
                    }
                }

                override fun onFailure(reason: Int) {
                    isP2pGroupActive = false
                    tryStartLocalOnlyHotspot(
                        prefsCfg = prefsCfg,
                        priorHint = "Wi-Fi Direct code $reason"
                    )
                }
            })
        } catch (_: SecurityException) {
            isP2pGroupActive = false
            tryStartLocalOnlyHotspot(prefsCfg, "Permission denied")
        } catch (_: IllegalArgumentException) {
            isP2pGroupActive = false
            tryStartLocalOnlyHotspot(prefsCfg, "Invalid P2P config")
        }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun tryStartLocalOnlyHotspot(
        prefsCfg: BitVoidNetworkConfig,
        priorHint: String?
    ) {
        if (!hasHotspotPermission()) {
            activateAutonomousFallback(prefsCfg, priorHint)
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

                        val activeSsid: String
                        val activePass: String
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val softAp = reservation?.softApConfiguration
                            activeSsid = softAp?.ssid.orEmpty()
                            activePass = softAp?.passphrase.orEmpty()
                        } else {
                            val wifiCfg = reservation?.wifiConfiguration
                            activeSsid = wifiCfg?.SSID?.removeSurrounding("\"").orEmpty()
                            activePass = wifiCfg?.preSharedKey.orEmpty()
                        }

                        if (activePass.isBlank()) {
                            releaseHotspotOnly()
                            activateAutonomousFallback(
                                prefsCfg,
                                "Android returned an unsecured local hotspot; BitVoid refused to use it."
                            )
                            return
                        }

                        val resolvedSsid = activeSsid.ifBlank { prefsCfg.ssid.ifBlank { DEFAULT_SSID } }
                        val detection = detectActiveApAndWlanEndpoints()
                        val primaryIp = detection.primaryIp ?: "192.168.43.1"
                        val combinedIps = (detection.allEndpoints + "192.168.43.1" + "127.0.0.1").distinct()

                        publishBroadcastActiveState(
                            prefsCfg = prefsCfg,
                            activeSsid = resolvedSsid,
                            activePassphrase = activePass,
                            primaryIp = primaryIp,
                            combinedIps = combinedIps,
                            radioHint = null
                        )
                    }

                    override fun onFailed(reason: Int) {
                        activateAutonomousFallback(
                            prefsCfg = prefsCfg,
                            radioHint = "Enable device Wi-Fi or a WPA2 System Hotspot so nearby devices can connect (code $reason)."
                        )
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } catch (e: Exception) {
            activateAutonomousFallback(
                prefsCfg = prefsCfg,
                radioHint = if (!wifiManager.isWifiEnabled) {
                    "Device Wi-Fi is OFF — turn on Wi-Fi or a password-protected System Hotspot."
                } else {
                    "Secure hotspot radio unavailable (${e.localizedMessage ?: priorHint ?: "unsupported"}). Use the existing LAN gateway or a WPA2 System Hotspot."
                }
            )
        }
    }

    private fun activateAutonomousFallback(
        prefsCfg: BitVoidNetworkConfig,
        radioHint: String?
    ) {
        if (!ensureHttpServerRunning()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        val detection = detectActiveApAndWlanEndpoints()
        val systemHotspot = if (isSystemWifiApEnabled()) resolveSystemHotspotInfo() else null
        val secureSystemHotspot = systemHotspot?.isOpen == false &&
            !systemHotspot.passphrase.isNullOrBlank()
        if (secureSystemHotspot) {
            val primaryIp = detection.primaryIp ?: "192.168.43.1"
            val combinedIps = (detection.allEndpoints + primaryIp + "127.0.0.1").distinct()
            publishBroadcastActiveState(
                prefsCfg = prefsCfg,
                activeSsid = systemHotspot?.ssid ?: prefsCfg.ssid,
                activePassphrase = systemHotspot?.passphrase.orEmpty(),
                primaryIp = primaryIp,
                combinedIps = combinedIps,
                radioHint = radioHint
            )
            return
        }

        val primaryIp = detection.primaryIp ?: "127.0.0.1"
        val combinedIps = (detection.allEndpoints + "127.0.0.1").distinct()
        val secureReady = ensureSecureServerRunning(combinedIps)
        val refusedUnsecuredHotspot = systemHotspot?.isOpen == true
        BitVoidNodeStateHolder.updateHostState {
            it.copy(
                status = HotspotStatus.SERVER_ONLY,
                isServerRunning = true,
                securityLabel = BitVoidNetworkConfig.WPA2_SECURITY_LABEL,
                ssid = resolveConnectedWifiSsid().orEmpty(),
                passphrase = "Managed by connected Wi-Fi",
                gatewayIp = primaryIp,
                boundEndpoints = combinedIps.map { ip -> "http://$ip:${BitVoidLocalServer.DEFAULT_PORT}" },
                isSecureServerRunning = secureReady,
                errorMessage = when {
                    refusedUnsecuredHotspot ->
                        "An unsecured System Hotspot is active. BitVoid will not use it; enable WPA2/WPA3 protection in Android Hotspot settings."
                    else -> radioHint
                        ?: secureServerError
                        ?: "No password-protected hotspot or Wi-Fi Direct group is active. The vault is available only on the phone's existing Wi-Fi."
                }
            )
        }
        startUptimeTicker()
        probeAssignedGatewayIp()
        updateNotification("Local vault online · No secure hotspot broadcast · http://$primaryIp:${BitVoidLocalServer.DEFAULT_PORT}")
    }

    private fun publishBroadcastActiveState(
        prefsCfg: BitVoidNetworkConfig,
        activeSsid: String,
        activePassphrase: String,
        primaryIp: String,
        combinedIps: List<String>,
        radioHint: String?
    ) {
        if (activePassphrase.isBlank()) {
            activateAutonomousFallback(
                prefsCfg,
                "An unsecured hotspot was detected and refused. BitVoid requires WPA2/WPA3 credentials."
            )
            return
        }
        val secureReady = ensureSecureServerRunning(combinedIps)
        BitVoidNodeStateHolder.updateHostState {
            it.copy(
                status = HotspotStatus.ACTIVE,
                isServerRunning = true,
                isHiddenSsid = BitVoidNetworkConfig.ENFORCED_VISIBLE_SSID,
                securityLabel = BitVoidNetworkConfig.WPA2_SECURITY_LABEL,
                configuredSsid = prefsCfg.ssid,
                configuredPassphrase = prefsCfg.passphrase,
                ssid = activeSsid,
                passphrase = activePassphrase,
                gatewayIp = primaryIp,
                boundEndpoints = combinedIps.map { ip -> "http://$ip:${BitVoidLocalServer.DEFAULT_PORT}" },
                isSecureServerRunning = secureReady,
                uptimeSeconds = 0L,
                errorMessage = radioHint ?: secureServerError
            )
        }
        startUptimeTicker()
        probeAssignedGatewayIp()
        updateNotification("SECURE SSID: $activeSsid · http://$primaryIp:${BitVoidLocalServer.DEFAULT_PORT}")
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

    private data class SystemHotspotInfo(
        val ssid: String?,
        val passphrase: String?,
        val isOpen: Boolean?
    )

    @Suppress("DEPRECATION")
    private fun resolveSystemHotspotInfo(): SystemHotspotInfo {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val getSoftAp = wifiManager.javaClass.getMethod("getSoftApConfiguration")
                val cfg = getSoftAp.invoke(wifiManager) as? SoftApConfiguration
                SystemHotspotInfo(
                    ssid = cfg?.ssid?.takeIf { it.isNotBlank() },
                    passphrase = cfg?.passphrase?.takeIf { it.isNotBlank() },
                    isOpen = cfg?.let { it.securityType == SoftApConfiguration.SECURITY_TYPE_OPEN }
                )
            } else {
                val getApCfg = wifiManager.javaClass.getMethod("getWifiApConfiguration")
                val cfg = getApCfg.invoke(wifiManager) as? WifiConfiguration
                SystemHotspotInfo(
                    ssid = cfg?.SSID?.removeSurrounding("\"")?.takeIf { it.isNotBlank() },
                    passphrase = cfg?.preSharedKey?.removeSurrounding("\"")?.takeIf { it.isNotBlank() },
                    isOpen = cfg?.allowedKeyManagement?.get(WifiConfiguration.KeyMgmt.NONE)
                )
            }
        } catch (_: Exception) {
            SystemHotspotInfo(null, null, null)
        }
    }

    private fun resolveSystemHotspotSsid(): String? = resolveSystemHotspotInfo().ssid

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
                val secureReady = ensureSecureServerRunning(combinedIps)
                val systemHotspot = if (isSystemWifiApEnabled()) resolveSystemHotspotInfo() else null
                val secureBroadcastActive = isP2pGroupActive ||
                    hotspotReservation != null ||
                    (systemHotspot?.isOpen == false && !systemHotspot.passphrase.isNullOrBlank())
                val resolvedSsid = when {
                    isP2pGroupActive || hotspotReservation != null -> current.ssid
                    secureBroadcastActive -> systemHotspot?.ssid ?: current.ssid
                    else -> current.ssid
                }
                val nextStatus = if (secureBroadcastActive) {
                    HotspotStatus.ACTIVE
                } else {
                    HotspotStatus.SERVER_ONLY
                }
                if ((resolved != current.gatewayIp ||
                        urlList != current.boundEndpoints ||
                        nextStatus != current.status ||
                        (resolvedSsid.isNotBlank() && resolvedSsid != current.ssid)) &&
                    (current.status == HotspotStatus.ACTIVE || current.status == HotspotStatus.SERVER_ONLY)
                ) {
                    BitVoidNodeStateHolder.updateHostState {
                        it.copy(
                            status = nextStatus,
                            ssid = resolvedSsid.ifBlank { it.ssid },
                            gatewayIp = resolved,
                            boundEndpoints = urlList,
                            isSecureServerRunning = secureReady
                        )
                    }
                    updateNotification(
                        if (secureBroadcastActive) {
                            "BitVoid secure hotspot broadcast · http://$resolved:${BitVoidLocalServer.DEFAULT_PORT}"
                        } else {
                            "BitVoid local vault · No secure hotspot broadcast · http://$resolved:${BitVoidLocalServer.DEFAULT_PORT}"
                        }
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
        try {
            secureLocalServer?.stop()
        } catch (_: Exception) {
        }
        secureLocalServer = null
        secureCertificateHosts = emptySet()
        secureServerError = null

        BitVoidNodeStateHolder.updateHostState { state ->
            state.copy(
                status = HotspotStatus.IDLE,
                isServerRunning = false,
                isHiddenSsid = BitVoidNetworkConfig.ENFORCED_VISIBLE_SSID,
                securityLabel = BitVoidNetworkConfig.WPA2_SECURITY_LABEL,
                ssid = "",
                passphrase = "",
                gatewayIp = "",
                boundEndpoints = emptyList(),
                isSecureServerRunning = false,
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
