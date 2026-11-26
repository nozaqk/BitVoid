package com.bitvoid.network.connection

import android.net.Network
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

enum class BitVoidServerConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class BitVoidServerResponse(
    val statusCode: Int,
    val body: String,
    val headers: okhttp3.Headers
) {
    val isSuccessful: Boolean get() = statusCode in 200..299
}

/**
 * Direct transport to the BitVoid application Server.
 *
 * The supplied Android Network must be the Wi-Fi network shared with the Server. The Gateway is
 * not used as an API proxy: requests go directly to the resolved Server IPv4 address.
 */
class BitVoidServerClient(
    private val wifiNetwork: Network,
    private val serverAddress: Inet4Address,
    private val serverPort: Int = DEFAULT_PORT,
    private val tokenProvider: () -> String? = { null }
) : Closeable {

    companion object {
        const val DEFAULT_PORT = 8787
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val baseUrl: String
        get() = "http://${serverAddress.hostAddress}:$serverPort"

    private val http = OkHttpClient.Builder()
        .socketFactory(wifiNetwork.socketFactory)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor { chain ->
            val request = chain.request()
            val token = tokenProvider()?.takeIf { it.isNotBlank() }
            val builder = request.newBuilder()
                .header("Accept", "application/json")
                .header("Cache-Control", "no-store")
                .header("User-Agent", "BitVoid-Android/1")
            if (token != null) builder.header("Authorization", "Bearer $token")
            chain.proceed(builder.build())
        }
        .build()

    private val _connectionState =
        MutableStateFlow(BitVoidServerConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BitVoidServerConnectionState> =
        _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 128)
    val events: SharedFlow<String> = _events.asSharedFlow()

    private val _eventErrors = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val eventErrors: SharedFlow<String> = _eventErrors.asSharedFlow()

    @Volatile
    private var eventSocket: WebSocket? = null

    init {
        require(serverPort in 1..65535) { "Invalid Server port" }
    }

    suspend fun get(path: String): BitVoidServerResponse =
        execute(Request.Builder().url(resolveUrl(path)).get().build())

    suspend fun postJson(path: String, json: String, idempotencyKey: String? = null): BitVoidServerResponse {
        val builder = Request.Builder()
            .url(resolveUrl(path))
            .post(json.toRequestBody(JSON_MEDIA_TYPE))
        idempotencyKey?.takeIf { it.isNotBlank() }?.let {
            builder.header("Idempotency-Key", it)
        }
        return execute(builder.build())
    }

    suspend fun putJson(path: String, json: String): BitVoidServerResponse =
        execute(
            Request.Builder()
                .url(resolveUrl(path))
                .put(json.toRequestBody(JSON_MEDIA_TYPE))
                .build()
        )

    suspend fun delete(path: String): BitVoidServerResponse =
        execute(Request.Builder().url(resolveUrl(path)).delete().build())

    /** Opens the authenticated Server event stream. Incoming JSON frames are emitted in [events]. */
    fun connectEvents() {
        eventSocket?.cancel()
        _connectionState.value = BitVoidServerConnectionState.CONNECTING
        val token = tokenProvider()?.takeIf { it.isNotBlank() }
        val request = Request.Builder()
            .url("ws://${serverAddress.hostAddress}:$serverPort/api/v1/events")
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()

        eventSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _connectionState.value = BitVoidServerConnectionState.CONNECTED
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                _events.tryEmit(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, "Closing")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _connectionState.value = BitVoidServerConnectionState.DISCONNECTED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _connectionState.value = BitVoidServerConnectionState.ERROR
                _eventErrors.tryEmit(t.message ?: "Server event connection failed")
            }
        })
    }

    /** Sends a protocol frame such as a subscription or ping to the Server. */
    fun sendEventFrame(json: String): Boolean = eventSocket?.send(json) == true

    fun subscribe(
        conversationIds: List<String> = emptyList(),
        channelIds: List<String> = emptyList(),
        liveClassIds: List<String> = emptyList()
    ): Boolean {
        val frame = JSONObject().apply {
            put("type", "subscribe")
            put("conversationIds", org.json.JSONArray(conversationIds))
            put("channelIds", org.json.JSONArray(channelIds))
            put("liveClassIds", org.json.JSONArray(liveClassIds))
        }
        return sendEventFrame(frame.toString())
    }

    fun disconnectEvents() {
        eventSocket?.close(1000, "Client disconnected")
        eventSocket = null
        _connectionState.value = BitVoidServerConnectionState.DISCONNECTED
    }

    private suspend fun execute(request: Request): BitVoidServerResponse =
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                BitVoidServerResponse(
                    statusCode = response.code,
                    body = response.body?.string().orEmpty(),
                    headers = response.headers
                )
            }
        }

    private fun resolveUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            throw IllegalArgumentException("Only relative BitVoid Server API paths are accepted")
        }
        val normalized = if (path.startsWith('/')) path else "/$path"
        require(normalized.startsWith("/api/v1/")) {
            "Server API paths must be versioned under /api/v1"
        }
        return "$baseUrl$normalized"
    }

    override fun close() {
        disconnectEvents()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}
