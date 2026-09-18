package com.example.trueframe.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trueframe.core.annotation.AnnotationShape
import com.example.trueframe.data.AnnotationDao
import com.example.trueframe.data.AnnotationEntity
import com.example.trueframe.data.AnnotationJson
import com.example.trueframe.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.hypot

data class AnnotationItem(val id: Long, val shape: AnnotationShape)

data class EditorUiState(
    val videoUri: String? = null,
    val frameIndex: Int = 0,
    val frameRate: Float = 30f,
    val currentTimeMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val rotationDegrees: Int = 0,
    val annotations: List<AnnotationItem> = emptyList(),
    val selectedAnnotationIndex: Int? = null,
    val error: String? = null,
) {
    val frameIntervalMs: Long get() = (1000f / frameRate.coerceAtLeast(1f)).toLong().coerceAtLeast(1L)
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val annotationDao: AnnotationDao,
) : ViewModel() {

    private var projectId: Long = -1L

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val _frameIndexFlow = MutableStateFlow(0)

    @Volatile private var isDragging = false

    fun onDragStarted() { isDragging = true }

    fun initialize(id: Long) {
        if (projectId != -1L) return
        projectId = id

        viewModelScope.launch {
            val project = projectRepository.getById(projectId)
            if (project != null) {
                val uri = project.proxyUri ?: project.videoUri
                _uiState.update { it.copy(videoUri = uri, rotationDegrees = project.rotationDegrees) }
            } else {
                _uiState.update { it.copy(error = "Project not found") }
            }
        }

        viewModelScope.launch {
            annotationDao.observeForProject(projectId).collect { entities ->
                if (isDragging) return@collect          // never clobber a live drag
                val items = entities.mapNotNull { e ->
                    AnnotationJson.deserialize(e.serializedData)?.let { AnnotationItem(e.id, it) }
                }
                _uiState.update { it.copy(annotations = items) }
            }
        }
    }

    fun updatePlayerState(currentTimeMs: Long, totalDurationMs: Long, isPlaying: Boolean, frameRate: Float = 30f) {
        val interval = (1000f / frameRate.coerceAtLeast(1f)).toLong().coerceAtLeast(1L)
        val frameIdx = (currentTimeMs / interval).toInt()
        _frameIndexFlow.value = frameIdx
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
        val next = (_uiState.value.rotationDegrees + 90) % 360
        _uiState.update { it.copy(rotationDegrees = next) }
        viewModelScope.launch {
            projectRepository.getById(projectId)?.let {
                projectRepository.update(it.copy(rotationDegrees = next, updatedAt = System.currentTimeMillis()))
            }
        }
    }

    fun selectAnnotation(index: Int?) {
        _uiState.update { it.copy(selectedAnnotationIndex = index) }
    }

    private fun spawnOffset(): Offset {
        val n = _uiState.value.annotations.size % 8
        return Offset(n * 0.02f, n * 0.03f)
    }

    fun addLine() {
        val o = spawnOffset()
        saveNewShape(AnnotationShape.Line(Offset(0.3f, 0.5f) + o, Offset(0.7f, 0.5f) + o), "line")
    }

    fun addAngle() {
        val o = spawnOffset()
        val angle = AnnotationShape.Angle(
            start = Offset(0.3f, 0.4f) + o,
            center = Offset(0.5f, 0.5f) + o,
            end = Offset(0.7f, 0.4f) + o,
        )
        saveNewShape(angle, "angle")
    }

    fun addCircle() {
        val o = spawnOffset()
        val circle = AnnotationShape.Circle(Offset(0.5f, 0.5f) + o, 0.15f)
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
        val items = _uiState.value.annotations.toMutableList()
        if (shapeIndex !in items.indices) return

        val s = items[shapeIndex].shape
        val moved = when (s) {
            is AnnotationShape.Line -> s.copy(start = s.start + delta, end = s.end + delta)
            is AnnotationShape.Angle -> s.copy(
                start = s.start + delta,
                center = s.center + delta,
                end = s.end + delta
            )
            is AnnotationShape.Circle -> s.copy(center = s.center + delta)
        }
        items[shapeIndex] = items[shapeIndex].copy(shape = moved)
        _uiState.update { it.copy(annotations = items, selectedAnnotationIndex = shapeIndex) }
    }

    fun updateShapeHandle(shapeIndex: Int, handleIndex: Int, newOffset: Offset, aspectCorrection: Float = 1.0f) {
        val items = _uiState.value.annotations.toMutableList()
        if (shapeIndex !in items.indices) return

        val s = items[shapeIndex].shape
        val updatedShape = when (s) {
            is AnnotationShape.Line -> {
                when (handleIndex) {
                    0 -> s.copy(start = newOffset)
                    1 -> s.copy(end = newOffset)
                    else -> s
                }
            }
            is AnnotationShape.Angle -> {
                when (handleIndex) {
                    0 -> s.copy(start = newOffset)
                    1 -> s.copy(center = newOffset)
                    2 -> s.copy(end = newOffset)
                    else -> s
                }
            }
            is AnnotationShape.Circle -> {
                when (handleIndex) {
                    0 -> s.copy(center = newOffset)
                    1 -> {
                        val dx = (newOffset.x - s.center.x).toDouble()
                        val dy = ((newOffset.y - s.center.y) * aspectCorrection).toDouble()
                        val newRadius = hypot(dx, dy).toFloat()
                        s.copy(radius = newRadius.coerceAtLeast(0.02f))
                    }
                    else -> s
                }
            }
        }
        items[shapeIndex] = items[shapeIndex].copy(shape = updatedShape)
        _uiState.update { it.copy(annotations = items, selectedAnnotationIndex = shapeIndex) }
    }

    fun persistAnnotationsOnDragEnd(shapeIndex: Int?) {
        val item = shapeIndex?.let { _uiState.value.annotations.getOrNull(it) }
        val frame = _uiState.value.frameIndex
        viewModelScope.launch {
            if (item != null) {
                annotationDao.update(
                    AnnotationEntity(
                        id = item.id,
                        projectId = projectId,
                        frameIndex = frame,
                        shapeType = item.shape.typeName(),
                        serializedData = AnnotationJson.serialize(item.shape),
                    )
                )
            }
            isDragging = false
        }
    }

    private fun AnnotationShape.typeName(): String = when (this) {
        is AnnotationShape.Line -> "line"
        is AnnotationShape.Angle -> "angle"
        is AnnotationShape.Circle -> "circle"
    }

    fun deleteSelectedAnnotation() {
        val idx = _uiState.value.selectedAnnotationIndex ?: return
        val item = _uiState.value.annotations.getOrNull(idx) ?: return
        viewModelScope.launch {
            annotationDao.deleteById(item.id)
            _uiState.update { it.copy(selectedAnnotationIndex = null) }
        }
    }

    fun clearAllAnnotations() {
        viewModelScope.launch {
            annotationDao.deleteAllForProject(projectId)
            _uiState.update { it.copy(selectedAnnotationIndex = null) }
        }
    }
}