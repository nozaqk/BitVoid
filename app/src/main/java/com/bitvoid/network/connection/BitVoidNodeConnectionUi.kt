package com.bitvoid.network.connection

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bitvoid.network.ui.theme.VoidBorder
import com.bitvoid.network.ui.theme.VoidCrimson
import com.bitvoid.network.ui.theme.VoidCyan
import com.bitvoid.network.ui.theme.VoidMint
import com.bitvoid.network.ui.theme.VoidObsidian
import com.bitvoid.network.ui.theme.VoidSurface
import com.bitvoid.network.ui.theme.VoidTextPrimary
import com.bitvoid.network.ui.theme.VoidTextSecondary
import java.io.ByteArrayInputStream

/** Compact status/control card that can be embedded in the existing Client Node screen. */
@Composable
fun BitVoidHardwareConnectionCard(
    state: BitVoidConnectionState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDashboard by remember { mutableStateOf(false) }
    val accent = when (state.phase) {
        BitVoidConnectionPhase.READY -> VoidMint
        BitVoidConnectionPhase.ERROR -> VoidCrimson
        BitVoidConnectionPhase.BINDING, BitVoidConnectionPhase.DISCOVERING -> VoidCyan
        else -> VoidTextSecondary
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("esp_node_connection_card"),
        colors = CardDefaults.cardColors(containerColor = VoidSurface),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Router,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ESP HARDWARE NODE",
                        style = MaterialTheme.typography.titleSmall,
                        color = VoidTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = state.statusMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = VoidTextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (state.phase == BitVoidConnectionPhase.BINDING ||
                    state.phase == BitVoidConnectionPhase.DISCOVERING
                ) {
                    CircularProgressIndicator(
                        color = VoidCyan,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag("esp_node_refresh_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Discover ESP node again", tint = accent)
                    }
                }
            }

            if (state.isReady) {
                Text(
                    text = buildString {
                        append(state.endpoint?.portalUrl ?: BitVoidConnectionManager.NODE_URL)
                        append(" · Wi-Fi only")
                        state.endpoint?.source?.let { append(" · ${it.name.replace('_', ' ')}") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = VoidMint,
                    modifier = Modifier.testTag("esp_node_endpoint")
                )
                Button(
                    onClick = { showDashboard = !showDashboard },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VoidMint,
                        contentColor = VoidObsidian
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("esp_node_dashboard_button")
                ) {
                    Icon(
                        imageVector = if (showDashboard) Icons.Filled.Close else Icons.Filled.Language,
                        contentDescription = null
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(if (showDashboard) "CLOSE WEB DASHBOARD" else "OPEN WEB DASHBOARD")
                }
            } else {
                OutlinedButton(
                    onClick = onRefresh,
                    border = BorderStroke(1.dp, accent),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = accent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("RETRY ZERO-CONFIG DISCOVERY")
                }
            }

            state.errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = VoidCrimson,
                    modifier = Modifier.testTag("esp_node_error")
                )
            }

            AnimatedVisibility(visible = showDashboard && state.isReady) {
                BitVoidNodeWebDashboard(
                    portalUrl = state.dashboardUrl,
                    fallbackUrl = state.dashboardFallbackUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(460.dp)
                )
            }
        }
    }
}

/**
 * Embedded captive-portal/Web dashboard. Redirects are allowed only inside the private node link;
 * if Android WebView cannot resolve `.local`, the same page is retried through `192.168.4.1`.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BitVoidNodeWebDashboard(
    portalUrl: String = BitVoidConnectionManager.NODE_URL,
    fallbackUrl: String = BitVoidConnectionManager.GATEWAY_URL,
    modifier: Modifier = Modifier,
    onPageChanged: (String) -> Unit = {},
    onError: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val safePortalUrl = portalUrl.takeIf { BitVoidConnectionManager.isAllowedLocalUrl(it) }
        ?: BitVoidConnectionManager.NODE_URL
    val safeFallbackUrl = fallbackUrl.takeIf { BitVoidConnectionManager.isAllowedLocalUrl(it) }
        ?: BitVoidConnectionManager.GATEWAY_URL
    var fallbackAttempted by remember(safePortalUrl, safeFallbackUrl) { mutableStateOf(false) }

    val webView = remember(context, safePortalUrl, safeFallbackUrl) {
        WebView(context).apply {
            setBackgroundColor(android.graphics.Color.rgb(10, 14, 20))
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                mediaPlaybackRequiresUserGesture = true
                builtInZoomControls = true
                displayZoomControls = false
                userAgentString = "BitVoid-Android-ESP-WebView/1.0"
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val target = request?.url?.toString().orEmpty()
                    return !BitVoidConnectionManager.isAllowedLocalUrl(target)
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val target = request?.url?.toString().orEmpty()
                    return if (BitVoidConnectionManager.isAllowedLocalUrl(target)) {
                        null
                    } else {
                        WebResourceResponse(
                            "text/plain",
                            "utf-8",
                            403,
                            "Blocked outside ESP Wi-Fi",
                            emptyMap(),
                            ByteArrayInputStream(ByteArray(0))
                        )
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    url?.let(onPageChanged)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true && !fallbackAttempted &&
                        safePortalUrl != safeFallbackUrl
                    ) {
                        fallbackAttempted = true
                        view?.post { view.loadUrl(safeFallbackUrl) }
                    } else if (request?.isForMainFrame == true) {
                        onError(error?.description?.toString() ?: "Unable to open ESP dashboard")
                    }
                }
            }
            loadUrl(safePortalUrl)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
    }

    AndroidView(
        factory = { webView },
        modifier = modifier
            .background(Color(0xFF0A0E14))
            .testTag("esp_node_web_dashboard")
    )
}
