package com.example.trueframe.core.annotation

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedMathTest {

    private val width = 1000f
    private val height = 1000f

    /** 45 in reference measuring 450 px; ball moves 900 px over 10 frames. */
    private fun baseInput(fps: Float) = SpeedMath.Input(
        referenceStart = Offset(0.1f, 0.5f),
        referenceEnd = Offset(0.55f, 0.5f),
        knownInches = 45f,
        ballA = Offset(0.05f, 0.2f),
        frameA = 100,
        ballB = Offset(0.95f, 0.2f),
        frameB = 110,
        fps = fps,
        rawWidth = width,
        rawHeight = height,
    )

    private fun mph(result: SpeedMath.Result): Float {
        assertTrue("expected a speed, got $result", result is SpeedMath.Result.Speed)
        return (result as SpeedMath.Result.Speed).mph
    }

    @Test
    fun knownReference_at240fps_gives122_7mph() {
        assertEquals(122.7f, mph(SpeedMath.calculate(baseInput(240f))), 0.1f)
    }

    @Test
    fun sameInputs_at30fps_gives15_3mph() {
        assertEquals(15.3f, mph(SpeedMath.calculate(baseInput(30f))), 0.1f)
    }

    @Test
    fun reportsBallDistanceAndDuration() {
        val r = SpeedMath.calculate(baseInput(240f)) as SpeedMath.Result.Speed
        assertEquals(90f, r.ballInches, 0.01f)
        assertEquals(10, r.frames)
        assertEquals(10f / 240f, r.seconds, 1e-6f)
    }

    @Test
    fun framesInReverseOrder_useAbsoluteDifference() {
        val input = baseInput(240f).copy(frameA = 110, frameB = 100)
        assertEquals(122.7f, mph(SpeedMath.calculate(input)), 0.1f)
    }

    @Test
    fun nonSquareFrame_measuresDistancesInPixelSpace() {
        // 1920×1080: vertical reference 0.25..0.75 of height = 540 px = 54 in (0.1 in/px).
        // Ball moves horizontally 0.25 of width = 480 px = 48 in over 10 frames at 240 fps.
        val input = SpeedMath.Input(
            referenceStart = Offset(0.5f, 0.25f),
            referenceEnd = Offset(0.5f, 0.75f),
            knownInches = 54f,
            ballA = Offset(0.25f, 0.5f),
            frameA = 0,
            ballB = Offset(0.5f, 0.5f),
            frameB = 10,
            fps = 240f,
            rawWidth = 1920f,
            rawHeight = 1080f,
        )
        // 48 in / (10/240 s) = 1152 in/s = 65.45 mph. Normalized units would give 54 in/0.5 × 0.25 → 27 in.
        assertEquals(65.45f, mph(SpeedMath.calculate(input)), 0.05f)
    }

    @Test
    fun identicalFrames_isTypedError() {
        val input = baseInput(240f).copy(frameB = 100)
        assertEquals(SpeedMath.Result.SameFrame, SpeedMath.calculate(input))
    }

    @Test
    fun zeroFps_isTypedError() {
        assertEquals(SpeedMath.Result.InvalidFps, SpeedMath.calculate(baseInput(0f)))
        assertEquals(SpeedMath.Result.InvalidFps, SpeedMath.calculate(baseInput(-30f)))
    }

    @Test
    fun tooShortReference_isTypedError() {
        // 0.015 × 1000 = 15 px < 20 px
        val input = baseInput(240f).copy(referenceEnd = Offset(0.115f, 0.5f))
        assertEquals(SpeedMath.Result.ReferenceTooShort, SpeedMath.calculate(input))
        assertEquals("Reference line is too short", SpeedMath.errorMessage(SpeedMath.Result.ReferenceTooShort))
    }

    @Test
    fun knownLengthOutOfRange_isTypedError() {
        assertEquals(SpeedMath.Result.InvalidLength, SpeedMath.calculate(baseInput(240f).copy(knownInches = 0.5f)))
        assertEquals(SpeedMath.Result.InvalidLength, SpeedMath.calculate(baseInput(240f).copy(knownInches = 601f)))
    }
}
