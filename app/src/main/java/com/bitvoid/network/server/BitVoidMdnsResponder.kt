package com.bitvoid.network.server

import android.content.Context
import android.net.wifi.WifiManager
import com.bitvoid.network.model.BitVoidNetworkConfig
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets

/**
 * Minimal multicast-DNS responder for the stable local gateway name [HOSTNAME].
 *
 * Android's hotspot DHCP server does not let applications publish custom DNS records. mDNS is
 * therefore used so phones and computers on the same local Wi-Fi can resolve `bitvoid.local`
 * without knowing the gateway's changing IPv4 address.
 */
class BitVoidMdnsResponder(context: Context) {

    companion object {
        const val HOSTNAME = BitVoidNetworkConfig.LOCAL_GATEWAY_HOSTNAME
        private const val MDNS_PORT = 5353
        private const val RESPONSE_TTL_SECONDS = 120
        private const val MAX_PACKET_SIZE = 1500
        private val MDNS_GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
        private val HOSTNAME_WIRE = byteArrayOf(
            7,
            'b'.code.toByte(), 'i'.code.toByte(), 't'.code.toByte(),
            'v'.code.toByte(), 'o'.code.toByte(), 'i'.code.toByte(), 'd'.code.toByte(),
            5,
            'l'.code.toByte(), 'o'.code.toByte(), 'c'.code.toByte(),
            'a'.code.toByte(), 'l'.code.toByte(),
            0
        )

        internal fun isHostnameQuery(data: ByteArray, length: Int = data.size): Boolean {
            if (length < 12 || length > data.size) return false
            // Only answer DNS queries. Responding to another announcement would create a loop.
            if ((data[2].toInt() and 0x80) != 0) return false
            val questionCount = readUnsignedShort(data, 4)
            var cursor = 12
            repeat(questionCount) {
                val decoded = decodeName(data, length, cursor) ?: return false
                cursor = decoded.nextOffset
                if (cursor + 4 > length) return false
                val type = readUnsignedShort(data, cursor)
                val dnsClass = readUnsignedShort(data, cursor + 2) and 0x7FFF
                cursor += 4
                if (decoded.name.equals(HOSTNAME, ignoreCase = true) &&
                    (type == 1 || type == 255) &&
                    (dnsClass == 1 || dnsClass == 255)
                ) {
                    return true
                }
            }
            return false
        }

        private data class DecodedName(val name: String, val nextOffset: Int)

        private fun decodeName(
            data: ByteArray,
            length: Int,
            startOffset: Int,
            depth: Int = 0
        ): DecodedName? {
            if (depth > 8 || startOffset !in 0 until length) return null
            val labels = mutableListOf<String>()
            var cursor = startOffset
            var nextOffset = -1
            while (cursor < length) {
                val labelLength = data[cursor].toInt() and 0xFF
                when {
                    labelLength == 0 -> {
                        val end = if (nextOffset >= 0) nextOffset else cursor + 1
                        return DecodedName(labels.joinToString("."), end)
                    }
                    labelLength and 0xC0 == 0xC0 -> {
                        if (cursor + 1 >= length) return null
                        val pointer = ((labelLength and 0x3F) shl 8) or
                            (data[cursor + 1].toInt() and 0xFF)
                        if (nextOffset < 0) nextOffset = cursor + 2
                        val suffix = decodeName(data, length, pointer, depth + 1) ?: return null
                        if (suffix.name.isNotEmpty()) labels += suffix.name
                        return DecodedName(labels.joinToString("."), nextOffset)
                    }
                    labelLength > 63 || cursor + 1 + labelLength > length -> return null
                    else -> {
                        labels += String(
                            data,
                            cursor + 1,
                            labelLength,
                            StandardCharsets.US_ASCII
                        )
                        cursor += labelLength + 1
                    }
                }
            }
            return null
        }

        private fun readUnsignedShort(data: ByteArray, offset: Int): Int {
            return ((data[offset].toInt() and 0xFF) shl 8) or
                (data[offset + 1].toInt() and 0xFF)
        }

        internal fun buildAddressResponse(address: Inet4Address): ByteArray {
            val output = ByteArrayOutputStream(64)
            DataOutputStream(output).use { dns ->
                dns.writeShort(0) // mDNS transaction ID
                dns.writeShort(0x8400) // response + authoritative answer
                dns.writeShort(0) // questions
                dns.writeShort(1) // answers
                dns.writeShort(0) // authority records
                dns.writeShort(0) // additional records
                dns.write(HOSTNAME_WIRE)
                dns.writeShort(1) // A record
                dns.writeShort(0x8001) // IN class + cache-flush bit
                dns.writeInt(RESPONSE_TTL_SECONDS)
                dns.writeShort(4)
                dns.write(address.address)
            }
            return output.toByteArray()
        }
    }

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var socket: MulticastSocket? = null
    private var worker: Thread? = null
    private var activeAddress: Inet4Address? = null
    private var activeInterfaceName: String? = null

    @Volatile
    private var running = false

    @Synchronized
    fun start(rawIpv4: String): Boolean {
        val address = runCatching { InetAddress.getByName(rawIpv4) as? Inet4Address }.getOrNull()
            ?.takeUnless { it.isAnyLocalAddress || it.isLoopbackAddress }
            ?: return false
        // Never claim that the friendly hostname is available for a guessed/fallback address.
        // Wait until Android has assigned that address to a real hotspot/P2P/LAN interface.
        val networkInterface = findNetworkInterface(address) ?: run {
            stop()
            return false
        }
        if (running &&
            activeAddress == address &&
            activeInterfaceName == networkInterface.name &&
            socket?.isClosed == false
        ) return true

        stop()
        return try {
            val createdLock = wifiManager.createMulticastLock("BitVoid-mDNS").apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = createdLock

            val createdSocket = MulticastSocket(null)
            socket = createdSocket
            createdSocket.apply {
                reuseAddress = true
                bind(InetSocketAddress(MDNS_PORT))
                timeToLive = 255
                this.networkInterface = networkInterface
                joinGroup(InetSocketAddress(MDNS_GROUP, MDNS_PORT), networkInterface)
            }
            activeAddress = address
            activeInterfaceName = networkInterface.name
            running = true
            worker = Thread(
                { listen(createdSocket, address) },
                "BitVoid-mDNS"
            ).apply {
                isDaemon = true
                start()
            }
            announce(createdSocket, address)
            true
        } catch (_: Exception) {
            stop()
            false
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        worker?.interrupt()
        worker = null
        activeAddress = null
        activeInterfaceName = null
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    private fun listen(activeSocket: MulticastSocket, address: Inet4Address) {
        val receiveBuffer = ByteArray(MAX_PACKET_SIZE)
        while (running && !activeSocket.isClosed) {
            try {
                val packet = java.net.DatagramPacket(receiveBuffer, receiveBuffer.size)
                activeSocket.receive(packet)
                if (isHostnameQuery(packet.data, packet.length)) {
                    announce(activeSocket, address)
                }
            } catch (_: Exception) {
                if (running && !activeSocket.isClosed) continue
                break
            }
        }
    }

    private fun announce(activeSocket: MulticastSocket, address: Inet4Address) {
        val response = buildAddressResponse(address)
        val packet = java.net.DatagramPacket(response, response.size, MDNS_GROUP, MDNS_PORT)
        runCatching { activeSocket.send(packet) }
    }

    private fun findNetworkInterface(address: Inet4Address): NetworkInterface? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList().firstOrNull { networkInterface ->
                networkInterface.isUp && networkInterface.inetAddresses.toList().any { it == address }
            }
        }.getOrNull()
    }
}
