package com.example.trueframe.data.di

import android.content.Context
import androidx.room.Room
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
        ).build()
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
