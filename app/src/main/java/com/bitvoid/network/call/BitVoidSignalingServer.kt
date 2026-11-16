package com.bitvoid.network.call

import com.bitvoid.network.channel.BitVoidChannelManager
import com.bitvoid.network.direct.BitVoidDirectChatManager
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class SignalingSessionMode(val label: String) {
    // Protocol name retained for compatibility with already-installed nodes; calls are audio-only.
    VOICE_VIDEO_CALL("Voice Call"),
    LIVE_CLASSROOM("Live Class & Presentation")
}

enum class ParticipantRole(val label: String) {
    HOST_TEACHER("HOST"),
    SPEAKER("SPEAKER"),
    STUDENT_VIEWER("")
}

enum class DirectCallPhase {
    IDLE,
    RINGING,
    ACCEPTED,
    DECLINED,
    CANCELED,
    ENDED,
    MISSED
}

data class DirectCallInvitation(
    val dmId: String,
    val callerUsername: String,
    val calleeUsername: String,
    val withVideo: Boolean,
    val phase: DirectCallPhase,
    val createdAtMs: Long,
    val updatedAtMs: Long
)

data class SignalingPeer(
    val peerId: String,
    val displayName: String,
    val role: ParticipantRole,
    val roomId: String,
    val isMuted: Boolean = false,
    val isVideoOn: Boolean = false,
    val isScreenSharing: Boolean = false,
    val joinedAtMs: Long = System.currentTimeMillis()
)

data class LiveClassMaterial(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val uploadedBy: String,
    val downloadUrl: String,
    val streamUrl: String,
    val uploadedAtMs: Long = System.currentTimeMillis()
)

data class LiveClassInvite(
    val classCode: String,
    val roomId: String,
    val channelId: String,
    val className: String = channelId,
    val creatorUsername: String = "",
    val isHidden: Boolean = false
)

data class SignalingRoomState(
    val roomId: String,
    val channelId: String,
    val mode: SignalingSessionMode,
    val isActive: Boolean,
    val hostPeerId: String?,
    val classCode: String? = null,
    val className: String = "",
    val creatorUsername: String = "",
    val isHidden: Boolean = false,
    val activeMaterialId: String? = null,
    val activePageIndex: Int = 0,
    val materials: List<LiveClassMaterial> = emptyList(),
    val peers: List<SignalingPeer> = emptyList()
)

/**
 * Thread-safe local WebRTC signaling registry and message router for `/ws/signaling`.
 * Coordinates peer handshakes (SDP offer, answer, ICE candidates), direct 1-on-1 Voice Calls,
 * Live Classrooms with generated shareable Class IDs (`CLS-XXXX-XX`), and synchronized
 * live presentation materials (PDF, PPT/PPTX, DOC/DOCX, Images, Text) over local Wi-Fi.
 */
object BitVoidSignalingServer {

    interface SignalingSocketListener {
        fun onSendJson(jsonPayload: String)
        fun onForceClose(reason: String)
    }

    private data class PeerEntry(
        var peer: SignalingPeer,
        val listener: SignalingSocketListener
    )

    private data class InternalRoom(
        val roomId: String,
        val channelId: String,
        var mode: SignalingSessionMode,
        var hostPeerId: String? = null,
        var classCode: String? = null,
        var className: String = channelId,
        var creatorUsername: String = "",
        var isHidden: Boolean = false,
        var keepAliveUntilEnded: Boolean = false,
        var activeMaterialId: String? = null,
        var activePageIndex: Int = 0,
        val materials: CopyOnWriteArrayList<LiveClassMaterial> = CopyOnWriteArrayList(),
        val peers: ConcurrentHashMap<String, PeerEntry> = ConcurrentHashMap()
    )

    private val secureRandom = SecureRandom()
    private val HEX = "0123456789abcdef".toCharArray()
    private val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray()
    private val rooms = ConcurrentHashMap<String, InternalRoom>()
    private val classCodeToRoomId = ConcurrentHashMap<String, String>()
    private val directCallInvitations = ConcurrentHashMap<String, DirectCallInvitation>()
    private const val DIRECT_CALL_RING_TIMEOUT_MS = 180_000L

    private val _roomsFlow = MutableStateFlow<Map<String, SignalingRoomState>>(emptyMap())
    val roomsFlow: StateFlow<Map<String, SignalingRoomState>> = _roomsFlow.asStateFlow()

