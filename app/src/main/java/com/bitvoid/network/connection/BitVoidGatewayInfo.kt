package com.bitvoid.network.connection

import org.json.JSONObject

/**
 * Hardware-neutral description returned by GET /api/v1/gateway/info.
 * Do not branch on hardware.family/model in app features; use capabilities instead.
 */
data class BitVoidGatewayInfo(
    val gatewayId: String,
    val displayName: String,
    val hardwareFamily: String,
    val hardwareModel: String,
    val protocolMajor: Int,
    val protocolMinor: Int,
    val capabilities: Set<String>,
    val maxClients: Int?
) {
    fun supports(capability: String): Boolean = capability in capabilities

    companion object {
        fun parse(json: JSONObject): BitVoidGatewayInfo {
            require(json.optString("service") == "bitvoid") {
                "The endpoint is not a AirMesh gateway."
            }
            val protocol = json.optJSONObject("protocol")
                ?: throw IllegalArgumentException("Gateway protocol metadata is missing.")
            require(protocol.optString("name") == "bitvoid-gateway") {
                "Unsupported gateway protocol name."
            }
            val major = protocol.optInt("major", -1)
            require(major == 1) {
                "Unsupported AirMesh Gateway API major version: $major"
            }
            val gatewayId = json.optString("gatewayId").trim()
            require(gatewayId.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) {
                "Gateway identity is missing or invalid."
            }
            val hardware = json.optJSONObject("hardware") ?: JSONObject()
            val caps = linkedSetOf<String>()
            val array = json.optJSONArray("capabilities")
            if (array != null) {
                for (index in 0 until array.length()) {
                    array.optString(index).trim().takeIf { it.isNotEmpty() }?.let(caps::add)
                }
            }
            val limits = json.optJSONObject("limits")
            return BitVoidGatewayInfo(
                gatewayId = gatewayId,
                displayName = json.optString("displayName", "AirMesh Gateway"),
                hardwareFamily = hardware.optString("family", "unknown"),
                hardwareModel = hardware.optString("model", "unknown"),
                protocolMajor = major,
                protocolMinor = protocol.optInt("minor", 0),
                capabilities = caps,
                maxClients = limits?.optInt("maxClients")?.takeIf { it > 0 }
            )
        }
    }
}
