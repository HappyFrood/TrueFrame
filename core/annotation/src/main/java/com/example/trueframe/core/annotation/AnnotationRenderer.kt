package com.example.trueframe.core.annotation

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.hypot

/**
 * Composable Canvas overlay that renders annotation shapes on top of a video frame.
 * Includes interactive handle indicators and measurement values for the selected shape.
 */
@Composable
fun AnnotationOverlay(
    shapes: List<AnnotationShape>,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    activeDragTarget: Pair<Int, Int>? = null,
    showHandles: Boolean = true,
    rotationDegrees: Int = 0,
    frameAspect: Float = 1f,
    sourceWidthPx: Float = 0f,
    lineColor: Color = AnnotationColors.Line,
    angleColor: Color = AnnotationColors.Angle,
    circleColor: Color = AnnotationColors.Circle,
) {
    val density = LocalDensity.current
    val strokePx = with(density) { 3.6.dp.toPx() } // 20% thicker strokes
    val handlePx = with(density) { 9.dp.toPx() }
    val pillCornerPx = with(density) { 6.dp.toPx() }
    val pillPadHorizPx = with(density) { 6.dp.toPx() }
    val pillPadVertPx = with(density) { 3.dp.toPx() }

    val textPaint = remember(density) {
        Paint().apply {
            textSize = with(density) { 14.dp.toPx() }
            isAntiAlias = true
            isFakeBoldText = true
        }
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val toSource = if (sourceWidthPx > 0f) sourceWidthPx / w else 0f

        shapes.forEachIndexed { index, shape ->
            val isSelected = (index == selectedIndex)
            val stroke = if (isSelected) strokePx * 1.3f else strokePx
            val drawHandlesForShape = showHandles && isSelected

            val pixelShape = shape
                .rotateNorm(rotationDegrees, frameAspect)
                .toPixelSpace(w, h)

            val color = when (pixelShape) {
                is AnnotationShape.Line -> lineColor
                is AnnotationShape.Angle -> angleColor
                is AnnotationShape.Circle -> circleColor
            }

            // Draw faint glow underneath selected shape
            if (isSelected) {
                val glowStroke = stroke * 3f
                val glowColor = color.copy(alpha = 0.25f)
                when (pixelShape) {
                    is AnnotationShape.Line -> {
                        drawLine(color = glowColor, start = pixelShape.start, end = pixelShape.end, strokeWidth = glowStroke, cap = StrokeCap.Round)
                    }
                    is AnnotationShape.Angle -> {
                        drawLine(color = glowColor, start = pixelShape.center, end = pixelShape.start, strokeWidth = glowStroke, cap = StrokeCap.Round)
                        drawLine(color = glowColor, start = pixelShape.center, end = pixelShape.end, strokeWidth = glowStroke, cap = StrokeCap.Round)
                    }
                    is AnnotationShape.Circle -> {
                        drawCircle(color = glowColor, radius = pixelShape.radius, center = pixelShape.center, style = Stroke(width = glowStroke))
                    }
                }
            }

            val draggedHandleIdx = if (activeDragTarget?.first == index) activeDragTarget.second else null

            when (pixelShape) {
                is AnnotationShape.Line -> drawAnnotationLine(
                    line = pixelShape,
                    color = lineColor,
                    strokeWidth = stroke,
                    showHandles = drawHandlesForShape,
                    handleRadius = handlePx,
                    draggedHandleIdx = draggedHandleIdx,
                    textPaint = textPaint,
                    toSource = toSource,
                    viewWidth = w,
                    cornerPx = pillCornerPx,
                    padHorizPx = pillPadHorizPx,
                    padVertPx = pillPadVertPx,
                )
                is AnnotationShape.Angle -> drawAnnotationAngle(
                    angle = pixelShape,
                    color = angleColor,
                    strokeWidth = stroke,
                    showHandles = drawHandlesForShape,
                    handleRadius = handlePx,
                    draggedHandleIdx = draggedHandleIdx,
                    textPaint = textPaint,
                    cornerPx = pillCornerPx,
                    padHorizPx = pillPadHorizPx,
                    padVertPx = pillPadVertPx,
                )
                is AnnotationShape.Circle -> drawAnnotationCircle(
                    circle = pixelShape,
                    color = circleColor,
                    strokeWidth = stroke,
                    showHandles = drawHandlesForShape,
                    handleRadius = handlePx,
                    draggedHandleIdx = draggedHandleIdx,
                    textPaint = textPaint,
                    toSource = toSource,
                    viewWidth = w,
                    cornerPx = pillCornerPx,
                    padHorizPx = pillPadHorizPx,
                    padVertPx = pillPadVertPx,
                )
            }
        }
    }
}

