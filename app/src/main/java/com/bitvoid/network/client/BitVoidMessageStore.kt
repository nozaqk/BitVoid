package com.bitvoid.network.client

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small offline-first cache attached to the existing gateway message protocol. It stores stable
 * message IDs and delivery state only; backend session tokens and credentials are never persisted.
 */
internal class BitVoidMessageStore(context: Context?) {
    private val prefs = context?.applicationContext?.getSharedPreferences(
        "bitvoid_message_cache_v1",
        Context.MODE_PRIVATE
    )

    data class StoredThread(
        val conversationId: String,
        val direct: Boolean,
        val messages: List<ClientChatMessage>
    )

    fun load(conversationId: String, direct: Boolean): List<ClientChatMessage> {
        val raw = prefs?.getString(key(conversationId, direct), null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let(::decode)?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun loadAll(): List<StoredThread> {
        val entries = prefs?.all.orEmpty()
        return entries.mapNotNull { (key, value) ->
            val raw = value as? String ?: return@mapNotNull null
            val direct = when {
                key.startsWith(DIRECT_PREFIX) -> true
                key.startsWith(CHANNEL_PREFIX) -> false
                else -> return@mapNotNull null
            }
            val id = key.removePrefix(if (direct) DIRECT_PREFIX else CHANNEL_PREFIX)
            val messages = runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        array.optJSONObject(index)?.let(::decode)?.let(::add)
                    }
                }
            }.getOrDefault(emptyList())
            StoredThread(id, direct, messages)
        }
    }

    fun save(conversationId: String, direct: Boolean, messages: List<ClientChatMessage>) {
        val storage = prefs ?: return
        val array = JSONArray()
        messages.takeLast(if (direct) 150 else 120).forEach { array.put(encode(it)) }
        storage.edit().putString(key(conversationId, direct), array.toString()).apply()
    }

    private fun key(conversationId: String, direct: Boolean): String =
        (if (direct) DIRECT_PREFIX else CHANNEL_PREFIX) + conversationId

    private fun encode(message: ClientChatMessage): JSONObject = JSONObject().apply {
        put("id", message.id)
        put("channelId", message.channelId)
        put("sender", message.sender)
        put("text", message.text)
        put("time", message.time)
        put("timestampMs", message.timestampMs)
        put("isSystemNotice", message.isSystemNotice)
        put("deliveryState", message.deliveryState.name)
        message.attachment?.let { attachment ->
            put("attachment", JSONObject().apply {
                put("id", attachment.id)
                put("name", attachment.name)
                put("size", attachment.size)
                put("mimeType", attachment.mimeType)
                put("streamUrl", attachment.streamUrl)
                put("downloadUrl", attachment.downloadUrl)
            })
        }
    }

    private fun decode(obj: JSONObject): ClientChatMessage? {
        val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return null
        val conversationId = obj.optString("channelId").takeIf { it.isNotBlank() } ?: return null
        val attachment = obj.optJSONObject("attachment")?.let { value ->
            val attachmentId = value.optString("id").takeIf { it.isNotBlank() }
                ?: return@let null
            ClientChatAttachment(
                id = attachmentId,
                name = value.optString("name", "Shared file"),
                size = value.optLong("size", 0L),
                mimeType = value.optString("mimeType", "application/octet-stream"),
                streamUrl = value.optString("streamUrl"),
                downloadUrl = value.optString("downloadUrl")
            )
        }
        return ClientChatMessage(
            id = id,
            channelId = conversationId,
            sender = obj.optString("sender", "Peer"),
            text = obj.optString("text"),
            time = obj.optString("time"),
            timestampMs = obj.optLong("timestampMs", 0L),
            isSystemNotice = obj.optBoolean("isSystemNotice", false),
            attachment = attachment,
            deliveryState = runCatching {
                MessageDeliveryState.valueOf(obj.optString("deliveryState"))
            }.getOrDefault(MessageDeliveryState.DELIVERED)
        )
    }

    companion object {
        private const val DIRECT_PREFIX = "direct:"
        private const val CHANNEL_PREFIX = "channel:"
    }
}
