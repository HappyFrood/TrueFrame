package com.example.trueframe.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects")
    suspend fun getAll(): List<ProjectEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(project: ProjectEntity): Long

    @Update
    suspend fun update(project: ProjectEntity)

    @Delete
    suspend fun delete(project: ProjectEntity)
}

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotations WHERE projectId = :projectId AND frameIndex = :frameIndex ORDER BY id ASC")
    fun observeForFrame(projectId: Long, frameIndex: Int): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE projectId = :projectId ORDER BY id ASC")
    fun observeForProject(projectId: Long): Flow<List<AnnotationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(annotation: AnnotationEntity): Long

    @Update
    suspend fun update(annotation: AnnotationEntity)

    @Delete
    suspend fun delete(annotation: AnnotationEntity)

    @Query("DELETE FROM annotations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM annotations WHERE projectId = :projectId AND frameIndex = :frameIndex")
    suspend fun deleteAllForFrame(projectId: Long, frameIndex: Int)

    @Query("DELETE FROM annotations WHERE projectId = :projectId")
    suspend fun deleteAllForProject(projectId: Long)
}

@Dao
interface TagDao {
    @Query("SELECT * FROM tags ORDER BY name ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: TagEntity): Long

    @Delete
    suspend fun delete(tag: TagEntity)
}

@Dao
interface ProjectTagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(crossRef: ProjectTagCrossRef)

    @Delete
    suspend fun delete(crossRef: ProjectTagCrossRef)

    @Query(
        """
        SELECT tags.* FROM tags
        INNER JOIN project_tags ON tags.id = project_tags.tagId
        WHERE project_tags.projectId = :projectId
        """
    )
    fun observeTagsForProject(projectId: Long): Flow<List<TagEntity>>
}
