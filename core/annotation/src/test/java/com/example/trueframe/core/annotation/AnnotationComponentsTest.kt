package com.example.trueframe.core.annotation

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
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
}
