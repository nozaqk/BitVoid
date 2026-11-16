package com.bitvoid.network.direct

import android.content.Context
import android.util.Base64
import com.bitvoid.network.media.BitVoidMediaItem
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DirectChatMessage(
    val id: String,
    val dmId: String,
    val senderUsername: String,
    val text: String,
    val timestampLabel: String,
    val timestampMs: Long,
    val attachmentId: String? = null,
    val attachmentName: String? = null,
    val attachmentSize: Long = 0L,
    val attachmentMime: String? = null,
    val attachmentUrl: String? = null,
    val attachmentDownloadUrl: String? = null
)

data class DirectConversationSummary(
    val dmId: String,
    val peerUsername: String,
    val lastMessagePreview: String?,
    val messageCount: Int,
    val lastTimestampMs: Long
)

data class DirectSession(
    val token: String,
    val dmId: String,
    val myUsername: String,
    val peerUsername: String,
    val issuedAt: Long
)

object BitVoidUserPrefs {
    private const val PREFS_NAME = "bitvoid_user_identity"
    private const val KEY_USERNAME = "username"
    private const val KEY_DISCOVERABLE = "user_id_discoverable"
    private val random = SecureRandom()

    fun loadOrCreate(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_USERNAME, null)?.let { normalizeUsernameOrNull(it) }
        if (existing != null) {
            return existing
        }
        val suffix = (1000 + random.nextInt(9000)).toString()
        val generated = "user-$suffix"
        prefs.edit().putString(KEY_USERNAME, generated).apply()
        return generated
    }

    fun isDiscoverable(context: Context): Boolean {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DISCOVERABLE, true)
    }

    fun setDiscoverable(context: Context, discoverable: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DISCOVERABLE, discoverable)
            .apply()
    }

    fun saveUsername(context: Context, rawUsername: String): Result<String> {
        val normalized = normalizeUsernameOrNull(rawUsername)
            ?: return Result.failure(
                IllegalArgumentException(
                    "Username ID must be 2–24 characters using letters, numbers, '.', '_', or '-'."
                )
            )
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USERNAME, normalized)
            .apply()
        return Result.success(normalized)
    }

    fun normalizeUsernameOrNull(raw: String): String? {
        val clean = raw.trim().removePrefix("@")
        if (clean.length !in 2..24) return null
        if (!clean.matches(Regex("^[A-Za-z0-9._-]+$"))) return null
        return clean
    }
}

object BitVoidDirectChatManager {

    private const val MAX_MESSAGES_PER_DM = 150
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private const val USER_PRESENCE_TTL_MS = 45_000L

    private val HEX_ALPHABET = "0123456789abcdef".toCharArray()
    private val secureRandom = SecureRandom()
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val sessionSecretKey: SecretKey by lazy {
        KeyGenerator.getInstance("AES").apply {
            init(256, secureRandom)
        }.generateKey()
    }

    interface DirectSocketListener {
        fun onBroadcastJson(jsonPayload: String)
        fun onSessionClosed()
    }

    private data class InternalThread(
        val dmId: String,
        val userA: String,
        val userB: String,
        var updatedTimestampMs: Long
    )

    private val registeredUsers = ConcurrentHashMap<String, String>() // lower -> display username
    private val userDiscoverability = ConcurrentHashMap<String, Boolean>()
    private val userLastSeenMs = ConcurrentHashMap<String, Long>()
    private val threads = ConcurrentHashMap<String, InternalThread>()
    private val sessionTokens = ConcurrentHashMap<String, DirectSession>()
    private val messageHistory = ConcurrentHashMap<String, CopyOnWriteArrayList<DirectChatMessage>>()
    private val socketListeners = ConcurrentHashMap<String, CopyOnWriteArrayList<DirectSocketListener>>()
    private val dmMediaIds = ConcurrentHashMap<String, MutableSet<String>>()

