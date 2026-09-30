package com.example.trueframe.core.video

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads frame-rate metadata from a video file. Always runs on [Dispatchers.IO].
 *
 * - [Result.computedFps]: average playback fps from the container (frame count / duration),
 *   used when the player doesn't report a frame rate.
 * - [Result.captureFps]: `METADATA_KEY_CAPTURE_FRAMERATE`, set by many phones for slow-motion
 *   clips that are saved as a lower-fps file.
 */
object VideoProbe {

    data class Result(val computedFps: Float?, val captureFps: Float?)

    suspend fun probe(context: Context, uri: String): Result = withContext(Dispatchers.IO) {
        var captureFps: Float? = null
        var computedFps: Float? = null
        val retriever = MediaMetadataRetriever()
        try {
            setSource(retriever, context, uri)
            captureFps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                ?.toFloatOrNull()?.takeIf { it > 0f }
            val frameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull()
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (frameCount != null && durationMs != null) {
                computedFps = FrameMath.fpsFromSampleCount(frameCount, durationMs * 1000L)
            }
        } catch (_: Exception) {
            // Fall through to MediaExtractor.
        } finally {
            runCatching { retriever.release() }
        }
        if (computedFps == null) {
            computedFps = runCatching { countSamples(context, uri) }.getOrNull()
        }
        Result(computedFps = computedFps, captureFps = captureFps)
    }

    private fun countSamples(context: Context, uri: String): Float? {
        val extractor = MediaExtractor()
        try {
            if (uri.startsWith("content://")) {
                extractor.setDataSource(context, Uri.parse(uri), null)
            } else {
                extractor.setDataSource(uri.removePrefix("file://"))
            }
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: return null
            extractor.selectTrack(track)
            var count = 0L
            var firstUs = -1L
            var lastUs = -1L
            while (extractor.sampleTime >= 0L) {
                if (firstUs < 0L) firstUs = extractor.sampleTime
                lastUs = maxOf(lastUs, extractor.sampleTime)
                count++
                if (!extractor.advance()) break
            }
            // N samples span N-1 frame intervals between the first and last timestamps.
            return FrameMath.fpsFromSampleCount(count - 1, lastUs - firstUs)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun setSource(retriever: MediaMetadataRetriever, context: Context, uri: String) {
        if (uri.startsWith("content://")) {
            retriever.setDataSource(context, Uri.parse(uri))
        } else {
            retriever.setDataSource(uri.removePrefix("file://"))
        }
    }
}
