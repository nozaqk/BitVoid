package com.bitvoid.network.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitvoid.network.channel.BitVoidChannel
import com.bitvoid.network.channel.ChannelParticipant
import com.bitvoid.network.client.BitVoidClientManager
import com.bitvoid.network.media.BitVoidMediaItem
import com.bitvoid.network.model.AppBottomTab
import com.bitvoid.network.model.AuditLogEntry
import com.bitvoid.network.model.BitVoidNetworkConfig
import com.bitvoid.network.model.ClientConnectionStatus
import com.bitvoid.network.model.ClientState
import com.bitvoid.network.model.DiscoveredNetwork
import com.bitvoid.network.model.HostState
import com.bitvoid.network.model.HotspotSecurityMode
import com.bitvoid.network.model.HotspotStatus
import com.bitvoid.network.model.NodeMode
import com.bitvoid.network.ui.theme.VoidAmber
import com.bitvoid.network.ui.theme.VoidBorder
import com.bitvoid.network.ui.theme.VoidCrimson
import com.bitvoid.network.ui.theme.VoidCyan
import com.bitvoid.network.ui.theme.VoidMint
import com.bitvoid.network.ui.theme.VoidMintContainer
import com.bitvoid.network.ui.theme.VoidObsidian
import com.bitvoid.network.ui.theme.VoidSurface
import com.bitvoid.network.ui.theme.VoidSurfaceElevated
import com.bitvoid.network.ui.theme.VoidTextPrimary
import com.bitvoid.network.ui.theme.VoidTextSecondary
import com.bitvoid.network.R
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun BitVoidDashboard(
    viewModel: BitVoidViewModel,
    missingPermissions: List<String>,
    onRequestPermissions: () -> Unit,
    onPickDocuments: () -> Unit,
    onPickMedia: () -> Unit
) {
    val mode by viewModel.selectedMode.collectAsStateWithLifecycle()
    val selectedBottomTab by viewModel.selectedBottomTab.collectAsStateWithLifecycle()
    val hostState by viewModel.hostState.collectAsStateWithLifecycle()
    val auditLogs by viewModel.auditLogs.collectAsStateWithLifecycle()
    val clientState by viewModel.clientState.collectAsStateWithLifecycle()
    val isImporting by viewModel.isImportingFiles.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()

    val clientChannels by viewModel.clientChannels.collectAsStateWithLifecycle()
    val activeChannelSession by viewModel.activeChannelSession.collectAsStateWithLifecycle()
    val channelMessages by viewModel.channelMessages.collectAsStateWithLifecycle()
    val clientWsStatus by viewModel.clientWsStatus.collectAsStateWithLifecycle()
    val channelPeersCount by viewModel.channelPeersCount.collectAsStateWithLifecycle()
    val isJoiningChannel by viewModel.isJoiningChannel.collectAsStateWithLifecycle()
    val channelJoinError by viewModel.channelJoinError.collectAsStateWithLifecycle()

    val myUsername by viewModel.myUsername.collectAsStateWithLifecycle()
    val directConversations by viewModel.directConversations.collectAsStateWithLifecycle()
    val knownUsers by viewModel.knownUsers.collectAsStateWithLifecycle()
    val activeDirectSession by viewModel.activeDirectSession.collectAsStateWithLifecycle()
    val directMessages by viewModel.directMessages.collectAsStateWithLifecycle()
    val directWsStatus by viewModel.directWsStatus.collectAsStateWithLifecycle()
    val isOpeningDirectChat by viewModel.isOpeningDirectChat.collectAsStateWithLifecycle()
    val directChatError by viewModel.directChatError.collectAsStateWithLifecycle()
    val isUploadingChatFile by viewModel.isUploadingChatFile.collectAsStateWithLifecycle()
    val fileUploadError by viewModel.fileUploadError.collectAsStateWithLifecycle()
    val callUiState by viewModel.callUiState.collectAsStateWithLifecycle()
    val liveClasses by viewModel.liveClasses.collectAsStateWithLifecycle()

    val callActions = remember(viewModel) {
        BitVoidCallActions(
            onStartVoiceVideoCall = { channelId, channelName, token ->
                viewModel.startVoiceVideoCall(channelId, channelName, token)
            },
            onStartLiveClassHost = { channelId, channelName, token ->
                viewModel.startLiveClassAsHost(channelId, channelName, token)
            },
            onJoinLiveClassStudent = { channelId, channelName, token ->
                viewModel.joinLiveClassAsStudent(channelId, channelName, token)
            },
            onJoinLiveClassWithCode = { classCode, onDone ->
                viewModel.joinLiveClassByCode(classCode, onDone)
            },
            onStartDirectCall = { dmId, peerUsername, token, withVideo ->
                viewModel.startDirectCall(dmId, peerUsername, token, withVideo)
            },
            onAcceptDirectCall = { dmId, peerUsername, token, withVideo ->
                viewModel.acceptDirectCall(dmId, peerUsername, token, withVideo)
            },
            onDeclineDirectCall = { dmId, token ->
                viewModel.declineDirectCall(dmId, token)
            },
            onCancelDirectCall = { dmId, token ->
                viewModel.cancelDirectCall(dmId, token)
            },
            onUploadClassroomDocument = { roomId, uri, isDirect ->
                viewModel.uploadClassroomDocument(roomId, uri, isDirect)
            },
            onSelectClassroomMaterial = { materialId ->
                viewModel.selectClassroomMaterial(materialId)
            },
            onSetClassroomSlidePage = { pageIndex ->
                viewModel.setClassroomSlidePage(pageIndex)
            },
            onOpenMaterialExternally = { material ->
                viewModel.openMaterialExternally(material)
            },
            onToggleMicMute = { viewModel.toggleCallMicMute() },
            onToggleCamera = { viewModel.toggleCallCamera() },
            onSwitchCamera = { viewModel.switchCallCamera() },
            onToggleSpeakerphone = { viewModel.toggleCallSpeakerphone() },
            onStartScreenShare = { resultCode, data ->
                viewModel.startScreenShare(resultCode, data)
            },
            onStopScreenShare = { viewModel.stopScreenShare() },
            onLeaveSession = { viewModel.leaveCallSession() },
            onEndClassroomSession = { viewModel.endLiveClassSession() },
            onClearCallError = { viewModel.clearCallError() }
        )
    }

    var showChannelDialog by rememberSaveable { mutableStateOf(false) }
    var editingChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    var showProtocolDialog by rememberSaveable { mutableStateOf(false) }
    var showQrDialog by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(fileUploadError) {
        fileUploadError?.let { err ->
            snackbarHostState.showSnackbar(err)
            viewModel.clearFileUploadError()
        }
    }

    fun copyToClipboard(label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        coroutineScope.launch {
            snackbarHostState.showSnackbar("$label copied to clipboard")
        }
    }

    val clientHubContent: @Composable () -> Unit = {
        BitVoidClientHubContent(
            clientState = clientState,
            onScanNetworks = { viewModel.scanNearbyNodes() },
            onConnectNode = { ssid -> viewModel.connectToNode(ssid) },
            onOpenWifiSettings = { viewModel.openSystemWifiSettings() }
        )
    }

    val hubScreenContent: @Composable () -> Unit = {
        BitVoidHubScreen(
            clientState = clientState,
            myUsername = myUsername,
            hostChannels = channels,
            remoteChannels = clientChannels,
            directConversations = directConversations,
            knownUsers = knownUsers,
            activeChannelSession = activeChannelSession,
            channelMessages = channelMessages,
            wsStatus = clientWsStatus,
            channelPeersCount = channelPeersCount,
            isJoiningChannel = isJoiningChannel,
            channelJoinError = channelJoinError,
            activeDirectSession = activeDirectSession,
            directMessages = directMessages,
            directWsStatus = directWsStatus,
            isOpeningDirectChat = isOpeningDirectChat,
            directChatError = directChatError,
            onOpenCreateChannel = {
                editingChannelId = null
                showChannelDialog = true
            },
            onOpenChannel = { channelId, privateKey ->
                viewModel.openHubChannel(channelId, privateKey)
            },
            onSendChatMessage = { viewModel.sendChannelChatMessage(it) },
            onLeaveChannel = { viewModel.leaveChannel() },
            onOpenDirectChat = { target -> viewModel.openDirectChat(target) },
            onSendDirectMessage = { text -> viewModel.sendDirectChatMessage(text) },
            onLeaveDirectChat = { viewModel.leaveDirectChat() },
            onClearDirectChatError = { viewModel.clearDirectChatError() },
            onCopyUsername = ::copyToClipboard,
            onBuildStreamUrl = { item, token -> viewModel.buildClientStreamUrl(item, token) },
            isUploadingFile = isUploadingChatFile,
            onUploadChannelFile = { uri, caption -> viewModel.uploadFileToActiveChannel(uri, caption) },
            onUploadDirectFile = { uri, caption -> viewModel.uploadFileToActiveDirectChat(uri, caption) },
            onBuildAttachmentUrl = { path, token -> viewModel.buildAttachmentUrl(path, token) },
            callUiState = callUiState,
            callActions = callActions,
            liveClasses = liveClasses,
            onStartLiveClass = { name, isHidden, onDone ->
                viewModel.startNamedLiveClass(name, isHidden, onDone)
            },
            onOpenListedLiveClass = { liveClass ->
                viewModel.openListedLiveClass(liveClass)
            }
        )
    }

    if (clientState.isBrowserOpen) {
        BackHandler { viewModel.closeSandboxedBrowser() }
        SandboxedPortalBrowserScreen(
            clientState = clientState,
            onClose = { viewModel.closeSandboxedBrowser() },
            onRequestEvaluated = { allowed -> viewModel.recordSandboxRequest(allowed) }
        )
        return
    }

    val isInsideActiveHubChat = selectedBottomTab == AppBottomTab.HUB &&
        (activeChannelSession != null ||
            activeDirectSession != null ||
            (callUiState.activeRoomId != null &&
                !callUiState.activeRoomId!!.startsWith("dm__") &&
                callUiState.callState != com.bitvoid.network.call.BitVoidCallState.IDLE))

    BackHandler(
        enabled = selectedBottomTab != AppBottomTab.HOME && !isInsideActiveHubChat
    ) {
        viewModel.selectBottomTab(AppBottomTab.HOME)
    }

    Scaffold(
        containerColor = VoidObsidian,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        bottomBar = {
            if (!isInsideActiveHubChat) {
                BitVoidBottomNavigationBar(
                    selectedTab = selectedBottomTab,
                    onSelectTab = { viewModel.selectBottomTab(it) }
                )
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(VoidObsidian)
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            val isWideLayout = maxWidth >= 720.dp

            if (isWideLayout) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 1120.dp)
                        .align(Alignment.TopCenter)
                ) {
                    if (selectedBottomTab == AppBottomTab.HOME) {
                        BitVoidHeaderBar(
                            mode = mode,
                            hostState = hostState,
                            clientStatus = clientState.connectionStatus,
                            onSelectMode = { viewModel.selectMode(it) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }

                    WideDashboardContent(
                        selectedBottomTab = selectedBottomTab,
                        mode = mode,
                        hostState = hostState,
                        myUsername = myUsername,
                        channels = channels,
                        auditLogs = auditLogs,
                        isImporting = isImporting,
                        missingPermissionsCount = missingPermissions.size,
                        onSaveBroadcastCredentials = { ssid, pass ->
                            viewModel.updateBroadcastSettings(ssid, pass)
                        },
                        onBroadcastSaved = { msg ->
                            coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                        },
                        onRequestPermissions = onRequestPermissions,
                        onToggleHost = { viewModel.toggleHostGateway() },
                        onOpenProtocolDialog = { showProtocolDialog = true },
                        onOpenQrDialog = { showQrDialog = true },
                        onOpenWifiSettings = { viewModel.openSystemWifiSettings() },
                        onOpenHotspotSettings = { viewModel.openHotspotTetherSettings() },
                        onPickDocuments = onPickDocuments,
                        onPickMedia = onPickMedia,
                        onToggleVideoPublic = { id, isPublic ->
                            viewModel.setVideoPublic(id, isPublic)
                        },
                        onToggleMediaChannel = { channelId, mediaId ->
                            viewModel.toggleMediaInChannel(channelId, mediaId)
                        },
                        onDeleteFile = { viewModel.deleteFile(it) },
                        onClearLogs = { viewModel.clearAuditLogs() },
                        onCopy = ::copyToClipboard,
                        clientHubContent = clientHubContent,
                        hubScreenContent = hubScreenContent
                    )
                }
            } else {
                CompactDashboardContent(
                    selectedBottomTab = selectedBottomTab,
                    mode = mode,
                    hostState = hostState,
                    myUsername = myUsername,
                    channels = channels,
                    auditLogs = auditLogs,
                    clientState = clientState,
                    isImporting = isImporting,
                    missingPermissionsCount = missingPermissions.size,
                    onSaveBroadcastCredentials = { ssid, pass ->
                        viewModel.updateBroadcastSettings(ssid, pass)
                    },
                    onBroadcastSaved = { msg ->
                        coroutineScope.launch { snackbarHostState.showSnackbar(msg) }
                    },
                    onRequestPermissions = onRequestPermissions,
                    onSelectMode = { viewModel.selectMode(it) },
                    onToggleHost = { viewModel.toggleHostGateway() },
                    onOpenProtocolDialog = { showProtocolDialog = true },
                    onOpenQrDialog = { showQrDialog = true },
                    onOpenWifiSettings = { viewModel.openSystemWifiSettings() },
                    onOpenHotspotSettings = { viewModel.openHotspotTetherSettings() },
                    onPickDocuments = onPickDocuments,
                    onPickMedia = onPickMedia,
                    onToggleVideoPublic = { id, isPublic ->
                        viewModel.setVideoPublic(id, isPublic)
                    },
                    onToggleMediaChannel = { channelId, mediaId ->
                        viewModel.toggleMediaInChannel(channelId, mediaId)
                    },
                    onDeleteFile = { viewModel.deleteFile(it) },
                    onClearLogs = { viewModel.clearAuditLogs() },
                    onCopy = ::copyToClipboard,
                    clientHubContent = clientHubContent,
                    hubScreenContent = hubScreenContent,
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 1120.dp)
                        .align(Alignment.TopCenter)
                )
            }
        }
    }

    if (showProtocolDialog) {
        HostNetworkProtocolDialog(
            hostState = hostState,
            onSelectMode = { viewModel.selectHotspotSecurityMode(it) },
            onDismiss = { showProtocolDialog = false }
        )
    }

    if (showQrDialog) {
        HostQrCodeDialog(
            hostState = hostState,
            onCopy = ::copyToClipboard,
            onDismiss = { showQrDialog = false }
        )
    }

    if (showChannelDialog) {
        val editingChannel = editingChannelId?.let { target -> channels.find { it.channelId == target } }
        ChannelEditorDialog(
            existingChannel = editingChannel,
            suggestedPrivateId = remember(showChannelDialog, editingChannelId) {
                editingChannel?.channelId ?: viewModel.suggestPrivateChannelId()
            },
            vaultItems = hostState.sharedFiles,
            onSuggestPrivateId = { viewModel.suggestPrivateChannelId() },
            onDismiss = {
                showChannelDialog = false
                editingChannelId = null
            },
            onSave = { name, isPublic, isHidden, customChannelId, privateKey, selectedMediaIds ->
                if (editingChannel != null) {
                    viewModel.assignMediaToChannel(editingChannel.channelId, selectedMediaIds)
                    showChannelDialog = false
                    editingChannelId = null
                    coroutineScope.launch {
                        snackbarHostState.showSnackbar("Updated media for #${editingChannel.name}")
                    }
                    null
                } else {
                    val effectivePublic = isPublic && !isHidden
                    val result = viewModel.createChannel(
                        name = name,
                        isPublic = effectivePublic,
                        customChannelId = if (effectivePublic) null else customChannelId,
                        privateKey = if (effectivePublic) null else privateKey,
                        mediaIds = selectedMediaIds,
                        isHidden = isHidden
                    )
                    if (result.isSuccess) {
                        val created = result.getOrNull()
                        showChannelDialog = false
                        editingChannelId = null
                        if (created != null && (!created.isPublic || created.isHidden)) {
                            copyToClipboard(
                                if (created.isHidden) "Hidden Channel ID" else "Private Channel ID",
                                created.channelId
                            )
                        } else {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Public Channel #${created?.name} created")
                            }
                        }
                        null
                    } else {
                        result.exceptionOrNull()?.message ?: "Unable to create channel"
                    }
                }
            }
        )
    }
}

