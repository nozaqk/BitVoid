package com.bitvoid.network.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bitvoid.network.model.HostState
import com.bitvoid.network.ui.theme.VoidAmber
import com.bitvoid.network.ui.theme.VoidBorder
import com.bitvoid.network.ui.theme.VoidCyan
import com.bitvoid.network.ui.theme.VoidMint
import com.bitvoid.network.ui.theme.VoidMintContainer
import com.bitvoid.network.ui.theme.VoidObsidian
import com.bitvoid.network.ui.theme.VoidTextPrimary
import com.bitvoid.network.ui.theme.VoidTextSecondary
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

private enum class QrPayloadMode {
    WIFI_OPEN_SSID,
    PORTAL_URL
}

fun buildWifiQrPayload(
    ssid: String,
    passphrase: String,
    isOpen: Boolean
): String {
    val cleanSsid = ssid.ifBlank { HostState.DEFAULT_OPEN_SSID }
    val escapedSsid = escapeWifiQrToken(cleanSsid)
    return if (isOpen || passphrase.isBlank()) {
        "WIFI:T:nopass;S:$escapedSsid;H:false;;"
    } else {
        val escapedPass = escapeWifiQrToken(passphrase)
        "WIFI:T:WPA;S:$escapedSsid;P:$escapedPass;H:false;;"
    }
}

private fun escapeWifiQrToken(raw: String): String {
    val sb = StringBuilder(raw.length)
    for (ch in raw) {
        when (ch) {
            '\\', ';', ',', '"', ':' -> {
                sb.append('\\').append(ch)
            }
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

fun generateQrImageBitmap(payload: String, sizePx: Int = 512): ImageBitmap? {
    if (payload.isBlank()) return null
    return try {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )
        val bitMatrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            hints
        )
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] = if (bitMatrix.get(x, y)) {
                    AndroidColor.parseColor("#0D1117")
                } else {
                    AndroidColor.WHITE
                }
            }
        }
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

fun shareOpenNetworkDetails(context: Context, hostState: HostState) {
    val ssid = hostState.effectiveSsid
    val portalUrl = hostState.effectivePortalUrl
    val securityLine = if (hostState.isOpenSecurity || hostState.passphrase.isBlank()) {
        HostState.SECURITY_OPEN_LABEL
    } else {
        "SECURITY: WPA2 (PASS: ${hostState.passphrase})"
    }
    val shareBody = buildString {
        appendLine("Join BitVoid Local Network")
        appendLine("SSID: $ssid")
        appendLine(securityLine)
        appendLine("Portal: $portalUrl")
        append("Connect to Wi-Fi — BitVoid Channels portal opens automatically.")
    }
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Join BitVoid Open Wi-Fi: $ssid")
        putExtra(Intent.EXTRA_TEXT, shareBody)
    }
    val chooser = Intent.createChooser(sendIntent, "Share BitVoid Open SSID ($ssid)").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}

@Composable
fun HostOpenNetworkQrSection(
    hostState: HostState,
    onCopy: (String, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var qrMode by rememberSaveable { mutableStateOf(QrPayloadMode.WIFI_OPEN_SSID) }

    val ssid = hostState.effectiveSsid
    val portalUrl = hostState.effectivePortalUrl
    val wifiPayload = remember(ssid, hostState.passphrase, hostState.isOpenSecurity) {
        buildWifiQrPayload(
            ssid = ssid,
            passphrase = hostState.passphrase,
            isOpen = hostState.isOpenSecurity
        )
    }
    val activePayload = if (qrMode == QrPayloadMode.WIFI_OPEN_SSID) wifiPayload else portalUrl
    val qrBitmap = remember(activePayload) { generateQrImageBitmap(activePayload, 512) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(VoidObsidian)
            .border(
                1.dp,
                if (hostState.isOpenSecurity) VoidMint.copy(alpha = 0.45f) else VoidAmber.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp)
            )
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.QrCode2,
                    contentDescription = null,
                    tint = VoidMint,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "INSTANT SCAN & JOIN",
                    style = MaterialTheme.typography.labelLarge,
                    color = VoidMint
                )
            }
            Text(
                text = ssid,
                style = MaterialTheme.typography.labelMedium,
                color = VoidCyan,
                fontWeight = FontWeight.Bold
            )
        }

        // Toggle between Wi-Fi Auto-Join QR and Captive Portal URL QR
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            QrModePill(
                label = "WIFI OPEN QR",
                selected = qrMode == QrPayloadMode.WIFI_OPEN_SSID,
                modifier = Modifier
                    .weight(1f)
                    .testTag("qr_mode_wifi_button"),
                onClick = { qrMode = QrPayloadMode.WIFI_OPEN_SSID }
            )
            QrModePill(
                label = "PORTAL URL QR",
                selected = qrMode == QrPayloadMode.PORTAL_URL,
                modifier = Modifier
                    .weight(1f)
                    .testTag("qr_mode_portal_button"),
                onClick = { qrMode = QrPayloadMode.PORTAL_URL }
            )
        }

        if (qrBitmap != null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color.White,
                border = BorderStroke(2.dp, VoidMint.copy(alpha = 0.75f)),
                modifier = Modifier.testTag("open_ssid_qr_image")
            ) {
                Image(
                    bitmap = qrBitmap,
                    contentDescription = if (qrMode == QrPayloadMode.WIFI_OPEN_SSID) {
                        "QR code to join open Wi-Fi network $ssid without a password"
                    } else {
                        "QR code to open BitVoid portal at $portalUrl"
                    },
                    filterQuality = FilterQuality.None,
                    modifier = Modifier
                        .size(204.dp)
                        .padding(10.dp)
                )
            }
        }

        Text(
            text = if (qrMode == QrPayloadMode.WIFI_OPEN_SSID) {
                if (hostState.isOpenSecurity) {
                    "Friends scan with Camera to join '$ssid' (no password) and auto-open the BitVoid portal."
                } else {
                    "Scan with Camera to join '$ssid' with configured passkey."
                }
            } else {
                "Scan to launch $portalUrl directly in browser or BitVoid Client."
            },
            style = MaterialTheme.typography.labelSmall,
            color = VoidTextSecondary,
            textAlign = TextAlign.Center
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { shareOpenNetworkDetails(context, hostState) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = VoidMint,
                    contentColor = VoidObsidian
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("share_open_ssid_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    contentDescription = "Share Open SSID",
                    modifier = Modifier.size(17.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "SHARE OPEN SSID",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            OutlinedButton(
                onClick = { onCopy("Open SSID", ssid) },
                border = BorderStroke(1.dp, VoidCyan),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = VoidCyan),
                modifier = Modifier
                    .height(48.dp)
                    .testTag("copy_open_ssid_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy Open SSID",
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("COPY SSID", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun QrModePill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (selected) VoidMintContainer else Color.Transparent,
        border = BorderStroke(1.dp, if (selected) VoidMint else VoidBorder),
        modifier = modifier.height(48.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = if (label.contains("WIFI")) Icons.Filled.Wifi else Icons.Filled.QrCode2,
                    contentDescription = null,
                    tint = if (selected) VoidMint else VoidTextSecondary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) VoidMint else VoidTextPrimary
                )
            }
        }
    }
}
