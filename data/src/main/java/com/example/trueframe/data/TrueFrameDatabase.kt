package com.example.trueframe.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProjectEntity::class,
        AnnotationEntity::class,
        TagEntity::class,
        ProjectTagCrossRef::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class TrueFrameDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun annotationDao(): AnnotationDao
    abstract fun tagDao(): TagDao
    abstract fun projectTagDao(): ProjectTagDao
}