@Composable
private fun BitVoidHeaderBar(
    mode: NodeMode,
    hostState: HostState,
    clientStatus: ClientConnectionStatus,
    onSelectMode: (NodeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val hostStatus = hostState.status
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(VoidMintContainer)
                        .border(1.dp, VoidMint.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Router,
                        contentDescription = null,
                        tint = VoidMint,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "BITVOID // v1.4.2",
                        style = MaterialTheme.typography.titleLarge,
                        color = VoidTextPrimary
                    )
                    Text(
                        text = "OFFLINE MESH & ANONYMIZED GATEWAY",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidMint
                    )
                }
            }

            val isOnline = hostStatus == HotspotStatus.ACTIVE ||
                hostStatus == HotspotStatus.SERVER_ONLY ||
                hostState.isServerRunning ||
                clientStatus == ClientConnectionStatus.CONNECTED

            StatusChip(
                label = when {
                    hostStatus == HotspotStatus.ACTIVE -> "HOST LIVE (${hostState.publicCount} PUB)"
                    hostState.isOpenBroadcastMode && hostState.isServerRunning -> "OPEN BROADCAST (${hostState.publicCount} PUB)"
                    hostStatus == HotspotStatus.SERVER_ONLY || hostState.isServerRunning -> "VAULT LIVE (${hostState.publicCount} PUB)"
                    hostStatus == HotspotStatus.STARTING -> "BOOTING"
                    clientStatus == ClientConnectionStatus.CONNECTED -> "PEER LINKED"
                    else -> "AIR-GAPPED"
                },
                accentColor = if (isOnline) VoidMint else VoidTextSecondary
            )
        }

        // Tactical Mode Switcher
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(VoidSurface)
                .border(1.dp, VoidBorder, RoundedCornerShape(12.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ModeSwitcherTab(
                title = stringResource(R.string.node_mode_host),
                subtitle = "Gateway + HTTP Vault",
                selected = mode == NodeMode.HOST,
                icon = Icons.Filled.WifiTethering,
                testTag = "mode_tab_host",
                modifier = Modifier.weight(1f),
                onClick = { onSelectMode(NodeMode.HOST) }
            )
            ModeSwitcherTab(
                title = stringResource(R.string.node_mode_client),
                subtitle = "Peer + Sandbox",
                selected = mode == NodeMode.CLIENT,
                icon = Icons.Filled.WifiFind,
                testTag = "mode_tab_client",
                modifier = Modifier.weight(1f),
                onClick = { onSelectMode(NodeMode.CLIENT) }
            )
        }
    }
}

