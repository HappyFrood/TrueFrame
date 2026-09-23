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

    private fun Bitmap.rotated(degrees: Int): Bitmap {
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(this, 0, 0, width, height, m, true)
    }

    fun needsMetaRotation(rawWidth: Int, rawHeight: Int, metaRotation: Int, metaW: Int, metaH: Int): Boolean {
        if (metaRotation % 180 == 0) return false
        val (dispW, dispH) = metaH to metaW
        if (dispW <= 0 || dispH <= 0 || dispW == dispH) return false
        return (rawWidth > rawHeight) != (dispW > dispH)
    }

    suspend fun exportAndShareFrame(
        context: Context,
        videoUri: String,
        timeUs: Long,
        rotationDegrees: Int,
        annotations: List<AnnotationShape>,
    ): Uri? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var rawBitmap: Bitmap? = null
        var displayBitmap: Bitmap? = null
        var oriented: Bitmap? = null
        var bitmap: Bitmap? = null
        try {
            if (videoUri.startsWith("content://")) {
                retriever.setDataSource(context, Uri.parse(videoUri))
            } else {
                retriever.setDataSource(videoUri)
            }

            // Extract frame bitmap
            rawBitmap = retriever.getFrameAtTime(
                timeUs.coerceAtLeast(0L),
                MediaMetadataRetriever.OPTION_CLOSEST
            ) ?: return@withContext null

            val metaRotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val metaW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val metaH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0

            // Normally the platform has already applied metadata rotation. Only fix up if the bitmap's
            // orientation clearly disagrees with the expected display orientation (non-square frames only).
            val fixMeta = needsMetaRotation(rawBitmap.width, rawBitmap.height, metaRotation, metaW, metaH)
            displayBitmap = if (fixMeta) rawBitmap.rotated(metaRotation) else rawBitmap

            // Aspect of the frame BEFORE user rotation — this is what rotateNorm() expects.
            val frameAspect = displayBitmap.width.toFloat() / displayBitmap.height.toFloat()

            val userRotation = ((rotationDegrees % 360) + 360) % 360
            oriented = if (userRotation != 0) displayBitmap.rotated(userRotation) else displayBitmap

            bitmap = oriented.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(bitmap)
            val width = bitmap.width.toFloat()
            val height = bitmap.height.toFloat()

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
            if (rawBitmap != null && rawBitmap !== bitmap && !rawBitmap.isRecycled) rawBitmap.recycle()
            if (displayBitmap != null && displayBitmap !== bitmap && displayBitmap !== rawBitmap && !displayBitmap.isRecycled) displayBitmap.recycle()
            if (oriented != null && oriented !== bitmap && oriented !== displayBitmap && oriented !== rawBitmap && !oriented.isRecycled) oriented.recycle()
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
