package com.bitvoid.network.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitvoid.network.backend.BitVoidBackendPhase
import com.bitvoid.network.backend.BitVoidBackendState
import com.bitvoid.network.backend.BitVoidBackendType
import com.bitvoid.network.channel.BitVoidChannel
import com.bitvoid.network.channel.BitVoidChannelManager
import com.bitvoid.network.channel.BitVoidSpaceKind
import com.bitvoid.network.client.CachedConversationPreview
import com.bitvoid.network.client.RemoteChannelSummary
import com.bitvoid.network.direct.DirectConversationSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ConversationKind { LEGACY_CHANNEL, GROUP, BROADCAST_CHANNEL, DIRECT }

private data class TelegramConversationItem(
    val stableId: String,
    val title: String,
    val preview: String,
    val timestampMs: Long,
    val kind: ConversationKind,
    val peerUsername: String? = null,
    val channelId: String? = null,
    val avatarDataUrl: String? = null,
    val online: Boolean = false,
    val unreadCount: Int = 0,
    val messageCount: Int = 0
)

@Composable
fun BitVoidChatsScreen(
    myUsername: String,
    backendState: BitVoidBackendState,
    hostChannels: List<BitVoidChannel>,
    remoteChannels: List<RemoteChannelSummary>,
    directConversations: List<DirectConversationSummary>,
    cachedConversations: List<CachedConversationPreview>,
    knownUsers: List<String>,
    unreadCounts: Map<String, Int>,
    onRefresh: () -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenDirectChat: (String) -> Unit,
    onCreateGroup: () -> Unit,
    onCreateBroadcastChannel: () -> Unit,
    onDiscoverChannels: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showNewMessage by rememberSaveable { mutableStateOf(false) }
    var pullDistance by remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val isRefreshing = backendState.phase == BitVoidBackendPhase.DISCOVERING ||
        backendState.phase == BitVoidBackendPhase.RECONNECTING
    val refreshAlpha by animateFloatAsState(
        targetValue = if (isRefreshing || pullDistance > 28f) 1f else 0f,
        label = "refreshAlpha"
    )

    val onlineUsers = remember(knownUsers, myUsername) {
        knownUsers
            .filterNot { it.equals(myUsername, ignoreCase = true) }
            .distinctBy { it.lowercase(Locale.US) }
    }
    val onlineSet = onlineUsers.mapTo(hashSetOf()) { it.lowercase(Locale.US) }
    val useRemoteChannels = backendState.isConnected
    val conversations = remember(
        useRemoteChannels,
        remoteChannels,
        hostChannels,
        directConversations,
        cachedConversations,
        unreadCounts,
        onlineSet
    ) {
        val channels = if (useRemoteChannels) {
            remoteChannels
                .filter { channel ->
                    channel.spaceKind != BitVoidSpaceKind.BROADCAST_CHANNEL || channel.isSubscribed
                }
                .mapNotNull { channel ->
                    val id = channel.channelId ?: return@mapNotNull null
                    TelegramConversationItem(
                        stableId = id,
                        title = channel.name,
                        preview = channel.lastMessagePreview
                            ?: when (channel.spaceKind) {
                                BitVoidSpaceKind.GROUP -> "${channel.memberCount} members"
                                BitVoidSpaceKind.BROADCAST_CHANNEL -> "${channel.subscriberCount} subscribers"
                                BitVoidSpaceKind.LEGACY_CHAT -> if (channel.messageCount > 0) {
                                    "${channel.messageCount} messages"
                                } else {
                                    "No messages yet"
                                }
                            },
                        timestampMs = channel.lastTimestampMs,
                        kind = channel.spaceKind.toConversationKind(),
                        channelId = id,
                        avatarDataUrl = channel.avatarDataUrl,
                        online = channel.activePeers > 0,
                        unreadCount = unreadCounts[id] ?: 0,
                        messageCount = channel.messageCount
                    )
                }
        } else {
            hostChannels
                .filter { channel ->
                    when (channel.spaceKind) {
                        BitVoidSpaceKind.GROUP,
                        BitVoidSpaceKind.BROADCAST_CHANNEL -> channel.members.any {
                            it.username.equals(myUsername, ignoreCase = true)
                        }
                        BitVoidSpaceKind.LEGACY_CHAT -> !channel.isHidden ||
                            channel.creatorUsername.equals(myUsername, ignoreCase = true)
                    }
                }
                .map { channel ->
                    TelegramConversationItem(
                        stableId = channel.channelId,
                        title = channel.name,
                        preview = channel.lastMessagePreview
                            ?: when (channel.spaceKind) {
                                BitVoidSpaceKind.GROUP -> "${channel.members.size} members"
                                BitVoidSpaceKind.BROADCAST_CHANNEL -> "${channel.subscriberCount} subscribers"
                                BitVoidSpaceKind.LEGACY_CHAT -> if (channel.channelId == BitVoidChannelManager.DEFAULT_PUBLIC_CHANNEL_ID) {
                                    "Campus-wide conversation"
                                } else {
                                    "No messages yet"
                                }
                            },
                        timestampMs = channel.lastTimestampMs,
                        kind = channel.spaceKind.toConversationKind(),
                        channelId = channel.channelId,
                        avatarDataUrl = channel.avatarDataUrl,
                        online = channel.activeParticipants.any { it.isWebSocketConnected },
                        unreadCount = unreadCounts[channel.channelId] ?: 0,
                        messageCount = channel.messageCount
                    )
                }
        }
        val direct = directConversations.map { conversation ->
            TelegramConversationItem(
                stableId = conversation.dmId,
                title = conversation.peerUsername,
                preview = conversation.lastMessagePreview ?: "Start a private conversation",
                timestampMs = conversation.lastTimestampMs,
                kind = ConversationKind.DIRECT,
                peerUsername = conversation.peerUsername,
                online = conversation.peerUsername.lowercase(Locale.US) in onlineSet,
                unreadCount = unreadCounts[conversation.dmId] ?: 0,
                messageCount = conversation.messageCount
            )
        }
        val knownIds = (direct.map { it.stableId } + channels.map { it.stableId }).toSet()
        val cachedOnly = cachedConversations
            .filterNot { it.conversationId in knownIds }
            .mapNotNull { cached ->
                if (cached.direct) {
                    val members = cached.conversationId.removePrefix("dm__").split("__")
                    val peer = members.firstOrNull {
                        !it.equals(myUsername, ignoreCase = true)
                    } ?: members.lastOrNull() ?: return@mapNotNull null
                    TelegramConversationItem(
                        stableId = cached.conversationId,
                        title = peer,
                        preview = cached.lastMessage.text.ifBlank { "Queued attachment" },
                        timestampMs = cached.lastMessage.timestampMs,
                        kind = ConversationKind.DIRECT,
                        peerUsername = peer,
                        online = peer.lowercase(Locale.US) in onlineSet,
                        unreadCount = unreadCounts[cached.conversationId] ?: 0,
                        messageCount = cached.messageCount
                    )
                } else {
                    TelegramConversationItem(
                        stableId = cached.conversationId,
                        title = cached.conversationId.replace('-', ' ').replaceFirstChar { it.uppercase() },
                        preview = cached.lastMessage.text.ifBlank { "Queued attachment" },
                        timestampMs = cached.lastMessage.timestampMs,
                        kind = ConversationKind.LEGACY_CHANNEL,
                        channelId = cached.conversationId,
                        unreadCount = unreadCounts[cached.conversationId] ?: 0,
                        messageCount = cached.messageCount
                    )
                }
            }
        (direct + channels + cachedOnly).sortedWith(
            compareByDescending<TelegramConversationItem> { it.timestampMs }
                .thenByDescending { it.unreadCount }
                .thenBy { it.title.lowercase(Locale.US) }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(26.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isRefreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(17.dp)
                            .alpha(refreshAlpha)
                    )
                }
            }

            if (conversations.isEmpty()) {
                EmptyConversationState(
                    isSearching = false,
                    isOffline = backendState.type == BitVoidBackendType.OFFLINE,
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .pointerInput(onRefresh, listState.firstVisibleItemIndex) {
                            detectVerticalDragGestures(
                                onVerticalDrag = { _, amount ->
                                    if (listState.firstVisibleItemIndex == 0 &&
                                        listState.firstVisibleItemScrollOffset == 0 && amount > 0
                                    ) {
                                        pullDistance = (pullDistance + amount).coerceAtMost(180f)
                                    }
                                },
                                onDragEnd = {
                                    if (pullDistance > 96f) onRefresh()
                                    pullDistance = 0f
                                },
                                onDragCancel = { pullDistance = 0f }
                            )
                        },
                    contentPadding = PaddingValues(bottom = 92.dp)
                ) {
                    items(conversations, key = { it.stableId }) { item ->
                        TelegramConversationRow(
                            item = item,
                            onClick = {
                                if (item.kind == ConversationKind.DIRECT) {
                                    item.peerUsername?.let(onOpenDirectChat)
                                } else {
                                    item.channelId?.let(onOpenChannel)
                                }
                            }
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 82.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showNewMessage = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .testTag("chats_new_message_fab")
        ) {
            Icon(Icons.Filled.AddComment, contentDescription = "New message")
        }
    }

    if (showNewMessage) {
        NewMessageDialog(
            onlineUsers = onlineUsers,
            channels = conversations.filter { it.kind != ConversationKind.DIRECT },
            onDismiss = { showNewMessage = false },
            onOpenUser = { username ->
                showNewMessage = false
                onOpenDirectChat(username)
            },
            onOpenChannel = { channelId ->
                showNewMessage = false
                onOpenChannel(channelId)
            },
            onCreateGroup = {
                showNewMessage = false
                onCreateGroup()
            },
            onCreateBroadcastChannel = {
                showNewMessage = false
                onCreateBroadcastChannel()
            },
            onDiscoverChannels = {
                showNewMessage = false
                onDiscoverChannels()
            }
        )
    }
}

@Composable
private fun TelegramConversationRow(item: TelegramConversationItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("conversation_${item.stableId}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ConversationAvatar(item)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (item.unreadCount > 0) FontWeight.Bold else FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = formatConversationTimestamp(item.timestampMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.unreadCount > 0) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    AnimatedVisibility(item.unreadCount > 0) {
                        Badge(containerColor = MaterialTheme.colorScheme.primary) {
                            Text(
                                text = if (item.unreadCount > 99) "99+" else item.unreadCount.toString(),
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationAvatar(item: TelegramConversationItem) {
    val palette = listOf(
        Color(0xFF2AABEE), Color(0xFF8774E1), Color(0xFF34C759),
        Color(0xFFFF9500), Color(0xFFFF5A5F), Color(0xFF00A6A6)
    )
    val color = palette[(item.stableId.hashCode() and Int.MAX_VALUE) % palette.size]
    Box(modifier = Modifier.size(56.dp)) {
        if (!item.avatarDataUrl.isNullOrBlank()) {
            SpaceAvatar(
                dataUrl = item.avatarDataUrl,
                fallback = if (item.kind == ConversationKind.BROADCAST_CHANNEL) Icons.Filled.Campaign else Icons.Filled.Group,
                modifier = Modifier.size(54.dp)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center
            ) {
                when (item.kind) {
                    ConversationKind.GROUP -> Icon(Icons.Filled.Group, contentDescription = null, tint = Color.White)
                    ConversationKind.BROADCAST_CHANNEL -> Icon(Icons.Filled.Campaign, contentDescription = null, tint = Color.White)
                    else -> Text(
                        text = initials(item.title),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        if (item.online) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF35C759))
            )
        }
    }
}

@Composable
private fun EmptyConversationState(
    isSearching: Boolean,
    isOffline: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = if (isOffline) Icons.Filled.CloudOff else Icons.Filled.AddComment,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(58.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (isSearching) "No matching conversations" else "Your conversations appear here",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (isOffline) {
                "BitVoid is available offline. Pull down to reconnect when campus or internet access returns."
            } else {
                "Tap the new-message button to start chatting. Server discovery runs automatically."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NewMessageDialog(
    onlineUsers: List<String>,
    channels: List<TelegramConversationItem>,
    onDismiss: () -> Unit,
    onOpenUser: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onCreateGroup: () -> Unit,
    onCreateBroadcastChannel: () -> Unit,
    onDiscoverChannels: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New message") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item(key = "new_create_group") {
                    NewSpaceActionRow(
                        icon = Icons.Filled.PersonAdd,
                        title = "Create Group",
                        subtitle = "Message multiple members",
                        onClick = onCreateGroup
                    )
                }
                item(key = "new_create_channel") {
                    NewSpaceActionRow(
                        icon = Icons.Filled.Campaign,
                        title = "Create Channel",
                        subtitle = "Broadcast posts to subscribers",
                        onClick = onCreateBroadcastChannel
                    )
                }
                item(key = "new_discover_channels") {
                    NewSpaceActionRow(
                        icon = Icons.Filled.Search,
                        title = "Discover Channels",
                        subtitle = "Search public channels or use an invitation",
                        onClick = onDiscoverChannels
                    )
                }
                if (onlineUsers.isNotEmpty()) {
                    item {
                        Text(
                            "PEOPLE ONLINE",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(onlineUsers, key = { "new_user_$it" }) { username ->
                        Card(
                            onClick = { onOpenUser(username) },
                            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Person, contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Text("@$username", modifier = Modifier.weight(1f))
                                Text("online", color = Color(0xFF35C759), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                item {
                    Text(
                        "CHANNELS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                    )
                }
                items(channels, key = { "new_channel_${it.stableId}" }) { channel ->
                    Card(
                        onClick = { channel.channelId?.let(onOpenChannel) },
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Group, contentDescription = null)
                            Spacer(Modifier.width(12.dp))
                            Text(channel.title, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("CLOSE") }
        }
    )
}

@Composable
private fun NewSpaceActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun BitVoidSpaceKind.toConversationKind(): ConversationKind = when (this) {
    BitVoidSpaceKind.LEGACY_CHAT -> ConversationKind.LEGACY_CHANNEL
    BitVoidSpaceKind.GROUP -> ConversationKind.GROUP
    BitVoidSpaceKind.BROADCAST_CHANNEL -> ConversationKind.BROADCAST_CHANNEL
}

private fun initials(value: String): String {
    val words = value.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "BV"
        words.size == 1 -> words.first().take(2).uppercase(Locale.US)
        else -> "${words.first().first()}${words.last().first()}".uppercase(Locale.US)
    }
}

private fun formatConversationTimestamp(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    val now = System.currentTimeMillis()
    val sameDay = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(now)) ==
        SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(timestampMs))
    return SimpleDateFormat(if (sameDay) "HH:mm" else "MMM d", Locale.getDefault())
        .format(Date(timestampMs))
}
