package com.example.trueframe.core.video

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Transcodes source video into a lower-resolution proxy using MediaCodec + OpenGL + MediaMuxer.
 * OpenGL surface bakes in rotation metadata so downstream doesn't need to handle it.
 * Spec: "MediaCodec + OpenGL + MediaMuxer", "OpenGL bakes rotation."
 */
class ProxyTranscoder {

    sealed interface TranscodeState {
        data object Idle : TranscodeState
        data class Progress(val fraction: Float) : TranscodeState
        data object Complete : TranscodeState
        data class Error(val cause: Throwable) : TranscodeState
    }

    private val _state = MutableStateFlow<TranscodeState>(TranscodeState.Idle)
    val state: StateFlow<TranscodeState> = _state

    /**
     * Starts transcoding [sourceUri] into a proxy file at [outputPath].
     * Should be called from a Foreground Service to prevent OS killing the process.
     * Spec: "Foreground Service to prevent OS killing transcode."
     */
    suspend fun start(sourceUri: String, outputPath: String) {
        _state.value = TranscodeState.Progress(0f)
        // TODO: MediaCodec + OpenGL pipeline implementation
        _state.value = TranscodeState.Complete
    }

    fun cancel() {
        // TODO: Cancel ongoing transcode
        _state.value = TranscodeState.Idle
    }
}

/**
 * Reads frames from the proxy video file using MediaCodec.
 * Spec: "ProxyReader"
 */
class ProxyReader {

    /**
     * Extracts a single frame at [frameIndex] from [proxyPath].
     * Returns the decoded Bitmap, or null on failure.
     */
    suspend fun readFrame(proxyPath: String, frameIndex: Int): Bitmap? {
        // TODO: Use MediaCodec to seek and decode a specific frame
        return null
    }
}

/**
 * Maps presentation timestamps to sequential frame indices for the proxy video.
 * Spec: "FrameIndex"
 */
class FrameIndex {

    data class FrameInfo(
        val index: Int,
        val presentationTimeUs: Long,
    )

    private val frames = mutableListOf<FrameInfo>()

    val totalFrames: Int get() = frames.size

    /**
     * Builds the frame index from the proxy file at [proxyPath].
     */
    suspend fun build(proxyPath: String) {
        // TODO: Scan video track for all frame timestamps
        frames.clear()
    }

    fun getTimestampForFrame(index: Int): Long? = frames.getOrNull(index)?.presentationTimeUs

    fun getFrameForTimestamp(timeUs: Long): Int {
        // Binary search for nearest frame
        // TODO: implement
        return 0
    }
}

/**
 * Clock for controlling playback speed (normal-speed and slow-motion).
 * Spec: "Supports normal-speed playback and slow-motion review of high-frame-rate footage (60/120/240 fps)."
 */
class PlaybackClock {

    enum class Speed(val multiplier: Float) {
        NORMAL(1.0f),
        HALF(0.5f),
        QUARTER(0.25f),
        EIGHTH(0.125f),
    }

    var speed: Speed = Speed.NORMAL
    var isPlaying: Boolean = false
        private set

    private var startTimeNs: Long = 0L
    private var startFrameTimeUs: Long = 0L

    fun play(fromTimeUs: Long) {
        isPlaying = true
        startTimeNs = System.nanoTime()
        startFrameTimeUs = fromTimeUs
    }

    fun pause() {
        isPlaying = false
    }

    /**
     * Returns the current playback time in microseconds based on wall-clock and speed.
     */
    fun currentTimeUs(): Long {
        if (!isPlaying) return startFrameTimeUs
        val elapsedNs = System.nanoTime() - startTimeNs
        val elapsedUs = (elapsedNs / 1000 * speed.multiplier).toLong()
        return startFrameTimeUs + elapsedUs
    }
}

/**
 * Extracts the audio track from the source video for playback.
 * Spec: "AudioExtractor" — "Audio playback added."
 */
class AudioExtractor {

    /**
     * Extracts the audio track from [sourceUri] into a raw audio file at [outputPath].
     */
    suspend fun extract(sourceUri: String, outputPath: String) {
        // TODO: Use MediaExtractor + MediaCodec to extract and decode audio
    }
}
