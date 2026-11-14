package com.bitvoid.network.client

import com.bitvoid.network.model.MediaCategory
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

data class RemoteMediaItem(
    val id: String,
    val title: String,
    val size: Long,
    val mimeType: String,
    val category: MediaCategory,
    val badge: String,
    val streamUrl: String,
    val downloadUrl: String
)

data class RemoteChannelSummary(
    val channelId: String?,
    val name: String,
    val isPublic: Boolean,
    val hasPrivateKey: Boolean,
    val mediaCount: Int,
    val activePeers: Int,
    val messageCount: Int
)

data class ClientChannelSession(
    val channelId: String,
    val name: String,
    val isPublic: Boolean,
    val token: String,
    val callsign: String,
    val mediaList: List<RemoteMediaItem>
)

data class ClientChatMessage(
    val id: String,
    val channelId: String,
    val sender: String,
    val text: String,
    val time: String,
    val isSystemNotice: Boolean = false
)

data class ClientDirectSession(
    val dmId: String,
    val myUsername: String,
    val peerUsername: String,
    val token: String
)

enum class ClientWsStatus {
    DISCONNECTED,
    CONNECTING,
    LIVE,
    ERROR
}

enum class ClientHubTab(val label: String) {
    CHANNELS("CHANNELS"),
    PUBLIC_FEED("PUBLIC FEED"),
    WEB_PORTAL("WEB PORTAL")
}

class BitVoidClientRepository {

    companion object {
        const val DEFAULT_GATEWAY_URL = "http://192.168.43.1:8080"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val host = original.url.host
            if (!BitVoidClientManager.isPrivateLocalHost(host)) {
                throw IOException("Blocked non-RFC1918 host '$host' by BitVoid firewall.")
            }
            val sanitized = original.newBuilder()
                .header("User-Agent", "BitVoid/1.0 (Isolated)")
                .header("Cache-Control", "no-store")
                .removeHeader("Referer")
                .removeHeader("Cookie")
                .build()
            chain.proceed(sanitized)
        }
        .build()

    private var activeWebSocket: WebSocket? = null
    private var activeDirectWebSocket: WebSocket? = null

    private val _gatewayBaseUrl = MutableStateFlow(DEFAULT_GATEWAY_URL)
    val gatewayBaseUrl: StateFlow<String> = _gatewayBaseUrl.asStateFlow()

    private val _publicMedia = MutableStateFlow<List<RemoteMediaItem>>(emptyList())
    val publicMedia: StateFlow<List<RemoteMediaItem>> = _publicMedia.asStateFlow()

    private val _isLoadingPublicMedia = MutableStateFlow(false)
    val isLoadingPublicMedia: StateFlow<Boolean> = _isLoadingPublicMedia.asStateFlow()

    private val _publicMediaError = MutableStateFlow<String?>(null)
    val publicMediaError: StateFlow<String?> = _publicMediaError.asStateFlow()

    private val _channels = MutableStateFlow<List<RemoteChannelSummary>>(emptyList())
    val channels: StateFlow<List<RemoteChannelSummary>> = _channels.asStateFlow()

    private val _isLoadingChannels = MutableStateFlow(false)
    val isLoadingChannels: StateFlow<Boolean> = _isLoadingChannels.asStateFlow()

    private val _channelsError = MutableStateFlow<String?>(null)
    val channelsError: StateFlow<String?> = _channelsError.asStateFlow()

    private val _activeChannelSession = MutableStateFlow<ClientChannelSession?>(null)
    val activeChannelSession: StateFlow<ClientChannelSession?> = _activeChannelSession.asStateFlow()

    private val _channelMessages = MutableStateFlow<List<ClientChatMessage>>(emptyList())
    val channelMessages: StateFlow<List<ClientChatMessage>> = _channelMessages.asStateFlow()

    private val _wsStatus = MutableStateFlow(ClientWsStatus.DISCONNECTED)
    val wsStatus: StateFlow<ClientWsStatus> = _wsStatus.asStateFlow()

    private val _channelPeersCount = MutableStateFlow(1)
    val channelPeersCount: StateFlow<Int> = _channelPeersCount.asStateFlow()

