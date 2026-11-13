package com.bitvoid.network

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val clientManager by lazy { BitVoidClientManager(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val initialFiles = SharedFileStore.refresh(applicationContext)
        BitVoidMediaManager.initialize(applicationContext, initialFiles)

        setContent {
            BitVoidTheme {
                val nodeState by BitVoidForegroundService.nodeState.collectAsStateWithLifecycle()
                val clientState by clientManager.state.collectAsStateWithLifecycle()
                val mediaItems by BitVoidMediaManager.videos.collectAsStateWithLifecycle()
                val rooms by BitVoidRoomManager.rooms.collectAsStateWithLifecycle()
                var permissionRefresh by remember { mutableIntStateOf(0) }
                var browserUrl by rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }

                val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) {
                    permissionRefresh += 1
                }
                val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenMultipleDocuments(),
                ) { uris ->
                    if (uris.isNotEmpty()) {
                        uris.forEach { uri ->
                            runCatching {
                                contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                            }
                        }
                        lifecycleScope.launch {
                            val result = SharedFileStore.importUris(applicationContext, uris)
                            result.onSuccess { count ->
                                Toast.makeText(
                                    this@MainActivity,
                                    "$count video${if (count == 1) "" else "s"} imported as Private",
                                    Toast.LENGTH_SHORT,
                                ).show()
                                if (BitVoidForegroundService.nodeState.value.isActive) {
                                    startService(
                                        Intent(
                                            this@MainActivity,
                                            BitVoidForegroundService::class.java,
                                        ).setAction(BitVoidForegroundService.ACTION_REFRESH_FILES),
                                    )
                                }
                            }.onFailure { error ->
                                Toast.makeText(
                                    this@MainActivity,
                                    error.message ?: "The selected file could not be imported",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                }

                val permissions = remember { requiredRuntimePermissions() }
                LaunchedEffect(Unit) {
                    val missing = permissions.filter { permission ->
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            permission,
                        ) != PackageManager.PERMISSION_GRANTED
                    }
                    if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
                }

                // Reading this key deliberately refreshes checks after a permission result.
                val permissionsGranted = remember(permissionRefresh) {
                    criticalPermissionsGranted()
                }

                if (browserUrl != null) {
                    SanitizedLocalBrowser(
                        url = browserUrl.orEmpty(),
                        onClose = { browserUrl = null },
                    )
                } else {
                    BitVoidDashboard(
                        nodeState = nodeState,
                        clientState = clientState,
                        mediaItems = mediaItems,
                        rooms = rooms,
                        criticalPermissionsGranted = permissionsGranted,
                        onRequestPermissions = {
                            permissionLauncher.launch(permissions.toTypedArray())
                        },
                        onHostPowerChanged = ::setHostEnabled,
                        onPickFiles = {
                            filePicker.launch(arrayOf("video/*"))
                        },
                        onVideoVisibilityChanged = { id, isPublic ->
                            BitVoidMediaManager.setVideoPublic(id, isPublic)
                        },
                        onCreateRoom = { roomId, passkey, videoIds ->
                            val privateVideoIds = BitVoidMediaManager.videos.value
                                .filterNot { it.isPublic }
                                .mapTo(HashSet()) { it.id }
                            val selectedPrivateVideos = videoIds.intersect(privateVideoIds)
                            selectedPrivateVideos.isNotEmpty() && BitVoidRoomManager.createRoom(
                                roomId,
                                passkey,
                                selectedPrivateVideos,
                            )
                        },
                        onScan = clientManager::scan,
                        onConnect = clientManager::connect,
                        onDisconnect = { clientManager.disconnect() },
                        onOpenBrowser = { url -> browserUrl = url },
                    )
                }
            }
        }
    }

    private fun setHostEnabled(enabled: Boolean) {
        val intent = Intent(this, BitVoidForegroundService::class.java).setAction(
            if (enabled) {
                BitVoidForegroundService.ACTION_START_NODE
            } else {
                BitVoidForegroundService.ACTION_STOP_NODE
            },
        )
        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun criticalPermissionsGranted(): Boolean {
        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requiredRuntimePermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_VIDEO)
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.READ_MEDIA_IMAGES)
            }

            else -> add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    override fun onDestroy() {
        clientManager.close()
        super.onDestroy()
    }
}
