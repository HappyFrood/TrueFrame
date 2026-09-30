package com.example.trueframe.core.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun fpsFallback_usesComputedFpsWhenPlayerReportsNone() {
        val computed = FrameMath.fpsFromSampleCount(sampleCount = 2400, durationUs = 10_000_000L)
        assertEquals(240f, computed!!, 0.001f)

        val resolved = FrameMath.resolveFrameRate(playerFps = null, computedFps = computed)
        assertEquals(240f, resolved.fps, 0.001f)
        assertEquals(FrameRateSource.COMPUTED, resolved.source)

        // Stepping +1 at the computed rate lands on the next real frame, not ~8 frames ahead.
        val target = FrameMath.calculateTargetTimeMs(currentFrameIdx = 100, delta = 1, frameRate = resolved.fps)
        assertEquals(101, FrameMath.frameForMs(target, resolved.fps))
    }

    @Test
    fun fpsFallback_prefersPlayerAndAssumes30WhenNothingKnown() {
        assertEquals(FrameRateSource.PLAYER, FrameMath.resolveFrameRate(60f, 240f).source)
        assertEquals(60f, FrameMath.resolveFrameRate(60f, 240f).fps)
        assertEquals(FrameRateSource.COMPUTED, FrameMath.resolveFrameRate(0f, 120f).source)

        val assumed = FrameMath.resolveFrameRate(null, null)
        assertEquals(FrameMath.ASSUMED_FPS, assumed.fps)
        assertTrue(assumed.isAssumed)
    }

    @Test
    fun fpsFromSampleCount_rejectsUnusableInputs() {
        assertNull(FrameMath.fpsFromSampleCount(0, 1_000_000L))
        assertNull(FrameMath.fpsFromSampleCount(1, 1_000_000L))
        assertNull(FrameMath.fpsFromSampleCount(100, 0L))
    }

    @Test
    fun speedFps_prefersHigherCaptureRate() {
        assertEquals(240f, FrameMath.speedFps(playbackFps = 30f, captureFps = 240f))
        assertEquals(60f, FrameMath.speedFps(playbackFps = 60f, captureFps = 60f))
        assertEquals(60f, FrameMath.speedFps(playbackFps = 60f, captureFps = null))
    }
}
