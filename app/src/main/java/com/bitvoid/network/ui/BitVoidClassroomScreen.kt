package com.bitvoid.network.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bitvoid.network.call.BitVoidCallState
import com.bitvoid.network.call.DirectCallPhase
import com.bitvoid.network.call.ParticipantRole
import com.bitvoid.network.call.SignalingPeer
import com.bitvoid.network.call.SignalingSessionMode
import com.bitvoid.network.client.ChannelCallStatus
import com.bitvoid.network.ui.theme.VoidAmber
import com.bitvoid.network.ui.theme.VoidBorder
import com.bitvoid.network.ui.theme.VoidCrimson
import com.bitvoid.network.ui.theme.VoidCyan
import com.bitvoid.network.ui.theme.VoidMint
import com.bitvoid.network.ui.theme.VoidObsidian
import com.bitvoid.network.ui.theme.VoidSurface
import com.bitvoid.network.ui.theme.VoidSurfaceElevated
import com.bitvoid.network.ui.theme.VoidTextPrimary
import com.bitvoid.network.ui.theme.VoidTextSecondary

@Immutable
data class BitVoidCallUiState(
    val callState: BitVoidCallState = BitVoidCallState.IDLE,
    val activeRoomId: String? = null,
    val activeChannelName: String = "",
    val sessionMode: SignalingSessionMode = SignalingSessionMode.AUDIO_CALL,
    val localRole: ParticipantRole = ParticipantRole.SPEAKER,
    val myPeerId: String = "",
    val participants: List<SignalingPeer> = emptyList(),
    val isMicMuted: Boolean = false,
    val isSpeakerphoneOn: Boolean = true,
    val activeChannelCallStatus: ChannelCallStatus? = null,
    val activeDirectCallStatus: ChannelCallStatus? = null,
    val liveClassCode: String? = null,
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
    val onToggleMicMute: () -> Unit = {},
    val onToggleSpeakerphone: () -> Unit = {},
    val onLeaveSession: () -> Unit = {},
    val onEndClassroomSession: () -> Unit = {},
    val onClearCallError: () -> Unit = {}
)

@Composable
fun BitVoidClassroomScreen(
    channelId: String,
    channelName: String,
    channelToken: String?,
    localCallsign: String,
    callUiState: BitVoidCallUiState,
    callActions: BitVoidCallActions,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isDirectRoom = channelId.startsWith("dm__")
    var showJoinDialog by rememberSaveable(channelId) { mutableStateOf(false) }
    var joinError by remember { mutableStateOf<String?>(null) }
    var isJoiningByCode by remember { mutableStateOf(false) }
    var pendingAudioAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) pendingAudioAction?.invoke()
        pendingAudioAction = null
    }

    fun withMicrophonePermission(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            pendingAudioAction = action
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(callUiState.callError) {
        if (showJoinDialog && callUiState.callError != null) {
            joinError = callUiState.callError
            isJoiningByCode = false
        }
    }

    val isJoinedHere = callUiState.isInRoom(channelId)
    val remoteStatus = (if (isDirectRoom) {
        callUiState.activeDirectCallStatus
    } else {
        callUiState.activeChannelCallStatus
    })?.takeIf { it.roomId == channelId }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E14))
    ) {
        callUiState.callError?.let { error ->
            CallErrorBanner(error = error, onDismiss = callActions.onClearCallError)
        }

        if (!isJoinedHere) {
            AudioSessionLobby(
                channelName = channelName,
                localCallsign = localCallsign,
                isDirectRoom = isDirectRoom,
                remoteStatus = remoteStatus,
                onStartVoiceCall = {
                    withMicrophonePermission {
                        callActions.onStartVoiceCall(channelId, channelName, channelToken)
                    }
                },
                onStartLiveClass = {
                    withMicrophonePermission {
                        callActions.onStartLiveClassHost(channelId, channelName, channelToken)
                    }
                },
                onJoinLiveClass = {
                    joinError = null
                    callActions.onClearCallError()
                    showJoinDialog = true
                },
                modifier = Modifier.weight(1f)
            )
        } else {
            AudioSessionHeader(
                channelName = channelName,
                isDirectRoom = isDirectRoom,
                callUiState = callUiState,
                remoteStatus = remoteStatus,
                onLeave = callActions.onLeaveSession,
                onEndClass = callActions.onEndClassroomSession
            )
            HorizontalDivider(thickness = 1.dp, color = VoidBorder)

            AudioParticipantStage(
                participants = callUiState.participants,
                myPeerId = callUiState.myPeerId,
                localCallsign = localCallsign,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

            AudioControls(
                isMicMuted = callUiState.isMicMuted,
                isSpeakerphoneOn = callUiState.isSpeakerphoneOn,
                onToggleMic = {
                    if (callUiState.isMicMuted) {
                        withMicrophonePermission(callActions.onToggleMicMute)
                    } else {
                        callActions.onToggleMicMute()
                    }
                },
                onToggleSpeakerphone = callActions.onToggleSpeakerphone
            )
        }
    }

    if (showJoinDialog) {
        JoinLiveClassByIdDialog(
            isJoining = isJoiningByCode,
            errorMessage = joinError,
            onDismiss = {
                showJoinDialog = false
                isJoiningByCode = false
            },
            onSubmit = { code ->
                isJoiningByCode = true
                joinError = null
                withMicrophonePermission {
                    callActions.onJoinLiveClassWithCode(code) {
                        isJoiningByCode = false
                        if (callUiState.callError == null) showJoinDialog = false
                    }
                }
            }
        )
    }
}

