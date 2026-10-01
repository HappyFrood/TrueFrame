package com.example.trueframe.ui.editor

import androidx.compose.ui.geometry.Offset
import com.example.trueframe.core.annotation.AnnotationShape
import com.example.trueframe.core.annotation.SpeedMath
import com.example.trueframe.core.video.FrameMath
import com.example.trueframe.core.video.VideoProbe
import com.example.trueframe.data.AnnotationDao
import com.example.trueframe.data.AnnotationEntity
import com.example.trueframe.data.AnnotationJson
import com.example.trueframe.data.ProjectDao
import com.example.trueframe.data.ProjectEntity
import com.example.trueframe.data.repository.ProjectRepository
import com.example.trueframe.di.VideoMetadataProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var projectDao: FakeProjectDao
    private lateinit var annotationDao: FakeAnnotationDao
    private lateinit var probe: FakeProbe

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        projectDao = FakeProjectDao()
        annotationDao = FakeAnnotationDao()
        probe = FakeProbe()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(appScope: CoroutineScope = CoroutineScope(dispatcher)) =
        EditorViewModel(ProjectRepository(projectDao), annotationDao, probe, appScope)

    private fun initializedViewModel(appScope: CoroutineScope = CoroutineScope(dispatcher)) =
        newViewModel(appScope).also { it.initialize(PROJECT_ID) }

    private fun line(id: Long, frame: Int = 0) = AnnotationEntity(
        id = id, projectId = PROJECT_ID, frameIndex = frame, shapeType = "line",
        serializedData = AnnotationJson.serialize(AnnotationShape.Line(Offset(0.1f, 0.1f), Offset(0.9f, 0.9f))),
    )

    @Test
    fun updatePlayerState_updatesCurrentTimeMsInUiState() {
        val viewModel = newViewModel()

        viewModel.updatePlayerState(currentTimeMs = 1234L, totalDurationMs = 5000L, isPlaying = false)

        assertEquals(1234L, viewModel.uiState.value.currentTimeMs)
        assertEquals(5000L, viewModel.uiState.value.totalDurationMs)
    }

    // ---------------------------------------------------------------- B1

    @Test
    fun selection_survivesReEmit_andDeleteRemovesTheSelectedShape() {
        annotationDao.seed(line(10))
        val vm = initializedViewModel()
        vm.selectAnnotation(10)

        // Insert a shape that sorts *before* the selected one; indices shift on re-emit.
        annotationDao.seed(line(5))
        assertEquals(listOf(5L, 10L), vm.uiState.value.annotations.map { it.id })
        assertEquals(10L, vm.uiState.value.selectedAnnotationId)
        assertEquals(1, vm.uiState.value.selectedAnnotationIndex)

        vm.deleteSelectedAnnotation()

        assertEquals(listOf(10L), annotationDao.deletedIds)
        assertEquals(listOf(5L), vm.uiState.value.annotations.map { it.id })
        assertNull(vm.uiState.value.selectedAnnotationId)
    }

    @Test
    fun selection_clearedWhenSelectedIdDisappears() {
        annotationDao.seed(line(1), line(2))
        val vm = initializedViewModel()
        vm.selectAnnotation(2)

        annotationDao.remove(2)

        assertNull(vm.uiState.value.selectedAnnotationId)
    }

    @Test
    fun dragPersistsTheShapeById() {
        annotationDao.seed(line(3), line(7))
        val vm = initializedViewModel()

        vm.onDragStarted()
        vm.offsetShape(7, Offset(0.05f, 0f))
        vm.persistAnnotationsOnDragEnd(7)

        val updated = annotationDao.updated.single()
        assertEquals(7L, updated.id)
        val shape = AnnotationJson.deserialize(updated.serializedData) as AnnotationShape.Line
        assertEquals(0.15f, shape.start.x, 1e-4f)
    }

    // ---------------------------------------------------------------- B2 / B3

    @Test
    fun rotateAndSavePosition_backToBack_bothPersist() = runTest(dispatcher) {
        val vm = initializedViewModel(appScope = this)

        vm.rotateVideo()
        vm.saveLastPosition(4321L)
        advanceUntilIdle()

        val project = projectDao.project!!
        assertEquals(90, project.rotationDegrees)
        assertEquals(4321L, project.lastPositionMs)
    }

    @Test
    fun savePosition_runsInApplicationScope_notViewModelScope() = runTest {
        val appDispatcher = StandardTestDispatcher(testScheduler)
        val vm = initializedViewModel(appScope = CoroutineScope(appDispatcher))

        vm.saveLastPosition(999L)
        assertEquals(0L, projectDao.project!!.lastPositionMs) // queued on the app scope
        advanceUntilIdle()

        assertEquals(999L, projectDao.project!!.lastPositionMs)
    }

    @Test
    fun rename_updatesOnlyTheName() {
        val vm = initializedViewModel()
        vm.rotateVideo()
        vm.renameProject("  Swing 3  ")

        assertEquals("Swing 3", projectDao.project!!.name)
        assertEquals(90, projectDao.project!!.rotationDegrees)
    }

    // ---------------------------------------------------------------- B5

    @Test
    fun frameRate_usesComputedFpsWhenPlayerReportsNone() {
        probe.result = VideoProbe.Result(computedFps = 240f, captureFps = null)
        val vm = initializedViewModel()

        vm.onPlayerFrameRate(null)

        assertEquals(240f, vm.uiState.value.frameRate)
        assertFalse(vm.uiState.value.frameRateAssumed)
    }

    @Test
    fun frameRate_assumedWhenNothingReported() {
        val vm = initializedViewModel()
        vm.onPlayerFrameRate(null)

        assertEquals(FrameMath.ASSUMED_FPS, vm.uiState.value.frameRate)
        assertTrue(vm.uiState.value.frameRateAssumed)
    }

    @Test
    fun frameRate_laterNullReportDoesNotOverridePlayerValue() {
        val vm = initializedViewModel()
        vm.onPlayerFrameRate(120f)
        vm.onPlayerFrameRate(null)

        assertEquals(120f, vm.uiState.value.frameRate)
    }

    // ---------------------------------------------------------------- Arrow & Text

    @Test
    fun addArrow_insertsArrowShape() {
        val vm = initializedViewModel()
        vm.addArrow()

        val entity = annotationDao.all().single()
        assertEquals("arrow", entity.shapeType)
        val arrow = AnnotationJson.deserialize(entity.serializedData) as AnnotationShape.Arrow
        assertTrue("points left to right", arrow.end.x > arrow.start.x)
        assertEquals(1, vm.uiState.value.annotations.size)
    }

    @Test
    fun addText_trimsAndRejectsEmpty() {
        val vm = initializedViewModel()

        vm.openAddTextDialog()
        vm.submitTextDialog("    ")
        assertNotNull("empty text keeps the dialog open", vm.uiState.value.textDialog)
        assertTrue(annotationDao.all().isEmpty())

        vm.submitTextDialog("  Hips open  ")
        assertNull(vm.uiState.value.textDialog)
        val entity = annotationDao.all().single()
        assertEquals("text", entity.shapeType)
        assertEquals("Hips open", (AnnotationJson.deserialize(entity.serializedData) as AnnotationShape.Text).text)
    }

    @Test
    fun editText_updatesThroughDao() {
        val vm = initializedViewModel()
        vm.openAddTextDialog()
        vm.submitTextDialog("Before")
        val id = annotationDao.all().single().id

        vm.openEditTextDialog(id)
        assertEquals(TextDialogState(editingId = id, initialText = "Before"), vm.uiState.value.textDialog)
        vm.submitTextDialog("After")

        val updated = annotationDao.all().single()
        assertEquals(id, updated.id)
        assertEquals("After", (AnnotationJson.deserialize(updated.serializedData) as AnnotationShape.Text).text)
    }

    // ---------------------------------------------------------------- Ghost & grid

    @Test
    fun ghost_onOffAndOpacityClamp() {
        val vm = initializedViewModel()
        vm.onPlayerFrameRate(30f)
        vm.updatePlayerState(FrameMath.msForFrame(12, 30f), 10_000L, false)

        vm.captureGhost(null)
        assertEquals(12, vm.uiState.value.ghostFrameIndex)
        assertEquals(EditorUiState.DEFAULT_GHOST_OPACITY, vm.uiState.value.ghostOpacity)

        vm.setGhostOpacity(0.95f)
        assertEquals(EditorUiState.MAX_GHOST_OPACITY, vm.uiState.value.ghostOpacity)
        vm.setGhostOpacity(0.05f)
        assertEquals(EditorUiState.MIN_GHOST_OPACITY, vm.uiState.value.ghostOpacity)

        vm.turnOffGhost()
        assertNull(vm.uiState.value.ghostFrameIndex)
        assertNull(vm.ghostBitmap.value)
    }

    @Test
    fun grid_toggles() {
        val vm = initializedViewModel()
        vm.toggleGrid()
        assertTrue(vm.uiState.value.showGrid)
        vm.toggleGrid()
        assertFalse(vm.uiState.value.showGrid)
    }

    // ---------------------------------------------------------------- Speed

    @Test
    fun speedMode_stepTransitions_resultAndLabel() {
        val vm = initializedViewModel()
        vm.onPlayerFrameRate(240f)
        vm.setSourceSize(1920f, 1080f)

        vm.enterSpeedMode()
        assertEquals(SpeedStep.REFERENCE, vm.uiState.value.speed?.step)
        assertTrue(vm.uiState.value.speed!!.fpsConfirmed)

        vm.speedNext()
        assertEquals(SpeedStep.LENGTH, vm.uiState.value.speed?.step)
        assertFalse(vm.submitKnownLength("0.5"))
        assertEquals(SpeedStep.LENGTH, vm.uiState.value.speed?.step)
        assertTrue(vm.submitKnownLength("45"))
        assertEquals(SpeedStep.BALL_START, vm.uiState.value.speed?.step)

        assertFalse(vm.canAdvanceSpeed())
        vm.placeSpeedMarker(Offset(0.1f, 0.5f)) // frame 0
        vm.speedNext()
        assertEquals(SpeedStep.BALL_END, vm.uiState.value.speed?.step)

        vm.placeSpeedMarker(Offset(0.5f, 0.5f)) // still frame 0
        assertFalse("B must be on a different frame", vm.canAdvanceSpeed())
        vm.updatePlayerState(FrameMath.msForFrame(10, 240f), 10_000L, false)
        vm.placeSpeedMarker(Offset(0.5f, 0.5f))
        vm.speedNext()
        assertEquals(SpeedStep.RESULT, vm.uiState.value.speed?.step)

        // Reference 0.4 × 1920 = 768 px = 45 in; ball 768 px over 10 frames @ 240 fps.
        val result = vm.uiState.value.speedResult as SpeedMath.Result.Speed
        assertEquals(61.4f, result.mph, 0.1f)

        vm.setSpeedFps(120f)
        assertEquals(30.7f, (vm.uiState.value.speedResult as SpeedMath.Result.Speed).mph, 0.1f)

        vm.speedBack()
        assertEquals(SpeedStep.BALL_END, vm.uiState.value.speed?.step)
        vm.speedNext()

        vm.addSpeedAsLabel()
        assertNull(vm.uiState.value.speed)
        val label = annotationDao.all().single()
        assertEquals(10, label.frameIndex)
        assertEquals("30.7 mph", (AnnotationJson.deserialize(label.serializedData) as AnnotationShape.Text).text)

        // Calibration is kept for the session.
        vm.enterSpeedMode()
        assertTrue(vm.uiState.value.speed!!.canUsePreviousReference)
        vm.usePreviousReference()
        assertEquals(SpeedStep.BALL_START, vm.uiState.value.speed?.step)
        assertEquals(45f, vm.uiState.value.speed?.knownInches)
    }

    @Test
    fun speedMode_redoBallKeepsReference() {
        val vm = initializedViewModel()
        vm.onPlayerFrameRate(60f)
        vm.setSourceSize(1920f, 1080f)
        vm.enterSpeedMode()
        vm.speedNext()
        vm.submitKnownLength("33")
        vm.placeSpeedMarker(Offset(0.2f, 0.5f))

        vm.redoBall()

        val speed = vm.uiState.value.speed!!
        assertEquals(SpeedStep.BALL_START, speed.step)
        assertNull(speed.markerA)
        assertEquals(33f, speed.knownInches)
    }

    @Test
    fun speedMode_markersAndReferenceMoveRelatively() {
        val vm = initializedViewModel()
        vm.onPlayerFrameRate(240f)
        vm.setSourceSize(1920f, 1080f)
        vm.enterSpeedMode()

        vm.offsetReferenceHandle(1, Offset(0.1f, 0f))
        assertEquals(0.8f, vm.uiState.value.speed!!.referenceEnd.x, 1e-4f)
        assertEquals(0.3f, vm.uiState.value.speed!!.referenceStart.x, 1e-4f)

        vm.speedNext()
        vm.submitKnownLength("45")
        vm.placeSpeedMarker(Offset(0.2f, 0.5f)) // frame 0
        vm.updatePlayerState(FrameMath.msForFrame(4, 240f), 10_000L, false)
        vm.offsetSpeedMarker(Offset(0.05f, -0.1f))

        val a = vm.uiState.value.speed!!.markerA!!
        assertEquals(0.25f, a.position.x, 1e-4f)
        assertEquals(0.4f, a.position.y, 1e-4f)
        assertEquals("dragging re-stamps the marker with the shown frame", 4, a.frameIndex)
    }

    @Test
    fun frameRates_storedOnProjectAreUsedWithoutProbing() {
        projectDao.project = projectDao.project!!.copy(frameRate = 30f, captureFps = 240f)
        probe.result = VideoProbe.Result(computedFps = 99f, captureFps = 99f)
        val vm = initializedViewModel()
        vm.onPlayerFrameRate(null)

        assertEquals(30f, vm.uiState.value.frameRate)
        assertEquals(240f, vm.uiState.value.captureFps)
        assertEquals(0, probe.calls)
    }

    @Test
    fun frameRates_probedFromOriginalFileAndBackfilled() {
        projectDao.project = projectDao.project!!.copy(videoUri = "file:///original.mp4", proxyUri = "file:///proxy.mp4")
        probe.result = VideoProbe.Result(computedFps = 30f, captureFps = 240f)
        val vm = initializedViewModel()

        assertEquals(listOf("file:///original.mp4"), probe.uris)
        assertEquals(240f, vm.uiState.value.captureFps)
        assertEquals(30f, projectDao.project!!.frameRate)
        assertEquals(240f, projectDao.project!!.captureFps)
    }

    @Test
    fun speedMode_tooShortReferenceBlocksNext() {
        val vm = initializedViewModel()
        vm.setSourceSize(1920f, 1080f)
        vm.enterSpeedMode()
        vm.moveReferenceHandle(1, Offset(0.305f, 0.5f)) // ~10 px

        vm.speedNext()

        assertEquals(SpeedStep.REFERENCE, vm.uiState.value.speed?.step)
        assertEquals("Reference line is too short", vm.uiState.value.speed?.error)
    }

    @Test
    fun speedMode_fpsPrefillsFromCaptureRate_andUnconfirmedWhenAssumed() {
        probe.result = VideoProbe.Result(computedFps = null, captureFps = 240f)
        val vm = initializedViewModel()
        vm.onPlayerFrameRate(30f)
        vm.enterSpeedMode()
        assertEquals(240f, vm.uiState.value.speed!!.fps)
        assertTrue(vm.uiState.value.speed!!.fpsConfirmed)

        probe.result = VideoProbe.Result(computedFps = null, captureFps = null)
        projectDao.project = projectDao.project!!.copy(frameRate = null, captureFps = null) // undo the backfill
        val assumed = newViewModel().also { it.initialize(PROJECT_ID) }
        assumed.enterSpeedMode()
        assertFalse(assumed.uiState.value.speed!!.fpsConfirmed)
        assumed.setSpeedFps(240f)
        assertTrue(assumed.uiState.value.speed!!.fpsConfirmed)
    }

    private companion object {
        const val PROJECT_ID = 1L
    }
}

