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
import com.bitvoid.network.client.BitVoidClientRepositoryProvider
import com.bitvoid.network.media.BitVoidMediaManager
import com.bitvoid.network.model.BitVoidNetworkConfig
import com.bitvoid.network.model.BitVoidNetworkPrefs
import com.bitvoid.network.model.HostState
import com.bitvoid.network.model.HotspotStatus
import com.bitvoid.network.server.BitVoidLocalServer
import com.bitvoid.network.server.BitVoidMdnsResponder
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
        const val ACTION_START_MESSAGE_MONITOR = "com.bitvoid.network.action.START_MESSAGE_MONITOR"
        const val ACTION_STOP_MESSAGE_MONITOR = "com.bitvoid.network.action.STOP_MESSAGE_MONITOR"

        const val EXTRA_MESSAGE_DESTINATION = "com.bitvoid.network.extra.MESSAGE_DESTINATION"
        const val EXTRA_CONVERSATION_ID = "com.bitvoid.network.extra.CONVERSATION_ID"
        const val EXTRA_PEER_USERNAME = "com.bitvoid.network.extra.PEER_USERNAME"

        private const val CHANNEL_ID = "bitvoid_node_channel"
        private const val MESSAGE_CHANNEL_ID = "bitvoid_message_channel_v1"
        private const val NOTIFICATION_ID = 7070
        private const val FRIENDLY_HTTP_PORT = 80
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

        fun startMessageMonitoring(context: Context) {
            val intent = Intent(context, BitVoidForegroundService::class.java).apply {
                action = ACTION_START_MESSAGE_MONITOR
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopMessageMonitoring(context: Context) {
            val intent = Intent(context, BitVoidForegroundService::class.java).apply {
                action = ACTION_STOP_MESSAGE_MONITOR
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
    private var messageNotificationJob: Job? = null

    @Volatile
    private var isNodeForegroundActive = false
    @Volatile
    private var isMessageMonitoringActive = false

    private var localServer: BitVoidLocalServer? = null
    private var friendlyHttpServer: BitVoidLocalServer? = null
    private var friendlyPort80Unavailable = false
    private var mdnsResponder: BitVoidMdnsResponder? = null
    private var secureLocalServer: BitVoidLocalServer? = null
    private var secureCertificateHosts: Set<String> = emptySet()
    private var secureServerError: String? = null
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var wifiP2pManager: WifiP2pManager? = null
    private var wifiP2pChannel: WifiP2pManager.Channel? = null
    private var isP2pGroupActive: Boolean = false
    private var activeP2pGatewayIp: String? = null
    private lateinit var wifiManager: WifiManager
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiP2pManager = applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mdnsResponder = BitVoidMdnsResponder(applicationContext)
        ensureNotificationChannels()
        BitVoidChannelManager.ensureDefaultChannel()
        refreshFileList()
        observeMediaVisibilityChanges()
        observeIncomingMessages()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_NODE -> {
                isNodeForegroundActive = false
                shutdownNode()
                if (isMessageMonitoringActive) {
                    promoteToForeground("Listening for local channel and direct messages...")
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_START_MESSAGE_MONITOR -> {
                isMessageMonitoringActive = true
                if (!isNodeForegroundActive &&
                    !promoteToForeground("Listening for local channel and direct messages...")
                ) {
                    isMessageMonitoringActive = false
                    stopSelf()
                }
                return if (isNodeForegroundActive) START_STICKY else START_NOT_STICKY
            }

            ACTION_STOP_MESSAGE_MONITOR -> {
                isMessageMonitoringActive = false
                if (!isNodeForegroundActive) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                return START_STICKY
            }

            ACTION_REFRESH_FILES -> {
                refreshFileList()
                return START_STICKY
            }

            ACTION_START_SERVER_ONLY -> {
                isNodeForegroundActive = true
                if (!promoteToForeground("Initializing local HTTP vault...")) {
                    isNodeForegroundActive = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                startEmbeddedServerOnly()
                return START_STICKY
            }

            ACTION_START_HOST_NODE, null -> {
                isNodeForegroundActive = true
                if (!promoteToForeground("Initializing password-protected BitVoid Host Engine...")) {
                    isNodeForegroundActive = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                startFullHostNode()
                return START_STICKY
            }
        }
        return if (isNodeForegroundActive) START_STICKY else START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isNodeForegroundActive = false
        isMessageMonitoringActive = false
        shutdownNode()
        stopForeground(STOP_FOREGROUND_REMOVE)
        mediaSyncJob?.cancel()
        messageNotificationJob?.cancel()
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

    private fun observeIncomingMessages() {
        messageNotificationJob?.cancel()
        messageNotificationJob = serviceScope.launch {
            BitVoidClientRepositoryProvider.repository.incomingMessages.collect { event ->
                if (!isMessageMonitoringActive) return@collect
                val spec = BitVoidMessageNotifications.createSpec(event) ?: return@collect
                showMessageNotification(spec)
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

    private fun handleNodeStartupFailure() {
        isNodeForegroundActive = false
        if (isMessageMonitoringActive) {
            promoteToForeground("Listening for local channel and direct messages...")
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
                gatewayIp = "",
                isGatewayAddressVerified = false,
                gatewayHostname = "",
                boundEndpoints = emptyList(),
                gatewayHostnamePort = BitVoidLocalServer.DEFAULT_PORT,
                errorMessage = null
            )
        }
        startLocalOnlyHotspotWpa2()
    }

    private fun startEmbeddedServerOnly() {
        releaseHotspotOnly()
        if (!ensureHttpServerRunning()) {
            handleNodeStartupFailure()
            return
        }

        val allEndpoints = (resolveAllNetworkIpv4s() + "127.0.0.1").distinct()
        val resolvedIp = allEndpoints.first()
        val activeSsid = resolveConnectedWifiSsid() ?: DEFAULT_SSID
        val friendlyPort = ensureFriendlyGateway(resolvedIp)
        val urlList = buildPortalEndpoints(allEndpoints, friendlyPort)
        val secureReady = ensureSecureServerRunning(allEndpoints)
        BitVoidNodeStateHolder.updateHostState {
            it.copy(
                status = HotspotStatus.SERVER_ONLY,
                isServerRunning = true,
                ssid = activeSsid,
                passphrase = if (resolvedIp == "127.0.0.1") "" else "Managed by Wi-Fi / LAN",
                gatewayIp = resolvedIp,
                isGatewayAddressVerified = resolvedIp != "127.0.0.1",
                gatewayHostname = if (friendlyPort != null) BitVoidNetworkConfig.LOCAL_GATEWAY_HOSTNAME else "",
                gatewayHostnamePort = friendlyPort ?: BitVoidLocalServer.DEFAULT_PORT,
                boundEndpoints = urlList,
                isSecureServerRunning = secureReady,
                uptimeSeconds = 0L,
                errorMessage = secureServerError
            )
        }
        startUptimeTicker()
        probeAssignedGatewayIp()
        updateNotification("Local Vault Online: ${preferredPortalUrl(resolvedIp, friendlyPort)} · HTTPS :${BitVoidTlsManager.SECURE_PORT}")
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
                    isGatewayAddressVerified = false,
                    gatewayHostname = "",
                    boundEndpoints = emptyList(),
                    errorMessage = "Failed to bind HTTP server on port ${BitVoidLocalServer.DEFAULT_PORT}: ${e.localizedMessage}"
                )
            }
            false
        }
    }

    @Synchronized
    private fun ensureFriendlyGateway(ipv4: String): Int? {
        if (ipv4.isBlank() || ipv4 == "127.0.0.1" || ipv4 == "0.0.0.0") {
            mdnsResponder?.stop()
            return null
        }
        val responder = mdnsResponder ?: BitVoidMdnsResponder(applicationContext).also {
            mdnsResponder = it
        }
        if (!responder.start(ipv4)) return null

        val current = friendlyHttpServer
        if (current != null && current.isAlive) return FRIENDLY_HTTP_PORT
        if (friendlyPort80Unavailable) return BitVoidLocalServer.DEFAULT_PORT
        return try {
            val created = BitVoidLocalServer(applicationContext, FRIENDLY_HTTP_PORT)
            created.start(0, false)
            friendlyHttpServer = created
            FRIENDLY_HTTP_PORT
        } catch (_: Exception) {
            friendlyHttpServer = null
            friendlyPort80Unavailable = true
            // The stable name still works on the regular HTTP port if Android reserves port 80.
            BitVoidLocalServer.DEFAULT_PORT
        }
    }

    private fun friendlyPortalUrl(port: Int): String {
        return if (port == FRIENDLY_HTTP_PORT) {
            "http://${BitVoidNetworkConfig.LOCAL_GATEWAY_HOSTNAME}"
        } else {
            "http://${BitVoidNetworkConfig.LOCAL_GATEWAY_HOSTNAME}:$port"
        }
    }

    private fun buildPortalEndpoints(ipv4Hosts: Collection<String>, friendlyPort: Int?): List<String> {
        return buildList {
            if (friendlyPort != null) add(friendlyPortalUrl(friendlyPort))
            ipv4Hosts.forEach { add("http://$it:${BitVoidLocalServer.DEFAULT_PORT}") }
        }.distinct()
    }

    private fun preferredPortalUrl(ipv4: String, friendlyPort: Int?): String {
        return friendlyPort?.let(::friendlyPortalUrl)
            ?: "http://$ipv4:${BitVoidLocalServer.DEFAULT_PORT}"
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
                "Secure browser audio portal could not start: ${e.localizedMessage ?: "TLS error"}"
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
            handleNodeStartupFailure()
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

        // Android's legacy LocalOnlyHotspot API always chooses random credentials. Prefer the
        // configured LocalOnlyHotspot overload where the platform exposes it, then Wi-Fi Direct.
        // Never silently fall back to the randomized API: the network shown on another device
        // must use the credentials the user saved in BitVoid.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            tryStartConfiguredLocalOnlyHotspot(prefsCfg)
        ) {
            return
        }

        tryStartConfiguredP2pBroadcast(
            prefsCfg = prefsCfg,
            priorHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "Configured Android hotspot API unavailable"
            } else {
                null
            }
        )
    }

    /**
     * Android 16 publicly exposes startLocalOnlyHotspotWithConfiguration. Android 13-15 ship a
     * compatible system overload on some devices, so this uses reflection as a best-effort bridge
     * without claiming success until the reservation reports the exact requested credentials.
     */
    @Suppress("MissingPermission", "DEPRECATION")
    private fun tryStartConfiguredLocalOnlyHotspot(prefsCfg: BitVoidNetworkConfig): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false

        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                if (!ensureHttpServerRunning()) {
                    reservation?.close()
                    handleNodeStartupFailure()
                    return
                }

                val softAp = reservation?.softApConfiguration
                val activeSsid = softAp?.ssid.orEmpty()
                val activePass = softAp?.passphrase.orEmpty()
                if (reservation == null ||
                    activeSsid != prefsCfg.ssid ||
                    activePass != prefsCfg.passphrase
                ) {
                    try {
                        reservation?.close()
                    } catch (_: Exception) {
                    }
                    hotspotReservation = null
                    tryStartConfiguredP2pBroadcast(
                        prefsCfg,
                        "Android replaced the requested hotspot credentials"
                    )
                    return
                }

                hotspotReservation = reservation
                publishBroadcastAfterAddressAssignment(
                    prefsCfg = prefsCfg,
                    activeSsid = activeSsid,
                    activePassphrase = activePass,
                    fallbackGatewayIp = "192.168.43.1"
                )
            }

            override fun onFailed(reason: Int) {
                hotspotReservation = null
                tryStartConfiguredP2pBroadcast(
                    prefsCfg,
                    "Configured Android hotspot failed (code $reason)"
                )
            }
        }

        return try {
            val config = buildConfiguredLocalOnlyHotspot(prefsCfg)
            val methodName = if (Build.VERSION.SDK_INT >= 36) {
                "startLocalOnlyHotspotWithConfiguration"
            } else {
                "startLocalOnlyHotspot"
            }
            val startMethod = WifiManager::class.java.getMethod(
                methodName,
                SoftApConfiguration::class.java,
                java.util.concurrent.Executor::class.java,
                WifiManager.LocalOnlyHotspotCallback::class.java
            )
            startMethod.invoke(wifiManager, config, mainExecutor, callback)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Build a WPA2 SoftApConfiguration while remaining compatible with SDK extension revisions. */
    private fun buildConfiguredLocalOnlyHotspot(
        prefsCfg: BitVoidNetworkConfig
    ): SoftApConfiguration {
        val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
        val builder = builderClass.getDeclaredConstructor().newInstance()
        val wifiSsidClass = Class.forName("android.net.wifi.WifiSsid")
        val wifiSsid = wifiSsidClass
            .getMethod("fromBytes", ByteArray::class.java)
            .invoke(null, prefsCfg.ssid.toByteArray(Charsets.UTF_8))

        val configuredSsid = try {
            builderClass
                .getMethod("setWifiSsid", wifiSsidClass)
                .invoke(builder, wifiSsid)
            true
        } catch (_: Exception) {
            try {
                builderClass
                    .getMethod("setSsid", String::class.java)
                    .invoke(builder, prefsCfg.ssid)
                true
            } catch (_: Exception) {
                false
            }
        }
        check(configuredSsid) { "Custom hotspot SSID is unavailable" }

        builderClass
            .getMethod(
                "setPassphrase",
                String::class.java,
                Int::class.javaPrimitiveType!!
            )
            .invoke(builder, prefsCfg.passphrase, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)

        return builderClass.getMethod("build").invoke(builder) as SoftApConfiguration
    }

    @Suppress("MissingPermission")
    private fun tryStartConfiguredP2pBroadcast(
        prefsCfg: BitVoidNetworkConfig,
        priorHint: String?
    ) {
        val p2pMgr = wifiP2pManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            p2pMgr != null &&
            wifiManager.isWifiEnabled
        ) {
            try {
                val channel = wifiP2pChannel ?: p2pMgr.initialize(
                    this,
                    Looper.getMainLooper(),
                    null
                ).also { wifiP2pChannel = it }
                if (channel != null) {
                    val customNetworkName =
                        BitVoidNetworkConfig.formatWifiDirectNetworkName(prefsCfg.ssid)
                    val config = WifiP2pConfig.Builder()
                        .setNetworkName(customNetworkName)
                        .setPassphrase(prefsCfg.passphrase)
                        .build()

                    val launchGroup = {
                        launchConfiguredP2pGroup(
                            p2pMgr = p2pMgr,
                            channel = channel,
                            config = config,
                            prefsCfg = prefsCfg,
                            expectedSsid = customNetworkName,
                            expectedPassphrase = prefsCfg.passphrase,
                            priorHint = priorHint,
                            attempt = 0
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

        activateAutonomousFallback(
            prefsCfg,
            buildConfiguredBroadcastFailureHint(
                priorHint,
                if (!wifiManager.isWifiEnabled) {
                    "Device Wi-Fi is off"
                } else {
                    "Configured Wi-Fi Direct is unsupported"
                }
            )
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
        priorHint: String?,
        attempt: Int
    ) {
        try {
            p2pMgr.createGroup(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    isP2pGroupActive = true
                    if (!ensureHttpServerRunning()) {
                        releaseHotspotOnly()
                        handleNodeStartupFailure()
                        return
                    }
                    requestConfiguredP2pGroupInfo(
                        p2pMgr = p2pMgr,
                        channel = channel,
                        prefsCfg = prefsCfg,
                        expectedSsid = expectedSsid,
                        expectedPassphrase = expectedPassphrase,
                        priorHint = priorHint,
                        readAttempt = 0
                    )
                }

                override fun onFailure(reason: Int) {
                    isP2pGroupActive = false
                    activeP2pGatewayIp = null
                    if (reason == WifiP2pManager.BUSY && attempt < 2) {
                        Handler(Looper.getMainLooper()).postDelayed(
                            {
                                p2pMgr.removeGroup(channel, object : WifiP2pManager.ActionListener {
                                    override fun onSuccess() = retry()
                                    override fun onFailure(removeReason: Int) = retry()

                                    private fun retry() {
                                        launchConfiguredP2pGroup(
                                            p2pMgr = p2pMgr,
                                            channel = channel,
                                            config = config,
                                            prefsCfg = prefsCfg,
                                            expectedSsid = expectedSsid,
                                            expectedPassphrase = expectedPassphrase,
                                            priorHint = priorHint,
                                            attempt = attempt + 1
                                        )
                                    }
                                })
                            },
                            750L * (attempt + 1)
                        )
                        return
                    }
                    activateAutonomousFallback(
                        prefsCfg,
                        buildConfiguredBroadcastFailureHint(
                            priorHint,
                            "Wi-Fi Direct failed (code $reason)"
                        )
                    )
                }
            })
        } catch (_: SecurityException) {
            isP2pGroupActive = false
            activeP2pGatewayIp = null
            activateAutonomousFallback(
                prefsCfg,
                buildConfiguredBroadcastFailureHint(priorHint, "Wi-Fi Direct permission denied")
            )
        } catch (_: IllegalArgumentException) {
            isP2pGroupActive = false
            activeP2pGatewayIp = null
            activateAutonomousFallback(
                prefsCfg,
                buildConfiguredBroadcastFailureHint(priorHint, "Invalid Wi-Fi Direct configuration")
            )
        }
    }

    @Suppress("MissingPermission")
    private fun requestConfiguredP2pGroupInfo(
        p2pMgr: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        prefsCfg: BitVoidNetworkConfig,
        expectedSsid: String,
        expectedPassphrase: String,
        priorHint: String?,
        readAttempt: Int
    ) {
        p2pMgr.requestGroupInfo(channel) { group ->
            if (group == null && readAttempt < 3) {
                Handler(Looper.getMainLooper()).postDelayed(
                    {
                        requestConfiguredP2pGroupInfo(
                            p2pMgr,
                            channel,
                            prefsCfg,
                            expectedSsid,
                            expectedPassphrase,
                            priorHint,
                            readAttempt + 1
                        )
                    },
                    500L
                )
                return@requestGroupInfo
            }

            val activeSsid = group?.networkName.orEmpty()
            val activePass = group?.passphrase.orEmpty()
            if (activeSsid != expectedSsid || activePass != expectedPassphrase) {
                releaseHotspotOnly()
                activateAutonomousFallback(
                    prefsCfg,
                    buildConfiguredBroadcastFailureHint(
                        priorHint,
                        "Android did not retain the requested Wi-Fi Direct credentials"
                    )
                )
                return@requestGroupInfo
            }

            p2pMgr.requestConnectionInfo(channel) { info ->
                val groupOwnerIp = info?.groupOwnerAddress?.hostAddress
                    ?.takeIf { it.isNotBlank() }
                    ?: "192.168.49.1"
                activeP2pGatewayIp = groupOwnerIp
                publishBroadcastAfterAddressAssignment(
                    prefsCfg = prefsCfg,
                    activeSsid = activeSsid,
                    activePassphrase = activePass,
                    fallbackGatewayIp = groupOwnerIp,
                    fallbackGatewayIsAuthoritative = true
                )
            }
        }
    }

    private fun publishBroadcastAfterAddressAssignment(
        prefsCfg: BitVoidNetworkConfig,
        activeSsid: String,
        activePassphrase: String,
        fallbackGatewayIp: String,
        fallbackGatewayIsAuthoritative: Boolean = false
    ) {
        serviceScope.launch {
            var detection = detectActiveApAndWlanEndpoints()
            var attempts = 0
            while (isNodeForegroundActive && attempts < 24) {
                val knownGatewayAssigned = detection.allEndpoints.contains(fallbackGatewayIp)
                val interfaceReady = detection.primaryBroadcastIp != null
                // Give Android at least two seconds to expose its AP/P2P interface. This avoids
                // publishing the phone's unrelated wlan0 client address on concurrent Wi-Fi.
                val addressReady = when {
                    fallbackGatewayIsAuthoritative -> knownGatewayAssigned
                    fallbackGatewayIp == "192.168.49.1" ->
                        knownGatewayAssigned || (attempts >= 16 && interfaceReady)
                    else -> interfaceReady
                }
                if (attempts >= 8 && addressReady) break
                delay(250L)
                detection = detectActiveApAndWlanEndpoints()
                attempts++
            }
            if (!isNodeForegroundActive) return@launch

            val primaryIp = when {
                fallbackGatewayIsAuthoritative -> fallbackGatewayIp
                detection.allEndpoints.contains(fallbackGatewayIp) -> fallbackGatewayIp
                else -> detection.primaryBroadcastIp ?: fallbackGatewayIp
            }
            val combinedIps = (detection.allEndpoints + primaryIp + "127.0.0.1").distinct()
            publishBroadcastActiveState(
                prefsCfg = prefsCfg,
                activeSsid = activeSsid,
                activePassphrase = activePassphrase,
                primaryIp = primaryIp,
                combinedIps = combinedIps,
                radioHint = if (detection.primaryBroadcastIp == null) {
                    "Gateway interface is still being assigned. On a computer, open " +
                        "http://$fallbackGatewayIp:${BitVoidLocalServer.DEFAULT_PORT} exactly."
                } else {
                    null
                }
            )
        }
    }

    private fun buildConfiguredBroadcastFailureHint(
        priorHint: String?,
        failure: String
    ): String {
        val detail = listOfNotNull(priorHint, failure).joinToString("; ")
        return "$detail. BitVoid did not start Android's randomized hotspot. " +
            "Use Android Hotspot settings with the saved credentials, or keep this phone on the shared Wi-Fi."
    }

    private fun activateAutonomousFallback(
        prefsCfg: BitVoidNetworkConfig,
        radioHint: String?
    ) {
        if (!ensureHttpServerRunning()) {
            handleNodeStartupFailure()
            return
        }

        val detection = detectActiveApAndWlanEndpoints()
        val systemHotspot = if (isSystemWifiApEnabled()) resolveSystemHotspotInfo() else null
        val secureSystemHotspot = systemHotspot?.isOpen == false &&
            !systemHotspot.passphrase.isNullOrBlank()
        if (secureSystemHotspot) {
            val primaryIp = detection.primaryBroadcastIp ?: "192.168.43.1"
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
        val friendlyPort = ensureFriendlyGateway(primaryIp)
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
                isGatewayAddressVerified = detection.allEndpoints.contains(primaryIp),
                gatewayHostname = if (friendlyPort != null) BitVoidNetworkConfig.LOCAL_GATEWAY_HOSTNAME else "",
                gatewayHostnamePort = friendlyPort ?: BitVoidLocalServer.DEFAULT_PORT,
                boundEndpoints = buildPortalEndpoints(combinedIps, friendlyPort),
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
        updateNotification("Local vault online · No secure hotspot broadcast · ${preferredPortalUrl(primaryIp, friendlyPort)}")
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
        val gatewayVerified = resolveAllNetworkIpv4s().contains(primaryIp)
        val friendlyPort = ensureFriendlyGateway(primaryIp)
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
                isGatewayAddressVerified = gatewayVerified,
                gatewayHostname = if (friendlyPort != null) BitVoidNetworkConfig.LOCAL_GATEWAY_HOSTNAME else "",
                gatewayHostnamePort = friendlyPort ?: BitVoidLocalServer.DEFAULT_PORT,
                boundEndpoints = buildPortalEndpoints(combinedIps, friendlyPort),
                isSecureServerRunning = secureReady,
                uptimeSeconds = 0L,
                errorMessage = radioHint ?: secureServerError
            )
        }
        startUptimeTicker()
        probeAssignedGatewayIp()
        updateNotification("SECURE SSID: $activeSsid · ${preferredPortalUrl(primaryIp, friendlyPort)}")
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
        val primaryIp: String?,
        val primaryBroadcastIp: String?,
        val allEndpoints: List<String>,
        val detectedApSsid: String?
    )

    private fun detectActiveApAndWlanEndpoints(): DetectedApNetwork {
        val candidates = mutableListOf<Triple<Int, String, Boolean>>()
        val apEnabledByWifi = isSystemWifiApEnabled()
        val hasStationWifiConnection = resolveConnectedWifiSsid() != null
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                val isCellularOrTunnel = name.startsWith("rmnet") ||
                    name.startsWith("ccmni") ||
                    name.startsWith("pdp") ||
                    name.startsWith("rev_rmnet") ||
                    name.startsWith("tun") ||
                    name.startsWith("tap") ||
                    name.startsWith("ppp") ||
                    name.startsWith("wg")
                if (isCellularOrTunnel) continue
                val isP2pIface = name.startsWith("p2p") || name.contains("-p2p")
                val wlanIndex = name.removePrefix("wlan").toIntOrNull()
                val isWlanIface = name.startsWith("wlan") || name.startsWith("wifi")
                val isSecondaryWlan = wlanIndex != null && wlanIndex > 0
                val isBridgeIface = name.startsWith("br") || name.contains("bridge")
                val isApIface = name.startsWith("ap") ||
                    name.startsWith("swlan") ||
                    name.startsWith("softap") ||
                    name.startsWith("sap") ||
                    name.contains("tether") ||
                    isSecondaryWlan ||
                    isBridgeIface
                val broadcastRadioExpected = isP2pGroupActive ||
                    hotspotReservation != null ||
                    apEnabledByWifi
                val canTreatPrimaryWlanAsBroadcast = broadcastRadioExpected &&
                    !hasStationWifiConnection
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val ip = addr.hostAddress ?: continue
                        val isLikelyGatewayAddress = addr.isSiteLocalAddress && ip.endsWith(".1")
                        val isKnownAndroidGateway = ip == "192.168.43.1" ||
                            ip == "192.168.49.1" ||
                            ip == activeP2pGatewayIp
                        val isStrongBroadcastInterface = isP2pIface || isApIface
                        val priority = when {
                            isP2pIface && ip == "192.168.49.1" -> 0
                            isStrongBroadcastInterface && isLikelyGatewayAddress -> 1
                            isStrongBroadcastInterface -> 2
                            isKnownAndroidGateway -> 3
                            canTreatPrimaryWlanAsBroadcast && isWlanIface && isLikelyGatewayAddress -> 4
                            canTreatPrimaryWlanAsBroadcast && isWlanIface -> 5
                            isWlanIface -> 6
                            addr.isSiteLocalAddress -> 7
                            else -> 8
                        }
                        // Vendor hotspot interfaces use many names (ap0, swlan0, softap0,
                        // wlan1, bridge0). When a broadcast is active, also accept its wlan
                        // address even when the phone does not use the traditional .1 gateway.
                        val isBroadcastEndpoint = isStrongBroadcastInterface ||
                            isKnownAndroidGateway ||
                            (canTreatPrimaryWlanAsBroadcast && isWlanIface)
                        candidates.add(Triple(priority, ip, isBroadcastEndpoint))
                    }
                }
            }
        } catch (_: Exception) {
            return DetectedApNetwork(null, null, emptyList(), null)
        }
        val sorted = candidates.sortedBy { it.first }
        val endpoints = sorted.map { it.second }.distinct()
        val apSsid = if (apEnabledByWifi) resolveSystemHotspotSsid() else null
        return DetectedApNetwork(
            primaryIp = endpoints.firstOrNull(),
            primaryBroadcastIp = sorted.firstOrNull { it.third }?.second,
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
            // Some vendors expose the AP/P2P interface several seconds after reporting success.
            // Keep resolving for one minute so the URL updates to the address a laptop can reach.
            repeat(80) {
                delay(750L)
                val detection = detectActiveApAndWlanEndpoints()
                val current = BitVoidNodeStateHolder.hostState.value
                val systemHotspot = if (isSystemWifiApEnabled()) resolveSystemHotspotInfo() else null
                val secureSystemHotspot = systemHotspot?.isOpen == false &&
                    !systemHotspot.passphrase.isNullOrBlank()
                val secureBroadcastActive = isP2pGroupActive ||
                    hotspotReservation != null ||
                    secureSystemHotspot
                val resolved = when {
                    isP2pGroupActive -> detection.primaryBroadcastIp ?: "192.168.49.1"
                    hotspotReservation != null || secureSystemHotspot ->
                        detection.primaryBroadcastIp ?: "192.168.43.1"
                    else -> detection.primaryIp ?: "127.0.0.1"
                }
                val gatewayVerified = detection.allEndpoints.contains(resolved)
                val combinedIps = (detection.allEndpoints + resolved + "127.0.0.1").distinct()
                val friendlyPort = ensureFriendlyGateway(resolved)
                val urlList = buildPortalEndpoints(combinedIps, friendlyPort)
                val secureReady = ensureSecureServerRunning(combinedIps)
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
                val resolvedHostname = if (friendlyPort != null) BitVoidNetworkConfig.LOCAL_GATEWAY_HOSTNAME else ""
                if ((resolved != current.gatewayIp ||
                        gatewayVerified != current.isGatewayAddressVerified ||
                        resolvedHostname != current.gatewayHostname ||
                        (friendlyPort ?: BitVoidLocalServer.DEFAULT_PORT) != current.gatewayHostnamePort ||
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
                            isGatewayAddressVerified = gatewayVerified,
                            gatewayHostname = resolvedHostname,
                            gatewayHostnamePort = friendlyPort ?: BitVoidLocalServer.DEFAULT_PORT,
                            boundEndpoints = urlList,
                            isSecureServerRunning = secureReady
                        )
                    }
                    updateNotification(
                        if (secureBroadcastActive) {
                            "BitVoid secure hotspot broadcast · ${preferredPortalUrl(resolved, friendlyPort)}"
                        } else {
                            "BitVoid local vault · No secure hotspot broadcast · ${preferredPortalUrl(resolved, friendlyPort)}"
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
                        state.copy(
                            uptimeSeconds = state.uptimeSeconds + 1L,
                            connectedNodesCount = localServer?.connectedNodeCount() ?: 0
                        )
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
        mdnsResponder?.stop()
        try {
            hotspotReservation?.close()
        } catch (_: Exception) {
        }
        hotspotReservation = null
        if (isP2pGroupActive) {
            isP2pGroupActive = false
            activeP2pGatewayIp = null
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
            friendlyHttpServer?.stop()
        } catch (_: Exception) {
        }
        friendlyHttpServer = null
        mdnsResponder?.stop()
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
                isGatewayAddressVerified = false,
                gatewayHostname = "",
                gatewayHostnamePort = BitVoidLocalServer.DEFAULT_PORT,
                boundEndpoints = emptyList(),
                isSecureServerRunning = false,
                activeSessionsCount = 0,
                connectedNodesCount = 0,
                uptimeSeconds = 0L
            )
        }
    }

    private fun ensureNotificationChannels() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val messageChannel = NotificationChannel(
            MESSAGE_CHANNEL_ID,
            getString(R.string.message_notification_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = getString(R.string.message_notification_channel_desc)
            enableVibration(true)
            setShowBadge(true)
        }
        notificationManager.createNotificationChannels(listOf(serviceChannel, messageChannel))
    }

    private fun updateNotification(contentText: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun showMessageNotification(spec: MessageNotificationSpec) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            action = "com.bitvoid.network.action.OPEN_MESSAGE.${spec.destination.wireValue}.${spec.conversationId}"
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_MESSAGE_DESTINATION, spec.destination.wireValue)
            putExtra(EXTRA_CONVERSATION_ID, spec.conversationId)
            spec.peerUsername?.let { putExtra(EXTRA_PEER_USERNAME, it) }
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            spec.notificationId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bitvoid_notify)
            .setContentTitle(spec.title)
            .setContentText(spec.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(spec.body))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        notificationManager.notify(spec.notificationId, notification)
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

        val stopAction = if (isNodeForegroundActive) {
            ACTION_STOP_NODE
        } else {
            ACTION_STOP_MESSAGE_MONITOR
        }
        val stopIntent = Intent(this, BitVoidForegroundService::class.java).apply {
            action = stopAction
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            if (isNodeForegroundActive) 1 else 2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bitvoid_notify)
            .setContentTitle(
                if (isNodeForegroundActive) "BitVoid Gateway Active" else "BitVoid Messages Active"
            )
            .setContentText(contentText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(
                    if (isNodeForegroundActive) {
                        R.string.action_stop_node
                    } else {
                        R.string.action_stop_message_monitor
                    }
                ),
                stopPendingIntent
            )
            .build()
    }
}
