package com.alky.hifx.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal class VocalIsolationProcessor : BaseAudioProcessor() {
    @Volatile
    private var vocalRemovalEnabled = false

    @Volatile
    private var vocalKeyShiftSemitones = 0

    @Volatile
    private var vocalBandLowCutHz = 140

    @Volatile
    private var vocalBandHighCutHz = 4200

    private var sampleRateHz = 48_000
    private var channelCount = 2

    private val kotlinFallback = KotlinVocalIsolationBackend()
    private var nativeBackend: NativeVocalIsolationBackend? = NativeVocalIsolationBackend.createOrNull()

    fun updateConfig(
        vocalRemovalEnabled: Boolean,
        vocalKeyShiftSemitones: Int,
        vocalBandLowCutHz: Int,
        vocalBandHighCutHz: Int
    ) {
        this.vocalRemovalEnabled = vocalRemovalEnabled
        this.vocalKeyShiftSemitones = vocalKeyShiftSemitones.coerceIn(-24, 24)
        val normalizedLow = vocalBandLowCutHz.coerceIn(60, 7900)
        val normalizedHigh = vocalBandHighCutHz.coerceIn(normalizedLow + 100, 8000)
        this.vocalBandLowCutHz = normalizedLow
        this.vocalBandHighCutHz = normalizedHigh
        ensureNativeBackend()?.updateConfig(
            vocalRemovalEnabled = this.vocalRemovalEnabled,
            vocalKeyShiftSemitones = this.vocalKeyShiftSemitones,
            vocalBandLowCutHz = this.vocalBandLowCutHz,
            vocalBandHighCutHz = this.vocalBandHighCutHz
        )
        kotlinFallback.updateConfig(
            vocalRemovalEnabled = this.vocalRemovalEnabled,
            vocalKeyShiftSemitones = this.vocalKeyShiftSemitones,
            vocalBandLowCutHz = this.vocalBandLowCutHz,
            vocalBandHighCutHz = this.vocalBandHighCutHz
        )
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        sampleRateHz = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        ensureNativeBackend()?.configure(sampleRateHz, channelCount)
        kotlinFallback.configure(sampleRateHz)
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

        val nativeProcessed = ensureNativeBackend()?.process(inputBuffer, outputBuffer)
        if (nativeProcessed == true) {
            inputBuffer.position(inputBuffer.limit())
            outputBuffer.position(inputBytes)
            outputBuffer.flip()
            return
        }

        kotlinFallback.process(inputBuffer, outputBuffer)
        outputBuffer.flip()
    }

    override fun onFlush() {
        nativeBackend?.flush()
        kotlinFallback.flush()
    }

    override fun onReset() {
        vocalRemovalEnabled = false
        vocalKeyShiftSemitones = 0
        vocalBandLowCutHz = 140
        vocalBandHighCutHz = 4200
        sampleRateHz = 48_000
        channelCount = 2
        nativeBackend?.release()
        nativeBackend = null
        kotlinFallback.reset()
    }

    private fun ensureNativeBackend(): NativeVocalIsolationBackend? {
        if (nativeBackend == null) {
            nativeBackend = NativeVocalIsolationBackend.createOrNull()?.also { backend ->
                backend.configure(sampleRateHz, channelCount)
                backend.updateConfig(
                    vocalRemovalEnabled = vocalRemovalEnabled,
                    vocalKeyShiftSemitones = vocalKeyShiftSemitones,
                    vocalBandLowCutHz = vocalBandLowCutHz,
                    vocalBandHighCutHz = vocalBandHighCutHz
                )
            }
        }
        return nativeBackend
    }
}

