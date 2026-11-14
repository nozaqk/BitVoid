package com.bitvoid.network.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bitvoid.network.client.BitVoidClientManager
import com.bitvoid.network.client.BitVoidClientRepository
import com.bitvoid.network.client.ClientChannelSession
import com.bitvoid.network.client.ClientChatMessage
import com.bitvoid.network.client.ClientHubTab
import com.bitvoid.network.client.ClientWsStatus
import com.bitvoid.network.client.RemoteChannelSummary
import com.bitvoid.network.client.RemoteMediaItem
import com.bitvoid.network.model.ClientConnectionStatus
import com.bitvoid.network.model.ClientState
import com.bitvoid.network.model.DiscoveredNetwork
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
import java.util.Locale

@Composable
fun BitVoidClientHubContent(
    clientState: ClientState,
    gatewayUrl: String,
    channels: List<RemoteChannelSummary>,
    isLoadingChannels: Boolean,
    channelsError: String?,
    activeChannelSession: ClientChannelSession?,
    channelMessages: List<ClientChatMessage>,
    wsStatus: ClientWsStatus,
    channelPeersCount: Int,
    isJoiningChannel: Boolean,
    channelJoinError: String?,
    publicMedia: List<RemoteMediaItem>,
    isLoadingPublicMedia: Boolean,
    publicMediaError: String?,
    onSetGatewayUrl: (String) -> Boolean,
    onRefreshChannels: (String?) -> Unit,
    onRefreshPublicMedia: (String?) -> Unit,
    onJoinChannel: (String, String, String?) -> Unit,
    onSendChatMessage: (String) -> Unit,
    onLeaveChannel: () -> Unit,
    onBuildStreamUrl: (RemoteMediaItem, String?) -> String,
    onScanNetworks: () -> Unit,
    onConnectNode: (String, String, String) -> Unit,
    onDisconnectClient: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onRequestEvaluated: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableStateOf(ClientHubTab.CHANNELS) }
    var showWifiDrawer by rememberSaveable { mutableStateOf(false) }
    var gatewayInput by rememberSaveable(gatewayUrl) { mutableStateOf(gatewayUrl) }
    var urlInputError by remember { mutableStateOf<String?>(null) }

    var targetSsid by rememberSaveable { mutableStateOf("") }
    var targetPassphrase by rememberSaveable { mutableStateOf("") }

    var activePublicStream by remember { mutableStateOf<Pair<RemoteMediaItem, String>?>(null) }
    var activeChannelStream by remember { mutableStateOf<Pair<RemoteMediaItem, String>?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ClientGatewayBarCard(
            clientState = clientState,
            gatewayInput = gatewayInput,
            urlError = urlInputError,
            wifiExpanded = showWifiDrawer,
            onGatewayInputChange = {
                gatewayInput = it
                urlInputError = null
            },
            onUseDefaultGateway = {
                gatewayInput = BitVoidClientRepository.DEFAULT_GATEWAY_URL
                urlInputError = null
                onSetGatewayUrl(BitVoidClientRepository.DEFAULT_GATEWAY_URL)
                onRefreshChannels(BitVoidClientRepository.DEFAULT_GATEWAY_URL)
                onRefreshPublicMedia(BitVoidClientRepository.DEFAULT_GATEWAY_URL)
            },
            onSyncGateway = {
                if (onSetGatewayUrl(gatewayInput)) {
                    urlInputError = null
                    onRefreshChannels(gatewayInput)
                    onRefreshPublicMedia(gatewayInput)
                } else {
                    urlInputError = "Use a local RFC1918 or loopback address (e.g., 192.168.43.1:8080)"
                }
            },
            onToggleWifiDrawer = { showWifiDrawer = !showWifiDrawer },
            targetSsid = targetSsid,
            onSsidChange = { targetSsid = it },
            targetPassphrase = targetPassphrase,
            onPassphraseChange = { targetPassphrase = it },
            onScanNetworks = onScanNetworks,
            onConnectNode = { onConnectNode(targetSsid, targetPassphrase, gatewayInput) },
            onDisconnectClient = onDisconnectClient,
            onOpenPortalBrowser = { selectedTab = ClientHubTab.WEB_PORTAL },
            onSelectDiscoveredNetwork = { net ->
                targetSsid = net.ssid
                if (net.securityLabel.equals("OPEN", ignoreCase = true)) {
                    targetPassphrase = ""
                }
            },
            onOpenWifiSettings = onOpenWifiSettings
        )

        // Three-Tab Client Hub Switcher: Channels | Public Feed | Web Portal
        TabRow(
            selectedTabIndex = selectedTab.ordinal,
            containerColor = VoidSurface,
            contentColor = VoidMint,
            indicator = { tabPositions ->
                SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab.ordinal]),
                    color = VoidMint
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, VoidBorder, RoundedCornerShape(12.dp))
        ) {
            ClientHubTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                Tab(
                    selected = selected,
                    onClick = {
                        selectedTab = tab
                        when (tab) {
                            ClientHubTab.CHANNELS -> onRefreshChannels(gatewayInput)
                            ClientHubTab.PUBLIC_FEED -> onRefreshPublicMedia(gatewayInput)
                            ClientHubTab.WEB_PORTAL -> Unit
                        }
                    },
                    text = {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) VoidMint else VoidTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    modifier = Modifier.testTag("client_tab_${tab.name.lowercase()}")
                )
            }
        }

        when (selectedTab) {
            ClientHubTab.CHANNELS -> {
                ChannelsBrowserTabPanel(
                    gatewayUrl = gatewayUrl,
                    channels = channels,
                    isLoadingChannels = isLoadingChannels,
                    channelsError = channelsError,
                    activeChannelSession = activeChannelSession,
                    channelMessages = channelMessages,
                    wsStatus = wsStatus,
                    channelPeersCount = channelPeersCount,
                    isJoiningChannel = isJoiningChannel,
                    channelJoinError = channelJoinError,
                    activePlayback = activeChannelStream,
                    onRefreshChannels = { onRefreshChannels(gatewayInput) },
                    onJoinChannel = { channelId, privateKey ->
                        if (onSetGatewayUrl(gatewayInput)) {
                            activeChannelStream = null
                            onJoinChannel(channelId, privateKey, gatewayInput)
                        }
                    },
                    onPlayItem = { item ->
                        activePublicStream = null
                        val token = activeChannelSession?.token
                        activeChannelStream = item to onBuildStreamUrl(item, token)
                    },
                    onStopPlayback = { activeChannelStream = null },
                    onSendChatMessage = onSendChatMessage,
                    onLeaveChannel = {
                        activeChannelStream = null
                        onLeaveChannel()
                    }
                )
            }

            ClientHubTab.PUBLIC_FEED -> {
                PublicFeedTabPanel(
                    gatewayUrl = gatewayUrl,
                    items = publicMedia,
                    isLoading = isLoadingPublicMedia,
                    errorMessage = publicMediaError,
                    activePlayback = activePublicStream,
                    onRefresh = { onRefreshPublicMedia(gatewayInput) },
                    onPlayItem = { item ->
                        activeChannelStream = null
                        activePublicStream = item to onBuildStreamUrl(item, null)
                    },
                    onStopPlayback = { activePublicStream = null }
                )
            }

            ClientHubTab.WEB_PORTAL -> {
                EmbeddedPortalTabPanel(
                    targetUrl = gatewayUrl,
                    clientState = clientState,
                    onRequestEvaluated = onRequestEvaluated
                )
            }
        }
    }
}

