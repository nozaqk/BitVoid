package com.bitvoid.network.local

import android.os.Build
import com.bitvoid.network.BuildConfig
import com.bitvoid.network.backend.BitVoidBackendPrefs
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
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

class LocalApiException(val statusCode: Int, message: String) : IOException(message)

data class LocalPairingResult(
    val requestId: String?,
    val pairingCode: String?,
    val status: String,
    val deviceToken: String?
)

data class LocalProfile(val id: String, val username: String, val displayName: String)

/** Client for the versioned PC-server API. It rejects every non-LAN destination. */
class BitVoidLocalApi {
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var baseUrl: String = BitVoidBackendPrefs.DEFAULT_LOCAL_URL
    @Volatile private var deviceToken: String = ""

    fun configure(serverUrl: String, token: String?) {
        val normalized = BitVoidBackendPrefs.normalizeConfiguredUrl(serverUrl, allowCleartext = true)
            ?.removeSuffix("/api/v1")
            ?.trimEnd('/')
            ?.takeIf(BitVoidBackendPrefs::isAllowedLocalServer)
            ?: throw IllegalArgumentException("Only a private local AirMesh server is allowed.")
        baseUrl = normalized
        deviceToken = token.orEmpty()
    }

    suspend fun requestPairing(
        credentials: BitVoidLocalCredentials,
        previousRequestId: String? = null
    ): LocalPairingResult = postJson(
        "/api/v1/pairing/request",
        JSONObject().apply {
            put("deviceId", credentials.deviceId())
            put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
            put("platform", "android")
            put("publicKey", credentials.publicKeyBase64())
            put("publicKeyAlgorithm", "EC_P256_SHA256")
            put("appVersion", BuildConfig.VERSION_NAME)
            previousRequestId?.takeIf { it.isNotBlank() }?.let { put("requestId", it) }
        },
        authenticated = false
    ).let(::parsePairing)

    suspend fun loadProfile(): LocalProfile = getJson("/api/v1/me").let(::parseProfile)

    suspend fun registerUser(username: String, deviceId: String): LocalProfile = postJson(
        "/api/v1/users/register",
        JSONObject().apply {
            put("username", username)
            put("displayName", username)
            put("deviceId", deviceId)
        }
    ).let(::parseProfile)

