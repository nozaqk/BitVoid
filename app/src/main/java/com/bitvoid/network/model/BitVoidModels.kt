package com.bitvoid.network.model

import com.bitvoid.network.media.BitVoidMediaItem

enum class NodeMode {
    HOST,
    CLIENT
}

enum class AppBottomTab(val label: String) {
    HOME("Chats"),
    HUB("Channels"),
    PCHAT("People"),
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

enum class NodeConnectionEventType {
    APP,
    WIFI,
    SCAN,
    DISCOVERY,
    IDENTITY,
    COORDINATOR,
    GATEWAY,
    CONNECTED,
    DISCONNECTED,
    RETRY,
    ERROR
}

data class NodeConnectionLogEntry(
    val id: String,
    val timestampMillis: Long,
    val type: NodeConnectionEventType,
    val title: String,
    val detail: String,
    val isError: Boolean = false
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
    val isGatewayAddressVerified: Boolean = false,
    val gatewayHostname: String = "",
    val gatewayHostnamePort: Int = 8080,
    val boundEndpoints: List<String> = emptyList(),
    val port: Int = 8080,
    val sharedFiles: List<BitVoidMediaItem> = emptyList(),
    val activeSessionsCount: Int = 0,
    val connectedNodesCount: Int = 0,
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

    val hasClientGatewayAddress: Boolean
        get() = isServerRunning &&
            isGatewayAddressVerified &&
            gatewayIp.isNotBlank() &&
            gatewayIp != "127.0.0.1" &&
            gatewayIp != "0.0.0.0"

    val networkApiUrl: String
        get() = if (isServerRunning && gatewayIp.isNotBlank()) {
            buildHttpUrl(gatewayIp, port)
        } else {
            buildHttpUrl("192.168.43.1", port)
        }

    val effectiveApiUrl: String
        get() = if (isServerRunning && gatewayHostname.isNotBlank()) {
            buildHttpUrl(gatewayHostname, gatewayHostnamePort)
        } else {
            networkApiUrl
        }

    val apiUrl: String
        get() = if (isServerRunning && gatewayIp.isNotBlank()) {
            effectiveApiUrl
        } else {
            "— offline —"
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

data class ClientState(
    val connectionStatus: ClientConnectionStatus = ClientConnectionStatus.DISCONNECTED,
    val isScanning: Boolean = false,
    val discoveredNetworks: List<DiscoveredNetwork> = emptyList(),
    val currentWifiSsid: String? = null,
    val connectedSsid: String? = null,
    val linkedNodeUrls: List<String> = emptyList(),
    val coordinatorUrls: List<String> = emptyList(),
    val meshNodeCount: Int = 0,
    val targetPortalUrl: String = "http://${BitVoidNetworkConfig.LOCAL_GATEWAY_HOSTNAME}",
    val statusMessage: String = "Standby — ready to scan or connect to a AirMesh node",
    val errorMessage: String? = null,
)
