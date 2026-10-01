package com.example.trueframe.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.trueframe.core.annotation.AnnotationShape
import com.example.trueframe.core.annotation.SpeedMath
import com.example.trueframe.core.video.FrameMath
import com.example.trueframe.core.video.FrameRateSource
import com.example.trueframe.data.AnnotationDao
import com.example.trueframe.data.AnnotationEntity
import com.example.trueframe.data.AnnotationJson
import com.example.trueframe.data.ProjectEntity
import com.example.trueframe.data.repository.ProjectRepository
import com.example.trueframe.di.ApplicationScope
import com.example.trueframe.di.VideoMetadataProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

data class AnnotationItem(val id: Long, val frameIndex: Int, val shape: AnnotationShape)

/** Text add/edit dialog. [editingId] is null when adding a new label. */
data class TextDialogState(val editingId: Long?, val initialText: String)

enum class SpeedStep { REFERENCE, LENGTH, BALL_START, BALL_END, RESULT }

data class SpeedMarker(val position: Offset, val frameIndex: Int)

/** Session-only calibration, reused via "Use previous reference". */
data class SpeedCalibration(val referenceStart: Offset, val referenceEnd: Offset, val knownInches: Float)

/** Temporary Speed-mode state. Never saved to Room and never exported. */
data class SpeedState(
    val step: SpeedStep = SpeedStep.REFERENCE,
    val referenceStart: Offset = Offset(0.3f, 0.5f),
    val referenceEnd: Offset = Offset(0.7f, 0.5f),
    val knownInches: Float? = null,
    val markerA: SpeedMarker? = null,
    val markerB: SpeedMarker? = null,
    val fps: Float = FrameMath.ASSUMED_FPS,
    /** False when the fps was only assumed; the user must pick a value before a result is shown. */
    val fpsConfirmed: Boolean = true,
    val canUsePreviousReference: Boolean = false,
    val error: String? = null,
)