@Composable
private fun ChannelsBrowserTabPanel(
    gatewayUrl: String,
    channels: List<RemoteChannelSummary>,
    isLoadingChannels: Boolean,
    channelsError: String?,
    activeChannelSession: ClientChannelSession?,
    channelMessages: List<ClientChatMessage>,
    wsStatus: ClientWsStatus,
    channelPeersCount: Int,
    isJoiningChannel: Boolean,
    channelJoinError: String?,
    activePlayback: Pair<RemoteMediaItem, String>?,
    onRefreshChannels: () -> Unit,
    onJoinChannel: (String, String) -> Unit,
    onPlayItem: (RemoteMediaItem) -> Unit,
    onStopPlayback: () -> Unit,
    onSendChatMessage: (String) -> Unit,
    onLeaveChannel: () -> Unit
) {
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = VoidMint,
        unfocusedBorderColor = VoidBorder,
        focusedLabelColor = VoidMint,
        unfocusedLabelColor = VoidTextSecondary,
        focusedTextColor = VoidTextPrimary,
        unfocusedTextColor = VoidTextPrimary,
        cursorColor = VoidMint
    )

    if (activeChannelSession != null) {
        var chatDraft by rememberSaveable { mutableStateOf("") }
        val chatListState = rememberLazyListState()

        LaunchedEffect(channelMessages.size) {
            if (channelMessages.isNotEmpty()) {
                chatListState.animateScrollToItem(channelMessages.lastIndex)
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = VoidSurface),
                border = BorderStroke(
                    1.dp,
                    if (activeChannelSession.isPublic) VoidMint.copy(alpha = 0.65f) else VoidAmber.copy(alpha = 0.7f)
                ),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            IconButton(
                                onClick = onLeaveChannel,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("native_leave_channel_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back to channels",
                                    tint = VoidTextPrimary
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "# ${activeChannelSession.name}",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (activeChannelSession.isPublic) VoidMint else VoidAmber,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${activeChannelSession.callsign} · $channelPeersCount live peer(s)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VoidTextSecondary
                                )
                            }
                        }
                        ClientBadgeChip(
                            label = when (wsStatus) {
                                ClientWsStatus.LIVE -> "WS LIVE"
                                ClientWsStatus.CONNECTING -> "CONNECTING"
                                ClientWsStatus.ERROR -> "HTTP CHAT"
                                ClientWsStatus.DISCONNECTED -> "OFFLINE"
                            },
                            accent = if (wsStatus == ClientWsStatus.LIVE) VoidMint else VoidAmber
                        )
                    }
                }
            }

            if (activePlayback != null) {
                InlineExoPlayerCard(
                    item = activePlayback.first,
                    streamUrl = activePlayback.second,
                    onClose = onStopPlayback
                )
            }

            Text(
                text = "CHANNEL MEDIA (${activeChannelSession.mediaList.size})",
                style = MaterialTheme.typography.labelLarge,
                color = VoidCyan
            )

            if (activeChannelSession.mediaList.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = VoidSurface.copy(alpha = 0.6f)),
                    border = BorderStroke(1.dp, VoidBorder),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "No videos/files assigned to this channel yet. Host assignments appear here in real time.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VoidTextSecondary,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            } else {
                activeChannelSession.mediaList.forEach { item ->
                    RemoteMediaRowCard(
                        item = item,
                        isPlaying = activePlayback?.first?.id == item.id,
                        onPlay = { onPlayItem(item) }
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = VoidSurface),
                border = BorderStroke(1.dp, VoidBorder),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Chat,
                            contentDescription = null,
                            tint = VoidCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "CHANNEL CHAT (/ws/chat/${activeChannelSession.channelId})",
                            style = MaterialTheme.typography.labelLarge,
                            color = VoidCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    LazyColumn(
                        state = chatListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(VoidObsidian)
                            .border(1.dp, VoidBorder, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (channelMessages.isEmpty()) {
                            item {
                                Text(
                                    text = "Connected to #${activeChannelSession.name}. Messages sync live between native app & browser users.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = VoidTextSecondary
                                )
                            }
                        } else {
                            items(channelMessages, key = { it.id }) { msg ->
                                val isSelf = msg.sender == activeChannelSession.callsign
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    if (msg.isSystemNotice) {
                                        Text(
                                            text = "[SYSTEM] ${msg.text}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = VoidMint
                                        )
                                    } else {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                text = if (isSelf) "${msg.sender} (YOU)" else msg.sender,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isSelf) VoidMint else VoidCyan
                                            )
                                            if (msg.time.isNotBlank()) {
                                                Text(
                                                    text = msg.time,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = VoidTextSecondary
                                                )
                                            }
                                        }
                                        Text(
                                            text = msg.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = VoidTextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = chatDraft,
                            onValueChange = { chatDraft = it },
                            placeholder = { Text("Message #${activeChannelSession.name}...") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (chatDraft.isNotBlank()) {
                                    onSendChatMessage(chatDraft)
                                    chatDraft = ""
                                }
                            }),
                            colors = fieldColors,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("native_chat_input")
                        )
                        Button(
                            onClick = {
                                if (chatDraft.isNotBlank()) {
                                    onSendChatMessage(chatDraft)
                                    chatDraft = ""
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = VoidCyan,
                                contentColor = VoidObsidian
                            ),
                            modifier = Modifier
                                .height(52.dp)
                                .testTag("native_chat_send_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send chat message",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
        return
    }

    // Channel Browser View: Unlock Private Channel box + Public Channels + Locked Private Channel titles
    var privateChannelIdInput by rememberSaveable { mutableStateOf("") }
    var privateChannelKeyInput by rememberSaveable { mutableStateOf("") }

    val publicChannels = remember(channels) { channels.filter { it.isPublic } }
    val privateChannels = remember(channels) { channels.filter { !it.isPublic } }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Unlock Private Channel Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = VoidSurface),
            border = BorderStroke(1.dp, VoidAmber.copy(alpha = 0.6f)),
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
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = VoidAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "UNLOCK PRIVATE CHANNEL",
                        style = MaterialTheme.typography.labelLarge,
                        color = VoidAmber
                    )
                }

                Text(
                    text = "Enter a Private Channel ID shared by the Host to unlock its encrypted stream & chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VoidTextSecondary
                )

                OutlinedTextField(
                    value = privateChannelIdInput,
                    onValueChange = { privateChannelIdInput = it },
                    label = { Text("Private Channel ID (e.g. CH-8A1F...)") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("native_private_channel_id_input")
                )

                OutlinedTextField(
                    value = privateChannelKeyInput,
                    onValueChange = { privateChannelKeyInput = it },
                    label = { Text("Optional Private Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("native_private_channel_key_input")
                )

                channelJoinError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = VoidCrimson
                    )
                }

                Button(
                    onClick = { onJoinChannel(privateChannelIdInput, privateChannelKeyInput) },
                    enabled = !isJoiningChannel,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VoidAmber,
                        contentColor = VoidObsidian
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("native_unlock_channel_button")
                ) {
                    if (isJoiningChannel) {
                        CircularProgressIndicator(
                            color = VoidObsidian,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("UNLOCK PRIVATE CHANNEL", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // Public Channels Browser Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = VoidSurface),
            border = BorderStroke(1.dp, VoidBorder),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Forum,
                        contentDescription = null,
                        tint = VoidMint,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "PUBLIC CHANNELS (${publicChannels.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = VoidMint
                        )
                        Text(
                            text = "$gatewayUrl/api/channels",
                            style = MaterialTheme.typography.labelSmall,
                            color = VoidTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = onRefreshChannels,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("refresh_channels_button")
                ) {
                    if (isLoadingChannels) {
                        CircularProgressIndicator(
                            color = VoidMint,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh channels",
                            tint = VoidCyan
                        )
                    }
                }
            }
        }

        channelsError?.let { err ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF281217)),
                border = BorderStroke(1.dp, VoidCrimson.copy(alpha = 0.7f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "Gateway unreachable: $err\nConnect to the Host hotspot or tap 192.168.43.1:8080 above.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VoidCrimson,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        if (publicChannels.isEmpty() && !isLoadingChannels) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = VoidSurface.copy(alpha = 0.65f)),
                border = BorderStroke(1.dp, VoidBorder),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "No public channels found at $gatewayUrl yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VoidTextSecondary,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            publicChannels.forEach { channel ->
                PublicChannelRowCard(
                    channel = channel,
                    onOpen = {
                        channel.channelId?.let { cid -> onJoinChannel(cid, "") }
                    }
                )
            }
        }

        if (privateChannels.isNotEmpty()) {
            Text(
                text = "LOCKED PRIVATE CHANNELS (${privateChannels.size})",
                style = MaterialTheme.typography.labelLarge,
                color = VoidAmber
            )
            privateChannels.forEach { channel ->
                LockedPrivateChannelRowCard(channel = channel)
            }
        }
    }
}

