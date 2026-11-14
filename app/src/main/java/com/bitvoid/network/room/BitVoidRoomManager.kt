package com.bitvoid.network.room

import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RoomParticipant(
    val sessionToken: String,
    val callsign: String,
    val joinedTimestamp: Long,
    val isWebSocketConnected: Boolean
)

data class RoomChatMessage(
    val id: String,
    val roomId: String,
    val senderCallsign: String,
    val text: String,
    val timestampLabel: String,
    val timestampMs: Long
)

data class BitVoidRoom(
    val roomId: String,
    val hasPasskey: Boolean,
    val videoIds: Set<String>,
    val activeParticipants: List<RoomParticipant>,
    val messageCount: Int,
    val createdTimestamp: Long
)

data class RoomSession(
    val token: String,
    val roomId: String,
    val callsign: String,
    val issuedAt: Long
)

object BitVoidRoomManager {

    private const val MAX_CHAT_HISTORY_PER_ROOM = 100
    private val TOKEN_ALPHABET = "0123456789abcdef".toCharArray()
    private val secureRandom = SecureRandom()
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    interface ChatSocketListener {
        fun onBroadcastJson(jsonPayload: String)
        fun onRoomClosed()
    }

    private data class InternalRoom(
        val roomId: String,
        val passkey: String?,
        val videoIds: MutableSet<String>,
        val createdTimestamp: Long
    )

    private val rooms = ConcurrentHashMap<String, InternalRoom>()
    private val sessionTokens = ConcurrentHashMap<String, RoomSession>()
    private val wsPresenceByToken = ConcurrentHashMap<String, Boolean>()
    private val chatHistory = ConcurrentHashMap<String, CopyOnWriteArrayList<RoomChatMessage>>()
    private val socketListeners = ConcurrentHashMap<String, CopyOnWriteArrayList<ChatSocketListener>>()

    private val _roomsFlow = MutableStateFlow<List<BitVoidRoom>>(emptyList())
    val roomsFlow: StateFlow<List<BitVoidRoom>> = _roomsFlow.asStateFlow()

    fun createOrUpdateRoom(
        rawRoomId: String,
        rawPasskey: String?,
        videoIds: Set<String>
    ): Result<BitVoidRoom> {
        val roomId = rawRoomId.trim()
        if (roomId.isEmpty() || roomId.length > 48 || !roomId.matches(Regex("^[A-Za-z0-9._-]+$"))) {
            return Result.failure(
                IllegalArgumentException("Room ID must be 1-48 characters using letters, numbers, '.', '_', or '-'.")
            )
        }
        val cleanPasskey = rawPasskey?.trim()?.takeIf { it.isNotEmpty() }

        val internal = InternalRoom(
            roomId = roomId,
            passkey = cleanPasskey,
            videoIds = ConcurrentHashMap.newKeySet<String>().apply { addAll(videoIds) },
            createdTimestamp = rooms[roomId]?.createdTimestamp ?: System.currentTimeMillis()
        )
        rooms[roomId] = internal
        chatHistory.putIfAbsent(roomId, CopyOnWriteArrayList())
        socketListeners.putIfAbsent(roomId, CopyOnWriteArrayList())
        publishRoomsSnapshot()

        return Result.success(toPublicRoom(internal))
    }

    fun updateRoomVideos(roomId: String, videoIds: Set<String>) {
        val room = rooms[roomId] ?: return
        room.videoIds.clear()
        room.videoIds.addAll(videoIds)
        publishRoomsSnapshot()
        broadcastSystemNotice(roomId, "Host updated shared media catalog (${videoIds.size} item(s)).")
    }

    fun deleteRoom(roomId: String) {
        rooms.remove(roomId)
        chatHistory.remove(roomId)

        val listeners = socketListeners.remove(roomId)
        listeners?.forEach { listener ->
            try {
                listener.onRoomClosed()
            } catch (_: Exception) {
            }
        }

        val tokenIter = sessionTokens.entries.iterator()
        while (tokenIter.hasNext()) {
            val entry = tokenIter.next()
            if (entry.value.roomId == roomId) {
                wsPresenceByToken.remove(entry.key)
                tokenIter.remove()
            }
        }
        publishRoomsSnapshot()
    }

    fun unlockRoom(rawRoomId: String, rawPasskey: String?): RoomSession? {
        val roomId = rawRoomId.trim()
        val room = rooms[roomId] ?: return null
        val requiredPass = room.passkey
        if (requiredPass != null && requiredPass != (rawPasskey?.trim() ?: "")) {
            return null
        }

        val token = "RM-${randomHex(16)}"
        val callsign = "Node-${randomHex(4).uppercase(Locale.US)}"
        val session = RoomSession(
            token = token,
            roomId = roomId,
            callsign = callsign,
            issuedAt = System.currentTimeMillis()
        )
        sessionTokens[token] = session
        wsPresenceByToken[token] = false
        publishRoomsSnapshot()
        return session
    }

    fun validateToken(token: String?, requiredRoomId: String? = null): RoomSession? {
        if (token.isNullOrBlank()) return null
        val session = sessionTokens[token.trim()] ?: return null
        if (!rooms.containsKey(session.roomId)) {
            sessionTokens.remove(token.trim())
            return null
        }
        if (requiredRoomId != null && session.roomId != requiredRoomId) {
            return null
        }
        return session
    }

