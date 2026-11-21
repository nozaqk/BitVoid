package com.bitvoid.network.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitvoid.network.call.BitVoidCallManager
import com.bitvoid.network.call.BitVoidCallState
import com.bitvoid.network.call.DirectCallPhase
import com.bitvoid.network.call.ParticipantRole
import com.bitvoid.network.call.SignalingPeer
import com.bitvoid.network.call.SignalingSessionMode
import com.bitvoid.network.channel.BitVoidChannel
import com.bitvoid.network.channel.BitVoidChannelManager
import com.bitvoid.network.client.BitVoidClientManager
import com.bitvoid.network.client.BitVoidClientRepository
import com.bitvoid.network.client.BitVoidClientRepositoryProvider
import com.bitvoid.network.client.ChannelCallStatus
import com.bitvoid.network.client.ClientChannelSession
import com.bitvoid.network.client.ClientChatMessage
import com.bitvoid.network.client.ClientNodeConnectResult
import com.bitvoid.network.client.ClientDirectSession
import com.bitvoid.network.client.ClientWsStatus
import com.bitvoid.network.client.RemoteChannelSummary
import com.bitvoid.network.client.RemoteLiveClassSummary
import com.bitvoid.network.client.RemoteMediaItem
import com.bitvoid.network.connection.BitVoidConnectionPhase
import com.bitvoid.network.connection.BitVoidConnectionState
import com.bitvoid.network.connection.BitVoidHardwareIntegration
import com.bitvoid.network.connection.BitVoidNodeClient
import com.bitvoid.network.direct.BitVoidDirectChatManager
import com.bitvoid.network.direct.BitVoidUserPrefs
import com.bitvoid.network.direct.DirectConversationSummary
import com.bitvoid.network.media.BitVoidMediaManager
import com.bitvoid.network.model.AppBottomTab
import com.bitvoid.network.model.AuditLogEntry
import com.bitvoid.network.model.BitVoidNetworkConfig
import com.bitvoid.network.model.BitVoidNetworkPrefs
import com.bitvoid.network.model.ClientConnectionStatus
import com.bitvoid.network.model.ClientState
import com.bitvoid.network.model.HostState
import com.bitvoid.network.model.HotspotStatus
import com.bitvoid.network.model.NodeConnectionEventType
import com.bitvoid.network.model.NodeMode
import com.bitvoid.network.service.BitVoidForegroundService
import com.bitvoid.network.service.BitVoidNodeStateHolder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MESH_REFRESH_INTERVAL_MS = 15_000L
private const val AUTOMATIC_WIFI_SCAN_INTERVAL_MS = 60_000L
private const val AUTO_GATEWAY_RETRY_INTERVAL_MS = 30_000L

class BitVoidViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val clientManager = BitVoidClientManager(appContext)
    private val clientRepository = BitVoidClientRepositoryProvider.repository
    private val hardwareIntegrationDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BitVoidHardwareIntegration(appContext)
    }
    private val callManager = BitVoidCallManager(appContext)

    private val _hardwareConnectionState = MutableStateFlow(
        BitVoidConnectionState(
            statusMessage = "Hardware integration is optional. Open Node Log to monitor discovery."
        )
    )
    val hardwareConnectionState: StateFlow<BitVoidConnectionState> =
        _hardwareConnectionState.asStateFlow()
    val hardwareNodeClient: BitVoidNodeClient?
        get() = if (hardwareIntegrationDelegate.isInitialized()) {
            hardwareIntegrationDelegate.value.nodeClient
        } else {
            null
        }
    private var hardwareStateJob: Job? = null

    private val _selectedMode = MutableStateFlow(NodeMode.HOST)
    val selectedMode: StateFlow<NodeMode> = _selectedMode.asStateFlow()

    private val _selectedBottomTab = MutableStateFlow(AppBottomTab.HOME)
    val selectedBottomTab: StateFlow<AppBottomTab> = _selectedBottomTab.asStateFlow()

    private val _isImportingFiles = MutableStateFlow(false)
    val isImportingFiles: StateFlow<Boolean> = _isImportingFiles.asStateFlow()

    private val _myUsername = MutableStateFlow(BitVoidUserPrefs.loadOrCreate(appContext))
    val myUsername: StateFlow<String> = _myUsername.asStateFlow()

    private val _isUserIdDiscoverable = MutableStateFlow(BitVoidUserPrefs.isDiscoverable(appContext))
    val isUserIdDiscoverable: StateFlow<Boolean> = _isUserIdDiscoverable.asStateFlow()

    val hostState: StateFlow<HostState> = BitVoidNodeStateHolder.hostState
    val auditLogs: StateFlow<List<AuditLogEntry>> = BitVoidNodeStateHolder.auditLogs
    val nodeConnectionLogs = BitVoidNodeStateHolder.nodeConnectionLogs
    val clientState: StateFlow<ClientState> = BitVoidNodeStateHolder.clientState
    val channels: StateFlow<List<BitVoidChannel>> = BitVoidChannelManager.channelsFlow
    val directConversations: StateFlow<List<DirectConversationSummary>> = BitVoidDirectChatManager.conversationsFlow
    val knownUsers: StateFlow<List<String>> = combine(
        BitVoidDirectChatManager.knownUsersFlow,
        clientRepository.knownUsers
    ) { localUsers, remoteUsers ->
        (localUsers + remoteUsers)
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val clientGatewayUrl: StateFlow<String> = clientRepository.gatewayBaseUrl
    val clientPublicMedia: StateFlow<List<RemoteMediaItem>> = clientRepository.publicMedia
    val isLoadingPublicMedia: StateFlow<Boolean> = clientRepository.isLoadingPublicMedia
    val publicMediaError: StateFlow<String?> = clientRepository.publicMediaError

    val clientChannels: StateFlow<List<RemoteChannelSummary>> = clientRepository.channels
    val liveClasses: StateFlow<List<RemoteLiveClassSummary>> = clientRepository.liveClasses
    val isLoadingChannels: StateFlow<Boolean> = clientRepository.isLoadingChannels
    val channelsError: StateFlow<String?> = clientRepository.channelsError
    val activeChannelSession: StateFlow<ClientChannelSession?> = clientRepository.activeChannelSession
    val channelMessages: StateFlow<List<ClientChatMessage>> = clientRepository.channelMessages
    val clientWsStatus: StateFlow<ClientWsStatus> = clientRepository.wsStatus
    val channelPeersCount: StateFlow<Int> = clientRepository.channelPeersCount
    val isJoiningChannel: StateFlow<Boolean> = clientRepository.isJoiningChannel
    val channelJoinError: StateFlow<String?> = clientRepository.channelJoinError

    val activeDirectSession: StateFlow<ClientDirectSession?> = clientRepository.activeDirectSession
    val directMessages: StateFlow<List<ClientChatMessage>> = clientRepository.directMessages
    val directWsStatus: StateFlow<ClientWsStatus> = clientRepository.directWsStatus
    val isOpeningDirectChat: StateFlow<Boolean> = clientRepository.isOpeningDirectChat
    val directChatError: StateFlow<String?> = clientRepository.directChatError
    val isUploadingChatFile: StateFlow<Boolean> = clientRepository.isUploadingFile
    val fileUploadError: StateFlow<String?> = clientRepository.fileUploadError

    private val _extraCallError = MutableStateFlow<String?>(null)
    private var pendingOutgoingDirectCall: Pair<String, String>? = null
    private var openingIncomingCallDmId: String? = null

    private data class CallSessionMeta(
        val callState: BitVoidCallState,
        val activeRoomId: String?,
        val activeChannelName: String,
        val sessionMode: SignalingSessionMode,
        val localRole: ParticipantRole
    )

    private data class CallMediaToggles(
        val isMicMuted: Boolean,
        val isSpeakerphoneOn: Boolean
    )

    private data class CallTracksAndPeers(
        val myPeerId: String,
        val participants: List<SignalingPeer>
    )

    private data class CallStatusAndClassMeta(
        val channelCallStatus: ChannelCallStatus?,
        val directCallStatus: ChannelCallStatus?,
        val callError: String?,
        val liveClassCode: String?
    )

    private val callSessionMetaFlow = combine(
        callManager.callState,
        callManager.activeRoomId,
        callManager.activeChannelName,
        callManager.sessionMode,
        callManager.localRole
    ) { callState, activeRoomId, activeChannelName, sessionMode, localRole ->
        CallSessionMeta(
            callState = callState,
            activeRoomId = activeRoomId,
            activeChannelName = activeChannelName,
            sessionMode = sessionMode,
            localRole = localRole
        )
    }

    private val callMediaTogglesFlow = combine(
        callManager.isMicMuted,
        callManager.isSpeakerphoneOn
    ) { isMicMuted, isSpeakerphoneOn ->
        CallMediaToggles(
            isMicMuted = isMicMuted,
            isSpeakerphoneOn = isSpeakerphoneOn
        )
    }

    private val callTracksAndPeersFlow = combine(
        callManager.myPeerId,
        callManager.participants
    ) { myPeerId, participants ->
        CallTracksAndPeers(
            myPeerId = myPeerId,
            participants = participants
        )
    }

    private val callStatusAndClassFlow = combine(
        clientRepository.activeChannelCallStatus,
        clientRepository.activeDirectCallStatus,
        callManager.callError,
        _extraCallError,
        callManager.liveClassCode
    ) { chStatus, dmStatus, mgrErr, extraErr, classCode ->
        CallStatusAndClassMeta(
            channelCallStatus = chStatus,
            directCallStatus = dmStatus,
            callError = extraErr ?: mgrErr,
            liveClassCode = classCode
        )
    }

    val callUiState: StateFlow<BitVoidCallUiState> = combine(
        callSessionMetaFlow,
        callMediaTogglesFlow,
        callTracksAndPeersFlow,
        callStatusAndClassFlow
    ) { meta, toggles, tracks, statusMeta ->
        BitVoidCallUiState(
            callState = meta.callState,
            activeRoomId = meta.activeRoomId,
            activeChannelName = meta.activeChannelName,
            sessionMode = meta.sessionMode,
            localRole = meta.localRole,
            myPeerId = tracks.myPeerId,
            participants = tracks.participants,
            isMicMuted = toggles.isMicMuted,
            isSpeakerphoneOn = toggles.isSpeakerphoneOn,
            activeChannelCallStatus = statusMeta.channelCallStatus,
            activeDirectCallStatus = statusMeta.directCallStatus,
            liveClassCode = statusMeta.liveClassCode,
            callError = statusMeta.callError
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BitVoidCallUiState())

    private var customClientUrlPinned = false
    private var autoGatewayStartInProgress = false
    private var lastAutoGatewayAttemptMs = 0L
    private var lastAutomaticWifiScanMs = 0L

    init {
        BitVoidNodeStateHolder.appendNodeConnectionLog(
            NodeConnectionEventType.APP,
            "BitVoid mesh monitor started",
            "Automatic same-LAN identity discovery is active."
        )
        BitVoidChannelManager.ensureDefaultChannel()
        BitVoidDirectChatManager.registerUser(
            _myUsername.value,
            _isUserIdDiscoverable.value
        )
        BitVoidDirectChatManager.refreshConversationsForUser(_myUsername.value)
        loadSavedNetworkPreferences()
        viewModelScope.launch {
            var wasMonitoringMessages = false
            combine(
                clientRepository.activeChannelSession,
                clientRepository.activeDirectSession
            ) { channelSession, directSession ->
                (channelSession != null && !channelSession.isLiveClassRoom) || directSession != null
            }.collect { hasActiveConversation ->
                if (hasActiveConversation) {
                    BitVoidForegroundService.startMessageMonitoring(appContext)
                    wasMonitoringMessages = true
                } else if (wasMonitoringMessages) {
                    BitVoidForegroundService.stopMessageMonitoring(appContext)
                    wasMonitoringMessages = false
                }
            }
        }
        viewModelScope.launch {
            BitVoidMediaManager.mediaItems.collect { items ->
                BitVoidChannelManager.syncMediaCatalog(items)
                BitVoidNodeStateHolder.updateHostState { it.copy(sharedFiles = items) }
            }
        }
        viewModelScope.launch {
            var lastStatus: ClientConnectionStatus? = null
            var lastGatewayUrl: String? = null
            clientState.collect { cState ->
                if (cState.connectionStatus == ClientConnectionStatus.CONNECTED) {
                    val gatewayChanged = lastGatewayUrl != cState.targetPortalUrl
                    clientRepository.setGatewayBaseUrl(cState.targetPortalUrl)
                    if (lastStatus != ClientConnectionStatus.CONNECTED || gatewayChanged) {
                        refreshClientChannels(cState.targetPortalUrl)
                        refreshClientPublicMedia(cState.targetPortalUrl)
                        refreshLiveClasses(cState.targetPortalUrl)
                    }
                    lastGatewayUrl = cState.targetPortalUrl
                }
                lastStatus = cState.connectionStatus
            }
        }
        viewModelScope.launch {
            var previousHostStatus: HotspotStatus? = null
            var previousHostGateway: String? = null
            hostState.collect { hState ->
                if (previousHostStatus != null && previousHostStatus != hState.status) {
                    val eventType = when (hState.status) {
                        HotspotStatus.ACTIVE, HotspotStatus.SERVER_ONLY -> NodeConnectionEventType.GATEWAY
                        HotspotStatus.ERROR -> NodeConnectionEventType.ERROR
                        HotspotStatus.IDLE -> NodeConnectionEventType.DISCONNECTED
                        HotspotStatus.STARTING -> NodeConnectionEventType.RETRY
                    }
                    BitVoidNodeStateHolder.appendNodeConnectionLog(
                        eventType,
                        "App gateway ${hState.status.name.lowercase().replace('_', ' ')}",
                        hState.errorMessage ?: if (hState.hasClientGatewayAddress) {
                            hState.networkPortalUrl
                        } else {
                            "No client gateway address is currently assigned."
                        },
                        isError = hState.status == HotspotStatus.ERROR
                    )
                }
                if (hState.hasClientGatewayAddress && hState.networkPortalUrl != previousHostGateway) {
                    BitVoidNodeStateHolder.appendNodeConnectionLog(
                        NodeConnectionEventType.GATEWAY,
                        "App gateway address assigned",
                        hState.networkPortalUrl
                    )
                    previousHostGateway = hState.networkPortalUrl
                }
                previousHostStatus = hState.status
                if (!customClientUrlPinned &&
                    clientState.value.connectionStatus != ClientConnectionStatus.CONNECTED &&
                    hState.isServerRunning &&
                    hState.gatewayIp.isNotBlank()
                ) {
                    val localUrl = hState.networkPortalUrl
                    if (clientGatewayUrl.value != localUrl) {
                        clientRepository.setGatewayBaseUrl(localUrl)
                        if (_selectedBottomTab.value == AppBottomTab.HUB || _selectedMode.value == NodeMode.CLIENT) {
                            refreshClientChannels(localUrl)
                            refreshClientPublicMedia(localUrl)
                            refreshLiveClasses(localUrl)
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            // Refresh every BitVoid node over the phone's existing Wi-Fi without asking Android to
            // switch networks. In Node Log mode, recover a missing mesh by automatically starting
            // this phone's LAN gateway, then advertise it for every other app to discover. Keep
            // scanning and listening while this phone hosts the gateway so newly joined peers link
            // automatically without stopping gateway sharing.
            delay(1_500L)
            while (isActive) {
                if (clientState.value.connectionStatus != ClientConnectionStatus.CONNECTING &&
                    !clientState.value.isScanning
                ) {
                    val gateway = clientManager.discoverAndLinkSilently()
                    if (gateway == null && _selectedMode.value == NodeMode.CLIENT) {
                        ensureAutomaticGatewayOnCurrentWifi()
                    }
                }
                val now = System.currentTimeMillis()
                val shouldMonitorLocalLinks = _selectedMode.value == NodeMode.CLIENT ||
                    hostState.value.isServerRunning
                if (shouldMonitorLocalLinks &&
                    !clientState.value.isScanning &&
                    now - lastAutomaticWifiScanMs >= AUTOMATIC_WIFI_SCAN_INTERVAL_MS
                ) {
                    lastAutomaticWifiScanMs = now
                    clientManager.scanNearbyNetworks()
                }
                delay(MESH_REFRESH_INTERVAL_MS)
            }
        }
        viewModelScope.launch {
            // Presence is intentionally ephemeral: active app users renew their username on
            // this local node, while users that leave the Wi-Fi disappear automatically.
            delay(2_000L)
            while (isActive) {
                BitVoidDirectChatManager.registerUser(
                    _myUsername.value,
                    _isUserIdDiscoverable.value
                )
                if (clientState.value.connectionStatus == ClientConnectionStatus.CONNECTED ||
                    hostState.value.isServerRunning
                ) {
                    clientRepository.registerUsername(
                        _myUsername.value,
                        discoverable = _isUserIdDiscoverable.value
                    )
                }
                delay(15_000L)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                if (_selectedBottomTab.value == AppBottomTab.PCHAT &&
                    (hostState.value.isServerRunning ||
                        clientState.value.connectionStatus == ClientConnectionStatus.CONNECTED)
                ) {
                    clientRepository.registerUsername(
                        _myUsername.value,
                        discoverable = _isUserIdDiscoverable.value
                    )
                    clientRepository.fetchKnownUsers()
                }
                delay(3_000L)
            }
        }
        viewModelScope.launch {
            clientRepository.activeDirectCallStatus.collect { status ->
                val pending = pendingOutgoingDirectCall ?: return@collect
                if (status?.roomId != pending.first) return@collect
                when (status.directCallPhase) {
                    DirectCallPhase.ACCEPTED -> {
                        pendingOutgoingDirectCall = null
                        val session = activeDirectSession.value
                        if (session != null && session.dmId == pending.first &&
                            callManager.activeRoomId.value != session.dmId
                        ) {
                            connectDirectCall(session)
                        }
                    }
                    DirectCallPhase.DECLINED -> {
                        pendingOutgoingDirectCall = null
                        if (callManager.activeRoomId.value == pending.first) {
                            callManager.leaveSession()
                        }
                        _extraCallError.value = "@${pending.second} declined the call."
                    }
                    DirectCallPhase.CANCELED, DirectCallPhase.MISSED, DirectCallPhase.ENDED -> {
                        pendingOutgoingDirectCall = null
                        if (callManager.activeRoomId.value == pending.first) {
                            callManager.leaveSession()
                        }
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            delay(1_000L)
            while (isActive) {
                val incoming = clientRepository.fetchIncomingDirectCall(_myUsername.value)
                if (incoming != null) {
                    if (activeDirectSession.value?.dmId != incoming.dmId &&
                        openingIncomingCallDmId != incoming.dmId
                    ) {
                        openingIncomingCallDmId = incoming.dmId
                        clientRepository.openDirectChat(
                            myUsername = _myUsername.value,
                            targetUsername = incoming.callerUsername
                        )
                        openingIncomingCallDmId = null
                    }
                    if (activeDirectSession.value?.dmId == incoming.dmId) {
                        _selectedBottomTab.value = AppBottomTab.PCHAT
                    }
                }
                delay(1_000L)
            }
        }
        refreshVaultFiles()
    }

    private fun loadSavedNetworkPreferences() {
        val saved = BitVoidNetworkPrefs.load(appContext)
        BitVoidNodeStateHolder.updateHostState { current ->
            if (current.isEngineLocked) {
                current.copy(
                    configuredSsid = saved.ssid,
                    configuredPassphrase = saved.passphrase
                )
            } else {
                current.copy(
                    isHiddenSsid = saved.isHiddenSsid,
                    securityLabel = BitVoidNetworkConfig.WPA2_SECURITY_LABEL,
                    configuredSsid = saved.ssid,
                    configuredPassphrase = saved.passphrase
                )
            }
        }
    }

    fun openMessageNotification(
        destination: String?,
        conversationId: String?,
        peerUsername: String?
    ) {
        val cleanConversationId = conversationId?.trim().orEmpty()
        when (destination) {
            "channel" -> {
                _selectedBottomTab.value = AppBottomTab.HUB
                if (cleanConversationId.isNotEmpty() &&
                    activeChannelSession.value?.channelId != cleanConversationId
                ) {
                    openHubChannel(cleanConversationId)
                }
            }

            "direct" -> {
                _selectedBottomTab.value = AppBottomTab.PCHAT
                val cleanPeer = peerUsername?.trim()?.removePrefix("@").orEmpty()
                if (cleanPeer.isNotEmpty() &&
                    (activeDirectSession.value?.dmId != cleanConversationId ||
                        !activeDirectSession.value?.peerUsername.equals(cleanPeer, ignoreCase = true))
                ) {
                    openDirectChat(cleanPeer)
                }
            }
        }
    }

    fun selectBottomTab(tab: AppBottomTab) {
        _selectedBottomTab.value = tab
        if (tab == AppBottomTab.HUB) {
            syncGatewayToLocalIfAvailable()
            refreshClientChannels()
            refreshClientPublicMedia()
            refreshLiveClasses()
        } else if (tab == AppBottomTab.PCHAT) {
            syncGatewayToLocalIfAvailable()
            BitVoidDirectChatManager.refreshConversationsForUser(_myUsername.value)
            if (hostState.value.isServerRunning ||
                clientState.value.connectionStatus == ClientConnectionStatus.CONNECTED
            ) {
                viewModelScope.launch {
                    clientRepository.registerUsername(
                        _myUsername.value,
                        discoverable = _isUserIdDiscoverable.value
                    )
                    clientRepository.fetchKnownUsers()
                }
            }
        }
    }

    fun selectMode(mode: NodeMode) {
        _selectedMode.value = mode
        if (mode == NodeMode.CLIENT) {
            startHardwareIntegrationIfNeeded()
            lastAutomaticWifiScanMs = System.currentTimeMillis()
            clientManager.scanNearbyNetworks()
            viewModelScope.launch {
                var scanWaits = 0
                while (clientState.value.isScanning && scanWaits < 60) {
                    delay(100L)
                    scanWaits++
                }
                if (_selectedMode.value == NodeMode.CLIENT) {
                    val gateway = clientManager.discoverAndLinkSilently()
                    if (gateway == null) ensureAutomaticGatewayOnCurrentWifi()
                }
            }
        }
    }

    private suspend fun ensureAutomaticGatewayOnCurrentWifi() {
        val now = System.currentTimeMillis()
        if (autoGatewayStartInProgress ||
            clientState.value.currentWifiSsid.isNullOrBlank() ||
            hostState.value.status == HotspotStatus.STARTING ||
            hostState.value.isServerRunning ||
            now - lastAutoGatewayAttemptMs < AUTO_GATEWAY_RETRY_INTERVAL_MS
        ) {
            return
        }
        autoGatewayStartInProgress = true
        lastAutoGatewayAttemptMs = now
        BitVoidNodeStateHolder.appendNodeConnectionLog(
            NodeConnectionEventType.GATEWAY,
            "Starting shared app gateway",
            "No app gateway answered on ${clientState.value.currentWifiSsid}; this phone will share one automatically."
        )
        val started = runCatching {
            BitVoidForegroundService.startServerOnly(appContext)
        }.isSuccess
        if (!started) {
            BitVoidNodeStateHolder.appendNodeConnectionLog(
                NodeConnectionEventType.ERROR,
                "Automatic gateway start blocked",
                "Open Node Log and allow BitVoid's foreground-service notification, then retry.",
                isError = true
            )
            autoGatewayStartInProgress = false
            return
        }
        var gatewayWaits = 0
        while ((!hostState.value.isServerRunning || hostState.value.gatewayIp.isBlank()) &&
            hostState.value.status != HotspotStatus.ERROR &&
            gatewayWaits < 80
        ) {
            delay(100L)
            gatewayWaits++
        }
        if (hostState.value.isServerRunning) {
            clientManager.discoverAndLinkSilently()
        }
        autoGatewayStartInProgress = false
    }

    private fun startHardwareIntegrationIfNeeded(): BitVoidHardwareIntegration? {
        if (hardwareStateJob != null) return hardwareIntegrationDelegate.value
        return try {
            val integration = hardwareIntegrationDelegate.value
            integration.start()
            hardwareStateJob = viewModelScope.launch {
                var previousSignature: String? = null
                integration.state.collect { state ->
                    _hardwareConnectionState.value = state
                    val signature = "${state.phase}|${state.endpoint?.directUrl}|${state.errorMessage}"
                    if (signature != previousSignature) {
                        val eventType = when (state.phase) {
                            BitVoidConnectionPhase.READY -> NodeConnectionEventType.CONNECTED
                            BitVoidConnectionPhase.ERROR -> NodeConnectionEventType.ERROR
                            BitVoidConnectionPhase.BINDING -> NodeConnectionEventType.WIFI
                            BitVoidConnectionPhase.DISCOVERING -> NodeConnectionEventType.DISCOVERY
                            BitVoidConnectionPhase.WAITING_FOR_WIFI -> NodeConnectionEventType.RETRY
                            BitVoidConnectionPhase.STOPPED -> NodeConnectionEventType.DISCONNECTED
                        }
                        BitVoidNodeStateHolder.appendNodeConnectionLog(
                            eventType,
                            "Hardware node ${state.phase.name.lowercase().replace('_', ' ')}",
                            state.errorMessage ?: state.statusMessage,
                            isError = state.phase == BitVoidConnectionPhase.ERROR
                        )
                        previousSignature = signature
                    }
                }
            }
            integration
        } catch (error: Throwable) {
            _hardwareConnectionState.value = BitVoidConnectionState(
                phase = BitVoidConnectionPhase.ERROR,
                statusMessage = "ESP integration is unavailable; phone-hosted sharing still works.",
                errorMessage = error.message ?: "Unable to initialize optional ESP networking."
            )
            null
        }
    }

    private fun syncGatewayToLocalIfAvailable() {
        val hState = hostState.value
        if (!customClientUrlPinned &&
            clientState.value.connectionStatus != ClientConnectionStatus.CONNECTED &&
            hState.isServerRunning &&
            hState.gatewayIp.isNotBlank()
        ) {
            clientRepository.setGatewayBaseUrl(hState.networkPortalUrl)
        }
    }

    fun updateBroadcastSettings(rawSsid: String, rawPassphrase: String): Result<Unit> {
        val cleanSsid = rawSsid.trim()
        if (cleanSsid.isEmpty()) {
            return Result.failure(IllegalArgumentException("Wi-Fi broadcast name (SSID) cannot be empty."))
        }
        if (cleanSsid.toByteArray(StandardCharsets.UTF_8).size > 32) {
            return Result.failure(IllegalArgumentException("Wi-Fi broadcast name must be 32 bytes or fewer."))
        }
        val cleanPass = rawPassphrase.trim()
        if (!BitVoidNetworkConfig.isValidWpa2Passphrase(cleanPass)) {
            return Result.failure(
                IllegalArgumentException(
                    "Wi-Fi password must contain 8 to 63 printable ASCII characters."
                )
            )
        }

        BitVoidNetworkPrefs.saveBroadcastCredentials(appContext, cleanSsid, cleanPass)
        BitVoidNodeStateHolder.updateHostState { current ->
            // Saved credentials describe the next custom broadcast. Never overwrite the live
            // radio credentials here: Android may still be serving an existing LAN/hotspot.
            current.copy(
                configuredSsid = cleanSsid,
                configuredPassphrase = cleanPass
            )
        }
        return Result.success(Unit)
    }

    fun toggleHostGateway() {
        val currentState = hostState.value
        if (currentState.isEngineLocked) {
            BitVoidForegroundService.stopNode(appContext)
        } else {
            BitVoidForegroundService.startHostNode(appContext)
        }
    }

    fun startServerOnlyMode() {
        BitVoidForegroundService.startServerOnly(appContext)
    }

    fun stopHostNode() {
        BitVoidForegroundService.stopNode(appContext)
    }

    fun openHotspotTetherSettings() {
        val tetherIntent = Intent().apply {
            component = ComponentName("com.android.settings", "com.android.settings.TetherSettings")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val wirelessFallback = Intent(Settings.ACTION_WIRELESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            if (tetherIntent.resolveActivity(appContext.packageManager) != null) {
                appContext.startActivity(tetherIntent)
            } else {
                appContext.startActivity(wirelessFallback)
            }
        } catch (_: Exception) {
            appContext.startActivity(wirelessFallback)
        }
    }

    fun refreshVaultFiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val files = BitVoidMediaManager.refreshFromDisk(appContext)
            BitVoidChannelManager.syncMediaCatalog(files)
        }
    }

    fun setVideoPublic(id: String, isPublic: Boolean) {
        BitVoidMediaManager.setVideoPublic(id, isPublic)
    }

    fun createChannel(
        name: String,
        isPublic: Boolean,
        customChannelId: String?,
        privateKey: String?,
        mediaIds: Set<String>,
        isHidden: Boolean = false
    ): Result<BitVoidChannel> {
        val result = BitVoidChannelManager.createChannel(
            rawName = name,
            isPublic = isPublic,
            rawChannelId = customChannelId,
            rawPrivateKey = privateKey,
            mediaIds = mediaIds,
            isHidden = isHidden,
            creatorUsername = _myUsername.value
        )
        if (result.isSuccess && hostState.value.isServerRunning) {
            refreshClientChannels()
        }
        return result
    }

    fun updateChannel(
        channelId: String,
        name: String,
        isPublic: Boolean,
        privateKey: String?,
        mediaIds: Set<String>,
        isHidden: Boolean
    ): Result<BitVoidChannel> {
        val result = BitVoidChannelManager.updateChannel(
            channelId = channelId,
            rawName = name,
            isPublic = isPublic,
            rawPrivateKey = privateKey,
            mediaIds = mediaIds,
            isHidden = isHidden,
            requesterUsername = _myUsername.value
        )
        if (result.isSuccess && hostState.value.isServerRunning) refreshClientChannels()
        return result
    }

    fun setChannelVisibility(channelId: String, isPublic: Boolean, privateKey: String? = null): Result<BitVoidChannel> {
        val res = BitVoidChannelManager.setChannelVisibility(channelId, isPublic, privateKey)
        if (res.isSuccess && hostState.value.isServerRunning) {
            refreshClientChannels()
        }
        return res
    }

    fun assignMediaToChannel(channelId: String, mediaIds: Set<String>) {
        BitVoidChannelManager.assignMediaToChannel(channelId, mediaIds)
        if (hostState.value.isServerRunning) {
            refreshClientChannels()
            viewModelScope.launch { clientRepository.refreshActiveChannelMedia() }
        }
    }

    fun toggleMediaInChannel(channelId: String, mediaId: String) {
        BitVoidChannelManager.toggleMediaAssignment(channelId, mediaId)
        if (hostState.value.isServerRunning) {
            refreshClientChannels()
            viewModelScope.launch { clientRepository.refreshActiveChannelMedia() }
        }
    }

    fun deleteChannel(channelId: String): Result<Unit> {
        val result = BitVoidChannelManager.deleteChannelByCreator(channelId, _myUsername.value)
        if (result.isSuccess && hostState.value.isServerRunning) {
            refreshClientChannels()
        }
        return result
    }

    fun suggestPrivateChannelId(): String {
        return BitVoidChannelManager.generatePrivateChannelId()
    }

    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isImportingFiles.value = true
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    BitVoidMediaManager.importFromUri(appContext, appContext.contentResolver, uri)
                }
            }
            _isImportingFiles.value = false
        }
    }

    fun deleteFile(idOrName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            BitVoidMediaManager.deleteByIdOrName(appContext, idOrName)
        }
    }

    fun clearAuditLogs() {
        BitVoidNodeStateHolder.clearAuditLogs()
    }

    fun clearNodeConnectionLogs() {
        BitVoidNodeStateHolder.clearNodeConnectionLogs()
        BitVoidNodeStateHolder.appendNodeConnectionLog(
            NodeConnectionEventType.APP,
            "Node Log cleared",
            "New connection events will appear here."
        )
    }

    fun scanNearbyNodes() {
        lastAutomaticWifiScanMs = System.currentTimeMillis()
        clientManager.scanNearbyNetworks()
    }

    fun connectToGatewayUrl(rawUrl: String) {
        viewModelScope.launch {
            clientManager.connectToGatewayUrl(rawUrl)
        }
    }

    fun connectToNode(ssid: String) {
        viewModelScope.launch {
            when (clientManager.connectToNode(ssid, reportMissingNode = false)) {
                ClientNodeConnectResult.NO_NODE -> {
                    // No gateway is present yet. Host one on the Wi-Fi Android already joined,
                    // wait for the LAN server to bind, then rescan and link all reachable nodes.
                    BitVoidForegroundService.startServerOnly(appContext)
                    var attempts = 0
                    while (!hostState.value.isServerRunning &&
                        hostState.value.status != HotspotStatus.ERROR &&
                        attempts < 60
                    ) {
                        delay(100L)
                        attempts++
                    }
                    clientManager.connectToNode(ssid, reportMissingNode = true)
                }

                ClientNodeConnectResult.CONNECTED,
                ClientNodeConnectResult.NO_WIFI,
                ClientNodeConnectResult.WRONG_WIFI -> Unit
            }
        }
    }

    fun disconnectClient() {
        if (callManager.activeRoomId.value != null) {
            callManager.leaveSession()
        }
        clientRepository.leaveChannel()
        clientManager.disconnectFromNode()
    }

    fun setClientGatewayUrl(rawUrl: String): Boolean {
        val normalized = clientRepository.setGatewayBaseUrl(rawUrl) ?: return false
        customClientUrlPinned = normalized != hostState.value.effectivePortalUrl &&
            normalized != hostState.value.networkPortalUrl &&
            normalized != BitVoidClientRepository.DEFAULT_GATEWAY_URL
        BitVoidNodeStateHolder.updateClientState {
            it.copy(
                targetPortalUrl = normalized,
                errorMessage = null
            )
        }
        return true
    }

    fun refreshClientChannels(urlOverride: String? = null) {
        viewModelScope.launch {
            clientRepository.fetchChannels(urlOverride, username = _myUsername.value)
        }
    }

    fun refreshLiveClasses(urlOverride: String? = null) {
        viewModelScope.launch {
            clientRepository.fetchLiveClasses(_myUsername.value, urlOverride)
        }
    }

    fun refreshClientPublicMedia(urlOverride: String? = null) {
        viewModelScope.launch {
            clientRepository.fetchPublicMedia(urlOverride)
        }
    }

    fun joinChannel(channelId: String, privateKey: String? = null, urlOverride: String? = null) {
        viewModelScope.launch {
            clientRepository.joinChannel(
                rawChannelId = channelId,
                rawPrivateKey = privateKey,
                urlOverride = urlOverride,
                username = _myUsername.value
            )
        }
    }

    fun openHubChannel(channelId: String, privateKey: String? = null) {
        viewModelScope.launch {
            ensureLocalGatewayReadyIfNeeded()
            syncGatewayToLocalIfAvailable()
            clientRepository.joinChannel(
                rawChannelId = channelId,
                rawPrivateKey = privateKey,
                username = _myUsername.value
            )
        }
    }

    fun updateMyUsername(rawUsername: String): Result<String> {
        val res = BitVoidUserPrefs.saveUsername(appContext, rawUsername)
        res.onSuccess { clean ->
            _myUsername.value = clean
            BitVoidDirectChatManager.registerUser(clean, _isUserIdDiscoverable.value)
            BitVoidDirectChatManager.refreshConversationsForUser(clean)
            if (hostState.value.isServerRunning ||
                clientState.value.connectionStatus == ClientConnectionStatus.CONNECTED
            ) {
                viewModelScope.launch {
                    clientRepository.registerUsername(
                        clean,
                        discoverable = _isUserIdDiscoverable.value
                    )
                }
            }
        }
        return res
    }

    fun setUserIdDiscoverable(discoverable: Boolean) {
        _isUserIdDiscoverable.value = discoverable
        BitVoidUserPrefs.setDiscoverable(appContext, discoverable)
        BitVoidDirectChatManager.registerUser(_myUsername.value, discoverable)
        viewModelScope.launch {
            if (hostState.value.isServerRunning ||
                clientState.value.connectionStatus == ClientConnectionStatus.CONNECTED
            ) {
                clientRepository.registerUsername(
                    _myUsername.value,
                    discoverable = discoverable
                )
                clientRepository.fetchKnownUsers()
            }
        }
    }

    fun openDirectChat(targetUsername: String) {
        viewModelScope.launch {
            ensureLocalGatewayReadyIfNeeded()
            syncGatewayToLocalIfAvailable()
            clientRepository.registerUsername(
                _myUsername.value,
                discoverable = _isUserIdDiscoverable.value
            )
            clientRepository.openDirectChat(
                myUsername = _myUsername.value,
                targetUsername = targetUsername
            )
            BitVoidDirectChatManager.refreshConversationsForUser(_myUsername.value)
        }
    }

    fun sendDirectChatMessage(text: String) {
        clientRepository.sendDirectMessage(text)
    }

    fun leaveDirectChat() {
        val currentDmId = activeDirectSession.value?.dmId
        if (currentDmId != null && callManager.activeRoomId.value == currentDmId) {
            callManager.leaveSession()
        }
        clientRepository.leaveDirectChat()
        BitVoidDirectChatManager.refreshConversationsForUser(_myUsername.value)
    }

    fun clearDirectChatError() {
        clientRepository.clearDirectChatError()
    }

    private suspend fun ensureLocalGatewayReadyIfNeeded() {
        if (!hostState.value.isServerRunning &&
            clientState.value.connectionStatus != ClientConnectionStatus.CONNECTED
        ) {
            BitVoidForegroundService.startServerOnly(appContext)
            var attempts = 0
            while ((!hostState.value.isServerRunning || hostState.value.gatewayIp.isBlank()) && attempts < 40) {
                delay(80)
                attempts++
            }
        }
    }

    fun sendChannelChatMessage(text: String) {
        clientRepository.sendChannelChatMessage(text)
    }

    fun uploadFileToActiveChannel(uri: Uri, caption: String = "") {
        viewModelScope.launch {
            ensureLocalGatewayReadyIfNeeded()
            syncGatewayToLocalIfAvailable()
            clientRepository.uploadFileToChannel(appContext, uri, caption)
        }
    }

    fun uploadFileToActiveDirectChat(uri: Uri, caption: String = "") {
        viewModelScope.launch {
            ensureLocalGatewayReadyIfNeeded()
            syncGatewayToLocalIfAvailable()
            clientRepository.uploadFileToDirectChat(appContext, uri, caption)
        }
    }

    fun clearFileUploadError() {
        clientRepository.clearFileUploadError()
    }

    fun leaveChannel() {
        if (callManager.activeRoomId.value != null) {
            callManager.leaveSession()
        }
        clientRepository.leaveChannel()
        if (hostState.value.isServerRunning ||
            clientState.value.connectionStatus == ClientConnectionStatus.CONNECTED
        ) {
            refreshLiveClasses()
        }
    }

    fun startVoiceCall(channelId: String, channelName: String, token: String?) {
        viewModelScope.launch {
            ensureLocalGatewayReadyIfNeeded()
            syncGatewayToLocalIfAvailable()
            callManager.startOrJoinSession(
                gatewayBaseUrl = clientRepository.gatewayBaseUrl.value,
                channelId = channelId,
                channelName = channelName,
                displayName = activeChannelSession.value?.callsign ?: _myUsername.value,
                channelToken = token,
                mode = SignalingSessionMode.AUDIO_CALL,
                role = ParticipantRole.SPEAKER
            )
        }
    }

    fun startLiveClassAsHost(channelId: String, channelName: String, token: String?) {
        viewModelScope.launch {
            ensureLocalGatewayReadyIfNeeded()
            syncGatewayToLocalIfAvailable()
            clientRepository.suspendChannelChatForLiveClass()
            callManager.startOrJoinSession(
                gatewayBaseUrl = clientRepository.gatewayBaseUrl.value,
                channelId = channelId,
                channelName = channelName,
                displayName = activeChannelSession.value?.callsign ?: _myUsername.value,
                channelToken = token,
                mode = SignalingSessionMode.LIVE_CLASSROOM,
                role = ParticipantRole.HOST_TEACHER
            )
        }
    }

    fun joinLiveClassAsStudent(channelId: String, channelName: String, token: String?) {
        viewModelScope.launch {
            ensureLocalGatewayReadyIfNeeded()
            syncGatewayToLocalIfAvailable()
            clientRepository.suspendChannelChatForLiveClass()
            callManager.startOrJoinSession(
                gatewayBaseUrl = clientRepository.gatewayBaseUrl.value,
                channelId = channelId,
                channelName = channelName,
                displayName = activeChannelSession.value?.callsign ?: _myUsername.value,
                channelToken = token,
                mode = SignalingSessionMode.LIVE_CLASSROOM,
                role = ParticipantRole.STUDENT_VIEWER
            )
        }
    }

    private fun isGatewayOnLocalWifiNode(): Boolean {
        val raw = clientRepository.gatewayBaseUrl.value
        val host = Uri.parse(raw).host.orEmpty()
        return host.isNotBlank() && BitVoidClientManager.isPrivateLocalHost(host)
    }

    fun startNamedLiveClass(name: String, isHidden: Boolean, onDone: () -> Unit) {
        viewModelScope.launch {
            _extraCallError.value = null
            try {
                ensureLocalGatewayReadyIfNeeded()
                syncGatewayToLocalIfAvailable()
                if (!isGatewayOnLocalWifiNode()) {
                    _extraCallError.value = "Connect to a local BitVoid Wi-Fi node before starting a Live Class."
                    return@launch
                }
                val res = clientRepository.startLiveClassSession(
                    name = name,
                    isHidden = isHidden,
                    creatorUsername = _myUsername.value
                )
                res.onSuccess { info ->
                    callManager.startOrJoinSession(
                        gatewayBaseUrl = clientRepository.gatewayBaseUrl.value,
                        channelId = info.roomId,
                        channelName = info.channelName,
                        displayName = info.callsign,
                        channelToken = info.token,
                        mode = SignalingSessionMode.LIVE_CLASSROOM,
                        role = ParticipantRole.HOST_TEACHER,
                        presetClassCode = info.classCode
                    )
                    clientRepository.fetchLiveClasses(_myUsername.value)
                }.onFailure { err ->
                    _extraCallError.value = err.message ?: "Unable to start Live Class."
                }
            } finally {
                onDone()
            }
        }
    }

    fun openListedLiveClass(liveClass: RemoteLiveClassSummary) {
        viewModelScope.launch {
            _extraCallError.value = null
            ensureLocalGatewayReadyIfNeeded()
            syncGatewayToLocalIfAvailable()
            if (!isGatewayOnLocalWifiNode()) {
                _extraCallError.value = "Join must be on the same BitVoid Wi-Fi node."
                return@launch
            }
            val isCreator = liveClass.creatorUsername.equals(_myUsername.value, ignoreCase = true)
            val res = if (isCreator && liveClass.classCode.isNotBlank()) {
                clientRepository.joinLiveClassByCode(
                    rawClassCode = liveClass.classCode,
                    username = _myUsername.value
                )
            } else {
                clientRepository.joinLiveClassByRoomId(
                    roomId = liveClass.roomId,
                    username = _myUsername.value
                )
            }
            res.onSuccess { info ->
                callManager.startOrJoinSession(
                    gatewayBaseUrl = clientRepository.gatewayBaseUrl.value,
                    channelId = info.roomId,
                    channelName = info.channelName,
                    displayName = info.callsign,
                    channelToken = info.token,
                    mode = SignalingSessionMode.LIVE_CLASSROOM,
                    role = if (isCreator) ParticipantRole.HOST_TEACHER else ParticipantRole.STUDENT_VIEWER,
                    presetClassCode = info.classCode
                )
            }.onFailure { err ->
                _extraCallError.value = err.message ?: "Unable to enter Live Class."
            }
        }
    }

    fun joinLiveClassByCode(rawCode: String, onDone: () -> Unit) {
        viewModelScope.launch {
            _extraCallError.value = null
            try {
                ensureLocalGatewayReadyIfNeeded()
                syncGatewayToLocalIfAvailable()
                if (!isGatewayOnLocalWifiNode()) {
                    _extraCallError.value = "Access allowed only on the same BitVoid Wi-Fi node."
                    return@launch
                }
                val res = clientRepository.joinLiveClassByCode(
                    rawClassCode = rawCode,
                    username = _myUsername.value
                )
                res.onSuccess { info ->
                    val isCreator = info.creatorUsername.equals(_myUsername.value, ignoreCase = true)
                    callManager.startOrJoinSession(
                        gatewayBaseUrl = clientRepository.gatewayBaseUrl.value,
                        channelId = info.roomId,
                        channelName = info.channelName,
                        displayName = info.callsign,
                        channelToken = info.token,
                        mode = SignalingSessionMode.LIVE_CLASSROOM,
                        role = if (isCreator) ParticipantRole.HOST_TEACHER else ParticipantRole.STUDENT_VIEWER,
                        presetClassCode = info.classCode
                    )
                }.onFailure { err ->
                    _extraCallError.value = err.message ?: "Live Class ID not found or ended."
                }
            } finally {
                onDone()
            }
        }
    }

    fun startDirectCall(dmId: String, peerUsername: String, token: String) {
        viewModelScope.launch {
            _extraCallError.value = null
            ensureLocalGatewayReadyIfNeeded()
            syncGatewayToLocalIfAvailable()
            // The repository retains the legacy wire field for mixed-version compatibility,
            // but every new direct call explicitly requests audio only.
            clientRepository.requestDirectCall(dmId, token)
                .onSuccess {
                    pendingOutgoingDirectCall = dmId to peerUsername
                    val session = activeDirectSession.value
                    if (session != null && session.dmId == dmId &&
                        callManager.activeRoomId.value != dmId
                    ) {
                        connectDirectCall(session)
                    }
                }
                .onFailure { error ->
                    _extraCallError.value = error.message ?: "Unable to ring @$peerUsername."
                }
        }
    }

    fun acceptDirectCall(dmId: String, peerUsername: String, token: String) {
        viewModelScope.launch {
            _extraCallError.value = null
            clientRepository.acceptDirectCall(dmId, token)
                .onSuccess {
                    val session = activeDirectSession.value
                    if (session != null && session.dmId == dmId) {
                        connectDirectCall(session)
                    }
                }
                .onFailure { error ->
                    _extraCallError.value = error.message ?: "Unable to accept the call."
                }
        }
    }

    fun declineDirectCall(dmId: String, token: String) {
        viewModelScope.launch {
            clientRepository.declineDirectCall(dmId, token)
                .onFailure { error ->
                    _extraCallError.value = error.message ?: "Unable to decline the call."
                }
        }
    }

    fun cancelDirectCall(dmId: String, token: String) {
        pendingOutgoingDirectCall = null
        if (callManager.activeRoomId.value == dmId) {
            callManager.leaveSession()
        }
        viewModelScope.launch {
            clientRepository.cancelOrEndDirectCall(dmId, token, action = "cancel")
        }
    }

    private fun connectDirectCall(session: ClientDirectSession) {
        callManager.startOrJoinSession(
            gatewayBaseUrl = clientRepository.gatewayBaseUrl.value,
            channelId = session.dmId,
            channelName = "@${session.peerUsername}",
            displayName = _myUsername.value,
            channelToken = session.token,
            mode = SignalingSessionMode.AUDIO_CALL,
            role = ParticipantRole.SPEAKER
        )
    }

    fun toggleCallMicMute() {
        callManager.toggleMicMute()
    }

    fun toggleCallSpeakerphone() {
        callManager.toggleSpeakerphone()
    }

    fun leaveCallSession() {
        val directSession = activeDirectSession.value
            ?.takeIf { callManager.activeRoomId.value == it.dmId }
        callManager.leaveSession()
        if (directSession != null) {
            viewModelScope.launch {
                clientRepository.cancelOrEndDirectCall(
                    directSession.dmId,
                    directSession.token,
                    action = "end"
                )
            }
        }
        if (hostState.value.isServerRunning ||
            clientState.value.connectionStatus == ClientConnectionStatus.CONNECTED
        ) {
            viewModelScope.launch {
                delay(120)
                clientRepository.fetchLiveClasses(_myUsername.value)
            }
        }
    }

    fun endLiveClassSession() {
        val wasLiveClassRoom = activeChannelSession.value?.isLiveClassRoom == true
        callManager.endClassroomSession()
        if (wasLiveClassRoom) {
            clientRepository.leaveChannel()
        }
        viewModelScope.launch {
            delay(150)
            clientRepository.fetchLiveClasses(_myUsername.value)
        }
    }

    fun clearCallError() {
        _extraCallError.value = null
        callManager.clearCallError()
    }

    fun buildClientStreamUrl(item: RemoteMediaItem, token: String? = null): String {
        return clientRepository.buildAbsoluteStreamUrl(item, token)
    }

    fun buildAttachmentUrl(pathOrUrl: String, token: String? = null): String {
        return clientRepository.buildAttachmentUrl(pathOrUrl, token)
    }

    fun openSystemWifiSettings() {
        clientManager.openSystemWifiSettings()
    }

    fun refreshHardwareNodeConnection() {
        startHardwareIntegrationIfNeeded()?.refresh()
    }

    fun openSandboxedBrowser(urlOverride: String? = null) {
        val target = urlOverride?.let { BitVoidClientManager.normalizeLocalPortalUrl(it) }
            ?: clientState.value.targetPortalUrl
        clientRepository.setGatewayBaseUrl(target)
        BitVoidNodeStateHolder.updateClientState {
            it.copy(
                targetPortalUrl = target,
                isBrowserOpen = true,
                errorMessage = null
            )
        }
    }

    fun closeSandboxedBrowser() {
        BitVoidNodeStateHolder.updateClientState {
            it.copy(isBrowserOpen = false)
        }
    }

    fun recordSandboxRequest(allowed: Boolean) {
        BitVoidNodeStateHolder.updateClientState { state ->
            val current = state.privacyTelemetry
            state.copy(
                privacyTelemetry = if (allowed) {
                    current.copy(strippedRequestsCount = current.strippedRequestsCount + 1)
                } else {
                    current.copy(blockedExternalRequestsCount = current.blockedExternalRequestsCount + 1)
                }
            )
        }
    }

    override fun onCleared() {
        callManager.release()
        // The process-wide repository is owned by the foreground service while a conversation is
        // joined, so dismissing the Activity must not close its WebSockets or release the Wi-Fi
        // network binding they rely on.
        val keepMessageConnection =
            activeDirectSession.value != null ||
                activeChannelSession.value?.isLiveClassRoom == false
        clientManager.release(preserveNetworkBinding = keepMessageConnection)
        hardwareStateJob?.cancel()
        if (hardwareIntegrationDelegate.isInitialized() && !keepMessageConnection) {
            runCatching { hardwareIntegrationDelegate.value.close() }
        }
        super.onCleared()
    }
}
