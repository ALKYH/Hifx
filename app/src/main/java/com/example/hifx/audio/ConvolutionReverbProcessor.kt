package com.example.hifx.audio

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
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

internal class ConvolutionReverbProcessor : BaseAudioProcessor() {
    @Volatile
    private var enabled = false

    @Volatile
    private var wetMix = 0.35f

    @Volatile
    private var impulse = FloatArray(0)

    @Volatile
    private var impulseCompensation = 1f

    @Volatile
    private var realtimeMeter = 0f

    private val convolutionLock = Any()

    private var blockSize = DEFAULT_BLOCK_SIZE
    private var fftSize = 0
    private var tailLength = 0

    private var kernelReal = FloatArray(0)
    private var kernelImag = FloatArray(0)
    private var overlapTailLeft = FloatArray(0)
    private var overlapTailRight = FloatArray(0)

    private var fftWorkReal = FloatArray(0)
    private var fftWorkImag = FloatArray(0)

    private var limiterGain = 1f

    fun updateConfig(enabled: Boolean, wetMix: Float) {
        this.enabled = enabled
        this.wetMix = wetMix.coerceIn(0f, 1f)
    }

    fun readRealtimeMeterPercent(): Int {
        val normalized = (realtimeMeter / 0.85f).coerceIn(0f, 1f)
        return (normalized * 100f).toInt()
    }

