package com.bitvoid.network.local

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitvoid.network.backend.BitVoidBackendConnectionManager
import com.bitvoid.network.backend.BitVoidBackendPhase
import com.bitvoid.network.backend.BitVoidBackendState
import com.bitvoid.network.backend.BitVoidBackendType
import com.bitvoid.network.backend.BitVoidLocalDiscovery
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.WebSocket

data class LocalPairingUiState(
    val requestId: String? = null,
    val pairingCode: String? = null,
    val status: String = "not_started",
    val isWorking: Boolean = false,
    val error: String? = null
)

class BitVoidLocalViewModel(application: Application) : AndroidViewModel(application) {
    private val credentials = BitVoidLocalCredentials(application)
    private val store = BitVoidLocalStore(application)
    private val api = BitVoidLocalApi()
    private val discovery = BitVoidLocalDiscovery(application)
    private val backend = BitVoidBackendConnectionManager(application)

    val backendState: StateFlow<BitVoidBackendState> = backend.state
    val networkState = discovery.state

    private val _configuredServerUrl = MutableStateFlow(backend.config.value.localServerUrl)
    val configuredServerUrl: StateFlow<String> = _configuredServerUrl.asStateFlow()

    private val _pairing = MutableStateFlow(LocalPairingUiState())
    val pairing: StateFlow<LocalPairingUiState> = _pairing.asStateFlow()

    private val _profile = MutableStateFlow(
        credentials.deviceToken()?.let {
            credentials.username()?.let { username -> LocalProfile(username, username, username) }
        }
    )
    val profile: StateFlow<LocalProfile?> = _profile.asStateFlow()

    private val _conversations = MutableStateFlow(store.conversations())
    val conversations: StateFlow<List<LocalConversation>> = _conversations.asStateFlow()

    private val _activeConversation = MutableStateFlow<LocalConversation?>(null)
    val activeConversation: StateFlow<LocalConversation?> = _activeConversation.asStateFlow()

    private val _messages = MutableStateFlow<List<LocalMessage>>(emptyList())
    val messages: StateFlow<List<LocalMessage>> = _messages.asStateFlow()

    private val _eventsConnected = MutableStateFlow(false)
    val eventsConnected: StateFlow<Boolean> = _eventsConnected.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var eventsSocket: WebSocket? = null
    private var reconnectJob: Job? = null
    private var serverSessionUrl: String? = null

    init {
        backend.start(
            scope = viewModelScope,
            discoverCampusBackend = discovery::discover,
            onBackendSelected = { type, baseUrl ->
                if (type == BitVoidBackendType.CAMPUS && baseUrl != null) {
                    onServerConnected(baseUrl)
                } else {
                    _eventsConnected.value = false
                    eventsSocket?.cancel()
                    eventsSocket = null
                }
            }
        )
    }

    fun setManualServer(rawUrl: String): Boolean {
        val accepted = backend.setLocalServerUrl(rawUrl)
        if (accepted) {
            _configuredServerUrl.value = backend.config.value.localServerUrl
            _error.value = null
        } else {
            _error.value = "Enter a private LAN address such as 192.168.4.20:8787."
        }
        return accepted
    }

    fun retryConnection() {
        _error.value = null
        backend.requestRefresh()
    }

    fun requestPairing(username: String) {
        val cleanUsername = normalizeUsername(username) ?: return
        val baseUrl = backendState.value.baseUrl ?: run {
            _error.value = "Connect to the local PC server before pairing."
            return
        }
        viewModelScope.launch {
            _pairing.value = _pairing.value.copy(isWorking = true, error = null)
            runCatching {
                api.configure(baseUrl, null)
                api.requestPairing(credentials, _pairing.value.requestId)
            }.onSuccess { result ->
                _pairing.value = LocalPairingUiState(
                    requestId = result.requestId,
                    pairingCode = result.pairingCode,
                    status = result.status,
                    isWorking = false
                )
                result.deviceToken?.let { token ->
                    finishPairing(baseUrl, token, cleanUsername)
                }
            }.onFailure { failure ->
                _pairing.value = _pairing.value.copy(
                    isWorking = false,
                    error = failure.message ?: "Pairing request failed."
                )
            }
        }
    }

