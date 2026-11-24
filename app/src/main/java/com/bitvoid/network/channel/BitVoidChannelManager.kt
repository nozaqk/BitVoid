package com.bitvoid.network.channel

import android.content.Context
import android.util.Base64
import com.bitvoid.network.media.BitVoidMediaItem
import com.bitvoid.network.media.BitVoidMediaManager
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
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

enum class BitVoidSpaceKind {
    LEGACY_CHAT,
    GROUP,
    BROADCAST_CHANNEL
}

enum class BitVoidSpaceRole {
    OWNER,
    ADMINISTRATOR,
    MEMBER,
    SUBSCRIBER
}

data class BitVoidSpaceMember(
    val username: String,
    val role: BitVoidSpaceRole,
    val joinedTimestampMs: Long
)

data class BitVoidSpaceInvitation(
    val code: String,
    val spaceId: String,
    val expiresAtMs: Long,
    val remainingUses: Int
)

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
    val createdTimestamp: Long = System.currentTimeMillis(),
    val lastMessagePreview: String? = null,
    val lastTimestampMs: Long = 0L,
    val spaceKind: BitVoidSpaceKind = BitVoidSpaceKind.LEGACY_CHAT,
    val description: String = "",
    val avatarDataUrl: String? = null,
    val members: List<BitVoidSpaceMember> = emptyList(),
    val subscriberCount: Int = 0
)

data class ChannelSession(
    val token: String,
    val channelId: String,
    val callsign: String,
    val isPublic: Boolean,
    val issuedAt: Long,
    val role: BitVoidSpaceRole? = null,
    val spaceKind: BitVoidSpaceKind = BitVoidSpaceKind.LEGACY_CHAT
)

object BitVoidChannelManager {

    const val DEFAULT_PUBLIC_CHANNEL_ID = "general-chat"
    private const val MAX_CHAT_HISTORY_PER_CHANNEL = 120
    private const val MAX_CHANNELS = 256
    private const val MAX_SPACE_MEMBERS = 256
    private const val MAX_DESCRIPTION_LENGTH = 500
    private const val MAX_AVATAR_DATA_URL_LENGTH = 180_000
    private const val INVITATION_LIFETIME_MS = 7L * 24L * 60L * 60L * 1_000L
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

    private data class InternalSpaceMember(
        val username: String,
        var role: BitVoidSpaceRole,
        val joinedTimestampMs: Long
    )

    private data class InternalInvitation(
        val codeHash: String,
        val expiresAtMs: Long,
        val maxUses: Int,
        var uses: Int
    )

    private data class InternalChannel(
        val channelId: String,
        var name: String,
        var isPublic: Boolean,
        var isHidden: Boolean,
        var privateKey: String?,
        val mediaIds: MutableSet<String>,
        val creatorUsername: String,
        val createdTimestamp: Long,
        var spaceKind: BitVoidSpaceKind = BitVoidSpaceKind.LEGACY_CHAT,
        var description: String = "",
        var avatarDataUrl: String? = null,
        val members: MutableMap<String, InternalSpaceMember> = ConcurrentHashMap(),
        val invitations: MutableMap<String, InternalInvitation> = ConcurrentHashMap()
    )

    private val channels = ConcurrentHashMap<String, InternalChannel>()
    private val sessionTokens = ConcurrentHashMap<String, ChannelSession>()
    private val wsPresenceByToken = ConcurrentHashMap<String, Boolean>()
    private val chatHistory = ConcurrentHashMap<String, CopyOnWriteArrayList<ChannelChatMessage>>()
    private val socketListeners = ConcurrentHashMap<String, CopyOnWriteArrayList<ChatSocketListener>>()
    @Volatile private var spaceStore: BitVoidSpaceStore? = null

    private val _channelsFlow = MutableStateFlow<List<BitVoidChannel>>(emptyList())
    val channelsFlow: StateFlow<List<BitVoidChannel>> = _channelsFlow.asStateFlow()

    init {
        ensureDefaultChannel()
    }

