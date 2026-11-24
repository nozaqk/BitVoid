package com.bitvoid.network.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.bitvoid.network.client.BitVoidClientManager
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

enum class BitVoidCallState {
    IDLE,
    JOINING,
    ACTIVE,
    ENDED,
    ERROR
}

/**
 * Local-only WebRTC audio engine for voice calls and Live Class.
 * - Uses zero external STUN/TURN servers (host ICE candidates over RFC1918 local Wi-Fi only).
 * - Connects to the Host's `/ws/signaling` WebSocket for SDP & ICE negotiation.
 * - Supports microphone mute and speakerphone routing only.
 */
class BitVoidCallManager(context: Context) {

    companion object {
        private val webrtcInitialized = AtomicBoolean(false)

        private fun ensureFactoryInitialized(appContext: Context) {
            if (webrtcInitialized.compareAndSet(false, true)) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
            }
        }
    }

    private val appContext = context.applicationContext
    @Volatile private var allowedCloudHost: String? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val original = chain.request()
            val host = original.url.host
            val allowedRemote = !allowedCloudHost.isNullOrBlank() &&
                host.equals(allowedCloudHost, ignoreCase = true) &&
                original.url.isHttps
            if (!BitVoidClientManager.isPrivateLocalHost(host) && !allowedRemote) {
                throw IOException("Blocked unconfigured signaling host '$host'.")
            }
            val sanitized = original.newBuilder()
                .header("User-Agent", "BitVoid-RTC/1.0")
                .removeHeader("Referer")
                .removeHeader("Cookie")
                .build()
            chain.proceed(sanitized)
        }
        .build()

    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val pendingIceByPeer = ConcurrentHashMap<String, MutableList<IceCandidate>>()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var signalingSocket: WebSocket? = null

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private val _callState = MutableStateFlow(BitVoidCallState.IDLE)
    val callState: StateFlow<BitVoidCallState> = _callState.asStateFlow()

    private val _activeRoomId = MutableStateFlow<String?>(null)
    val activeRoomId: StateFlow<String?> = _activeRoomId.asStateFlow()

    private val _activeChannelName = MutableStateFlow("")
    val activeChannelName: StateFlow<String> = _activeChannelName.asStateFlow()

    private val _sessionMode = MutableStateFlow(SignalingSessionMode.AUDIO_CALL)
    val sessionMode: StateFlow<SignalingSessionMode> = _sessionMode.asStateFlow()

    private val _localRole = MutableStateFlow(ParticipantRole.SPEAKER)
    val localRole: StateFlow<ParticipantRole> = _localRole.asStateFlow()

    private val _myPeerId = MutableStateFlow("")
    val myPeerId: StateFlow<String> = _myPeerId.asStateFlow()

    private val _participants = MutableStateFlow<List<SignalingPeer>>(emptyList())
    val participants: StateFlow<List<SignalingPeer>> = _participants.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _isSpeakerphoneOn = MutableStateFlow(true)
    val isSpeakerphoneOn: StateFlow<Boolean> = _isSpeakerphoneOn.asStateFlow()

    private val _callError = MutableStateFlow<String?>(null)
    val callError: StateFlow<String?> = _callError.asStateFlow()

    private val _liveClassCode = MutableStateFlow<String?>(null)
    val liveClassCode: StateFlow<String?> = _liveClassCode.asStateFlow()

    fun configureCloudBaseUrl(baseUrl: String?) {
        allowedCloudHost = baseUrl
            ?.takeIf { it.startsWith("https://", ignoreCase = true) }
            ?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }
    }

    @Synchronized
    fun startOrJoinSession(
        gatewayBaseUrl: String,
        channelId: String,
        channelName: String,
        displayName: String,
        channelToken: String?,
        mode: SignalingSessionMode,
        role: ParticipantRole,
        presetClassCode: String? = null
    ) {
        tearDownMediaAndPeersOnly()
        closeSignalingSocketOnly()

        _callError.value = null
        _callState.value = BitVoidCallState.JOINING
        _activeRoomId.value = channelId
        _activeChannelName.value = channelName
        _sessionMode.value = mode
        _localRole.value = role
        _liveClassCode.value = presetClassCode?.takeIf { it.isNotBlank() }

        val peerId = BitVoidSignalingServer.generatePeerId(
            if (role == ParticipantRole.HOST_TEACHER) "teacher" else "peer"
        )
        _myPeerId.value = peerId

        try {
            ensureWebRtcPipeline()
        } catch (e: Throwable) {
            _callState.value = BitVoidCallState.ERROR
            _callError.value = "Unable to initialize WebRTC media engine: ${e.localizedMessage ?: e.javaClass.simpleName}"
            return
        }

        val micPermissionGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (micPermissionGranted) {
            createLocalAudioTrack(startMuted = role == ParticipantRole.STUDENT_VIEWER)
        } else {
            _isMicMuted.value = true
        }

        applySpeakerphoneRouting(enabled = true)
        connectSignalingSocket(
            gatewayBaseUrl = gatewayBaseUrl,
            channelId = channelId,
            channelToken = channelToken,
            peerId = peerId,
            displayName = displayName,
            mode = mode,
            role = role
        )
    }

    @Synchronized
    fun toggleMicMute() {
        val nextMuted = !_isMicMuted.value
        if (!nextMuted && localAudioTrack == null) {
            val micGranted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (micGranted) {
                createLocalAudioTrack(startMuted = false)
                attachAudioTrackToAllPeers()
            } else {
                _callError.value = "Microphone permission required to unmute."
                return
            }
        }
        _isMicMuted.value = nextMuted
        localAudioTrack?.setEnabled(!nextMuted)
        sendMediaStateUpdate()
    }

    fun toggleSpeakerphone() {
        applySpeakerphoneRouting(!_isSpeakerphoneOn.value)
    }

    @Synchronized
    fun leaveSession() {
        val roomId = _activeRoomId.value
        val ws = signalingSocket
        if (roomId != null && ws != null) {
            try {
                ws.send(JSONObject().apply {
                    put("type", "leave")
                    put("roomId", roomId)
                }.toString())
            } catch (_: Exception) {
            }
        }
        closeSignalingSocketOnly()
        tearDownMediaAndPeersOnly()
        restoreNormalAudioMode()
        _activeRoomId.value = null
        _participants.value = emptyList()
        _liveClassCode.value = null
        _callState.value = BitVoidCallState.IDLE
        _callError.value = null
    }

    @Synchronized
    fun endClassroomSession() {
        val roomId = _activeRoomId.value
        val ws = signalingSocket
        if (roomId != null && ws != null) {
            try {
                ws.send(JSONObject().apply {
                    put("type", "end_session")
                    put("roomId", roomId)
                }.toString())
            } catch (_: Exception) {
            }
        }
        leaveSession()
    }

    fun clearCallError() {
        _callError.value = null
    }

    fun release() {
        leaveSession()
        scope.cancel()
        okHttpClient.dispatcher.executorService.shutdown()
        try {
            peerConnectionFactory?.dispose()
        } catch (_: Exception) {
        }
        peerConnectionFactory = null
    }

    private fun ensureWebRtcPipeline() {
        ensureFactoryInitialized(appContext)
        if (peerConnectionFactory == null) {
            peerConnectionFactory = PeerConnectionFactory.builder()
                .createPeerConnectionFactory()
        }
    }

    private fun createLocalAudioTrack(startMuted: Boolean) {
        val factory = peerConnectionFactory ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        val source = factory.createAudioSource(constraints)
        audioSource = source
        val track = factory.createAudioTrack("BV_AUDIO_${System.nanoTime()}", source).apply {
            setEnabled(!startMuted)
        }
        localAudioTrack = track
        _isMicMuted.value = startMuted
    }

    private fun applySpeakerphoneRouting(enabled: Boolean) {
        _isSpeakerphoneOn.value = enabled
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val desiredType = if (enabled) {
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                } else {
                    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                val device = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == desiredType }
                if (device != null) {
                    audioManager.setCommunicationDevice(device)
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = enabled
            }
        } catch (_: Exception) {
        }
    }

    private fun restoreNormalAudioMode() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }
    }

    private fun connectSignalingSocket(
        gatewayBaseUrl: String,
        channelId: String,
        channelToken: String?,
        peerId: String,
        displayName: String,
        mode: SignalingSessionMode,
        role: ParticipantRole
    ) {
        val normalizedBase = gatewayBaseUrl.trim().trimEnd('/')
        val wsBase = when {
            normalizedBase.startsWith("https://") -> "wss://${normalizedBase.removePrefix("https://")}"
            normalizedBase.startsWith("http://") -> "ws://${normalizedBase.removePrefix("http://")}"
            else -> "ws://$normalizedBase"
        }
        val enc = { v: String -> URLEncoder.encode(v, StandardCharsets.UTF_8.name()).replace("+", "%20") }
        val url = buildString {
            append("$wsBase/ws/signaling")
            append("?roomId=${enc(channelId)}")
            append("&channelId=${enc(channelId)}")
            append("&peerId=${enc(peerId)}")
            append("&name=${enc(displayName)}")
            append("&mode=${enc(mode.name)}")
            append("&role=${enc(role.name)}")
            append("&isMuted=${_isMicMuted.value}")
            if (!channelToken.isNullOrBlank()) {
                append("&token=${enc(channelToken)}")
            }
        }

        val reqBuilder = Request.Builder().url(url)
        if (!channelToken.isNullOrBlank()) {
            reqBuilder.header("X-Channel-Token", channelToken)
        }

        signalingSocket = okHttpClient.newWebSocket(
            reqBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    _callState.value = BitVoidCallState.ACTIVE
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleSignalingMessage(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (_callState.value == BitVoidCallState.ACTIVE || _callState.value == BitVoidCallState.JOINING) {
                        _callState.value = BitVoidCallState.ENDED
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    _callState.value = BitVoidCallState.ERROR
                    _callError.value = "Signaling disconnected: ${t.localizedMessage ?: response?.message ?: "network error"}"
                }
            }
        )
    }

    private fun handleSignalingMessage(rawJson: String) {
        val obj = try {
            JSONObject(rawJson)
        } catch (_: Exception) {
            return
        }
        val myId = _myPeerId.value
        val type = obj.optString("type")
        when (type) {
            "welcome" -> {
                _callState.value = BitVoidCallState.ACTIVE
                _liveClassCode.value = obj.optString("classCode").takeIf { it.isNotBlank() }
                val peers = parsePeers(obj.optJSONArray("peers"))
                _participants.value = peers
                peers.filter { it.peerId != myId }.forEach { remote ->
                    ensurePeerConnection(remote.peerId)
                    if (shouldInitiateOffer(remote)) {
                        createAndSendOffer(remote.peerId)
                    }
                }
            }

            "peer_joined" -> {
                val code = obj.optString("classCode").takeIf { it.isNotBlank() }
                if (code != null) _liveClassCode.value = code
                val peers = parsePeers(obj.optJSONArray("peers"))
                _participants.value = peers
                val peerObj = obj.optJSONObject("peer") ?: return
                val remote = parsePeer(peerObj)
                if (remote.peerId != myId) {
                    ensurePeerConnection(remote.peerId)
                    if (shouldInitiateOffer(remote)) {
                        createAndSendOffer(remote.peerId)
                    }
                }
            }

            "peers_update" -> {
                val code = obj.optString("classCode").takeIf { it.isNotBlank() }
                if (code != null) _liveClassCode.value = code
                _participants.value = parsePeers(obj.optJSONArray("peers"))
            }

            "peer_left" -> {
                _participants.value = parsePeers(obj.optJSONArray("peers"))
                val leftPeerId = obj.optString("peerId")
                closePeerConnection(leftPeerId)
            }

            "session_ended" -> {
                tearDownMediaAndPeersOnly()
                closeSignalingSocketOnly()
                restoreNormalAudioMode()
                _activeRoomId.value = null
                _participants.value = emptyList()
                _liveClassCode.value = null
                _callState.value = BitVoidCallState.ENDED
            }

            "offer" -> {
                val fromPeerId = obj.optString("fromPeerId")
                val sdp = obj.optString("sdp")
                if (fromPeerId.isNotBlank() && sdp.isNotBlank()) {
                    handleRemoteOffer(fromPeerId, sdp)
                }
            }

            "answer" -> {
                val fromPeerId = obj.optString("fromPeerId")
                val sdp = obj.optString("sdp")
                if (fromPeerId.isNotBlank() && sdp.isNotBlank()) {
                    handleRemoteAnswer(fromPeerId, sdp)
                }
            }

            "ice" -> {
                val fromPeerId = obj.optString("fromPeerId")
                val candObj = obj.optJSONObject("candidate") ?: return
                val candidate = IceCandidate(
                    candObj.optString("sdpMid"),
                    candObj.optInt("sdpMLineIndex", 0),
                    candObj.optString("candidate")
                )
                handleRemoteIceCandidate(fromPeerId, candidate)
            }
        }
    }

    private fun shouldInitiateOffer(remote: SignalingPeer): Boolean {
        val myRole = _localRole.value
        val myId = _myPeerId.value
        return when {
            myRole == ParticipantRole.HOST_TEACHER && remote.role != ParticipantRole.HOST_TEACHER -> true
            myRole != ParticipantRole.HOST_TEACHER && remote.role == ParticipantRole.HOST_TEACHER -> false
            else -> myId < remote.peerId
        }
    }

    @Synchronized
    private fun ensurePeerConnection(remotePeerId: String): PeerConnection? {
        peerConnections[remotePeerId]?.let { return it }
        val factory = peerConnectionFactory ?: return null

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                val roomId = _activeRoomId.value ?: return
                signalingSocket?.send(
                    JSONObject().apply {
                        put("type", "ice")
                        put("roomId", roomId)
                        put("targetPeerId", remotePeerId)
                        put("candidate", JSONObject().apply {
                            put("sdpMid", candidate.sdpMid ?: "0")
                            put("sdpMLineIndex", candidate.sdpMLineIndex)
                            put("candidate", candidate.sdp)
                        })
                    }.toString()
                )
            }

            override fun onAddStream(stream: MediaStream?) {}
            override fun onTrack(transceiver: RtpTransceiver?) {}
        }

        val pc = factory.createPeerConnection(rtcConfig, observer) ?: return null

        val audioTransceiver = pc.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(
                RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                listOf("bitvoid_stream")
            )
        )
        localAudioTrack?.let { audioTransceiver.sender.setTrack(it, false) }

        peerConnections[remotePeerId] = pc
        return pc
    }

    private fun createAndSendOffer(remotePeerId: String) {
        val pc = ensurePeerConnection(remotePeerId) ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }
        pc.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(desc: SessionDescription?) {
                if (desc == null) return
                pc.setLocalDescription(object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        val roomId = _activeRoomId.value ?: return
                        signalingSocket?.send(
                            JSONObject().apply {
                                put("type", "offer")
                                put("roomId", roomId)
                                put("targetPeerId", remotePeerId)
                                put("sdp", desc.description)
                            }.toString()
                        )
                    }
                }, desc)
            }
        }, constraints)
    }

    private fun handleRemoteOffer(remotePeerId: String, sdp: String) {
        val pc = ensurePeerConnection(remotePeerId) ?: return
        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, sdp)
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                drainPendingIce(remotePeerId, pc)
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                }
                pc.createAnswer(object : SdpObserverAdapter() {
                    override fun onCreateSuccess(answerDesc: SessionDescription?) {
                        if (answerDesc == null) return
                        pc.setLocalDescription(object : SdpObserverAdapter() {
                            override fun onSetSuccess() {
                                val roomId = _activeRoomId.value ?: return
                                signalingSocket?.send(
                                    JSONObject().apply {
                                        put("type", "answer")
                                        put("roomId", roomId)
                                        put("targetPeerId", remotePeerId)
                                        put("sdp", answerDesc.description)
                                    }.toString()
                                )
                            }
                        }, answerDesc)
                    }
                }, constraints)
            }
        }, remoteDesc)
    }

    private fun handleRemoteAnswer(remotePeerId: String, sdp: String) {
        val pc = peerConnections[remotePeerId] ?: return
        val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        pc.setRemoteDescription(object : SdpObserverAdapter() {
            override fun onSetSuccess() {
                drainPendingIce(remotePeerId, pc)
            }
        }, remoteDesc)
    }

    private fun handleRemoteIceCandidate(remotePeerId: String, candidate: IceCandidate) {
        val pc = peerConnections[remotePeerId]
        if (pc != null && pc.remoteDescription != null) {
            pc.addIceCandidate(candidate)
        } else {
            pendingIceByPeer.computeIfAbsent(remotePeerId) { mutableListOf() }.add(candidate)
        }
    }

    private fun drainPendingIce(remotePeerId: String, pc: PeerConnection) {
        val queued = pendingIceByPeer.remove(remotePeerId) ?: return
        queued.forEach { pc.addIceCandidate(it) }
    }

    private fun attachAudioTrackToAllPeers() {
        val track = localAudioTrack ?: return
        peerConnections.values.forEach { pc ->
            pc.senders.firstOrNull { it.track()?.kind() == "audio" }?.setTrack(track, false)
        }
    }

    private fun sendMediaStateUpdate() {
        val roomId = _activeRoomId.value ?: return
        val ws = signalingSocket ?: return
        try {
            ws.send(
                JSONObject().apply {
                    put("type", "media_state")
                    put("roomId", roomId)
                    put("isMuted", _isMicMuted.value)
                }.toString()
            )
        } catch (_: Exception) {
        }
    }

    private fun closePeerConnection(peerId: String) {
        pendingIceByPeer.remove(peerId)
        peerConnections.remove(peerId)?.let { pc ->
            try {
                pc.close()
                pc.dispose()
            } catch (_: Exception) {
            }
        }
    }

    private fun closeSignalingSocketOnly() {
        try {
            signalingSocket?.close(1000, "Session ended")
        } catch (_: Exception) {
        }
        signalingSocket = null
    }

    private fun tearDownMediaAndPeersOnly() {
        peerConnections.keys.toList().forEach { closePeerConnection(it) }

        try {
            localAudioTrack?.dispose()
        } catch (_: Exception) {
        }
        localAudioTrack = null
        try {
            audioSource?.dispose()
        } catch (_: Exception) {
        }
        audioSource = null
        _isMicMuted.value = false
    }

    private fun parsePeers(arr: JSONArray?): List<SignalingPeer> {
        if (arr == null) return emptyList()
        val list = ArrayList<SignalingPeer>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(parsePeer(obj))
        }
        return list
    }

    private fun parsePeer(obj: JSONObject): SignalingPeer {
        val roleStr = obj.optString("role", ParticipantRole.SPEAKER.name)
        val role = runCatching { ParticipantRole.valueOf(roleStr) }
            .getOrDefault(ParticipantRole.SPEAKER)
        return SignalingPeer(
            peerId = obj.optString("peerId"),
            displayName = obj.optString("displayName", "Peer"),
            role = role,
            roomId = obj.optString("roomId"),
            isMuted = obj.optBoolean("isMuted", false),
            joinedAtMs = obj.optLong("joinedAtMs", 0L)
        )
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }
}
