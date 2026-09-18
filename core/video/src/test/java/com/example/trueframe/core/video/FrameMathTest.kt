package com.example.trueframe.core.video

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameMathTest {

    private val testFrameRates = listOf(23.976f, 24f, 29.97f, 30f, 59.94f, 60f, 120f, 240f)

    @Test
    fun testFrameForMsAndMsForFrameConsistency() {
        for (fps in testFrameRates) {
            for (frame in 0..100) {
                val ms = FrameMath.msForFrame(frame, fps)
                val calculatedFrame = FrameMath.frameForMs(ms, fps)
                assertEquals(
                    "Frame roundtrip failed for frame $frame at fps $fps (ms=$ms)",
                    frame,
                    calculatedFrame
                )
            }
        }
    }

    @Test
    fun testSteppingPlusOneYieldsNextFrameAcrossAllFrameRates() {
        for (fps in testFrameRates) {
            for (frame in 0..100) {
                val targetMs = FrameMath.calculateTargetTimeMs(currentFrameIdx = frame, delta = 1, frameRate = fps)
                val resultingFrame = FrameMath.frameForMs(targetMs, fps)
                assertEquals(
                    "Stepping +1 from frame $frame failed for fps $fps (targetMs=$targetMs)",
                    frame + 1,
                    resultingFrame
                )
            }
        }
    }

    @Test
    fun testSteppingMinusOneYieldsPreviousFrameAcrossAllFrameRates() {
        for (fps in testFrameRates) {
            for (frame in 1..100) {
                val targetMs = FrameMath.calculateTargetTimeMs(currentFrameIdx = frame, delta = -1, frameRate = fps)
                val resultingFrame = FrameMath.frameForMs(targetMs, fps)
                assertEquals(
                    "Stepping -1 from frame $frame failed for fps $fps (targetMs=$targetMs)",
                    frame - 1,
                    resultingFrame
                )
            }
        }
    }

    @Test
    fun testSteppingTenFramesAcrossAllFrameRates() {
        for (fps in testFrameRates) {
            for (frame in 0..50) {
                val targetMsPlus10 = FrameMath.calculateTargetTimeMs(currentFrameIdx = frame, delta = 10, frameRate = fps)
                val resultingFramePlus10 = FrameMath.frameForMs(targetMsPlus10, fps)
                assertEquals(
                    "Stepping +10 from frame $frame failed for fps $fps",
                    frame + 10,
                    resultingFramePlus10
                )

                if (frame >= 10) {
                    val targetMsMinus10 = FrameMath.calculateTargetTimeMs(currentFrameIdx = frame, delta = -10, frameRate = fps)
                    val resultingFrameMinus10 = FrameMath.frameForMs(targetMsMinus10, fps)
                    assertEquals(
                        "Stepping -10 from frame $frame failed for fps $fps",
                        frame - 10,
                        resultingFrameMinus10
                    )
                }
            }
        }
    }

    @Test
    fun testBoundaryClamping() {
        for (fps in testFrameRates) {
            // Delta below frame 0 clamps to 0
            val targetMsClamped = FrameMath.calculateTargetTimeMs(currentFrameIdx = 0, delta = -5, frameRate = fps)
            val frameClamped = FrameMath.frameForMs(targetMsClamped, fps)
            assertEquals(0, frameClamped)

            // Duration capping
            val totalDurationMs = 500L
            val targetMsCapped = FrameMath.calculateTargetTimeMs(currentFrameIdx = 1000, delta = 500, frameRate = fps, totalDurationMs = totalDurationMs)
            assertEquals(totalDurationMs, targetMsCapped)
        }
    }
}
