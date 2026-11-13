package com.bitvoid.network

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
    RUNNING,
    ERROR,
}

data class BitVoidNodeState(
    val status: NodeStatus = NodeStatus.STOPPED,
    val ssid: String = "—",
    val passphrase: String = "—",
    val gatewayIp: String = DEFAULT_GATEWAY_IP,
    val portalUrl: String = "http://$DEFAULT_GATEWAY_IP:${BitVoidLocalServer.DEFAULT_PORT}",
    val serverOnline: Boolean = false,
    val sharedFileCount: Int = 0,
    val errorMessage: String? = null,
) {
    val isActive: Boolean get() = status == NodeStatus.STARTING || status == NodeStatus.RUNNING

    companion object {
        const val DEFAULT_GATEWAY_IP = "192.168.43.1"
    }
}

/**
 * Keeps the local-only hotspot reservation and HTTP server alive as one foreground
 * lifecycle. Closing either resource immediately removes access to the node.
 */
class BitVoidForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var wifiManager: WifiManager
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var localServer: BitVoidLocalServer? = null
    private var startupJob: Job? = null
    private var nodeRequested = false
    private var generation = 0L
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
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
                val count = SharedFileStore.refresh(applicationContext).size
                updateState { it.copy(sharedFileCount = count) }
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
        if (hotspotReservation != null || nodeState.value.status == NodeStatus.STARTING) return

        generation += 1
        updateState {
            BitVoidNodeState(
                status = NodeStatus.STARTING,
                sharedFileCount = SharedFileStore.refresh(applicationContext).size,
            )
        }
        updateNotification()

        try {
            wifiManager.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        if (!nodeRequested) {
                            reservation.close()
                            return
                        }
                        hotspotReservation = reservation
                        val credentials = readHotspotCredentials(reservation)
                        beginServerStartup(credentials.first, credentials.second, generation)
                    }

                    override fun onStopped() {
                        hotspotReservation = null
                        stopServerOnly()
                        if (nodeRequested) {
                            nodeRequested = false
                            updateState {
                                it.copy(
                                    status = NodeStatus.ERROR,
                                    serverOnline = false,
                                    errorMessage = "The system stopped the local-only hotspot.",
                                )
                            }
                            updateNotification()
                            if (foregroundStarted) {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                foregroundStarted = false
                            }
                            stopSelf()
                        }
                    }

                    override fun onFailed(reason: Int) {
                        hotspotReservation = null
                        nodeRequested = false
                        updateState {
                            it.copy(
                                status = NodeStatus.ERROR,
                                serverOnline = false,
                                errorMessage = hotspotFailureMessage(reason),
                            )
                        }
                        updateNotification()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        foregroundStarted = false
                        stopSelf()
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        } catch (error: SecurityException) {
            failStartup("Wi-Fi permission was denied. Grant Nearby devices and Location access.")
        } catch (error: IllegalStateException) {
            failStartup(error.message ?: "The Wi-Fi hotspot could not be started.")
        }
    }

    private fun beginServerStartup(ssid: String, passphrase: String, startupGeneration: Long) {
        startupJob?.cancel()
        startupJob = serviceScope.launch {
            val gatewayIp = resolveHotspotAddress()
            if (!nodeRequested || startupGeneration != generation) return@launch

            updateState {
                it.copy(
                    ssid = ssid,
                    passphrase = passphrase.ifBlank { "Open network" },
                    gatewayIp = gatewayIp,
                    portalUrl = "http://$gatewayIp:${BitVoidLocalServer.DEFAULT_PORT}",
                )
            }

            try {
                val server = BitVoidLocalServer(
                    context = applicationContext,
                    bindAddress = gatewayIp,
                )
                server.startServer()
                if (!nodeRequested || startupGeneration != generation) {
                    server.stop()
                    return@launch
                }
                localServer = server
                updateState {
                    it.copy(
                        status = NodeStatus.RUNNING,
                        serverOnline = true,
                        sharedFileCount = SharedFileStore.refresh(applicationContext).size,
                        errorMessage = null,
                    )
                }
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

    private fun stopNode(updateStoppedState: Boolean) {
        generation += 1
        startupJob?.cancel()
        startupJob = null
        stopServerOnly()
        val reservation = hotspotReservation
        hotspotReservation = null
        runCatching { reservation?.close() }
        if (updateStoppedState) {
            _nodeState.value = BitVoidNodeState(
                status = NodeStatus.STOPPED,
                sharedFileCount = SharedFileStore.refresh(applicationContext).size,
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
    ): Pair<String, String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val configuration = reservation.softApConfiguration
            (configuration.ssid ?: "BitVoid Local Node") to configuration.passphrase.orEmpty()
        } else {
            val configuration = reservation.wifiConfiguration
            configuration.SSID.orEmpty().trim('"').ifBlank { "BitVoid Local Node" } to
                configuration.preSharedKey.orEmpty().trim('"')
        }
    }

    private suspend fun resolveHotspotAddress(): String {
        repeat(12) {
            findBestLocalAddress()?.let { return it }
            delay(250)
        }
        return BitVoidNodeState.DEFAULT_GATEWAY_IP
    }

    private fun findBestLocalAddress(): String? = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { network ->
                Collections.list(network.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                    .map { address ->
                        val interfaceName = network.name.lowercase(Locale.US)
                        var score = 0
                        if (interfaceName.contains("ap") || interfaceName.contains("soft")) score += 10
                        if (interfaceName.contains("wlan") || interfaceName.contains("wifi")) score += 5
                        if (address.hostAddress?.endsWith(".1") == true) score += 4
                        score to address.hostAddress
                    }
            }
            .maxByOrNull { it.first }
            ?.second
    }.getOrNull()

    private fun hotspotFailureMessage(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
            "No Wi-Fi channel is available. Disable another hotspot or Wi-Fi Direct session."

        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
            "The current Wi-Fi mode is incompatible with a local-only hotspot."

        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
            "This device policy does not allow a local-only hotspot."

        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC ->
            "Android could not create the local-only hotspot."

        else -> "Local-only hotspot failed with system code $reason."
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
        } catch (error: SecurityException) {
            _nodeState.value = BitVoidNodeState(
                status = NodeStatus.ERROR,
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
            description = "Keeps the isolated BitVoid hotspot and local portal active"
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
            NodeStatus.RUNNING -> "Portal online at ${state.portalUrl}"
            NodeStatus.STARTING -> "Creating an isolated local network…"
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

    companion object {
        const val ACTION_START_NODE = "com.bitvoid.network.action.START_NODE"
        const val ACTION_STOP_NODE = "com.bitvoid.network.action.STOP_NODE"
        const val ACTION_REFRESH_FILES = "com.bitvoid.network.action.REFRESH_FILES"

        private const val NOTIFICATION_CHANNEL_ID = "bitvoid_node"
        private const val NOTIFICATION_ID = 8080

        private val _nodeState = MutableStateFlow(BitVoidNodeState())
        val nodeState: StateFlow<BitVoidNodeState> = _nodeState.asStateFlow()
    }
}
