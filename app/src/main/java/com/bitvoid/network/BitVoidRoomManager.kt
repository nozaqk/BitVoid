package com.bitvoid.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Host-visible room metadata. Passkeys and bearer tokens are never exposed here. */
data class BitVoidRoom(
    val roomId: String,
    val videoIds: Set<String>,
    val hasPasskey: Boolean,
    val activeParticipants: Int = 0,
    val activeParticipantNames: List<String> = emptyList(),
)

data class BitVoidChatMessage(
    val id: String,
    val roomId: String,
    val sender: String,
    val text: String,
    val timestamp: Long,
)

/**
 * In-memory private-room authority. Room passkeys are salted before storage, session
 * tokens expire, and room chat/history disappears when the app process ends.
 */
object BitVoidRoomManager {
    private val lock = Any()
    private val random = SecureRandom()
    private val _rooms = MutableStateFlow<List<BitVoidRoom>>(emptyList())
    val rooms: StateFlow<List<BitVoidRoom>> = _rooms.asStateFlow()

    private val roomSecrets = HashMap<String, RoomSecret>()
    private val sessions = ConcurrentHashMap<String, ClientSession>()
    private val participants = HashMap<String, MutableMap<String, Int>>()
    private val histories = HashMap<String, ArrayDeque<BitVoidChatMessage>>()

    fun createRoom(roomId: String, passkey: String?, videoIds: Set<String>): Boolean =
        synchronized(lock) {
            val normalizedId = roomId.trim()
            if (!ROOM_ID_PATTERN.matches(normalizedId) || roomSecrets.containsKey(normalizedId)) {
                return@synchronized false
            }
            val normalizedPasskey = passkey.orEmpty()
            val salt = if (normalizedPasskey.isBlank()) null else ByteArray(SALT_BYTES).also(random::nextBytes)
            val hash = salt?.let { derivePasskey(normalizedPasskey, it) }
            roomSecrets[normalizedId] = RoomSecret(
                roomId = normalizedId,
                salt = salt,
                passkeyHash = hash,
                videoIds = videoIds.toSet(),
            )
            histories[normalizedId] = ArrayDeque()
            publishRoomsLocked()
            true
        }

