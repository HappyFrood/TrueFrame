package com.example.trueframe.ui.main

import android.content.Context
import android.content.Intent
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
            }
        }
    }
}

sealed interface MainScreenUiState {
    data object Loading : MainScreenUiState
    data class Error(val throwable: Throwable) : MainScreenUiState
    data class Success(val projects: List<ProjectEntity>) : MainScreenUiState
}