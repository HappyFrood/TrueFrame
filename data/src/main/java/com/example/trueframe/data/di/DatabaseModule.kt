package com.example.trueframe.data.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.trueframe.data.AnnotationDao
import com.example.trueframe.data.ProjectDao
import com.example.trueframe.data.ProjectTagDao
import com.example.trueframe.data.TagDao
import com.example.trueframe.data.TrueFrameDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE projects ADD COLUMN transcodeState TEXT NOT NULL DEFAULT 'PENDING'")
        db.execSQL("ALTER TABLE projects ADD COLUMN rotationDegrees INTEGER NOT NULL DEFAULT 0")
        db.execSQL("DROP INDEX IF EXISTS index_annotations_projectId")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_annotations_projectId_frameIndex ON annotations (projectId, frameIndex)")
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TrueFrameDatabase {
        return Room.databaseBuilder(
            context,
            TrueFrameDatabase::class.java,
            "trueframe.db",
        ).addMigrations(MIGRATION_1_2).build()
    }

    @Provides
    fun provideProjectDao(db: TrueFrameDatabase): ProjectDao = db.projectDao()

    @Provides
    fun provideAnnotationDao(db: TrueFrameDatabase): AnnotationDao = db.annotationDao()

    @Provides
    fun provideTagDao(db: TrueFrameDatabase): TagDao = db.tagDao()

    @Provides
    fun provideProjectTagDao(db: TrueFrameDatabase): ProjectTagDao = db.projectTagDao()
}
