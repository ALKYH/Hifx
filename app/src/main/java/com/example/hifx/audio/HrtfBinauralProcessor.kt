package com.example.hifx.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

internal data class HrtfRenderConfig(
    val enabled: Boolean = true,
    val spatialEnabled: Boolean = false,
    val channelSeparated: Boolean = false,
    val leftXNorm: Float = 0f,
    val leftYNorm: Float = 0f,
    val leftZNorm: Float = 0f,
    val rightXNorm: Float = 0f,
    val rightYNorm: Float = 0f,
    val rightZNorm: Float = 0f,
    val roomDampingNorm: Float = 0.35f,
    val wetMixNorm: Float = 0.4f,
    val headRadiusMeters: Float = 0.087f,
    val blend: Float = 0.78f,
    val crossfeed: Float = 0.28f,
    val externalization: Float = 0.55f,
    val useHrtfDatabase: Boolean = true,
    val surroundMode: Int = 0
)

private data class HrtfBin(
    val itdScale: Float,
    val farShadowDb: Float,
    val farCutoffHz: Float
)

private data class HrtfCoefficients(
    val leftDelaySamples: Float,
    val rightDelaySamples: Float,
    val leftGain: Float,
    val rightGain: Float,
    val leftLowGain: Float,
    val rightLowGain: Float,
    val leftHighGain: Float,
    val rightHighGain: Float,
    val leftLpA: Float,
    val rightLpA: Float,
    val crossfeedAmount: Float,
    val crossfeedDelaySamples: Float,
    val earlyMix: Float,
    val earlyDelaySamples: Float,
    val azimuthPan: Float,
    val lateralFocus: Float
)

private class SourceRenderState {
    val sourceDelay = FloatArray(8192)
    val leftHistory = FloatArray(4096)
    val rightHistory = FloatArray(4096)
    var sourceWriteIndex = 0
    var earWriteIndex = 0
    var leftLpState = 0f
    var rightLpState = 0f

    fun clear() {
        sourceDelay.fill(0f)
        leftHistory.fill(0f)
        rightHistory.fill(0f)
        sourceWriteIndex = 0
        earWriteIndex = 0
        leftLpState = 0f
        rightLpState = 0f
    }
}

internal class HrtfBinauralProcessor : BaseAudioProcessor() {
    @Volatile
    private var config = HrtfRenderConfig()

    private val leftSourceState = SourceRenderState()
    private val rightSourceState = SourceRenderState()
    private val centerSourceState = SourceRenderState()
    private val lfeSourceState = SourceRenderState()
    private val surroundLeftState = SourceRenderState()
    private val surroundRightState = SourceRenderState()
    private val rearLeftState = SourceRenderState()
    private val rearRightState = SourceRenderState()

    private var lfeMonoState = 0f

