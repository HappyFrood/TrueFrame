package com.example.trueframe.core.annotation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Canonical colors for annotation shapes across the app. */
object AnnotationColors {
    val Line = Color(0xFFFF6D00)     // Orange for lines
    val Angle = Color(0xFF00E676)    // Green for angles
    val Circle = Color(0xFF448AFF)   // Blue for circles
    val Selected = Color(0xFFFFEB3B) // Yellow for selected shape
}

/**
 * Sealed hierarchy of annotation shapes.
 * Spec: "Shape models, geometry/measurement math"
 */
sealed interface AnnotationShape {

    /** A line between two points, measuring distance. */
    data class Line(
        val start: Offset,
        val end: Offset,
    ) : AnnotationShape

    /**
     * An angle formed by three points: vertex at [center],
     * rays to [start] and [end].
     */
    data class Angle(
        val start: Offset,
        val center: Offset,
        val end: Offset,
    ) : AnnotationShape {
        /** Angle in degrees between the two rays (interior angle, 0..180°). */
        fun degrees(): Float {
            val a1 = atan2((start.y - center.y).toDouble(), (start.x - center.x).toDouble())
            val a2 = atan2((end.y - center.y).toDouble(), (end.x - center.x).toDouble())
            var angle = Math.toDegrees(a2 - a1).toFloat()
            if (angle < 0) angle += 360f
            if (angle > 180f) angle = 360f - angle
            return angle
        }
    }

    /** A circle defined by a center point and radius. */
    data class Circle(
        val center: Offset,
        val radius: Float,
    ) : AnnotationShape
}

/**
 * Hit-testing utilities for annotation shapes.
 * Spec: "hit-testing"
 *
 * [touchSlop] is the minimum touch-target distance in pixels.
 * Spec: "Added minimum touch targets for annotations."
 */
object HitTesting {
    /** Minimum touch-target radius in **dp**. Convert at the call site via LocalDensity. */
    const val MIN_TOUCH_TARGET_DP = 24f   // 24dp radius == 48dp target

    fun distanceTo(shape: AnnotationShape, point: Offset): Float = when (shape) {
        is AnnotationShape.Line -> distanceToSegment(point, shape.start, shape.end)
        is AnnotationShape.Angle -> minOf(
            distanceToSegment(point, shape.center, shape.start),
            distanceToSegment(point, shape.center, shape.end),
        )
        is AnnotationShape.Circle -> abs(
            hypot(
                (point.x - shape.center.x).toDouble(),
                (point.y - shape.center.y).toDouble(),
            ).toFloat() - shape.radius
        )
    }

    fun hitTest(shape: AnnotationShape, point: Offset, touchSlop: Float): Boolean =
        distanceTo(shape, point) <= touchSlop

    private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
        val ab = b - a
        val dot = ab.x * ab.x + ab.y * ab.y
        if (dot == 0f) {
            return hypot((p.x - a.x).toDouble(), (p.y - a.y).toDouble()).toFloat()
        }
        val ap = p - a
        val t = ((ap.x * ab.x + ap.y * ab.y) / dot).coerceIn(0f, 1f)
        val closest = Offset(a.x + t * ab.x, a.y + t * ab.y)
        return hypot((p.x - closest.x).toDouble(), (p.y - closest.y).toDouble()).toFloat()
    }
}
