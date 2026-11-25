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
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bitvoid.network.MainActivity
import com.bitvoid.network.call.BitVoidSignalingServer
import com.bitvoid.network.channel.BitVoidChannelManager
import com.bitvoid.network.client.BitVoidClientRepositoryProvider
import com.bitvoid.network.media.BitVoidMediaManager
import com.bitvoid.network.model.BitVoidNetworkConfig
import com.bitvoid.network.model.HostState
import com.bitvoid.network.model.HotspotStatus
import com.bitvoid.network.server.BitVoidLocalServer
import com.bitvoid.network.server.BitVoidMdnsResponder
import com.bitvoid.network.R
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

        @Volatile
        private var appInForeground = false

        @Volatile
        private var visibleConversationId: String? = null

        /** Tracks whether a conversation is actually on screen, not merely connected. */
        fun setAppInForeground(isForeground: Boolean) {
            // Keep the last visible conversation across background/foreground transitions.
            // The foreground flag alone decides whether notifications are suppressed.
            appInForeground = isForeground
        }

        fun setVisibleConversation(conversationId: String?) {
            visibleConversationId = conversationId?.trim()?.takeIf { it.isNotEmpty() }
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
    private lateinit var wifiManager: WifiManager
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
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

            null -> {
                // A sticky restart restores the foreground listener as well as the local gateway.
                // Active WebSocket subscriptions still need to be rehydrated by the repository.
                isMessageMonitoringActive = true
                isNodeForegroundActive = true
                if (!promoteToForeground("Connecting BitVoid to the current local Wi-Fi...")) {
                    isNodeForegroundActive = false
                    stopSelf()
                    return START_NOT_STICKY
                }
                startEmbeddedServerOnly()
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

    private fun startEmbeddedServerOnly() {
        stopLanEndpointMonitoring()
        if (!ensureHttpServerRunning()) {
            handleNodeStartupFailure()
            return
        }

        val allEndpoints = (resolveAllNetworkIpv4s() + "127.0.0.1").distinct()
        val resolvedIp = allEndpoints.first()
        val activeSsid = resolveConnectedWifiSsid() ?: "Connected local Wi-Fi"
        val friendlyPort = ensureFriendlyGateway(resolvedIp)
        val urlList = buildPortalEndpoints(allEndpoints, friendlyPort)
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
                uptimeSeconds = 0L,
                errorMessage = null
            )
        }
        startUptimeTicker()
        probeAssignedGatewayIp()
        updateNotification("BitVoid local API online: ${preferredPortalUrl(resolvedIp, friendlyPort)}")
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

    private data class DetectedLanNetwork(
        val primaryIp: String?,
        val allEndpoints: List<String>
    )

    private fun detectActiveLanEndpoints(): DetectedLanNetwork {
        val candidates = mutableListOf<Pair<Int, String>>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                val excluded = name.startsWith("rmnet") ||
                    name.startsWith("ccmni") ||
                    name.startsWith("pdp") ||
                    name.startsWith("rev_rmnet") ||
                    name.startsWith("tun") ||
                    name.startsWith("tap") ||
                    name.startsWith("ppp") ||
                    name.startsWith("wg") ||
                    name.startsWith("p2p") ||
                    name.startsWith("ap") ||
                    name.startsWith("softap") ||
                    name.contains("tether")
                if (excluded) continue
                val priority = when {
                    name.startsWith("wlan") || name.startsWith("wifi") -> 0
                    name.startsWith("eth") || name.startsWith("en") -> 1
                    else -> 2
                }
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        address.isSiteLocalAddress
                    ) {
                        address.hostAddress?.let { candidates += priority to it }
                    }
                }
            }
        } catch (_: Exception) {
            return DetectedLanNetwork(null, emptyList())
        }
        val endpoints = candidates.sortedBy { it.first }.map { it.second }.distinct()
        return DetectedLanNetwork(endpoints.firstOrNull(), endpoints)
    }

    private fun probeAssignedGatewayIp() {
        ipProbeJob?.cancel()
        ipProbeJob = serviceScope.launch {
            repeat(80) {
                delay(750L)
                val detection = detectActiveLanEndpoints()
                val current = BitVoidNodeStateHolder.hostState.value
                val resolved = detection.primaryIp ?: "127.0.0.1"
                val combinedIps = (detection.allEndpoints + "127.0.0.1").distinct()
                val friendlyPort = ensureFriendlyGateway(resolved)
                val urlList = buildPortalEndpoints(combinedIps, friendlyPort)
                val resolvedHostname = if (friendlyPort != null) {
                    BitVoidNetworkConfig.LOCAL_GATEWAY_HOSTNAME
                } else {
                    ""
                }
                if ((resolved != current.gatewayIp ||
                        current.status != HotspotStatus.SERVER_ONLY ||
                        current.isGatewayAddressVerified != (resolved != "127.0.0.1") ||
                        resolvedHostname != current.gatewayHostname ||
                        urlList != current.boundEndpoints) &&
                    current.isServerRunning
                ) {
                    BitVoidNodeStateHolder.updateHostState {
                        it.copy(
                            status = HotspotStatus.SERVER_ONLY,
                            ssid = resolveConnectedWifiSsid().orEmpty(),
                            passphrase = "Managed by connected Wi-Fi",
                            gatewayIp = resolved,
                            isGatewayAddressVerified = resolved != "127.0.0.1",
                            gatewayHostname = resolvedHostname,
                            gatewayHostnamePort = friendlyPort ?: BitVoidLocalServer.DEFAULT_PORT,
                            boundEndpoints = urlList,
                            errorMessage = if (resolved == "127.0.0.1") {
                                "Connect this phone to local Wi-Fi to share the local server."
                            } else null
                        )
                    }
                    updateNotification(
                        "BitVoid local API · ${preferredPortalUrl(resolved, friendlyPort)}"
                    )
                }
            }
        }
    }

    private fun resolveAllNetworkIpv4s(): List<String> = detectActiveLanEndpoints().allEndpoints

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

    private fun stopLanEndpointMonitoring() {
        ipProbeJob?.cancel()
        mdnsResponder?.stop()
    }

    private fun shutdownNode() {
        uptimeJob?.cancel()
        stopLanEndpointMonitoring()
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
        if (BitVoidMessageNotifications.shouldSuppress(
                spec = spec,
                isAppInForeground = appInForeground,
                visibleConversationId = visibleConversationId
            )
        ) {
            return
        }

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
