package com.example.trueframe.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.example.trueframe.core.video.ProxyTranscoder
import com.example.trueframe.data.repository.ProjectRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

import kotlinx.coroutines.cancel

/**
 * Foreground service to run video transcoding in the background.
 * Spec: "Foreground Service to prevent OS killing transcode."
 * Spec: "Guaranteed transcode execution."
 */
@AndroidEntryPoint
class TranscodeService : Service() {

    @Inject
    lateinit var projectRepository: ProjectRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val CHANNEL_ID = "transcode_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_PROJECT_ID = "project_id"
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_OUTPUT_PATH = "output_path"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Transcoding video…")
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)

        val projectId = intent?.getLongExtra(EXTRA_PROJECT_ID, -1L) ?: -1L
        val sourceUri = intent?.getStringExtra(EXTRA_SOURCE_URI)
        val outputPath = intent?.getStringExtra(EXTRA_OUTPUT_PATH)

        if (sourceUri == null || outputPath == null || projectId == -1L) {
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            val proxyTranscoder = ProxyTranscoder()
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
                            // Update project in DB with the new proxyUri
                            launch {
                                val project = projectRepository.getById(projectId)
                                if (project != null) {
                                    projectRepository.update(project.copy(proxyUri = outputPath))
                                }
                                stopSelf()
                            }
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
        serviceScope.cancel()
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
