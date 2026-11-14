package com.bitvoid.network

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale

private val VoidBackground = Color(0xFF080D12)
private val VoidPanel = Color(0xFF101820)
private val VoidPanelBright = Color(0xFF16222C)
private val VoidGreen = Color(0xFF3CE5A4)
private val VoidGreenDark = Color(0xFF123A2E)
private val VoidBlue = Color(0xFF75C7FF)
private val VoidText = Color(0xFFF2F7F5)
private val VoidMuted = Color(0xFF98A7B2)
private val VoidLine = Color(0xFF283741)
private val VoidRed = Color(0xFFFF7085)
private val VoidAmber = Color(0xFFFFC76B)
private val WEBRTC_BLOCK_SCRIPT =
    """
    (() => {
      const disable = name => {
        try { Object.defineProperty(globalThis, name, {value: undefined, configurable: false}); } catch (_) {}
      };
      disable('RTCPeerConnection');
      disable('webkitRTCPeerConnection');
      disable('RTCDataChannel');
      try { Object.defineProperty(navigator, 'mediaDevices', {value: undefined, configurable: false}); } catch (_) {}
    })();
    """.trimIndent()

@Composable
fun BitVoidTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = bitVoidColorScheme(),
        typography = Typography(
            displayLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Black,
                fontSize = 46.sp,
                letterSpacing = (-1.5).sp,
            ),
            headlineSmall = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 21.sp,
                lineHeight = 27.sp,
            ),
            titleMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                lineHeight = 21.sp,
            ),
            bodyLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 15.sp,
                lineHeight = 22.sp,
            ),
            bodyMedium = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            ),
            labelLarge = TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 0.2.sp,
            ),
        ),
        content = content,
    )
}

private fun bitVoidColorScheme(): ColorScheme = darkColorScheme(
    primary = VoidGreen,
    onPrimary = Color(0xFF06251B),
    primaryContainer = VoidGreenDark,
    onPrimaryContainer = VoidGreen,
    secondary = VoidBlue,
    background = VoidBackground,
    onBackground = VoidText,
    surface = VoidPanel,
    onSurface = VoidText,
    surfaceVariant = VoidPanelBright,
    onSurfaceVariant = VoidMuted,
    outline = VoidLine,
    error = VoidRed,
)

private enum class DashboardMode { HOST, CLIENT }

