package com.bitvoid.network.server

import android.content.Context
import com.bitvoid.network.call.BitVoidSignalingServer
import com.bitvoid.network.call.LiveClassMaterial
import com.bitvoid.network.call.ParticipantRole
import com.bitvoid.network.call.SignalingPeer
import com.bitvoid.network.call.SignalingSessionMode
import com.bitvoid.network.channel.BitVoidChannel
import com.bitvoid.network.channel.BitVoidChannelManager
import com.bitvoid.network.channel.ChannelSession
import com.bitvoid.network.client.BitVoidClientManager
import com.bitvoid.network.direct.BitVoidDirectChatManager
import com.bitvoid.network.direct.DirectSession
import com.bitvoid.network.media.BitVoidMediaItem
import com.bitvoid.network.media.BitVoidMediaManager
import com.bitvoid.network.model.AuditLogEntry
import com.bitvoid.network.model.MediaCategory
import com.bitvoid.network.room.BitVoidRoomManager
import com.bitvoid.network.room.RoomSession
import com.bitvoid.network.service.BitVoidNodeStateHolder
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
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

        private val CAPTIVE_PORTAL_PATHS = setOf(
            "/generate_204",
            "/gen_204",
            "/hotspot-detect.html",
            "/library/test/success.html",
            "/connecttest.txt",
            "/ncsi.txt",
            "/canonical.html",
            "/redirect",
            "/success.txt",
            "/mobile/status.php",
            "/check_network_status.txt",
            "/kindle-wifi/wifistub.html"
        )

        private val CAPTIVE_PORTAL_DOMAINS = setOf(
            "captiveportal.recaptcha.net",
            "connectivitycheck.gstatic.com",
            "connectivitycheck.android.com",
            "clients1.google.com",
            "clients3.google.com",
            "clients4.google.com",
            "www.gstatic.com",
            "captive.apple.com",
            "www.appleiphonecell.com",
            "www.ibook.info",
            "www.itools.info",
            "www.thinkdifferent.us",
            "www.msftconnecttest.com",
            "www.msftncsi.com",
            "ipv6.msftconnecttest.com",
            "detectportal.firefox.com",
            "nmcheck.gnome.org",
            "networkcheck.kde.org",
            "connectivity-check.ubuntu.com"
        )

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
    private val activeSessions = ConcurrentHashMap<String, Long>()
    private val requestCounter = AtomicInteger(0)
    private val totalBytesServed = AtomicLong(0L)
    private val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

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
            val mode = runCatching {
                SignalingSessionMode.valueOf(params["mode"].orEmpty())
            }.getOrDefault(SignalingSessionMode.VOICE_VIDEO_CALL)
            val role = runCatching {
                ParticipantRole.valueOf(params["role"].orEmpty())
            }.getOrDefault(ParticipantRole.SPEAKER)
            val peerId = params["peerId"]
            val displayName = params["name"]
            val isMuted = params["isMuted"]?.toBooleanStrictOrNull() ?: false
            val isVideoOn = params["isVideoOn"]?.toBooleanStrictOrNull() ?: false
            val isScreenSharing = params["isScreenSharing"]?.toBooleanStrictOrNull() ?: false

            return BitVoidSignalingWebSocket(
                handshakeRequest = handshake,
                roomId = roomId,
                channelId = channelId,
                requestedPeerId = peerId,
                displayName = displayName,
                mode = mode,
                role = role,
                initialMuted = isMuted,
                initialVideoOn = isVideoOn,
                initialScreenSharing = isScreenSharing
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
        activeSessions[ephemeralToken] = now
        pruneStaleSessions(now)

        // Strip all identifying HTTP headers before processing.
        val (sanitizedHeaders, strippedCount) = stripIdentifyingHeaders(session.headers)

        val rawUri = session.uri ?: "/"
        val normalizedPath = rawUri.lowercase(Locale.US)
        val method = session.method
        val authToken = extractAuthToken(session)

        val response = try {
            when {
                method == Method.OPTIONS -> {
                    buildPaddedTextResponse(
                        status = Response.Status.OK,
                        mimeType = "text/plain; charset=utf-8",
                        body = "",
                        token = ephemeralToken
                    )
                }

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

                // Live Class & Shared Presentation Materials POST endpoints
                method == Method.POST && rawUri == "/api/live-class/start" -> {
                    requireSameWifiNode(session)
                    handleLiveClassStart(session, ephemeralToken)
                }

                method == Method.POST && rawUri == "/api/live-class/join" -> {
                    requireSameWifiNode(session)
                    handleLiveClassJoin(session, ephemeralToken)
                }

                method == Method.POST && rawUri.startsWith("/api/live-class/") && rawUri.endsWith("/materials") -> {
                    requireSameWifiNode(session)
                    val roomId = extractLiveClassRoomId(rawUri, "/materials")
                    handleLiveClassMaterialUpload(roomId, session, authToken, ephemeralToken)
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

                isCaptivePortalProbe(normalizedPath, sanitizedHeaders) -> {
                    serveCaptivePortalRedirect(
                        sanitizedHeaders = sanitizedHeaders,
                        token = ephemeralToken,
                        isHead = (method == Method.HEAD)
                    )
                }

                rawUri == "/" || rawUri == "/index.html" -> {
                    servePortalPage(ephemeralToken, isHead = (method == Method.HEAD))
                }

                // Channel GET endpoints
                rawUri == "/api/channels" -> {
                    serveChannelsListJson(ephemeralToken, isHead = (method == Method.HEAD))
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

                // Direct User Chat GET endpoints
                rawUri == "/api/users" -> {
                    serveUsersListJson(ephemeralToken, isHead = (method == Method.HEAD))
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

                rawUri.startsWith("/api/live-class/") && rawUri.endsWith("/materials") -> {
                    requireSameWifiNode(session)
                    val roomId = extractLiveClassRoomId(rawUri, "/materials")
                    serveLiveClassMaterialsJson(
                        roomId = roomId,
                        authToken = authToken,
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

    private fun extractLiveClassRoomId(rawUri: String, suffix: String): String {
        val inner = rawUri.removePrefix("/api/live-class/").removeSuffix(suffix)
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

    private fun readPostBody(session: IHTTPSession): String {
        val bodyMap = HashMap<String, String>()
        session.parseBody(bodyMap)
        return bodyMap["postData"] ?: ""
    }

    /** Lightweight identity response used by silent same-Wi-Fi client discovery. */
    private fun serveNodeIdentity(ephemeralToken: String, isHead: Boolean): Response {
        val payload = JSONObject().apply {
            put("service", "bitvoid")
            put("nodeType", "gateway")
            put("protocolVersion", 1)
            put("port", DEFAULT_PORT)
            put("features", JSONArray(listOf("channels", "calls", "liveClass", "files")))
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
        response.addHeader("X-BitVoid-Node", "1")
        response.addHeader("X-BitVoid-Protocol", "1")
        response.addHeader("X-BitVoid-Session", ephemeralToken)
        if (!isHead) {
            totalBytesServed.addAndGet(payloadBytes.size.toLong())
        }
        return response
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Channel API Handlers
    // ───────────────────────────────────────────────────────────────────────────

    private fun serveChannelsListJson(ephemeralToken: String, isHead: Boolean): Response {
        val diskItems = BitVoidMediaManager.refreshFromDisk(context)
        BitVoidChannelManager.syncMediaCatalog(diskItems)

        // Hidden channels are intentionally excluded from all directory listings;
        // peers must enter the exact Private Channel ID to unlock and join them.
        val listedChannels = BitVoidChannelManager.getListedChannelSnapshots()
        val publicList = listedChannels.filter { it.isPublic }
        val privateList = listedChannels.filter { !it.isPublic }

        val publicJson = publicList.joinToString(",") { ch -> channelSummaryJson(ch, revealId = true) }
        val privateJson = privateList.joinToString(",") { ch -> channelSummaryJson(ch, revealId = false) }
        val combinedJson = listedChannels.joinToString(",") { ch -> channelSummaryJson(ch, revealId = ch.isPublic) }

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

    private fun channelSummaryJson(ch: BitVoidChannel, revealId: Boolean): String {
        val livePeers = ch.activeParticipants.count { it.isWebSocketConnected }
        return if (revealId) {
            """{"channelId":"${jsonEscape(ch.channelId)}","name":"${jsonEscape(ch.name)}","isPublic":true,"locked":false,"requiresKey":false,"hasPrivateKey":false,"mediaCount":${ch.mediaList.size},"activePeers":$livePeers,"messageCount":${ch.messageCount}}"""
        } else {
            // Strictly omit channelId, privateKey, and mediaList for locked private channels
            """{"name":"${jsonEscape(ch.name)}","isPublic":false,"locked":true,"requiresKey":true,"hasPrivateKey":${!ch.privateKey.isNullOrBlank()},"mediaCount":${ch.mediaList.size},"activePeers":$livePeers,"messageCount":${ch.messageCount}}"""
        }
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
        val joined = BitVoidChannelManager.joinChannel(channelId, privateKey, username)
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

        val payload =
            """{"token":"${jsonEscape(joined.token)}","channelId":"${jsonEscape(joined.channelId)}","name":"${jsonEscape(channel?.name ?: joined.channelId)}","isPublic":${joined.isPublic},"callsign":"${jsonEscape(joined.callsign)}","media":[$mediaJson],"videos":[$mediaJson],"messages":[$historyJson]}"""

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
        BitVoidDirectChatManager.validateToken(authToken, requiredDmId = dmId)
            ?: return unauthorizedResponse(ephemeralToken)
        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = BitVoidSignalingServer.roomStatusToJson(dmId),
            token = ephemeralToken,
            isHead = isHead
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
            put("activeMaterialId", "")
            put("activePageIndex", 0)
            put("media", JSONArray())
            put("videos", JSONArray())
            put("messages", JSONArray())
            put("materials", JSONArray())
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

        val diskItems = BitVoidMediaManager.refreshFromDisk(context)
        BitVoidChannelManager.syncMediaCatalog(diskItems)
        val channel = BitVoidChannelManager.getChannel(studentSession.channelId)
        val mediaJson = buildMediaItemsArrayJson(BitVoidChannelManager.getChannelMedia(studentSession.channelId))
        val historyJson = BitVoidChannelManager.getChatHistory(studentSession.channelId)
            .joinToString(",") { BitVoidChannelManager.messageToJson(it) }
        val roomState = BitVoidSignalingServer.getRoomState(invite.roomId)
        val materialsJson = BitVoidSignalingServer.materialsToJsonArray(roomState?.materials.orEmpty()).toString()

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
            put("activeMaterialId", roomState?.activeMaterialId.orEmpty())
            put("activePageIndex", roomState?.activePageIndex ?: 0)
        }.toString().dropLast(1) +
            ""","media":[$mediaJson],"videos":[$mediaJson],"messages":[$historyJson],"materials":$materialsJson}"""

        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = payload,
            token = ephemeralToken
        )
    }

    private fun handleLiveClassMaterialUpload(
        roomId: String,
        session: IHTTPSession,
        authToken: String?,
        ephemeralToken: String
    ): Response {
        val cleanRoom = roomId.trim()
        if (cleanRoom.isEmpty()) {
            return buildPaddedTextResponse(
                status = Response.Status.BAD_REQUEST,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"invalid_room"}""",
                token = ephemeralToken
            )
        }

        val isDm = cleanRoom.startsWith("dm__", ignoreCase = false)
        var uploaderName = "Presenter"
        var targetChannelId: String? = null

        if (isDm) {
            val dmValidated = BitVoidDirectChatManager.validateToken(authToken, requiredDmId = cleanRoom)
                ?: return unauthorizedResponse(ephemeralToken)
            uploaderName = dmValidated.myUsername
        } else {
            val channel = BitVoidChannelManager.getChannel(cleanRoom)
                ?: BitVoidSignalingServer.getRoomState(cleanRoom)?.let { BitVoidChannelManager.getChannel(it.channelId) }
                ?: return buildPaddedTextResponse(
                    status = Response.Status.NOT_FOUND,
                    mimeType = "application/json; charset=utf-8",
                    body = """{"error":"channel_not_found"}""",
                    token = ephemeralToken
                )
            val validated = BitVoidChannelManager.validateToken(authToken, requiredChannelId = channel.channelId)
            if (!channel.isPublic && validated == null) {
                return unauthorizedResponse(ephemeralToken)
            }
            targetChannelId = channel.channelId
            uploaderName = validated?.callsign ?: "Host-${ephemeralToken.takeLast(4).uppercase(Locale.US)}"
        }

        val (importedItem, _) = parseAndImportUploadedFile(session)
            ?: return buildPaddedTextResponse(
                status = Response.Status.BAD_REQUEST,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"missing_file","detail":"Select a document, PPT, PDF, or file to share."}""",
                token = ephemeralToken
            )

        val encodedMediaId = urlEncode(importedItem.id)
        val streamUrl: String
        val downloadUrl: String
        if (isDm) {
            val encodedDm = urlEncode(cleanRoom)
            streamUrl = "/stream/dm/$encodedDm/$encodedMediaId"
            downloadUrl = "/download/dm/$encodedDm/$encodedMediaId"
            BitVoidDirectChatManager.appendMessage(
                dmId = cleanRoom,
                senderUsername = uploaderName,
                rawText = "Shared document in call: ${importedItem.title}",
                attachment = importedItem
            )
        } else {
            val chId = targetChannelId!!
            streamUrl = "/stream/$encodedMediaId"
            downloadUrl = "/download/$encodedMediaId"
            BitVoidChannelManager.addMediaToChannel(chId, importedItem.id)
            BitVoidChannelManager.appendChatMessage(
                channelId = chId,
                callsign = uploaderName,
                rawText = "Presented Live Class document: ${importedItem.title}",
                attachment = importedItem
            )
        }

        val material = LiveClassMaterial(
            id = importedItem.id,
            fileName = importedItem.title,
            mimeType = importedItem.mimeType,
            sizeBytes = importedItem.size,
            uploadedBy = uploaderName,
            downloadUrl = downloadUrl,
            streamUrl = streamUrl,
            uploadedAtMs = System.currentTimeMillis()
        )
        BitVoidSignalingServer.addMaterialToRoom(cleanRoom, material, setAsActive = true)
        val roomState = BitVoidSignalingServer.getRoomState(cleanRoom)
        val body = JSONObject().apply {
            put("ok", true)
            put("roomId", cleanRoom)
            put("material", BitVoidSignalingServer.materialToJson(material))
            put("activeMaterialId", roomState?.activeMaterialId ?: material.id)
            put("activePageIndex", roomState?.activePageIndex ?: 0)
            put("materials", BitVoidSignalingServer.materialsToJsonArray(roomState?.materials ?: listOf(material)))
        }.toString()

        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = body,
            token = ephemeralToken
        )
    }

    private fun serveLiveClassMaterialsJson(
        roomId: String,
        authToken: String?,
        ephemeralToken: String,
        isHead: Boolean
    ): Response {
        val cleanRoom = roomId.trim()
        if (cleanRoom.startsWith("dm__", ignoreCase = false)) {
            BitVoidDirectChatManager.validateToken(authToken, requiredDmId = cleanRoom)
                ?: return unauthorizedResponse(ephemeralToken)
        } else {
            val channel = BitVoidChannelManager.getChannel(cleanRoom)
                ?: BitVoidSignalingServer.getRoomState(cleanRoom)?.let { BitVoidChannelManager.getChannel(it.channelId) }
                ?: return buildPaddedTextResponse(
                    status = Response.Status.NOT_FOUND,
                    mimeType = "application/json; charset=utf-8",
                    body = """{"error":"channel_not_found"}""",
                    token = ephemeralToken
                )
            if (!channel.isPublic) {
                BitVoidChannelManager.validateToken(authToken, requiredChannelId = channel.channelId)
                    ?: return unauthorizedResponse(ephemeralToken)
            }
        }
        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = BitVoidSignalingServer.roomMaterialsToJson(cleanRoom),
            token = ephemeralToken,
            isHead = isHead
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

        val validated = BitVoidChannelManager.validateToken(authToken, requiredChannelId = channelId)
        if (!channel.isPublic && validated == null) {
            return unauthorizedResponse(ephemeralToken)
        }

        val rawBody = readPostBody(session)
        val text = try {
            JSONObject(rawBody).optString("text", "")
        } catch (_: Exception) {
            rawBody
        }

        val callsign = validated?.callsign ?: "Anon-${ephemeralToken.takeLast(4).uppercase(Locale.US)}"
        val saved = BitVoidChannelManager.appendChatMessage(
            channelId = channelId,
            callsign = callsign,
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
        if (!channel.isPublic && validated == null) {
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
        val res = BitVoidDirectChatManager.registerUser(username)
        if (res.isFailure) {
            return buildPaddedTextResponse(
                status = Response.Status.BAD_REQUEST,
                mimeType = "application/json; charset=utf-8",
                body = """{"error":"invalid_username","detail":"${jsonEscape(res.exceptionOrNull()?.message ?: "Invalid username")}"}""",
                token = ephemeralToken
            )
        }
        val clean = res.getOrThrow()
        val usersJson = BitVoidDirectChatManager.getKnownUsers()
            .joinToString(",") { """"${jsonEscape(it)}"""" }
        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "application/json; charset=utf-8",
            body = """{"ok":true,"username":"${jsonEscape(clean)}","users":[$usersJson]}""",
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

    private fun handleDirectOpen(session: IHTTPSession, ephemeralToken: String): Response {
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
        val text = try {
            JSONObject(rawBody).optString("text", "")
        } catch (_: Exception) {
            rawBody
        }
        val saved = BitVoidDirectChatManager.appendMessage(
            dmId = dmId,
            senderUsername = validated.myUsername,
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

    private fun isCaptivePortalProbe(
        normalizedPath: String,
        headers: Map<String, String>
    ): Boolean {
        if (normalizedPath in CAPTIVE_PORTAL_PATHS ||
            normalizedPath.endsWith("/generate_204") ||
            normalizedPath.endsWith("/gen_204") ||
            normalizedPath.endsWith("/ncsi.txt") ||
            normalizedPath.endsWith("/connecttest.txt") ||
            normalizedPath.endsWith("/hotspot-detect.html")
        ) {
            return true
        }
        val rawHost = headers["host"]?.trim().orEmpty()
        val hostOnly = rawHost.substringBefore(':').lowercase(Locale.US)
        if (hostOnly in CAPTIVE_PORTAL_DOMAINS) {
            return true
        }
        if (hostOnly == "www.apple.com" &&
            (normalizedPath.contains("success.html") || normalizedPath.contains("hotspot-detect"))
        ) {
            return true
        }
        return false
    }

    private fun isLocalIpHost(hostOnly: String): Boolean {
        if (hostOnly == "127.0.0.1" || hostOnly == "localhost") return true
        if (hostOnly.startsWith("192.168.") || hostOnly.startsWith("10.")) return true
        if (hostOnly.startsWith("172.")) {
            val secondOctet = hostOnly.removePrefix("172.").substringBefore('.').toIntOrNull()
            if (secondOctet != null && secondOctet in 16..31) return true
        }
        return false
    }

    private fun serveCaptivePortalRedirect(
        sanitizedHeaders: Map<String, String>,
        token: String,
        isHead: Boolean = false
    ): Response {
        val hostState = BitVoidNodeStateHolder.hostState.value
        val nonLoopbackEndpoint = hostState.boundEndpoints
            .firstOrNull { url -> !url.contains("127.0.0.1") && !url.contains("localhost") }
            ?.removePrefix("http://")
            ?.substringBefore('/')
        val activeGatewayHost = when {
            hostState.gatewayIp.isNotBlank() &&
                hostState.gatewayIp != "127.0.0.1" &&
                hostState.gatewayIp != "localhost" -> {
                "${hostState.gatewayIp}:${hostState.port}"
            }
            !nonLoopbackEndpoint.isNullOrBlank() -> nonLoopbackEndpoint
            else -> "192.168.43.1:$DEFAULT_PORT"
        }

        val rawHostHeader = sanitizedHeaders["host"]?.trim().orEmpty()
        val hostOnly = rawHostHeader.substringBefore(':').lowercase(Locale.US)
        val useDirectLocalHost = rawHostHeader.isNotBlank() &&
            hostOnly !in CAPTIVE_PORTAL_DOMAINS &&
            isLocalIpHost(hostOnly)

        val resolvedAuthority = if (useDirectLocalHost) {
            if (rawHostHeader.contains(':')) rawHostHeader else "$rawHostHeader:$listeningPort"
        } else {
            activeGatewayHost
        }
        val portalTarget = "http://$resolvedAuthority/"

        val redirectHtml = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta charset="utf-8">
              <meta http-equiv="refresh" content="0;url=$portalTarget">
              <title>BitVoid Captive Portal</title>
            </head>
            <body style="background:#0D1117;color:#00FF9C;font-family:monospace;padding:24px;">
              <p>BitVoid Open Network Captive Portal — Redirecting to BitVoid Channels...</p>
              <p><a href="$portalTarget" style="color:#00FF9C;">Open BitVoid Portal ($portalTarget)</a></p>
            </body>
            </html>
        """.trimIndent()

        val status302 = object : Response.IStatus {
            override fun getDescription(): String = "302 Found"
            override fun getRequestStatus(): Int = 302
        }
        val bytes = padToConstantBlock(redirectHtml.toByteArray(StandardCharsets.UTF_8), "text/html")
        val stream = if (isHead) {
            ByteArrayInputStream(ByteArray(0))
        } else {
            ByteArrayInputStream(bytes)
        }
        val response = newFixedLengthResponse(
            status302,
            "text/html; charset=utf-8",
            stream,
            bytes.size.toLong()
        )
        response.setGzipEncoding(false)
        response.addHeader("Content-Length", bytes.size.toString())
        response.addHeader("Location", portalTarget)
        response.addHeader("X-BitVoid-Captive-Portal", "1")
        response.addHeader("X-BitVoid-Padded-Bytes", bytes.size.toString())
        response.addHeader("X-BitVoid-Session", token)
        if (!isHead) {
            totalBytesServed.addAndGet(bytes.size.toLong())
        }
        return response
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
        response.addHeader("Referrer-Policy", "no-referrer")
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("X-Frame-Options", "SAMEORIGIN")
        response.addHeader(
            "Content-Security-Policy",
            "default-src 'self' 'unsafe-inline'; connect-src 'self' ws: wss:; media-src 'self' blob: mediastream:; img-src 'self'; object-src 'none'; frame-ancestors 'none';"
        )
        response.addHeader(
            "Permissions-Policy",
            "interest-cohort=(), camera=(self), microphone=(self), display-capture=(self), geolocation=(), usb=()"
        )
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

        val isHtml = mimeType.contains("text/html", ignoreCase = true)
        val paddingBytes = if (isHtml && delta >= 9) {
            val innerLen = delta - 9
            val sb = StringBuilder(delta)
            sb.append("<!-- ")
            repeat(innerLen) { sb.append('0') }
            sb.append(" -->")
            sb.toString().toByteArray(StandardCharsets.UTF_8)
        } else {
            ByteArray(delta) { ' '.code.toByte() }
        }

        return ByteArray(targetSize).also { out ->
            System.arraycopy(raw, 0, out, 0, raw.size)
            System.arraycopy(paddingBytes, 0, out, raw.size, paddingBytes.size)
        }
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Responsive Channel-Based Web Portal (/)
    // ───────────────────────────────────────────────────────────────────────────

    private fun servePortalPage(token: String, isHead: Boolean): Response {
        val diskItems = BitVoidMediaManager.refreshFromDisk(context)
        BitVoidChannelManager.syncMediaCatalog(diskItems)

        val publicItems = BitVoidChannelManager.getPublicChannelMediaItems()
        val allPublicChannels = BitVoidChannelManager.getPublicChannels()
        val generalChannels = allPublicChannels.filter {
            it.channelId == BitVoidChannelManager.DEFAULT_PUBLIC_CHANNEL_ID
        }
        val otherPublicChannels = allPublicChannels.filter {
            it.channelId != BitVoidChannelManager.DEFAULT_PUBLIC_CHANNEL_ID
        }
        val privateChannels = BitVoidChannelManager.getListedChannelSnapshots().filter { !it.isPublic }
        val hostState = BitVoidNodeStateHolder.hostState.value
        val gatewayLabel = if (hostState.gatewayIp.isNotBlank()) {
            "${hostState.gatewayIp}:${hostState.port}"
        } else {
            "192.168.43.1:${hostState.port}"
        }

        val publicFeedHtml = if (publicItems.isEmpty()) {
            """
            <div class="empty">
              <div class="empty-title">NO PUBLIC STREAMS YET</div>
              <p>Select a channel or unlock a Private Channel ID to stream &amp; chat.</p>
            </div>
            """.trimIndent()
        } else {
            publicItems.joinToString("\n") { renderMediaCardHtml(it) }
        }

        val initialGeneralHtml = if (generalChannels.isEmpty()) {
            """<button type="button" class="wa-chan-row" data-channel-id="${BitVoidChannelManager.DEFAULT_PUBLIC_CHANNEL_ID}"><div class="wa-avatar">#</div><div class="wa-chan-info"><div class="wa-chan-title">General Chat</div><div class="wa-chan-sub">Open local mesh room</div></div></button>"""
        } else {
            generalChannels.joinToString("\n") { ch ->
                """
                <button type="button" class="wa-chan-row" data-channel-id="${htmlEscape(ch.channelId)}">
                  <div class="wa-avatar">#</div>
                  <div class="wa-chan-info">
                    <div class="wa-chan-title">${htmlEscape(ch.name)}</div>
                    <div class="wa-chan-sub">${ch.mediaList.size} media · ${ch.messageCount} msgs</div>
                  </div>
                </button>
                """.trimIndent()
            }
        }

        val initialPublicChannelsHtml = if (otherPublicChannels.isEmpty()) {
            """<div class="muted-note">No extra public channels yet.</div>"""
        } else {
            otherPublicChannels.joinToString("\n") { ch ->
                """
                <button type="button" class="wa-chan-row" data-channel-id="${htmlEscape(ch.channelId)}">
                  <div class="wa-avatar">#</div>
                  <div class="wa-chan-info">
                    <div class="wa-chan-title">${htmlEscape(ch.name)}</div>
                    <div class="wa-chan-sub">${ch.mediaList.size} media · ${ch.messageCount} msgs</div>
                  </div>
                </button>
                """.trimIndent()
            }
        }

        val initialPrivateChannelsHtml = if (privateChannels.isEmpty()) {
            """<div class="muted-note">No private channels active.</div>"""
        } else {
            privateChannels.joinToString("\n") { ch ->
                """
                <button type="button" class="wa-chan-row wa-chan-locked" data-locked-name="${htmlEscape(ch.name)}">
                  <div class="wa-avatar wa-avatar-lock">ID</div>
                  <div class="wa-chan-info">
                    <div class="wa-chan-title">${htmlEscape(ch.name)}</div>
                    <div class="wa-chan-sub">Private · Tap &amp; enter ID (${ch.mediaList.size} media)</div>
                  </div>
                </button>
                """.trimIndent()
            }
        }

        val html = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
          <meta name="referrer" content="no-referrer">
          <title>BitVoid // Channel Chat Portal</title>
          <style>
            :root {
              --bg: #0D1117;
              --chat-bg: #0A0E14;
              --surface: #161B22;
              --elevated: #1C232D;
              --bubble-in: #161B22;
              --bubble-out: #0C3325;
              --border: #283240;
              --mint: #00FF9C;
              --cyan: #00D1FF;
              --amber: #FFB020;
              --crimson: #FF4D6D;
              --text: #E6EDF3;
              --muted: #8B949E;
            }
            * { box-sizing: border-box; margin: 0; padding: 0; }
            html, body {
              height: 100%;
              background: var(--bg);
              color: var(--text);
              font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
              line-height: 1.45;
              overflow: hidden;
            }
            .hidden { display: none !important; }
            .wa-app {
              position: relative;
              width: 100%;
              height: 100%;
              height: 100dvh;
              max-width: 1440px;
              margin: 0 auto;
              display: flex;
              flex-direction: column;
              background: var(--chat-bg);
              border-left: 1px solid var(--border);
              border-right: 1px solid var(--border);
              overflow: hidden;
            }
            .wa-main-pane {
              flex: 1;
              min-width: 0;
              min-height: 0;
              display: flex;
              flex-direction: column;
              height: 100%;
            }
            /* Top WhatsApp Channel Bar */
            .wa-topbar {
              flex-shrink: 0;
              display: flex;
              align-items: center;
              gap: 10px;
              padding: 10px 12px;
              background: var(--surface);
              border-bottom: 1px solid var(--border);
              z-index: 15;
            }
            .hamburger-btn {
              flex-shrink: 0;
              width: 42px;
              height: 42px;
              border-radius: 10px;
              border: 1px solid var(--border);
              background: var(--bg);
              display: flex;
              flex-direction: column;
              align-items: center;
              justify-content: center;
              gap: 4px;
              cursor: pointer;
              padding: 0;
            }
            .hamburger-btn:hover, .hamburger-btn:active {
              border-color: var(--mint);
              background: rgba(0, 255, 156, 0.08);
            }
            .hamburger-line {
              width: 18px;
              height: 2px;
              background: var(--mint);
              border-radius: 2px;
              display: block;
            }
            .wa-top-avatar {
              flex-shrink: 0;
              width: 38px;
              height: 38px;
              border-radius: 50%;
              background: rgba(0, 255, 156, 0.14);
              border: 1px solid var(--mint);
              color: var(--mint);
              font-weight: 700;
              font-size: 16px;
              display: flex;
              align-items: center;
              justify-content: center;
            }
            .wa-top-avatar.private-mode {
              background: rgba(255, 176, 32, 0.14);
              border-color: var(--amber);
              color: var(--amber);
              font-size: 11px;
            }
            .wa-top-info {
              min-width: 0;
              flex: 1;
            }
            .wa-top-title-row {
              display: flex;
              align-items: center;
              gap: 8px;
              flex-wrap: wrap;
            }
            .wa-top-title {
              font-size: 15px;
              font-weight: 700;
              color: var(--text);
              white-space: nowrap;
              overflow: hidden;
              text-overflow: ellipsis;
            }
            .pill {
              font-size: 10px;
              font-weight: 700;
              padding: 2px 8px;
              border-radius: 999px;
              border: 1px solid var(--mint);
              color: var(--mint);
              background: rgba(0, 255, 156, 0.08);
            }
            .pill-amber {
              border-color: var(--amber);
              color: var(--amber);
              background: rgba(255, 176, 32, 0.12);
            }
            .wa-top-sub {
              font-size: 11px;
              color: var(--muted);
              white-space: nowrap;
              overflow: hidden;
              text-overflow: ellipsis;
              margin-top: 2px;
            }
            .wa-top-sub strong { color: var(--cyan); }
            .top-actions {
              flex-shrink: 0;
              display: flex;
              align-items: center;
              gap: 6px;
            }
            button, .btn {
              display: inline-flex;
              align-items: center;
              justify-content: center;
              text-decoration: none;
              border: none;
              cursor: pointer;
              font-family: inherit;
              font-size: 11px;
              font-weight: 700;
              padding: 8px 11px;
              border-radius: 8px;
              letter-spacing: 0.4px;
            }
            .btn-primary { background: var(--mint); color: var(--bg); }
            .btn-cyan { background: var(--cyan); color: var(--bg); }
            .btn-ghost { border: 1px solid var(--border); color: var(--text); background: var(--bg); }
            .btn-ghost.active { border-color: var(--mint); color: var(--mint); background: rgba(0, 255, 156, 0.08); }
            .btn-danger { border: 1px solid var(--crimson); color: var(--crimson); background: transparent; }

            /* Slide-out Sidebar Drawer */
            .sidebar-backdrop {
              position: fixed;
              inset: 0;
              background: rgba(5, 8, 12, 0.72);
              z-index: 40;
            }
            aside#channels-sidebar {
              position: fixed;
              top: 0;
              left: 0;
              bottom: 0;
              width: min(320px, 86vw);
              background: var(--surface);
              border-right: 1px solid var(--border);
              z-index: 50;
              transform: translateX(-104%);
              transition: transform 0.2s ease;
              display: flex;
              flex-direction: column;
              overflow-y: auto;
              padding: 14px;
              gap: 12px;
            }
            aside#channels-sidebar.open {
              transform: translateX(0);
            }
            .drawer-header {
              display: flex;
              align-items: center;
              justify-content: space-between;
              padding-bottom: 10px;
              border-bottom: 1px solid var(--border);
            }
            .drawer-brand {
              font-size: 14px;
              font-weight: 700;
              color: var(--mint);
              letter-spacing: 0.7px;
            }
            .drawer-meta-card {
              background: var(--bg);
              border: 1px solid var(--border);
              border-radius: 10px;
              padding: 10px;
              font-size: 11px;
              color: var(--muted);
              display: grid;
              gap: 3px;
            }
            .drawer-meta-card strong { color: var(--cyan); }
            .section-title {
              font-size: 11px;
              font-weight: 700;
              color: var(--cyan);
              letter-spacing: 0.7px;
              margin-top: 2px;
            }
            .sidebar-list {
              display: grid;
              gap: 6px;
            }
            .wa-chan-row {
              width: 100%;
              justify-content: flex-start;
              text-align: left;
              gap: 10px;
              padding: 9px 10px;
              border-radius: 10px;
              background: var(--bg);
              border: 1px solid var(--border);
              color: var(--text);
            }
            .wa-chan-row:hover, .wa-chan-row.active {
              border-color: var(--mint);
              background: rgba(0, 255, 156, 0.08);
            }
            .wa-chan-locked {
              border-style: dashed;
              border-color: rgba(255, 176, 32, 0.65);
              background: rgba(255, 176, 32, 0.05);
            }
            .wa-avatar {
              flex-shrink: 0;
              width: 34px;
              height: 34px;
              border-radius: 50%;
              background: rgba(0, 255, 156, 0.14);
              border: 1px solid rgba(0, 255, 156, 0.55);
              color: var(--mint);
              font-weight: 700;
              font-size: 14px;
              display: flex;
              align-items: center;
              justify-content: center;
            }
            .wa-avatar-lock {
              background: rgba(255, 176, 32, 0.14);
              border-color: rgba(255, 176, 32, 0.7);
              color: var(--amber);
              font-size: 10px;
            }
            .wa-chan-info { min-width: 0; flex: 1; }
            .wa-chan-title {
              font-size: 13px;
              font-weight: 700;
              color: var(--text);
              white-space: nowrap;
              overflow: hidden;
              text-overflow: ellipsis;
            }
            .wa-chan-sub {
              font-size: 11px;
              color: var(--muted);
              margin-top: 2px;
            }
            .muted-note { font-size: 11px; color: var(--muted); padding: 4px 2px; }
            .unlock-box {
              display: grid;
              gap: 8px;
              background: var(--bg);
              border: 1px solid var(--border);
              border-radius: 10px;
              padding: 10px;
            }
            input {
              width: 100%;
              background: var(--surface);
              color: var(--text);
              border: 1px solid var(--border);
              border-radius: 8px;
              padding: 10px 12px;
              font-family: inherit;
              font-size: 13px;
            }
            input:focus { outline: none; border-color: var(--mint); }
            .err { color: var(--crimson); font-size: 11px; }

            /* Collapsible Channel Media Drawer */
            .media-drawer {
              flex-shrink: 0;
              max-height: 48vh;
              overflow-y: auto;
              background: var(--surface);
              border-bottom: 1px solid var(--border);
              padding: 12px;
              display: grid;
              gap: 12px;
            }
            .grid { display: grid; gap: 10px; }
            .card {
              background: var(--elevated);
              border: 1px solid var(--border);
              border-radius: 10px;
              padding: 12px;
            }
            .card-head { display: flex; align-items: flex-start; gap: 10px; margin-bottom: 8px; }
            .badge {
              flex-shrink: 0;
              font-size: 10px;
              font-weight: 700;
              color: var(--bg);
              background: var(--mint);
              padding: 3px 7px;
              border-radius: 6px;
            }
            .meta { min-width: 0; flex: 1; }
            .filename { font-size: 13px; font-weight: 700; word-break: break-word; }
            .sub { font-size: 11px; color: var(--muted); margin-top: 2px; }
            video, audio {
              width: 100%;
              border-radius: 8px;
              margin-bottom: 8px;
              background: #05070A;
              border: 1px solid var(--border);
            }
            .preview-img {
              width: 100%;
              max-height: 240px;
              object-fit: contain;
              border-radius: 8px;
              margin-bottom: 8px;
              background: #05070A;
              border: 1px solid var(--border);
            }
            .actions { display: flex; flex-wrap: wrap; gap: 8px; }

            /* WhatsApp Channel Chat Viewport */
            #active-channel-panel {
              flex: 1;
              min-height: 0;
              display: flex;
              flex-direction: column;
            }
            .wa-chat-scroll {
              flex: 1;
              min-height: 0;
              overflow-y: auto;
              padding: 14px 12px;
              display: flex;
              flex-direction: column;
              gap: 8px;
              background-color: var(--chat-bg);
              background-image: radial-gradient(rgba(0, 255, 156, 0.06) 1px, transparent 1px);
              background-size: 20px 20px;
            }
            .wa-welcome-pill {
              align-self: center;
              text-align: center;
              max-width: 90%;
              background: var(--surface);
              border: 1px solid var(--border);
              color: var(--muted);
              font-size: 11px;
              padding: 8px 14px;
              border-radius: 12px;
              margin-bottom: 6px;
            }
            .wa-welcome-pill strong { color: var(--mint); }
            .msg-sys {
              align-self: center;
              background: rgba(0, 209, 255, 0.1);
              border: 1px solid rgba(0, 209, 255, 0.35);
              color: var(--cyan);
              font-size: 11px;
              padding: 4px 10px;
              border-radius: 999px;
              max-width: 92%;
              text-align: center;
            }
            .msg-row {
              display: flex;
              width: 100%;
            }
            .msg-row.out { justify-content: flex-end; }
            .msg-row.in { justify-content: flex-start; }
            .bubble {
              max-width: 82%;
              padding: 8px 12px 6px;
              border-radius: 14px;
              word-break: break-word;
              font-size: 13px;
            }
            .msg-row.in .bubble {
              background: var(--bubble-in);
              border: 1px solid var(--border);
              border-top-left-radius: 4px;
            }
            .msg-row.out .bubble {
              background: var(--bubble-out);
              border: 1px solid rgba(0, 255, 156, 0.35);
              border-top-right-radius: 4px;
            }
            .msg-sender {
              font-size: 11px;
              font-weight: 700;
              color: var(--cyan);
              margin-bottom: 2px;
            }
            .msg-text {
              color: var(--text);
              white-space: pre-wrap;
            }
            .msg-foot {
              display: flex;
              justify-content: flex-end;
              align-items: center;
              gap: 4px;
              margin-top: 3px;
              font-size: 10px;
              color: var(--muted);
            }
            .msg-ticks { color: var(--mint); font-weight: 700; }

            /* Sticky Bottom Composer */
            .wa-composer {
              flex-shrink: 0;
              display: flex;
              align-items: center;
              gap: 8px;
              padding: 10px 12px max(10px, env(safe-area-inset-bottom));
              background: var(--surface);
              border-top: 1px solid var(--border);
            }
            .wa-composer input[type="text"] {
              flex: 1;
              min-width: 0;
              border-radius: 999px;
              background: var(--bg);
              padding: 11px 16px;
              font-size: 13px;
            }
            .attach-circle {
              flex-shrink: 0;
              width: 44px;
              height: 44px;
              border-radius: 50%;
              background: var(--bg);
              border: 1px solid var(--mint);
              color: var(--mint);
              font-size: 22px;
              font-weight: 700;
              padding: 0;
              line-height: 1;
            }
            .attach-circle:hover { background: rgba(0, 255, 156, 0.12); }
            .attach-circle:disabled { opacity: 0.5; cursor: wait; }
            .send-circle {
              flex-shrink: 0;
              width: 44px;
              height: 44px;
              border-radius: 50%;
              background: var(--mint);
              color: var(--bg);
              font-size: 16px;
              font-weight: 700;
              padding: 0;
            }
            .send-circle:hover { filter: brightness(1.08); }
            .msg-attachment {
              margin-top: 4px;
              margin-bottom: 4px;
              padding: 8px 10px;
              border-radius: 10px;
              background: rgba(13, 17, 23, 0.72);
              border: 1px solid var(--border);
              display: grid;
              gap: 6px;
            }
            .msg-att-head {
              display: flex;
              align-items: center;
              gap: 8px;
            }
            .msg-att-name {
              font-size: 12px;
              font-weight: 700;
              color: var(--text);
              word-break: break-all;
            }
            .msg-att-sub {
              font-size: 10px;
              color: var(--muted);
            }
            .msg-att-img {
              width: 100%;
              max-height: 200px;
              object-fit: contain;
              border-radius: 6px;
              background: #05070A;
            }
            .msg-att-actions {
              display: flex;
              gap: 6px;
              flex-wrap: wrap;
            }
            .empty {
              border: 1px dashed var(--border);
              border-radius: 10px;
              padding: 16px 12px;
              text-align: center;
              color: var(--muted);
              font-size: 12px;
            }
            .empty-title { color: var(--mint); font-weight: 700; margin-bottom: 4px; }

            /* Live Class & Voice/Video Call Stage */
            .live-stage-panel {
              flex-shrink: 0;
              max-height: 52vh;
              overflow-y: auto;
              background: #0F151D;
              border-bottom: 1px solid var(--border);
              padding: 10px 12px;
              display: grid;
              gap: 10px;
            }
            .live-head {
              display: flex;
              flex-wrap: wrap;
              align-items: center;
              justify-content: space-between;
              gap: 8px;
            }
            .live-badge-pulse {
              font-size: 10px;
              font-weight: 700;
              padding: 3px 9px;
              border-radius: 999px;
              border: 1px solid var(--crimson);
              color: var(--crimson);
              background: rgba(255, 77, 109, 0.14);
            }
            .live-video-grid {
              display: grid;
              grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
              gap: 8px;
            }
            .live-video-tile {
              position: relative;
              background: #05070A;
              border: 1px solid var(--border);
              border-radius: 10px;
              overflow: hidden;
              min-height: 132px;
              display: flex;
              align-items: center;
              justify-content: center;
            }
            .live-video-tile.teacher-tile {
              border-color: var(--mint);
              grid-column: 1 / -1;
              min-height: 190px;
            }
            .live-video-tile video {
              width: 100%;
              height: 100%;
              max-height: 260px;
              object-fit: contain;
              margin: 0;
              border: none;
              border-radius: 0;
            }
            .live-tile-label {
              position: absolute;
              left: 8px;
              bottom: 8px;
              background: rgba(13, 17, 23, 0.85);
              border: 1px solid var(--border);
              color: var(--text);
              font-size: 10px;
              font-weight: 700;
              padding: 2px 7px;
              border-radius: 6px;
            }
            .live-participants-grid {
              display: flex;
              flex-wrap: wrap;
              gap: 6px;
            }
            .live-participant-chip {
              display: inline-flex;
              align-items: center;
              gap: 6px;
              padding: 4px 9px;
              border-radius: 999px;
              background: var(--surface);
              border: 1px solid var(--border);
              font-size: 11px;
              color: var(--text);
            }
            .live-participant-chip.teacher {
              border-color: var(--mint);
              color: var(--mint);
            }
            .live-controls-bar {
              display: flex;
              flex-wrap: wrap;
              gap: 6px;
              align-items: center;
            }
            .btn-amber { background: var(--amber); color: var(--bg); }
            .live-class-code-pill {
              display: inline-flex;
              align-items: center;
              gap: 6px;
              padding: 4px 10px;
              border-radius: 999px;
              border: 1px solid var(--mint);
              background: rgba(0, 255, 156, 0.14);
              color: var(--mint);
              font-size: 11px;
              font-weight: 700;
            }
            .live-join-id-row {
              display: flex;
              flex-wrap: wrap;
              gap: 6px;
              align-items: center;
              padding: 8px 10px;
              border-radius: 10px;
              background: var(--bg);
              border: 1px solid rgba(56, 189, 248, 0.45);
            }
            .live-join-id-row input {
              flex: 1;
              min-width: 160px;
            }
            .live-doc-stage {
              background: #080C12;
              border: 1px solid rgba(56, 189, 248, 0.55);
              border-radius: 10px;
              padding: 8px 10px;
              display: grid;
              gap: 8px;
            }
            .live-doc-toolbar {
              display: flex;
              flex-wrap: wrap;
              align-items: center;
              justify-content: space-between;
              gap: 6px;
            }
            .live-docs-bar {
              display: flex;
              flex-wrap: wrap;
              gap: 6px;
            }
            .live-doc-chip {
              background: var(--surface);
              border: 1px solid var(--border);
              color: var(--text);
              padding: 4px 9px;
              border-radius: 8px;
              font-size: 11px;
              font-weight: 600;
            }
            .live-doc-chip.active {
              border-color: var(--mint);
              color: var(--mint);
              background: rgba(0, 255, 156, 0.12);
            }
            .live-doc-preview-frame {
              width: 100%;
              height: 220px;
              border: 1px solid var(--border);
              border-radius: 8px;
              background: #05070A;
              object-fit: contain;
            }

            /* PC / Desktop Split-Pane Format */
            @media (min-width: 900px) {
              .wa-app {
                flex-direction: row;
              }
              #menu-toggle-btn,
              #close-sidebar-btn,
              #sidebar-backdrop {
                display: none !important;
              }
              aside#channels-sidebar {
                position: static;
                transform: none !important;
                width: 340px;
                flex-shrink: 0;
                height: 100%;
                z-index: 1;
              }
              .media-drawer {
                max-height: 42vh;
              }
              .live-stage-panel {
                max-height: 54vh;
              }
              .bubble {
                max-width: 68%;
              }
            }
          </style>
        </head>
        <body>
          <div class="wa-app">
            <!-- Slide-out Drawer Backdrop & Sidebar -->
            <div id="sidebar-backdrop" class="sidebar-backdrop hidden"></div>
            <aside id="channels-sidebar" aria-label="Channels navigation">
              <div class="drawer-header">
                <div class="drawer-brand">BITVOID // CHANNELS</div>
                <button id="close-sidebar-btn" class="btn-ghost" type="button">CLOSE</button>
              </div>

              <div class="drawer-meta-card">
                <div>MODE: <strong>${if (hostState.isOpenSecurity) "OPEN NET · NO PASSWORD" else "WPA2 / 4KB PADDED"}</strong></div>
                <div>SSID: <strong>${htmlEscape(hostState.effectiveSsid)}</strong> · GATEWAY: <strong>${htmlEscape(gatewayLabel)}</strong></div>
                <div>SESSION: <strong>${htmlEscape(token)}</strong> · SYNC: <strong id="sync-status">LIVE</strong></div>
              </div>

              <div class="section-title">GENERAL CHAT</div>
              <div id="general-chat-list" class="sidebar-list">
                $initialGeneralHtml
              </div>

              <div class="section-title">PUBLIC</div>
              <div id="public-channels-list" class="sidebar-list">
                $initialPublicChannelsHtml
              </div>

              <div class="section-title">PRIVATE</div>
              <div id="private-channels-list" class="sidebar-list">
                $initialPrivateChannelsHtml
              </div>

              <div class="unlock-box">
                <div class="section-title" style="margin:0;">UNLOCK PRIVATE CHANNEL ID</div>
                <input id="private-channel-id" type="text" placeholder="Enter Private Channel ID" autocomplete="off">
                <input id="private-channel-key" type="password" placeholder="Optional Private Key" autocomplete="off">
                <button id="unlock-channel-btn" class="btn-primary" type="button">OPEN CHAT SPACE</button>
                <div id="channel-error" class="err hidden"></div>
              </div>

              <div class="unlock-box">
                <div class="section-title" style="margin:0;color:var(--mint);">DIRECT CHAT (USERNAME ID)</div>
                <input id="dm-my-username" type="text" placeholder="Your Username ID" readonly autocomplete="off">
                <input id="dm-target-username" type="text" placeholder="Peer Username ID (e.g. user-4210)" autocomplete="off">
                <button id="dm-open-btn" class="btn-cyan" type="button">CHAT WITH USER</button>
                <div id="dm-error" class="err hidden"></div>
              </div>

              <div class="unlock-box">
                <div class="section-title" style="margin:0;color:var(--cyan);">JOIN LIVE CLASS (STUDENT ID)</div>
                <input id="sidebar-class-code-input" type="text" placeholder="Enter Live Class ID (e.g. CLS-XXXX-XX)" autocomplete="off">
                <button id="sidebar-join-class-btn" class="btn-primary" type="button">JOIN BY CLASS ID</button>
                <div id="sidebar-class-error" class="err hidden"></div>
              </div>
            </aside>

            <!-- Main Chat & Media Workspace -->
            <div class="wa-main-pane">
              <!-- Top Bar with Three-Line Hamburger Menu at Top Left -->
              <header class="wa-topbar">
                <button id="menu-toggle-btn" class="hamburger-btn" type="button" aria-label="Open channels menu">
                  <span class="hamburger-line"></span>
                  <span class="hamburger-line"></span>
                  <span class="hamburger-line"></span>
                </button>
                <div id="active-channel-avatar" class="wa-top-avatar">#</div>
                <div class="wa-top-info">
                  <div class="wa-top-title-row">
                    <div class="wa-top-title" id="active-channel-title">General Chat</div>
                    <span id="active-channel-badge" class="pill">PUBLIC</span>
                  </div>
                  <div class="wa-top-sub">
                    ID: <strong id="active-callsign">-</strong> · WS: <strong id="ws-status">CONNECTING</strong> · PEERS: <strong id="channel-peers">1</strong>
                  </div>
                </div>
                <div class="top-actions">
                  <button id="live-class-btn" class="btn-ghost" type="button">LIVE CLASS / CALL</button>
                  <button id="toggle-media-btn" class="btn-ghost" type="button">MEDIA (<span id="public-count">${publicItems.size}</span>)</button>
                  <button id="exit-channel-btn" class="btn-danger hidden" type="button">EXIT</button>
                </div>
              </header>

              <!-- Collapsible Channel Media & Public Broadcasts Drawer -->
              <section id="media-drawer" class="media-drawer hidden">
                <div>
                  <div class="section-title" style="margin-bottom:8px;">CHANNEL STREAM &amp; FILES</div>
                  <div id="channel-media-feed" class="grid"></div>
                </div>
                <div>
                  <div class="section-title" style="margin-bottom:8px;">ALL PUBLIC BROADCASTS</div>
                  <div id="void-feed" class="grid">
                    $publicFeedHtml
                  </div>
                </div>
              </section>

              <!-- Live Class & Voice/Video Call WebRTC Stage -->
              <section id="live-stage-panel" class="live-stage-panel hidden">
                <div class="live-head">
                  <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">
                    <span id="live-mode-badge" class="pill">LIVE CLASS &amp; CALL STAGE</span>
                    <span id="live-class-code-pill" class="live-class-code-pill hidden">
                      <span>CLASS ID: <strong id="live-class-code-text">-</strong></span>
                      <button id="live-copy-code-btn" class="btn-ghost" style="padding:2px 6px;font-size:10px;" type="button">COPY</button>
                    </span>
                    <span id="live-room-status" class="muted-note">Local Wi-Fi WebRTC (/ws/signaling · zero external servers)</span>
                  </div>
                  <button id="close-live-stage-btn" class="btn-ghost" type="button">HIDE</button>
                </div>

                <div id="live-launcher-controls" class="live-controls-bar">
                  <button id="rtc-start-class-host-btn" class="btn-primary" type="button">START LIVE CLASS (TEACHER)</button>
                  <button id="rtc-join-class-student-btn" class="btn-cyan" type="button">JOIN LIVE CLASS (STUDENT)</button>
                  <button id="rtc-start-call-btn" class="btn-amber" type="button">START VOICE / VIDEO CALL</button>
                </div>

                <div id="live-join-id-row" class="live-join-id-row hidden">
                  <input id="rtc-student-class-id-input" type="text" placeholder="Enter Live Class ID (e.g. CLS-ABCD-12)" autocomplete="off">
                  <button id="rtc-student-class-id-submit" class="btn-cyan" type="button">ENTER CLASS</button>
                  <button id="rtc-student-class-id-cancel" class="btn-ghost" type="button">CANCEL</button>
                </div>

                <div id="live-active-controls" class="live-controls-bar hidden">
                  <button id="rtc-mute-btn" class="btn-ghost" type="button">MIC ON</button>
                  <button id="rtc-cam-btn" class="btn-ghost" type="button">CAM OFF</button>
                  <button id="rtc-share-screen-btn" class="btn-cyan" type="button">SHARE SCREEN</button>
                  <input id="rtc-doc-input" type="file" hidden accept=".pdf,.ppt,.pptx,.doc,.docx,.txt,.csv,.png,.jpg,.jpeg,.webp,*/*">
                  <button id="rtc-upload-doc-btn" class="btn-amber" type="button">UPLOAD DOC / PPT / PDF</button>
                  <button id="rtc-leave-btn" class="btn-danger" type="button">LEAVE</button>
                  <button id="rtc-end-session-btn" class="btn-danger hidden" type="button">END CLASS</button>
                </div>

                <div id="live-error-msg" class="err hidden"></div>

                <div id="live-presentation-stage" class="live-doc-stage hidden">
                  <div class="live-doc-toolbar">
                    <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">
                      <span class="pill pill-cyan">SHARED DOC / SLIDES</span>
                      <strong id="live-doc-title" style="font-size:12px;">-</strong>
                    </div>
                    <div style="display:flex;align-items:center;gap:6px;flex-wrap:wrap;">
                      <button id="rtc-slide-prev-btn" class="btn-ghost" style="padding:4px 8px;font-size:10px;" type="button">PREV</button>
                      <span id="rtc-slide-page-label" class="muted-note">Page 1</span>
                      <button id="rtc-slide-next-btn" class="btn-ghost" style="padding:4px 8px;font-size:10px;" type="button">NEXT</button>
                      <a id="rtc-doc-open-link" class="btn btn-primary" style="padding:4px 8px;font-size:10px;" href="#" target="_blank">OPEN</a>
                      <a id="rtc-doc-download-link" class="btn btn-ghost" style="padding:4px 8px;font-size:10px;" href="#" download>DOWNLOAD</a>
                    </div>
                  </div>
                  <div id="live-materials-list" class="live-docs-bar"></div>
                  <div id="live-doc-viewer-box"></div>
                </div>

                <div id="live-video-grid" class="live-video-grid"></div>
                <div id="live-participants-grid" class="live-participants-grid"></div>
              </section>

              <!-- WhatsApp-Style Channel Chat Space -->
              <main id="active-channel-panel">
                <div id="chat-messages" class="wa-chat-scroll">
                  <div class="wa-welcome-pill">
                    <strong>BitVoid Local Channel</strong> · Messages stay encrypted &amp; in-memory on this local node. Tap <strong>&#9776;</strong> top-left to switch between General Chat, Public, and Private channels.
                  </div>
                </div>
                <div class="wa-composer">
                  <input id="chat-file-input" type="file" hidden>
                  <button id="chat-attach-btn" class="attach-circle" type="button" title="Upload any file" aria-label="Upload any file">+</button>
                  <input id="chat-input" type="text" placeholder="Message channel..." maxlength="1000" autocomplete="off">
                  <button id="chat-send-btn" class="send-circle" type="button" aria-label="Send message">&#10148;</button>
                </div>
              </main>
            </div>
          </div>

          <script>
            (function () {
              var sidebar = document.getElementById('channels-sidebar');
              var backdrop = document.getElementById('sidebar-backdrop');
              var menuToggleBtn = document.getElementById('menu-toggle-btn');
              var closeSidebarBtn = document.getElementById('close-sidebar-btn');

              var generalChatEl = document.getElementById('general-chat-list');
              var publicChannelsEl = document.getElementById('public-channels-list');
              var privateChannelsEl = document.getElementById('private-channels-list');
              var privateIdInput = document.getElementById('private-channel-id');
              var privateKeyInput = document.getElementById('private-channel-key');
              var unlockBtn = document.getElementById('unlock-channel-btn');
              var channelErr = document.getElementById('channel-error');
              var dmMyInput = document.getElementById('dm-my-username');
              var dmTargetInput = document.getElementById('dm-target-username');
              var dmOpenBtn = document.getElementById('dm-open-btn');
              var dmErr = document.getElementById('dm-error');

              var activeAvatar = document.getElementById('active-channel-avatar');
              var activeTitle = document.getElementById('active-channel-title');
              var activeBadge = document.getElementById('active-channel-badge');
              var activeCallsign = document.getElementById('active-callsign');
              var wsStatusEl = document.getElementById('ws-status');
              var peersEl = document.getElementById('channel-peers');
              var exitChannelBtn = document.getElementById('exit-channel-btn');
              var liveClassBtn = document.getElementById('live-class-btn');
              var liveStagePanel = document.getElementById('live-stage-panel');
              var closeLiveStageBtn = document.getElementById('close-live-stage-btn');
              var liveModeBadge = document.getElementById('live-mode-badge');
              var liveClassCodePill = document.getElementById('live-class-code-pill');
              var liveClassCodeText = document.getElementById('live-class-code-text');
              var liveCopyCodeBtn = document.getElementById('live-copy-code-btn');
              var liveRoomStatusEl = document.getElementById('live-room-status');
              var liveLauncherControls = document.getElementById('live-launcher-controls');
              var liveJoinIdRow = document.getElementById('live-join-id-row');
              var rtcStudentClassIdInput = document.getElementById('rtc-student-class-id-input');
              var rtcStudentClassIdSubmit = document.getElementById('rtc-student-class-id-submit');
              var rtcStudentClassIdCancel = document.getElementById('rtc-student-class-id-cancel');
              var sidebarClassCodeInput = document.getElementById('sidebar-class-code-input');
              var sidebarJoinClassBtn = document.getElementById('sidebar-join-class-btn');
              var sidebarClassErr = document.getElementById('sidebar-class-error');
              var liveActiveControls = document.getElementById('live-active-controls');
              var rtcStartClassHostBtn = document.getElementById('rtc-start-class-host-btn');
              var rtcJoinClassStudentBtn = document.getElementById('rtc-join-class-student-btn');
              var rtcStartCallBtn = document.getElementById('rtc-start-call-btn');
              var rtcMuteBtn = document.getElementById('rtc-mute-btn');
              var rtcCamBtn = document.getElementById('rtc-cam-btn');
              var rtcShareScreenBtn = document.getElementById('rtc-share-screen-btn');
              var rtcDocInput = document.getElementById('rtc-doc-input');
              var rtcUploadDocBtn = document.getElementById('rtc-upload-doc-btn');
              var rtcLeaveBtn = document.getElementById('rtc-leave-btn');
              var rtcEndSessionBtn = document.getElementById('rtc-end-session-btn');
              var liveErrorMsg = document.getElementById('live-error-msg');
              var livePresentationStage = document.getElementById('live-presentation-stage');
              var liveDocTitle = document.getElementById('live-doc-title');
              var rtcSlidePrevBtn = document.getElementById('rtc-slide-prev-btn');
              var rtcSlidePageLabel = document.getElementById('rtc-slide-page-label');
              var rtcSlideNextBtn = document.getElementById('rtc-slide-next-btn');
              var rtcDocOpenLink = document.getElementById('rtc-doc-open-link');
              var rtcDocDownloadLink = document.getElementById('rtc-doc-download-link');
              var liveMaterialsList = document.getElementById('live-materials-list');
              var liveDocViewerBox = document.getElementById('live-doc-viewer-box');
              var liveVideoGrid = document.getElementById('live-video-grid');
              var liveParticipantsGrid = document.getElementById('live-participants-grid');

              var toggleMediaBtn = document.getElementById('toggle-media-btn');
              var mediaDrawer = document.getElementById('media-drawer');
              var channelMediaFeed = document.getElementById('channel-media-feed');
              var chatBox = document.getElementById('chat-messages');
              var chatFileInput = document.getElementById('chat-file-input');
              var chatAttachBtn = document.getElementById('chat-attach-btn');
              var chatInput = document.getElementById('chat-input');
              var chatSendBtn = document.getElementById('chat-send-btn');

              var publicFeedEl = document.getElementById('void-feed');
              var publicCountEl = document.getElementById('public-count');
              var syncEl = document.getElementById('sync-status');

              var activeChannel = null;
              var activeDm = null;
              var activeSocket = null;
              var seenMsgIds = {};
              var currentChannelMediaSig = '';
              var currentPublicFeedSig = '';
              var autoJoinedOnce = false;

              var rtcSocket = null;
              var rtcPeers = {};
              var rtcRemoteStreams = {};
              var rtcParticipantList = [];
              var rtcPeerId = '';
              var rtcRoomId = '';
              var rtcMode = 'VOICE_VIDEO_CALL';
              var rtcRole = 'SPEAKER';
              var rtcClassCode = '';
              var rtcMaterials = [];
              var rtcActiveMaterialId = '';
              var rtcActivePageIndex = 0;
              var rtcLocalStream = null;
              var rtcScreenStream = null;
              var rtcMuted = false;
              var rtcVideoOn = false;
              var rtcScreenSharing = false;
              try {
                var storedUser = localStorage.getItem('bv_username');
                if (!storedUser) {
                  storedUser = 'web-' + Math.floor(1000 + Math.random() * 9000);
                  localStorage.setItem('bv_username', storedUser);
                }
                dmMyInput.value = storedUser;
              } catch (e) {
                dmMyInput.value = 'web-' + Math.floor(1000 + Math.random() * 9000);
              }

              function openSidebar() {
                sidebar.classList.add('open');
                backdrop.classList.remove('hidden');
              }

              function closeSidebar() {
                sidebar.classList.remove('open');
                backdrop.classList.add('hidden');
              }

              function esc(s) {
                return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
                  .replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#39;');
              }

              function fmtBytes(b) {
                if (b < 1024) return b + ' B';
                var kb = b / 1024;
                if (kb < 1024) return kb.toFixed(1) + ' KB';
                var mb = kb / 1024;
                if (mb < 1024) return mb.toFixed(1) + ' MB';
                return (mb / 1024).toFixed(2) + ' GB';
              }

              function buildMediaCard(item, tokenQuery) {
                var card = document.createElement('article');
                card.className = 'card';
                card.setAttribute('data-id', item.id);
                var sUrl = item.streamUrl + (tokenQuery ? ('?token=' + encodeURIComponent(tokenQuery)) : '');
                var dUrl = item.downloadUrl + (tokenQuery ? ('?token=' + encodeURIComponent(tokenQuery)) : '');
                var preview = '';
                if (item.category === 'VIDEO') {
                  preview = '<video controls preload="metadata" playsinline>' +
                    '<source src="' + esc(sUrl) + '" type="' + esc(item.mimeType) + '">' +
                    '</video>';
                } else if (item.category === 'AUDIO') {
                  preview = '<audio controls preload="metadata">' +
                    '<source src="' + esc(sUrl) + '" type="' + esc(item.mimeType) + '">' +
                    '</audio>';
                } else if (item.category === 'IMAGE') {
                  preview = '<img class="preview-img" src="' + esc(sUrl) + '" alt="' + esc(item.title) + '" loading="lazy">';
                }
                card.innerHTML =
                  '<div class="card-head">' +
                    '<span class="badge">' + esc(item.badge) + '</span>' +
                    '<div class="meta">' +
                      '<div class="filename">' + esc(item.title) + '</div>' +
                      '<div class="sub">' + fmtBytes(item.size) + ' · ' + esc(item.mimeType) + '</div>' +
                    '</div>' +
                  '</div>' +
                  preview +
                  '<div class="actions">' +
                    '<a class="btn btn-primary" href="' + esc(sUrl) + '" target="_self">OPEN / STREAM</a>' +
                    '<a class="btn btn-ghost" href="' + esc(dUrl) + '" download="' + esc(item.title) + '">DOWNLOAD</a>' +
                  '</div>';
                return card;
              }

              function renderMediaList(container, items, tokenQuery, emptyTitle, emptyHint) {
                container.innerHTML = '';
                if (!items || items.length === 0) {
                  container.innerHTML = '<div class="empty"><div class="empty-title">' + esc(emptyTitle) + '</div><p>' + esc(emptyHint) + '</p></div>';
                  return;
                }
                items.forEach(function (item) {
                  container.appendChild(buildMediaCard(item, tokenQuery));
                });
              }

              function renderWelcomeBanner(chName, isPublic) {
                var banner = document.createElement('div');
                banner.className = 'wa-welcome-pill';
                banner.innerHTML = '<strong>' + esc(chName) + '</strong> · ' +
                  (isPublic ? 'Public BitVoid channel space.' : 'Private ID-locked channel space.') +
                  ' Tap <strong>MEDIA</strong> top-right for channel streams.';
                chatBox.appendChild(banner);
              }

              function buildAttachmentHtml(att) {
                if (!att || !att.name) return '';
                var token = activeDm ? activeDm.token : (activeChannel ? activeChannel.token : '');
                var q = token ? ('?token=' + encodeURIComponent(token)) : '';
                var sUrl = (att.streamUrl || '') + q;
                var dUrl = (att.downloadUrl || att.streamUrl || '') + q;
                var mime = String(att.mimeType || '').toLowerCase();
                var preview = '';
                if (mime.indexOf('image/') === 0) {
                  preview = '<img class="msg-att-img" src="' + esc(sUrl) + '" alt="' + esc(att.name) + '" loading="lazy">';
                } else if (mime.indexOf('video/') === 0) {
                  preview = '<video controls preload="metadata" playsinline><source src="' + esc(sUrl) + '" type="' + esc(att.mimeType) + '"></video>';
                } else if (mime.indexOf('audio/') === 0) {
                  preview = '<audio controls preload="metadata"><source src="' + esc(sUrl) + '" type="' + esc(att.mimeType) + '"></audio>';
                }
                var ext = (att.name.split('.').pop() || 'FILE').toUpperCase().slice(0, 6);
                return '<div class="msg-attachment">' +
                  '<div class="msg-att-head">' +
                    '<span class="badge">' + esc(ext) + '</span>' +
                    '<div class="meta">' +
                      '<div class="msg-att-name">' + esc(att.name) + '</div>' +
                      '<div class="msg-att-sub">' + fmtBytes(att.size || 0) + ' · ' + esc(att.mimeType || 'file') + '</div>' +
                    '</div>' +
                  '</div>' +
                  preview +
                  '<div class="msg-att-actions">' +
                    '<a class="btn btn-primary" href="' + esc(sUrl) + '" target="_blank">OPEN</a>' +
                    '<a class="btn btn-ghost" href="' + esc(dUrl) + '" download="' + esc(att.name) + '">DOWNLOAD</a>' +
                  '</div>' +
                '</div>';
              }

              function appendChatMessage(msg) {
                if (!msg || !msg.id || seenMsgIds[msg.id]) return;
                seenMsgIds[msg.id] = true;
                var selfName = activeDm ? activeDm.myUsername : (activeChannel ? activeChannel.callsign : '');
                var isMine = !!selfName && msg.sender === selfName;
                var row = document.createElement('div');
                row.className = 'msg-row ' + (isMine ? 'out' : 'in');
                var senderLine = isMine ? '' : ('<div class="msg-sender">' + esc(msg.sender) + '</div>');
                var attHtml = buildAttachmentHtml(msg.attachment);
                var textHtml = msg.text ? ('<div class="msg-text">' + esc(msg.text) + '</div>') : '';
                var ticks = isMine ? '<span class="msg-ticks">&#10003;&#10003;</span>' : '';
                row.innerHTML =
                  '<div class="bubble">' +
                    senderLine +
                    attHtml +
                    textHtml +
                    '<div class="msg-foot"><span>' + esc(msg.time || '') + '</span>' + ticks + '</div>' +
                  '</div>';
                chatBox.appendChild(row);
                chatBox.scrollTop = chatBox.scrollHeight;
              }

              function appendNotice(text) {
                var pill = document.createElement('div');
                pill.className = 'msg-sys';
                pill.textContent = text;
                chatBox.appendChild(pill);
                chatBox.scrollTop = chatBox.scrollHeight;
              }

              function connectChannelWs(channelId, token) {
                if (activeSocket) {
                  try { activeSocket.close(); } catch (e) {}
                }
                var scheme = location.protocol === 'https:' ? 'wss://' : 'ws://';
                var wsUrl = scheme + location.host + '/ws/chat/' + encodeURIComponent(channelId) + '?token=' + encodeURIComponent(token);
                wsStatusEl.textContent = 'CONNECTING';
                try {
                  activeSocket = new WebSocket(wsUrl);
                  activeSocket.onopen = function () { wsStatusEl.textContent = 'LIVE'; };
                  activeSocket.onclose = function () {
                    wsStatusEl.textContent = 'HTTP-SYNC';
                    setTimeout(function () {
                      if (activeChannel && activeChannel.channelId === channelId && activeChannel.token === token && !activeDm) {
                        connectChannelWs(channelId, token);
                      }
                    }, 3500);
                  };
                  activeSocket.onerror = function () { wsStatusEl.textContent = 'HTTP-SYNC'; };
                  activeSocket.onmessage = function (ev) {
                    try {
                      var data = JSON.parse(ev.data);
                      if (data.type === 'history' && Array.isArray(data.messages)) {
                        data.messages.forEach(appendChatMessage);
                      } else if (data.type === 'chat' && data.message) {
                        appendChatMessage(data.message);
                      } else if (data.type === 'presence') {
                        peersEl.textContent = String(data.activeCount || 1);
                      } else if (data.type === 'notice' && data.text) {
                        appendNotice(data.text);
                        refreshActiveChannelMedia();
                      }
                    } catch (e) {}
                  };
                } catch (e) {
                  wsStatusEl.textContent = 'HTTP-SYNC';
                }
              }

              function applyJoinedChannelData(data) {
                activeDm = null;
                activeChannel = data;
                seenMsgIds = {};
                chatBox.innerHTML = '';
                var label = data.name || data.channelId;
                activeTitle.textContent = label;
                activeCallsign.textContent = data.callsign || '-';
                chatInput.placeholder = 'Message ' + label + '...';
                liveClassBtn.textContent = 'LIVE CLASS / CALL';
                liveClassBtn.classList.remove('active');
                if (data.isPublic) {
                  activeAvatar.textContent = '#';
                  activeAvatar.className = 'wa-top-avatar';
                  activeBadge.textContent = 'PUBLIC';
                  activeBadge.className = 'pill';
                  exitChannelBtn.classList.add('hidden');
                } else {
                  activeAvatar.textContent = 'ID';
                  activeAvatar.className = 'wa-top-avatar private-mode';
                  activeBadge.textContent = 'PRIVATE';
                  activeBadge.className = 'pill pill-amber';
                  exitChannelBtn.classList.remove('hidden');
                }
                renderWelcomeBanner(label, !!data.isPublic);
                var list = data.media || data.videos || [];
                currentChannelMediaSig = list.map(function (m) { return m.id; }).join('|');
                renderMediaList(
                  channelMediaFeed,
                  list,
                  data.token,
                  'NO MEDIA IN CHANNEL YET',
                  'Chat is active! Any media assigned by the Host appears here automatically.'
                );
                (data.messages || []).forEach(appendChatMessage);
                connectChannelWs(data.channelId, data.token);
                highlightActiveSidebar();
                closeSidebar();
              }

              function joinChannel(channelId, privateKey) {
                channelErr.classList.add('hidden');
                var myU = (dmMyInput && dmMyInput.value ? dmMyInput.value.trim() : '');
                fetch('/api/channels/join', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ channelId: channelId, privateKey: privateKey || '', username: myU })
                })
                  .then(function (r) {
                    if (!r.ok) throw new Error('Invalid Channel ID or key (HTTP ' + r.status + ')');
                    return r.json();
                  })
                  .then(applyJoinedChannelData)
                  .catch(function (err) {
                    channelErr.textContent = err.message || 'Unable to join channel';
                    channelErr.classList.remove('hidden');
                  });
              }

              function joinLiveClassByCode(rawCode, errEl) {
                var cleanCode = (rawCode || '').trim().toUpperCase();
                if (!cleanCode) {
                  if (errEl) {
                    errEl.textContent = 'Enter the Live Class ID (e.g. CLS-ABCD-12).';
                    errEl.classList.remove('hidden');
                  } else {
                    showRtcError('Enter the Live Class ID (e.g. CLS-ABCD-12).');
                  }
                  return;
                }
                if (errEl) errEl.classList.add('hidden');
                showRtcError('');
                var myU = (dmMyInput && dmMyInput.value ? dmMyInput.value.trim() : '');
                fetch('/api/live-class/join', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ classCode: cleanCode, username: myU })
                })
                  .then(function (r) {
                    if (!r.ok) {
                      return r.json().then(function (d) {
                        throw new Error((d && d.detail) || ('Invalid Live Class ID (HTTP ' + r.status + ')'));
                      });
                    }
                    return r.json();
                  })
                  .then(function (data) {
                    applyJoinedChannelData(data);
                    liveJoinIdRow.classList.add('hidden');
                    rtcStudentClassIdInput.value = '';
                    sidebarClassCodeInput.value = '';
                    joinRtcSession('LIVE_CLASSROOM', 'STUDENT_VIEWER', false, data.roomId || data.channelId, data.classCode || cleanCode);
                  })
                  .catch(function (err) {
                    var msg = err.message || 'Invalid or ended Live Class ID.';
                    if (errEl) {
                      errEl.textContent = msg;
                      errEl.classList.remove('hidden');
                    } else {
                      showRtcError(msg);
                    }
                  });
              }

              function exitPrivateChannel() {
                activeChannel = null;
                activeDm = null;
                currentChannelMediaSig = '';
                seenMsgIds = {};
                if (activeSocket) {
                  try { activeSocket.close(); } catch (e) {}
                  activeSocket = null;
                }
                chatBox.innerHTML = '';
                channelMediaFeed.innerHTML = '';
                joinChannel('${BitVoidChannelManager.DEFAULT_PUBLIC_CHANNEL_ID}', '');
              }

              function openDirectChat(myUser, targetUser) {
                dmErr.classList.add('hidden');
                try { localStorage.setItem('bv_username', myUser); } catch (e) {}
                fetch('/api/dm/open', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({ myUsername: myUser, targetUsername: targetUser })
                })
                  .then(function (r) {
                    if (!r.ok) return r.json().then(function (d) { throw new Error((d && d.detail) || ('HTTP ' + r.status)); });
                    return r.json();
                  })
                  .then(function (data) {
                    activeChannel = null;
                    activeDm = data;
                    seenMsgIds = {};
                    chatBox.innerHTML = '';
                    activeTitle.textContent = '@' + data.peerUsername;
                    activeCallsign.textContent = data.myUsername;
                    chatInput.placeholder = 'Direct message @' + data.peerUsername + '...';
                    activeAvatar.textContent = '@';
                    activeAvatar.className = 'wa-top-avatar';
                    activeBadge.textContent = 'DIRECT CHAT';
                    activeBadge.className = 'pill';
                    peersEl.textContent = '2';
                    liveClassBtn.textContent = 'VOICE / VIDEO CALL';
                    exitChannelBtn.classList.remove('hidden');
                    var banner = document.createElement('div');
                    banner.className = 'wa-welcome-pill';
                    banner.innerHTML = '<strong>Direct Chat with @' + esc(data.peerUsername) + '</strong> · You: <strong>@' + esc(data.myUsername) + '</strong> · Tap VOICE / VIDEO CALL above to call.';
                    chatBox.appendChild(banner);
                    (data.messages || []).forEach(appendChatMessage);
                    connectDirectWs(data.dmId, data.token);
                    highlightActiveSidebar();
                    closeSidebar();
                  })
                  .catch(function (err) {
                    dmErr.textContent = err.message || 'Unable to start direct chat';
                    dmErr.classList.remove('hidden');
                  });
              }

              function connectDirectWs(dmId, token) {
                if (activeSocket) { try { activeSocket.close(); } catch (e) {} }
                var scheme = location.protocol === 'https:' ? 'wss://' : 'ws://';
                var dmWsUrl = scheme + location.host + '/ws/dm/' + encodeURIComponent(dmId) + '?token=' + encodeURIComponent(token);
                wsStatusEl.textContent = 'CONNECTING';
                try {
                  activeSocket = new WebSocket(dmWsUrl);
                  activeSocket.onopen = function () { wsStatusEl.textContent = 'LIVE'; };
                  activeSocket.onclose = function () {
                    wsStatusEl.textContent = 'HTTP-SYNC';
                    setTimeout(function () {
                      if (activeDm && activeDm.dmId === dmId && activeDm.token === token) {
                        connectDirectWs(dmId, token);
                      }
                    }, 3500);
                  };
                  activeSocket.onerror = function () { wsStatusEl.textContent = 'HTTP-SYNC'; };
                  activeSocket.onmessage = function (ev) {
                    try {
                      var p = JSON.parse(ev.data);
                      if (p.type === 'history' && Array.isArray(p.messages)) p.messages.forEach(appendChatMessage);
                      else if (p.type === 'chat' && p.message) appendChatMessage(p.message);
                    } catch (e) {}
                  };
                } catch (e) { wsStatusEl.textContent = 'HTTP-SYNC'; }
              }

              function refreshActiveChannelMedia() {
                if (activeDm) {
                  fetch('/api/dm/' + encodeURIComponent(activeDm.dmId) + '/messages', {
                    headers: { 'X-DM-Token': activeDm.token },
                    cache: 'no-store'
                  })
                    .then(function (r) { return r.ok ? r.json() : null; })
                    .then(function (d) {
                      if (d && Array.isArray(d.messages)) d.messages.forEach(appendChatMessage);
                    })
                    .catch(function () {});
                  fetch('/api/dm/' + encodeURIComponent(activeDm.dmId) + '/call/status', {
                    headers: { 'X-DM-Token': activeDm.token },
                    cache: 'no-store'
                  })
                    .then(function (r) { return r.ok ? r.json() : null; })
                    .then(function (st) {
                      if (!st) return;
                      if (st.isActive) {
                        liveClassBtn.textContent = 'CALL ACTIVE (' + (st.participantCount || 1) + ')';
                        liveClassBtn.classList.add('active');
                        if (!rtcSocket) {
                          liveRoomStatusEl.textContent = 'Direct Voice/Video Call ringing/active · ' + (st.participantCount || 1) + ' peer(s)';
                          renderRtcParticipants(st.peers || []);
                        }
                      } else if (!rtcSocket) {
                        liveClassBtn.textContent = 'VOICE / VIDEO CALL';
                        liveClassBtn.classList.remove('active');
                      }
                    })
                    .catch(function () {});
                  return;
                }
                if (!activeChannel) return;
                fetch('/api/channels/' + encodeURIComponent(activeChannel.channelId) + '/media', {
                  headers: { 'X-Channel-Token': activeChannel.token },
                  cache: 'no-store'
                })
                  .then(function (r) {
                    if (r.status === 401 || r.status === 404) { exitPrivateChannel(); return null; }
                    return r.json();
                  })
                  .then(function (data) {
                    if (!data) return;
                    var list = data.media || data.videos || [];
                    var sig = list.map(function (m) { return m.id; }).join('|');
                    if (sig !== currentChannelMediaSig) {
                      currentChannelMediaSig = sig;
                      renderMediaList(
                        channelMediaFeed,
                        list,
                        activeChannel.token,
                        'NO MEDIA IN CHANNEL YET',
                        'Chat is active! Any media assigned by the Host appears here automatically.'
                      );
                    }
                  })
                  .catch(function () {});

                fetch('/api/channels/' + encodeURIComponent(activeChannel.channelId) + '/chat', {
                  headers: { 'X-Channel-Token': activeChannel.token },
                  cache: 'no-store'
                })
                  .then(function (r) { return r.ok ? r.json() : null; })
                  .then(function (data) {
                    if (!data) return;
                    if (typeof data.participantCount === 'number') {
                      peersEl.textContent = String(Math.max(1, data.participantCount));
                    }
                    if (Array.isArray(data.messages)) {
                      data.messages.forEach(appendChatMessage);
                    }
                  })
                  .catch(function () {});

                fetch('/api/channels/' + encodeURIComponent(activeChannel.channelId) + '/call/status', {
                  headers: { 'X-Channel-Token': activeChannel.token },
                  cache: 'no-store'
                })
                  .then(function (r) { return r.ok ? r.json() : null; })
                  .then(function (st) {
                    if (!st) return;
                    if (st.isActive) {
                      var mLabel = st.mode === 'LIVE_CLASSROOM' ? 'LIVE CLASS' : 'CALL LIVE';
                      liveClassBtn.textContent = mLabel + ' (' + (st.participantCount || 1) + ')';
                      liveClassBtn.classList.add('active');
                      if (!rtcSocket) {
                        liveRoomStatusEl.textContent = mLabel + ' in progress · ' + (st.participantCount || 1) + ' peer(s) active';
                        renderRtcParticipants(st.peers || []);
                      }
                    } else if (!rtcSocket) {
                      liveClassBtn.textContent = 'LIVE CLASS / CALL';
                      liveClassBtn.classList.remove('active');
                      liveRoomStatusEl.textContent = 'Local Wi-Fi WebRTC (/ws/signaling · zero external servers)';
                      renderRtcParticipants([]);
                    }
                  })
                  .catch(function () {});
              }

              function highlightActiveSidebar() {
                Array.prototype.forEach.call(document.querySelectorAll('.wa-chan-row[data-channel-id]'), function (btn) {
                  var cid = btn.getAttribute('data-channel-id');
                  if (activeChannel && activeChannel.channelId === cid) {
                    btn.classList.add('active');
                  } else {
                    btn.classList.remove('active');
                  }
                });
              }

              function bindSidebarClicks() {
                Array.prototype.forEach.call(document.querySelectorAll('.wa-chan-row[data-channel-id]'), function (btn) {
                  btn.onclick = function () {
                    joinChannel(btn.getAttribute('data-channel-id'), '');
                  };
                });
                Array.prototype.forEach.call(document.querySelectorAll('.wa-chan-row[data-locked-name]'), function (btn) {
                  btn.onclick = function () {
                    var cname = btn.getAttribute('data-locked-name') || 'Private Channel';
                    channelErr.textContent = 'Enter Private ID for "' + cname + '" below.';
                    channelErr.classList.remove('hidden');
                    privateIdInput.focus();
                  };
                });
              }

              function sendChat() {
                var txt = (chatInput.value || '').trim();
                if (!txt || (!activeChannel && !activeDm)) return;
                chatInput.value = '';
                if (activeSocket && activeSocket.readyState === 1) {
                  activeSocket.send(JSON.stringify({ text: txt }));
                } else if (activeDm) {
                  fetch('/api/dm/' + encodeURIComponent(activeDm.dmId) + '/messages', {
                    method: 'POST',
                    headers: {
                      'Content-Type': 'application/json',
                      'X-DM-Token': activeDm.token
                    },
                    body: JSON.stringify({ text: txt })
                  })
                    .then(function (r) { return r.json(); })
                    .then(function (d) { if (d && d.message) appendChatMessage(d.message); })
                    .catch(function () {});
                } else {
                  fetch('/api/channels/' + encodeURIComponent(activeChannel.channelId) + '/chat', {
                    method: 'POST',
                    headers: {
                      'Content-Type': 'application/json',
                      'X-Channel-Token': activeChannel.token
                    },
                    body: JSON.stringify({ text: txt })
                  })
                    .then(function (r) { return r.json(); })
                    .then(function (d) { if (d && d.message) appendChatMessage(d.message); })
                    .catch(function () {});
                }
              }

              function renderSidebarRows(pubChannels, prvChannels) {
                var gen = pubChannels.filter(function (c) { return c.channelId === '${BitVoidChannelManager.DEFAULT_PUBLIC_CHANNEL_ID}'; });
                var others = pubChannels.filter(function (c) { return c.channelId !== '${BitVoidChannelManager.DEFAULT_PUBLIC_CHANNEL_ID}'; });

                if (gen.length > 0) {
                  generalChatEl.innerHTML = gen.map(function (c) {
                    var activeCls = (activeChannel && activeChannel.channelId === c.channelId) ? ' active' : '';
                    return '<button type="button" class="wa-chan-row' + activeCls + '" data-channel-id="' + esc(c.channelId) + '">' +
                      '<div class="wa-avatar">#</div>' +
                      '<div class="wa-chan-info">' +
                        '<div class="wa-chan-title">' + esc(c.name) + '</div>' +
                        '<div class="wa-chan-sub">' + c.mediaCount + ' media · ' + c.activePeers + ' live · ' + c.messageCount + ' msgs</div>' +
                      '</div></button>';
                  }).join('');
                }

                if (others.length === 0) {
                  publicChannelsEl.innerHTML = '<div class="muted-note">No extra public channels yet.</div>';
                } else {
                  publicChannelsEl.innerHTML = others.map(function (c) {
                    var activeCls = (activeChannel && activeChannel.channelId === c.channelId) ? ' active' : '';
                    return '<button type="button" class="wa-chan-row' + activeCls + '" data-channel-id="' + esc(c.channelId) + '">' +
                      '<div class="wa-avatar">#</div>' +
                      '<div class="wa-chan-info">' +
                        '<div class="wa-chan-title">' + esc(c.name) + '</div>' +
                        '<div class="wa-chan-sub">' + c.mediaCount + ' media · ' + c.activePeers + ' live · ' + c.messageCount + ' msgs</div>' +
                      '</div></button>';
                  }).join('');
                }

                if (prvChannels.length === 0) {
                  privateChannelsEl.innerHTML = '<div class="muted-note">No private channels active.</div>';
                } else {
                  privateChannelsEl.innerHTML = prvChannels.map(function (c) {
                    return '<button type="button" class="wa-chan-row wa-chan-locked" data-locked-name="' + esc(c.name) + '">' +
                      '<div class="wa-avatar wa-avatar-lock">ID</div>' +
                      '<div class="wa-chan-info">' +
                        '<div class="wa-chan-title">' + esc(c.name) + '</div>' +
                        '<div class="wa-chan-sub">Private · Tap &amp; enter ID (' + c.mediaCount + ' media)</div>' +
                      '</div></button>';
                  }).join('');
                }
                bindSidebarClicks();
              }

              function pollChannelsAndFeed() {
                fetch('/api/channels', { cache: 'no-store' })
                  .then(function (r) { return r.json(); })
                  .then(function (data) {
                    var pub = data.publicChannels || [];
                    var prv = data.privateChannels || [];
                    renderSidebarRows(pub, prv);
                    if (!autoJoinedOnce && !activeChannel && !activeDm && pub.length > 0) {
                      autoJoinedOnce = true;
                      joinChannel(pub[0].channelId, '');
                    }
                    syncEl.textContent = 'LIVE';
                  })
                  .catch(function () { syncEl.textContent = 'RETRYING'; });

                fetch('/api/public/media', { cache: 'no-store' })
                  .then(function (r) { return r.json(); })
                  .then(function (data) {
                    var list = data.media || data.videos || [];
                    publicCountEl.textContent = String(list.length);
                    var nextSig = list.map(function (v) { return v.id; }).join('|');
                    if (nextSig !== currentPublicFeedSig) {
                      currentPublicFeedSig = nextSig;
                      renderMediaList(
                        publicFeedEl,
                        list,
                        null,
                        'NO PUBLIC STREAMS YET',
                        'Select a channel from the sidebar or unlock a Private Channel ID to stream & chat.'
                      );
                    }
                  })
                  .catch(function () {});

                refreshActiveChannelMedia();
              }

              function showRtcError(msg) {
                if (!msg) {
                  liveErrorMsg.classList.add('hidden');
                  liveErrorMsg.textContent = '';
                } else {
                  liveErrorMsg.textContent = msg;
                  liveErrorMsg.classList.remove('hidden');
                }
              }

              function renderLiveClassCode(code) {
                rtcClassCode = code || '';
                if (rtcClassCode) {
                  liveClassCodeText.textContent = rtcClassCode;
                  liveClassCodePill.classList.remove('hidden');
                } else {
                  liveClassCodeText.textContent = '-';
                  liveClassCodePill.classList.add('hidden');
                }
              }

              function renderLiveMaterials() {
                if (!rtcMaterials || rtcMaterials.length === 0) {
                  livePresentationStage.classList.add('hidden');
                  liveMaterialsList.innerHTML = '';
                  liveDocViewerBox.innerHTML = '';
                  return;
                }
                livePresentationStage.classList.remove('hidden');
                var activeMat = rtcMaterials.find(function (m) { return m.id === rtcActiveMaterialId; }) || rtcMaterials[0];
                rtcActiveMaterialId = activeMat.id;

                liveMaterialsList.innerHTML = rtcMaterials.map(function (m) {
                  var cls = 'live-doc-chip' + (m.id === rtcActiveMaterialId ? ' active' : '');
                  return '<button type="button" class="' + cls + '" data-mat-id="' + esc(m.id) + '">' + esc(m.fileName) + '</button>';
                }).join('');
                Array.prototype.forEach.call(liveMaterialsList.querySelectorAll('.live-doc-chip'), function (btn) {
                  btn.onclick = function () {
                    var mid = btn.getAttribute('data-mat-id');
                    rtcActiveMaterialId = mid;
                    rtcActivePageIndex = 0;
                    renderLiveMaterials();
                    if (rtcSocket && rtcSocket.readyState === 1) {
                      rtcSocket.send(JSON.stringify({ type: 'active_material', materialId: mid, pageIndex: 0 }));
                    }
                  };
                });

                var tok = activeDm ? activeDm.token : (activeChannel ? activeChannel.token : '');
                var q = tok ? ('?token=' + encodeURIComponent(tok)) : '';
                var sUrl = (activeMat.streamUrl || '') + q;
                var dUrl = (activeMat.downloadUrl || activeMat.streamUrl || '') + q;
                liveDocTitle.textContent = activeMat.fileName + ' (' + fmtBytes(activeMat.sizeBytes || 0) + ' · by ' + (activeMat.uploadedBy || 'Presenter') + ')';
                rtcSlidePageLabel.textContent = 'Slide / Page ' + (rtcActivePageIndex + 1);
                rtcDocOpenLink.href = sUrl;
                rtcDocDownloadLink.href = dUrl;
                rtcDocDownloadLink.setAttribute('download', activeMat.fileName || 'document');

                var lowerName = String(activeMat.fileName || '').toLowerCase();
                var mime = String(activeMat.mimeType || '').toLowerCase();
                if (mime.indexOf('image/') === 0) {
                  liveDocViewerBox.innerHTML = '<img class="live-doc-preview-frame" src="' + esc(sUrl) + '" alt="' + esc(activeMat.fileName) + '">';
                } else if (mime.indexOf('pdf') !== -1 || lowerName.endsWith('.pdf') || mime.indexOf('text/') === 0 || lowerName.endsWith('.txt')) {
                  var pageHash = (mime.indexOf('pdf') !== -1 || lowerName.endsWith('.pdf')) ? ('#page=' + (rtcActivePageIndex + 1)) : '';
                  liveDocViewerBox.innerHTML = '<iframe class="live-doc-preview-frame" src="' + esc(sUrl + pageHash) + '"></iframe>';
                } else {
                  liveDocViewerBox.innerHTML =
                    '<div class="empty" style="padding:14px;">' +
                      '<div class="empty-title">' + esc(activeMat.fileName) + '</div>' +
                      '<p>Slide / Office presentation shared live (' + esc(activeMat.mimeType || 'document') + '). View synced slides in Android app or click OPEN / DOWNLOAD above.</p>' +
                    '</div>';
                }
              }

              function updateRtcControlsUi() {
                var joined = !!rtcSocket;
                if (activeDm) {
                  rtcStartClassHostBtn.classList.add('hidden');
                  rtcJoinClassStudentBtn.classList.add('hidden');
                  rtcStartCallBtn.textContent = 'CALL @' + activeDm.peerUsername;
                } else {
                  rtcStartClassHostBtn.classList.remove('hidden');
                  rtcJoinClassStudentBtn.classList.remove('hidden');
                  rtcStartCallBtn.textContent = 'START VOICE / VIDEO CALL';
                }
                if (joined) {
                  liveLauncherControls.classList.add('hidden');
                  liveJoinIdRow.classList.add('hidden');
                  liveActiveControls.classList.remove('hidden');
                  rtcMuteBtn.textContent = rtcMuted ? 'MIC MUTED' : 'MIC ON';
                  rtcCamBtn.textContent = rtcVideoOn ? 'CAM ON' : 'CAM OFF';
                  rtcShareScreenBtn.textContent = rtcScreenSharing ? 'STOP SHARE' : 'SHARE SCREEN';
                  if (rtcRole === 'HOST_TEACHER') {
                    rtcEndSessionBtn.classList.remove('hidden');
                  } else {
                    rtcEndSessionBtn.classList.add('hidden');
                  }
                } else {
                  liveLauncherControls.classList.remove('hidden');
                  liveActiveControls.classList.add('hidden');
                }
              }

              function renderRtcParticipants(peers) {
                rtcParticipantList = peers || [];
                liveParticipantsGrid.innerHTML = '';
                rtcParticipantList.forEach(function (p) {
                  var chip = document.createElement('span');
                  var isTeacher = p.role === 'HOST_TEACHER';
                  chip.className = 'live-participant-chip' + (isTeacher ? ' teacher' : '');
                  var statusBits = [];
                  if (isTeacher) statusBits.push('TEACHER');
                  else if (p.role === 'STUDENT_VIEWER') statusBits.push('STUDENT');
                  if (p.isScreenSharing) statusBits.push('SCREEN');
                  else if (p.isVideoOn) statusBits.push('CAM');
                  if (p.isMuted) statusBits.push('MUTED');
                  chip.textContent = (p.displayName || p.peerId) + (statusBits.length ? (' · ' + statusBits.join('/')) : '');
                  liveParticipantsGrid.appendChild(chip);
                });
              }

              function renderRtcVideoTiles() {
                liveVideoGrid.innerHTML = '';
                var localStream = rtcScreenSharing && rtcScreenStream ? rtcScreenStream : rtcLocalStream;
                if (localStream && (rtcVideoOn || rtcScreenSharing)) {
                  var localTile = document.createElement('div');
                  localTile.className = 'live-video-tile' + (rtcRole === 'HOST_TEACHER' ? ' teacher-tile' : '');
                  var v = document.createElement('video');
                  v.autoplay = true;
                  v.muted = true;
                  v.playsInline = true;
                  v.srcObject = localStream;
                  var lbl = document.createElement('span');
                  lbl.className = 'live-tile-label';
                  lbl.textContent = 'YOU (' + (rtcScreenSharing ? 'SCREEN' : rtcRole) + ')';
                  localTile.appendChild(v);
                  localTile.appendChild(lbl);
                  liveVideoGrid.appendChild(localTile);
                }

                Object.keys(rtcRemoteStreams).forEach(function (pid) {
                  var stream = rtcRemoteStreams[pid];
                  if (!stream) return;
                  var peerMeta = rtcParticipantList.find(function (p) { return p.peerId === pid; });
                  var tile = document.createElement('div');
                  var isTeacher = peerMeta && peerMeta.role === 'HOST_TEACHER';
                  tile.className = 'live-video-tile' + (isTeacher ? ' teacher-tile' : '');
                  var v = document.createElement('video');
                  v.autoplay = true;
                  v.playsInline = true;
                  v.srcObject = stream;
                  var lbl = document.createElement('span');
                  lbl.className = 'live-tile-label';
                  lbl.textContent = (peerMeta ? peerMeta.displayName : pid) + (isTeacher ? ' [TEACHER]' : '');
                  tile.appendChild(v);
                  tile.appendChild(lbl);
                  liveVideoGrid.appendChild(tile);
                });
              }

              function sendRtcMediaState() {
                if (!rtcSocket || rtcSocket.readyState !== 1) return;
                rtcSocket.send(JSON.stringify({
                  type: 'media_state',
                  roomId: rtcRoomId,
                  isMuted: rtcMuted,
                  isVideoOn: rtcVideoOn,
                  isScreenSharing: rtcScreenSharing,
                  role: rtcRole
                }));
              }

              function shouldInitiateRtcOffer(remotePeer) {
                if (rtcRole === 'HOST_TEACHER' && remotePeer.role !== 'HOST_TEACHER') return true;
                if (rtcRole !== 'HOST_TEACHER' && remotePeer.role === 'HOST_TEACHER') return false;
                return rtcPeerId < remotePeer.peerId;
              }

              function createOrGetRtcPeer(remotePeerId) {
                if (rtcPeers[remotePeerId]) return rtcPeers[remotePeerId];
                var pc = new RTCPeerConnection({ iceServers: [] });
                rtcPeers[remotePeerId] = pc;

                var activeOutVideo = (rtcScreenSharing && rtcScreenStream)
                  ? rtcScreenStream.getVideoTracks()[0]
                  : (rtcLocalStream ? rtcLocalStream.getVideoTracks()[0] : null);
                var activeOutAudio = rtcLocalStream ? rtcLocalStream.getAudioTracks()[0] : null;

                var audioT = pc.addTransceiver('audio', { direction: 'sendrecv' });
                if (activeOutAudio) audioT.sender.replaceTrack(activeOutAudio);
                var videoT = pc.addTransceiver('video', { direction: 'sendrecv' });
                if (activeOutVideo) videoT.sender.replaceTrack(activeOutVideo);

                pc.onicecandidate = function (ev) {
                  if (ev.candidate && rtcSocket && rtcSocket.readyState === 1) {
                    rtcSocket.send(JSON.stringify({
                      type: 'ice',
                      roomId: rtcRoomId,
                      targetPeerId: remotePeerId,
                      candidate: {
                        sdpMid: ev.candidate.sdpMid || '0',
                        sdpMLineIndex: ev.candidate.sdpMLineIndex || 0,
                        candidate: ev.candidate.candidate
                      }
                    }));
                  }
                };

                pc.ontrack = function (ev) {
                  var stream = (ev.streams && ev.streams[0]) ? ev.streams[0] : new MediaStream([ev.track]);
                  rtcRemoteStreams[remotePeerId] = stream;
                  renderRtcVideoTiles();
                };
                return pc;
              }

              function sendRtcOffer(remotePeerId) {
                var pc = createOrGetRtcPeer(remotePeerId);
                pc.createOffer({ offerToReceiveAudio: true, offerToReceiveVideo: true })
                  .then(function (offer) {
                    return pc.setLocalDescription(offer).then(function () { return offer; });
                  })
                  .then(function (offer) {
                    if (rtcSocket && rtcSocket.readyState === 1) {
                      rtcSocket.send(JSON.stringify({
                        type: 'offer',
                        roomId: rtcRoomId,
                        targetPeerId: remotePeerId,
                        sdp: offer.sdp
                      }));
                    }
                  })
                  .catch(function () {});
              }

              function cleanupRtcPeersAndMedia() {
                Object.keys(rtcPeers).forEach(function (pid) {
                  try { rtcPeers[pid].close(); } catch (e) {}
                });
                rtcPeers = {};
                rtcRemoteStreams = {};
                if (rtcLocalStream) {
                  rtcLocalStream.getTracks().forEach(function (t) { t.stop(); });
                  rtcLocalStream = null;
                }
                if (rtcScreenStream) {
                  rtcScreenStream.getTracks().forEach(function (t) { t.stop(); });
                  rtcScreenStream = null;
                }
                rtcScreenSharing = false;
                rtcVideoOn = false;
                rtcMuted = false;
                liveVideoGrid.innerHTML = '';
              }

              function leaveRtcSession() {
                if (rtcSocket) {
                  try {
                    if (rtcSocket.readyState === 1) {
                      rtcSocket.send(JSON.stringify({ type: 'leave', roomId: rtcRoomId }));
                    }
                    rtcSocket.close();
                  } catch (e) {}
                  rtcSocket = null;
                }
                cleanupRtcPeersAndMedia();
                renderRtcParticipants([]);
                renderLiveClassCode('');
                rtcMaterials = [];
                rtcActiveMaterialId = '';
                rtcActivePageIndex = 0;
                renderLiveMaterials();
                updateRtcControlsUi();
                liveRoomStatusEl.textContent = 'Left live session';
              }

              function joinRtcSession(mode, role, startWithMedia, roomOverride, presetClassCode) {
                var targetRoom = roomOverride || (activeDm ? activeDm.dmId : (activeChannel ? activeChannel.channelId : ''));
                var targetToken = activeDm ? activeDm.token : (activeChannel ? activeChannel.token : '');
                var targetChannelId = activeChannel ? activeChannel.channelId : targetRoom;
                if (!targetRoom) {
                  showRtcError('Open a channel or Direct Chat before starting a Live Class or Call.');
                  return;
                }
                if (typeof RTCPeerConnection === 'undefined') {
                  showRtcError('WebRTC is not supported by this browser.');
                  return;
                }
                leaveRtcSession();
                showRtcError('');
                liveStagePanel.classList.remove('hidden');
                rtcRoomId = targetRoom;
                rtcMode = mode;
                rtcRole = role;
                if (presetClassCode) renderLiveClassCode(presetClassCode);
                rtcPeerId = 'web_' + Math.random().toString(36).slice(2, 10);
                rtcMuted = (role === 'STUDENT_VIEWER');

                var mediaPromise = (startWithMedia && navigator.mediaDevices && navigator.mediaDevices.getUserMedia)
                  ? navigator.mediaDevices.getUserMedia({ audio: true, video: role !== 'STUDENT_VIEWER' })
                      .catch(function () {
                        return navigator.mediaDevices.getUserMedia({ audio: true, video: false })
                          .catch(function () { return null; });
                      })
                  : Promise.resolve(null);

                mediaPromise.then(function (stream) {
                  rtcLocalStream = stream;
                  if (stream) {
                    stream.getAudioTracks().forEach(function (t) { t.enabled = !rtcMuted; });
                    rtcVideoOn = stream.getVideoTracks().length > 0 && role !== 'STUDENT_VIEWER';
                    stream.getVideoTracks().forEach(function (t) { t.enabled = rtcVideoOn; });
                  } else {
                    rtcVideoOn = false;
                    rtcMuted = true;
                  }

                  var scheme = location.protocol === 'https:' ? 'wss://' : 'ws://';
                  var displayName = (dmMyInput && dmMyInput.value ? dmMyInput.value.trim() : '') ||
                    (activeDm ? activeDm.myUsername : (activeChannel ? activeChannel.callsign : 'WebPeer'));
                  var wsUrl = scheme + location.host + '/ws/signaling' +
                    '?roomId=' + encodeURIComponent(rtcRoomId) +
                    '&channelId=' + encodeURIComponent(targetChannelId) +
                    '&peerId=' + encodeURIComponent(rtcPeerId) +
                    '&name=' + encodeURIComponent(displayName) +
                    '&mode=' + encodeURIComponent(rtcMode) +
                    '&role=' + encodeURIComponent(rtcRole) +
                    '&isMuted=' + rtcMuted +
                    '&isVideoOn=' + rtcVideoOn +
                    '&isScreenSharing=false' +
                    (targetToken ? ('&token=' + encodeURIComponent(targetToken)) : '');

                  rtcSocket = new WebSocket(wsUrl);
                  updateRtcControlsUi();
                  renderRtcVideoTiles();
                  liveRoomStatusEl.textContent = 'Connecting WebRTC signaling...';

                  rtcSocket.onopen = function () {
                    liveRoomStatusEl.textContent = (rtcMode === 'LIVE_CLASSROOM' ? 'LIVE CLASS ACTIVE' : (activeDm ? ('CALL WITH @' + activeDm.peerUsername) : 'VOICE/VIDEO CALL LIVE')) + ' · Local P2P';
                  };
                  rtcSocket.onerror = function () {
                    showRtcError('WebRTC signaling error on /ws/signaling.');
                  };
                  rtcSocket.onclose = function () {
                    rtcSocket = null;
                    updateRtcControlsUi();
                  };
                  rtcSocket.onmessage = function (ev) {
                    try {
                      var msg = JSON.parse(ev.data);
                      if (msg.type === 'welcome') {
                        if (msg.classCode) renderLiveClassCode(msg.classCode);
                        if (Array.isArray(msg.materials)) {
                          rtcMaterials = msg.materials;
                          rtcActiveMaterialId = msg.activeMaterialId || '';
                          rtcActivePageIndex = msg.activePageIndex || 0;
                          renderLiveMaterials();
                        }
                        renderRtcParticipants(msg.peers || []);
                        (msg.peers || []).forEach(function (p) {
                          if (p.peerId !== rtcPeerId) {
                            createOrGetRtcPeer(p.peerId);
                            if (shouldInitiateRtcOffer(p)) sendRtcOffer(p.peerId);
                          }
                        });
                      } else if (msg.type === 'peer_joined') {
                        if (msg.classCode) renderLiveClassCode(msg.classCode);
                        renderRtcParticipants(msg.peers || []);
                        if (msg.peer && msg.peer.peerId !== rtcPeerId) {
                          createOrGetRtcPeer(msg.peer.peerId);
                          if (shouldInitiateRtcOffer(msg.peer)) sendRtcOffer(msg.peer.peerId);
                        }
                      } else if (msg.type === 'peers_update') {
                        if (msg.classCode) renderLiveClassCode(msg.classCode);
                        renderRtcParticipants(msg.peers || []);
                        renderRtcVideoTiles();
                      } else if (msg.type === 'material_shared' || msg.type === 'material_state') {
                        if (Array.isArray(msg.materials)) rtcMaterials = msg.materials;
                        if (msg.activeMaterialId) rtcActiveMaterialId = msg.activeMaterialId;
                        if (typeof msg.activePageIndex === 'number') rtcActivePageIndex = msg.activePageIndex;
                        renderLiveMaterials();
                      } else if (msg.type === 'peer_left') {
                        renderRtcParticipants(msg.peers || []);
                        if (msg.peerId && rtcPeers[msg.peerId]) {
                          try { rtcPeers[msg.peerId].close(); } catch (e) {}
                          delete rtcPeers[msg.peerId];
                          delete rtcRemoteStreams[msg.peerId];
                          renderRtcVideoTiles();
                        }
                      } else if (msg.type === 'session_ended') {
                        leaveRtcSession();
                      } else if (msg.type === 'offer' && msg.fromPeerId && msg.sdp) {
                        var pc = createOrGetRtcPeer(msg.fromPeerId);
                        pc.setRemoteDescription({ type: 'offer', sdp: msg.sdp })
                          .then(function () { return pc.createAnswer(); })
                          .then(function (ans) {
                            return pc.setLocalDescription(ans).then(function () { return ans; });
                          })
                          .then(function (ans) {
                            if (rtcSocket && rtcSocket.readyState === 1) {
                              rtcSocket.send(JSON.stringify({
                                type: 'answer',
                                roomId: rtcRoomId,
                                targetPeerId: msg.fromPeerId,
                                sdp: ans.sdp
                              }));
                            }
                          })
                          .catch(function () {});
                      } else if (msg.type === 'answer' && msg.fromPeerId && msg.sdp) {
                        var pcAns = rtcPeers[msg.fromPeerId];
                        if (pcAns) {
                          pcAns.setRemoteDescription({ type: 'answer', sdp: msg.sdp }).catch(function () {});
                        }
                      } else if (msg.type === 'ice' && msg.fromPeerId && msg.candidate) {
                        var pcIce = rtcPeers[msg.fromPeerId];
                        if (pcIce) {
                          pcIce.addIceCandidate(new RTCIceCandidate(msg.candidate)).catch(function () {});
                        }
                      }
                    } catch (e) {}
                  };
                });
              }

              liveClassBtn.addEventListener('click', function () {
                liveStagePanel.classList.toggle('hidden');
                updateRtcControlsUi();
              });
              closeLiveStageBtn.addEventListener('click', function () {
                liveStagePanel.classList.add('hidden');
              });
              liveCopyCodeBtn.addEventListener('click', function () {
                if (rtcClassCode && navigator.clipboard) {
                  navigator.clipboard.writeText(rtcClassCode).catch(function () {});
                }
              });
              rtcStartClassHostBtn.addEventListener('click', function () {
                joinRtcSession('LIVE_CLASSROOM', 'HOST_TEACHER', true);
              });
              rtcJoinClassStudentBtn.addEventListener('click', function () {
                liveJoinIdRow.classList.toggle('hidden');
                if (!liveJoinIdRow.classList.contains('hidden')) {
                  rtcStudentClassIdInput.focus();
                }
              });
              rtcStudentClassIdCancel.addEventListener('click', function () {
                liveJoinIdRow.classList.add('hidden');
              });
              rtcStudentClassIdSubmit.addEventListener('click', function () {
                joinLiveClassByCode(rtcStudentClassIdInput.value, null);
              });
              sidebarJoinClassBtn.addEventListener('click', function () {
                joinLiveClassByCode(sidebarClassCodeInput.value, sidebarClassErr);
              });
              rtcStartCallBtn.addEventListener('click', function () {
                joinRtcSession('VOICE_VIDEO_CALL', 'SPEAKER', true);
              });
              rtcUploadDocBtn.addEventListener('click', function () {
                if (!rtcRoomId) return;
                rtcDocInput.value = '';
                rtcDocInput.click();
              });
              rtcDocInput.addEventListener('change', function () {
                var file = rtcDocInput.files && rtcDocInput.files[0];
                if (!file || !rtcRoomId) return;
                var fd = new FormData();
                fd.append('file', file, file.name);
                var headers = activeDm
                  ? { 'X-DM-Token': activeDm.token }
                  : (activeChannel && activeChannel.token ? { 'X-Channel-Token': activeChannel.token } : {});
                rtcUploadDocBtn.disabled = true;
                rtcUploadDocBtn.textContent = 'UPLOADING...';
                fetch('/api/live-class/' + encodeURIComponent(rtcRoomId) + '/materials', {
                  method: 'POST',
                  headers: headers,
                  body: fd
                })
                  .then(function (r) {
                    if (!r.ok) throw new Error('Material upload failed (HTTP ' + r.status + ')');
                    return r.json();
                  })
                  .then(function (d) {
                    if (Array.isArray(d.materials)) rtcMaterials = d.materials;
                    if (d.activeMaterialId) rtcActiveMaterialId = d.activeMaterialId;
                    rtcActivePageIndex = d.activePageIndex || 0;
                    renderLiveMaterials();
                  })
                  .catch(function (err) {
                    showRtcError(err.message || 'Unable to upload presentation material.');
                  })
                  .finally(function () {
                    rtcUploadDocBtn.disabled = false;
                    rtcUploadDocBtn.textContent = 'UPLOAD DOC / PPT / PDF';
                    rtcDocInput.value = '';
                  });
              });
              rtcSlidePrevBtn.addEventListener('click', function () {
                if (rtcActivePageIndex > 0) {
                  rtcActivePageIndex = rtcActivePageIndex - 1;
                  renderLiveMaterials();
                  if (rtcSocket && rtcSocket.readyState === 1) {
                    rtcSocket.send(JSON.stringify({ type: 'slide_nav', materialId: rtcActiveMaterialId, pageIndex: rtcActivePageIndex }));
                  }
                }
              });
              rtcSlideNextBtn.addEventListener('click', function () {
                rtcActivePageIndex = rtcActivePageIndex + 1;
                renderLiveMaterials();
                if (rtcSocket && rtcSocket.readyState === 1) {
                  rtcSocket.send(JSON.stringify({ type: 'slide_nav', materialId: rtcActiveMaterialId, pageIndex: rtcActivePageIndex }));
                }
              });
              rtcMuteBtn.addEventListener('click', function () {
                rtcMuted = !rtcMuted;
                if (rtcLocalStream) {
                  rtcLocalStream.getAudioTracks().forEach(function (t) { t.enabled = !rtcMuted; });
                }
                updateRtcControlsUi();
                sendRtcMediaState();
              });
              rtcCamBtn.addEventListener('click', function () {
                rtcVideoOn = !rtcVideoOn;
                if (rtcLocalStream && rtcLocalStream.getVideoTracks().length > 0) {
                  rtcLocalStream.getVideoTracks().forEach(function (t) { t.enabled = rtcVideoOn; });
                  updateRtcControlsUi();
                  renderRtcVideoTiles();
                  sendRtcMediaState();
                } else if (rtcVideoOn && navigator.mediaDevices && navigator.mediaDevices.getUserMedia) {
                  navigator.mediaDevices.getUserMedia({ video: true, audio: false })
                    .then(function (camStream) {
                      var vTrack = camStream.getVideoTracks()[0];
                      if (!rtcLocalStream) rtcLocalStream = camStream;
                      else if (vTrack) rtcLocalStream.addTrack(vTrack);
                      Object.keys(rtcPeers).forEach(function (pid) {
                        var sender = rtcPeers[pid].getSenders().find(function (s) { return s.track && s.track.kind === 'video'; });
                        if (sender && vTrack) sender.replaceTrack(vTrack);
                      });
                      updateRtcControlsUi();
                      renderRtcVideoTiles();
                      sendRtcMediaState();
                    })
                    .catch(function () {
                      rtcVideoOn = false;
                      showRtcError('No camera permission or camera unavailable.');
                    });
                }
              });
              rtcShareScreenBtn.addEventListener('click', function () {
                if (rtcScreenSharing) {
                  if (rtcScreenStream) {
                    rtcScreenStream.getTracks().forEach(function (t) { t.stop(); });
                    rtcScreenStream = null;
                  }
                  rtcScreenSharing = false;
                  var camTrack = rtcLocalStream ? rtcLocalStream.getVideoTracks()[0] : null;
                  Object.keys(rtcPeers).forEach(function (pid) {
                    var sender = rtcPeers[pid].getSenders().find(function (s) {
                      var trans = rtcPeers[pid].getTransceivers().find(function (tr) { return tr.sender === s; });
                      return trans && trans.receiver && trans.receiver.track && trans.receiver.track.kind === 'video';
                    });
                    if (sender) sender.replaceTrack(camTrack || null);
                  });
                  updateRtcControlsUi();
                  renderRtcVideoTiles();
                  sendRtcMediaState();
                } else if (navigator.mediaDevices && navigator.mediaDevices.getDisplayMedia) {
                  navigator.mediaDevices.getDisplayMedia({ video: true, audio: false })
                    .then(function (screenStream) {
                      rtcScreenStream = screenStream;
                      rtcScreenSharing = true;
                      var screenTrack = screenStream.getVideoTracks()[0];
                      if (screenTrack) {
                        screenTrack.onended = function () {
                          if (rtcScreenSharing) rtcShareScreenBtn.click();
                        };
                        Object.keys(rtcPeers).forEach(function (pid) {
                          var trans = rtcPeers[pid].getTransceivers().find(function (tr) {
                            return tr.receiver && tr.receiver.track && tr.receiver.track.kind === 'video';
                          });
                          if (trans && trans.sender) trans.sender.replaceTrack(screenTrack);
                        });
                      }
                      updateRtcControlsUi();
                      renderRtcVideoTiles();
                      sendRtcMediaState();
                    })
                    .catch(function () {
                      showRtcError('Screen sharing permission cancelled or unavailable.');
                    });
                } else {
                  showRtcError('Screen sharing requires Chrome/Desktop browser or native Android app.');
                }
              });
              rtcLeaveBtn.addEventListener('click', leaveRtcSession);
              rtcEndSessionBtn.addEventListener('click', function () {
                if (rtcSocket && rtcSocket.readyState === 1) {
                  rtcSocket.send(JSON.stringify({ type: 'end_session', roomId: rtcRoomId }));
                }
                leaveRtcSession();
              });

              menuToggleBtn.addEventListener('click', openSidebar);
              closeSidebarBtn.addEventListener('click', closeSidebar);
              backdrop.addEventListener('click', closeSidebar);
              toggleMediaBtn.addEventListener('click', function () {
                var hidden = mediaDrawer.classList.toggle('hidden');
                if (hidden) {
                  toggleMediaBtn.classList.remove('active');
                } else {
                  toggleMediaBtn.classList.add('active');
                }
              });

              unlockBtn.addEventListener('click', function () {
                var cid = (privateIdInput.value || '').trim();
                if (!cid) {
                  channelErr.textContent = 'Enter a Private Channel ID.';
                  channelErr.classList.remove('hidden');
                  return;
                }
                joinChannel(cid, privateKeyInput.value || '');
              });
              dmOpenBtn.addEventListener('click', function () {
                var mu = (dmMyInput.value || '').trim();
                var tu = (dmTargetInput.value || '').trim();
                if (!mu || !tu) {
                  dmErr.textContent = 'Enter both your Username ID and peer Username ID.';
                  dmErr.classList.remove('hidden');
                  return;
                }
                openDirectChat(mu, tu);
              });
              exitChannelBtn.addEventListener('click', function () {
                leaveRtcSession();
                exitPrivateChannel();
              });
              chatAttachBtn.addEventListener('click', function () {
                if (!activeChannel && !activeDm) return;
                chatFileInput.value = '';
                chatFileInput.click();
              });
              chatFileInput.addEventListener('change', function () {
                var file = chatFileInput.files && chatFileInput.files[0];
                if (!file || (!activeChannel && !activeDm)) return;
                var caption = (chatInput.value || '').trim();
                var fd = new FormData();
                fd.append('file', file, file.name);
                if (caption) fd.append('caption', caption);
                chatAttachBtn.disabled = true;
                chatAttachBtn.textContent = '...';
                var uploadUrl = activeDm
                  ? ('/api/dm/' + encodeURIComponent(activeDm.dmId) + '/upload')
                  : ('/api/channels/' + encodeURIComponent(activeChannel.channelId) + '/upload');
                var headers = activeDm
                  ? { 'X-DM-Token': activeDm.token }
                  : { 'X-Channel-Token': activeChannel.token };
                fetch(uploadUrl, {
                  method: 'POST',
                  headers: headers,
                  body: fd
                })
                  .then(function (r) {
                    if (!r.ok) throw new Error('Upload failed (HTTP ' + r.status + ')');
                    return r.json();
                  })
                  .then(function (d) {
                    if (caption) chatInput.value = '';
                    if (d && d.message) appendChatMessage(d.message);
                    refreshActiveChannelMedia();
                  })
                  .catch(function (err) {
                    appendNotice(err.message || 'File upload failed');
                  })
                  .finally(function () {
                    chatAttachBtn.disabled = false;
                    chatAttachBtn.textContent = '+';
                    chatFileInput.value = '';
                  });
              });
              chatSendBtn.addEventListener('click', sendChat);
              chatInput.addEventListener('keydown', function (ev) {
                if (ev.key === 'Enter') sendChat();
              });

              bindSidebarClicks();
              pollChannelsAndFeed();
              setInterval(pollChannelsAndFeed, 3000);
            })();
          </script>
        </body>
        </html>
        """.trimIndent()

        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "text/html; charset=utf-8",
            body = html,
            token = token,
            isHead = isHead
        )
    }

    private fun renderMediaCardHtml(item: BitVoidMediaItem): String {
        val safeTitle = htmlEscape(item.title)
        val sizeLabel = formatBytes(item.size)
        val streamUrl = "/stream/${urlEncode(item.id)}"
        val downloadUrl = "/download/${urlEncode(item.id)}"
        val previewMarkup = when (item.category) {
            MediaCategory.VIDEO -> """
                <video controls preload="metadata" playsinline>
                  <source src="$streamUrl" type="${htmlEscape(item.mimeType)}">
                </video>
            """.trimIndent()
            MediaCategory.AUDIO -> """
                <audio controls preload="metadata">
                  <source src="$streamUrl" type="${htmlEscape(item.mimeType)}">
                </audio>
            """.trimIndent()
            MediaCategory.IMAGE -> """
                <img class="preview-img" src="$streamUrl" alt="$safeTitle" loading="lazy">
            """.trimIndent()
            else -> ""
        }
        return """
        <article class="card" data-id="${htmlEscape(item.id)}">
          <div class="card-head">
            <span class="badge">${item.category.badge}</span>
            <div class="meta">
              <div class="filename">$safeTitle</div>
              <div class="sub">$sizeLabel · ${htmlEscape(item.mimeType)}</div>
            </div>
          </div>
          $previewMarkup
          <div class="actions">
            <a class="btn btn-primary" href="$streamUrl" target="_self">OPEN / STREAM</a>
            <a class="btn btn-ghost" href="$downloadUrl" download="$safeTitle">DOWNLOAD</a>
          </div>
        </article>
        """.trimIndent()
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

    private fun pruneStaleSessions(now: Long) {
        val cutoff = now - 120_000L
        val iterator = activeSessions.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value < cutoff) {
                iterator.remove()
            }
        }
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
                activeSessionsCount = activeSessions.size,
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

    private fun htmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
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
            val text = try {
                val obj = JSONObject(raw)
                obj.optString("text", raw)
            } catch (_: Exception) {
                raw
            }
            BitVoidChannelManager.appendChatMessage(
                channelId = channelSession.channelId,
                callsign = channelSession.callsign,
                rawText = text
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
            val text = try {
                val obj = JSONObject(raw)
                obj.optString("text", raw)
            } catch (_: Exception) {
                raw
            }
            BitVoidDirectChatManager.appendMessage(
                dmId = directSession.dmId,
                senderUsername = directSession.myUsername,
                rawText = text
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
        private val initialMuted: Boolean,
        private val initialVideoOn: Boolean,
        private val initialScreenSharing: Boolean
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
                isVideoOn = initialVideoOn,
                isScreenSharing = initialScreenSharing,
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