@Composable
private fun ModeSwitcherTab(
    title: String,
    subtitle: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bgColor = if (selected) VoidMintContainer else Color.Transparent
    val borderColor = if (selected) VoidMint.copy(alpha = 0.65f) else Color.Transparent
    val contentColor = if (selected) VoidMint else VoidTextSecondary

    Row(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = contentColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) VoidMint else VoidTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = VoidTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CompactDashboardContent(
    selectedBottomTab: AppBottomTab,
    mode: NodeMode,
    hostState: HostState,
    myUsername: String,
    channels: List<BitVoidChannel>,
    auditLogs: List<AuditLogEntry>,
    clientState: ClientState,
    isImporting: Boolean,
    missingPermissionsCount: Int,
    onSaveBroadcastCredentials: (String, String) -> Result<Unit>,
    onBroadcastSaved: (String) -> Unit,
    onRequestPermissions: () -> Unit,
    onSelectMode: (NodeMode) -> Unit,
    onToggleHost: () -> Unit,
    onOpenProtocolDialog: () -> Unit,
    onOpenQrDialog: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
    onPickDocuments: () -> Unit,
    onPickMedia: () -> Unit,
    onToggleVideoPublic: (String, Boolean) -> Unit,
    onToggleMediaChannel: (String, String) -> Unit,
    onDeleteFile: (String) -> Unit,
    onClearLogs: () -> Unit,
    onCopy: (String, String) -> Unit,
    clientHubContent: @Composable () -> Unit,
    hubScreenContent: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selectedBottomTab == AppBottomTab.HUB) {
        Box(modifier = modifier.fillMaxSize()) {
            hubScreenContent()
        }
        return
    }

    var showAuditLogs by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        when (selectedBottomTab) {
            AppBottomTab.HOME -> {
                item(key = "header_bar") {
                    BitVoidHeaderBar(
                        mode = mode,
                        hostState = hostState,
                        clientStatus = clientState.connectionStatus,
                        onSelectMode = onSelectMode,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                if (mode == NodeMode.HOST) {
                    item(key = "host_power_card") {
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            HostPowerControlCard(
                                hostState = hostState,
                                onToggleHost = onToggleHost,
                                onOpenProtocolDialog = onOpenProtocolDialog,
                                onOpenQrDialog = onOpenQrDialog,
                                onOpenHotspotSettings = onOpenHotspotSettings
                            )
                        }
                    }
                    item(key = "host_credentials_card") {
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            HostCredentialsCard(
                                hostState = hostState,
                                onCopy = onCopy
                            )
                        }
                    }
                    item(key = "host_telemetry_card") {
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            PrivacyTelemetryCard(hostState = hostState)
                        }
                    }
                } else {
                    item(key = "home_client_hub") {
                        clientHubContent()
                    }
                }
            }

            AppBottomTab.HUB -> Unit

            AppBottomTab.UPLOAD -> {
                item(key = "upload_vault_header") {
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        SharedVaultHeaderCard(
                            totalCount = hostState.sharedFiles.size,
                            publicCount = hostState.publicCount,
                            isImporting = isImporting,
                            onPickDocuments = onPickDocuments,
                            onPickMedia = onPickMedia
                        )
                    }
                }
                if (hostState.sharedFiles.isEmpty()) {
                    item(key = "upload_empty_vault") {
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            EmptyVaultCard()
                        }
                    }
                } else {
                    items(hostState.sharedFiles, key = { it.id }) { file ->
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            SharedFileRowCard(
                                item = file,
                                channels = channels,
                                onTogglePublic = { isPublic -> onToggleVideoPublic(file.id, isPublic) },
                                onToggleChannelAssignment = { channelId ->
                                    onToggleMediaChannel(channelId, file.id)
                                },
                                onDelete = { onDeleteFile(file.id) }
                            )
                        }
                    }
                }
            }

            AppBottomTab.SETTINGS -> {
                item(key = "settings_cards") {
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        SettingsAutonomousCard(
                            hostState = hostState,
                            myUsername = myUsername,
                            missingPermissionsCount = missingPermissionsCount,
                            onCopyUsername = onCopy,
                            onSaveBroadcastCredentials = onSaveBroadcastCredentials,
                            onBroadcastSaved = onBroadcastSaved,
                            onRequestPermissions = onRequestPermissions,
                            onOpenProtocolDialog = onOpenProtocolDialog,
                            onOpenQrDialog = onOpenQrDialog,
                            onOpenWifiSettings = onOpenWifiSettings,
                            onOpenHotspotSettings = onOpenHotspotSettings
                        )
                    }
                }
                item(key = "settings_audit_logs") {
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        AuditLogPanel(
                            logs = auditLogs,
                            expanded = showAuditLogs,
                            onToggleExpand = { showAuditLogs = !showAuditLogs },
                            onClearLogs = onClearLogs
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WideDashboardContent(
    selectedBottomTab: AppBottomTab,
    mode: NodeMode,
    hostState: HostState,
    myUsername: String,
    channels: List<BitVoidChannel>,
    auditLogs: List<AuditLogEntry>,
    isImporting: Boolean,
    missingPermissionsCount: Int,
    onSaveBroadcastCredentials: (String, String) -> Result<Unit>,
    onBroadcastSaved: (String) -> Unit,
    onRequestPermissions: () -> Unit,
    onToggleHost: () -> Unit,
    onOpenProtocolDialog: () -> Unit,
    onOpenQrDialog: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenHotspotSettings: () -> Unit,
    onPickDocuments: () -> Unit,
    onPickMedia: () -> Unit,
    onToggleVideoPublic: (String, Boolean) -> Unit,
    onToggleMediaChannel: (String, String) -> Unit,
    onDeleteFile: (String) -> Unit,
    onClearLogs: () -> Unit,
    onCopy: (String, String) -> Unit,
    clientHubContent: @Composable () -> Unit,
    hubScreenContent: @Composable () -> Unit
) {
    var showAuditLogs by rememberSaveable { mutableStateOf(true) }

    when (selectedBottomTab) {
        AppBottomTab.HOME -> {
            if (mode == NodeMode.CLIENT) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(modifier = Modifier.widthIn(max = 860.dp)) {
                        clientHubContent()
                    }
                }
                return
            }

            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        HostPowerControlCard(
                            hostState = hostState,
                            onToggleHost = onToggleHost,
                            onOpenProtocolDialog = onOpenProtocolDialog,
                            onOpenQrDialog = onOpenQrDialog,
                            onOpenHotspotSettings = onOpenHotspotSettings
                        )
                    }
                }

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        HostCredentialsCard(
                            hostState = hostState,
                            onCopy = onCopy
                        )
                    }
                    item { PrivacyTelemetryCard(hostState = hostState) }
                }
            }
        }

        AppBottomTab.HUB -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 860.dp)
                ) {
                    hubScreenContent()
                }
            }
        }

        AppBottomTab.UPLOAD -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    SharedVaultHeaderCard(
                        totalCount = hostState.sharedFiles.size,
                        publicCount = hostState.publicCount,
                        isImporting = isImporting,
                        onPickDocuments = onPickDocuments,
                        onPickMedia = onPickMedia
                    )
                }
                if (hostState.sharedFiles.isEmpty()) {
                    item { EmptyVaultCard() }
                } else {
                    items(hostState.sharedFiles, key = { it.id }) { file ->
                        SharedFileRowCard(
                            item = file,
                            channels = channels,
                            onTogglePublic = { isPublic -> onToggleVideoPublic(file.id, isPublic) },
                            onToggleChannelAssignment = { channelId ->
                                onToggleMediaChannel(channelId, file.id)
                            },
                            onDelete = { onDeleteFile(file.id) }
                        )
                    }
                }
            }
        }

        AppBottomTab.SETTINGS -> {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    SettingsAutonomousCard(
                        hostState = hostState,
                        myUsername = myUsername,
                        missingPermissionsCount = missingPermissionsCount,
                        onCopyUsername = onCopy,
                        onSaveBroadcastCredentials = onSaveBroadcastCredentials,
                        onBroadcastSaved = onBroadcastSaved,
                        onRequestPermissions = onRequestPermissions,
                        onOpenProtocolDialog = onOpenProtocolDialog,
                        onOpenQrDialog = onOpenQrDialog,
                        onOpenWifiSettings = onOpenWifiSettings,
                        onOpenHotspotSettings = onOpenHotspotSettings
                    )
                }
                item {
                    AuditLogPanel(
                        logs = auditLogs,
                        expanded = showAuditLogs,
                        onToggleExpand = { showAuditLogs = !showAuditLogs },
                        onClearLogs = onClearLogs
                    )
                }
            }
        }
    }
}

