package com.bitvoid.network.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitvoid.network.call.BitVoidCallState
import com.bitvoid.network.call.DirectCallPhase
import com.bitvoid.network.channel.BitVoidChannel
import com.bitvoid.network.channel.BitVoidChannelManager
import com.bitvoid.network.channel.BitVoidSpaceKind
import com.bitvoid.network.channel.BitVoidSpaceRole
import com.bitvoid.network.client.ClientChannelSession
import com.bitvoid.network.client.ClientChatAttachment
import com.bitvoid.network.client.ClientChatMessage
import com.bitvoid.network.client.ClientDirectSession
import com.bitvoid.network.client.ClientWsStatus
import com.bitvoid.network.client.MessageDeliveryState
import com.bitvoid.network.client.RemoteChannelSummary
import com.bitvoid.network.client.RemoteLiveClassSummary
import com.bitvoid.network.client.RemoteMediaItem
import com.bitvoid.network.client.RemoteSpaceDetails
import com.bitvoid.network.client.RemoteSpaceInvitation
import com.bitvoid.network.direct.DirectConversationSummary
import com.bitvoid.network.model.ClientConnectionStatus
import com.bitvoid.network.model.ClientState
import com.bitvoid.network.ui.theme.VoidAmber
import com.bitvoid.network.ui.theme.VoidCrimson
import com.bitvoid.network.ui.theme.VoidCyan
import com.bitvoid.network.ui.theme.VoidMint
import com.bitvoid.network.ui.theme.VoidMintContainer
import java.util.Locale

private data class HubChannelDisplayItem(
    val channelId: String?,
    val name: String,
    val isPublic: Boolean,
    val isHidden: Boolean,
    val isOwner: Boolean,
    val hasPrivateKey: Boolean,
    val mediaCount: Int,
    val activePeers: Int,
    val messageCount: Int,
    val isGeneralChat: Boolean,
    val spaceKind: BitVoidSpaceKind = BitVoidSpaceKind.LEGACY_CHAT,
    val myRole: BitVoidSpaceRole? = null,
    val memberCount: Int = 0,
    val subscriberCount: Int = 0
)

