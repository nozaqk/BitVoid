package com.bitvoid.network

import com.bitvoid.network.client.ClientChannelSession
import com.bitvoid.network.client.ClientChatAttachment
import com.bitvoid.network.client.ClientChatMessage
import com.bitvoid.network.client.ClientDirectSession
import com.bitvoid.network.client.ClientIncomingMessageEvent
import com.bitvoid.network.service.BitVoidMessageNotifications
import com.bitvoid.network.service.MessageNotificationDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitVoidMessageNotificationsTest {

    private val channelSession = ClientChannelSession(
        channelId = "CH-OPS",
        name = "Field Ops",
        isPublic = false,
        token = "token",
        callsign = "local-user",
        mediaList = emptyList()
    )

    private val directSession = ClientDirectSession(
        dmId = "dm__alice__bob",
        myUsername = "alice",
        peerUsername = "bob",
        token = "token"
    )

    @Test
    fun `channel notification identifies channel and remote sender`() {
        val spec = BitVoidMessageNotifications.createSpec(
            ClientIncomingMessageEvent.Channel(
                channelSession,
                message(sender = "bob", text = "Rally at the local node")
            )
        )

        requireNotNull(spec)
        assertEquals(MessageNotificationDestination.CHANNEL, spec.destination)
        assertEquals("CH-OPS", spec.conversationId)
        assertTrue(spec.title.contains("Field Ops"))
        assertTrue(spec.title.contains("bob"))
        assertEquals("Rally at the local node", spec.body)
    }

    @Test
    fun `channel policy excludes own messages system notices and live classes`() {
        assertNull(
            BitVoidMessageNotifications.createSpec(
                ClientIncomingMessageEvent.Channel(
                    channelSession,
                    message(sender = "LOCAL-USER", text = "own")
                )
            )
        )
        assertNull(
            BitVoidMessageNotifications.createSpec(
                ClientIncomingMessageEvent.Channel(
                    channelSession,
                    message(sender = "SYSTEM", text = "Media changed", isSystem = true)
                )
            )
        )
        assertNull(
            BitVoidMessageNotifications.createSpec(
                ClientIncomingMessageEvent.Channel(
                    channelSession.copy(isLiveClassRoom = true),
                    message(sender = "teacher", text = "Class activity")
                )
            )
        )
    }

    @Test
    fun `direct notification identifies remote user and formats attachment fallback`() {
        val spec = BitVoidMessageNotifications.createSpec(
            ClientIncomingMessageEvent.Direct(
                directSession,
                message(
                    channelId = directSession.dmId,
                    sender = "bob",
                    text = "",
                    attachment = ClientChatAttachment(
                        id = "file-1",
                        name = "map.pdf",
                        size = 42,
                        mimeType = "application/pdf",
                        streamUrl = "/stream/map",
                        downloadUrl = "/download/map"
                    )
                )
            )
        )

        requireNotNull(spec)
        assertEquals(MessageNotificationDestination.DIRECT, spec.destination)
        assertEquals("bob", spec.peerUsername)
        assertEquals("Message from @bob", spec.title)
        assertEquals("Shared file: map.pdf", spec.body)
    }

    @Test
    fun `direct policy excludes local users own messages`() {
        assertNull(
            BitVoidMessageNotifications.createSpec(
                ClientIncomingMessageEvent.Direct(
                    directSession,
                    message(channelId = directSession.dmId, sender = "ALICE", text = "own")
                )
            )
        )
    }

    @Test
    fun `conversation notification ids are stable and type scoped`() {
        val first = BitVoidMessageNotifications.stableId(
            MessageNotificationDestination.CHANNEL,
            "same-id"
        )
        val second = BitVoidMessageNotifications.stableId(
            MessageNotificationDestination.CHANNEL,
            "same-id"
        )
        val direct = BitVoidMessageNotifications.stableId(
            MessageNotificationDestination.DIRECT,
            "same-id"
        )

        assertEquals(first, second)
        assertNotEquals(first, direct)
    }

    private fun message(
        channelId: String = channelSession.channelId,
        sender: String,
        text: String,
        isSystem: Boolean = false,
        attachment: ClientChatAttachment? = null
    ) = ClientChatMessage(
        id = "message-${sender}-${text}",
        channelId = channelId,
        sender = sender,
        text = text,
        time = "10:00",
        isSystemNotice = isSystem,
        attachment = attachment
    )
}
