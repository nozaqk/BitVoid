package com.bitvoid.network.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitvoid.network.model.AppBottomTab
import com.bitvoid.network.model.HostState
import com.bitvoid.network.model.HotspotStatus
import com.bitvoid.network.ui.theme.VoidAmber
import com.bitvoid.network.ui.theme.VoidBorder
import com.bitvoid.network.ui.theme.VoidCrimson
import com.bitvoid.network.ui.theme.VoidCyan
import com.bitvoid.network.ui.theme.VoidMint
import com.bitvoid.network.ui.theme.VoidMintContainer
import com.bitvoid.network.ui.theme.VoidObsidian
import com.bitvoid.network.ui.theme.VoidSurface
import com.bitvoid.network.ui.theme.VoidTextPrimary
import com.bitvoid.network.ui.theme.VoidTextSecondary

@Composable
fun BitVoidBottomNavigationBar(
    selectedTab: AppBottomTab,
    onSelectTab: (AppBottomTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = VoidBorder)
        NavigationBar(
            containerColor = VoidSurface,
            contentColor = VoidTextPrimary,
            tonalElevation = 0.dp
        ) {
            AppBottomTab.entries.forEach { tab ->
                val selected = selectedTab == tab
                val selectedIcon = when (tab) {
                    AppBottomTab.HOME -> Icons.Filled.Home
                    AppBottomTab.HUB -> Icons.Filled.Forum
                    AppBottomTab.PCHAT -> Icons.Filled.Person
                    AppBottomTab.SETTINGS -> Icons.Filled.Settings
                }
                val unselectedIcon = when (tab) {
                    AppBottomTab.HOME -> Icons.Outlined.Home
                    AppBottomTab.HUB -> Icons.Outlined.Forum
                    AppBottomTab.PCHAT -> Icons.Filled.Person
                    AppBottomTab.SETTINGS -> Icons.Outlined.Settings
                }
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelectTab(tab) },
                    icon = {
                        Icon(
                            imageVector = if (selected) selectedIcon else unselectedIcon,
                            contentDescription = tab.label,
                            modifier = Modifier.size(22.dp)
                        )
                    },
                    label = {
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = VoidMint,
                        selectedTextColor = VoidMint,
                        indicatorColor = VoidMintContainer,
                        unselectedIconColor = VoidTextSecondary,
                        unselectedTextColor = VoidTextSecondary
                    ),
                    modifier = Modifier.testTag("bottom_nav_${tab.name.lowercase()}")
                )
            }
        }
    }
}

@Composable
fun HostNetworkProtocolDialog(
    hostState: HostState,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VoidSurface,
        titleContentColor = VoidCyan,
        textContentColor = VoidTextPrimary,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Router,
                    contentDescription = null,
                    tint = VoidCyan,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "NETWORK PROTOCOL & SECURITY",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(VoidObsidian)
                        .border(1.dp, VoidBorder, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "ACTIVE PROTOCOL STACK",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidMint,
                        fontWeight = FontWeight.Bold
                    )
                    ProtocolDetailRow(
                        "Transport",
                        "HTTP/1.1 :${hostState.port} + trusted HTTPS/WSS :${hostState.securePort}"
                    )
                    ProtocolDetailRow("Streaming", "HTTP 206 Partial Content · 4KB Padded")
                    ProtocolDetailRow("Captive Portal", "HTTP discovery + certificate setup")
                    ProtocolDetailRow("Wireless Security", "WPA2/WPA3 password required")
                    ProtocolDetailRow(
                        "Wireless Broadcast",
                        when (hostState.status) {
                            HotspotStatus.ACTIVE -> "Active · protected SSID: ${hostState.ssid}"
                            HotspotStatus.STARTING -> "Starting protected Android hotspot"
                            else -> "Not active · existing Wi-Fi gateway only"
                        }
                    )
                    if (hostState.isServerRunning) {
                        ProtocolDetailRow(
                            "Direct Gateway",
                            if (hostState.hasClientGatewayAddress) {
                                hostState.networkPortalUrl
                            } else {
                                "Waiting for assigned gateway IPv4"
                            }
                        )
                        if (hostState.gatewayHostname.isNotBlank()) {
                            ProtocolDetailRow("Friendly Gateway", hostState.effectivePortalUrl)
                        }
                        if (hostState.isSecureServerRunning && hostState.hasClientGatewayAddress) {
                            ProtocolDetailRow("Secure Media Gateway", hostState.secureNetworkPortalUrl)
                        }
                    }
                }

                Text(
                    text = "BitVoid does not create or advertise password-free networks. If Android returns an unsecured hotspot, BitVoid refuses it and keeps the vault in server-only mode.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VoidTextSecondary
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = VoidMint,
                    contentColor = VoidObsidian
                ),
                modifier = Modifier.height(48.dp)
            ) {
                Text("DONE", style = MaterialTheme.typography.labelLarge)
            }
        }
    )
}