private class NativeVocalIsolationBackend private constructor(
    private var handle: Long
) {
    fun configure(sampleRateHz: Int, channelCount: Int) {
        if (handle == 0L) return
        NativeVocalIsolationBridge.nativeConfigure(handle, sampleRateHz, channelCount)
    }

    fun updateConfig(
        vocalRemovalEnabled: Boolean,
        vocalKeyShiftSemitones: Int,
        vocalBandLowCutHz: Int,
        vocalBandHighCutHz: Int
    ) {
        if (handle == 0L) return
        NativeVocalIsolationBridge.nativeUpdateConfig(
            handle = handle,
            vocalRemovalEnabled = vocalRemovalEnabled,
            vocalKeyShiftSemitones = vocalKeyShiftSemitones,
            vocalBandLowCutHz = vocalBandLowCutHz,
            vocalBandHighCutHz = vocalBandHighCutHz
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
        NativeVocalIsolationBridge.nativeProcess(handle, inputBuffer, outputBuffer, inputBytes)
        return true
    }

    fun flush() {
        if (handle != 0L) {
            NativeVocalIsolationBridge.nativeFlush(handle)
        }
    }

    fun release() {
        val localHandle = handle
        if (localHandle == 0L) {
            return
        }
        handle = 0L
        NativeVocalIsolationBridge.nativeRelease(localHandle)
    }

    companion object {
        fun createOrNull(): NativeVocalIsolationBackend? {
            if (!NativeVocalIsolationBridge.isAvailable) {
                return null
            }
            val handle = runCatching { NativeVocalIsolationBridge.nativeCreate() }.getOrDefault(0L)
            return handle.takeIf { it != 0L }?.let(::NativeVocalIsolationBackend)
        }
    }
}

private object NativeVocalIsolationBridge {
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
        vocalRemovalEnabled: Boolean,
        vocalKeyShiftSemitones: Int,
        vocalBandLowCutHz: Int,
        vocalBandHighCutHz: Int
    )
    external fun nativeProcess(handle: Long, inputBuffer: ByteBuffer, outputBuffer: ByteBuffer, inputBytes: Int)
    external fun nativeFlush(handle: Long)
}

private class KotlinVocalIsolationBackend {
    private var vocalRemovalEnabled = false
    private var vocalKeyShiftSemitones = 0
    private var vocalBandLowCutHz = 140
    private var vocalBandHighCutHz = 4200
    private var sampleRateHz = 48_000
    private var leftHighPass = BiquadFilter.passThrough()
    private var leftLowPass = BiquadFilter.passThrough()
    private var rightHighPass = BiquadFilter.passThrough()
    private var rightLowPass = BiquadFilter.passThrough()
    private var vocalPitchShifter = DualDelayPitchShifter(sampleRateHz)

    fun configure(sampleRateHz: Int) {
        this.sampleRateHz = sampleRateHz
        rebuildFilters()
        vocalPitchShifter = DualDelayPitchShifter(sampleRateHz).also {
            it.setPitchSemitones(vocalKeyShiftSemitones)
        }
    }

    fun updateConfig(
        vocalRemovalEnabled: Boolean,
        vocalKeyShiftSemitones: Int,
        vocalBandLowCutHz: Int,
        vocalBandHighCutHz: Int
    ) {
        this.vocalRemovalEnabled = vocalRemovalEnabled
        this.vocalKeyShiftSemitones = vocalKeyShiftSemitones.coerceIn(-24, 24)
        val normalizedLow = vocalBandLowCutHz.coerceIn(60, 7900)
        val normalizedHigh = vocalBandHighCutHz.coerceIn(normalizedLow + 100, 8000)
        val bandChanged =
            this.vocalBandLowCutHz != normalizedLow || this.vocalBandHighCutHz != normalizedHigh
        this.vocalBandLowCutHz = normalizedLow
        this.vocalBandHighCutHz = normalizedHigh
        vocalPitchShifter.setPitchSemitones(this.vocalKeyShiftSemitones)
        if (bandChanged) {
            rebuildFilters()
        }
    }

    fun process(inputBuffer: ByteBuffer, outputBuffer: ByteBuffer) {
        val keyShift = vocalKeyShiftSemitones
        val shouldRemoveOriginalVocal = vocalRemovalEnabled || keyShift != 0
        val shouldReinjectShiftedVocal = keyShift != 0
        if (!shouldRemoveOriginalVocal && !shouldReinjectShiftedVocal) {
            outputBuffer.put(inputBuffer)
            return
        }

        while (inputBuffer.remaining() >= 4) {
            val left = shortToFloat(inputBuffer.short)
            val right = shortToFloat(inputBuffer.short)

            val filteredLeft = leftLowPass.process(leftHighPass.process(left))
            val filteredRight = rightLowPass.process(rightHighPass.process(right))
            val isolatedVocal = extractCenteredVocal(filteredLeft, filteredRight)

            var outputLeft = left
            var outputRight = right
            if (shouldRemoveOriginalVocal) {
                outputLeft -= isolatedVocal
                outputRight -= isolatedVocal
            }
            if (shouldReinjectShiftedVocal) {
                val shiftedVocal = vocalPitchShifter.processSample(isolatedVocal)
                outputLeft += shiftedVocal
                outputRight += shiftedVocal
            }

            outputBuffer.putShort(floatToShort(outputLeft))
            outputBuffer.putShort(floatToShort(outputRight))
        }
    }

