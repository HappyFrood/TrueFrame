package com.example.trueframe.ui.main

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trueframe.core.video.ProxyTranscoder
import com.example.trueframe.data.ProjectEntity
import com.example.trueframe.data.ProxyCacheManager
import com.example.trueframe.data.repository.ProjectRepository
import com.example.trueframe.service.TranscodeService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class MainScreenViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val projectRepository: ProjectRepository,
    private val proxyCacheManager: ProxyCacheManager,
    val proxyTranscoder: ProxyTranscoder,
) : ViewModel() {

    private val _projectsError = MutableStateFlow<String?>(null)
    val projectsError: StateFlow<String?> = _projectsError
    
    val uiState: StateFlow<MainScreenUiState> =
        projectRepository.observeAll()
            .map<List<ProjectEntity>, MainScreenUiState> { MainScreenUiState.Success(it) }
            .catch { emit(MainScreenUiState.Error(it)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MainScreenUiState.Loading)

    init {
        // Monitor transcode state to update proxy URI when done
        viewModelScope.launch {
            proxyTranscoder.state.collect { state ->
                if (state is ProxyTranscoder.TranscodeState.Complete) {
                    _activeTranscodeProjectId.value?.let { projectId ->
                        val project = projectRepository.getById(projectId)
                        if (project != null) {
                            val proxyPath = proxyCacheManager.generateProxyPath(projectId)
                            projectRepository.update(project.copy(proxyUri = proxyPath))
                        }
                    }
                    _activeTranscodeProjectId.value = null
                } else if (state is ProxyTranscoder.TranscodeState.Error) {
                    _projectsError.update { "Transcode failed: ${state.cause.message}" }
                    _activeTranscodeProjectId.value = null
                }
            }
        }
    }

    private val _activeTranscodeProjectId = MutableStateFlow<Long?>(null)
    val activeTranscodeProjectId: StateFlow<Long?> = _activeTranscodeProjectId.asStateFlow()

    fun clearError() {
        _projectsError.update { null }
    }

    fun addProject(videoUri: String) {
        viewModelScope.launch {
            val uri = Uri.parse(videoUri)
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
            } catch (_: Exception) {
                // Ignore if provider doesn't support persistable permission
            }

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(videoUri))
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durationMs = durationStr?.toLongOrNull() ?: 0L
                if (durationMs > 60_000 * 5) {
                    _projectsError.update { "Video is too long! Max 5 minutes allowed." }
                    return@launch
                }
            } catch (e: Exception) {
                _projectsError.update { "Failed to read video metadata." }
                return@launch
            } finally {
                retriever.release()
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val projectId = projectRepository.create(name = "Video $timestamp", videoUri = videoUri)
            
            _activeTranscodeProjectId.value = projectId
            val proxyPath = proxyCacheManager.generateProxyPath(projectId)
            
            val intent = Intent(context, TranscodeService::class.java).apply {
                putExtra(TranscodeService.EXTRA_SOURCE_URI, videoUri)
                putExtra(TranscodeService.EXTRA_OUTPUT_PATH, proxyPath)
            }
            context.startForegroundService(intent)
        }
    }
    
    fun deleteProject(project: ProjectEntity) {
        viewModelScope.launch {
            projectRepository.delete(project)
            project.proxyUri?.let { proxyUri ->
                val file = File(proxyUri)
                if (file.exists()) file.delete()
            }
        }
    }
}

sealed interface MainScreenUiState {
    data object Loading : MainScreenUiState
    data class Error(val throwable: Throwable) : MainScreenUiState
    data class Success(val projects: List<ProjectEntity>) : MainScreenUiState
}
