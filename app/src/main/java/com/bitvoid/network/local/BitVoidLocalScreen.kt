package com.bitvoid.network.local

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitvoid.network.backend.BitVoidBackendPhase
import com.bitvoid.network.backend.BitVoidBackendState
import com.bitvoid.network.backend.BitVoidLocalNetworkState
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay

@Composable
fun BitVoidLocalApp(viewModel: BitVoidLocalViewModel) {
    val backend by viewModel.backendState.collectAsStateWithLifecycle()
    val network by viewModel.networkState.collectAsStateWithLifecycle()
    val configuredUrl by viewModel.configuredServerUrl.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val activeConversation by viewModel.activeConversation.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val eventsConnected by viewModel.eventsConnected.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    val localNetworkReady = network.wifiConnected &&
        network.gatewayOnline &&
        backend.isConnected
    var accessTransitionReady by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(localNetworkReady) {
        if (localNetworkReady) {
            delay(700)
            accessTransitionReady = true
        } else {
            accessTransitionReady = false
        }
    }

    when {
        profile == null && !accessTransitionReady -> LocalConnectionScreen(
            backend = backend,
            network = network,
            eventsConnected = eventsConnected,
            error = error
        )
        profile == null -> LocalPairingScreen(
            pairing = pairing,
            serverUrl = backend.baseUrl.orEmpty(),
            onRequest = viewModel::requestPairing,
            onCheck = viewModel::checkPairingApproval
        )
        else -> LocalMessagingScreen(
            profile = requireNotNull(profile),
            backend = backend,
            network = network,
            configuredUrl = configuredUrl,
            conversations = conversations,
            activeConversation = activeConversation,
            messages = messages,
            eventsConnected = eventsConnected,
            isRefreshing = isRefreshing,
            error = error,
            onRefresh = viewModel::refresh,
            onOpenConversation = viewModel::openConversation,
            onCloseConversation = viewModel::closeConversation,
            onCreateConversation = viewModel::createConversation,
            onSendMessage = viewModel::sendMessage,
            onSaveServer = viewModel::setManualServer,
            onLogout = viewModel::logout,
            onClearError = viewModel::clearError
        )
    }
}

@Composable
private fun LocalConnectionScreen(
    backend: BitVoidBackendState,
    network: BitVoidLocalNetworkState,
    eventsConnected: Boolean,
    error: String?
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag("local_connection_screen"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Lan, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("LOCAL BITVOID", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Internet and cloud accounts are not required.\nConnect this phone and the Server to the same Wi-Fi.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp)
        )
        LocalStatusPanel(
            network = network,
            backend = backend,
            eventsConnected = eventsConnected
        )
        Text(
            "When Wi-Fi, Gateway, and Server are online, BitVoid automatically continues to local access authorization. Real-time events connect after the Server approves this device.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 16.dp)
        )
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }
        if (backend.phase == BitVoidBackendPhase.DISCOVERING ||
            backend.phase == BitVoidBackendPhase.RECONNECTING
        ) {
            Spacer(Modifier.height(18.dp))
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            Text(
                "Searching for the local Server…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun LocalPairingScreen(
    pairing: LocalPairingUiState,
    serverUrl: String,
    onRequest: (String) -> Unit,
    onCheck: (String) -> Unit
) {
    var username by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).testTag("local_pairing_screen"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Computer, null, modifier = Modifier.size(60.dp), tint = MaterialTheme.colorScheme.primary)
        Text("PAIR WITH LOCAL SERVER", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(serverUrl, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 6.dp))
        Text(
            "Request pairing, then give the displayed code to the server administrator. The server issues a device token after approval.",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Local user ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp).testTag("local_username")
        )
        pairing.pairingCode?.let { code ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp)
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("PAIRING CODE", style = MaterialTheme.typography.labelLarge)
                    Text(code, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    Text("Status: ${pairing.status}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        pairing.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Button(
            onClick = {
                if (pairing.requestId == null) onRequest(username) else onCheck(username)
            },
            enabled = username.isNotBlank() && !pairing.isWorking,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp).testTag("request_pairing")
        ) {
            if (pairing.isWorking) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(if (pairing.requestId == null) "REQUEST PAIRING" else "CHECK APPROVAL")
        }
    }
}

