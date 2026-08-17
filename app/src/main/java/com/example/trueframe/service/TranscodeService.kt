package com.example.trueframe.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint

/**
 * Foreground service to run video transcoding in the background.
 * Spec: "Foreground Service to prevent OS killing transcode."
 * Spec: "Guaranteed transcode execution."
 */
@AndroidEntryPoint
class TranscodeService : Service() {

    companion object {
        const val CHANNEL_ID = "transcode_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_OUTPUT_PATH = "output_path"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Transcoding video…")
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        val sourceUri = intent?.getStringExtra(EXTRA_SOURCE_URI)
        val outputPath = intent?.getStringExtra(EXTRA_OUTPUT_PATH)

        if (sourceUri == null || outputPath == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // TODO: Launch coroutine to run ProxyTranscoder.start(sourceUri, outputPath)
        // and update notification progress, then stopSelf() on completion.

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Video Transcoding",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows progress while transcoding video"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("TrueFrame")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .build()
    }
}
