package com.alky.hifx.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class NativeSurroundProcessor : BaseAudioProcessor() {
    @Volatile
    private var enabled = false

    @Volatile
    private var surroundMode = AudioEngine.SURROUND_MODE_STEREO

    @Volatile
    private var frontLeftGain = 1f

    @Volatile
    private var frontRightGain = 1f

    @Volatile
    private var centerGain = 1f

    @Volatile
    private var lfeGain = 1f

    @Volatile
    private var surroundLeftGain = 1f

    @Volatile
    private var surroundRightGain = 1f

    @Volatile
    private var rearLeftGain = 1f

    @Volatile
    private var rearRightGain = 1f

    private var sampleRateHz = 48_000
    private var channelCount = 2
    private var nativeBackend: NativeSurroundBackend? = NativeSurroundBackend.createOrNull()

    fun updateConfig(
        enabled: Boolean,
        surroundMode: Int,
        frontLeftGain: Float,
        frontRightGain: Float,
        centerGain: Float,
        lfeGain: Float,
        surroundLeftGain: Float,
        surroundRightGain: Float,
        rearLeftGain: Float,
        rearRightGain: Float
    ) {
        this.enabled = enabled
        this.surroundMode = surroundMode.coerceIn(
            AudioEngine.SURROUND_MODE_STEREO,
            AudioEngine.SURROUND_MODE_7_1
        )
        this.frontLeftGain = frontLeftGain.coerceIn(0f, 2f)
        this.frontRightGain = frontRightGain.coerceIn(0f, 2f)
        this.centerGain = centerGain.coerceIn(0f, 2f)
        this.lfeGain = lfeGain.coerceIn(0f, 2f)
        this.surroundLeftGain = surroundLeftGain.coerceIn(0f, 2f)
        this.surroundRightGain = surroundRightGain.coerceIn(0f, 2f)
        this.rearLeftGain = rearLeftGain.coerceIn(0f, 2f)
        this.rearRightGain = rearRightGain.coerceIn(0f, 2f)
        ensureNativeBackend()?.updateConfig(
            enabled = this.enabled,
            surroundMode = this.surroundMode,
            frontLeftGain = this.frontLeftGain,
            frontRightGain = this.frontRightGain,
            centerGain = this.centerGain,
            lfeGain = this.lfeGain,
            surroundLeftGain = this.surroundLeftGain,
            surroundRightGain = this.surroundRightGain,
            rearLeftGain = this.rearLeftGain,
            rearRightGain = this.rearRightGain
        )
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRateHz = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        ensureNativeBackend()?.configure(sampleRateHz, channelCount)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return
        }
        val inputBytes = inputBuffer.remaining()
        val outputBuffer = replaceOutputBuffer(inputBytes)
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        val processed = ensureNativeBackend()?.process(inputBuffer, outputBuffer)
        if (processed == true) {
            inputBuffer.position(inputBuffer.limit())
            outputBuffer.position(inputBytes)
            outputBuffer.flip()
            return
        }

        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    override fun onFlush() {
        nativeBackend?.flush()
    }

    override fun onReset() {
        enabled = false
        surroundMode = AudioEngine.SURROUND_MODE_STEREO
        frontLeftGain = 1f
        frontRightGain = 1f
        centerGain = 1f
        lfeGain = 1f
        surroundLeftGain = 1f
        surroundRightGain = 1f
        rearLeftGain = 1f
        rearRightGain = 1f
        sampleRateHz = 48_000
        channelCount = 2
        nativeBackend?.release()
        nativeBackend = null
    }

    private fun ensureNativeBackend(): NativeSurroundBackend? {
        if (nativeBackend == null) {
            nativeBackend = NativeSurroundBackend.createOrNull()?.also { backend ->
                backend.configure(sampleRateHz, channelCount)
                backend.updateConfig(
                    enabled = enabled,
                    surroundMode = surroundMode,
                    frontLeftGain = frontLeftGain,
                    frontRightGain = frontRightGain,
                    centerGain = centerGain,
                    lfeGain = lfeGain,
                    surroundLeftGain = surroundLeftGain,
                    surroundRightGain = surroundRightGain,
                    rearLeftGain = rearLeftGain,
                    rearRightGain = rearRightGain
                )
            }
        }
        return nativeBackend
    }
}

private class NativeSurroundBackend private constructor(
    private var handle: Long
) {
    fun configure(sampleRateHz: Int, channelCount: Int) {
        if (handle == 0L) return
        NativeSurroundBridge.nativeConfigure(handle, sampleRateHz, channelCount)
    }

    fun updateConfig(
        enabled: Boolean,
        surroundMode: Int,
        frontLeftGain: Float,
        frontRightGain: Float,
        centerGain: Float,
        lfeGain: Float,
        surroundLeftGain: Float,
        surroundRightGain: Float,
        rearLeftGain: Float,
        rearRightGain: Float
    ) {
        if (handle == 0L) return
        NativeSurroundBridge.nativeUpdateConfig(
            handle = handle,
            enabled = enabled,
            surroundMode = surroundMode,
            frontLeftGain = frontLeftGain,
            frontRightGain = frontRightGain,
            centerGain = centerGain,
            lfeGain = lfeGain,
            surroundLeftGain = surroundLeftGain,
            surroundRightGain = surroundRightGain,
            rearLeftGain = rearLeftGain,
            rearRightGain = rearRightGain
        )
    }

    fun process(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer): Boolean {
        if (handle == 0L || !inputBuffer.isDirect || !outputBuffer.isDirect) {
            return false
        }
        val inputBytes = inputBuffer.remaining()
        if (inputBytes <= 0) {
            return true
        }
        NativeSurroundBridge.nativeProcess(handle, inputBuffer, outputBuffer, inputBytes)
        return true
    }

    fun flush() {
        if (handle != 0L) {
            NativeSurroundBridge.nativeFlush(handle)
        }
    }

    fun release() {
        val localHandle = handle
        if (localHandle == 0L) return
        handle = 0L
        NativeSurroundBridge.nativeRelease(localHandle)
    }

    companion object {
        fun createOrNull(): NativeSurroundBackend? {
            if (!NativeSurroundBridge.isAvailable) {
                return null
            }
            val handle = runCatching { NativeSurroundBridge.nativeCreate() }.getOrDefault(0L)
            return handle.takeIf { it != 0L }?.let(::NativeSurroundBackend)
        }
    }
}

private object NativeSurroundBridge {
    val isAvailable: Boolean by lazy {
        runCatching {
            System.loadLibrary("hifxaudio")
            true
        }.getOrDefault(false)
    }

    external fun nativeCreate(): Long
    external fun nativeRelease(handle: Long)
    external fun nativeConfigure(handle: Long, sampleRateHz: Int, channelCount: Int)
    external fun nativeUpdateConfig(
        handle: Long,
        enabled: Boolean,
        surroundMode: Int,
        frontLeftGain: Float,
        frontRightGain: Float,
        centerGain: Float,
        lfeGain: Float,
        surroundLeftGain: Float,
        surroundRightGain: Float,
        rearLeftGain: Float,
        rearRightGain: Float
    )
    external fun nativeProcess(handle: Long, inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, inputBytes: Int)
    external fun nativeFlush(handle: Long)
}
