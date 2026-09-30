package com.example.trueframe.core.video

import kotlin.math.floor

object FrameMath {
    fun intervalMs(frameRate: Float): Float = 1000f / frameRate.coerceAtLeast(1f)

    fun frameForMs(timeMs: Long, frameRate: Float): Int {
        val interval = intervalMs(frameRate)
        return floor(timeMs / interval).toInt().coerceAtLeast(0)
    }

    fun msForFrame(frame: Int, frameRate: Float): Long =
        ((frame.coerceAtLeast(0) + 0.5f) * intervalMs(frameRate)).toLong()

    fun calculateTargetTimeMs(
        currentFrameIdx: Int,
        delta: Int,
        frameRate: Float,
        totalDurationMs: Long = Long.MAX_VALUE,
    ): Long {
        val targetFrame = (currentFrameIdx + delta).coerceAtLeast(0)
        return msForFrame(targetFrame, frameRate).coerceIn(0L, totalDurationMs)
    }

    /** Frame rate assumed when neither the player nor the file reports one. */
    const val ASSUMED_FPS = 30f

    /** Average fps from a sample (frame) count over a duration, or null when either is unusable. */
    fun fpsFromSampleCount(sampleCount: Long, durationUs: Long): Float? {
        if (sampleCount <= 1L || durationUs <= 0L) return null
        val fps = sampleCount * 1_000_000.0 / durationUs
        return if (fps.isFinite() && fps > 0.0) fps.toFloat() else null
    }

    /**
     * Picks the playback frame rate: the player's reported value first, then the value computed
     * from the file, then [ASSUMED_FPS]. [FrameRate.isAssumed] is true only in the last case.
     */
    fun resolveFrameRate(playerFps: Float?, computedFps: Float?): FrameRate = when {
        playerFps != null && playerFps > 0f -> FrameRate(playerFps, FrameRateSource.PLAYER)
        computedFps != null && computedFps > 0f -> FrameRate(computedFps, FrameRateSource.COMPUTED)
        else -> FrameRate(ASSUMED_FPS, FrameRateSource.ASSUMED)
    }

    /**
     * Frame rate to pre-fill for speed calculations: the capture rate when it is present and higher
     * than playback (slow-motion saved as a 30 fps file), otherwise the playback rate.
     */
    fun speedFps(playbackFps: Float, captureFps: Float?): Float =
        if (captureFps != null && captureFps > playbackFps + 0.5f) captureFps else playbackFps

    // Deprecated / Backwards compatibility aliases
    fun getFrameIntervalMs(frameRate: Float): Float = intervalMs(frameRate)
    fun calculateFrameIndex(currentPositionMs: Long, frameRate: Float): Int = frameForMs(currentPositionMs, frameRate)
}

enum class FrameRateSource { PLAYER, COMPUTED, ASSUMED }

data class FrameRate(val fps: Float, val source: FrameRateSource) {
    val isAssumed: Boolean get() = source == FrameRateSource.ASSUMED
}
