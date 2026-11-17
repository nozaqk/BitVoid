package com.bitvoid.network.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.bitvoid.network.call.BitVoidCallState
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
import java.util.Locale

@Composable
fun LiveClassIdBanner(
    classCode: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var copied by remember(classCode) { mutableStateOf(false) }

    Surface(
        color = Color(0xFF0F241D),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, VoidMint.copy(alpha = 0.7f)),
        modifier = modifier
            .fillMaxWidth()
            .testTag("live_class_id_banner")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "LIVE CLASS ID (SHARE WITH STUDENTS)",
                    style = MaterialTheme.typography.labelSmall,
                    color = VoidMint,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = classCode,
                    style = MaterialTheme.typography.titleMedium,
                    color = VoidTextPrimary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("live_class_id_value")
                )
            }
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Live Class ID", classCode))
                    copied = true
                },
                border = BorderStroke(1.dp, VoidMint),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidMint),
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("live_class_id_copy_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy Live Class ID",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (copied) "COPIED" else "COPY ID",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun JoinLiveClassByIdDialog(
    isJoining: Boolean,
    initialCode: String = "",
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var codeInput by rememberSaveable(initialCode) { mutableStateOf(initialCode) }
    var localError by remember { mutableStateOf<String?>(null) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = VoidCyan,
        unfocusedBorderColor = VoidBorder,
        focusedLabelColor = VoidCyan,
        unfocusedLabelColor = VoidTextSecondary,
        focusedTextColor = VoidTextPrimary,
        unfocusedTextColor = VoidTextPrimary,
        cursorColor = VoidCyan
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VoidSurface,
        titleContentColor = VoidCyan,
        textContentColor = VoidTextPrimary,
        modifier = Modifier.testTag("join_live_class_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.School,
                    contentDescription = null,
                    tint = VoidCyan,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "JOIN LIVE CLASS BY ID",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Enter the Live Class ID shared by your Host Teacher (for example CLS-ABCD-12) to join the live audio class.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VoidTextSecondary
                )
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = {
                        codeInput = it.uppercase(Locale.US)
                        localError = null
                    },
                    label = { Text("Live Class ID (e.g. CLS-ABCD-12)") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("join_live_class_id_input")
                )
                (localError ?: errorMessage)?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = VoidCrimson,
                        modifier = Modifier.testTag("join_live_class_error")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = codeInput.trim()
                    if (trimmed.isEmpty()) {
                        localError = "Enter the Live Class ID shared by the host."
                    } else {
                        localError = null
                        onSubmit(trimmed)
                    }
                },
                enabled = !isJoining,
                colors = ButtonDefaults.buttonColors(
                    containerColor = VoidCyan,
                    contentColor = VoidObsidian
                ),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("join_live_class_submit_button")
            ) {
                Text(
                    text = if (isJoining) "JOINING..." else "JOIN LIVE CLASS",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.height(48.dp)
            ) {
                Text("CANCEL", color = VoidTextSecondary)
            }
        }
    )
}

@Composable
fun StartLiveClassDialog(
    isStarting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSubmit: (name: String, isHidden: Boolean) -> Unit
) {
    var className by rememberSaveable { mutableStateOf("") }
    var isHidden by rememberSaveable { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = VoidMint,
        unfocusedBorderColor = VoidBorder,
        focusedLabelColor = VoidMint,
        unfocusedLabelColor = VoidTextSecondary,
        focusedTextColor = VoidTextPrimary,
        unfocusedTextColor = VoidTextPrimary,
        cursorColor = VoidMint
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VoidSurface,
        titleContentColor = VoidMint,
        textContentColor = VoidTextPrimary,
        modifier = Modifier.testTag("start_live_class_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.School,
                    contentDescription = null,
                    tint = VoidMint,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "START LIVE CLASS",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Enter a class name and choose whether peers on the same Wi-Fi node can see it in the Hub or must join strictly by its generated CLS- ID.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VoidTextSecondary
                )

                OutlinedTextField(
                    value = className,
                    onValueChange = {
                        className = it
                        localError = null
                    },
                    label = { Text("Live Class Name (e.g. Physics Lecture)") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("live_class_name_input")
                )

                Text(
                    text = "HUB VISIBILITY",
                    style = MaterialTheme.typography.labelSmall,
                    color = VoidCyan,
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        onClick = { isHidden = false },
                        shape = RoundedCornerShape(10.dp),
                        color = if (!isHidden) VoidMintContainer else VoidObsidian,
                        border = BorderStroke(1.dp, if (!isHidden) VoidMint else VoidBorder),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .testTag("live_class_show_option")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "SHOW IN HUB",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (!isHidden) VoidMint else VoidTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Peers on same Wi-Fi node see it",
                                style = MaterialTheme.typography.labelSmall,
                                color = VoidTextSecondary
                            )
                        }
                    }

                    Surface(
                        onClick = { isHidden = true },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isHidden) VoidAmber.copy(alpha = 0.16f) else VoidObsidian,
                        border = BorderStroke(1.dp, if (isHidden) VoidAmber else VoidBorder),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 56.dp)
                            .testTag("live_class_hidden_option")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = "HIDDEN (ID ONLY)",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isHidden) VoidAmber else VoidTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Only you see it; others need CLS- ID",
                                style = MaterialTheme.typography.labelSmall,
                                color = VoidTextSecondary
                            )
                        }
                    }
                }

                (localError ?: errorMessage)?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = VoidCrimson,
                        modifier = Modifier.testTag("start_live_class_error")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = className.trim()
                    if (trimmed.isEmpty()) {
                        localError = "Enter a Live Class name."
                    } else {
                        localError = null
                        onSubmit(trimmed, isHidden)
                    }
                },
                enabled = !isStarting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = VoidMint,
                    contentColor = VoidObsidian
                ),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("start_live_class_submit_button")
            ) {
                Text(
                    text = if (isStarting) "STARTING..." else "START LIVE CLASS",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.height(48.dp)
            ) {
                Text("CANCEL", color = VoidTextSecondary)
            }
        }
    )
}

