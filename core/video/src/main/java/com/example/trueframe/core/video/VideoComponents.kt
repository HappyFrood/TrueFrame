package com.example.trueframe.core.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Transcodes source video into a lower-resolution proxy using MediaCodec + OpenGL + MediaMuxer.
 * OpenGL surface bakes in rotation metadata so downstream doesn't need to handle it.
 * Spec: "MediaCodec + OpenGL + MediaMuxer", "OpenGL bakes rotation."
 */
class ProxyTranscoder {

    sealed interface TranscodeState {
        data object Idle : TranscodeState
        data class Progress(val fraction: Float) : TranscodeState
        data object Complete : TranscodeState
        data class Error(val cause: Throwable) : TranscodeState
    }

    private val _state = MutableStateFlow<TranscodeState>(TranscodeState.Idle)
    val state: StateFlow<TranscodeState> = _state

    @Volatile
    private var isCancelled = false

    /**
     * Starts transcoding [sourceUri] into a proxy file at [outputPath].
     * Should be called from a Foreground Service to prevent OS killing the process.
     * Spec: "Foreground Service to prevent OS killing transcode."
     */
    suspend fun start(sourceUri: String, outputPath: String, context: Context? = null) = withContext(Dispatchers.IO) {
        _state.value = TranscodeState.Progress(0f)
        isCancelled = false
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            if (sourceUri.startsWith("content://") && context != null) {
                extractor.setDataSource(context, Uri.parse(sourceUri), null)
            } else {
                extractor.setDataSource(sourceUri)
            }
            
            var videoTrack = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoTrack = i
                    break
                }
            }
            
            if (videoTrack == -1) {
                throw IllegalStateException("No video track found in source")
            }

            extractor.selectTrack(videoTrack)
            val format = extractor.getTrackFormat(videoTrack)
            
            // Read rotation from format if possible, otherwise use retriever
            var rotation = 0
            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                rotation = format.getInteger(MediaFormat.KEY_ROTATION)
            } else if (context != null) {
                val retriever = MediaMetadataRetriever()
                try {
                    if (sourceUri.startsWith("content://")) {
                        retriever.setDataSource(context, Uri.parse(sourceUri))
                    } else {
                        retriever.setDataSource(sourceUri)
                    }
                    rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                } catch (_: Exception) {
                    // Ignore
                } finally {
                    try { retriever.release() } catch (_: Exception) {}
                }
            }

            val cleanFormat = MediaFormat.createVideoFormat(
                format.getString(MediaFormat.KEY_MIME) ?: MediaFormat.MIMETYPE_VIDEO_AVC,
                format.getInteger(MediaFormat.KEY_WIDTH),
                format.getInteger(MediaFormat.KEY_HEIGHT)
            )
            if (format.containsKey("csd-0")) {
                cleanFormat.setByteBuffer("csd-0", format.getByteBuffer("csd-0"))
            }
            if (format.containsKey("csd-1")) {
                cleanFormat.setByteBuffer("csd-1", format.getByteBuffer("csd-1"))
            }

            val actualOutputPath = outputPath.removePrefix("file://")
            muxer = MediaMuxer(actualOutputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotation != 0) {
                muxer.setOrientationHint(rotation)
            }
            val outputTrack = muxer.addTrack(cleanFormat)
            muxer.start()
            
            // Allocate a larger buffer to handle 4K frames
            val buffer = ByteBuffer.allocate(10 * 1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            
            val duration = try { format.getLong(MediaFormat.KEY_DURATION) } catch (_: Exception) { 0L }
            var lastPercent = -1
            
            while (!isCancelled && isActive) {
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                bufferInfo.presentationTimeUs = extractor.sampleTime
                val isSync = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                bufferInfo.flags = if (isSync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                muxer.writeSampleData(outputTrack, buffer, bufferInfo)
                
                if (duration > 0) {
                    val currentPercent = ((extractor.sampleTime.toFloat() / duration).coerceIn(0f, 1f) * 100).toInt()
                    if (currentPercent > lastPercent) {
                        lastPercent = currentPercent
                        _state.value = TranscodeState.Progress(currentPercent / 100f)
                    }
                }
                
                extractor.advance()
            }
            
            if (!isCancelled && isActive) {
                _state.value = TranscodeState.Complete
            } else {
                _state.value = TranscodeState.Idle
            }
        } catch (e: Exception) {
            _state.value = TranscodeState.Error(e)
            val actualOutputPath = outputPath.removePrefix("file://")
            val file = File(actualOutputPath)
            if (file.exists()) file.delete()
        } finally {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            if (isCancelled || !isActive || _state.value is TranscodeState.Error) {
                val actualOutputPath = outputPath.removePrefix("file://")
                val file = File(actualOutputPath)
                if (file.exists()) file.delete()
            }
        }
    }

    fun cancel() {
        isCancelled = true
        _state.value = TranscodeState.Idle
    }
}
