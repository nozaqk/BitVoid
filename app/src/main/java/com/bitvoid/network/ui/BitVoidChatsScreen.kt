package com.bitvoid.network.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.bitvoid.network.client.CachedConversationPreview
import com.bitvoid.network.client.RemoteChannelSummary
import com.bitvoid.network.direct.DirectConversationSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ConversationKind { CHANNEL, DIRECT }

private data class TelegramConversationItem(
    val stableId: String,
    val title: String,
    val preview: String,
    val timestampMs: Long,
    val kind: ConversationKind,
    val peerUsername: String? = null,
    val channelId: String? = null,
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
    homeStatusContent: (@Composable () -> Unit)? = null,
    onRefresh: () -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenDirectChat: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
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
    val useRemoteChannels = backendState.isConnected && remoteChannels.isNotEmpty()
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
            remoteChannels.mapNotNull { channel ->
                val id = channel.channelId ?: return@mapNotNull null
                TelegramConversationItem(
                    stableId = id,
                    title = channel.name,
                    preview = channel.lastMessagePreview
                        ?: if (channel.messageCount > 0) {
                            "${channel.messageCount} messages"
                        } else {
                            "No messages yet"
                        },
                    timestampMs = channel.lastTimestampMs,
                    kind = ConversationKind.CHANNEL,
                    channelId = id,
                    online = channel.activePeers > 0,
                    unreadCount = unreadCounts[id] ?: 0,
                    messageCount = channel.messageCount
                )
            }
        } else {
            hostChannels
                .filter { !it.isHidden || it.creatorUsername.equals(myUsername, ignoreCase = true) }
                .map { channel ->
                    TelegramConversationItem(
                        stableId = channel.channelId,
                        title = channel.name,
                        preview = channel.lastMessagePreview
                            ?: if (channel.channelId == BitVoidChannelManager.DEFAULT_PUBLIC_CHANNEL_ID) {
                                "Campus-wide conversation"
                            } else {
                                "No messages yet"
                            },
                        timestampMs = channel.lastTimestampMs,
                        kind = ConversationKind.CHANNEL,
                        channelId = channel.channelId,
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
                        kind = ConversationKind.CHANNEL,
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
    val filtered = remember(conversations, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) conversations else conversations.filter {
            it.title.contains(query, ignoreCase = true) ||
                it.preview.contains(query, ignoreCase = true)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            BitVoidChatsHeader(
                myUsername = myUsername,
                backendState = backendState,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                onRefresh = onRefresh
            )

            homeStatusContent?.let { statusContent ->
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                    statusContent()
                }
            }

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

            if (filtered.isEmpty()) {
                EmptyConversationState(
                    isSearching = searchQuery.isNotBlank(),
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
                    items(filtered, key = { it.stableId }) { item ->
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
            channels = conversations.filter { it.kind == ConversationKind.CHANNEL },
            onDismiss = { showNewMessage = false },
            onOpenUser = { username ->
                showNewMessage = false
                onOpenDirectChat(username)
            },
            onOpenChannel = { channelId ->
                showNewMessage = false
                onOpenChannel(channelId)
            }
        )
    }
}

@Composable
private fun BitVoidChatsHeader(
    myUsername: String,
    backendState: BitVoidBackendState,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    onRefresh: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "BV",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "BitVoid",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "@$myUsername",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BackendConnectionPill(backendState)
                IconButton(onClick = onRefresh, modifier = Modifier.size(42.dp)) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Refresh conversations",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                },
                placeholder = { Text("Search conversations") },
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("chats_search_field")
            )
        }
    }
}

@Composable
fun BackendConnectionPill(state: BitVoidBackendState, modifier: Modifier = Modifier) {
    val color = when {
        state.phase == BitVoidBackendPhase.RECONNECTING ||
            state.phase == BitVoidBackendPhase.DISCOVERING -> MaterialTheme.colorScheme.tertiary
        state.type == BitVoidBackendType.CAMPUS -> MaterialTheme.colorScheme.primary
        state.type == BitVoidBackendType.CLOUD -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.error
    }
    val icon = when (state.type) {
        BitVoidBackendType.CAMPUS -> Icons.Filled.Lan
        BitVoidBackendType.CLOUD -> Icons.Filled.Cloud
        BitVoidBackendType.OFFLINE -> if (
            state.phase == BitVoidBackendPhase.DISCOVERING ||
            state.phase == BitVoidBackendPhase.RECONNECTING
        ) Icons.Filled.Refresh else Icons.Filled.CloudOff
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f),
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.45f)),
        modifier = modifier.testTag("backend_connection_indicator")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(13.dp))
            Text(
                text = when {
                    state.phase == BitVoidBackendPhase.DISCOVERING -> "CONNECTING"
                    state.phase == BitVoidBackendPhase.RECONNECTING -> "RECONNECTING"
                    state.type == BitVoidBackendType.CAMPUS -> "CAMPUS"
                    state.type == BitVoidBackendType.CLOUD -> "CLOUD"
                    else -> "OFFLINE"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1
            )
        }
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
        Box(
            modifier = Modifier
                .size(54.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center
        ) {
            if (item.kind == ConversationKind.CHANNEL && item.title.isBlank()) {
                Icon(Icons.Filled.Group, contentDescription = null, tint = Color.White)
            } else {
                Text(
                    text = initials(item.title),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
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
    onOpenChannel: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New message") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
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
