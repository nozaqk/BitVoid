package com.bitvoid.network.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class LocalConversation(
    val id: String,
    val title: String,
    val updatedAtMs: Long,
    val lastMessage: String = ""
)

enum class LocalDeliveryState { PENDING, SENT, DELIVERED, FAILED }

data class LocalMessage(
    val id: String,
    val conversationId: String,
    val sender: String,
    val content: String,
    val createdAtMs: Long,
    val deliveryState: LocalDeliveryState = LocalDeliveryState.DELIVERED
)

/** SQLite cache and outgoing queue used when the PC server temporarily disappears. */
class BitVoidLocalStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE conversations (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                last_message TEXT NOT NULL DEFAULT ''
            )""".trimIndent()
        )
        db.execSQL(
            """CREATE TABLE messages (
                id TEXT PRIMARY KEY NOT NULL,
                conversation_id TEXT NOT NULL,
                sender TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                delivery_state TEXT NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX messages_conversation_time ON messages(conversation_id, created_at)")
        db.execSQL("CREATE INDEX messages_delivery ON messages(delivery_state)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS messages")
        db.execSQL("DROP TABLE IF EXISTS conversations")
        onCreate(db)
    }

    fun saveConversations(items: List<LocalConversation>) {
        writableDatabase.beginTransaction()
        try {
            items.forEach { item ->
                writableDatabase.insertWithOnConflict(
                    "conversations",
                    null,
                    ContentValues().apply {
                        put("id", item.id)
                        put("title", item.title)
                        put("updated_at", item.updatedAtMs)
                        put("last_message", item.lastMessage)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun conversations(): List<LocalConversation> = readableDatabase.query(
        "conversations",
        arrayOf("id", "title", "updated_at", "last_message"),
        null,
        null,
        null,
        null,
        "updated_at DESC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(
                    LocalConversation(
                        id = cursor.getString(0),
                        title = cursor.getString(1),
                        updatedAtMs = cursor.getLong(2),
                        lastMessage = cursor.getString(3)
                    )
                )
            }
        }
    }

    fun saveMessages(items: List<LocalMessage>) {
        writableDatabase.beginTransaction()
        try {
            items.forEach(::saveMessageInternal)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun saveMessage(item: LocalMessage) = saveMessageInternal(item)

    private fun saveMessageInternal(item: LocalMessage) {
        writableDatabase.insertWithOnConflict(
            "messages",
            null,
            ContentValues().apply {
                put("id", item.id)
                put("conversation_id", item.conversationId)
                put("sender", item.sender)
                put("content", item.content)
                put("created_at", item.createdAtMs)
                put("delivery_state", item.deliveryState.name)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun messages(conversationId: String): List<LocalMessage> = readableDatabase.query(
        "messages",
        arrayOf("id", "conversation_id", "sender", "content", "created_at", "delivery_state"),
        "conversation_id = ?",
        arrayOf(conversationId),
        null,
        null,
        "created_at ASC",
        "500"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.toMessage())
        }
    }

    fun pendingMessages(): List<LocalMessage> = readableDatabase.query(
        "messages",
        arrayOf("id", "conversation_id", "sender", "content", "created_at", "delivery_state"),
        "delivery_state IN (?, ?)",
        arrayOf(LocalDeliveryState.PENDING.name, LocalDeliveryState.FAILED.name),
        null,
        null,
        "created_at ASC",
        "200"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toMessage()) } }

    fun updateDelivery(id: String, state: LocalDeliveryState) {
        writableDatabase.update(
            "messages",
            ContentValues().apply { put("delivery_state", state.name) },
            "id = ?",
            arrayOf(id)
        )
    }

    fun deleteMessage(id: String) {
        writableDatabase.delete("messages", "id = ?", arrayOf(id))
    }

    fun clear() {
        writableDatabase.delete("messages", null, null)
        writableDatabase.delete("conversations", null, null)
    }

    private fun android.database.Cursor.toMessage(): LocalMessage = LocalMessage(
        id = getString(0),
        conversationId = getString(1),
        sender = getString(2),
        content = getString(3),
        createdAtMs = getLong(4),
        deliveryState = runCatching { LocalDeliveryState.valueOf(getString(5)) }
            .getOrDefault(LocalDeliveryState.DELIVERED)
    )

    companion object {
        private const val DATABASE_NAME = "bitvoid_local_cache.db"
        private const val DATABASE_VERSION = 1
    }
}
