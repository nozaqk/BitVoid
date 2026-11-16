package com.bitvoid.network.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PresentToAll
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.bitvoid.network.call.BitVoidCallState
import com.bitvoid.network.call.BitVoidScreenProjectionService
import com.bitvoid.network.call.DirectCallPhase
import com.bitvoid.network.call.LiveClassMaterial
import com.bitvoid.network.call.ParticipantRole
import com.bitvoid.network.call.RenderedMaterialDeck
import com.bitvoid.network.call.SignalingPeer
import com.bitvoid.network.call.SignalingSessionMode
import com.bitvoid.network.client.ChannelCallStatus
import com.bitvoid.network.client.ClientChatMessage
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
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Immutable
data class BitVoidCallUiState(
    val callState: BitVoidCallState = BitVoidCallState.IDLE,
    val activeRoomId: String? = null,
    val activeChannelName: String = "",
    val sessionMode: SignalingSessionMode = SignalingSessionMode.VOICE_VIDEO_CALL,
    val localRole: ParticipantRole = ParticipantRole.SPEAKER,
    val myPeerId: String = "",
    val participants: List<SignalingPeer> = emptyList(),
    val isMicMuted: Boolean = false,
    val isCameraEnabled: Boolean = false,
    val isUsingFrontCamera: Boolean = true,
    val isSpeakerphoneOn: Boolean = true,
    val isScreenSharing: Boolean = false,
    val localVideoTrack: VideoTrack? = null,
    val remoteVideoTracks: Map<String, VideoTrack> = emptyMap(),
    val eglBaseContext: EglBase.Context? = null,
    val activeChannelCallStatus: ChannelCallStatus? = null,
    val activeDirectCallStatus: ChannelCallStatus? = null,
    val liveClassCode: String? = null,
    val roomMaterials: List<LiveClassMaterial> = emptyList(),
    val activeMaterialId: String? = null,
    val activePageIndex: Int = 0,
    val isUploadingMaterial: Boolean = false,
    val loadedMaterialDecks: Map<String, RenderedMaterialDeck> = emptyMap(),
    val currentMaterialBitmap: ImageBitmap? = null,
    val isLoadingMaterialPreview: Boolean = false,
    val materialPreviewError: String? = null,
    val callError: String? = null
) {
    fun isInRoom(channelId: String): Boolean {
        return activeRoomId == channelId &&
            (callState == BitVoidCallState.ACTIVE || callState == BitVoidCallState.JOINING)
    }
}