@Composable
private fun PublicChannelRowCard(
    channel: RemoteChannelSummary,
    onOpen: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        border = BorderStroke(1.dp, VoidMint.copy(alpha = 0.45f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(VoidMintContainer)
                    .border(1.dp, VoidMint.copy(alpha = 0.5f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Tag,
                    contentDescription = null,
                    tint = VoidMint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = VoidTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "#${channel.channelId.orEmpty()} · ${channel.mediaCount} media · ${channel.activePeers} live · ${channel.messageCount} msgs",
                    style = MaterialTheme.typography.labelSmall,
                    color = VoidTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = onOpen,
                colors = ButtonDefaults.buttonColors(
                    containerColor = VoidMint,
                    contentColor = VoidObsidian
                ),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("open_channel_${channel.channelId.orEmpty()}")
            ) {
                Text("OPEN", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun LockedPrivateChannelRowCard(channel: RemoteChannelSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = VoidSurface.copy(alpha = 0.7f)),
        border = BorderStroke(1.dp, VoidAmber.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = "Locked channel",
                tint = VoidAmber,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = VoidTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${channel.mediaCount} media · ${if (channel.hasPrivateKey) "Private ID + Key required" else "Private Channel ID required"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = VoidTextSecondary
                )
            }
            ClientBadgeChip(label = "LOCKED", accent = VoidAmber)
        }
    }
}

@Composable
private fun ClientGatewayBarCard(
    clientState: ClientState,
    gatewayInput: String,
    urlError: String?,
    wifiExpanded: Boolean,
    onGatewayInputChange: (String) -> Unit,
    onUseDefaultGateway: () -> Unit,
    onSyncGateway: () -> Unit,
    onToggleWifiDrawer: () -> Unit,
    targetSsid: String,
    onSsidChange: (String) -> Unit,
    targetPassphrase: String,
    onPassphraseChange: (String) -> Unit,
    onScanNetworks: () -> Unit,
    onConnectNode: () -> Unit,
    onDisconnectClient: () -> Unit,
    onOpenPortalBrowser: () -> Unit,
    onSelectDiscoveredNetwork: (DiscoveredNetwork) -> Unit,
    onOpenWifiSettings: () -> Unit
) {
    var showPassword by rememberSaveable { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = VoidMint,
        unfocusedBorderColor = VoidBorder,
        focusedLabelColor = VoidMint,
        unfocusedLabelColor = VoidTextSecondary,
        focusedTextColor = VoidTextPrimary,
        unfocusedTextColor = VoidTextPrimary,
        cursorColor = VoidMint
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        border = BorderStroke(1.dp, VoidBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "GATEWAY ENDPOINT",
                    style = MaterialTheme.typography.labelLarge,
                    color = VoidCyan
                )
                ClientBadgeChip(
                    label = clientState.connectionStatus.name,
                    accent = when (clientState.connectionStatus) {
                        ClientConnectionStatus.CONNECTED -> VoidMint
                        ClientConnectionStatus.CONNECTING -> VoidCyan
                        ClientConnectionStatus.ERROR -> VoidCrimson
                        else -> VoidTextSecondary
                    }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = gatewayInput,
                    onValueChange = onGatewayInputChange,
                    label = { Text("Host URL (e.g. http://192.168.43.1:8080)") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("client_gateway_url_input")
                )
                Button(
                    onClick = onSyncGateway,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VoidMint,
                        contentColor = VoidObsidian
                    ),
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("client_sync_gateway_button")
                ) {
                    Text("SYNC", style = MaterialTheme.typography.labelLarge)
                }
            }

            urlError?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall, color = VoidCrimson)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onUseDefaultGateway,
                    border = BorderStroke(1.dp, VoidCyan.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidCyan),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Text("192.168.43.1:8080", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onToggleWifiDrawer,
                    border = BorderStroke(1.dp, VoidBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidTextPrimary),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("toggle_wifi_discovery_button")
                ) {
                    Icon(
                        imageVector = if (wifiExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("WI-FI SCAN / JOIN", style = MaterialTheme.typography.labelMedium)
                }
            }

            AnimatedVisibility(visible = wifiExpanded) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HorizontalDivider(color = VoidBorder)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = clientState.statusMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = VoidTextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onScanNetworks,
                            enabled = !clientState.isScanning,
                            border = BorderStroke(1.dp, VoidMint.copy(alpha = 0.6f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidMint),
                            modifier = Modifier.height(44.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.WifiFind,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (clientState.isScanning) "SCANNING" else "SCAN",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    if (clientState.discoveredNetworks.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            clientState.discoveredNetworks.take(5).forEach { net ->
                                DiscoveredBeaconChipRow(
                                    network = net,
                                    onSelect = { onSelectDiscoveredNetwork(net) }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = targetSsid,
                        onValueChange = onSsidChange,
                        label = { Text("Node SSID") },
                        singleLine = true,
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = targetPassphrase,
                        onValueChange = onPassphraseChange,
                        label = { Text("WPA2 Passphrase (blank for Open)") },
                        singleLine = true,
                        visualTransformation = if (showPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            TextButton(onClick = { showPassword = !showPassword }) {
                                Text(
                                    text = if (showPassword) "HIDE" else "SHOW",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VoidCyan
                                )
                            }
                        },
                        colors = fieldColors,
                        modifier = Modifier.fillMaxWidth()
                    )

                    clientState.errorMessage?.let { err ->
                        Text(text = err, style = MaterialTheme.typography.bodySmall, color = VoidCrimson)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (clientState.connectionStatus == ClientConnectionStatus.CONNECTED) {
                            OutlinedButton(
                                onClick = onDisconnectClient,
                                border = BorderStroke(1.dp, VoidCrimson),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidCrimson),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("client_disconnect_button")
                            ) {
                                Text("DISCONNECT", style = MaterialTheme.typography.labelLarge)
                            }
                            Button(
                                onClick = onOpenPortalBrowser,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VoidMint,
                                    contentColor = VoidObsidian
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("client_open_portal_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Language,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("OPEN PORTAL", style = MaterialTheme.typography.labelLarge)
                            }
                        } else {
                            Button(
                                onClick = onConnectNode,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = VoidMint,
                                    contentColor = VoidObsidian
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Wifi,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("CONNECT NODE", style = MaterialTheme.typography.labelLarge)
                            }
                        }

                        OutlinedButton(
                            onClick = onOpenWifiSettings,
                            border = BorderStroke(1.dp, VoidBorder),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidTextPrimary),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Settings,
                                contentDescription = "Wi-Fi Settings",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveredBeaconChipRow(
    network: DiscoveredNetwork,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(VoidObsidian)
            .border(
                1.dp,
                if (network.isBitVoidCandidate) VoidMint.copy(alpha = 0.6f) else VoidBorder,
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = network.ssid,
                style = MaterialTheme.typography.labelLarge,
                color = if (network.isBitVoidCandidate) VoidMint else VoidTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${network.securityLabel} · ${network.signalDbm} dBm",
                style = MaterialTheme.typography.labelSmall,
                color = VoidTextSecondary
            )
        }
        Text(
            text = "USE",
            style = MaterialTheme.typography.labelMedium,
            color = VoidCyan
        )
    }
}

@Composable
private fun PublicFeedTabPanel(
    gatewayUrl: String,
    items: List<RemoteMediaItem>,
    isLoading: Boolean,
    errorMessage: String?,
    activePlayback: Pair<RemoteMediaItem, String>?,
    onRefresh: () -> Unit,
    onPlayItem: (RemoteMediaItem) -> Unit,
    onStopPlayback: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (activePlayback != null) {
            InlineExoPlayerCard(
                item = activePlayback.first,
                streamUrl = activePlayback.second,
                onClose = onStopPlayback
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = VoidSurface),
            border = BorderStroke(1.dp, VoidBorder),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Public,
                        contentDescription = null,
                        tint = VoidMint,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "PUBLIC BROADCASTS (${items.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = VoidMint
                        )
                        Text(
                            text = "$gatewayUrl/api/public/media",
                            style = MaterialTheme.typography.labelSmall,
                            color = VoidTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("refresh_public_feed_button")
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = VoidMint,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh public feed",
                            tint = VoidCyan
                        )
                    }
                }
            }
        }

        errorMessage?.let { err ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF281217)),
                border = BorderStroke(1.dp, VoidCrimson.copy(alpha = 0.7f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "Gateway unreachable: $err\nEnsure you are connected to the Host hotspot or synced to $gatewayUrl.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VoidCrimson,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        if (items.isEmpty() && !isLoading) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = VoidSurface.copy(alpha = 0.65f)),
                border = BorderStroke(1.dp, VoidBorder),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No Public Media Available",
                        style = MaterialTheme.typography.titleMedium,
                        color = VoidTextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Ask the Host to toggle a video to Public or assign it to a Public Channel.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VoidTextSecondary
                    )
                }
            }
        } else {
            items.forEach { item ->
                RemoteMediaRowCard(
                    item = item,
                    isPlaying = activePlayback?.first?.id == item.id,
                    onPlay = { onPlayItem(item) }
                )
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun InlineExoPlayerCard(
    item: RemoteMediaItem,
    streamUrl: String,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build()
    }

    LaunchedEffect(streamUrl) {
        exoPlayer.setMediaItem(MediaItem.fromUri(streamUrl))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        border = BorderStroke(1.dp, VoidMint),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "NOW PLAYING (EXOPLAYER HTTP 206)",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidMint
                    )
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = VoidTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Stop player",
                        tint = VoidCrimson
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black)
                    .border(1.dp, VoidBorder, RoundedCornerShape(10.dp))
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            useController = true
                            player = exoPlayer
                        }
                    },
                    update = { view ->
                        view.player = exoPlayer
                    }
                )
            }
        }
    }
}

