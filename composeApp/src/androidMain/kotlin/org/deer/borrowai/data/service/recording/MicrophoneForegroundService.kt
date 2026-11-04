package org.deer.borrowai.data.service.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps the process alive while the microphone is recording, so capture continues when the app is minimized.
 */
class MicrophoneForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ensureChannel()
        val notification = buildNotification(paused = intent?.getBooleanExtra(EXTRA_PAUSED, false) == true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(paused: Boolean): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle(CONTENT_TITLE)
            .setContentText(if (paused) CONTENT_TEXT_PAUSED else CONTENT_TEXT)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "microphone_recording"
        private const val CHANNEL_NAME = "Recording"
        private const val CONTENT_TITLE = "Recording audio"
        private const val CONTENT_TEXT = "Borrow AI is recording from the microphone"
        private const val CONTENT_TEXT_PAUSED = "Recording paused"
        private const val EXTRA_PAUSED = "org.deer.borrowai.extra.PAUSED"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "org.deer.borrowai.action.START_MIC"
        private const val ACTION_STOP = "org.deer.borrowai.action.STOP_MIC"

        fun start(context: Context) {
            val intent = Intent(context, MicrophoneForegroundService::class.java).setAction(ACTION_START)
            // startForegroundService() was added in API 26. Older devices start the service normally and it
            // promotes itself to the foreground in onStartCommand.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Updates the notification text. The service is already in the foreground, so a plain start is enough. */
        fun setPaused(context: Context, paused: Boolean) {
            context.startService(
                Intent(context, MicrophoneForegroundService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_PAUSED, paused),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, MicrophoneForegroundService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