@Composable
private fun CallErrorBanner(error: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF281217))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = error,
            style = MaterialTheme.typography.labelMedium,
            color = VoidCrimson,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Filled.Close, contentDescription = "Dismiss call notice", tint = VoidCrimson)
        }
    }
}

@Composable
private fun AudioSessionLobby(
    channelName: String,
    localCallsign: String,
    isDirectRoom: Boolean,
    remoteStatus: ChannelCallStatus?,
    onStartVoiceCall: () -> Unit,
    onStartLiveClass: () -> Unit,
    onJoinLiveClass: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Card(
            colors = CardDefaults.cardColors(containerColor = VoidSurface),
            border = BorderStroke(1.dp, VoidCyan.copy(alpha = 0.55f)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(VoidCyan.copy(alpha = 0.14f))
                            .border(1.dp, VoidCyan, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isDirectRoom) Icons.Filled.Call else Icons.Filled.School,
                            contentDescription = null,
                            tint = VoidCyan
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isDirectRoom) "PRIVATE AUDIO CALL // $channelName" else "AUDIO ROOM // #$channelName",
                            style = MaterialTheme.typography.titleMedium,
                            color = VoidTextPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Local Wi-Fi audio · Identity: $localCallsign",
                            style = MaterialTheme.typography.labelSmall,
                            color = VoidMint
                        )
                    }
                }

                if (remoteStatus?.isActive == true) {
                    Text(
                        text = "ACTIVE ${remoteStatus.mode.label.uppercase()} · ${remoteStatus.participantCount} PARTICIPANT(S)",
                        color = VoidMint,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Text(
                        text = if (isDirectRoom) {
                            "Start a private microphone-only call."
                        } else {
                            "Start an audio call or host a microphone-only Live Class."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = VoidTextSecondary
                    )
                }

                if (!isDirectRoom) {
                    Button(
                        onClick = onStartLiveClass,
                        colors = ButtonDefaults.buttonColors(containerColor = VoidMint, contentColor = VoidObsidian),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("classroom_start_teacher_button")
                    ) {
                        Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("START LIVE AUDIO CLASS (HOST)")
                    }
                    Button(
                        onClick = onJoinLiveClass,
                        colors = ButtonDefaults.buttonColors(containerColor = VoidCyan, contentColor = VoidObsidian),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("classroom_join_student_button")
                    ) {
                        Icon(Icons.Filled.Groups, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("JOIN LIVE AUDIO CLASS")
                    }
                }

                OutlinedButton(
                    onClick = onStartVoiceCall,
                    border = BorderStroke(1.dp, VoidAmber),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidAmber),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("classroom_start_call_button")
                ) {
                    Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("START AUDIO CALL")
                }
            }
        }
    }
}

