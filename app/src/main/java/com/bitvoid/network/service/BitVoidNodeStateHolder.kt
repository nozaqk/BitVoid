package com.bitvoid.network.service

import com.bitvoid.network.model.AuditLogEntry
import com.bitvoid.network.model.ClientState
import com.bitvoid.network.model.HostState
import com.bitvoid.network.model.NodeConnectionEventType
import com.bitvoid.network.model.NodeConnectionLogEntry
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object BitVoidNodeStateHolder {

    private val _hostState = MutableStateFlow(HostState())
    val hostState: StateFlow<HostState> = _hostState.asStateFlow()

    private val _auditLogs = MutableStateFlow<List<AuditLogEntry>>(emptyList())
    val auditLogs: StateFlow<List<AuditLogEntry>> = _auditLogs.asStateFlow()

    private val _clientState = MutableStateFlow(ClientState())
    val clientState: StateFlow<ClientState> = _clientState.asStateFlow()

    private val _nodeConnectionLogs = MutableStateFlow<List<NodeConnectionLogEntry>>(emptyList())
    val nodeConnectionLogs: StateFlow<List<NodeConnectionLogEntry>> =
        _nodeConnectionLogs.asStateFlow()

    fun updateHostState(transform: (HostState) -> HostState) {
        _hostState.update(transform)
    }

    fun updateClientState(transform: (ClientState) -> ClientState) {
        _clientState.update(transform)
    }

    fun appendAuditLog(entry: AuditLogEntry) {
        _auditLogs.update { current ->
            (listOf(entry) + current).take(40)
        }
    }

    fun clearAuditLogs() {
        _auditLogs.value = emptyList()
    }

    fun appendNodeConnectionLog(
        type: NodeConnectionEventType,
        title: String,
        detail: String,
        isError: Boolean = type == NodeConnectionEventType.ERROR
    ) {
        val now = System.currentTimeMillis()
        _nodeConnectionLogs.update { current ->
            val last = current.firstOrNull()
            if (last != null &&
                last.type == type &&
                last.title == title &&
                last.detail == detail &&
                now - last.timestampMillis < NODE_LOG_DEDUP_WINDOW_MS
            ) {
                current
            } else {
                listOf(
                    NodeConnectionLogEntry(
                        id = UUID.randomUUID().toString(),
                        timestampMillis = now,
                        type = type,
                        title = title,
                        detail = detail,
                        isError = isError
                    )
                ) + current.take(MAX_NODE_LOG_ENTRIES - 1)
            }
        }
    }

    fun clearNodeConnectionLogs() {
        _nodeConnectionLogs.value = emptyList()
    }

    private const val MAX_NODE_LOG_ENTRIES = 200
    private const val NODE_LOG_DEDUP_WINDOW_MS = 10_000L
}
