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

    suspend fun create(name: String, videoUri: String, frameRate: Float? = null, captureFps: Float? = null): Long {
        return projectDao.insert(
            ProjectEntity(name = name, videoUri = videoUri, frameRate = frameRate, captureFps = captureFps)
        )
    }

    suspend fun update(project: ProjectEntity) {
        projectDao.update(project.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateLastPosition(id: Long, positionMs: Long) {
        projectDao.updateLastPosition(id, positionMs, System.currentTimeMillis())
    }

    suspend fun updateRotation(id: Long, rotationDegrees: Int) {
        projectDao.updateRotation(id, rotationDegrees, System.currentTimeMillis())
    }

    suspend fun updateName(id: Long, name: String) {
        projectDao.updateName(id, name, System.currentTimeMillis())
    }

    suspend fun updateFrameRates(id: Long, frameRate: Float?, captureFps: Float?) {
        projectDao.updateFrameRates(id, frameRate, captureFps)
    }

    suspend fun delete(project: ProjectEntity) {
        projectDao.delete(project)
    }
}