@Composable
fun BitVoidPrivateChatScreen(
    myUsername: String,
    directConversations: List<DirectConversationSummary>,
    knownUsers: List<String>,
    activeDirectSession: ClientDirectSession?,
    directMessages: List<ClientChatMessage>,
    directWsStatus: ClientWsStatus,
    isOpeningDirectChat: Boolean,
    directChatError: String?,
    isUploadingFile: Boolean,
    callUiState: BitVoidCallUiState,
    callActions: BitVoidCallActions,
    onOpenDirectChat: (String) -> Unit,
    onSendDirectMessage: (String) -> Unit,
    onLeaveDirectChat: () -> Unit,
    onClearDirectChatError: () -> Unit,
    onUploadDirectFile: (Uri, String) -> Unit,
    onBuildAttachmentUrl: (String, String?) -> String,
    modifier: Modifier = Modifier
) {
    if (activeDirectSession != null) {
        BackHandler(onBack = onLeaveDirectChat)
        WhatsAppDirectChatSpace(
            session = activeDirectSession,
            messages = directMessages,
            wsStatus = directWsStatus,
            isUploadingFile = isUploadingFile,
            callUiState = callUiState,
            callActions = callActions,
            onBack = onLeaveDirectChat,
            onSendMessage = onSendDirectMessage,
            onUploadFile = onUploadDirectFile,
            onBuildAttachmentUrl = onBuildAttachmentUrl,
            modifier = modifier
        )
        return
    }

    var showAddUserDialog by rememberSaveable { mutableStateOf(false) }
    var targetUsername by rememberSaveable { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val visibleUsers = remember(knownUsers, myUsername) {
        knownUsers
            .filter { !it.equals(myUsername, ignoreCase = true) }
            .distinctBy { it.lowercase(Locale.US) }
            .sortedBy { it.lowercase(Locale.US) }
    }
    val conversationByPeer = directConversations.associateBy {
        it.peerUsername.lowercase(Locale.US)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "P.CHAT",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "PRIVATE USER CHAT · VOICE CALLS",
                    style = MaterialTheme.typography.labelSmall,
                    color = VoidMint
                )
            }
            Surface(
                onClick = {
                    targetUsername = ""
                    localError = null
                    onClearDirectChatError()
                    showAddUserDialog = true
                },
                shape = CircleShape,
                color = VoidMintContainer,
                border = BorderStroke(1.5.dp, VoidMint),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("pchat_add_user_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Add user by User ID",
                        tint = VoidMint,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

        if (isOpeningDirectChat) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = VoidMint
                )
                Text("Opening private chat…", color = VoidMint)
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "pchat_visible_header") {
                HubSectionHeader(title = "USERS ON THIS NODE", accent = VoidMint)
            }
            if (visibleUsers.isEmpty()) {
                item(key = "pchat_visible_empty") {
                    Text(
                        "No visible users are online. Tap + to add someone by their exact User ID.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(visibleUsers, key = { "visible_$it" }) { peer ->
                    val conversation = conversationByPeer[peer.lowercase(Locale.US)]
                    HubDirectChatRow(
                        peerUsername = peer,
                        subtitle = conversation?.lastMessagePreview
                            ?.let { "Online · ${conversation.messageCount} msgs · $it" }
                            ?: "Online on this BitVoid node · Tap to chat or call",
                        onClick = { onOpenDirectChat(peer) },
                        testTag = "pchat_visible_user_$peer"
                    )
                }
            }
        }
    }

    if (showAddUserDialog) {
        AlertDialog(
            onDismissRequest = { showAddUserDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = VoidMint,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("pchat_add_user_dialog"),
            title = { Text("ADD PRIVATE CHAT") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Enter the exact User ID. This also works when that user disabled automatic display.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = targetUsername,
                        onValueChange = {
                            targetUsername = it
                            localError = null
                            onClearDirectChatError()
                        },
                        label = { Text("User ID") },
                        placeholder = { Text("user-4210") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = VoidMint,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            cursorColor = VoidMint
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pchat_exact_user_id_input")
                    )
                    (localError ?: directChatError)?.let { error ->
                        Text(error, color = VoidCrimson, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clean = targetUsername.trim().removePrefix("@")
                        when {
                            clean.isEmpty() -> localError = "Enter a User ID."
                            clean.equals(myUsername, ignoreCase = true) ->
                                localError = "Enter another user's ID."
                            else -> {
                                localError = null
                                onOpenDirectChat(clean)
                            }
                        }
                    },
                    enabled = !isOpeningDirectChat,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VoidMint,
                        contentColor = MaterialTheme.colorScheme.background
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("pchat_add_user_submit")
                ) {
                    Text(if (isOpeningDirectChat) "OPENING…" else "OPEN CHAT")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddUserDialog = false }) {
                    Text("CANCEL", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BitVoidHubScreen(
    clientState: ClientState,
    myUsername: String,
    hostChannels: List<BitVoidChannel>,
    remoteChannels: List<RemoteChannelSummary>,
    directConversations: List<DirectConversationSummary>,
    knownUsers: List<String>,
    activeChannelSession: ClientChannelSession?,
    channelMessages: List<ClientChatMessage>,
    wsStatus: ClientWsStatus,
    channelPeersCount: Int,
    isJoiningChannel: Boolean,
    channelJoinError: String?,
    activeSpaceDetails: RemoteSpaceDetails?,
    isLoadingSpaceDetails: Boolean,
    spaceError: String?,
    activeDirectSession: ClientDirectSession?,
    directMessages: List<ClientChatMessage>,
    directWsStatus: ClientWsStatus,
    isOpeningDirectChat: Boolean,
    directChatError: String?,
    onOpenCreateChannel: () -> Unit,
    onOpenCreateGroup: () -> Unit,
    onOpenCreateBroadcastChannel: () -> Unit,
    onOpenDiscoverChannels: () -> Unit,
    onEditChannel: (channelId: String) -> Unit,
    onDeleteChannel: (channelId: String) -> Unit,
    onCopyChannelId: (label: String, channelId: String) -> Unit,
    onOpenChannel: (channelId: String, privateKey: String?) -> Unit,
    onSendChatMessage: (String) -> Unit,
    onLeaveChannel: () -> Unit,
    onRefreshSpaceDetails: () -> Unit,
    onAddSpaceMember: (String, (Result<RemoteSpaceDetails>) -> Unit) -> Unit,
    onRemoveSpaceMember: (String, (Result<RemoteSpaceDetails>) -> Unit) -> Unit,
    onSetSpaceMemberRole: (String, BitVoidSpaceRole, (Result<RemoteSpaceDetails>) -> Unit) -> Unit,
    onUpdateSpace: (String, String, Uri?, Boolean, Boolean?, (Result<RemoteSpaceDetails>) -> Unit) -> Unit,
    onCreateSpaceInvitation: ((Result<RemoteSpaceInvitation>) -> Unit) -> Unit,
    onLeaveSpace: ((Result<Unit>) -> Unit) -> Unit,
    onDeleteSpace: ((Result<Unit>) -> Unit) -> Unit,
    onOpenDirectChat: (targetUsername: String) -> Unit,
    onSendDirectMessage: (String) -> Unit,
    onLeaveDirectChat: () -> Unit,
    onClearDirectChatError: () -> Unit,
    onCopyUsername: (String, String) -> Unit,
    onBuildStreamUrl: (RemoteMediaItem, String?) -> String,
    isUploadingFile: Boolean = false,
    onUploadChannelFile: (Uri, String) -> Unit = { _, _ -> },
    onUploadDirectFile: (Uri, String) -> Unit = { _, _ -> },
    onBuildAttachmentUrl: (String, String?) -> String = { path, _ -> path },
    callUiState: BitVoidCallUiState = BitVoidCallUiState(),
    callActions: BitVoidCallActions = BitVoidCallActions(),
    liveClasses: List<RemoteLiveClassSummary> = emptyList(),
    onStartLiveClass: (name: String, isHidden: Boolean, onDone: () -> Unit) -> Unit = { _, _, onDone -> onDone() },
    onOpenListedLiveClass: (RemoteLiveClassSummary) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var pendingAutoVoiceCall by rememberSaveable { mutableStateOf(false) }
    var showActiveSpaceInfo by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(activeChannelSession?.channelId) {
        showActiveSpaceInfo = false
    }

    if (activeDirectSession != null) {
        val directCallStageActive = callUiState.isInRoom(activeDirectSession.dmId)
        if (directCallStageActive) {
            val leaveDirectMeeting: () -> Unit = {
                pendingAutoVoiceCall = false
                callActions.onLeaveSession()
            }
            BackHandler(onBack = leaveDirectMeeting)
            BitVoidClassroomScreen(
                channelId = activeDirectSession.dmId,
                channelName = "@${activeDirectSession.peerUsername}",
                channelToken = activeDirectSession.token,
                localCallsign = activeDirectSession.myUsername,
                callUiState = callUiState,
                callActions = callActions,
                modifier = modifier.fillMaxSize()
            )
            return
        }

        BackHandler {
            pendingAutoVoiceCall = false
            onLeaveDirectChat()
        }
        WhatsAppDirectChatSpace(
            session = activeDirectSession,
            messages = directMessages,
            wsStatus = directWsStatus,
            isUploadingFile = isUploadingFile,
            pendingAutoVoiceCall = pendingAutoVoiceCall,
            onConsumePendingAutoCall = { pendingAutoVoiceCall = false },
            callUiState = callUiState,
            callActions = callActions,
            onBack = {
                pendingAutoVoiceCall = false
                onLeaveDirectChat()
            },
            onSendMessage = onSendDirectMessage,
            onUploadFile = onUploadDirectFile,
            onBuildAttachmentUrl = onBuildAttachmentUrl,
            modifier = modifier
        )
        return
    }

    val isHubLiveClassActive = (activeChannelSession != null && activeChannelSession.isLiveClassRoom) ||
        (callUiState.activeRoomId != null &&
            !callUiState.activeRoomId.startsWith("dm__") &&
            callUiState.callState != BitVoidCallState.IDLE)

    if (isHubLiveClassActive) {
        val roomId = activeChannelSession?.channelId ?: callUiState.activeRoomId.orEmpty()
        val roomName = activeChannelSession?.name
            ?: callUiState.activeChannelName.ifBlank { "Live Class" }
        val roomToken = activeChannelSession?.token
        val callsign = activeChannelSession?.callsign ?: myUsername
        val returnToHub: () -> Unit = {
            callActions.onLeaveSession()
            onLeaveChannel()
        }
        BackHandler(onBack = returnToHub)
        BitVoidClassroomScreen(
            channelId = roomId,
            channelName = roomName,
            channelToken = roomToken,
            localCallsign = callsign,
            callUiState = callUiState,
            callActions = callActions,
            modifier = modifier.fillMaxSize()
        )
        return
    }

    if (activeChannelSession != null) {
        if (activeChannelSession.spaceKind != BitVoidSpaceKind.LEGACY_CHAT && showActiveSpaceInfo) {
            BackHandler { showActiveSpaceInfo = false }
            BitVoidSpaceInfoScreen(
                session = activeChannelSession,
                details = activeSpaceDetails,
                isLoading = isLoadingSpaceDetails,
                errorMessage = spaceError,
                onBack = { showActiveSpaceInfo = false },
                onRefresh = onRefreshSpaceDetails,
                onAddMember = onAddSpaceMember,
                onRemoveMember = onRemoveSpaceMember,
                onSetRole = onSetSpaceMemberRole,
                onUpdate = onUpdateSpace,
                onCreateInvitation = onCreateSpaceInvitation,
                onLeave = onLeaveSpace,
                onDelete = onDeleteSpace,
                modifier = modifier
            )
            return
        }
        BackHandler { onLeaveChannel() }
        WhatsAppChannelChatSpace(
            session = activeChannelSession,
            messages = channelMessages,
            wsStatus = wsStatus,
            peersCount = channelPeersCount,
            isUploadingFile = isUploadingFile,
            onBack = onLeaveChannel,
            onOpenInfo = {
                if (activeChannelSession.spaceKind != BitVoidSpaceKind.LEGACY_CHAT) {
                    showActiveSpaceInfo = true
                    onRefreshSpaceDetails()
                }
            },
            onSendMessage = onSendChatMessage,
            onUploadFile = onUploadChannelFile,
            onBuildStreamUrl = onBuildStreamUrl,
            onBuildAttachmentUrl = onBuildAttachmentUrl,
            modifier = modifier
        )
        return
    }

    var showPlusChooserDialog by rememberSaveable { mutableStateOf(false) }
    var showDirectChatDialog by rememberSaveable { mutableStateOf(false) }
    var directDialogForCall by rememberSaveable { mutableStateOf(false) }
    var showStartLiveClassDialog by rememberSaveable { mutableStateOf(false) }
    var isStartingLiveClass by remember { mutableStateOf(false) }
    var showJoinClassByIdDialog by rememberSaveable { mutableStateOf(false) }
    var isJoiningClassByCode by remember { mutableStateOf(false) }
    var showPrivateUnlockDialog by rememberSaveable { mutableStateOf(false) }
    var targetPrivateChannelName by rememberSaveable { mutableStateOf<String?>(null) }
    var privateIdInput by rememberSaveable { mutableStateOf("") }
    var privateKeyInput by rememberSaveable { mutableStateOf("") }
    var localDialogError by remember { mutableStateOf<String?>(null) }
    var targetUsernameInput by rememberSaveable { mutableStateOf("") }
    var localDmError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(activeChannelSession) {
        if (activeChannelSession != null) {
            showPrivateUnlockDialog = false
            showJoinClassByIdDialog = false
            showStartLiveClassDialog = false
            isStartingLiveClass = false
            isJoiningClassByCode = false
            privateIdInput = ""
            privateKeyInput = ""
            localDialogError = null
        }
    }

    LaunchedEffect(activeDirectSession) {
        if (activeDirectSession != null) {
            showDirectChatDialog = false
            targetUsernameInput = ""
            localDmError = null
        }
    }

    val useRemoteList = clientState.connectionStatus == ClientConnectionStatus.CONNECTED

    val displayItems = remember(useRemoteList, remoteChannels, hostChannels) {
        if (useRemoteList) {
            remoteChannels.map { rc ->
                HubChannelDisplayItem(
                    channelId = rc.channelId,
                    name = rc.name,
                    isPublic = rc.isPublic,
                    isHidden = rc.isHidden,
                    isOwner = rc.isOwner,
                    hasPrivateKey = rc.hasPrivateKey,
                    mediaCount = rc.mediaCount,
                    activePeers = rc.activePeers,
                    messageCount = rc.messageCount,
                    isGeneralChat = rc.channelId == BitVoidChannelManager.DEFAULT_PUBLIC_CHANNEL_ID,
                    spaceKind = rc.spaceKind,
                    myRole = rc.myRole,
                    memberCount = rc.memberCount,
                    subscriberCount = rc.subscriberCount
                )
            }
        } else {
            // Hidden channels remain private, but their creator always sees and enters them
            // directly without typing the private ID again.
            hostChannels
                .filter { channel ->
                    when (channel.spaceKind) {
                        BitVoidSpaceKind.LEGACY_CHAT -> !channel.isHidden ||
                            channel.creatorUsername.equals(myUsername, ignoreCase = true)
                        BitVoidSpaceKind.GROUP,
                        BitVoidSpaceKind.BROADCAST_CHANNEL -> channel.isPublic || channel.members.any {
                            it.username.equals(myUsername, ignoreCase = true)
                        }
                    }
                }
                .map { hc ->
                    val isOwner = hc.creatorUsername.equals(myUsername, ignoreCase = true)
                    HubChannelDisplayItem(
                        channelId = if (hc.isPublic || isOwner) hc.channelId else null,
                        name = hc.name,
                        isPublic = hc.isPublic,
                        isHidden = hc.isHidden,
                        isOwner = isOwner,
                        hasPrivateKey = !hc.privateKey.isNullOrBlank(),
                        mediaCount = hc.mediaList.size,
                        activePeers = hc.activeParticipants.count { it.isWebSocketConnected },
                        messageCount = hc.messageCount,
                        isGeneralChat = hc.channelId == BitVoidChannelManager.DEFAULT_PUBLIC_CHANNEL_ID,
                        spaceKind = hc.spaceKind,
                        myRole = hc.members.firstOrNull {
                            it.username.equals(myUsername, ignoreCase = true)
                        }?.role,
                        memberCount = hc.members.size,
                        subscriberCount = hc.subscriberCount
                    )
                }
        }
    }

    val generalChannels = displayItems.filter { it.isGeneralChat }
    val groupSpaces = displayItems.filter { it.spaceKind == BitVoidSpaceKind.GROUP }
    val broadcastSpaces = displayItems.filter {
        it.spaceKind == BitVoidSpaceKind.BROADCAST_CHANNEL && it.myRole != null
    }
    val publicChannels = displayItems.filter {
        it.spaceKind == BitVoidSpaceKind.LEGACY_CHAT && it.isPublic && !it.isGeneralChat
    }
    val privateChannels = displayItems.filter {
        it.spaceKind == BitVoidSpaceKind.LEGACY_CHAT && !it.isPublic
    }
    val selectablePeers = remember(knownUsers, myUsername) {
        knownUsers.filter { !it.equals(myUsername, ignoreCase = true) }
    }
    val visibleHubLiveClasses = remember(liveClasses, myUsername) {
        liveClasses.filter { lc ->
            !lc.isHidden || lc.creatorUsername.equals(myUsername, ignoreCase = true)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Minimal Hub Header with Top-Right Plus (+) Logo
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "HUB",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "CHANNELS · LIVE CLASS & CALLS",
                    style = MaterialTheme.typography.labelSmall,
                    color = VoidMint
                )
            }

            Surface(
                onClick = { showPlusChooserDialog = true },
                shape = CircleShape,
                color = VoidMintContainer,
                border = BorderStroke(1.5.dp, VoidMint),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("hub_plus_create_channel")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Create Channel or Chat with User",
                        tint = VoidMint,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

        if (isJoiningChannel || isOpeningDirectChat) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = VoidMint
                )
                Text(
                    text = if (isOpeningDirectChat) "Opening direct user chat..." else "Opening channel space...",
                    style = MaterialTheme.typography.labelMedium,
                    color = VoidMint
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {

            item(key = "section_general") {
                HubSectionHeader(title = "GENERAL CHAT", accent = VoidMint)
            }

            if (generalChannels.isEmpty()) {
                item(key = "general_fallback") {
                    HubChannelRow(
                        title = "General Chat",
                        subtitle = "Tap to open chat space",
                        isPublic = true,
                        onClick = { onOpenChannel(BitVoidChannelManager.DEFAULT_PUBLIC_CHANNEL_ID, null) },
                        testTag = "hub_channel_general"
                    )
                }
            } else {
                items(generalChannels, key = { "gen_${it.channelId ?: it.name}" }) { ch ->
                    HubChannelRow(
                        title = ch.name,
                        subtitle = "${ch.mediaCount} media · ${ch.activePeers} live · ${ch.messageCount} msgs",
                        isPublic = true,
                        onClick = {
                            ch.channelId?.let { id -> onOpenChannel(id, null) }
                        },
                        testTag = "hub_channel_${ch.channelId ?: ch.name}"
                    )
                }
            }

            item(key = "section_groups") {
                Spacer(modifier = Modifier.height(6.dp))
                HubSectionHeader(title = "GROUPS", accent = VoidMint)
            }
            if (groupSpaces.isEmpty()) {
                item(key = "empty_groups") {
                    Text(
                        "No groups yet. Tap + to create one with members by username.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(groupSpaces, key = { "group_${it.channelId ?: it.name}" }) { group ->
                    HubChannelRow(
                        title = group.name,
                        subtitle = "${group.memberCount} members · ${group.myRole?.name?.replace('_', ' ') ?: "MEMBER"} · ${group.messageCount} msgs",
                        isPublic = true,
                        onClick = { group.channelId?.let { onOpenChannel(it, null) } },
                        testTag = "hub_group_${group.channelId ?: group.name}"
                    )
                }
            }

            item(key = "section_broadcast_channels") {
                Spacer(modifier = Modifier.height(6.dp))
                HubSectionHeader(title = "CHANNELS", accent = VoidCyan)
            }
            if (broadcastSpaces.isEmpty()) {
                item(key = "empty_broadcast_channels") {
                    Text(
                        "No subscriptions yet. Tap + to create or discover a Channel.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(broadcastSpaces, key = { "broadcast_${it.channelId ?: it.name}" }) { channel ->
                    HubChannelRow(
                        title = channel.name,
                        subtitle = "${channel.subscriberCount} subscribers · ${channel.myRole?.name?.replace('_', ' ') ?: "SUBSCRIBER"} · ${channel.messageCount} posts",
                        isPublic = channel.isPublic,
                        onClick = { channel.channelId?.let { onOpenChannel(it, null) } },
                        testTag = "hub_broadcast_${channel.channelId ?: channel.name}"
                    )
                }
            }

            item(key = "section_public") {
                Spacer(modifier = Modifier.height(6.dp))
                HubSectionHeader(title = "MEDIA CHAT SPACES · PUBLIC", accent = VoidCyan)
            }

            if (publicChannels.isEmpty()) {
                item(key = "empty_public") {
                    Text(
                        text = "No extra public channels yet. Tap + top right to create one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            } else {
                items(publicChannels, key = { "pub_${it.channelId ?: it.name}" }) { ch ->
                    HubChannelRow(
                        title = ch.name,
                        subtitle = "${ch.mediaCount} media · ${ch.activePeers} live · ${ch.messageCount} msgs",
                        isPublic = true,
                        canManage = ch.isOwner,
                        onEdit = { ch.channelId?.let(onEditChannel) },
                        onDelete = { ch.channelId?.let(onDeleteChannel) },
                        onClick = {
                            ch.channelId?.let { id -> onOpenChannel(id, null) }
                        },
                        testTag = "hub_channel_${ch.channelId ?: ch.name}"
                    )
                }
            }

            item(key = "section_private") {
                Spacer(modifier = Modifier.height(6.dp))
                HubSectionHeader(title = "MEDIA CHAT SPACES · PRIVATE", accent = VoidAmber)
            }

            if (privateChannels.isEmpty()) {
                item(key = "empty_private") {
                    Text(
                        text = "No private channels active. Tap + to create one or enter a Private Channel ID.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            } else {
                items(privateChannels, key = { "prv_${it.channelId ?: it.name}_${it.mediaCount}" }) { ch ->
                    HubChannelRow(
                        title = ch.name,
                        subtitle = if (ch.isOwner) {
                            if (ch.isHidden) {
                                "YOUR HIDDEN CHANNEL · Tap to open (${ch.mediaCount} media)"
                            } else {
                                "YOUR PRIVATE CHANNEL · Tap to open (${ch.mediaCount} media)"
                            }
                        } else {
                            "Locked · Tap & enter Private ID (${ch.mediaCount} media)"
                        },
                        isPublic = false,
                        channelId = ch.channelId,
                        canManage = ch.isOwner,
                        onCopyChannelId = { id ->
                            onCopyChannelId(
                                if (ch.isHidden) "Hidden Channel ID" else "Private Channel ID",
                                id
                            )
                        },
                        onEdit = { ch.channelId?.let(onEditChannel) },
                        onDelete = { ch.channelId?.let(onDeleteChannel) },
                        onClick = {
                            if (ch.isOwner && ch.channelId != null) {
                                onOpenChannel(ch.channelId, null)
                            } else {
                                targetPrivateChannelName = ch.name
                                privateIdInput = ""
                                privateKeyInput = ""
                                localDialogError = null
                                showPrivateUnlockDialog = true
                            }
                        },
                        testTag = "hub_private_${ch.channelId ?: ch.name}"
                    )
                }
            }

            item(key = "section_live_classes") {
                Spacer(modifier = Modifier.height(6.dp))
                HubSectionHeader(title = "LIVE CLASSES", accent = VoidCyan)
            }

            items(visibleHubLiveClasses, key = { "lc_${it.roomId}" }) { liveClass ->
                val isCreator = liveClass.creatorUsername.equals(myUsername, ignoreCase = true)
                HubLiveClassRow(
                    liveClass = liveClass,
                    isCreator = isCreator,
                    onClick = { onOpenListedLiveClass(liveClass) },
                    testTag = "hub_live_class_${liveClass.roomId}"
                )
            }

        }
    }

    if (showPlusChooserDialog) {
        AlertDialog(
            onDismissRequest = { showPlusChooserDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = VoidMint,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("hub_plus_chooser_dialog"),
            title = {
                Text(
                    text = "NEW ACTION",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HubQuickActionCard(
                        icon = Icons.Filled.GroupAdd,
                        title = "Create Group",
                        subtitle = "Start a role-managed group by username",
                        accent = VoidMint,
                        onClick = {
                            showPlusChooserDialog = false
                            onOpenCreateGroup()
                        }
                    )
                    HubQuickActionCard(
                        icon = Icons.Filled.Campaign,
                        title = "Create Broadcast Channel",
                        subtitle = "Public or private · administrators publish",
                        accent = VoidCyan,
                        onClick = {
                            showPlusChooserDialog = false
                            onOpenCreateBroadcastChannel()
                        }
                    )
                    HubQuickActionCard(
                        icon = Icons.Filled.Search,
                        title = "Discover Channels",
                        subtitle = "Search public channels or enter an invitation",
                        accent = VoidAmber,
                        onClick = {
                            showPlusChooserDialog = false
                            onOpenDiscoverChannels()
                        }
                    )
                    HubQuickActionCard(
                        icon = Icons.Filled.VpnKey,
                        title = "Enter Private Channel ID",
                        subtitle = "Unlock a Private or Hidden media space",
                        accent = VoidAmber,
                        onClick = {
                            showPlusChooserDialog = false
                            targetPrivateChannelName = null
                            privateIdInput = ""
                            privateKeyInput = ""
                            localDialogError = null
                            showPrivateUnlockDialog = true
                        }
                    )
                    HubQuickActionCard(
                        icon = Icons.Filled.School,
                        title = "Join Live Class",
                        subtitle = "Enter a CLS- ID from the connected server",
                        accent = VoidCyan,
                        onClick = {
                            showPlusChooserDialog = false
                            callActions.onClearCallError()
                            showJoinClassByIdDialog = true
                        }
                    )
                    Card(
                        onClick = {
                            showPlusChooserDialog = false
                            onOpenCreateChannel()
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                        border = BorderStroke(1.dp, VoidMint.copy(alpha = 0.65f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("hub_plus_option_create_channel")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(VoidMintContainer)
                                    .border(1.dp, VoidMint, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Forum,
                                    contentDescription = null,
                                    tint = VoidMint,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Create Media Chat Space",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Legacy public/private space with assigned vault media",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = VoidMint
                            )
                        }
                    }

                    Card(
                        onClick = {
                            showPlusChooserDialog = false
                            callActions.onClearCallError()
                            showStartLiveClassDialog = true
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                        border = BorderStroke(1.dp, VoidCyan.copy(alpha = 0.65f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("hub_plus_option_start_live_class")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(VoidCyan.copy(alpha = 0.14f))
                                    .border(1.dp, VoidCyan, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.School,
                                    contentDescription = null,
                                    tint = VoidCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Start Live Class",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Name class, Show/Hidden in Hub & share CLS- ID",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = VoidCyan
                            )
                        }
                    }

                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showPlusChooserDialog = false },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("CLOSE", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    if (showDirectChatDialog) {
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = VoidCyan,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedLabelColor = VoidCyan,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            cursorColor = VoidCyan
        )

        AlertDialog(
            onDismissRequest = { showDirectChatDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = VoidCyan,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("direct_chat_dialog"),
            title = {
                Text(
                    text = if (directDialogForCall) "VOICE CALL USER" else "CHAT WITH USER",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "YOUR USERNAME ID (FIXED)",
                                style = MaterialTheme.typography.labelSmall,
                                color = VoidCyan
                            )
                            Text(
                                text = "@$myUsername",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    OutlinedTextField(
                        value = targetUsernameInput,
                        onValueChange = {
                            targetUsernameInput = it
                            localDmError = null
                            onClearDirectChatError()
                        },
                        label = { Text("Enter Peer Username ID (e.g. user-4210)") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("dm_target_username_input")
                    )

                    if (selectablePeers.isNotEmpty()) {
                        Text(
                            text = "RECENT PEERS ON NODE",
                            style = MaterialTheme.typography.labelSmall,
                            color = VoidMint
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            selectablePeers.forEach { peer ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .background(MaterialTheme.colorScheme.background)
                                        .border(1.dp, VoidCyan.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
                                        .clickable {
                                            targetUsernameInput = peer
                                            localDmError = null
                                            onClearDirectChatError()
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = "@$peer",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VoidCyan
                                    )
                                }
                            }
                        }
                    }

                    (localDmError ?: directChatError)?.let { err ->
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
                        val trimmed = targetUsernameInput.trim().removePrefix("@")
                        if (trimmed.isEmpty()) {
                            localDmError = "Enter a peer Username ID."
                        } else if (trimmed.equals(myUsername, ignoreCase = true)) {
                            localDmError = "Enter a different user's Username ID."
                        } else {
                            localDmError = null
                            pendingAutoVoiceCall = directDialogForCall
                            onOpenDirectChat(trimmed)
                        }
                    },
                    enabled = !isOpeningDirectChat,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VoidMint,
                        contentColor = MaterialTheme.colorScheme.background
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("dm_start_chat_submit")
                ) {
                    Text(
                        text = when {
                            isOpeningDirectChat -> "OPENING..."
                            directDialogForCall -> "START VOICE CALL"
                            else -> "START CHAT"
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDirectChatDialog = false },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("CANCEL", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }

    if (showStartLiveClassDialog) {
        StartLiveClassDialog(
            isStarting = isStartingLiveClass,
            errorMessage = callUiState.callError ?: channelJoinError,
            onDismiss = {
                showStartLiveClassDialog = false
                isStartingLiveClass = false
                callActions.onClearCallError()
            },
            onSubmit = { className, isHidden ->
                isStartingLiveClass = true
                onStartLiveClass(className, isHidden) {
                    isStartingLiveClass = false
                }
            }
        )
    }

    if (showJoinClassByIdDialog) {
        JoinLiveClassByIdDialog(
            isJoining = isJoiningClassByCode,
            errorMessage = callUiState.callError ?: channelJoinError,
            onDismiss = {
                showJoinClassByIdDialog = false
                isJoiningClassByCode = false
                callActions.onClearCallError()
            },
            onSubmit = { classCode ->
                isJoiningClassByCode = true
                callActions.onJoinLiveClassWithCode(classCode) {
                    isJoiningClassByCode = false
                }
            }
        )
    }

    if (showPrivateUnlockDialog) {
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = VoidAmber,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            focusedLabelColor = VoidAmber,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            cursorColor = VoidAmber
        )

        AlertDialog(
            onDismissRequest = { showPrivateUnlockDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = VoidAmber,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("private_id_prompt_dialog"),
            title = {
                Text(
                    text = targetPrivateChannelName?.let { "UNLOCK // $it" }
                        ?: "ENTER PRIVATE CHANNEL ID",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Enter the Private Channel ID (and optional key) to open its chat space.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = privateIdInput,
                        onValueChange = {
                            privateIdInput = it
                            localDialogError = null
                        },
                        label = { Text("Private Channel ID") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("hub_private_id_input")
                    )
                    OutlinedTextField(
                        value = privateKeyInput,
                        onValueChange = {
                            privateKeyInput = it
                            localDialogError = null
                        },
                        label = { Text("Optional Private Key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        colors = fieldColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("hub_private_key_input")
                    )
                    (localDialogError ?: channelJoinError)?.let { err ->
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
                        val trimmed = privateIdInput.trim()
                        if (trimmed.isEmpty()) {
                            localDialogError = "Please enter the Private Channel ID."
                        } else {
                            localDialogError = null
                            onOpenChannel(trimmed, privateKeyInput.trim().ifBlank { null })
                        }
                    },
                    enabled = !isJoiningChannel,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VoidMint,
                        contentColor = MaterialTheme.colorScheme.background
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("hub_private_unlock_submit")
                ) {
                    Text(
                        text = if (isJoiningChannel) "OPENING..." else "OPEN CHAT SPACE",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showPrivateUnlockDialog = false },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("CANCEL", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

@Composable
private fun HubQuickActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f))
                    .border(1.dp, accent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = accent)
        }
    }
}

@Composable
private fun HubSectionHeader(title: String, accent: Color) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = accent,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun HubLiveClassRow(
    liveClass: RemoteLiveClassSummary,
    isCreator: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val accent = if (liveClass.isHidden) VoidAmber else VoidCyan
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.65f)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f))
                    .border(1.dp, accent.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.School,
                    contentDescription = "Open Live Class ${liveClass.name}",
                    tint = accent,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = liveClass.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildString {
                        if (liveClass.isHidden && isCreator) {
                            append("HIDDEN · ONLY YOU SEE")
                        } else if (isCreator) {
                            append("YOUR LIVE CLASS")
                        } else {
                            append("LIVE ON SAME NODE")
                        }
                        if (liveClass.classCode.isNotBlank() && isCreator) {
                            append(" · ID: ${liveClass.classCode}")
                        }
                        append(" · ${liveClass.participantCount} live")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "Enter live class",
                tint = accent
            )
        }
    }
}

@Composable
private fun HubChannelRow(
    title: String,
    subtitle: String,
    isPublic: Boolean,
    channelId: String? = null,
    canManage: Boolean = false,
    onCopyChannelId: (String) -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onClick: () -> Unit,
    testTag: String
) {
    val accent = if (isPublic) VoidMint else VoidAmber
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f))
                    .border(1.dp, accent.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (isPublic) {
                    Text(
                        text = "#",
                        style = MaterialTheme.typography.titleMedium,
                        color = VoidMint,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Private channel",
                        tint = VoidAmber,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (canManage) {
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("${testTag}_more")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = "Options for $title",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        if (!isPublic && !channelId.isNullOrBlank()) {
                            DropdownMenuItem(
                                text = { Text("Copy channel ID", color = MaterialTheme.colorScheme.onSurface) },
                                leadingIcon = {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = VoidMint)
                                },
                                onClick = {
                                    menuExpanded = false
                                    channelId?.let(onCopyChannelId)
                                },
                                modifier = Modifier.testTag("${testTag}_copy_id")
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        DropdownMenuItem(
                            text = { Text("Edit channel", color = MaterialTheme.colorScheme.onSurface) },
                            leadingIcon = {
                                Icon(Icons.Filled.Edit, contentDescription = null, tint = VoidCyan)
                            },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                            modifier = Modifier.testTag("${testTag}_edit")
                        )
                        DropdownMenuItem(
                            text = { Text("Delete channel", color = VoidCrimson) },
                            leadingIcon = {
                                Icon(Icons.Filled.Delete, contentDescription = null, tint = VoidCrimson)
                            },
                            onClick = {
                                menuExpanded = false
                                confirmDelete = true
                            },
                            modifier = Modifier.testTag("${testTag}_delete")
                        )
                    }
                }
            } else {
                Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = "Open channel",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("DELETE CHANNEL?", color = VoidCrimson) },
            text = {
                Text(
                    "Delete '$title' and close it for every participant? This cannot be undone.",
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VoidCrimson,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) { Text("DELETE") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("CANCEL", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        )
    }
}

@Composable
private fun HubDirectChatRow(
    peerUsername: String,
    subtitle: String,
    onClick: () -> Unit,
    testTag: String
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, VoidMint.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(VoidCyan.copy(alpha = 0.14f))
                    .border(1.dp, VoidCyan.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "@",
                    style = MaterialTheme.typography.titleMedium,
                    color = VoidCyan,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "@$peerUsername",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "Open direct chat with @$peerUsername",
                tint = VoidCyan
            )
        }
    }
}

@Composable
private fun WhatsAppDirectChatSpace(
    session: ClientDirectSession,
    messages: List<ClientChatMessage>,
    wsStatus: ClientWsStatus,
    isUploadingFile: Boolean,
    pendingAutoVoiceCall: Boolean = false,
    onConsumePendingAutoCall: () -> Unit = {},
    callUiState: BitVoidCallUiState = BitVoidCallUiState(),
    callActions: BitVoidCallActions = BitVoidCallActions(),
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onUploadFile: (Uri, String) -> Unit,
    onBuildAttachmentUrl: (String, String?) -> String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var draft by rememberSaveable(session.dmId) { mutableStateOf("") }
    val listState = rememberLazyListState()
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onUploadFile(uri, draft)
            draft = ""
        }
    }

    var pendingCallAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val callPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        val action = pendingCallAction
        pendingCallAction = null
        if (granted) {
            action?.invoke()
        } else {
            android.widget.Toast.makeText(
                context,
                "Microphone access is required for BitVoid audio calls. Allow it in Android app permissions, then try again.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    fun launchDirectVoiceCallWithPermission(accepting: Boolean = false) {
        val hasAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val startCallAction = {
            if (accepting) {
                callActions.onAcceptDirectCall(
                    session.dmId,
                    session.peerUsername,
                    session.token
                )
            } else {
                callActions.onStartDirectCall(
                    session.dmId,
                    session.peerUsername,
                    session.token
                )
            }
        }
        if (hasAudio) {
            startCallAction()
        } else {
            pendingCallAction = startCallAction
            callPermLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    LaunchedEffect(session.dmId, pendingAutoVoiceCall) {
        if (pendingAutoVoiceCall) {
            onConsumePendingAutoCall()
            launchDirectVoiceCallWithPermission()
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val isInDirectCall = callUiState.isInRoom(session.dmId)
    val remoteCallStatus = callUiState.activeDirectCallStatus?.takeIf { it.roomId == session.dmId }
    val showAudioCallScreen = isInDirectCall ||
        remoteCallStatus?.directCallPhase == DirectCallPhase.RINGING ||
        remoteCallStatus?.directCallPhase == DirectCallPhase.ACCEPTED

    if (showAudioCallScreen) {
        BitVoidAudioCallScreen(
            peerUsername = session.peerUsername,
            callUiState = callUiState,
            remoteStatus = remoteCallStatus,
            onAnswer = { launchDirectVoiceCallWithPermission(accepting = true) },
            onReject = { callActions.onDeclineDirectCall(session.dmId, session.token) },
            onCancel = { callActions.onCancelDirectCall(session.dmId, session.token) },
            onToggleMute = callActions.onToggleMicMute,
            onToggleSpeaker = callActions.onToggleSpeakerphone,
            onEndCall = callActions.onLeaveSession,
            modifier = modifier
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("hub_dm_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Hub",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(VoidCyan.copy(alpha = 0.14f))
                    .border(1.dp, VoidCyan, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "@",
                    style = MaterialTheme.typography.titleMedium,
                    color = VoidCyan,
                    fontWeight = FontWeight.Bold
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "@${session.peerUsername}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append("DIRECT CHAT · ")
                        append(if (wsStatus == ClientWsStatus.LIVE) "WS LIVE" else wsStatus.name)
                        append(" · you: @${session.myUsername}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (wsStatus == ClientWsStatus.LIVE) VoidMint else VoidCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            IconButton(
                onClick = { launchDirectVoiceCallWithPermission() },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("dm_voice_call_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Call,
                    contentDescription = "Start voice call with @${session.peerUsername}",
                    tint = if (isInDirectCall) VoidMint else VoidCyan
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

        if (isInDirectCall) {
            DirectCallStagePanel(
                peerUsername = session.peerUsername,
                callUiState = callUiState,
                callActions = callActions
            )
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        } else if (remoteCallStatus?.directCallPhase == DirectCallPhase.RINGING &&
            remoteCallStatus.isIncoming
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF10201A)),
                border = BorderStroke(1.dp, VoidMint),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("dm_incoming_call_prompt")
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "INCOMING VOICE CALL",
                        style = MaterialTheme.typography.titleMedium,
                        color = VoidMint,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "@${remoteCallStatus.callerUsername} is calling. Media will not connect until you accept.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = {
                                callActions.onDeclineDirectCall(session.dmId, session.token)
                            },
                            border = BorderStroke(1.dp, VoidCrimson),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidCrimson),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("dm_decline_call_button")
                        ) { Text("DECLINE") }
                        Button(
                            onClick = {
                                launchDirectVoiceCallWithPermission(accepting = true)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = VoidMint,
                                contentColor = MaterialTheme.colorScheme.background
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("dm_accept_call_button")
                        ) { Text("ACCEPT") }
                    }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        } else if (remoteCallStatus?.directCallPhase == DirectCallPhase.RINGING &&
            remoteCallStatus.isOutgoing
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101A20)),
                border = BorderStroke(1.dp, VoidCyan.copy(alpha = 0.8f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("dm_outgoing_call_ringing")
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(color = VoidCyan, modifier = Modifier.size(24.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("RINGING @${session.peerUsername}…", color = VoidCyan, fontWeight = FontWeight.Bold)
                        Text(
                            "Voice call invitation",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    TextButton(
                        onClick = { callActions.onCancelDirectCall(session.dmId, session.token) },
                        modifier = Modifier.testTag("dm_cancel_call_button")
                    ) { Text("CANCEL", color = VoidCrimson) }
                }
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        } else if (remoteCallStatus?.directCallPhase == DirectCallPhase.ACCEPTED) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0E1B18)),
                border = BorderStroke(1.dp, VoidMint.copy(alpha = 0.7f)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("dm_call_connecting_banner")
            ) {
                Text(
                    text = "CALL ACCEPTED · CONNECTING SECURE MEDIA…",
                    color = VoidMint,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(14.dp)
                )
            }
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "dm_welcome_pill") {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Direct 1-on-1 chat with @${session.peerUsername} (you: @${session.myUsername})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (messages.isEmpty()) {
                item(key = "dm_empty_messages") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No messages yet. Say hello to @${session.peerUsername}.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(messages, key = { it.id }) { msg ->
                    WhatsAppMessageBubble(
                        message = msg,
                        isMine = msg.sender.equals(session.myUsername, ignoreCase = true),
                        onOpenAttachment = { att ->
                            val rawTarget = att.downloadUrl.ifBlank { att.streamUrl }
                            val fullUrl = onBuildAttachmentUrl(rawTarget, session.token)
                            openAttachmentExternally(context, fullUrl)
                        }
                    )
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = {
                    if (!isUploadingFile) {
                        filePickerLauncher.launch(arrayOf("*/*"))
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(1.dp, if (isUploadingFile) VoidMint else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("hub_dm_attach_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isUploadingFile) {
                        CircularProgressIndicator(
                            color = VoidMint,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Upload any file",
                            tint = VoidMint,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = {
                    Text(
                        text = "Message @${session.peerUsername}...",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VoidMint,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.background,
                    unfocusedContainerColor = MaterialTheme.colorScheme.background,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = VoidMint
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("hub_dm_input")
            )
            Surface(
                onClick = {
                    val trimmed = draft.trim()
                    if (trimmed.isNotEmpty()) {
                        onSendMessage(trimmed)
                        draft = ""
                    } else {
                        launchDirectVoiceCallWithPermission()
                    }
                },
                shape = CircleShape,
                color = VoidMint,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("hub_dm_send_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (draft.isBlank()) {
                            Icons.Filled.Mic
                        } else {
                            Icons.AutoMirrored.Filled.Send
                        },
                        contentDescription = if (draft.isBlank()) {
                            "Start audio call"
                        } else {
                            "Send direct message"
                        },
                        tint = MaterialTheme.colorScheme.background,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WhatsAppChannelChatSpace(
    session: ClientChannelSession,
    messages: List<ClientChatMessage>,
    wsStatus: ClientWsStatus,
    peersCount: Int,
    isUploadingFile: Boolean,
    onBack: () -> Unit,
    onOpenInfo: () -> Unit,
    onSendMessage: (String) -> Unit,
    onUploadFile: (Uri, String) -> Unit,
    onBuildStreamUrl: (RemoteMediaItem, String?) -> String,
    onBuildAttachmentUrl: (String, String?) -> String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var draft by rememberSaveable(session.channelId) { mutableStateOf("") }
    var showMediaDrawer by rememberSaveable(session.channelId) { mutableStateOf(false) }
    var activePlaybackItem by remember(session.channelId) { mutableStateOf<RemoteMediaItem?>(null) }
    val listState = rememberLazyListState()
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onUploadFile(uri, draft)
            draft = ""
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val accent = if (session.isPublic) VoidMint else VoidAmber

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // WhatsApp-style Top Channel Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("hub_chat_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Hub channels",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }

            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.14f))
                    .border(1.dp, accent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (!session.avatarDataUrl.isNullOrBlank() && session.spaceKind != BitVoidSpaceKind.LEGACY_CHAT) {
                    SpaceAvatar(
                        dataUrl = session.avatarDataUrl,
                        fallback = if (session.spaceKind == BitVoidSpaceKind.GROUP) Icons.Filled.Group else Icons.Filled.Campaign,
                        modifier = Modifier.fillMaxSize()
                    )
                } else when (session.spaceKind) {
                    BitVoidSpaceKind.GROUP -> Icon(
                        Icons.Filled.Group,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                    BitVoidSpaceKind.BROADCAST_CHANNEL -> Icon(
                        Icons.Filled.Campaign,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(20.dp)
                    )
                    BitVoidSpaceKind.LEGACY_CHAT -> if (session.isPublic) {
                        Text(
                            text = "#",
                            style = MaterialTheme.typography.titleMedium,
                            color = VoidMint,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = VoidAmber,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        when (session.spaceKind) {
                            BitVoidSpaceKind.GROUP -> append("${session.memberCount} MEMBERS")
                            BitVoidSpaceKind.BROADCAST_CHANNEL -> append("${session.subscriberCount} SUBSCRIBERS")
                            BitVoidSpaceKind.LEGACY_CHAT -> append(if (session.isPublic) "PUBLIC" else "PRIVATE")
                        }
                        session.role?.let { append(" · ${it.name.replace('_', ' ')}") }
                        append(" · ")
                        append(if (wsStatus == ClientWsStatus.LIVE) "LIVE" else wsStatus.name)
                        if (session.spaceKind != BitVoidSpaceKind.BROADCAST_CHANNEL) append(" · $peersCount peer(s)")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (wsStatus == ClientWsStatus.LIVE) VoidMint else VoidCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (session.spaceKind != BitVoidSpaceKind.LEGACY_CHAT) {
                IconButton(
                    onClick = onOpenInfo,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("space_info_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = if (session.spaceKind == BitVoidSpaceKind.GROUP) "Group information" else "Channel information",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            IconButton(
                onClick = { showMediaDrawer = !showMediaDrawer },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("hub_chat_media_toggle")
            ) {
                Icon(
                    imageVector = Icons.Filled.VideoLibrary,
                    contentDescription = "Toggle channel media (${session.mediaList.size})",
                    tint = if (showMediaDrawer || session.mediaList.isNotEmpty()) VoidMint else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

        // Collapsible Channel Media Drawer with Inline ExoPlayer
        AnimatedVisibility(visible = showMediaDrawer || activePlaybackItem != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                activePlaybackItem?.let { item ->
                    InlineExoPlayerCard(
                        item = item,
                        streamUrl = onBuildStreamUrl(item, session.token),
                        onClose = { activePlaybackItem = null }
                    )
                }

                if (showMediaDrawer) {
                    Text(
                        text = "CHANNEL MEDIA (${session.mediaList.size})",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidCyan,
                        fontWeight = FontWeight.Bold
                    )
                    if (session.mediaList.isEmpty()) {
                        Text(
                            text = "No videos or files assigned to this channel yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            session.mediaList.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MaterialTheme.colorScheme.background)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                                        .clickable { activePlaybackItem = item }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = item.badge,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = VoidMint,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = "Play ${item.title}",
                                        tint = VoidMint
                                    )
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        // WhatsApp-style Message Stream
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item(key = "wa_welcome_pill") {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = when (session.spaceKind) {
                                BitVoidSpaceKind.GROUP -> "${session.name} · Group conversation (you: ${session.callsign})"
                                BitVoidSpaceKind.BROADCAST_CHANNEL -> "${session.name} · ${if (session.isPublic) "Public" else "Private"} broadcast channel"
                                BitVoidSpaceKind.LEGACY_CHAT -> if (session.isPublic) {
                                    "${session.name} · Public channel chat space (you: ${session.callsign})"
                                } else {
                                    "${session.name} · Private ID-locked chat space (you: ${session.callsign})"
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (messages.isEmpty()) {
                item(key = "wa_empty_messages") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (session.canPublish) {
                                "No messages yet. Start the conversation."
                            } else {
                                "No posts yet. New posts from channel administrators will appear here."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(messages, key = { it.id }) { msg ->
                    WhatsAppMessageBubble(
                        message = msg,
                        isMine = msg.sender == session.callsign,
                        onOpenAttachment = { att ->
                            // Sent files stay as compact metadata cards. Opening/downloading is
                            // always an explicit external action; chat never expands file content.
                            val rawTarget = att.downloadUrl.ifBlank { att.streamUrl }
                            openAttachmentExternally(
                                context,
                                onBuildAttachmentUrl(rawTarget, session.token)
                            )
                        }
                    )
                }
            }
        }

        // Sticky Bottom Composer. Broadcast subscribers intentionally receive a read-only view;
        // the same rule is enforced again by the repository and server.
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        if (session.canPublish) {
            Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = {
                    if (!isUploadingFile) {
                        filePickerLauncher.launch(arrayOf("*/*"))
                    }
                },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.background,
                border = BorderStroke(1.dp, if (isUploadingFile) VoidMint else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .size(48.dp)
                    .testTag("hub_chat_attach_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isUploadingFile) {
                        CircularProgressIndicator(
                            color = VoidMint,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = "Upload any file",
                            tint = VoidMint,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = {
                    Text(
                        text = "Message ${session.name}...",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VoidMint,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.background,
                    unfocusedContainerColor = MaterialTheme.colorScheme.background,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = VoidMint
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("hub_chat_input")
            )
            Surface(
                onClick = {
                    val trimmed = draft.trim()
                    if (trimmed.isNotEmpty()) {
                        onSendMessage(trimmed)
                        draft = ""
                    }
                },
                shape = CircleShape,
                color = VoidMint,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("hub_chat_send_button")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send message",
                        tint = MaterialTheme.colorScheme.background,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Filled.Campaign, contentDescription = null, tint = VoidCyan)
                Text(
                    "Only the channel owner and administrators can publish.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WhatsAppMessageBubble(
    message: ClientChatMessage,
    isMine: Boolean,
    onOpenAttachment: (ClientChatAttachment) -> Unit = {}
) {
    if (message.isSystemNotice) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(VoidCyan.copy(alpha = 0.1f))
                    .border(1.dp, VoidCyan.copy(alpha = 0.35f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.labelSmall,
                    color = VoidCyan
                )
            }
        }
        return
    }

    val bubbleShape = if (isMine) {
        RoundedCornerShape(topStart = 14.dp, topEnd = 4.dp, bottomStart = 14.dp, bottomEnd = 14.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomStart = 14.dp, bottomEnd = 14.dp)
    }
    val bubbleBg = if (isMine) Color(0xFF0C3325) else MaterialTheme.colorScheme.surface
    val bubbleBorder = if (isMine) VoidMint.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 310.dp)
                .clip(bubbleShape)
                .background(bubbleBg)
                .border(1.dp, bubbleBorder, bubbleShape)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (!isMine) {
                Text(
                    text = message.sender,
                    style = MaterialTheme.typography.labelSmall,
                    color = VoidCyan,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
            }
            message.attachment?.let { att ->
                val extBadge = att.name.substringAfterLast('.', "")
                    .uppercase(Locale.US)
                    .take(5)
                    .ifBlank { "FILE" }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.85f))
                        .border(1.dp, VoidCyan.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .clickable { onOpenAttachment(att) }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("chat_attachment_card"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(VoidMint.copy(alpha = 0.16f))
                            .border(1.dp, VoidMint.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = extBadge,
                            style = MaterialTheme.typography.labelSmall,
                            color = VoidMint,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = att.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = formatAttachmentSize(att.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = "Open or download ${att.name}",
                        tint = VoidCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (message.text.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
            if (message.text.isNotBlank()) {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = message.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isMine) {
                    Text(
                        text = when (message.deliveryState) {
                            MessageDeliveryState.PENDING -> "◷"
                            MessageDeliveryState.SENT -> "✓"
                            MessageDeliveryState.DELIVERED -> "✓✓"
                            MessageDeliveryState.READ -> "✓✓"
                            MessageDeliveryState.FAILED -> "!"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = when (message.deliveryState) {
                            MessageDeliveryState.READ -> VoidCyan
                            MessageDeliveryState.FAILED -> VoidCrimson
                            MessageDeliveryState.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> VoidMint
                        },
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

private fun formatAttachmentSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

private fun openAttachmentExternally(context: android.content.Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}