    fun authenticate(roomId: String, passkey: String?, displayName: String?): String? =
        synchronized(lock) {
            pruneExpiredSessionsLocked()
            val normalizedId = roomId.trim()
            val room = roomSecrets[normalizedId] ?: return@synchronized null
            if (!room.matchesPasskey(passkey.orEmpty())) return@synchronized null

            val tokenBytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes)
            val safeName = sanitizeDisplayName(displayName, token)
            sessions[token] = ClientSession(
                token = token,
                displayName = safeName,
                authorizedRoomIds = setOf(normalizedId),
                expiresAt = System.currentTimeMillis() + SESSION_LIFETIME_MS,
            )
            token
        }

    fun isAuthorized(token: String?, roomId: String): Boolean {
        if (token.isNullOrBlank()) return false
        val session = sessions[token] ?: return false
        if (session.expiresAt <= System.currentTimeMillis()) {
            sessions.remove(token, session)
            synchronized(lock) {
                participants.values.forEach { roomParticipants -> roomParticipants.remove(token) }
                participants.entries.removeIf { it.value.isEmpty() }
                publishRoomsLocked()
            }
            return false
        }
        return roomId in session.authorizedRoomIds && synchronized(lock) {
            roomSecrets.containsKey(roomId)
        }
    }

    fun participantName(token: String): String? =
        sessions[token]
            ?.takeIf { it.expiresAt > System.currentTimeMillis() }
            ?.displayName

    fun room(roomId: String): BitVoidRoom? = _rooms.value.firstOrNull { it.roomId == roomId }

    fun isVideoInRoom(roomId: String, videoId: String): Boolean = synchronized(lock) {
        videoId in (roomSecrets[roomId]?.videoIds ?: emptySet())
    }

    fun registerParticipant(roomId: String, token: String) = synchronized(lock) {
        if (!isAuthorized(token, roomId)) return@synchronized
        val roomParticipants = participants.getOrPut(roomId) { HashMap() }
        roomParticipants[token] = (roomParticipants[token] ?: 0) + 1
        publishRoomsLocked()
    }

    fun unregisterParticipant(roomId: String, token: String) = synchronized(lock) {
        val roomParticipants = participants[roomId] ?: return@synchronized
        val remaining = (roomParticipants[token] ?: return@synchronized) - 1
        if (remaining <= 0) roomParticipants.remove(token) else roomParticipants[token] = remaining
        if (roomParticipants.isEmpty()) participants.remove(roomId)
        publishRoomsLocked()
    }

    fun appendMessage(roomId: String, token: String, text: String): BitVoidChatMessage? =
        synchronized(lock) {
            if (!isAuthorized(token, roomId)) return@synchronized null
            val cleanText = text.trim().take(MAX_MESSAGE_CHARS)
            if (cleanText.isBlank()) return@synchronized null
            val sender = participantName(token) ?: return@synchronized null
            val message = BitVoidChatMessage(
                id = UUID.randomUUID().toString(),
                roomId = roomId,
                sender = sender,
                text = cleanText,
                timestamp = System.currentTimeMillis(),
            )
            val history = histories.getOrPut(roomId) { ArrayDeque() }
            history.addLast(message)
            while (history.size > MAX_HISTORY_MESSAGES) history.removeFirst()
            message
        }

    fun messageHistory(roomId: String, token: String): List<BitVoidChatMessage>? =
        synchronized(lock) {
            if (!isAuthorized(token, roomId)) return@synchronized null
            histories[roomId]?.toList().orEmpty()
        }

    fun participantCount(roomId: String): Int = synchronized(lock) {
        participants[roomId]?.size ?: 0
    }

    fun clearAll() = synchronized(lock) {
        roomSecrets.clear()
        sessions.clear()
        participants.clear()
        histories.clear()
        _rooms.value = emptyList()
    }

    private fun publishRoomsLocked() {
        _rooms.value = roomSecrets.values
            .map { secret ->
                BitVoidRoom(
                    roomId = secret.roomId,
                    videoIds = secret.videoIds,
                    hasPasskey = secret.passkeyHash != null,
                    activeParticipants = participants[secret.roomId]?.size ?: 0,
                    activeParticipantNames = participants[secret.roomId]
                        ?.keys
                        ?.mapNotNull { token -> sessions[token]?.displayName }
                        ?.sortedWith(String.CASE_INSENSITIVE_ORDER)
                        .orEmpty(),
                )
            }
            .sortedBy { it.roomId.lowercase() }
    }

    private fun pruneExpiredSessionsLocked() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { (_, session) -> session.expiresAt <= now }
        participants.forEach { (_, roomParticipants) ->
            roomParticipants.keys.removeAll { token -> sessions[token] == null }
        }
        participants.entries.removeIf { it.value.isEmpty() }
        publishRoomsLocked()
    }

    private fun sanitizeDisplayName(value: String?, token: String): String {
        val clean = value.orEmpty()
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
            .trim()
            .take(MAX_NAME_CHARS)
        return clean.ifBlank { "Guest-${token.take(5)}" }
    }

    private fun derivePasskey(passkey: String, salt: ByteArray): ByteArray {
        val specification = PBEKeySpec(
            passkey.toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            PASSKEY_HASH_BITS,
        )
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(specification)
                .encoded
        } finally {
            specification.clearPassword()
        }
    }

    private data class RoomSecret(
        val roomId: String,
        val salt: ByteArray?,
        val passkeyHash: ByteArray?,
        val videoIds: Set<String>,
    ) {
        fun matchesPasskey(candidate: String): Boolean {
            val expected = passkeyHash ?: return true
            val roomSalt = salt ?: return false
            val actual = BitVoidRoomManager.derivePasskey(candidate, roomSalt)
            return MessageDigest.isEqual(expected, actual)
        }
    }

    private data class ClientSession(
        val token: String,
        val displayName: String,
        val authorizedRoomIds: Set<String>,
        val expiresAt: Long,
    )

    private const val SALT_BYTES = 16
    private const val TOKEN_BYTES = 32
    private const val PBKDF2_ITERATIONS = 120_000
    private const val PASSKEY_HASH_BITS = 256
    private const val SESSION_LIFETIME_MS = 8 * 60 * 60 * 1_000L
    private const val MAX_MESSAGE_CHARS = 500
    private const val MAX_HISTORY_MESSAGES = 200
    private const val MAX_NAME_CHARS = 24
    private val ROOM_ID_PATTERN = Regex("[A-Za-z0-9_-]{3,32}")
}