@Immutable
data class BitVoidCallActions(
    val onStartVoiceCall: (channelId: String, channelName: String, token: String?) -> Unit = { _, _, _ -> },
    val onStartLiveClassHost: (channelId: String, channelName: String, token: String?) -> Unit = { _, _, _ -> },
    val onJoinLiveClassStudent: (channelId: String, channelName: String, token: String?) -> Unit = { _, _, _ -> },
    val onJoinLiveClassWithCode: (classCode: String, onDone: () -> Unit) -> Unit = { _, _ -> },
    val onStartDirectCall: (dmId: String, peerUsername: String, token: String) -> Unit = { _, _, _ -> },
    val onAcceptDirectCall: (dmId: String, peerUsername: String, token: String) -> Unit = { _, _, _ -> },
    val onDeclineDirectCall: (dmId: String, token: String) -> Unit = { _, _ -> },
    val onCancelDirectCall: (dmId: String, token: String) -> Unit = { _, _ -> },
    val onUploadClassroomDocument: (roomId: String, uri: Uri, isDirect: Boolean) -> Unit = { _, _, _ -> },
    val onSelectClassroomMaterial: (materialId: String) -> Unit = {},
    val onSetClassroomSlidePage: (pageIndex: Int) -> Unit = {},
    val onOpenMaterialExternally: (material: LiveClassMaterial) -> Unit = {},
    val onToggleMicMute: () -> Unit = {},
    val onToggleCamera: () -> Unit = {},
    val onSwitchCamera: () -> Unit = {},
    val onToggleSpeakerphone: () -> Unit = {},
    val onStartScreenShare: (resultCode: Int, data: Intent) -> Unit = { _, _ -> },
    val onStopScreenShare: () -> Unit = {},
    val onLeaveSession: () -> Unit = {},
    val onEndClassroomSession: () -> Unit = {},
    val onClearCallError: () -> Unit = {}
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BitVoidClassroomScreen(
    channelId: String,
    channelName: String,
    channelToken: String?,
    localCallsign: String,
    channelMessages: List<ClientChatMessage>,
    onSendChatMessage: (String) -> Unit,
    callUiState: BitVoidCallUiState,
    callActions: BitVoidCallActions,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDirectRoom = channelId.startsWith("dm__")
    val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    var showQaChatDrawer by rememberSaveable(channelId) { mutableStateOf(false) }
    var showDocsStage by rememberSaveable(channelId) { mutableStateOf(false) }
    var showJoinClassByIdDialog by rememberSaveable(channelId) { mutableStateOf(false) }
    var joinClassDialogError by remember { mutableStateOf<String?>(null) }
    var isSubmittingClassCode by remember { mutableStateOf(false) }
    var qaDraft by rememberSaveable(channelId) { mutableStateOf("") }
    var pendingPostPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val avPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        pendingPostPermissionAction?.invoke()
        pendingPostPermissionAction = null
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            showDocsStage = true
            callActions.onUploadClassroomDocument(channelId, uri, isDirectRoom)
        }
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            BitVoidScreenProjectionService.startWithConsent(
                context = context,
                resultCode = result.resultCode,
                data = data
            ) { code, projectionIntent ->
                callActions.onStartScreenShare(code, projectionIntent)
            }
        }
    }

    LaunchedEffect(callUiState.roomMaterials.size) {
        if (callUiState.roomMaterials.isNotEmpty()) {
            showDocsStage = true
        }
    }

    LaunchedEffect(callUiState.callError) {
        if (showJoinClassByIdDialog && callUiState.callError != null) {
            joinClassDialogError = callUiState.callError
            isSubmittingClassCode = false
        }
    }

    fun runWithCamMicPermissions(block: () -> Unit) {
        val hasAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val hasCam = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (hasAudio && hasCam) {
            block()
        } else {
            pendingPostPermissionAction = block
            avPermissionLauncher.launch(
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            )
        }
    }

    fun runWithMicPermission(block: () -> Unit) {
        val hasAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasAudio) {
            block()
        } else {
            pendingPostPermissionAction = block
            avPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    fun requestSystemScreenShare() {
        val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return
        screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
    }

    val isJoinedHere = callUiState.isInRoom(channelId)
    val isLiveClassSession = callUiState.sessionMode == SignalingSessionMode.LIVE_CLASSROOM
    val remoteStatus = (if (isDirectRoom) {
        callUiState.activeDirectCallStatus
    } else {
        callUiState.activeChannelCallStatus
    })?.takeIf { it.roomId == channelId }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .background(Color(0xFF0A0E14))
    ) {
        callUiState.callError?.let { err ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF281217))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = err,
                    style = MaterialTheme.typography.labelMedium,
                    color = VoidCrimson,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = callActions.onClearCallError,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Dismiss call notice",
                        tint = VoidCrimson,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        if (!isJoinedHere) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = VoidSurface),
                    border = BorderStroke(1.dp, VoidCyan.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(VoidCyan.copy(alpha = 0.14f))
                                    .border(1.dp, VoidCyan, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.School,
                                    contentDescription = null,
                                    tint = VoidCyan,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isDirectRoom) {
                                        "PRIVATE MEETING // $channelName"
                                    } else {
                                        "LIVE CLASS & PRESENTATION // #$channelName"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = VoidTextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Local Wi-Fi WebRTC · Zero external STUN/TURN · Identity: $localCallsign",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = VoidMint
                                )
                            }
                        }

                        if (remoteStatus?.isActive == true) {
                            Surface(
                                color = Color(0xFF13242D),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, VoidMint.copy(alpha = 0.6f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "ACTIVE SESSION: ${remoteStatus.mode.label.uppercase()} (${remoteStatus.participantCount} PEER(S))",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = VoidMint,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (remoteStatus.peers.isNotEmpty()) {
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            remoteStatus.peers.forEach { p ->
                                                ParticipantBadgeChip(peer = p)
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "Host a Live Classroom with camera and screen presentation, or launch a peer-to-peer voice call across connected Wi-Fi devices.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = VoidTextSecondary
                            )
                        }

                        Button(
                            onClick = {
                                runWithCamMicPermissions {
                                    callActions.onStartLiveClassHost(channelId, channelName, channelToken)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = VoidMint,
                                contentColor = VoidObsidian
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("classroom_start_teacher_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PresentToAll,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "START LIVE CLASS (HOST / TEACHER)",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        Button(
                            onClick = {
                                joinClassDialogError = null
                                callActions.onClearCallError()
                                showJoinClassByIdDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = VoidCyan,
                                contentColor = VoidObsidian
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("classroom_join_student_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Groups,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "JOIN LIVE CLASS (ENTER ID)",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                runWithMicPermission {
                                    callActions.onStartVoiceCall(channelId, channelName, channelToken)
                                }
                            },
                            border = BorderStroke(1.dp, VoidAmber),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidAmber),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("classroom_start_call_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Call,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "START VOICE CALL",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
            if (showJoinClassByIdDialog) {
                JoinLiveClassByIdDialog(
                    isJoining = isSubmittingClassCode,
                    initialCode = "",
                    errorMessage = joinClassDialogError,
                    onDismiss = {
                        showJoinClassByIdDialog = false
                        isSubmittingClassCode = false
                    },
                    onSubmit = { enteredCode ->
                        isSubmittingClassCode = true
                        joinClassDialogError = null
                        callActions.onJoinLiveClassWithCode(enteredCode) {
                            isSubmittingClassCode = false
                            if (callUiState.callError == null) {
                                showJoinClassByIdDialog = false
                            }
                        }
                    }
                )
            }
            return@Column
        }

        // Active Live Class / Voice Call header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(VoidSurface)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (onBack != null) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("classroom_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Hub",
                            tint = VoidTextPrimary
                        )
                    }
                }
                if (callUiState.callState == BitVoidCallState.JOINING) {
                    CircularProgressIndicator(
                        color = VoidMint,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(VoidCrimson)
                    )
                }
                Column {
                    Text(
                        text = if (callUiState.sessionMode == SignalingSessionMode.LIVE_CLASSROOM) {
                            "$channelName · ${callUiState.localRole.label}"
                        } else {
                            "VOICE CALL · ${callUiState.participants.size.coerceAtLeast(1)} PEERS"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = VoidMint,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            if (isDirectRoom && remoteStatus?.let {
                                    it.directCallPhase == DirectCallPhase.RINGING && it.isOutgoing
                                } == true
                            ) {
                                append("Ringing $channelName · waiting for answer")
                            } else {
                                append("${callUiState.participants.size.coerceAtLeast(1)} active")
                            }
                            if (!callUiState.liveClassCode.isNullOrBlank()) {
                                append(" · ID: ${callUiState.liveClassCode}")
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (callUiState.localRole == ParticipantRole.HOST_TEACHER) {
                OutlinedButton(
                    onClick = callActions.onEndClassroomSession,
                    border = BorderStroke(1.dp, VoidCrimson),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidCrimson),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("classroom_end_session_button")
                ) {
                    Text("END CLASS", style = MaterialTheme.typography.labelSmall)
                }
            } else {
                OutlinedButton(
                    onClick = onBack ?: callActions.onLeaveSession,
                    border = BorderStroke(1.dp, VoidCrimson),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidCrimson),
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("classroom_leave_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.CallEnd,
                        contentDescription = "Leave call",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("LEAVE", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = VoidBorder)

        // Stage + Participant Grid + Optional Q&A Chat
        val screenSharingPeer = callUiState.participants.firstOrNull {
            it.peerId != callUiState.myPeerId && it.isScreenSharing
        }
        val teacherPeer = callUiState.participants.firstOrNull { it.role == ParticipantRole.HOST_TEACHER }
        val arrangedParticipants = callUiState.participants.sortedWith(
            compareByDescending<SignalingPeer> { it.isScreenSharing }
                .thenByDescending { it.role == ParticipantRole.HOST_TEACHER }
                .thenBy { it.displayName.lowercase() }
        )
        val remotePresenter = if (isLiveClassSession) {
            screenSharingPeer
                ?.let { peer -> callUiState.remoteVideoTracks[peer.peerId]?.let { peer to it } }
                ?: teacherPeer
                    ?.takeIf { it.peerId != callUiState.myPeerId && it.isVideoOn }
                    ?.let { tp -> callUiState.remoteVideoTracks[tp.peerId]?.let { tp to it } }
                ?: callUiState.remoteVideoTracks.entries
                    .firstOrNull { (pid, _) ->
                        callUiState.participants.firstOrNull { it.peerId == pid }
                            ?.let { it.isVideoOn || it.isScreenSharing } == true
                    }
                    ?.let { (pid, track) ->
                        val p = callUiState.participants.firstOrNull { it.peerId == pid }
                            ?: SignalingPeer(pid, pid, ParticipantRole.SPEAKER, channelId)
                        p to track
                    }
        } else {
            null
        }
        val hasFeaturedMedia = isLiveClassSession &&
            (callUiState.isScreenSharing ||
                remotePresenter != null ||
                (callUiState.localVideoTrack != null && callUiState.isCameraEnabled))

        if (showQaChatDrawer) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    onClick = { showQaChatDrawer = false },
                    shape = RoundedCornerShape(10.dp),
                    color = VoidSurface,
                    border = BorderStroke(1.dp, VoidCyan.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("classroom_back_to_stage_button")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back to Live Class stage",
                                tint = VoidCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "BACK TO VIDEO & SLIDES STAGE",
                                style = MaterialTheme.typography.labelMedium,
                                color = VoidCyan,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (!callUiState.liveClassCode.isNullOrBlank()) {
                            Text(
                                text = callUiState.liveClassCode,
                                style = MaterialTheme.typography.labelSmall,
                                color = VoidMint,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                ClassroomQaChatPanel(
                    channelName = channelName,
                    localCallsign = localCallsign,
                    isDirectRoom = isDirectRoom,
                    messages = channelMessages,
                    draft = qaDraft,
                    onDraftChange = { qaDraft = it },
                    isImeVisible = isImeVisible,
                    onSend = {
                        val clean = qaDraft.trim()
                        if (clean.isNotEmpty()) {
                            onSendChatMessage(clean)
                            qaDraft = ""
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!callUiState.liveClassCode.isNullOrBlank()) {
                    LiveClassIdBanner(classCode = callUiState.liveClassCode)
                }

                if (isLiveClassSession) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                documentPickerLauncher.launch(
                                    arrayOf(
                                        "application/pdf",
                                        "application/vnd.ms-powerpoint",
                                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                        "application/msword",
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        "text/*",
                                        "image/*",
                                        "*/*"
                                    )
                                )
                            },
                            border = BorderStroke(1.dp, VoidAmber),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidAmber),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("classroom_upload_doc_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.UploadFile,
                                contentDescription = "Upload document, PPT, or PDF to live class",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (callUiState.isUploadingMaterial) "UPLOADING..." else "UPLOAD DOC / PPT / PDF",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (callUiState.roomMaterials.isNotEmpty() || showDocsStage) {
                            OutlinedButton(
                                onClick = { showDocsStage = !showDocsStage },
                                border = BorderStroke(1.dp, if (showDocsStage) VoidCyan else VoidBorder),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = if (showDocsStage) VoidCyan else VoidTextSecondary
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("classroom_toggle_docs_stage_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Description,
                                    contentDescription = "Toggle shared slides/documents",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (showDocsStage) {
                                        "HIDE SLIDES"
                                    } else {
                                        "SLIDES (${callUiState.roomMaterials.size})"
                                    },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }

                    if (showDocsStage || callUiState.roomMaterials.isNotEmpty()) {
                        ClassroomDocumentStage(
                            materials = callUiState.roomMaterials,
                            activeMaterialId = callUiState.activeMaterialId,
                            activePageIndex = callUiState.activePageIndex,
                            loadedDecks = callUiState.loadedMaterialDecks,
                            currentPageBitmap = callUiState.currentMaterialBitmap,
                            isUploadingMaterial = callUiState.isUploadingMaterial,
                            isLoadingPreview = callUiState.isLoadingMaterialPreview,
                            previewError = callUiState.materialPreviewError,
                            onUploadDocumentClick = {
                                documentPickerLauncher.launch(
                                    arrayOf(
                                        "application/pdf",
                                        "application/vnd.ms-powerpoint",
                                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                        "application/msword",
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        "text/*",
                                        "image/*",
                                        "*/*"
                                    )
                                )
                            },
                            onSelectMaterial = callActions.onSelectClassroomMaterial,
                            onChangePage = callActions.onSetClassroomSlidePage,
                            onOpenMaterialExternally = callActions.onOpenMaterialExternally
                        )
                    }
                }

                // A large stage is useful only while someone is actively presenting video or a screen.
                // Otherwise participant tiles move up and use the space instead of showing an empty box.
                if (hasFeaturedMedia) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF05070A)),
                        border = BorderStroke(1.5.dp, VoidMint.copy(alpha = 0.65f)),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if ((isLiveClassSession && showDocsStage) || showQaChatDrawer) 170.dp else 220.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            when {
                                isLiveClassSession && callUiState.isScreenSharing -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Cast,
                                            contentDescription = null,
                                            tint = VoidCyan,
                                            modifier = Modifier.size(38.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "SHARING YOUR SCREEN LIVE",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = VoidCyan,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "All connected students & peers see your screen in real time",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = VoidTextSecondary
                                        )
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Button(
                                            onClick = callActions.onStopScreenShare,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = VoidCrimson,
                                                contentColor = VoidTextPrimary
                                            ),
                                            modifier = Modifier.height(48.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.StopScreenShare,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("STOP SCREEN SHARE", style = MaterialTheme.typography.labelMedium)
                                        }
                                    }
                                }

                                remotePresenter != null -> {
                                    val (presenterPeer, presenterTrack) = remotePresenter
                                    WebRtcVideoSurface(
                                        videoTrack = presenterTrack,
                                        eglBaseContext = callUiState.eglBaseContext,
                                        mirror = false,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    StageVideoOverlayLabel(
                                        text = "${presenterPeer.displayName} · ${if (presenterPeer.isScreenSharing) "SCREEN SHARE" else presenterPeer.role.label}",
                                        accent = if (presenterPeer.role == ParticipantRole.HOST_TEACHER) VoidMint else VoidCyan,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(10.dp)
                                    )
                                }

                                isLiveClassSession &&
                                    callUiState.localVideoTrack != null &&
                                    callUiState.isCameraEnabled -> {
                                    WebRtcVideoSurface(
                                        videoTrack = callUiState.localVideoTrack,
                                        eglBaseContext = callUiState.eglBaseContext,
                                        mirror = callUiState.isUsingFrontCamera,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    StageVideoOverlayLabel(
                                        text = "YOU (${callUiState.localRole.label})",
                                        accent = VoidMint,
                                        modifier = Modifier
                                            .align(Alignment.BottomStart)
                                            .padding(10.dp)
                                    )
                                }
                            }

                            // Self PiP thumbnail if watching remote presenter while local camera is enabled
                            if (remotePresenter != null &&
                                callUiState.localVideoTrack != null &&
                                callUiState.isCameraEnabled &&
                                !callUiState.isScreenSharing
                            ) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                        .size(width = 88.dp, height = 112.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .border(1.5.dp, VoidMint, RoundedCornerShape(10.dp))
                                ) {
                                    WebRtcVideoSurface(
                                        videoTrack = callUiState.localVideoTrack,
                                        eglBaseContext = callUiState.eglBaseContext,
                                        mirror = callUiState.isUsingFrontCamera,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }

            // Participant Grid (Speakers & Viewers)
            Text(
                text = "PARTICIPANTS (${callUiState.participants.size})",
                style = MaterialTheme.typography.labelMedium,
                color = VoidCyan,
                fontWeight = FontWeight.Bold
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                arrangedParticipants.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { peer ->
                            val isSelf = peer.peerId == callUiState.myPeerId
                            val videoTrack = if (!isLiveClassSession) {
                                null
                            } else if (isSelf && !callUiState.isScreenSharing) {
                                callUiState.localVideoTrack
                            } else {
                                callUiState.remoteVideoTracks[peer.peerId]
                            }
                            ParticipantGridTile(
                                peer = peer,
                                isSelf = isSelf,
                                videoTrack = if (remotePresenter?.first?.peerId == peer.peerId) null else videoTrack,
                                eglBaseContext = callUiState.eglBaseContext,
                                mirrorSelf = isSelf && callUiState.isUsingFrontCamera,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (pair.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }

            }
        }

        // Bottom Studio Controls Dock (hidden while soft keyboard is open so typing never gets covered)
        if (!isImeVisible) {
            HorizontalDivider(thickness = 1.dp, color = VoidBorder)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(VoidSurface)
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CallDockButton(
                icon = if (callUiState.isMicMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                label = if (callUiState.isMicMuted) "MUTED" else "MIC",
                active = !callUiState.isMicMuted,
                activeColor = VoidMint,
                inactiveColor = VoidCrimson,
                onClick = {
                    if (callUiState.isMicMuted) {
                        if (isLiveClassSession) {
                            runWithCamMicPermissions { callActions.onToggleMicMute() }
                        } else {
                            runWithMicPermission { callActions.onToggleMicMute() }
                        }
                    } else {
                        callActions.onToggleMicMute()
                    }
                },
                testTag = "classroom_mic_button"
            )

            if (isLiveClassSession) {
                CallDockButton(
                    icon = if (callUiState.isCameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                    label = if (callUiState.isCameraEnabled) "CAM ON" else "CAM",
                    active = callUiState.isCameraEnabled,
                    activeColor = VoidMint,
                    inactiveColor = VoidTextSecondary,
                    onClick = {
                        if (!callUiState.isCameraEnabled) {
                            runWithCamMicPermissions { callActions.onToggleCamera() }
                        } else {
                            callActions.onToggleCamera()
                        }
                    },
                    testTag = "classroom_camera_button"
                )

                CallDockButton(
                    icon = Icons.Filled.Cameraswitch,
                    label = "FLIP",
                    active = callUiState.isCameraEnabled,
                    activeColor = VoidCyan,
                    inactiveColor = VoidTextSecondary,
                    onClick = callActions.onSwitchCamera,
                    testTag = "classroom_flip_button"
                )
            }

            CallDockButton(
                icon = if (callUiState.isSpeakerphoneOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                label = if (callUiState.isSpeakerphoneOn) "SPKR" else "EAR",
                active = callUiState.isSpeakerphoneOn,
                activeColor = VoidCyan,
                inactiveColor = VoidTextSecondary,
                onClick = callActions.onToggleSpeakerphone,
                testTag = "classroom_speaker_button"
            )

            if (isLiveClassSession) {
                CallDockButton(
                    icon = if (callUiState.isScreenSharing) Icons.Filled.StopScreenShare else Icons.Filled.PresentToAll,
                    label = if (callUiState.isScreenSharing) "STOP" else "SCREEN",
                    active = callUiState.isScreenSharing,
                    activeColor = VoidAmber,
                    inactiveColor = VoidCyan,
                    onClick = {
                        if (callUiState.isScreenSharing) {
                            callActions.onStopScreenShare()
                        } else {
                            requestSystemScreenShare()
                        }
                    },
                    testTag = "classroom_screenshare_button"
                )
            }

            CallDockButton(
                icon = Icons.Filled.Chat,
                label = if (isDirectRoom) "CHAT" else "Q&A",
                active = showQaChatDrawer,
                activeColor = VoidMint,
                inactiveColor = VoidTextSecondary,
                onClick = { showQaChatDrawer = !showQaChatDrawer },
                testTag = "classroom_chat_toggle_button"
            )
        }
        }
    }
}

@Composable
fun WebRtcVideoSurface(
    videoTrack: VideoTrack?,
    eglBaseContext: EglBase.Context?,
    mirror: Boolean,
    modifier: Modifier = Modifier
) {
    val rendererRef = remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(rendererRef.value, videoTrack) {
        val renderer = rendererRef.value
        if (renderer != null && videoTrack != null) {
            try {
                videoTrack.addSink(renderer)
            } catch (_: Exception) {
            }
        }
        onDispose {
            if (renderer != null && videoTrack != null) {
                try {
                    videoTrack.removeSink(renderer)
                } catch (_: Exception) {
                }
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                setMirror(mirror)
                if (eglBaseContext != null) {
                    try {
                        init(eglBaseContext, null)
                    } catch (_: Exception) {
                    }
                }
                rendererRef.value = this
            }
        },
        update = { view ->
            view.setMirror(mirror)
        },
        onRelease = { view ->
            rendererRef.value = null
            try {
                view.clearImage()
                view.release()
            } catch (_: Exception) {
            }
        }
    )
}

@Composable
private fun StageVideoOverlayLabel(
    text: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xD90D1117))
            .border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ParticipantGridTile(
    peer: SignalingPeer,
    isSelf: Boolean,
    videoTrack: VideoTrack?,
    eglBaseContext: EglBase.Context?,
    mirrorSelf: Boolean,
    modifier: Modifier = Modifier
) {
    val accent = when {
        peer.role == ParticipantRole.HOST_TEACHER -> VoidMint
        !peer.isMuted -> VoidCyan
        else -> VoidBorder
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        border = BorderStroke(1.dp, accent),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.height(92.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (videoTrack != null && (peer.isVideoOn || peer.isScreenSharing)) {
                WebRtcVideoSurface(
                    videoTrack = videoTrack,
                    eglBaseContext = eglBaseContext,
                    mirror = mirrorSelf,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(
                                (if (peer.role == ParticipantRole.HOST_TEACHER) VoidMint else VoidCyan)
                                    .copy(alpha = 0.16f)
                            )
                            .border(
                                1.dp,
                                if (peer.role == ParticipantRole.HOST_TEACHER) VoidMint else VoidCyan,
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = peer.displayName.take(2).uppercase(),
                            style = MaterialTheme.typography.labelMedium,
                            color = VoidTextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isSelf) "${peer.displayName} (YOU)" else peer.displayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = VoidTextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                append(
                                    when (peer.role) {
                                        ParticipantRole.HOST_TEACHER -> "TEACHER"
                                        ParticipantRole.SPEAKER -> "SPEAKER"
                                        ParticipantRole.STUDENT_VIEWER -> "STUDENT"
                                    }
                                )
                                if (peer.isScreenSharing) append(" · SCREEN")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (peer.role == ParticipantRole.HOST_TEACHER) VoidMint else VoidTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = if (peer.isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        contentDescription = if (peer.isMuted) "Muted" else "Speaking",
                        tint = if (peer.isMuted) VoidCrimson else VoidMint,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ParticipantBadgeChip(peer: SignalingPeer) {
    val accent = if (peer.role == ParticipantRole.HOST_TEACHER) VoidMint else VoidCyan
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(VoidObsidian)
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = "${peer.displayName} · ${if (peer.role == ParticipantRole.HOST_TEACHER) "TEACHER" else "PEER"}",
            style = MaterialTheme.typography.labelSmall,
            color = accent
        )
    }
}

@Composable
private fun ClassroomQaChatPanel(
    channelName: String,
    localCallsign: String,
    isDirectRoom: Boolean,
    messages: List<ClientChatMessage>,
    draft: String,
    onDraftChange: (String) -> Unit,
    isImeVisible: Boolean,
    onSend: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, isImeVisible) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        border = BorderStroke(1.dp, VoidBorder),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = if (isDirectRoom) {
                    "MEETING CHAT // $channelName ($localCallsign)"
                } else {
                    "CLASS Q&A CHAT // $channelName ($localCallsign)"
                },
                style = MaterialTheme.typography.labelSmall,
                color = VoidMint,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(VoidObsidian)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Text(
                            text = if (isDirectRoom) {
                                "Messages shared here stay with this private call."
                            } else {
                                "Ask questions here during the Live Class presentation."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = VoidTextSecondary
                        )
                    }
                } else {
                    items(messages, key = { it.id }) { msg ->
                        val isSelf = msg.sender == localCallsign
                        Text(
                            text = if (msg.isSystemNotice) {
                                "[SYS] ${msg.text}"
                            } else {
                                "${if (isSelf) "You" else msg.sender}: ${msg.text}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                msg.isSystemNotice -> VoidCyan
                                isSelf -> VoidMint
                                else -> VoidTextPrimary
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    placeholder = {
                        Text(if (isDirectRoom) "Message $channelName..." else "Ask question in #$channelName...")
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VoidMint,
                        unfocusedBorderColor = VoidBorder,
                        focusedTextColor = VoidTextPrimary,
                        unfocusedTextColor = VoidTextPrimary,
                        cursorColor = VoidMint
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("classroom_qa_input")
                )
                Surface(
                    onClick = onSend,
                    shape = CircleShape,
                    color = VoidMint,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("classroom_qa_send_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send Q&A message",
                            tint = VoidObsidian,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CallDockButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    onClick: () -> Unit,
    testTag: String
) {
    val tint = if (active) activeColor else inactiveColor
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .testTag(testTag)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (active) activeColor.copy(alpha = 0.16f) else VoidObsidian)
                .border(1.dp, if (active) activeColor else VoidBorder, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1
        )
    }
}
