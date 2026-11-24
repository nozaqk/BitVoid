package com.bitvoid.network

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.bitvoid.network.service.BitVoidForegroundService
import com.bitvoid.network.ui.BitVoidDashboard
import com.bitvoid.network.ui.BitVoidViewModel
import com.bitvoid.network.ui.theme.BitVoidTheme

class MainActivity : ComponentActivity() {

    private val viewModel: BitVoidViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        BitVoidForegroundService.setAppInForeground(true)
    }

    override fun onStop() {
        BitVoidForegroundService.setAppInForeground(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        setContent {
            val context = LocalContext.current

            fun computeMissingPermissions(): List<String> {
                val required = buildList {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                return required.filter { perm ->
                    ContextCompat.checkSelfPermission(context, perm) !=
                        PackageManager.PERMISSION_GRANTED
                }
            }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { }

            LaunchedEffect(Unit) {
                val missing = computeMissingPermissions()
                if (missing.isNotEmpty()) {
                    permissionLauncher.launch(missing.toTypedArray())
                }
            }

            BitVoidTheme {
                BitVoidDashboard(viewModel = viewModel)
            }
        }

        handleMessageNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleMessageNotificationIntent(intent)
    }

    private fun handleMessageNotificationIntent(intent: Intent?) {
        val destination = intent?.getStringExtra(
            BitVoidForegroundService.EXTRA_MESSAGE_DESTINATION
        ) ?: return
        viewModel.openMessageNotification(
            destination = destination,
            conversationId = intent.getStringExtra(
                BitVoidForegroundService.EXTRA_CONVERSATION_ID
            ),
            peerUsername = intent.getStringExtra(
                BitVoidForegroundService.EXTRA_PEER_USERNAME
            )
        )
        intent.removeExtra(BitVoidForegroundService.EXTRA_MESSAGE_DESTINATION)
    }
}
