package com.bitvoid.network

import com.bitvoid.network.mesh.BitVoidMeshNodeDescriptor
import com.bitvoid.network.mesh.BitVoidMeshProtocol
import com.bitvoid.network.model.NodeConnectionEventType
import com.bitvoid.network.service.BitVoidNodeStateHolder
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BitVoidMeshProtocolTest {

    @After
    fun clearLogs() {
        BitVoidNodeStateHolder.clearNodeConnectionLogs()
    }

    @Test
    fun `pi coordinator identity is recognized without becoming an app portal`() {
        val descriptor = BitVoidMeshNodeDescriptor.fromJson(
            JSONObject()
                .put("service", BitVoidMeshProtocol.SERVICE)
                .put("nodeId", "pi-test")
                .put("nodeType", BitVoidMeshProtocol.TYPE_PI_COORDINATOR)
                .put("protocolVersion", BitVoidMeshProtocol.PROTOCOL_VERSION)
                .put("coordinator", true)
                .put("portalAvailable", false)
                .put("directPortalUrl", "http://192.168.1.2:8080")
        )

        requireNotNull(descriptor)
        assertTrue(descriptor.coordinator)
        assertFalse(descriptor.portalAvailable)
        assertEquals("http://192.168.1.2:8080", descriptor.directPortalUrl)
    }

    @Test
    fun `future incompatible identity is rejected`() {
        val descriptor = BitVoidMeshNodeDescriptor.fromJson(
            JSONObject()
                .put("service", BitVoidMeshProtocol.SERVICE)
                .put("nodeId", "future-node")
                .put("protocolVersion", BitVoidMeshProtocol.PROTOCOL_VERSION + 1)
        )
        assertNull(descriptor)
    }

    @Test
    fun `node log suppresses immediate duplicate connection events`() {
        BitVoidNodeStateHolder.clearNodeConnectionLogs()
        repeat(2) {
            BitVoidNodeStateHolder.appendNodeConnectionLog(
                NodeConnectionEventType.DISCOVERY,
                "Mesh scan",
                "192.168.1.0/24"
            )
        }
        assertEquals(1, BitVoidNodeStateHolder.nodeConnectionLogs.value.size)
        assertEquals("Mesh scan", BitVoidNodeStateHolder.nodeConnectionLogs.value.single().title)
    }
}