    fun updateImpulse(samples: FloatArray?) {
        synchronized(convolutionLock) {
            val prepared = preprocessImpulse(samples)
            impulse = prepared
            impulseCompensation = computeImpulseCompensation(prepared)
            configureConvolutionPlan(prepared)
            limiterGain = 1f
            realtimeMeter = 0f
        }
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            return
        }
        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN)

        synchronized(convolutionLock) {
            val localEnabled = enabled && impulse.isNotEmpty() && fftSize > 0 && kernelReal.isNotEmpty()
            if (!localEnabled) {
                clearOverlapState()
                limiterGain = 1f
                realtimeMeter = 0f
                outputBuffer.put(inputBuffer)
                outputBuffer.flip()
                return
            }

            val frameCount = inputBuffer.remaining() / 4
            if (frameCount <= 0) {
                outputBuffer.flip()
                return
            }

            val inputLeft = FloatArray(frameCount)
            val inputRight = FloatArray(frameCount)
            for (index in 0 until frameCount) {
                inputLeft[index] = shortToFloat(inputBuffer.short)
                inputRight[index] = shortToFloat(inputBuffer.short)
            }

            val wetLeft = convolveChannelOla(inputLeft, overlapTailLeft)
            val wetRight = convolveChannelOla(inputRight, overlapTailRight)

            val wet = wetMix.coerceIn(0f, 1f)
            val dry = 1f - wet
            val irComp = impulseCompensation.coerceIn(0.06f, 1f)
            val levelCompensation = (1f - wet * 0.25f).coerceIn(0.72f, 1f)
            val limiterThreshold = 0.92f
            val limiterRelease = 0.0035f
            var meter = realtimeMeter
            val meterAttack = 0.18f
            val meterRelease = 0.025f

            for (index in 0 until frameCount) {
                val wetLeftSample = wetLeft[index] * wet * irComp
                val wetRightSample = wetRight[index] * wet * irComp
                val mixedLeft = (inputLeft[index] * dry + wetLeftSample) * levelCompensation
                val mixedRight = (inputRight[index] * dry + wetRightSample) * levelCompensation

                val wetEnergy = max(abs(wetLeftSample), abs(wetRightSample)).coerceIn(0f, 1.5f)
                meter += (wetEnergy - meter) * if (wetEnergy > meter) meterAttack else meterRelease

                val peak = max(abs(mixedLeft), abs(mixedRight))
                val targetGain = if (peak > limiterThreshold) {
                    (limiterThreshold / peak).coerceIn(0.05f, 1f)
                } else {
                    1f
                }
                limiterGain = if (targetGain < limiterGain) {
                    targetGain
                } else {
                    limiterGain + (targetGain - limiterGain) * limiterRelease
                }

                val limitedLeft = mixedLeft * limiterGain
                val limitedRight = mixedRight * limiterGain
                outputBuffer.putShort(floatToShort(softSaturate(limitedLeft)))
                outputBuffer.putShort(floatToShort(softSaturate(limitedRight)))
            }
            realtimeMeter = meter.coerceIn(0f, 1.5f)
            outputBuffer.flip()
        }
    }

    override fun onFlush() {
        synchronized(convolutionLock) {
            clearOverlapState()
            limiterGain = 1f
            realtimeMeter = 0f
        }
    }

    override fun onReset() {
        synchronized(convolutionLock) {
            impulse = FloatArray(0)
            impulseCompensation = 1f
            clearConvolutionPlan()
            limiterGain = 1f
            realtimeMeter = 0f
            enabled = false
            wetMix = 0.35f
        }
    }

    private fun convolveChannelOla(input: FloatArray, overlapTail: FloatArray): FloatArray {
        if (input.isEmpty() || impulse.isEmpty() || fftSize <= 0 || kernelReal.isEmpty()) {
            return input.copyOf()
        }
        val output = FloatArray(input.size)
        var offset = 0
        while (offset < input.size) {
            val currentBlockSize = min(blockSize, input.size - offset)

            fftWorkReal.fill(0f)
            fftWorkImag.fill(0f)
            input.copyInto(
                destination = fftWorkReal,
                destinationOffset = 0,
                startIndex = offset,
                endIndex = offset + currentBlockSize
            )

            fft(fftWorkReal, fftWorkImag, inverse = false)
            for (index in 0 until fftSize) {
                val ar = fftWorkReal[index]
                val ai = fftWorkImag[index]
                val br = kernelReal[index]
                val bi = kernelImag[index]
                fftWorkReal[index] = ar * br - ai * bi
                fftWorkImag[index] = ar * bi + ai * br
            }
            fft(fftWorkReal, fftWorkImag, inverse = true)

            for (index in 0 until tailLength) {
                fftWorkReal[index] += overlapTail[index]
            }

            for (index in 0 until currentBlockSize) {
                output[offset + index] = fftWorkReal[index]
            }

            if (tailLength > 0) {
                val tailReadStart = currentBlockSize
                for (index in 0 until tailLength) {
                    overlapTail[index] = fftWorkReal[tailReadStart + index]
                }
            }

            offset += currentBlockSize
        }
        return output
    }

    private fun configureConvolutionPlan(preparedImpulse: FloatArray) {
        if (preparedImpulse.isEmpty()) {
            clearConvolutionPlan()
            return
        }
        tailLength = max(preparedImpulse.size - 1, 0)
        blockSize = selectBlockSize(preparedImpulse.size)
        fftSize = nextPowerOfTwo(blockSize + tailLength)

        kernelReal = FloatArray(fftSize)
        kernelImag = FloatArray(fftSize)
        preparedImpulse.copyInto(kernelReal, destinationOffset = 0, startIndex = 0, endIndex = preparedImpulse.size)
        fft(kernelReal, kernelImag, inverse = false)

        overlapTailLeft = FloatArray(tailLength)
        overlapTailRight = FloatArray(tailLength)
        fftWorkReal = FloatArray(fftSize)
        fftWorkImag = FloatArray(fftSize)
    }

    private fun clearConvolutionPlan() {
        blockSize = DEFAULT_BLOCK_SIZE
        fftSize = 0
        tailLength = 0
        kernelReal = FloatArray(0)
        kernelImag = FloatArray(0)
        overlapTailLeft = FloatArray(0)
        overlapTailRight = FloatArray(0)
        fftWorkReal = FloatArray(0)
        fftWorkImag = FloatArray(0)
    }

    private fun clearOverlapState() {
        overlapTailLeft.fill(0f)
        overlapTailRight.fill(0f)
    }

    private fun preprocessImpulse(samples: FloatArray?): FloatArray {
        val source = samples ?: return FloatArray(0)
        if (source.isEmpty()) {
            return FloatArray(0)
        }
        val trimmed = trimSilence(source, IR_TRIM_THRESHOLD)
        if (trimmed.isEmpty()) {
            return FloatArray(0)
        }
        val cropped = if (trimmed.size > MAX_IR_TAPS) {
            trimmed.copyOf(MAX_IR_TAPS)
        } else {
            trimmed.copyOf()
        }
        var peak = 0f
        for (sample in cropped) {
            peak = max(peak, abs(sample))
        }
        if (peak > 0.000001f) {
            val normalizeGain = 0.96f / peak
            for (index in cropped.indices) {
                cropped[index] = (cropped[index] * normalizeGain).coerceIn(-1f, 1f)
            }
        }
        applyTailFade(cropped)
        return cropped
    }

    private fun trimSilence(source: FloatArray, threshold: Float): FloatArray {
        var start = 0
        while (start < source.size && abs(source[start]) < threshold) {
            start++
        }
        var end = source.lastIndex
        while (end >= start && abs(source[end]) < threshold) {
            end--
        }
        if (start > end) {
            return FloatArray(0)
        }
        return source.copyOfRange(start, end + 1)
    }

    private fun applyTailFade(ir: FloatArray) {
        if (ir.isEmpty()) return
        val fadeLength = min(320, max(16, ir.size / 3))
        if (fadeLength >= ir.size) return
        val fadeStart = ir.size - fadeLength
        val denom = max(1f, (fadeLength - 1).toFloat())
        for (index in fadeStart until ir.size) {
            val progress = ((index - fadeStart).toFloat() / denom).coerceIn(0f, 1f)
            val fade = (1f - progress)
            ir[index] *= fade * fade
        }
    }

    private fun selectBlockSize(impulseLength: Int): Int {
        return when {
            impulseLength <= 192 -> 256
            impulseLength <= 768 -> 512
            impulseLength <= 2048 -> 1024
            else -> 2048
        }
    }

    private fun fft(real: FloatArray, imag: FloatArray, inverse: Boolean) {
        val n = real.size
        if (n <= 1) return

        var j = 0
        for (i in 1 until n) {
            var bit = n ushr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit ushr 1
            }
            j = j xor bit
            if (i < j) {
                val tempR = real[i]
                real[i] = real[j]
                real[j] = tempR

                val tempI = imag[i]
                imag[i] = imag[j]
                imag[j] = tempI
            }
        }

        var len = 2
        while (len <= n) {
            val angle = 2.0 * PI / len * if (inverse) 1.0 else -1.0
            val wLenCos = cos(angle).toFloat()
            val wLenSin = sin(angle).toFloat()

            var offset = 0
            while (offset < n) {
                var wCos = 1f
                var wSin = 0f
                val half = len / 2
                for (k in 0 until half) {
                    val evenIndex = offset + k
                    val oddIndex = evenIndex + half

                    val oddReal = real[oddIndex]
                    val oddImag = imag[oddIndex]
                    val tReal = oddReal * wCos - oddImag * wSin
                    val tImag = oddReal * wSin + oddImag * wCos

                    val uReal = real[evenIndex]
                    val uImag = imag[evenIndex]

                    real[evenIndex] = uReal + tReal
                    imag[evenIndex] = uImag + tImag
                    real[oddIndex] = uReal - tReal
                    imag[oddIndex] = uImag - tImag

                    val nextCos = wCos * wLenCos - wSin * wLenSin
                    wSin = wCos * wLenSin + wSin * wLenCos
                    wCos = nextCos
                }
                offset += len
            }
            len = len shl 1
        }

        if (inverse) {
            val invN = 1f / n
            for (index in 0 until n) {
                real[index] *= invN
                imag[index] *= invN
            }
        }
    }

    private fun nextPowerOfTwo(value: Int): Int {
        var result = 1
        while (result < value) {
            result = result shl 1
        }
        return result
    }

    private fun computeImpulseCompensation(localImpulse: FloatArray): Float {
        if (localImpulse.isEmpty()) {
            return 1f
        }
        var l1 = 0f
        var l2Sq = 0f
        var peak = 0f
        for (sample in localImpulse) {
            val a = abs(sample)
            l1 += a
            l2Sq += sample * sample
            peak = max(peak, a)
        }
        val l2 = sqrt(max(l2Sq, 0f))
        val expectedGain = max(1f, peak * 0.9f + l2 * 0.9f + l1 * 0.08f)
        return (0.9f / expectedGain).coerceIn(0.06f, 1f)
    }

    private fun shortToFloat(value: Short): Float {
        return (value.toInt() / 32768f).coerceIn(-1f, 1f)
    }

    private fun floatToShort(value: Float): Short {
        val clamped = (max(-1f, min(1f, value)) * 32767f).toInt()
        return clamped.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun softSaturate(value: Float): Float {
        val drive = 1.18f
        val scaled = (value * drive).coerceIn(-4f, 4f)
        val norm = tanh(drive.toDouble()).toFloat().coerceAtLeast(0.0001f)
        return (tanh(scaled.toDouble()).toFloat() / norm).coerceIn(-1f, 1f)
    }

    companion object {
        private const val DEFAULT_BLOCK_SIZE = 512
        private const val MAX_IR_TAPS = 4096
        private const val IR_TRIM_THRESHOLD = 0.0004f
    }
}
