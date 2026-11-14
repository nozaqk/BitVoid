package com.bitvoid.network.server

import android.content.Context
import com.bitvoid.network.channel.BitVoidChannel
import com.bitvoid.network.channel.BitVoidChannelManager
import com.bitvoid.network.channel.ChannelSession
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
import org.json.JSONObject

class BitVoidLocalServer(
    private val context: Context,
    port: Int = DEFAULT_PORT
) : NanoWSD(port) {

    companion object {
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
            "default-src 'self' 'unsafe-inline'; connect-src 'self' ws: wss:; media-src 'self'; img-src 'self'; object-src 'none'; frame-ancestors 'none';"
        )
        response.addHeader(
            "Permissions-Policy",
            "interest-cohort=(), camera=(), microphone=(), geolocation=(), usb=()"
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
                  .then(function (data) {
                    activeDm = null;
                    activeChannel = data;
                    seenMsgIds = {};
                    chatBox.innerHTML = '';
                    var label = data.name || data.channelId;
                    activeTitle.textContent = label;
                    activeCallsign.textContent = data.callsign || '-';
                    chatInput.placeholder = 'Message ' + label + '...';
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
                  })
                  .catch(function (err) {
                    channelErr.textContent = err.message || 'Unable to join channel';
                    channelErr.classList.remove('hidden');
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
                    exitChannelBtn.classList.remove('hidden');
                    var banner = document.createElement('div');
                    banner.className = 'wa-welcome-pill';
                    banner.innerHTML = '<strong>Direct Chat with @' + esc(data.peerUsername) + '</strong> · You: <strong>@' + esc(data.myUsername) + '</strong>';
                    chatBox.appendChild(banner);
                    (data.messages || []).forEach(appendChatMessage);
                    if (activeSocket) { try { activeSocket.close(); } catch (e) {} }
                    var scheme = location.protocol === 'https:' ? 'wss://' : 'ws://';
                    var dmWsUrl = scheme + location.host + '/ws/dm/' + encodeURIComponent(data.dmId) + '?token=' + encodeURIComponent(data.token);
                    wsStatusEl.textContent = 'CONNECTING';
                    try {
                      activeSocket = new WebSocket(dmWsUrl);
                      activeSocket.onopen = function () { wsStatusEl.textContent = 'LIVE'; };
                      activeSocket.onclose = function () { wsStatusEl.textContent = 'HTTP-SYNC'; };
                      activeSocket.onerror = function () { wsStatusEl.textContent = 'HTTP-SYNC'; };
                      activeSocket.onmessage = function (ev) {
                        try {
                          var p = JSON.parse(ev.data);
                          if (p.type === 'history' && Array.isArray(p.messages)) p.messages.forEach(appendChatMessage);
                          else if (p.type === 'chat' && p.message) appendChatMessage(p.message);
                        } catch (e) {}
                      };
                    } catch (e) { wsStatusEl.textContent = 'HTTP-SYNC'; }
                    highlightActiveSidebar();
                    closeSidebar();
                  })
                  .catch(function (err) {
                    dmErr.textContent = err.message || 'Unable to start direct chat';
                    dmErr.classList.remove('hidden');
                  });
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
              exitChannelBtn.addEventListener('click', exitPrivateChannel);
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
