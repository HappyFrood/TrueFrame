package com.example.trueframe.core.annotation

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import java.util.Locale

/**
 * Composable Canvas overlay that renders annotation shapes on top of a video frame.
 * Includes interactive handle indicators and measurement values.
 */
@Composable
fun AnnotationOverlay(
    shapes: List<AnnotationShape>,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    showHandles: Boolean = true,
    lineColor: Color = Color(0xFFFF6D00),
    angleColor: Color = Color(0xFF00E676),
    circleColor: Color = Color(0xFF448AFF),
    selectedColor: Color = Color(0xFFFFEB3B),
    strokeWidth: Float = 10f,
) {
    Canvas(modifier = modifier) {
        shapes.forEachIndexed { index, shape ->
            val isSelected = (index == selectedIndex)
            val stroke = if (isSelected) strokeWidth * 1.3f else strokeWidth

            when (shape) {
                is AnnotationShape.Line -> drawAnnotationLine(
                    line = shape,
                    color = if (isSelected) selectedColor else lineColor,
                    strokeWidth = stroke,
                    showHandles = showHandles
                )
                is AnnotationShape.Angle -> drawAnnotationAngle(
                    angle = shape,
                    color = if (isSelected) selectedColor else angleColor,
                    strokeWidth = stroke,
                    showHandles = showHandles
                )
                is AnnotationShape.Circle -> drawAnnotationCircle(
                    circle = shape,
                    color = if (isSelected) selectedColor else circleColor,
                    strokeWidth = stroke,
                    showHandles = showHandles
                )
            }
        }
    }
}

private fun DrawScope.drawHandle(center: Offset, color: Color, radius: Float = 24f) {
    // Outer white ring
    drawCircle(color = Color.White, radius = radius, center = center)
    // Inner colored circle
    drawCircle(color = color, radius = radius * 0.65f, center = center)
}

private fun DrawScope.drawAnnotationLine(
    line: AnnotationShape.Line,
    color: Color,
    strokeWidth: Float,
    showHandles: Boolean,
) {
    drawLine(
        color = color,
        start = line.start,
        end = line.end,
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    if (showHandles) {
        drawHandle(line.start, color)
        drawHandle(line.end, color)
    }
}

private fun DrawScope.drawAnnotationAngle(
    angle: AnnotationShape.Angle,
    color: Color,
    strokeWidth: Float,
    showHandles: Boolean,
) {
    // Draw rays
    drawLine(color = color, start = angle.center, end = angle.start, strokeWidth = strokeWidth, cap = StrokeCap.Round)
    drawLine(color = color, start = angle.center, end = angle.end, strokeWidth = strokeWidth, cap = StrokeCap.Round)

    if (showHandles) {
        drawHandle(angle.start, color)
        drawHandle(angle.center, color, radius = 24f) // Vertex is slightly larger
        drawHandle(angle.end, color)
    }

    // Render angle text in degrees
    val degrees = angle.degrees()
    val text = String.format(Locale.US, "%.1f°", degrees)
    val textPaint = Paint().apply {
        this.color = color.toArgb()
        textSize = 42f
        isAntiAlias = true
        isFakeBoldText = true
        setShadowLayer(6f, 2f, 2f, android.graphics.Color.BLACK)
    }
    
    val textOffset = angle.center + Offset(24f, -24f)
    drawContext.canvas.nativeCanvas.drawText(text, textOffset.x, textOffset.y, textPaint)
}

private fun DrawScope.drawAnnotationCircle(
    circle: AnnotationShape.Circle,
    color: Color,
    strokeWidth: Float,
    showHandles: Boolean,
) {
    drawCircle(
        color = color,
        radius = circle.radius,
        center = circle.center,
        style = Stroke(width = strokeWidth),
    )
    if (showHandles) {
        drawHandle(circle.center, color)
        drawHandle(circle.center + Offset(circle.radius, 0f), color)
    }
}