@Composable
private fun AudioSessionHeader(
    channelName: String,
    isDirectRoom: Boolean,
    callUiState: BitVoidCallUiState,
    remoteStatus: ChannelCallStatus?,
    onLeave: () -> Unit,
    onEndClass: () -> Unit
) {
    val isLiveClass = callUiState.sessionMode == SignalingSessionMode.LIVE_CLASSROOM
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(VoidSurface)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (callUiState.callState == BitVoidCallState.JOINING) {
            CircularProgressIndicator(color = VoidMint, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        } else {
            Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(VoidCrimson))
        }
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isLiveClass) "LIVE AUDIO CLASS · $channelName" else "AUDIO CALL · $channelName",
                style = MaterialTheme.typography.labelMedium,
                color = VoidMint,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    if (isDirectRoom && remoteStatus?.directCallPhase == DirectCallPhase.RINGING && remoteStatus.isOutgoing) {
                        append("Ringing · waiting for answer")
                    } else {
                        append("${callUiState.participants.size.coerceAtLeast(1)} participant(s)")
                    }
                    callUiState.liveClassCode?.takeIf { it.isNotBlank() }?.let { append(" · ID: $it") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = VoidTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isLiveClass && callUiState.localRole == ParticipantRole.HOST_TEACHER) {
            OutlinedButton(
                onClick = onEndClass,
                border = BorderStroke(1.dp, VoidCrimson),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidCrimson),
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.height(48.dp).testTag("classroom_end_session_button")
            ) {
                Text("END CLASS", style = MaterialTheme.typography.labelSmall)
            }
        } else {
            OutlinedButton(
                onClick = onLeave,
                border = BorderStroke(1.dp, VoidCrimson),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidCrimson),
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.height(48.dp).testTag("classroom_leave_button")
            ) {
                Icon(Icons.Filled.CallEnd, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("LEAVE", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun AudioParticipantStage(
    participants: List<SignalingPeer>,
    myPeerId: String,
    localCallsign: String,
    modifier: Modifier = Modifier
) {
    val ordered = participants.sortedWith(
        compareByDescending<SignalingPeer> { it.role == ParticipantRole.HOST_TEACHER }
            .thenBy { it.displayName.lowercase() }
    )

    Column(modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            text = "PARTICIPANTS",
            style = MaterialTheme.typography.labelMedium,
            color = VoidCyan,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 144.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (ordered.isEmpty()) {
                item {
                    AudioParticipantCard(
                        displayName = localCallsign.ifBlank { "You" },
                        role = ParticipantRole.SPEAKER,
                        isMuted = false,
                        isSelf = true
                    )
                }
            } else {
                items(ordered, key = { it.peerId }) { peer ->
                    AudioParticipantCard(
                        displayName = peer.displayName,
                        role = peer.role,
                        isMuted = peer.isMuted,
                        isSelf = peer.peerId == myPeerId
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioParticipantCard(
    displayName: String,
    role: ParticipantRole,
    isMuted: Boolean,
    isSelf: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = VoidSurfaceElevated),
        border = BorderStroke(
            1.dp,
            if (role == ParticipantRole.HOST_TEACHER) VoidMint else VoidBorder
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.height(142.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(if (role == ParticipantRole.HOST_TEACHER) VoidMint.copy(alpha = 0.18f) else VoidCyan.copy(alpha = 0.15f))
                    .border(1.dp, if (role == ParticipantRole.HOST_TEACHER) VoidMint else VoidCyan, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = displayName.trim().firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.headlineSmall,
                    color = VoidTextPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (isSelf) "$displayName (YOU)" else displayName,
                color = VoidTextPrimary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    contentDescription = null,
                    tint = if (isMuted) VoidCrimson else VoidMint,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (role == ParticipantRole.HOST_TEACHER) "HOST" else role.label.uppercase(),
                    color = VoidTextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

@Composable
private fun AudioControls(
    isMicMuted: Boolean,
    isSpeakerphoneOn: Boolean,
    onToggleMic: () -> Unit,
    onToggleSpeakerphone: () -> Unit
) {
    Surface(
        color = VoidSurface,
        border = BorderStroke(1.dp, VoidBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onToggleMic,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isMicMuted) VoidCrimson else VoidMint,
                    contentColor = VoidObsidian
                ),
                modifier = Modifier.height(50.dp).testTag("classroom_mic_button")
            ) {
                Icon(if (isMicMuted) Icons.Filled.MicOff else Icons.Filled.Mic, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isMicMuted) "UNMUTE" else "MUTE")
            }
            OutlinedButton(
                onClick = onToggleSpeakerphone,
                border = BorderStroke(1.dp, VoidCyan),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidCyan),
                modifier = Modifier.height(50.dp).testTag("classroom_speaker_button")
            ) {
                Icon(
                    if (isSpeakerphoneOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(if (isSpeakerphoneOn) "SPEAKER" else "EARPIECE")
            }
        }
    }
}

@Composable
private fun JoinLiveClassByIdDialog(
    isJoining: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var classCode by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!isJoining) onDismiss() },
        title = { Text("Join Live Audio Class") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter the class ID shared by the host.")
                OutlinedTextField(
                    value = classCode,
                    onValueChange = { value ->
                        classCode = value.uppercase().filter { it.isLetterOrDigit() }
                    },
                    label = { Text("Class ID") },
                    singleLine = true,
                    enabled = !isJoining,
                    modifier = Modifier.fillMaxWidth().testTag("classroom_join_code_input")
                )
                errorMessage?.let { Text(it, color = VoidCrimson, style = MaterialTheme.typography.labelMedium) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(classCode.trim()) },
                enabled = classCode.isNotBlank() && !isJoining,
                modifier = Modifier.testTag("classroom_join_code_submit")
            ) {
                if (isJoining) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text("JOIN")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isJoining) { Text("CANCEL") }
        }
    )
}
