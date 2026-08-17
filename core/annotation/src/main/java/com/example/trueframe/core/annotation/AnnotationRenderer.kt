package com.example.trueframe.core.annotation

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Composable Canvas overlay that renders annotation shapes on top of a video frame.
 * Spec: "renderer" in :core:annotation
 */
@Composable
fun AnnotationOverlay(
    shapes: List<AnnotationShape>,
    modifier: Modifier = Modifier,
    lineColor: Color = Color(0xFFFF6D00),
    angleColor: Color = Color(0xFF00E676),
    circleColor: Color = Color(0xFF448AFF),
    strokeWidth: Float = 4f,
) {
    Canvas(modifier = modifier) {
        shapes.forEach { shape ->
            when (shape) {
                is AnnotationShape.Line -> drawAnnotationLine(shape, lineColor, strokeWidth)
                is AnnotationShape.Angle -> drawAnnotationAngle(shape, angleColor, strokeWidth)
                is AnnotationShape.Circle -> drawAnnotationCircle(shape, circleColor, strokeWidth)
            }
        }
    }
}

private fun DrawScope.drawAnnotationLine(
    line: AnnotationShape.Line,
    color: Color,
    strokeWidth: Float,
) {
    drawLine(
        color = color,
        start = line.start,
        end = line.end,
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    // Draw endpoints
    drawCircle(color = color, radius = strokeWidth * 2, center = line.start)
    drawCircle(color = color, radius = strokeWidth * 2, center = line.end)
}

private fun DrawScope.drawAnnotationAngle(
    angle: AnnotationShape.Angle,
    color: Color,
    strokeWidth: Float,
) {
    // Draw the two rays
    drawLine(color = color, start = angle.center, end = angle.start, strokeWidth = strokeWidth, cap = StrokeCap.Round)
    drawLine(color = color, start = angle.center, end = angle.end, strokeWidth = strokeWidth, cap = StrokeCap.Round)
    // Draw vertex
    drawCircle(color = color, radius = strokeWidth * 2, center = angle.center)
}

private fun DrawScope.drawAnnotationCircle(
    circle: AnnotationShape.Circle,
    color: Color,
    strokeWidth: Float,
) {
    drawCircle(
        color = color,
        radius = circle.radius,
        center = circle.center,
        style = Stroke(width = strokeWidth),
    )
    // Draw center dot
    drawCircle(color = color, radius = strokeWidth * 1.5f, center = circle.center)
}
