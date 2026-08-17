package com.example.trueframe.data.repository

import com.example.trueframe.data.ProjectDao
import com.example.trueframe.data.ProjectEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepository @Inject constructor(
    private val projectDao: ProjectDao,
) {
    fun observeAll(): Flow<List<ProjectEntity>> = projectDao.observeAll()

    suspend fun getById(id: Long): ProjectEntity? = projectDao.getById(id)

    suspend fun create(name: String, videoUri: String): Long {
        return projectDao.insert(
            ProjectEntity(name = name, videoUri = videoUri)
        )
    }

    suspend fun update(project: ProjectEntity) {
        projectDao.update(project.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(project: ProjectEntity) {
        projectDao.delete(project)
    }
}