    fun flush() {
        resetDspState()
    }

    fun reset() {
        vocalRemovalEnabled = false
        vocalKeyShiftSemitones = 0
        vocalBandLowCutHz = 140
        vocalBandHighCutHz = 4200
        sampleRateHz = 48_000
        leftHighPass = BiquadFilter.passThrough()
        leftLowPass = BiquadFilter.passThrough()
        rightHighPass = BiquadFilter.passThrough()
        rightLowPass = BiquadFilter.passThrough()
        vocalPitchShifter = DualDelayPitchShifter(sampleRateHz)
    }

    private fun resetDspState() {
        leftHighPass.reset()
        leftLowPass.reset()
        rightHighPass.reset()
        rightLowPass.reset()
        vocalPitchShifter.reset()
    }

    private fun rebuildFilters() {
        val lowCut = vocalBandLowCutHz.toFloat().coerceIn(60f, 7900f)
        val highCut = vocalBandHighCutHz.toFloat().coerceIn(lowCut + 100f, 8000f)
        leftHighPass = BiquadFilter.highPass(sampleRateHz.toFloat(), lowCut, 0.707f)
        leftLowPass = BiquadFilter.lowPass(sampleRateHz.toFloat(), highCut, 0.707f)
        rightHighPass = BiquadFilter.highPass(sampleRateHz.toFloat(), lowCut, 0.707f)
        rightLowPass = BiquadFilter.lowPass(sampleRateHz.toFloat(), highCut, 0.707f)
        resetDspState()
    }

    private fun extractCenteredVocal(filteredLeft: Float, filteredRight: Float): Float {
        val center = (filteredLeft + filteredRight) * 0.5f
        val absLeft = abs(filteredLeft)
        val absRight = abs(filteredRight)
        val energy = absLeft + absRight + 1.0e-5f
        val similarity = 1f - (abs(filteredLeft - filteredRight) / energy).coerceIn(0f, 1f)
        val balance = (min(absLeft, absRight) / max(max(absLeft, absRight), 1.0e-5f)).coerceIn(0f, 1f)
        val confidence = (similarity * balance).coerceIn(0f, 1f)
        return (center * confidence).coerceIn(-1f, 1f)
    }

    private fun shortToFloat(value: Short): Float {
        return (value.toInt() / 32768f).coerceIn(-1f, 1f)
    }

