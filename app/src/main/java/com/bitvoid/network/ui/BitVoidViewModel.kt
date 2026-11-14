package com.bitvoid.network.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitvoid.network.channel.BitVoidChannel
import com.bitvoid.network.channel.BitVoidChannelManager
import com.bitvoid.network.client.BitVoidClientManager
import com.bitvoid.network.client.BitVoidClientRepository
import com.bitvoid.network.client.ClientChannelSession
import com.bitvoid.network.client.ClientChatMessage
import com.bitvoid.network.client.ClientDirectSession
import com.bitvoid.network.client.ClientWsStatus
import com.bitvoid.network.client.RemoteChannelSummary
import com.bitvoid.network.client.RemoteMediaItem
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
import com.bitvoid.network.model.HotspotSecurityMode
import com.bitvoid.network.model.HotspotStatus
import com.bitvoid.network.model.NodeMode
import com.bitvoid.network.service.BitVoidForegroundService
import com.bitvoid.network.service.BitVoidNodeStateHolder
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BitVoidViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val clientManager = BitVoidClientManager(appContext)
    private val clientRepository = BitVoidClientRepository()

    private val _selectedMode = MutableStateFlow(NodeMode.HOST)
    val selectedMode: StateFlow<NodeMode> = _selectedMode.asStateFlow()

    private val _selectedBottomTab = MutableStateFlow(AppBottomTab.HOME)
    val selectedBottomTab: StateFlow<AppBottomTab> = _selectedBottomTab.asStateFlow()

    private val _isImportingFiles = MutableStateFlow(false)
    val isImportingFiles: StateFlow<Boolean> = _isImportingFiles.asStateFlow()

    private val _myUsername = MutableStateFlow(BitVoidUserPrefs.loadOrCreate(appContext))
    val myUsername: StateFlow<String> = _myUsername.asStateFlow()

    val hostState: StateFlow<HostState> = BitVoidNodeStateHolder.hostState
    val auditLogs: StateFlow<List<AuditLogEntry>> = BitVoidNodeStateHolder.auditLogs
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

    private var customClientUrlPinned = false

    init {
        BitVoidChannelManager.ensureDefaultChannel()
        BitVoidDirectChatManager.registerUser(_myUsername.value)
        BitVoidDirectChatManager.refreshConversationsForUser(_myUsername.value)
        loadSavedNetworkPreferences()
        viewModelScope.launch {
            BitVoidMediaManager.mediaItems.collect { items ->
                BitVoidChannelManager.syncMediaCatalog(items)
                BitVoidNodeStateHolder.updateHostState { it.copy(sharedFiles = items) }
            }
        }
        viewModelScope.launch {
            var lastStatus: ClientConnectionStatus? = null
            clientState.collect { cState ->
                if (cState.connectionStatus == ClientConnectionStatus.CONNECTED) {
                    clientRepository.setGatewayBaseUrl(cState.targetPortalUrl)
                    if (lastStatus != ClientConnectionStatus.CONNECTED) {
                        refreshClientChannels(cState.targetPortalUrl)
                        refreshClientPublicMedia(cState.targetPortalUrl)
                    }
                }
                lastStatus = cState.connectionStatus
            }
        }
        viewModelScope.launch {
            hostState.collect { hState ->
                if (!customClientUrlPinned &&
                    clientState.value.connectionStatus != ClientConnectionStatus.CONNECTED &&
                    hState.isServerRunning &&
                    hState.gatewayIp.isNotBlank()
                ) {
                    val localUrl = hState.effectivePortalUrl
                    if (clientGatewayUrl.value != localUrl) {
                        clientRepository.setGatewayBaseUrl(localUrl)
                        if (_selectedBottomTab.value == AppBottomTab.HUB || _selectedMode.value == NodeMode.CLIENT) {
                            refreshClientChannels(localUrl)
                            refreshClientPublicMedia(localUrl)
                        }
                    }
                }
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
                    selectedSecurityMode = saved.securityMode,
                    isHiddenSsid = saved.isHiddenSsid,
                    isOpenSecurity = saved.securityMode == HotspotSecurityMode.OPEN_VISIBLE,
                    securityLabel = saved.securityMode.badgeLabel,
                    configuredSsid = saved.ssid,
                    configuredPassphrase = saved.passphrase
                )
            }
        }
    }

    fun selectBottomTab(tab: AppBottomTab) {
        _selectedBottomTab.value = tab
        if (tab == AppBottomTab.HUB) {
            syncGatewayToLocalIfAvailable()
            refreshClientChannels()
            refreshClientPublicMedia()
            BitVoidDirectChatManager.refreshConversationsForUser(_myUsername.value)
            if (hostState.value.isServerRunning ||
                clientState.value.connectionStatus == ClientConnectionStatus.CONNECTED
            ) {
                viewModelScope.launch {
                    clientRepository.registerUsername(_myUsername.value)
                }
            }
        }
    }

    fun selectMode(mode: NodeMode) {
        _selectedMode.value = mode
        if (mode == NodeMode.CLIENT) {
            syncGatewayToLocalIfAvailable()
            refreshClientChannels()
            refreshClientPublicMedia()
        }
    }

    private fun syncGatewayToLocalIfAvailable() {
        val hState = hostState.value
        if (!customClientUrlPinned &&
            clientState.value.connectionStatus != ClientConnectionStatus.CONNECTED &&
            hState.isServerRunning &&
            hState.gatewayIp.isNotBlank()
        ) {
            clientRepository.setGatewayBaseUrl(hState.effectivePortalUrl)
        }
    }

    fun selectHotspotSecurityMode(mode: HotspotSecurityMode) {
        BitVoidNetworkPrefs.saveSecurityMode(appContext, mode)
        BitVoidNodeStateHolder.updateHostState { current ->
            if (current.isEngineLocked) {
                current
            } else {
                val config = BitVoidNetworkConfig(
                    securityMode = mode,
                    ssid = current.configuredSsid,
                    passphrase = current.configuredPassphrase,
                    isHiddenSsid = BitVoidNetworkConfig.ENFORCED_VISIBLE_SSID
                )
                current.copy(
                    selectedSecurityMode = config.securityMode,
                    isHiddenSsid = config.isHiddenSsid,
                    isOpenSecurity = config.securityMode == HotspotSecurityMode.OPEN_VISIBLE,
                    securityLabel = config.securityMode.badgeLabel,
                    needsOpenHotspotSetupBanner = false,
                    errorMessage = null
                )
            }
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
        if (cleanPass.length < 8 || cleanPass.toByteArray(StandardCharsets.UTF_8).size > 63) {
            return Result.failure(IllegalArgumentException("Wi-Fi password must be between 8 and 63 characters."))
        }

        BitVoidNetworkPrefs.saveBroadcastCredentials(appContext, cleanSsid, cleanPass)
        BitVoidNodeStateHolder.updateHostState { current ->
            val updateLiveAutonomousFields = current.status == HotspotStatus.SERVER_ONLY ||
                (current.isServerRunning && current.status != HotspotStatus.ACTIVE)
            current.copy(
                configuredSsid = cleanSsid,
                configuredPassphrase = cleanPass,
                ssid = if (updateLiveAutonomousFields) cleanSsid else current.ssid,
                passphrase = if (updateLiveAutonomousFields && !current.isOpenSecurity) cleanPass else current.passphrase
            )
        }
        return Result.success(Unit)
    }

    fun toggleHostGateway() {
        val currentState = hostState.value
        if (currentState.isEngineLocked) {
            BitVoidForegroundService.stopNode(appContext)
        } else {
            BitVoidForegroundService.startHostNode(
                context = appContext,
                securityMode = currentState.selectedSecurityMode
            )
        }
    }

    fun startOpenBroadcastMode() {
        selectHotspotSecurityMode(HotspotSecurityMode.OPEN_VISIBLE)
        BitVoidForegroundService.startOpenBroadcast(appContext)
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
            isHidden = isHidden
        )
        if (result.isSuccess && hostState.value.isServerRunning) {
            refreshClientChannels()
        }
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

    fun deleteChannel(channelId: String) {
        BitVoidChannelManager.deleteChannel(channelId)
        if (hostState.value.isServerRunning) {
            refreshClientChannels()
        }
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

    fun scanNearbyNodes() {
        clientManager.scanNearbyNetworks()
    }

    fun connectToNode(ssid: String, passphrase: String, portalHostPort: String) {
        clientRepository.setGatewayBaseUrl(portalHostPort)
        clientManager.connectToNode(ssid, passphrase, portalHostPort)
    }

    fun disconnectClient() {
        clientRepository.leaveChannel()
        clientManager.disconnectFromNode()
    }

    fun setClientGatewayUrl(rawUrl: String): Boolean {
        val normalized = clientRepository.setGatewayBaseUrl(rawUrl) ?: return false
        customClientUrlPinned = normalized != hostState.value.effectivePortalUrl &&
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
            clientRepository.fetchChannels(urlOverride)
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
            BitVoidDirectChatManager.registerUser(clean)
            BitVoidDirectChatManager.refreshConversationsForUser(clean)
            if (hostState.value.isServerRunning ||
                clientState.value.connectionStatus == ClientConnectionStatus.CONNECTED
            ) {
                viewModelScope.launch {
                    clientRepository.registerUsername(clean)
                }
            }
        }
        return res
    }

    fun openDirectChat(targetUsername: String) {
        viewModelScope.launch {
            ensureLocalGatewayReadyIfNeeded()
            syncGatewayToLocalIfAvailable()
            clientRepository.registerUsername(_myUsername.value)
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

    fun leaveChannel() {
        clientRepository.leaveChannel()
    }

    fun buildClientStreamUrl(item: RemoteMediaItem, token: String? = null): String {
        return clientRepository.buildAbsoluteStreamUrl(item, token)
    }

    fun openSystemWifiSettings() {
        clientManager.openSystemWifiSettings()
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
        clientRepository.release()
        clientManager.release()
        super.onCleared()
    }
}
