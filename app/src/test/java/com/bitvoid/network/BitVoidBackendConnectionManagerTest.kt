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
import com.bitvoid.network.direct.BitVoidUserPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BitVoidBackendConnectionManagerTest {

    @Before
    fun setUp() {
        BitVoidDirectChatManager.clearAllForTests()
    }

    @After
    fun tearDown() {
        BitVoidDirectChatManager.clearAllForTests()
    }

    @Test
    fun `backend URLs require safe schemes and strip trailing slash`() {
        assertEquals(
            "http://bitvoid.local",
            BitVoidBackendPrefs.normalizeConfiguredUrl("bitvoid.local/", allowCleartext = true)
        )
        assertEquals(
            "https://campus-chat.onrender.com",
            BitVoidBackendPrefs.normalizeConfiguredUrl(
                "https://campus-chat.onrender.com/",
                allowCleartext = false
            )
        )
        assertNull(
            BitVoidBackendPrefs.normalizeConfiguredUrl(
                "http://campus-chat.onrender.com",
                allowCleartext = false
            )
        )
        assertNull(
            BitVoidBackendPrefs.normalizeConfiguredUrl(
                "https://user:password@example.com",
                allowCleartext = false
            )
        )
    }

    @Test
    fun `production cloud backend is always configured`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val legacyPrefs = context.getSharedPreferences(
            "bitvoid_backend_preferences",
            Context.MODE_PRIVATE
        )
        legacyPrefs.edit()
            .putString("campus_server_url", "http://legacy.invalid")
            .putString("cloud_server_url", "https://legacy.invalid")
            .commit()

        val config = BitVoidBackendPrefs.load(context)

        assertEquals("http://bitvoid.local", config.localServerUrl)
        assertEquals("https://bitvoid-cloud-api.onrender.com", config.cloudServerUrl)
        assertFalse(legacyPrefs.contains("campus_server_url"))
        assertFalse(legacyPrefs.contains("cloud_server_url"))
    }

    @Test
    fun `account access completion persists`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        BitVoidUserPrefs.setAccountAccessComplete(context, false)
        assertFalse(BitVoidUserPrefs.hasCompletedAccountAccess(context))

        BitVoidUserPrefs.setAccountAccessComplete(context, true)
        assertTrue(BitVoidUserPrefs.hasCompletedAccountAccess(context))

        BitVoidUserPrefs.setAccountAccessComplete(context, false)
    }

    @Test
    fun `connection gate remains closed until a backend is connected`() {
        assertFalse(BitVoidBackendState().isConnected)
        assertFalse(
            BitVoidBackendState(
                phase = BitVoidBackendPhase.OFFLINE,
                baseUrl = null
            ).isConnected
        )
        assertTrue(
            BitVoidBackendState(
                type = BitVoidBackendType.CLOUD,
                phase = BitVoidBackendPhase.CONNECTED,
                baseUrl = "https://bitvoid-cloud-api.onrender.com"
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
    fun `backend selection prefers campus then cloud then offline`() {
        assertEquals(
            BitVoidBackendType.CAMPUS to "http://10.20.30.40:8080",
            BitVoidBackendConnectionManager.selectBackend(
                campusUrl = "http://10.20.30.40:8080/",
                cloudUrl = "https://bitvoid.onrender.com",
                hasInternet = true,
                cloudHealthy = true
            )
        )
        assertEquals(
            BitVoidBackendType.CLOUD to "https://bitvoid.onrender.com",
            BitVoidBackendConnectionManager.selectBackend(
                campusUrl = null,
                cloudUrl = "https://bitvoid.onrender.com/",
                hasInternet = true,
                cloudHealthy = true
            )
        )
        assertEquals(
            BitVoidBackendType.OFFLINE to null,
            BitVoidBackendConnectionManager.selectBackend(
                campusUrl = null,
                cloudUrl = "https://bitvoid.onrender.com",
                hasInternet = false,
                cloudHealthy = false
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
