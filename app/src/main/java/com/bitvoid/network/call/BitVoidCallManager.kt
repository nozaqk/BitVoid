package com.bitvoid.network.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjection
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
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

enum class BitVoidCallState {
    IDLE,
    JOINING,
    ACTIVE,
    ENDED,
    ERROR
}

/**
 * Local-only WebRTC Voice Call & Live Class Presentation engine.
 * - Uses zero external STUN/TURN servers (host ICE candidates over RFC1918 local Wi-Fi only).
 * - Connects to the Host's `/ws/signaling` WebSocket for SDP & ICE negotiation.
 * - Supports microphone mute, speakerphone routing, front/back camera switching,
 *   and teacher screen broadcasting via Android's [MediaProjection] API.
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
            if (!BitVoidClientManager.isPrivateLocalHost(host)) {
                throw IOException("Blocked non-local signaling host '$host'.")
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
    private val videoSendersByPeer = ConcurrentHashMap<String, org.webrtc.RtpSender>()

    private var eglBase: EglBase? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var signalingSocket: WebSocket? = null

    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null

    private var cameraTextureHelper: SurfaceTextureHelper? = null
    private var cameraCapturer: VideoCapturer? = null
    private var cameraVideoSource: VideoSource? = null
    private var cameraVideoTrack: VideoTrack? = null

    private var screenTextureHelper: SurfaceTextureHelper? = null
    private var screenCapturer: VideoCapturer? = null
    private var screenVideoSource: VideoSource? = null
    private var screenVideoTrack: VideoTrack? = null

    private val _callState = MutableStateFlow(BitVoidCallState.IDLE)
    val callState: StateFlow<BitVoidCallState> = _callState.asStateFlow()

    private val _activeRoomId = MutableStateFlow<String?>(null)
    val activeRoomId: StateFlow<String?> = _activeRoomId.asStateFlow()

    private val _activeChannelName = MutableStateFlow("")
    val activeChannelName: StateFlow<String> = _activeChannelName.asStateFlow()

    private val _sessionMode = MutableStateFlow(SignalingSessionMode.VOICE_VIDEO_CALL)
    val sessionMode: StateFlow<SignalingSessionMode> = _sessionMode.asStateFlow()

    private val _localRole = MutableStateFlow(ParticipantRole.SPEAKER)
    val localRole: StateFlow<ParticipantRole> = _localRole.asStateFlow()

    private val _myPeerId = MutableStateFlow("")
    val myPeerId: StateFlow<String> = _myPeerId.asStateFlow()

    private val _participants = MutableStateFlow<List<SignalingPeer>>(emptyList())
    val participants: StateFlow<List<SignalingPeer>> = _participants.asStateFlow()

    private val _isMicMuted = MutableStateFlow(false)
    val isMicMuted: StateFlow<Boolean> = _isMicMuted.asStateFlow()

    private val _isCameraEnabled = MutableStateFlow(false)
    val isCameraEnabled: StateFlow<Boolean> = _isCameraEnabled.asStateFlow()

    private val _isUsingFrontCamera = MutableStateFlow(true)
    val isUsingFrontCamera: StateFlow<Boolean> = _isUsingFrontCamera.asStateFlow()

    private val _isSpeakerphoneOn = MutableStateFlow(true)
    val isSpeakerphoneOn: StateFlow<Boolean> = _isSpeakerphoneOn.asStateFlow()

    private val _isScreenSharing = MutableStateFlow(false)
    val isScreenSharing: StateFlow<Boolean> = _isScreenSharing.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTracks = MutableStateFlow<Map<String, VideoTrack>>(emptyMap())
    val remoteVideoTracks: StateFlow<Map<String, VideoTrack>> = _remoteVideoTracks.asStateFlow()

    private val _callError = MutableStateFlow<String?>(null)
    val callError: StateFlow<String?> = _callError.asStateFlow()

    private val _eglBaseContext = MutableStateFlow<EglBase.Context?>(null)
    val eglBaseContext: StateFlow<EglBase.Context?> = _eglBaseContext.asStateFlow()

    private val _liveClassCode = MutableStateFlow<String?>(null)
    val liveClassCode: StateFlow<String?> = _liveClassCode.asStateFlow()

    private val _roomMaterials = MutableStateFlow<List<LiveClassMaterial>>(emptyList())
    val roomMaterials: StateFlow<List<LiveClassMaterial>> = _roomMaterials.asStateFlow()

    private val _activeMaterialId = MutableStateFlow<String?>(null)
    val activeMaterialId: StateFlow<String?> = _activeMaterialId.asStateFlow()

    private val _activePageIndex = MutableStateFlow(0)
    val activePageIndex: StateFlow<Int> = _activePageIndex.asStateFlow()

    @Synchronized
    fun startOrJoinSession(
        gatewayBaseUrl: String,
        channelId: String,
        channelName: String,
        displayName: String,
        channelToken: String?,
        mode: SignalingSessionMode,
        role: ParticipantRole,
        startWithVideo: Boolean,
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
        _roomMaterials.value = emptyList()
        _activeMaterialId.value = null
        _activePageIndex.value = 0

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

        val camPermissionGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (micPermissionGranted) {
            createLocalAudioTrack(startMuted = role == ParticipantRole.STUDENT_VIEWER)
        } else {
            _isMicMuted.value = true
        }

        val shouldEnableCamera = mode == SignalingSessionMode.LIVE_CLASSROOM &&
            startWithVideo && camPermissionGranted && role != ParticipantRole.STUDENT_VIEWER
        if (shouldEnableCamera) {
            createCameraVideoTrack(frontCamera = true)
        } else {
            _isCameraEnabled.value = false
            _localVideoTrack.value = null
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

    @Synchronized
    fun toggleCamera() {
        if (_sessionMode.value != SignalingSessionMode.LIVE_CLASSROOM) {
            _callError.value = "Camera video is available only in Live Class. Calls are voice-only."
            return
        }
        if (_isScreenSharing.value) {
            stopScreenShare()
        }
        val nextEnabled = !_isCameraEnabled.value
        if (nextEnabled) {
            val camGranted = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (!camGranted) {
                _callError.value = "Camera permission is required to broadcast video."
                return
            }
            if (cameraVideoTrack == null) {
                createCameraVideoTrack(frontCamera = _isUsingFrontCamera.value)
            } else {
                cameraVideoTrack?.setEnabled(true)
                _localVideoTrack.value = cameraVideoTrack
                _isCameraEnabled.value = true
            }
            replaceVideoTrackOnAllPeers(cameraVideoTrack)
        } else {
            cameraVideoTrack?.setEnabled(false)
            _isCameraEnabled.value = false
            if (!_isScreenSharing.value) {
                _localVideoTrack.value = null
            }
        }
        sendMediaStateUpdate()
    }

    @Synchronized
    fun switchCamera() {
        if (_sessionMode.value != SignalingSessionMode.LIVE_CLASSROOM) return
        val camCap = cameraCapturer as? CameraVideoCapturer ?: return
        camCap.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                _isUsingFrontCamera.value = isFrontCamera
            }

            override fun onCameraSwitchError(errorDescription: String?) {
                _callError.value = errorDescription ?: "Unable to switch camera"
            }
        })
    }

    fun toggleSpeakerphone() {
        applySpeakerphoneRouting(!_isSpeakerphoneOn.value)
    }

    @Synchronized
    fun startScreenShare(resultCode: Int, data: Intent) {
        if (_activeRoomId.value == null) return
        if (_sessionMode.value != SignalingSessionMode.LIVE_CLASSROOM) {
            _callError.value = "Screen sharing is available only in Live Class. Calls are voice-only."
            BitVoidScreenProjectionService.stopProjectionService(appContext)
            return
        }
        val factory = peerConnectionFactory ?: return
        val eglCtx = eglBase?.eglBaseContext ?: return

        stopScreenShareInternal(restoreCamera = false)

        try {
            val helper = SurfaceTextureHelper.create("BitVoidScreenCapture", eglCtx)
            screenTextureHelper = helper
            val source = factory.createVideoSource(true)
            screenVideoSource = source

            val capturer = ScreenCapturerAndroid(
                data,
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopScreenShare()
                    }
                }
            )
            screenCapturer = capturer
            capturer.initialize(helper, appContext, source.capturerObserver)

            val metrics = appContext.resources.displayMetrics
            val rawWidth = metrics.widthPixels.coerceIn(480, 1280)
            val rawHeight = metrics.heightPixels.coerceIn(480, 1280)
            val width = rawWidth - (rawWidth % 2)
            val height = rawHeight - (rawHeight % 2)
            capturer.startCapture(width, height, 15)

            val track = factory.createVideoTrack("BV_SCREEN_${System.nanoTime()}", source).apply {
                setEnabled(true)
            }
            screenVideoTrack = track
            _isScreenSharing.value = true
            _localVideoTrack.value = track
            replaceVideoTrackOnAllPeers(track)
            sendMediaStateUpdate()
        } catch (e: Exception) {
            _callError.value = "Screen share failed: ${e.localizedMessage ?: "Unsupported device state"}"
            stopScreenShareInternal(restoreCamera = true)
            BitVoidScreenProjectionService.stopProjectionService(appContext)
        }
    }

    @Synchronized
    fun stopScreenShare() {
        stopScreenShareInternal(restoreCamera = true)
        BitVoidScreenProjectionService.stopProjectionService(appContext)
        sendMediaStateUpdate()
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
        BitVoidScreenProjectionService.stopProjectionService(appContext)
        closeSignalingSocketOnly()
        tearDownMediaAndPeersOnly()
        restoreNormalAudioMode()
        _activeRoomId.value = null
        _participants.value = emptyList()
        _liveClassCode.value = null
        _roomMaterials.value = emptyList()
        _activeMaterialId.value = null
        _activePageIndex.value = 0
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

    fun selectActiveMaterial(materialId: String, pageIndex: Int = 0) {
        val safePage = pageIndex.coerceAtLeast(0)
        _activeMaterialId.value = materialId
        _activePageIndex.value = safePage
        val roomId = _activeRoomId.value ?: return
        signalingSocket?.send(
            JSONObject().apply {
                put("type", "active_material")
                put("roomId", roomId)
                put("materialId", materialId)
                put("pageIndex", safePage)
            }.toString()
        )
    }

    fun setActiveSlidePage(pageIndex: Int) {
        val safePage = pageIndex.coerceAtLeast(0)
        _activePageIndex.value = safePage
        val roomId = _activeRoomId.value ?: return
        signalingSocket?.send(
            JSONObject().apply {
                put("type", "slide_nav")
                put("roomId", roomId)
                put("materialId", _activeMaterialId.value.orEmpty())
                put("pageIndex", safePage)
            }.toString()
        )
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
        try {
            eglBase?.release()
        } catch (_: Exception) {
        }
        eglBase = null
        _eglBaseContext.value = null
    }

    private fun ensureWebRtcPipeline() {
        ensureFactoryInitialized(appContext)
        if (eglBase == null) {
            eglBase = EglBase.create()
            _eglBaseContext.value = eglBase?.eglBaseContext
        }
        if (peerConnectionFactory == null) {
            val eglContext = eglBase?.eglBaseContext
            val encoderFactory = DefaultVideoEncoderFactory(eglContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(eglContext)
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
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

    private fun createCameraVideoTrack(frontCamera: Boolean) {
        val factory = peerConnectionFactory ?: return
        val eglCtx = eglBase?.eglBaseContext ?: return

        val capturer = buildCameraCapturer(frontCamera)
        if (capturer == null) {
            _isCameraEnabled.value = false
            _callError.value = "No camera hardware detected — audio-only mode active."
            return
        }

        try {
            val helper = SurfaceTextureHelper.create("BitVoidCamCapture", eglCtx)
            cameraTextureHelper = helper
            val source = factory.createVideoSource(false)
            cameraVideoSource = source
            cameraCapturer = capturer
            capturer.initialize(helper, appContext, source.capturerObserver)
            capturer.startCapture(960, 540, 24)

            val track = factory.createVideoTrack("BV_VIDEO_${System.nanoTime()}", source).apply {
                setEnabled(true)
            }
            cameraVideoTrack = track
            _isUsingFrontCamera.value = frontCamera
            _isCameraEnabled.value = true
            if (!_isScreenSharing.value) {
                _localVideoTrack.value = track
            }
        } catch (e: Exception) {
            _isCameraEnabled.value = false
            _callError.value = "Camera busy or unavailable (${e.localizedMessage ?: "error"})."
        }
    }

    private fun buildCameraCapturer(preferFront: Boolean): CameraVideoCapturer? {
        val enumerator = if (Camera2Enumerator.isSupported(appContext)) {
            Camera2Enumerator(appContext)
        } else {
            Camera1Enumerator(false)
        }
        val deviceNames = enumerator.deviceNames
        val targetName = deviceNames.firstOrNull { name ->
            if (preferFront) enumerator.isFrontFacing(name) else enumerator.isBackFacing(name)
        } ?: deviceNames.firstOrNull()
        return targetName?.let { enumerator.createCapturer(it, null) }
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
            append("&isVideoOn=${_isCameraEnabled.value}")
            append("&isScreenSharing=${_isScreenSharing.value}")
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
                _roomMaterials.value = BitVoidSignalingServer.parseMaterialsJsonArray(obj.optJSONArray("materials"))
                _activeMaterialId.value = obj.optString("activeMaterialId").takeIf { it.isNotBlank() }
                _activePageIndex.value = obj.optInt("activePageIndex", 0)
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

            "material_shared", "material_state" -> {
                _roomMaterials.value = BitVoidSignalingServer.parseMaterialsJsonArray(obj.optJSONArray("materials"))
                val matId = obj.optString("activeMaterialId").takeIf { it.isNotBlank() }
                if (matId != null) {
                    _activeMaterialId.value = matId
                }
                _activePageIndex.value = obj.optInt("activePageIndex", 0)
            }

            "peer_left" -> {
                _participants.value = parsePeers(obj.optJSONArray("peers"))
                val leftPeerId = obj.optString("peerId")
                closePeerConnection(leftPeerId)
            }

            "session_ended" -> {
                BitVoidScreenProjectionService.stopProjectionService(appContext)
                tearDownMediaAndPeersOnly()
                closeSignalingSocketOnly()
                restoreNormalAudioMode()
                _activeRoomId.value = null
                _participants.value = emptyList()
                _liveClassCode.value = null
                _roomMaterials.value = emptyList()
                _activeMaterialId.value = null
                _activePageIndex.value = 0
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

            override fun onAddStream(stream: MediaStream?) {
                if (_sessionMode.value != SignalingSessionMode.LIVE_CLASSROOM) {
                    stream?.videoTracks?.forEach { it.setEnabled(false) }
                    return
                }
                val track = stream?.videoTracks?.firstOrNull()
                if (track != null) {
                    _remoteVideoTracks.value = _remoteVideoTracks.value + (remotePeerId to track)
                }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (_sessionMode.value != SignalingSessionMode.LIVE_CLASSROOM) {
                    if (track is VideoTrack) track.setEnabled(false)
                    return
                }
                if (track is VideoTrack) {
                    track.setEnabled(true)
                    _remoteVideoTracks.value = _remoteVideoTracks.value + (remotePeerId to track)
                }
            }
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

        if (_sessionMode.value == SignalingSessionMode.LIVE_CLASSROOM) {
            val videoTransceiver = pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(
                    RtpTransceiver.RtpTransceiverDirection.SEND_RECV,
                    listOf("bitvoid_stream")
                )
            )
            val activeOutVideo = activeOutgoingVideoTrack()
            if (activeOutVideo != null) {
                videoTransceiver.sender.setTrack(activeOutVideo, false)
            }
            videoSendersByPeer[remotePeerId] = videoTransceiver.sender
        }

        peerConnections[remotePeerId] = pc
        return pc
    }

    private fun activeOutgoingVideoTrack(): VideoTrack? {
        return when {
            _isScreenSharing.value && screenVideoTrack != null -> screenVideoTrack
            _isCameraEnabled.value && cameraVideoTrack != null -> cameraVideoTrack
            else -> null
        }
    }

    private fun createAndSendOffer(remotePeerId: String) {
        val pc = ensurePeerConnection(remotePeerId) ?: return
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(
                MediaConstraints.KeyValuePair(
                    "OfferToReceiveVideo",
                    (_sessionMode.value == SignalingSessionMode.LIVE_CLASSROOM).toString()
                )
            )
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
                val allowsVideo = _sessionMode.value == SignalingSessionMode.LIVE_CLASSROOM
                if (!allowsVideo) {
                    pc.transceivers
                        .filter { it.receiver.track()?.kind() == "video" }
                        .forEach { transceiver ->
                            transceiver.receiver.track()?.setEnabled(false)
                            transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.INACTIVE)
                        }
                }
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", allowsVideo.toString()))
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

    private fun replaceVideoTrackOnAllPeers(track: VideoTrack?) {
        videoSendersByPeer.values.forEach { sender ->
            try {
                sender.setTrack(track, false)
            } catch (_: Exception) {
            }
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
                    put("isVideoOn", _isCameraEnabled.value)
                    put("isScreenSharing", _isScreenSharing.value)
                    put("role", _localRole.value.name)
                }.toString()
            )
        } catch (_: Exception) {
        }
    }

    private fun stopScreenShareInternal(restoreCamera: Boolean) {
        try {
            screenCapturer?.stopCapture()
        } catch (_: Exception) {
        }
        try {
            screenCapturer?.dispose()
        } catch (_: Exception) {
        }
        screenCapturer = null
        try {
            screenVideoTrack?.dispose()
        } catch (_: Exception) {
        }
        screenVideoTrack = null
        try {
            screenVideoSource?.dispose()
        } catch (_: Exception) {
        }
        screenVideoSource = null
        try {
            screenTextureHelper?.dispose()
        } catch (_: Exception) {
        }
        screenTextureHelper = null

        _isScreenSharing.value = false
        if (restoreCamera) {
            val cam = if (_isCameraEnabled.value) cameraVideoTrack else null
            _localVideoTrack.value = cam
            replaceVideoTrackOnAllPeers(cam)
        }
    }

    private fun closePeerConnection(peerId: String) {
        videoSendersByPeer.remove(peerId)
        pendingIceByPeer.remove(peerId)
        peerConnections.remove(peerId)?.let { pc ->
            try {
                pc.close()
                pc.dispose()
            } catch (_: Exception) {
            }
        }
        _remoteVideoTracks.value = _remoteVideoTracks.value - peerId
    }

    private fun closeSignalingSocketOnly() {
        try {
            signalingSocket?.close(1000, "Session ended")
        } catch (_: Exception) {
        }
        signalingSocket = null
    }

    private fun tearDownMediaAndPeersOnly() {
        stopScreenShareInternal(restoreCamera = false)
        peerConnections.keys.toList().forEach { closePeerConnection(it) }
        _remoteVideoTracks.value = emptyMap()

        try {
            cameraCapturer?.stopCapture()
        } catch (_: Exception) {
        }
        try {
            cameraCapturer?.dispose()
        } catch (_: Exception) {
        }
        cameraCapturer = null
        try {
            cameraVideoTrack?.dispose()
        } catch (_: Exception) {
        }
        cameraVideoTrack = null
        try {
            cameraVideoSource?.dispose()
        } catch (_: Exception) {
        }
        cameraVideoSource = null
        try {
            cameraTextureHelper?.dispose()
        } catch (_: Exception) {
        }
        cameraTextureHelper = null

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

        _localVideoTrack.value = null
        _isCameraEnabled.value = false
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
            isVideoOn = obj.optBoolean("isVideoOn", false),
            isScreenSharing = obj.optBoolean("isScreenSharing", false),
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
