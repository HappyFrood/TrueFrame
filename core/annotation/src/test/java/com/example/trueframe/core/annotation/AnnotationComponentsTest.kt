package com.example.trueframe.core.annotation

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationComponentsTest {

    @Test
    fun angleDegrees_returnsInteriorAngleBetween0And180() {
        // Right angle (90 degrees)
        val rightAngle = AnnotationShape.Angle(
            start = Offset(0f, 100f),
            center = Offset(0f, 0f),
            end = Offset(100f, 0f)
        )
        assertEquals(90f, rightAngle.degrees(), 0.01f)

        // Reversed rays order should produce the exact same interior angle (90 degrees, not 270)
        val rightAngleReversed = AnnotationShape.Angle(
            start = Offset(100f, 0f),
            center = Offset(0f, 0f),
            end = Offset(0f, 100f)
        )
        assertEquals(90f, rightAngleReversed.degrees(), 0.01f)

        // Straight angle (180 degrees)
        val straightAngle = AnnotationShape.Angle(
            start = Offset(-100f, 0f),
            center = Offset(0f, 0f),
            end = Offset(100f, 0f)
        )
        assertEquals(180f, straightAngle.degrees(), 0.01f)
    }

    @Test
    fun hitTesting_lineAndCircle() {
        val line = AnnotationShape.Line(Offset(0f, 0f), Offset(100f, 0f))
        // Touch point directly on line segment
        assertTrue(HitTesting.hitTest(line, Offset(50f, 0f), touchSlop = 10f))
        // Touch point near line segment within slop
        assertTrue(HitTesting.hitTest(line, Offset(50f, 5f), touchSlop = 10f))

        val circle = AnnotationShape.Circle(Offset(100f, 100f), radius = 50f)
        // Touch point on circle edge
        assertTrue(HitTesting.hitTest(circle, Offset(150f, 100f), touchSlop = 10f))
    }

    @Test
    fun hitTesting_arrowUsesSegmentDistanceLikeLine() {
        val arrow = AnnotationShape.Arrow(Offset(0f, 0f), Offset(100f, 0f))
        val line = AnnotationShape.Line(Offset(0f, 0f), Offset(100f, 0f))
        for (p in listOf(Offset(50f, 5f), Offset(-10f, 0f), Offset(120f, 30f))) {
            assertEquals(HitTesting.distanceTo(line, p), HitTesting.distanceTo(arrow, p), 1e-4f)
        }
        assertTrue(HitTesting.hitTest(arrow, Offset(50f, 5f), touchSlop = 10f))
        assertFalse(HitTesting.hitTest(arrow, Offset(50f, 20f), touchSlop = 10f))
    }

    @Test
    fun hitTesting_textUsesPillRect() {
        val text = AnnotationShape.Text(Offset(10f, 10f), "Hello")
        val rect = Rect(10f, 10f, 110f, 40f)
        // Inside
        assertEquals(0f, HitTesting.distanceTo(text, Offset(60f, 25f), rect), 0f)
        // On the edge
        assertEquals(0f, HitTesting.distanceTo(text, Offset(110f, 40f), rect), 0f)
        assertEquals(0f, HitTesting.distanceTo(text, Offset(10f, 25f), rect), 0f)
        // Outside: 30 px right of the right edge
        assertEquals(30f, HitTesting.distanceTo(text, Offset(140f, 25f), rect), 1e-4f)
        // Outside, diagonal from the bottom-right corner (3-4-5)
        assertEquals(5f, HitTesting.distanceTo(text, Offset(113f, 44f), rect), 1e-4f)
        assertTrue(HitTesting.hitTest(text, Offset(115f, 25f), touchSlop = 10f, textRect = rect))
        assertFalse(HitTesting.hitTest(text, Offset(200f, 25f), touchSlop = 10f, textRect = rect))
    }

    @Test
    fun textSanitize_trimsLimitsAndRejectsEmpty() {
        assertEquals("Hello", AnnotationShape.Text.sanitize("  Hello  "))
        assertEquals("a b", AnnotationShape.Text.sanitize("a\nb"))
        assertNull(AnnotationShape.Text.sanitize("   "))
        assertEquals(AnnotationShape.Text.MAX_LENGTH, AnnotationShape.Text.sanitize("x".repeat(100))!!.length)
    }

    @Test
    fun arrowGeometry_headScalesWithStrokeAndIsClamped() {
        // Long arrow: head length = 4 × stroke = 40, half-angle 28°.
        val g = ArrowGeometry.compute(Offset(0f, 0f), Offset(1000f, 0f), strokeWidth = 10f)
        assertEquals(Offset(1000f, 0f), g.tip)
        val back = 40f * kotlin.math.cos(Math.toRadians(28.0)).toFloat()
        val side = 40f * kotlin.math.sin(Math.toRadians(28.0)).toFloat()
        assertEquals(1000f - back, g.shaftEnd.x, 1e-3f)
        assertEquals(side, kotlin.math.abs(g.left.y), 1e-3f)
        assertEquals(-g.left.y, g.right.y, 1e-3f)

        // Short arrow: head clamped to 40% of the length.
        val short = ArrowGeometry.compute(Offset(0f, 0f), Offset(50f, 0f), strokeWidth = 10f)
        val shortBack = 20f * kotlin.math.cos(Math.toRadians(28.0)).toFloat()
        assertEquals(50f - shortBack, short.shaftEnd.x, 1e-3f)

        // Degenerate arrow doesn't produce NaN.
        val zero = ArrowGeometry.compute(Offset(5f, 5f), Offset(5f, 5f), strokeWidth = 10f)
        assertFalse(zero.left.x.isNaN())
    }
}
