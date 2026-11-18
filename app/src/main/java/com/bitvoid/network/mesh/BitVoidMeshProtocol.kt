package com.bitvoid.network.mesh

import android.content.Context
import org.json.JSONObject
import java.util.UUID

/**
 * Public same-LAN identity protocol shared by Android, ESP32, and Raspberry Pi nodes.
 *
 * This is intentionally an identity check, not authentication: there is no embedded universal
 * secret to extract from the APK or firmware. Nodes are accepted only after answering the local
 * `/api/node` endpoint with the BitVoid headers and protocol metadata.
 */
object BitVoidMeshProtocol {
    const val SERVICE = "bitvoid"
    const val PROTOCOL_VERSION = 2
    const val IDENTITY_MODE = "local-identity-v1"

    const val HEADER_NODE = "X-BitVoid-Node"
    const val HEADER_PROTOCOL = "X-BitVoid-Protocol"
    const val HEADER_IDENTITY = "X-BitVoid-Identity"

    const val TYPE_ANDROID_GATEWAY = "android-gateway"
    const val TYPE_ANDROID_CLIENT = "android-client"
    const val TYPE_PI_COORDINATOR = "pi-coordinator"
    const val TYPE_ESP32_COORDINATOR = "esp32-coordinator"

    fun isCoordinator(nodeType: String): Boolean =
        nodeType == TYPE_PI_COORDINATOR || nodeType == TYPE_ESP32_COORDINATOR
}

object BitVoidMeshIdentity {
    private const val PREFS_NAME = "bitvoid_mesh_identity"
    private const val KEY_NODE_ID = "node_id"

    fun getOrCreateNodeId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_NODE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val created = "android-${UUID.randomUUID()}"
        prefs.edit().putString(KEY_NODE_ID, created).apply()
        return created
    }
}

data class BitVoidMeshNodeDescriptor(
    val nodeId: String,
    val nodeType: String,
    val directPortalUrl: String?,
    val portalUrl: String?,
    val portalAvailable: Boolean,
    val gatewayPriority: Int,
    val protocolVersion: Int,
    val coordinator: Boolean
) {
    companion object {
        fun fromJson(json: JSONObject, sourceUrl: String? = null): BitVoidMeshNodeDescriptor? {
            if (!json.optString("service").equals(BitVoidMeshProtocol.SERVICE, ignoreCase = true)) {
                return null
            }
            val version = json.optInt("protocolVersion", 0)
            if (version !in 1..BitVoidMeshProtocol.PROTOCOL_VERSION) return null
            val nodeId = json.optString("nodeId")
                .trim()
                .takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,128}")) }
                ?: sourceUrl?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
            val nodeType = json.optString("nodeType", BitVoidMeshProtocol.TYPE_ANDROID_GATEWAY)
                .trim()
                .ifBlank { BitVoidMeshProtocol.TYPE_ANDROID_GATEWAY }
            val directUrl = json.optNullableString("directPortalUrl") ?: sourceUrl
            val portalUrl = json.optNullableString("portalUrl")
            val coordinator = json.optBoolean(
                "coordinator",
                BitVoidMeshProtocol.isCoordinator(nodeType)
            )
            val portalAvailable = json.optBoolean(
                "portalAvailable",
                !coordinator && (directUrl != null || portalUrl != null)
            )
            return BitVoidMeshNodeDescriptor(
                nodeId = nodeId,
                nodeType = nodeType,
                directPortalUrl = directUrl,
                portalUrl = portalUrl,
                portalAvailable = portalAvailable,
                gatewayPriority = json.optInt("gatewayPriority", if (coordinator) 10 else 100),
                protocolVersion = version,
                coordinator = coordinator
            )
        }

        private fun JSONObject.optNullableString(name: String): String? {
            if (!has(name) || isNull(name)) return null
            return optString(name).trim().takeIf { it.isNotEmpty() }
        }
    }
}
