package com.example.hifx.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

object WaveformAnalyzer {
    suspend fun analyze(
        context: Context,
        uri: Uri,
        durationMs: Long,
        targetPoints: Int = 220
    ): FloatArray = withContext(Dispatchers.IO) {
        val safePoints = targetPoints.coerceIn(64, 512)
        if (durationMs <= 0L) {
            return@withContext fallback(uri, safePoints)
        }
        runCatching {
            decodeWaveform(context, uri, durationMs, safePoints)
        }.getOrElse {
            fallback(uri, safePoints)
        }
    }

    private fun decodeWaveform(
        context: Context,
        uri: Uri,
        durationMs: Long,
        targetPoints: Int
    ): FloatArray {
        val sums = FloatArray(targetPoints)
        val counts = IntArray(targetPoints)

        val extractor = MediaExtractor()
        val codec: MediaCodec
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { index ->
                val mime = extractor.getTrackFormat(index).getString(android.media.MediaFormat.KEY_MIME).orEmpty()
                mime.startsWith("audio/")
            } ?: return fallback(uri, targetPoints)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (_: Throwable) {
            runCatching { extractor.release() }
            return fallback(uri, targetPoints)
        }

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(8_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val sampleSize = extractor.readSampleData(inputBuffer ?: ByteBuffer.allocate(0), 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, 8_000)
                when {
                    outputIndex >= 0 -> {
                        if (info.size > 0) {
                            val outputBuffer = codec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null) {
                                val amp = readAmplitude(outputBuffer, info)
                                val timeMs = (info.presentationTimeUs / 1000L).coerceIn(0L, durationMs)
                                val ratio = timeMs.toFloat() / durationMs.toFloat().coerceAtLeast(1f)
                                val bucket = (ratio * (targetPoints - 1)).roundToInt().coerceIn(0, targetPoints - 1)
                                sums[bucket] += amp
                                counts[bucket] += 1
                            }
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        }
                    }

                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }

        val levels = FloatArray(targetPoints)
        var maxValue = 0f
        for (i in 0 until targetPoints) {
            val value = if (counts[i] > 0) sums[i] / counts[i] else 0f
            levels[i] = value
            maxValue = max(maxValue, value)
        }
        if (maxValue < 0.0001f) {
            return fallback(uri, targetPoints)
        }
        for (i in levels.indices) {
            val normalized = (levels[i] / maxValue).coerceIn(0f, 1f)
            levels[i] = (0.08f + normalized * 0.92f).coerceIn(0.08f, 1f)
        }
        return levels
    }

    private fun readAmplitude(buffer: ByteBuffer, info: MediaCodec.BufferInfo): Float {
        val copy = buffer.duplicate()
        copy.position(info.offset)
        copy.limit(info.offset + info.size)
        if (copy.remaining() < 2) return 0f

        var sum = 0f
        var samples = 0
        while (copy.remaining() >= 2) {
            val low = copy.get().toInt() and 0xFF
            val high = copy.get().toInt()
            val sample = (high shl 8) or low
            sum += abs(sample.toShort().toInt() / 32768f)
            samples++
            if (samples > 24_000) break
        }
        return if (samples == 0) 0f else (sum / samples).coerceIn(0f, 1f)
    }

    private fun fallback(uri: Uri, targetPoints: Int): FloatArray {
        val seed = uri.toString().hashCode().toLong().let { if (it == 0L) 1L else it }
        var value = seed
        val output = FloatArray(targetPoints)
        for (i in 0 until targetPoints) {
            value = (value * 1103515245L + 12345L) and 0x7fffffff
            val r = (value % 1000L) / 1000f
            val shaped = 0.2f + (0.8f * r)
            output[i] = shaped.coerceIn(0.08f, 1f)
        }
        return output
    }
}
