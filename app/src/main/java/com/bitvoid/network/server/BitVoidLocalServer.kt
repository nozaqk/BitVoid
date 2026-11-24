package com.bitvoid.network.server

import android.content.Context
import com.bitvoid.network.call.BitVoidSignalingServer
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
    private val nodeActivitySalt = ByteArray(16).also { secureRandom.nextBytes(it) }
    // Volatile, in-memory activity keyed by a process-salted endpoint digest. This counts one app
    // or browser device once across requests without retaining its local address or identity.
    private val activeNodes = ConcurrentHashMap<String, Long>()
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

                rawUri == "/bitvoid-local-ca.crt" || rawUri == "/bitvoid-local-ca.pem" -> {
                    serveLocalCaCertificate(ephemeralToken, pem = true, isHead = (method == Method.HEAD))
                }

                rawUri == "/bitvoid-local-ca.cer" -> {
                    serveLocalCaCertificate(ephemeralToken, pem = false, isHead = (method == Method.HEAD))
                }

                rawUri == "/secure-setup" -> {
                    serveSecureBrowserSetup(ephemeralToken, isHead = (method == Method.HEAD))
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
                    @Suppress("DEPRECATION")
                    val username = session.parms["username"]
                    serveChannelsListJson(
                        username = username,
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
            put("portalUrl", hostState.effectivePortalUrl)
            put(
                "directPortalUrl",
                if (hostState.hasClientGatewayAddress) hostState.networkPortalUrl else JSONObject.NULL
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

    private fun serveLocalCaCertificate(
        ephemeralToken: String,
        pem: Boolean,
        isHead: Boolean
    ): Response {
        val bytes = if (pem) {
            BitVoidTlsManager.caCertificatePem(context)
        } else {
            BitVoidTlsManager.caCertificateDer(context)
        }
        val fileName = if (pem) "bitvoid-local-ca.crt" else "bitvoid-local-ca.cer"
        val mimeType = if (pem) "application/x-x509-ca-cert" else "application/pkix-cert"
        val response = newFixedLengthResponse(
            Response.Status.OK,
            mimeType,
            ByteArrayInputStream(if (isHead) ByteArray(0) else bytes),
            bytes.size.toLong()
        )
        response.setGzipEncoding(false)
        response.addHeader("Content-Length", bytes.size.toString())
        response.addHeader("Content-Disposition", "attachment; filename=\"$fileName\"")
        response.addHeader("Content-Description", "BitVoid Local Root CA")
        response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
        response.addHeader("X-BitVoid-CA-SHA256", BitVoidTlsManager.caCertificateSha256Fingerprint(context))
        response.addHeader("X-BitVoid-Session", ephemeralToken)
        if (!isHead) totalBytesServed.addAndGet(bytes.size.toLong())
        return response
    }

    private fun serveSecureBrowserSetup(ephemeralToken: String, isHead: Boolean): Response {
        val state = BitVoidNodeStateHolder.hostState.value
        val secureUrl = state.securePortalUrl
        val fingerprint = BitVoidTlsManager.caCertificateSha256Fingerprint(context)
        val body = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta name="referrer" content="no-referrer"><title>BitVoid secure browser setup</title>
            <style>
            body{margin:0;background:#07100d;color:#eef8f4;font:16px system-ui,sans-serif}.wrap{max-width:760px;margin:auto;padding:28px 18px 52px}
            .card{background:#101c18;border:1px solid #2a4840;border-radius:18px;padding:22px;margin:16px 0}h1,h2{color:#74f6c3}code{color:#7dddf2;overflow-wrap:anywhere}.steps{line-height:1.55}.fingerprint{font-size:12px;word-break:break-all;background:#07100d;border:1px solid #2a4840;border-radius:9px;padding:10px}
            a,.button{color:#07100d;background:#74f6c3;text-decoration:none;font-weight:800;border-radius:10px;padding:13px 16px;display:inline-block;margin:6px 8px 6px 0}.secondary{color:#eef8f4;background:transparent;border:1px solid #74f6c3}.muted{color:#aec2ba}.warning{color:#ffd58a}
            </style></head><body><main class="wrap"><h1>Secure microphone setup</h1>
            <p class="muted">Complete this once on each browser device. Stay connected to the same BitVoid Wi-Fi node throughout setup.</p>
            <div class="card"><h2>1. Save the BitVoid Local Root CA</h2><p>Tap the first button for the widely compatible PEM certificate. If your device does not offer to save or install it, use the DER fallback.</p><a class="button" href="/bitvoid-local-ca.crt" target="_top">Save / install CA (.crt)</a><a class="button secondary" href="/bitvoid-local-ca.cer" target="_top">DER fallback (.cer)</a><p class="muted">If this page is inside a Wi-Fi sign-in mini-window and the button does nothing, open this page in Chrome, Edge, Firefox, or Safari and tap it again.</p><p>Verify the certificate name is <b>BitVoid Local Root CA</b>.</p><div class="fingerprint"><b>SHA-256</b><br>${htmlEscape(fingerprint)}</div></div>
            <div class="card steps"><h2>2. Install it as a trusted CA</h2><p><b>Android:</b> open Settings → Security &amp; privacy → More security settings → Encryption &amp; credentials → Install a certificate → CA certificate, then choose the downloaded <code>bitvoid-local-ca.crt</code>. A screen-lock confirmation may be required.</p><p><b>Windows:</b> open the file → Install Certificate → Current User → Place all certificates in <b>Trusted Root Certification Authorities</b>.</p><p><b>macOS:</b> open the file in Keychain Access, place it in the login keychain, open <b>BitVoid Local Root CA</b>, and set Trust → Always Trust.</p><p><b>iPhone / iPad:</b> download in Safari, approve the profile in Settings → General → VPN &amp; Device Management, then enable it in Settings → General → About → Certificate Trust Settings.</p><p><b>Firefox:</b> if it still warns, import the file in Settings → Privacy &amp; Security → Certificates → View Certificates → Authorities.</p><p class="warning">Only trust the certificate from the phone hosting your BitVoid node. Remove it when you no longer use that node.</p></div>
            <div class="card"><h2>3. Reopen BitVoid over HTTPS</h2><p><code id="secure-url-code">${htmlEscape(secureUrl)}</code></p><a class="button" id="secure-portal-link" href="${htmlEscape(secureUrl)}" target="_top">Open secure BitVoid</a><p class="muted">Fully close the browser first if it cached an earlier warning. The address must exactly match the one above. Once trusted, the HTTPS page will show the normal microphone permission prompt.</p></div>
            </main><script>
            (function(){var h=location.hostname;var host=h.indexOf(':')>=0?'['+h+']':h;var secure='https://'+host+':${BitVoidTlsManager.SECURE_PORT}';var u=new URLSearchParams(location.search).get('username');document.getElementById('secure-url-code').textContent=secure;document.getElementById('secure-portal-link').href=secure+(u?'?username='+encodeURIComponent(u):'');})();
            </script></body></html>
        """.trimIndent()
        return buildPaddedTextResponse(
            status = Response.Status.OK,
            mimeType = "text/html; charset=utf-8",
            body = body,
            token = ephemeralToken,
            isHead = isHead
        )
    }

    // ───────────────────────────────────────────────────────────────────────────
    // Channel API Handlers
    // ───────────────────────────────────────────────────────────────────────────

    private fun serveChannelsListJson(
        username: String?,
        ephemeralToken: String,
        isHead: Boolean
    ): Response {
        val diskItems = BitVoidMediaManager.refreshFromDisk(context)
        BitVoidChannelManager.syncMediaCatalog(diskItems)

        // Hidden channels stay invisible to everyone except their creator. The creator receives
        // the ID and can enter directly without retyping the private ID/key.
        val listedChannels = BitVoidChannelManager.getChannelSnapshotsForUser(username)
        val publicList = listedChannels.filter { it.isPublic }
        val privateList = listedChannels.filter { !it.isPublic }

        fun summary(channel: BitVoidChannel): String {
            val isOwner = !username.isNullOrBlank() &&
                channel.creatorUsername.equals(username.trim().removePrefix("@"), ignoreCase = true)
            return channelSummaryJson(channel, revealId = channel.isPublic || isOwner, isOwner = isOwner)
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
        isOwner: Boolean = false
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
        }.toString()
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

        val validated = BitVoidChannelManager.validateToken(authToken, requiredChannelId = channelId)
        if (!channel.isPublic && validated == null) {
            return unauthorizedResponse(ephemeralToken)
        }

        val rawBody = readPostBody(session)
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
            hostState.gatewayHostname.isNotBlank() -> {
                if (hostState.gatewayHostnamePort == 80) {
                    hostState.gatewayHostname
                } else {
                    "${hostState.gatewayHostname}:${hostState.gatewayHostnamePort}"
                }
            }
            hostState.gatewayIp.isNotBlank() &&
                hostState.gatewayIp != "127.0.0.1" &&
                hostState.gatewayIp != "localhost" -> {
                "${hostState.gatewayIp}:${hostState.port}"
            }
            !nonLoopbackEndpoint.isNullOrBlank() -> nonLoopbackEndpoint
            else -> BitVoidMdnsResponder.HOSTNAME
        }

        val rawHostHeader = sanitizedHeaders["host"]?.trim().orEmpty()
        val hostOnly = rawHostHeader.substringBefore(':').lowercase(Locale.US)
        val useDirectLocalHost = hostState.gatewayHostname.isBlank() &&
            rawHostHeader.isNotBlank() &&
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
              <p>BitVoid Protected Network Portal — Redirecting to BitVoid Channels...</p>
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
            "interest-cohort=(), microphone=(self), geolocation=(), usb=()"
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
        val gatewayLabel = hostState.effectivePortalUrl.removePrefix("http://")

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
            .portal-access-backdrop {
              position: fixed;
              inset: 0;
              z-index: 2000;
              display: flex;
              align-items: center;
              justify-content: center;
              padding: 18px;
              background: rgba(4, 8, 13, 0.88);
              backdrop-filter: blur(8px);
            }
            .portal-access-dialog {
              width: min(420px, 100%);
              border-radius: 18px;
              border: 1px solid rgba(0, 255, 156, 0.65);
              background: var(--surface);
              box-shadow: 0 24px 70px rgba(0, 0, 0, 0.58);
              padding: 22px;
              text-align: center;
            }
            .portal-access-mark {
              width: 54px;
              height: 54px;
              margin: 0 auto 12px;
              border-radius: 50%;
              border: 1px solid var(--mint);
              background: rgba(0, 255, 156, 0.12);
              color: var(--mint);
              display: flex;
              align-items: center;
              justify-content: center;
              font-size: 25px;
              font-weight: 800;
            }
            .portal-access-title {
              color: var(--mint);
              font-size: 17px;
              font-weight: 800;
              letter-spacing: 0.05em;
            }
            .portal-access-copy {
              margin: 8px 0 14px;
              color: var(--muted);
              font-size: 12px;
            }
            .portal-login-input {
              width: 100%;
              min-height: 44px;
              margin-bottom: 12px;
              border: 1px solid var(--border);
              border-radius: 10px;
              background: var(--bg);
              color: var(--text);
              padding: 9px 11px;
              font: inherit;
              outline: none;
            }
            .portal-login-input:focus { border-color: var(--cyan); }
            .portal-access-error {
              margin: -3px 0 10px;
              color: var(--crimson);
              font-size: 11px;
              text-align: left;
            }
            .portal-access-actions {
              display: grid;
              grid-template-columns: 1fr 1fr;
              gap: 9px;
            }
            .portal-access-actions button {
              min-height: 46px;
              border-radius: 10px;
              font-family: inherit;
              font-weight: 800;
              cursor: pointer;
            }
            .portal-guest-btn {
              border: 1px solid var(--cyan);
              background: transparent;
              color: var(--cyan);
            }
            .portal-login-btn {
              border: 1px solid var(--mint);
              background: var(--mint);
              color: #07110C;
            }
            .portal-login-back {
              width: 100%;
              min-height: 40px;
              margin-top: 9px;
              border-radius: 10px;
              font-family: inherit;
              font-weight: 800;
              cursor: pointer;
            }
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
            .call-icon-btn {
              width: 42px;
              height: 42px;
              padding: 0;
              border-radius: 12px;
              border: 1px solid var(--border);
              color: var(--text);
              background: var(--bg);
              transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease, transform 0.16s ease;
            }
            .call-icon-btn svg {
              width: 20px;
              height: 20px;
              fill: none;
              stroke: currentColor;
              stroke-width: 1.9;
              stroke-linecap: round;
              stroke-linejoin: round;
              pointer-events: none;
            }
            .call-icon-btn:hover, .call-icon-btn:focus-visible {
              border-color: var(--mint);
              color: var(--mint);
              background: rgba(0, 255, 156, 0.1);
              transform: translateY(-1px);
              outline: none;
            }
            .call-icon-btn.active {
              border-color: var(--mint);
              color: var(--mint);
              box-shadow: 0 0 0 3px rgba(0, 255, 156, 0.1);
            }
            .call-icon-btn:disabled {
              opacity: 0.38;
              cursor: not-allowed;
              transform: none;
            }

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
            .pchat-box {
              display: grid;
              gap: 9px;
              background: var(--bg);
              border: 1px solid rgba(0, 255, 156, 0.45);
              border-radius: 12px;
              padding: 10px;
            }
            .pchat-head, .pchat-identity {
              display: flex;
              align-items: center;
              justify-content: space-between;
              gap: 10px;
            }
            .pchat-head-copy { min-width: 0; }
            .pchat-head-copy .section-title { color: var(--mint); margin: 0; font-size: 13px; }
            .pchat-head-copy p { color: var(--muted); font-size: 9px; margin-top: 2px; }
            .pchat-add-btn {
              width: 38px;
              height: 38px;
              flex-shrink: 0;
              padding: 0;
              border-radius: 50%;
              background: rgba(0, 255, 156, 0.12);
              border: 1px solid var(--mint);
              color: var(--mint);
              font-size: 22px;
              line-height: 1;
            }
            .pchat-identity {
              padding: 8px 9px;
              border-radius: 9px;
              border: 1px solid var(--border);
              background: var(--surface);
            }
            .pchat-you { min-width: 0; font-size: 11px; font-weight: 700; color: var(--text); }
            .pchat-you strong { color: var(--cyan); overflow-wrap: anywhere; }
            .pchat-discovery-note { color: var(--muted); font-size: 9px; margin-top: 2px; }
            .pchat-switch {
              position: relative;
              width: 42px;
              height: 24px;
              flex-shrink: 0;
              border-radius: 999px;
              padding: 0;
              border: 1px solid var(--border);
              background: var(--elevated);
            }
            .pchat-switch::after {
              content: '';
              position: absolute;
              width: 16px;
              height: 16px;
              top: 3px;
              left: 4px;
              border-radius: 50%;
              background: var(--muted);
              transition: transform 0.16s ease, background 0.16s ease;
            }
            .pchat-switch[aria-checked="true"] { border-color: var(--mint); background: rgba(0, 255, 156, 0.16); }
            .pchat-switch[aria-checked="true"]::after { transform: translateX(17px); background: var(--mint); }
            .pchat-users-label { color: var(--muted); font-size: 9px; letter-spacing: 0.55px; font-weight: 700; }
            .pchat-users { display: grid; gap: 6px; }
            .pchat-user-row {
              width: 100%;
              justify-content: flex-start;
              gap: 9px;
              padding: 8px 9px;
              color: var(--text);
              background: var(--surface);
              border: 1px solid var(--border);
              text-align: left;
            }
            .pchat-user-row:hover, .pchat-user-row.active { border-color: var(--mint); background: rgba(0, 255, 156, 0.08); }
            .pchat-user-avatar {
              width: 30px;
              height: 30px;
              flex-shrink: 0;
              display: flex;
              align-items: center;
              justify-content: center;
              border-radius: 50%;
              color: var(--mint);
              background: rgba(0, 255, 156, 0.12);
              border: 1px solid rgba(0, 255, 156, 0.5);
            }
            .pchat-user-copy { min-width: 0; flex: 1; }
            .pchat-user-name { display: block; font-size: 11px; font-weight: 700; overflow: hidden; text-overflow: ellipsis; }
            .pchat-user-sub { display: block; color: var(--mint); font-size: 9px; margin-top: 1px; }
            .pchat-empty { color: var(--muted); font-size: 10px; line-height: 1.4; padding: 3px 1px; }
            .pchat-dialog-backdrop {
              position: fixed;
              inset: 0;
              z-index: 80;
              display: flex;
              align-items: center;
              justify-content: center;
              padding: 18px;
              background: rgba(5, 8, 12, 0.84);
            }
            .pchat-dialog {
              width: min(430px, 100%);
              display: grid;
              gap: 12px;
              padding: 20px;
              border-radius: 16px;
              border: 1px solid var(--mint);
              background: var(--surface);
              box-shadow: 0 22px 60px rgba(0, 0, 0, 0.5);
            }
            .pchat-dialog-title { color: var(--mint); font-size: 15px; font-weight: 700; }
            .pchat-dialog-hint { color: var(--muted); font-size: 11px; line-height: 1.45; }
            .pchat-dialog-actions { display: flex; justify-content: flex-end; gap: 8px; }

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
            .bubble.has-attachment {
              width: min(460px, 76%);
              max-width: min(460px, 76%);
              padding: 6px 6px 5px;
            }
            .msg-attachment {
              position: relative;
              overflow: hidden;
              margin-bottom: 5px;
              padding: 10px;
              border-radius: 12px;
              background: linear-gradient(145deg, rgba(20, 29, 38, 0.98), rgba(10, 15, 21, 0.98));
              border: 1px solid rgba(139, 148, 158, 0.28);
              display: grid;
              gap: 8px;
              box-shadow: 0 10px 28px rgba(0, 0, 0, 0.22);
            }
            .msg-attachment::before {
              content: '';
              position: absolute;
              inset: 0 0 auto 0;
              height: 2px;
              background: linear-gradient(90deg, var(--mint), var(--cyan), transparent 82%);
            }
            .msg-att-head {
              display: flex;
              align-items: center;
              gap: 10px;
              min-width: 0;
            }
            .msg-att-icon {
              flex-shrink: 0;
              width: 42px;
              height: 42px;
              border-radius: 11px;
              display: flex;
              align-items: center;
              justify-content: center;
              background: rgba(0, 255, 156, 0.1);
              border: 1px solid rgba(0, 255, 156, 0.35);
              color: var(--mint);
              font-size: 10px;
              font-weight: 800;
              letter-spacing: 0.4px;
            }
            .msg-att-meta { min-width: 0; flex: 1; }
            .msg-att-name {
              font-size: 13px;
              font-weight: 750;
              color: var(--text);
              white-space: nowrap;
              overflow: hidden;
              text-overflow: ellipsis;
            }
            .msg-att-sub {
              margin-top: 3px;
              font-size: 10px;
              color: var(--muted);
              white-space: nowrap;
              overflow: hidden;
              text-overflow: ellipsis;
            }
            .msg-att-state {
              flex-shrink: 0;
              padding: 3px 7px;
              border-radius: 999px;
              border: 1px solid rgba(0, 209, 255, 0.35);
              color: var(--cyan);
              background: rgba(0, 209, 255, 0.08);
              font-size: 9px;
              font-weight: 700;
            }
            .msg-att-actions {
              display: grid;
              grid-template-columns: repeat(2, minmax(0, 1fr));
              gap: 8px;
            }
            .msg-att-actions .btn {
              min-height: 38px;
              border-radius: 9px;
              font-size: 10px;
            }
            .upload-bubble {
              display: flex;
              align-items: center;
              gap: 11px;
              min-width: min(320px, 78vw);
            }
            .upload-spinner {
              width: 26px;
              height: 26px;
              flex-shrink: 0;
              border-radius: 50%;
              border: 3px solid rgba(0, 255, 156, 0.18);
              border-top-color: var(--mint);
              animation: upload-spin 0.8s linear infinite;
            }
            .upload-state-name {
              color: var(--text);
              font-size: 12px;
              font-weight: 700;
              white-space: nowrap;
              overflow: hidden;
              text-overflow: ellipsis;
            }
            .upload-state-sub { color: var(--muted); font-size: 10px; margin-top: 2px; }
            @keyframes upload-spin { to { transform: rotate(360deg); } }
            .empty {
              border: 1px dashed var(--border);
              border-radius: 10px;
              padding: 16px 12px;
              text-align: center;
              color: var(--muted);
              font-size: 12px;
            }
            .empty-title { color: var(--mint); font-weight: 700; margin-bottom: 4px; }

            /* Audio-only Live Class & Call Stage */
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
            .live-stage-panel.meeting-active {
              flex: 1 1 auto;
              min-height: 0;
              max-height: none;
              padding: 14px;
              overflow-y: auto;
              align-content: start;
              background: radial-gradient(circle at 50% 12%, #182432 0%, #0b1017 48%, #05070a 100%);
              border-bottom: 0;
            }
            .wa-main-pane.meeting-mode #active-channel-panel,
            .wa-main-pane.meeting-mode #media-drawer,
            .wa-app.meeting-running #channels-sidebar,
            .wa-app.meeting-running #sidebar-backdrop {
              display: none !important;
            }
            .meeting-active .live-head {
              position: sticky;
              top: -14px;
              z-index: 8;
              margin: -14px -14px 0;
              padding: 12px 14px;
              background: rgba(15, 21, 29, 0.96);
              border-bottom: 1px solid var(--border);
              backdrop-filter: blur(10px);
            }
            .meeting-active #live-active-controls,
            .meeting-active #direct-incoming-controls {
              position: sticky;
              bottom: -14px;
              z-index: 9;
              order: 20;
              justify-content: center;
              margin: 0 -14px -14px;
              padding: 12px 14px;
              background: rgba(15, 21, 29, 0.96);
              border-top: 1px solid var(--border);
              backdrop-filter: blur(10px);
            }
            .live-head {
              display: flex;
              flex-wrap: wrap;
              align-items: center;
              justify-content: space-between;
              gap: 8px;
            }
            .live-participants-grid {
              display: grid;
              grid-template-columns: repeat(auto-fit, minmax(min(180px, 100%), 1fr));
              gap: 10px;
              align-content: start;
            }
            .meeting-active .live-participants-grid {
              grid-template-columns: repeat(auto-fit, minmax(min(220px, 100%), 1fr));
            }
            .live-participant-card {
              min-height: 142px;
              display: flex;
              flex-direction: column;
              align-items: center;
              justify-content: center;
              gap: 7px;
              padding: 14px;
              text-align: center;
              color: var(--muted);
              background: #080c12;
              border: 1px solid var(--border);
              border-radius: 12px;
            }
            .live-participant-card.teacher { border-color: var(--mint); }
            .live-participant-avatar {
              width: 58px;
              height: 58px;
              display: flex;
              align-items: center;
              justify-content: center;
              border-radius: 50%;
              border: 2px solid var(--cyan);
              color: var(--cyan);
              background: rgba(56, 189, 248, 0.12);
              font-size: 19px;
              font-weight: 800;
            }
            .live-participant-card.teacher .live-participant-avatar {
              border-color: var(--mint);
              color: var(--mint);
              background: rgba(0, 255, 156, 0.12);
            }
            .live-participant-name { color: var(--text); font-size: 13px; font-weight: 700; }
            .live-participant-state { font-size: 11px; }
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
            @media (max-width: 640px) {
              .wa-topbar { gap: 7px; padding: 8px; }
              .wa-top-avatar { width: 34px; height: 34px; }
              .wa-top-title { font-size: 13px; }
              .wa-top-sub { font-size: 9px; }
              .top-actions { gap: 4px; }
              .call-icon-btn { width: 38px; height: 38px; border-radius: 10px; }
              .call-icon-btn svg { width: 19px; height: 19px; }
              .bubble.has-attachment { width: 94%; max-width: 94%; }
              .msg-att-state { display: none; }
              .live-stage-panel.meeting-active { padding: 10px; }
              .meeting-active .live-head {
                top: -10px;
                margin: -10px -10px 0;
                padding: 9px 10px;
              }
              .meeting-active .live-participants-grid {
                grid-template-columns: repeat(auto-fit, minmax(min(140px, 100%), 1fr));
              }
              .live-participant-card { min-height: 122px; }
              .meeting-active #live-active-controls,
              .meeting-active #direct-incoming-controls {
                bottom: -10px;
                margin: 0 -10px -10px;
                padding: 10px;
              }
              .meeting-active #live-active-controls .btn {
                min-height: 44px;
                flex: 1 1 92px;
              }
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
          <div id="portal-access-dialog" class="portal-access-backdrop" role="dialog" aria-modal="true" aria-labelledby="portal-access-title">
            <div class="portal-access-dialog">
              <div class="portal-access-mark">BV</div>
              <div id="portal-access-title" class="portal-access-title">OPEN BITVOID GATEWAY</div>
              <div id="portal-access-copy" class="portal-access-copy">Continue as a temporary guest, or log in with your BitVoid app User ID.</div>
              <input id="portal-login-user-id" class="portal-login-input hidden" type="text" placeholder="User ID (for example user-7724)" autocomplete="username">
              <div id="portal-access-error" class="portal-access-error hidden"></div>
              <div class="portal-access-actions">
                <button id="portal-guest-btn" class="portal-guest-btn" type="button">GUEST</button>
                <button id="portal-login-btn" class="portal-login-btn" type="button">LOGIN</button>
              </div>
              <button id="portal-login-back-btn" class="portal-guest-btn portal-login-back hidden" type="button">BACK</button>
            </div>
          </div>

          <div id="wa-app" class="wa-app">
            <!-- Slide-out Drawer Backdrop & Sidebar -->
            <div id="sidebar-backdrop" class="sidebar-backdrop hidden"></div>
            <aside id="channels-sidebar" aria-label="Channels navigation">
              <div class="drawer-header">
                <div class="drawer-brand">BITVOID // CHANNELS</div>
                <button id="close-sidebar-btn" class="btn-ghost" type="button">CLOSE</button>
              </div>

              <div class="drawer-meta-card">
                <div>MODE: <strong>WPA2 PROTECTED / 4KB PADDED</strong></div>
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

              <section class="pchat-box" aria-labelledby="pchat-title">
                <div class="pchat-head">
                  <div class="pchat-head-copy">
                    <div id="pchat-title" class="section-title">P.CHAT</div>
                    <p>PRIVATE USER CHAT · VOICE CALLS</p>
                  </div>
                  <button id="pchat-add-user-btn" class="pchat-add-btn" type="button" title="Add user by exact User ID" aria-label="Add user by exact User ID">+</button>
                </div>
                <div class="pchat-identity">
                  <div class="pchat-you">
                    YOU · <strong id="pchat-my-username">@web</strong>
                    <div id="pchat-discovery-note" class="pchat-discovery-note">Visible automatically on this node</div>
                  </div>
                  <button id="pchat-discoverable-toggle" class="pchat-switch" type="button" role="switch" aria-checked="true" aria-label="Show my User ID automatically"></button>
                </div>
                <div class="pchat-users-label">USERS ON THIS NODE</div>
                <div id="pchat-users-list" class="pchat-users">
                  <div class="pchat-empty">Looking for visible users on this BitVoid node…</div>
                </div>
                <div id="pchat-error" class="err hidden"></div>
              </section>

              <div class="unlock-box">
                <div class="section-title" style="margin:0;color:var(--cyan);">JOIN LIVE CLASS (STUDENT ID)</div>
                <input id="sidebar-class-code-input" type="text" placeholder="Enter Live Class ID (e.g. CLS-XXXX-XX)" autocomplete="off">
                <button id="sidebar-join-class-btn" class="btn-primary" type="button">JOIN BY CLASS ID</button>
                <div id="sidebar-class-error" class="err hidden"></div>
              </div>
            </aside>

            <div id="pchat-add-dialog" class="pchat-dialog-backdrop hidden" role="dialog" aria-modal="true" aria-labelledby="pchat-dialog-title">
              <div class="pchat-dialog">
                <div id="pchat-dialog-title" class="pchat-dialog-title">ADD PRIVATE CHAT</div>
                <div class="pchat-dialog-hint">Enter the exact User ID. This also works when that user disabled automatic display.</div>
                <input id="pchat-exact-user-id" type="text" placeholder="User ID (e.g. user-4210)" autocomplete="off">
                <div id="pchat-dialog-error" class="err hidden"></div>
                <div class="pchat-dialog-actions">
                  <button id="pchat-dialog-cancel" class="btn-ghost" type="button">CANCEL</button>
                  <button id="pchat-dialog-open" class="btn-primary" type="button">OPEN CHAT</button>
                </div>
              </div>
            </div>

            <!-- Main Chat & Media Workspace -->
            <div id="wa-main-pane" class="wa-main-pane">
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
                  <button id="live-class-btn" class="btn-ghost hidden" type="button">LIVE CLASS / CALL</button>
                  <button id="voice-call-btn" class="call-icon-btn hidden" type="button" title="Start voice call" aria-label="Start voice call">
                    <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7.2 3.5 9.7 7.9 7.9 10c1.3 2.8 3.4 4.9 6.2 6.2l2.1-1.8 4.4 2.5-.7 3.1c-.2.8-.9 1.4-1.8 1.4C9.6 20.9 3.1 14.4 2.6 5.9c0-.9.6-1.6 1.4-1.8l3.2-.6Z"/></svg>
                  </button>
                  <button id="rtc-leave-btn" class="btn-danger hidden" type="button">LEAVE</button>
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

              <!-- Live Class & Voice Call WebRTC Stage -->
              <section id="live-stage-panel" class="live-stage-panel hidden">
                <div class="live-head">
                  <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">
                    <span id="live-mode-badge" class="pill">AUDIO CLASS &amp; CALL</span>
                    <span id="live-class-code-pill" class="live-class-code-pill hidden">
                      <span>CLASS ID: <strong id="live-class-code-text">-</strong></span>
                      <button id="live-copy-code-btn" class="btn-ghost" style="padding:2px 6px;font-size:10px;" type="button">COPY</button>
                    </span>
                    <span id="live-room-status" class="muted-note">Local Wi-Fi WebRTC (/ws/signaling · zero external servers)</span>
                  </div>
                  <button id="close-live-stage-btn" class="btn-ghost" type="button">HIDE</button>
                </div>

                <div id="live-launcher-controls" class="live-controls-bar">
                  <button id="rtc-start-class-host-btn" class="btn-primary" type="button">START LIVE AUDIO CLASS (HOST)</button>
                  <button id="rtc-join-class-student-btn" class="btn-cyan" type="button">JOIN LIVE AUDIO CLASS</button>
                  <button id="rtc-start-call-btn" class="btn-amber" type="button">START AUDIO CALL</button>
                </div>

                <div id="live-join-id-row" class="live-join-id-row hidden">
                  <input id="rtc-student-class-id-input" type="text" placeholder="Enter Live Class ID (e.g. CLS-ABCD-12)" autocomplete="off">
                  <button id="rtc-student-class-id-submit" class="btn-cyan" type="button">ENTER CLASS</button>
                  <button id="rtc-student-class-id-cancel" class="btn-ghost" type="button">CANCEL</button>
                </div>

                <div id="live-active-controls" class="live-controls-bar hidden">
                  <button id="rtc-mute-btn" class="btn-ghost" type="button">MIC ON</button>
                  <button id="rtc-end-session-btn" class="btn-danger hidden" type="button">END CLASS</button>
                </div>

                <div id="direct-incoming-controls" class="live-controls-bar hidden">
                  <button id="direct-decline-call-btn" class="btn-danger" type="button">DECLINE</button>
                  <button id="direct-accept-call-btn" class="btn-primary" type="button">ACCEPT &amp; JOIN</button>
                </div>

                <div id="live-error-msg" class="err hidden"></div>

                <div class="section-title">PARTICIPANTS</div>
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
              var appRoot = document.getElementById('wa-app');
              var sidebar = document.getElementById('channels-sidebar');
              var backdrop = document.getElementById('sidebar-backdrop');
              var menuToggleBtn = document.getElementById('menu-toggle-btn');
              var closeSidebarBtn = document.getElementById('close-sidebar-btn');
              var portalAccessDialog = document.getElementById('portal-access-dialog');
              var portalLoginUserId = document.getElementById('portal-login-user-id');
              var portalAccessCopy = document.getElementById('portal-access-copy');
              var portalAccessError = document.getElementById('portal-access-error');
              var portalGuestBtn = document.getElementById('portal-guest-btn');
              var portalLoginBtn = document.getElementById('portal-login-btn');
              var portalLoginBackBtn = document.getElementById('portal-login-back-btn');

              var generalChatEl = document.getElementById('general-chat-list');
              var publicChannelsEl = document.getElementById('public-channels-list');
              var privateChannelsEl = document.getElementById('private-channels-list');
              var privateIdInput = document.getElementById('private-channel-id');
              var privateKeyInput = document.getElementById('private-channel-key');
              var unlockBtn = document.getElementById('unlock-channel-btn');
              var channelErr = document.getElementById('channel-error');
              var pchatMyUsername = document.getElementById('pchat-my-username');
              var pchatDiscoveryNote = document.getElementById('pchat-discovery-note');
              var pchatDiscoverableToggle = document.getElementById('pchat-discoverable-toggle');
              var pchatUsersList = document.getElementById('pchat-users-list');
              var pchatAddUserBtn = document.getElementById('pchat-add-user-btn');
              var pchatError = document.getElementById('pchat-error');
              var pchatAddDialog = document.getElementById('pchat-add-dialog');
              var pchatExactUserInput = document.getElementById('pchat-exact-user-id');
              var pchatDialogError = document.getElementById('pchat-dialog-error');
              var pchatDialogCancel = document.getElementById('pchat-dialog-cancel');
              var pchatDialogOpen = document.getElementById('pchat-dialog-open');
              var portalUsername = '';
              var portalDiscoverable = true;

              var activeAvatar = document.getElementById('active-channel-avatar');
              var activeTitle = document.getElementById('active-channel-title');
              var activeBadge = document.getElementById('active-channel-badge');
              var activeCallsign = document.getElementById('active-callsign');
              var wsStatusEl = document.getElementById('ws-status');
              var peersEl = document.getElementById('channel-peers');
              var exitChannelBtn = document.getElementById('exit-channel-btn');
              var liveClassBtn = document.getElementById('live-class-btn');
              var voiceCallBtn = document.getElementById('voice-call-btn');
              var mainPane = document.getElementById('wa-main-pane');
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
              var rtcLeaveBtn = document.getElementById('rtc-leave-btn');
              var rtcEndSessionBtn = document.getElementById('rtc-end-session-btn');
              var directIncomingControls = document.getElementById('direct-incoming-controls');
              var directDeclineCallBtn = document.getElementById('direct-decline-call-btn');
              var directAcceptCallBtn = document.getElementById('direct-accept-call-btn');
              var liveErrorMsg = document.getElementById('live-error-msg');
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
              var rtcRemoteAudio = {};
              var rtcParticipantList = [];
              var rtcPeerId = '';
              var rtcRoomId = '';
              var rtcMode = 'AUDIO_CALL';
              var rtcRole = 'SPEAKER';
              var rtcClassCode = '';
              var rtcLocalStream = null;
              var rtcMuted = false;
              var directCallPromptOpen = false;
              var directCallConnecting = false;
              var directCallIncomingOpenPending = false;
              var directCallLastPhase = 'IDLE';
              var suggestedLoginUsername = '';
              var portalSessionStarted = false;
              var portalLoginStep = false;
              try {
                var queryUser = new URLSearchParams(location.search).get('username');
                var storedUser = String(queryUser || localStorage.getItem('bv_username') || '')
                  .trim().replace(/^@/, '');
                if (/^[A-Za-z0-9._-]{2,24}$/.test(storedUser)) {
                  suggestedLoginUsername = storedUser;
                }
                portalDiscoverable = localStorage.getItem('bv_pchat_discoverable') !== 'false';
              } catch (e) {
                suggestedLoginUsername = '';
                portalDiscoverable = true;
              }
              portalLoginUserId.value = suggestedLoginUsername;
              portalLoginBtn.textContent = 'LOGIN';
              pchatMyUsername.textContent = '@choose-access';

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

              function setPortalAccessError(message) {
                portalAccessError.textContent = message || '';
                portalAccessError.classList.toggle('hidden', !message);
              }

              function showPortalLoginStep(show) {
                portalLoginStep = !!show;
                portalLoginUserId.classList.toggle('hidden', !portalLoginStep);
                portalGuestBtn.classList.toggle('hidden', portalLoginStep);
                portalLoginBackBtn.classList.toggle('hidden', !portalLoginStep);
                portalAccessCopy.textContent = portalLoginStep
                  ? 'Enter a User ID that is currently active on this BitVoid node or gateway.'
                  : 'Continue as a temporary guest, or log in with your BitVoid app User ID.';
                setPortalAccessError('');
                if (portalLoginStep) {
                  setTimeout(function () { portalLoginUserId.focus(); }, 0);
                }
              }

              function submitPortalLogin() {
                var clean = String(portalLoginUserId.value || '').trim().replace(/^@/, '');
                if (!/^[A-Za-z0-9._-]{2,24}$/.test(clean)) {
                  setPortalAccessError('Enter a valid User ID using 2–24 letters, numbers, dots, underscores, or hyphens.');
                  return;
                }
                portalLoginBtn.disabled = true;
                portalLoginBtn.textContent = 'CHECKING…';
                fetch('/api/users/status?username=' + encodeURIComponent(clean), { cache: 'no-store' })
                  .then(function (response) {
                    if (!response.ok) throw new Error('Unable to verify this User ID.');
                    return response.json();
                  })
                  .then(function (data) {
                    if (!data || data.available !== true) {
                      throw new Error('That User ID is not active on this node or gateway.');
                    }
                    startPortalSession(clean, false);
                  })
                  .catch(function (error) {
                    setPortalAccessError(error && error.message
                      ? error.message
                      : 'Unable to verify this User ID.');
                  })
                  .finally(function () {
                    portalLoginBtn.disabled = false;
                    portalLoginBtn.textContent = 'LOGIN';
                  });
              }

              function startPortalSession(username, asGuest) {
                var clean = String(username || '').trim().replace(/^@/, '');
                if (!/^[A-Za-z0-9._-]{2,24}$/.test(clean)) {
                  setPortalAccessError('Enter a valid User ID using 2–24 letters, numbers, dots, underscores, or hyphens.');
                  return;
                }
                if (portalSessionStarted) return;
                portalSessionStarted = true;
                portalUsername = clean;
                portalDiscoverable = asGuest ? false : portalDiscoverable;
                pchatMyUsername.textContent = '@' + portalUsername;
                if (!asGuest) {
                  try { localStorage.setItem('bv_username', portalUsername); } catch (e) {}
                }
                updatePchatDiscoverabilityUi();
                portalAccessDialog.classList.add('hidden');
                registerBrowserPresence();
                pollChannelsAndFeed();
                setInterval(pollChannelsAndFeed, 3000);
                setInterval(registerBrowserPresence, 15000);
              }

              function fmtBytes(b) {
                if (b < 1024) return b + ' B';
                var kb = b / 1024;
                if (kb < 1024) return kb.toFixed(1) + ' KB';
                var mb = kb / 1024;
                if (mb < 1024) return mb.toFixed(1) + ' MB';
                return (mb / 1024).toFixed(2) + ' GB';
              }

              function showPchatError(message, inDialog) {
                var target = inDialog ? pchatDialogError : pchatError;
                target.textContent = message || '';
                target.classList.toggle('hidden', !message);
              }

              function updatePchatDiscoverabilityUi() {
                pchatDiscoverableToggle.setAttribute('aria-checked', portalDiscoverable ? 'true' : 'false');
                pchatDiscoveryNote.textContent = portalDiscoverable
                  ? 'Visible automatically on this node'
                  : 'Hidden from lists · exact User ID still works';
              }

              function renderPchatUsers(users) {
                var visibleUsers = (Array.isArray(users) ? users : []).filter(function (user) {
                  return String(user).toLowerCase() !== portalUsername.toLowerCase();
                }).filter(function (user, index, all) {
                  return all.findIndex(function (candidate) {
                    return String(candidate).toLowerCase() === String(user).toLowerCase();
                  }) === index;
                }).sort(function (a, b) {
                  return String(a).toLowerCase().localeCompare(String(b).toLowerCase());
                });
                if (visibleUsers.length === 0) {
                  pchatUsersList.innerHTML = '<div class="pchat-empty">No visible users are online. Tap + to add someone by their exact User ID.</div>';
                  return;
                }
                pchatUsersList.innerHTML = visibleUsers.map(function (user) {
                  var activeCls = activeDm && String(activeDm.peerUsername).toLowerCase() === String(user).toLowerCase()
                    ? ' active' : '';
                  return '<button type="button" class="pchat-user-row' + activeCls + '" data-pchat-user="' + esc(user) + '">' +
                    '<span class="pchat-user-avatar">@</span>' +
                    '<span class="pchat-user-copy"><span class="pchat-user-name">@' + esc(user) + '</span>' +
                    '<span class="pchat-user-sub">Online · Tap to chat or call</span></span></button>';
                }).join('');
                Array.prototype.forEach.call(pchatUsersList.querySelectorAll('[data-pchat-user]'), function (button) {
                  button.onclick = function () {
                    showPchatError('', false);
                    openDirectChat(portalUsername, button.getAttribute('data-pchat-user') || '');
                  };
                });
              }

              function refreshPchatUsers() {
                return fetch('/api/users', { cache: 'no-store' })
                  .then(function (r) {
                    if (!r.ok) throw new Error('P.Chat discovery failed');
                    return r.json();
                  })
                  .then(function (data) {
                    renderPchatUsers(data.users || []);
                    return data.users || [];
                  })
                  .catch(function () {
                    pchatUsersList.innerHTML = '<div class="pchat-empty">Unable to refresh P.Chat users. Retrying on this node…</div>';
                    return [];
                  });
              }

              function refreshPeerPresence(rawTarget) {
                var target = String(rawTarget || (activeDm ? activeDm.peerUsername : ''))
                  .trim().replace(/^@/, '');
                if (!target) return Promise.resolve(false);
                return fetch('/api/users/status?username=' + encodeURIComponent(target), { cache: 'no-store' })
                  .then(function (r) {
                    if (!r.ok) throw new Error('Presence check failed');
                    return r.json();
                  })
                  .then(function (data) {
                    var available = !!data.available;
                    if (activeDm && String(activeDm.peerUsername).toLowerCase() === target.toLowerCase()) {
                      voiceCallBtn.disabled = !available;
                    }
                    return available;
                  })
                  .catch(function () {
                    if (activeDm && String(activeDm.peerUsername).toLowerCase() === target.toLowerCase()) {
                      voiceCallBtn.disabled = true;
                    }
                    return false;
                  });
              }

              function registerBrowserPresence() {
                if (!portalUsername) return Promise.resolve();
                return fetch('/api/users/register', {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json' },
                  body: JSON.stringify({
                    username: portalUsername,
                    discoverable: portalDiscoverable
                  }),
                  cache: 'no-store'
                }).then(function (response) {
                  if (!response.ok) throw new Error('Unable to register P.Chat identity');
                  return response.json();
                }).then(function (data) {
                  renderPchatUsers(data.users || []);
                  if (activeDm) return refreshPeerPresence(activeDm.peerUsername);
                }).catch(function () {});
              }

              updatePchatDiscoverabilityUi();

              function friendlyFileType(name, mime) {
                var m = String(mime || '').toLowerCase();
                var lower = String(name || '').toLowerCase();
                if (m.indexOf('video/') === 0) return 'Video';
                if (m.indexOf('audio/') === 0) return 'Audio';
                if (m.indexOf('image/') === 0) return 'Image';
                if (m.indexOf('pdf') !== -1 || lower.endsWith('.pdf')) return 'PDF document';
                if (m.indexOf('word') !== -1 || lower.endsWith('.doc') || lower.endsWith('.docx')) return 'Word document';
                if (m.indexOf('sheet') !== -1 || m.indexOf('excel') !== -1 || lower.endsWith('.xls') || lower.endsWith('.xlsx') || lower.endsWith('.csv')) return 'Spreadsheet';
                if (m.indexOf('presentation') !== -1 || m.indexOf('powerpoint') !== -1 || lower.endsWith('.ppt') || lower.endsWith('.pptx')) return 'Presentation';
                if (m.indexOf('text/') === 0 || lower.endsWith('.txt')) return 'Text document';
                if (m.indexOf('zip') !== -1 || m.indexOf('archive') !== -1 || lower.endsWith('.zip') || lower.endsWith('.rar') || lower.endsWith('.7z')) return 'Archive';
                return 'File';
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
                  ' Share videos and files securely from the composer below.';
                chatBox.appendChild(banner);
              }

              function buildAttachmentHtml(att) {
                if (!att || !att.name) return '';
                var token = activeDm ? activeDm.token : (activeChannel ? activeChannel.token : '');
                var q = token ? ('?token=' + encodeURIComponent(token)) : '';
                var sUrl = (att.streamUrl || '') + q;
                var dUrl = (att.downloadUrl || att.streamUrl || '') + q;
                var rawExt = att.name.indexOf('.') >= 0 ? att.name.split('.').pop() : '';
                var ext = (rawExt || 'FILE').toUpperCase().slice(0, 6);
                var typeLabel = friendlyFileType(att.name, att.mimeType);
                return '<div class="msg-attachment">' +
                  '<div class="msg-att-head">' +
                    '<span class="msg-att-icon">' + esc(ext) + '</span>' +
                    '<div class="msg-att-meta">' +
                      '<div class="msg-att-name" title="' + esc(att.name) + '">' + esc(att.name) + '</div>' +
                      '<div class="msg-att-sub">' + esc(typeLabel) + ' · ' + fmtBytes(att.size || 0) + '</div>' +
                    '</div>' +
                    '<span class="msg-att-state">LOCAL FILE</span>' +
                  '</div>' +
                  '<div class="msg-att-actions">' +
                    '<a class="btn btn-primary" href="' + esc(sUrl) + '" target="_blank" rel="noopener">OPEN</a>' +
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
                  '<div class="bubble' + (msg.attachment ? ' has-attachment' : '') + '">' +
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

              function appendUploadProgress(file) {
                var row = document.createElement('div');
                row.className = 'msg-row out';
                row.innerHTML =
                  '<div class="bubble upload-bubble">' +
                    '<span class="upload-spinner" aria-hidden="true"></span>' +
                    '<div style="min-width:0;flex:1;">' +
                      '<div class="upload-state-name">' + esc(file.name || 'Local file') + '</div>' +
                      '<div class="upload-state-sub">Uploading ' + fmtBytes(file.size || 0) + ' securely to this node...</div>' +
                    '</div>' +
                  '</div>';
                chatBox.appendChild(row);
                chatBox.scrollTop = chatBox.scrollHeight;
                return row;
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
                var isLiveAudioClass = !!data.classCode;
                seenMsgIds = {};
                chatBox.innerHTML = '';
                var label = data.name || data.channelId;
                activeTitle.textContent = label;
                activeCallsign.textContent = data.callsign || '-';
                chatInput.placeholder = 'Message ' + label + '...';
                liveClassBtn.textContent = 'LIVE CLASS / CALL';
                liveClassBtn.classList.remove('active');
                var isGeneralChannel = data.channelId === '${BitVoidChannelManager.DEFAULT_PUBLIC_CHANNEL_ID}';
                liveClassBtn.classList.toggle('hidden', isGeneralChannel);
                voiceCallBtn.classList.add('hidden');
                rtcStartCallBtn.classList.toggle('hidden', isGeneralChannel);
                if (isGeneralChannel && !rtcSocket) {
                  liveStagePanel.classList.add('hidden');
                  setMeetingStageActive(false);
                }
                updateRtcControlsUi();
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
                if (isLiveAudioClass) {
                  if (activeSocket) {
                    try { activeSocket.close(); } catch (e) {}
                    activeSocket = null;
                  }
                  wsStatusEl.textContent = 'AUDIO';
                  currentChannelMediaSig = '';
                  channelMediaFeed.innerHTML = '';
                } else {
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
                }
                highlightActiveSidebar();
                closeSidebar();
              }

              function joinChannel(channelId, privateKey) {
                channelErr.classList.add('hidden');
                var myU = portalUsername;
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
                var myU = portalUsername;
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
                showPchatError('', false);
                showPchatError('', true);
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
                    directCallPromptOpen = false;
                    directCallConnecting = false;
                    directCallLastPhase = 'IDLE';
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
                    liveClassBtn.classList.add('hidden');
                    voiceCallBtn.classList.remove('hidden');
                    rtcStartCallBtn.classList.add('hidden');
                    voiceCallBtn.classList.remove('active');
                    refreshPeerPresence(data.peerUsername);
                    exitChannelBtn.classList.remove('hidden');
                    pchatAddDialog.classList.add('hidden');
                    refreshPchatUsers();
                    var banner = document.createElement('div');
                    banner.className = 'wa-welcome-pill';
                    banner.innerHTML = '<strong>Direct Chat with @' + esc(data.peerUsername) + '</strong> · You: <strong>@' + esc(data.myUsername) + '</strong> · Use the phone icon above to start a voice call.';
                    chatBox.appendChild(banner);
                    (data.messages || []).forEach(appendChatMessage);
                    connectDirectWs(data.dmId, data.token);
                    highlightActiveSidebar();
                    closeSidebar();
                  })
                  .catch(function (err) {
                    var message = err.message || 'Unable to start private chat';
                    showPchatError(message, pchatAddDialog.classList.contains('hidden') === false);
                    if (pchatAddDialog.classList.contains('hidden')) showPchatError(message, false);
                  })
                  .then(function () { directCallIncomingOpenPending = false; });
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
                      if (!st || !activeDm) return;
                      var phase = st.callPhase || 'IDLE';
                      directCallLastPhase = phase;
                      if (phase === 'RINGING' && st.isOutgoing) {
                        voiceCallBtn.classList.add('active');
                        liveStagePanel.classList.remove('hidden');
                        setMeetingStageActive(true);
                        liveRoomStatusEl.textContent = 'Ringing @' + activeDm.peerUsername + ' · your meeting stage is ready';
                        renderRtcParticipants(rtcParticipantList);
                      } else if (phase === 'RINGING' && st.isIncoming && !rtcSocket) {
                        directCallPromptOpen = true;
                        liveStagePanel.classList.remove('hidden');
                        setMeetingStageActive(true);
                        liveRoomStatusEl.textContent = 'Incoming voice call from @' + st.callerUsername;
                        updateRtcControlsUi();
                        renderRtcParticipants(rtcParticipantList);
                      } else if (phase === 'ACCEPTED') {
                        directCallPromptOpen = false;
                        updateRtcControlsUi();
                        if (!rtcSocket && !directCallConnecting &&
                            st.callerUsername && st.callerUsername.toLowerCase() === String(activeDm.myUsername).toLowerCase()) {
                          directCallConnecting = true;
                          joinRtcSession('AUDIO_CALL', 'SPEAKER', true, null, null);
                        } else if (rtcSocket) {
                          liveRoomStatusEl.textContent = 'CALL WITH @' + activeDm.peerUsername + ' · Local P2P';
                        }
                      } else if (phase === 'DECLINED') {
                        directCallPromptOpen = false;
                        directCallConnecting = false;
                        if (rtcSocket) leaveRtcSession();
                        voiceCallBtn.classList.remove('active');
                        setMeetingStageActive(false);
                        liveStagePanel.classList.add('hidden');
                        showPchatError('@' + activeDm.peerUsername + ' declined the call.', false);
                      } else if (phase === 'MISSED' || phase === 'CANCELED' || phase === 'ENDED' || phase === 'IDLE') {
                        directCallPromptOpen = false;
                        directCallConnecting = false;
                        if (rtcSocket) leaveRtcSession();
                        voiceCallBtn.classList.remove('active');
                        voiceCallBtn.title = 'Start voice call';
                        if (phase !== 'IDLE') {
                          setMeetingStageActive(false);
                          liveStagePanel.classList.add('hidden');
                        }
                        updateRtcControlsUi();
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
                    var owned = !!c.isOwner && !!c.channelId;
                    var attrs = owned
                      ? (' data-channel-id="' + esc(c.channelId) + '"')
                      : (' data-locked-name="' + esc(c.name) + '"');
                    var cls = 'wa-chan-row' + (owned ? '' : ' wa-chan-locked');
                    var sub = owned
                      ? ((c.isHidden ? 'Your hidden channel' : 'Your private channel') + ' · Tap to open (' + c.mediaCount + ' media)')
                      : ('Private · Tap &amp; enter ID (' + c.mediaCount + ' media)');
                    return '<button type="button" class="' + cls + '"' + attrs + '>' +
                      '<div class="wa-avatar wa-avatar-lock">ID</div>' +
                      '<div class="wa-chan-info">' +
                        '<div class="wa-chan-title">' + esc(c.name) + '</div>' +
                        '<div class="wa-chan-sub">' + sub + '</div>' +
                      '</div></button>';
                  }).join('');
                }
                bindSidebarClicks();
              }

              function pollChannelsAndFeed() {
                refreshPchatUsers();
                if (activeDm) refreshPeerPresence(activeDm.peerUsername);
                var incomingUsername = portalUsername;
                if (incomingUsername && !directCallIncomingOpenPending) {
                  fetch('/api/calls/incoming?username=' + encodeURIComponent(incomingUsername), { cache: 'no-store' })
                    .then(function (r) { return r.ok ? r.json() : null; })
                    .then(function (incoming) {
                      if (!incoming || !incoming.hasIncomingCall || !incoming.callerUsername) return;
                      if (!activeDm || activeDm.dmId !== incoming.dmId) {
                        directCallIncomingOpenPending = true;
                        openDirectChat(incomingUsername, incoming.callerUsername);
                      }
                    }).catch(function () {});
                }

                var channelUsername = portalUsername;
                var channelListUrl = '/api/channels' + (channelUsername ? ('?username=' + encodeURIComponent(channelUsername)) : '');
                fetch(channelListUrl, { cache: 'no-store' })
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
                    if (publicCountEl) publicCountEl.textContent = String(list.length);
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

              function isGeneralChatActive() {
                return !!activeChannel &&
                  activeChannel.channelId === '${BitVoidChannelManager.DEFAULT_PUBLIC_CHANNEL_ID}';
              }

              function setMeetingStageActive(active) {
                liveStagePanel.classList.toggle('meeting-active', !!active);
                mainPane.classList.toggle('meeting-mode', !!active);
                appRoot.classList.toggle('meeting-running', !!active);
                closeLiveStageBtn.textContent = active ? 'BACK TO CHAT' : 'HIDE';
              }

              function updateRtcControlsUi() {
                var joined = !!rtcSocket;
                var generalChat = isGeneralChatActive();
                if (activeDm || generalChat) {
                  rtcStartClassHostBtn.classList.add('hidden');
                  rtcJoinClassStudentBtn.classList.add('hidden');
                  rtcStartCallBtn.classList.add('hidden');
                } else {
                  rtcStartClassHostBtn.classList.remove('hidden');
                  rtcJoinClassStudentBtn.classList.remove('hidden');
                  rtcStartCallBtn.classList.remove('hidden');
                  rtcStartCallBtn.textContent = 'START AUDIO CALL';
                }
                directIncomingControls.classList.toggle(
                  'hidden',
                  !(activeDm && directCallPromptOpen && directCallLastPhase === 'RINGING' && !joined)
                );
                closeLiveStageBtn.classList.toggle('hidden', joined);
                if (joined) {
                  var liveClassMode = rtcMode === 'LIVE_CLASSROOM';
                  var isClassHost = liveClassMode && rtcRole === 'HOST_TEACHER';
                  liveLauncherControls.classList.add('hidden');
                  liveJoinIdRow.classList.add('hidden');
                  liveActiveControls.classList.remove('hidden');
                  rtcMuteBtn.textContent = rtcMuted ? 'MIC MUTED' : 'MIC ON';
                  rtcLeaveBtn.classList.toggle('hidden', isClassHost);
                  rtcEndSessionBtn.classList.toggle('hidden', !isClassHost);
                } else {
                  liveLauncherControls.classList.toggle('hidden', !!activeDm || generalChat);
                  liveActiveControls.classList.add('hidden');
                  rtcLeaveBtn.classList.add('hidden');
                  rtcEndSessionBtn.classList.add('hidden');
                }
              }

              function renderRtcParticipants(peers) {
                rtcParticipantList = Array.isArray(peers) ? peers.slice() : [];
                var displayPeers = rtcParticipantList.slice();
                if (!displayPeers.length && rtcSocket && rtcPeerId) {
                  displayPeers.push({
                    peerId: rtcPeerId,
                    displayName: portalUsername || (activeDm ? activeDm.myUsername : 'You'),
                    role: rtcRole,
                    isMuted: rtcMuted
                  });
                } else if (!displayPeers.length && activeDm && directCallLastPhase === 'RINGING') {
                  displayPeers.push({
                    peerId: 'pending_remote',
                    displayName: '@' + activeDm.peerUsername,
                    role: 'SPEAKER',
                    isMuted: true
                  });
                }
                displayPeers.sort(function (a, b) {
                  var hostOrder = Number(b.role === 'HOST_TEACHER') - Number(a.role === 'HOST_TEACHER');
                  if (hostOrder) return hostOrder;
                  return String(a.displayName || a.peerId).localeCompare(String(b.displayName || b.peerId));
                });
                liveParticipantsGrid.innerHTML = '';
                displayPeers.forEach(function (peer) {
                  var isSelf = peer.peerId === rtcPeerId;
                  var isHost = peer.role === 'HOST_TEACHER';
                  var name = peer.displayName || peer.peerId || 'Participant';
                  var card = document.createElement('div');
                  card.className = 'live-participant-card' + (isHost ? ' teacher' : '');
                  var avatar = document.createElement('div');
                  avatar.className = 'live-participant-avatar';
                  avatar.textContent = String(name).slice(0, 2).toUpperCase();
                  var title = document.createElement('div');
                  title.className = 'live-participant-name';
                  title.textContent = (isSelf ? 'YOU · ' : '') + name;
                  var state = document.createElement('div');
                  state.className = 'live-participant-state';
                  state.textContent = (isHost ? 'HOST · ' : '') + (peer.isMuted ? 'MIC MUTED' : 'MIC LIVE');
                  card.appendChild(avatar);
                  card.appendChild(title);
                  card.appendChild(state);
                  liveParticipantsGrid.appendChild(card);
                });
              }

              function sendRtcMediaState() {
                if (!rtcSocket || rtcSocket.readyState !== 1) return;
                rtcSocket.send(JSON.stringify({
                  type: 'media_state',
                  roomId: rtcRoomId,
                  isMuted: rtcMuted
                }));
              }

              function shouldInitiateRtcOffer(remotePeer) {
                if (rtcRole === 'HOST_TEACHER' && remotePeer.role !== 'HOST_TEACHER') return true;
                if (rtcRole !== 'HOST_TEACHER' && remotePeer.role === 'HOST_TEACHER') return false;
                return rtcPeerId < remotePeer.peerId;
              }

              function removeRtcRemoteAudio(remotePeerId) {
                var audio = rtcRemoteAudio[remotePeerId];
                if (audio) {
                  try { audio.pause(); } catch (e) {}
                  audio.srcObject = null;
                  if (audio.parentNode) audio.parentNode.removeChild(audio);
                  delete rtcRemoteAudio[remotePeerId];
                }
              }

              function createOrGetRtcPeer(remotePeerId) {
                if (rtcPeers[remotePeerId]) return rtcPeers[remotePeerId];
                var pc = new RTCPeerConnection({ iceServers: [] });
                rtcPeers[remotePeerId] = pc;
                var activeOutAudio = rtcLocalStream ? rtcLocalStream.getAudioTracks()[0] : null;
                var audioT = pc.addTransceiver('audio', { direction: 'sendrecv' });
                if (activeOutAudio) audioT.sender.replaceTrack(activeOutAudio);

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
                  if (!ev.track || ev.track.kind !== 'audio') {
                    if (ev.track) ev.track.stop();
                    return;
                  }
                  removeRtcRemoteAudio(remotePeerId);
                  var audio = document.createElement('audio');
                  audio.autoplay = true;
                  audio.playsInline = true;
                  audio.style.display = 'none';
                  audio.srcObject = (ev.streams && ev.streams[0])
                    ? ev.streams[0]
                    : new MediaStream([ev.track]);
                  document.body.appendChild(audio);
                  rtcRemoteAudio[remotePeerId] = audio;
                  audio.play().catch(function () {});
                };
                return pc;
              }

              function sendRtcOffer(remotePeerId) {
                var pc = createOrGetRtcPeer(remotePeerId);
                pc.createOffer({ offerToReceiveAudio: true })
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
                Object.keys(rtcRemoteAudio).forEach(removeRtcRemoteAudio);
                rtcRemoteAudio = {};
                if (rtcLocalStream) {
                  rtcLocalStream.getTracks().forEach(function (track) { track.stop(); });
                  rtcLocalStream = null;
                }
                rtcMuted = false;
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
                setMeetingStageActive(false);
                updateRtcControlsUi();
                liveRoomStatusEl.textContent = 'Left audio session';
              }

              function mediaPermissionMessage(err) {
                var name = err && err.name ? err.name : '';
                if (name === 'NotAllowedError' || name === 'SecurityError') {
                  return 'Microphone permission was blocked. Update the browser site permission, then try again.';
                }
                if (name === 'NotFoundError' || name === 'DevicesNotFoundError') {
                  return 'No usable microphone was found. Connect one and check operating-system privacy settings.';
                }
                if (name === 'NotReadableError' || name === 'TrackStartError') {
                  return 'The microphone is busy in another app. Close that app and try again.';
                }
                return 'Unable to start the microphone: ' + ((err && err.message) || name || 'unknown browser error');
              }

              function joinRtcSession(mode, role, startWithMedia, roomOverride, presetClassCode) {
                var targetRoom = roomOverride || (activeDm ? activeDm.dmId : (activeChannel ? activeChannel.channelId : ''));
                var targetToken = activeDm ? activeDm.token : (activeChannel ? activeChannel.token : '');
                var targetChannelId = activeChannel ? activeChannel.channelId : targetRoom;
                if (!targetRoom) {
                  showRtcError('Open a channel or Direct Chat before starting an audio session.');
                  return;
                }
                if (!activeDm && mode === 'AUDIO_CALL' && isGeneralChatActive()) {
                  showRtcError('Audio calls are unavailable in General Chat. Open P.Chat or another channel.');
                  liveStagePanel.classList.add('hidden');
                  setMeetingStageActive(false);
                  return;
                }
                if (typeof RTCPeerConnection === 'undefined') {
                  showRtcError('WebRTC audio is not supported by this browser.');
                  directCallConnecting = false;
                  return;
                }
                if (startWithMedia && !window.isSecureContext) {
                  showRtcError('Microphone access requires trusted HTTPS. Open /secure-setup, install the BitVoid local CA, then use the secure portal URL.');
                  liveStagePanel.classList.remove('hidden');
                  setMeetingStageActive(true);
                  liveRoomStatusEl.innerHTML = 'Secure browser setup required · <a href="/secure-setup?username=' + encodeURIComponent(activeDm ? activeDm.myUsername : portalUsername) + '">OPEN SETUP</a>';
                  directCallConnecting = false;
                  return;
                }
                if (startWithMedia && (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia)) {
                  showRtcError('This browser does not expose microphone controls. Use a current browser over the secure portal URL.');
                  directCallConnecting = false;
                  return;
                }
                leaveRtcSession();
                showRtcError('');
                liveStagePanel.classList.remove('hidden');
                setMeetingStageActive(true);
                rtcRoomId = targetRoom;
                rtcMode = mode;
                rtcRole = role;
                if (mode === 'LIVE_CLASSROOM' && activeSocket) {
                  try { activeSocket.close(); } catch (e) {}
                  activeSocket = null;
                  wsStatusEl.textContent = 'AUDIO';
                }
                liveModeBadge.textContent = mode === 'LIVE_CLASSROOM' ? 'LIVE AUDIO CLASS' : 'AUDIO CALL';
                if (presetClassCode) renderLiveClassCode(presetClassCode);
                rtcPeerId = 'web_' + Math.random().toString(36).slice(2, 10);
                rtcMuted = role === 'STUDENT_VIEWER';

                var mediaPromise = startWithMedia
                  ? navigator.mediaDevices.getUserMedia({ audio: true })
                  : Promise.resolve(null);

                mediaPromise.then(function (stream) {
                  rtcLocalStream = stream;
                  if (stream) {
                    stream.getAudioTracks().forEach(function (track) { track.enabled = !rtcMuted; });
                  } else {
                    rtcMuted = true;
                  }

                  var scheme = location.protocol === 'https:' ? 'wss://' : 'ws://';
                  var displayName = portalUsername ||
                    (activeDm ? activeDm.myUsername : (activeChannel ? activeChannel.callsign : 'WebPeer'));
                  var wsUrl = scheme + location.host + '/ws/signaling' +
                    '?roomId=' + encodeURIComponent(rtcRoomId) +
                    '&channelId=' + encodeURIComponent(targetChannelId) +
                    '&peerId=' + encodeURIComponent(rtcPeerId) +
                    '&name=' + encodeURIComponent(displayName) +
                    '&mode=' + encodeURIComponent(rtcMode) +
                    '&role=' + encodeURIComponent(rtcRole) +
                    '&isMuted=' + rtcMuted +
                    (targetToken ? ('&token=' + encodeURIComponent(targetToken)) : '');

                  rtcSocket = new WebSocket(wsUrl);
                  updateRtcControlsUi();
                  renderRtcParticipants([]);
                  liveRoomStatusEl.textContent = 'Connecting audio signaling...';

                  rtcSocket.onopen = function () {
                    var callLabel = activeDm && directCallLastPhase === 'RINGING'
                      ? ('RINGING @' + activeDm.peerUsername + ' · your audio stage is ready')
                      : (activeDm ? ('AUDIO CALL WITH @' + activeDm.peerUsername) : 'AUDIO CALL LIVE');
                    liveRoomStatusEl.textContent = (rtcMode === 'LIVE_CLASSROOM' ? 'LIVE AUDIO CLASS ACTIVE' : callLabel) + ' · Local P2P';
                  };
                  rtcSocket.onerror = function () {
                    showRtcError('WebRTC audio signaling error on /ws/signaling.');
                  };
                  rtcSocket.onclose = function () {
                    rtcSocket = null;
                    directCallConnecting = false;
                    updateRtcControlsUi();
                  };
                  rtcSocket.onmessage = function (ev) {
                    try {
                      var msg = JSON.parse(ev.data);
                      if (msg.type === 'welcome') {
                        if (msg.classCode) renderLiveClassCode(msg.classCode);
                        renderRtcParticipants(msg.peers || []);
                        (msg.peers || []).forEach(function (peer) {
                          if (peer.peerId !== rtcPeerId) {
                            createOrGetRtcPeer(peer.peerId);
                            if (shouldInitiateRtcOffer(peer)) sendRtcOffer(peer.peerId);
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
                      } else if (msg.type === 'peer_left') {
                        renderRtcParticipants(msg.peers || []);
                        if (msg.peerId && rtcPeers[msg.peerId]) {
                          try { rtcPeers[msg.peerId].close(); } catch (e) {}
                          delete rtcPeers[msg.peerId];
                          removeRtcRemoteAudio(msg.peerId);
                        }
                      } else if (msg.type === 'session_ended') {
                        leaveRtcSession();
                      } else if (msg.type === 'offer' && msg.fromPeerId && msg.sdp) {
                        var pc = createOrGetRtcPeer(msg.fromPeerId);
                        pc.setRemoteDescription({ type: 'offer', sdp: msg.sdp })
                          .then(function () {
                            return pc.createAnswer({ offerToReceiveAudio: true });
                          })
                          .then(function (answer) {
                            return pc.setLocalDescription(answer).then(function () { return answer; });
                          })
                          .then(function (answer) {
                            if (rtcSocket && rtcSocket.readyState === 1) {
                              rtcSocket.send(JSON.stringify({
                                type: 'answer',
                                roomId: rtcRoomId,
                                targetPeerId: msg.fromPeerId,
                                sdp: answer.sdp
                              }));
                            }
                          })
                          .catch(function () {});
                      } else if (msg.type === 'answer' && msg.fromPeerId && msg.sdp) {
                        var pcAnswer = rtcPeers[msg.fromPeerId];
                        if (pcAnswer) {
                          pcAnswer.setRemoteDescription({ type: 'answer', sdp: msg.sdp }).catch(function () {});
                        }
                      } else if (msg.type === 'ice' && msg.fromPeerId && msg.candidate) {
                        var pcIce = rtcPeers[msg.fromPeerId];
                        if (pcIce) {
                          pcIce.addIceCandidate(new RTCIceCandidate(msg.candidate)).catch(function () {});
                        }
                      }
                    } catch (e) {}
                  };
                }).catch(function (err) {
                  directCallConnecting = false;
                  cleanupRtcPeersAndMedia();
                  showRtcError(mediaPermissionMessage(err));
                  liveRoomStatusEl.textContent = 'Microphone access is required before audio can connect';
                });
              }

              function startDirectVoiceCall() {
                if (!activeDm) return;
                if (!window.isSecureContext) {
                  liveStagePanel.classList.remove('hidden');
                  setMeetingStageActive(true);
                  renderRtcParticipants(rtcParticipantList);
                  showRtcError('Browser calls require the trusted HTTPS portal. Complete the one-time certificate setup first.');
                  liveRoomStatusEl.innerHTML = 'Secure browser setup required · <a href="/secure-setup?username=' + encodeURIComponent(activeDm.myUsername || '') + '">OPEN SETUP</a>';
                  return;
                }
                refreshPeerPresence(activeDm.peerUsername).then(function (available) {
                  if (!available) {
                    showPchatError('Call unavailable: @' + activeDm.peerUsername + ' is not active on this Wi-Fi node.', false);
                    openSidebar();
                    return;
                  }
                  fetch('/api/dm/' + encodeURIComponent(activeDm.dmId) + '/call/invite', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json', 'X-DM-Token': activeDm.token },
                    body: '{}'
                  }).then(function (r) {
                    if (!r.ok) {
                      return r.json().then(function (d) {
                        throw new Error((d && d.detail) || ('Unable to start call (HTTP ' + r.status + ')'));
                      });
                    }
                    return r.json();
                  }).then(function () {
                    directCallLastPhase = 'RINGING';
                    directCallConnecting = true;
                    liveStagePanel.classList.remove('hidden');
                    setMeetingStageActive(true);
                    voiceCallBtn.classList.add('active');
                    liveRoomStatusEl.textContent = 'Ringing @' + activeDm.peerUsername + ' · opening your voice call';
                    joinRtcSession('AUDIO_CALL', 'SPEAKER', true, null, null);
                  }).catch(function (err) {
                    showRtcError(err.message || 'Unable to ring this user.');
                  });
                });
              }

              function respondToIncomingDirectCall(accept) {
                if (!activeDm || directCallLastPhase !== 'RINGING') return;
                if (accept && !window.isSecureContext) {
                  showRtcError('Install the BitVoid local certificate and reopen the trusted HTTPS portal before accepting.');
                  window.location.href = '/secure-setup?username=' + encodeURIComponent(activeDm.myUsername || '');
                  return;
                }
                directAcceptCallBtn.disabled = true;
                directDeclineCallBtn.disabled = true;
                fetch('/api/dm/' + encodeURIComponent(activeDm.dmId) + '/call/' + (accept ? 'accept' : 'decline'), {
                  method: 'POST',
                  headers: { 'Content-Type': 'application/json', 'X-DM-Token': activeDm.token },
                  body: '{}'
                }).then(function (r) {
                  if (!r.ok) throw new Error('Call response failed (HTTP ' + r.status + ')');
                  return r.json();
                }).then(function () {
                  directCallPromptOpen = false;
                  updateRtcControlsUi();
                  if (accept) {
                    directCallLastPhase = 'ACCEPTED';
                    directCallConnecting = true;
                    joinRtcSession(
                      'AUDIO_CALL',
                      'SPEAKER',
                      true,
                      null,
                      null,
                      false
                    );
                  } else {
                    directCallLastPhase = 'DECLINED';
                    setMeetingStageActive(false);
                    liveStagePanel.classList.add('hidden');
                  }
                }).catch(function (err) {
                  showRtcError(err.message || 'Unable to answer call.');
                }).finally(function () {
                  directAcceptCallBtn.disabled = false;
                  directDeclineCallBtn.disabled = false;
                });
              }

              liveClassBtn.addEventListener('click', function () {
                liveStagePanel.classList.toggle('hidden');
                updateRtcControlsUi();
              });
              voiceCallBtn.addEventListener('click', startDirectVoiceCall);
              directAcceptCallBtn.addEventListener('click', function () { respondToIncomingDirectCall(true); });
              directDeclineCallBtn.addEventListener('click', function () { respondToIncomingDirectCall(false); });
              closeLiveStageBtn.addEventListener('click', function () {
                if (activeDm && directCallLastPhase === 'RINGING') {
                  fetch('/api/dm/' + encodeURIComponent(activeDm.dmId) + '/call/cancel', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json', 'X-DM-Token': activeDm.token },
                    body: '{}'
                  }).catch(function () {});
                  directCallLastPhase = 'CANCELED';
                  directCallPromptOpen = false;
                  leaveRtcSession();
                } else {
                  setMeetingStageActive(false);
                }
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
                if (isGeneralChatActive()) {
                  showRtcError('Calls are unavailable in General Chat. Use P.Chat for a direct call.');
                  liveStagePanel.classList.add('hidden');
                  setMeetingStageActive(false);
                  return;
                }
                joinRtcSession('AUDIO_CALL', 'SPEAKER', true, null, null);
              });
              rtcMuteBtn.addEventListener('click', function () {
                // Listeners join without a captured track; the MIC control acquires one on demand.
                if (!rtcLocalStream || rtcLocalStream.getAudioTracks().length === 0) {
                  if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
                    showRtcError('This browser does not expose microphone controls.');
                    return;
                  }
                  navigator.mediaDevices.getUserMedia({ audio: true })
                    .then(function (micStream) {
                      var micTrack = micStream.getAudioTracks()[0];
                      if (!micTrack) return;
                      if (rtcLocalStream) {
                        rtcLocalStream.addTrack(micTrack);
                      } else {
                        rtcLocalStream = micStream;
                      }
                      rtcMuted = false;
                      micTrack.enabled = true;
                      Object.keys(rtcPeers).forEach(function (pid) {
                        var audioTransceiver = rtcPeers[pid].getTransceivers().find(function (tr) {
                          return tr.receiver && tr.receiver.track && tr.receiver.track.kind === 'audio';
                        });
                        if (audioTransceiver && audioTransceiver.sender) {
                          audioTransceiver.sender.replaceTrack(micTrack).catch(function () {});
                        }
                      });
                      updateRtcControlsUi();
                      renderRtcParticipants(rtcParticipantList);
                      sendRtcMediaState();
                    })
                    .catch(function () {
                      showRtcError('Microphone permission denied or unavailable.');
                    });
                  return;
                }
                rtcMuted = !rtcMuted;
                rtcLocalStream.getAudioTracks().forEach(function (t) { t.enabled = !rtcMuted; });
                updateRtcControlsUi();
                sendRtcMediaState();
              });
              rtcLeaveBtn.addEventListener('click', function () {
                var leavingDirectCall = activeDm && rtcRoomId === activeDm.dmId;
                if (leavingDirectCall) {
                  directCallConnecting = true;
                  directCallLastPhase = 'ENDED';
                  fetch('/api/dm/' + encodeURIComponent(activeDm.dmId) + '/call/end', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json', 'X-DM-Token': activeDm.token },
                    body: '{}'
                  }).catch(function () {}).finally(function () {
                    directCallConnecting = false;
                  });
                }
                leaveRtcSession();
                if (leavingDirectCall) liveStagePanel.classList.add('hidden');
              });
              rtcEndSessionBtn.addEventListener('click', function () {
                if (rtcSocket && rtcSocket.readyState === 1) {
                  rtcSocket.send(JSON.stringify({ type: 'end_session', roomId: rtcRoomId }));
                }
                leaveRtcSession();
              });

              menuToggleBtn.addEventListener('click', openSidebar);
              closeSidebarBtn.addEventListener('click', closeSidebar);
              backdrop.addEventListener('click', closeSidebar);
              if (toggleMediaBtn) {
                toggleMediaBtn.addEventListener('click', function () {
                  var hidden = mediaDrawer.classList.toggle('hidden');
                  if (hidden) {
                    toggleMediaBtn.classList.remove('active');
                  } else {
                    toggleMediaBtn.classList.add('active');
                  }
                });
              }

              unlockBtn.addEventListener('click', function () {
                var cid = (privateIdInput.value || '').trim();
                if (!cid) {
                  channelErr.textContent = 'Enter a Private Channel ID.';
                  channelErr.classList.remove('hidden');
                  return;
                }
                joinChannel(cid, privateKeyInput.value || '');
              });
              pchatAddUserBtn.addEventListener('click', function () {
                pchatExactUserInput.value = '';
                showPchatError('', true);
                pchatAddDialog.classList.remove('hidden');
                setTimeout(function () { pchatExactUserInput.focus(); }, 0);
              });
              pchatDialogCancel.addEventListener('click', function () {
                pchatAddDialog.classList.add('hidden');
                showPchatError('', true);
              });
              pchatAddDialog.addEventListener('click', function (ev) {
                if (ev.target === pchatAddDialog) pchatDialogCancel.click();
              });
              pchatExactUserInput.addEventListener('input', function () {
                showPchatError('', true);
              });
              pchatExactUserInput.addEventListener('keydown', function (ev) {
                if (ev.key === 'Enter') pchatDialogOpen.click();
                if (ev.key === 'Escape') pchatDialogCancel.click();
              });
              pchatDialogOpen.addEventListener('click', function () {
                var target = (pchatExactUserInput.value || '').trim().replace(/^@/, '');
                if (!target) {
                  showPchatError('Enter a User ID.', true);
                  return;
                }
                if (target.toLowerCase() === portalUsername.toLowerCase()) {
                  showPchatError("Enter another user's ID.", true);
                  return;
                }
                pchatDialogOpen.disabled = true;
                pchatDialogOpen.textContent = 'CHECKING…';
                refreshPeerPresence(target).then(function (available) {
                  if (!available) {
                    showPchatError('This user is not active on the current BitVoid node or local Wi-Fi.', true);
                    return;
                  }
                  openDirectChat(portalUsername, target);
                }).finally(function () {
                  pchatDialogOpen.disabled = false;
                  pchatDialogOpen.textContent = 'OPEN CHAT';
                });
              });
              pchatDiscoverableToggle.addEventListener('click', function () {
                portalDiscoverable = !portalDiscoverable;
                try { localStorage.setItem('bv_pchat_discoverable', portalDiscoverable ? 'true' : 'false'); } catch (e) {}
                updatePchatDiscoverabilityUi();
                registerBrowserPresence();
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
                var uploadProgressRow = appendUploadProgress(file);
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
                    if (uploadProgressRow && uploadProgressRow.parentNode) uploadProgressRow.parentNode.removeChild(uploadProgressRow);
                    uploadProgressRow = null;
                    if (caption) chatInput.value = '';
                    if (d && d.message) appendChatMessage(d.message);
                    refreshActiveChannelMedia();
                  })
                  .catch(function (err) {
                    if (uploadProgressRow && uploadProgressRow.parentNode) uploadProgressRow.parentNode.removeChild(uploadProgressRow);
                    uploadProgressRow = null;
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

              portalLoginUserId.addEventListener('input', function () {
                setPortalAccessError('');
              });
              portalLoginUserId.addEventListener('keydown', function (ev) {
                if (ev.key === 'Enter') submitPortalLogin();
                if (ev.key === 'Escape') showPortalLoginStep(false);
              });
              portalGuestBtn.addEventListener('click', function () {
                startPortalSession('guest-' + Math.floor(1000 + Math.random() * 9000), true);
              });
              portalLoginBackBtn.addEventListener('click', function () {
                showPortalLoginStep(false);
              });
              portalLoginBtn.addEventListener('click', function () {
                if (!portalLoginStep) {
                  showPortalLoginStep(true);
                } else {
                  submitPortalLogin();
                }
              });

              bindSidebarClicks();
              setTimeout(function () { portalLoginBtn.focus(); }, 0);
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
            val json = runCatching { JSONObject(raw) }.getOrNull()
            val text = json?.optString("text", raw) ?: raw
            BitVoidChannelManager.appendChatMessage(
                channelId = channelSession.channelId,
                callsign = channelSession.callsign,
                rawText = text,
                stableMessageId = json?.optString("clientMessageId")?.takeIf { it.isNotBlank() },
                clientTimestampMs = json?.optLong("timestampMs", 0L)?.takeIf { it > 0L }
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