private class FakeProbe : VideoMetadataProbe {
    var result = VideoProbe.Result(computedFps = null, captureFps = null)
    val uris = mutableListOf<String>()
    val calls get() = uris.size
    override suspend fun probe(uri: String): VideoProbe.Result = result.also { uris += uri }
}

private class FakeProjectDao : ProjectDao {
    var project: ProjectEntity? = ProjectEntity(id = 1L, name = "Test", videoUri = "content://video")

    override fun observeAll(): Flow<List<ProjectEntity>> = flow { emit(listOfNotNull(project)) }
    override suspend fun getById(id: Long): ProjectEntity? = project?.takeIf { it.id == id }
    override suspend fun getAll(): List<ProjectEntity> = listOfNotNull(project)
    override suspend fun insert(project: ProjectEntity): Long = project.id.also { this.project = project }
    override suspend fun update(project: ProjectEntity) { this.project = project }
    override suspend fun updateLastPosition(id: Long, positionMs: Long, updatedAt: Long) {
        project = project?.takeIf { it.id == id }?.copy(lastPositionMs = positionMs, updatedAt = updatedAt) ?: project
    }
    override suspend fun updateRotation(id: Long, rotationDegrees: Int, updatedAt: Long) {
        project = project?.takeIf { it.id == id }?.copy(rotationDegrees = rotationDegrees, updatedAt = updatedAt) ?: project
    }
    override suspend fun updateName(id: Long, name: String, updatedAt: Long) {
        project = project?.takeIf { it.id == id }?.copy(name = name, updatedAt = updatedAt) ?: project
    }
    override suspend fun updateFrameRates(id: Long, frameRate: Float?, captureFps: Float?) {
        project = project?.takeIf { it.id == id }?.copy(frameRate = frameRate, captureFps = captureFps) ?: project
    }
    override suspend fun delete(project: ProjectEntity) { this.project = null }
}