    fun getRoomVideoIds(roomId: String): Set<String> {
        return rooms[roomId]?.videoIds?.toSet() ?: emptySet()
    }

    fun isVideoInAnyRoom(videoId: String): Boolean {
        return rooms.values.any { videoId in it.videoIds }
    }

    fun isVideoAuthorizedForToken(token: String?, videoId: String): Boolean {
        val session = validateToken(token) ?: return false
        val room = rooms[session.roomId] ?: return false
        return videoId in room.videoIds
    }

    fun registerChatSocket(
        session: RoomSession,
        listener: ChatSocketListener
    ) {
        val list = socketListeners.computeIfAbsent(session.roomId) { CopyOnWriteArrayList() }
        list.add(listener)
        wsPresenceByToken[session.token] = true
        publishRoomsSnapshot()
        broadcastPresenceUpdate(session.roomId)
    }

    fun unregisterChatSocket(
        session: RoomSession,
        listener: ChatSocketListener
    ) {
        socketListeners[session.roomId]?.remove(listener)
        wsPresenceByToken[session.token] = false
        publishRoomsSnapshot()
        broadcastPresenceUpdate(session.roomId)
    }

    fun appendChatMessage(
        roomId: String,
        callsign: String,
        rawText: String
    ): RoomChatMessage? {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty() || !rooms.containsKey(roomId)) return null
        val safeText = if (trimmed.length > 1000) trimmed.take(1000) else trimmed
        val now = System.currentTimeMillis()
        val message = RoomChatMessage(
            id = "msg_${randomHex(8)}",
            roomId = roomId,
            senderCallsign = callsign,
            text = safeText,
            timestampLabel = timeFormatter.format(Date(now)),
            timestampMs = now
        )
        val history = chatHistory.computeIfAbsent(roomId) { CopyOnWriteArrayList() }
        history.add(message)
        while (history.size > MAX_CHAT_HISTORY_PER_ROOM) {
            history.removeAt(0)
        }
        publishRoomsSnapshot()

        val payload = """{"type":"chat","message":${messageToJson(message)}}"""
        socketListeners[roomId]?.forEach { listener ->
            try {
                listener.onBroadcastJson(payload)
            } catch (_: Exception) {
            }
        }
        return message
    }

    fun getChatHistory(roomId: String): List<RoomChatMessage> {
        return chatHistory[roomId]?.toList() ?: emptyList()
    }

    fun getParticipants(roomId: String): List<RoomParticipant> {
        return sessionTokens.values
            .filter { it.roomId == roomId }
            .sortedBy { it.issuedAt }
            .map { session ->
                RoomParticipant(
                    sessionToken = session.token,
                    callsign = session.callsign,
                    joinedTimestamp = session.issuedAt,
                    isWebSocketConnected = wsPresenceByToken[session.token] == true
                )
            }
    }

    fun generateSuggestedRoomId(): String {
        return "VOID-${randomHex(4).uppercase(Locale.US)}"
    }

    fun clearAllForTests() {
        rooms.clear()
        sessionTokens.clear()
        wsPresenceByToken.clear()
        chatHistory.clear()
        socketListeners.clear()
        _roomsFlow.value = emptyList()
    }

    private fun broadcastPresenceUpdate(roomId: String) {
        val participants = getParticipants(roomId)
        val activeCount = participants.count { it.isWebSocketConnected }
        val namesJson = participants.joinToString(",") {
            """{"callsign":"${jsonEscape(it.callsign)}","live":${it.isWebSocketConnected}}"""
        }
        val payload = """{"type":"presence","roomId":"${jsonEscape(roomId)}","activeCount":$activeCount,"totalUnlocked":${participants.size},"participants":[$namesJson]}"""
        socketListeners[roomId]?.forEach { listener ->
            try {
                listener.onBroadcastJson(payload)
            } catch (_: Exception) {
            }
        }
    }

    private fun broadcastSystemNotice(roomId: String, text: String) {
        val payload = """{"type":"notice","roomId":"${jsonEscape(roomId)}","text":"${jsonEscape(text)}"}"""
        socketListeners[roomId]?.forEach { listener ->
            try {
                listener.onBroadcastJson(payload)
            } catch (_: Exception) {
            }
        }
    }

    private fun publishRoomsSnapshot() {
        _roomsFlow.value = rooms.values
            .sortedByDescending { it.createdTimestamp }
            .map { toPublicRoom(it) }
    }

    private fun toPublicRoom(internal: InternalRoom): BitVoidRoom {
        return BitVoidRoom(
            roomId = internal.roomId,
            hasPasskey = internal.passkey != null,
            videoIds = internal.videoIds.toSet(),
            activeParticipants = getParticipants(internal.roomId),
            messageCount = chatHistory[internal.roomId]?.size ?: 0,
            createdTimestamp = internal.createdTimestamp
        )
    }

    fun messageToJson(msg: RoomChatMessage): String {
        return """{"id":"${jsonEscape(msg.id)}","roomId":"${jsonEscape(msg.roomId)}","sender":"${jsonEscape(msg.senderCallsign)}","text":"${jsonEscape(msg.text)}","time":"${jsonEscape(msg.timestampLabel)}"}"""
    }

    private fun randomHex(length: Int): String {
        val chars = CharArray(length)
        for (i in chars.indices) {
            chars[i] = TOKEN_ALPHABET[secureRandom.nextInt(TOKEN_ALPHABET.size)]
        }
        return String(chars)
    }

    private fun jsonEscape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }
}
