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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.bitvoid.network.ui.theme.VoidAmber
import com.bitvoid.network.ui.theme.VoidBorder
import com.bitvoid.network.ui.theme.VoidCrimson
import com.bitvoid.network.ui.theme.VoidCyan
import com.bitvoid.network.ui.theme.VoidMint
import com.bitvoid.network.ui.theme.VoidObsidian
import com.bitvoid.network.ui.theme.VoidSurface
import com.bitvoid.network.ui.theme.VoidTextPrimary
import com.bitvoid.network.ui.theme.VoidTextSecondary

@Composable
fun BitVoidClientHubContent(
    clientState: ClientState,
    onBack: () -> Unit,
    onScanNetworks: () -> Unit,
    onConnectNode: (String) -> Unit,
    onOpenWifiSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSsid by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(clientState.currentWifiSsid) {
        clientState.currentWifiSsid?.takeIf { it.isNotBlank() }?.let { current ->
            selectedSsid = current
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ClientGatewayBarCard(
            clientState = clientState,
            selectedSsid = selectedSsid,
            onBack = onBack,
            onScanNetworks = onScanNetworks,
            onConnectNode = { onConnectNode(selectedSsid) },
            onSelectDiscoveredNetwork = { selectedSsid = it.ssid },
            onOpenWifiSettings = onOpenWifiSettings
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
                        text = "WI-FI SCAN",
                        style = MaterialTheme.typography.titleMedium,
                        color = VoidTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
                ClientBadgeChip(
                    label = when (clientState.connectionStatus) {
                        ClientConnectionStatus.CONNECTED -> {
                            val count = clientState.linkedNodeUrls.size.coerceAtLeast(1)
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
                text = "BitVoid never joins or switches Wi-Fi here. Connect this phone in Android first, then select that same network to link every local BitVoid node through one shared gateway.",
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
                val linkedCount = clientState.linkedNodeUrls.size.coerceAtLeast(1)
                Text(
                    text = "${linkedCount} ${if (linkedCount == 1) "node" else "nodes"} linked · Shared gateway: ${clientState.targetPortalUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = VoidMint,
                    modifier = Modifier.testTag("client_linked_gateway")
                )
                if (clientState.linkedNodeUrls.size > 1) {
                    Text(
                        text = "All reachable BitVoid nodes on this Wi-Fi are linked and use the same gateway selection.",
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
                        "LINKING BITVOID NODES"
                    } else {
                        "CONNECT NODE"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Text(
                text = "BitVoid silently refreshes all nodes on this Wi-Fi. If none exists, Connect Nodes starts a local gateway on the current Wi-Fi and shares it.",
                style = MaterialTheme.typography.labelSmall,
                color = VoidTextSecondary
            )
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