    fun checkPairingApproval(username: String) = requestPairing(username)

    fun refresh() {
        backend.requestRefresh()
        if (backendState.value.isConnected && _profile.value != null) {
            viewModelScope.launch { refreshServerData() }
        }
    }

    fun openConversation(conversation: LocalConversation) {
        _activeConversation.value = conversation
        _messages.value = store.messages(conversation.id)
        if (backendState.value.isConnected) {
            viewModelScope.launch { refreshMessages(conversation.id) }
        }
    }

    fun closeConversation() {
        _activeConversation.value = null
        _messages.value = emptyList()
    }

    fun createConversation(participantId: String) {
        val target = participantId.trim()
        if (target.isBlank()) {
            _error.value = "Enter a local user ID."
            return
        }
        if (!backendState.value.isConnected) {
            _error.value = "The PC server must be online to create a conversation."
            return
        }
        viewModelScope.launch {
            _isRefreshing.value = true
            runCatching { api.createConversation(target) }
                .onSuccess { conversation ->
                    store.saveConversations(listOf(conversation))
                    _conversations.value = store.conversations()
                    openConversation(conversation)
                    _error.value = null
                }
                .onFailure { _error.value = it.message ?: "Unable to create conversation." }
            _isRefreshing.value = false
        }
    }

    fun sendMessage(content: String) {
        val conversation = _activeConversation.value ?: return
        val text = content.trim()
        if (text.isBlank()) return
        val localId = "android-${UUID.randomUUID()}"
        val pending = LocalMessage(
            id = localId,
            conversationId = conversation.id,
            sender = _profile.value?.username ?: "me",
            content = text,
            createdAtMs = System.currentTimeMillis(),
            deliveryState = LocalDeliveryState.PENDING
        )
        store.saveMessage(pending)
        publishMessages(conversation.id)
        if (backendState.value.isConnected) {
            viewModelScope.launch { deliverPending(pending) }
        }
    }

    fun logout() {
        eventsSocket?.cancel()
        eventsSocket = null
        credentials.clearAuthorization()
        store.clear()
        _profile.value = null
        _conversations.value = emptyList()
        _activeConversation.value = null
        _messages.value = emptyList()
        _eventsConnected.value = false
        _pairing.value = LocalPairingUiState()
    }

    fun clearError() {
        _error.value = null
    }

    private suspend fun onServerConnected(baseUrl: String) {
        val normalizedUrl = baseUrl.trimEnd('/')
        if (serverSessionUrl != null && serverSessionUrl != normalizedUrl) {
            eventsSocket?.cancel()
            eventsSocket = null
            _eventsConnected.value = false
        }
        serverSessionUrl = normalizedUrl
        val token = credentials.deviceToken()
            ?.takeIf { credentials.authorizedServerUrl() == normalizedUrl }
        api.configure(normalizedUrl, token)
        if (token == null) {
            _profile.value = null
            _pairing.value = _pairing.value.copy(status = "not_paired")
            return
        }
        runCatching { loadOrRegisterProfile() }
            .onSuccess { profile ->
                credentials.saveUsername(profile.username)
                _profile.value = profile
                _pairing.value = LocalPairingUiState(status = "approved")
                refreshServerData()
                connectEvents()
            }
            .onFailure { failure ->
                if (failure is LocalApiException && failure.statusCode == 401) {
                    credentials.clearAuthorization()
                    _profile.value = null
                    _pairing.value = LocalPairingUiState(
                        status = "revoked",
                        error = "This device authorization was revoked. Pair again."
                    )
                } else {
                    _error.value = failure.message
                }
            }
    }

    private suspend fun finishPairing(baseUrl: String, token: String, username: String) {
        credentials.saveDeviceToken(token, baseUrl)
        credentials.saveUsername(username)
        api.configure(baseUrl, token)
        runCatching { loadOrRegisterProfile() }
            .onSuccess { profile ->
                credentials.saveUsername(profile.username)
                _profile.value = profile
                _pairing.value = LocalPairingUiState(status = "approved")
                refreshServerData()
                connectEvents()
            }
            .onFailure { failure ->
                _pairing.value = _pairing.value.copy(
                    isWorking = false,
                    status = "approved",
                    error = failure.message ?: "Paired, but local profile registration failed."
                )
            }
    }