    private val _isJoiningChannel = MutableStateFlow(false)
    val isJoiningChannel: StateFlow<Boolean> = _isJoiningChannel.asStateFlow()

    private val _channelJoinError = MutableStateFlow<String?>(null)
    val channelJoinError: StateFlow<String?> = _channelJoinError.asStateFlow()

    private val _knownUsers = MutableStateFlow<List<String>>(emptyList())
    val knownUsers: StateFlow<List<String>> = _knownUsers.asStateFlow()

    private val _activeDirectSession = MutableStateFlow<ClientDirectSession?>(null)
    val activeDirectSession: StateFlow<ClientDirectSession?> = _activeDirectSession.asStateFlow()

    private val _directMessages = MutableStateFlow<List<ClientChatMessage>>(emptyList())
    val directMessages: StateFlow<List<ClientChatMessage>> = _directMessages.asStateFlow()

    private val _directWsStatus = MutableStateFlow(ClientWsStatus.DISCONNECTED)
    val directWsStatus: StateFlow<ClientWsStatus> = _directWsStatus.asStateFlow()

    private val _isOpeningDirectChat = MutableStateFlow(false)
    val isOpeningDirectChat: StateFlow<Boolean> = _isOpeningDirectChat.asStateFlow()

    private val _directChatError = MutableStateFlow<String?>(null)
    val directChatError: StateFlow<String?> = _directChatError.asStateFlow()

    fun setGatewayBaseUrl(rawUrl: String): String? {
        val normalized = BitVoidClientManager.normalizeLocalPortalUrl(rawUrl)
            ?.trimEnd('/')
            ?: return null
        _gatewayBaseUrl.value = normalized
        return normalized
    }

    suspend fun fetchPublicMedia(urlOverride: String? = null) {
        val targetBase = (urlOverride?.let { setGatewayBaseUrl(it) } ?: _gatewayBaseUrl.value)
            .trimEnd('/')
        _isLoadingPublicMedia.value = true
        _publicMediaError.value = null

        val result = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$targetBase/api/public/media")
                    .get()
                    .build()
                okHttpClient.newCall(request).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Result.failure(IOException("Gateway returned HTTP ${resp.code}"))
                    } else {
                        val json = JSONObject(bodyStr)
                        val arr = json.optJSONArray("media") ?: json.optJSONArray("videos") ?: JSONArray()
                        Result.success(parseMediaArray(arr))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        _isLoadingPublicMedia.value = false
        result.onSuccess { items ->
            _publicMedia.value = items
            _publicMediaError.value = null
        }.onFailure { err ->
            _publicMediaError.value = err.message ?: "Unable to reach $targetBase"
        }
    }

    suspend fun fetchChannels(urlOverride: String? = null) {
        val targetBase = (urlOverride?.let { setGatewayBaseUrl(it) } ?: _gatewayBaseUrl.value)
            .trimEnd('/')
        _isLoadingChannels.value = true
        _channelsError.value = null

        val result = withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$targetBase/api/channels")
                    .get()
                    .build()
                okHttpClient.newCall(request).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Result.failure(IOException("Gateway returned HTTP ${resp.code}"))
                    } else {
                        val json = JSONObject(bodyStr)
                        val arr = json.optJSONArray("channels") ?: JSONArray()
                        Result.success(parseChannelsArray(arr))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        _isLoadingChannels.value = false
        result.onSuccess { list ->
            _channels.value = list
            _channelsError.value = null
        }.onFailure { err ->
            _channelsError.value = err.message ?: "Unable to reach $targetBase"
        }
    }

