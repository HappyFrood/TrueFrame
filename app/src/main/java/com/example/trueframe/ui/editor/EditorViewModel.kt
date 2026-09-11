package com.example.trueframe.ui.editor

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trueframe.core.annotation.AnnotationShape
import com.example.trueframe.core.video.ProxyReader
import com.example.trueframe.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditorUiState(
    val currentFrame: Bitmap? = null,
    val frameIndex: Int = 0,
    val annotations: List<AnnotationShape> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
) : ViewModel() {

    private var projectId: Long = -1L

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val proxyReader = ProxyReader()
    private var videoUri: String? = null

    fun initialize(id: Long) {
        if (projectId != -1L) return
        projectId = id
        viewModelScope.launch {
            val project = projectRepository.getById(projectId)
            if (project != null) {
                videoUri = project.proxyUri ?: project.videoUri
                loadFrame(0)
            } else {
                _uiState.update { it.copy(error = "Project not found") }
            }
        }
    }

    fun nextFrame() {
        val newIndex = _uiState.value.frameIndex + 1
        loadFrame(newIndex)
    }

    fun prevFrame() {
        val newIndex = (_uiState.value.frameIndex - 1).coerceAtLeast(0)
        loadFrame(newIndex)
    }

    private fun loadFrame(index: Int) {
        val uri = videoUri ?: return
        viewModelScope.launch {
            val bitmap = proxyReader.readFrame(uri, index, context)
            _uiState.update { it.copy(currentFrame = bitmap, frameIndex = index) }
        }
    }

    fun addLine() {
        val line = AnnotationShape.Line(Offset(100f, 100f), Offset(400f, 400f))
        _uiState.update { it.copy(annotations = it.annotations + line) }
    }

    fun addAngle() {
        val angle = AnnotationShape.Angle(Offset(100f, 100f), Offset(300f, 300f), Offset(500f, 100f))
        _uiState.update { it.copy(annotations = it.annotations + angle) }
    }

    fun addCircle() {
        val circle = AnnotationShape.Circle(Offset(300f, 300f), 100f)
        _uiState.update { it.copy(annotations = it.annotations + circle) }
    }
}
