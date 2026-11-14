package com.bitvoid.network.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitvoid.network.channel.BitVoidChannel
import com.bitvoid.network.channel.BitVoidChannelManager
import com.bitvoid.network.client.ClientChannelSession
import com.bitvoid.network.client.ClientChatMessage
import com.bitvoid.network.client.ClientDirectSession
import com.bitvoid.network.client.ClientWsStatus
import com.bitvoid.network.client.RemoteChannelSummary
import com.bitvoid.network.client.RemoteMediaItem
import com.bitvoid.network.direct.DirectConversationSummary
import com.bitvoid.network.model.ClientConnectionStatus
import com.bitvoid.network.model.ClientState
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

private data class HubChannelDisplayItem(
    val publicChannelId: String?,
    val name: String,
    val isPublic: Boolean,
    val hasPrivateKey: Boolean,
    val mediaCount: Int,
    val activePeers: Int,
    val messageCount: Int,
    val isGeneralChat: Boolean
)

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
    activeDirectSession: ClientDirectSession?,
    directMessages: List<ClientChatMessage>,
    directWsStatus: ClientWsStatus,
    isOpeningDirectChat: Boolean,
    directChatError: String?,
    onOpenCreateChannel: () -> Unit,
    onOpenChannel: (channelId: String, privateKey: String?) -> Unit,
    onSendChatMessage: (String) -> Unit,
    onLeaveChannel: () -> Unit,
    onOpenDirectChat: (targetUsername: String) -> Unit,
    onSendDirectMessage: (String) -> Unit,
    onLeaveDirectChat: () -> Unit,
    onClearDirectChatError: () -> Unit,
    onCopyUsername: (String, String) -> Unit,
    onBuildStreamUrl: (RemoteMediaItem, String?) -> String,
    modifier: Modifier = Modifier
) {
    if (activeDirectSession != null) {
        BackHandler { onLeaveDirectChat() }
        WhatsAppDirectChatSpace(
            session = activeDirectSession,
            messages = directMessages,
            wsStatus = directWsStatus,
            onBack = onLeaveDirectChat,
            onSendMessage = onSendDirectMessage,
            modifier = modifier
        )
        return
    }

    if (activeChannelSession != null) {
        BackHandler { onLeaveChannel() }
        WhatsAppChannelChatSpace(
            session = activeChannelSession,
            messages = channelMessages,
            wsStatus = wsStatus,
            peersCount = channelPeersCount,
            onBack = onLeaveChannel,
            onSendMessage = onSendChatMessage,
            onBuildStreamUrl = onBuildStreamUrl,
            modifier = modifier
        )
        return
    }

    var showPlusChooserDialog by rememberSaveable { mutableStateOf(false) }
    var showDirectChatDialog by rememberSaveable { mutableStateOf(false) }
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

    val useRemoteList = clientState.connectionStatus == ClientConnectionStatus.CONNECTED &&
        remoteChannels.isNotEmpty()

    val displayItems = remember(useRemoteList, remoteChannels, hostChannels) {
        if (useRemoteList) {
            remoteChannels.map { rc ->
                HubChannelDisplayItem(
                    publicChannelId = rc.channelId,
                    name = rc.name,
                    isPublic = rc.isPublic,
                    hasPrivateKey = rc.hasPrivateKey,
                    mediaCount = rc.mediaCount,
                    activePeers = rc.activePeers,
                    messageCount = rc.messageCount,
                    isGeneralChat = rc.channelId == BitVoidChannelManager.DEFAULT_PUBLIC_CHANNEL_ID
                )
            }
        } else {
            // Hidden channels (isHidden == true) are deliberately omitted from the Hub list;
            // they can only be entered by typing their Private Channel ID.
            hostChannels
                .filter { !it.isHidden }
                .map { hc ->
                    HubChannelDisplayItem(
                        publicChannelId = if (hc.isPublic) hc.channelId else null,
                        name = hc.name,
                        isPublic = hc.isPublic,
                        hasPrivateKey = !hc.privateKey.isNullOrBlank(),
                        mediaCount = hc.mediaList.size,
                        activePeers = hc.activeParticipants.count { it.isWebSocketConnected },
                        messageCount = hc.messageCount,
                        isGeneralChat = hc.channelId == BitVoidChannelManager.DEFAULT_PUBLIC_CHANNEL_ID
                    )
                }
        }
    }

    val generalChannels = displayItems.filter { it.isGeneralChat }
    val publicChannels = displayItems.filter { it.isPublic && !it.isGeneralChat }
    val privateChannels = displayItems.filter { !it.isPublic }
    val selectablePeers = remember(knownUsers, myUsername) {
        knownUsers.filter { !it.equals(myUsername, ignoreCase = true) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VoidObsidian)
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
                    color = VoidTextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "GENERAL · PUBLIC · PRIVATE CHANNELS",
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

        HorizontalDivider(thickness = 1.dp, color = VoidBorder)

        if (isJoiningChannel || isOpeningDirectChat) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(VoidSurface)
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

            if (directConversations.isNotEmpty()) {
                item(key = "section_direct_chats") {
                    Spacer(modifier = Modifier.height(4.dp))
                    HubSectionHeader(title = "DIRECT USER CHATS", accent = VoidMint)
                }
                items(directConversations, key = { "dm_${it.dmId}" }) { dm ->
                    HubDirectChatRow(
                        peerUsername = dm.peerUsername,
                        subtitle = dm.lastMessagePreview
                            ?.let { "${dm.messageCount} msgs · $it" }
                            ?: "Tap to continue direct chat",
                        onClick = { onOpenDirectChat(dm.peerUsername) },
                        testTag = "hub_dm_row_${dm.peerUsername}"
                    )
                }
            }
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
                items(generalChannels, key = { "gen_${it.publicChannelId ?: it.name}" }) { ch ->
                    HubChannelRow(
                        title = ch.name,
                        subtitle = "${ch.mediaCount} media · ${ch.activePeers} live · ${ch.messageCount} msgs",
                        isPublic = true,
                        onClick = {
                            ch.publicChannelId?.let { id -> onOpenChannel(id, null) }
                        },
                        testTag = "hub_channel_${ch.publicChannelId ?: ch.name}"
                    )
                }
            }

            item(key = "section_public") {
                Spacer(modifier = Modifier.height(6.dp))
                HubSectionHeader(title = "PUBLIC", accent = VoidCyan)
            }

            if (publicChannels.isEmpty()) {
                item(key = "empty_public") {
                    Text(
                        text = "No extra public channels yet. Tap + top right to create one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VoidTextSecondary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            } else {
                items(publicChannels, key = { "pub_${it.publicChannelId ?: it.name}" }) { ch ->
                    HubChannelRow(
                        title = ch.name,
                        subtitle = "${ch.mediaCount} media · ${ch.activePeers} live · ${ch.messageCount} msgs",
                        isPublic = true,
                        onClick = {
                            ch.publicChannelId?.let { id -> onOpenChannel(id, null) }
                        },
                        testTag = "hub_channel_${ch.publicChannelId ?: ch.name}"
                    )
                }
            }

            item(key = "section_private") {
                Spacer(modifier = Modifier.height(6.dp))
                HubSectionHeader(title = "PRIVATE", accent = VoidAmber)
            }

            if (privateChannels.isEmpty()) {
                item(key = "empty_private") {
                    Text(
                        text = "No private channels active. Tap + top right to create a Private Channel or enter a Private ID below.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VoidTextSecondary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            } else {
                items(privateChannels, key = { "prv_${it.name}_${it.mediaCount}" }) { ch ->
                    HubChannelRow(
                        title = ch.name,
                        subtitle = "Locked · Tap & enter Private ID (${ch.mediaCount} media)",
                        isPublic = false,
                        onClick = {
                            targetPrivateChannelName = ch.name
                            privateIdInput = ""
                            privateKeyInput = ""
                            localDialogError = null
                            showPrivateUnlockDialog = true
                        },
                        testTag = "hub_private_${ch.name}"
                    )
                }
            }

            item(key = "enter_private_id_row") {
                Card(
                    onClick = {
                        targetPrivateChannelName = null
                        privateIdInput = ""
                        privateKeyInput = ""
                        localDialogError = null
                        showPrivateUnlockDialog = true
                    },
                    colors = CardDefaults.cardColors(containerColor = VoidSurface),
                    border = BorderStroke(1.dp, VoidAmber.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hub_enter_private_id_button")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(VoidAmber.copy(alpha = 0.14f))
                                .border(1.dp, VoidAmber, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.VpnKey,
                                contentDescription = null,
                                tint = VoidAmber,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Enter Private Channel ID",
                                style = MaterialTheme.typography.titleSmall,
                                color = VoidAmber,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Unlock any Private or Hidden channel by ID",
                                style = MaterialTheme.typography.labelSmall,
                                color = VoidTextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = VoidAmber
                        )
                    }
                }
            }
        }
    }

    if (showPlusChooserDialog) {
        AlertDialog(
            onDismissRequest = { showPlusChooserDialog = false },
            containerColor = VoidSurface,
            titleContentColor = VoidMint,
            textContentColor = VoidTextPrimary,
            modifier = Modifier.testTag("hub_plus_chooser_dialog"),
            title = {
                Text(
                    text = "NEW ACTION",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Card(
                        onClick = {
                            showPlusChooserDialog = false
                            onOpenCreateChannel()
                        },
                        colors = CardDefaults.cardColors(containerColor = VoidObsidian),
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
                                    text = "Create Channel",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = VoidTextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Public, Private (Show), or Private (Hidden)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VoidTextSecondary
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
                            onClearDirectChatError()
                            localDmError = null
                            showDirectChatDialog = true
                        },
                        colors = CardDefaults.cardColors(containerColor = VoidObsidian),
                        border = BorderStroke(1.dp, VoidCyan.copy(alpha = 0.65f)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("hub_plus_option_chat_user")
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
                                    imageVector = Icons.Filled.Chat,
                                    contentDescription = null,
                                    tint = VoidCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Chat with User",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = VoidTextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Direct 1-on-1 chat using Username ID",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VoidTextSecondary
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
                    Text("CLOSE", color = VoidTextSecondary)
                }
            }
        )
    }

    if (showDirectChatDialog) {
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = VoidCyan,
            unfocusedBorderColor = VoidBorder,
            focusedLabelColor = VoidCyan,
            unfocusedLabelColor = VoidTextSecondary,
            focusedTextColor = VoidTextPrimary,
            unfocusedTextColor = VoidTextPrimary,
            cursorColor = VoidCyan
        )

        AlertDialog(
            onDismissRequest = { showDirectChatDialog = false },
            containerColor = VoidSurface,
            titleContentColor = VoidCyan,
            textContentColor = VoidTextPrimary,
            modifier = Modifier.testTag("direct_chat_dialog"),
            title = {
                Text(
                    text = "CHAT WITH USER",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(VoidObsidian)
                            .border(1.dp, VoidBorder, RoundedCornerShape(10.dp))
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
                                color = VoidTextPrimary,
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
                                        .background(VoidObsidian)
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
                            onOpenDirectChat(trimmed)
                        }
                    },
                    enabled = !isOpeningDirectChat,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VoidMint,
                        contentColor = VoidObsidian
                    ),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("dm_start_chat_submit")
                ) {
                    Text(
                        text = if (isOpeningDirectChat) "OPENING..." else "START CHAT",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDirectChatDialog = false },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("CANCEL", color = VoidTextSecondary)
                }
            }
        )
    }

    if (showPrivateUnlockDialog) {
        val fieldColors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = VoidAmber,
            unfocusedBorderColor = VoidBorder,
            focusedLabelColor = VoidAmber,
            unfocusedLabelColor = VoidTextSecondary,
            focusedTextColor = VoidTextPrimary,
            unfocusedTextColor = VoidTextPrimary,
            cursorColor = VoidAmber
        )

        AlertDialog(
            onDismissRequest = { showPrivateUnlockDialog = false },
            containerColor = VoidSurface,
            titleContentColor = VoidAmber,
            textContentColor = VoidTextPrimary,
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
                        color = VoidTextSecondary
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
                        contentColor = VoidObsidian
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
                    Text("CANCEL", color = VoidTextSecondary)
                }
            }
        )
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
private fun HubChannelRow(
    title: String,
    subtitle: String,
    isPublic: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val accent = if (isPublic) VoidMint else VoidAmber
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        border = BorderStroke(1.dp, VoidBorder),
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
                    color = VoidTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = VoidTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = "Open channel",
                tint = VoidTextSecondary
            )
        }
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
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
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
                    color = VoidTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = VoidTextSecondary,
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
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by rememberSaveable(session.dmId) { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E14))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(VoidSurface)
                .padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                    tint = VoidTextPrimary
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
                    color = VoidTextPrimary,
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
        }
        HorizontalDivider(thickness = 1.dp, color = VoidBorder)

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
                            .background(VoidSurface)
                            .border(1.dp, VoidBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "Direct 1-on-1 chat with @${session.peerUsername} (you: @${session.myUsername})",
                            style = MaterialTheme.typography.labelSmall,
                            color = VoidTextSecondary
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
                            color = VoidTextSecondary
                        )
                    }
                }
            } else {
                items(messages, key = { it.id }) { msg ->
                    WhatsAppMessageBubble(
                        message = msg,
                        isMine = msg.sender.equals(session.myUsername, ignoreCase = true)
                    )
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = VoidBorder)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(VoidSurface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                    unfocusedBorderColor = VoidBorder,
                    focusedContainerColor = VoidObsidian,
                    unfocusedContainerColor = VoidObsidian,
                    focusedTextColor = VoidTextPrimary,
                    unfocusedTextColor = VoidTextPrimary,
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
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send direct message",
                        tint = VoidObsidian,
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
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onBuildStreamUrl: (RemoteMediaItem, String?) -> String,
    modifier: Modifier = Modifier
) {
    var draft by rememberSaveable(session.channelId) { mutableStateOf("") }
    var showMediaDrawer by rememberSaveable(session.channelId) { mutableStateOf(false) }
    var activePlaybackItem by remember(session.channelId) { mutableStateOf<RemoteMediaItem?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val accent = if (session.isPublic) VoidMint else VoidAmber

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E14))
    ) {
        // WhatsApp-style Top Channel Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(VoidSurface)
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
                    tint = VoidTextPrimary
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
                if (session.isPublic) {
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

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = VoidTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(if (session.isPublic) "PUBLIC" else "PRIVATE")
                        append(" · ")
                        append(if (wsStatus == ClientWsStatus.LIVE) "WS LIVE" else wsStatus.name)
                        append(" · $peersCount peer(s)")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (wsStatus == ClientWsStatus.LIVE) VoidMint else VoidCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
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
                    tint = if (showMediaDrawer || session.mediaList.isNotEmpty()) VoidMint else VoidTextSecondary
                )
            }
        }
        HorizontalDivider(thickness = 1.dp, color = VoidBorder)

        // Collapsible Channel Media Drawer with Inline ExoPlayer
        AnimatedVisibility(visible = showMediaDrawer || activePlaybackItem != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(VoidSurfaceElevated)
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
                            color = VoidTextSecondary
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
                                        .background(VoidObsidian)
                                        .border(1.dp, VoidBorder, RoundedCornerShape(10.dp))
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
                                        color = VoidTextPrimary,
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
                HorizontalDivider(thickness = 1.dp, color = VoidBorder)
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
                            .background(VoidSurface)
                            .border(1.dp, VoidBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = if (session.isPublic) {
                                "${session.name} · Public channel chat space (you: ${session.callsign})"
                            } else {
                                "${session.name} · Private ID-locked chat space (you: ${session.callsign})"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = VoidTextSecondary
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
                            text = "No messages yet. Say hello to start this channel conversation.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = VoidTextSecondary
                        )
                    }
                }
            } else {
                items(messages, key = { it.id }) { msg ->
                    WhatsAppMessageBubble(
                        message = msg,
                        isMine = msg.sender == session.callsign
                    )
                }
            }
        }

        // Sticky Bottom Composer
        HorizontalDivider(thickness = 1.dp, color = VoidBorder)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(VoidSurface)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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
                    unfocusedBorderColor = VoidBorder,
                    focusedContainerColor = VoidObsidian,
                    unfocusedContainerColor = VoidObsidian,
                    focusedTextColor = VoidTextPrimary,
                    unfocusedTextColor = VoidTextPrimary,
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
                        tint = VoidObsidian,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun WhatsAppMessageBubble(
    message: ClientChatMessage,
    isMine: Boolean
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
    val bubbleBg = if (isMine) Color(0xFF0C3325) else VoidSurface
    val bubbleBorder = if (isMine) VoidMint.copy(alpha = 0.45f) else VoidBorder

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
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyMedium,
                color = VoidTextPrimary
            )
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                modifier = Modifier.align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = message.time,
                    style = MaterialTheme.typography.labelSmall,
                    color = VoidTextSecondary
                )
                if (isMine) {
                    Text(
                        text = "✓✓",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidMint,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
