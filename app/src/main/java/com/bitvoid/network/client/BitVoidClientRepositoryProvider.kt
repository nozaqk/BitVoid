package com.bitvoid.network.client

import android.content.Context

/**
 * Process-wide client session owner.
 *
 * The foreground service and every Activity/ViewModel instance must observe the same repository so
 * joined-channel and direct-message WebSockets remain connected when the UI is dismissed. Android
 * can still terminate all networking after an explicit force-stop.
 */
object BitVoidClientRepositoryProvider {
    @Volatile private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    val repository: BitVoidClientRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BitVoidClientRepository(appContext)
    }
}
