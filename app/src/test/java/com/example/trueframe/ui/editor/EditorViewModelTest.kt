package com.example.trueframe.ui.editor

import com.example.trueframe.data.AnnotationDao
import com.example.trueframe.data.AnnotationEntity
import com.example.trueframe.data.ProjectDao
import com.example.trueframe.data.ProjectEntity
import com.example.trueframe.data.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorViewModelTest {

    @Test
    fun updatePlayerState_updatesCurrentTimeMsInUiState() {
        val fakeDao = FakeProjectDao()
        val fakeRepo = ProjectRepository(fakeDao)
        val fakeAnnotationDao = FakeAnnotationDao()
        val viewModel = EditorViewModel(fakeRepo, fakeAnnotationDao)

        viewModel.updatePlayerState(currentTimeMs = 1234L, totalDurationMs = 5000L, isPlaying = false, frameRate = 30f)

        assertEquals(1234L, viewModel.uiState.value.currentTimeMs)
        assertEquals(5000L, viewModel.uiState.value.totalDurationMs)
    }
}

private class FakeProjectDao : ProjectDao {
    override fun observeAll(): Flow<List<ProjectEntity>> = flow { emit(emptyList()) }
    override suspend fun getById(id: Long): ProjectEntity? = null
    override suspend fun getAll(): List<ProjectEntity> = emptyList()
    override suspend fun insert(project: ProjectEntity): Long = 1L
    override suspend fun update(project: ProjectEntity) {}
    override suspend fun delete(project: ProjectEntity) {}
}

private class FakeAnnotationDao : AnnotationDao {
    override fun observeForFrame(projectId: Long, frameIndex: Int): Flow<List<AnnotationEntity>> = flow { emit(emptyList()) }
    override fun observeForProject(projectId: Long): Flow<List<AnnotationEntity>> = flow { emit(emptyList()) }
    override suspend fun insert(annotation: AnnotationEntity): Long = 1L
    override suspend fun update(annotation: AnnotationEntity) {}
    override suspend fun delete(annotation: AnnotationEntity) {}
    override suspend fun deleteById(id: Long) {}
    override suspend fun deleteAllForFrame(projectId: Long, frameIndex: Int) {}
    override suspend fun deleteAllForProject(projectId: Long) {}
}
