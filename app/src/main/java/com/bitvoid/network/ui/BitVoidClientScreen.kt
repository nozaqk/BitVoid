package com.bitvoid.network.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bitvoid.network.model.NodeConnectionEventType
import com.bitvoid.network.model.NodeConnectionLogEntry
import com.bitvoid.network.ui.theme.VoidAmber
import com.bitvoid.network.ui.theme.VoidBorder
import com.bitvoid.network.ui.theme.VoidCrimson
import com.bitvoid.network.ui.theme.VoidCyan
import com.bitvoid.network.ui.theme.VoidMint
import com.bitvoid.network.ui.theme.VoidObsidian
import com.bitvoid.network.ui.theme.VoidSurface
import com.bitvoid.network.ui.theme.VoidTextPrimary
import com.bitvoid.network.ui.theme.VoidTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BitVoidClientHubContent(
    connectionLogs: List<NodeConnectionLogEntry>,
    onClearConnectionLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NodeConnectionLogCard(
            logs = connectionLogs,
            onClear = onClearConnectionLogs
        )
    }
}

@Composable
private fun NodeConnectionLogCard(
    logs: List<NodeConnectionLogEntry>,
    onClear: () -> Unit
) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("node_connection_log"),
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        border = BorderStroke(1.dp, VoidCyan.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CONNECTION EVENT LOG",
                        style = MaterialTheme.typography.titleSmall,
                        color = VoidCyan,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${logs.size} in-memory event(s) · newest first",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidTextSecondary
                    )
                }
                TextButton(
                    onClick = onClear,
                    modifier = Modifier.testTag("clear_node_connection_log")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text("CLEAR")
                }
            }

            HorizontalDivider(color = VoidBorder)

            if (logs.isEmpty()) {
                Text(
                    text = "No events yet. Automatic same-LAN discovery runs in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VoidTextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                logs.take(50).forEach { entry ->
                    val accent = when {
                        entry.isError || entry.type == NodeConnectionEventType.ERROR -> VoidCrimson
                        entry.type == NodeConnectionEventType.CONNECTED -> VoidMint
                        entry.type == NodeConnectionEventType.COORDINATOR ||
                            entry.type == NodeConnectionEventType.GATEWAY -> VoidCyan
                        entry.type == NodeConnectionEventType.RETRY -> VoidAmber
                        else -> VoidTextSecondary
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(VoidObsidian)
                            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = timeFormatter.format(Date(entry.timestampMillis)),
                            style = MaterialTheme.typography.labelSmall,
                            color = accent
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${entry.type.name} · ${entry.title}",
                                style = MaterialTheme.typography.labelMedium,
                                color = VoidTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = entry.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = VoidTextSecondary
                            )
                        }
                    }
                }
                if (logs.size > 50) {
                    Text(
                        text = "Showing the latest 50 of ${logs.size} events.",
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidTextSecondary
                    )
                }
            }
        }
    }
}
