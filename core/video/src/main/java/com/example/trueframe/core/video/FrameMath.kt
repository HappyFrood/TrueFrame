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

    // Deprecated / Backwards compatibility aliases
    fun getFrameIntervalMs(frameRate: Float): Float = intervalMs(frameRate)
    fun calculateFrameIndex(currentPositionMs: Long, frameRate: Float): Int = frameForMs(currentPositionMs, frameRate)
}
