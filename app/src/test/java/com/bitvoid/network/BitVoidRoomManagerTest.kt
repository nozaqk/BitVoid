package com.bitvoid.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BitVoidRoomManagerTest {
    @Before
    fun setUp() {
        BitVoidRoomManager.clearAll()
    }

    @After
    fun tearDown() {
        BitVoidRoomManager.clearAll()
    }

    @Test
    fun roomAuthenticationAuthorizationAndEphemeralChat() {
        assertTrue(
            BitVoidRoomManager.createRoom(
                roomId = "room-alpha",
                passkey = "local secret",
                videoIds = setOf("video-1", "video-2"),
            ),
        )
        assertNull(BitVoidRoomManager.authenticate("room-alpha", "wrong", "Peer"))

        val token = BitVoidRoomManager.authenticate("room-alpha", "local secret", "Peer One")
        assertNotNull(token)
        val validToken = requireNotNull(token)
        assertTrue(BitVoidRoomManager.isAuthorized(validToken, "room-alpha"))
        assertFalse(BitVoidRoomManager.isAuthorized(validToken, "different-room"))
        assertTrue(BitVoidRoomManager.isVideoInRoom("room-alpha", "video-1"))

        BitVoidRoomManager.registerParticipant("room-alpha", validToken)
        val activeRoom = BitVoidRoomManager.room("room-alpha")
        assertEquals(1, activeRoom?.activeParticipants)
        assertEquals(listOf("Peer One"), activeRoom?.activeParticipantNames)

        val message = BitVoidRoomManager.appendMessage("room-alpha", validToken, "Hello nearby peers")
        assertNotNull(message)
        assertEquals(
            listOf("Hello nearby peers"),
            BitVoidRoomManager.messageHistory("room-alpha", validToken)?.map { it.text },
        )

        BitVoidRoomManager.unregisterParticipant("room-alpha", validToken)
        assertEquals(0, BitVoidRoomManager.room("room-alpha")?.activeParticipants)
    }

    @Test
    fun roomIdsAreValidatedAndUnique() {
        assertFalse(BitVoidRoomManager.createRoom("x", null, setOf("video-1")))
        assertFalse(BitVoidRoomManager.createRoom("spaces are invalid", null, setOf("video-1")))
        assertTrue(BitVoidRoomManager.createRoom("valid_room-01", null, setOf("video-1")))
        assertFalse(BitVoidRoomManager.createRoom("valid_room-01", null, setOf("video-2")))

        val token = BitVoidRoomManager.authenticate("valid_room-01", null, null)
        assertNotNull(token)
    }
}
