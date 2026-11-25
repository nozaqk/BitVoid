package com.bitvoid.network.connection
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitvoid.network.ui.theme.VoidCrimson
import com.bitvoid.network.ui.theme.VoidCyan
import com.bitvoid.network.ui.theme.VoidMint
import com.bitvoid.network.ui.theme.VoidObsidian
import com.bitvoid.network.ui.theme.VoidSurface
import com.bitvoid.network.ui.theme.VoidTextPrimary
import com.bitvoid.network.ui.theme.VoidTextSecondary

/** Native status card for the headless ESP gateway API. */
@Composable
fun BitVoidHardwareConnectionCard(state: BitVoidConnectionState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val accent = when (state.phase) {
        BitVoidConnectionPhase.READY -> VoidMint
        BitVoidConnectionPhase.ERROR -> VoidCrimson
        BitVoidConnectionPhase.BINDING, BitVoidConnectionPhase.DISCOVERING -> VoidCyan
        else -> VoidTextSecondary
    }
    Card(modifier = modifier.fillMaxWidth().testTag("esp_node_connection_card"), colors = CardDefaults.cardColors(containerColor = VoidSurface), border = BorderStroke(1.dp, accent.copy(alpha = 0.7f)), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.Router, contentDescription = null, tint = accent, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text("BITVOID GATEWAY API", style = MaterialTheme.typography.titleSmall, color = VoidTextPrimary, fontWeight = FontWeight.Bold)
                    Text(state.statusMessage, style = MaterialTheme.typography.labelSmall, color = VoidTextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (state.phase == BitVoidConnectionPhase.BINDING || state.phase == BitVoidConnectionPhase.DISCOVERING) CircularProgressIndicator(color = VoidCyan, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                else IconButton(onClick = onRefresh, modifier = Modifier.testTag("esp_node_refresh_button")) { Icon(Icons.Filled.Refresh, contentDescription = "Discover ESP gateway again", tint = accent) }
            }
            if (state.isReady) {
                Text("API: ${state.endpoint?.directUrl ?: BitVoidConnectionManager.GATEWAY_URL} · Wi-Fi only", style = MaterialTheme.typography.bodySmall, color = VoidMint, modifier = Modifier.testTag("esp_node_endpoint"))
                Button(onClick = onRefresh, colors = ButtonDefaults.buttonColors(containerColor = VoidMint, contentColor = VoidObsidian), modifier = Modifier.fillMaxWidth().height(44.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = null); Spacer(Modifier.size(8.dp)); Text("REFRESH GATEWAY STATUS")
                }
            } else {
                OutlinedButton(onClick = onRefresh, border = BorderStroke(1.dp, accent), colors = ButtonDefaults.outlinedButtonColors(contentColor = accent), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Refresh, contentDescription = null); Spacer(Modifier.size(8.dp)); Text("RETRY GATEWAY DISCOVERY")
                }
            }
            state.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VoidCrimson, modifier = Modifier.testTag("esp_node_error")) }
        }
    }
}
