package com.bitvoid.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitvoid.network.local.BitVoidLocalStore
import com.bitvoid.network.local.LocalDeliveryState
import com.bitvoid.network.local.LocalMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BitVoidLocalStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("bitvoid_local_cache.db")
    }

    @After
    fun tearDown() {
        context.deleteDatabase("bitvoid_local_cache.db")
    }

    @Test
    fun `message IDs replace duplicates`() {
        BitVoidLocalStore(context).use { store ->
            val first = message(content = "first")
            store.saveMessage(first)
            store.saveMessage(first.copy(content = "server copy", deliveryState = LocalDeliveryState.DELIVERED))

            val restored = store.messages("conversation-1")
            assertEquals(1, restored.size)
            assertEquals("server copy", restored.single().content)
            assertEquals(LocalDeliveryState.DELIVERED, restored.single().deliveryState)
        }
    }

    @Test
    fun `pending queue survives store restoration and tracks failures`() {
        BitVoidLocalStore(context).use { store ->
            store.saveMessage(message())
            store.updateDelivery("message-1", LocalDeliveryState.FAILED)
        }

        BitVoidLocalStore(context).use { restored ->
            val pending = restored.pendingMessages()
            assertEquals(1, pending.size)
            assertEquals(LocalDeliveryState.FAILED, pending.single().deliveryState)
            restored.deleteMessage("message-1")
            assertTrue(restored.pendingMessages().isEmpty())
        }
    }

    private fun message(
        content: String = "queued",
        state: LocalDeliveryState = LocalDeliveryState.PENDING
    ) = LocalMessage(
        id = "message-1",
        conversationId = "conversation-1",
        sender = "alice",
        content = content,
        createdAtMs = 1_700_000_000_000L,
        deliveryState = state
    )
}
