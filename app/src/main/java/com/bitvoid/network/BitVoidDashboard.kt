package com.bitvoid.network

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
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

private val VoidBackground = Color(0xFF0D1117)
private val VoidPanel = Color(0xFF111820)
private val VoidPanelBright = Color(0xFF17212B)
private val VoidGreen = Color(0xFF00FF9C)
private val VoidGreenDark = Color(0xFF063E2C)
private val VoidText = Color(0xFFE6EEE9)
private val VoidMuted = Color(0xFF83919B)
private val VoidLine = Color(0xFF26333D)
private val VoidRed = Color(0xFFFF5D73)
private val VoidAmber = Color(0xFFFFCC66)
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
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 48.sp,
                letterSpacing = (-2).sp,
            ),
            headlineSmall = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
            ),
            titleMedium = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 0.5.sp,
            ),
            bodyLarge = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            labelLarge = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 0.7.sp,
            ),
        ),
        content = content,
    )
}

private fun bitVoidColorScheme(): ColorScheme = darkColorScheme(
    primary = VoidGreen,
    onPrimary = Color(0xFF00291D),
    primaryContainer = VoidGreenDark,
    onPrimaryContainer = VoidGreen,
    secondary = Color(0xFF81E6C1),
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
    criticalPermissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onHostPowerChanged: (Boolean) -> Unit,
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
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
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
                        onPowerChanged = onHostPowerChanged,
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
                    text = "ZERO INTERNET  /  ZERO CLOUD  /  EPHEMERAL SESSIONS",
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "BITVOID",
                color = VoidText,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 30.sp,
                letterSpacing = (-1).sp,
            )
            Text(
                text = "OFF-GRID LOCAL NETWORK",
                color = VoidGreen,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
            )
        }
        val statusColor = when (status) {
            NodeStatus.RUNNING -> VoidGreen
            NodeStatus.STARTING -> VoidAmber
            NodeStatus.ERROR -> VoidRed
            NodeStatus.STOPPED -> VoidMuted
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                .background(statusColor.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = status.name,
                color = statusColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun ModeSwitcher(mode: DashboardMode, onModeChanged: (DashboardMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, VoidLine, RoundedCornerShape(8.dp))
            .background(VoidPanel, RoundedCornerShape(8.dp))
            .padding(4.dp),
    ) {
        DashboardMode.entries.forEach { item ->
            val selected = mode == item
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (selected) VoidGreenDark else Color.Transparent,
                        RoundedCornerShape(5.dp),
                    )
                    .clickable { onModeChanged(item) }
                    .padding(vertical = 13.dp),
            ) {
                Text(
                    text = if (item == DashboardMode.HOST) "HOST NODE" else "CLIENT NODE",
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
            .border(1.dp, VoidAmber.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .background(VoidAmber.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
            .padding(14.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("PERMISSIONS REQUIRED", color = VoidAmber, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "Nearby Wi-Fi and Location access let Android create and discover local nodes.",
                color = VoidMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        TextButton(onClick = onRequestPermissions) {
            Text("GRANT", color = VoidAmber, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun HostDashboard(
    state: BitVoidNodeState,
    mediaItems: List<BitVoidMediaItem>,
    rooms: List<BitVoidRoom>,
    onPowerChanged: (Boolean) -> Unit,
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
        targetValue = if (active) VoidGreen else VoidMuted,
        label = "powerColor",
    )

    SectionLabel("01 / GATEWAY CONTROL")
    Spacer(Modifier.height(20.dp))
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(178.dp)
                .border(1.dp, powerColor.copy(alpha = 0.45f), CircleShape)
                .padding(12.dp)
                .border(2.dp, powerColor, CircleShape)
                .background(
                    if (active) VoidGreen.copy(alpha = 0.09f) else VoidPanel,
                    CircleShape,
                )
                .clickable(enabled = state.status != NodeStatus.STARTING) {
                    onPowerChanged(!active)
                },
        ) {
            if (state.status == NodeStatus.STARTING) {
                CircularProgressIndicator(
                    color = VoidGreen,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(48.dp),
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (active) "■" else "○",
                        color = powerColor,
                        fontSize = 36.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (active) "NODE ONLINE" else "POWER NODE",
                        color = powerColor,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
    Text(
        text = when (state.status) {
            NodeStatus.RUNNING -> if (state.securityMode == HotspotSecurityMode.OPEN) {
                "Open hotspot and local portal are accepting nearby peers with zero Wi-Fi authentication."
            } else {
                "Public server is bound to system Wi-Fi interfaces. Confirm the system hotspot is configured Open."
            }
            NodeStatus.STARTING -> "Requesting BitVoid_Net as an open local-only hotspot…"
            NodeStatus.ERROR -> state.errorMessage ?: "The node could not start."
            NodeStatus.STOPPED -> "Starts a local broadcast with no cloud or internet dependency."
        },
        color = if (state.status == NodeStatus.ERROR) VoidRed else VoidMuted,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, start = 24.dp, end = 24.dp),
    )

    Spacer(Modifier.height(32.dp))
    SectionLabel("02 / LIVE CREDENTIALS")
    Spacer(Modifier.height(12.dp))
    CredentialCard("LOCAL SSID", state.ssid, state.status == NodeStatus.RUNNING)
    Spacer(Modifier.height(8.dp))
    SecurityStatusCard(state)
    if (state.securityMode != HotspotSecurityMode.OPEN) {
        Spacer(Modifier.height(8.dp))
        CredentialCard(
            "NETWORK ACCESS",
            state.passphrase,
            state.status == NodeStatus.RUNNING,
        )
    }
    Spacer(Modifier.height(8.dp))
    CredentialCard("LOCAL PORTAL", state.portalUrl, state.serverOnline)
    Spacer(Modifier.height(10.dp))
    NetworkShareCard(state)
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
    SectionLabel("03 / PUBLIC BROADCAST")
    Spacer(Modifier.height(12.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, VoidGreen.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .background(VoidGreen.copy(alpha = 0.07f), RoundedCornerShape(8.dp))
            .padding(16.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(58.dp)
                .border(1.dp, VoidGreen, RoundedCornerShape(6.dp))
                .background(VoidGreenDark, RoundedCornerShape(6.dp)),
        ) {
            Text("$publicVideoCount", color = VoidGreen, fontWeight = FontWeight.Black, fontSize = 20.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("LIVE PUBLIC VIDEOS", color = VoidGreen, style = MaterialTheme.typography.titleMedium)
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
    PrimaryActionButton(text = "+  ADD VIDEOS", onClick = onPickFiles)
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
                "NO VIDEOS IMPORTED · NEW VIDEOS START PRIVATE",
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
    SectionLabel("04 / PRIVATE ROOMS")
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = {
            showRoomCreator = !showRoomCreator
            roomCreatorMessage = null
        },
        shape = RoundedCornerShape(7.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidGreen),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (showRoomCreator) "CANCEL ROOM CREATION" else "+  CREATE PRIVATE ROOM",
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
                label = "PRIVATE ROOM ID",
                placeholder = "room-alpha",
            )
            TacticalTextField(
                value = roomPasskeyInput,
                onValueChange = { roomPasskeyInput = it; roomCreatorMessage = null },
                label = "OPTIONAL PASSKEY",
                placeholder = "Leave blank to use Room ID only",
                isPassword = true,
            )
            Text(
                "SELECT PRIVATE VIDEOS",
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
                text = "CREATE SECURE ROOM",
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
            "NO ACTIVE PRIVATE ROOMS · ROOM DATA IS EPHEMERAL",
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
private fun SecurityStatusCard(state: BitVoidNodeState) {
    val (label, color) = when (state.securityMode) {
        HotspotSecurityMode.OPEN -> "SECURITY: OPEN (NO PASSWORD REQUIRED)" to VoidGreen
        HotspotSecurityMode.SECURED -> "SECURITY: SECURED (OPEN REQUEST REJECTED)" to VoidRed
        HotspotSecurityMode.SYSTEM_MANAGED -> "SECURITY: SYSTEM MANAGED · VERIFY OPEN MODE" to VoidAmber
        HotspotSecurityMode.UNKNOWN -> if (state.status == NodeStatus.STARTING) {
            "SECURITY: REQUESTING OPEN NETWORK" to VoidMuted
        } else {
            "SECURITY: OPEN NETWORK NOT STARTED" to VoidMuted
        }
    }
    Text(
        text = label,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Black,
        fontSize = 13.sp,
        letterSpacing = 0.7.sp,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 17.dp),
    )
}

@Composable
private fun NetworkShareCard(state: BitVoidNodeState) {
    val context = LocalContext.current
    val open = state.securityMode == HotspotSecurityMode.OPEN
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (open) VoidGreen.copy(alpha = 0.55f) else VoidLine,
                RoundedCornerShape(8.dp),
            )
            .background(if (open) VoidGreen.copy(alpha = 0.06f) else VoidPanel, RoundedCornerShape(8.dp))
            .padding(16.dp),
    ) {
        Text(
            if (open) "ZERO-AUTH JOIN" else "SHARE LOCAL PORTAL",
            color = if (open) VoidGreen else VoidMuted,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            if (open) {
                "Share ${state.ssid} and the local portal. Friends join without a Wi-Fi password; Open Wi-Fi traffic is not link-encrypted."
            } else {
                "BitVoid cannot verify that a system-managed hotspot is open. Share the portal after configuring the system hotspot."
            },
            color = VoidMuted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = { shareNetworkDetails(context, state) },
            enabled = state.status == NodeStatus.RUNNING,
            shape = RoundedCornerShape(7.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = VoidGreen,
                contentColor = Color(0xFF00291D),
                disabledContainerColor = VoidGreenDark,
                disabledContentColor = VoidMuted,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Text(
                if (open) "SHARE OPEN SSID + PORTAL" else "SHARE PORTAL DETAILS",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private fun shareNetworkDetails(context: Context, state: BitVoidNodeState) {
    val wifiPayload = if (state.securityMode == HotspotSecurityMode.OPEN) {
        "WIFI:T:nopass;S:${escapeWifiPayload(state.ssid)};H:false;;"
    } else {
        null
    }
    val text = buildString {
        appendLine("Join BitVoid nearby")
        appendLine("SSID: ${state.ssid}")
        appendLine(
            if (state.securityMode == HotspotSecurityMode.OPEN) {
                "Security: Open — no password required"
            } else {
                "Security: Configure the system hotspot as Open"
            },
        )
        appendLine("Portal: ${state.portalUrl}")
        wifiPayload?.let {
            appendLine()
            append("Wi-Fi QR payload: $it")
        }
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
private fun CredentialCard(label: String, value: String, available: Boolean) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, if (available) VoidGreen.copy(alpha = 0.28f) else VoidLine, RoundedCornerShape(8.dp))
            .background(VoidPanel, RoundedCornerShape(8.dp))
            .clickable(enabled = available) {
                clipboard.setText(AnnotatedString(value))
                Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
            }
            .padding(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = VoidMuted, fontSize = 9.sp, letterSpacing = 1.3.sp)
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
            Text("COPY", color = VoidGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
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

    SectionLabel("01 / PEER DISCOVERY")
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(
            onClick = onScan,
            enabled = !state.isScanning,
            shape = RoundedCornerShape(7.dp),
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
            Text(if (state.isScanning) "SCANNING" else "SCAN NEARBY", style = MaterialTheme.typography.labelLarge)
        }
        if (state.isConnected) {
            OutlinedButton(
                onClick = onDisconnect,
                shape = RoundedCornerShape(7.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidRed),
            ) {
                Text("DISCONNECT", style = MaterialTheme.typography.labelLarge)
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
    SectionLabel("02 / QUICK CONNECT")
    Spacer(Modifier.height(12.dp))
    TacticalTextField(
        value = ssid,
        onValueChange = { ssid = it },
        label = "HOST SSID",
        placeholder = "AndroidShare_0000",
    )
    Spacer(Modifier.height(9.dp))
    TacticalTextField(
        value = passphrase,
        onValueChange = { passphrase = it },
        label = "WPA2 PASSPHRASE",
        placeholder = "Enter host passphrase",
        isPassword = true,
    )
    Spacer(Modifier.height(11.dp))
    PrimaryActionButton(
        text = if (state.isConnecting) "AWAITING ANDROID…" else if (state.isConnected) "CONNECTED TO ${state.connectedSsid}" else "CONNECT TO LOCAL NODE",
        enabled = !state.isConnecting && !state.isConnected,
        onClick = { onConnect(ssid, passphrase) },
        showProgress = state.isConnecting,
    )

    Spacer(Modifier.height(28.dp))
    SectionLabel("03 / SANITIZED VIEWER")
    Spacer(Modifier.height(12.dp))
    TacticalTextField(
        value = portalUrl,
        onValueChange = { portalUrl = it; inputError = null },
        label = "LOCAL PORTAL URL",
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
        shape = RoundedCornerShape(7.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidGreen),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("OPEN SANITIZED LOCAL BROWSER", style = MaterialTheme.typography.labelLarge)
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
        shape = RoundedCornerShape(7.dp),
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
    Text(
        text = text,
        color = VoidGreen,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        letterSpacing = 1.7.sp,
    )
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
        shape = RoundedCornerShape(7.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = VoidGreen,
            contentColor = Color(0xFF00291D),
            disabledContainerColor = VoidGreenDark,
            disabledContentColor = VoidMuted,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp),
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
            setBackgroundColor(android.graphics.Color.rgb(13, 17, 23))
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
                Text("CLOSE", color = VoidGreen, style = MaterialTheme.typography.labelLarge)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("SANITIZED LOCAL VIEWER", color = VoidText, style = MaterialTheme.typography.labelLarge)
                Text(
                    normalizedUrl ?: "INVALID LOCAL URL",
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