private fun Offset.toPixel(w: Float, h: Float): Offset = Offset(x * w, y * h)

fun AnnotationShape.toPixelSpace(w: Float, h: Float): AnnotationShape = when (this) {
    is AnnotationShape.Line -> AnnotationShape.Line(start.toPixel(w, h), end.toPixel(w, h))
    is AnnotationShape.Angle -> AnnotationShape.Angle(start.toPixel(w, h), center.toPixel(w, h), end.toPixel(w, h))
    is AnnotationShape.Circle -> AnnotationShape.Circle(center.toPixel(w, h), radius * w)
}

fun Offset.rotateNorm(degrees: Int): Offset {
    return when ((degrees % 360 + 360) % 360) {
        90 -> Offset(1f - y, x)
        180 -> Offset(1f - x, 1f - y)
        270 -> Offset(y, 1f - x)
        else -> this
    }
}

/** Rotates a normalized *vector* (no translation term). Use for drag deltas. */
fun Offset.rotateVectorNorm(degrees: Int): Offset =
    when ((degrees % 360 + 360) % 360) {
        90 -> Offset(-y, x)
        180 -> Offset(-x, -y)
        270 -> Offset(y, -x)
        else -> this
    }

fun AnnotationShape.rotateNorm(degrees: Int, aspect: Float): AnnotationShape {
    val normDegrees = (degrees % 360 + 360) % 360
    if (normDegrees == 0) return this
    
    return when (this) {
        is AnnotationShape.Line -> AnnotationShape.Line(start.rotateNorm(normDegrees), end.rotateNorm(normDegrees))
        is AnnotationShape.Angle -> AnnotationShape.Angle(start.rotateNorm(normDegrees), center.rotateNorm(normDegrees), end.rotateNorm(normDegrees))
        is AnnotationShape.Circle -> {
            val newRadius = if (normDegrees == 90 || normDegrees == 270) {
                radius * aspect // aspect is original width / height
            } else {
                radius
            }
            AnnotationShape.Circle(center.rotateNorm(normDegrees), newRadius)
        }
    }
}

private fun DrawScope.drawHandle(center: Offset, color: Color, radius: Float, isDragged: Boolean = false) {
    val r = if (isDragged) radius * 1.3f else radius
    // Outer white ring
    drawCircle(color = Color.White, radius = r, center = center)
    // Inner colored circle
    drawCircle(color = color, radius = r * 0.65f, center = center)
}

private fun DrawScope.drawPillLabel(
    text: String,
    position: Offset,
    color: Color,
    textPaint: Paint,
    cornerPx: Float,
    padHorizPx: Float,
    padVertPx: Float,
) {
    textPaint.color = color.toArgb()
    val textWidth = textPaint.measureText(text)
    val fontMetrics = textPaint.fontMetrics

    val pillLeft = position.x
    val pillTop = position.y + fontMetrics.ascent - padVertPx
    val pillRight = position.x + textWidth + padHorizPx * 2f
    val pillBottom = position.y + fontMetrics.descent + padVertPx

    drawRoundRect(
        color = Color.Black.copy(alpha = 0.6f),
        topLeft = Offset(pillLeft, pillTop),
        size = Size(pillRight - pillLeft, pillBottom - pillTop),
        cornerRadius = CornerRadius(cornerPx, cornerPx)
    )

    drawContext.canvas.nativeCanvas.drawText(
        text,
        pillLeft + padHorizPx,
        position.y,
        textPaint
    )
}

