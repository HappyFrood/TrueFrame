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
 * Remuxes the source video track into app storage using MediaExtractor + MediaMuxer.
 *
 * This is **not** a transcode: compressed samples are copied as-is at full resolution, with no
 * re-encode and no downscale. Rotation is carried over as an orientation hint (not baked into the
 * pixels). Color / HDR metadata, profile, level and frame rate are copied from the source track
 * format so 10-bit and HDR clips keep correct colors. Audio is dropped.
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
     * Starts remuxing [sourceUri] into a proxy file at [outputPath].
     * Should be called from a Foreground Service to prevent OS killing the process.
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
            if (format.containsKey("csd-2")) {
                cleanFormat.setByteBuffer("csd-2", format.getByteBuffer("csd-2"))
            }
            copyMetadataKeys(format, cleanFormat)

            val actualOutputPath = outputPath.removePrefix("file://")
            muxer = MediaMuxer(actualOutputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotation != 0) {
                muxer.setOrientationHint(rotation)
            }
            val outputTrack = muxer.addTrack(cleanFormat)
            muxer.start()
            
            val buffer = ByteBuffer.allocate(sampleBufferSize(format))
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
                muxer.stop()
                muxer.release()
                muxer = null
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

    private fun copyMetadataKeys(from: MediaFormat, to: MediaFormat) {
        for (key in INT_KEYS) {
            if (from.containsKey(key)) runCatching { to.setInteger(key, from.getInteger(key)) }
        }
        if (from.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            // Frame rate may be stored as either an int or a float depending on the extractor.
            runCatching { to.setInteger(MediaFormat.KEY_FRAME_RATE, from.getInteger(MediaFormat.KEY_FRAME_RATE)) }
                .onFailure { runCatching { to.setFloat(MediaFormat.KEY_FRAME_RATE, from.getFloat(MediaFormat.KEY_FRAME_RATE)) } }
        }
        if (from.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) {
            runCatching { to.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, from.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO)) }
        }
    }

    fun cancel() {
        isCancelled = true
        _state.value = TranscodeState.Idle
    }

    companion object {
        /** Fallback sample buffer when the track doesn't report max-input-size (high-bitrate 4K keyframes fit). */
        const val DEFAULT_SAMPLE_BUFFER_BYTES = 32 * 1024 * 1024

        private val INT_KEYS = listOf(
            MediaFormat.KEY_COLOR_STANDARD,
            MediaFormat.KEY_COLOR_TRANSFER,
            MediaFormat.KEY_COLOR_RANGE,
            MediaFormat.KEY_PROFILE,
            MediaFormat.KEY_LEVEL,
        )

        /** Buffer size from the track's max-input-size when present, else [DEFAULT_SAMPLE_BUFFER_BYTES]. */
        fun sampleBufferSize(maxInputSize: Int?): Int =
            if (maxInputSize != null && maxInputSize > 0) maxInputSize else DEFAULT_SAMPLE_BUFFER_BYTES

        private fun sampleBufferSize(format: MediaFormat): Int = sampleBufferSize(
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else null
        )
    }
}