    private suspend fun loadOrRegisterProfile(): LocalProfile {
        return try {
            api.loadProfile()
        } catch (failure: LocalApiException) {
            val username = credentials.username()
            if (failure.statusCode == 404 && !username.isNullOrBlank()) {
                api.registerUser(username, credentials.deviceId())
            } else {
                throw failure
            }
        }
    }

    private suspend fun refreshServerData() {
        if (_profile.value == null || !backendState.value.isConnected) return
        _isRefreshing.value = true
        runCatching { api.conversations() }
            .onSuccess { items ->
                store.saveConversations(items)
                _conversations.value = store.conversations()
                _error.value = null
                flushPending()
            }
            .onFailure { _error.value = it.message ?: "Unable to refresh conversations." }
        _activeConversation.value?.id?.let { refreshMessages(it) }
        _isRefreshing.value = false
    }

    private suspend fun refreshMessages(conversationId: String) {
        runCatching { api.messages(conversationId) }
            .onSuccess { items ->
                store.saveMessages(items)
                publishMessages(conversationId)
            }
            .onFailure { failure ->
                _error.value = failure.message ?: "Unable to refresh message history."
            }
    }

    private suspend fun flushPending() {
        store.pendingMessages().forEach { deliverPending(it) }
    }

    private suspend fun deliverPending(message: LocalMessage) {
        runCatching {
            api.sendMessage(message.conversationId, message.content, message.id)
        }.onSuccess { delivered ->
            store.deleteMessage(message.id)
            store.saveMessage(
                delivered.copy(
                    id = message.id,
                    deliveryState = LocalDeliveryState.DELIVERED
                )
            )
            if (_activeConversation.value?.id == message.conversationId) {
                publishMessages(message.conversationId)
            }
        }.onFailure {
            store.updateDelivery(message.id, LocalDeliveryState.FAILED)
            if (_activeConversation.value?.id == message.conversationId) {
                publishMessages(message.conversationId)
            }
        }
    }

    private fun connectEvents() {
        if (eventsSocket != null || _profile.value == null || !backendState.value.isConnected) return
        eventsSocket = api.openEvents(
            listener = { message ->
                store.saveMessage(message)
                if (_activeConversation.value?.id == message.conversationId) {
                    publishMessages(message.conversationId)
                }
                viewModelScope.launch { refreshServerData() }
            },
            onState = { connected, reason ->
                _eventsConnected.value = connected
                if (!connected) {
                    eventsSocket = null
                    scheduleEventReconnect()
                    if (!reason.isNullOrBlank()) _error.value = "Events reconnecting: $reason"
                }
            }
        )
    }

    private fun scheduleEventReconnect() {
        if (reconnectJob?.isActive == true || !backendState.value.isConnected || _profile.value == null) return
        reconnectJob = viewModelScope.launch {
            var delayMs = 1_000L
            while (backendState.value.phase == BitVoidBackendPhase.CONNECTED &&
                !_eventsConnected.value && _profile.value != null
            ) {
                delay(delayMs)
                connectEvents()
                delayMs = (delayMs * 2).coerceAtMost(15_000L)
            }
        }
    }

    private fun publishMessages(conversationId: String) {
        _messages.value = store.messages(conversationId)
    }

    private fun normalizeUsername(raw: String): String? {
        val clean = raw.trim().removePrefix("@")
        if (!clean.matches(Regex("[A-Za-z0-9._-]{2,32}"))) {
            _pairing.value = _pairing.value.copy(
                error = "User ID must be 2–32 letters, numbers, dots, underscores, or hyphens."
            )
            return null
        }
        return clean
    }

    override fun onCleared() {
        reconnectJob?.cancel()
        eventsSocket?.cancel()
        backend.close()
        discovery.release()
        api.close()
        store.close()
        super.onCleared()
    }
}