    fun updateConfig(newConfig: HrtfRenderConfig) {
        val clampedLeftX = newConfig.leftXNorm.coerceIn(-1f, 1f)
        val clampedLeftY = newConfig.leftYNorm.coerceIn(-1f, 1f)
        val clampedLeftZ = newConfig.leftZNorm.coerceIn(-1f, 1f)
        val clampedRightX = if (newConfig.channelSeparated) newConfig.rightXNorm.coerceIn(-1f, 1f) else clampedLeftX
        val clampedRightY = if (newConfig.channelSeparated) newConfig.rightYNorm.coerceIn(-1f, 1f) else clampedLeftY
        val clampedRightZ = if (newConfig.channelSeparated) newConfig.rightZNorm.coerceIn(-1f, 1f) else clampedLeftZ

        config = newConfig.copy(
            leftXNorm = clampedLeftX,
            leftYNorm = clampedLeftY,
            leftZNorm = clampedLeftZ,
            rightXNorm = clampedRightX,
            rightYNorm = clampedRightY,
            rightZNorm = clampedRightZ,
            roomDampingNorm = newConfig.roomDampingNorm.coerceIn(0f, 1f),
            wetMixNorm = newConfig.wetMixNorm.coerceIn(0f, 1f),
            headRadiusMeters = newConfig.headRadiusMeters.coerceIn(0.07f, 0.11f),
            blend = newConfig.blend.coerceIn(0f, 1f),
            crossfeed = newConfig.crossfeed.coerceIn(0f, 1f),
            externalization = newConfig.externalization.coerceIn(0f, 1f),
            surroundMode = newConfig.surroundMode.coerceIn(0, 2)
        )
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

        val localConfig = config
        val shouldProcess = localConfig.enabled && localConfig.spatialEnabled && localConfig.blend > 0.001f
        if (!shouldProcess) {
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val leftCoeff = buildCoefficients(
            xNorm = localConfig.leftXNorm,
            yNorm = localConfig.leftYNorm,
            zNorm = localConfig.leftZNorm,
            config = localConfig,
            sampleRate = inputAudioFormat.sampleRate
        )
        val rightCoeff = buildCoefficients(
            xNorm = localConfig.rightXNorm,
            yNorm = localConfig.rightYNorm,
            zNorm = localConfig.rightZNorm,
            config = localConfig,
            sampleRate = inputAudioFormat.sampleRate
        )
        val surroundMode = localConfig.surroundMode.coerceIn(0, 2)
        val centerCoeff = if (surroundMode != 0) {
            buildCoefficients(
                xNorm = 0f,
                yNorm = 0f,
                zNorm = 0.85f,
                config = localConfig,
                sampleRate = inputAudioFormat.sampleRate
            )
        } else {
            null
        }
        val lfeCoeff = if (surroundMode != 0) {
            buildCoefficients(
                xNorm = 0f,
                yNorm = -0.25f,
                zNorm = 0.35f,
                config = localConfig,
                sampleRate = inputAudioFormat.sampleRate
            )
        } else {
            null
        }
        val surroundLeftCoeff = if (surroundMode != 0) {
            buildCoefficients(
                xNorm = -0.98f,
                yNorm = 0f,
                zNorm = -0.22f,
                config = localConfig,
                sampleRate = inputAudioFormat.sampleRate
            )
        } else {
            null
        }
        val surroundRightCoeff = if (surroundMode != 0) {
            buildCoefficients(
                xNorm = 0.98f,
                yNorm = 0f,
                zNorm = -0.22f,
                config = localConfig,
                sampleRate = inputAudioFormat.sampleRate
            )
        } else {
            null
        }
        val rearLeftCoeff = if (surroundMode == 2) {
            buildCoefficients(
                xNorm = -0.74f,
                yNorm = 0f,
                zNorm = -0.88f,
                config = localConfig,
                sampleRate = inputAudioFormat.sampleRate
            )
        } else {
            null
        }
        val rearRightCoeff = if (surroundMode == 2) {
            buildCoefficients(
                xNorm = 0.74f,
                yNorm = 0f,
                zNorm = -0.88f,
                config = localConfig,
                sampleRate = inputAudioFormat.sampleRate
            )
        } else {
            null
        }

        val blend = localConfig.blend.coerceIn(0f, 1f)
        val wetSourceGain = 0.62f
        val headroom = (1f - 0.26f * blend).coerceIn(0.68f, 1f)
        while (inputBuffer.remaining() >= 4) {
            val inLeft = shortToFloat(inputBuffer.short)
            val inRight = shortToFloat(inputBuffer.short)
            var wetOutLeft: Float
            var wetOutRight: Float
            if (surroundMode == 0) {
                val leftSourceWet = renderSource(
                    inputSample = inLeft,
                    coeff = leftCoeff,
                    state = leftSourceState
                )
                val rightSourceWet = renderSource(
                    inputSample = inRight,
                    coeff = rightCoeff,
                    state = rightSourceState
                )
                wetOutLeft = (leftSourceWet.first + rightSourceWet.first) * wetSourceGain
                wetOutRight = (leftSourceWet.second + rightSourceWet.second) * wetSourceGain
            } else {
                val mono = (inLeft + inRight) * 0.5f
                val centerIn = mono * 0.92f
                val lfeIn = extractLfe(mono, inputAudioFormat.sampleRate) * 0.85f
                val surroundLeftIn = (inLeft * 0.76f + inRight * 0.24f) * 0.82f
                val surroundRightIn = (inRight * 0.76f + inLeft * 0.24f) * 0.82f
                val rearLeftIn = (inLeft * 0.62f + inRight * 0.18f) * 0.65f
                val rearRightIn = (inRight * 0.62f + inLeft * 0.18f) * 0.65f

                var accumulatorLeft = 0f
                var accumulatorRight = 0f

                fun mixSpeaker(sample: Float, coeff: HrtfCoefficients?, state: SourceRenderState, gain: Float) {
                    if (coeff == null || gain <= 0f || abs(sample) < 1e-5f) return
                    val rendered = renderSource(sample, coeff, state)
                    accumulatorLeft += rendered.first * gain
                    accumulatorRight += rendered.second * gain
                }

                mixSpeaker(inLeft, leftCoeff, leftSourceState, 0.70f)
                mixSpeaker(inRight, rightCoeff, rightSourceState, 0.70f)
                mixSpeaker(centerIn, centerCoeff, centerSourceState, 0.48f)
                mixSpeaker(lfeIn, lfeCoeff, lfeSourceState, 0.30f)
                mixSpeaker(surroundLeftIn, surroundLeftCoeff, surroundLeftState, 0.44f)
                mixSpeaker(surroundRightIn, surroundRightCoeff, surroundRightState, 0.44f)
                if (surroundMode == 2) {
                    mixSpeaker(rearLeftIn, rearLeftCoeff, rearLeftState, 0.34f)
                    mixSpeaker(rearRightIn, rearRightCoeff, rearRightState, 0.34f)
                }

                val modeCompensation = if (surroundMode == 2) 0.57f else 0.63f
                wetOutLeft = accumulatorLeft * wetSourceGain * modeCompensation
                wetOutRight = accumulatorRight * wetSourceGain * modeCompensation
            }

            val mixedLeft = (inLeft * (1f - blend) + wetOutLeft * blend) * headroom
            val mixedRight = (inRight * (1f - blend) + wetOutRight * blend) * headroom
            val outLeft = softSaturate(mixedLeft)
            val outRight = softSaturate(mixedRight)

            outputBuffer.putShort(floatToShort(outLeft))
            outputBuffer.putShort(floatToShort(outRight))
        }
        outputBuffer.flip()
    }

    override fun onFlush() {
        clearInternalState()
    }

    override fun onReset() {
        clearInternalState()
    }

    private fun clearInternalState() {
        leftSourceState.clear()
        rightSourceState.clear()
        centerSourceState.clear()
        lfeSourceState.clear()
        surroundLeftState.clear()
        surroundRightState.clear()
        rearLeftState.clear()
        rearRightState.clear()
        lfeMonoState = 0f
    }

    private fun extractLfe(mono: Float, sampleRate: Int): Float {
        val cutoff = 140f
        val coefficient = onePoleCoefficient(cutoff, sampleRate)
        lfeMonoState = onePoleLowpass(mono, lfeMonoState, coefficient)
        return lfeMonoState
    }

    private fun renderSource(
        inputSample: Float,
        coeff: HrtfCoefficients,
        state: SourceRenderState
    ): Pair<Float, Float> {
        state.sourceDelay[state.sourceWriteIndex] = inputSample

        val delayedLeft = readRing(state.sourceDelay, state.sourceWriteIndex, coeff.leftDelaySamples)
        val delayedRight = readRing(state.sourceDelay, state.sourceWriteIndex, coeff.rightDelaySamples)

        state.leftLpState = onePoleLowpass(delayedLeft, state.leftLpState, coeff.leftLpA)
        state.rightLpState = onePoleLowpass(delayedRight, state.rightLpState, coeff.rightLpA)
        val leftHigh = delayedLeft - state.leftLpState
        val rightHigh = delayedRight - state.rightLpState

        var shapedLeft =
            (state.leftLpState * coeff.leftLowGain + leftHigh * coeff.leftHighGain) * coeff.leftGain
        var shapedRight =
            (state.rightLpState * coeff.rightLowGain + rightHigh * coeff.rightHighGain) * coeff.rightGain

        val mid = (shapedLeft + shapedRight) * 0.5f
        val side = (shapedLeft - shapedRight) * 0.5f
        val sideGain = (1f + coeff.lateralFocus).coerceIn(1f, 1.9f)
        shapedLeft = mid + side * sideGain
        shapedRight = mid - side * sideGain

        state.leftHistory[state.earWriteIndex] = shapedLeft
        state.rightHistory[state.earWriteIndex] = shapedRight
        val crossfedToLeft = readRing(state.rightHistory, state.earWriteIndex, coeff.crossfeedDelaySamples)
        val crossfedToRight = readRing(state.leftHistory, state.earWriteIndex, coeff.crossfeedDelaySamples)
        val early = readRing(state.sourceDelay, state.sourceWriteIndex, coeff.earlyDelaySamples)

        shapedLeft += crossfedToLeft * coeff.crossfeedAmount
        shapedRight += crossfedToRight * coeff.crossfeedAmount
        shapedLeft += early * coeff.earlyMix * (1f - coeff.azimuthPan * 0.2f)
        shapedRight += early * coeff.earlyMix * (1f + coeff.azimuthPan * 0.2f)

        state.sourceWriteIndex = (state.sourceWriteIndex + 1) % state.sourceDelay.size
        state.earWriteIndex = (state.earWriteIndex + 1) % state.leftHistory.size
        return shapedLeft to shapedRight
    }

    private fun buildCoefficients(
        xNorm: Float,
        yNorm: Float,
        zNorm: Float,
        config: HrtfRenderConfig,
        sampleRate: Int
    ): HrtfCoefficients {
        val azimuth = atan2(xNorm.toDouble(), zNorm.toDouble()).toFloat()
        val absAzimuthRad = abs(azimuth).coerceIn(0f, PI.toFloat())
        val absAzimuthDeg = Math.toDegrees(absAzimuthRad.toDouble()).toFloat().coerceIn(0f, 180f)
        val profile = resolveHrtfBin(absAzimuthDeg, config.useHrtfDatabase)

        val headRadius = config.headRadiusMeters.coerceIn(0.07f, 0.11f)
        val headRadiusNorm = ((headRadius - 0.07f) / 0.04f).coerceIn(0f, 1f)
        val rigidSphereItdSec = (headRadius / SPEED_OF_SOUND_MPS) * (absAzimuthRad + sin(absAzimuthRad))
        val itdSec = (rigidSphereItdSec * profile.itdScale).coerceIn(0f, 0.001f)
        val itdSamples = itdSec * sampleRate * abs(xNorm).coerceIn(0f, 1f)
        val leftDelaySamples = (itdSamples * xNorm.coerceIn(0f, 1f)).coerceAtLeast(0f)
        val rightDelaySamples = (itdSamples * (-xNorm).coerceIn(0f, 1f)).coerceAtLeast(0f)

        val distanceNorm = sqrt((xNorm * xNorm + yNorm * yNorm + zNorm * zNorm).toDouble()).toFloat()
        val distanceMeters = 0.35f + distanceNorm.coerceIn(0f, 1.73f) * 5.8f
        val distanceFactor = ((distanceMeters - 0.4f) / 6f).coerceIn(0f, 1f)
        val backFactor = (-zNorm).coerceIn(0f, 1f)
        val lateral = abs(xNorm).pow(0.85f).coerceIn(0f, 1f)
        val directionality = (0.72f + config.externalization * 0.34f + config.blend * 0.24f).coerceIn(0.7f, 1.35f)
        val shadowStrength = (0.9f + headRadiusNorm * 0.42f + config.externalization * 0.22f).coerceIn(0.9f, 1.65f)
        val farShadowDb = (
            profile.farShadowDb * shadowStrength +
                distanceFactor * 2.4f +
                backFactor * 3.2f +
                lateral * 2.6f * directionality
            ).coerceIn(0.6f, 18f)
        val nearBoostDb = min(4.8f, 0.8f + farShadowDb * (0.32f + 0.15f * directionality))

        val ildScale = (1f + lateral * (0.22f + config.externalization * 0.28f)).coerceIn(1f, 1.72f)
        val nearGain = dbToLinear(nearBoostDb * ildScale)
        val farGain = dbToLinear(-farShadowDb * ildScale)
        val rightWeight = ((xNorm + 1f) * 0.5f).coerceIn(0f, 1f)
        val leftWeight = 1f - rightWeight
        val leftGain = nearGain * leftWeight + farGain * rightWeight
        val rightGain = nearGain * rightWeight + farGain * leftWeight

        val farCutoff = (
            profile.farCutoffHz -
                backFactor * 1700f -
                distanceFactor * 1200f -
                lateral * 1200f -
                headRadiusNorm * 950f
            ).coerceIn(900f, 11_000f)
        val nearCutoff = (
            18_600f +
                yNorm * 2400f -
                lateral * 650f +
                headRadiusNorm * 700f
            ).coerceIn(6500f, 20_000f)
        val leftCutoff = nearCutoff * leftWeight + farCutoff * rightWeight
        val rightCutoff = nearCutoff * rightWeight + farCutoff * leftWeight

        val leftLpA = onePoleCoefficient(leftCutoff, sampleRate)
        val rightLpA = onePoleCoefficient(rightCutoff, sampleRate)

        val farHighAttenDb = (
            3.2f +
                farShadowDb * 0.68f +
                backFactor * 3.0f +
                lateral * 2.1f
            ).coerceIn(1.5f, 18f)
        val nearHighBoostDb = (
            0.4f +
                lateral * 2.4f +
                (1f - backFactor) * 0.7f +
                headRadiusNorm * 0.6f
            ).coerceIn(0f, 5f)
        val nearHighGain = dbToLinear(nearHighBoostDb)
        val farHighGain = dbToLinear(-farHighAttenDb)
        val leftHighGain = nearHighGain * leftWeight + farHighGain * rightWeight
        val rightHighGain = nearHighGain * rightWeight + farHighGain * leftWeight

        val farLowAttenDb = -(farShadowDb * 0.2f + backFactor * 0.8f).coerceIn(0f, 4.2f)
        val nearLowBoostDb = (nearBoostDb * 0.2f + headRadiusNorm * 0.4f).coerceIn(0f, 2.2f)
        val nearLowGain = dbToLinear(nearLowBoostDb)
        val farLowGain = dbToLinear(farLowAttenDb)
        val leftLowGain = nearLowGain * leftWeight + farLowGain * rightWeight
        val rightLowGain = nearLowGain * rightWeight + farLowGain * leftWeight

        val frontWeight = ((zNorm + 1f) * 0.5f).coerceIn(0f, 1f)
        val centerWeight = 1f - (absAzimuthDeg / 180f).coerceIn(0f, 1f)
        val lateralSuppression = (1f - lateral * 0.78f).coerceIn(0.15f, 1f)
        val rearSuppression = (1f - backFactor * 0.45f).coerceIn(0.35f, 1f)
        val crossfeedAmount = config.crossfeed *
            (0.12f + 0.27f * frontWeight) *
            (0.42f + 0.58f * centerWeight) *
            lateralSuppression *
            rearSuppression
        val crossfeedDelaySamples = (sampleRate * (0.00022f + 0.00018f * frontWeight))
            .coerceIn(1f, 96f)

        val earlyMix = config.externalization *
            (0.018f + config.wetMixNorm * 0.102f) *
            (1f + backFactor * 0.44f)
        val earlyDelaySamples = (sampleRate * (0.00145f + distanceFactor * 0.0038f + backFactor * 0.0012f))
            .coerceIn(4f, 360f)
        val lateralFocus = (
            lateral *
                (0.22f + config.externalization * 0.5f + config.blend * 0.18f) *
                (0.84f + backFactor * 0.28f)
            ).coerceIn(0f, 0.88f)

        return HrtfCoefficients(
            leftDelaySamples = leftDelaySamples,
            rightDelaySamples = rightDelaySamples,
            leftGain = leftGain,
            rightGain = rightGain,
            leftLowGain = leftLowGain.coerceIn(0.25f, 2.3f),
            rightLowGain = rightLowGain.coerceIn(0.25f, 2.3f),
            leftHighGain = leftHighGain.coerceIn(0.08f, 1.9f),
            rightHighGain = rightHighGain.coerceIn(0.08f, 1.9f),
            leftLpA = leftLpA,
            rightLpA = rightLpA,
            crossfeedAmount = crossfeedAmount.coerceIn(0f, 0.42f),
            crossfeedDelaySamples = crossfeedDelaySamples,
            earlyMix = earlyMix.coerceIn(0f, 0.24f),
            earlyDelaySamples = earlyDelaySamples,
            azimuthPan = xNorm.coerceIn(-1f, 1f),
            lateralFocus = lateralFocus
        )
    }

    private fun resolveHrtfBin(absAzimuthDeg: Float, useDatabase: Boolean): HrtfBin {
        if (!useDatabase) {
            val t = (absAzimuthDeg / 180f).coerceIn(0f, 1f)
            return HrtfBin(
                itdScale = 0.95f + t * 0.1f,
                farShadowDb = 1.2f + t.pow(1.2f) * 9f,
                farCutoffHz = 11_000f - t.pow(1.35f) * 7600f
            )
        }
        val bins = HRTF_DB_BINS
        if (absAzimuthDeg <= bins.first()) {
            return HRTF_DB_VALUES.first()
        }
        if (absAzimuthDeg >= bins.last()) {
            return HRTF_DB_VALUES.last()
        }
        for (index in 0 until bins.lastIndex) {
            val leftAzimuth = bins[index]
            val rightAzimuth = bins[index + 1]
            if (absAzimuthDeg in leftAzimuth..rightAzimuth) {
                val ratio = ((absAzimuthDeg - leftAzimuth) / (rightAzimuth - leftAzimuth)).coerceIn(0f, 1f)
                val left = HRTF_DB_VALUES[index]
                val right = HRTF_DB_VALUES[index + 1]
                return HrtfBin(
                    itdScale = lerp(left.itdScale, right.itdScale, ratio),
                    farShadowDb = lerp(left.farShadowDb, right.farShadowDb, ratio),
                    farCutoffHz = lerp(left.farCutoffHz, right.farCutoffHz, ratio)
                )
            }
        }
        return HRTF_DB_VALUES.last()
    }

    private fun readRing(buffer: FloatArray, writeIndex: Int, delaySamples: Float): Float {
        val clampedDelay = delaySamples.coerceIn(0f, (buffer.size - 2).toFloat())
        val baseDelay = clampedDelay.toInt()
        val fraction = clampedDelay - baseDelay
        val idxA = mod(writeIndex - baseDelay, buffer.size)
        val idxB = mod(writeIndex - baseDelay - 1, buffer.size)
        val a = buffer[idxA]
        val b = buffer[idxB]
        return a * (1f - fraction) + b * fraction
    }

    private fun onePoleLowpass(input: Float, prev: Float, coefficientA: Float): Float {
        val a = coefficientA.coerceIn(0f, 0.9999f)
        return (1f - a) * input + a * prev
    }

    private fun onePoleCoefficient(cutoffHz: Float, sampleRate: Int): Float {
        val fc = cutoffHz.coerceIn(20f, sampleRate * 0.45f)
        val omega = -2.0 * PI * fc / sampleRate
        return exp(omega).toFloat().coerceIn(0f, 0.9999f)
    }

    private fun dbToLinear(db: Float): Float {
        return 10f.pow(db / 20f)
    }

    private fun shortToFloat(value: Short): Float {
        return (value.toInt() / 32768f).coerceIn(-1f, 1f)
    }

    private fun floatToShort(value: Float): Short {
        val clamped = (max(-1f, min(1f, value)) * 32767f).toInt()
        return clamped.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    private fun mod(value: Int, size: Int): Int {
        val result = value % size
        return if (result < 0) result + size else result
    }

    private fun lerp(left: Float, right: Float, ratio: Float): Float {
        return left + (right - left) * ratio
    }

    private fun softSaturate(value: Float): Float {
        val drive = 1.35f
        val scaled = (value * drive).coerceIn(-4f, 4f)
        val norm = tanh(drive.toDouble()).toFloat().coerceAtLeast(0.0001f)
        return (tanh(scaled.toDouble()).toFloat() / norm).coerceIn(-1f, 1f)
    }

    companion object {
        private const val SPEED_OF_SOUND_MPS = 343f
        private val HRTF_DB_BINS = floatArrayOf(0f, 15f, 30f, 45f, 60f, 75f, 90f, 120f, 150f, 180f)

        private val HRTF_DB_VALUES = arrayOf(
            HrtfBin(itdScale = 0.92f, farShadowDb = 0.8f, farCutoffHz = 12_500f),
            HrtfBin(itdScale = 0.96f, farShadowDb = 1.6f, farCutoffHz = 11_400f),
            HrtfBin(itdScale = 1.00f, farShadowDb = 2.8f, farCutoffHz = 9_900f),
            HrtfBin(itdScale = 1.03f, farShadowDb = 4.0f, farCutoffHz = 8_600f),
            HrtfBin(itdScale = 1.06f, farShadowDb = 5.4f, farCutoffHz = 7_100f),
            HrtfBin(itdScale = 1.08f, farShadowDb = 6.7f, farCutoffHz = 5_800f),
            HrtfBin(itdScale = 1.10f, farShadowDb = 8.1f, farCutoffHz = 4_600f),
            HrtfBin(itdScale = 1.08f, farShadowDb = 8.8f, farCutoffHz = 4_000f),
            HrtfBin(itdScale = 1.04f, farShadowDb = 7.2f, farCutoffHz = 4_900f),
            HrtfBin(itdScale = 1.00f, farShadowDb = 6.0f, farCutoffHz = 5_600f)
        )
    }
}
