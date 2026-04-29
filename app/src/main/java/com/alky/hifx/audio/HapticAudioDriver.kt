package com.alky.hifx.audio

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.roundToInt

/**
 * Slow haptic consumer that turns coarse bass/kick information into drum-like vibration pulses.
 *
 * Design constraints:
 *  - asynchronous feedback only
 *  - max one request every ~100 ms
 *  - simulate kick drum pulses from low-frequency energy
 *  - never flood the system vibration service
 */
internal class HapticAudioDriver private constructor(
    context: Context,
    private val vibrator: Vibrator,
    initialDelayMs: Int
) {

    private val appContext = context.applicationContext
    private val workerThread = HandlerThread("HifxHapticAudio").apply {
        priority = Thread.NORM_PRIORITY + 1
        start()
    }
    private val handler = Handler(workerThread.looper)

    @Volatile private var enabled = false
    @Volatile private var released = false
    @Volatile private var dispatchScheduled = false
    @Volatile private var delayMs = initialDelayMs.coerceIn(-250, 250)

    private var pendingBassLevel = 0f
    private var pendingKickStrength = 0f
    private var lastBassLevel = 0f
    private var lastKickStrength = 0f
    private var lastAmplitude = 0
    private var lastAmplitudeBucket = 0
    private var lastDispatchUptimeMs = 0L
    private var activeUntilUptimeMs = 0L

    private var submitCount = 0L
    private var dispatchCount = 0L
    private var vendorDispatchCount = 0L
    private var aospDispatchCount = 0L

    private val vendorBackend: VendorBackend? = VendorBackend.detect(appContext)
    private val supportsAmplitudeControl =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && vibrator.hasAmplitudeControl()
    private val hasVibratePermission =
        appContext.packageManager.checkPermission(android.Manifest.permission.VIBRATE, appContext.packageName) ==
            PackageManager.PERMISSION_GRANTED

    fun setEnabled(value: Boolean) {
        if (released || enabled == value) return
        enabled = value
        if (!value) {
            handler.post { stopAllOutput() }
        }
    }

    fun setDelayMs(value: Int) {
        delayMs = value.coerceIn(-250, 250)
    }

    fun submitPulse(bassLevel: Float, kickStrength: Float) {
        if (!enabled || released) return
        pendingBassLevel = bassLevel.coerceIn(0f, 1f)
        pendingKickStrength = kickStrength.coerceIn(0f, 1f)
        lastBassLevel = pendingBassLevel
        lastKickStrength = pendingKickStrength
        submitCount++
        scheduleDispatch()
    }

    fun release() {
        if (released) return
        released = true
        enabled = false
        handler.post {
            stopAllOutput()
            workerThread.quitSafely()
        }
    }

    fun debugStatus(): String {
        return buildString {
            append("enabled=").append(enabled)
            append(" released=").append(released)
            append(" permission=").append(hasVibratePermission)
            append(" hasVibrator=").append(vibrator.hasVibrator())
            append(" ampCtl=").append(supportsAmplitudeControl)
            append(" delayMs=").append(delayMs)
            append(" backend=").append(vendorBackend?.label ?: "aosp")
            append(" lastAmp=").append(lastAmplitude)
            append(" lastBass=").append(lastBassLevel)
            append(" lastKick=").append(lastKickStrength)
            append(" submits=").append(submitCount)
            append(" dispatch=").append(dispatchCount)
            append(" vendorDispatch=").append(vendorDispatchCount)
            append(" aospDispatch=").append(aospDispatchCount)
            append(" scheduled=").append(dispatchScheduled)
            append(" activeUntil=").append(activeUntilUptimeMs)
            append(" backendStatus=").append(vendorBackend?.debugStatus() ?: "none")
        }
    }

    private fun scheduleDispatch() {
        if (dispatchScheduled || released || !enabled) return
        dispatchScheduled = true
        val baseDelayMs = (lastDispatchUptimeMs + MIN_DISPATCH_INTERVAL_MS - SystemClock.uptimeMillis())
            .coerceAtLeast(0L)
        val tunedDelayMs = (baseDelayMs + delayMs).coerceAtLeast(0L)
        handler.postDelayed({ dispatchInternal() }, tunedDelayMs)
    }

    private fun dispatchInternal() {
        dispatchScheduled = false
        if (!enabled || released) return

        val amplitude = amplitudeFor(pendingBassLevel, pendingKickStrength)
        val amplitudeBucket = amplitudeBucket(amplitude)
        if (amplitudeBucket == 0) {
            return
        }

        val now = SystemClock.uptimeMillis()
        if (now < activeUntilUptimeMs && amplitudeBucket == lastAmplitudeBucket) {
            return
        }
        if (now - lastDispatchUptimeMs < MIN_DISPATCH_INTERVAL_MS) {
            return
        }

        val durationMs = durationFor(amplitudeBucket, pendingKickStrength)
        val backend = vendorBackend
        val vendorOk = backend?.tryVibrate(amplitude, durationMs) == true
        val aospOk = if (backend == null && !vendorOk) dispatchAosp(amplitude, durationMs) else false
        val ok = vendorOk || aospOk
        if (!ok) {
            return
        }

        dispatchCount++
        if (vendorOk) vendorDispatchCount++
        if (aospOk) aospDispatchCount++
        lastAmplitude = amplitude
        lastAmplitudeBucket = amplitudeBucket
        lastDispatchUptimeMs = now
        activeUntilUptimeMs = now + durationMs
    }

    private fun dispatchAosp(amplitude: Int, durationMs: Long): Boolean {
        return runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && supportsAmplitudeControl -> {
                    vibrator.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    if (amplitude < AMPLITUDE_FALLBACK_GATE) return@runCatching false
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                }
                else -> {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(durationMs)
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun stopAllOutput() {
        vendorBackend?.stop()
        runCatching { vibrator.cancel() }
        lastAmplitude = 0
        lastAmplitudeBucket = 0
        activeUntilUptimeMs = 0L
    }

    private fun amplitudeFor(bassLevel: Float, kickStrength: Float): Int {
        val combined = (bassLevel * BASS_BODY_WEIGHT + kickStrength * KICK_ATTACK_WEIGHT)
            .coerceIn(0f, 1f)
        return when {
            combined < LEVEL_GATE -> 0
            combined < 0.18f -> 54
            combined < 0.34f -> 92
            combined < 0.52f -> 136
            combined < 0.74f -> 186
            else -> 232
        }
    }

    private fun amplitudeBucket(amplitude: Int): Int {
        return when {
            amplitude <= 0 -> 0
            amplitude < 80 -> 1
            amplitude < 160 -> 2
            else -> 3
        }
    }

    private fun durationFor(amplitudeBucket: Int, kickStrength: Float): Long {
        val base = when (amplitudeBucket) {
            1 -> 58
            2 -> 76
            else -> 96
        }
        val kickBonus = when {
            kickStrength < 0.18f -> 0
            kickStrength < 0.42f -> 10
            kickStrength < 0.68f -> 18
            else -> 26
        }
        return (base + kickBonus).coerceIn(50, 126).toLong()
    }

    private class VendorBackend private constructor(
        val label: String,
        private val invoker: (amplitude: Int, durationMs: Long) -> Boolean,
        private val stopper: () -> Unit = {}
    ) {
        private var lastStatus = "idle"

        fun tryVibrate(amplitude: Int, durationMs: Long): Boolean = runCatching {
            invoker(amplitude, durationMs).also { ok ->
                lastStatus = if (ok) {
                    "ok amp=$amplitude dur=$durationMs"
                } else {
                    "rejected amp=$amplitude dur=$durationMs"
                }
            }
        }.getOrElse {
            lastStatus = it.message ?: it.javaClass.simpleName
            false
        }

        fun stop() {
            runCatching {
                stopper()
                lastStatus = "stopped"
            }.onFailure {
                lastStatus = it.message ?: it.javaClass.simpleName
            }
        }

        fun debugStatus(): String = lastStatus

        companion object {
            fun detect(context: Context): VendorBackend? {
                detectMiui(context)?.let { return it }
                detectOplusLinearMotor(context)?.let { return it }
                detectVivoFf(context)?.let { return it }
                return null
            }

            private fun detectMiui(context: Context): VendorBackend? {
                val utilClass = runCatching { Class.forName("miui.util.HapticFeedbackUtil") }.getOrNull()
                if (utilClass != null) {
                    val ctor = runCatching {
                        utilClass.getConstructor(Context::class.java, Boolean::class.javaPrimitiveType)
                    }.getOrNull()
                    val instance = runCatching { ctor?.newInstance(context, false) }.getOrNull()
                    val perform = runCatching {
                        utilClass.getMethod("performExtHapticFeedback", Int::class.javaPrimitiveType)
                    }.getOrNull()
                    if (instance != null && perform != null) {
                        return VendorBackend(
                            label = "miui_reflect_util",
                            invoker = { amplitude, _ ->
                                perform.invoke(instance, miuiStrengthFromAmplitude(amplitude))
                                true
                            }
                        )
                    }
                }

                val systemVibratorClass = runCatching {
                    Class.forName("android.os.SystemVibrator")
                }.getOrNull()
                val linearMotorMethod = systemVibratorClass?.declaredMethods?.firstOrNull {
                    it.name.equals("linearMotorVibrate", ignoreCase = true) && it.parameterTypes.size >= 2
                }
                if (linearMotorMethod != null) {
                    linearMotorMethod.isAccessible = true
                    val service = context.getSystemService(Context.VIBRATOR_SERVICE)
                    return VendorBackend(
                        label = "miui_reflect_linear_motor",
                        invoker = { amplitude, durationMs ->
                            linearMotorMethod.invoke(service, amplitude, durationMs.toInt())
                            true
                        }
                    )
                }
                return null
            }

            private fun detectOplusLinearMotor(context: Context): VendorBackend? {
                val candidates = listOf(
                    "com.oplus.os.LinearmotorVibrator",
                    "com.oneplus.os.LinearmotorVibrator"
                )
                for (className in candidates) {
                    val cls = runCatching { Class.forName(className) }.getOrNull() ?: continue
                    val service = runCatching { context.getSystemService("linearmotor") }.getOrNull() ?: continue
                    val vibrate = cls.declaredMethods.firstOrNull {
                        it.name == "vibrate" && it.parameterTypes.size == 3
                    } ?: continue
                    vibrate.isAccessible = true
                    return VendorBackend(
                        label = "oplus_linear_motor",
                        invoker = { amplitude, _ ->
                            vibrate.invoke(service, 2, amplitude, 170)
                            true
                        }
                    )
                }
                return null
            }

            private fun detectVivoFf(context: Context): VendorBackend? {
                val vibratorService = context.getSystemService(Context.VIBRATOR_SERVICE) ?: return null
                val method = vibratorService.javaClass.declaredMethods.firstOrNull {
                    it.name == "vibratorPro" && it.parameterTypes.size >= 3
                } ?: return null
                method.isAccessible = true
                return VendorBackend(
                    label = "vivo_vibrator_pro",
                    invoker = { amplitude, durationMs ->
                        method.invoke(vibratorService, -1, amplitude, 170, durationMs)
                        true
                    }
                )
            }

            private fun miuiStrengthFromAmplitude(amplitude: Int): Int = when {
                amplitude >= 190 -> 0
                amplitude >= 135 -> 1
                amplitude >= 85 -> 2
                amplitude >= 45 -> 3
                else -> 4
            }
        }
    }

    companion object {
        private const val LEVEL_GATE = 0.05f
        private const val MIN_DISPATCH_INTERVAL_MS = 100L
        private const val AMPLITUDE_FALLBACK_GATE = 48
        private const val BASS_BODY_WEIGHT = 0.56f
        private const val KICK_ATTACK_WEIGHT = 0.88f

        @Volatile
        private var lastCreateStatus: String = "not-created"

        fun lastStatus(): String = lastCreateStatus

        fun createOrNull(context: Context, delayMs: Int): HapticAudioDriver? {
            lastCreateStatus = "initializing"
            val hasPermission = context.packageManager.checkPermission(
                android.Manifest.permission.VIBRATE,
                context.packageName
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                lastCreateStatus = "missing android.permission.VIBRATE"
                return null
            }
            val vibrator = resolveVibrator(context) ?: return null
            if (!vibrator.hasVibrator()) {
                lastCreateStatus = "device reports no vibrator"
                return null
            }
            val driver = HapticAudioDriver(context, vibrator, delayMs)
            lastCreateStatus = "created backend=${driver.vendorBackend?.label ?: "aosp"}"
            return driver
        }

        private fun resolveVibrator(context: Context): Vibrator? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }
    }
}
