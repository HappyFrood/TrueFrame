package com.example.trueframe.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import com.example.trueframe.core.annotation.AnnotationShape
import com.example.trueframe.core.annotation.rotateNorm
import com.example.trueframe.core.annotation.toPixelSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.hypot

object FrameExporter {

    suspend fun exportAndShareFrame(
        context: Context,
        videoUri: String,
        timeMs: Long,
        rotationDegrees: Int,
        annotations: List<AnnotationShape>,
    ): Uri? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            if (videoUri.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(videoUri))
            } else {
                retriever.setDataSource(videoUri)
            }

            // Extract frame bitmap
            val rawBitmap = retriever.getFrameAtTime(
                timeMs.coerceAtLeast(0L) * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST
            ) ?: return@withContext null

            // Read video metadata rotation
            val metaRotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val totalRotation = (rotationDegrees + metaRotation) % 360

            val orientedBitmap = if (totalRotation != 0) {
                val matrix = Matrix().apply { postRotate(totalRotation.toFloat()) }
                Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
            } else {
                rawBitmap
            }

            // Create mutable bitmap overlay
            val bitmap = orientedBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(bitmap)
            val width = bitmap.width.toFloat()
            val height = bitmap.height.toFloat()
            val frameAspect = width / height

            // Paint setup
            val strokeWidthPx = (width * 0.005f).coerceAtLeast(4f)

            val linePaint = Paint().apply {
                color = Color(0xFFFF6D00).toArgb()
                strokeWidth = strokeWidthPx
                style = Paint.Style.STROKE
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
            }
            val anglePaint = Paint().apply {
                color = Color(0xFF00E676).toArgb()
                strokeWidth = strokeWidthPx
                style = Paint.Style.STROKE
                isAntiAlias = true
                strokeCap = Paint.Cap.ROUND
            }
            val circlePaint = Paint().apply {
                color = Color(0xFF448AFF).toArgb()
                strokeWidth = strokeWidthPx
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
            val textPaint = Paint().apply {
                color = android.graphics.Color.YELLOW
                textSize = (width * 0.025f).coerceAtLeast(24f)
                isAntiAlias = true
                isFakeBoldText = true
                setShadowLayer(6f, 2f, 2f, android.graphics.Color.BLACK)
            }

            // Render annotations in bitmap pixel space
            annotations.forEach { shape ->
                val pixelShape = shape
                    .rotateNorm(rotationDegrees, frameAspect)
                    .toPixelSpace(width, height)

                when (pixelShape) {
                    is AnnotationShape.Line -> {
                        canvas.drawLine(pixelShape.start.x, pixelShape.start.y, pixelShape.end.x, pixelShape.end.y, linePaint)
                        val len = hypot((pixelShape.end.x - pixelShape.start.x).toDouble(), (pixelShape.end.y - pixelShape.start.y).toDouble()).toFloat()
                        val text = String.format(Locale.US, "%.0f px", len)
                        val midX = (pixelShape.start.x + pixelShape.end.x) / 2f
                        val midY = (pixelShape.start.y + pixelShape.end.y) / 2f
                        canvas.drawText(text, midX + 16f, midY - 16f, textPaint)
                    }
                    is AnnotationShape.Angle -> {
                        canvas.drawLine(pixelShape.center.x, pixelShape.center.y, pixelShape.start.x, pixelShape.start.y, anglePaint)
                        canvas.drawLine(pixelShape.center.x, pixelShape.center.y, pixelShape.end.x, pixelShape.end.y, anglePaint)
                        val degrees = pixelShape.degrees()
                        val text = String.format(Locale.US, "%.1f°", degrees)
                        canvas.drawText(text, pixelShape.center.x + 24f, pixelShape.center.y - 24f, textPaint)
                    }
                    is AnnotationShape.Circle -> {
                        canvas.drawCircle(pixelShape.center.x, pixelShape.center.y, pixelShape.radius, circlePaint)
                        val text = String.format(Locale.US, "r: %.0f px", pixelShape.radius)
                        canvas.drawText(text, pixelShape.center.x + pixelShape.radius + 16f, pixelShape.center.y - 16f, textPaint)
                    }
                }
            }

            // Save to shared_images directory
            val sharedDir = File(context.cacheDir, "shared_images").apply { if (!exists()) mkdirs() }
            val imageFile = File(sharedDir, "trueframe_${System.currentTimeMillis()}.jpg")
            FileOutputStream(imageFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }

            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", imageFile)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
