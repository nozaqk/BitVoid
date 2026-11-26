package com.bitvoid.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitvoid.network.backend.BitVoidBackendConnectionManager
import com.bitvoid.network.backend.BitVoidBackendPhase
import com.bitvoid.network.backend.BitVoidBackendPrefs
import com.bitvoid.network.backend.BitVoidBackendState
import com.bitvoid.network.backend.BitVoidBackendType
import com.bitvoid.network.client.BitVoidMessageStore
import com.bitvoid.network.client.ClientChatMessage
import com.bitvoid.network.client.MessageDeliveryState
import com.bitvoid.network.direct.BitVoidDirectChatManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BitVoidBackendConnectionManagerTest {

    @Before
    fun setUp() {
        BitVoidDirectChatManager.clearAllForTests()
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("bitvoid_backend_preferences", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        BitVoidDirectChatManager.clearAllForTests()
    }

    @Test
    fun `local URLs default to port 8787 and strip API suffix`() {
        assertEquals(
            "http://bitvoid.local:8787",
            BitVoidBackendPrefs.normalizeConfiguredUrl("bitvoid.local", allowCleartext = true)
        )
        assertEquals(
            "http://192.168.4.20:8787",
            BitVoidBackendPrefs.normalizeConfiguredUrl(
                "http://192.168.4.20:8787/api/v1/",
                allowCleartext = true
            )
        )
        assertNull(
            BitVoidBackendPrefs.normalizeConfiguredUrl(
                "http://user:password@192.168.4.20:8787",
                allowCleartext = true
            )
        )
    }

    @Test
    fun `only local PC targets are allowed`() {
        assertTrue(BitVoidBackendPrefs.isAllowedLocalServer("http://bitvoid.local:8787"))
        assertTrue(BitVoidBackendPrefs.isAllowedLocalServer("http://192.168.1.40:8787"))
        assertTrue(BitVoidBackendPrefs.isAllowedLocalServer("https://10.1.2.3:8787"))
        assertFalse(BitVoidBackendPrefs.isAllowedLocalServer("https://bitvoid.example.com:8787"))
        assertFalse(BitVoidBackendPrefs.isAllowedLocalServer("http://8.8.8.8:8787"))
        assertFalse(BitVoidBackendPrefs.isAllowedLocalServer("http://192.168.1.40:8080"))
    }

    @Test
    fun `legacy cloud configuration is erased and never loaded`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("bitvoid_backend_preferences", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("campus_server_url", "http://legacy.invalid")
            .putString("cloud_server_url", "https://legacy.invalid")
            .commit()

        val config = BitVoidBackendPrefs.load(context)

        assertEquals("http://bitvoid.local:8787", config.localServerUrl)
        assertEquals("", config.cloudServerUrl)
        assertFalse(prefs.contains("campus_server_url"))
        assertFalse(prefs.contains("cloud_server_url"))
    }

    @Test
    fun `connection gate opens only for a connected local PC`() {
        assertFalse(BitVoidBackendState().isConnected)
        assertFalse(
            BitVoidBackendState(
                phase = BitVoidBackendPhase.OFFLINE,
                baseUrl = null
            ).isConnected
        )
        assertTrue(
            BitVoidBackendState(
                type = BitVoidBackendType.CAMPUS,
                phase = BitVoidBackendPhase.CONNECTED,
                baseUrl = "http://192.168.1.40:8787"
            ).isConnected
        )
        assertFalse(
            BitVoidBackendState(
                type = BitVoidBackendType.CLOUD,
                phase = BitVoidBackendPhase.CONNECTED,
                baseUrl = "https://example.com"
            ).isConnected
        )
    }

    @Test
    fun `retry backoff is exponential and capped`() {
        assertEquals(2_000L, BitVoidBackendConnectionManager.retryDelayMs(1))
        assertEquals(4_000L, BitVoidBackendConnectionManager.retryDelayMs(2))
        assertEquals(32_000L, BitVoidBackendConnectionManager.retryDelayMs(5))
        assertEquals(60_000L, BitVoidBackendConnectionManager.retryDelayMs(20))
    }

    @Test
    fun `backend selection never falls through to cloud`() {
        assertEquals(
            BitVoidBackendType.CAMPUS to "http://10.20.30.40:8787",
            BitVoidBackendConnectionManager.selectBackend(
                campusUrl = "http://10.20.30.40:8787/",
                cloudUrl = "https://bitvoid.example.com",
                hasInternet = true,
                cloudHealthy = true
            )
        )
        assertEquals(
            BitVoidBackendType.OFFLINE to null,
            BitVoidBackendConnectionManager.selectBackend(
                campusUrl = null,
                cloudUrl = "https://bitvoid.example.com",
                hasInternet = true,
                cloudHealthy = true
            )
        )
    }

    @Test
    fun `pending message outbox survives a store recreation`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val conversationId = "dm__alice__bob_test"
        val queued = ClientChatMessage(
            id = "client-persisted-1234",
            channelId = conversationId,
            sender = "alice",
            text = "send after reconnect",
            time = "12:00",
            timestampMs = 1_700_000_000_000L,
            deliveryState = MessageDeliveryState.PENDING
        )

        BitVoidMessageStore(context).save(conversationId, direct = true, listOf(queued))
        val restored = BitVoidMessageStore(context).load(conversationId, direct = true)

        assertEquals(listOf(queued), restored)
    }

    @Test
    fun `stable direct message ID is idempotent`() {
        BitVoidDirectChatManager.registerUser("alice")
        BitVoidDirectChatManager.registerUser("bob")
        val session = BitVoidDirectChatManager.openDirectSession("alice", "bob").getOrThrow()

        val first = BitVoidDirectChatManager.appendMessage(
            dmId = session.dmId,
            senderUsername = "alice",
            rawText = "queued once",
            stableMessageId = "client-stable-1234",
            clientTimestampMs = 1_700_000_000_000L
        )
        val duplicate = BitVoidDirectChatManager.appendMessage(
            dmId = session.dmId,
            senderUsername = "alice",
            rawText = "queued once",
            stableMessageId = "client-stable-1234",
            clientTimestampMs = 1_700_000_000_000L
        )

        assertSame(first, duplicate)
        assertEquals(1, BitVoidDirectChatManager.getHistory(session.dmId).size)
        assertEquals("client-stable-1234", first?.id)
    }
}
