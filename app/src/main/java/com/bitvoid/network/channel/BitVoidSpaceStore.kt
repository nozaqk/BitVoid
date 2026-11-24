package com.bitvoid.network.channel

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Durable, versioned storage for server-owned groups and broadcast channels.
 *
 * Session tokens and WebSocket presence are intentionally not persisted: callers must obtain a
 * fresh scoped session after a process restart. Space membership, invitation hashes, bounded
 * message history, and media assignments survive restarts.
 */
internal class BitVoidSpaceStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        const val DATABASE_NAME = "bitvoid_spaces.db"
        const val DATABASE_VERSION = 1
    }

    data class StoredMember(
        val username: String,
        val role: BitVoidSpaceRole,
        val joinedTimestampMs: Long
    )

    data class StoredInvitation(
        val codeHash: String,
        val expiresAtMs: Long,
        val maxUses: Int,
        val uses: Int
    )

    data class StoredSpace(
        val channelId: String,
        val name: String,
        val isPublic: Boolean,
        val isHidden: Boolean,
        val privateKey: String?,
        val creatorUsername: String,
        val createdTimestamp: Long,
        val spaceKind: BitVoidSpaceKind,
        val description: String,
        val avatarDataUrl: String?,
        val mediaIds: Set<String>,
        val members: List<StoredMember>,
        val invitations: List<StoredInvitation>,
        val messages: List<ChannelChatMessage>
    )

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE spaces (
                channel_id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                is_public INTEGER NOT NULL,
                is_hidden INTEGER NOT NULL,
                private_key TEXT,
                creator_username TEXT NOT NULL,
                created_timestamp INTEGER NOT NULL,
                space_kind TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                avatar_data_url TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE space_members (
                channel_id TEXT NOT NULL,
                username_key TEXT NOT NULL,
                username TEXT NOT NULL,
                role TEXT NOT NULL,
                joined_timestamp INTEGER NOT NULL,
                PRIMARY KEY (channel_id, username_key),
                FOREIGN KEY (channel_id) REFERENCES spaces(channel_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE space_invitations (
                channel_id TEXT NOT NULL,
                code_hash TEXT NOT NULL,
                expires_at INTEGER NOT NULL,
                max_uses INTEGER NOT NULL,
                uses INTEGER NOT NULL,
                PRIMARY KEY (channel_id, code_hash),
                FOREIGN KEY (channel_id) REFERENCES spaces(channel_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE space_media (
                channel_id TEXT NOT NULL,
                media_id TEXT NOT NULL,
                PRIMARY KEY (channel_id, media_id),
                FOREIGN KEY (channel_id) REFERENCES spaces(channel_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE channel_messages (
                message_id TEXT PRIMARY KEY NOT NULL,
                channel_id TEXT NOT NULL,
                sender_callsign TEXT NOT NULL,
                text_body TEXT NOT NULL,
                timestamp_label TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                attachment_id TEXT,
                attachment_name TEXT,
                attachment_size INTEGER NOT NULL DEFAULT 0,
                attachment_mime TEXT,
                attachment_url TEXT,
                attachment_download_url TEXT,
                FOREIGN KEY (channel_id) REFERENCES spaces(channel_id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_channel_messages_space_time ON channel_messages(channel_id, timestamp_ms)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first durable-space schema. Future versions must add forward-only,
        // transactional migrations here; never drop existing account or conversation data.
        if (oldVersion < 1) onCreate(db)
    }

    @Synchronized
    fun loadSpaces(): List<StoredSpace> {
        val db = readableDatabase
        val spaces = LinkedHashMap<String, MutableStoredSpace>()
        db.query("spaces", null, null, null, null, null, "created_timestamp ASC").use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.string("channel_id")
                spaces[id] = MutableStoredSpace(
                    channelId = id,
                    name = cursor.string("name"),
                    isPublic = cursor.int("is_public") != 0,
                    isHidden = cursor.int("is_hidden") != 0,
                    privateKey = cursor.nullableString("private_key"),
                    creatorUsername = cursor.string("creator_username"),
                    createdTimestamp = cursor.long("created_timestamp"),
                    spaceKind = runCatching {
                        BitVoidSpaceKind.valueOf(cursor.string("space_kind"))
                    }.getOrDefault(BitVoidSpaceKind.LEGACY_CHAT),
                    description = cursor.string("description"),
                    avatarDataUrl = cursor.nullableString("avatar_data_url")
                )
            }
        }
        if (spaces.isEmpty()) return emptyList()

        db.query("space_members", null, null, null, null, null, "joined_timestamp ASC").use { cursor ->
            while (cursor.moveToNext()) {
                spaces[cursor.string("channel_id")]?.members?.add(
                    StoredMember(
                        username = cursor.string("username"),
                        role = runCatching { BitVoidSpaceRole.valueOf(cursor.string("role")) }
                            .getOrDefault(BitVoidSpaceRole.MEMBER),
                        joinedTimestampMs = cursor.long("joined_timestamp")
                    )
                )
            }
        }
        db.query("space_invitations", null, null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                spaces[cursor.string("channel_id")]?.invitations?.add(
                    StoredInvitation(
                        codeHash = cursor.string("code_hash"),
                        expiresAtMs = cursor.long("expires_at"),
                        maxUses = cursor.int("max_uses"),
                        uses = cursor.int("uses")
                    )
                )
            }
        }
        db.query("space_media", null, null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) {
                spaces[cursor.string("channel_id")]?.mediaIds?.add(cursor.string("media_id"))
            }
        }
        db.query(
            "channel_messages",
            null,
            null,
            null,
            null,
            null,
            "channel_id ASC, timestamp_ms ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val channelId = cursor.string("channel_id")
                spaces[channelId]?.messages?.add(
                    ChannelChatMessage(
                        id = cursor.string("message_id"),
                        channelId = channelId,
                        senderCallsign = cursor.string("sender_callsign"),
                        text = cursor.string("text_body"),
                        timestampLabel = cursor.string("timestamp_label"),
                        timestampMs = cursor.long("timestamp_ms"),
                        attachmentId = cursor.nullableString("attachment_id"),
                        attachmentName = cursor.nullableString("attachment_name"),
                        attachmentSize = cursor.long("attachment_size"),
                        attachmentMime = cursor.nullableString("attachment_mime"),
                        attachmentUrl = cursor.nullableString("attachment_url"),
                        attachmentDownloadUrl = cursor.nullableString("attachment_download_url")
                    )
                )
            }
        }
        return spaces.values.map(MutableStoredSpace::freeze)
    }

    @Synchronized
    fun saveSpace(space: StoredSpace) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("channel_messages", "channel_id = ?", arrayOf(space.channelId))
            db.delete("space_media", "channel_id = ?", arrayOf(space.channelId))
            db.delete("space_invitations", "channel_id = ?", arrayOf(space.channelId))
            db.delete("space_members", "channel_id = ?", arrayOf(space.channelId))
            db.insertWithOnConflict(
                "spaces",
                null,
                ContentValues().apply {
                    put("channel_id", space.channelId)
                    put("name", space.name)
                    put("is_public", if (space.isPublic) 1 else 0)
                    put("is_hidden", if (space.isHidden) 1 else 0)
                    putNullable("private_key", space.privateKey)
                    put("creator_username", space.creatorUsername)
                    put("created_timestamp", space.createdTimestamp)
                    put("space_kind", space.spaceKind.name)
                    put("description", space.description)
                    putNullable("avatar_data_url", space.avatarDataUrl)
                },
                SQLiteDatabase.CONFLICT_REPLACE
            )
            space.members.forEach { member ->
                db.insertOrThrow("space_members", null, ContentValues().apply {
                    put("channel_id", space.channelId)
                    put("username_key", member.username.lowercase(java.util.Locale.US))
                    put("username", member.username)
                    put("role", member.role.name)
                    put("joined_timestamp", member.joinedTimestampMs)
                })
            }
            space.invitations.forEach { invitation ->
                db.insertOrThrow("space_invitations", null, ContentValues().apply {
                    put("channel_id", space.channelId)
                    put("code_hash", invitation.codeHash)
                    put("expires_at", invitation.expiresAtMs)
                    put("max_uses", invitation.maxUses)
                    put("uses", invitation.uses)
                })
            }
            space.mediaIds.forEach { mediaId ->
                db.insertOrThrow("space_media", null, ContentValues().apply {
                    put("channel_id", space.channelId)
                    put("media_id", mediaId)
                })
            }
            space.messages.forEach { message ->
                db.insertWithOnConflict(
                    "channel_messages",
                    null,
                    ContentValues().apply {
                        put("message_id", message.id)
                        put("channel_id", space.channelId)
                        put("sender_callsign", message.senderCallsign)
                        put("text_body", message.text)
                        put("timestamp_label", message.timestampLabel)
                        put("timestamp_ms", message.timestampMs)
                        putNullable("attachment_id", message.attachmentId)
                        putNullable("attachment_name", message.attachmentName)
                        put("attachment_size", message.attachmentSize)
                        putNullable("attachment_mime", message.attachmentMime)
                        putNullable("attachment_url", message.attachmentUrl)
                        putNullable("attachment_download_url", message.attachmentDownloadUrl)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun deleteSpace(channelId: String) {
        writableDatabase.delete("spaces", "channel_id = ?", arrayOf(channelId))
    }

    @Synchronized
    fun clearAll() {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("channel_messages", null, null)
            db.delete("space_media", null, null)
            db.delete("space_invitations", null, null)
            db.delete("space_members", null, null)
            db.delete("spaces", null, null)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private data class MutableStoredSpace(
        val channelId: String,
        val name: String,
        val isPublic: Boolean,
        val isHidden: Boolean,
        val privateKey: String?,
        val creatorUsername: String,
        val createdTimestamp: Long,
        val spaceKind: BitVoidSpaceKind,
        val description: String,
        val avatarDataUrl: String?,
        val mediaIds: MutableSet<String> = linkedSetOf(),
        val members: MutableList<StoredMember> = mutableListOf(),
        val invitations: MutableList<StoredInvitation> = mutableListOf(),
        val messages: MutableList<ChannelChatMessage> = mutableListOf()
    ) {
        fun freeze() = StoredSpace(
            channelId = channelId,
            name = name,
            isPublic = isPublic,
            isHidden = isHidden,
            privateKey = privateKey,
            creatorUsername = creatorUsername,
            createdTimestamp = createdTimestamp,
            spaceKind = spaceKind,
            description = description,
            avatarDataUrl = avatarDataUrl,
            mediaIds = mediaIds,
            members = members,
            invitations = invitations,
            messages = messages
        )
    }

    private fun android.database.Cursor.string(column: String): String =
        getString(getColumnIndexOrThrow(column))

    private fun android.database.Cursor.nullableString(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun android.database.Cursor.long(column: String): Long =
        getLong(getColumnIndexOrThrow(column))

    private fun android.database.Cursor.int(column: String): Int =
        getInt(getColumnIndexOrThrow(column))

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }
}
