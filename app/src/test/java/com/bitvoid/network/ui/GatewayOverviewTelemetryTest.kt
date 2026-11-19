package com.bitvoid.network.ui

import com.bitvoid.network.channel.BitVoidChannel
import com.bitvoid.network.channel.ChannelParticipant
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayOverviewTelemetryTest {

    @Test
    fun `connected users are deduplicated across channels and case`() {
        val alice = ChannelParticipant("alice-one", "Alice", 1L, true)
        val aliceAgain = ChannelParticipant("alice-two", " alice ", 2L, true)
        val bob = ChannelParticipant("bob", "Bob", 3L, true)
        val disconnected = ChannelParticipant("offline", "Offline", 4L, false)
        val channels = listOf(
            BitVoidChannel("one", "One", true, activeParticipants = listOf(alice, bob, disconnected)),
            BitVoidChannel("two", "Two", true, activeParticipants = listOf(aliceAgain))
        )

        assertEquals(2, countConnectedUsers(channels))
    }

    @Test
    fun `gateway byte rate uses elapsed monotonic time`() {
        assertEquals(
            2_048L,
            calculateGatewayBytesPerSecond(
                previousBytes = 1_000L,
                currentBytes = 3_048L,
                elapsedNanos = 1_000_000_000L
            )
        )
        assertEquals(0L, calculateGatewayBytesPerSecond(100L, 90L, 1_000_000_000L))
        assertEquals(0L, calculateGatewayBytesPerSecond(0L, 100L, 0L))
    }

    @Test
    fun `gateway speed formats in readable bit units`() {
        assertEquals("0 b/s", formatGatewaySpeed(0L))
        assertEquals("8.0 Kb/s", formatGatewaySpeed(1_000L))
        assertEquals("8.0 Mb/s", formatGatewaySpeed(1_000_000L))
        assertEquals("8.0 Gb/s", formatGatewaySpeed(1_000_000_000L))
    }
}
