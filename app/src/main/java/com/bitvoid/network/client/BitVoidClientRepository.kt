package com.bitvoid.network.client

import android.content.Context
import android.net.Uri
import com.bitvoid.network.backend.BitVoidBackendPrefs
import android.provider.OpenableColumns
import com.bitvoid.network.call.DirectCallPhase
import com.bitvoid.network.call.ParticipantRole
import com.bitvoid.network.call.SignalingPeer
import com.bitvoid.network.call.SignalingSessionMode
import com.bitvoid.network.model.BitVoidNetworkConfig
import com.bitvoid.network.model.MediaCategory
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
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
    val isHidden: Boolean = false,
    val isOwner: Boolean = false,
    val hasPrivateKey: Boolean,
    val mediaCount: Int,
    val activePeers: Int,
    val messageCount: Int,
    val lastMessagePreview: String? = null,
    val lastTimestampMs: Long = 0L
)

data class ClientChannelSession(
    val channelId: String,
    val name: String,
    val isPublic: Boolean,
    val token: String,
    val callsign: String,
    val mediaList: List<RemoteMediaItem>,
    val isLiveClassRoom: Boolean = false
)

data class ClientChatAttachment(
    val id: String,
    val name: String,
    val size: Long,
    val mimeType: String,
    val streamUrl: String,
    val downloadUrl: String
)

enum class MessageDeliveryState {
    PENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}

data class ClientChatMessage(
    val id: String,
    val channelId: String,
    val sender: String,
    val text: String,
    val time: String,
    val timestampMs: Long = 0L,
    val isSystemNotice: Boolean = false,
    val attachment: ClientChatAttachment? = null,
    val deliveryState: MessageDeliveryState = MessageDeliveryState.DELIVERED
)

data class ClientDirectSession(
    val dmId: String,
    val myUsername: String,
    val peerUsername: String,
    val token: String
)

data class CachedConversationPreview(
    val conversationId: String,
    val direct: Boolean,
    val lastMessage: ClientChatMessage,
    val messageCount: Int
)

/**
 * A newly received WebSocket chat frame. History snapshots, presence updates, call signaling,
 * and Live Class state never enter this stream.
 */
sealed interface ClientIncomingMessageEvent {
    val message: ClientChatMessage

    data class Channel(
        val session: ClientChannelSession,
        override val message: ClientChatMessage
    ) : ClientIncomingMessageEvent

    data class Direct(
        val session: ClientDirectSession,
        override val message: ClientChatMessage
    ) : ClientIncomingMessageEvent
}

data class IncomingDirectCall(
    val dmId: String,
    val callerUsername: String,
    val calleeUsername: String,
    val createdAtMs: Long
)

data class ChannelCallStatus(
    val roomId: String = "",
    val isActive: Boolean = false,
    val mode: SignalingSessionMode = SignalingSessionMode.AUDIO_CALL,
    val hostPeerId: String = "",
    val classCode: String? = null,
    val participantCount: Int = 0,
    val peers: List<SignalingPeer> = emptyList(),
    val directCallPhase: DirectCallPhase = DirectCallPhase.IDLE,
    val callerUsername: String = "",
    val calleeUsername: String = "",
    val isIncoming: Boolean = false,
    val isOutgoing: Boolean = false
)

data class LiveClassJoinInfo(
    val classCode: String,
    val roomId: String,
    val channelId: String,
    val channelName: String,
    val token: String,
    val callsign: String,
    val creatorUsername: String = "",
    val isHidden: Boolean = false
)

data class RemoteLiveClassSummary(
    val roomId: String,
    val channelId: String,
    val name: String,
    val classCode: String,
    val creatorUsername: String,
    val isHidden: Boolean,
    val participantCount: Int
)

enum class ClientWsStatus {
    DISCONNECTED,
    CONNECTING,
    LIVE,
    ERROR
}

class BitVoidClientRepository(private val appContext: Context? = null) {