@Composable
private fun HostPowerControlCard(
    hostState: HostState,
    onToggleHost: () -> Unit,
    onOpenProtocolDialog: () -> Unit,
    onOpenQrDialog: () -> Unit,
    onOpenHotspotSettings: () -> Unit
) {
    val isActive = hostState.status == HotspotStatus.ACTIVE ||
        hostState.status == HotspotStatus.SERVER_ONLY
    val isStarting = hostState.status == HotspotStatus.STARTING
    var menuExpanded by remember { mutableStateOf(false) }

    val infiniteTransition = rememberInfiniteTransition(label = "nodePulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        border = BorderStroke(
            width = 1.dp,
            color = if (isActive) VoidMint.copy(alpha = 0.65f) else VoidBorder
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Image(
                painter = painterResource(id = R.drawable.img_void_node_banner_1794598540599),
                contentDescription = stringResource(R.string.cd_node_banner),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .alpha(if (isActive) 0.28f else 0.14f)
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                VoidObsidian.copy(alpha = 0.45f),
                                VoidSurface.copy(alpha = 0.92f)
                            )
                        )
                    )
            )

            // Three-dot menu anchored in the top-right corner of the Power Control Card
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 6.dp, end = 6.dp)
            ) {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("host_power_more_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "Network protocol and QR options",
                        tint = VoidTextPrimary
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = VoidSurfaceElevated
                ) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "Network Protocol",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = VoidTextPrimary
                                )
                                Text(
                                    text = if (hostState.selectedSecurityMode == HotspotSecurityMode.OPEN_VISIBLE) {
                                        "OPEN (VISIBLE) · HTTP/WS :${hostState.port}"
                                    } else {
                                        "WPA2-PSK (VISIBLE) · HTTP/WS :${hostState.port}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VoidCyan
                                )
                            }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.WifiTethering,
                                contentDescription = null,
                                tint = VoidCyan
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onOpenProtocolDialog()
                        },
                        modifier = Modifier.testTag("menu_network_protocol")
                    )
                    HorizontalDivider(color = VoidBorder)
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = "QR Code",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = VoidTextPrimary
                                )
                                Text(
                                    text = "Wi-Fi & Portal QR Share",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VoidMint
                                )
                            }
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.QrCode2,
                                contentDescription = null,
                                tint = VoidMint
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onOpenQrDialog()
                        },
                        modifier = Modifier.testTag("menu_qr_code")
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Live Public Broadcast Pill
                StatusChip(
                    label = if (hostState.publicCount > 0) {
                        "LIVE BROADCAST: ${hostState.publicCount} PUBLIC STREAM(S)"
                    } else {
                        "0 PUBLIC STREAMS · ALL PRIVATE"
                    },
                    accentColor = if (hostState.publicCount > 0 && isActive) VoidMint else VoidAmber
                )

                if (hostState.needsOpenHotspotSetupBanner) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = VoidAmber.copy(alpha = 0.12f)),
                        border = BorderStroke(1.dp, VoidAmber.copy(alpha = 0.8f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("open_hotspot_setup_banner")
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "FINISH OPEN NETWORK BROADCAST",
                                style = MaterialTheme.typography.labelLarge,
                                color = VoidAmber,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Android must create the password-free hotspot. Set Network name to '${hostState.configuredSsid}', choose No password / Open, and turn it on. BitVoid will detect it automatically.",
                                style = MaterialTheme.typography.bodySmall,
                                color = VoidTextPrimary
                            )
                            Button(
                                onClick = onOpenHotspotSettings,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VoidAmber,
                                    contentColor = VoidObsidian
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("open_android_hotspot_settings")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.WifiTethering,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("OPEN ANDROID HOTSPOT SETTINGS")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Large Tactical Power Core Button
                Box(
                    modifier = Modifier.size(136.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isActive || isStarting) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val maxRadius = size.minDimension / 2f
                            drawCircle(
                                color = VoidMint.copy(alpha = (1f - pulseProgress) * 0.45f),
                                radius = maxRadius * (0.65f + pulseProgress * 0.35f),
                                style = Stroke(width = 3.dp.toPx())
                            )
                        }
                    }

                    Surface(
                        onClick = onToggleHost,
                        shape = CircleShape,
                        color = if (isActive) VoidMint else VoidSurfaceElevated,
                        border = BorderStroke(
                            width = 2.dp,
                            color = if (isActive) VoidMint else VoidMint.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier
                            .size(96.dp)
                            .testTag("host_power_toggle")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isStarting) {
                                CircularProgressIndicator(
                                    color = VoidMint,
                                    strokeWidth = 3.dp,
                                    modifier = Modifier.size(34.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.PowerSettingsNew,
                                    contentDescription = stringResource(R.string.cd_power_toggle),
                                    tint = if (isActive) VoidObsidian else VoidMint,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = when {
                        hostState.status == HotspotStatus.ACTIVE && hostState.isOpenSecurity ->
                            "OPEN ENGINE & PORTAL LIVE"
                        hostState.status == HotspotStatus.ACTIVE ->
                            "WPA2 VISIBLE ENGINE & PORTAL LIVE"
                        hostState.status == HotspotStatus.SERVER_ONLY ->
                            "HTTP VAULT ONLINE (0.0.0.0:${hostState.port})"
                        hostState.status == HotspotStatus.STARTING ->
                            "BOOTING BITVOID HOST ENGINE..."
                        hostState.status == HotspotStatus.ERROR ->
                            "HOST ENGINE STANDBY"
                        else -> "READY TO START BITVOID HOST ENGINE"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = when (hostState.status) {
                        HotspotStatus.ACTIVE, HotspotStatus.SERVER_ONLY -> VoidMint
                        HotspotStatus.ERROR -> VoidCrimson
                        else -> VoidTextPrimary
                    }
                )

                Spacer(modifier = Modifier.height(6.dp))

                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (hostState.isOpenSecurity) {
                        VoidMint.copy(alpha = 0.14f)
                    } else {
                        VoidCyan.copy(alpha = 0.14f)
                    },
                    border = BorderStroke(
                        1.dp,
                        if (hostState.isOpenSecurity) {
                            VoidMint.copy(alpha = 0.65f)
                        } else {
                            VoidCyan.copy(alpha = 0.65f)
                        }
                    ),
                    modifier = Modifier.testTag("host_security_status_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (hostState.isOpenSecurity) {
                                Icons.Filled.LockOpen
                            } else {
                                Icons.Filled.Lock
                            },
                            contentDescription = null,
                            tint = if (hostState.isOpenSecurity) VoidMint else VoidCyan,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = hostState.securityLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hostState.isOpenSecurity) VoidMint else VoidCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                hostState.errorMessage?.let { err ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = VoidCrimson
                    )
                }
            }
        }
    }
}