@Composable
private fun RemoteMediaRowCard(
    item: RemoteMediaItem,
    isPlaying: Boolean,
    onPlay: () -> Unit
) {
    val accent = if (isPlaying) VoidMint else VoidCyan
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        border = BorderStroke(1.dp, if (isPlaying) VoidMint else VoidBorder),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isPlaying) VoidMintContainer else VoidObsidian)
                    .border(1.dp, accent.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    text = item.badge,
                    style = MaterialTheme.typography.labelMedium,
                    color = accent
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = VoidTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatRemoteBytes(item.size)} · ${item.mimeType}",
                    style = MaterialTheme.typography.labelSmall,
                    color = VoidTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = onPlay,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPlaying) VoidMintContainer else VoidMint,
                    contentColor = if (isPlaying) VoidMint else VoidObsidian
                ),
                border = if (isPlaying) BorderStroke(1.dp, VoidMint) else null,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("play_remote_${item.id}")
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "Play ${item.title}",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (isPlaying) "ACTIVE" else "PLAY",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun EmbeddedPortalTabPanel(
    targetUrl: String,
    clientState: ClientState,
    onRequestEvaluated: (Boolean) -> Unit
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var currentLoadedUrl by remember(targetUrl) { mutableStateOf(targetUrl) }

    LaunchedEffect(targetUrl) {
        val wv = webViewRef ?: return@LaunchedEffect
        if (wv.url != targetUrl) {
            wv.loadUrl(targetUrl)
        }
    }

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
                    .background(VoidSurfaceElevated)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = null,
                        tint = VoidMint,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = currentLoadedUrl,
                            style = MaterialTheme.typography.labelMedium,
                            color = VoidMint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "SANDBOXED · LOCAL ${clientState.privacyTelemetry.strippedRequestsCount} · BLOCKED ${clientState.privacyTelemetry.blockedExternalRequestsCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = VoidCyan,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = { webViewRef?.loadUrl(targetUrl) },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Reload embedded web portal",
                        tint = VoidCyan
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(520.dp)
                    .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            setBackgroundColor(android.graphics.Color.parseColor("#0D1117"))
                            BitVoidClientManager.configureSandboxedWebView(
                                webView = this,
                                onRequestEvaluated = onRequestEvaluated,
                                onPageLoaded = { loaded -> currentLoadedUrl = loaded }
                            )
                            tag = targetUrl
                            loadUrl(targetUrl)
                            webViewRef = this
                        }
                    },
                    update = { wv ->
                        if (wv.tag != targetUrl) {
                            wv.tag = targetUrl
                            currentLoadedUrl = targetUrl
                            wv.loadUrl(targetUrl)
                        }
                    },
                    onRelease = { wv ->
                        wv.stopLoading()
                        wv.clearCache(true)
                        wv.clearHistory()
                        wv.destroy()
                    }
                )
            }
        }
    }
}

@Composable
private fun ClientBadgeChip(label: String, accent: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatRemoteBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(Locale.US, "%.2f GB", gb)
}
