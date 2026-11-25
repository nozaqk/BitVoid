package com.bitvoid.network

import com.bitvoid.network.connection.BitVoidConnectionManager
import com.bitvoid.network.connection.BitVoidDiscoverySource
import com.bitvoid.network.connection.BitVoidMdnsCodec
import com.bitvoid.network.connection.BitVoidNodeEndpoint
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitVoidConnectionManagerTest {

    @Test
    fun `mdns query asks for bitvoid local A record`() {
        val query = BitVoidMdnsCodec.buildAQuery(BitVoidConnectionManager.NODE_HOSTNAME)

        assertEquals(1, unsignedShort(query, 4))
        assertEquals(1, unsignedShort(query, query.size - 4))
        assertEquals(1, unsignedShort(query, query.size - 2))
        assertTrue(query.toList().windowed(7).any { bytes ->
            bytes.map { it.toInt().toChar() }.joinToString("") == "bitvoid"
        })
    }

    @Test
    fun `mdns response extracts gateway ipv4`() {
        val expected = InetAddress.getByName("192.168.4.1") as Inet4Address
        val response = buildAddressResponse(expected)

        val actual = BitVoidMdnsCodec.findARecord(
            response,
            response.size,
            BitVoidConnectionManager.NODE_HOSTNAME
        )

        assertEquals(expected, actual)
        assertNull(BitVoidMdnsCodec.findARecord(response, response.size, "other.local"))
        assertNull(BitVoidMdnsCodec.findARecord(byteArrayOf(0, 1, 2), 3, "bitvoid.local"))
    }

    @Test
    fun `node endpoint exposes friendly API and direct transport fallback`() {
        val endpoint = BitVoidNodeEndpoint(
            address = InetAddress.getByName("192.168.4.1") as Inet4Address,
            source = BitVoidDiscoverySource.MULTICAST_DNS
        )

        assertEquals("http://bitvoid.local", endpoint.portalUrl)
        assertEquals("http://192.168.4.1", endpoint.directUrl)
        assertEquals("ws://bitvoid.local:80", endpoint.webSocketBaseUrl)
    }

    @Test
    fun `local API URLs are restricted to private node addresses`() {
        assertTrue(BitVoidConnectionManager.isAllowedLocalUrl("http://bitvoid.local"))
        assertTrue(BitVoidConnectionManager.isAllowedLocalUrl("http://192.168.4.1/dashboard"))
        assertTrue(BitVoidConnectionManager.isAllowedLocalUrl("http://10.0.0.2/local"))
        assertFalse(BitVoidConnectionManager.isAllowedLocalUrl("https://example.com/login"))
        assertFalse(BitVoidConnectionManager.isAllowedLocalUrl("javascript:alert(1)"))
    }

    private fun buildAddressResponse(address: Inet4Address): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { dns ->
            dns.writeShort(0)
            dns.writeShort(0x8400)
            dns.writeShort(0)
            dns.writeShort(1)
            dns.writeShort(0)
            dns.writeShort(0)
            dns.writeByte(7)
            dns.writeBytes("bitvoid")
            dns.writeByte(5)
            dns.writeBytes("local")
            dns.writeByte(0)
            dns.writeShort(1)
            dns.writeShort(0x8001)
            dns.writeInt(120)
            dns.writeShort(4)
            dns.write(address.address)
        }
        return output.toByteArray()
    }

    private fun unsignedShort(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    }
}