@Composable
fun DirectCallStagePanel(
    peerUsername: String,
    callUiState: BitVoidCallUiState,
    callActions: BitVoidCallActions,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var pendingPermissionAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        pendingPermissionAction?.invoke()
        pendingPermissionAction = null
    }

    fun runWithAudioPermission(action: () -> Unit) {
        val hasAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (hasAudio) {
            action()
        } else {
            pendingPermissionAction = action
            permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }

    val remotePeer = callUiState.participants.firstOrNull { it.peerId != callUiState.myPeerId }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0E141D)),
        border = BorderStroke(1.5.dp, VoidMint.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("dm_call_stage_panel")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(VoidMint)
                    )
                    Column {
                        Text(
                            text = if (callUiState.callState == BitVoidCallState.JOINING) {
                                "CONNECTING CALL WITH @$peerUsername..."
                            } else {
                                "1-ON-1 CALL // @$peerUsername"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = VoidMint,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${callUiState.participants.size.coerceAtLeast(1)} participant(s) on local Wi-Fi",
                            style = MaterialTheme.typography.labelSmall,
                            color = VoidTextSecondary
                        )
                    }
                }
                IconButton(
                    onClick = callActions.onLeaveSession,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("dm_call_close_button")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Hang up direct call",
                        tint = VoidCrimson
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF05070A))
                    .border(1.dp, VoidBorder, RoundedCornerShape(12.dp))
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(VoidCyan.copy(alpha = 0.16f))
                            .border(1.5.dp, VoidCyan, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = peerUsername.take(2).uppercase(Locale.US),
                            style = MaterialTheme.typography.titleMedium,
                            color = VoidCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (remotePeer != null) {
                            "@$peerUsername connected · ${if (remotePeer.isMuted) "Muted" else "Audio Live"}"
                        } else {
                            "Waiting for @$peerUsername to join voice call..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = VoidTextSecondary
                    )
                    Text(
                        text = "Microphone-only local audio",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidMint
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = {
                        if (callUiState.isMicMuted) runWithAudioPermission { callActions.onToggleMicMute() }
                        else callActions.onToggleMicMute()
                    },
                    shape = CircleShape,
                    color = if (callUiState.isMicMuted) VoidCrimson.copy(alpha = 0.2f) else VoidMintContainer,
                    border = BorderStroke(1.dp, if (callUiState.isMicMuted) VoidCrimson else VoidMint),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("dm_call_mute_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (callUiState.isMicMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = "Toggle mic mute",
                            tint = if (callUiState.isMicMuted) VoidCrimson else VoidMint
                        )
                    }
                }

                Surface(
                    onClick = callActions.onToggleSpeakerphone,
                    shape = CircleShape,
                    color = if (callUiState.isSpeakerphoneOn) VoidCyan.copy(alpha = 0.16f) else VoidObsidian,
                    border = BorderStroke(1.dp, if (callUiState.isSpeakerphoneOn) VoidCyan else VoidBorder),
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("dm_call_speaker_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (callUiState.isSpeakerphoneOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = "Toggle speakerphone",
                            tint = if (callUiState.isSpeakerphoneOn) VoidCyan else VoidTextSecondary
                        )
                    }
                }

                Surface(
                    onClick = callActions.onLeaveSession,
                    shape = CircleShape,
                    color = VoidCrimson,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("dm_call_hangup_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.CallEnd,
                            contentDescription = "End direct call",
                            tint = VoidTextPrimary
                        )
                    }
                }
            }
        }
    }
}
