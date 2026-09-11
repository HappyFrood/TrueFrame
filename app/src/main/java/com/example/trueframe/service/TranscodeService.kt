package com.example.trueframe.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.example.trueframe.core.video.ProxyTranscoder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service to run video transcoding in the background.
 * Spec: "Foreground Service to prevent OS killing transcode."
 * Spec: "Guaranteed transcode execution."
 */
@AndroidEntryPoint
class TranscodeService : Service() {

    @Inject
    lateinit var proxyTranscoder: ProxyTranscoder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

        serviceScope.launch {
            launch {
                proxyTranscoder.state.collect { state ->
                    val manager = getSystemService(NotificationManager::class.java)
                    when (state) {
                        is ProxyTranscoder.TranscodeState.Progress -> {
                            val percent = (state.fraction * 100).toInt()
                            val updateNotification = buildNotification("Transcoding video… $percent%")
                            manager.notify(NOTIFICATION_ID, updateNotification)
                        }
                        is ProxyTranscoder.TranscodeState.Complete -> {
                            stopSelf()
                        }
                        is ProxyTranscoder.TranscodeState.Error -> {
                            stopSelf()
                        }
                        else -> {}
                    }
                }
            }
            proxyTranscoder.start(sourceUri, outputPath, this@TranscodeService)
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        proxyTranscoder.cancel()
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
