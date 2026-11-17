package com.bitvoid.network.model

import com.bitvoid.network.media.BitVoidMediaItem

enum class NodeMode {
    HOST,
    CLIENT
}

enum class AppBottomTab(val label: String) {
    HOME("Home"),
    HUB("Hub"),
    PCHAT("P.Chat"),
    SETTINGS("Settings")
}

enum class HotspotStatus {
    IDLE,
    STARTING,
    ACTIVE,
    SERVER_ONLY,
    ERROR
}

enum class MediaCategory(val badge: String) {
    VIDEO("VID"),
    AUDIO("AUD"),
    IMAGE("IMG"),
    DOCUMENT("DOC"),
    ARCHIVE("BIN")
}

data class AuditLogEntry(
    val ephemeralToken: String,
    val timestampLabel: String,
    val method: String,
    val sanitizedPath: String,
    val statusCode: Int,
    val paddedSizeBytes: Long,
    val strippedHeaderCount: Int
)

data class HostState(
    val status: HotspotStatus = HotspotStatus.IDLE,
    val isServerRunning: Boolean = false,
    val isHiddenSsid: Boolean = BitVoidNetworkConfig.ENFORCED_VISIBLE_SSID,
    val securityLabel: String = BitVoidNetworkConfig.WPA2_SECURITY_LABEL,
    val configuredSsid: String = BitVoidNetworkConfig.DEFAULT_VISIBLE_SSID,
    val configuredPassphrase: String = BitVoidNetworkConfig.DEFAULT_WPA2_PASSPHRASE,
    val ssid: String = "",
    val passphrase: String = "",
    val gatewayIp: String = "",
    val gatewayHostname: String = "",
    val gatewayHostnamePort: Int = 8080,
    val boundEndpoints: List<String> = emptyList(),
    val port: Int = 8080,
    val securePort: Int = 8443,
    val isSecureServerRunning: Boolean = false,
    val sharedFiles: List<BitVoidMediaItem> = emptyList(),
    val activeSessionsCount: Int = 0,
    val bytesServed: Long = 0L,
    val anonymizedRequestsCount: Int = 0,
    val strippedHeadersTotal: Int = 0,
    val paddedResponsesCount: Int = 0,
    val uptimeSeconds: Long = 0L,
    val errorMessage: String? = null
) {
    val isEngineLocked: Boolean
        get() = status == HotspotStatus.ACTIVE ||
            status == HotspotStatus.STARTING ||
            status == HotspotStatus.SERVER_ONLY ||
            isServerRunning

    val publicCount: Int
        get() = sharedFiles.count { it.isPublic }

    val effectiveSsid: String
        get() = ssid.ifBlank { configuredSsid.ifBlank { DEFAULT_SSID } }

    val networkPortalUrl: String
        get() = if (isServerRunning && gatewayIp.isNotBlank()) {
            buildHttpUrl(gatewayIp, port)
        } else {
            buildHttpUrl("192.168.43.1", port)
        }

    val effectivePortalUrl: String
        get() = if (isServerRunning && gatewayHostname.isNotBlank()) {
            buildHttpUrl(gatewayHostname, gatewayHostnamePort)
        } else {
            networkPortalUrl
        }

    val portalUrl: String
        get() = if (isServerRunning && gatewayIp.isNotBlank()) {
            effectivePortalUrl
        } else {
            "— offline —"
        }

    val securePortalUrl: String
        get() = if (isSecureServerRunning && gatewayIp.isNotBlank()) {
            val host = gatewayHostname.ifBlank { gatewayIp }
            "https://$host:$securePort"
        } else {
            "— secure portal starting —"
        }

    private fun buildHttpUrl(host: String, targetPort: Int): String {
        return if (targetPort == 80) "http://$host" else "http://$host:$targetPort"
    }

    companion object {
        const val DEFAULT_SSID = BitVoidNetworkConfig.DEFAULT_VISIBLE_SSID
    }
}

data class DiscoveredNetwork(
    val ssid: String,
    val signalDbm: Int,
    val frequencyMhz: Int,
    val securityLabel: String,
    val isBitVoidCandidate: Boolean
)

enum class ClientConnectionStatus {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class PrivacyTelemetry(
    val userAgentStripped: Boolean = true,
    val webRtcDisabled: Boolean = true,
    val thirdPartyCookiesDisabled: Boolean = true,
    val memoryOnlyCache: Boolean = true,
    val strippedRequestsCount: Int = 0,
    val blockedExternalRequestsCount: Int = 0
)

data class ClientState(
    val connectionStatus: ClientConnectionStatus = ClientConnectionStatus.DISCONNECTED,
    val isScanning: Boolean = false,
    val discoveredNetworks: List<DiscoveredNetwork> = emptyList(),
    val currentWifiSsid: String? = null,
    val connectedSsid: String? = null,
    val linkedNodeUrls: List<String> = emptyList(),
    val targetPortalUrl: String = "http://${BitVoidNetworkConfig.LOCAL_GATEWAY_HOSTNAME}",
    val statusMessage: String = "Standby — ready to scan or connect to a BitVoid node",
    val errorMessage: String? = null,
    val isBrowserOpen: Boolean = false,
    val privacyTelemetry: PrivacyTelemetry = PrivacyTelemetry()
)
