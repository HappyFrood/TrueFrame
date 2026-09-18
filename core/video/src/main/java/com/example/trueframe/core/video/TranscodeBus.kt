package com.example.trueframe.core.video

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TranscodeEvent {
    data class Completed(val projectId: Long, val proxyUri: String) : TranscodeEvent
    data class Failed(val projectId: Long, val message: String?) : TranscodeEvent
}

@Singleton
class TranscodeBus @Inject constructor() {
    private val _progress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val progress: StateFlow<Map<Long, Float>> = _progress.asStateFlow()

    // SharedFlow, not StateFlow: terminal events must not be conflated away.
    private val _events = MutableSharedFlow<TranscodeEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<TranscodeEvent> = _events

    fun setProgress(projectId: Long, fraction: Float) {
        _progress.value = _progress.value + (projectId to fraction)
    }

    suspend fun finish(event: TranscodeEvent, projectId: Long) {
        _progress.value = _progress.value - projectId
        _events.emit(event)
    }
}