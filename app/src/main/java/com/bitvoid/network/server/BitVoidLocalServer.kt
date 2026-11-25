package com.bitvoid.network.server

import android.content.Context
import com.bitvoid.network.call.BitVoidSignalingServer
import com.bitvoid.network.call.ParticipantRole
import com.bitvoid.network.call.SignalingPeer
import com.bitvoid.network.call.SignalingSessionMode
import com.bitvoid.network.channel.BitVoidChannel
import com.bitvoid.network.channel.BitVoidChannelManager
import com.bitvoid.network.channel.BitVoidSpaceKind
import com.bitvoid.network.channel.BitVoidSpaceRole
import com.bitvoid.network.channel.ChannelSession
import com.bitvoid.network.client.BitVoidClientManager
import com.bitvoid.network.direct.BitVoidDirectChatManager
import com.bitvoid.network.direct.BitVoidUserPrefs
import com.bitvoid.network.direct.DirectSession
import com.bitvoid.network.media.BitVoidMediaItem
import com.bitvoid.network.media.BitVoidMediaManager
import com.bitvoid.network.mesh.BitVoidMeshIdentity
import com.bitvoid.network.mesh.BitVoidMeshProtocol
import com.bitvoid.network.model.AuditLogEntry
import com.bitvoid.network.model.MediaCategory
import com.bitvoid.network.room.BitVoidRoomManager
import com.bitvoid.network.room.RoomSession
import com.bitvoid.network.service.BitVoidNodeStateHolder
import fi.iki.elonen.NanoWSD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

