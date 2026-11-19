package com.bitvoid.network.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitvoid.network.model.ClientConnectionStatus
import com.bitvoid.network.model.ClientState
import com.bitvoid.network.model.DiscoveredNetwork
import com.bitvoid.network.model.NodeConnectionEventType
import com.bitvoid.network.model.NodeConnectionLogEntry
import com.bitvoid.network.ui.theme.VoidAmber
import com.bitvoid.network.ui.theme.VoidBorder
import com.bitvoid.network.ui.theme.VoidCrimson
import com.bitvoid.network.ui.theme.VoidCyan
import com.bitvoid.network.ui.theme.VoidMint
import com.bitvoid.network.ui.theme.VoidObsidian
import com.bitvoid.network.ui.theme.VoidSurface
import com.bitvoid.network.ui.theme.VoidTextPrimary
import com.bitvoid.network.ui.theme.VoidTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BitVoidClientHubContent(
    clientState: ClientState,
    connectionLogs: List<NodeConnectionLogEntry>,
    onBack: () -> Unit,
    onScanNetworks: () -> Unit,
    onConnectNode: (String) -> Unit,
    onConnectGatewayUrl: (String) -> Unit,
    onOpenWifiSettings: () -> Unit,
    onClearConnectionLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSsid by rememberSaveable { mutableStateOf("") }
    var gatewayAddress by rememberSaveable { mutableStateOf(clientState.targetPortalUrl) }

    LaunchedEffect(clientState.currentWifiSsid) {
        clientState.currentWifiSsid?.takeIf { it.isNotBlank() }?.let { current ->
            selectedSsid = current
        }
    }
    LaunchedEffect(clientState.connectionStatus, clientState.targetPortalUrl) {
        if (clientState.connectionStatus == ClientConnectionStatus.CONNECTED) {
            gatewayAddress = clientState.targetPortalUrl
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ClientGatewayBarCard(
            clientState = clientState,
            selectedSsid = selectedSsid,
            onBack = onBack,
            onScanNetworks = onScanNetworks,
            onConnectNode = { onConnectNode(selectedSsid) },
            gatewayAddress = gatewayAddress,
            onGatewayAddressChange = { gatewayAddress = it },
            onConnectGatewayUrl = { onConnectGatewayUrl(gatewayAddress) },
            onSelectDiscoveredNetwork = { selectedSsid = it.ssid },
            onOpenWifiSettings = onOpenWifiSettings
        )
        NodeConnectionLogCard(
            logs = connectionLogs,
            onClear = onClearConnectionLogs
        )
    }
}

@Composable
private fun ClientGatewayBarCard(
    clientState: ClientState,
    selectedSsid: String,
    onBack: () -> Unit,
    onScanNetworks: () -> Unit,
    onConnectNode: () -> Unit,
    gatewayAddress: String,
    onGatewayAddressChange: (String) -> Unit,
    onConnectGatewayUrl: () -> Unit,
    onSelectDiscoveredNetwork: (DiscoveredNetwork) -> Unit,
    onOpenWifiSettings: () -> Unit
) {
    val currentWifi = clientState.currentWifiSsid
    val selectedIsCurrent = selectedSsid.isBlank() ||
        currentWifi == null ||
        selectedSsid.equals(currentWifi, ignoreCase = true)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("client_wifi_scan_card"),
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        border = BorderStroke(1.dp, VoidBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("client_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Home",
                            tint = VoidMint,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = "NODE LOG & MESH",
                        style = MaterialTheme.typography.titleMedium,
                        color = VoidTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                ClientBadgeChip(
                    label = when (clientState.connectionStatus) {
                        ClientConnectionStatus.CONNECTED -> {
                            val count = clientState.meshNodeCount.coerceAtLeast(1)
                            "$count ${if (count == 1) "NODE" else "NODES"} LINKED"
                        }
                        ClientConnectionStatus.CONNECTING -> "FINDING NODES"
                        ClientConnectionStatus.SCANNING -> "SCANNING"
                        ClientConnectionStatus.ERROR -> "CHECK WI-FI"
                        ClientConnectionStatus.DISCONNECTED -> "READY"
                    },
                    accent = when (clientState.connectionStatus) {
                        ClientConnectionStatus.CONNECTED -> VoidMint
                        ClientConnectionStatus.CONNECTING, ClientConnectionStatus.SCANNING -> VoidCyan
                        ClientConnectionStatus.ERROR -> VoidCrimson
                        ClientConnectionStatus.DISCONNECTED -> VoidTextSecondary
                    }
                )
            }

            Text(
                text = "BitVoid automatically scans Wi-Fi and links app, ESP32, and Pi identities on Android's current network. A local gateway IP/URL can also be entered as a fallback.",
                style = MaterialTheme.typography.bodyMedium,
                color = VoidTextSecondary
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(VoidObsidian)
                    .border(1.dp, VoidMint.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Wifi,
                    contentDescription = null,
                    tint = if (currentWifi != null) VoidMint else VoidTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = currentWifi ?: "No active Wi-Fi detected",
                        style = MaterialTheme.typography.titleSmall,
                        color = VoidTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "PHONE'S CURRENT WI-FI",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidTextSecondary
                    )
                }
                IconButton(
                    onClick = onOpenWifiSettings,
                    modifier = Modifier
                        .size(44.dp)
                        .testTag("client_open_wifi_settings")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Open Android Wi-Fi settings",
                        tint = VoidCyan
                    )
                }
            }

            OutlinedTextField(
                value = gatewayAddress,
                onValueChange = onGatewayAddressChange,
                label = { Text("GATEWAY IP OR URL") },
                supportingText = {
                    Text("Automatic discovery is preferred; use a local IP/URL as a fallback.")
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("client_gateway_url_input")
            )
            Button(
                onClick = onConnectGatewayUrl,
                enabled = gatewayAddress.isNotBlank() &&
                    !clientState.isScanning &&
                    clientState.connectionStatus != ClientConnectionStatus.CONNECTING,
                colors = ButtonDefaults.buttonColors(
                    containerColor = VoidMint,
                    contentColor = VoidObsidian
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .testTag("client_connect_gateway_url")
            ) {
                Text("CONNECT GATEWAY")
            }

            Button(
                onClick = onScanNetworks,
                enabled = !clientState.isScanning,
                colors = ButtonDefaults.buttonColors(
                    containerColor = VoidCyan,
                    contentColor = VoidObsidian
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("client_scan_wifi_button")
            ) {
                if (clientState.isScanning) {
                    CircularProgressIndicator(
                        color = VoidObsidian,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.WifiFind,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (clientState.isScanning) "SCANNING WI-FI" else "SCAN AVAILABLE WI-FI",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            if (clientState.discoveredNetworks.isNotEmpty()) {
                HorizontalDivider(color = VoidBorder)
                Text(
                    text = "AVAILABLE WI-FI",
                    style = MaterialTheme.typography.labelLarge,
                    color = VoidCyan
                )
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    clientState.discoveredNetworks.take(10).forEach { network ->
                        DiscoveredBeaconChipRow(
                            network = network,
                            isCurrentWifi = network.ssid.equals(currentWifi, ignoreCase = true),
                            isSelected = network.ssid.equals(selectedSsid, ignoreCase = true),
                            onSelect = { onSelectDiscoveredNetwork(network) }
                        )
                    }
                }
            }

            if (selectedSsid.isNotBlank()) {
                Text(
                    text = "Selected: $selectedSsid${if (selectedIsCurrent) "" else " · connect phone to this Wi-Fi first"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selectedIsCurrent) VoidMint else VoidAmber,
                    modifier = Modifier.testTag("client_selected_wifi")
                )
            }

            clientState.errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = VoidCrimson,
                    modifier = Modifier.testTag("client_wifi_error")
                )
            }

            if (clientState.connectionStatus == ClientConnectionStatus.CONNECTED) {
                val linkedCount = clientState.meshNodeCount.coerceAtLeast(1)
                Text(
                    text = "${linkedCount} ${if (linkedCount == 1) "node" else "nodes"} linked · Shared gateway: ${clientState.targetPortalUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = VoidMint,
                    modifier = Modifier.testTag("client_linked_gateway")
                )
                if (clientState.meshNodeCount > 1) {
                    Text(
                        text = "${clientState.linkedNodeUrls.size} app gateway(s) · ${clientState.coordinatorUrls.size} ESP32/Pi coordinator(s) · all use the same LAN identity protocol.",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidTextSecondary,
                        modifier = Modifier.testTag("client_linked_node_count")
                    )
                }
            }

            Button(
                onClick = onConnectNode,
                enabled = !clientState.isScanning &&
                    clientState.connectionStatus != ClientConnectionStatus.CONNECTING &&
                    (selectedSsid.isNotBlank() || currentWifi != null),
                colors = ButtonDefaults.buttonColors(
                    containerColor = VoidMint,
                    contentColor = VoidObsidian
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("client_connect_node_button")
            ) {
                if (clientState.connectionStatus == ClientConnectionStatus.CONNECTING) {
                    CircularProgressIndicator(
                        color = VoidObsidian,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Wifi,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (clientState.connectionStatus == ClientConnectionStatus.CONNECTING) {
                        "REFRESHING BITVOID MESH"
                    } else {
                        "REFRESH MESH NOW"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Text(
                text = "Mesh discovery refreshes every 15 seconds and Wi-Fi names refresh automatically. If no app gateway exists, Node Log starts one on this phone for every device on the same Wi-Fi.",
                style = MaterialTheme.typography.labelSmall,
                color = VoidTextSecondary
            )
        }
    }
}

@Composable
private fun NodeConnectionLogCard(
    logs: List<NodeConnectionLogEntry>,
    onClear: () -> Unit
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("node_connection_log"),
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        border = BorderStroke(1.dp, VoidCyan.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CONNECTION EVENT LOG",
                        style = MaterialTheme.typography.titleSmall,
                        color = VoidCyan,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${logs.size} in-memory event(s) · newest first",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidTextSecondary
                    )
                }
                TextButton(
                    onClick = onClear,
                    modifier = Modifier.testTag("clear_node_connection_log")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("CLEAR")
                }
            }

            HorizontalDivider(color = VoidBorder)

            if (logs.isEmpty()) {
                Text(
                    text = "No events yet. Automatic same-LAN discovery runs in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VoidTextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                logs.take(50).forEach { entry ->
                    val accent = when {
                        entry.isError || entry.type == NodeConnectionEventType.ERROR -> VoidCrimson
                        entry.type == NodeConnectionEventType.CONNECTED -> VoidMint
                        entry.type == NodeConnectionEventType.COORDINATOR ||
                            entry.type == NodeConnectionEventType.GATEWAY -> VoidCyan
                        entry.type == NodeConnectionEventType.RETRY -> VoidAmber
                        else -> VoidTextSecondary
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(VoidObsidian)
                            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = timeFormatter.format(Date(entry.timestampMillis)),
                            style = MaterialTheme.typography.labelSmall,
                            color = accent
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${entry.type.name} · ${entry.title}",
                                style = MaterialTheme.typography.labelMedium,
                                color = VoidTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = entry.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = VoidTextSecondary
                            )
                        }
                    }
                }
                if (logs.size > 50) {
                    Text(
                        text = "Showing the latest 50 of ${logs.size} events.",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidTextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun DiscoveredBeaconChipRow(
    network: DiscoveredNetwork,
    isCurrentWifi: Boolean,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) VoidMint.copy(alpha = 0.08f) else VoidObsidian)
            .border(
                1.dp,
                when {
                    isSelected -> VoidMint
                    isCurrentWifi -> VoidCyan.copy(alpha = 0.7f)
                    else -> VoidBorder
                },
                RoundedCornerShape(8.dp)
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 9.dp)
            .testTag("client_wifi_${network.ssid}"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = network.ssid,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected || isCurrentWifi) VoidMint else VoidTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(network.securityLabel)
                    append(" · ")
                    append(network.signalDbm)
                    append(" dBm")
                    if (isCurrentWifi) append(" · PHONE CONNECTED")
                },
                style = MaterialTheme.typography.labelSmall,
                color = VoidTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = if (isSelected) "SELECTED" else "USE THIS WI-FI",
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) VoidMint else VoidCyan
        )
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
