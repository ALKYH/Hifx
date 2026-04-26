package com.example.hifx.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class TopBarVisualizationProcessor : BaseAudioProcessor() {
    private var configuredFormat: AudioProcessor.AudioFormat = AudioProcessor.AudioFormat.NOT_SET
    private var nativeBackend: NativeTopBarVisualizerBackend? = NativeTopBarVisualizerBackend.createOrNull()

    fun fillSnapshot(mode: TopBarVisualizationMode, target: FloatArray) {
        if (target.isEmpty()) {
            return
        }
        target.fill(0f)
        nativeBackend?.fillSnapshot(mode, target)
    }

    fun clearSnapshot() {
        nativeBackend?.reset()
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        configuredFormat = inputAudioFormat
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT -> inputAudioFormat

            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return
        }
        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
        val analysisBuffer = inputBuffer.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN)
        when (configuredFormat.encoding) {
            C.ENCODING_PCM_16BIT -> {
                nativeBackend?.analyzePcm16(
                    buffer = analysisBuffer,
                    lengthBytes = analysisBuffer.remaining(),
                    channelCount = configuredFormat.channelCount.coerceAtLeast(1)
                )
            }

            C.ENCODING_PCM_FLOAT -> {
                nativeBackend?.analyzeFloat(
                    buffer = analysisBuffer,
                    sampleCount = analysisBuffer.remaining() / 4,
                    channelCount = configuredFormat.channelCount.coerceAtLeast(1)
                )
            }
        }
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    override fun onFlush() {
        nativeBackend?.reset()
    }

    override fun onReset() {
        nativeBackend?.release()
        nativeBackend = NativeTopBarVisualizerBackend.createOrNull()
        configuredFormat = AudioProcessor.AudioFormat.NOT_SET
    }
}

private class NativeTopBarVisualizerBackend private constructor(
    private var handle: Long
) {
    fun analyzePcm16(buffer: ByteBuffer, lengthBytes: Int, channelCount: Int) {
        if (handle == 0L || !buffer.isDirect) {
            return
        }
        NativeTopBarVisualizerBridge.nativeAnalyzePcm16(handle, buffer, lengthBytes, channelCount)
    }

    fun analyzeFloat(buffer: ByteBuffer, sampleCount: Int, channelCount: Int) {
        if (handle == 0L || !buffer.isDirect) {
            return
        }
        NativeTopBarVisualizerBridge.nativeAnalyzeFloat(handle, buffer, sampleCount, channelCount)
    }

    fun fillSnapshot(mode: TopBarVisualizationMode, target: FloatArray) {
        if (handle == 0L || target.isEmpty()) {
            return
        }
        val nativeMode = mode.nativeMode
        if (nativeMode < 0) {
            return
        }
        NativeTopBarVisualizerBridge.nativeFillSnapshot(handle, nativeMode, target)
    }

    fun reset() {
        if (handle != 0L) {
            NativeTopBarVisualizerBridge.nativeReset(handle)
        }
    }

    fun release() {
        val localHandle = handle
        if (localHandle == 0L) {
            return
        }
        handle = 0L
        NativeTopBarVisualizerBridge.nativeRelease(localHandle)
    }

    companion object {
        fun createOrNull(): NativeTopBarVisualizerBackend? {
            if (!NativeTopBarVisualizerBridge.isAvailable) {
                return null
            }
            val handle = runCatching { NativeTopBarVisualizerBridge.nativeCreate() }.getOrDefault(0L)
            return handle.takeIf { it != 0L }?.let(::NativeTopBarVisualizerBackend)
        }
    }
}

private object NativeTopBarVisualizerBridge {
    val isAvailable: Boolean = runCatching {
        System.loadLibrary("hifxaudio")
        true
    }.getOrDefault(false)

    external fun nativeCreate(): Long
    external fun nativeRelease(handle: Long)
    external fun nativeReset(handle: Long)
    external fun nativeAnalyzePcm16(handle: Long, buffer: ByteBuffer, lengthBytes: Int, channelCount: Int)
    external fun nativeAnalyzeFloat(handle: Long, buffer: ByteBuffer, sampleCount: Int, channelCount: Int)
    external fun nativeFillSnapshot(handle: Long, mode: Int, target: FloatArray)
}
