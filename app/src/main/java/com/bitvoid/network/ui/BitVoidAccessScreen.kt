package com.bitvoid.network.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitvoid.network.backend.BitVoidBackendPhase
import com.bitvoid.network.backend.BitVoidBackendState
import com.bitvoid.network.backend.BitVoidBackendType

enum class BitVoidAccessMode { LOGIN, CREATE_ACCOUNT, RECOVER }

@Composable
fun BitVoidConnectionGateScreen(
    state: BitVoidBackendState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reconnecting = state.phase == BitVoidBackendPhase.DISCOVERING ||
        state.phase == BitVoidBackendPhase.RECONNECTING
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag("connection_gate"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = CircleShape,
                color = if (reconnecting) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                },
                modifier = Modifier.size(88.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (reconnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(42.dp),
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(42.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(22.dp))
            Text(
                text = if (reconnecting) "CONNECTING BITVOID" else "CONNECTION LOST",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = if (reconnecting) {
                    MaterialTheme.colorScheme.onBackground
                } else {
                    MaterialTheme.colorScheme.error
                },
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (reconnecting) {
                    "Checking for a BitVoid server on the connected local Wi-Fi, then trying the cloud backend."
                } else {
                    "Connect to the local BitVoid Wi-Fi or restore internet access. The app will retry automatically."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(22.dp))
            if (!reconnecting) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("connection_retry_button")
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("TRY AGAIN", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun BitVoidAccountAccessScreen(
    backendState: BitVoidBackendState,
    suggestedUsername: String,
    isSubmitting: Boolean,
    errorMessage: String?,
    onSubmit: (BitVoidAccessMode, String, String, String, String) -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier
) {
    var mode by rememberSaveable { mutableStateOf(BitVoidAccessMode.LOGIN) }
    var username by rememberSaveable { mutableStateOf(suggestedUsername) }
    var password by rememberSaveable { mutableStateOf("") }
    var masterPassphrase by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(suggestedUsername) {
        if (username.isBlank()) username = suggestedUsername
    }

    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .testTag("account_access_screen"),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(22.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp)
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("BV", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.ExtraBold)
                    }
                }
                Text("BITVOID", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (backendState.type == BitVoidBackendType.CAMPUS) Icons.Filled.Lan else Icons.Filled.CloudDone,
                        contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp)
                    )
                    Text(
                        if (backendState.type == BitVoidBackendType.CAMPUS) "Connected through local Wi-Fi" else "Connected to BitVoid Cloud",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary
                    )
                }
                if (mode != BitVoidAccessMode.RECOVER) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (mode == BitVoidAccessMode.LOGIN) {
                            Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("LOG IN") }
                            OutlinedButton(onClick = { mode = BitVoidAccessMode.CREATE_ACCOUNT; onClearError() }, modifier = Modifier.weight(1f)) { Text("CREATE") }
                        } else {
                            OutlinedButton(onClick = { mode = BitVoidAccessMode.LOGIN; onClearError() }, modifier = Modifier.weight(1f)) { Text("LOG IN") }
                            Button(onClick = {}, modifier = Modifier.weight(1f)) { Text("CREATE") }
                        }
                    }
                }
                Text(
                    when (mode) {
                        BitVoidAccessMode.LOGIN -> "Log in using your BitVoid username and password."
                        BitVoidAccessMode.CREATE_ACCOUNT -> "Create your BitVoid account. A recovery master passphrase will be generated once."
                        BitVoidAccessMode.RECOVER -> "Enter your 44-character hexadecimal master passphrase to recover your account and choose a new password. Your username is not required."
                    },
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center
                )
                if (mode != BitVoidAccessMode.RECOVER) {
                    OutlinedTextField(
                        value = username, onValueChange = { username = it; onClearError() },
                        label = { Text("Username") }, placeholder = { Text("your-username") }, prefix = { Text("@") },
                        singleLine = true, enabled = !isSubmitting,
                        modifier = Modifier.fillMaxWidth().testTag("account_user_id_input")
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it; onClearError() },
                        label = { Text("Password") }, singleLine = true, enabled = !isSubmitting,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("account_password_input")
                    )
                }
                if (mode == BitVoidAccessMode.RECOVER) {
                    OutlinedTextField(
                        value = masterPassphrase, onValueChange = { masterPassphrase = it.filter { ch -> ch in "0123456789abcdefABCDEF" }.take(44); onClearError() },
                        label = { Text("44-character master passphrase") }, placeholder = { Text("Hexadecimal recovery key") },
                        singleLine = true, enabled = !isSubmitting,
                        modifier = Modifier.fillMaxWidth().testTag("account_master_passphrase_input")
                    )
                    OutlinedTextField(
                        value = newPassword, onValueChange = { newPassword = it; onClearError() },
                        label = { Text("New password") }, singleLine = true, enabled = !isSubmitting,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("account_new_password_input")
                    )
                }
                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                }
                Button(
                    onClick = { onSubmit(mode, username, password, masterPassphrase, newPassword) },
                    enabled = !isSubmitting && when (mode) {
                        BitVoidAccessMode.LOGIN -> username.isNotBlank() && password.isNotBlank()
                        BitVoidAccessMode.CREATE_ACCOUNT -> username.isNotBlank() && password.length >= 10
                        BitVoidAccessMode.RECOVER -> masterPassphrase.length == 44 && newPassword.length >= 10
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                    modifier = Modifier.fillMaxWidth().height(52.dp).testTag("account_access_submit")
                ) {
                    if (isSubmitting) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text(when (mode) {
                        BitVoidAccessMode.LOGIN -> "LOG IN"
                        BitVoidAccessMode.CREATE_ACCOUNT -> "CREATE ACCOUNT"
                        BitVoidAccessMode.RECOVER -> "RECOVER ACCOUNT"
                    }, fontWeight = FontWeight.Bold)
                }
                if (mode == BitVoidAccessMode.LOGIN) {
                    OutlinedButton(onClick = { mode = BitVoidAccessMode.RECOVER; onClearError() }, enabled = !isSubmitting, modifier = Modifier.fillMaxWidth()) {
                        Text("FORGOT PASSWORD OR USERNAME?")
                    }
                } else if (mode == BitVoidAccessMode.RECOVER) {
                    OutlinedButton(onClick = { mode = BitVoidAccessMode.LOGIN; onClearError() }, enabled = !isSubmitting, modifier = Modifier.fillMaxWidth()) {
                        Text("BACK TO LOGIN")
                    }
                }
                Text("Keep your master passphrase private. BitVoid cannot display it again after account creation.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}
