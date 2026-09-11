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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.hypot

data class EditorUiState(
    val currentFrame: Bitmap? = null,
    val frameIndex: Int = 0,
    val currentTimeMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val rotationDegrees: Int = 0,
    val annotations: List<AnnotationShape> = emptyList(),
    val selectedAnnotationIndex: Int? = null,
    val error: String? = null,
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
    private var session: ProxyReader.Session? = null
    private var videoUri: String? = null
    private var playbackJob: Job? = null
    private var frameLoadJob: Job? = null

    fun initialize(id: Long) {
        if (projectId != -1L) return
        projectId = id
        viewModelScope.launch {
            val project = projectRepository.getById(projectId)
            if (project != null) {
                videoUri = project.proxyUri ?: project.videoUri
                val currentSession = ProxyReader.Session(videoUri!!, context)
                session = currentSession
                val duration = currentSession.getDurationMs()
                _uiState.update { it.copy(totalDurationMs = duration) }
                loadFrameAtTime(0L)
            } else {
                _uiState.update { it.copy(error = "Project not found") }
            }
        }
    }

    fun rotateVideo() {
        _uiState.update { it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360) }
    }

    fun stepFrames(delta: Int) {
        pausePlayback()
        val currentMs = _uiState.value.currentTimeMs
        val newMs = (currentMs + (delta * 33L)).coerceIn(0L, _uiState.value.totalDurationMs.coerceAtLeast(1000L))
        loadFrameAtTime(newMs, fastSeek = false)
    }

    fun seekToMs(timeMs: Long, isScrubbing: Boolean = false) {
        pausePlayback()
        val clampedMs = timeMs.coerceIn(0L, _uiState.value.totalDurationMs.coerceAtLeast(1000L))
        _uiState.update { it.copy(currentTimeMs = clampedMs, frameIndex = (clampedMs / 33L).toInt()) }
        loadFrameAtTime(clampedMs, fastSeek = isScrubbing)
    }

    fun togglePlayPause() {
        if (_uiState.value.isPlaying) {
            pausePlayback()
        } else {
            startPlayback()
        }
    }

    private fun startPlayback() {
        playbackJob?.cancel()
        _uiState.update { it.copy(isPlaying = true) }
        playbackJob = viewModelScope.launch {
            while (_uiState.value.isPlaying) {
                val nextMs = _uiState.value.currentTimeMs + 33L
                val duration = _uiState.value.totalDurationMs.coerceAtLeast(1000L)
                if (nextMs >= duration) {
                    pausePlayback()
                    break
                }
                loadFrameAtTime(nextMs)
                delay(33L)
            }
        }
    }

    private fun pausePlayback() {
        playbackJob?.cancel()
        playbackJob = null
        _uiState.update { it.copy(isPlaying = false) }
    }

    private fun loadFrameAtTime(timeMs: Long, fastSeek: Boolean = false) {
        val currentSession = session ?: return
        frameLoadJob?.cancel()
        frameLoadJob = viewModelScope.launch {
            val bitmap = currentSession.readFrameAtTimeUs(timeMs * 1000L, fastSeek = fastSeek)
            if (bitmap != null) {
                val frameIdx = (timeMs / 33L).toInt()
                _uiState.update {
                    it.copy(
                        currentFrame = bitmap,
                        currentTimeMs = timeMs,
                        frameIndex = frameIdx,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        session?.release()
        session = null
    }

    fun selectAnnotation(index: Int?) {
        _uiState.update { it.copy(selectedAnnotationIndex = index) }
    }

    fun addLine() {
        val center = Offset(500f, 600f)
        val line = AnnotationShape.Line(center - Offset(150f, 0f), center + Offset(150f, 0f))
        val newIndex = _uiState.value.annotations.size
        _uiState.update {
            it.copy(
                annotations = it.annotations + line,
                selectedAnnotationIndex = newIndex,
            )
        }
    }

    fun addAngle() {
        val vertex = Offset(500f, 600f)
        val angle = AnnotationShape.Angle(
            start = vertex + Offset(-150f, -100f),
            center = vertex,
            end = vertex + Offset(150f, -100f),
        )
        val newIndex = _uiState.value.annotations.size
        _uiState.update {
            it.copy(
                annotations = it.annotations + angle,
                selectedAnnotationIndex = newIndex,
            )
        }
    }

    fun addCircle() {
        val center = Offset(500f, 600f)
        val circle = AnnotationShape.Circle(center, 150f)
        val newIndex = _uiState.value.annotations.size
        _uiState.update {
            it.copy(
                annotations = it.annotations + circle,
                selectedAnnotationIndex = newIndex,
            )
        }
    }

    fun offsetShape(shapeIndex: Int, delta: Offset) {
        val shapes = _uiState.value.annotations.toMutableList()
        if (shapeIndex !in shapes.indices) return

        val updatedShape = when (val shape = shapes[shapeIndex]) {
            is AnnotationShape.Line -> shape.copy(start = shape.start + delta, end = shape.end + delta)
            is AnnotationShape.Angle -> shape.copy(
                start = shape.start + delta,
                center = shape.center + delta,
                end = shape.end + delta
            )
            is AnnotationShape.Circle -> shape.copy(center = shape.center + delta)
        }
        shapes[shapeIndex] = updatedShape
        _uiState.update { it.copy(annotations = shapes, selectedAnnotationIndex = shapeIndex) }
    }

    fun updateShapeHandle(shapeIndex: Int, handleIndex: Int, newOffset: Offset) {
        val shapes = _uiState.value.annotations.toMutableList()
        if (shapeIndex !in shapes.indices) return

        val updatedShape = when (val shape = shapes[shapeIndex]) {
            is AnnotationShape.Line -> {
                when (handleIndex) {
                    0 -> shape.copy(start = newOffset)
                    1 -> shape.copy(end = newOffset)
                    else -> shape
                }
            }
            is AnnotationShape.Angle -> {
                when (handleIndex) {
                    0 -> shape.copy(start = newOffset)
                    1 -> shape.copy(center = newOffset)
                    2 -> shape.copy(end = newOffset)
                    else -> shape
                }
            }
            is AnnotationShape.Circle -> {
                when (handleIndex) {
                    0 -> shape.copy(center = newOffset)
                    1 -> {
                        val newRadius = hypot((newOffset.x - shape.center.x).toDouble(), (newOffset.y - shape.center.y).toDouble()).toFloat()
                        shape.copy(radius = newRadius.coerceAtLeast(20f))
                    }
                    else -> shape
                }
            }
        }
        shapes[shapeIndex] = updatedShape
        _uiState.update { it.copy(annotations = shapes, selectedAnnotationIndex = shapeIndex) }
    }

    fun deleteSelectedAnnotation() {
        val selected = _uiState.value.selectedAnnotationIndex ?: return
        val shapes = _uiState.value.annotations.toMutableList()
        if (selected in shapes.indices) {
            shapes.removeAt(selected)
            _uiState.update {
                it.copy(
                    annotations = shapes,
                    selectedAnnotationIndex = null,
                )
            }
        }
    }

    fun clearAllAnnotations() {
        _uiState.update { it.copy(annotations = emptyList(), selectedAnnotationIndex = null) }
    }
}
