package com.bitvoid.network

import android.app.Application
import com.bitvoid.network.client.BitVoidClientRepositoryProvider

class BitVoidApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BitVoidClientRepositoryProvider.initialize(this)
    }
}