@Composable
fun BitVoidDashboard(
    nodeState: BitVoidNodeState,
    clientState: BitVoidClientState,
    mediaItems: List<BitVoidMediaItem>,
    rooms: List<BitVoidRoom>,
    networkConfig: BitVoidNetworkConfigState,
    criticalPermissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onHostPowerChanged: (Boolean) -> Unit,
    onSecurityModeChanged: (HotspotSecurityMode) -> Boolean,
    onPickFiles: () -> Unit,
    onVideoVisibilityChanged: (String, Boolean) -> Unit,
    onCreateRoom: (String, String?, Set<String>) -> Boolean,
    onScan: () -> Unit,
    onConnect: (String, String) -> Unit,
    onDisconnect: () -> Unit,
    onOpenBrowser: (String) -> Unit,
) {
    var modeName by rememberSaveable { mutableStateOf(DashboardMode.HOST.name) }
    val mode = DashboardMode.valueOf(modeName)

    Surface(color = VoidBackground, modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(VoidBackground)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 760.dp)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                VoidGreen.copy(alpha = 0.055f),
                                Color.Transparent,
                                Color.Transparent,
                            ),
                        ),
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            ) {
                DashboardHeader(nodeState.status)
                Spacer(Modifier.height(24.dp))
                ModeSwitcher(
                    mode = mode,
                    onModeChanged = { modeName = it.name },
                )
                if (!criticalPermissionsGranted) {
                    Spacer(Modifier.height(14.dp))
                    PermissionCard(onRequestPermissions)
                }
                Spacer(Modifier.height(28.dp))
                when (mode) {
                    DashboardMode.HOST -> HostDashboard(
                        state = nodeState,
                        mediaItems = mediaItems,
                        rooms = rooms,
                        networkConfig = networkConfig,
                        onPowerChanged = onHostPowerChanged,
                        onSecurityModeChanged = onSecurityModeChanged,
                        onPickFiles = onPickFiles,
                        onVideoVisibilityChanged = onVideoVisibilityChanged,
                        onCreateRoom = onCreateRoom,
                    )

                    DashboardMode.CLIENT -> ClientDashboard(
                        state = clientState,
                        onScan = onScan,
                        onConnect = onConnect,
                        onDisconnect = onDisconnect,
                        onOpenBrowser = onOpenBrowser,
                    )
                }
                Spacer(Modifier.height(32.dp))
                HorizontalDivider(color = VoidLine)
                Text(
                    text = "Private by design  •  Local by default  •  No cloud required",
                    color = VoidMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 18.dp, bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun DashboardHeader(status: NodeStatus) {
    val statusColor = when (status) {
        NodeStatus.RUNNING -> VoidGreen
        NodeStatus.STARTING, NodeStatus.WAITING_FOR_HOTSPOT -> VoidAmber
        NodeStatus.ERROR -> VoidRed
        NodeStatus.STOPPED -> VoidMuted
    }
    val statusLabel = when (status) {
        NodeStatus.RUNNING -> "Online"
        NodeStatus.STARTING -> "Starting"
        NodeStatus.WAITING_FOR_HOTSPOT -> "Setup needed"
        NodeStatus.ERROR -> "Needs attention"
        NodeStatus.STOPPED -> "Offline"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(48.dp)
                .background(VoidGreen, RoundedCornerShape(14.dp)),
        ) {
            Text(
                "BV",
                color = Color(0xFF06251B),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 17.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "BitVoid",
                color = VoidText,
                fontWeight = FontWeight.Black,
                fontSize = 25.sp,
                letterSpacing = (-0.6).sp,
            )
            Text(
                text = "Private local sharing",
                color = VoidMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .border(1.dp, statusColor.copy(alpha = 0.35f), RoundedCornerShape(50.dp))
                .background(statusColor.copy(alpha = 0.09f), RoundedCornerShape(50.dp))
                .padding(horizontal = 11.dp, vertical = 8.dp),
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = statusLabel,
                color = statusColor,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ModeSwitcher(mode: DashboardMode, onModeChanged: (DashboardMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, VoidLine, RoundedCornerShape(14.dp))
            .background(VoidPanel, RoundedCornerShape(14.dp))
            .padding(5.dp),
    ) {
        DashboardMode.entries.forEach { item ->
            val selected = mode == item
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (selected) VoidGreenDark else Color.Transparent,
                        RoundedCornerShape(10.dp),
                    )
                    .clickable { onModeChanged(item) }
                    .padding(vertical = 13.dp),
            ) {
                Text(
                    text = if (item == DashboardMode.HOST) "Host & share" else "Join & watch",
                    color = if (selected) VoidGreen else VoidMuted,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun PermissionCard(onRequestPermissions: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, VoidAmber.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
            .background(VoidAmber.copy(alpha = 0.06f), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Permissions required", color = VoidAmber, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Nearby Wi-Fi and Location access let Android create and discover local nodes.",
                color = VoidMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = onRequestPermissions) {
            Text("Grant", color = VoidAmber, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun HostDashboard(
    state: BitVoidNodeState,
    mediaItems: List<BitVoidMediaItem>,
    rooms: List<BitVoidRoom>,
    networkConfig: BitVoidNetworkConfigState,
    onPowerChanged: (Boolean) -> Unit,
    onSecurityModeChanged: (HotspotSecurityMode) -> Boolean,
    onPickFiles: () -> Unit,
    onVideoVisibilityChanged: (String, Boolean) -> Unit,
    onCreateRoom: (String, String?, Set<String>) -> Boolean,
) {
    val active = state.isActive
    val publicVideoCount = mediaItems.count { it.isPublic }
    var showRoomCreator by rememberSaveable { mutableStateOf(false) }
    var roomIdInput by rememberSaveable { mutableStateOf("") }
    var roomPasskeyInput by rememberSaveable { mutableStateOf("") }
    var selectedRoomVideos by remember { mutableStateOf<Set<String>>(emptySet()) }
    var roomCreatorMessage by remember { mutableStateOf<String?>(null) }
    val powerColor by animateColorAsState(
        targetValue = when (state.status) {
            NodeStatus.RUNNING -> VoidGreen
            NodeStatus.STARTING, NodeStatus.WAITING_FOR_HOTSPOT -> VoidAmber
            NodeStatus.ERROR -> VoidRed
            NodeStatus.STOPPED -> VoidMuted
        },
        label = "powerColor",
    )

    SectionLabel("00 / Network security")
    Spacer(Modifier.height(12.dp))
    NetworkSecuritySelector(
        selectedMode = networkConfig.securityMode,
        enabled = !active,
        onModeSelected = onSecurityModeChanged,
    )
    Spacer(Modifier.height(28.dp))

    SectionLabel("01 / Host engine")
    Spacer(Modifier.height(20.dp))
    HostEngineCard(
        state = state,
        selectedMode = networkConfig.securityMode,
        active = active,
        accentColor = powerColor,
        onPowerChanged = onPowerChanged,
    )

    Spacer(Modifier.height(32.dp))
    SectionLabel("02 / Connection details")
    Spacer(Modifier.height(12.dp))
    CredentialCard("Network name (SSID)", state.ssid, state.status == NodeStatus.RUNNING)
    Spacer(Modifier.height(8.dp))
    SecurityStatusCard(state)
    if (state.securityMode == HotspotSecurityMode.WPA2_VISIBLE) {
        Spacer(Modifier.height(8.dp))
        CredentialCard(
            "WPA2 passphrase",
            state.passphrase,
            state.status == NodeStatus.RUNNING,
        )
    }
    Spacer(Modifier.height(8.dp))
    CredentialCard("Local portal", state.portalUrl, state.serverOnline)
    Spacer(Modifier.height(10.dp))
    NetworkShareCard(state)
    if (state.status == NodeStatus.WAITING_FOR_HOTSPOT) {
        Spacer(Modifier.height(10.dp))
        OpenHotspotSetupCard()
    }
    state.networkNotice?.let { notice ->
        Spacer(Modifier.height(8.dp))
        Text(
            text = notice,
            color = VoidAmber,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, VoidAmber.copy(alpha = 0.35f), RoundedCornerShape(7.dp))
                .background(VoidAmber.copy(alpha = 0.05f), RoundedCornerShape(7.dp))
                .padding(12.dp),
        )
    }

    Spacer(Modifier.height(28.dp))
    SectionLabel("03 / Public library")
    Spacer(Modifier.height(12.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, VoidGreen.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .background(VoidGreen.copy(alpha = 0.055f), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(58.dp)
                .border(1.dp, VoidGreen.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                .background(VoidGreenDark, RoundedCornerShape(14.dp)),
        ) {
            Text("$publicVideoCount", color = VoidGreen, fontWeight = FontWeight.Black, fontSize = 20.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Public videos", color = VoidGreen, style = MaterialTheme.typography.titleMedium)
            Text(
                "$publicVideoCount of ${mediaItems.size} video${if (mediaItems.size == 1) "" else "s"} visible to connected viewers",
                color = VoidMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Box(
            Modifier
                .size(9.dp)
                .background(if (publicVideoCount > 0 && state.serverOnline) VoidGreen else VoidMuted, CircleShape),
        )
    }
    Spacer(Modifier.height(10.dp))
    PrimaryActionButton(text = "Add videos", onClick = onPickFiles)
    Spacer(Modifier.height(14.dp))
    if (mediaItems.isEmpty()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, VoidLine, RoundedCornerShape(8.dp))
                .background(VoidPanel, RoundedCornerShape(8.dp))
                .padding(28.dp),
        ) {
            Text(
                "No videos yet. New imports are private by default.",
                color = VoidMuted,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            mediaItems.forEach { item ->
                VideoVisibilityRow(
                    item = item,
                    onVisibilityChanged = { isPublic ->
                        onVideoVisibilityChanged(item.id, isPublic)
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(30.dp))
    SectionLabel("04 / Private rooms")
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = {
            showRoomCreator = !showRoomCreator
            roomCreatorMessage = null
        },
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidGreen),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (showRoomCreator) "Cancel" else "Create private room",
            style = MaterialTheme.typography.labelLarge,
        )
    }

    if (showRoomCreator) {
        Spacer(Modifier.height(10.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, VoidGreen.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .background(VoidPanel, RoundedCornerShape(8.dp))
                .padding(14.dp),
        ) {
            TacticalTextField(
                value = roomIdInput,
                onValueChange = { roomIdInput = it; roomCreatorMessage = null },
                label = "Private room ID",
                placeholder = "room-alpha",
            )
            TacticalTextField(
                value = roomPasskeyInput,
                onValueChange = { roomPasskeyInput = it; roomCreatorMessage = null },
                label = "Optional passkey",
                placeholder = "Leave blank to use Room ID only",
                isPassword = true,
            )
            Text(
                "Select private videos",
                color = VoidMuted,
                fontSize = 9.sp,
                letterSpacing = 1.2.sp,
            )
            val privateVideos = mediaItems.filterNot { it.isPublic }
            if (privateVideos.isEmpty()) {
                Text(
                    "Mark at least one imported video Private before creating a room.",
                    color = VoidAmber,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                privateVideos.forEach { video ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, VoidLine, RoundedCornerShape(6.dp))
                            .clickable {
                                selectedRoomVideos = if (video.id in selectedRoomVideos) {
                                    selectedRoomVideos - video.id
                                } else {
                                    selectedRoomVideos + video.id
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Checkbox(
                            checked = video.id in selectedRoomVideos,
                            onCheckedChange = { checked ->
                                selectedRoomVideos = if (checked) {
                                    selectedRoomVideos + video.id
                                } else {
                                    selectedRoomVideos - video.id
                                }
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = VoidGreen,
                                uncheckedColor = VoidMuted,
                                checkmarkColor = VoidBackground,
                            ),
                        )
                        Text(
                            video.title,
                            color = VoidText,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            PrimaryActionButton(
                text = "Create secure room",
                enabled = roomIdInput.isNotBlank() && selectedRoomVideos.isNotEmpty(),
                onClick = {
                    val created = onCreateRoom(
                        roomIdInput.trim(),
                        roomPasskeyInput.takeIf { it.isNotBlank() },
                        selectedRoomVideos,
                    )
                    if (created) {
                        roomCreatorMessage = "ROOM ${roomIdInput.trim()} CREATED"
                        roomIdInput = ""
                        roomPasskeyInput = ""
                        selectedRoomVideos = emptySet()
                        showRoomCreator = false
                    } else {
                        roomCreatorMessage = "Room ID must be 3–32 letters, numbers, _ or -, and must be unique."
                    }
                },
            )
        }
    }

    roomCreatorMessage?.let { message ->
        Text(
            message,
            color = if (message.startsWith("ROOM ")) VoidGreen else VoidRed,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 9.dp),
        )
    }

    if (rooms.isEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text(
            "No active private rooms. Room data is kept only for this session.",
            color = VoidMuted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, VoidLine, RoundedCornerShape(8.dp))
                .padding(18.dp),
        )
    } else {
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rooms.forEach { room ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, VoidLine, RoundedCornerShape(8.dp))
                        .background(VoidPanel, RoundedCornerShape(8.dp))
                        .padding(14.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("ROOM ${room.roomId}", color = VoidText, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${room.videoIds.size} PRIVATE VIDEO${if (room.videoIds.size == 1) "" else "S"} · ${if (room.hasPasskey) "PASSKEY ON" else "ROOM ID ONLY"}",
                            color = VoidMuted,
                            fontSize = 9.sp,
                        )
                        Text(
                            if (room.activeParticipantNames.isEmpty()) {
                                "ACTIVE: NONE"
                            } else {
                                "ACTIVE: ${room.activeParticipantNames.joinToString(", ")}"
                            },
                            color = if (room.activeParticipants > 0) VoidGreen else VoidMuted,
                            fontSize = 9.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            room.activeParticipants.toString(),
                            color = if (room.activeParticipants > 0) VoidGreen else VoidMuted,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black,
                        )
                        Text("IN CHAT", color = VoidMuted, fontSize = 8.sp, letterSpacing = 1.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun HostEngineCard(
    state: BitVoidNodeState,
    selectedMode: HotspotSecurityMode,
    active: Boolean,
    accentColor: Color,
    onPowerChanged: (Boolean) -> Unit,
) {
    val modeLabel = if (selectedMode == HotspotSecurityMode.OPEN_VISIBLE) {
        "Open · no password"
    } else {
        "WPA2 · password protected"
    }
    val description = when (state.status) {
        NodeStatus.RUNNING -> if (state.securityMode == HotspotSecurityMode.OPEN_VISIBLE) {
            "Your Open network and local portal are ready for nearby guests."
        } else {
            "Your protected local network and portal are ready for nearby guests."
        }
        NodeStatus.STARTING -> "Android is preparing the selected network and local portal."
        NodeStatus.WAITING_FOR_HOTSPOT ->
            "Complete the hotspot setup below. Detection will continue automatically."
        NodeStatus.ERROR -> state.errorMessage ?: "The host engine needs attention before it can start."
        NodeStatus.STOPPED -> "Start an offline sharing session with no internet or cloud dependency."
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accentColor.copy(alpha = 0.32f), RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.11f),
                        VoidPanel,
                        VoidPanel,
                    ),
                ),
                RoundedCornerShape(18.dp),
            )
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(46.dp)
                    .background(accentColor.copy(alpha = 0.14f), RoundedCornerShape(13.dp))
                    .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(13.dp)),
            ) {
                Box(
                    Modifier
                        .size(11.dp)
                        .background(accentColor, CircleShape),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Host engine", color = VoidText, style = MaterialTheme.typography.headlineSmall)
                Text(modeLabel, color = VoidMuted, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                when (state.status) {
                    NodeStatus.RUNNING -> "LIVE"
                    NodeStatus.STARTING -> "STARTING"
                    NodeStatus.WAITING_FOR_HOTSPOT -> "SETUP"
                    NodeStatus.ERROR -> "ERROR"
                    NodeStatus.STOPPED -> "READY"
                },
                color = accentColor,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp,
                modifier = Modifier
                    .background(accentColor.copy(alpha = 0.1f), RoundedCornerShape(50.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Text(
            description,
            color = if (state.status == NodeStatus.ERROR) VoidRed else VoidMuted,
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(
            onClick = { onPowerChanged(!active) },
            enabled = state.status != NodeStatus.STARTING,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (active) VoidRed.copy(alpha = 0.16f) else VoidGreen,
                contentColor = if (active) VoidRed else Color(0xFF06251B),
                disabledContainerColor = VoidPanelBright,
                disabledContentColor = VoidMuted,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
        ) {
            if (state.status == NodeStatus.STARTING) {
                CircularProgressIndicator(
                    color = VoidMuted,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(9.dp))
            }
            Text(
                when {
                    state.status == NodeStatus.STARTING -> "Starting host engine…"
                    active -> "Stop host engine"
                    state.status == NodeStatus.ERROR -> "Try again"
                    else -> "Start host engine"
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun VideoVisibilityRow(
    item: BitVoidMediaItem,
    onVisibilityChanged: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (item.isPublic) VoidGreen.copy(alpha = 0.5f) else VoidLine,
                RoundedCornerShape(8.dp),
            )
            .background(
                if (item.isPublic) VoidGreen.copy(alpha = 0.05f) else VoidPanel,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                color = VoidText,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "${formatMediaSize(item.size)} · ${if (item.isPublic) "BROADCASTING NOW" else "HOST ONLY"}",
                color = if (item.isPublic) VoidGreen else VoidMuted,
                fontSize = 9.sp,
                letterSpacing = 0.6.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (item.isPublic) "PUBLIC" else "PRIVATE",
                color = if (item.isPublic) VoidGreen else VoidAmber,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
            )
            Switch(
                checked = item.isPublic,
                onCheckedChange = onVisibilityChanged,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = VoidBackground,
                    checkedTrackColor = VoidGreen,
                    uncheckedThumbColor = VoidMuted,
                    uncheckedTrackColor = VoidPanelBright,
                    uncheckedBorderColor = VoidLine,
                ),
            )
        }
    }
}

private fun formatMediaSize(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1_024.0
        unit += 1
    } while (value >= 1_024 && unit < units.lastIndex)
    return String.format(Locale.US, "%.1f %s", value, units[unit])
}

@Composable
private fun NetworkSecuritySelector(
    selectedMode: HotspotSecurityMode,
    enabled: Boolean,
    onModeSelected: (HotspotSecurityMode) -> Boolean,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, VoidLine, RoundedCornerShape(18.dp))
            .background(VoidPanel, RoundedCornerShape(18.dp))
            .padding(14.dp),
    ) {
        Text("Choose how guests connect", color = VoidText, style = MaterialTheme.typography.titleMedium)
        Text(
            "Both options broadcast a visible network. You can change this before the host starts.",
            color = VoidMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        HotspotSecurityMode.entries.forEach { mode ->
            val selected = mode == selectedMode
            val isOpen = mode == HotspotSecurityMode.OPEN_VISIBLE
            val badgeColor = if (isOpen) VoidAmber else VoidBlue
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (selected) VoidGreen.copy(alpha = 0.62f) else VoidLine,
                        RoundedCornerShape(14.dp),
                    )
                    .background(
                        if (selected) VoidGreen.copy(alpha = 0.065f) else VoidPanelBright,
                        RoundedCornerShape(14.dp),
                    )
                    .clickable(enabled = enabled) { onModeSelected(mode) }
                    .padding(horizontal = 10.dp, vertical = 11.dp),
            ) {
                RadioButton(
                    selected = selected,
                    onClick = { if (enabled) onModeSelected(mode) },
                    enabled = enabled,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = VoidGreen,
                        unselectedColor = VoidMuted,
                        disabledSelectedColor = VoidGreen.copy(alpha = 0.5f),
                        disabledUnselectedColor = VoidMuted.copy(alpha = 0.5f),
                    ),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (isOpen) "Open network" else "WPA2 network",
                            color = if (selected) VoidText else VoidMuted,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            if (isOpen) "NO PASSWORD" else "RECOMMENDED",
                            color = badgeColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 9.sp,
                            letterSpacing = 0.5.sp,
                            modifier = Modifier
                                .background(badgeColor.copy(alpha = 0.1f), RoundedCornerShape(50.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                    Text(
                        if (isOpen) {
                            "Fastest guest access. Configure Android hotspot security as None/Open."
                        } else {
                            "Android generates a protected network name and passphrase for you."
                        },
                        color = VoidMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        if (!enabled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(VoidAmber.copy(alpha = 0.07f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 11.dp, vertical = 9.dp),
            ) {
                Box(Modifier.size(7.dp).background(VoidAmber, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Network selection is locked while the host engine is active.",
                    color = VoidAmber,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun SecurityStatusCard(state: BitVoidNodeState) {
    val (label, color) = when {
        state.status == NodeStatus.WAITING_FOR_HOTSPOT ->
            "Open hotspot setup required" to VoidAmber
        state.securityMode == HotspotSecurityMode.OPEN_VISIBLE && state.status == NodeStatus.RUNNING ->
            "Open network · No password required" to VoidGreen
        state.securityMode == HotspotSecurityMode.WPA2_VISIBLE && state.status == NodeStatus.RUNNING ->
            "WPA2 network · Password required" to VoidGreen
        state.status == NodeStatus.STARTING ->
            "Preparing ${state.securityMode.name.replace('_', ' ').lowercase()}" to VoidMuted
        else -> "${state.securityMode.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }} selected" to VoidMuted
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.07f), RoundedCornerShape(12.dp))
            .padding(horizontal = 15.dp, vertical = 14.dp),
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            color = color,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun NetworkShareCard(state: BitVoidNodeState) {
    val context = LocalContext.current
    val open = state.securityMode == HotspotSecurityMode.OPEN_VISIBLE
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (open) VoidGreen.copy(alpha = 0.55f) else VoidLine,
                RoundedCornerShape(16.dp),
            )
            .background(if (open) VoidGreen.copy(alpha = 0.055f) else VoidPanel, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(
            if (open) "Share Open network" else "Share WPA2 access",
            color = if (state.status == NodeStatus.RUNNING) VoidGreen else VoidMuted,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            if (open) {
                "Share ${state.ssid} and the local portal. Friends join without a Wi-Fi password; Open Wi-Fi traffic is not link-encrypted."
            } else {
                "Share Android's generated visible SSID, WPA2 passphrase, and the local portal."
            },
            color = VoidMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = { shareNetworkDetails(context, state) },
            enabled = state.status == NodeStatus.RUNNING,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = VoidGreen,
                contentColor = Color(0xFF06251B),
                disabledContainerColor = VoidGreenDark,
                disabledContentColor = VoidMuted,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(
                if (open) "Share network details" else "Share network & passphrase",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private fun shareNetworkDetails(context: Context, state: BitVoidNodeState) {
    val open = state.securityMode == HotspotSecurityMode.OPEN_VISIBLE
    val wifiPayload = if (open) {
        "WIFI:T:nopass;S:${escapeWifiPayload(state.ssid)};H:false;;"
    } else {
        "WIFI:T:WPA;S:${escapeWifiPayload(state.ssid)};P:${escapeWifiPayload(state.passphrase)};H:false;;"
    }
    val text = buildString {
        appendLine("Join BitVoid nearby")
        appendLine("SSID: ${state.ssid}")
        if (open) {
            appendLine("Security: Open — no password required")
        } else {
            appendLine("Security: WPA2")
            appendLine("Passphrase: ${state.passphrase}")
        }
        appendLine("Hidden network: Off")
        appendLine("Portal: ${state.portalUrl}")
        appendLine()
        append("Wi-Fi QR payload: $wifiPayload")
    }.trim()
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Join ${state.ssid}")
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(
            Intent.createChooser(shareIntent, "Share BitVoid network")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        Toast.makeText(context, "No compatible sharing app is available", Toast.LENGTH_SHORT).show()
    }
}

private fun escapeWifiPayload(value: String): String = value
    .replace("\\", "\\\\")
    .replace(";", "\\;")
    .replace(",", "\\,")
    .replace(":", "\\:")

@Composable
private fun OpenHotspotSetupCard() {
    val context = LocalContext.current
    Column(
        verticalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, VoidAmber.copy(alpha = 0.42f), RoundedCornerShape(16.dp))
            .background(VoidAmber.copy(alpha = 0.07f), RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text("Finish Open hotspot setup", color = VoidAmber, style = MaterialTheme.typography.titleMedium)
        Text(
            "In Android hotspot settings set Network name to ${BitVoidNetworkConfig.DEFAULT_SSID}, Security to None/Open, and Hidden Network to Off. Return to BitVoid; detection continues automatically.",
            color = VoidText,
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = { openWirelessHotspotSettings(context) },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidAmber),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
        ) {
            Text("Open Android hotspot settings", style = MaterialTheme.typography.labelLarge)
        }
    }
}

private fun openWirelessHotspotSettings(context: Context) {
    val flags = Intent.FLAG_ACTIVITY_NEW_TASK
    val tetherSettings = Intent().apply {
        setClassName("com.android.settings", "com.android.settings.TetherSettings")
        addFlags(flags)
    }
    val opened = runCatching { context.startActivity(tetherSettings) }.isSuccess
    if (opened) return
    val fallback = Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(flags)
    runCatching { context.startActivity(fallback) }
        .onFailure {
            Toast.makeText(context, "Wireless settings are unavailable on this device", Toast.LENGTH_LONG).show()
        }
}

@Composable
private fun CredentialCard(label: String, value: String, available: Boolean) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (available) VoidGreen.copy(alpha = 0.25f) else VoidLine, RoundedCornerShape(12.dp))
            .background(VoidPanel, RoundedCornerShape(12.dp))
            .clickable(enabled = available) {
                clipboard.setText(AnnotatedString(value))
                Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
            }
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = VoidMuted, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
            Spacer(Modifier.height(5.dp))
            Text(
                text = value,
                color = if (available) VoidText else VoidMuted,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (available) {
            Text("Copy", color = VoidGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ClientDashboard(
    state: BitVoidClientState,
    onScan: () -> Unit,
    onConnect: (String, String) -> Unit,
    onDisconnect: () -> Unit,
    onOpenBrowser: (String) -> Unit,
) {
    var ssid by rememberSaveable { mutableStateOf("") }
    var passphrase by rememberSaveable { mutableStateOf("") }
    var portalUrl by rememberSaveable { mutableStateOf(state.portalUrl) }
    var inputError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.connectedSsid, state.portalUrl) {
        state.connectedSsid?.let { ssid = it }
        if (state.isConnected) portalUrl = state.portalUrl
    }

    SectionLabel("01 / Nearby networks")
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = onScan,
            enabled = !state.isScanning,
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidGreen),
            modifier = Modifier.weight(1f),
        ) {
            if (state.isScanning) {
                CircularProgressIndicator(
                    color = VoidGreen,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(if (state.isScanning) "Scanning…" else "Scan nearby", style = MaterialTheme.typography.labelLarge)
        }
        if (state.isConnected) {
            OutlinedButton(
                onClick = onDisconnect,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidRed),
            ) {
                Text("Disconnect", style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    if (state.networks.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            state.networks.take(6).forEach { network ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (network.isBitVoidNamed) VoidGreen.copy(alpha = 0.45f) else VoidLine,
                            RoundedCornerShape(7.dp),
                        )
                        .background(VoidPanel, RoundedCornerShape(7.dp))
                        .clickable {
                            ssid = network.ssid
                            passphrase = ""
                        }
                        .padding(13.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            network.ssid,
                            color = VoidText,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (network.isBitVoidNamed) "BITVOID SIGNATURE" else "LOCAL WI-FI · VERIFY HOST SSID",
                            color = if (network.isBitVoidNamed) VoidGreen else VoidMuted,
                            fontSize = 9.sp,
                        )
                    }
                    SignalMeter(network.signalLevel)
                    Spacer(Modifier.width(10.dp))
                    Text(if (network.secured) "LOCK" else "OPEN", color = VoidMuted, fontSize = 9.sp)
                }
            }
        }
    }

    Spacer(Modifier.height(28.dp))
    SectionLabel("02 / Connect manually")
    Spacer(Modifier.height(12.dp))
    TacticalTextField(
        value = ssid,
        onValueChange = { ssid = it },
        label = "Network name (SSID)",
        placeholder = "BitVoid_Net",
    )
    Spacer(Modifier.height(9.dp))
    TacticalTextField(
        value = passphrase,
        onValueChange = { passphrase = it },
        label = "Passphrase (leave blank for Open)",
        placeholder = "Enter host passphrase",
        isPassword = true,
    )
    Spacer(Modifier.height(11.dp))
    PrimaryActionButton(
        text = if (state.isConnecting) "AWAITING ANDROID…" else if (state.isConnected) "CONNECTED TO ${state.connectedSsid}" else "Connect to local node",
        enabled = !state.isConnecting && !state.isConnected,
        onClick = { onConnect(ssid, passphrase) },
        showProgress = state.isConnecting,
    )

    Spacer(Modifier.height(28.dp))
    SectionLabel("03 / Private viewer")
    Spacer(Modifier.height(12.dp))
    TacticalTextField(
        value = portalUrl,
        onValueChange = { portalUrl = it; inputError = null },
        label = "Local portal address",
        placeholder = "http://192.168.43.1:8080",
        keyboardType = KeyboardType.Uri,
    )
    Spacer(Modifier.height(10.dp))
    OutlinedButton(
        onClick = {
            val normalized = normalizeLocalPortalUrl(portalUrl)
            if (normalized == null) {
                inputError = "Only private or link-local HTTP addresses on port 8080 are allowed."
            } else {
                inputError = null
                onOpenBrowser(normalized)
            }
        },
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidGreen),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Open private local viewer", style = MaterialTheme.typography.labelLarge)
    }

    val error = inputError ?: state.errorMessage
    if (error != null) {
        Text(
            text = error,
            color = VoidRed,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    Spacer(Modifier.height(12.dp))
    Text(
        "Local portal JavaScript is enabled for rooms and chat. WebRTC, cookies, persistent cache, external hosts, camera, microphone, and geolocation remain blocked.",
        color = VoidMuted,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun SignalMeter(level: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        repeat(4) { index ->
            Box(
                Modifier
                    .width(3.dp)
                    .height((5 + index * 3).dp)
                    .background(if (index < level) VoidGreen else VoidLine),
            )
        }
    }
}

@Composable
private fun TacticalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 10.sp, letterSpacing = 1.sp) },
        placeholder = { Text(placeholder, color = VoidMuted, fontSize = 12.sp) },
        singleLine = true,
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = VoidGreen,
            unfocusedBorderColor = VoidLine,
            focusedLabelColor = VoidGreen,
            unfocusedLabelColor = VoidMuted,
            cursorColor = VoidGreen,
            focusedContainerColor = VoidPanel,
            unfocusedContainerColor = VoidPanel,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SectionLabel(text: String) {
    val number = text.substringBefore('/').trim()
    val title = text.substringAfter('/', text).trim()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = number,
            color = VoidGreen,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            modifier = Modifier
                .background(VoidGreen.copy(alpha = 0.1f), RoundedCornerShape(7.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            color = VoidText,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        HorizontalDivider(color = VoidLine, modifier = Modifier.width(36.dp))
    }
}

@Composable
private fun PrimaryActionButton(
    text: String,
    enabled: Boolean = true,
    showProgress: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = VoidGreen,
            contentColor = Color(0xFF06251B),
            disabledContainerColor = VoidGreenDark,
            disabledContentColor = VoidMuted,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
    ) {
        if (showProgress) {
            CircularProgressIndicator(
                color = VoidGreen,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(9.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A local-address-only WebView backed by a header-sanitizing URL interceptor. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SanitizedLocalBrowser(url: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val normalizedUrl = remember(url) { normalizeLocalPortalUrl(url) }
    val webView = remember {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.rgb(8, 13, 18))
            WebView.setWebContentsDebuggingEnabled(false)
            settings.apply {
                javaScriptEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                domStorageEnabled = false
                databaseEnabled = false
                setGeolocationEnabled(false)
                allowFileAccess = false
                allowContentAccess = false
                cacheMode = WebSettings.LOAD_NO_CACHE
                userAgentString = "BitVoid"
                mediaPlaybackRequiresUserGesture = true
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                builtInZoomControls = true
                displayZoomControls = false
                safeBrowsingEnabled = true
            }
            val localWebView = this
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(
                    localWebView,
                    WEBRTC_BLOCK_SCRIPT,
                    setOf("*"),
                )
            }
            CookieManager.getInstance().apply {
                setAcceptCookie(false)
                setAcceptThirdPartyCookies(localWebView, false)
                removeAllCookies(null)
                flush()
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest?) {
                    request?.deny()
                }
            }
            webViewClient = SanitizedWebViewClient()
            setDownloadListener { _, _, _, _, _ ->
                Toast.makeText(
                    context,
                    "Downloads stay blocked in the sandbox; media can be streamed here.",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onClose()
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.clearCache(true)
            webView.removeAllViews()
            CookieManager.getInstance().removeAllCookies(null)
            webView.destroy()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(VoidBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(VoidPanel)
                .border(0.dp, Color.Transparent)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            TextButton(onClick = onClose) {
                Text("Close", color = VoidGreen, style = MaterialTheme.typography.labelLarge)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Private local viewer", color = VoidText, style = MaterialTheme.typography.labelLarge)
                Text(
                    normalizedUrl ?: "Invalid local URL",
                    color = VoidMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(
                Modifier
                    .size(8.dp)
                    .background(if (normalizedUrl != null) VoidGreen else VoidRed, CircleShape),
            )
            Spacer(Modifier.width(10.dp))
        }
        HorizontalDivider(color = VoidLine)
        if (normalizedUrl == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "The viewer accepts private HTTP addresses on port 8080 only.",
                    color = VoidRed,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            AndroidView(
                factory = { webView },
                update = { view ->
                    if (view.url != normalizedUrl) {
                        view.loadUrl(
                            normalizedUrl,
                            mapOf(
                                "User-Agent" to "BitVoid",
                                "Cache-Control" to "no-store",
                            ),
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

private class SanitizedWebViewClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val requested = request?.url ?: return true
        return if (isAllowedLocalPortalUri(requested)) {
            false
        } else {
            true
        }
    }

    @Deprecated("Deprecated in the Android WebView API")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        return url?.let { normalizeLocalPortalUrl(it) == null } ?: true
    }

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        val requestValue = request ?: return blockedResponse()
        if (!isAllowedLocalPortalUri(requestValue.url)) return blockedResponse()
        // WebResourceRequest does not expose a POST body. Let WebView send local
        // room-authentication POSTs directly; cookies and device permissions remain disabled.
        if (requestValue.method != "GET" && requestValue.method != "HEAD") return null
        return fetchSanitized(requestValue)
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        view?.clearCache(false)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            view?.evaluateJavascript(WEBRTC_BLOCK_SCRIPT, null)
        }
        super.onPageStarted(view, url, favicon)
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        handler?.cancel()
    }

    private fun fetchSanitized(request: WebResourceRequest): WebResourceResponse {
        var connection: HttpURLConnection? = null
        return try {
            val connected = (URL(request.url.toString()).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                useCaches = false
                connectTimeout = 8_000
                readTimeout = 30_000
                requestMethod = request.method
                setRequestProperty("User-Agent", "BitVoid")
                setRequestProperty("Accept", "*/*")
                request.requestHeaders.entries
                    .firstOrNull { (name, _) -> name.equals("Range", ignoreCase = true) }
                    ?.value
                    ?.let { setRequestProperty("Range", it) }
                request.requestHeaders.entries
                    .firstOrNull { (name, _) -> name.equals("X-Room-Token", ignoreCase = true) }
                    ?.value
                    ?.let { setRequestProperty("X-Room-Token", it) }
                setRequestProperty("Cache-Control", "no-store")
                connect()
            }
            connection = connected
            val statusCode = connected.responseCode
            if (statusCode in 300..399) {
                connected.disconnect()
                return blockedResponse("Redirects are blocked")
            }
            val contentType = connected.contentType.orEmpty()
            val mimeType = contentType.substringBefore(';').ifBlank { "application/octet-stream" }
            val encoding = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE)
                .find(contentType)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?: "utf-8"
            val responseHeaders = connected.headerFields
                .mapNotNull { (key, values) ->
                    key?.takeUnless {
                        it.equals("set-cookie", ignoreCase = true) ||
                            it.equals("set-cookie2", ignoreCase = true) ||
                            it.equals("server", ignoreCase = true)
                    }?.let { it to values.joinToString(", ") }
                }
                .toMap()
            val source = if (statusCode >= 400) connected.errorStream else connected.inputStream
            val input = DisconnectingInputStream(
                source ?: ByteArrayInputStream(ByteArray(0)),
                connected,
            )
            WebResourceResponse(
                mimeType,
                encoding,
                statusCode,
                connected.responseMessage ?: "Local response",
                responseHeaders,
                input,
            )
        } catch (_: Exception) {
            connection?.disconnect()
            blockedResponse("Local node unavailable", 502)
        }
    }

    private fun blockedResponse(message: String = "Blocked by BitVoid privacy policy", code: Int = 403): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            "utf-8",
            code,
            message,
            mapOf("Cache-Control" to "no-store"),
            ByteArrayInputStream(message.toByteArray()),
        )

    private class DisconnectingInputStream(
        input: InputStream,
        private val connection: HttpURLConnection,
    ) : FilterInputStream(input) {
        override fun close() {
            try {
                super.close()
            } finally {
                connection.disconnect()
            }
        }
    }
}

private fun normalizeLocalPortalUrl(value: String): String? {
    val candidate = value.trim().let {
        if (it.startsWith("http://", ignoreCase = true)) it else "http://$it"
    }
    val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
    if (!uri.scheme.equals("http", ignoreCase = true)) return null
    if (uri.userInfo != null || uri.host.isNullOrBlank()) return null
    val port = if (uri.port == -1) 80 else uri.port
    if (port != BitVoidLocalServer.DEFAULT_PORT) return null
    if (!isPrivateOrLinkLocalHost(uri.host)) return null
    val path = uri.rawPath?.ifBlank { "/" } ?: "/"
    val query = uri.rawQuery?.let { "?$it" }.orEmpty()
    return "http://${formatHostForUrl(uri.host)}:$port$path$query"
}

private fun isAllowedLocalPortalUri(uri: Uri): Boolean {
    if (!uri.scheme.equals("http", ignoreCase = true)) return false
    val port = if (uri.port == -1) 80 else uri.port
    return port == BitVoidLocalServer.DEFAULT_PORT && isPrivateOrLinkLocalHost(uri.host.orEmpty())
}

private fun isPrivateOrLinkLocalHost(hostValue: String): Boolean {
    val host = hostValue.removePrefix("[").removeSuffix("]").lowercase(Locale.US)
    if (host == "localhost" || host == "::1") return true
    if (host.contains(':')) {
        return host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")
    }
    val parts = host.split('.').mapNotNull { it.toIntOrNull() }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    return parts[0] == 10 ||
        parts[0] == 127 ||
        parts[0] == 169 && parts[1] == 254 ||
        parts[0] == 192 && parts[1] == 168 ||
        parts[0] == 172 && parts[1] in 16..31 ||
        parts[0] == 100 && parts[1] in 64..127
}

private fun formatHostForUrl(host: String): String =
    if (host.contains(':') && !host.startsWith('[')) "[$host]" else host