    suspend fun conversations(): List<LocalConversation> = withContext(Dispatchers.IO) {
        val text = execute(Request.Builder().url(url("/api/v1/conversations")).get().build())
        val array = jsonArray(text, "conversations")
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.string("id", "conversationId") ?: continue
                add(
                    LocalConversation(
                        id = id,
                        title = item.string("title", "name", "displayName") ?: "Conversation",
                        updatedAtMs = item.timestamp("updatedAt", "updatedAtMs", "lastTimestampMs"),
                        lastMessage = item.optJSONObject("lastMessage")
                            ?.string("content", "text")
                            ?: item.string("lastMessage", "lastMessagePreview").orEmpty()
                    )
                )
            }
        }
    }

    suspend fun createConversation(participantId: String): LocalConversation = postJson(
        "/api/v1/conversations",
        JSONObject().put("participantIds", JSONArray().put(participantId))
    ).let { root ->
        val item = root.optJSONObject("conversation") ?: root
        val id = item.string("id", "conversationId")
            ?: throw IOException("Server response is missing the conversation ID.")
        LocalConversation(
            id = id,
            title = item.string("title", "name", "displayName") ?: participantId,
            updatedAtMs = item.timestamp("updatedAt", "updatedAtMs").takeIf { it > 0 }
                ?: System.currentTimeMillis()
        )
    }

    suspend fun messages(conversationId: String): List<LocalMessage> = withContext(Dispatchers.IO) {
        val encodedId = encodePath(conversationId)
        val text = execute(
            Request.Builder().url(url("/api/v1/conversations/$encodedId/messages")).get().build()
        )
        parseMessages(jsonArray(text, "messages"), conversationId)
    }

    suspend fun sendMessage(
        conversationId: String,
        content: String,
        clientMessageId: String
    ): LocalMessage {
        val encodedId = encodePath(conversationId)
        val request = Request.Builder()
            .url(url("/api/v1/conversations/$encodedId/messages"))
            .header("Idempotency-Key", clientMessageId)
            .post(JSONObject().put("content", content).toString().toRequestBody(jsonType))
            .build()
        val root = withContext(Dispatchers.IO) { JSONObject(execute(request)) }
        val item = root.optJSONObject("message") ?: root
        return parseMessage(item, conversationId) ?: LocalMessage(
            id = clientMessageId,
            conversationId = conversationId,
            sender = "me",
            content = content,
            createdAtMs = System.currentTimeMillis(),
            deliveryState = LocalDeliveryState.SENT
        )
    }

    fun openEvents(
        listener: (LocalMessage) -> Unit,
        onState: (Boolean, String?) -> Unit
    ): WebSocket {
        val wsBase = if (baseUrl.startsWith("https://")) {
            "wss://${baseUrl.removePrefix("https://")}"
        } else {
            "ws://${baseUrl.removePrefix("http://")}"
        }
        val request = authorized(Request.Builder().url("$wsBase/api/v1/events")).build()
        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onState(true, null)

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = JSONObject(text)
                    val data = root.optJSONObject("data") ?: root.optJSONObject("message") ?: root
                    val conversationId = data.string("conversationId", "conversation_id") ?: return
                    parseMessage(data, conversationId)?.let(listener)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                onState(false, reason.takeIf { it.isNotBlank() })

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                onState(false, t.message)
        })
    }

    fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        JSONObject(execute(Request.Builder().url(url(path)).get().build()))
    }

    private suspend fun postJson(
        path: String,
        body: JSONObject,
        authenticated: Boolean = true
    ): JSONObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url(path))
            .post(body.toString().toRequestBody(jsonType))
        val request = if (authenticated) authorized(builder).build() else builder.build()
        JSONObject(execute(request, addAuthorization = authenticated))
    }

    private fun execute(request: Request, addAuthorization: Boolean = true): String {
        val localRequest = if (addAuthorization) authorized(request.newBuilder()).build() else request
        client.newCall(localRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(body).string("message", "detail", "error")
                }.getOrNull()
                throw LocalApiException(
                    response.code,
                    detail?.takeIf { it.isNotBlank() }
                        ?: "Local server request failed (HTTP ${response.code})."
                )
            }
            return body.ifBlank { "{}" }
        }
    }

    private fun authorized(builder: Request.Builder): Request.Builder {
        if (deviceToken.isNotBlank()) builder.header("Authorization", "Bearer $deviceToken")
        builder.header("Accept", "application/json")
        builder.header("User-Agent", "BitVoid-Android/${BuildConfig.VERSION_NAME}")
        return builder
    }

    private fun url(path: String): String = baseUrl.trimEnd('/') + path

    private fun parsePairing(root: JSONObject): LocalPairingResult {
        val data = root.optJSONObject("pairing") ?: root
        return LocalPairingResult(
            requestId = data.string("requestId", "pairingId", "challengeId"),
            pairingCode = data.string("pairingCode", "code"),
            status = data.string("status") ?: if (data.string("deviceToken", "token", "accessToken") != null) "approved" else "pending",
            deviceToken = data.string("deviceToken", "token", "accessToken")
        )
    }

    private fun parseProfile(root: JSONObject): LocalProfile {
        val item = root.optJSONObject("user") ?: root.optJSONObject("profile") ?: root
        val username = item.string("username", "name")
            ?: throw IOException("Server response is missing the local username.")
        return LocalProfile(
            id = item.string("id", "userId") ?: username,
            username = username,
            displayName = item.string("displayName", "name") ?: username
        )
    }

    private fun parseMessages(array: JSONArray, conversationId: String): List<LocalMessage> =
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { parseMessage(it, conversationId) }?.let(::add)
            }
        }.distinctBy { it.id }.sortedBy { it.createdAtMs }

    private fun parseMessage(item: JSONObject, conversationId: String): LocalMessage? {
        val content = item.string("content", "text", "body") ?: return null
        val id = item.string("clientMessageId", "id", "messageId")
            ?: "server-${conversationId}-${content.hashCode()}-${item.timestamp("createdAt", "timestamp", "createdAtMs")}"
        val senderObject = item.optJSONObject("sender")
        return LocalMessage(
            id = id,
            conversationId = item.string("conversationId", "conversation_id") ?: conversationId,
            sender = senderObject?.string("username", "displayName", "id")
                ?: item.string("senderUsername", "sender", "userId")
                ?: "Peer",
            content = content,
            createdAtMs = item.timestamp("createdAt", "timestamp", "createdAtMs")
                .takeIf { it > 0 } ?: System.currentTimeMillis(),
            deliveryState = LocalDeliveryState.DELIVERED
        )
    }

    private fun jsonArray(text: String, key: String): JSONArray = if (text.trimStart().startsWith("[")) {
        JSONArray(text)
    } else {
        val root = JSONObject(text)
        root.optJSONArray(key) ?: root.optJSONObject("data")?.optJSONArray(key) ?: JSONArray()
    }

    private fun JSONObject.string(vararg keys: String): String? {
        keys.forEach { key ->
            optString(key).trim().takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        }
        return null
    }

    private fun JSONObject.timestamp(vararg keys: String): Long {
        keys.forEach { key ->
            if (has(key)) {
                optLong(key, 0L).takeIf { it > 0 }?.let { return it }
                string(key)?.let { value ->
                    runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()?.let { return it }
                }
            }
        }
        return 0L
    }

    private fun encodePath(value: String): String = java.net.URLEncoder
        .encode(value, Charsets.UTF_8.name())
        .replace("+", "%20")
}