@Composable
private fun LocalMessagingScreen(
    profile: LocalProfile,
    backend: BitVoidBackendState,
    network: BitVoidLocalNetworkState,
    configuredUrl: String,
    conversations: List<LocalConversation>,
    activeConversation: LocalConversation?,
    messages: List<LocalMessage>,
    eventsConnected: Boolean,
    isRefreshing: Boolean,
    error: String?,
    onRefresh: () -> Unit,
    onOpenConversation: (LocalConversation) -> Unit,
    onCloseConversation: () -> Unit,
    onCreateConversation: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onSaveServer: (String) -> Boolean,
    onLogout: () -> Unit,
    onClearError: () -> Unit
) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showNewConversation by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    if (showSettings) {
        LocalSettingsScreen(
            profile = profile,
            backend = backend,
            network = network,
            configuredUrl = configuredUrl,
            onBack = { showSettings = false },
            onSaveServer = onSaveServer,
            onRetry = onRefresh,
            onLogout = { confirmLogout = true }
        )
    } else if (activeConversation != null) {
        LocalChatScreen(
            conversation = activeConversation,
            messages = messages,
            online = backend.isConnected,
            eventsConnected = eventsConnected,
            onBack = onCloseConversation,
            onSend = onSendMessage
        )
    } else {
        Column(Modifier.fillMaxSize().testTag("local_conversation_list")) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("BitVoid", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("@${profile.username} · local Wi-Fi", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onRefresh) {
                    if (isRefreshing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Refresh, "Refresh")
                }
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Filled.Settings, "Settings") }
            }
            LocalStatusPanel(network, backend, eventsConnected, Modifier.padding(horizontal = 16.dp))
            if (!backend.isConnected) {
                Text(
                    "Server offline · cached messages remain available and new messages will queue.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
            error?.let {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                        TextButton(onClick = onClearError) { Text("DISMISS") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("CONVERSATIONS", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                TextButton(onClick = { showNewConversation = true }, enabled = backend.isConnected) {
                    Text("NEW")
                }
            }
            if (conversations.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No local conversations yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(conversations, key = { it.id }) { conversation ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onOpenConversation(conversation) }
                                .padding(horizontal = 18.dp, vertical = 14.dp)
                        ) {
                            Text(conversation.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                conversation.lastMessage.ifBlank { conversation.id },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showNewConversation) {
        var participant by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewConversation = false },
            title = { Text("New local conversation") },
            text = {
                OutlinedTextField(
                    value = participant,
                    onValueChange = { participant = it },
                    label = { Text("Participant user ID") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = { showNewConversation = false; onCreateConversation(participant) }) {
                    Text("CREATE")
                }
            },
            dismissButton = { TextButton(onClick = { showNewConversation = false }) { Text("CANCEL") } }
        )
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            title = { Text("Remove local authorization?") },
            text = { Text("This removes the server-issued token and cached local messages from this phone.") },
            confirmButton = { TextButton(onClick = { confirmLogout = false; onLogout() }) { Text("REMOVE") } },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("CANCEL") } }
        )
    }
}

@Composable
private fun LocalChatScreen(
    conversation: LocalConversation,
    messages: List<LocalMessage>,
    online: Boolean,
    eventsConnected: Boolean,
    onBack: () -> Unit,
    onSend: (String) -> Unit
) {
    var text by rememberSaveable(conversation.id) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().testTag("local_chat_screen")) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(conversation.title, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        !online -> "Offline · messages queue locally"
                        eventsConnected -> "Local events connected"
                        else -> "Connected · events reconnecting"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(10.dp)) {
                        Text(message.sender, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(message.content)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text(
                                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.createdAtMs)) +
                                    if (message.deliveryState == LocalDeliveryState.PENDING || message.deliveryState == LocalDeliveryState.FAILED) {
                                        " · ${message.deliveryState.name.lowercase()}"
                                    } else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(if (online) "Message" else "Queue message offline") },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            IconButton(
                onClick = { val send = text; text = ""; onSend(send) },
                enabled = text.isNotBlank()
            ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }
}

@Composable
private fun LocalSettingsScreen(
    profile: LocalProfile,
    backend: BitVoidBackendState,
    network: BitVoidLocalNetworkState,
    configuredUrl: String,
    onBack: () -> Unit,
    onSaveServer: (String) -> Boolean,
    onRetry: () -> Unit,
    onLogout: () -> Unit
) {
    var serverUrl by rememberSaveable(configuredUrl) { mutableStateOf(configuredUrl) }
    Column(Modifier.fillMaxSize().padding(18.dp).testTag("local_settings_screen")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Local settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Text("User @${profile.username}", modifier = Modifier.padding(vertical = 12.dp))
        LocalStatusPanel(network, backend, eventsConnected = false)
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            label = { Text("Server address") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        )
        Button(
            onClick = { if (onSaveServer(serverUrl)) onRetry() },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        ) { Text("SAVE AND RECONNECT") }
        OutlinedButton(
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
        ) { Text("REMOVE LOCAL AUTHORIZATION") }
    }
}

@Composable
private fun LocalStatusPanel(
    network: BitVoidLocalNetworkState,
    backend: BitVoidBackendState,
    eventsConnected: Boolean,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusRow("Wi-Fi", network.wifiConnected, if (network.wifiConnected) "connected" else "unavailable")
            StatusRow("Server", backend.isConnected, if (backend.isConnected) "online" else "offline")
            StatusRow("Gateway", network.gatewayOnline, if (network.gatewayOnline) "online" else "not detected")
            StatusRow("Real-time events", eventsConnected, if (eventsConnected) "connected" else "reconnecting / idle")
        }
    }
}

@Composable
private fun StatusRow(label: String, healthy: Boolean, detail: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (healthy) Icons.Filled.CheckCircle else Icons.Filled.Error,
            null,
            tint = if (healthy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(17.dp)
        )
        Text(label, modifier = Modifier.padding(start = 8.dp).weight(1f), fontWeight = FontWeight.Medium)
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
