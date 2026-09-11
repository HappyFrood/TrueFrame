package com.example.trueframe.di

import com.example.trueframe.core.video.ProxyTranscoder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideProxyTranscoder(): ProxyTranscoder {
        return ProxyTranscoder()
    }
}
