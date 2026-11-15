package com.bitvoid.network.call

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bitvoid.network.MainActivity
import com.bitvoid.network.R

/**
 * Dedicated Android 14+ compliant foreground service (`foregroundServiceType="mediaProjection"`)
 * started strictly after user grants screen-capture consent via [MediaProjectionManager].
 */
class BitVoidScreenProjectionService : Service() {

    companion object {
        private const val ACTION_START_PROJECTION = "com.bitvoid.network.action.START_PROJECTION"
        private const val ACTION_STOP_PROJECTION = "com.bitvoid.network.action.STOP_PROJECTION"
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "bitvoid_projection_channel"
        private const val NOTIFICATION_ID = 7085

        @Volatile
        private var pendingConsentCallback: ((Int, Intent) -> Unit)? = null

        fun startWithConsent(
            context: Context,
            resultCode: Int,
            data: Intent,
            onReadyForProjection: (Int, Intent) -> Unit
        ) {
            pendingConsentCallback = onReadyForProjection
            val intent = Intent(context, BitVoidScreenProjectionService::class.java).apply {
                action = ACTION_START_PROJECTION
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopProjectionService(context: Context) {
            pendingConsentCallback = null
            val intent = Intent(context, BitVoidScreenProjectionService::class.java).apply {
                action = ACTION_STOP_PROJECTION
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_PROJECTION -> {
                pendingConsentCallback = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START_PROJECTION -> {
                ensureChannel()
                val notification = buildNotification()
                val promoted = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(
                            NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                        )
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    true
                } catch (_: SecurityException) {
                    false
                }

                if (!promoted) {
                    pendingConsentCallback = null
                    stopSelf()
                    return START_NOT_STICKY
                }

                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    val cb = pendingConsentCallback
                    pendingConsentCallback = null
                    cb?.invoke(resultCode, resultData)
                } else {
                    pendingConsentCallback = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BitVoid Classroom Screen Share",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Active while presenting your screen in a BitVoid Live Class"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingLaunch = PendingIntent.getActivity(
            this,
            10,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, BitVoidScreenProjectionService::class.java).apply {
            action = ACTION_STOP_PROJECTION
        }
        val pendingStop = PendingIntent.getService(
            this,
            11,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bitvoid_notify)
            .setContentTitle("BitVoid Live Class Presentation")
            .setContentText("Sharing your screen on the local Wi-Fi classroom stream")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingLaunch)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop Screen Share",
                pendingStop
            )
            .build()
    }
}