    /**
     * Authorizes access to a signaling room (channel room, Live Class room, or 1-on-1 DM room).
     * - Direct calls (`dm__*`) require a valid DM session token from [BitVoidDirectChatManager].
     * - Standalone Live Classes (`LC-*`) require a valid channel token from `/api/live-class/start` or `/api/live-class/join`.
     * - Private channels require a valid channel token from [BitVoidChannelManager].
     * - Public channels allow direct local Wi-Fi access or a valid channel token.
     */
    fun authorizeSignalingJoin(roomId: String, channelId: String, token: String?): Boolean {
        val cleanRoom = roomId.trim()
        val cleanChannel = channelId.trim().ifEmpty { cleanRoom }
        if (cleanRoom.isEmpty()) return false

        if (cleanRoom.startsWith("dm__", ignoreCase = false)) {
            val directSession = BitVoidDirectChatManager.validateToken(token, requiredDmId = cleanRoom)
                ?: return false
            return canJoinDirectCallSignaling(directSession)
        }

        if (cleanRoom.startsWith("LC-", ignoreCase = false)) {
            val backingChannel = BitVoidChannelManager.getChannel(cleanRoom)
                ?: BitVoidChannelManager.getChannel(cleanChannel)
                ?: return false
            return BitVoidChannelManager.validateToken(token, requiredChannelId = backingChannel.channelId) != null
        }

        val channel = BitVoidChannelManager.getChannel(cleanChannel)
            ?: BitVoidChannelManager.getChannel(cleanRoom)
        if (channel != null && !channel.isPublic) {
            return BitVoidChannelManager.validateToken(token, requiredChannelId = channel.channelId) != null
        }
        if (channel != null && channel.isPublic) {
            return true
        }
        return false
    }

    /** Backwards-compatible overload delegating to [authorizeSignalingJoin]. */
    fun authorizeSignalingJoin(channelId: String, token: String?): Boolean {
        return authorizeSignalingJoin(roomId = channelId, channelId = channelId, token = token)
    }

