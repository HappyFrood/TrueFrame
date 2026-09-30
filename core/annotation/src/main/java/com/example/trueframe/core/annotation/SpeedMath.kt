package com.example.trueframe.core.annotation

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Calibrated ball-speed estimate from a reference object of known length.
 *
 * All points are normalized (0..1) in unrotated source-frame space. They are converted to
 * **source pixels** (`x * rawWidth`, `y * rawHeight`) before any distance is measured, because
 * normalized x and y have different scales on non-square frames.
 *
 * ```
 * inchesPerPx = knownInches / referenceLengthPx
 * ballInches  = distancePx(A, B) * inchesPerPx
 * seconds     = |frameB - frameA| / fps
 * mph         = (ballInches / seconds) * 3600 / 63360
 * ```
 */
object SpeedMath {

    /** Minimum reference-line length, in source pixels. */
    const val MIN_REFERENCE_PX = 20f
    const val MIN_KNOWN_INCHES = 1f
    const val MAX_KNOWN_INCHES = 600f

    private const val INCHES_PER_MILE = 63_360f
    private const val SECONDS_PER_HOUR = 3_600f

    data class Input(
        val referenceStart: Offset,
        val referenceEnd: Offset,
        val knownInches: Float,
        val ballA: Offset,
        val frameA: Int,
        val ballB: Offset,
        val frameB: Int,
        val fps: Float,
        val rawWidth: Float,
        val rawHeight: Float,
    )

    sealed interface Result {
        data class Speed(
            val mph: Float,
            val ballInches: Float,
            val frames: Int,
            val seconds: Float,
            val fps: Float,
        ) : Result

        sealed interface Error : Result
        data object ReferenceTooShort : Error
        data object SameFrame : Error
        data object InvalidFps : Error
        data object InvalidLength : Error
    }

    fun distancePx(a: Offset, b: Offset, rawWidth: Float, rawHeight: Float): Float =
        hypot(((b.x - a.x) * rawWidth).toDouble(), ((b.y - a.y) * rawHeight).toDouble()).toFloat()

    fun calculate(input: Input): Result = with(input) {
        if (!(fps > 0f) || fps.isInfinite()) return Result.InvalidFps
        if (!(knownInches in MIN_KNOWN_INCHES..MAX_KNOWN_INCHES)) return Result.InvalidLength
        val frames = abs(frameB - frameA)
        if (frames == 0) return Result.SameFrame
        val referencePx = distancePx(referenceStart, referenceEnd, rawWidth, rawHeight)
        if (referencePx < MIN_REFERENCE_PX) return Result.ReferenceTooShort

        val inchesPerPx = knownInches / referencePx
        val ballInches = distancePx(ballA, ballB, rawWidth, rawHeight) * inchesPerPx
        val seconds = frames / fps
        val mph = (ballInches / seconds) * SECONDS_PER_HOUR / INCHES_PER_MILE
        Result.Speed(mph = mph, ballInches = ballInches, frames = frames, seconds = seconds, fps = fps)
    }

    fun errorMessage(error: Result.Error): String = when (error) {
        Result.ReferenceTooShort -> "Reference line is too short"
        Result.SameFrame -> "Ball points must be on different frames"
        Result.InvalidFps -> "Frame rate must be greater than 0"
        Result.InvalidLength -> "Length must be between 1 and 600 inches"
    }
}