    private fun floatToShort(value: Float): Short {
        val clamped = (max(-1f, min(1f, value)) * 32767f).toInt()
        return clamped.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private class DualDelayPitchShifter(sampleRateHz: Int) {
        private val ringBuffer = FloatArray(RING_BUFFER_SIZE)
        private var writeIndex = 0
        private var phase = 0f
        private var pitchRatio = 1f
        private var phaseIncrement = 0f
        private var windowSamples = computeWindowSamples(sampleRateHz)
        private var baseDelaySamples = windowSamples + 8

        fun setPitchSemitones(semitones: Int) {
            pitchRatio = 2.0.pow(semitones / 12.0).toFloat()
            phaseIncrement = if (abs(pitchRatio - 1f) < 0.0001f) {
                0f
            } else {
                (abs(1f - pitchRatio) / windowSamples.coerceAtLeast(1f)).coerceAtLeast(1f / 16384f)
            }
        }

        fun processSample(input: Float): Float {
            ringBuffer[writeIndex] = input
            if (abs(pitchRatio - 1f) < 0.0001f) {
                writeIndex = (writeIndex + 1) and RING_BUFFER_MASK
                return input
            }

            phase += phaseIncrement
            if (phase >= 1f) {
                phase -= 1f
            }
            val phaseB = (phase + 0.5f) % 1f
            val delayA = computeDelaySamples(phase)
            val delayB = computeDelaySamples(phaseB)
            val sampleA = readDelayed(delayA)
            val sampleB = readDelayed(delayB)
            val gainA = hannWindow(phase)
            val gainB = hannWindow(phaseB)
            val output = (sampleA * gainA + sampleB * gainB).coerceIn(-1f, 1f)

            writeIndex = (writeIndex + 1) and RING_BUFFER_MASK
            return output
        }

        fun reset() {
            ringBuffer.fill(0f)
            writeIndex = 0
            phase = 0f
        }

        private fun computeDelaySamples(localPhase: Float): Float {
            val sweep = if (pitchRatio >= 1f) {
                (1f - localPhase) * windowSamples
            } else {
                localPhase * windowSamples
            }
            return baseDelaySamples + sweep
        }

        private fun readDelayed(delaySamples: Float): Float {
            val readPos = writeIndex - delaySamples
            var wrapped = readPos
            while (wrapped < 0f) {
                wrapped += RING_BUFFER_SIZE
            }
            while (wrapped >= RING_BUFFER_SIZE) {
                wrapped -= RING_BUFFER_SIZE
            }
            val indexA = wrapped.toInt() and RING_BUFFER_MASK
            val indexB = (indexA + 1) and RING_BUFFER_MASK
            val frac = wrapped - wrapped.toInt()
            return ringBuffer[indexA] * (1f - frac) + ringBuffer[indexB] * frac
        }

        private fun hannWindow(localPhase: Float): Float {
            return (0.5f - 0.5f * cos((localPhase * TWO_PI).toFloat())).coerceIn(0f, 1f)
        }

        companion object {
            private const val RING_BUFFER_SIZE = 8192
            private const val RING_BUFFER_MASK = RING_BUFFER_SIZE - 1
            private const val TWO_PI = PI * 2.0

            private fun computeWindowSamples(sampleRateHz: Int): Float {
                return (sampleRateHz * 0.03f).coerceIn(256f, 2048f)
            }
        }
    }

    private class BiquadFilter(
        private val b0: Float,
        private val b1: Float,
        private val b2: Float,
        private val a1: Float,
        private val a2: Float
    ) {
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        fun process(input: Float): Float {
            val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = input
            y2 = y1
            y1 = output
            return output
        }

        fun reset() {
            x1 = 0f
            x2 = 0f
            y1 = 0f
            y2 = 0f
        }

        companion object {
            fun passThrough(): BiquadFilter = BiquadFilter(1f, 0f, 0f, 0f, 0f)

            fun lowPass(sampleRateHz: Float, cutoffHz: Float, q: Float): BiquadFilter {
                val omega = (2.0 * PI * cutoffHz / sampleRateHz).toFloat()
                val alpha = (kotlin.math.sin(omega) / (2f * q.coerceAtLeast(0.1f))).coerceAtLeast(1.0e-6f)
                val cosOmega = cos(omega)
                val rawB0 = (1f - cosOmega) * 0.5f
                val rawB1 = 1f - cosOmega
                val rawB2 = (1f - cosOmega) * 0.5f
                val rawA0 = 1f + alpha
                val rawA1 = -2f * cosOmega
                val rawA2 = 1f - alpha
                return normalize(rawB0, rawB1, rawB2, rawA0, rawA1, rawA2)
            }

            fun highPass(sampleRateHz: Float, cutoffHz: Float, q: Float): BiquadFilter {
                val omega = (2.0 * PI * cutoffHz / sampleRateHz).toFloat()
                val alpha = (kotlin.math.sin(omega) / (2f * q.coerceAtLeast(0.1f))).coerceAtLeast(1.0e-6f)
                val cosOmega = cos(omega)
                val rawB0 = (1f + cosOmega) * 0.5f
                val rawB1 = -(1f + cosOmega)
                val rawB2 = (1f + cosOmega) * 0.5f
                val rawA0 = 1f + alpha
                val rawA1 = -2f * cosOmega
                val rawA2 = 1f - alpha
                return normalize(rawB0, rawB1, rawB2, rawA0, rawA1, rawA2)
            }

            private fun normalize(
                b0: Float,
                b1: Float,
                b2: Float,
                a0: Float,
                a1: Float,
                a2: Float
            ): BiquadFilter {
                val safeA0 = if (abs(a0) < 1.0e-6f) 1f else a0
                return BiquadFilter(
                    b0 = b0 / safeA0,
                    b1 = b1 / safeA0,
                    b2 = b2 / safeA0,
                    a1 = a1 / safeA0,
                    a2 = a2 / safeA0
                )
            }
        }
    }
}