@Composable
private fun HostCredentialsCard(
    hostState: HostState,
    onCopy: (String, String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        border = BorderStroke(1.dp, VoidBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "NODE ACCESS & INSTANT JOIN",
                    style = MaterialTheme.typography.labelLarge,
                    color = VoidCyan
                )
                StatusChip(
                    label = when {
                        hostState.isSecureServerRunning -> "HTTPS :${hostState.securePort} UP"
                        hostState.isServerRunning -> "HTTP :${hostState.port} UP"
                        else -> "PORTAL STANDBY"
                    },
                    accentColor = if (hostState.isServerRunning) VoidMint else VoidTextSecondary
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (hostState.isOpenSecurity) {
                            VoidMint.copy(alpha = 0.1f)
                        } else {
                            VoidCyan.copy(alpha = 0.1f)
                        }
                    )
                    .border(
                        1.dp,
                        if (hostState.isOpenSecurity) VoidMint.copy(alpha = 0.55f) else VoidCyan.copy(alpha = 0.55f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (hostState.isOpenSecurity) Icons.Filled.LockOpen else Icons.Filled.Lock,
                    contentDescription = null,
                    tint = if (hostState.isOpenSecurity) VoidMint else VoidCyan,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = hostState.securityLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hostState.isOpenSecurity) VoidMint else VoidCyan,
                    fontWeight = FontWeight.Bold
                )
            }

            CredentialRow(
                label = if (hostState.isOpenSecurity) {
                    "VISIBLE SSID (OPEN · NO PASSWORD)"
                } else {
                    "VISIBLE SSID (WPA2-PSK)"
                },
                value = if (hostState.ssid.isNotBlank()) {
                    hostState.ssid
                } else {
                    "${hostState.effectiveSsid} (ready)"
                },
                canCopy = true,
                valueColor = VoidCyan,
                onCopy = { onCopy("SSID", hostState.effectiveSsid) }
            )

            if (!hostState.isOpenSecurity || hostState.passphrase.isNotBlank()) {
                val displayPassphrase = hostState.passphrase.ifBlank {
                    hostState.configuredPassphrase.ifBlank { BitVoidNetworkConfig.DEFAULT_WPA2_PASSPHRASE }
                }
                CredentialRow(
                    label = if (hostState.isOpenSecurity && hostState.passphrase.isNotBlank()) {
                        "HOTSPOT PASSPHRASE (OS REQUIRED)"
                    } else {
                        "WPA2 PASSPHRASE"
                    },
                    value = displayPassphrase,
                    canCopy = displayPassphrase.isNotBlank(),
                    valueColor = VoidMint,
                    onCopy = { onCopy("Hotspot Passphrase", displayPassphrase) }
                )
            }

            CredentialRow(
                label = "CAPTIVE PORTAL AUTO-REDIRECT TARGET",
                value = hostState.effectivePortalUrl,
                canCopy = true,
                valueColor = VoidMint,
                onCopy = { onCopy("Portal URL", hostState.effectivePortalUrl) }
            )

            if (hostState.isSecureServerRunning) {
                CredentialRow(
                    label = "SECURE VIDEO CALL PORTAL (CAMERA + MIC)",
                    value = hostState.securePortalUrl,
                    canCopy = true,
                    valueColor = VoidCyan,
                    onCopy = { onCopy("Secure Portal URL", hostState.securePortalUrl) }
                )
                CredentialRow(
                    label = "ONE-TIME BROWSER CERTIFICATE SETUP",
                    value = "${hostState.effectivePortalUrl}/secure-setup",
                    canCopy = true,
                    valueColor = VoidAmber,
                    onCopy = {
                        onCopy(
                            "Secure Browser Setup",
                            "${hostState.effectivePortalUrl}/secure-setup"
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun CredentialRow(
    label: String,
    value: String,
    canCopy: Boolean,
    valueColor: Color = VoidTextPrimary,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(VoidObsidian)
            .border(1.dp, VoidBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = VoidTextSecondary
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (canCopy) {
            IconButton(
                onClick = onCopy,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.cd_copy_value),
                    tint = VoidMint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun PrivacyTelemetryCard(hostState: HostState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        border = BorderStroke(1.dp, VoidBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Shield,
                    contentDescription = null,
                    tint = VoidMint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "ANONYMIZATION & TRAFFIC PADDING",
                    style = MaterialTheme.typography.labelLarge,
                    color = VoidMint
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TelemetryTile(
                    label = "EPHEMERAL SESS",
                    value = hostState.activeSessionsCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                TelemetryTile(
                    label = "HEADERS STRIPPED",
                    value = hostState.strippedHeadersTotal.toString(),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TelemetryTile(
                    label = "4KB PADDED RESP",
                    value = hostState.paddedResponsesCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                TelemetryTile(
                    label = "BYTES SERVED",
                    value = formatBytes(hostState.bytesServed),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun TelemetryTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(VoidObsidian)
            .border(1.dp, VoidBorder, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = VoidTextSecondary,
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = VoidCyan,
            maxLines = 1
        )
    }
}

@Composable
private fun SharedVaultHeaderCard(
    totalCount: Int,
    publicCount: Int,
    isImporting: Boolean,
    onPickDocuments: () -> Unit,
    onPickMedia: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        border = BorderStroke(1.dp, VoidBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderOpen,
                        contentDescription = null,
                        tint = VoidMint,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "VAULT ($totalCount)",
                        style = MaterialTheme.typography.labelLarge,
                        color = VoidTextPrimary
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusChip(
                        label = if (publicCount > 0) "$publicCount PUBLIC" else "0 PUBLIC",
                        accentColor = if (publicCount > 0) VoidMint else VoidTextSecondary
                    )
                    if (isImporting) {
                        CircularProgressIndicator(
                            color = VoidMint,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onPickMedia,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VoidMint,
                        contentColor = VoidObsidian
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("pick_media_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.PhotoLibrary,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("VIDEO / PHOTO", style = MaterialTheme.typography.labelMedium)
                }

                OutlinedButton(
                    onClick = onPickDocuments,
                    border = BorderStroke(1.dp, VoidMint.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidMint),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("pick_files_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("ANY FILE", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun EmptyVaultCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = VoidSurface.copy(alpha = 0.6f)),
        border = BorderStroke(1.dp, VoidBorder),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = null,
                tint = VoidTextSecondary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "No shared payloads in BitVoid_Shared",
                style = MaterialTheme.typography.titleMedium,
                color = VoidTextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Import MP4 videos, audio tracks, images, or archives to stream via HTTP 206 byte-ranges to connected peers.",
                style = MaterialTheme.typography.bodyMedium,
                color = VoidTextSecondary
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SharedFileRowCard(
    item: BitVoidMediaItem,
    channels: List<BitVoidChannel>,
    onTogglePublic: (Boolean) -> Unit,
    onToggleChannelAssignment: (String) -> Unit,
    onDelete: () -> Unit
) {
    val accent = if (item.isPublic) VoidMint else VoidTextSecondary
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        border = BorderStroke(
            width = 1.dp,
            color = if (item.isPublic) VoidMint.copy(alpha = 0.65f) else VoidBorder
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (item.isPublic) VoidMintContainer else VoidObsidian)
                        .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = item.category.badge,
                        style = MaterialTheme.typography.labelMedium,
                        color = accent
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = VoidTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${formatBytes(item.size)} · ${item.mimeType}",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.cd_delete_file),
                        tint = VoidCrimson
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(VoidObsidian)
                    .border(
                        1.dp,
                        if (item.isPublic) VoidMint.copy(alpha = 0.4f) else VoidBorder,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (item.isPublic) Icons.Filled.Public else Icons.Filled.VisibilityOff,
                        contentDescription = null,
                        tint = if (item.isPublic) VoidMint else VoidAmber,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (item.isPublic) "[ PUBLIC · BROADCASTING ]" else "[ PRIVATE · CHANNEL / 403 ]",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (item.isPublic) VoidMint else VoidAmber
                    )
                }
                Switch(
                    checked = item.isPublic,
                    onCheckedChange = onTogglePublic,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = VoidObsidian,
                        checkedTrackColor = VoidMint,
                        uncheckedThumbColor = VoidTextSecondary,
                        uncheckedTrackColor = VoidSurfaceElevated,
                        uncheckedBorderColor = VoidBorder
                    ),
                    modifier = Modifier.testTag("visibility_switch_${item.id}")
                )
            }

            if (channels.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "ASSIGN TO CHANNELS",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidCyan
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        channels.forEach { channel ->
                            val isAssigned = channel.mediaList.any { it.id == item.id }
                            val chipAccent = when {
                                isAssigned && channel.isPublic -> VoidMint
                                isAssigned -> VoidAmber
                                else -> VoidTextSecondary
                            }
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(
                                        if (isAssigned) chipAccent.copy(alpha = 0.14f) else VoidObsidian
                                    )
                                    .border(
                                        1.dp,
                                        if (isAssigned) chipAccent else VoidBorder,
                                        RoundedCornerShape(999.dp)
                                    )
                                    .clickable { onToggleChannelAssignment(channel.channelId) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                                    .testTag("assign_${item.id}_${channel.channelId}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (isAssigned) "✓ #${channel.name}" else "+ #${channel.name}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = chipAccent
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditLogPanel(
    logs: List<AuditLogEntry>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onClearLogs: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        border = BorderStroke(1.dp, VoidBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Terminal,
                        contentDescription = null,
                        tint = VoidCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "EPHEMERAL SESSION LOG (${logs.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = VoidCyan
                    )
                }
                Text(
                    text = if (expanded) "HIDE" else "SHOW",
                    style = MaterialTheme.typography.labelMedium,
                    color = VoidMint
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "IP/MAC addresses are discarded; tokens rotate per request.",
                            style = MaterialTheme.typography.labelSmall,
                            color = VoidTextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        if (logs.isNotEmpty()) {
                            TextButton(onClick = onClearLogs) {
                                Text(
                                    text = "PURGE",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = VoidCrimson
                                )
                            }
                        }
                    }

                    if (logs.isEmpty()) {
                        Text(
                            text = "No HTTP transactions recorded yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VoidTextSecondary
                        )
                    } else {
                        logs.take(12).forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(VoidObsidian)
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${entry.ephemeralToken} · ${entry.method} ${entry.sanitizedPath}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VoidMint,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${entry.timestampLabel} · HTTP ${entry.statusCode} · ${entry.strippedHeaderCount} hdrs stripped",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VoidTextSecondary
                                    )
                                }
                                Text(
                                    text = formatBytes(entry.paddedSizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VoidCyan
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SandboxedPortalBrowserScreen(
    clientState: ClientState,
    onClose: () -> Unit,
    onRequestEvaluated: (Boolean) -> Unit
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(clientState.targetPortalUrl) }

    Scaffold(
        containerColor = VoidObsidian,
        contentWindowInsets = WindowInsets.safeDrawing
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(VoidObsidian)
                .padding(innerPadding)
        ) {
            // Browser Chrome Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(VoidSurface)
                    .border(1.dp, VoidBorder)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        val wv = webViewRef
                        if (wv != null && wv.canGoBack()) {
                            wv.goBack()
                        } else {
                            onClose()
                        }
                    },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Navigate back",
                        tint = VoidTextPrimary
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                ) {
                    Text(
                        text = currentUrl,
                        style = MaterialTheme.typography.labelLarge,
                        color = VoidMint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "UA STRIPPED · NO WEBRTC · MEMORY-ONLY · RFC1918 FIREWALL",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidCyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = { webViewRef?.reload() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Reload portal",
                        tint = VoidCyan
                    )
                }

                IconButton(
                    onClick = {
                        webViewRef?.apply {
                            stopLoading()
                            clearCache(true)
                            clearHistory()
                            loadUrl("about:blank")
                        }
                        onClose()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("close_sandbox_browser_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close sandboxed browser",
                        tint = VoidCrimson
                    )
                }
            }

            // Live Firewall & Anonymizer Strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0A0E14))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "LOCAL REQS: ${clientState.privacyTelemetry.strippedRequestsCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = VoidMint
                )
                Text(
                    text = "EXT BLOCKED: ${clientState.privacyTelemetry.blockedExternalRequestsCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (clientState.privacyTelemetry.blockedExternalRequestsCount > 0) {
                        VoidAmber
                    } else {
                        VoidTextSecondary
                    }
                )
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.parseColor("#0D1117"))
                        BitVoidClientManager.configureSandboxedWebView(
                            webView = this,
                            onRequestEvaluated = onRequestEvaluated,
                            onPageLoaded = { loadedUrl -> currentUrl = loadedUrl }
                        )
                        tag = clientState.targetPortalUrl
                        loadUrl(clientState.targetPortalUrl)
                        webViewRef = this
                    }
                },
                update = { webView ->
                    val target = clientState.targetPortalUrl
                    if (target.isNotBlank() && webView.tag != target) {
                        webView.tag = target
                        currentUrl = target
                        webView.loadUrl(target)
                    }
                },
                onRelease = { webView ->
                    webView.stopLoading()
                    webView.clearCache(true)
                    webView.clearHistory()
                    webView.destroy()
                }
            )
        }
    }
}

@Composable
private fun StatusChip(
    label: String,
    accentColor: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(accentColor.copy(alpha = 0.12f))
            .border(1.dp, accentColor.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accentColor,
            fontWeight = FontWeight.Bold
        )
    }
}



@Composable
private fun ChannelEditorDialog(
    existingChannel: BitVoidChannel?,
    suggestedPrivateId: String,
    vaultItems: List<BitVoidMediaItem>,
    onSuggestPrivateId: () -> String,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        isPublic: Boolean,
        isHidden: Boolean,
        customChannelId: String?,
        privateKey: String?,
        selectedMediaIds: Set<String>
    ) -> String?
) {
    var channelName by rememberSaveable(existingChannel?.channelId) {
        mutableStateOf(existingChannel?.name ?: "")
    }
    var isPublic by rememberSaveable(existingChannel?.channelId) {
        mutableStateOf(existingChannel?.isPublic ?: true)
    }
    var isHidden by rememberSaveable(existingChannel?.channelId) {
        mutableStateOf(existingChannel?.isHidden ?: false)
    }
    var privateChannelId by rememberSaveable(existingChannel?.channelId) {
        mutableStateOf(existingChannel?.channelId ?: suggestedPrivateId)
    }
    var privateKey by rememberSaveable(existingChannel?.channelId) {
        mutableStateOf(existingChannel?.privateKey.orEmpty())
    }
    var selectedMediaIds by remember(existingChannel?.channelId) {
        mutableStateOf<Set<String>>(existingChannel?.mediaList?.map { it.id }?.toSet() ?: emptySet())
    }
    var validationError by remember { mutableStateOf<String?>(null) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = VoidMint,
        unfocusedBorderColor = VoidBorder,
        focusedLabelColor = VoidMint,
        unfocusedLabelColor = VoidTextSecondary,
        focusedTextColor = VoidTextPrimary,
        unfocusedTextColor = VoidTextPrimary,
        cursorColor = VoidMint
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VoidSurface,
        titleContentColor = VoidMint,
        textContentColor = VoidTextPrimary,
        title = {
            Text(
                text = if (existingChannel != null) {
                    "ASSIGN MEDIA // #${existingChannel.name}"
                } else {
                    "CREATE NEW CHANNEL"
                },
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (existingChannel == null) {
                    OutlinedTextField(
                        value = channelName,
                        onValueChange = {
                            channelName = it
                            validationError = null
                        },
                        label = { Text("Channel Name (e.g. Field Briefings)") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("channel_name_dialog_input")
                    )

                    Text(
                        text = "CHANNEL ACCESS",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidCyan
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                isPublic = true
                                isHidden = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isPublic && !isHidden) VoidMint else VoidObsidian,
                                contentColor = if (isPublic && !isHidden) VoidObsidian else VoidTextSecondary
                            ),
                            border = BorderStroke(1.dp, if (isPublic && !isHidden) VoidMint else VoidBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("channel_toggle_public")
                        ) {
                            Text("PUBLIC", style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = { isPublic = false },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!isPublic || isHidden) VoidAmber else VoidObsidian,
                                contentColor = if (!isPublic || isHidden) VoidObsidian else VoidTextSecondary
                            ),
                            border = BorderStroke(1.dp, if (!isPublic || isHidden) VoidAmber else VoidBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("channel_toggle_private")
                        ) {
                            Text("PRIVATE (ID)", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    Text(
                        text = "HUB DIRECTORY VISIBILITY",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidCyan
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { isHidden = false },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!isHidden) VoidCyan else VoidObsidian,
                                contentColor = if (!isHidden) VoidObsidian else VoidTextSecondary
                            ),
                            border = BorderStroke(1.dp, if (!isHidden) VoidCyan else VoidBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("channel_toggle_show")
                        ) {
                            Text("SHOW", style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = {
                                isHidden = true
                                isPublic = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isHidden) VoidCrimson else VoidObsidian,
                                contentColor = if (isHidden) VoidTextPrimary else VoidTextSecondary
                            ),
                            border = BorderStroke(1.dp, if (isHidden) VoidCrimson else VoidBorder),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("channel_toggle_hidden")
                        ) {
                            Text("HIDDEN", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    if (isHidden) {
                        Text(
                            text = "Hidden from Hub & Web Portal lists — accessible ONLY by entering this Private Channel ID in Hub → Enter Private Channel ID.",
                            style = MaterialTheme.typography.labelSmall,
                            color = VoidAmber
                        )
                    }

                    AnimatedVisibility(visible = !isPublic || isHidden) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = privateChannelId,
                                    onValueChange = {
                                        privateChannelId = it
                                        validationError = null
                                    },
                                    label = { Text("Private Channel ID") },
                                    singleLine = true,
                                    colors = fieldColors,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("channel_id_dialog_input")
                                )
                                OutlinedButton(
                                    onClick = { privateChannelId = onSuggestPrivateId() },
                                    border = BorderStroke(1.dp, VoidAmber),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidAmber),
                                    modifier = Modifier.height(52.dp)
                                ) {
                                    Text("GEN", style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            OutlinedTextField(
                                value = privateKey,
                                onValueChange = { privateKey = it },
                                label = { Text("Optional Private Key (blank = ID only)") },
                                singleLine = true,
                                colors = fieldColors,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("channel_key_dialog_input")
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Select Vault videos/files assigned to #${existingChannel.name}:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VoidTextSecondary
                    )
                }

                Text(
                    text = "ASSIGN VAULT MEDIA (${selectedMediaIds.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = VoidCyan
                )

                if (vaultItems.isEmpty()) {
                    Text(
                        text = "Vault is empty. Import videos anytime and assign them right from the Vault cards.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VoidTextSecondary
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 220.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(VoidObsidian)
                            .border(1.dp, VoidBorder, RoundedCornerShape(8.dp))
                            .verticalScroll(rememberScrollState())
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        vaultItems.forEach { item ->
                            val isChecked = item.id in selectedMediaIds
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        selectedMediaIds = if (isChecked) {
                                            selectedMediaIds - item.id
                                        } else {
                                            selectedMediaIds + item.id
                                        }
                                    }
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selectedMediaIds = if (checked) {
                                            selectedMediaIds + item.id
                                        } else {
                                            selectedMediaIds - item.id
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = VoidMint,
                                        checkmarkColor = VoidObsidian,
                                        uncheckedColor = VoidTextSecondary
                                    )
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = VoidTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${item.category.badge} · ${formatBytes(item.size)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VoidTextSecondary
                                    )
                                }
                            }
                        }
                    }
                }

                validationError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = VoidCrimson
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    validationError = onSave(
                        channelName,
                        isPublic,
                        isHidden,
                        privateChannelId,
                        privateKey.ifBlank { null },
                        selectedMediaIds
                    )
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = VoidMint,
                    contentColor = VoidObsidian
                ),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("confirm_create_channel_button")
            ) {
                Text(
                    text = if (existingChannel != null) "SAVE MEDIA" else "CREATE CHANNEL",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.height(48.dp)
            ) {
                Text(
                    text = "CANCEL",
                    style = MaterialTheme.typography.labelLarge,
                    color = VoidTextSecondary
                )
            }
        }
    )
}

private fun formatDuration(totalSeconds: Long): String {
    val hrs = totalSeconds / 3600
    val mins = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return if (hrs > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format(Locale.US, "%02d:%02d", mins, secs)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
