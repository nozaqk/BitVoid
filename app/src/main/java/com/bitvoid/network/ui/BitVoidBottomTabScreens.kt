package com.bitvoid.network.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCopy
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
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.outlined.CloudUpload
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitvoid.network.model.AppBottomTab
import com.bitvoid.network.model.HostState
import com.bitvoid.network.model.HotspotSecurityMode
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
                    AppBottomTab.UPLOAD -> Icons.Filled.CloudUpload
                    AppBottomTab.SETTINGS -> Icons.Filled.Settings
                }
                val unselectedIcon = when (tab) {
                    AppBottomTab.HOME -> Icons.Outlined.Home
                    AppBottomTab.HUB -> Icons.Outlined.Forum
                    AppBottomTab.UPLOAD -> Icons.Outlined.CloudUpload
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
    onSelectMode: (HotspotSecurityMode) -> Unit,
    onDismiss: () -> Unit
) {
    val locked = hostState.isEngineLocked
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
                    ProtocolDetailRow("Transport", "HTTP/1.1 + WebSocket (0.0.0.0:${hostState.port})")
                    ProtocolDetailRow("Streaming", "HTTP 206 Partial Content · 4KB Padded")
                    ProtocolDetailRow("Captive Portal", "HTTP 302 Auto-Redirect Enabled")
                    ProtocolDetailRow("SSID Visibility", "Always Visible (Hidden Network OFF)")
                    ProtocolDetailRow(
                        "Autonomous Mode",
                        "Works without Phone Wi-Fi or Hotspot ON"
                    )
                    if (hostState.isServerRunning) {
                        ProtocolDetailRow("Active Gateway", hostState.effectivePortalUrl)
                    }
                }

                Text(
                    text = if (locked) {
                        "SECURITY MODE (LOCKED WHILE ENGINE IS LIVE)"
                    } else {
                        "SELECT WIRELESS SECURITY MODE"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (locked) VoidAmber else VoidCyan
                )

                HotspotSecurityMode.entries.forEach { option ->
                    val isSelected = hostState.selectedSecurityMode == option
                    val optionAccent =
                        if (option == HotspotSecurityMode.OPEN_VISIBLE) VoidMint else VoidCyan
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) {
                                    optionAccent.copy(alpha = if (locked) 0.06f else 0.12f)
                                } else {
                                    VoidObsidian
                                }
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSelected) {
                                    optionAccent.copy(alpha = if (locked) 0.45f else 0.8f)
                                } else {
                                    VoidBorder
                                },
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable(enabled = !locked) { onSelectMode(option) }
                            .padding(horizontal = 8.dp, vertical = 10.dp)
                            .alpha(if (locked && !isSelected) 0.55f else 1f)
                            .testTag("dialog_security_mode_${option.name.lowercase()}"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = if (locked) null else ({ onSelectMode(option) }),
                            enabled = !locked,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = optionAccent,
                                unselectedColor = VoidTextSecondary,
                                disabledSelectedColor = optionAccent.copy(alpha = 0.65f),
                                disabledUnselectedColor = VoidTextSecondary.copy(alpha = 0.4f)
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.title,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isSelected) optionAccent else VoidTextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = option.description,
                                style = MaterialTheme.typography.bodyMedium,
                                color = VoidTextSecondary
                            )
                        }
                    }
                }
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
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "NETWORK & PORTAL QR CODE",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                HostOpenNetworkQrSection(
                    hostState = hostState,
                    onCopy = onCopy
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.height(48.dp)
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
    myUsername: String,
    missingPermissionsCount: Int,
    onCopyUsername: (String, String) -> Unit,
    onSaveBroadcastCredentials: (String, String) -> Result<Unit>,
    onBroadcastSaved: (String) -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenProtocolDialog: () -> Unit,
    onOpenQrDialog: () -> Unit,
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
        // User Identity (Username ID) Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = VoidSurface),
            border = BorderStroke(1.dp, VoidMint.copy(alpha = 0.55f)),
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
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = VoidMint,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "MY USERNAME (USER ID)",
                        style = MaterialTheme.typography.labelLarge,
                        color = VoidMint
                    )
                }
                Text(
                    text = "Your fixed Username ID cannot be changed. Share it so peers can message you directly from the Hub.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VoidTextSecondary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(VoidObsidian)
                        .border(1.dp, VoidBorder, RoundedCornerShape(10.dp))
                        .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "FIXED USERNAME ID (LOCKED)",
                            style = MaterialTheme.typography.labelSmall,
                            color = VoidCyan
                        )
                        Text(
                            text = "@$myUsername",
                            style = MaterialTheme.typography.titleMedium,
                            color = VoidTextPrimary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("settings_username_value")
                        )
                    }
                    IconButton(
                        onClick = { onCopyUsername("Username ID", myUsername) },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("settings_copy_username_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy Username ID",
                            tint = VoidMint,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

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
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.WifiTethering,
                        contentDescription = null,
                        tint = VoidCyan,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "WI-FI BROADCAST NAME & PASSWORD",
                        style = MaterialTheme.typography.labelLarge,
                        color = VoidCyan
                    )
                }
                Text(
                    text = "Customize your BitVoid wireless broadcast SSID and WPA2 password. SSID broadcast remains visible (never hidden).",
                    style = MaterialTheme.typography.bodyMedium,
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
                    visualTransformation = if (showPassword) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { showPassword = !showPassword },
                            modifier = Modifier.size(48.dp)
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
                        text = "Hardware hotspot is currently active; restart Host Engine on Home to apply new radio credentials.",
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
                        .height(48.dp)
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

        // Autonomous Zero-Wi-Fi / Zero-Hotspot Engine Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = VoidSurface),
            border = BorderStroke(1.dp, VoidMint.copy(alpha = 0.55f)),
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
                        imageVector = Icons.Filled.Dns,
                        contentDescription = null,
                        tint = VoidMint,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "AUTONOMOUS LOCAL ENGINE",
                        style = MaterialTheme.typography.labelLarge,
                        color = VoidMint
                    )
                }
                Text(
                    text = "BitVoid binds its encrypted HTTP/WebSocket vault directly on 0.0.0.0:${hostState.port}. Phone Wi-Fi and Portable Hotspot do NOT need to be turned on to host, upload, stream, or chat.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VoidTextSecondary
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(VoidObsidian)
                        .border(1.dp, VoidBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ProtocolDetailRow(
                        "Engine State",
                        if (hostState.isServerRunning) "RUNNING (${hostState.effectivePortalUrl})" else "STANDBY"
                    )
                    ProtocolDetailRow("Configured SSID", hostState.configuredSsid)
                    ProtocolDetailRow("Security Mode", hostState.selectedSecurityMode.title)
                    ProtocolDetailRow("Hidden SSID", "OFF (Always Discoverable)")
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
                            .height(48.dp)
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
                            .height(48.dp)
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
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = VoidTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