private fun DrawScope.drawAnnotationLine(
    line: AnnotationShape.Line,
    color: Color,
    strokeWidth: Float,
    showHandles: Boolean,
    handleRadius: Float,
    draggedHandleIdx: Int?,
    textPaint: Paint,
    toSource: Float,
    viewWidth: Float,
    cornerPx: Float,
    padHorizPx: Float,
    padVertPx: Float,
) {
    drawLine(
        color = color,
        start = line.start,
        end = line.end,
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )
    if (showHandles) {
        drawHandle(line.start, color, handleRadius, isDragged = (draggedHandleIdx == 0))
        drawHandle(line.end, color, handleRadius, isDragged = (draggedHandleIdx == 1))

        // Distance text readout in source video pixels (or % if source width is unknown)
        val len = hypot((line.end.x - line.start.x).toDouble(), (line.end.y - line.start.y).toDouble()).toFloat()
        val text = if (toSource > 0f) {
            String.format(Locale.US, "%.0f px", len * toSource)
        } else {
            String.format(Locale.US, "%.1f%%", (len / viewWidth) * 100f)
        }
        val mid = Offset((line.start.x + line.end.x) / 2f, (line.start.y + line.end.y) / 2f)
        drawPillLabel(text, mid + Offset(12f, -12f), color, textPaint, cornerPx, padHorizPx, padVertPx)
    }
}

private fun DrawScope.drawAnnotationAngle(
    angle: AnnotationShape.Angle,
    color: Color,
    strokeWidth: Float,
    showHandles: Boolean,
    handleRadius: Float,
    draggedHandleIdx: Int?,
    textPaint: Paint,
    cornerPx: Float,
    padHorizPx: Float,
    padVertPx: Float,
) {
    // Draw rays
    drawLine(color = color, start = angle.center, end = angle.start, strokeWidth = strokeWidth, cap = StrokeCap.Round)
    drawLine(color = color, start = angle.center, end = angle.end, strokeWidth = strokeWidth, cap = StrokeCap.Round)

    if (showHandles) {
        drawHandle(angle.start, color, handleRadius, isDragged = (draggedHandleIdx == 0))
        drawHandle(angle.center, color, handleRadius, isDragged = (draggedHandleIdx == 1))
        drawHandle(angle.end, color, handleRadius, isDragged = (draggedHandleIdx == 2))
    }

    // Always render angle degree label continuously
    val degrees = angle.degrees()
    val text = String.format(Locale.US, "%.1f°", degrees)
    val textOffset = angle.center + Offset(20f, -20f)
    drawPillLabel(text, textOffset, color, textPaint, cornerPx, padHorizPx, padVertPx)
}

private fun DrawScope.drawAnnotationCircle(
    circle: AnnotationShape.Circle,
    color: Color,
    strokeWidth: Float,
    showHandles: Boolean,
    handleRadius: Float,
    draggedHandleIdx: Int?,
    textPaint: Paint,
    toSource: Float,
    viewWidth: Float,
    cornerPx: Float,
    padHorizPx: Float,
    padVertPx: Float,
) {
    drawCircle(
        color = color,
        radius = circle.radius,
        center = circle.center,
        style = Stroke(width = strokeWidth),
    )
    if (showHandles) {
        drawHandle(circle.center, color, handleRadius, isDragged = (draggedHandleIdx == 0))
        drawHandle(circle.center + Offset(circle.radius, 0f), color, handleRadius, isDragged = (draggedHandleIdx == 1))

        // Radius text readout in source video pixels (or % if source width is unknown)
        val text = if (toSource > 0f) {
            String.format(Locale.US, "r: %.0f px", circle.radius * toSource)
        } else {
            String.format(Locale.US, "r: %.1f%%", (circle.radius / viewWidth) * 100f)
        }
        val textOffset = circle.center + Offset(circle.radius + 12f, -12f)
        drawPillLabel(text, textOffset, color, textPaint, cornerPx, padHorizPx, padVertPx)
    }
}