    private val _knownUsersFlow = MutableStateFlow<List<String>>(emptyList())
    val knownUsersFlow: StateFlow<List<String>> = _knownUsersFlow.asStateFlow()

    private val _conversationsFlow = MutableStateFlow<List<DirectConversationSummary>>(emptyList())
    val conversationsFlow: StateFlow<List<DirectConversationSummary>> = _conversationsFlow.asStateFlow()

    fun registerUser(rawUsername: String, isDiscoverable: Boolean? = null): Result<String> {
        val clean = BitVoidUserPrefs.normalizeUsernameOrNull(rawUsername)
            ?: return Result.failure(
                IllegalArgumentException(
                    "Username ID must be 2–24 characters using letters, numbers, '.', '_', or '-'."
                )
            )
        val key = clean.lowercase(Locale.US)
        registeredUsers[key] = clean
        if (isDiscoverable != null) {
            userDiscoverability[key] = isDiscoverable
        } else {
            userDiscoverability.putIfAbsent(key, true)
        }
        userLastSeenMs[key] = System.currentTimeMillis()
        publishUsers()
        return Result.success(clean)
    }

    fun getKnownUsers(): List<String> {
        publishUsers()
        return _knownUsersFlow.value
    }

    fun isUserAvailable(rawUsername: String): Boolean {
        val clean = BitVoidUserPrefs.normalizeUsernameOrNull(rawUsername) ?: return false
        pruneStaleUsers()
        return registeredUsers.containsKey(clean.lowercase(Locale.US))
    }

    fun computeDmId(userOne: String, userTwo: String): String {
        val first = userOne.trim().lowercase(Locale.US)
        val second = userTwo.trim().lowercase(Locale.US)
        val pair = if (first <= second) listOf(first, second) else listOf(second, first)
        return "dm__${pair[0]}__${pair[1]}"
    }

    fun openDirectSession(rawMyUsername: String, rawTargetUsername: String): Result<DirectSession> {
        val myClean = BitVoidUserPrefs.normalizeUsernameOrNull(rawMyUsername)
            ?: return Result.failure(IllegalArgumentException("Your Username ID must be 2–24 characters."))
        val targetClean = BitVoidUserPrefs.normalizeUsernameOrNull(rawTargetUsername)
            ?: return Result.failure(IllegalArgumentException("Target Username ID must be 2–24 characters."))
        if (myClean.equals(targetClean, ignoreCase = true)) {
            return Result.failure(IllegalArgumentException("Enter another user's Username ID to chat."))
        }

        registerUser(myClean)
        pruneStaleUsers()
        val targetKey = targetClean.lowercase(Locale.US)
        val activeTarget = registeredUsers[targetKey]
            ?: return Result.failure(
                IllegalStateException(
                    "User @$targetClean is not available on this BitVoid node or local Wi-Fi."
                )
            )

        val dmId = computeDmId(myClean, activeTarget)
        val existing = threads[dmId]
        if (existing == null) {
            threads[dmId] = InternalThread(
                dmId = dmId,
                userA = myClean,
                userB = activeTarget,
                updatedTimestampMs = System.currentTimeMillis()
            )
        }
        messageHistory.putIfAbsent(dmId, CopyOnWriteArrayList())
        socketListeners.putIfAbsent(dmId, CopyOnWriteArrayList())

        val issuedAt = System.currentTimeMillis()
        val token = encryptToken(dmId, myClean, issuedAt)
        val session = DirectSession(
            token = token,
            dmId = dmId,
            myUsername = myClean,
            peerUsername = activeTarget,
            issuedAt = issuedAt
        )
        sessionTokens[token] = session
        publishUsers()
        publishConversations(myClean)
        return Result.success(session)
    }