    @Synchronized
    fun initializePersistence(context: Context) {
        if (spaceStore != null) return
        val store = BitVoidSpaceStore(context)
        val restored = store.loadSpaces()
        spaceStore = store
        if (restored.isNotEmpty()) {
            channels.clear()
            sessionTokens.clear()
            wsPresenceByToken.clear()
            chatHistory.clear()
            socketListeners.clear()
            restored.forEach { saved ->
                val channel = InternalChannel(
                    channelId = saved.channelId,
                    name = saved.name,
                    isPublic = saved.isPublic,
                    isHidden = saved.isHidden,
                    privateKey = saved.privateKey,
                    mediaIds = ConcurrentHashMap.newKeySet<String>().apply { addAll(saved.mediaIds) },
                    creatorUsername = saved.creatorUsername,
                    createdTimestamp = saved.createdTimestamp,
                    spaceKind = saved.spaceKind,
                    description = saved.description,
                    avatarDataUrl = saved.avatarDataUrl
                )
                saved.members.forEach { member ->
                    channel.members[member.username.lowercase(Locale.US)] = InternalSpaceMember(
                        member.username,
                        member.role,
                        member.joinedTimestampMs
                    )
                }
                saved.invitations.forEach { invitation ->
                    channel.invitations[invitation.codeHash] = InternalInvitation(
                        invitation.codeHash,
                        invitation.expiresAtMs,
                        invitation.maxUses,
                        invitation.uses
                    )
                }
                channels[saved.channelId] = channel
                chatHistory[saved.channelId] = CopyOnWriteArrayList(saved.messages)
                socketListeners[saved.channelId] = CopyOnWriteArrayList()
            }
        }
        ensureDefaultChannel()
        channels.values.forEach(::persistChannel)
        publishChannelsSnapshot()
    }

    fun ensureDefaultChannel() {
        if (channels.containsKey(DEFAULT_PUBLIC_CHANNEL_ID)) return
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
        persistChannel(defaultChannel)
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
        if (channels.size >= MAX_CHANNELS) {
            return Result.failure(IllegalStateException("This gateway has reached its channel limit."))
        }
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
        val createdAt = System.currentTimeMillis()
        val storedChannel = InternalChannel(
            channelId = resolvedId,
            name = cleanName,
            isPublic = effectivePublic,
            isHidden = isHidden,
            privateKey = cleanKey,
            mediaIds = ConcurrentHashMap.newKeySet<String>().apply { addAll(mediaIds) },
            creatorUsername = cleanCreator,
            createdTimestamp = createdAt
        )
        if (cleanCreator.isNotBlank()) {
            storedChannel.members[cleanCreator.lowercase(Locale.US)] = InternalSpaceMember(
                username = cleanCreator,
                role = BitVoidSpaceRole.OWNER,
                joinedTimestampMs = createdAt
            )
        }
        channels[resolvedId] = storedChannel
        chatHistory.putIfAbsent(resolvedId, CopyOnWriteArrayList())
        socketListeners.putIfAbsent(resolvedId, CopyOnWriteArrayList())
        persistChannel(storedChannel)
        publishChannelsSnapshot()
        return Result.success(toPublicChannel(storedChannel))
    }

    fun createGroup(
        rawName: String,
        rawDescription: String,
        ownerUsername: String,
        initialMembers: Collection<String>,
        avatarDataUrl: String? = null
    ): Result<BitVoidChannel> {
        val owner = normalizeRequiredUsername(ownerUsername)
            ?: return Result.failure(IllegalArgumentException("A valid owner username is required."))
        val description = validateDescription(rawDescription)
            ?: return Result.failure(IllegalArgumentException("Group description is too long."))
        val avatar = validateAvatarDataUrl(avatarDataUrl)
            ?: if (avatarDataUrl.isNullOrBlank()) null else {
                return Result.failure(IllegalArgumentException("Profile picture must be a supported image under 135 KB."))
            }
        val requestedMembers = initialMembers.map { it.trim() }.filter { it.isNotBlank() }
        val invalidMember = requestedMembers.firstOrNull { normalizeRequiredUsername(it) == null }
        if (invalidMember != null) {
            return Result.failure(IllegalArgumentException("Invalid member username '$invalidMember'."))
        }
        val normalizedMembers = requestedMembers.mapNotNull(::normalizeRequiredUsername)
            .filterNot { it.equals(owner, ignoreCase = true) }
            .distinctBy { it.lowercase(Locale.US) }
        if (normalizedMembers.size + 1 > MAX_SPACE_MEMBERS) {
            return Result.failure(IllegalArgumentException("Groups support up to $MAX_SPACE_MEMBERS members."))
        }
        val created = createChannel(
            rawName = rawName,
            isPublic = false,
            rawChannelId = generateGroupId(),
            rawPrivateKey = null,
            isHidden = true,
            creatorUsername = owner
        ).getOrElse { return Result.failure(it) }
        val stored = channels[created.channelId]
            ?: return Result.failure(IllegalStateException("Group creation failed."))
        stored.spaceKind = BitVoidSpaceKind.GROUP
        stored.description = description
        stored.avatarDataUrl = avatar
        val joinedAt = System.currentTimeMillis()
        normalizedMembers.forEach { username ->
            stored.members.putIfAbsent(
                username.lowercase(Locale.US),
                InternalSpaceMember(username, BitVoidSpaceRole.MEMBER, joinedAt)
            )
        }
        persistChannel(stored)
        publishChannelsSnapshot()
        return Result.success(toPublicChannel(stored))
    }

