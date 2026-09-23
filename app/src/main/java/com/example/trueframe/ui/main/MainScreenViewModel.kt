package com.example.trueframe.ui.main

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trueframe.core.video.TranscodeBus
import com.example.trueframe.core.video.TranscodeEvent
import com.example.trueframe.data.ProjectEntity
import com.example.trueframe.data.ProxyCacheManager
import com.example.trueframe.data.repository.ProjectRepository
import com.example.trueframe.service.TranscodeService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class ProjectMeta(
    val thumbnailPath: String? = null,
    val durationSec: Float? = null,
    val fps: Float? = null,
)

@HiltViewModel
class MainScreenViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
    private val proxyCacheManager: ProxyCacheManager,
    private val transcodeBus: TranscodeBus,
) : ViewModel() {

    private val _projectsError = MutableStateFlow<String?>(null)
    val projectsError: StateFlow<String?> = _projectsError

    val transcodeProgress: StateFlow<Map<Long, Float>> = transcodeBus.progress

    private val _activeTranscodeProjectId = MutableStateFlow<Long?>(null)
    val activeTranscodeProjectId: StateFlow<Long?> = _activeTranscodeProjectId.asStateFlow()

    private val _projectMetaMap = MutableStateFlow<Map<Long, ProjectMeta>>(emptyMap())
    val projectMetaMap: StateFlow<Map<Long, ProjectMeta>> = _projectMetaMap.asStateFlow()

    val uiState: StateFlow<MainScreenUiState> =
        projectRepository.observeAll()
            .map<List<ProjectEntity>, MainScreenUiState> { MainScreenUiState.Success(it) }
            .catch { emit(MainScreenUiState.Error(it)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MainScreenUiState.Loading)

    init {
        viewModelScope.launch {
            transcodeBus.events.collect { event ->
                when (event) {
                    is TranscodeEvent.Completed -> {
                        _activeTranscodeProjectId.value = null
                    }
                    is TranscodeEvent.Failed -> {
                        _projectsError.update { "Transcode failed: ${event.message}" }
                        _activeTranscodeProjectId.value = null
                    }
                }
            }
        }

        viewModelScope.launch {
            projectRepository.observeAll().collect { projects ->
                loadMetadataForProjects(projects)
            }
        }
    }

    private fun loadMetadataForProjects(projects: List<ProjectEntity>) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentMap = _projectMetaMap.value.toMutableMap()
            var changed = false
            projects.forEach { project ->
                if (currentMap.containsKey(project.id)) return@forEach

                val videoUri = project.videoUri
                val retriever = MediaMetadataRetriever()
                var thumbPath: String? = null
                var durSec: Float? = null
                var frameRate: Float? = null

                try {
                    if (videoUri.startsWith("content://")) {
                        retriever.setDataSource(context, Uri.parse(videoUri))
                    } else {
                        retriever.setDataSource(videoUri)
                    }

                    val durMsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val durMs = durMsStr?.toLongOrNull() ?: 0L
                    if (durMs > 0) durSec = durMs / 1000f

                    val fpsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                        ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                    frameRate = fpsStr?.toFloatOrNull() ?: 30f

                    val thumbDir = File(context.cacheDir, "thumbs").apply { if (!exists()) mkdirs() }
                    val thumbFile = File(thumbDir, "thumb_${project.id}.jpg")
                    if (thumbFile.exists() && thumbFile.length() > 0) {
                        thumbPath = thumbFile.absolutePath
                    } else {
                        val frameBitmap = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
                        if (frameBitmap != null) {
                            FileOutputStream(thumbFile).use { out ->
                                frameBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                            }
                            if (!frameBitmap.isRecycled) frameBitmap.recycle()
                            thumbPath = thumbFile.absolutePath
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }

                currentMap[project.id] = ProjectMeta(
                    thumbnailPath = thumbPath,
                    durationSec = durSec,
                    fps = frameRate,
                )
                changed = true
            }
            if (changed) {
                _projectMetaMap.value = currentMap
            }
        }
    }

    fun clearError() {
        _projectsError.update { null }
    }

    fun addProject(videoUri: String) {
        viewModelScope.launch {
            val sourceUri = Uri.parse(videoUri)

            // 1. Read metadata on Dispatchers.IO BEFORE copying file to prevent leaking multi-GB rejected files
            val isTooLong = withContext(Dispatchers.IO) {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, sourceUri)
                    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val durationMs = durationStr?.toLongOrNull() ?: 0L
                    durationMs > 60_000 * 5
                } catch (_: Exception) {
                    false
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            }

            if (isTooLong) {
                _projectsError.update { "Video is too long! Max 5 minutes allowed." }
                return@launch
            }

            // 2. Copy video to durable app storage (noBackupFilesDir) so access never expires
            val durableUri = withContext(Dispatchers.IO) {
                try {
                    val importDir = File(context.noBackupFilesDir, "imported_videos").apply { if (!exists()) mkdirs() }
                    val destFile = File(importDir, "video_${System.currentTimeMillis()}.mp4")
                    context.contentResolver.openInputStream(sourceUri)?.use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (destFile.exists() && destFile.length() > 0) {
                        Uri.fromFile(destFile).toString()
                    } else {
                        videoUri
                    }
                } catch (_: Exception) {
                    videoUri
                }
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val projectId = projectRepository.create(name = "Video $timestamp", videoUri = durableUri)

            _activeTranscodeProjectId.value = projectId
            val proxyPath = proxyCacheManager.generateProxyPath(projectId)

            val intent = Intent(context, TranscodeService::class.java).apply {
                putExtra(TranscodeService.EXTRA_PROJECT_ID, projectId)
                putExtra(TranscodeService.EXTRA_SOURCE_URI, durableUri)
                putExtra(TranscodeService.EXTRA_OUTPUT_PATH, proxyPath)
            }
            context.startForegroundService(intent)
        }
    }

    fun renameProject(project: ProjectEntity, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            projectRepository.update(project.copy(name = trimmed, updatedAt = System.currentTimeMillis()))
        }
    }

    fun deleteProject(project: ProjectEntity) {
        viewModelScope.launch {
            projectRepository.delete(project)
            withContext(Dispatchers.IO) {
                val videoPath = project.videoUri.removePrefix("file://")
                val videoFile = File(videoPath)
                if (videoFile.exists()) videoFile.delete()

                project.proxyUri?.let { proxyUri ->
                    val proxyPath = proxyUri.removePrefix("file://")
                    val proxyFile = File(proxyPath)
                    if (proxyFile.exists()) proxyFile.delete()
                }

                val thumbDir = File(context.cacheDir, "thumbs")
                val thumbFile = File(thumbDir, "thumb_${project.id}.jpg")
                if (thumbFile.exists()) thumbFile.delete()
            }
        }
    }
}

sealed interface MainScreenUiState {
    data object Loading : MainScreenUiState
    data class Error(val throwable: Throwable) : MainScreenUiState
    data class Success(val projects: List<ProjectEntity>) : MainScreenUiState
}