    fun validateToken(token: String?, requiredDmId: String? = null): DirectSession? {
        if (token.isNullOrBlank()) return null
        val trimmed = token.trim()
        val session = sessionTokens[trimmed] ?: return null
        if (!decryptAndVerifyToken(trimmed, session)) {
            sessionTokens.remove(trimmed)
            return null
        }
        if (requiredDmId != null && session.dmId != requiredDmId) {
            return null
        }
        touchUser(session.myUsername)
        return session
    }

    fun registerSocket(session: DirectSession, listener: DirectSocketListener) {
        touchUser(session.myUsername)
        val list = socketListeners.computeIfAbsent(session.dmId) { CopyOnWriteArrayList() }
        list.add(listener)
    }

    fun unregisterSocket(session: DirectSession, listener: DirectSocketListener) {
        socketListeners[session.dmId]?.remove(listener)
    }

    fun isMediaAuthorizedForToken(token: String?, dmId: String, mediaId: String): Boolean {
        validateToken(token, requiredDmId = dmId) ?: return false
        return dmMediaIds[dmId]?.contains(mediaId) == true
    }

    fun isMediaSharedInAnyDm(mediaId: String): Boolean {
        return dmMediaIds.values.any { mediaId in it }
    }

    fun appendMessage(
        dmId: String,
        senderUsername: String,
        rawText: String,
        attachment: BitVoidMediaItem? = null
    ): DirectChatMessage? {
        val trimmed = rawText.trim()
        if ((trimmed.isEmpty() && attachment == null) || !threads.containsKey(dmId)) return null
        val safeText = if (trimmed.length > 1000) trimmed.take(1000) else trimmed
        val now = System.currentTimeMillis()
        val encodedDm = URLEncoder.encode(dmId, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val encodedMedia = attachment?.let {
            URLEncoder.encode(it.id, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
        if (attachment != null) {
            dmMediaIds.computeIfAbsent(dmId) { ConcurrentHashMap.newKeySet() }.add(attachment.id)
        }
        val message = DirectChatMessage(
            id = "dmsg_${randomHex(8)}",
            dmId = dmId,
            senderUsername = senderUsername,
            text = safeText,
            timestampLabel = timeFormatter.format(Date(now)),
            timestampMs = now,
            attachmentId = attachment?.id,
            attachmentName = attachment?.title,
            attachmentSize = attachment?.size ?: 0L,
            attachmentMime = attachment?.mimeType,
            attachmentUrl = encodedMedia?.let { "/stream/dm/$encodedDm/$it" },
            attachmentDownloadUrl = encodedMedia?.let { "/download/dm/$encodedDm/$it" }
        )
        val history = messageHistory.computeIfAbsent(dmId) { CopyOnWriteArrayList() }
        history.add(message)
        while (history.size > MAX_MESSAGES_PER_DM) {
            history.removeAt(0)
        }
        threads[dmId]?.updatedTimestampMs = now
        publishConversations(senderUsername)

        val payload = """{"type":"chat","message":${messageToJson(message)}}"""
        socketListeners[dmId]?.forEach { listener ->
            try {
                listener.onBroadcastJson(payload)
            } catch (_: Exception) {
            }
        }
        return message
    }

    fun getHistory(dmId: String): List<DirectChatMessage> {
        return messageHistory[dmId]?.toList() ?: emptyList()
    }

    fun refreshConversationsForUser(myUsername: String) {
        publishConversations(myUsername)
    }

    fun clearAllForTests() {
        registeredUsers.clear()
        userDiscoverability.clear()
        userLastSeenMs.clear()
        threads.clear()
        sessionTokens.clear()
        messageHistory.clear()
        socketListeners.clear()
        dmMediaIds.clear()
        _knownUsersFlow.value = emptyList()
        _conversationsFlow.value = emptyList()
    }

    fun messageToJson(msg: DirectChatMessage): String {
        val attachmentJson = if (msg.attachmentId != null && msg.attachmentName != null) {
            ""","attachment":{"id":"${jsonEscape(msg.attachmentId)}","name":"${jsonEscape(msg.attachmentName)}","size":${msg.attachmentSize},"mimeType":"${jsonEscape(msg.attachmentMime ?: "application/octet-stream")}","streamUrl":"${jsonEscape(msg.attachmentUrl ?: "")}","downloadUrl":"${jsonEscape(msg.attachmentDownloadUrl ?: "")}"}"""
        } else {
            ""
        }
        return """{"id":"${jsonEscape(msg.id)}","dmId":"${jsonEscape(msg.dmId)}","channelId":"${jsonEscape(msg.dmId)}","sender":"${jsonEscape(msg.senderUsername)}","text":"${jsonEscape(msg.text)}","time":"${jsonEscape(msg.timestampLabel)}"$attachmentJson}"""
    }

    private fun touchUser(rawUsername: String) {
        val clean = BitVoidUserPrefs.normalizeUsernameOrNull(rawUsername) ?: return
        val key = clean.lowercase(Locale.US)
        registeredUsers[key] = clean
        userLastSeenMs[key] = System.currentTimeMillis()
    }

    private fun pruneStaleUsers(nowMs: Long = System.currentTimeMillis()) {
        userLastSeenMs.forEach { (key, lastSeen) ->
            if (nowMs - lastSeen > USER_PRESENCE_TTL_MS && userLastSeenMs.remove(key, lastSeen)) {
                registeredUsers.remove(key)
                userDiscoverability.remove(key)
            }
        }
    }

    private fun publishUsers() {
        pruneStaleUsers()
        _knownUsersFlow.value = registeredUsers
            .filterKeys { userDiscoverability[it] != false }
            .values
            .sortedBy { it.lowercase(Locale.US) }
    }

    private fun publishConversations(perspectiveUsername: String) {
        val lowerMe = perspectiveUsername.trim().lowercase(Locale.US)
        _conversationsFlow.value = threads.values
            .filter {
                it.userA.equals(lowerMe, ignoreCase = true) ||
                    it.userB.equals(lowerMe, ignoreCase = true)
            }
            .sortedByDescending { it.updatedTimestampMs }
            .map { t ->
                val peer = if (t.userA.equals(lowerMe, ignoreCase = true)) t.userB else t.userA
                val msgs = messageHistory[t.dmId]
                DirectConversationSummary(
                    dmId = t.dmId,
                    peerUsername = peer,
                    lastMessagePreview = msgs?.lastOrNull()?.text,
                    messageCount = msgs?.size ?: 0,
                    lastTimestampMs = t.updatedTimestampMs
                )
            }
    }

    private fun encryptToken(dmId: String, username: String, issuedAt: Long): String {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sessionSecretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val plaintext = "$dmId|$username|$issuedAt|${randomHex(6)}"
            .toByteArray(StandardCharsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintext)
        val packed = ByteBuffer.allocate(iv.size + ciphertext.size)
            .put(iv)
            .put(ciphertext)
            .array()
        val encoded = Base64.encodeToString(packed, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "BVD.$encoded"
    }

    private fun decryptAndVerifyToken(token: String, expected: DirectSession): Boolean {
        if (!token.startsWith("BVD.")) return false
        return try {
            val raw = Base64.decode(
                token.removePrefix("BVD."),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )
            if (raw.size <= GCM_IV_LENGTH_BYTES) return false
            val iv = raw.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val ciphertext = raw.copyOfRange(GCM_IV_LENGTH_BYTES, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, sessionSecretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            val plaintext = String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
            val parts = plaintext.split('|')
            parts.size >= 3 &&
                parts[0] == expected.dmId &&
                parts[1] == expected.myUsername &&
                parts[2] == expected.issuedAt.toString()
        } catch (_: Exception) {
            false
        }
    }

    private fun randomHex(length: Int): String {
        val chars = CharArray(length)
        for (i in chars.indices) {
            chars[i] = HEX_ALPHABET[secureRandom.nextInt(HEX_ALPHABET.size)]
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
