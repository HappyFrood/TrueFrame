package com.example.trueframe.core.annotation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Canonical colors for annotation shapes across the app. Screen and export use the same values. */
object AnnotationColors {
    val Line = Color(0xFFFF8F00)     // Vibrant bright orange for lines
    val Angle = Color(0xFF00E676)    // Green for angles
    val Circle = Color(0xFF448AFF)   // Blue for circles
    val Arrow = Color(0xFFFF1744)    // Vivid red for arrows
    val Text = Color(0xFFFFEB3B)     // Yellow for text labels
}

/**
 * Sealed hierarchy of annotation shapes. All coordinates are normalized (0..1) in unrotated
 * source-frame space; radii are normalized against frame width.
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

    /**
     * A pointer arrow. The tail is [start] and the filled head sits at [end].
     * Same geometry as [Line], but it carries no measurement label.
     */
    data class Arrow(
        val start: Offset,
        val end: Offset,
    ) : AnnotationShape

    /**
     * A free-text label drawn as a pill. [anchor] is the pill's top-left corner.
     * The glyphs always render upright; only the anchor follows video rotation.
     */
    data class Text(
        val anchor: Offset,
        val text: String,
    ) : AnnotationShape {
        companion object {
            const val MAX_LENGTH = 60

            /** Normalizes user input: single line, trimmed, at most [MAX_LENGTH] chars. Null if empty. */
            fun sanitize(input: String): String? {
                val cleaned = input.replace('\n', ' ').replace('\r', ' ').trim().take(MAX_LENGTH).trim()
                return cleaned.ifEmpty { null }
            }
        }
    }
}

/**
 * Hit-testing utilities for annotation shapes. All inputs are in view-pixel space.
 * Spec: "hit-testing"
 */
object HitTesting {
    /** Minimum touch-target radius in **dp**. Convert at the call site via LocalDensity. */
    @Suppress("unused")
    const val MIN_TOUCH_TARGET_DP = 24f   // 24dp radius == 48dp target

    /**
     * Distance from [point] to [shape]. A [AnnotationShape.Text] needs its measured pill rect
     * ([textRect], see `textPillRect`), otherwise it falls back to the distance to its anchor.
     */
    fun distanceTo(shape: AnnotationShape, point: Offset, textRect: Rect? = null): Float = when (shape) {
        is AnnotationShape.Line -> distanceToSegment(point, shape.start, shape.end)
        is AnnotationShape.Arrow -> distanceToSegment(point, shape.start, shape.end)
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
        is AnnotationShape.Text -> if (textRect != null) {
            distanceToRect(point, textRect)
        } else {
            hypot((point.x - shape.anchor.x).toDouble(), (point.y - shape.anchor.y).toDouble()).toFloat()
        }
    }

    fun hitTest(shape: AnnotationShape, point: Offset, touchSlop: Float, textRect: Rect? = null): Boolean =
        distanceTo(shape, point, textRect) <= touchSlop

    /** 0 inside (or on the edge of) [rect], otherwise the Euclidean distance to its nearest edge. */
    fun distanceToRect(p: Offset, rect: Rect): Float {
        val dx = max(max(rect.left - p.x, 0f), p.x - rect.right)
        val dy = max(max(rect.top - p.y, 0f), p.y - rect.bottom)
        return hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }

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

/**
 * Arrowhead geometry shared by the on-screen renderer and the JPEG exporter so both match.
 * Head length = `4 × strokeWidth`, clamped to 40% of the arrow length; half-angle 28°.
 * The shaft runs from [tail] to [shaftEnd] (the base of the head); the filled head is the
 * triangle [tip], [left], [right].
 */
data class ArrowGeometry(
    val tail: Offset,
    val shaftEnd: Offset,
    val tip: Offset,
    val left: Offset,
    val right: Offset,
) {
    companion object {
        const val HEAD_LENGTH_STROKES = 4f
        const val MAX_HEAD_FRACTION = 0.4f
        const val HEAD_HALF_ANGLE_DEG = 28.0

        /** [start] and [end] must already be in pixel space. */
        fun compute(start: Offset, end: Offset, strokeWidth: Float): ArrowGeometry {
            val dx = end.x - start.x
            val dy = end.y - start.y
            val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            if (len <= 0f) return ArrowGeometry(start, end, end, end, end)
            val ux = dx / len
            val uy = dy / len
            val headLen = minOf(HEAD_LENGTH_STROKES * strokeWidth, MAX_HEAD_FRACTION * len)
            val rad = Math.toRadians(HEAD_HALF_ANGLE_DEG)
            val back = headLen * cos(rad).toFloat()
            val side = headLen * sin(rad).toFloat()
            val base = Offset(end.x - ux * back, end.y - uy * back)
            // Perpendicular (-uy, ux)
            val left = Offset(base.x - uy * side, base.y + ux * side)
            val right = Offset(base.x + uy * side, base.y - ux * side)
            return ArrowGeometry(start, base, end, left, right)
        }
    }
}