data class EditorUiState(
    val videoUri: String? = null,
    val projectName: String = "",
    val frameIndex: Int = 0,
    val frameRate: Float = FrameMath.ASSUMED_FPS,
    val frameRateSource: FrameRateSource = FrameRateSource.ASSUMED,
    /** `METADATA_KEY_CAPTURE_FRAMERATE`, when the file reports one. */
    val captureFps: Float? = null,
    val currentTimeMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val rotationDegrees: Int = 0,
    val annotations: List<AnnotationItem> = emptyList(),
    val selectedAnnotationId: Long? = null,
    val loadError: String? = null,
    val userMessage: String? = null,
    /** Source frame size in pixels, pixel-aspect corrected (as displayed). 0 until known. */
    val sourceWidth: Float = 0f,
    val sourceHeight: Float = 0f,
    val showGrid: Boolean = false,
    val ghostFrameIndex: Int? = null,
    val ghostOpacity: Float = DEFAULT_GHOST_OPACITY,
    val textDialog: TextDialogState? = null,
    val speed: SpeedState? = null,
    val speedCalibration: SpeedCalibration? = null,
) {
    val frameRateAssumed: Boolean get() = frameRateSource == FrameRateSource.ASSUMED

    val selectedAnnotationIndex: Int?
        get() = selectedAnnotationId?.let { id -> annotations.indexOfFirst { it.id == id }.takeIf { it >= 0 } }

    val selectedAnnotation: AnnotationItem?
        get() = selectedAnnotationId?.let { id -> annotations.firstOrNull { it.id == id } }

    val isGhostOn: Boolean get() = ghostFrameIndex != null

    /** Live speed result for the current Speed-mode inputs, or null if inputs are incomplete. */
    val speedResult: SpeedMath.Result?
        get() {
            val s = speed ?: return null
            val a = s.markerA ?: return null
            val b = s.markerB ?: return null
            val inches = s.knownInches ?: return null
            if (sourceWidth <= 0f || sourceHeight <= 0f) return null
            return SpeedMath.calculate(
                SpeedMath.Input(
                    referenceStart = s.referenceStart,
                    referenceEnd = s.referenceEnd,
                    knownInches = inches,
                    ballA = a.position,
                    frameA = a.frameIndex,
                    ballB = b.position,
                    frameB = b.frameIndex,
                    fps = s.fps,
                    rawWidth = sourceWidth,
                    rawHeight = sourceHeight,
                )
            )
        }

    @Suppress("unused")
    val frameIntervalMs: Float get() = FrameMath.intervalMs(frameRate)

    companion object {
        const val DEFAULT_GHOST_OPACITY = 0.4f
        const val MIN_GHOST_OPACITY = 0.2f
        const val MAX_GHOST_OPACITY = 0.7f
    }
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    private val projectRepository: ProjectRepository,
    private val annotationDao: AnnotationDao,
    private val videoMetadataProbe: VideoMetadataProbe,
    @ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private var projectId: Long = -1L
    private var observeAnnotationsJob: Job? = null
    private var probeJob: Job? = null

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    // Held here (not in a composable) so it survives configuration changes. Session-only.
    private val _ghostBitmap = MutableStateFlow<Bitmap?>(null)
    val ghostBitmap: StateFlow<Bitmap?> = _ghostBitmap.asStateFlow()

    @Volatile private var dragUntilMs = 0L
    private val DRAG_LATCH_MS = 2_000L

    private var spawnCounter = 0
    private var playerFps: Float? = null
    private var computedFps: Float? = null

    private val isDragging: Boolean
        get() = System.currentTimeMillis() < dragUntilMs

    fun onDragStarted() { dragUntilMs = Long.MAX_VALUE }
    fun onDragCancelled() { dragUntilMs = 0L }

    fun initialize(id: Long) {
        if (projectId == id) return
        projectId = id

        replaceGhostBitmap(null)
        _uiState.value = EditorUiState()
        spawnCounter = 0
        playerFps = null
        computedFps = null

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
                        currentTimeMs = project.lastPositionMs,
                    )
                }
                loadFrameRates(project)
            } else {
                _uiState.update { it.copy(loadError = "Project not found") }
            }
        }

        observeAnnotations()
    }

    /**
     * Frame-rate metadata comes from the **original** file: the remuxed proxy loses the capture-rate
     * tag (`com.android.capture.fps`) that slow-motion clips rely on. Values are read at import and
     * stored on the project; older projects are probed once here and backfilled.
     */
    private fun loadFrameRates(project: ProjectEntity) {
        probeJob?.cancel()
        if (project.frameRate != null || project.captureFps != null) {
            computedFps = project.frameRate
            _uiState.update { it.copy(captureFps = project.captureFps) }
            applyFrameRate()
            return
        }
        probeJob = viewModelScope.launch {
            suspend fun probe(uri: String) = runCatching { videoMetadataProbe.probe(uri) }.getOrNull()
                ?.takeIf { it.computedFps != null || it.captureFps != null }
            val result = probe(project.videoUri)
                ?: project.proxyUri?.let { probe(it) }
                ?: return@launch
            computedFps = result.computedFps
            _uiState.update { it.copy(captureFps = result.captureFps) }
            applyFrameRate()
            if (result.computedFps != null || result.captureFps != null) {
                projectRepository.updateFrameRates(project.id, result.computedFps, result.captureFps)
            }
        }
    }

    /**
     * Called when the player reports its video format. Null or non-positive means the player
     * didn't report a rate; that never overrides a rate it reported earlier.
     */
    fun onPlayerFrameRate(fps: Float?) {
        fps?.takeIf { it > 0f }?.let { playerFps = it }
        applyFrameRate()
    }

    private fun applyFrameRate() {
        val resolved = FrameMath.resolveFrameRate(playerFps, computedFps)
        _uiState.update { current ->
            current.copy(
                frameRate = resolved.fps,
                frameRateSource = resolved.source,
                frameIndex = FrameMath.frameForMs(current.currentTimeMs, resolved.fps),
            )
        }
    }

    /**
     * Persists the last playback position. Runs in the application scope because this is called
     * while the screen is being disposed, right before the ViewModel (and viewModelScope) is cleared.
     */
    fun saveLastPosition(positionMs: Long) {
        val id = projectId
        if (id <= 0L || positionMs < 0L) return
        applicationScope.launch {
            projectRepository.updateLastPosition(id, positionMs)
        }
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
                    val sel = current.selectedAnnotationId?.takeIf { id -> items.any { it.id == id } }
                    current.copy(annotations = items, selectedAnnotationId = sel)
                }
            }
        }
    }

    fun updatePlayerState(currentTimeMs: Long, totalDurationMs: Long, isPlaying: Boolean) {
        _uiState.update { current ->
            val frameIdx = FrameMath.frameForMs(currentTimeMs, current.frameRate)
            if (current.frameIndex == frameIdx && current.isPlaying == isPlaying && current.currentTimeMs == currentTimeMs && current.totalDurationMs == totalDurationMs) {
                current
            } else {
                current.copy(
                    currentTimeMs = currentTimeMs,
                    totalDurationMs = totalDurationMs,
                    isPlaying = isPlaying,
                    frameIndex = frameIdx,
                )
            }
        }
    }

    fun setSourceSize(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        _uiState.update { if (it.sourceWidth == width && it.sourceHeight == height) it else it.copy(sourceWidth = width, sourceHeight = height) }
    }

    fun rotateVideo() {
        val next = (_uiState.value.rotationDegrees + 90) % 360
        _uiState.update { it.copy(rotationDegrees = next) }
        val id = projectId
        viewModelScope.launch {
            projectRepository.updateRotation(id, next)
        }
    }

    fun renameProject(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        _uiState.update { it.copy(projectName = trimmed) }
        val id = projectId
        viewModelScope.launch {
            projectRepository.updateName(id, trimmed)
        }
    }

    fun selectAnnotation(id: Long?) {
        _uiState.update { it.copy(selectedAnnotationId = id) }
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
        saveNewShape(AnnotationShape.Line(start, end))
    }

    fun addAngle() {
        val o = spawnOffset()
        val start = (Offset(0.3f, 0.4f) + o).clampNorm()
        val center = (Offset(0.5f, 0.5f) + o).clampNorm()
        val end = (Offset(0.7f, 0.4f) + o).clampNorm()
        saveNewShape(AnnotationShape.Angle(start, center, end))
    }

    fun addCircle() {
        val o = spawnOffset()
        val center = (Offset(0.5f, 0.5f) + o).clampNorm()
        saveNewShape(AnnotationShape.Circle(center, 0.15f))
    }

    /** Spawns an arrow pointing left to right, tail at [AnnotationShape.Arrow.start]. */
    fun addArrow() {
        val o = spawnOffset()
        val start = (Offset(0.3f, 0.5f) + o).clampNorm()
        val end = (Offset(0.7f, 0.5f) + o).clampNorm()
        saveNewShape(AnnotationShape.Arrow(start, end))
    }

    fun openAddTextDialog() {
        _uiState.update { it.copy(textDialog = TextDialogState(editingId = null, initialText = "")) }
    }

    /** Reopens the dialog pre-filled for an existing Text annotation. */
    fun openEditTextDialog(id: Long) {
        val item = _uiState.value.annotations.firstOrNull { it.id == id } ?: return
        val shape = item.shape as? AnnotationShape.Text ?: return
        _uiState.update { it.copy(textDialog = TextDialogState(editingId = id, initialText = shape.text)) }
    }

    fun dismissTextDialog() {
        _uiState.update { it.copy(textDialog = null) }
    }

    /** Saves the dialog's text. Empty (after trimming) input is rejected and keeps the dialog open. */
    fun submitTextDialog(input: String) {
        val dialog = _uiState.value.textDialog ?: return
        val text = AnnotationShape.Text.sanitize(input) ?: return
        _uiState.update { it.copy(textDialog = null) }
        val editingId = dialog.editingId
        if (editingId == null) {
            val o = spawnOffset()
            saveNewShape(AnnotationShape.Text((Offset(0.42f, 0.46f) + o).clampNorm(), text))
            return
        }
        val items = _uiState.value.annotations
        val idx = items.indexOfFirst { it.id == editingId }
        if (idx < 0) return
        val item = items[idx]
        val shape = item.shape as? AnnotationShape.Text ?: return
        val updated = item.copy(shape = shape.copy(text = text))
        _uiState.update { it.copy(annotations = items.toMutableList().also { list -> list[idx] = updated }) }
        viewModelScope.launch { annotationDao.update(updated.toEntity()) }
    }

    private fun saveNewShape(shape: AnnotationShape, frameIndex: Int = _uiState.value.frameIndex) {
        viewModelScope.launch {
            val entity = AnnotationEntity(
                projectId = projectId,
                frameIndex = frameIndex,
                shapeType = shape.typeName(),
                serializedData = AnnotationJson.serialize(shape),
            )
            annotationDao.insert(entity)
        }
    }

    fun offsetShape(id: Long, delta: Offset) {
        dragUntilMs = System.currentTimeMillis() + DRAG_LATCH_MS
        val items = _uiState.value.annotations.toMutableList()
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return

        val moved = when (val s = items[idx].shape) {
            is AnnotationShape.Line -> s.copy(start = s.start + delta, end = s.end + delta)
            is AnnotationShape.Arrow -> s.copy(start = s.start + delta, end = s.end + delta)
            is AnnotationShape.Angle -> s.copy(
                start = s.start + delta,
                center = s.center + delta,
                end = s.end + delta
            )
            is AnnotationShape.Circle -> s.copy(center = s.center + delta)
            is AnnotationShape.Text -> s.copy(anchor = s.anchor + delta)
        }
        items[idx] = items[idx].copy(shape = moved)
        _uiState.update { it.copy(annotations = items, selectedAnnotationId = id) }
    }

    fun updateShapeHandle(id: Long, handleIndex: Int, newOffset: Offset, aspectCorrection: Float = 1.0f) {
        dragUntilMs = System.currentTimeMillis() + DRAG_LATCH_MS
        val items = _uiState.value.annotations.toMutableList()
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return

        val updatedShape = when (val s = items[idx].shape) {
            is AnnotationShape.Line -> when (handleIndex) {
                0 -> s.copy(start = newOffset)
                1 -> s.copy(end = newOffset)
                else -> s
            }
            is AnnotationShape.Arrow -> when (handleIndex) {
                0 -> s.copy(start = newOffset)
                1 -> s.copy(end = newOffset)
                else -> s
            }
            is AnnotationShape.Angle -> when (handleIndex) {
                0 -> s.copy(start = newOffset)
                1 -> s.copy(center = newOffset)
                2 -> s.copy(end = newOffset)
                else -> s
            }
            is AnnotationShape.Circle -> when (handleIndex) {
                0 -> s.copy(center = newOffset)
                1 -> {
                    val dx = (newOffset.x - s.center.x).toDouble()
                    val dy = ((newOffset.y - s.center.y) * aspectCorrection).toDouble()
                    val newRadius = hypot(dx, dy).toFloat()
                    s.copy(radius = newRadius.coerceAtLeast(0.02f))
                }
                else -> s
            }
            is AnnotationShape.Text -> when (handleIndex) {
                0 -> s.copy(anchor = newOffset)
                else -> s
            }
        }
        items[idx] = items[idx].copy(shape = updatedShape)
        _uiState.update { it.copy(annotations = items, selectedAnnotationId = id) }
    }

    fun persistAnnotationsOnDragEnd(id: Long?) {
        dragUntilMs = 0L
        val item = id?.let { target -> _uiState.value.annotations.firstOrNull { it.id == target } }
        viewModelScope.launch {
            if (item != null) annotationDao.update(item.toEntity())
        }
    }

    private fun AnnotationItem.toEntity() = AnnotationEntity(
        id = id,
        projectId = projectId,
        frameIndex = frameIndex,
        shapeType = shape.typeName(),
        serializedData = AnnotationJson.serialize(shape),
    )

    private fun AnnotationShape.typeName(): String = when (this) {
        is AnnotationShape.Line -> "line"
        is AnnotationShape.Angle -> "angle"
        is AnnotationShape.Circle -> "circle"
        is AnnotationShape.Arrow -> "arrow"
        is AnnotationShape.Text -> "text"
    }

    fun deleteSelectedAnnotation() {
        val id = _uiState.value.selectedAnnotationId ?: return
        if (_uiState.value.annotations.none { it.id == id }) return
        _uiState.update { it.copy(selectedAnnotationId = null) }
        viewModelScope.launch {
            annotationDao.deleteById(id)
        }
    }

    fun clearAllAnnotations() {
        _uiState.update { it.copy(selectedAnnotationId = null) }
        viewModelScope.launch {
            annotationDao.deleteAllForProject(projectId)
        }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    // ---------------------------------------------------------------- Grid

    fun toggleGrid() {
        _uiState.update { it.copy(showGrid = !it.showGrid) }
    }

    // ---------------------------------------------------------------- Ghost frame

    /** Captures [bitmap] (the currently displayed frame) as the ghost and turns the ghost on. */
    fun captureGhost(bitmap: Bitmap?) {
        replaceGhostBitmap(bitmap)
        _uiState.update { it.copy(ghostFrameIndex = it.frameIndex) }
    }

    fun setGhostOpacity(opacity: Float) {
        val clamped = opacity.coerceIn(EditorUiState.MIN_GHOST_OPACITY, EditorUiState.MAX_GHOST_OPACITY)
        _uiState.update { it.copy(ghostOpacity = clamped) }
    }

    fun turnOffGhost() {
        replaceGhostBitmap(null)
        _uiState.update { it.copy(ghostFrameIndex = null) }
    }

    private fun replaceGhostBitmap(bitmap: Bitmap?) {
        val old = _ghostBitmap.value
        _ghostBitmap.value = bitmap
        if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
    }

    override fun onCleared() {
        replaceGhostBitmap(null)
        super.onCleared()
    }

    // ---------------------------------------------------------------- Speed calculator

    fun enterSpeedMode() {
        val s = _uiState.value
        val calibration = s.speedCalibration
        val fps = FrameMath.speedFps(s.frameRate, s.captureFps)
        val confirmed = !(s.frameRateAssumed && s.captureFps == null)
        _uiState.update {
            it.copy(
                selectedAnnotationId = null,
                speed = SpeedState(
                    step = SpeedStep.REFERENCE,
                    referenceStart = calibration?.referenceStart ?: Offset(0.3f, 0.5f),
                    referenceEnd = calibration?.referenceEnd ?: Offset(0.7f, 0.5f),
                    knownInches = calibration?.knownInches,
                    fps = fps,
                    fpsConfirmed = confirmed,
                    canUsePreviousReference = calibration != null,
                ),
            )
        }
    }

    fun exitSpeedMode() {
        _uiState.update { it.copy(speed = null) }
    }

    /** Skips steps 1 and 2 using the calibration from earlier in this session. */
    fun usePreviousReference() {
        val calibration = _uiState.value.speedCalibration ?: return
        updateSpeed {
            it.copy(
                step = SpeedStep.BALL_START,
                referenceStart = calibration.referenceStart,
                referenceEnd = calibration.referenceEnd,
                knownInches = calibration.knownInches,
                canUsePreviousReference = false,
                error = null,
            )
        }
    }

    fun moveReferenceHandle(handleIndex: Int, position: Offset) {
        updateSpeed {
            when (handleIndex) {
                0 -> it.copy(referenceStart = position, error = null)
                1 -> it.copy(referenceEnd = position, error = null)
                else -> it
            }
        }
    }

    /** Moves one reference endpoint by [delta] (relative drag, so the end isn't hidden under the finger). */
    fun offsetReferenceHandle(handleIndex: Int, delta: Offset) {
        updateSpeed {
            when (handleIndex) {
                0 -> it.copy(referenceStart = (it.referenceStart + delta).clampUnit(), error = null)
                1 -> it.copy(referenceEnd = (it.referenceEnd + delta).clampUnit(), error = null)
                else -> it
            }
        }
    }

    /**
     * Moves the current step's ball marker (A in step 3, B in step 4) by [delta] and re-stamps it
     * with the current frame, so a marker can be dragged into place on whatever frame is shown.
     */
    fun offsetSpeedMarker(delta: Offset) {
        val frame = _uiState.value.frameIndex
        updateSpeed {
            when (it.step) {
                SpeedStep.BALL_START -> it.markerA?.let { m ->
                    it.copy(markerA = SpeedMarker((m.position + delta).clampUnit(), frame), error = null)
                } ?: it
                SpeedStep.BALL_END -> it.markerB?.let { m ->
                    it.copy(markerB = SpeedMarker((m.position + delta).clampUnit(), frame), error = null)
                } ?: it
                else -> it
            }
        }
    }

    private fun Offset.clampUnit(): Offset = Offset(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))

    fun offsetReference(delta: Offset) {
        updateSpeed { it.copy(referenceStart = it.referenceStart + delta, referenceEnd = it.referenceEnd + delta, error = null) }
    }

    /** Places marker A (step 3) or B (step 4) at [position] on the current frame. */
    fun placeSpeedMarker(position: Offset) {
        val frame = _uiState.value.frameIndex
        updateSpeed {
            when (it.step) {
                SpeedStep.BALL_START -> it.copy(markerA = SpeedMarker(position, frame), error = null)
                SpeedStep.BALL_END -> it.copy(markerB = SpeedMarker(position, frame), error = null)
                else -> it
            }
        }
    }

    /** Whether the wizard's Next button is enabled for the current step. */
    fun canAdvanceSpeed(state: SpeedState = _uiState.value.speed ?: SpeedState()): Boolean = when (state.step) {
        SpeedStep.REFERENCE -> true
        SpeedStep.LENGTH -> state.knownInches != null
        SpeedStep.BALL_START -> state.markerA != null
        SpeedStep.BALL_END -> state.markerB != null && state.markerA != null && state.markerB.frameIndex != state.markerA.frameIndex
        SpeedStep.RESULT -> false
    }

    fun speedNext() {
        val s = _uiState.value
        val speed = s.speed ?: return
        when (speed.step) {
            SpeedStep.REFERENCE -> {
                val refPx = SpeedMath.distancePx(speed.referenceStart, speed.referenceEnd, s.sourceWidth, s.sourceHeight)
                if (s.sourceWidth > 0f && refPx < SpeedMath.MIN_REFERENCE_PX) {
                    updateSpeed { it.copy(error = SpeedMath.errorMessage(SpeedMath.Result.ReferenceTooShort)) }
                } else {
                    updateSpeed { it.copy(step = SpeedStep.LENGTH, canUsePreviousReference = false, error = null) }
                }
            }
            SpeedStep.LENGTH -> speed.knownInches?.let { submitKnownLength(it.toString()) }
            SpeedStep.BALL_START -> if (canAdvanceSpeed(speed)) updateSpeed { it.copy(step = SpeedStep.BALL_END, error = null) }
            SpeedStep.BALL_END -> if (canAdvanceSpeed(speed)) updateSpeed { it.copy(step = SpeedStep.RESULT, error = null) }
            SpeedStep.RESULT -> Unit
        }
    }

    /** Step 2: validates the known length (1–600 in) and moves on to step 3. Returns false if invalid. */
    fun submitKnownLength(input: String): Boolean {
        val inches = input.trim().replace(',', '.').toFloatOrNull()
        if (inches == null || inches < SpeedMath.MIN_KNOWN_INCHES || inches > SpeedMath.MAX_KNOWN_INCHES) {
            updateSpeed { it.copy(error = SpeedMath.errorMessage(SpeedMath.Result.InvalidLength)) }
            return false
        }
        val speed = _uiState.value.speed ?: return false
        _uiState.update {
            it.copy(
                speedCalibration = SpeedCalibration(speed.referenceStart, speed.referenceEnd, inches),
                speed = speed.copy(step = SpeedStep.BALL_START, knownInches = inches, error = null),
            )
        }
        return true
    }

    fun speedBack() {
        updateSpeed {
            val prev = when (it.step) {
                SpeedStep.REFERENCE -> SpeedStep.REFERENCE
                SpeedStep.LENGTH -> SpeedStep.REFERENCE
                SpeedStep.BALL_START -> SpeedStep.LENGTH
                SpeedStep.BALL_END -> SpeedStep.BALL_START
                SpeedStep.RESULT -> SpeedStep.BALL_END
            }
            it.copy(step = prev, error = null)
        }
    }

    /** Returns to step 3, keeping the reference and known length. */
    fun redoBall() {
        updateSpeed { it.copy(step = SpeedStep.BALL_START, markerA = null, markerB = null, error = null) }
    }

    fun setSpeedFps(fps: Float) {
        if (!(fps > 0f)) return
        updateSpeed { it.copy(fps = fps, fpsConfirmed = true) }
    }

    /** Creates a persistent Text annotation with the speed at marker B's position, on B's frame. */
    fun addSpeedAsLabel() {
        val s = _uiState.value
        val speed = s.speed ?: return
        val b = speed.markerB ?: return
        if (!speed.fpsConfirmed) return
        val result = s.speedResult as? SpeedMath.Result.Speed ?: return
        val anchor = (b.position + Offset(0.02f, 0.02f)).clampNorm()
        saveNewShape(AnnotationShape.Text(anchor, formatMph(result.mph)), frameIndex = b.frameIndex)
        exitSpeedMode()
    }

    private inline fun updateSpeed(crossinline transform: (SpeedState) -> SpeedState) {
        _uiState.update { current -> current.speed?.let { current.copy(speed = transform(it)) } ?: current }
    }

    // ---------------------------------------------------------------- Export

    suspend fun exportFrameUri(context: Context): Uri? {
        val s = _uiState.value
        val videoUri = s.videoUri ?: return null
        val timeUs = FrameMath.msForFrame(s.frameIndex, s.frameRate) * 1000L
        val ghost = s.ghostFrameIndex?.let {
            FrameExporter.Ghost(timeUs = FrameMath.msForFrame(it, s.frameRate) * 1000L, opacity = s.ghostOpacity)
        }

        val uri = FrameExporter.exportAndShareFrame(
            context = context.applicationContext,
            videoUri = videoUri,
            timeUs = timeUs,
            rotationDegrees = s.rotationDegrees,
            annotations = s.annotations.map { it.shape },
            ghost = ghost,
        )
        if (uri == null) {
            _uiState.update { it.copy(userMessage = "Failed to export frame") }
        }
        return uri
    }

    companion object {
        fun formatMph(mph: Float): String = String.format(Locale.US, "%.1f mph", mph)
    }
}
