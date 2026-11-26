package com.bitvoid.network

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.bitvoid.network.local.BitVoidLocalApp
import com.bitvoid.network.local.BitVoidLocalViewModel
import com.bitvoid.network.ui.theme.BitVoidTheme

class MainActivity : ComponentActivity() {
    private val viewModel: BitVoidLocalViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            BitVoidTheme { BitVoidLocalApp(viewModel) }
        }
    }
}