/** In-memory AnnotationDao that re-emits (ordered by id, like Room) on every write. */
private class FakeAnnotationDao : AnnotationDao {
    private val rows = MutableStateFlow<List<AnnotationEntity>>(emptyList())
    private var nextId = 100L
    val deletedIds = mutableListOf<Long>()
    val updated = mutableListOf<AnnotationEntity>()

    fun all(): List<AnnotationEntity> = rows.value

    fun seed(vararg entities: AnnotationEntity) {
        rows.value = (rows.value + entities).sortedBy { it.id }
    }

    fun remove(id: Long) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override fun observeForFrame(projectId: Long, frameIndex: Int): Flow<List<AnnotationEntity>> =
        rows.map { list -> list.filter { it.projectId == projectId && it.frameIndex == frameIndex } }

    override fun observeForProject(projectId: Long): Flow<List<AnnotationEntity>> =
        rows.map { list -> list.filter { it.projectId == projectId } }

    override suspend fun insert(annotation: AnnotationEntity): Long {
        val id = if (annotation.id != 0L) annotation.id else nextId++
        rows.value = (rows.value.filterNot { it.id == id } + annotation.copy(id = id)).sortedBy { it.id }
        return id
    }

    override suspend fun update(annotation: AnnotationEntity) {
        updated += annotation
        rows.value = rows.value.map { if (it.id == annotation.id) annotation else it }
    }

    override suspend fun delete(annotation: AnnotationEntity) = deleteById(annotation.id)

    override suspend fun deleteById(id: Long) {
        deletedIds += id
        remove(id)
    }

    override suspend fun deleteAllForFrame(projectId: Long, frameIndex: Int) {
        rows.value = rows.value.filterNot { it.projectId == projectId && it.frameIndex == frameIndex }
    }

    override suspend fun deleteAllForProject(projectId: Long) {
        rows.value = rows.value.filterNot { it.projectId == projectId }
    }
}
