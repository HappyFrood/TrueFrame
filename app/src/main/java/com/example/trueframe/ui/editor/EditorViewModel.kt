package com.example.trueframe.ui.editor

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trueframe.core.annotation.AnnotationShape
import com.example.trueframe.core.video.FrameMath
import com.example.trueframe.data.AnnotationDao
import com.example.trueframe.data.AnnotationEntity
import com.example.trueframe.data.AnnotationJson
import com.example.trueframe.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class AnnotationItem(val id: Long, val frameIndex: Int, val shape: AnnotationShape)

data class EditorUiState(
    val videoUri: String? = null,
    val projectName: String = "",
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
    val frameIntervalMs: Float get() = FrameMath.intervalMs(frameRate)
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val annotationDao: AnnotationDao,
) : ViewModel() {

    private var projectId: Long = -1L
    private var observeAnnotationsJob: Job? = null

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    @Volatile private var dragUntilMs = 0L
    private val DRAG_LATCH_MS = 2_000L

    private var spawnCounter = 0

    private val isDragging: Boolean
        get() = System.currentTimeMillis() < dragUntilMs

    fun onDragStarted() { dragUntilMs = Long.MAX_VALUE }
    fun onDragCancelled() { dragUntilMs = 0L }

    fun initialize(id: Long) {
        if (projectId == id) return
        projectId = id

        _uiState.value = EditorUiState()
        spawnCounter = 0

        viewModelScope.launch {
            val project = projectRepository.getById(projectId)
            if (project != null) {
                val proxyUri = project.proxyUri
                val validProxy = if (proxyUri != null) {
                    val cleanPath = proxyUri.removePrefix("file://")
                    val file = File(cleanPath)
                    if (file.exists() && file.length() > 0) proxyUri else null
                } else null

                val uri = validProxy ?: project.videoUri
                _uiState.update {
                    it.copy(
                        videoUri = uri,
                        projectName = project.name,
                        rotationDegrees = project.rotationDegrees,
                    )
                }
            } else {
                _uiState.update { it.copy(error = "Project not found") }
            }
        }

        observeAnnotations()
    }

    private fun observeAnnotations() {
        observeAnnotationsJob?.cancel()
        observeAnnotationsJob = viewModelScope.launch {
            annotationDao.observeForProject(projectId).collect { entities ->
                if (isDragging) return@collect
                val items = entities.mapNotNull { e ->
                    AnnotationJson.deserialize(e.serializedData)?.let { AnnotationItem(e.id, e.frameIndex, it) }
                }
                _uiState.update { current ->
                    val sel = current.selectedAnnotationIndex?.takeIf { it in items.indices }
                    current.copy(annotations = items, selectedAnnotationIndex = sel)
                }
            }
        }
    }

    fun updatePlayerState(currentTimeMs: Long, totalDurationMs: Long, isPlaying: Boolean, frameRate: Float = 30f) {
        val frameIdx = FrameMath.frameForMs(currentTimeMs, frameRate)
        _uiState.update { current ->
            if (current.frameIndex == frameIdx && current.frameRate == frameRate && current.isPlaying == isPlaying) {
                current
            } else {
                current.copy(
                    isPlaying = isPlaying,
                    frameRate = frameRate,
                    frameIndex = frameIdx,
                )
            }
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
        val i = spawnCounter++
        // Golden-angle spiral: never repeats, stays near centre, spreads outward.
        val angle = i * 2.39996323f                       // ~137.5° in radians
        val radius = 0.02f + 0.012f * sqrt(i.toFloat())
        return Offset(
            (radius * cos(angle)).coerceIn(-0.25f, 0.25f),
            (radius * sin(angle)).coerceIn(-0.25f, 0.25f),
        )
    }

    private fun Offset.clampNorm(): Offset = Offset(x.coerceIn(0.05f, 0.95f), y.coerceIn(0.05f, 0.95f))

    fun addLine() {
        val o = spawnOffset()
        val start = (Offset(0.3f, 0.5f) + o).clampNorm()
        val end = (Offset(0.7f, 0.5f) + o).clampNorm()
        saveNewShape(AnnotationShape.Line(start, end), "line")
    }

    fun addAngle() {
        val o = spawnOffset()
        val start = (Offset(0.3f, 0.4f) + o).clampNorm()
        val center = (Offset(0.5f, 0.5f) + o).clampNorm()
        val end = (Offset(0.7f, 0.4f) + o).clampNorm()
        val angle = AnnotationShape.Angle(start, center, end)
        saveNewShape(angle, "angle")
    }

    fun addCircle() {
        val o = spawnOffset()
        val center = (Offset(0.5f, 0.5f) + o).clampNorm()
        val circle = AnnotationShape.Circle(center, 0.15f)
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
        dragUntilMs = System.currentTimeMillis() + DRAG_LATCH_MS
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
        dragUntilMs = System.currentTimeMillis() + DRAG_LATCH_MS
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
        dragUntilMs = 0L
        val item = shapeIndex?.let { _uiState.value.annotations.getOrNull(it) }
        viewModelScope.launch {
            if (item != null) {
                annotationDao.update(
                    AnnotationEntity(
                        id = item.id,
                        projectId = projectId,
                        frameIndex = item.frameIndex,
                        shapeType = item.shape.typeName(),
                        serializedData = AnnotationJson.serialize(item.shape),
                    )
                )
            }
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
