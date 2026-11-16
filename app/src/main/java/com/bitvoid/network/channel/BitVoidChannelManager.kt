package com.bitvoid.network.channel

import android.util.Base64
import com.bitvoid.network.media.BitVoidMediaItem
import com.bitvoid.network.media.BitVoidMediaManager
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

data class ChannelParticipant(
    val sessionToken: String,
    val callsign: String,
    val joinedTimestamp: Long,
    val isWebSocketConnected: Boolean
)

data class ChannelChatMessage(
    val id: String,
    val channelId: String,
    val senderCallsign: String,
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

data class BitVoidChannel(
    val channelId: String,
    val name: String,
    val isPublic: Boolean,
    val isHidden: Boolean = false,
    val privateKey: String? = null,
    val mediaList: List<BitVoidMediaItem> = emptyList(),
    val activeParticipants: List<ChannelParticipant> = emptyList(),
    val messageCount: Int = 0,
    val creatorUsername: String = "",
    val createdTimestamp: Long = System.currentTimeMillis()
)

data class ChannelSession(
    val token: String,
    val channelId: String,
    val callsign: String,
    val isPublic: Boolean,
    val issuedAt: Long
)

object BitVoidChannelManager {

    const val DEFAULT_PUBLIC_CHANNEL_ID = "general-chat"
    private const val MAX_CHAT_HISTORY_PER_CHANNEL = 120
    private const val GCM_IV_LENGTH_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    private val HEX_ALPHABET = "0123456789abcdef".toCharArray()
    private val secureRandom = SecureRandom()
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val sessionSecretKey: SecretKey by lazy {
        KeyGenerator.getInstance("AES").apply {
            init(256, secureRandom)
        }.generateKey()
    }

    interface ChatSocketListener {
        fun onBroadcastJson(jsonPayload: String)
        fun onChannelClosed()
    }

    private data class InternalChannel(
        val channelId: String,
        var name: String,
        var isPublic: Boolean,
        var isHidden: Boolean,
        var privateKey: String?,
        val mediaIds: MutableSet<String>,
        val creatorUsername: String,
        val createdTimestamp: Long
    )

    private val channels = ConcurrentHashMap<String, InternalChannel>()
    private val sessionTokens = ConcurrentHashMap<String, ChannelSession>()
    private val wsPresenceByToken = ConcurrentHashMap<String, Boolean>()
    private val chatHistory = ConcurrentHashMap<String, CopyOnWriteArrayList<ChannelChatMessage>>()
    private val socketListeners = ConcurrentHashMap<String, CopyOnWriteArrayList<ChatSocketListener>>()

    private val _channelsFlow = MutableStateFlow<List<BitVoidChannel>>(emptyList())
    val channelsFlow: StateFlow<List<BitVoidChannel>> = _channelsFlow.asStateFlow()

    init {
        ensureDefaultChannel()
    }

    fun ensureDefaultChannel() {
        if (channels.isNotEmpty()) return
        val defaultChannel = InternalChannel(
            channelId = DEFAULT_PUBLIC_CHANNEL_ID,
            name = "General Chat",
            isPublic = true,
            isHidden = false,
            privateKey = null,
            mediaIds = ConcurrentHashMap.newKeySet(),
            creatorUsername = "",
            createdTimestamp = System.currentTimeMillis()
        )
        channels.putIfAbsent(DEFAULT_PUBLIC_CHANNEL_ID, defaultChannel)
        chatHistory.putIfAbsent(DEFAULT_PUBLIC_CHANNEL_ID, CopyOnWriteArrayList())
        socketListeners.putIfAbsent(DEFAULT_PUBLIC_CHANNEL_ID, CopyOnWriteArrayList())
        publishChannelsSnapshot()
    }

    fun createChannel(
        rawName: String,
        isPublic: Boolean,
        rawChannelId: String? = null,
        rawPrivateKey: String? = null,
        mediaIds: Set<String> = emptySet(),
        isHidden: Boolean = false,
        creatorUsername: String? = null
    ): Result<BitVoidChannel> {
        val cleanName = rawName.trim()
        if (cleanName.isEmpty() || cleanName.length > 48) {
            return Result.failure(IllegalArgumentException("Channel name must be 1–48 characters."))
        }

        // Hidden channels are strictly accessible by Private Channel ID (never public).
        val effectivePublic = isPublic && !isHidden

        val resolvedId = if (!rawChannelId.isNullOrBlank()) {
            rawChannelId.trim()
        } else if (effectivePublic) {
            generateUniquePublicSlug(cleanName)
        } else {
            generatePrivateChannelId()
        }

        if (resolvedId.length > 48 || !resolvedId.matches(Regex("^[A-Za-z0-9._-]+$"))) {
            return Result.failure(
                IllegalArgumentException(
                    "Channel ID must be 1–48 characters using letters, numbers, '.', '_', or '-'."
                )
            )
        }
        if (channels.containsKey(resolvedId)) {
            return Result.failure(
                IllegalArgumentException("Channel ID '$resolvedId' already exists.")
            )
        }

        val cleanKey = if (effectivePublic) {
            null
        } else {
            rawPrivateKey?.trim()?.takeIf { it.isNotEmpty() }
        }

        val cleanCreator = creatorUsername?.let {
            com.bitvoid.network.direct.BitVoidUserPrefs.normalizeUsernameOrNull(it)
        }.orEmpty()
        val storedChannel = InternalChannel(
            channelId = resolvedId,
            name = cleanName,
            isPublic = effectivePublic,
            isHidden = isHidden,
            privateKey = cleanKey,
            mediaIds = ConcurrentHashMap.newKeySet<String>().apply { addAll(mediaIds) },
            creatorUsername = cleanCreator,
            createdTimestamp = System.currentTimeMillis()
        )
        channels[resolvedId] = storedChannel
        chatHistory.putIfAbsent(resolvedId, CopyOnWriteArrayList())
        socketListeners.putIfAbsent(resolvedId, CopyOnWriteArrayList())
        publishChannelsSnapshot()
        return Result.success(toPublicChannel(storedChannel))
    }

    fun setChannelVisibility(
        channelId: String,
        isPublic: Boolean,
        rawPrivateKey: String? = null,
        isHidden: Boolean = false
    ): Result<BitVoidChannel> {
        val storedChannel = channels[channelId]
            ?: return Result.failure(IllegalArgumentException("Channel '$channelId' not found."))
        val wasPublic = storedChannel.isPublic
        val effectivePublic = isPublic && !isHidden
        storedChannel.isPublic = effectivePublic
        storedChannel.isHidden = isHidden
        storedChannel.privateKey = if (effectivePublic) {
            null
        } else {
            rawPrivateKey?.trim()?.takeIf { it.isNotEmpty() } ?: storedChannel.privateKey
        }

        if (wasPublic && !effectivePublic) {
            // Revoke unverified/public tokens when converting a channel to private so peers re-authenticate with Private ID/key.
            revokeChannelTokens(channelId)
        }
        publishChannelsSnapshot()
        broadcastSystemNotice(
            channelId,
            if (effectivePublic) "Channel switched to PUBLIC broadcast." else "Channel locked to PRIVATE mode."
        )
        return Result.success(toPublicChannel(storedChannel))
    }

    fun updateChannel(
        channelId: String,
        rawName: String,
        isPublic: Boolean,
        rawPrivateKey: String?,
        mediaIds: Set<String>,
        isHidden: Boolean,
        requesterUsername: String
    ): Result<BitVoidChannel> {
        val storedChannel = channels[channelId]
            ?: return Result.failure(IllegalArgumentException("Channel '$channelId' not found."))
        if (channelId == DEFAULT_PUBLIC_CHANNEL_ID) {
            return Result.failure(IllegalArgumentException("General Chat cannot be edited."))
        }
        if (!isCreator(storedChannel, requesterUsername)) {
            return Result.failure(IllegalAccessException("Only the channel creator can edit this channel."))
        }
        val cleanName = rawName.trim()
        if (cleanName.isEmpty() || cleanName.length > 48) {
            return Result.failure(IllegalArgumentException("Channel name must be 1–48 characters."))
        }
        val effectivePublic = isPublic && !isHidden
        val wasPublic = storedChannel.isPublic
        storedChannel.name = cleanName
        storedChannel.isPublic = effectivePublic
        storedChannel.isHidden = isHidden
        storedChannel.privateKey = if (effectivePublic) {
            null
        } else {
            rawPrivateKey?.trim()?.takeIf { it.isNotEmpty() }
        }
        storedChannel.mediaIds.clear()
        storedChannel.mediaIds.addAll(mediaIds)
        if (wasPublic && !effectivePublic) revokeChannelTokens(channelId)
        publishChannelsSnapshot()
        broadcastSystemNotice(channelId, "Channel settings updated by its creator.")
        return Result.success(toPublicChannel(storedChannel))
    }

    fun canManageChannel(channelId: String, username: String): Boolean {
        val channel = channels[channelId] ?: return false
        return channelId != DEFAULT_PUBLIC_CHANNEL_ID && isCreator(channel, username)
    }

    fun deleteChannelByCreator(channelId: String, requesterUsername: String): Result<Unit> {
        val channel = channels[channelId]
            ?: return Result.failure(IllegalArgumentException("Channel '$channelId' not found."))
        if (channelId == DEFAULT_PUBLIC_CHANNEL_ID) {
            return Result.failure(IllegalArgumentException("General Chat cannot be deleted."))
        }
        if (!isCreator(channel, requesterUsername)) {
            return Result.failure(IllegalAccessException("Only the channel creator can delete this channel."))
        }
        deleteChannel(channelId)
        return Result.success(Unit)
    }

    fun assignMediaToChannel(channelId: String, mediaIds: Set<String>) {
        val channel = channels[channelId] ?: return
        channel.mediaIds.clear()
        channel.mediaIds.addAll(mediaIds)
        publishChannelsSnapshot()
        broadcastSystemNotice(
            channelId,
            "Host updated channel media (${mediaIds.size} item(s))."
        )
    }

    fun addMediaToChannel(channelId: String, mediaId: String) {
        val channel = channels[channelId] ?: return
        channel.mediaIds.add(mediaId)
        publishChannelsSnapshot()
    }

    fun toggleMediaAssignment(channelId: String, mediaId: String) {
        val channel = channels[channelId] ?: return
        val added = if (channel.mediaIds.contains(mediaId)) {
            channel.mediaIds.remove(mediaId)
            false
        } else {
            channel.mediaIds.add(mediaId)
            true
        }
        publishChannelsSnapshot()
        broadcastSystemNotice(
            channelId,
            if (added) "Host added media item to channel." else "Host removed media item from channel."
        )
    }

    fun syncMediaCatalog(availableMedia: List<BitVoidMediaItem>) {
        val validIds = availableMedia.mapTo(HashSet(availableMedia.size)) { it.id }
        var changed = false
        channels.values.forEach { channel ->
            val removed = channel.mediaIds.removeAll { it !in validIds }
            if (removed) changed = true
        }
        if (changed || _channelsFlow.value.isNotEmpty() || channels.isNotEmpty()) {
            publishChannelsSnapshot(availableMedia)
        }
    }

    fun deleteChannel(channelId: String) {
        channels.remove(channelId)
        chatHistory.remove(channelId)

        val listeners = socketListeners.remove(channelId)
        listeners?.forEach { listener ->
            try {
                listener.onChannelClosed()
            } catch (_: Exception) {
            }
        }
        revokeChannelTokens(channelId)
        publishChannelsSnapshot()
    }

    fun joinChannel(
        rawChannelId: String,
        rawPrivateKey: String? = null,
        rawUsername: String? = null
    ): ChannelSession? {
        val channelId = rawChannelId.trim()
        val channel = channels[channelId] ?: return null
        val cleanUsername = rawUsername?.let {
            com.bitvoid.network.direct.BitVoidUserPrefs.normalizeUsernameOrNull(it)
        }
        val creatorAccess = cleanUsername != null && isCreator(channel, cleanUsername)
        if (!channel.isPublic && !creatorAccess) {
            val requiredKey = channel.privateKey
            if (requiredKey != null && requiredKey != (rawPrivateKey?.trim() ?: "")) {
                return null
            }
        }

        val issuedAt = System.currentTimeMillis()
        if (cleanUsername != null) {
            com.bitvoid.network.direct.BitVoidDirectChatManager.registerUser(cleanUsername)
        }
        val callsign = cleanUsername ?: "Node-${randomHex(4).uppercase(Locale.US)}"
        val encryptedToken = encryptSessionToken(channelId, callsign, issuedAt)
        val session = ChannelSession(
            token = encryptedToken,
            channelId = channelId,
            callsign = callsign,
            isPublic = channel.isPublic,
            issuedAt = issuedAt
        )
        sessionTokens[encryptedToken] = session
        wsPresenceByToken[encryptedToken] = false
        publishChannelsSnapshot()
        return session
    }

    /**
     * Issues a scoped [ChannelSession] token to a student who has provided a verified
     * active Live Class ID (`CLS-XXXX-XX`) issued by the Host Teacher.
     */
    fun issueLiveClassStudentSession(
        rawChannelId: String,
        rawUsername: String? = null
    ): ChannelSession? {
        val channelId = rawChannelId.trim()
        val channel = channels[channelId] ?: return null
        val issuedAt = System.currentTimeMillis()
        val cleanUsername = rawUsername?.let {
            com.bitvoid.network.direct.BitVoidUserPrefs.normalizeUsernameOrNull(it)
        }
        if (cleanUsername != null) {
            com.bitvoid.network.direct.BitVoidDirectChatManager.registerUser(cleanUsername)
        }
        val callsign = cleanUsername ?: "Student-${randomHex(4).uppercase(Locale.US)}"
        val encryptedToken = encryptSessionToken(channelId, callsign, issuedAt)
        val session = ChannelSession(
            token = encryptedToken,
            channelId = channelId,
            callsign = callsign,
            isPublic = channel.isPublic,
            issuedAt = issuedAt
        )
        sessionTokens[encryptedToken] = session
        wsPresenceByToken[encryptedToken] = false
        publishChannelsSnapshot()
        return session
    }

    fun validateToken(token: String?, requiredChannelId: String? = null): ChannelSession? {
        if (token.isNullOrBlank()) return null
        val trimmed = token.trim()
        val session = sessionTokens[trimmed] ?: return null
        if (!decryptAndVerifyToken(trimmed, session)) {
            sessionTokens.remove(trimmed)
            wsPresenceByToken.remove(trimmed)
            return null
        }
        if (!channels.containsKey(session.channelId)) {
            sessionTokens.remove(trimmed)
            wsPresenceByToken.remove(trimmed)
            return null
        }
        if (requiredChannelId != null && session.channelId != requiredChannelId) {
            return null
        }
        return session
    }

    fun getChannel(channelId: String): BitVoidChannel? {
        val storedChannel = channels[channelId] ?: return null
        return toPublicChannel(storedChannel)
    }

    fun getPublicChannels(): List<BitVoidChannel> {
        return channels.values
            .filter { it.isPublic }
            .sortedBy { it.createdTimestamp }
            .map { toPublicChannel(it) }
    }

    fun getAllChannelSnapshots(): List<BitVoidChannel> {
        return channels.values
            .sortedBy { it.createdTimestamp }
            .map { toPublicChannel(it) }
    }

    fun getListedChannelSnapshots(): List<BitVoidChannel> {
        return channels.values
            .filter { !it.isHidden }
            .sortedBy { it.createdTimestamp }
            .map { toPublicChannel(it) }
    }

    fun getChannelSnapshotsForUser(rawUsername: String?): List<BitVoidChannel> {
        val username = rawUsername?.trim()?.removePrefix("@").orEmpty()
        return channels.values
            .filter { channel -> !channel.isHidden || isCreator(channel, username) }
            .sortedBy { it.createdTimestamp }
            .map { toPublicChannel(it) }
    }

    fun getChannelMedia(channelId: String): List<BitVoidMediaItem> {
        val mediaIds = channels[channelId]?.mediaIds?.toSet() ?: return emptyList()
        return BitVoidMediaManager.mediaItems.value.filter { it.id in mediaIds }
    }

    fun getPublicChannelMediaItems(): List<BitVoidMediaItem> {
        val publicMediaIds = channels.values
            .filter { it.isPublic }
            .flatMapTo(HashSet()) { it.mediaIds }
        return BitVoidMediaManager.mediaItems.value.filter {
            it.isPublic || it.id in publicMediaIds
        }
    }

    fun isMediaInPublicChannel(mediaId: String): Boolean {
        return channels.values.any { it.isPublic && mediaId in it.mediaIds }
    }

    fun isMediaInAnyPrivateChannel(mediaId: String): Boolean {
        return channels.values.any { !it.isPublic && mediaId in it.mediaIds }
    }

    fun isMediaAuthorizedForToken(token: String?, mediaId: String): Boolean {
        val session = validateToken(token) ?: return false
        val channel = channels[session.channelId] ?: return false
        return mediaId in channel.mediaIds
    }

    fun registerChatSocket(session: ChannelSession, listener: ChatSocketListener) {
        val list = socketListeners.computeIfAbsent(session.channelId) { CopyOnWriteArrayList() }
        list.add(listener)
        wsPresenceByToken[session.token] = true
        publishChannelsSnapshot()
        broadcastPresenceUpdate(session.channelId)
    }

    fun unregisterChatSocket(session: ChannelSession, listener: ChatSocketListener) {
        socketListeners[session.channelId]?.remove(listener)
        wsPresenceByToken[session.token] = false
        publishChannelsSnapshot()
        broadcastPresenceUpdate(session.channelId)
    }

    fun appendChatMessage(
        channelId: String,
        callsign: String,
        rawText: String,
        attachment: BitVoidMediaItem? = null
    ): ChannelChatMessage? {
        val trimmed = rawText.trim()
        if ((trimmed.isEmpty() && attachment == null) || !channels.containsKey(channelId)) return null
        val safeText = if (trimmed.length > 1000) trimmed.take(1000) else trimmed
        val now = System.currentTimeMillis()
        val encodedAttachmentId = attachment?.let {
            URLEncoder.encode(it.id, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
        val message = ChannelChatMessage(
            id = "cmsg_${randomHex(8)}",
            channelId = channelId,
            senderCallsign = callsign,
            text = safeText,
            timestampLabel = timeFormatter.format(Date(now)),
            timestampMs = now,
            attachmentId = attachment?.id,
            attachmentName = attachment?.title,
            attachmentSize = attachment?.size ?: 0L,
            attachmentMime = attachment?.mimeType,
            attachmentUrl = encodedAttachmentId?.let { "/stream/$it" },
            attachmentDownloadUrl = encodedAttachmentId?.let { "/download/$it" }
        )
        if (attachment != null) {
            channels[channelId]?.mediaIds?.add(attachment.id)
        }
        val history = chatHistory.computeIfAbsent(channelId) { CopyOnWriteArrayList() }
        history.add(message)
        while (history.size > MAX_CHAT_HISTORY_PER_CHANNEL) {
            history.removeAt(0)
        }
        publishChannelsSnapshot()

        val payload = """{"type":"chat","message":${messageToJson(message)}}"""
        socketListeners[channelId]?.forEach { listener ->
            try {
                listener.onBroadcastJson(payload)
            } catch (_: Exception) {
            }
        }
        return message
    }

    fun getChatHistory(channelId: String): List<ChannelChatMessage> {
        return chatHistory[channelId]?.toList() ?: emptyList()
    }

    fun getParticipants(channelId: String): List<ChannelParticipant> {
        return sessionTokens.values
            .filter { it.channelId == channelId }
            .sortedBy { it.issuedAt }
            .map { session ->
                ChannelParticipant(
                    sessionToken = session.token,
                    callsign = session.callsign,
                    joinedTimestamp = session.issuedAt,
                    isWebSocketConnected = wsPresenceByToken[session.token] == true
                )
            }
    }

    fun generatePrivateChannelId(): String {
        var candidate: String
        do {
            candidate = "CH-${randomHex(8).uppercase(Locale.US)}"
        } while (channels.containsKey(candidate))
        return candidate
    }

    fun clearAllForTests() {
        channels.clear()
        sessionTokens.clear()
        wsPresenceByToken.clear()
        chatHistory.clear()
        socketListeners.clear()
        _channelsFlow.value = emptyList()
    }

    private fun revokeChannelTokens(channelId: String) {
        val iter = sessionTokens.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.value.channelId == channelId) {
                wsPresenceByToken.remove(entry.key)
                iter.remove()
            }
        }
        val listeners = socketListeners[channelId]
        listeners?.forEach { listener ->
            try {
                listener.onChannelClosed()
            } catch (_: Exception) {
            }
        }
        listeners?.clear()
    }

    private fun encryptSessionToken(channelId: String, callsign: String, issuedAt: Long): String {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, sessionSecretKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val plaintext = "$channelId|$callsign|$issuedAt|${randomHex(6)}"
            .toByteArray(StandardCharsets.UTF_8)
        val ciphertext = cipher.doFinal(plaintext)
        val packed = ByteBuffer.allocate(iv.size + ciphertext.size)
            .put(iv)
            .put(ciphertext)
            .array()
        val encoded = Base64.encodeToString(packed, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return "BVC.$encoded"
    }

    private fun decryptAndVerifyToken(token: String, expected: ChannelSession): Boolean {
        if (!token.startsWith("BVC.")) return false
        return try {
            val raw = Base64.decode(
                token.removePrefix("BVC."),
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
                parts[0] == expected.channelId &&
                parts[1] == expected.callsign &&
                parts[2] == expected.issuedAt.toString()
        } catch (_: Exception) {
            false
        }
    }

    private fun generateUniquePublicSlug(name: String): String {
        val base = name.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "channel" }
            .take(36)
        if (!channels.containsKey(base)) return base
        var idx = 2
        while (channels.containsKey("$base-$idx")) {
            idx++
        }
        return "$base-$idx"
    }

    private fun broadcastPresenceUpdate(channelId: String) {
        val participants = getParticipants(channelId)
        val activeCount = participants.count { it.isWebSocketConnected }
        val namesJson = participants.joinToString(",") {
            """{"callsign":"${jsonEscape(it.callsign)}","live":${it.isWebSocketConnected}}"""
        }
        val payload =
            """{"type":"presence","channelId":"${jsonEscape(channelId)}","roomId":"${jsonEscape(channelId)}","activeCount":$activeCount,"totalUnlocked":${participants.size},"participants":[$namesJson]}"""
        socketListeners[channelId]?.forEach { listener ->
            try {
                listener.onBroadcastJson(payload)
            } catch (_: Exception) {
            }
        }
    }

    private fun broadcastSystemNotice(channelId: String, text: String) {
        val payload =
            """{"type":"notice","channelId":"${jsonEscape(channelId)}","roomId":"${jsonEscape(channelId)}","text":"${jsonEscape(text)}"}"""
        socketListeners[channelId]?.forEach { listener ->
            try {
                listener.onBroadcastJson(payload)
            } catch (_: Exception) {
            }
        }
    }

    private fun publishChannelsSnapshot(
        mediaCatalog: List<BitVoidMediaItem> = BitVoidMediaManager.mediaItems.value
    ) {
        _channelsFlow.value = channels.values
            .sortedWith(
                compareByDescending<InternalChannel> { it.channelId == DEFAULT_PUBLIC_CHANNEL_ID }
                    .thenByDescending { it.createdTimestamp }
            )
            .map { toPublicChannel(it, mediaCatalog) }
    }

    private fun toPublicChannel(
        storedChannel: InternalChannel,
        mediaCatalog: List<BitVoidMediaItem> = BitVoidMediaManager.mediaItems.value
    ): BitVoidChannel {
        val assignedMedia = mediaCatalog.filter { it.id in storedChannel.mediaIds }
        return BitVoidChannel(
            channelId = storedChannel.channelId,
            name = storedChannel.name,
            isPublic = storedChannel.isPublic,
            isHidden = storedChannel.isHidden,
            privateKey = storedChannel.privateKey,
            mediaList = assignedMedia,
            activeParticipants = getParticipants(storedChannel.channelId),
            messageCount = chatHistory[storedChannel.channelId]?.size ?: 0,
            creatorUsername = storedChannel.creatorUsername,
            createdTimestamp = storedChannel.createdTimestamp
        )
    }

    fun messageToJson(msg: ChannelChatMessage): String {
        val attachmentJson = if (msg.attachmentId != null && msg.attachmentName != null) {
            ""","attachment":{"id":"${jsonEscape(msg.attachmentId)}","name":"${jsonEscape(msg.attachmentName)}","size":${msg.attachmentSize},"mimeType":"${jsonEscape(msg.attachmentMime ?: "application/octet-stream")}","streamUrl":"${jsonEscape(msg.attachmentUrl ?: "")}","downloadUrl":"${jsonEscape(msg.attachmentDownloadUrl ?: "")}"}"""
        } else {
            ""
        }
        return """{"id":"${jsonEscape(msg.id)}","channelId":"${jsonEscape(msg.channelId)}","roomId":"${jsonEscape(msg.channelId)}","sender":"${jsonEscape(msg.senderCallsign)}","text":"${jsonEscape(msg.text)}","time":"${jsonEscape(msg.timestampLabel)}"$attachmentJson}"""
    }

    private fun isCreator(channel: InternalChannel, rawUsername: String): Boolean {
        val username = rawUsername.trim().removePrefix("@")
        return username.isNotEmpty() &&
            channel.creatorUsername.equals(username, ignoreCase = true)
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
