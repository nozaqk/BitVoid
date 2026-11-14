package com.bitvoid.network.service

import com.bitvoid.network.model.AuditLogEntry
import com.bitvoid.network.model.ClientState
import com.bitvoid.network.model.HostState
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
}