    suspend fun joinChannel(
        rawChannelId: String,
        rawPrivateKey: String? = null,
        urlOverride: String? = null,
        username: String? = null
    ) {
        val channelId = rawChannelId.trim()
        if (channelId.isEmpty()) {
            _channelJoinError.value = "Enter a Channel ID."
            return
        }
        val targetBase = (urlOverride?.let { setGatewayBaseUrl(it) } ?: _gatewayBaseUrl.value)
            .trimEnd('/')

        _isJoiningChannel.value = true
        _channelJoinError.value = null

        val result = withContext(Dispatchers.IO) {
            try {
                val bodyJson = JSONObject().apply {
                    put("channelId", channelId)
                    put("privateKey", rawPrivateKey?.trim().orEmpty())
                    val cleanUsername = username?.trim()
                    if (!cleanUsername.isNullOrEmpty()) {
                        put("username", cleanUsername)
                    }
                }.toString()

                val request = Request.Builder()
                    .url("$targetBase/api/channels/join")
                    .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                okHttpClient.newCall(request).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (resp.code == 401) {
                        Result.failure(SecurityException("Invalid Private Channel ID or key (401 Unauthorized)"))
                    } else if (!resp.isSuccessful) {
                        Result.failure(IOException("Channel join failed (HTTP ${resp.code})"))
                    } else {
                        val json = JSONObject(bodyStr)
                        val token = json.getString("token")
                        val resolvedId = json.optString("channelId", channelId)
                        val name = json.optString("name", resolvedId)
                        val isPublic = json.optBoolean("isPublic", false)
                        val callsign = json.optString("callsign", "Peer")
                        val mediaArr = json.optJSONArray("media") ?: json.optJSONArray("videos") ?: JSONArray()
                        val msgArr = json.optJSONArray("messages") ?: JSONArray()

                        val session = ClientChannelSession(
                            channelId = resolvedId,
                            name = name,
                            isPublic = isPublic,
                            token = token,
                            callsign = callsign,
                            mediaList = parseMediaArray(mediaArr)
                        )
                        val messages = parseMessagesArray(msgArr)
                        Result.success(session to messages)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        _isJoiningChannel.value = false
        result.onSuccess { (session, initialMessages) ->
            closeSocketOnly()
            _activeChannelSession.value = session
            _channelMessages.value = initialMessages
            _channelJoinError.value = null
            _channelPeersCount.value = 1
            connectChatWebSocket(targetBase, session)
        }.onFailure { err ->
            _channelJoinError.value = err.message ?: "Failed to unlock channel"
        }
    }

    suspend fun refreshActiveChannelMedia() {
        val session = _activeChannelSession.value ?: return
        val targetBase = _gatewayBaseUrl.value.trimEnd('/')
        val encodedId = URLEncoder.encode(session.channelId, StandardCharsets.UTF_8.name())
            .replace("+", "%20")

        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$targetBase/api/channels/$encodedId/media")
                    .header("X-Channel-Token", session.token)
                    .get()
                    .build()
                okHttpClient.newCall(request).execute().use { resp ->
                    if (resp.code == 401 || resp.code == 404) {
                        leaveChannel()
                        _channelJoinError.value = "Channel session expired or locked by Host."
                        return@withContext
                    }
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val mediaArr = json.optJSONArray("media") ?: json.optJSONArray("videos") ?: JSONArray()
                        val updated = parseMediaArray(mediaArr)
                        _activeChannelSession.value = session.copy(
                            isPublic = json.optBoolean("isPublic", session.isPublic),
                            mediaList = updated
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun sendChannelChatMessage(rawText: String) {
        val trimmed = rawText.trim()
        val session = _activeChannelSession.value ?: return
        if (trimmed.isEmpty()) return

        val payload = JSONObject().put("text", trimmed).toString()
        val ws = activeWebSocket
        if (ws != null && _wsStatus.value == ClientWsStatus.LIVE && ws.send(payload)) {
            return
        }

        val targetBase = _gatewayBaseUrl.value.trimEnd('/')
        val encodedId = URLEncoder.encode(session.channelId, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        scope.launch {
            try {
                val request = Request.Builder()
                    .url("$targetBase/api/channels/$encodedId/chat")
                    .header("X-Channel-Token", session.token)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                okHttpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val obj = JSONObject(resp.body?.string().orEmpty())
                        val msgJson = obj.optJSONObject("message")
                        if (msgJson != null) {
                            appendMessageIfNew(parseMessageObject(msgJson))
                        }
                    } else if (resp.code == 401) {
                        leaveChannel()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun buildAbsoluteStreamUrl(item: RemoteMediaItem, channelToken: String? = null): String {
        val base = _gatewayBaseUrl.value.trimEnd('/')
        val path = if (item.streamUrl.startsWith("/")) item.streamUrl else "/${item.streamUrl}"
        return if (!channelToken.isNullOrBlank()) {
            val encoded = URLEncoder.encode(channelToken, StandardCharsets.UTF_8.name())
            val separator = if (path.contains('?')) '&' else '?'
            "$base$path${separator}token=$encoded"
        } else {
            "$base$path"
        }
    }

    suspend fun registerUsername(username: String, urlOverride: String? = null) {
        val clean = username.trim()
        if (clean.isEmpty()) return
        val targetBase = (urlOverride?.let { setGatewayBaseUrl(it) } ?: _gatewayBaseUrl.value)
            .trimEnd('/')
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject().put("username", clean).toString()
                val request = Request.Builder()
                    .url("$targetBase/api/users/register")
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                okHttpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val arr = json.optJSONArray("users")
                        if (arr != null) {
                            _knownUsers.value = parseStringArray(arr)
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    suspend fun fetchKnownUsers(urlOverride: String? = null) {
        val targetBase = (urlOverride?.let { setGatewayBaseUrl(it) } ?: _gatewayBaseUrl.value)
            .trimEnd('/')
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$targetBase/api/users")
                    .get()
                    .build()
                okHttpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val arr = json.optJSONArray("users") ?: JSONArray()
                        _knownUsers.value = parseStringArray(arr)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    suspend fun openDirectChat(
        myUsername: String,
        targetUsername: String,
        urlOverride: String? = null
    ) {
        val cleanTarget = targetUsername.trim().removePrefix("@")
        if (cleanTarget.isEmpty()) {
            _directChatError.value = "Enter a peer Username ID."
            return
        }
        val targetBase = (urlOverride?.let { setGatewayBaseUrl(it) } ?: _gatewayBaseUrl.value)
            .trimEnd('/')

        _isOpeningDirectChat.value = true
        _directChatError.value = null

        val result = withContext(Dispatchers.IO) {
            try {
                val bodyJson = JSONObject().apply {
                    put("myUsername", myUsername.trim().removePrefix("@"))
                    put("targetUsername", cleanTarget)
                }.toString()
                val request = Request.Builder()
                    .url("$targetBase/api/dm/open")
                    .post(bodyJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                okHttpClient.newCall(request).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val detail = try {
                            JSONObject(bodyStr).optString("detail", "")
                        } catch (_: Exception) {
                            ""
                        }
                        Result.failure(
                            IllegalArgumentException(
                                detail.ifBlank { "Unable to start direct chat (HTTP ${resp.code})" }
                            )
                        )
                    } else {
                        val json = JSONObject(bodyStr)
                        val session = ClientDirectSession(
                            dmId = json.getString("dmId"),
                            myUsername = json.getString("myUsername"),
                            peerUsername = json.getString("peerUsername"),
                            token = json.getString("token")
                        )
                        val messages = parseMessagesArray(json.optJSONArray("messages") ?: JSONArray())
                        Result.success(session to messages)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        _isOpeningDirectChat.value = false
        result.onSuccess { (session, initialMessages) ->
            closeDirectSocketOnly()
            _activeDirectSession.value = session
            _directMessages.value = initialMessages
            _directChatError.value = null
            connectDirectWebSocket(targetBase, session)
            scope.launch { fetchKnownUsers(targetBase) }
        }.onFailure { err ->
            _directChatError.value = err.message ?: "Unable to start direct chat"
        }
    }

    fun sendDirectMessage(rawText: String) {
        val trimmed = rawText.trim()
        val session = _activeDirectSession.value ?: return
        if (trimmed.isEmpty()) return

        val payload = JSONObject().put("text", trimmed).toString()
        val ws = activeDirectWebSocket
        if (ws != null && _directWsStatus.value == ClientWsStatus.LIVE && ws.send(payload)) {
            return
        }

        val targetBase = _gatewayBaseUrl.value.trimEnd('/')
        val encodedDm = URLEncoder.encode(session.dmId, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        scope.launch {
            try {
                val request = Request.Builder()
                    .url("$targetBase/api/dm/$encodedDm/messages")
                    .header("X-DM-Token", session.token)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                okHttpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val obj = JSONObject(resp.body?.string().orEmpty())
                        val msgJson = obj.optJSONObject("message")
                        if (msgJson != null) {
                            appendDirectMessageIfNew(parseMessageObject(msgJson))
                        }
                    } else if (resp.code == 401) {
                        leaveDirectChat()
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun leaveChannel() {
        closeSocketOnly()
        _activeChannelSession.value = null
        _channelMessages.value = emptyList()
        _channelPeersCount.value = 1
        _wsStatus.value = ClientWsStatus.DISCONNECTED
        _channelJoinError.value = null
    }

    fun leaveDirectChat() {
        closeDirectSocketOnly()
        _activeDirectSession.value = null
        _directMessages.value = emptyList()
        _directWsStatus.value = ClientWsStatus.DISCONNECTED
        _directChatError.value = null
    }

    fun clearDirectChatError() {
        _directChatError.value = null
    }

    fun release() {
        leaveChannel()
        leaveDirectChat()
        scope.cancel()
        okHttpClient.dispatcher.executorService.shutdown()
    }

    private fun connectChatWebSocket(baseUrl: String, session: ClientChannelSession) {
        closeSocketOnly()
        _wsStatus.value = ClientWsStatus.CONNECTING

        val wsBase = when {
            baseUrl.startsWith("https://") -> "wss://${baseUrl.removePrefix("https://")}"
            baseUrl.startsWith("http://") -> "ws://${baseUrl.removePrefix("http://")}"
            else -> "ws://$baseUrl"
        }
        val encodedChannel = URLEncoder.encode(session.channelId, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        val encodedToken = URLEncoder.encode(session.token, StandardCharsets.UTF_8.name())
        val wsUrl = "$wsBase/ws/chat/$encodedChannel?token=$encodedToken"

        val request = Request.Builder()
            .url(wsUrl)
            .header("X-Channel-Token", session.token)
            .build()

        activeWebSocket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _wsStatus.value = ClientWsStatus.LIVE
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleWebSocketFrame(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    _wsStatus.value = ClientWsStatus.DISCONNECTED
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _wsStatus.value = ClientWsStatus.DISCONNECTED
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _wsStatus.value = ClientWsStatus.ERROR
                }
            }
        )
    }

    private fun handleWebSocketFrame(rawJson: String) {
        try {
            val json = JSONObject(rawJson)
            when (json.optString("type")) {
                "history" -> {
                    val arr = json.optJSONArray("messages") ?: return
                    val parsed = parseMessagesArray(arr)
                    parsed.forEach { appendMessageIfNew(it) }
                }
                "chat" -> {
                    val msgObj = json.optJSONObject("message") ?: return
                    appendMessageIfNew(parseMessageObject(msgObj))
                }
                "presence" -> {
                    val count = json.optInt("activeCount", 1).coerceAtLeast(1)
                    _channelPeersCount.value = count
                }
                "notice" -> {
                    val noticeText = json.optString("text", "")
                    if (noticeText.isNotBlank()) {
                        appendMessageIfNew(
                            ClientChatMessage(
                                id = "notice_${System.nanoTime()}",
                                channelId = json.optString("channelId", json.optString("roomId")),
                                sender = "SYSTEM",
                                text = noticeText,
                                time = "",
                                isSystemNotice = true
                            )
                        )
                        scope.launch { refreshActiveChannelMedia() }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun connectDirectWebSocket(baseUrl: String, session: ClientDirectSession) {
        closeDirectSocketOnly()
        _directWsStatus.value = ClientWsStatus.CONNECTING

        val wsBase = when {
            baseUrl.startsWith("https://") -> "wss://${baseUrl.removePrefix("https://")}"
            baseUrl.startsWith("http://") -> "ws://${baseUrl.removePrefix("http://")}"
            else -> "ws://$baseUrl"
        }
        val encodedDm = URLEncoder.encode(session.dmId, StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        val encodedToken = URLEncoder.encode(session.token, StandardCharsets.UTF_8.name())
        val wsUrl = "$wsBase/ws/dm/$encodedDm?token=$encodedToken"

        val request = Request.Builder()
            .url(wsUrl)
            .header("X-DM-Token", session.token)
            .build()

        activeDirectWebSocket = okHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _directWsStatus.value = ClientWsStatus.LIVE
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleDirectWebSocketFrame(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    _directWsStatus.value = ClientWsStatus.DISCONNECTED
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    _directWsStatus.value = ClientWsStatus.DISCONNECTED
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _directWsStatus.value = ClientWsStatus.ERROR
                }
            }
        )
    }

    private fun handleDirectWebSocketFrame(rawJson: String) {
        try {
            val json = JSONObject(rawJson)
            when (json.optString("type")) {
                "history" -> {
                    val arr = json.optJSONArray("messages") ?: return
                    parseMessagesArray(arr).forEach { appendDirectMessageIfNew(it) }
                }
                "chat" -> {
                    val msgObj = json.optJSONObject("message") ?: return
                    appendDirectMessageIfNew(parseMessageObject(msgObj))
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun appendMessageIfNew(msg: ClientChatMessage) {
        val current = _channelMessages.value
        if (current.any { it.id == msg.id }) return
        _channelMessages.value = (current + msg).takeLast(120)
    }

    private fun appendDirectMessageIfNew(msg: ClientChatMessage) {
        val current = _directMessages.value
        if (current.any { it.id == msg.id }) return
        _directMessages.value = (current + msg).takeLast(150)
    }

    private fun closeSocketOnly() {
        try {
            activeWebSocket?.close(1000, "Client exited channel")
        } catch (_: Exception) {
        }
        activeWebSocket = null
    }

    private fun closeDirectSocketOnly() {
        try {
            activeDirectWebSocket?.close(1000, "Client exited direct chat")
        } catch (_: Exception) {
        }
        activeDirectWebSocket = null
    }

    private fun parseStringArray(arr: JSONArray): List<String> {
        val list = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val v = arr.optString(i).trim()
            if (v.isNotEmpty()) list.add(v)
        }
        return list
    }

    private fun parseChannelsArray(arr: JSONArray): List<RemoteChannelSummary> {
        val list = ArrayList<RemoteChannelSummary>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val isPublic = obj.optBoolean("isPublic", false)
            val id = if (isPublic) obj.optString("channelId").takeIf { it.isNotBlank() } else null
            list.add(
                RemoteChannelSummary(
                    channelId = id,
                    name = obj.optString("name", "Channel"),
                    isPublic = isPublic,
                    hasPrivateKey = obj.optBoolean("hasPrivateKey", false),
                    mediaCount = obj.optInt("mediaCount", 0),
                    activePeers = obj.optInt("activePeers", 0),
                    messageCount = obj.optInt("messageCount", 0)
                )
            )
        }
        return list
    }

    private fun parseMediaArray(arr: JSONArray): List<RemoteMediaItem> {
        val list = ArrayList<RemoteMediaItem>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val categoryStr = obj.optString("category", "DOCUMENT")
            val category = MediaCategory.entries.find { it.name == categoryStr } ?: MediaCategory.DOCUMENT
            list.add(
                RemoteMediaItem(
                    id = obj.optString("id"),
                    title = obj.optString("title", "Untitled"),
                    size = obj.optLong("size", 0L),
                    mimeType = obj.optString("mimeType", "application/octet-stream"),
                    category = category,
                    badge = obj.optString("badge", category.badge),
                    streamUrl = obj.optString("streamUrl"),
                    downloadUrl = obj.optString("downloadUrl")
                )
            )
        }
        return list
    }

    private fun parseMessagesArray(arr: JSONArray): List<ClientChatMessage> {
        val list = ArrayList<ClientChatMessage>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(parseMessageObject(obj))
        }
        return list
    }

    private fun parseMessageObject(obj: JSONObject): ClientChatMessage {
        return ClientChatMessage(
            id = obj.optString("id", "msg_${System.nanoTime()}"),
            channelId = obj.optString("channelId", obj.optString("roomId", "")),
            sender = obj.optString("sender", "Peer"),
            text = obj.optString("text", ""),
            time = obj.optString("time", ""),
            isSystemNotice = false
        )
    }
}
