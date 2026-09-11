package com.example.trueframe.ui.main

import android.content.Context
import android.content.ContextWrapper
import com.example.trueframe.core.video.ProxyTranscoder
import com.example.trueframe.data.ProjectDao
import com.example.trueframe.data.ProjectEntity
import com.example.trueframe.data.ProxyCacheManager
import com.example.trueframe.data.repository.ProjectRepository
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MainScreenViewModelTest {
    @Test
    fun uiState_initiallyLoading() = runTest {
        val fakeDao = FakeProjectDao()
        val fakeContext = ContextWrapper(null)
        val projectRepo = ProjectRepository(fakeDao)
        val proxyCacheManager = ProxyCacheManager(fakeContext, fakeDao)
        val proxyTranscoder = ProxyTranscoder()

        val viewModel = MainScreenViewModel(
            context = fakeContext,
            projectRepository = projectRepo,
            proxyCacheManager = proxyCacheManager,
            proxyTranscoder = proxyTranscoder
        )
        assertEquals(viewModel.uiState.first(), MainScreenUiState.Loading)
    }
}

private class FakeProjectDao : ProjectDao {
    override fun observeAll(): Flow<List<ProjectEntity>> = flow {
        emit(listOf(ProjectEntity(id = 1, name = "Test Project", videoUri = "content://test")))
    }

    override suspend fun getById(id: Long): ProjectEntity? = null
    override suspend fun getAll(): List<ProjectEntity> = listOf(ProjectEntity(id = 1, name = "Test Project", videoUri = "content://test"))
    override suspend fun insert(project: ProjectEntity): Long = 1L
    override suspend fun update(project: ProjectEntity) {}
    override suspend fun delete(project: ProjectEntity) {}
}
