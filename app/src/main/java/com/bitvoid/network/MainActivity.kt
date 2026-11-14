package com.bitvoid.network

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.bitvoid.network.ui.BitVoidDashboard
import com.bitvoid.network.ui.BitVoidViewModel
import com.bitvoid.network.ui.theme.BitVoidTheme

class MainActivity : ComponentActivity() {

    private val viewModel: BitVoidViewModel by viewModels()

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
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.NEARBY_WIFI_DEVICES)
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                return required.filter { perm ->
                    ContextCompat.checkSelfPermission(context, perm) !=
                        PackageManager.PERMISSION_GRANTED
                }
            }

            var missingPermissions by remember { mutableStateOf(computeMissingPermissions()) }

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) {
                missingPermissions = computeMissingPermissions()
            }

            val openDocumentsLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenMultipleDocuments()
            ) { uris ->
                if (uris.isNotEmpty()) {
                    viewModel.importFiles(uris)
                }
            }

            val pickVisualMediaLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.PickMultipleVisualMedia()
            ) { uris ->
                if (uris.isNotEmpty()) {
                    viewModel.importFiles(uris)
                }
            }

            LaunchedEffect(Unit) {
                val missing = computeMissingPermissions()
                if (missing.isNotEmpty()) {
                    permissionLauncher.launch(missing.toTypedArray())
                }
            }

            BitVoidTheme {
                BitVoidDashboard(
                    viewModel = viewModel,
                    missingPermissions = missingPermissions,
                    onRequestPermissions = {
                        val missing = computeMissingPermissions()
                        if (missing.isNotEmpty()) {
                            permissionLauncher.launch(missing.toTypedArray())
                        }
                    },
                    onPickDocuments = {
                        openDocumentsLauncher.launch(arrayOf("*/*"))
                    },
                    onPickMedia = {
                        pickVisualMediaLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageAndVideo
                            )
                        )
                    }
                )
            }
        }
    }
}