    fun createBroadcastChannel(
        rawName: String,
        rawDescription: String,
        ownerUsername: String,
        isPublic: Boolean,
        avatarDataUrl: String? = null
    ): Result<BitVoidChannel> {
        val owner = normalizeRequiredUsername(ownerUsername)
            ?: return Result.failure(IllegalArgumentException("A valid owner username is required."))
        val description = validateDescription(rawDescription)
            ?: return Result.failure(IllegalArgumentException("Channel description is too long."))
        val avatar = validateAvatarDataUrl(avatarDataUrl)
            ?: if (avatarDataUrl.isNullOrBlank()) null else {
                return Result.failure(IllegalArgumentException("Profile picture must be a supported image under 135 KB."))
            }
        val created = createChannel(
            rawName = rawName,
            isPublic = isPublic,
            rawChannelId = if (isPublic) null else generatePrivateChannelId(),
            rawPrivateKey = null,
            isHidden = !isPublic,
            creatorUsername = owner
        ).getOrElse { return Result.failure(it) }
        val stored = channels[created.channelId]
            ?: return Result.failure(IllegalStateException("Channel creation failed."))
        stored.spaceKind = BitVoidSpaceKind.BROADCAST_CHANNEL
        stored.description = description
        stored.avatarDataUrl = avatar
        persistChannel(stored)
        publishChannelsSnapshot()
        return Result.success(toPublicChannel(stored))
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
        persistChannel(storedChannel)
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
        persistChannel(storedChannel)
        publishChannelsSnapshot()
        broadcastSystemNotice(channelId, "Channel settings updated by its creator.")
        return Result.success(toPublicChannel(storedChannel))
    }

