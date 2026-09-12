package com.example.trueframe.ui.editor

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trueframe.core.annotation.AnnotationShape
import com.example.trueframe.data.AnnotationDao
import com.example.trueframe.data.AnnotationEntity
import com.example.trueframe.data.AnnotationJson
import com.example.trueframe.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.hypot

data class EditorUiState(
    val videoUri: String? = null,
    val frameIndex: Int = 0,
    val frameRate: Float = 30f,
    val currentTimeMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val rotationDegrees: Int = 0,
    val annotations: List<AnnotationShape> = emptyList(),
    val selectedAnnotationIndex: Int? = null,
    val error: String? = null,
) {
    val frameIntervalMs: Long get() = (1000f / frameRate.coerceAtLeast(1f)).toLong().coerceAtLeast(1L)
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val projectRepository: ProjectRepository,
    private val annotationDao: AnnotationDao,
) : ViewModel() {

    private var projectId: Long = -1L

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    fun initialize(id: Long) {
        if (projectId != -1L) return
        projectId = id
        viewModelScope.launch {
            val project = projectRepository.getById(projectId)
            if (project != null) {
                val uri = project.proxyUri ?: project.videoUri
                _uiState.update { it.copy(videoUri = uri) }
            } else {
                _uiState.update { it.copy(error = "Project not found") }
            }
        }

        viewModelScope.launch {
            annotationDao.observeForProject(id).collect { entities ->
                val shapes = entities.mapNotNull { AnnotationJson.deserialize(it.serializedData) }
                _uiState.update { it.copy(annotations = shapes) }
            }
        }
    }

    fun updatePlayerState(currentTimeMs: Long, totalDurationMs: Long, isPlaying: Boolean, frameRate: Float = 30f) {
        val interval = (1000f / frameRate.coerceAtLeast(1f)).toLong().coerceAtLeast(1L)
        val frameIdx = (currentTimeMs / interval).toInt()
        _uiState.update {
            it.copy(
                currentTimeMs = currentTimeMs,
                totalDurationMs = totalDurationMs,
                isPlaying = isPlaying,
                frameRate = frameRate,
                frameIndex = frameIdx,
            )
        }
    }

    fun rotateVideo() {
        _uiState.update { it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360) }
    }

    fun selectAnnotation(index: Int?) {
        _uiState.update { it.copy(selectedAnnotationIndex = index) }
    }

    fun addLine() {
        val line = AnnotationShape.Line(Offset(0.3f, 0.5f), Offset(0.7f, 0.5f))
        saveNewShape(line, "line")
    }

    fun addAngle() {
        val angle = AnnotationShape.Angle(
            start = Offset(0.3f, 0.4f),
            center = Offset(0.5f, 0.5f),
            end = Offset(0.7f, 0.4f),
        )
        saveNewShape(angle, "angle")
    }

    fun addCircle() {
        val circle = AnnotationShape.Circle(Offset(0.5f, 0.5f), 0.15f)
        saveNewShape(circle, "circle")
    }

    private fun saveNewShape(shape: AnnotationShape, shapeType: String) {
        viewModelScope.launch {
            val json = AnnotationJson.serialize(shape)
            val entity = AnnotationEntity(
                projectId = projectId,
                frameIndex = _uiState.value.frameIndex,
                shapeType = shapeType,
                serializedData = json,
            )
            annotationDao.insert(entity)
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

    fun updateShapeHandle(shapeIndex: Int, handleIndex: Int, newOffset: Offset, aspectCorrection: Float = 1.0f) {
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
                        val dx = (newOffset.x - shape.center.x).toDouble()
                        val dy = ((newOffset.y - shape.center.y) * aspectCorrection).toDouble()
                        val newRadius = hypot(dx, dy).toFloat()
                        shape.copy(radius = newRadius.coerceAtLeast(0.02f))
                    }
                    else -> shape
                }
            }
        }
        shapes[shapeIndex] = updatedShape
        _uiState.update { it.copy(annotations = shapes, selectedAnnotationIndex = shapeIndex) }
    }

    fun persistAnnotationsOnDragEnd() {
        persistShapes(_uiState.value.annotations)
    }

    private fun persistShapes(shapes: List<AnnotationShape>) {
        viewModelScope.launch {
            annotationDao.deleteAllForProject(projectId)
            shapes.forEach { shape ->
                val typeStr = when (shape) {
                    is AnnotationShape.Line -> "line"
                    is AnnotationShape.Angle -> "angle"
                    is AnnotationShape.Circle -> "circle"
                }
                val entity = AnnotationEntity(
                    projectId = projectId,
                    frameIndex = _uiState.value.frameIndex,
                    shapeType = typeStr,
                    serializedData = AnnotationJson.serialize(shape),
                )
                annotationDao.insert(entity)
            }
        }
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
            persistShapes(shapes)
        }
    }

    fun clearAllAnnotations() {
        _uiState.update { it.copy(annotations = emptyList(), selectedAnnotationIndex = null) }
        viewModelScope.launch {
            annotationDao.deleteAllForProject(projectId)
        }
    }
}
