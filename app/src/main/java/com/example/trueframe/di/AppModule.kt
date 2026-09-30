package com.example.trueframe.di

import android.content.Context
import com.example.trueframe.core.video.VideoProbe
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** Process-lifetime scope for writes that must outlive a screen's ViewModel (e.g. last position on exit). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** Reads frame-rate metadata for a video. Abstracted so the editor ViewModel is unit-testable. */
interface VideoMetadataProbe {
    suspend fun probe(uri: String): VideoProbe.Result
}

class AndroidVideoMetadataProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) : VideoMetadataProbe {
    override suspend fun probe(uri: String): VideoProbe.Result = VideoProbe.probe(context, uri)
}

@Module
@InstallIn(SingletonComponent::class)
object AppScopeModule {
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class VideoModule {
    @Binds
    abstract fun bindVideoMetadataProbe(impl: AndroidVideoMetadataProbe): VideoMetadataProbe
}