@Composable
fun HostQrCodeDialog(
    hostState: HostState,
    onCopy: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VoidSurface,
        titleContentColor = VoidMint,
        textContentColor = VoidTextPrimary,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.QrCode2,
                    contentDescription = null,
                    tint = VoidMint,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "NETWORK & PORTAL QR",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                HostWifiQrSection(
                    hostState = hostState,
                    onCopy = onCopy
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.height(42.dp)
            ) {
                Text(
                    text = "CLOSE",
                    style = MaterialTheme.typography.labelLarge,
                    color = VoidMint
                )
            }
        }
    )
}

@Composable
fun SettingsAutonomousCard(
    hostState: HostState,
    missingPermissionsCount: Int,
    onSaveBroadcastCredentials: (String, String) -> Result<Unit>,
    onBroadcastSaved: (String) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenProtocolDialog: () -> Unit,
    onOpenQrDialog: () -> Unit,
    onOpenNodeAccess: () -> Unit,
    onOpenClientNode: () -> Unit,
    onOpenWifiSettings: () -> Unit,
    onOpenHotspotSettings: () -> Unit
) {
    var ssidDraft by rememberSaveable(hostState.configuredSsid) {
        mutableStateOf(hostState.configuredSsid)
    }
    var passphraseDraft by rememberSaveable(hostState.configuredPassphrase) {
        mutableStateOf(hostState.configuredPassphrase)
    }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var credentialError by remember { mutableStateOf<String?>(null) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = VoidMint,
        unfocusedBorderColor = VoidBorder,
        focusedLabelColor = VoidMint,
        unfocusedLabelColor = VoidTextSecondary,
        focusedTextColor = VoidTextPrimary,
        unfocusedTextColor = VoidTextPrimary,
        cursorColor = VoidMint
    )

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Wi-Fi Broadcast Name (SSID) & Password Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = VoidSurface),
            border = BorderStroke(1.dp, VoidCyan.copy(alpha = 0.55f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.WifiTethering,
                        contentDescription = null,
                        tint = VoidCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "WI-FI BROADCAST NAME & PASSWORD",
                        style = MaterialTheme.typography.labelLarge,
                        color = VoidCyan
                    )
                }
                Text(
                    text = "Set the visible SSID and WPA2 password used by the BitVoid hotspot.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VoidTextSecondary
                )

                OutlinedTextField(
                    value = ssidDraft,
                    onValueChange = {
                        ssidDraft = it
                        credentialError = null
                    },
                    label = { Text("Wi-Fi Broadcast Name (SSID)") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_ssid_input")
                )

                OutlinedTextField(
                    value = passphraseDraft,
                    onValueChange = {
                        passphraseDraft = it
                        credentialError = null
                    },
                    label = { Text("Wi-Fi Password (WPA2, min 8 chars)") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { showPassword = !showPassword },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                                tint = VoidTextSecondary
                            )
                        }
                    },
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_passphrase_input")
                )

                if (hostState.status == HotspotStatus.ACTIVE) {
                    Text(
                        text = "Hotspot active — restart Host Engine to apply changes.",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidAmber
                    )
                }

                credentialError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = VoidCrimson
                    )
                }

                Button(
                    onClick = {
                        val res = onSaveBroadcastCredentials(ssidDraft, passphraseDraft)
                        res.onSuccess {
                            credentialError = null
                            onBroadcastSaved("Saved Wi-Fi broadcast credentials (${ssidDraft.trim()})")
                        }.onFailure { err ->
                            credentialError = err.message ?: "Unable to save broadcast settings"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VoidMint,
                        contentColor = VoidObsidian
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("settings_save_broadcast_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("SAVE BROADCAST SETTINGS", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // Local gateway and real radio-broadcast status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = VoidSurface),
            border = BorderStroke(1.dp, VoidMint.copy(alpha = 0.55f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Dns,
                        contentDescription = null,
                        tint = VoidMint,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "LOCAL GATEWAY & RADIO STATUS",
                        style = MaterialTheme.typography.labelLarge,
                        color = VoidMint
                    )
                }
                Text(
                    text = "Gateway availability on the phone's connected Wi-Fi or Android hotspot.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VoidTextSecondary
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(VoidObsidian)
                        .border(1.dp, VoidBorder, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    ProtocolDetailRow(
                        "Engine State",
                        when {
                            !hostState.isServerRunning -> "STANDBY"
                            hostState.hasClientGatewayAddress -> "RUNNING · ${hostState.networkPortalUrl}"
                            else -> "LOCAL · IPv4 PENDING"
                        }
                    )
                    ProtocolDetailRow("Configured SSID", hostState.configuredSsid)
                    ProtocolDetailRow("Security", "WPA2/WPA3")
                    ProtocolDetailRow(
                        "Actual Broadcast",
                        if (hostState.status == HotspotStatus.ACTIVE) {
                            "${hostState.ssid} · ${hostState.securityLabel}"
                        } else {
                            "NONE"
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenProtocolDialog,
                        border = BorderStroke(1.dp, VoidCyan.copy(alpha = 0.65f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidCyan),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("settings_network_protocol_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WifiTethering,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("PROTOCOL", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = onOpenQrDialog,
                        border = BorderStroke(1.dp, VoidMint.copy(alpha = 0.65f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidMint),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("settings_qr_code_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QrCode2,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("QR CODE", style = MaterialTheme.typography.labelMedium)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenNodeAccess,
                        border = BorderStroke(1.dp, VoidMint.copy(alpha = 0.65f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidMint),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("settings_node_access_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("NODE ACCESS", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = onOpenClientNode,
                        border = BorderStroke(1.dp, VoidCyan.copy(alpha = 0.65f)),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidCyan),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("settings_node_log_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WifiFind,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("NODE LOG", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // Privacy & Sandbox Guardrails
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
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        tint = VoidCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "PRIVACY & SANDBOX GUARDRAILS",
                        style = MaterialTheme.typography.labelLarge,
                        color = VoidCyan
                    )
                }
                ProtocolDetailRow("User-Agent", "Stripped -> BitVoid/1.0 (Isolated)")
                ProtocolDetailRow("WebRTC Leaks", "Disabled in Sandboxed WebView")
                ProtocolDetailRow("Disk Cache / Cookies", "Memory-Only · Zero Cookies")
                ProtocolDetailRow("Traffic Padding", "4096-byte Block Aligned Responses")
                ProtocolDetailRow("Firewall Allowlist", "RFC1918 & Loopback Only")
            }
        }

        // Optional System Radio Bridges & Permissions
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Security,
                        contentDescription = null,
                        tint = VoidAmber,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "OPTIONAL EXTERNAL RADIO BRIDGES",
                        style = MaterialTheme.typography.labelLarge,
                        color = VoidAmber
                    )
                }
                Text(
                    text = "Only needed if you choose to bridge external nearby devices over phone Wi-Fi or system Hotspot.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VoidTextSecondary
                )

                if (missingPermissionsCount > 0) {
                    Button(
                        onClick = onRequestPermissions,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = VoidAmber,
                            contentColor = VoidObsidian
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "GRANT $missingPermissionsCount OPTIONAL PERMISSION(S)",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenWifiSettings,
                        border = BorderStroke(1.dp, VoidBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidTextPrimary),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Wifi,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("WI-FI SETTINGS", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = onOpenHotspotSettings,
                        border = BorderStroke(1.dp, VoidBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidTextPrimary),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("HOTSPOT", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProtocolDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = VoidTextSecondary,
            modifier = Modifier
                .weight(0.38f)
                .padding(end = 6.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = VoidTextPrimary,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.62f)
        )
    }
}
