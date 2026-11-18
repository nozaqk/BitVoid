package com.bitvoid.network.client

/**
 * Process-wide client session owner.
 *
 * The foreground service and every Activity/ViewModel instance must observe the same repository so
 * joined-channel and direct-message WebSockets remain connected when the UI is dismissed. Android
 * can still terminate all local networking after an explicit force-stop.
 */
object BitVoidClientRepositoryProvider {
    val repository: BitVoidClientRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BitVoidClientRepository()
    }
}