    fun generatePeerId(prefix: String = "peer"): String {
        val bytes = ByteArray(4)
        secureRandom.nextBytes(bytes)
        val hex = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hex[i * 2] = HEX[v ushr 4]
            hex[i * 2 + 1] = HEX[v and 0x0F]
        }
        return "${prefix}_${String(hex)}"
    }

    fun generateClassCode(): String {
        fun chunk(len: Int): String {
            return buildString(len) {
                repeat(len) {
                    append(CODE_ALPHABET[secureRandom.nextInt(CODE_ALPHABET.size)])
                }
            }
        }
        var candidate: String
        do {
            candidate = "CLS-${chunk(4)}-${chunk(2)}"
        } while (classCodeToRoomId.containsKey(candidate))
        return candidate
    }

    fun registerLiveClass(
        rawName: String,
        rawCreatorUsername: String,
        isHidden: Boolean
    ): LiveClassInvite {
        val cleanName = rawName.trim().ifEmpty { "Live Class" }
        val cleanCreator = rawCreatorUsername.trim().removePrefix("@").ifEmpty { "Host" }
        val bytes = ByteArray(4)
        secureRandom.nextBytes(bytes)
        val suffix = bytes.joinToString("") { "%02X".format(it) }
        val roomId = "LC-$suffix"
        val classCode = generateClassCode()

        val room = InternalRoom(
            roomId = roomId,
            channelId = roomId,
            mode = SignalingSessionMode.LIVE_CLASSROOM,
            classCode = classCode,
            className = cleanName,
            creatorUsername = cleanCreator,
            isHidden = isHidden,
            keepAliveUntilEnded = true
        )
        rooms[roomId] = room
        classCodeToRoomId[classCode] = roomId
        publishRoomsSnapshot()

        return LiveClassInvite(
            classCode = classCode,
            roomId = roomId,
            channelId = roomId,
            className = cleanName,
            creatorUsername = cleanCreator,
            isHidden = isHidden
        )
    }

    fun listLiveClassesForUser(rawUsername: String?): List<SignalingRoomState> {
        val cleanUser = rawUsername?.trim()?.removePrefix("@").orEmpty()
        return rooms.values
            .asSequence()
            .filter { it.mode == SignalingSessionMode.LIVE_CLASSROOM }
            .filter { room ->
                !room.isHidden ||
                    (cleanUser.isNotEmpty() && room.creatorUsername.equals(cleanUser, ignoreCase = true))
            }
            .map { room ->
                val snapshotPeers = room.peers.values.map { it.peer }.sortedBy { it.joinedAtMs }
                SignalingRoomState(
                    roomId = room.roomId,
                    channelId = room.channelId,
                    mode = room.mode,
                    isActive = room.keepAliveUntilEnded || snapshotPeers.isNotEmpty(),
                    hostPeerId = room.hostPeerId,
                    classCode = room.classCode,
                    className = room.className.ifBlank { room.roomId },
                    creatorUsername = room.creatorUsername,
                    isHidden = room.isHidden,
                    activeMaterialId = room.activeMaterialId,
                    activePageIndex = room.activePageIndex,
                    materials = room.materials.toList(),
                    peers = snapshotPeers
                )
            }
            .sortedBy { it.className.lowercase(Locale.US) }
            .toList()
    }

    fun resolveLiveClassByRoomIdForUser(rawRoomId: String?, rawUsername: String?): LiveClassInvite? {
        val cleanRoomId = rawRoomId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cleanUser = rawUsername?.trim()?.removePrefix("@").orEmpty()
        val room = rooms[cleanRoomId] ?: return null
        synchronized(room) {
            if (room.mode != SignalingSessionMode.LIVE_CLASSROOM) return null
            if (room.peers.isEmpty() && !room.keepAliveUntilEnded) return null
            val isCreator = cleanUser.isNotEmpty() && room.creatorUsername.equals(cleanUser, ignoreCase = true)
            if (room.isHidden && !isCreator) {
                return null
            }
            val code = room.classCode ?: return null
            return LiveClassInvite(
                classCode = code,
                roomId = room.roomId,
                channelId = room.channelId,
                className = room.className.ifBlank { room.roomId },
                creatorUsername = room.creatorUsername,
                isHidden = room.isHidden
            )
        }
    }

    fun resolveLiveClassByCode(rawCode: String?): LiveClassInvite? {
        val normalized = normalizeClassCode(rawCode) ?: return null
        val roomId = classCodeToRoomId[normalized] ?: return null
        val room = rooms[roomId] ?: run {
            classCodeToRoomId.remove(normalized)
            return null
        }
        synchronized(room) {
            if ((room.peers.isEmpty() && !room.keepAliveUntilEnded) ||
                room.mode != SignalingSessionMode.LIVE_CLASSROOM
            ) {
                return null
            }
            val code = room.classCode ?: return null
            return LiveClassInvite(
                classCode = code,
                roomId = room.roomId,
                channelId = room.channelId,
                className = room.className.ifBlank { room.roomId },
                creatorUsername = room.creatorUsername,
                isHidden = room.isHidden
            )
        }
    }

    @Synchronized
    fun requestDirectCall(session: com.bitvoid.network.direct.DirectSession): DirectCallInvitation {
        val now = System.currentTimeMillis()
        val current = currentDirectCallInvitation(session.dmId)
        if (current != null && current.phase in setOf(DirectCallPhase.RINGING, DirectCallPhase.ACCEPTED)) {
            if (!current.callerUsername.equals(session.myUsername, ignoreCase = true)) {
                throw IllegalStateException("@${current.callerUsername} is already calling you.")
            }
            return current
        }
        val invite = DirectCallInvitation(
            dmId = session.dmId,
            callerUsername = session.myUsername,
            calleeUsername = session.peerUsername,
            withVideo = false,
            phase = DirectCallPhase.RINGING,
            createdAtMs = now,
            updatedAtMs = now
        )
        directCallInvitations[session.dmId] = invite
        return invite
    }

    @Synchronized
    fun respondToDirectCall(
        session: com.bitvoid.network.direct.DirectSession,
        accept: Boolean
    ): DirectCallInvitation? {
        val current = currentDirectCallInvitation(session.dmId) ?: return null
        if (!current.calleeUsername.equals(session.myUsername, ignoreCase = true) ||
            current.phase != DirectCallPhase.RINGING
        ) {
            return null
        }
        val updated = current.copy(
            phase = if (accept) DirectCallPhase.ACCEPTED else DirectCallPhase.DECLINED,
            updatedAtMs = System.currentTimeMillis()
        )
        directCallInvitations[session.dmId] = updated
        return updated
    }

    @Synchronized
    fun cancelOrEndDirectCall(session: com.bitvoid.network.direct.DirectSession): DirectCallInvitation? {
        val current = currentDirectCallInvitation(session.dmId) ?: return null
        val isParticipant = current.callerUsername.equals(session.myUsername, ignoreCase = true) ||
            current.calleeUsername.equals(session.myUsername, ignoreCase = true)
        if (!isParticipant) return null
        val updated = current.copy(
            phase = if (current.phase == DirectCallPhase.RINGING) {
                DirectCallPhase.CANCELED
            } else {
                DirectCallPhase.ENDED
            },
            updatedAtMs = System.currentTimeMillis()
        )
        directCallInvitations[session.dmId] = updated
        if (current.phase == DirectCallPhase.ACCEPTED) {
            endSession(session.dmId)
        }
        return updated
    }

    @Synchronized
    fun currentDirectCallInvitation(dmId: String): DirectCallInvitation? {
        val current = directCallInvitations[dmId] ?: return null
        if (current.phase == DirectCallPhase.RINGING &&
            System.currentTimeMillis() - current.createdAtMs > DIRECT_CALL_RING_TIMEOUT_MS
        ) {
            val missed = current.copy(
                phase = DirectCallPhase.MISSED,
                updatedAtMs = System.currentTimeMillis()
            )
            directCallInvitations[dmId] = missed
            return missed
        }
        return current
    }

    fun isDirectCallAccepted(dmId: String): Boolean {
        return currentDirectCallInvitation(dmId)?.phase == DirectCallPhase.ACCEPTED
    }

    /**
     * The person placing a call may enter signaling while it is ringing so their selected
     * camera/microphone opens directly in the meeting stage. The recipient remains blocked
     * until explicitly accepting, so no incoming media is exposed before consent.
     */
    fun canJoinDirectCallSignaling(session: com.bitvoid.network.direct.DirectSession): Boolean {
        val invitation = currentDirectCallInvitation(session.dmId) ?: return false
        return invitation.phase == DirectCallPhase.ACCEPTED ||
            (invitation.phase == DirectCallPhase.RINGING &&
                invitation.callerUsername.equals(session.myUsername, ignoreCase = true))
    }

    fun incomingDirectCallFor(rawUsername: String?): DirectCallInvitation? {
        val username = rawUsername?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() } ?: return null
        return directCallInvitations.values
            .mapNotNull { currentDirectCallInvitation(it.dmId) }
            .filter { it.phase == DirectCallPhase.RINGING }
            .filter { it.calleeUsername.equals(username, ignoreCase = true) }
            .minByOrNull { it.createdAtMs }
    }

    fun directCallStatusToJson(dmId: String, session: com.bitvoid.network.direct.DirectSession): String {
        val roomState = getRoomState(dmId)
        val invite = currentDirectCallInvitation(dmId)
        return JSONObject().apply {
            put("roomId", dmId)
            put("isActive", roomState?.isActive == true && invite?.phase == DirectCallPhase.ACCEPTED)
            put("mode", SignalingSessionMode.VOICE_VIDEO_CALL.name)
            put("hostPeerId", roomState?.hostPeerId.orEmpty())
            put("participantCount", roomState?.peers?.size ?: 0)
            put("peers", peersToJsonArray(roomState?.peers.orEmpty()))
            put("callPhase", invite?.phase?.name ?: DirectCallPhase.IDLE.name)
            put("callerUsername", invite?.callerUsername.orEmpty())
            put("calleeUsername", invite?.calleeUsername.orEmpty())
            put("withVideo", invite?.withVideo == true)
            put("isIncoming", invite?.let {
                it.phase == DirectCallPhase.RINGING &&
                    it.calleeUsername.equals(session.myUsername, ignoreCase = true)
            } == true)
            put("isOutgoing", invite?.let {
                it.phase == DirectCallPhase.RINGING &&
                    it.callerUsername.equals(session.myUsername, ignoreCase = true)
            } == true)
            put("createdAtMs", invite?.createdAtMs ?: 0L)
            put("updatedAtMs", invite?.updatedAtMs ?: 0L)
        }.toString()
    }

    fun joinRoom(
        rawRoomId: String,
        rawChannelId: String?,
        rawPeerId: String?,
        rawDisplayName: String?,
        mode: SignalingSessionMode,
        role: ParticipantRole,
        isMuted: Boolean,
        isVideoOn: Boolean,
        isScreenSharing: Boolean,
        listener: SignalingSocketListener
    ): SignalingPeer {
        val roomId = rawRoomId.trim()
        val channelId = rawChannelId?.trim()?.takeIf { it.isNotEmpty() } ?: roomId
        val peerId = rawPeerId?.trim()?.takeIf { it.isNotEmpty() } ?: generatePeerId("bv")
        val displayName = rawDisplayName?.trim()?.takeIf { it.isNotEmpty() } ?: peerId
        val effectiveMode = if (roomId.startsWith("dm__", ignoreCase = false)) {
            SignalingSessionMode.VOICE_VIDEO_CALL
        } else {
            mode
        }

        val room = rooms.computeIfAbsent(roomId) {
            InternalRoom(
                roomId = roomId,
                channelId = channelId,
                mode = effectiveMode,
                hostPeerId = if (role == ParticipantRole.HOST_TEACHER) peerId else null,
                className = BitVoidChannelManager.getChannel(channelId)?.name ?: channelId,
                keepAliveUntilEnded = roomId.startsWith("LC-", ignoreCase = false)
            )
        }

        synchronized(room) {
            // A room's purpose is fixed when it is created. In particular, a later peer cannot
            // promote an active voice-call room to Live Class and thereby re-enable video.
            if (room.mode == SignalingSessionMode.LIVE_CLASSROOM && room.classCode == null) {
                val code = generateClassCode()
                room.classCode = code
                classCodeToRoomId[code] = room.roomId
            }
            if (role == ParticipantRole.HOST_TEACHER || room.hostPeerId == null) {
                room.hostPeerId = peerId
            }

            room.peers.remove(peerId)?.let { old ->
                if (old.listener !== listener) {
                    try {
                        old.listener.onForceClose("Superseded by newer connection")
                    } catch (_: Exception) {
                    }
                }
            }

            val allowsVideo = room.mode == SignalingSessionMode.LIVE_CLASSROOM
            val peer = SignalingPeer(
                peerId = peerId,
                displayName = displayName,
                role = role,
                roomId = roomId,
                isMuted = isMuted,
                isVideoOn = allowsVideo && isVideoOn,
                isScreenSharing = allowsVideo && isScreenSharing,
                joinedAtMs = System.currentTimeMillis()
            )
            room.peers[peerId] = PeerEntry(peer = peer, listener = listener)

            val snapshotPeers = room.peers.values
                .map { it.peer }
                .sortedBy { it.joinedAtMs }

            val welcomePayload = JSONObject().apply {
                put("type", "welcome")
                put("roomId", room.roomId)
                put("channelId", room.channelId)
                put("peerId", peer.peerId)
                put("mode", room.mode.name)
                put("hostPeerId", room.hostPeerId.orEmpty())
                put("classCode", room.classCode.orEmpty())
                put("className", room.className)
                put("creatorUsername", room.creatorUsername)
                put("isHidden", room.isHidden)
                put("activeMaterialId", room.activeMaterialId.orEmpty())
                put("activePageIndex", room.activePageIndex)
                put("materials", materialsToJsonArray(room.materials))
                put("peers", peersToJsonArray(snapshotPeers))
            }.toString()
            listener.onSendJson(welcomePayload)

            val joinedBroadcast = JSONObject().apply {
                put("type", "peer_joined")
                put("roomId", room.roomId)
                put("mode", room.mode.name)
                put("hostPeerId", room.hostPeerId.orEmpty())
                put("classCode", room.classCode.orEmpty())
                put("peer", peerToJson(peer))
                put("peers", peersToJsonArray(snapshotPeers))
            }.toString()

            room.peers.forEach { (otherId, entry) ->
                if (otherId != peerId) {
                    safeSend(entry.listener, joinedBroadcast)
                }
            }
            publishRoomsSnapshot()
            return peer
        }
    }

    fun leaveRoom(roomId: String, peerId: String) {
        val room = rooms[roomId] ?: return
        synchronized(room) {
            val removed = room.peers.remove(peerId) ?: return
            if (room.hostPeerId == peerId) {
                room.hostPeerId = room.peers.values
                    .firstOrNull { it.peer.role == ParticipantRole.HOST_TEACHER }?.peer?.peerId
                    ?: room.peers.keys.firstOrNull()
            }
            if (room.peers.isEmpty() && !room.keepAliveUntilEnded) {
                room.classCode?.let { classCodeToRoomId.remove(it) }
                rooms.remove(roomId)
            } else {
                val remainingPeers = room.peers.values.map { it.peer }.sortedBy { it.joinedAtMs }
                val leftBroadcast = JSONObject().apply {
                    put("type", "peer_left")
                    put("roomId", room.roomId)
                    put("peerId", removed.peer.peerId)
                    put("hostPeerId", room.hostPeerId.orEmpty())
                    put("classCode", room.classCode.orEmpty())
                    put("peers", peersToJsonArray(remainingPeers))
                }.toString()
                room.peers.values.forEach { entry ->
                    safeSend(entry.listener, leftBroadcast)
                }
            }
            publishRoomsSnapshot()
        }
    }

    fun updatePeerMediaState(
        roomId: String,
        peerId: String,
        isMuted: Boolean? = null,
        isVideoOn: Boolean? = null,
        isScreenSharing: Boolean? = null,
        role: ParticipantRole? = null
    ) {
        val room = rooms[roomId] ?: return
        synchronized(room) {
            val entry = room.peers[peerId] ?: return
            val allowsVideo = room.mode == SignalingSessionMode.LIVE_CLASSROOM
            val updated = entry.peer.copy(
                isMuted = isMuted ?: entry.peer.isMuted,
                isVideoOn = allowsVideo && (isVideoOn ?: entry.peer.isVideoOn),
                isScreenSharing = allowsVideo && (isScreenSharing ?: entry.peer.isScreenSharing),
                role = role ?: entry.peer.role
            )
            entry.peer = updated
            if (updated.role == ParticipantRole.HOST_TEACHER) {
                room.hostPeerId = peerId
            }
            val snapshotPeers = room.peers.values.map { it.peer }.sortedBy { it.joinedAtMs }
            val payload = JSONObject().apply {
                put("type", "peers_update")
                put("roomId", room.roomId)
                put("mode", room.mode.name)
                put("hostPeerId", room.hostPeerId.orEmpty())
                put("classCode", room.classCode.orEmpty())
                put("peer", peerToJson(updated))
                put("peers", peersToJsonArray(snapshotPeers))
            }.toString()
            room.peers.values.forEach { target ->
                safeSend(target.listener, payload)
            }
            publishRoomsSnapshot()
        }
    }

    /**
     * Adds a shared document/presentation (PDF, PPT/PPTX, DOC/DOCX, image, text, etc.)
     * to an active Live Class and notifies all connected participants in real time.
     */
    fun addMaterialToRoom(
        roomId: String,
        material: LiveClassMaterial,
        setAsActive: Boolean = true
    ): LiveClassMaterial {
        val room = rooms[roomId]
            ?: throw IllegalStateException("Live Class is not active")
        if (room.mode != SignalingSessionMode.LIVE_CLASSROOM) {
            throw IllegalStateException("Document presentation is available only in Live Class")
        }
        synchronized(room) {
            room.materials.removeAll { it.id == material.id }
            room.materials.add(material)
            if (setAsActive || room.activeMaterialId == null) {
                room.activeMaterialId = material.id
                room.activePageIndex = 0
            }
            val broadcast = JSONObject().apply {
                put("type", "material_shared")
                put("roomId", room.roomId)
                put("material", materialToJson(material))
                put("activeMaterialId", room.activeMaterialId.orEmpty())
                put("activePageIndex", room.activePageIndex)
                put("materials", materialsToJsonArray(room.materials))
            }.toString()
            room.peers.values.forEach { target ->
                safeSend(target.listener, broadcast)
            }
            publishRoomsSnapshot()
            return material
        }
    }

    fun updateActiveMaterialState(
        roomId: String,
        materialId: String?,
        pageIndex: Int
    ) {
        val room = rooms[roomId] ?: return
        if (room.mode != SignalingSessionMode.LIVE_CLASSROOM) return
        synchronized(room) {
            if (!materialId.isNullOrBlank()) {
                room.activeMaterialId = materialId
            }
            room.activePageIndex = pageIndex.coerceAtLeast(0)
            val broadcast = JSONObject().apply {
                put("type", "material_state")
                put("roomId", room.roomId)
                put("activeMaterialId", room.activeMaterialId.orEmpty())
                put("activePageIndex", room.activePageIndex)
                put("materials", materialsToJsonArray(room.materials))
            }.toString()
            room.peers.values.forEach { target ->
                safeSend(target.listener, broadcast)
            }
            publishRoomsSnapshot()
        }
    }

    /**
     * Routes SDP Offer, SDP Answer, ICE Candidate, Media State, and Live Slide Sync
     * messages within [roomId].
     */
    fun handleSignalingEnvelope(roomId: String, fromPeerId: String, rawJson: String) {
        val obj = try {
            JSONObject(rawJson)
        } catch (_: Exception) {
            return
        }
        val type = obj.optString("type").trim()
        when (type) {
            "offer", "answer", "ice", "ice_candidate" -> {
                val room = rooms[roomId] ?: return
                val targetId = obj.optString("targetPeerId").trim()
                val normalizedType = if (type == "ice_candidate") "ice" else type
                val relayed = JSONObject(obj.toString()).apply {
                    put("type", normalizedType)
                    put("roomId", roomId)
                    put("fromPeerId", fromPeerId)
                }.toString()

                if (targetId.isNotEmpty()) {
                    room.peers[targetId]?.let { safeSend(it.listener, relayed) }
                } else {
                    room.peers.forEach { (id, entry) ->
                        if (id != fromPeerId) {
                            safeSend(entry.listener, relayed)
                        }
                    }
                }
            }

            "media_state" -> {
                val roleStr = obj.optString("role").takeIf { it.isNotBlank() }
                val role = roleStr?.let { runCatching { ParticipantRole.valueOf(it) }.getOrNull() }
                updatePeerMediaState(
                    roomId = roomId,
                    peerId = fromPeerId,
                    isMuted = if (obj.has("isMuted")) obj.optBoolean("isMuted") else null,
                    isVideoOn = if (obj.has("isVideoOn")) obj.optBoolean("isVideoOn") else null,
                    isScreenSharing = if (obj.has("isScreenSharing")) obj.optBoolean("isScreenSharing") else null,
                    role = role
                )
            }

            "active_material", "slide_nav" -> {
                val matId = obj.optString("materialId").takeIf { it.isNotBlank() }
                val page = obj.optInt("pageIndex", 0)
                updateActiveMaterialState(
                    roomId = roomId,
                    materialId = matId,
                    pageIndex = page
                )
            }

            "end_session" -> {
                endSession(roomId, fromPeerId)
            }

            "leave" -> {
                leaveRoom(roomId, fromPeerId)
            }

            "ping" -> {
                val room = rooms[roomId] ?: return
                room.peers[fromPeerId]?.let {
                    safeSend(it.listener, """{"type":"pong","roomId":"${jsonEscape(roomId)}"}""")
                }
            }
        }
    }

    fun endSession(roomId: String, requestedByPeerId: String? = null) {
        if (roomId.startsWith("dm__", ignoreCase = false)) {
            directCallInvitations.computeIfPresent(roomId) { _, invite ->
                invite.copy(phase = DirectCallPhase.ENDED, updatedAtMs = System.currentTimeMillis())
            }
        }
        val room = rooms.remove(roomId) ?: return
        synchronized(room) {
            room.classCode?.let { classCodeToRoomId.remove(it) }
            val payload = JSONObject().apply {
                put("type", "session_ended")
                put("roomId", roomId)
                put("endedBy", requestedByPeerId.orEmpty())
            }.toString()
            room.peers.values.forEach { entry ->
                safeSend(entry.listener, payload)
                try {
                    entry.listener.onForceClose("Session ended")
                } catch (_: Exception) {
                }
            }
            room.peers.clear()
        }
        if (roomId.startsWith("LC-", ignoreCase = false)) {
            BitVoidChannelManager.deleteChannel(roomId)
        }
        publishRoomsSnapshot()
    }

    fun getRoomState(roomId: String): SignalingRoomState? {
        val room = rooms[roomId] ?: return null
        val snapshotPeers = room.peers.values.map { it.peer }.sortedBy { it.joinedAtMs }
        return SignalingRoomState(
            roomId = room.roomId,
            channelId = room.channelId,
            mode = room.mode,
            isActive = snapshotPeers.isNotEmpty() || room.keepAliveUntilEnded,
            hostPeerId = room.hostPeerId,
            classCode = room.classCode,
            className = room.className.ifBlank { room.roomId },
            creatorUsername = room.creatorUsername,
            isHidden = room.isHidden,
            activeMaterialId = room.activeMaterialId,
            activePageIndex = room.activePageIndex,
            materials = room.materials.toList(),
            peers = snapshotPeers
        )
    }

    fun roomStatusToJson(roomId: String): String {
        val state = getRoomState(roomId)
        return JSONObject().apply {
            put("roomId", roomId)
            put("isActive", state?.isActive == true)
            put("mode", state?.mode?.name ?: SignalingSessionMode.VOICE_VIDEO_CALL.name)
            put("hostPeerId", state?.hostPeerId.orEmpty())
            put("classCode", state?.classCode.orEmpty())
            put("className", state?.className.orEmpty())
            put("creatorUsername", state?.creatorUsername.orEmpty())
            put("isHidden", state?.isHidden == true)
            put("activeMaterialId", state?.activeMaterialId.orEmpty())
            put("activePageIndex", state?.activePageIndex ?: 0)
            put("materials", materialsToJsonArray(state?.materials.orEmpty()))
            put("participantCount", state?.peers?.size ?: 0)
            put("peers", peersToJsonArray(state?.peers.orEmpty()))
        }.toString()
    }

    fun roomMaterialsToJson(roomId: String): String {
        val state = getRoomState(roomId)
        return JSONObject().apply {
            put("roomId", roomId)
            put("classCode", state?.classCode.orEmpty())
            put("activeMaterialId", state?.activeMaterialId.orEmpty())
            put("activePageIndex", state?.activePageIndex ?: 0)
            put("materials", materialsToJsonArray(state?.materials.orEmpty()))
        }.toString()
    }

    fun closeAllRooms(reason: String = "Host stopped") {
        rooms.values.forEach { room ->
            room.peers.values.forEach { entry ->
                try {
                    entry.listener.onForceClose(reason)
                } catch (_: Exception) {
                }
            }
            room.peers.clear()
        }
        rooms.clear()
        classCodeToRoomId.clear()
        directCallInvitations.clear()
        publishRoomsSnapshot()
    }

    fun clearAllForTests() {
        closeAllRooms("Test cleanup")
    }

    fun materialsToJsonArray(materials: List<LiveClassMaterial>): JSONArray {
        return JSONArray().apply {
            materials.forEach { put(materialToJson(it)) }
        }
    }

    fun materialToJson(material: LiveClassMaterial): JSONObject {
        return JSONObject().apply {
            put("id", material.id)
            put("fileName", material.fileName)
            put("mimeType", material.mimeType)
            put("sizeBytes", material.sizeBytes)
            put("uploadedBy", material.uploadedBy)
            put("downloadUrl", material.downloadUrl)
            put("streamUrl", material.streamUrl)
            put("uploadedAtMs", material.uploadedAtMs)
        }
    }

    fun parseMaterialJson(obj: JSONObject): LiveClassMaterial {
        return LiveClassMaterial(
            id = obj.optString("id"),
            fileName = obj.optString("fileName", "document"),
            mimeType = obj.optString("mimeType", "application/octet-stream"),
            sizeBytes = obj.optLong("sizeBytes", 0L),
            uploadedBy = obj.optString("uploadedBy", "Presenter"),
            downloadUrl = obj.optString("downloadUrl", ""),
            streamUrl = obj.optString("streamUrl", ""),
            uploadedAtMs = obj.optLong("uploadedAtMs", 0L)
        )
    }

    fun parseMaterialsJsonArray(arr: JSONArray?): List<LiveClassMaterial> {
        if (arr == null) return emptyList()
        val list = ArrayList<LiveClassMaterial>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val parsed = parseMaterialJson(obj)
            if (parsed.id.isNotBlank()) list.add(parsed)
        }
        return list
    }

    private fun normalizeClassCode(raw: String?): String? {
        val clean = raw?.trim()?.uppercase(Locale.US)?.replace(" ", "") ?: return null
        return clean.takeIf { it.isNotEmpty() }
    }

    private fun publishRoomsSnapshot() {
        val snapshot = LinkedHashMap<String, SignalingRoomState>(rooms.size)
        rooms.forEach { (id, room) ->
            val peerList = room.peers.values.map { it.peer }.sortedBy { it.joinedAtMs }
            if (peerList.isNotEmpty() || room.keepAliveUntilEnded) {
                snapshot[id] = SignalingRoomState(
                    roomId = room.roomId,
                    channelId = room.channelId,
                    mode = room.mode,
                    isActive = true,
                    hostPeerId = room.hostPeerId,
                    classCode = room.classCode,
                    className = room.className.ifBlank { room.roomId },
                    creatorUsername = room.creatorUsername,
                    isHidden = room.isHidden,
                    activeMaterialId = room.activeMaterialId,
                    activePageIndex = room.activePageIndex,
                    materials = room.materials.toList(),
                    peers = peerList
                )
            }
        }
        _roomsFlow.value = snapshot
    }

    private fun peersToJsonArray(peers: List<SignalingPeer>): JSONArray {
        return JSONArray().apply {
            peers.forEach { put(peerToJson(it)) }
        }
    }

    private fun peerToJson(peer: SignalingPeer): JSONObject {
        return JSONObject().apply {
            put("peerId", peer.peerId)
            put("displayName", peer.displayName)
            put("role", peer.role.name)
            put("roomId", peer.roomId)
            put("isMuted", peer.isMuted)
            put("isVideoOn", peer.isVideoOn)
            put("isScreenSharing", peer.isScreenSharing)
            put("joinedAtMs", peer.joinedAtMs)
        }
    }

    private fun safeSend(listener: SignalingSocketListener, payload: String) {
        try {
            listener.onSendJson(payload)
        } catch (_: Exception) {
        }
    }

    private fun jsonEscape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    }
}