class BitVoidLocalServer(
    private val context: Context,
    port: Int = DEFAULT_PORT
) : NanoWSD(WILDCARD_ADDRESS, port) {

    companion object {
        const val WILDCARD_ADDRESS = "0.0.0.0"
        const val DEFAULT_PORT = 8080
        private const val CONSTANT_BLOCK_SIZE = 4096
        private const val STREAM_CHUNK_SIZE = 65536
        private const val HEADER_PAD_STEP = 64
        internal const val NODE_ACTIVITY_TIMEOUT_MS = 120_000L
        private const val IDENTITY_SESSION_LIFETIME_MS = 24L * 60L * 60L * 1_000L

        private val IDENTIFYING_REQUEST_HEADERS = setOf(
            "user-agent",
            "referer",
            "x-requested-with",
            "cookie",
            "x-forwarded-for",
            "x-real-ip",
            "via",
            "dnt",
            "sec-ch-ua",
            "sec-ch-ua-mobile",
            "sec-ch-ua-platform",
            "sec-ch-ua-full-version-list",
            "origin",
            "from",
            "client-ip",
            "x-client-ip"
        )

        private val PAD_ALPHABET = "0123456789abcdef".toCharArray()
    }

    private val secureRandom = SecureRandom()
    private val nodeActivitySalt = ByteArray(16).also { secureRandom.nextBytes(it) }
    // Volatile, in-memory activity keyed by a process-salted endpoint digest. This counts one app
    // device once across requests without retaining its local address or identity.
    private data class IdentitySession(
        val username: String,
        val issuedAtMs: Long,
        val expiresAtMs: Long
    )

    private val activeNodes = ConcurrentHashMap<String, Long>()
    private val identitySessions = ConcurrentHashMap<String, IdentitySession>()
    private val requestCounter = AtomicInteger(0)
    private val totalBytesServed = AtomicLong(0L)
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        BitVoidChannelManager.initializePersistence(context)
    }

    val sharedDirectory: File
        get() = BitVoidMediaManager.resolveSharedDirectory(context)

    override fun serve(session: IHTTPSession): Response {
        val rawUri = session.uri ?: "/"
        if (rawUri.startsWith("/ws/signaling")) {
            if (!isSameWifiNodeRequest(session)) {
                val respToken = generateEphemeralToken()
                val forbidden = buildPaddedTextResponse(
                    status = Response.Status.FORBIDDEN,
                    mimeType = "application/json; charset=utf-8",
                    body = """{"error":"forbidden","detail":"Signaling restricted to peers on the same local Wi-Fi node."}""",
                    token = respToken
                )
                applyAnonymizationHeaders(forbidden, respToken)
                return forbidden
            }
            @Suppress("DEPRECATION")
            val params = session.parms
            val pathRoom = rawUri.removePrefix("/ws/signaling").trimStart('/')
            val roomId = URLDecoder.decode(
                params["roomId"] ?: params["channelId"] ?: pathRoom,
                StandardCharsets.UTF_8.name()
            ).trim()
            val channelId = URLDecoder.decode(
                params["channelId"] ?: roomId,
                StandardCharsets.UTF_8.name()
            ).trim()
            val token = extractAuthToken(session)
            if (roomId.isBlank() || !BitVoidSignalingServer.authorizeSignalingJoin(roomId, channelId, token)) {
                val respToken = generateEphemeralToken()
                val unauthorized = buildPaddedTextResponse(
                    status = Response.Status.UNAUTHORIZED,
                    mimeType = "application/json; charset=utf-8",
                    body = """{"error":"unauthorized","detail":"Valid Live Class ID or channel access required for WebRTC signaling"}""",
                    token = respToken
                )
                applyAnonymizationHeaders(unauthorized, respToken)
                return unauthorized
            }
            return super.serve(session)
        }
        if (rawUri.startsWith("/ws/chat/") || rawUri.startsWith("/ws/dm/") || isWebsocketRequested(session)) {
            val isDmWs = rawUri.startsWith("/ws/dm/")
            val targetId = when {
                isDmWs -> URLDecoder.decode(rawUri.removePrefix("/ws/dm/"), StandardCharsets.UTF_8.name())
                rawUri.startsWith("/ws/chat/") -> URLDecoder.decode(rawUri.removePrefix("/ws/chat/"), StandardCharsets.UTF_8.name())
                else -> ""
            }
            val token = extractAuthToken(session)
            val directSession = if (isDmWs) {
                BitVoidDirectChatManager.validateToken(token = token, requiredDmId = targetId.ifBlank { null })
            } else {
                null
            }
            val channelSession = if (!isDmWs) {
                BitVoidChannelManager.validateToken(
                    token = token,
                    requiredChannelId = targetId.ifBlank { null }
                )
            } else {
                null
            }
            val roomSession = if (!isDmWs && channelSession == null) {
                BitVoidRoomManager.validateToken(
                    token = token,
                    requiredRoomId = targetId.ifBlank { null }
                )
            } else {
                null
            }

            if ((directSession == null && channelSession == null && roomSession == null) || targetId.isBlank()) {
                val respToken = generateEphemeralToken()
                val unauthorized = buildPaddedTextResponse(
                    status = Response.Status.UNAUTHORIZED,
                    mimeType = "application/json; charset=utf-8",
                    body = """{"error":"unauthorized","detail":"Valid session token required for WebSocket chat"}""",
                    token = respToken
                )
                applyAnonymizationHeaders(unauthorized, respToken)
                return unauthorized
            }
            return super.serve(session)
        }
        return super.serve(session)
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        val rawUri = handshake.uri ?: ""
        val token = extractAuthToken(handshake)
        if (rawUri.startsWith("/ws/signaling")) {
            @Suppress("DEPRECATION")
            val params = handshake.parms
            val pathRoom = rawUri.removePrefix("/ws/signaling").trimStart('/')
            val roomId = URLDecoder.decode(
                params["roomId"] ?: params["channelId"] ?: pathRoom,
                StandardCharsets.UTF_8.name()
            ).trim()
            val channelId = URLDecoder.decode(
                params["channelId"] ?: roomId,
                StandardCharsets.UTF_8.name()
            ).trim()
            if (roomId.isBlank() || !BitVoidSignalingServer.authorizeSignalingJoin(roomId, channelId, token)) {
                throw SecurityException("Unauthorized WebRTC signaling WebSocket session")
            }
            val requestedMode = runCatching {
                SignalingSessionMode.valueOf(params["mode"].orEmpty())
            }.getOrDefault(SignalingSessionMode.AUDIO_CALL)
            // Direct Chat rooms are always ordinary voice calls.
            val mode = if (roomId.startsWith("dm__", ignoreCase = false)) {
                SignalingSessionMode.AUDIO_CALL
            } else {
                requestedMode
            }
            val role = runCatching {
                ParticipantRole.valueOf(params["role"].orEmpty())
            }.getOrDefault(ParticipantRole.SPEAKER)
            val peerId = params["peerId"]
            val displayName = params["name"]
            val isMuted = params["isMuted"]?.toBooleanStrictOrNull() ?: false

            return BitVoidSignalingWebSocket(
                handshakeRequest = handshake,
                roomId = roomId,
                channelId = channelId,
                requestedPeerId = peerId,
                displayName = displayName,
                mode = mode,
                role = role,
                initialMuted = isMuted
            )
        }
        if (rawUri.startsWith("/ws/dm/")) {
            val dmId = URLDecoder.decode(rawUri.removePrefix("/ws/dm/"), StandardCharsets.UTF_8.name())
            val directSession = BitVoidDirectChatManager.validateToken(token, requiredDmId = dmId)
                ?: throw SecurityException("Unauthorized Direct Chat WebSocket session")
            return BitVoidDirectChatWebSocket(handshake, directSession)
        }
        val targetId = URLDecoder.decode(rawUri.removePrefix("/ws/chat/"), StandardCharsets.UTF_8.name())
        val channelSession = BitVoidChannelManager.validateToken(token, requiredChannelId = targetId)
        if (channelSession != null) {
            return BitVoidChannelChatWebSocket(handshake, channelSession)
        }
        val roomSession = BitVoidRoomManager.validateToken(token, requiredRoomId = targetId)
            ?: throw SecurityException("Unauthorized WebSocket session")
        return BitVoidRoomChatWebSocket(handshake, roomSession)
    }

    override fun serveHttp(session: IHTTPSession): Response {
        val ephemeralToken = generateEphemeralToken()
        val now = System.currentTimeMillis()
        activeNodes[connectionActivityKey(session)] = now
        pruneStaleNodes(now)

        // Strip all identifying HTTP headers before processing.
        val (sanitizedHeaders, strippedCount) = stripIdentifyingHeaders(session.headers)

        val rawUri = session.uri ?: "/"
        val normalizedPath = rawUri.lowercase(Locale.US)
        val method = session.method
        val authToken = extractAuthToken(session)

        val response = try {
            when {
                // Channel POST endpoints
                method == Method.POST && rawUri == "/api/channels/join" -> {
                    handleChannelJoin(session, ephemeralToken)
                }

                method == Method.POST && rawUri.startsWith("/api/channels/") && rawUri.endsWith("/upload") -> {
                    val channelId = extractChannelIdFromPath(rawUri, "/upload")
                    handleChannelFileUpload(channelId, session, authToken, ephemeralToken)
                }

                method == Method.POST && rawUri.startsWith("/api/channels/") && rawUri.endsWith("/chat") -> {
                    val channelId = extractChannelIdFromPath(rawUri, "/chat")
                    handleChannelChatPost(channelId, session, authToken, ephemeralToken)
                }

                // Telegram-style Group and Broadcast Channel endpoints. These extend the existing
                // channel transport rather than introducing a second messaging system.
                method == Method.POST && rawUri == "/api/spaces/groups" ->
                    handleCreateGroup(session, ephemeralToken)

                method == Method.POST && rawUri == "/api/spaces/channels" ->
                    handleCreateBroadcastChannel(session, ephemeralToken)

                method == Method.POST && rawUri == "/api/spaces/invitations/join" ->
                    handleJoinSpaceInvitation(session, ephemeralToken)

                method == Method.POST && rawUri.startsWith("/api/spaces/") && rawUri.endsWith("/subscribe") ->
                    handleSubscribeToSpace(extractSpaceIdFromPath(rawUri, "/subscribe"), session, ephemeralToken)

                method == Method.POST && rawUri.startsWith("/api/spaces/") && rawUri.endsWith("/unsubscribe") ->
                    handleUnsubscribeFromSpace(extractSpaceIdFromPath(rawUri, "/unsubscribe"), authToken, ephemeralToken)

                method == Method.POST && rawUri.startsWith("/api/spaces/") && rawUri.endsWith("/members/add") ->
                    handleSpaceMemberAction(extractSpaceIdFromPath(rawUri, "/members/add"), session, authToken, ephemeralToken, "add")

                method == Method.POST && rawUri.startsWith("/api/spaces/") && rawUri.endsWith("/members/remove") ->
                    handleSpaceMemberAction(extractSpaceIdFromPath(rawUri, "/members/remove"), session, authToken, ephemeralToken, "remove")

                method == Method.POST && rawUri.startsWith("/api/spaces/") && rawUri.endsWith("/members/role") ->
                    handleSpaceMemberAction(extractSpaceIdFromPath(rawUri, "/members/role"), session, authToken, ephemeralToken, "role")

                method == Method.POST && rawUri.startsWith("/api/spaces/") && rawUri.endsWith("/settings") ->
                    handleUpdateSpace(extractSpaceIdFromPath(rawUri, "/settings"), session, authToken, ephemeralToken)

                method == Method.POST && rawUri.startsWith("/api/spaces/") && rawUri.endsWith("/invitations") ->
                    handleCreateSpaceInvitation(extractSpaceIdFromPath(rawUri, "/invitations"), session, authToken, ephemeralToken)

                method == Method.POST && rawUri.startsWith("/api/spaces/") && rawUri.endsWith("/leave") ->
                    handleLeaveSpace(extractSpaceIdFromPath(rawUri, "/leave"), authToken, ephemeralToken)

                method == Method.POST && rawUri.startsWith("/api/spaces/") && rawUri.endsWith("/delete") ->
                    handleDeleteSpace(extractSpaceIdFromPath(rawUri, "/delete"), authToken, ephemeralToken)

                // Direct User Chat POST endpoints
                method == Method.POST && rawUri == "/api/users/register" -> {
                    handleRegisterUser(session, ephemeralToken)
                }

                method == Method.POST && rawUri == "/api/dm/open" -> {
                    handleDirectOpen(session, ephemeralToken)
                }

                method == Method.POST && rawUri.startsWith("/api/dm/") && rawUri.endsWith("/upload") -> {
                    val dmId = extractDmIdFromPath(rawUri, "/upload")
                    handleDmFileUpload(dmId, session, authToken, ephemeralToken)
                }

                method == Method.POST && rawUri.startsWith("/api/dm/") && rawUri.endsWith("/messages") -> {
                    val dmId = extractDmIdFromPath(rawUri, "/messages")
                    handleDirectMessagePost(dmId, session, authToken, ephemeralToken)
                }

                method == Method.POST && rawUri.startsWith("/api/dm/") && rawUri.endsWith("/call/invite") -> {
                    val dmId = extractDmIdFromPath(rawUri, "/call/invite")
                    handleDirectCallInvite(dmId, authToken, ephemeralToken)
                }

                method == Method.POST && rawUri.startsWith("/api/dm/") && rawUri.endsWith("/call/accept") -> {
                    val dmId = extractDmIdFromPath(rawUri, "/call/accept")
                    handleDirectCallResponse(dmId, authToken, ephemeralToken, accept = true)
                }

                method == Method.POST && rawUri.startsWith("/api/dm/") && rawUri.endsWith("/call/decline") -> {
                    val dmId = extractDmIdFromPath(rawUri, "/call/decline")
                    handleDirectCallResponse(dmId, authToken, ephemeralToken, accept = false)
                }

                method == Method.POST && rawUri.startsWith("/api/dm/") &&
                    (rawUri.endsWith("/call/cancel") || rawUri.endsWith("/call/end")) -> {
                    val suffix = if (rawUri.endsWith("/call/cancel")) "/call/cancel" else "/call/end"
                    val dmId = extractDmIdFromPath(rawUri, suffix)
                    handleDirectCallCancelOrEnd(dmId, authToken, ephemeralToken)
                }

                // Live Class POST endpoints
                method == Method.POST && rawUri == "/api/live-class/start" -> {
                    requireSameWifiNode(session)
                    handleLiveClassStart(session, ephemeralToken)
                }

                method == Method.POST && rawUri == "/api/live-class/join" -> {
                    requireSameWifiNode(session)
                    handleLiveClassJoin(session, ephemeralToken)
                }

                // Legacy Room POST endpoints
                method == Method.POST && (rawUri == "/api/rooms/unlock" || rawUri == "/api/room/join") -> {
                    handleRoomUnlock(session, ephemeralToken)
                }

                method == Method.POST && (rawUri == "/api/rooms/chat" || rawUri == "/api/room/chat") -> {
                    handleRoomChatPost(session, authToken, ephemeralToken)
                }

                method != Method.GET && method != Method.HEAD -> {
                    buildPaddedTextResponse(
                        status = Response.Status.METHOD_NOT_ALLOWED,
                        mimeType = "application/json; charset=utf-8",
                        body = """{"error":"method_not_allowed"}""",
                        token = ephemeralToken
                    )
                }

                rawUri == "/api/node" -> {
                    serveNodeIdentity(ephemeralToken, isHead = (method == Method.HEAD))
                }

                rawUri == "/api/mesh/nodes" -> {
                    serveMeshNodes(ephemeralToken, isHead = (method == Method.HEAD))
                }

                rawUri == "/" -> {
                    buildPaddedTextResponse(
                        status = Response.Status.OK,
                        mimeType = "application/json; charset=utf-8",
                        body = """{"service":"bitvoid-local-api","client":"android-app","api":"/api"}""",
                        token = ephemeralToken,
                        isHead = method == Method.HEAD
                    )
                }

                // Group/channel discovery and authenticated information.
                rawUri == "/api/spaces/discover" -> {
                    @Suppress("DEPRECATION")
                    val query = session.parms["q"].orEmpty()
                    serveDiscoveredChannels(query, ephemeralToken, isHead = method == Method.HEAD)
                }

                rawUri.startsWith("/api/spaces/") && rawUri.count { it == '/' } == 3 -> {
                    val spaceId = URLDecoder.decode(
                        rawUri.removePrefix("/api/spaces/"),
                        StandardCharsets.UTF_8.name()
                    )
                    serveSpaceDetails(spaceId, authToken, ephemeralToken, isHead = method == Method.HEAD)
                }

                // Channel GET endpoints
                rawUri == "/api/channels" -> {
                    @Suppress("DEPRECATION")
                    val username = session.parms["username"]
                    serveChannelsListJson(
                        username = username,
                        requestSession = session,
                        ephemeralToken = ephemeralToken,
                        isHead = (method == Method.HEAD)
                    )
                }

                rawUri.startsWith("/api/channels/") && rawUri.endsWith("/media") -> {
                    val channelId = extractChannelIdFromPath(rawUri, "/media")
                    serveChannelMediaJson(
                        channelId = channelId,
                        authToken = authToken,
                        ephemeralToken = ephemeralToken,
                        isHead = (method == Method.HEAD)
                    )
                }

                rawUri.startsWith("/api/channels/") && rawUri.endsWith("/chat") -> {
                    val channelId = extractChannelIdFromPath(rawUri, "/chat")
                    serveChannelChatHistory(
                        channelId = channelId,
                        authToken = authToken,
                        ephemeralToken = ephemeralToken,
                        isHead = (method == Method.HEAD)
                    )
                }

                rawUri.startsWith("/api/channels/") && rawUri.endsWith("/call/status") -> {
                    val channelId = extractChannelIdFromPath(rawUri, "/call/status")
                    serveChannelCallStatusJson(
                        channelId = channelId,
                        authToken = authToken,
                        ephemeralToken = ephemeralToken,
                        isHead = (method == Method.HEAD)
                    )
                }

                // P.Chat user discovery GET endpoints
                rawUri == "/api/users/status" -> {
                    requireSameWifiNode(session)
                    @Suppress("DEPRECATION")
                    val username = session.parms["username"]
                    serveUserStatusJson(username, ephemeralToken, isHead = (method == Method.HEAD))
                }

                rawUri == "/api/users" -> {
                    requireSameWifiNode(session)
                    serveUsersListJson(ephemeralToken, isHead = (method == Method.HEAD))
                }

                rawUri == "/api/calls/incoming" -> {
                    requireSameWifiNode(session)
                    @Suppress("DEPRECATION")
                    val username = session.parms["username"]
                    serveIncomingCallJson(username, ephemeralToken, isHead = (method == Method.HEAD))
                }

                rawUri.startsWith("/api/dm/") && rawUri.endsWith("/messages") -> {
                    val dmId = extractDmIdFromPath(rawUri, "/messages")
                    serveDirectMessages(
                        dmId = dmId,
                        authToken = authToken,
                        ephemeralToken = ephemeralToken,
                        isHead = (method == Method.HEAD)
                    )
                }

                rawUri.startsWith("/api/dm/") && rawUri.endsWith("/call/status") -> {
                    val dmId = extractDmIdFromPath(rawUri, "/call/status")
                    serveDirectCallStatusJson(
                        dmId = dmId,
                        authToken = authToken,
                        ephemeralToken = ephemeralToken,
                        isHead = (method == Method.HEAD)
                    )
                }

                rawUri == "/api/live-classes" -> {
                    requireSameWifiNode(session)
                    @Suppress("DEPRECATION")
                    val username = session.parms["username"]?.trim()
                    serveLiveClassesListJson(
                        username = username,
                        ephemeralToken = ephemeralToken,
                        isHead = (method == Method.HEAD)
                    )
                }

                // Public media endpoint
                rawUri == "/api/public/media" || rawUri == "/api/videos" || rawUri == "/api/files" -> {
                    servePublicVideosJson(ephemeralToken, isHead = (method == Method.HEAD))
                }

                // Legacy Room GET endpoints
                rawUri == "/api/rooms/videos" || rawUri == "/api/room/media" -> {
                    serveRoomVideosJson(authToken, ephemeralToken, isHead = (method == Method.HEAD))
                }

                rawUri == "/api/rooms/chat" || rawUri == "/api/room/chat" -> {
                    serveRoomChatHistory(authToken, ephemeralToken, isHead = (method == Method.HEAD))
                }

                rawUri.startsWith("/stream/dm/") -> {
                    val remainder = rawUri.removePrefix("/stream/dm/")
                    val dmId = URLDecoder.decode(remainder.substringBefore('/'), StandardCharsets.UTF_8.name())
                    val itemId = URLDecoder.decode(remainder.substringAfter('/', ""), StandardCharsets.UTF_8.name())
                    serveDmMediaFile(
                        dmId = dmId,
                        identifier = itemId,
                        dmToken = authToken,
                        requestHeaders = sanitizedHeaders,
                        ephemeralToken = ephemeralToken,
                        asAttachment = false,
                        isHead = (method == Method.HEAD)
                    )
                }

                rawUri.startsWith("/download/dm/") -> {
                    val remainder = rawUri.removePrefix("/download/dm/")
                    val dmId = URLDecoder.decode(remainder.substringBefore('/'), StandardCharsets.UTF_8.name())
                    val itemId = URLDecoder.decode(remainder.substringAfter('/', ""), StandardCharsets.UTF_8.name())
                    serveDmMediaFile(
                        dmId = dmId,
                        identifier = itemId,
                        dmToken = authToken,
                        requestHeaders = sanitizedHeaders,
                        ephemeralToken = ephemeralToken,
                        asAttachment = true,
                        isHead = (method == Method.HEAD)
                    )
                }

                rawUri.startsWith("/stream/room/") -> {
                    val remainder = rawUri.removePrefix("/stream/room/")
                    val roomId = URLDecoder.decode(remainder.substringBefore('/'), StandardCharsets.UTF_8.name())
                    val itemId = URLDecoder.decode(remainder.substringAfter('/', ""), StandardCharsets.UTF_8.name())
                    serveRoomMediaFile(
                        roomId = roomId,
                        identifier = itemId,
                        roomToken = authToken,
                        requestHeaders = sanitizedHeaders,
                        ephemeralToken = ephemeralToken,
                        asAttachment = false,
                        isHead = (method == Method.HEAD)
                    )
                }

                rawUri.startsWith("/download/room/") -> {
                    val remainder = rawUri.removePrefix("/download/room/")
                    val roomId = URLDecoder.decode(remainder.substringBefore('/'), StandardCharsets.UTF_8.name())
                    val itemId = URLDecoder.decode(remainder.substringAfter('/', ""), StandardCharsets.UTF_8.name())
                    serveRoomMediaFile(
                        roomId = roomId,
                        identifier = itemId,
                        roomToken = authToken,
                        requestHeaders = sanitizedHeaders,
                        ephemeralToken = ephemeralToken,
                        asAttachment = true,
                        isHead = (method == Method.HEAD)
                    )
                }

                rawUri.startsWith("/stream/") -> {
                    val identifier = rawUri.removePrefix("/stream/")
                    serveMediaFile(
                        encodedIdentifier = identifier,
                        authToken = authToken,
                        requestHeaders = sanitizedHeaders,
                        token = ephemeralToken,
                        asAttachment = false,
                        isHead = (method == Method.HEAD)
                    )
                }

                rawUri.startsWith("/download/") -> {
                    val identifier = rawUri.removePrefix("/download/")
                    serveMediaFile(
                        encodedIdentifier = identifier,
                        authToken = authToken,
                        requestHeaders = sanitizedHeaders,
                        token = ephemeralToken,
                        asAttachment = true,
                        isHead = (method == Method.HEAD)
                    )
                }

                else -> {
                    buildPaddedTextResponse(
                        status = Response.Status.NOT_FOUND,
                        mimeType = "text/plain; charset=utf-8",
                        body = "404 - Void Resource Not Found",
                        token = ephemeralToken
                    )
                }
            }
        } catch (e: SecurityException) {
            buildPaddedTextResponse(
                status = Response.Status.FORBIDDEN,
                mimeType = "text/plain; charset=utf-8",
                body = "403 - Forbidden",
                token = ephemeralToken
            )
        } catch (e: Exception) {
            buildPaddedTextResponse(
                status = Response.Status.INTERNAL_ERROR,
                mimeType = "text/plain; charset=utf-8",
                body = "500 - Internal Gateway Fault",
                token = ephemeralToken
            )
        }

        applyAnonymizationHeaders(response, ephemeralToken)

        val paddedBytesHeader = response.getHeader("X-BitVoid-Padded-Bytes")?.toLongOrNull() ?: 0L
        recordTelemetryAndAudit(
            ephemeralToken = ephemeralToken,
            method = method.name,
            sanitizedPath = sanitizePathForAudit(rawUri),
            statusCode = response.status.requestStatus,
            paddedBytes = paddedBytesHeader,
            strippedHeaders = strippedCount
        )

        return response
    }

    private fun extractChannelIdFromPath(rawUri: String, suffix: String): String {
        val inner = rawUri.removePrefix("/api/channels/").removeSuffix(suffix)
        return URLDecoder.decode(inner, StandardCharsets.UTF_8.name())
    }

    private fun extractDmIdFromPath(rawUri: String, suffix: String): String {
        val inner = rawUri.removePrefix("/api/dm/").removeSuffix(suffix)
        return URLDecoder.decode(inner, StandardCharsets.UTF_8.name())
    }

    private fun extractSpaceIdFromPath(rawUri: String, suffix: String): String {
        val inner = rawUri.removePrefix("/api/spaces/").removeSuffix(suffix)
        return URLDecoder.decode(inner, StandardCharsets.UTF_8.name())
    }

    private fun extractAuthToken(session: IHTTPSession): String? {
        val fromDmHeader = session.headers["x-dm-token"]
        if (!fromDmHeader.isNullOrBlank()) return fromDmHeader.trim()
        val fromChannelHeader = session.headers["x-channel-token"]
        if (!fromChannelHeader.isNullOrBlank()) return fromChannelHeader.trim()
        val fromRoomHeader = session.headers["x-room-token"]
        if (!fromRoomHeader.isNullOrBlank()) return fromRoomHeader.trim()
        val authHeader = session.headers["authorization"]
        if (!authHeader.isNullOrBlank() && authHeader.startsWith("Bearer ", ignoreCase = true)) {
            val bearer = authHeader.substring(7).trim()
            if (bearer.isNotEmpty()) return bearer
        }
        @Suppress("DEPRECATION")
        val fromParams = session.parms["token"]
        return fromParams?.trim()?.takeIf { it.isNotEmpty() }
    }

    @Synchronized
    private fun issueIdentityToken(username: String): String {
        val chars = CharArray(64) {
            PAD_ALPHABET[secureRandom.nextInt(PAD_ALPHABET.size)]
        }
        val token = "BVU.${String(chars)}"
        val now = System.currentTimeMillis()
        identitySessions.entries.removeIf { it.value.expiresAtMs < now }
        val oldestForUser = identitySessions.entries
            .filter { it.value.username.equals(username, ignoreCase = true) }
            .sortedBy { it.value.issuedAtMs }
        oldestForUser.take((oldestForUser.size - 7).coerceAtLeast(0)).forEach {
            identitySessions.remove(it.key)
        }
        identitySessions[token] = IdentitySession(
            username = username,
            issuedAtMs = now,
            expiresAtMs = now + IDENTITY_SESSION_LIFETIME_MS
        )
        return token
    }

    private fun requireIdentity(session: IHTTPSession, rawUsername: String): String {
        val username = BitVoidUserPrefs.normalizeUsernameOrNull(rawUsername)
            ?: throw SecurityException("A valid authenticated username is required.")
        val token = session.headers["x-bitvoid-identity-token"]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw SecurityException("Register this user with the gateway before changing spaces.")
        val identity = identitySessions[token]
            ?: throw SecurityException("Your identity session expired. Register with the gateway again.")
        if (identity.expiresAtMs < System.currentTimeMillis()) {
            identitySessions.remove(token)
            throw SecurityException("Your identity session expired. Register with the gateway again.")
        }
        if (!identity.username.equals(username, ignoreCase = true)) {
            throw SecurityException("The identity session does not match this username.")
        }
        if (!BitVoidDirectChatManager.isUserAvailable(username)) {
            throw SecurityException("This user is no longer active on the gateway.")
        }
        return username
    }

    private fun readPostBody(session: IHTTPSession): String {
        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        return bodyMap["postData"] ?: ""
    }

    /** Lightweight public identity response used by silent same-Wi-Fi mesh discovery. */
    private fun serveNodeIdentity(ephemeralToken: String, isHead: Boolean): Response {
        val payload = buildLocalMeshIdentityJson().toString()
        val payloadBytes = payload.toByteArray(StandardCharsets.UTF_8)
        val response = newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            ByteArrayInputStream(if (isHead) ByteArray(0) else payloadBytes),
            payloadBytes.size.toLong()
        )
        response.setGzipEncoding(false)
        response.addHeader("Content-Length", payloadBytes.size.toString())
        response.addHeader("Cache-Control", "no-store")
        response.addHeader(BitVoidMeshProtocol.HEADER_NODE, "1")
        response.addHeader(
            BitVoidMeshProtocol.HEADER_PROTOCOL,
            BitVoidMeshProtocol.PROTOCOL_VERSION.toString()
        )
        response.addHeader(BitVoidMeshProtocol.HEADER_IDENTITY, BitVoidMeshProtocol.IDENTITY_MODE)
        response.addHeader("X-BitVoid-Session", ephemeralToken)
        if (!isHead) {
            totalBytesServed.addAndGet(payloadBytes.size.toLong())
        }
        return response
    }

    private fun buildLocalMeshIdentityJson(): JSONObject {
        val hostState = BitVoidNodeStateHolder.hostState.value
        return JSONObject().apply {
            put("service", BitVoidMeshProtocol.SERVICE)
            put("nodeId", BitVoidMeshIdentity.getOrCreateNodeId(context))
            put("nodeType", BitVoidMeshProtocol.TYPE_ANDROID_GATEWAY)
            put("protocolVersion", BitVoidMeshProtocol.PROTOCOL_VERSION)
            put("identityMode", BitVoidMeshProtocol.IDENTITY_MODE)
            put("coordinator", false)
            put("portalAvailable", true)
            put("gatewayPriority", 100)
            put("hostname", BitVoidMdnsResponder.HOSTNAME)
            put("portalUrl", hostState.effectiveApiUrl)
            put(
                "directPortalUrl",
                if (hostState.hasClientGatewayAddress) hostState.networkApiUrl else JSONObject.NULL
            )
            put("port", DEFAULT_PORT)
            put(
                "features",
                JSONArray(listOf("mesh-v2", "channels", "calls", "liveClass", "files"))
            )
        }
    }

    private fun serveMeshNodes(ephemeralToken: String, isHead: Boolean): Response {
        val clientState = BitVoidNodeStateHolder.clientState.value
        val nodes = JSONArray().put(buildLocalMeshIdentityJson())
        clientState.linkedNodeUrls.forEach { url ->
            nodes.put(
                JSONObject().apply {
                    put("service", BitVoidMeshProtocol.SERVICE)
                    put("nodeId", "peer-${Integer.toHexString(url.hashCode())}")
                    put("nodeType", BitVoidMeshProtocol.TYPE_ANDROID_GATEWAY)
                    put("protocolVersion", BitVoidMeshProtocol.PROTOCOL_VERSION)
                    put("identityMode", BitVoidMeshProtocol.IDENTITY_MODE)
                    put("coordinator", false)
                    put("portalAvailable", true)
                    put("gatewayPriority", 100)
                    put("directPortalUrl", url)
                    put("portalUrl", url)
                }
            )
        }
        clientState.coordinatorUrls.forEach { url ->
            nodes.put(
                JSONObject().apply {
                    put("service", BitVoidMeshProtocol.SERVICE)
                    put("nodeId", "coordinator-${Integer.toHexString(url.hashCode())}")
                    put("nodeType", "coordinator")
                    put("protocolVersion", BitVoidMeshProtocol.PROTOCOL_VERSION)
                    put("identityMode", BitVoidMeshProtocol.IDENTITY_MODE)
                    put("coordinator", true)
                    put("portalAvailable", false)
                    put("gatewayPriority", 10)
                    put("directPortalUrl", url)
                }
            )
        }
        val payload = JSONObject().apply {
            put("service", BitVoidMeshProtocol.SERVICE)
            put("protocolVersion", BitVoidMeshProtocol.PROTOCOL_VERSION)
            put("identityMode", BitVoidMeshProtocol.IDENTITY_MODE)
            put("nodes", nodes)
        }.toString()
        val payloadBytes = payload.toByteArray(StandardCharsets.UTF_8)
        val response = newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            ByteArrayInputStream(if (isHead) ByteArray(0) else payloadBytes),
            payloadBytes.size.toLong()
        )
        response.setGzipEncoding(false)
        response.addHeader("Content-Length", payloadBytes.size.toString())
        response.addHeader("Cache-Control", "no-store")
        response.addHeader(BitVoidMeshProtocol.HEADER_NODE, "1")
        response.addHeader(
            BitVoidMeshProtocol.HEADER_PROTOCOL,
            BitVoidMeshProtocol.PROTOCOL_VERSION.toString()
        )
        response.addHeader(BitVoidMeshProtocol.HEADER_IDENTITY, BitVoidMeshProtocol.IDENTITY_MODE)
        response.addHeader("X-BitVoid-Session", ephemeralToken)
        if (!isHead) totalBytesServed.addAndGet(payloadBytes.size.toLong())
        return response
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Channel API Handlers
    // ───────────────────────────────────────────────────────────────────────────

    private fun serveChannelsListJson(
        username: String?,
        requestSession: IHTTPSession,
        ephemeralToken: String,
        isHead: Boolean
    ): Response {
        val diskItems = BitVoidMediaManager.refreshFromDisk(context)
        BitVoidChannelManager.syncMediaCatalog(diskItems)

        // Typed private spaces and role metadata are only listed for the authenticated identity.
        // Legacy private media spaces retain their previous username-list compatibility.
        val cleanRequestedUsername = username?.trim()?.removePrefix("@").orEmpty()
        val authenticatedUsername = if (cleanRequestedUsername.isNotEmpty()) {
            runCatching { requireIdentity(requestSession, cleanRequestedUsername) }.getOrNull()
        } else {
            null
        }
        val listedChannels = BitVoidChannelManager.getChannelSnapshotsForUser(
            authenticatedUsername ?: cleanRequestedUsername
        ).filter { channel ->
            channel.spaceKind == BitVoidSpaceKind.LEGACY_CHAT ||
                channel.isPublic || authenticatedUsername != null
        }
        val publicList = listedChannels.filter { it.isPublic }
        val privateList = listedChannels.filter { !it.isPublic }

        fun summary(channel: BitVoidChannel): String {
            val viewer = if (channel.spaceKind == BitVoidSpaceKind.LEGACY_CHAT) {
                cleanRequestedUsername
            } else {
                authenticatedUsername.orEmpty()
            }
            val viewerRole = channel.members.firstOrNull {
                it.username.equals(viewer, ignoreCase = true)
            }?.role
            val isOwner = viewerRole == BitVoidSpaceRole.OWNER ||
                (viewerRole == null && viewer.isNotBlank() &&
                    channel.creatorUsername.equals(viewer, ignoreCase = true))
            val revealId = channel.isPublic || isOwner || viewerRole != null
            return channelSummaryJson(channel, revealId, isOwner, viewerRole)
        }

        val publicJson = publicList.joinToString(",") { summary(it) }
        val privateJson = privateList.joinToString(",") { summary(it) }
        val combinedJson = listedChannels.joinToString(",") { summary(it) }

        val payload =
            """{"session":"$ephemeralToken","publicCount":${publicList.size},"privateCount":${privateList.size},"publicChannels":[$publicJson],"privateChannels":[$privateJson],"channels":[$combinedJson]}"""

        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = payload,
            token = ephemeralToken,
            isHead = isHead
        )
    }

    private fun channelSummaryJson(
        ch: BitVoidChannel,
        revealId: Boolean,
        isOwner: Boolean = false,
        viewerRole: BitVoidSpaceRole? = null
    ): String {
        val livePeers = ch.activeParticipants.count { it.isWebSocketConnected }
        return JSONObject().apply {
            if (revealId) put("channelId", ch.channelId)
            put("name", ch.name)
            put("isPublic", ch.isPublic)
            put("isHidden", ch.isHidden)
            put("isOwner", isOwner)
            put("locked", !ch.isPublic && !isOwner)
            put("requiresKey", !ch.isPublic && !isOwner)
            put("hasPrivateKey", !ch.privateKey.isNullOrBlank())
            put("mediaCount", ch.mediaList.size)
            put("activePeers", livePeers)
            put("messageCount", ch.messageCount)
            put("lastMessagePreview", ch.lastMessagePreview ?: JSONObject.NULL)
            put("lastTimestampMs", ch.lastTimestampMs)
            put("spaceKind", ch.spaceKind.name)
            put("description", ch.description)
            put("avatarDataUrl", ch.avatarDataUrl ?: JSONObject.NULL)
            put("memberCount", ch.members.size)
            put("subscriberCount", ch.subscriberCount)
            put("myRole", viewerRole?.name ?: JSONObject.NULL)
            put("isSubscribed", viewerRole != null)
        }.toString()
    }

    private fun handleCreateGroup(session: IHTTPSession, ephemeralToken: String): Response {
        val json = parseJsonBody(session) ?: return invalidJsonResponse(ephemeralToken)
        val owner = runCatching { requireIdentity(session, json.optString("username")) }
            .getOrElse { return spaceFailureResponse(it, ephemeralToken) }
        val membersArray = json.optJSONArray("members") ?: JSONArray()
        val members = buildList {
            for (index in 0 until membersArray.length()) {
                membersArray.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        val result = BitVoidChannelManager.createGroup(
            rawName = json.optString("name"),
            rawDescription = json.optString("description"),
            ownerUsername = owner,
            initialMembers = members,
            avatarDataUrl = json.optString("avatarDataUrl").takeIf { it.isNotBlank() }
        )
        return result.fold(
            onSuccess = { channel -> spaceMutationResponse(channel, owner, ephemeralToken) },
            onFailure = { error -> spaceFailureResponse(error, ephemeralToken) }
        )
    }

    private fun handleCreateBroadcastChannel(session: IHTTPSession, ephemeralToken: String): Response {
        val json = parseJsonBody(session) ?: return invalidJsonResponse(ephemeralToken)
        val owner = runCatching { requireIdentity(session, json.optString("username")) }
            .getOrElse { return spaceFailureResponse(it, ephemeralToken) }
        val result = BitVoidChannelManager.createBroadcastChannel(
            rawName = json.optString("name"),
            rawDescription = json.optString("description"),
            ownerUsername = owner,
            isPublic = json.optBoolean("isPublic", true),
            avatarDataUrl = json.optString("avatarDataUrl").takeIf { it.isNotBlank() }
        )
        return result.fold(
            onSuccess = { channel -> spaceMutationResponse(channel, owner, ephemeralToken) },
            onFailure = { error -> spaceFailureResponse(error, ephemeralToken) }
        )
    }

    private fun handleSubscribeToSpace(
        spaceId: String,
        session: IHTTPSession,
        ephemeralToken: String
    ): Response {
        val json = parseJsonBody(session) ?: return invalidJsonResponse(ephemeralToken)
        val username = runCatching { requireIdentity(session, json.optString("username")) }
            .getOrElse { return spaceFailureResponse(it, ephemeralToken) }
        return BitVoidChannelManager.subscribeToChannel(spaceId, username).fold(
            onSuccess = { joined ->
                val channel = BitVoidChannelManager.getChannel(spaceId)
                    ?: return@fold spaceFailureResponse(IllegalArgumentException("Channel not found."), ephemeralToken)
                JSONObject().apply {
                    put("ok", true)
                    put("channelId", joined.channelId)
                    put("token", joined.token)
                    put("space", JSONObject(spaceDetailsJson(channel, joined.role)))
                }.let {
                    buildPaddedTextResponse(Response.Status.OK, "application/json; charset=utf-8", it.toString(), ephemeralToken)
                }
            },
            onFailure = { spaceFailureResponse(it, ephemeralToken) }
        )
    }

    private fun handleUnsubscribeFromSpace(
        spaceId: String,
        authToken: String?,
        ephemeralToken: String
    ): Response = BitVoidChannelManager.unsubscribeFromChannel(spaceId, authToken.orEmpty()).fold(
        onSuccess = { okResponse(ephemeralToken) },
        onFailure = { spaceFailureResponse(it, ephemeralToken) }
    )

    private fun handleJoinSpaceInvitation(session: IHTTPSession, ephemeralToken: String): Response {
        val json = parseJsonBody(session) ?: return invalidJsonResponse(ephemeralToken)
        val username = runCatching { requireIdentity(session, json.optString("username")) }
            .getOrElse { return spaceFailureResponse(it, ephemeralToken) }
        return BitVoidChannelManager.joinChannelByInvitation(
            json.optString("invitation"),
            username
        ).fold(
            onSuccess = { joined ->
                val channel = BitVoidChannelManager.getChannel(joined.channelId)
                    ?: return@fold spaceFailureResponse(IllegalArgumentException("Channel not found."), ephemeralToken)
                JSONObject().apply {
                    put("ok", true)
                    put("token", joined.token)
                    put("channelId", joined.channelId)
                    put("space", JSONObject(spaceDetailsJson(channel, joined.role)))
                }.let {
                    buildPaddedTextResponse(Response.Status.OK, "application/json; charset=utf-8", it.toString(), ephemeralToken)
                }
            },
            onFailure = { spaceFailureResponse(it, ephemeralToken) }
        )
    }

    private fun handleSpaceMemberAction(
        spaceId: String,
        session: IHTTPSession,
        authToken: String?,
        ephemeralToken: String,
        action: String
    ): Response {
        val json = parseJsonBody(session) ?: return invalidJsonResponse(ephemeralToken)
        val username = json.optString("username")
        val result = when (action) {
            "add" -> BitVoidChannelManager.addGroupMember(spaceId, authToken.orEmpty(), username)
            "remove" -> BitVoidChannelManager.removeSpaceMember(spaceId, authToken.orEmpty(), username)
            "role" -> {
                val role = runCatching {
                    BitVoidSpaceRole.valueOf(json.optString("role").uppercase(Locale.US))
                }.getOrElse {
                    return spaceFailureResponse(IllegalArgumentException("Invalid role."), ephemeralToken)
                }
                BitVoidChannelManager.setSpaceMemberRole(spaceId, authToken.orEmpty(), username, role)
            }
            else -> Result.failure(IllegalArgumentException("Unsupported member action."))
        }
        return result.fold(
            onSuccess = { spaceMutationResponse(it, null, ephemeralToken, authToken) },
            onFailure = { spaceFailureResponse(it, ephemeralToken) }
        )
    }

    private fun handleUpdateSpace(
        spaceId: String,
        session: IHTTPSession,
        authToken: String?,
        ephemeralToken: String
    ): Response {
        val json = parseJsonBody(session) ?: return invalidJsonResponse(ephemeralToken)
        val current = BitVoidChannelManager.getChannel(spaceId)
            ?: return spaceFailureResponse(IllegalArgumentException("Space not found."), ephemeralToken)
        return BitVoidChannelManager.updateSpaceSettings(
            channelId = spaceId,
            sessionToken = authToken.orEmpty(),
            rawName = json.optString("name", current.name),
            rawDescription = json.optString("description", current.description),
            avatarDataUrl = if (json.has("avatarDataUrl")) json.optString("avatarDataUrl") else current.avatarDataUrl,
            isPublic = if (json.has("isPublic")) json.optBoolean("isPublic") else null
        ).fold(
            onSuccess = { spaceMutationResponse(it, null, ephemeralToken, authToken) },
            onFailure = { spaceFailureResponse(it, ephemeralToken) }
        )
    }

    private fun handleCreateSpaceInvitation(
        spaceId: String,
        session: IHTTPSession,
        authToken: String?,
        ephemeralToken: String
    ): Response {
        val json = parseJsonBody(session) ?: JSONObject()
        return BitVoidChannelManager.createInvitation(
            spaceId,
            authToken.orEmpty(),
            json.optInt("maxUses", 50)
        ).fold(
            onSuccess = { invitation ->
                val payload = JSONObject().apply {
                    put("code", invitation.code)
                    put("inviteLink", "bitvoid://channel/invite/${invitation.code}")
                    put("expiresAtMs", invitation.expiresAtMs)
                    put("remainingUses", invitation.remainingUses)
                }
                buildPaddedTextResponse(Response.Status.OK, "application/json; charset=utf-8", payload.toString(), ephemeralToken)
            },
            onFailure = { spaceFailureResponse(it, ephemeralToken) }
        )
    }

    private fun handleLeaveSpace(
        spaceId: String,
        authToken: String?,
        ephemeralToken: String
    ): Response {
        val channel = BitVoidChannelManager.getChannel(spaceId)
            ?: return spaceFailureResponse(IllegalArgumentException("Space not found."), ephemeralToken)
        val result = if (channel.spaceKind == BitVoidSpaceKind.GROUP) {
            BitVoidChannelManager.leaveGroup(spaceId, authToken.orEmpty())
        } else {
            BitVoidChannelManager.unsubscribeFromChannel(spaceId, authToken.orEmpty())
        }
        return result.fold(
            onSuccess = { okResponse(ephemeralToken) },
            onFailure = { spaceFailureResponse(it, ephemeralToken) }
        )
    }

    private fun handleDeleteSpace(
        spaceId: String,
        authToken: String?,
        ephemeralToken: String
    ): Response = BitVoidChannelManager.deleteSpace(spaceId, authToken.orEmpty()).fold(
        onSuccess = { okResponse(ephemeralToken) },
        onFailure = { spaceFailureResponse(it, ephemeralToken) }
    )

    private fun serveSpaceDetails(
        spaceId: String,
        authToken: String?,
        ephemeralToken: String,
        isHead: Boolean
    ): Response = BitVoidChannelManager.getSpaceDetails(spaceId, authToken.orEmpty()).fold(
        onSuccess = { channel ->
            val role = channel.members.firstOrNull { member ->
                BitVoidChannelManager.validateToken(authToken, spaceId)?.callsign
                    ?.equals(member.username, ignoreCase = true) == true
            }?.role
            buildPaddedTextResponse(
                Response.Status.OK,
                "application/json; charset=utf-8",
                spaceDetailsJson(channel, role),
                ephemeralToken,
                isHead = isHead
            )
        },
        onFailure = { spaceFailureResponse(it, ephemeralToken) }
    )

    private fun serveDiscoveredChannels(
        query: String,
        ephemeralToken: String,
        isHead: Boolean
    ): Response {
        val channels = BitVoidChannelManager.discoverPublicBroadcastChannels(query)
        val payload = JSONObject().apply {
            put("channels", JSONArray().apply {
                channels.forEach { put(JSONObject(channelSummaryJson(it, revealId = true))) }
            })
        }
        return buildPaddedTextResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            payload.toString(),
            ephemeralToken,
            isHead = isHead
        )
    }

    private fun spaceMutationResponse(
        channel: BitVoidChannel,
        username: String?,
        ephemeralToken: String,
        existingToken: String? = null
    ): Response {
        val joined = existingToken?.let { BitVoidChannelManager.validateToken(it, channel.channelId) }
            ?: username?.let { BitVoidChannelManager.joinChannel(channel.channelId, rawUsername = it) }
        val role = joined?.role ?: channel.members.firstOrNull {
            it.username.equals(username, ignoreCase = true)
        }?.role
        val payload = JSONObject().apply {
            put("ok", true)
            put("channelId", channel.channelId)
            put("token", joined?.token ?: JSONObject.NULL)
            put("space", JSONObject(spaceDetailsJson(channel, role)))
        }
        return buildPaddedTextResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            payload.toString(),
            ephemeralToken
        )
    }

    private fun spaceDetailsJson(channel: BitVoidChannel, viewerRole: BitVoidSpaceRole?): String {
        val visibleMembers = if (channel.spaceKind == BitVoidSpaceKind.BROADCAST_CHANNEL &&
            viewerRole !in setOf(BitVoidSpaceRole.OWNER, BitVoidSpaceRole.ADMINISTRATOR)
        ) {
            channel.members.filter { it.role in setOf(BitVoidSpaceRole.OWNER, BitVoidSpaceRole.ADMINISTRATOR) }
        } else {
            channel.members
        }
        return JSONObject().apply {
            put("channelId", channel.channelId)
            put("name", channel.name)
            put("description", channel.description)
            put("avatarDataUrl", channel.avatarDataUrl ?: JSONObject.NULL)
            put("spaceKind", channel.spaceKind.name)
            put("isPublic", channel.isPublic)
            put("isHidden", channel.isHidden)
            put("myRole", viewerRole?.name ?: JSONObject.NULL)
            put("memberCount", channel.members.size)
            put("subscriberCount", channel.subscriberCount)
            put("messageCount", channel.messageCount)
            put("members", JSONArray().apply {
                visibleMembers.forEach { member ->
                    put(JSONObject().apply {
                        put("username", member.username)
                        put("role", member.role.name)
                        put("joinedTimestampMs", member.joinedTimestampMs)
                    })
                }
            })
        }.toString()
    }

    private fun parseJsonBody(session: IHTTPSession): JSONObject? =
        runCatching { JSONObject(readPostBody(session)) }.getOrNull()

    private fun invalidJsonResponse(ephemeralToken: String): Response = buildPaddedTextResponse(
        Response.Status.BAD_REQUEST,
        "application/json; charset=utf-8",
        """{"error":"invalid_json"}""",
        ephemeralToken
    )

    private fun okResponse(ephemeralToken: String): Response = buildPaddedTextResponse(
        Response.Status.OK,
        "application/json; charset=utf-8",
        """{"ok":true}""",
        ephemeralToken
    )

    private fun spaceFailureResponse(error: Throwable, ephemeralToken: String): Response {
        val status = if (error is SecurityException || error is IllegalAccessException) {
            Response.Status.FORBIDDEN
        } else {
            Response.Status.BAD_REQUEST
        }
        return buildPaddedTextResponse(
            status,
            "application/json; charset=utf-8",
            JSONObject().apply {
                put("error", if (status == Response.Status.FORBIDDEN) "forbidden" else "invalid_request")
                put("detail", error.message ?: "Unable to complete the request.")
            }.toString(),
            ephemeralToken
        )
    }

    private fun handleChannelJoin(session: IHTTPSession, ephemeralToken: String): Response {
        val rawBody = readPostBody(session)
        val json = try {
            JSONObject(rawBody)
        } catch (_: Exception) {
            return buildPaddedTextResponse(
                status = Response.Status.BAD_REQUEST,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"invalid_json"}""",
                token = ephemeralToken
            )
        }

        val channelId = json.optString("channelId", json.optString("roomId", "")).trim()
        val privateKey = json.optString("privateKey", json.optString("passkey", ""))
        val username = json.optString("username", "").trim().takeIf { it.isNotEmpty() }
        val targetChannel = BitVoidChannelManager.getChannel(channelId)
        val authenticatedUsername = if (targetChannel?.spaceKind != null &&
            targetChannel.spaceKind != BitVoidSpaceKind.LEGACY_CHAT
        ) {
            runCatching { requireIdentity(session, username.orEmpty()) }
                .getOrElse { return spaceFailureResponse(it, ephemeralToken) }
        } else {
            username
        }
        val joined = BitVoidChannelManager.joinChannel(channelId, privateKey, authenticatedUsername)
            ?: return buildPaddedTextResponse(
                status = Response.Status.UNAUTHORIZED,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"unauthorized","detail":"Invalid Private Channel ID or key"}""",
                token = ephemeralToken
            )

        val diskItems = BitVoidMediaManager.refreshFromDisk(context)
        BitVoidChannelManager.syncMediaCatalog(diskItems)
        val channel = BitVoidChannelManager.getChannel(joined.channelId)
        val mediaJson = buildMediaItemsArrayJson(BitVoidChannelManager.getChannelMedia(joined.channelId))
        val historyJson = BitVoidChannelManager.getChatHistory(joined.channelId)
            .joinToString(",") { BitVoidChannelManager.messageToJson(it) }

        val payload = JSONObject().apply {
            put("token", joined.token)
            put("channelId", joined.channelId)
            put("name", channel?.name ?: joined.channelId)
            put("isPublic", joined.isPublic)
            put("callsign", joined.callsign)
            put("spaceKind", joined.spaceKind.name)
            put("role", joined.role?.name ?: JSONObject.NULL)
            put("description", channel?.description.orEmpty())
            put("avatarDataUrl", channel?.avatarDataUrl ?: JSONObject.NULL)
            put("memberCount", channel?.members?.size ?: 0)
            put("subscriberCount", channel?.subscriberCount ?: 0)
            put("canPublish", BitVoidChannelManager.canPublish(joined))
            put("media", JSONArray("[$mediaJson]"))
            put("videos", JSONArray("[$mediaJson]"))
            put("messages", JSONArray("[$historyJson]"))
        }.toString()

        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = payload,
            token = ephemeralToken
        )
    }

    private fun serveChannelMediaJson(
        channelId: String,
        authToken: String?,
        ephemeralToken: String,
        isHead: Boolean
    ): Response {
        val diskItems = BitVoidMediaManager.refreshFromDisk(context)
        BitVoidChannelManager.syncMediaCatalog(diskItems)

        val channel = BitVoidChannelManager.getChannel(channelId)
            ?: return buildPaddedTextResponse(
                status = Response.Status.NOT_FOUND,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"channel_not_found"}""",
                token = ephemeralToken
            )

        if (!channel.isPublic) {
            BitVoidChannelManager.validateToken(authToken, requiredChannelId = channelId)
                ?: return unauthorizedResponse(ephemeralToken)
        }

        val mediaJson = buildMediaItemsArrayJson(BitVoidChannelManager.getChannelMedia(channelId))
        val payload =
            """{"channelId":"${jsonEscape(channel.channelId)}","name":"${jsonEscape(channel.name)}","isPublic":${channel.isPublic},"media":[$mediaJson],"videos":[$mediaJson]}"""

        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = payload,
            token = ephemeralToken,
            isHead = isHead
        )
    }

    private fun serveChannelChatHistory(
        channelId: String,
        authToken: String?,
        ephemeralToken: String,
        isHead: Boolean
    ): Response {
        val channel = BitVoidChannelManager.getChannel(channelId)
            ?: return buildPaddedTextResponse(
                status = Response.Status.NOT_FOUND,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"channel_not_found"}""",
                token = ephemeralToken
            )

        if (!channel.isPublic) {
            BitVoidChannelManager.validateToken(authToken, requiredChannelId = channelId)
                ?: return unauthorizedResponse(ephemeralToken)
        }

        val messages = BitVoidChannelManager.getChatHistory(channelId)
            .joinToString(",") { BitVoidChannelManager.messageToJson(it) }
        val participants = BitVoidChannelManager.getParticipants(channelId)
        val payload =
            """{"channelId":"${jsonEscape(channelId)}","participantCount":${participants.size},"messages":[$messages]}"""
        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = payload,
            token = ephemeralToken,
            isHead = isHead
        )
    }

    private fun serveChannelCallStatusJson(
        channelId: String,
        authToken: String?,
        ephemeralToken: String,
        isHead: Boolean
    ): Response {
        val channel = BitVoidChannelManager.getChannel(channelId)
            ?: return buildPaddedTextResponse(
                status = Response.Status.NOT_FOUND,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"channel_not_found"}""",
                token = ephemeralToken
            )

        if (!channel.isPublic) {
            BitVoidChannelManager.validateToken(authToken, requiredChannelId = channelId)
                ?: return unauthorizedResponse(ephemeralToken)
        }

        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = BitVoidSignalingServer.roomStatusToJson(channelId),
            token = ephemeralToken,
            isHead = isHead
        )
    }

    private fun serveDirectCallStatusJson(
        dmId: String,
        authToken: String?,
        ephemeralToken: String,
        isHead: Boolean
    ): Response {
        val directSession = BitVoidDirectChatManager.validateToken(authToken, requiredDmId = dmId)
            ?: return unauthorizedResponse(ephemeralToken)
        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = BitVoidSignalingServer.directCallStatusToJson(dmId, directSession),
            token = ephemeralToken,
            isHead = isHead
        )
    }

    private fun handleDirectCallInvite(
        dmId: String,
        authToken: String?,
        ephemeralToken: String
    ): Response {
        val directSession = BitVoidDirectChatManager.validateToken(authToken, requiredDmId = dmId)
            ?: return unauthorizedResponse(ephemeralToken)
        return try {
            BitVoidSignalingServer.requestDirectCall(session = directSession)
            buildPaddedTextResponse(
                status = Response.Status.OK,
                mimeType = "application/json; charset=utf-8",
                body = BitVoidSignalingServer.directCallStatusToJson(dmId, directSession),
                token = ephemeralToken
            )
        } catch (e: IllegalStateException) {
            buildPaddedTextResponse(
                status = Response.Status.CONFLICT,
                mimeType = "application/json; charset=utf-8",
                body = JSONObject().put("error", "call_conflict")
                    .put("detail", e.message ?: "Another call is already ringing.").toString(),
                token = ephemeralToken
            )
        }
    }

    private fun handleDirectCallResponse(
        dmId: String,
        authToken: String?,
        ephemeralToken: String,
        accept: Boolean
    ): Response {
        val directSession = BitVoidDirectChatManager.validateToken(authToken, requiredDmId = dmId)
            ?: return unauthorizedResponse(ephemeralToken)
        val updated = BitVoidSignalingServer.respondToDirectCall(directSession, accept)
            ?: return buildPaddedTextResponse(
                status = Response.Status.CONFLICT,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"call_not_ringing","detail":"This invitation is no longer waiting for your response."}""",
                token = ephemeralToken
            )
        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = BitVoidSignalingServer.directCallStatusToJson(updated.dmId, directSession),
            token = ephemeralToken
        )
    }

    private fun handleDirectCallCancelOrEnd(
        dmId: String,
        authToken: String?,
        ephemeralToken: String
    ): Response {
        val directSession = BitVoidDirectChatManager.validateToken(authToken, requiredDmId = dmId)
            ?: return unauthorizedResponse(ephemeralToken)
        BitVoidSignalingServer.cancelOrEndDirectCall(directSession)
        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = BitVoidSignalingServer.directCallStatusToJson(dmId, directSession),
            token = ephemeralToken
        )
    }

    private fun isSameWifiNodeRequest(session: IHTTPSession): Boolean {
        val remote = session.remoteIpAddress?.trim()?.takeIf { it.isNotEmpty() } ?: return true
        var cleanIp = remote.substringBefore('%').trim('[', ']')
        if (cleanIp.startsWith("::ffff:", ignoreCase = true)) {
            cleanIp = cleanIp.substring(7)
        }
        if (cleanIp.isEmpty() ||
            cleanIp == "127.0.0.1" ||
            cleanIp == "::1" ||
            cleanIp == "0:0:0:0:0:0:0:1"
        ) {
            return true
        }
        return BitVoidClientManager.isPrivateLocalHost(cleanIp)
    }

    private fun requireSameWifiNode(session: IHTTPSession) {
        if (!isSameWifiNodeRequest(session)) {
            throw SecurityException("Access allowed only for devices connected on the same local Wi-Fi node.")
        }
    }

    private fun serveLiveClassesListJson(
        username: String?,
        ephemeralToken: String,
        isHead: Boolean
    ): Response {
        val cleanRequestUser = username?.trim()?.removePrefix("@").orEmpty()
        val list = BitVoidSignalingServer.listLiveClassesForUser(cleanRequestUser)
        val arr = JSONArray().apply {
            list.forEach { state ->
                val isCreator = cleanRequestUser.isNotEmpty() &&
                    state.creatorUsername.equals(cleanRequestUser, ignoreCase = true)
                put(
                    JSONObject().apply {
                        put("roomId", state.roomId)
                        put("channelId", state.channelId)
                        put("name", state.className.ifBlank { state.roomId })
                        put("classCode", if (isCreator) state.classCode.orEmpty() else "")
                        put("creatorUsername", state.creatorUsername)
                        put("isHidden", state.isHidden)
                        put("participantCount", state.peers.size)
                        put("isActive", state.isActive)
                    }
                )
            }
        }
        val payload = JSONObject().apply {
            put("liveClasses", arr)
        }.toString()
        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = payload,
            token = ephemeralToken,
            isHead = isHead
        )
    }

    private fun handleLiveClassStart(session: IHTTPSession, ephemeralToken: String): Response {
        val rawBody = readPostBody(session)
        val json = try {
            JSONObject(rawBody)
        } catch (_: Exception) {
            return buildPaddedTextResponse(
                status = Response.Status.BAD_REQUEST,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"invalid_json"}""",
                token = ephemeralToken
            )
        }

        val rawName = json.optString("name", json.optString("title", "")).trim()
        if (rawName.isEmpty()) {
            return buildPaddedTextResponse(
                status = Response.Status.BAD_REQUEST,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"missing_name","detail":"Enter a Live Class name."}""",
                token = ephemeralToken
            )
        }
        val isHidden = json.optBoolean("isHidden", false)
        val creatorUsername = json.optString("username", "").trim().removePrefix("@").ifEmpty {
            "Host-${ephemeralToken.takeLast(4).uppercase(Locale.US)}"
        }

        val invite = BitVoidSignalingServer.registerLiveClass(
            rawName = rawName,
            rawCreatorUsername = creatorUsername,
            isHidden = isHidden
        )

        BitVoidChannelManager.createChannel(
            rawName = invite.className,
            isPublic = false,
            rawChannelId = invite.roomId,
            rawPrivateKey = null,
            mediaIds = emptySet(),
            isHidden = true
        )

        val hostSession = BitVoidChannelManager.issueLiveClassStudentSession(
            rawChannelId = invite.channelId,
            rawUsername = creatorUsername
        ) ?: return buildPaddedTextResponse(
            status = Response.Status.INTERNAL_ERROR,
            mimeType = "application/json; charset=utf-8",
            body = """{"error":"channel_init_failed"}""",
            token = ephemeralToken
        )

        val payload = JSONObject().apply {
            put("token", hostSession.token)
            put("classCode", invite.classCode)
            put("roomId", invite.roomId)
            put("channelId", hostSession.channelId)
            put("name", invite.className)
            put("isPublic", false)
            put("isHidden", invite.isHidden)
            put("creatorUsername", invite.creatorUsername)
            put("callsign", hostSession.callsign)
        }.toString()

        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = payload,
            token = ephemeralToken
        )
    }

    private fun handleLiveClassJoin(session: IHTTPSession, ephemeralToken: String): Response {
        val rawBody = readPostBody(session)
        val json = try {
            JSONObject(rawBody)
        } catch (_: Exception) {
            return buildPaddedTextResponse(
                status = Response.Status.BAD_REQUEST,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"invalid_json"}""",
                token = ephemeralToken
            )
        }

        val rawCode = json.optString(
            "classCode",
            json.optString("liveClassId", json.optString("code", ""))
        ).trim()
        val rawRoomId = json.optString("roomId", "").trim()
        val username = json.optString("username", "").trim().takeIf { it.isNotEmpty() }

        val invite = if (rawCode.isNotEmpty()) {
            BitVoidSignalingServer.resolveLiveClassByCode(rawCode)
                ?: return buildPaddedTextResponse(
                    status = Response.Status.NOT_FOUND,
                    mimeType = "application/json; charset=utf-8",
                    body = """{"error":"live_class_not_found","detail":"Incorrect or inactive Live Class ID. Verify the CLS- code and try again."}""",
                    token = ephemeralToken
                )
        } else if (rawRoomId.isNotEmpty()) {
            BitVoidSignalingServer.resolveLiveClassByRoomIdForUser(rawRoomId, username)
                ?: return buildPaddedTextResponse(
                    status = Response.Status.FORBIDDEN,
                    mimeType = "application/json; charset=utf-8",
                    body = """{"error":"live_class_hidden_or_not_found","detail":"Hidden Live Class requires the exact CLS- Class ID and connection to the same Wi-Fi node."}""",
                    token = ephemeralToken
                )
        } else {
            return buildPaddedTextResponse(
                status = Response.Status.BAD_REQUEST,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"missing_class_id","detail":"Enter the Live Class ID (CLS-...) shared by the host."}""",
                token = ephemeralToken
            )
        }

        val studentSession = BitVoidChannelManager.issueLiveClassStudentSession(
            rawChannelId = invite.channelId,
            rawUsername = username
        ) ?: return buildPaddedTextResponse(
            status = Response.Status.NOT_FOUND,
            mimeType = "application/json; charset=utf-8",
            body = """{"error":"channel_not_found","detail":"Live Class channel is no longer available."}""",
            token = ephemeralToken
        )

        val channel = BitVoidChannelManager.getChannel(studentSession.channelId)
        val payload = JSONObject().apply {
            put("token", studentSession.token)
            put("classCode", invite.classCode)
            put("roomId", invite.roomId)
            put("channelId", studentSession.channelId)
            put("name", invite.className.ifBlank { channel?.name ?: studentSession.channelId })
            put("isPublic", studentSession.isPublic)
            put("isHidden", invite.isHidden)
            put("creatorUsername", invite.creatorUsername)
            put("callsign", studentSession.callsign)
        }.toString()

        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = payload,
            token = ephemeralToken
        )
    }

    private fun handleChannelChatPost(
        channelId: String,
        session: IHTTPSession,
        authToken: String?,
        ephemeralToken: String
    ): Response {
        val channel = BitVoidChannelManager.getChannel(channelId)
            ?: return buildPaddedTextResponse(
                status = Response.Status.NOT_FOUND,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"channel_not_found"}""",
                token = ephemeralToken
            )

        // Consume text request bodies even when authorization fails so a keep-alive connection
        // cannot carry unread bytes into the next request.
        val rawBody = readPostBody(session)
        val validated = BitVoidChannelManager.validateToken(authToken, requiredChannelId = channelId)
        if ((!channel.isPublic && validated == null) ||
            (channel.spaceKind != BitVoidSpaceKind.LEGACY_CHAT &&
                (validated == null || !BitVoidChannelManager.canPublish(validated)))
        ) {
            return unauthorizedResponse(ephemeralToken)
        }

        val bodyJson = runCatching { JSONObject(rawBody) }.getOrNull()
        val text = bodyJson?.optString("text", "") ?: rawBody
        val stableMessageId = bodyJson?.optString("clientMessageId")
            ?.takeIf { it.isNotBlank() }
            ?: session.headers["idempotency-key"]
        val clientTimestampMs = bodyJson?.optLong("timestampMs", 0L)
            ?.takeIf { it > 0L }

        val callsign = validated?.callsign ?: "Anon-${ephemeralToken.takeLast(4).uppercase(Locale.US)}"
        val saved = BitVoidChannelManager.appendChatMessage(
            channelId = channelId,
            callsign = callsign,
            rawText = text,
            stableMessageId = stableMessageId,
            clientTimestampMs = clientTimestampMs,
            sessionToken = authToken
        ) ?: return buildPaddedTextResponse(
            status = Response.Status.BAD_REQUEST,
            mimeType = "application/json; charset=utf-8",
            body = """{"error":"empty_message"}""",
            token = ephemeralToken
        )

        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = """{"ok":true,"message":${BitVoidChannelManager.messageToJson(saved)}}""",
            token = ephemeralToken
        )
    }

    private fun handleChannelFileUpload(
        channelId: String,
        session: IHTTPSession,
        authToken: String?,
        ephemeralToken: String
    ): Response {
        val channel = BitVoidChannelManager.getChannel(channelId)
            ?: return buildPaddedTextResponse(
                status = Response.Status.NOT_FOUND,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"channel_not_found"}""",
                token = ephemeralToken
            )

        val validated = BitVoidChannelManager.validateToken(authToken, requiredChannelId = channelId)
        if ((!channel.isPublic && validated == null) ||
            (channel.spaceKind != BitVoidSpaceKind.LEGACY_CHAT &&
                (validated == null || !BitVoidChannelManager.canPublish(validated)))
        ) {
            return unauthorizedResponse(ephemeralToken)
        }

        val (importedItem, caption) = parseAndImportUploadedFile(session)
            ?: return buildPaddedTextResponse(
                status = Response.Status.BAD_REQUEST,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"missing_file","detail":"No uploaded file payload found"}""",
                token = ephemeralToken
            )

        val callsign = validated?.callsign ?: "Anon-${ephemeralToken.takeLast(4).uppercase(Locale.US)}"
        val saved = BitVoidChannelManager.appendChatMessage(
            channelId = channelId,
            callsign = callsign,
            rawText = caption,
            attachment = importedItem,
            sessionToken = authToken
        ) ?: return buildPaddedTextResponse(
            status = Response.Status.BAD_REQUEST,
            mimeType = "application/json; charset=utf-8",
            body = """{"error":"upload_failed"}""",
            token = ephemeralToken
        )

        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = """{"ok":true,"message":${BitVoidChannelManager.messageToJson(saved)},"media":[${buildMediaItemsArrayJson(listOf(importedItem))}]}""",
            token = ephemeralToken
        )
    }

    private fun parseAndImportUploadedFile(session: IHTTPSession): Pair<BitVoidMediaItem, String>? {
        val files = HashMap<String, String>()
        session.parseBody(files)
        @Suppress("DEPRECATION")
        val params = session.parms
        val caption = (params["caption"] ?: params["text"] ?: "").trim()

        val multipartEntry = files.entries.firstOrNull { it.key == "file" }
            ?: files.entries.firstOrNull { it.key != "postData" && it.key != "content" }
        if (multipartEntry != null) {
            val tempFile = File(multipartEntry.value)
            val originalName = params[multipartEntry.key]?.takeIf { it.isNotBlank() }
                ?: params["filename"]?.takeIf { it.isNotBlank() }
                ?: session.headers["x-file-name"]?.takeIf { it.isNotBlank() }
                ?: tempFile.name
            val imported = BitVoidMediaManager.importFromTempFile(context, originalName, tempFile)
                ?: return null
            return imported to caption
        }

        val contentTempPath = files["content"]
        if (!contentTempPath.isNullOrBlank()) {
            val tempFile = File(contentTempPath)
            val originalName = params["filename"]?.takeIf { it.isNotBlank() }
                ?: session.headers["x-file-name"]?.takeIf { it.isNotBlank() }
                ?: "upload_${System.currentTimeMillis()}"
            val imported = BitVoidMediaManager.importFromTempFile(context, originalName, tempFile)
                ?: return null
            return imported to caption
        }

        val rawPost = files["postData"]
        if (!rawPost.isNullOrEmpty()) {
            val originalName = params["filename"]?.takeIf { it.isNotBlank() }
                ?: session.headers["x-file-name"]?.takeIf { it.isNotBlank() }
                ?: "upload_${System.currentTimeMillis()}.bin"
            val imported = BitVoidMediaManager.importFromBytes(
                context,
                originalName,
                rawPost.toByteArray(StandardCharsets.ISO_8859_1)
            ) ?: return null
            return imported to caption
        }

        return null
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Direct User-to-User Chat API Handlers
    // ───────────────────────────────────────────────────────────────────────────

    private fun handleRegisterUser(session: IHTTPSession, ephemeralToken: String): Response {
        requireSameWifiNode(session)
        val rawBody = readPostBody(session)
        val json = try {
            JSONObject(rawBody)
        } catch (_: Exception) {
            return buildPaddedTextResponse(
                status = Response.Status.BAD_REQUEST,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"invalid_json"}""",
                token = ephemeralToken
            )
        }
        val username = json.optString("username", "")
        val discoverable = if (json.has("discoverable")) json.optBoolean("discoverable", true) else null
        val res = BitVoidDirectChatManager.registerUser(username, discoverable)
        if (res.isFailure) {
            return buildPaddedTextResponse(
                status = Response.Status.BAD_REQUEST,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"invalid_username","detail":"${jsonEscape(res.exceptionOrNull()?.message ?: "Invalid username")}"}""",
                token = ephemeralToken
            )
        }
        val clean = res.getOrThrow()
        val suppliedIdentityToken = session.headers["x-bitvoid-identity-token"]?.trim().orEmpty()
        val existingIdentity = identitySessions[suppliedIdentityToken]
        val identityToken = if (existingIdentity != null &&
            existingIdentity.expiresAtMs >= System.currentTimeMillis() &&
            existingIdentity.username.equals(clean, ignoreCase = true)
        ) {
            suppliedIdentityToken
        } else {
            issueIdentityToken(clean)
        }
        val users = BitVoidDirectChatManager.getKnownUsers()
        val payload = JSONObject().apply {
            put("ok", true)
            put("username", clean)
            put("identityToken", identityToken)
            put("identityExpiresAtMs", System.currentTimeMillis() + IDENTITY_SESSION_LIFETIME_MS)
            put("users", JSONArray(users))
        }
        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = payload.toString(),
            token = ephemeralToken
        )
    }

    private fun serveUsersListJson(ephemeralToken: String, isHead: Boolean): Response {
        val users = BitVoidDirectChatManager.getKnownUsers()
        val usersJson = users.joinToString(",") { """"${jsonEscape(it)}"""" }
        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = """{"count":${users.size},"users":[$usersJson]}""",
            token = ephemeralToken,
            isHead = isHead
        )
    }

    /** Exact lookup lets P.Chat reach a hidden user without exposing that user in discovery lists. */
    private fun serveUserStatusJson(
        username: String?,
        ephemeralToken: String,
        isHead: Boolean
    ): Response {
        val clean = username?.trim()?.removePrefix("@").orEmpty()
        val available = clean.isNotEmpty() && BitVoidDirectChatManager.isUserAvailable(clean)
        val payload = JSONObject().apply {
            put("username", clean)
            put("available", available)
        }.toString()
        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = payload,
            token = ephemeralToken,
            isHead = isHead
        )
    }

    private fun serveIncomingCallJson(
        username: String?,
        ephemeralToken: String,
        isHead: Boolean
    ): Response {
        val invite = BitVoidSignalingServer.incomingDirectCallFor(username)
        val payload = JSONObject().apply {
            put("hasIncomingCall", invite != null)
            put("dmId", invite?.dmId.orEmpty())
            put("callerUsername", invite?.callerUsername.orEmpty())
            put("calleeUsername", invite?.calleeUsername.orEmpty())
            put("createdAtMs", invite?.createdAtMs ?: 0L)
        }.toString()
        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = payload,
            token = ephemeralToken,
            isHead = isHead
        )
    }

    private fun handleDirectOpen(session: IHTTPSession, ephemeralToken: String): Response {
        requireSameWifiNode(session)
        val rawBody = readPostBody(session)
        val json = try {
            JSONObject(rawBody)
        } catch (_: Exception) {
            return buildPaddedTextResponse(
                status = Response.Status.BAD_REQUEST,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"invalid_json"}""",
                token = ephemeralToken
            )
        }
        val myUsername = json.optString("myUsername", "")
        val targetUsername = json.optString("targetUsername", "")
        if (!BitVoidDirectChatManager.isUserAvailable(targetUsername)) {
            return buildPaddedTextResponse(
                status = Response.Status.NOT_FOUND,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"user_not_on_node","detail":"User @${jsonEscape(targetUsername.trim().removePrefix("@"))} is not available on this BitVoid node or local Wi-Fi."}""",
                token = ephemeralToken
            )
        }
        val opened = BitVoidDirectChatManager.openDirectSession(myUsername, targetUsername)
        if (opened.isFailure) {
            return buildPaddedTextResponse(
                status = Response.Status.BAD_REQUEST,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"invalid_direct_chat","detail":"${jsonEscape(opened.exceptionOrNull()?.message ?: "Invalid usernames")}"}""",
                token = ephemeralToken
            )
        }
        val directSession = opened.getOrThrow()
        val historyJson = BitVoidDirectChatManager.getHistory(directSession.dmId)
            .joinToString(",") { BitVoidDirectChatManager.messageToJson(it) }
        val payload =
            """{"token":"${jsonEscape(directSession.token)}","dmId":"${jsonEscape(directSession.dmId)}","myUsername":"${jsonEscape(directSession.myUsername)}","peerUsername":"${jsonEscape(directSession.peerUsername)}","messages":[$historyJson]}"""
        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = payload,
            token = ephemeralToken
        )
    }

    private fun serveDirectMessages(
        dmId: String,
        authToken: String?,
        ephemeralToken: String,
        isHead: Boolean
    ): Response {
        BitVoidDirectChatManager.validateToken(authToken, requiredDmId = dmId)
            ?: return unauthorizedResponse(ephemeralToken)
        val historyJson = BitVoidDirectChatManager.getHistory(dmId)
            .joinToString(",") { BitVoidDirectChatManager.messageToJson(it) }
        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = """{"dmId":"${jsonEscape(dmId)}","messages":[$historyJson]}""",
            token = ephemeralToken,
            isHead = isHead
        )
    }

    private fun handleDirectMessagePost(
        dmId: String,
        session: IHTTPSession,
        authToken: String?,
        ephemeralToken: String
    ): Response {
        val validated = BitVoidDirectChatManager.validateToken(authToken, requiredDmId = dmId)
            ?: return unauthorizedResponse(ephemeralToken)
        val rawBody = readPostBody(session)
        val bodyJson = runCatching { JSONObject(rawBody) }.getOrNull()
        val text = bodyJson?.optString("text", "") ?: rawBody
        val stableMessageId = bodyJson?.optString("clientMessageId")
            ?.takeIf { it.isNotBlank() }
            ?: session.headers["idempotency-key"]
        val clientTimestampMs = bodyJson?.optLong("timestampMs", 0L)
            ?.takeIf { it > 0L }
        val saved = BitVoidDirectChatManager.appendMessage(
            dmId = dmId,
            senderUsername = validated.myUsername,
            rawText = text,
            stableMessageId = stableMessageId,
            clientTimestampMs = clientTimestampMs
        ) ?: return buildPaddedTextResponse(
            status = Response.Status.BAD_REQUEST,
            mimeType = "application/json; charset=utf-8",
            body = """{"error":"empty_message"}""",
            token = ephemeralToken
        )
        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = """{"ok":true,"message":${BitVoidDirectChatManager.messageToJson(saved)}}""",
            token = ephemeralToken
        )
    }

    private fun handleDmFileUpload(
        dmId: String,
        session: IHTTPSession,
        authToken: String?,
        ephemeralToken: String
    ): Response {
        val validated = BitVoidDirectChatManager.validateToken(authToken, requiredDmId = dmId)
            ?: return unauthorizedResponse(ephemeralToken)

        val (importedItem, caption) = parseAndImportUploadedFile(session)
            ?: return buildPaddedTextResponse(
                status = Response.Status.BAD_REQUEST,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"missing_file","detail":"No uploaded file payload found"}""",
                token = ephemeralToken
            )

        val saved = BitVoidDirectChatManager.appendMessage(
            dmId = dmId,
            senderUsername = validated.myUsername,
            rawText = caption,
            attachment = importedItem
        ) ?: return buildPaddedTextResponse(
            status = Response.Status.BAD_REQUEST,
            mimeType = "application/json; charset=utf-8",
            body = """{"error":"upload_failed"}""",
            token = ephemeralToken
        )

        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = """{"ok":true,"message":${BitVoidDirectChatManager.messageToJson(saved)}}""",
            token = ephemeralToken
        )
    }

    private fun serveDmMediaFile(
        dmId: String,
        identifier: String,
        dmToken: String?,
        requestHeaders: Map<String, String>,
        ephemeralToken: String,
        asAttachment: Boolean,
        isHead: Boolean
    ): Response {
        val validated = BitVoidDirectChatManager.validateToken(dmToken, requiredDmId = dmId)
            ?: return unauthorizedResponse(ephemeralToken)

        if (identifier.contains("..") || identifier.contains('/') || identifier.contains('\\')) {
            throw SecurityException("Path traversal attempt blocked")
        }

        BitVoidMediaManager.refreshFromDisk(context)
        val item = BitVoidMediaManager.findItemByIdOrName(identifier)
            ?: return buildPaddedTextResponse(
                status = Response.Status.NOT_FOUND,
                mimeType = "text/plain; charset=utf-8",
                body = "404 - Media Not Found",
                token = ephemeralToken
            )

        if (!BitVoidDirectChatManager.isMediaAuthorizedForToken(validated.token, dmId, item.id)) {
            return buildPaddedTextResponse(
                status = Response.Status.FORBIDDEN,
                mimeType = "text/plain; charset=utf-8",
                body = "403 - Forbidden: Media not shared in this Direct Chat",
                token = ephemeralToken
            )
        }

        return streamAuthorizedFile(
            mediaItem = item,
            requestHeaders = requestHeaders,
            token = ephemeralToken,
            asAttachment = asAttachment,
            isHead = isHead
        )
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Legacy Room API Handlers (preserved for compatibility)
    // ───────────────────────────────────────────────────────────────────────────

    private fun handleRoomUnlock(session: IHTTPSession, ephemeralToken: String): Response {
        val rawBody = readPostBody(session)
        val json = try {
            JSONObject(rawBody)
        } catch (_: Exception) {
            return buildPaddedTextResponse(
                status = Response.Status.BAD_REQUEST,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"invalid_json"}""",
                token = ephemeralToken
            )
        }

        val roomId = json.optString("roomId", "")
        val passkey = json.optString("passkey", "")
        val unlocked = BitVoidRoomManager.unlockRoom(roomId, passkey)
            ?: return buildPaddedTextResponse(
                status = Response.Status.UNAUTHORIZED,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"unauthorized","detail":"Invalid Room ID or passkey"}""",
                token = ephemeralToken
            )

        BitVoidMediaManager.refreshFromDisk(context)
        val roomVideosJson = buildRoomVideosArrayJson(unlocked.roomId)
        val historyJson = BitVoidRoomManager.getChatHistory(unlocked.roomId)
            .joinToString(",") { BitVoidRoomManager.messageToJson(it) }
        val payload =
            """{"token":"${jsonEscape(unlocked.token)}","roomId":"${jsonEscape(unlocked.roomId)}","callsign":"${jsonEscape(unlocked.callsign)}","media":[$roomVideosJson],"videos":[$roomVideosJson],"messages":[$historyJson]}"""

        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = payload,
            token = ephemeralToken
        )
    }

    private fun serveRoomVideosJson(
        roomToken: String?,
        ephemeralToken: String,
        isHead: Boolean
    ): Response {
        val validated = BitVoidRoomManager.validateToken(roomToken)
            ?: return unauthorizedResponse(ephemeralToken)

        BitVoidMediaManager.refreshFromDisk(context)
        val videosJson = buildRoomVideosArrayJson(validated.roomId)
        val payload =
            """{"roomId":"${jsonEscape(validated.roomId)}","callsign":"${jsonEscape(validated.callsign)}","media":[$videosJson],"videos":[$videosJson]}"""
        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = payload,
            token = ephemeralToken,
            isHead = isHead
        )
    }

    private fun buildRoomVideosArrayJson(roomId: String): String {
        val allowedIds = BitVoidRoomManager.getRoomVideoIds(roomId)
        val items = BitVoidMediaManager.mediaItems.value.filter { it.id in allowedIds }
        val encodedRoom = urlEncode(roomId)
        return items.joinToString(",") { item ->
            val encodedId = urlEncode(item.id)
            """{"id":"${jsonEscape(item.id)}","title":"${jsonEscape(item.title)}","size":${item.size},"mimeType":"${jsonEscape(item.mimeType)}","category":"${item.category.name}","badge":"${item.category.badge}","streamUrl":"/stream/$encodedId","roomStreamUrl":"/stream/room/$encodedRoom/$encodedId","downloadUrl":"/download/$encodedId"}"""
        }
    }

    private fun buildMediaItemsArrayJson(items: List<BitVoidMediaItem>): String {
        return items.joinToString(",") { item ->
            val encodedId = urlEncode(item.id)
            """{"id":"${jsonEscape(item.id)}","title":"${jsonEscape(item.title)}","size":${item.size},"mimeType":"${jsonEscape(item.mimeType)}","category":"${item.category.name}","badge":"${item.category.badge}","isPublic":${item.isPublic},"streamUrl":"/stream/$encodedId","downloadUrl":"/download/$encodedId"}"""
        }
    }

    private fun serveRoomChatHistory(
        roomToken: String?,
        ephemeralToken: String,
        isHead: Boolean
    ): Response {
        val validated = BitVoidRoomManager.validateToken(roomToken)
            ?: return unauthorizedResponse(ephemeralToken)

        val messages = BitVoidRoomManager.getChatHistory(validated.roomId)
            .joinToString(",") { BitVoidRoomManager.messageToJson(it) }
        val participants = BitVoidRoomManager.getParticipants(validated.roomId)
        val payload =
            """{"roomId":"${jsonEscape(validated.roomId)}","participantCount":${participants.size},"messages":[$messages]}"""
        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = payload,
            token = ephemeralToken,
            isHead = isHead
        )
    }

    private fun handleRoomChatPost(
        session: IHTTPSession,
        roomToken: String?,
        ephemeralToken: String
    ): Response {
        val validated = BitVoidRoomManager.validateToken(roomToken)
            ?: return unauthorizedResponse(ephemeralToken)

        val rawBody = readPostBody(session)
        val text = try {
            JSONObject(rawBody).optString("text", "")
        } catch (_: Exception) {
            rawBody
        }

        val saved = BitVoidRoomManager.appendChatMessage(
            roomId = validated.roomId,
            callsign = validated.callsign,
            rawText = text
        ) ?: return buildPaddedTextResponse(
            status = Response.Status.BAD_REQUEST,
            mimeType = "application/json; charset=utf-8",
            body = """{"error":"empty_message"}""",
            token = ephemeralToken
        )

        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = """{"ok":true,"message":${BitVoidRoomManager.messageToJson(saved)}}""",
            token = ephemeralToken
        )
    }

    private fun serveRoomMediaFile(
        roomId: String,
        identifier: String,
        roomToken: String?,
        requestHeaders: Map<String, String>,
        ephemeralToken: String,
        asAttachment: Boolean,
        isHead: Boolean
    ): Response {
        val validated = BitVoidRoomManager.validateToken(roomToken, requiredRoomId = roomId)
            ?: return unauthorizedResponse(ephemeralToken)

        if (identifier.contains("..") || identifier.contains('/') || identifier.contains('\\')) {
            throw SecurityException("Path traversal attempt blocked")
        }

        BitVoidMediaManager.refreshFromDisk(context)
        val item = BitVoidMediaManager.findItemByIdOrName(identifier)
            ?: return buildPaddedTextResponse(
                status = Response.Status.NOT_FOUND,
                mimeType = "text/plain; charset=utf-8",
                body = "404 - Media Not Found",
                token = ephemeralToken
            )

        if (!BitVoidRoomManager.isVideoAuthorizedForToken(validated.token, item.id)) {
            return buildPaddedTextResponse(
                status = Response.Status.FORBIDDEN,
                mimeType = "text/plain; charset=utf-8",
                body = "403 - Forbidden: Media not assigned to Room",
                token = ephemeralToken
            )
        }

        return streamAuthorizedFile(
            mediaItem = item,
            requestHeaders = requestHeaders,
            token = ephemeralToken,
            asAttachment = asAttachment,
            isHead = isHead
        )
    }

    private fun unauthorizedResponse(ephemeralToken: String): Response {
        return buildPaddedTextResponse(
            status = Response.Status.UNAUTHORIZED,
            mimeType = "application/json; charset=utf-8",
            body = """{"error":"unauthorized","detail":"Valid channel session token required"}""",
            token = ephemeralToken
        )
    }

    private fun stripIdentifyingHeaders(
        incoming: Map<String, String>
    ): Pair<Map<String, String>, Int> {
        var strippedCount = 0
        val sanitized = LinkedHashMap<String, String>(incoming.size)
        for ((key, value) in incoming) {
            val normalizedKey = key.lowercase(Locale.US)
            if (normalizedKey in IDENTIFYING_REQUEST_HEADERS ||
                normalizedKey.startsWith("x-device-") ||
                normalizedKey.startsWith("sec-ch-")
            ) {
                strippedCount++
            } else {
                sanitized[normalizedKey] = value
            }
        }
        return sanitized to strippedCount
    }

    private fun applyAnonymizationHeaders(response: Response, ephemeralToken: String) {
        response.setGzipEncoding(false)
        response.addHeader("Server", "BitVoid")
        response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, private")
        response.addHeader("Pragma", "no-cache")
        response.addHeader("Expires", "0")
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("X-BitVoid-Session", ephemeralToken)
        response.addHeader("X-BitVoid-Block-Align", CONSTANT_BLOCK_SIZE.toString())
        response.addHeader("X-BitVoid-Pad", generateRandomHeaderPadding())
    }

    private fun buildPaddedTextResponse(
        status: Response.IStatus,
        mimeType: String,
        body: String,
        token: String,
        isHead: Boolean = false
    ): Response {
        val rawBytes = body.toByteArray(StandardCharsets.UTF_8)
        val paddedBytes = padToConstantBlock(rawBytes, mimeType)
        val stream = if (isHead) {
            ByteArrayInputStream(ByteArray(0))
        } else {
            ByteArrayInputStream(paddedBytes)
        }
        val length = paddedBytes.size.toLong()
        val response = newFixedLengthResponse(status, mimeType, stream, length)
        response.setGzipEncoding(false)
        response.addHeader("Content-Length", length.toString())
        response.addHeader("X-BitVoid-Padded-Bytes", length.toString())
        response.addHeader("X-BitVoid-Session", token)
        if (!isHead) {
            totalBytesServed.addAndGet(length)
        }
        return response
    }

    private fun padToConstantBlock(raw: ByteArray, mimeType: String): ByteArray {
        val targetSize = ((raw.size + CONSTANT_BLOCK_SIZE - 1) / CONSTANT_BLOCK_SIZE)
            .coerceAtLeast(1) * CONSTANT_BLOCK_SIZE
        val delta = targetSize - raw.size
        if (delta == 0) return raw

        val paddingBytes = ByteArray(delta) { ' '.code.toByte() }

        return ByteArray(targetSize).also { out ->
            System.arraycopy(raw, 0, out, 0, raw.size)
            System.arraycopy(paddingBytes, 0, out, raw.size, paddingBytes.size)
        }
    }

    private fun servePublicVideosJson(token: String, isHead: Boolean): Response {
        val diskItems = BitVoidMediaManager.refreshFromDisk(context)
        BitVoidChannelManager.syncMediaCatalog(diskItems)
        val publicVideos = BitVoidChannelManager.getPublicChannelMediaItems()
        val entries = publicVideos.joinToString(",") { item ->
            val encodedId = urlEncode(item.id)
            """{"id":"${jsonEscape(item.id)}","title":"${jsonEscape(item.title)}","size":${item.size},"mimeType":"${jsonEscape(item.mimeType)}","category":"${item.category.name}","badge":"${item.category.badge}","isPublic":true,"streamUrl":"/stream/$encodedId","downloadUrl":"/download/$encodedId"}"""
        }
        val json = """{"session":"$token","publicCount":${publicVideos.size},"media":[$entries],"videos":[$entries]}"""
        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = json,
            token = token,
            isHead = isHead
        )
    }

    private fun serveMediaFile(
        encodedIdentifier: String,
        authToken: String?,
        requestHeaders: Map<String, String>,
        token: String,
        asAttachment: Boolean,
        isHead: Boolean
    ): Response {
        val decodedIdentifier = URLDecoder.decode(encodedIdentifier, StandardCharsets.UTF_8.name())
        val diskItems = BitVoidMediaManager.refreshFromDisk(context)
        BitVoidChannelManager.syncMediaCatalog(diskItems)

        if (decodedIdentifier.contains("..") || decodedIdentifier.contains('/') || decodedIdentifier.contains('\\')) {
            throw SecurityException("Path traversal attempt blocked")
        }

        val mediaItem = BitVoidMediaManager.findItemByIdOrName(decodedIdentifier)
        if (mediaItem == null) {
            return buildPaddedTextResponse(
                status = Response.Status.NOT_FOUND,
                mimeType = "text/plain; charset=utf-8",
                body = "404 - Media Not Found",
                token = token
            )
        }

        val isPubliclyExposed = mediaItem.isPublic || BitVoidChannelManager.isMediaInPublicChannel(mediaItem.id)
        if (!isPubliclyExposed) {
            if (!authToken.isNullOrBlank()) {
                val channelSession = BitVoidChannelManager.validateToken(authToken)
                val roomSession = if (channelSession == null) BitVoidRoomManager.validateToken(authToken) else null
                if (channelSession == null && roomSession == null) {
                    return unauthorizedResponse(token)
                }
                val authorized = (channelSession != null && BitVoidChannelManager.isMediaAuthorizedForToken(channelSession.token, mediaItem.id)) ||
                    (roomSession != null && BitVoidRoomManager.isVideoAuthorizedForToken(roomSession.token, mediaItem.id))
                if (!authorized) {
                    return buildPaddedTextResponse(
                        status = Response.Status.FORBIDDEN,
                        mimeType = "text/plain; charset=utf-8",
                        body = "403 - Forbidden: Media not assigned to your active Channel",
                        token = token
                    )
                }
            } else if (BitVoidChannelManager.isMediaInAnyPrivateChannel(mediaItem.id) ||
                BitVoidRoomManager.isVideoInAnyRoom(mediaItem.id)
            ) {
                return unauthorizedResponse(token)
            } else {
                return buildPaddedTextResponse(
                    status = Response.Status.FORBIDDEN,
                    mimeType = "text/plain; charset=utf-8",
                    body = "403 - Forbidden: Media item is Private",
                    token = token
                )
            }
        }

        return streamAuthorizedFile(
            mediaItem = mediaItem,
            requestHeaders = requestHeaders,
            token = token,
            asAttachment = asAttachment,
            isHead = isHead
        )
    }

    private fun streamAuthorizedFile(
        mediaItem: BitVoidMediaItem,
        requestHeaders: Map<String, String>,
        token: String,
        asAttachment: Boolean,
        isHead: Boolean
    ): Response {
        val baseCanonical = sharedDirectory.canonicalFile
        val targetFile = File(mediaItem.filePath).canonicalFile
        if (!targetFile.path.startsWith(baseCanonical.path + File.separator) &&
            targetFile != baseCanonical
        ) {
            throw SecurityException("Path traversal attempt blocked")
        }

        if (!targetFile.exists() || !targetFile.isFile) {
            return buildPaddedTextResponse(
                status = Response.Status.NOT_FOUND,
                mimeType = "text/plain; charset=utf-8",
                body = "404 - Media Not Found",
                token = token
            )
        }

        val totalLength = targetFile.length()
        val mimeType = mediaItem.mimeType
        val rangeHeader = requestHeaders["range"]

        if (totalLength == 0L) {
            val emptyResp = newFixedLengthResponse(
                Response.Status.OK,
                mimeType,
                ByteArrayInputStream(ByteArray(0)),
                0L
            )
            emptyResp.addHeader("Accept-Ranges", "bytes")
            emptyResp.addHeader("Content-Length", "0")
            if (asAttachment) {
                emptyResp.addHeader(
                    "Content-Disposition",
                    """attachment; filename="${sanitizeHeaderFilename(targetFile.name)}""""
                )
            }
            return emptyResp
        }

        var start = 0L
        var end = totalLength - 1
        var isPartial = false

        if (!rangeHeader.isNullOrBlank() && rangeHeader.startsWith("bytes=", ignoreCase = true)) {
            isPartial = true
            val rangeSpec = rangeHeader.substringAfter("bytes=").substringBefore(",").trim()
            val dashIndex = rangeSpec.indexOf('-')
            if (dashIndex < 0) {
                return rangeNotSatisfiableResponse(totalLength, token)
            }
            val startPart = rangeSpec.substring(0, dashIndex).trim()
            val endPart = rangeSpec.substring(dashIndex + 1).trim()

            try {
                if (startPart.isEmpty()) {
                    val suffixLength = endPart.toLong()
                    if (suffixLength <= 0L) return rangeNotSatisfiableResponse(totalLength, token)
                    start = (totalLength - suffixLength).coerceAtLeast(0L)
                    end = totalLength - 1
                } else {
                    start = startPart.toLong()
                    end = if (endPart.isEmpty()) {
                        totalLength - 1
                    } else {
                        endPart.toLong().coerceAtMost(totalLength - 1)
                    }
                }
            } catch (_: NumberFormatException) {
                return rangeNotSatisfiableResponse(totalLength, token)
            }

            if (start < 0L || start > end || start >= totalLength) {
                return rangeNotSatisfiableResponse(totalLength, token)
            }
        }

        val contentLength = (end - start) + 1
        val status = if (isPartial) Response.Status.PARTIAL_CONTENT else Response.Status.OK

        val stream: InputStream = if (isHead) {
            ByteArrayInputStream(ByteArray(0))
        } else {
            BoundedFileInputStream(targetFile, start, contentLength, STREAM_CHUNK_SIZE)
        }

        val response = newFixedLengthResponse(status, mimeType, stream, contentLength)
        response.setGzipEncoding(false)
        response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("Content-Length", contentLength.toString())
        response.addHeader("X-BitVoid-Padded-Bytes", contentLength.toString())
        response.addHeader("X-BitVoid-Stream-Chunk", STREAM_CHUNK_SIZE.toString())
        if (isPartial) {
            response.addHeader("Content-Range", "bytes $start-$end/$totalLength")
        }
        if (asAttachment) {
            response.addHeader(
                "Content-Disposition",
                """attachment; filename="${sanitizeHeaderFilename(targetFile.name)}""""
            )
        }
        if (!isHead) {
            totalBytesServed.addAndGet(contentLength)
        }
        return response
    }

    private fun rangeNotSatisfiableResponse(totalLength: Long, token: String): Response {
        val response = newFixedLengthResponse(
            Response.Status.RANGE_NOT_SATISFIABLE,
            "text/plain; charset=utf-8",
            ByteArrayInputStream(ByteArray(0)),
            0L
        )
        response.addHeader("Content-Range", "bytes */$totalLength")
        response.addHeader("X-BitVoid-Session", token)
        return response
    }

    private fun sanitizeHeaderFilename(name: String): String {
        return name.replace("\"", "_").replace("\r", "").replace("\n", "")
    }

    private fun generateEphemeralToken(): String {
        val chars = CharArray(8)
        for (i in chars.indices) {
            chars[i] = PAD_ALPHABET[secureRandom.nextInt(PAD_ALPHABET.size)]
        }
        return "BV-${String(chars)}"
    }

    private fun generateRandomHeaderPadding(): String {
        val multipliers = intArrayOf(1, 2, 3, 4)
        val length = multipliers[secureRandom.nextInt(multipliers.size)] * HEADER_PAD_STEP
        val chars = CharArray(length)
        for (i in chars.indices) {
            chars[i] = PAD_ALPHABET[secureRandom.nextInt(PAD_ALPHABET.size)]
        }
        return String(chars)
    }

    private fun connectionActivityKey(session: IHTTPSession): String {
        val remote = session.remoteIpAddress
            ?.trim()
            ?.substringBefore('%')
            ?.trim('[', ']')
            ?.removePrefix("::ffff:")
            ?.takeIf { it.isNotEmpty() }
        if (remote == null) return "local-node"
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(nodeActivitySalt)
        return digest.digest(remote.toByteArray(StandardCharsets.UTF_8))
            .take(12)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun pruneStaleNodes(now: Long) {
        val cutoff = now - NODE_ACTIVITY_TIMEOUT_MS
        val iterator = activeNodes.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) {
                iterator.remove()
            }
        }
    }

    fun connectedNodeCount(now: Long = System.currentTimeMillis()): Int {
        pruneStaleNodes(now)
        return activeNodes.size
    }

    private fun sanitizePathForAudit(uri: String): String {
        val pathOnly = uri.substringBefore('?')
        return if (pathOnly.length > 48) pathOnly.take(45) + "..." else pathOnly
    }

    private fun recordTelemetryAndAudit(
        ephemeralToken: String,
        method: String,
        sanitizedPath: String,
        statusCode: Int,
        paddedBytes: Long,
        strippedHeaders: Int
    ) {
        val reqCount = requestCounter.incrementAndGet()
        val totalServed = totalBytesServed.get()
        BitVoidNodeStateHolder.updateHostState { state ->
            state.copy(
                activeSessionsCount = activeNodes.size,
                connectedNodesCount = activeNodes.size,
                bytesServed = totalServed,
                anonymizedRequestsCount = reqCount,
                strippedHeadersTotal = state.strippedHeadersTotal + strippedHeaders,
                paddedResponsesCount = state.paddedResponsesCount + 1
            )
        }
        BitVoidNodeStateHolder.appendAuditLog(
            AuditLogEntry(
                ephemeralToken = ephemeralToken,
                timestampLabel = timeFormatter.format(Date()),
                method = method,
                sanitizedPath = sanitizedPath,
                statusCode = statusCode,
                paddedSizeBytes = paddedBytes,
                strippedHeaderCount = strippedHeaders
            )
        )
    }

    private fun urlEncode(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }

    private fun jsonEscape(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f GB", gb)
    }

    private inner class BitVoidChannelChatWebSocket(
        handshakeRequest: IHTTPSession,
        private val channelSession: ChannelSession
    ) : WebSocket(handshakeRequest), BitVoidChannelManager.ChatSocketListener {

        override fun onOpen() {
            BitVoidChannelManager.registerChatSocket(channelSession, this)
            val historyJson = BitVoidChannelManager.getChatHistory(channelSession.channelId)
                .joinToString(",") { BitVoidChannelManager.messageToJson(it) }
            val initPayload =
                """{"type":"history","channelId":"${jsonEscape(channelSession.channelId)}","callsign":"${jsonEscape(channelSession.callsign)}","messages":[$historyJson]}"""
            onBroadcastJson(initPayload)
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode,
            reason: String,
            initiatedByRemote: Boolean
        ) {
            BitVoidChannelManager.unregisterChatSocket(channelSession, this)
        }

        override fun onMessage(message: WebSocketFrame) {
            val raw = message.textPayload ?: return
            val json = runCatching { JSONObject(raw) }.getOrNull()
            val text = json?.optString("text", raw) ?: raw
            BitVoidChannelManager.appendChatMessage(
                channelId = channelSession.channelId,
                callsign = channelSession.callsign,
                rawText = text,
                stableMessageId = json?.optString("clientMessageId")?.takeIf { it.isNotBlank() },
                clientTimestampMs = json?.optLong("timestampMs", 0L)?.takeIf { it > 0L },
                sessionToken = channelSession.token
            )
        }

        override fun onPong(pong: WebSocketFrame) {
        }

        override fun onException(exception: IOException) {
            BitVoidChannelManager.unregisterChatSocket(channelSession, this)
        }

        override fun onBroadcastJson(jsonPayload: String) {
            synchronized(this) {
                try {
                    send(jsonPayload)
                } catch (_: IOException) {
                }
            }
        }

        override fun onChannelClosed() {
            try {
                close(WebSocketFrame.CloseCode.NormalClosure, "Channel closed by host", false)
            } catch (_: IOException) {
            }
        }
    }

    private inner class BitVoidRoomChatWebSocket(
        handshakeRequest: IHTTPSession,
        private val roomSession: RoomSession
    ) : WebSocket(handshakeRequest), BitVoidRoomManager.ChatSocketListener {

        override fun onOpen() {
            BitVoidRoomManager.registerChatSocket(roomSession, this)
            val historyJson = BitVoidRoomManager.getChatHistory(roomSession.roomId)
                .joinToString(",") { BitVoidRoomManager.messageToJson(it) }
            val initPayload =
                """{"type":"history","roomId":"${jsonEscape(roomSession.roomId)}","callsign":"${jsonEscape(roomSession.callsign)}","messages":[$historyJson]}"""
            onBroadcastJson(initPayload)
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode,
            reason: String,
            initiatedByRemote: Boolean
        ) {
            BitVoidRoomManager.unregisterChatSocket(roomSession, this)
        }

        override fun onMessage(message: WebSocketFrame) {
            val raw = message.textPayload ?: return
            val text = try {
                val obj = JSONObject(raw)
                obj.optString("text", raw)
            } catch (_: Exception) {
                raw
            }
            BitVoidRoomManager.appendChatMessage(
                roomId = roomSession.roomId,
                callsign = roomSession.callsign,
                rawText = text
            )
        }

        override fun onPong(pong: WebSocketFrame) {
        }

        override fun onException(exception: IOException) {
            BitVoidRoomManager.unregisterChatSocket(roomSession, this)
        }

        override fun onBroadcastJson(jsonPayload: String) {
            synchronized(this) {
                try {
                    send(jsonPayload)
                } catch (_: IOException) {
                }
            }
        }

        override fun onRoomClosed() {
            try {
                close(WebSocketFrame.CloseCode.NormalClosure, "Room closed by host", false)
            } catch (_: IOException) {
            }
        }
    }

    private inner class BitVoidDirectChatWebSocket(
        handshakeRequest: IHTTPSession,
        private val directSession: DirectSession
    ) : WebSocket(handshakeRequest), BitVoidDirectChatManager.DirectSocketListener {

        override fun onOpen() {
            BitVoidDirectChatManager.registerSocket(directSession, this)
            val historyJson = BitVoidDirectChatManager.getHistory(directSession.dmId)
                .joinToString(",") { BitVoidDirectChatManager.messageToJson(it) }
            val initPayload =
                """{"type":"history","dmId":"${jsonEscape(directSession.dmId)}","myUsername":"${jsonEscape(directSession.myUsername)}","peerUsername":"${jsonEscape(directSession.peerUsername)}","messages":[$historyJson]}"""
            onBroadcastJson(initPayload)
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode,
            reason: String,
            initiatedByRemote: Boolean
        ) {
            BitVoidDirectChatManager.unregisterSocket(directSession, this)
        }

        override fun onMessage(message: WebSocketFrame) {
            val raw = message.textPayload ?: return
            val json = runCatching { JSONObject(raw) }.getOrNull()
            val text = json?.optString("text", raw) ?: raw
            BitVoidDirectChatManager.appendMessage(
                dmId = directSession.dmId,
                senderUsername = directSession.myUsername,
                rawText = text,
                stableMessageId = json?.optString("clientMessageId")?.takeIf { it.isNotBlank() },
                clientTimestampMs = json?.optLong("timestampMs", 0L)?.takeIf { it > 0L }
            )
        }

        override fun onPong(pong: WebSocketFrame) {
        }

        override fun onException(exception: IOException) {
            BitVoidDirectChatManager.unregisterSocket(directSession, this)
        }

        override fun onBroadcastJson(jsonPayload: String) {
            synchronized(this) {
                try {
                    send(jsonPayload)
                } catch (_: IOException) {
                }
            }
        }

        override fun onSessionClosed() {
            try {
                close(WebSocketFrame.CloseCode.NormalClosure, "Direct chat closed", false)
            } catch (_: IOException) {
            }
        }
    }

    private inner class BitVoidSignalingWebSocket(
        handshakeRequest: IHTTPSession,
        private val roomId: String,
        private val channelId: String,
        private val requestedPeerId: String?,
        private val displayName: String?,
        private val mode: SignalingSessionMode,
        private val role: ParticipantRole,
        private val initialMuted: Boolean
    ) : WebSocket(handshakeRequest), BitVoidSignalingServer.SignalingSocketListener {

        private var activePeer: SignalingPeer? = null

        override fun onOpen() {
            activePeer = BitVoidSignalingServer.joinRoom(
                rawRoomId = roomId,
                rawChannelId = channelId,
                rawPeerId = requestedPeerId,
                rawDisplayName = displayName,
                mode = mode,
                role = role,
                isMuted = initialMuted,
                listener = this
            )
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode,
            reason: String,
            initiatedByRemote: Boolean
        ) {
            activePeer?.let { BitVoidSignalingServer.leaveRoom(it.roomId, it.peerId) }
        }

        override fun onMessage(message: WebSocketFrame) {
            val peer = activePeer ?: return
            val raw = message.textPayload ?: return
            BitVoidSignalingServer.handleSignalingEnvelope(peer.roomId, peer.peerId, raw)
        }

        override fun onPong(pong: WebSocketFrame) {
        }

        override fun onException(exception: IOException) {
            activePeer?.let { BitVoidSignalingServer.leaveRoom(it.roomId, it.peerId) }
        }

        override fun onSendJson(jsonPayload: String) {
            synchronized(this) {
                try {
                    send(jsonPayload)
                } catch (_: IOException) {
                }
            }
        }

        override fun onForceClose(reason: String) {
            try {
                close(WebSocketFrame.CloseCode.NormalClosure, reason, false)
            } catch (_: IOException) {
            }
        }
    }

    private class BoundedFileInputStream(
        file: File,
        startOffset: Long,
        private var remaining: Long,
        private val blockSize: Int
    ) : InputStream() {

        private val fileStream = FileInputStream(file).apply {
            channel.position(startOffset)
        }

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val b = fileStream.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val toRead = minOf(len.toLong(), remaining, blockSize.toLong()).toInt()
            val count = fileStream.read(b, off, toRead)
            if (count > 0) {
                remaining -= count
            }
            return count
        }

        override fun available(): Int {
            return minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
        }

        override fun close() {
            fileStream.close()
        }
    }
}
