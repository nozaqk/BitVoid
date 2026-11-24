package com.bitvoid.network.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitvoid.network.call.BitVoidCallState
import com.bitvoid.network.call.DirectCallPhase
import com.bitvoid.network.client.ChannelCallStatus
import kotlinx.coroutines.delay

@Composable
fun BitVoidAudioCallScreen(
    peerUsername: String,
    callUiState: BitVoidCallUiState,
    remoteStatus: ChannelCallStatus?,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    val phase = remoteStatus?.directCallPhase ?: DirectCallPhase.IDLE
    val incoming = phase == DirectCallPhase.RINGING && remoteStatus?.isIncoming == true
    val outgoing = phase == DirectCallPhase.RINGING && remoteStatus?.isOutgoing == true
    val active = callUiState.callState == BitVoidCallState.ACTIVE
    var elapsedSeconds by remember(peerUsername, active) { mutableLongStateOf(0L) }

    LaunchedEffect(active) {
        if (!active) {
            elapsedSeconds = 0L
        } else {
            while (true) {
                delay(1_000L)
                elapsedSeconds++
            }
        }
    }

    val statusText = when {
        incoming -> "Incoming BitVoid audio call"
        outgoing -> "Ringing…"
        callUiState.callState == BitVoidCallState.JOINING -> "Connecting secure audio…"
        active -> formatCallDuration(elapsedSeconds)
        phase == DirectCallPhase.ACCEPTED -> "Call accepted · connecting…"
        else -> "Audio call"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF17283A), Color(0xFF0C1420), Color(0xFF080C12))
                )
            )
            .testTag("telegram_audio_call_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 38.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "BITVOID AUDIO",
                style = MaterialTheme.typography.labelLarge,
                color = Color(0xFF7DFFCB),
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(0.22f))
            Box(
                modifier = Modifier
                    .size(132.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF2AABEE))
                    .border(4.dp, Color.White.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = peerUsername.trim().take(2).uppercase(),
                    style = MaterialTheme.typography.displaySmall,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.size(24.dp))
            Text(
                text = "@$peerUsername",
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyLarge,
                color = if (active) Color(0xFF7DFFCB) else Color(0xFFB8C4D1),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.weight(1f))

            if (incoming) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(64.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallControl(
                        color = Color(0xFFFF4D6D),
                        label = "Decline",
                        testTag = "call_reject_button",
                        onClick = onReject
                    ) {
                        Icon(Icons.Filled.CallEnd, contentDescription = "Decline", tint = Color.White)
                    }
                    CallControl(
                        color = Color(0xFF35C759),
                        label = "Answer",
                        testTag = "call_answer_button",
                        onClick = onAnswer
                    ) {
                        Icon(Icons.Filled.Call, contentDescription = "Answer", tint = Color.White)
                    }
                }
            } else if (outgoing && !active) {
                CallControl(
                    color = Color(0xFFFF4D6D),
                    label = "Cancel",
                    testTag = "call_cancel_button",
                    onClick = onCancel
                ) {
                    Icon(Icons.Filled.CallEnd, contentDescription = "Cancel call", tint = Color.White)
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallControl(
                        color = if (callUiState.isMicMuted) Color(0xFFFF4D6D) else Color.White.copy(alpha = 0.13f),
                        label = if (callUiState.isMicMuted) "Unmute" else "Mute",
                        testTag = "call_mute_button",
                        onClick = onToggleMute
                    ) {
                        Icon(
                            if (callUiState.isMicMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = "Mute or unmute",
                            tint = Color.White
                        )
                    }
                    CallControl(
                        color = if (callUiState.isSpeakerphoneOn) Color(0xFF2AABEE) else Color.White.copy(alpha = 0.13f),
                        label = "Speaker",
                        testTag = "call_speaker_button",
                        onClick = onToggleSpeaker
                    ) {
                        Icon(
                            if (callUiState.isSpeakerphoneOn) {
                                Icons.AutoMirrored.Filled.VolumeUp
                            } else {
                                Icons.AutoMirrored.Filled.VolumeOff
                            },
                            contentDescription = "Speakerphone",
                            tint = Color.White
                        )
                    }
                    CallControl(
                        color = Color(0xFFFF4D6D),
                        label = "End",
                        testTag = "call_end_button",
                        onClick = onEndCall
                    ) {
                        Icon(Icons.Filled.CallEnd, contentDescription = "End call", tint = Color.White)
                    }
                }
            }
            Spacer(Modifier.size(34.dp))
            Text(
                text = "Audio only · encrypted peer connection",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF82909F)
            )
        }
    }
}

@Composable
private fun CallControl(
    color: Color,
    label: String,
    testTag: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = color,
            modifier = Modifier
                .size(66.dp)
                .testTag(testTag)
        ) {
            Box(contentAlignment = Alignment.Center) { icon() }
        }
        Spacer(Modifier.size(8.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

private fun formatCallDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