    companion object {
        const val DEFAULT_GATEWAY_URL = "http://" + BitVoidNetworkConfig.LOCAL_GATEWAY_HOSTNAME
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var allowedCloudBaseUrl: String = appContext
        ?.let(BitVoidBackendPrefs::load)
        ?.cloudServerUrl
        .orEmpty()

    private data class PendingOutgoingMessage(
        val id: String,
        val conversationId: String,
        val text: String,
        val direct: Boolean,
        val createdAtMs: Long
    )

    private val messageStore = BitVoidMessageStore(appContext)
    private val pendingOutgoing = ConcurrentHashMap<String, PendingOutgoingMessage>()
    private var pendingRetryJob: Job? = null
    private val _cachedConversationPreviews = MutableStateFlow<List<CachedConversationPreview>>(emptyList())
    val cachedConversationPreviews: StateFlow<List<CachedConversationPreview>> =
        _cachedConversationPreviews.asStateFlow()

    init {
        val storedThreads = messageStore.loadAll()
        _cachedConversationPreviews.value = storedThreads.mapNotNull { thread ->
            thread.messages.lastOrNull()?.let { last ->
                CachedConversationPreview(
                    conversationId = thread.conversationId,
                    direct = thread.direct,
                    lastMessage = last,
                    messageCount = thread.messages.size
                )
            }
        }
        storedThreads.forEach { thread ->
            thread.messages
                .filter { it.deliveryState != MessageDeliveryState.DELIVERED &&
                    it.deliveryState != MessageDeliveryState.READ }
                .forEach { message ->
                    pendingOutgoing[message.id] = PendingOutgoingMessage(
                        id = message.id,
                        conversationId = thread.conversationId,
                        text = message.text,
                        direct = thread.direct,
                        createdAtMs = message.timestampMs
                    )
                }
        }
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val host = original.url.host
            val configuredCloudHost = runCatching {
                Uri.parse(allowedCloudBaseUrl).host
            }.getOrNull()
            val allowedRemote = !configuredCloudHost.isNullOrBlank() &&
                host.equals(configuredCloudHost, ignoreCase = true) &&
                original.url.isHttps
            if (!BitVoidClientManager.isPrivateLocalHost(host) && !allowedRemote) {
                throw IOException("Blocked unconfigured backend host '$host' by BitVoid firewall.")
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
    private var channelReconnectJob: Job? = null
    private var directReconnectJob: Job? = null
    private var callStatusPollJob: Job? = null
    private var directCallStatusPollJob: Job? = null

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

    private val _incomingMessages = MutableSharedFlow<ClientIncomingMessageEvent>(
        extraBufferCapacity = 64
    )
    val incomingMessages: SharedFlow<ClientIncomingMessageEvent> = _incomingMessages.asSharedFlow()

    private val _directWsStatus = MutableStateFlow(ClientWsStatus.DISCONNECTED)
    val directWsStatus: StateFlow<ClientWsStatus> = _directWsStatus.asStateFlow()

    private val _isOpeningDirectChat = MutableStateFlow(false)
    val isOpeningDirectChat: StateFlow<Boolean> = _isOpeningDirectChat.asStateFlow()

    private val _directChatError = MutableStateFlow<String?>(null)
    val directChatError: StateFlow<String?> = _directChatError.asStateFlow()

    private val _isUploadingFile = MutableStateFlow(false)
    val isUploadingFile: StateFlow<Boolean> = _isUploadingFile.asStateFlow()

    private val _fileUploadError = MutableStateFlow<String?>(null)
    val fileUploadError: StateFlow<String?> = _fileUploadError.asStateFlow()

    private val _activeChannelCallStatus = MutableStateFlow<ChannelCallStatus?>(null)
    val activeChannelCallStatus: StateFlow<ChannelCallStatus?> = _activeChannelCallStatus.asStateFlow()

    private val _activeDirectCallStatus = MutableStateFlow<ChannelCallStatus?>(null)
    val activeDirectCallStatus: StateFlow<ChannelCallStatus?> = _activeDirectCallStatus.asStateFlow()

    private val _liveClasses = MutableStateFlow<List<RemoteLiveClassSummary>>(emptyList())
    val liveClasses: StateFlow<List<RemoteLiveClassSummary>> = _liveClasses.asStateFlow()

    fun configureCloudBaseUrl(rawUrl: String): String? {
        val normalized = BitVoidBackendPrefs.normalizeConfiguredUrl(rawUrl, allowCleartext = false)
        allowedCloudBaseUrl = normalized.orEmpty()
        return normalized
    }

    fun setGatewayBaseUrl(rawUrl: String): String? {
        val local = BitVoidClientManager.normalizeLocalPortalUrl(rawUrl)?.trimEnd('/')
        val configuredCloud = allowedCloudBaseUrl.trimEnd('/')
        val remote = BitVoidBackendPrefs.normalizeConfiguredUrl(rawUrl, allowCleartext = false)
            ?.trimEnd('/')
            ?.takeIf { candidate ->
                configuredCloud.isNotBlank() && candidate.equals(configuredCloud, ignoreCase = true)
            }
        val normalized = local ?: remote ?: return null
        val changed = normalized != _gatewayBaseUrl.value
        _gatewayBaseUrl.value = normalized
        if (changed) schedulePendingRetry(100L)
        return normalized
    }

    fun flushPendingMessages() {
        schedulePendingRetry(0L)
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

    suspend fun fetchChannels(
        urlOverride: String? = null,
        username: String? = null
    ) {
        val targetBase = (urlOverride?.let { setGatewayBaseUrl(it) } ?: _gatewayBaseUrl.value)
            .trimEnd('/')
        _isLoadingChannels.value = true
        _channelsError.value = null

        val result = withContext(Dispatchers.IO) {
            try {
                val cleanUsername = username?.trim()?.removePrefix("@").orEmpty()
                val channelsUrl = if (cleanUsername.isNotEmpty()) {
                    val encoded = URLEncoder.encode(cleanUsername, StandardCharsets.UTF_8.name())
                        .replace("+", "%20")
                    "$targetBase/api/channels?username=$encoded"
                } else {
                    "$targetBase/api/channels"
                }
                val request = Request.Builder()
                    .url(channelsUrl)
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
            val pendingForChannel = (
                _channelMessages.value + messageStore.load(session.channelId, direct = false)
            ).filter {
                it.channelId == session.channelId &&
                    it.deliveryState in setOf(MessageDeliveryState.PENDING, MessageDeliveryState.SENT, MessageDeliveryState.FAILED)
            }
            _channelMessages.value = (initialMessages + pendingForChannel)
                .distinctBy { it.id }
                .sortedBy { it.timestampMs }
                .takeLast(120)
            messageStore.save(session.channelId, direct = false, _channelMessages.value)
            _channelJoinError.value = null
            _channelPeersCount.value = 1
            connectChatWebSocket(targetBase, session)
            startCallStatusPolling(targetBase, session)
            schedulePendingRetry(0L)
        }.onFailure { err ->
            val cached = messageStore.load(channelId, direct = false)
            if (cached.isNotEmpty() && err !is SecurityException) {
                closeSocketOnly()
                _activeChannelSession.value = ClientChannelSession(
                    channelId = channelId,
                    name = _channels.value.firstOrNull { it.channelId == channelId }?.name
                        ?: channelId,
                    isPublic = true,
                    token = "",
                    callsign = username?.trim()?.removePrefix("@").orEmpty().ifBlank { "Offline" },
                    mediaList = emptyList()
                )
                _channelMessages.value = cached
                _wsStatus.value = ClientWsStatus.DISCONNECTED
                _channelJoinError.value = "Offline · messages are queued until BitVoid reconnects."
            } else {
                _channelJoinError.value = err.message ?: "Failed to unlock channel"
            }
        }
    }

    suspend fun fetchLiveClasses(username: String, urlOverride: String? = null) {
        val targetBase = (urlOverride?.let { setGatewayBaseUrl(it) } ?: _gatewayBaseUrl.value)
            .trimEnd('/')
        val cleanUser = username.trim().removePrefix("@")
        val encodedUser = URLEncoder.encode(cleanUser, StandardCharsets.UTF_8.name())
            .replace("+", "%20")

        val result = withContext(Dispatchers.IO) {
            try {
                val url = if (cleanUser.isNotEmpty()) {
                    "$targetBase/api/live-classes?username=$encodedUser"
                } else {
                    "$targetBase/api/live-classes"
                }
                val request = Request.Builder().url(url).get().build()
                okHttpClient.newCall(request).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Result.failure(IOException("Gateway returned HTTP ${resp.code}"))
                    } else {
                        val json = JSONObject(bodyStr)
                        val arr = json.optJSONArray("liveClasses") ?: JSONArray()
                        val list = ArrayList<RemoteLiveClassSummary>(arr.length())
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            list.add(
                                RemoteLiveClassSummary(
                                    roomId = obj.optString("roomId"),
                                    channelId = obj.optString("channelId", obj.optString("roomId")),
                                    name = obj.optString("name", "Live Class"),
                                    classCode = obj.optString("classCode"),
                                    creatorUsername = obj.optString("creatorUsername"),
                                    isHidden = obj.optBoolean("isHidden", false),
                                    participantCount = obj.optInt("participantCount", 0)
                                )
                            )
                        }
                        Result.success(list)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        result.onSuccess { list ->
            _liveClasses.value = list
        }
    }

    suspend fun startLiveClassSession(
        name: String,
        isHidden: Boolean,
        creatorUsername: String,
        urlOverride: String? = null
    ): Result<LiveClassJoinInfo> {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            return Result.failure(IllegalArgumentException("Enter a Live Class name."))
        }
        val targetBase = (urlOverride?.let { setGatewayBaseUrl(it) } ?: _gatewayBaseUrl.value)
            .trimEnd('/')

        _isJoiningChannel.value = true
        _channelJoinError.value = null

        val result = withContext(Dispatchers.IO) {
            try {
                val bodyJson = JSONObject().apply {
                    put("name", cleanName)
                    put("isHidden", isHidden)
                    val cleanUser = creatorUsername.trim().removePrefix("@")
                    if (cleanUser.isNotEmpty()) {
                        put("username", cleanUser)
                    }
                }.toString()

                val request = Request.Builder()
                    .url("$targetBase/api/live-class/start")
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
                            IOException(detail.ifBlank { "Unable to start Live Class (HTTP ${resp.code})" })
                        )
                    } else {
                        parseLiveClassSessionResponse(JSONObject(bodyStr), isLiveClass = true)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        _isJoiningChannel.value = false
        return result.fold(
            onSuccess = { (session, _, joinInfo) ->
                leaveDirectChat()
                closeSocketOnly()
                _activeChannelSession.value = session
                _channelMessages.value = emptyList()
                _channelJoinError.value = null
                _channelPeersCount.value = 1
                startCallStatusPolling(targetBase, session)
                scope.launch { fetchLiveClasses(creatorUsername, targetBase) }
                Result.success(joinInfo)
            },
            onFailure = { err ->
                _channelJoinError.value = err.message ?: "Unable to start Live Class"
                Result.failure(err)
            }
        )
    }

    suspend fun joinLiveClassByRoomId(
        roomId: String,
        username: String,
        urlOverride: String? = null
    ): Result<LiveClassJoinInfo> {
        val cleanRoomId = roomId.trim()
        if (cleanRoomId.isEmpty()) {
            return Result.failure(IllegalArgumentException("Missing Live Class room ID."))
        }
        val targetBase = (urlOverride?.let { setGatewayBaseUrl(it) } ?: _gatewayBaseUrl.value)
            .trimEnd('/')

        _isJoiningChannel.value = true
        _channelJoinError.value = null

        val result = withContext(Dispatchers.IO) {
            try {
                val bodyJson = JSONObject().apply {
                    put("roomId", cleanRoomId)
                    val cleanUser = username.trim().removePrefix("@")
                    if (cleanUser.isNotEmpty()) {
                        put("username", cleanUser)
                    }
                }.toString()

                val request = Request.Builder()
                    .url("$targetBase/api/live-class/join")
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
                                detail.ifBlank { "Unable to join Live Class (HTTP ${resp.code})" }
                            )
                        )
                    } else {
                        parseLiveClassSessionResponse(JSONObject(bodyStr), isLiveClass = true)
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        _isJoiningChannel.value = false
        return result.fold(
            onSuccess = { (session, _, joinInfo) ->
                leaveDirectChat()
                closeSocketOnly()
                _activeChannelSession.value = session
                _channelMessages.value = emptyList()
                _channelJoinError.value = null
                _channelPeersCount.value = 1
                startCallStatusPolling(targetBase, session)
                Result.success(joinInfo)
            },
            onFailure = { err ->
                _channelJoinError.value = err.message ?: "Unable to join Live Class"
                Result.failure(err)
            }
        )
    }

    suspend fun joinLiveClassByCode(
        rawClassCode: String,
        username: String? = null,
        urlOverride: String? = null
    ): Result<LiveClassJoinInfo> {
        val cleanCode = rawClassCode.trim().uppercase().replace(" ", "")
        if (cleanCode.isEmpty()) {
            val err = IllegalArgumentException("Enter a Live Class ID (e.g. CLS-ABCD-12).")
            return Result.failure(err)
        }
        val targetBase = (urlOverride?.let { setGatewayBaseUrl(it) } ?: _gatewayBaseUrl.value)
            .trimEnd('/')

        _isJoiningChannel.value = true
        _channelJoinError.value = null

        val result = withContext(Dispatchers.IO) {
            try {
                val bodyJson = JSONObject().apply {
                    put("classCode", cleanCode)
                    val cleanUser = username?.trim()?.removePrefix("@")
                    if (!cleanUser.isNullOrEmpty()) {
                        put("username", cleanUser)
                    }
                }.toString()

                val request = Request.Builder()
                    .url("$targetBase/api/live-class/join")
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
                                detail.ifBlank { "Invalid or inactive Live Class ID (HTTP ${resp.code})" }
                            )
                        )
                    } else {
                        parseLiveClassSessionResponse(
                            JSONObject(bodyStr),
                            fallbackCode = cleanCode,
                            isLiveClass = true
                        )
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        _isJoiningChannel.value = false
        return result.fold(
            onSuccess = { (session, _, joinInfo) ->
                leaveDirectChat()
                closeSocketOnly()
                _activeChannelSession.value = session
                _channelMessages.value = emptyList()
                _channelJoinError.value = null
                _channelPeersCount.value = 1
                startCallStatusPolling(targetBase, session)
                Result.success(joinInfo)
            },
            onFailure = { err ->
                _channelJoinError.value = err.message ?: "Unable to join Live Class"
                Result.failure(err)
            }
        )
    }

    private fun parseLiveClassSessionResponse(
        json: JSONObject,
        fallbackCode: String = "",
        isLiveClass: Boolean = true
    ): Result<Triple<ClientChannelSession, List<ClientChatMessage>, LiveClassJoinInfo>> {
        val token = json.getString("token")
        val resolvedChannelId = json.getString("channelId")
        val resolvedRoomId = json.optString("roomId", resolvedChannelId)
        val resolvedCode = json.optString("classCode", fallbackCode)
        val name = json.optString("name", resolvedChannelId)
        val isPublic = json.optBoolean("isPublic", false)
        val callsign = json.optString("callsign", "Student")
        val creatorUsername = json.optString("creatorUsername", "")
        val isHidden = json.optBoolean("isHidden", false)

        val session = ClientChannelSession(
            channelId = resolvedChannelId,
            name = name,
            isPublic = isPublic,
            token = token,
            callsign = callsign,
            mediaList = emptyList(),
            isLiveClassRoom = isLiveClass || resolvedRoomId.startsWith("LC-")
        )
        val joinInfo = LiveClassJoinInfo(
            classCode = resolvedCode,
            roomId = resolvedRoomId,
            channelId = resolvedChannelId,
            channelName = name,
            token = token,
            callsign = callsign,
            creatorUsername = creatorUsername,
            isHidden = isHidden
        )
        return Result.success(Triple(session, emptyList<ClientChatMessage>(), joinInfo))
    }

    suspend fun fetchChannelCallStatus(
        channelId: String? = null,
        token: String? = null,
        urlOverride: String? = null
    ) {
        val session = _activeChannelSession.value
        val targetChannelId = channelId?.trim()?.takeIf { it.isNotEmpty() }
            ?: session?.channelId
            ?: return
        val effectiveToken = token ?: session?.token
        val targetBase = (urlOverride?.let { setGatewayBaseUrl(it) } ?: _gatewayBaseUrl.value)
            .trimEnd('/')
        val encodedId = URLEncoder.encode(targetChannelId, StandardCharsets.UTF_8.name())
            .replace("+", "%20")

        withContext(Dispatchers.IO) {
            try {
                val reqBuilder = Request.Builder()
                    .url("$targetBase/api/channels/$encodedId/call/status")
                    .get()
                if (!effectiveToken.isNullOrBlank()) {
                    reqBuilder.header("X-Channel-Token", effectiveToken)
                }
                okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val modeStr = json.optString("mode", SignalingSessionMode.AUDIO_CALL.name)
                        val mode = runCatching { SignalingSessionMode.valueOf(modeStr) }
                            .getOrDefault(SignalingSessionMode.AUDIO_CALL)
                        val peersArr = json.optJSONArray("peers") ?: JSONArray()
                        val peers = ArrayList<SignalingPeer>(peersArr.length())
                        for (i in 0 until peersArr.length()) {
                            val p = peersArr.optJSONObject(i) ?: continue
                            val role = runCatching {
                                ParticipantRole.valueOf(p.optString("role", ParticipantRole.SPEAKER.name))
                            }.getOrDefault(ParticipantRole.SPEAKER)
                            peers.add(
                                SignalingPeer(
                                    peerId = p.optString("peerId"),
                                    displayName = p.optString("displayName", "Peer"),
                                    role = role,
                                    roomId = p.optString("roomId", targetChannelId),
                                    isMuted = p.optBoolean("isMuted", false),
                                    joinedAtMs = p.optLong("joinedAtMs", 0L)
                                )
                            )
                        }
                        _activeChannelCallStatus.value = ChannelCallStatus(
                            roomId = json.optString("roomId", targetChannelId),
                            isActive = json.optBoolean("isActive", false),
                            mode = mode,
                            hostPeerId = json.optString("hostPeerId", ""),
                            classCode = json.optString("classCode").takeIf { it.isNotBlank() },
                            participantCount = json.optInt("participantCount", peers.size),
                            peers = peers
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    suspend fun requestDirectCall(
        dmId: String,
        token: String,
        urlOverride: String? = null
    ): Result<Unit> = postDirectCallAction(
        dmId = dmId,
        token = token,
        action = "invite",
        body = "{}",
        urlOverride = urlOverride
    )

    suspend fun acceptDirectCall(
        dmId: String,
        token: String,
        urlOverride: String? = null
    ): Result<Unit> = postDirectCallAction(dmId, token, "accept", "{}", urlOverride)

    suspend fun declineDirectCall(
        dmId: String,
        token: String,
        urlOverride: String? = null
    ): Result<Unit> = postDirectCallAction(dmId, token, "decline", "{}", urlOverride)

    suspend fun cancelOrEndDirectCall(
        dmId: String,
        token: String,
        action: String = "end",
        urlOverride: String? = null
    ): Result<Unit> = postDirectCallAction(dmId, token, action, "{}", urlOverride)

    private suspend fun postDirectCallAction(
        dmId: String,
        token: String,
        action: String,
        body: String,
        urlOverride: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val targetBase = (urlOverride?.let { setGatewayBaseUrl(it) } ?: _gatewayBaseUrl.value)
                .trimEnd('/')
            val encodedDm = URLEncoder.encode(dmId, StandardCharsets.UTF_8.name())
                .replace("+", "%20")
            val request = Request.Builder()
                .url("$targetBase/api/dm/$encodedDm/call/$action")
                .header("X-DM-Token", token)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val payload = runCatching { JSONObject(response.body?.string().orEmpty()) }.getOrNull()
                    throw IOException(
                        payload?.optString("detail")?.takeIf { it.isNotBlank() }
                            ?: "Call request failed (${response.code})."
                    )
                }
            }
            fetchDirectCallStatus(dmId, token, targetBase)
            Result.success(Unit)
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }

    suspend fun fetchDirectCallStatus(
        dmId: String? = null,
        token: String? = null,
        urlOverride: String? = null
    ) {
        val session = _activeDirectSession.value
        val targetDmId = dmId?.trim()?.takeIf { it.isNotEmpty() } ?: session?.dmId ?: return
        val effectiveToken = token ?: session?.token ?: return
        val targetBase = (urlOverride?.let { setGatewayBaseUrl(it) } ?: _gatewayBaseUrl.value)
            .trimEnd('/')
        val encodedDm = URLEncoder.encode(targetDmId, StandardCharsets.UTF_8.name())
            .replace("+", "%20")

        withContext(Dispatchers.IO) {
            try {
                val req = Request.Builder()
                    .url("$targetBase/api/dm/$encodedDm/call/status")
                    .header("X-DM-Token", effectiveToken)
                    .get()
                    .build()
                okHttpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val json = JSONObject(resp.body?.string().orEmpty())
                        val mode = runCatching {
                            SignalingSessionMode.valueOf(json.optString("mode", SignalingSessionMode.AUDIO_CALL.name))
                        }.getOrDefault(SignalingSessionMode.AUDIO_CALL)
                        val peersArr = json.optJSONArray("peers") ?: JSONArray()
                        val peers = ArrayList<SignalingPeer>(peersArr.length())
                        for (i in 0 until peersArr.length()) {
                            val p = peersArr.optJSONObject(i) ?: continue
                            val role = runCatching {
                                ParticipantRole.valueOf(p.optString("role", ParticipantRole.SPEAKER.name))
                            }.getOrDefault(ParticipantRole.SPEAKER)
                            peers.add(
                                SignalingPeer(
                                    peerId = p.optString("peerId"),
                                    displayName = p.optString("displayName", "Peer"),
                                    role = role,
                                    roomId = p.optString("roomId", targetDmId),
                                    isMuted = p.optBoolean("isMuted", false),
                                    joinedAtMs = p.optLong("joinedAtMs", 0L)
                                )
                            )
                        }
                        _activeDirectCallStatus.value = ChannelCallStatus(
                            roomId = json.optString("roomId", targetDmId),
                            isActive = json.optBoolean("isActive", false),
                            mode = mode,
                            hostPeerId = json.optString("hostPeerId", ""),
                            classCode = null,
                            participantCount = json.optInt("participantCount", peers.size),
                            peers = peers,
                            directCallPhase = runCatching {
                                DirectCallPhase.valueOf(json.optString("callPhase", DirectCallPhase.IDLE.name))
                            }.getOrDefault(DirectCallPhase.IDLE),
                            callerUsername = json.optString("callerUsername", ""),
                            calleeUsername = json.optString("calleeUsername", ""),
                            isIncoming = json.optBoolean("isIncoming", false),
                            isOutgoing = json.optBoolean("isOutgoing", false)
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun startDirectCallStatusPolling(baseUrl: String, session: ClientDirectSession) {
        directCallStatusPollJob?.cancel()
        directCallStatusPollJob = scope.launch {
            while (true) {
                val cur = _activeDirectSession.value ?: break
                if (cur.dmId != session.dmId) break
                fetchDirectCallStatus(cur.dmId, cur.token, baseUrl)
                delay(1000L)
            }
        }
    }

    private fun startCallStatusPolling(baseUrl: String, session: ClientChannelSession) {
        callStatusPollJob?.cancel()
        callStatusPollJob = scope.launch {
            while (true) {
                val cur = _activeChannelSession.value ?: break
                if (cur.channelId != session.channelId) break
                fetchChannelCallStatus(cur.channelId, cur.token, baseUrl)
                delay(3500L)
            }
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

        val now = System.currentTimeMillis()
        val clientId = "client_${UUID.randomUUID()}"
        val pending = PendingOutgoingMessage(
            id = clientId,
            conversationId = session.channelId,
            text = trimmed,
            direct = false,
            createdAtMs = now
        )
        pendingOutgoing[clientId] = pending
        appendMessageIfNew(
            ClientChatMessage(
                id = clientId,
                channelId = session.channelId,
                sender = session.callsign,
                text = trimmed,
                time = formatMessageTime(now),
                timestampMs = now,
                deliveryState = MessageDeliveryState.PENDING
            )
        )
        val payload = pendingPayload(pending)
        val sentOverSocket = activeWebSocket
            ?.takeIf { _wsStatus.value == ClientWsStatus.LIVE }
            ?.send(payload) == true
        if (sentOverSocket) markMessageSent(clientId, direct = false)
        schedulePendingRetry(if (sentOverSocket) 2_000L else 0L)
    }

    fun buildAbsoluteStreamUrl(item: RemoteMediaItem, channelToken: String? = null): String {
        return buildAttachmentUrl(item.streamUrl, channelToken)
    }

    fun buildAttachmentUrl(pathOrUrl: String, token: String? = null): String {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            return if (!token.isNullOrBlank()) {
                val encoded = URLEncoder.encode(token, StandardCharsets.UTF_8.name())
                val separator = if (pathOrUrl.contains('?')) '&' else '?'
                "$pathOrUrl${separator}token=$encoded"
            } else {
                pathOrUrl
            }
        }
        val base = _gatewayBaseUrl.value.trimEnd('/')
        val path = if (pathOrUrl.startsWith("/")) pathOrUrl else "/$pathOrUrl"
        return if (!token.isNullOrBlank()) {
            val encoded = URLEncoder.encode(token, StandardCharsets.UTF_8.name())
            val separator = if (path.contains('?')) '&' else '?'
            "$base$path${separator}token=$encoded"
        } else {
            "$base$path"
        }
    }

    suspend fun uploadFileToChannel(
        context: Context,
        uri: Uri,
        caption: String = ""
    ): Result<Unit> {
        val session = _activeChannelSession.value
            ?: return Result.failure(IllegalStateException("Join a channel before uploading a file."))
        val targetBase = _gatewayBaseUrl.value.trimEnd('/')
        val encodedId = URLEncoder.encode(session.channelId, StandardCharsets.UTF_8.name())
            .replace("+", "%20")

        _isUploadingFile.value = true
        _fileUploadError.value = null

        val result = withContext(Dispatchers.IO) {
            uploadUriMultipart(
                context = context,
                uri = uri,
                uploadUrl = "$targetBase/api/channels/$encodedId/upload",
                tokenHeaderName = "X-Channel-Token",
                tokenValue = session.token,
                caption = caption
            )
        }

        _isUploadingFile.value = false
        result.onSuccess { msgJson ->
            if (msgJson != null) {
                appendMessageIfNew(parseMessageObject(msgJson))
            }
            refreshActiveChannelMedia()
        }.onFailure { err ->
            _fileUploadError.value = err.message ?: "Channel upload failed"
        }
        return result.map { Unit }
    }

    suspend fun uploadFileToDirectChat(
        context: Context,
        uri: Uri,
        caption: String = ""
    ): Result<Unit> {
        val session = _activeDirectSession.value
            ?: return Result.failure(IllegalStateException("Open a direct chat before uploading a file."))
        val targetBase = _gatewayBaseUrl.value.trimEnd('/')
        val encodedDm = URLEncoder.encode(session.dmId, StandardCharsets.UTF_8.name())
            .replace("+", "%20")

        _isUploadingFile.value = true
        _fileUploadError.value = null

        val result = withContext(Dispatchers.IO) {
            uploadUriMultipart(
                context = context,
                uri = uri,
                uploadUrl = "$targetBase/api/dm/$encodedDm/upload",
                tokenHeaderName = "X-DM-Token",
                tokenValue = session.token,
                caption = caption
            )
        }

        _isUploadingFile.value = false
        result.onSuccess { msgJson ->
            if (msgJson != null) {
                appendDirectMessageIfNew(parseMessageObject(msgJson))
            }
        }.onFailure { err ->
            _fileUploadError.value = err.message ?: "Direct chat upload failed"
        }
        return result.map { Unit }
    }

    fun clearFileUploadError() {
        _fileUploadError.value = null
    }

    private fun uploadUriMultipart(
        context: Context,
        uri: Uri,
        uploadUrl: String,
        tokenHeaderName: String,
        tokenValue: String,
        caption: String
    ): Result<JSONObject?> {
        var tempFile: File? = null
        return try {
            val resolver = context.contentResolver
            val fileName = resolveDisplayName(context, uri)
            val mimeType = resolver.getType(uri)?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
            tempFile = File.createTempFile("bv_upload_", ".tmp", context.cacheDir)
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: return Result.failure(IOException("Unable to read selected file."))

            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    fileName,
                    tempFile.asRequestBody(mimeType.toMediaTypeOrNull())
                )
            val trimmedCaption = caption.trim()
            if (trimmedCaption.isNotEmpty()) {
                multipartBuilder.addFormDataPart("text", trimmedCaption)
            }

            val request = Request.Builder()
                .url(uploadUrl)
                .header(tokenHeaderName, tokenValue)
                .post(multipartBuilder.build())
                .build()

            okHttpClient.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    val detail = try {
                        JSONObject(bodyStr).optString("detail", "")
                    } catch (_: Exception) {
                        ""
                    }
                    Result.failure(IOException(detail.ifBlank { "Upload failed (HTTP ${resp.code})" }))
                } else {
                    val json = JSONObject(bodyStr)
                    Result.success(json.optJSONObject("message"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempFile?.delete()
        }
    }

    private fun resolveDisplayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)?.trim()
                        if (!name.isNullOrEmpty()) return name
                    }
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "shared_file_${System.currentTimeMillis()}"
    }

    suspend fun registerUsername(
        username: String,
        urlOverride: String? = null,
        discoverable: Boolean = true
    ) {
        val clean = username.trim()
        if (clean.isEmpty()) return
        val targetBase = (urlOverride?.let { setGatewayBaseUrl(it) } ?: _gatewayBaseUrl.value)
            .trimEnd('/')
        withContext(Dispatchers.IO) {
            try {
                val payload = JSONObject()
                    .put("username", clean)
                    .put("discoverable", discoverable)
                    .toString()
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

    suspend fun fetchIncomingDirectCall(
        username: String,
        urlOverride: String? = null
    ): IncomingDirectCall? {
        val clean = username.trim().removePrefix("@")
        if (clean.isEmpty()) return null
        val targetBase = (urlOverride?.let { setGatewayBaseUrl(it) } ?: _gatewayBaseUrl.value)
            .trimEnd('/')
        return withContext(Dispatchers.IO) {
            try {
                val encodedUser = URLEncoder.encode(clean, StandardCharsets.UTF_8.name())
                    .replace("+", "%20")
                val request = Request.Builder()
                    .url("$targetBase/api/calls/incoming?username=$encodedUser")
                    .get()
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body?.string().orEmpty())
                    if (!json.optBoolean("hasIncomingCall", false)) return@use null
                    IncomingDirectCall(
                        dmId = json.optString("dmId"),
                        callerUsername = json.optString("callerUsername"),
                        calleeUsername = json.optString("calleeUsername"),
                        createdAtMs = json.optLong("createdAtMs", 0L)
                    )
                }
            } catch (_: Exception) {
                null
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
            val pendingForDirect = (
                _directMessages.value + messageStore.load(session.dmId, direct = true)
            ).filter {
                it.channelId == session.dmId &&
                    it.deliveryState in setOf(MessageDeliveryState.PENDING, MessageDeliveryState.SENT, MessageDeliveryState.FAILED)
            }
            _directMessages.value = (initialMessages + pendingForDirect)
                .distinctBy { it.id }
                .sortedBy { it.timestampMs }
                .takeLast(150)
            messageStore.save(session.dmId, direct = true, _directMessages.value)
            _directChatError.value = null
            connectDirectWebSocket(targetBase, session)
            startDirectCallStatusPolling(targetBase, session)
            schedulePendingRetry(0L)
            scope.launch { fetchKnownUsers(targetBase) }
        }.onFailure { err ->
            val cleanMe = myUsername.trim().removePrefix("@")
            val pair = listOf(cleanMe.lowercase(), cleanTarget.lowercase()).sorted()
            val offlineDmId = "dm__${pair[0]}__${pair[1]}"
            val cached = messageStore.load(offlineDmId, direct = true)
            if (cached.isNotEmpty()) {
                closeDirectSocketOnly()
                _activeDirectSession.value = ClientDirectSession(
                    dmId = offlineDmId,
                    myUsername = cleanMe,
                    peerUsername = cleanTarget,
                    token = ""
                )
                _directMessages.value = cached
                _directWsStatus.value = ClientWsStatus.DISCONNECTED
                _directChatError.value = "Offline · messages are queued until BitVoid reconnects."
            } else {
                _directChatError.value = err.message ?: "Unable to start direct chat"
            }
        }
    }

    fun sendDirectMessage(rawText: String) {
        val trimmed = rawText.trim()
        val session = _activeDirectSession.value ?: return
        if (trimmed.isEmpty()) return

        val now = System.currentTimeMillis()
        val clientId = "client_${UUID.randomUUID()}"
        val pending = PendingOutgoingMessage(
            id = clientId,
            conversationId = session.dmId,
            text = trimmed,
            direct = true,
            createdAtMs = now
        )
        pendingOutgoing[clientId] = pending
        appendDirectMessageIfNew(
            ClientChatMessage(
                id = clientId,
                channelId = session.dmId,
                sender = session.myUsername,
                text = trimmed,
                time = formatMessageTime(now),
                timestampMs = now,
                deliveryState = MessageDeliveryState.PENDING
            )
        )
        val payload = pendingPayload(pending)
        val sentOverSocket = activeDirectWebSocket
            ?.takeIf { _directWsStatus.value == ClientWsStatus.LIVE }
            ?.send(payload) == true
        if (sentOverSocket) markMessageSent(clientId, direct = true)
        schedulePendingRetry(if (sentOverSocket) 2_000L else 0L)
    }

    fun suspendChannelChatForLiveClass() {
        closeSocketOnly()
        _channelMessages.value = emptyList()
        _channelPeersCount.value = 1
        _wsStatus.value = ClientWsStatus.DISCONNECTED
    }

    fun leaveChannel() {
        callStatusPollJob?.cancel()
        callStatusPollJob = null
        _activeChannelCallStatus.value = null
        closeSocketOnly()
        _activeChannelSession.value = null
        _channelMessages.value = emptyList()
        _channelPeersCount.value = 1
        _wsStatus.value = ClientWsStatus.DISCONNECTED
        _channelJoinError.value = null
    }

    fun leaveDirectChat() {
        directCallStatusPollJob?.cancel()
        directCallStatusPollJob = null
        _activeDirectCallStatus.value = null
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
                    flushPendingMessages()
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
                    scheduleChannelReconnect(baseUrl, session)
                }
            }
        )
    }

    private fun scheduleChannelReconnect(baseUrl: String, session: ClientChannelSession) {
        channelReconnectJob?.cancel()
        channelReconnectJob = scope.launch {
            delay(1800)
            val current = _activeChannelSession.value ?: return@launch
            if (current.channelId == session.channelId && current.token == session.token) {
                connectChatWebSocket(baseUrl, current)
            }
        }
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
                    val message = parseMessageObject(msgObj)
                    val session = _activeChannelSession.value
                    if (appendMessageIfNew(message) && session != null &&
                        session.channelId == message.channelId
                    ) {
                        _incomingMessages.tryEmit(
                            ClientIncomingMessageEvent.Channel(session, message)
                        )
                    }
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
                    flushPendingMessages()
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
                    scheduleDirectReconnect(baseUrl, session)
                }
            }
        )
    }

    private fun scheduleDirectReconnect(baseUrl: String, session: ClientDirectSession) {
        directReconnectJob?.cancel()
        directReconnectJob = scope.launch {
            delay(1800)
            val current = _activeDirectSession.value ?: return@launch
            if (current.dmId == session.dmId && current.token == session.token) {
                connectDirectWebSocket(baseUrl, current)
            }
        }
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
                    val message = parseMessageObject(msgObj)
                    val session = _activeDirectSession.value
                    if (appendDirectMessageIfNew(message) && session != null &&
                        session.dmId == message.channelId
                    ) {
                        _incomingMessages.tryEmit(
                            ClientIncomingMessageEvent.Direct(session, message)
                        )
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun pendingPayload(pending: PendingOutgoingMessage): String = JSONObject()
        .put("text", pending.text)
        .put("clientMessageId", pending.id)
        .put("timestampMs", pending.createdAtMs)
        .toString()

    private fun schedulePendingRetry(delayMs: Long) {
        pendingRetryJob?.cancel()
        pendingRetryJob = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            flushPendingMessagesNow()
        }
    }

    private suspend fun flushPendingMessagesNow() {
        pendingOutgoing.values
            .sortedBy { it.createdAtMs }
            .forEach { pending ->
                val sessionToken = if (pending.direct) {
                    _activeDirectSession.value
                        ?.takeIf { it.dmId == pending.conversationId }
                        ?.token
                } else {
                    _activeChannelSession.value
                        ?.takeIf { it.channelId == pending.conversationId }
                        ?.token
                }?.takeIf { it.isNotBlank() } ?: return@forEach
                val encodedConversation = URLEncoder.encode(
                    pending.conversationId,
                    StandardCharsets.UTF_8.name()
                ).replace("+", "%20")
                val targetBase = _gatewayBaseUrl.value.trimEnd('/')
                val path = if (pending.direct) {
                    "/api/dm/$encodedConversation/messages"
                } else {
                    "/api/channels/$encodedConversation/chat"
                }
                val tokenHeader = if (pending.direct) "X-DM-Token" else "X-Channel-Token"
                try {
                    val request = Request.Builder()
                        .url(targetBase + path)
                        .header(tokenHeader, sessionToken)
                        .header("Idempotency-Key", pending.id)
                        .post(pendingPayload(pending).toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                    okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val body = JSONObject(response.body?.string().orEmpty())
                            val messageJson = body.optJSONObject("message")
                            if (messageJson != null) {
                                val delivered = parseMessageObject(messageJson)
                                    .copy(deliveryState = MessageDeliveryState.DELIVERED)
                                if (pending.direct) appendDirectMessageIfNew(delivered)
                                else appendMessageIfNew(delivered)
                            }
                            pendingOutgoing.remove(pending.id)
                        } else if (response.code == 401) {
                            // A backend switch invalidates old session tokens. Keep the message;
                            // reopening the same conversation supplies a new token and retries it.
                            markMessageFailed(pending.id, pending.direct)
                        }
                    }
                } catch (_: Exception) {
                    // Offline is a normal state. Keep the stable-ID item queued for the next
                    // backend health transition or WebSocket reconnect.
                }
            }
        if (pendingOutgoing.isNotEmpty()) schedulePendingRetry(8_000L)
    }

    private fun markMessageSent(id: String, direct: Boolean) {
        updateDeliveryState(id, direct, MessageDeliveryState.SENT)
    }

    private fun markMessageFailed(id: String, direct: Boolean) {
        updateDeliveryState(id, direct, MessageDeliveryState.FAILED)
    }

    private fun updateDeliveryState(
        id: String,
        direct: Boolean,
        state: MessageDeliveryState
    ) {
        if (direct) {
            _directMessages.value = _directMessages.value.map {
                if (it.id == id) it.copy(deliveryState = state) else it
            }
            _activeDirectSession.value?.let {
                messageStore.save(it.dmId, direct = true, _directMessages.value)
                updateCachedPreview(it.dmId, direct = true, _directMessages.value)
            }
        } else {
            _channelMessages.value = _channelMessages.value.map {
                if (it.id == id) it.copy(deliveryState = state) else it
            }
            _activeChannelSession.value?.let {
                messageStore.save(it.channelId, direct = false, _channelMessages.value)
                updateCachedPreview(it.channelId, direct = false, _channelMessages.value)
            }
        }
    }

    private fun updateCachedPreview(
        conversationId: String,
        direct: Boolean,
        messages: List<ClientChatMessage>
    ) {
        val last = messages.lastOrNull() ?: return
        val next = CachedConversationPreview(
            conversationId = conversationId,
            direct = direct,
            lastMessage = last,
            messageCount = messages.size
        )
        _cachedConversationPreviews.value = (
            _cachedConversationPreviews.value.filterNot {
                it.conversationId == conversationId && it.direct == direct
            } + next
        ).sortedByDescending { it.lastMessage.timestampMs }
    }

    private fun appendMessageIfNew(msg: ClientChatMessage): Boolean {
        val current = _channelMessages.value
        val existingIndex = current.indexOfFirst { it.id == msg.id }
        if (existingIndex >= 0) {
            val updated = current.toMutableList()
            updated[existingIndex] = msg.copy(deliveryState = MessageDeliveryState.DELIVERED)
            _channelMessages.value = updated
            messageStore.save(msg.channelId, direct = false, updated)
            updateCachedPreview(msg.channelId, direct = false, updated)
            pendingOutgoing.remove(msg.id)
            return false
        }
        _channelMessages.value = (current + msg)
            .distinctBy { it.id }
            .sortedWith(compareBy<ClientChatMessage> { it.timestampMs.takeIf { ts -> ts > 0L } ?: Long.MAX_VALUE })
            .takeLast(120)
        messageStore.save(msg.channelId, direct = false, _channelMessages.value)
        updateCachedPreview(msg.channelId, direct = false, _channelMessages.value)
        if (msg.deliveryState == MessageDeliveryState.DELIVERED ||
            msg.deliveryState == MessageDeliveryState.READ
        ) {
            pendingOutgoing.remove(msg.id)
        }
        return true
    }

    private fun appendDirectMessageIfNew(msg: ClientChatMessage): Boolean {
        val current = _directMessages.value
        val existingIndex = current.indexOfFirst { it.id == msg.id }
        if (existingIndex >= 0) {
            val updated = current.toMutableList()
            updated[existingIndex] = msg.copy(deliveryState = MessageDeliveryState.DELIVERED)
            _directMessages.value = updated
            messageStore.save(msg.channelId, direct = true, updated)
            updateCachedPreview(msg.channelId, direct = true, updated)
            pendingOutgoing.remove(msg.id)
            return false
        }
        _directMessages.value = (current + msg)
            .distinctBy { it.id }
            .sortedWith(compareBy<ClientChatMessage> { it.timestampMs.takeIf { ts -> ts > 0L } ?: Long.MAX_VALUE })
            .takeLast(150)
        messageStore.save(msg.channelId, direct = true, _directMessages.value)
        updateCachedPreview(msg.channelId, direct = true, _directMessages.value)
        if (msg.deliveryState == MessageDeliveryState.DELIVERED ||
            msg.deliveryState == MessageDeliveryState.READ
        ) {
            pendingOutgoing.remove(msg.id)
        }
        return true
    }

    private fun closeSocketOnly() {
        channelReconnectJob?.cancel()
        channelReconnectJob = null
        try {
            activeWebSocket?.close(1000, "Client exited channel")
        } catch (_: Exception) {
        }
        activeWebSocket = null
    }

    private fun closeDirectSocketOnly() {
        directReconnectJob?.cancel()
        directReconnectJob = null
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
            val isOwner = obj.optBoolean("isOwner", false)
            val id = if (isPublic || isOwner) {
                obj.optString("channelId").takeIf { it.isNotBlank() }
            } else {
                null
            }
            list.add(
                RemoteChannelSummary(
                    channelId = id,
                    name = obj.optString("name", "Channel"),
                    isPublic = isPublic,
                    isHidden = obj.optBoolean("isHidden", false),
                    isOwner = isOwner,
                    hasPrivateKey = obj.optBoolean("hasPrivateKey", false),
                    mediaCount = obj.optInt("mediaCount", 0),
                    activePeers = obj.optInt("activePeers", 0),
                    messageCount = obj.optInt("messageCount", 0),
                    lastMessagePreview = obj.optString("lastMessagePreview")
                        .takeIf { it.isNotBlank() },
                    lastTimestampMs = obj.optLong("lastTimestampMs", 0L)
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
        val attObj = obj.optJSONObject("attachment")
        val attachment = if (attObj != null && attObj.optString("id").isNotBlank()) {
            ClientChatAttachment(
                id = attObj.optString("id"),
                name = attObj.optString("name", "Shared file"),
                size = attObj.optLong("size", 0L),
                mimeType = attObj.optString("mimeType", "application/octet-stream"),
                streamUrl = attObj.optString("streamUrl"),
                downloadUrl = attObj.optString("downloadUrl")
            )
        } else {
            null
        }
        return ClientChatMessage(
            id = obj.optString("id", "msg_${System.nanoTime()}"),
            channelId = obj.optString("channelId", obj.optString("roomId", "")),
            sender = obj.optString("sender", "Peer"),
            text = obj.optString("text", ""),
            time = obj.optString("time", ""),
            timestampMs = obj.optLong("timestampMs", 0L),
            isSystemNotice = false,
            attachment = attachment,
            deliveryState = runCatching {
                MessageDeliveryState.valueOf(
                    obj.optString("deliveryState", MessageDeliveryState.DELIVERED.name)
                )
            }.getOrDefault(MessageDeliveryState.DELIVERED)
        )
    }

    private fun formatMessageTime(timestampMs: Long): String {
        return java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(timestampMs))
    }
}