    fun canManageChannel(channelId: String, username: String): Boolean {
        val channel = channels[channelId] ?: return false
        if (channelId == DEFAULT_PUBLIC_CHANNEL_ID) return false
        if (channel.spaceKind == BitVoidSpaceKind.LEGACY_CHAT) return isCreator(channel, username)
        return roleFor(channel, username) in setOf(
            BitVoidSpaceRole.OWNER,
            BitVoidSpaceRole.ADMINISTRATOR
        )
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

    fun getSpaceDetails(channelId: String, sessionToken: String): Result<BitVoidChannel> {
        val session = validateToken(sessionToken, channelId)
            ?: return Result.failure(SecurityException("A valid space session is required."))
        val channel = channels[channelId]
            ?: return Result.failure(IllegalArgumentException("Space not found."))
        if (channel.spaceKind != BitVoidSpaceKind.LEGACY_CHAT &&
            roleFor(channel, session.callsign) == null
        ) {
            return Result.failure(SecurityException("You are not a member of this space."))
        }
        return Result.success(toPublicChannel(channel))
    }

    fun addGroupMember(
        channelId: String,
        sessionToken: String,
        rawUsername: String
    ): Result<BitVoidChannel> {
        val (channel, actorRole) = authorizeManagement(channelId, sessionToken)
            .getOrElse { return Result.failure(it) }
        if (channel.spaceKind != BitVoidSpaceKind.GROUP) {
            return Result.failure(IllegalArgumentException("Members can only be added to groups."))
        }
        if (actorRole !in setOf(BitVoidSpaceRole.OWNER, BitVoidSpaceRole.ADMINISTRATOR)) {
            return Result.failure(SecurityException("Only owners and administrators can add members."))
        }
        val username = normalizeRequiredUsername(rawUsername)
            ?: return Result.failure(IllegalArgumentException("Enter a valid username."))
        if (channel.members.size >= MAX_SPACE_MEMBERS) {
            return Result.failure(IllegalStateException("This group has reached its member limit."))
        }
        channel.members.putIfAbsent(
            username.lowercase(Locale.US),
            InternalSpaceMember(username, BitVoidSpaceRole.MEMBER, System.currentTimeMillis())
        )
        persistChannel(channel)
        publishChannelsSnapshot()
        broadcastSystemNotice(channelId, "@$username joined the group.")
        return Result.success(toPublicChannel(channel))
    }

    fun removeSpaceMember(
        channelId: String,
        sessionToken: String,
        rawUsername: String
    ): Result<BitVoidChannel> {
        val (channel, actorRole) = authorizeManagement(channelId, sessionToken)
            .getOrElse { return Result.failure(it) }
        val target = normalizeRequiredUsername(rawUsername)
            ?: return Result.failure(IllegalArgumentException("Enter a valid username."))
        val targetMember = channel.members[target.lowercase(Locale.US)]
            ?: return Result.failure(IllegalArgumentException("That user is not a member."))
        if (targetMember.role == BitVoidSpaceRole.OWNER) {
            return Result.failure(SecurityException("The owner cannot be removed."))
        }
        if (actorRole == BitVoidSpaceRole.ADMINISTRATOR &&
            targetMember.role == BitVoidSpaceRole.ADMINISTRATOR
        ) {
            return Result.failure(SecurityException("Only the owner can remove an administrator."))
        }
        if (actorRole !in setOf(BitVoidSpaceRole.OWNER, BitVoidSpaceRole.ADMINISTRATOR)) {
            return Result.failure(SecurityException("You cannot remove members."))
        }
        channel.members.remove(target.lowercase(Locale.US))
        revokeUserTokens(channelId, target)
        persistChannel(channel)
        publishChannelsSnapshot()
        broadcastSystemNotice(channelId, "@$target was removed.")
        return Result.success(toPublicChannel(channel))
    }

    fun setSpaceMemberRole(
        channelId: String,
        sessionToken: String,
        rawUsername: String,
        role: BitVoidSpaceRole
    ): Result<BitVoidChannel> {
        val (channel, actorRole) = authorizeManagement(channelId, sessionToken)
            .getOrElse { return Result.failure(it) }
        if (actorRole != BitVoidSpaceRole.OWNER) {
            return Result.failure(SecurityException("Only the owner can change administrator permissions."))
        }
        if (role !in setOf(BitVoidSpaceRole.ADMINISTRATOR, BitVoidSpaceRole.MEMBER, BitVoidSpaceRole.SUBSCRIBER)) {
            return Result.failure(IllegalArgumentException("The owner role cannot be reassigned."))
        }
        val target = normalizeRequiredUsername(rawUsername)
            ?: return Result.failure(IllegalArgumentException("Enter a valid username."))
        val member = channel.members[target.lowercase(Locale.US)]
            ?: return Result.failure(IllegalArgumentException("That user is not a member."))
        if (member.role == BitVoidSpaceRole.OWNER) {
            return Result.failure(SecurityException("The owner role cannot be changed."))
        }
        val allowedRole = when (channel.spaceKind) {
            BitVoidSpaceKind.GROUP -> if (role == BitVoidSpaceRole.SUBSCRIBER) BitVoidSpaceRole.MEMBER else role
            BitVoidSpaceKind.BROADCAST_CHANNEL -> if (role == BitVoidSpaceRole.MEMBER) BitVoidSpaceRole.SUBSCRIBER else role
            BitVoidSpaceKind.LEGACY_CHAT -> role
        }
        member.role = allowedRole
        revokeUserTokens(channelId, target)
        persistChannel(channel)
        publishChannelsSnapshot()
        broadcastSystemNotice(channelId, "@$target is now ${allowedRole.name.lowercase().replace('_', ' ')}.")
        return Result.success(toPublicChannel(channel))
    }

    fun updateSpaceSettings(
        channelId: String,
        sessionToken: String,
        rawName: String,
        rawDescription: String,
        avatarDataUrl: String?,
        isPublic: Boolean? = null
    ): Result<BitVoidChannel> {
        val (channel, actorRole) = authorizeManagement(channelId, sessionToken)
            .getOrElse { return Result.failure(it) }
        if (actorRole !in setOf(BitVoidSpaceRole.OWNER, BitVoidSpaceRole.ADMINISTRATOR)) {
            return Result.failure(SecurityException("You cannot edit this space."))
        }
        val cleanName = rawName.trim()
        if (cleanName.isBlank() || cleanName.length > 48) {
            return Result.failure(IllegalArgumentException("Name must be 1–48 characters."))
        }
        val description = validateDescription(rawDescription)
            ?: return Result.failure(IllegalArgumentException("Description is too long."))
        val avatar = validateAvatarDataUrl(avatarDataUrl)
            ?: if (avatarDataUrl.isNullOrBlank()) null else {
                return Result.failure(IllegalArgumentException("Profile picture must be a supported image under 135 KB."))
            }
        if (isPublic != null && channel.spaceKind == BitVoidSpaceKind.BROADCAST_CHANNEL) {
            if (actorRole != BitVoidSpaceRole.OWNER) {
                return Result.failure(SecurityException("Only the owner can change channel visibility."))
            }
            channel.isPublic = isPublic
            channel.isHidden = !isPublic
        }
        channel.name = cleanName
        channel.description = description
        channel.avatarDataUrl = avatar
        persistChannel(channel)
        publishChannelsSnapshot()
        broadcastSystemNotice(channelId, "Space information updated.")
        return Result.success(toPublicChannel(channel))
    }

    fun subscribeToChannel(channelId: String, rawUsername: String): Result<ChannelSession> {
        val channel = channels[channelId]
            ?: return Result.failure(IllegalArgumentException("Channel not found."))
        if (channel.spaceKind != BitVoidSpaceKind.BROADCAST_CHANNEL || !channel.isPublic) {
            return Result.failure(SecurityException("This channel requires a valid invitation."))
        }
        val username = normalizeRequiredUsername(rawUsername)
            ?: return Result.failure(IllegalArgumentException("A valid username is required."))
        channel.members.putIfAbsent(
            username.lowercase(Locale.US),
            InternalSpaceMember(username, BitVoidSpaceRole.SUBSCRIBER, System.currentTimeMillis())
        )
        persistChannel(channel)
        publishChannelsSnapshot()
        return joinChannel(channelId, rawUsername = username)?.let { Result.success(it) }
            ?: Result.failure(SecurityException("Unable to subscribe."))
    }

    fun unsubscribeFromChannel(channelId: String, sessionToken: String): Result<Unit> {
        val session = validateToken(sessionToken, channelId)
            ?: return Result.failure(SecurityException("A valid channel session is required."))
        val channel = channels[channelId]
            ?: return Result.failure(IllegalArgumentException("Channel not found."))
        if (channel.spaceKind != BitVoidSpaceKind.BROADCAST_CHANNEL) {
            return Result.failure(IllegalArgumentException("This is not a broadcast channel."))
        }
        val member = channel.members[session.callsign.lowercase(Locale.US)]
            ?: return Result.failure(SecurityException("You are not subscribed."))
        if (member.role == BitVoidSpaceRole.OWNER) {
            return Result.failure(SecurityException("The owner must delete the channel instead."))
        }
        channel.members.remove(session.callsign.lowercase(Locale.US))
        revokeUserTokens(channelId, session.callsign)
        persistChannel(channel)
        publishChannelsSnapshot()
        return Result.success(Unit)
    }

    fun leaveGroup(channelId: String, sessionToken: String): Result<Unit> {
        val session = validateToken(sessionToken, channelId)
            ?: return Result.failure(SecurityException("A valid group session is required."))
        val channel = channels[channelId]
            ?: return Result.failure(IllegalArgumentException("Group not found."))
        if (channel.spaceKind != BitVoidSpaceKind.GROUP) {
            return Result.failure(IllegalArgumentException("This is not a group."))
        }
        val member = channel.members[session.callsign.lowercase(Locale.US)]
            ?: return Result.failure(SecurityException("You are not a group member."))
        if (member.role == BitVoidSpaceRole.OWNER) {
            return Result.failure(SecurityException("The owner must delete the group instead."))
        }
        channel.members.remove(session.callsign.lowercase(Locale.US))
        revokeUserTokens(channelId, session.callsign)
        persistChannel(channel)
        publishChannelsSnapshot()
        broadcastSystemNotice(channelId, "@${session.callsign} left the group.")
        return Result.success(Unit)
    }

    fun deleteSpace(channelId: String, sessionToken: String): Result<Unit> {
        val session = validateToken(sessionToken, channelId)
            ?: return Result.failure(SecurityException("A valid owner session is required."))
        val channel = channels[channelId]
            ?: return Result.failure(IllegalArgumentException("Space not found."))
        if (roleFor(channel, session.callsign) != BitVoidSpaceRole.OWNER) {
            return Result.failure(SecurityException("Only the owner can delete this space."))
        }
        deleteChannel(channelId)
        return Result.success(Unit)
    }

    fun createInvitation(
        channelId: String,
        sessionToken: String,
        maxUses: Int = 50
    ): Result<BitVoidSpaceInvitation> {
        val (channel, actorRole) = authorizeManagement(channelId, sessionToken)
            .getOrElse { return Result.failure(it) }
        if (channel.spaceKind != BitVoidSpaceKind.BROADCAST_CHANNEL) {
            return Result.failure(IllegalArgumentException("Invitations are only used for broadcast channels."))
        }
        if (actorRole !in setOf(BitVoidSpaceRole.OWNER, BitVoidSpaceRole.ADMINISTRATOR)) {
            return Result.failure(SecurityException("You cannot create invitations."))
        }
        val raw = ByteArray(24).also(secureRandom::nextBytes)
        val code = "bvi_" + Base64.encodeToString(raw, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val hash = invitationHash(code)
        val expiresAt = System.currentTimeMillis() + INVITATION_LIFETIME_MS
        val safeUses = maxUses.coerceIn(1, 500)
        channel.invitations[hash] = InternalInvitation(hash, expiresAt, safeUses, 0)
        persistChannel(channel)
        return Result.success(BitVoidSpaceInvitation(code, channelId, expiresAt, safeUses))
    }

    @Synchronized
    fun joinChannelByInvitation(rawCode: String, rawUsername: String): Result<ChannelSession> {
        val code = rawCode.trim().substringAfterLast('/')
        if (!code.startsWith("bvi_") || code.length !in 24..100) {
            return Result.failure(IllegalArgumentException("Invalid invitation."))
        }
        val hash = invitationHash(code)
        val channel = channels.values.firstOrNull { hash in it.invitations }
            ?: return Result.failure(SecurityException("Invitation is invalid or expired."))
        val invitation = channel.invitations[hash]
            ?: return Result.failure(SecurityException("Invitation is invalid."))
        if (invitation.expiresAtMs < System.currentTimeMillis() || invitation.uses >= invitation.maxUses) {
            channel.invitations.remove(hash)
            persistChannel(channel)
            return Result.failure(SecurityException("Invitation is invalid or expired."))
        }
        val username = normalizeRequiredUsername(rawUsername)
            ?: return Result.failure(IllegalArgumentException("A valid username is required."))
        val usernameKey = username.lowercase(Locale.US)
        if (!channel.members.containsKey(usernameKey)) {
            invitation.uses++
            channel.members[usernameKey] = InternalSpaceMember(
                username,
                BitVoidSpaceRole.SUBSCRIBER,
                System.currentTimeMillis()
            )
        }
        persistChannel(channel)
        publishChannelsSnapshot()
        return joinChannel(channel.channelId, rawUsername = username)?.let { Result.success(it) }
            ?: Result.failure(SecurityException("Unable to join this channel."))
    }

    fun discoverPublicBroadcastChannels(query: String = ""): List<BitVoidChannel> {
        val cleanQuery = query.trim()
        return channels.values
            .filter { it.spaceKind == BitVoidSpaceKind.BROADCAST_CHANNEL && it.isPublic }
            .filter {
                cleanQuery.isBlank() || it.name.contains(cleanQuery, ignoreCase = true) ||
                    it.description.contains(cleanQuery, ignoreCase = true)
            }
            .sortedByDescending { it.createdTimestamp }
            .map(::toPublicChannel)
    }

    fun assignMediaToChannel(channelId: String, mediaIds: Set<String>) {
        val channel = channels[channelId] ?: return
        channel.mediaIds.clear()
        channel.mediaIds.addAll(mediaIds)
        persistChannel(channel)
        publishChannelsSnapshot()
        broadcastSystemNotice(
            channelId,
            "Host updated channel media (${mediaIds.size} item(s))."
        )
    }

    fun addMediaToChannel(channelId: String, mediaId: String) {
        val channel = channels[channelId] ?: return
        channel.mediaIds.add(mediaId)
        persistChannel(channel)
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
        persistChannel(channel)
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
            if (removed) {
                changed = true
                persistChannel(channel)
            }
        }
        if (changed || _channelsFlow.value.isNotEmpty() || channels.isNotEmpty()) {
            publishChannelsSnapshot(availableMedia)
        }
    }

    fun deleteChannel(channelId: String) {
        spaceStore?.deleteSpace(channelId)
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
        var role = cleanUsername?.let { roleFor(channel, it) }
        when (channel.spaceKind) {
            BitVoidSpaceKind.GROUP -> {
                if (cleanUsername == null || role == null) return null
            }
            BitVoidSpaceKind.BROADCAST_CHANNEL -> {
                if (cleanUsername == null) return null
                if (role == null && channel.isPublic) {
                    channel.members[cleanUsername.lowercase(Locale.US)] = InternalSpaceMember(
                        cleanUsername,
                        BitVoidSpaceRole.SUBSCRIBER,
                        System.currentTimeMillis()
                    )
                    persistChannel(channel)
                    role = BitVoidSpaceRole.SUBSCRIBER
                }
                if (role == null) return null
            }
            BitVoidSpaceKind.LEGACY_CHAT -> {
                if (!channel.isPublic && !creatorAccess) {
                    val requiredKey = channel.privateKey
                    if (requiredKey != null && requiredKey != (rawPrivateKey?.trim() ?: "")) {
                        return null
                    }
                }
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
            issuedAt = issuedAt,
            role = role,
            spaceKind = channel.spaceKind
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
        val channel = channels[session.channelId]
        if (channel == null) {
            sessionTokens.remove(trimmed)
            wsPresenceByToken.remove(trimmed)
            return null
        }
        if (channel.spaceKind != BitVoidSpaceKind.LEGACY_CHAT) {
            val currentRole = roleFor(channel, session.callsign)
            if (currentRole == null || currentRole != session.role) {
                sessionTokens.remove(trimmed)
                wsPresenceByToken.remove(trimmed)
                return null
            }
        }
        if (requiredChannelId != null && session.channelId != requiredChannelId) {
            return null
        }
        return session
    }

    fun canPublish(session: ChannelSession): Boolean {
        val channel = channels[session.channelId] ?: return false
        return when (channel.spaceKind) {
            BitVoidSpaceKind.LEGACY_CHAT -> true
            BitVoidSpaceKind.GROUP -> session.role in setOf(
                BitVoidSpaceRole.OWNER,
                BitVoidSpaceRole.ADMINISTRATOR,
                BitVoidSpaceRole.MEMBER
            )
            BitVoidSpaceKind.BROADCAST_CHANNEL -> session.role in setOf(
                BitVoidSpaceRole.OWNER,
                BitVoidSpaceRole.ADMINISTRATOR
            )
        }
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
            .filter { channel ->
                when (channel.spaceKind) {
                    BitVoidSpaceKind.GROUP -> roleFor(channel, username) != null
                    BitVoidSpaceKind.BROADCAST_CHANNEL -> channel.isPublic || roleFor(channel, username) != null
                    BitVoidSpaceKind.LEGACY_CHAT -> !channel.isHidden || isCreator(channel, username)
                }
            }
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

    @Synchronized
    fun appendChatMessage(
        channelId: String,
        callsign: String,
        rawText: String,
        attachment: BitVoidMediaItem? = null,
        stableMessageId: String? = null,
        clientTimestampMs: Long? = null,
        sessionToken: String? = null
    ): ChannelChatMessage? {
        val trimmed = rawText.trim()
        val channel = channels[channelId] ?: return null
        if ((trimmed.isEmpty() && attachment == null)) return null
        if (channel.spaceKind != BitVoidSpaceKind.LEGACY_CHAT) {
            val session = validateToken(sessionToken, channelId) ?: return null
            if (!canPublish(session)) return null
            if (!session.callsign.equals(callsign, ignoreCase = true)) return null
        }
        val safeText = if (trimmed.length > 1000) trimmed.take(1000) else trimmed
        val history = chatHistory.computeIfAbsent(channelId) { CopyOnWriteArrayList() }
        val requestedId = stableMessageId
            ?.trim()
            ?.takeIf { it.length in 8..80 && it.matches(Regex("^[A-Za-z0-9._:-]+$")) }
        if (requestedId != null) {
            history.firstOrNull { it.id == requestedId }?.let { return it }
        }
        val now = clientTimestampMs
            ?.takeIf { it in 1..(System.currentTimeMillis() + 60_000L) }
            ?: System.currentTimeMillis()
        val encodedAttachmentId = attachment?.let {
            URLEncoder.encode(it.id, StandardCharsets.UTF_8.name()).replace("+", "%20")
        }
        val message = ChannelChatMessage(
            id = requestedId ?: "cmsg_${randomHex(8)}",
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
        history.add(message)
        while (history.size > MAX_CHAT_HISTORY_PER_CHANNEL) {
            history.removeAt(0)
        }
        persistChannel(channel)
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

    @Synchronized
    fun clearAllForTests() {
        channels.clear()
        sessionTokens.clear()
        wsPresenceByToken.clear()
        chatHistory.clear()
        socketListeners.clear()
        runCatching { spaceStore?.clearAll() }
        runCatching { spaceStore?.close() }
        spaceStore = null
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

    private fun persistChannel(channel: InternalChannel) {
        val store = spaceStore ?: return
        val snapshot = BitVoidSpaceStore.StoredSpace(
            channelId = channel.channelId,
            name = channel.name,
            isPublic = channel.isPublic,
            isHidden = channel.isHidden,
            privateKey = channel.privateKey,
            creatorUsername = channel.creatorUsername,
            createdTimestamp = channel.createdTimestamp,
            spaceKind = channel.spaceKind,
            description = channel.description,
            avatarDataUrl = channel.avatarDataUrl,
            mediaIds = channel.mediaIds.toSet(),
            members = channel.members.values.map {
                BitVoidSpaceStore.StoredMember(it.username, it.role, it.joinedTimestampMs)
            },
            invitations = channel.invitations.values.map {
                BitVoidSpaceStore.StoredInvitation(it.codeHash, it.expiresAtMs, it.maxUses, it.uses)
            },
            messages = chatHistory[channel.channelId]?.toList().orEmpty()
        )
        store.saveSpace(snapshot)
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
        val lastMessage = chatHistory[storedChannel.channelId]?.lastOrNull()
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
            createdTimestamp = storedChannel.createdTimestamp,
            lastMessagePreview = lastMessage?.let { message ->
                if (message.text.isNotBlank()) message.text else message.attachmentName
            },
            lastTimestampMs = lastMessage?.timestampMs ?: storedChannel.createdTimestamp,
            spaceKind = storedChannel.spaceKind,
            description = storedChannel.description,
            avatarDataUrl = storedChannel.avatarDataUrl,
            members = storedChannel.members.values
                .sortedWith(
                    compareBy<InternalSpaceMember> { roleSortOrder(it.role) }
                        .thenBy { it.username.lowercase(Locale.US) }
                )
                .map { BitVoidSpaceMember(it.username, it.role, it.joinedTimestampMs) },
            subscriberCount = if (storedChannel.spaceKind == BitVoidSpaceKind.BROADCAST_CHANNEL) {
                storedChannel.members.size
            } else {
                storedChannel.members.values.count { it.role == BitVoidSpaceRole.SUBSCRIBER }
            }
        )
    }

    fun messageToJson(msg: ChannelChatMessage): String {
        val attachmentJson = if (msg.attachmentId != null && msg.attachmentName != null) {
            ""","attachment":{"id":"${jsonEscape(msg.attachmentId)}","name":"${jsonEscape(msg.attachmentName)}","size":${msg.attachmentSize},"mimeType":"${jsonEscape(msg.attachmentMime ?: "application/octet-stream")}","streamUrl":"${jsonEscape(msg.attachmentUrl ?: "")}","downloadUrl":"${jsonEscape(msg.attachmentDownloadUrl ?: "")}"}"""
        } else {
            ""
        }
        return """{"id":"${jsonEscape(msg.id)}","channelId":"${jsonEscape(msg.channelId)}","roomId":"${jsonEscape(msg.channelId)}","sender":"${jsonEscape(msg.senderCallsign)}","text":"${jsonEscape(msg.text)}","time":"${jsonEscape(msg.timestampLabel)}","timestampMs":${msg.timestampMs},"deliveryState":"DELIVERED"$attachmentJson}"""
    }

    private fun isCreator(channel: InternalChannel, rawUsername: String): Boolean {
        val username = rawUsername.trim().removePrefix("@")
        return username.isNotEmpty() &&
            channel.creatorUsername.equals(username, ignoreCase = true)
    }

    private fun roleFor(channel: InternalChannel, rawUsername: String): BitVoidSpaceRole? {
        val username = rawUsername.trim().removePrefix("@")
        if (username.isBlank()) return null
        return channel.members[username.lowercase(Locale.US)]?.role
    }

    private fun authorizeManagement(
        channelId: String,
        sessionToken: String
    ): Result<Pair<InternalChannel, BitVoidSpaceRole>> {
        val session = validateToken(sessionToken, channelId)
            ?: return Result.failure(SecurityException("A valid space session is required."))
        val channel = channels[channelId]
            ?: return Result.failure(IllegalArgumentException("Space not found."))
        val role = roleFor(channel, session.callsign)
            ?: return Result.failure(SecurityException("You are not a member of this space."))
        return Result.success(channel to role)
    }

    private fun revokeUserTokens(channelId: String, rawUsername: String) {
        val username = rawUsername.trim().removePrefix("@")
        sessionTokens.entries.removeIf { (token, session) ->
            val remove = session.channelId == channelId &&
                session.callsign.equals(username, ignoreCase = true)
            if (remove) wsPresenceByToken.remove(token)
            remove
        }
    }

    private fun normalizeRequiredUsername(raw: String): String? =
        com.bitvoid.network.direct.BitVoidUserPrefs.normalizeUsernameOrNull(raw)

    private fun validateDescription(raw: String): String? =
        raw.trim().takeIf { it.length <= MAX_DESCRIPTION_LENGTH }

    private fun validateAvatarDataUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (value.length > MAX_AVATAR_DATA_URL_LENGTH) return null
        val prefix = value.substringBefore(',')
        if (!prefix.startsWith("data:image/", ignoreCase = true) ||
            !prefix.endsWith(";base64", ignoreCase = true)
        ) return null
        val mime = prefix.removePrefix("data:").substringBefore(';').lowercase(Locale.US)
        if (mime !in setOf("image/jpeg", "image/png", "image/webp")) return null
        val payload = value.substringAfter(',', "")
        if (payload.isBlank()) return null
        return runCatching {
            Base64.decode(payload, Base64.DEFAULT)
            value
        }.getOrNull()
    }

    private fun invitationHash(code: String): String = MessageDigest.getInstance("SHA-256")
        .digest(code.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun generateGroupId(): String {
        var candidate: String
        do {
            candidate = "GRP-${randomHex(10).uppercase(Locale.US)}"
        } while (channels.containsKey(candidate))
        return candidate
    }

    private fun roleSortOrder(role: BitVoidSpaceRole): Int = when (role) {
        BitVoidSpaceRole.OWNER -> 0
        BitVoidSpaceRole.ADMINISTRATOR -> 1
        BitVoidSpaceRole.MEMBER -> 2
        BitVoidSpaceRole.SUBSCRIBER -> 3
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
