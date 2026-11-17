package com.bitvoid.network.connection

import android.content.Context
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

enum class BitVoidHardwareSocketState {
    DISCONNECTED,
    CONNECTING,
    OPEN,
    ERROR
}

data class BitVoidApiResponse(
    val code: Int,
    val body: String,
    val headers: Headers
) {
    val isSuccessful: Boolean get() = code in 200..299
}

/**
 * Streaming response for an HTTP byte-range request. The caller must close it after consuming
 * [stream]. Keeping the OkHttp response open avoids copying a large MP4 segment into memory.
 */
class BitVoidByteRange internal constructor(
    val responseCode: Int,
    val contentType: String?,
    val contentLength: Long,
    val contentRange: String?,
    val stream: InputStream,
    private val response: Response
) : Closeable {
    val isPartialContent: Boolean get() = responseCode == 206
    override fun close() = response.close()
}

/**
 * Persistent native HTTP/WebSocket transport for the ESP hardware node.
 *
 * DNS for `bitvoid.local` is pinned to the address found by [BitVoidConnectionManager], and every
 * socket is created by Android's selected Wi-Fi [android.net.Network]. Consequently API, chat and
 * media traffic cannot silently fall back to cellular even if the ESP access point has no Internet.
 */
class BitVoidNodeClient(
    private val connectionManager: BitVoidConnectionManager
) : Closeable {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val USER_AGENT = "BitVoid-Android-ESP/1.0"
    }

    private data class ChatSubscription(
        val channelId: String,
        val token: String?
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectionPool = ConnectionPool(5, 2, TimeUnit.MINUTES)
    private val networkSocketFactory = CurrentNetworkSocketFactory(connectionManager)
    private val localDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = resolveLocalHostname(hostname)
    }

    private val httpClient = OkHttpClient.Builder()
        .socketFactory(networkSocketFactory)
        .dns(localDns)
        .connectionPool(connectionPool)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            val request = chain.request()
            if (!isAllowedNodeHost(request.url.host)) {
                throw IOException("Blocked non-local host '${request.url.host}'.")
            }
            chain.proceed(
                request.newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Cache-Control", "no-store")
                    .removeHeader("Cookie")
                    .removeHeader("Referer")
                    .build()
            )
        }
        .build()

    private val _socketState = MutableStateFlow(BitVoidHardwareSocketState.DISCONNECTED)
    val socketState: StateFlow<BitVoidHardwareSocketState> = _socketState.asStateFlow()

    private val _socketFrames = MutableSharedFlow<String>(extraBufferCapacity = 128)
    val socketFrames: SharedFlow<String> = _socketFrames.asSharedFlow()

    private val _socketErrors = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val socketErrors: SharedFlow<String> = _socketErrors.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var chatSubscription: ChatSubscription? = null
    private var reconnectAttempt = 0
    private var closed = false

    init {
        scope.launch {
            var lastNetworkHandle: Long? = null
            var lastAddress: String? = null
            connectionManager.state.collect { state ->
                val nextAddress = state.endpoint?.address?.hostAddress
                val routeChanged = state.networkHandle != lastNetworkHandle || nextAddress != lastAddress
                if (routeChanged) {
                    connectionPool.evictAll()
                    webSocket?.cancel()
                    webSocket = null
                    _socketState.value = BitVoidHardwareSocketState.DISCONNECTED
                    if (state.isReady && chatSubscription != null) scheduleReconnect(immediate = true)
                }
                lastNetworkHandle = state.networkHandle
                lastAddress = nextAddress
            }
        }
    }

    suspend fun get(path: String, headers: Map<String, String> = emptyMap()): BitVoidApiResponse {
        val builder = Request.Builder().url(resolveHttpUrl(path)).get()
        headers.forEach(builder::header)
        return execute(builder.build())
    }

    suspend fun postJson(
        path: String,
        json: String,
        headers: Map<String, String> = emptyMap()
    ): BitVoidApiResponse {
        val requestBody = json.toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder().url(resolveHttpUrl(path)).post(requestBody)
        headers.forEach(builder::header)
        return execute(builder.build())
    }

    suspend fun putJson(
        path: String,
        json: String,
        headers: Map<String, String> = emptyMap()
    ): BitVoidApiResponse {
        val builder = Request.Builder()
            .url(resolveHttpUrl(path))
            .put(json.toRequestBody(JSON_MEDIA_TYPE))
        headers.forEach(builder::header)
        return execute(builder.build())
    }

    suspend fun delete(path: String, headers: Map<String, String> = emptyMap()): BitVoidApiResponse {
        val builder = Request.Builder().url(resolveHttpUrl(path)).delete()
        headers.forEach(builder::header)
        return execute(builder.build())
    }

    /** Common synchronization endpoint used by both the native UI and the web dashboard. */
    suspend fun synchronizeChannels(username: String? = null): BitVoidApiResponse {
        val query = username?.trim()?.takeIf { it.isNotEmpty() }?.let {
            "?username=${encodePathSegment(it.removePrefix("@"))}"
        }.orEmpty()
        return get("/api/channels$query")
    }

    /**
     * Opens `ws://bitvoid.local:80/ws/chat/{channelId}` and keeps reconnecting while the ESP Wi-Fi
     * remains selected. Incoming JSON frames are emitted through [socketFrames].
     */
    fun connectChat(channelId: String, token: String? = null) {
        val cleanChannelId = channelId.trim()
        require(cleanChannelId.isNotEmpty()) { "channelId cannot be blank" }
        chatSubscription = ChatSubscription(cleanChannelId, token?.trim()?.takeIf { it.isNotEmpty() })
        reconnectAttempt = 0
        openChatSocket()
    }

    fun sendChatFrame(json: String): Boolean {
        if (_socketState.value != BitVoidHardwareSocketState.OPEN) return false
        return webSocket?.send(json) == true
    }

    fun sendChatMessage(text: String, sender: String? = null): Boolean {
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return false
        val frame = JSONObject().apply {
            put("type", "chat")
            put("text", cleanText)
            sender?.trim()?.takeIf { it.isNotEmpty() }?.let { put("sender", it) }
        }.toString()
        return sendChatFrame(frame)
    }

    fun disconnectChat() {
        reconnectJob?.cancel()
        reconnectJob = null
        chatSubscription = null
        reconnectAttempt = 0
        webSocket?.close(1000, "Client closed")
        webSocket = null
        _socketState.value = BitVoidHardwareSocketState.DISCONNECTED
    }

    /**
     * Starts a streaming HTTP request with a standards-compliant Range header. ESP firmware should
     * answer with 206 and Content-Range; a 200 response is accepted only for a range starting at 0.
     */
    suspend fun openVideoRange(
        path: String,
        startByte: Long,
        endByteInclusive: Long? = null
    ): BitVoidByteRange = withContext(Dispatchers.IO) {
        require(startByte >= 0) { "startByte must be non-negative" }
        require(endByteInclusive == null || endByteInclusive >= startByte) {
            "endByteInclusive must be greater than or equal to startByte"
        }
        val rangeValue = buildString {
            append("bytes=")
            append(startByte)
            append('-')
            endByteInclusive?.let(::append)
        }
        val request = Request.Builder()
            .url(resolveHttpUrl(path))
            .header("Range", rangeValue)
            .header("Accept", "video/mp4,application/octet-stream")
            .get()
            .build()
        val response = httpClient.newCall(request).execute()
        val body = response.body
        if (body == null || (!response.isSuccessful && response.code != 206)) {
            val code = response.code
            response.close()
            throw IOException("Video range request failed with HTTP $code")
        }
        if (startByte > 0 && response.code != 206) {
            response.close()
            throw IOException("ESP node ignored Range request; expected HTTP 206")
        }
        BitVoidByteRange(
            responseCode = response.code,
            contentType = body.contentType()?.toString(),
            contentLength = body.contentLength(),
            contentRange = response.header("Content-Range"),
            stream = body.byteStream(),
            response = response
        )
    }

    /** URL suitable for Media3/ExoPlayer; Media3 automatically issues subsequent Range requests. */
    fun buildVideoStreamUrl(path: String): String = resolveHttpUrl(path)

    private suspend fun execute(request: Request): BitVoidApiResponse = withContext(Dispatchers.IO) {
        ensureReady()
        httpClient.newCall(request).execute().use { response ->
            BitVoidApiResponse(
                code = response.code,
                body = response.body?.string().orEmpty(),
                headers = response.headers
            )
        }
    }

    private fun openChatSocket() {
        val subscription = chatSubscription ?: return
        reconnectJob?.cancel()
        if (!connectionManager.state.value.isReady) {
            _socketState.value = BitVoidHardwareSocketState.CONNECTING
            scheduleReconnect()
            return
        }
        webSocket?.cancel()
        _socketState.value = BitVoidHardwareSocketState.CONNECTING
        val encodedChannel = encodePathSegment(subscription.channelId)
        val tokenQuery = subscription.token?.let { "?token=${encodePathSegment(it)}" }.orEmpty()
        val requestBuilder = Request.Builder()
            .url("${webSocketBaseUrl()}/ws/chat/$encodedChannel$tokenQuery")
        subscription.token?.let { requestBuilder.header("X-Channel-Token", it) }
        webSocket = httpClient.newWebSocket(requestBuilder.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                _socketState.value = BitVoidHardwareSocketState.OPEN
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                _socketFrames.tryEmit(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (chatSubscription != null && !closed) scheduleReconnect()
                else _socketState.value = BitVoidHardwareSocketState.DISCONNECTED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _socketState.value = BitVoidHardwareSocketState.ERROR
                _socketErrors.tryEmit(t.message ?: "WebSocket connection failed")
                if (chatSubscription != null && !closed) scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect(immediate: Boolean = false) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val waitMs = if (immediate) 0L else (1_000L shl reconnectAttempt.coerceAtMost(4))
            reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(5)
            delay(waitMs)
            if (!closed && chatSubscription != null) openChatSocket()
        }
    }

    private fun resolveHttpUrl(path: String): String {
        ensureReady()
        if (path.startsWith("http://") || path.startsWith("https://")) {
            val uri = URI(path)
            require(isAllowedNodeHost(uri.host.orEmpty())) { "Only local BitVoid node URLs are allowed" }
            return path
        }
        val cleanPath = if (path.startsWith('/')) path else "/$path"
        return "${httpBaseUrl()}$cleanPath"
    }

    private fun httpBaseUrl(): String {
        val endpoint = connectionManager.state.value.endpoint
            ?: throw IllegalStateException("BitVoid ESP node is not connected")
        return endpoint.portalUrl
    }

    private fun webSocketBaseUrl(): String {
        val endpoint = connectionManager.state.value.endpoint
            ?: throw IllegalStateException("BitVoid ESP node is not connected")
        return endpoint.webSocketBaseUrl
    }

    private fun ensureReady() {
        check(connectionManager.state.value.isReady) {
            "Connect to ${BitVoidConnectionManager.NODE_WIFI_SSID} before accessing the node"
        }
    }

    private fun resolveLocalHostname(hostname: String): List<InetAddress> {
        if (hostname.equals(BitVoidConnectionManager.NODE_HOSTNAME, ignoreCase = true)) {
            val address = connectionManager.currentAddress()
                ?: throw UnknownHostException("${BitVoidConnectionManager.NODE_HOSTNAME} is not resolved")
            return listOf(address)
        }
        if (!isAllowedNodeHost(hostname)) {
            throw UnknownHostException("Blocked non-local hostname '$hostname'")
        }
        return Dns.SYSTEM.lookup(hostname)
    }

    private fun isAllowedNodeHost(hostname: String): Boolean {
        val lower = hostname.lowercase()
        if (lower == BitVoidConnectionManager.NODE_HOSTNAME ||
            lower == BitVoidConnectionManager.NODE_GATEWAY ||
            lower == "127.0.0.1" || lower == "localhost"
        ) return true
        val parsed = runCatching { InetAddress.getByName(lower) }.getOrNull() ?: return false
        return parsed.isSiteLocalAddress || parsed.isLoopbackAddress
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }

    override fun close() {
        closed = true
        disconnectChat()
        scope.cancel()
        connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
    }

    private class CurrentNetworkSocketFactory(
        private val manager: BitVoidConnectionManager
    ) : SocketFactory() {
        private fun delegate(): SocketFactory = manager.currentNetwork()?.socketFactory
            ?: throw IOException("No bound BitVoid ESP Wi-Fi network")

        override fun createSocket(): Socket = delegate().createSocket()
        override fun createSocket(host: String, port: Int): Socket = delegate().createSocket(host, port)
        override fun createSocket(
            host: String,
            port: Int,
            localHost: InetAddress,
            localPort: Int
        ): Socket = delegate().createSocket(host, port, localHost, localPort)

        override fun createSocket(host: InetAddress, port: Int): Socket =
            delegate().createSocket(host, port)

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress,
            localPort: Int
        ): Socket = delegate().createSocket(address, port, localAddress, localPort)
    }
}

/** Application-scoped owner for the manager and native transport. */
class BitVoidHardwareIntegration(context: Context) : Closeable {
    val connectionManager = BitVoidConnectionManager(context)
    val nodeClient = BitVoidNodeClient(connectionManager)
    val state: StateFlow<BitVoidConnectionState> = connectionManager.state

    fun start() = connectionManager.start()
    fun refresh() = connectionManager.refresh()

    override fun close() {
        nodeClient.close()
        connectionManager.close()
    }
}
