package com.sb.dsp

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.pow

/**
 * Único backend principal de DSP para audio externo en Android.
 *
 * La aplicación no recibe el PCM de otras apps; por eso la ruta de reproducción
 * global se realiza mediante AudioEffect/DynamicsProcessing sobre la sesión 0.
 * No existe una fuente PCM propia: el procesamiento se realiza directamente en
 * la cadena global de Android mediante AudioEffect/DynamicsProcessing.
 *
 * La estructura de DynamicsProcessing (número de bandas/etapas) se crea una sola
 * vez. Los parámetros de ganancia, MBC y limiter se actualizan en tiempo real.
 * Los cambios rápidos del EQ se coalescen y se escriben con una cadencia mínima,
 * siguiendo un enfoque de actualización optimizada para evitar stutter durante el drag.
 */
class DynamicsProcessingManager {
    companion object {
        private const val TAG = "SB_DynamicsProcessing"
        const val MBC_BAND_COUNT = 4
        const val MAX_LOGICAL_EQ_BANDS = 32
        /**
         * Hard ceiling for the physical DP Pre-EQ layout created by SB.
         * EQ32 is the logical UI model; SB must never request 128 physical bands.
         * The runtime still probes downward because Android/vendor implementations
         * may reject a requested band count and expose a smaller usable layout.
         */
        const val MAX_PHYSICAL_EQ_BANDS = 32
        private const val MIN_EQ_WRITE_SPACING_MS = 24L
        private const val MIN_CUTOFF_HZ = 20f
        private const val MAX_CUTOFF_HZ = 22000f

        // DynamicsProcessing lets the app choose the physical Pre-EQ layout.
        // Keep that layout bounded to SB's EQ32 ceiling; never manufacture a
        // 64/128+ band input merely because the UI has a logical EQ model.
        private val EQ_BAND_CANDIDATES = intArrayOf(32, 24, 20, 16, 12, 10, 8, 6, 5, 4, 3, 2, 1)
    }

    data class BackendInfo(
        val preEqBandCount: Int = 0,
        val postEqBandCount: Int = 0,
        val mbcBandCount: Int = 0,
        val channelCount: Int = 0,
        val variant: Int = DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION
    )

    private var dynamicsProcessing: DynamicsProcessing? = null

    var isAvailable: Boolean = false
        private set

    var currentSessionId: Int = 0
        private set

    var backendInfo: BackendInfo = BackendInfo()
        private set

    @Volatile
    var hasControl: Boolean = false
        private set

    private var lastConfig: DspConfig = DspConfig.DEFAULT
    private var appliedConfig: DspConfig? = null
    @Volatile private var lastControlLossMs: Long = 0L
    private val reclaimHandler = Handler(android.os.Looper.getMainLooper())
    private val reclaimCooldownMs = 2000L

    private val effectLock = Any()
    private val eqWorkerThread = HandlerThread("SB-DpEqWriter").apply { start() }
    private val eqWorker = Handler(eqWorkerThread.looper)
    private val pendingEqConfig = AtomicReference<DspConfig?>(null)
    private var pendingEqWrite: Runnable? = null
    private var lastEqWriteMs = 0L

    fun initialize(
        audioSessionId: Int,
        priority: Int = Int.MAX_VALUE,
        initialConfig: DspConfig = DspConfig.DEFAULT
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            isAvailable = false
            backendInfo = BackendInfo()
            return false
        }

        releaseEffectOnly()
        hasControl = false
        currentSessionId = audioSessionId
        val safeConfig = initialConfig.validate()
        lastConfig = safeConfig
        ParametricToDpConverter.setNumBands(MAX_PHYSICAL_EQ_BANDS)

        for (preEqCount in EQ_BAND_CANDIDATES) {
            try {
                val config = buildConfig(preEqCount, safeConfig)
                val dp = DynamicsProcessing(priority, audioSessionId, config)
                hasControl = dp.hasControl()
                dp.setControlStatusListener { effect, granted ->
                    hasControl = granted
                    Log.i(TAG, "Control DP ${if (granted) "obtenido" else "perdido"}: session=$audioSessionId")
                    if (!granted) {
                        scheduleReclaim(safeConfig)
                    }
                }
                dp.setEnableStatusListener { _, enabled ->
                    if (!enabled && safeConfig.dspEnabled) scheduleReclaim(safeConfig)
                }
                dp.setEnabled(safeConfig.dspEnabled)

                dynamicsProcessing = dp
                isAvailable = true
                backendInfo = BackendInfo(
                    preEqBandCount = dp.getConfig().preEqBandCount,
                    postEqBandCount = dp.getConfig().postEqBandCount,
                    mbcBandCount = dp.getConfig().mbcBandCount,
                    channelCount = dp.channelCount,
                    variant = dp.getConfig().variant
                )
                appliedConfig = null

                Log.i(
                    TAG,
                    "DP inicializado: session=$audioSessionId preEQ=${backendInfo.preEqBandCount} " +
                        "postEQ=${backendInfo.postEqBandCount} MBC=${backendInfo.mbcBandCount} " +
                        "channels=${backendInfo.channelCount}"
                )
                return true
            } catch (e: Throwable) {
                Log.w(TAG, "DP no acepta preEQ=$preEqCount en session=$audioSessionId: ${e.message}")
            }
        }

        isAvailable = false
        backendInfo = BackendInfo()
        dynamicsProcessing = null
        return false
    }

    fun setOutputSampleRateHz(rateHz: Float) {
        if (rateHz.isFinite() && rateHz > 0f) {
            ParametricToDpConverter.deviceSampleRateHz = rateHz
        }
    }

    private fun scheduleReclaim(config: DspConfig) {
        val now = SystemClock.uptimeMillis()
        if (now - lastControlLossMs < reclaimCooldownMs) return
        lastControlLossMs = now
        reclaimHandler.postDelayed({
            synchronized(effectLock) {
                val dp = dynamicsProcessing ?: return@synchronized
                try {
                    if (!isAvailable) return@synchronized
                    if (dp.hasControl()) {
                        hasControl = true
                        if (config.dspEnabled && !dp.enabled) dp.setEnabled(true)
                        return@synchronized
                    }
                } catch (_: Throwable) {}
                val latest = lastConfig
                releaseEffectOnly()
                initialize(currentSessionId, Int.MAX_VALUE, latest)
                applyConfig(latest)
            }
        }, 100L)
    }

    private fun buildConfig(preEqCount: Int, config: DspConfig): DynamicsProcessing.Config {
        ParametricToDpConverter.setNumBands(preEqCount)
        val eq = buildParametricEq(config)
        val converted = ParametricToDpConverter.convertFeatureAware(eq)
        val builder = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            2,
            true,
            preEqCount,
            true,
            MBC_BAND_COUNT,
            false,
            0,
            true
        )

        for (channel in 0 until 2) {
            val dpEq = DynamicsProcessing.Eq(true, true, preEqCount)
            for (band in 0 until preEqCount) {
                dpEq.setBand(
                    band,
                    DynamicsProcessing.EqBand(true, converted.cutoffs[band], converted.gains[band])
                )
            }
            builder.setPreEqByChannelIndex(channel, dpEq)

            val mbc = DynamicsProcessing.Mbc(true, config.mdrcEnabled, MBC_BAND_COUNT)
            val mdrcBands = config.mdrcBands()
            val cutoffs = config.mdrcCutoffs()
            for (band in 0 until MBC_BAND_COUNT) {
                mbc.setBand(band, createNativeMdrcBand(mdrcBands[band], cutoffs[band], config.mdrcEnabled))
            }
            builder.setMbcByChannelIndex(channel, mbc)
            builder.setLimiterByChannelIndex(channel, createNativeLimiter(config))
        }
        return builder.build()
    }

    /** Builds SB's logical EQ as parametric filters; UI remains EQ10/EQ20/EQ32. */
    private fun buildParametricEq(config: DspConfig): FunctionalParametricEqualizer {
        val eq = FunctionalParametricEqualizer(48000).apply { isEnabled = config.dspEnabled }
        eq.clearBands()

        val freqs = config.activeEqFrequencies()
        val gains = config.activeEqGains()
        for (i in freqs.indices) {
            eq.addBand(
                freqs[i].coerceIn(10f, 20000f),
                gains.getOrElse(i) { 0f }.coerceIn(-15f, 15f),
                FunctionalBiquadFilter.FilterType.BELL,
                4.318
            )
        }

        // Tone is part of the same parametric response before conversion.
        eq.addBand(100f, config.toneBass.coerceIn(-15f, 15f), FunctionalBiquadFilter.FilterType.LOW_SHELF, 0.707)
        eq.addBand(1000f, config.toneMid.coerceIn(-15f, 15f), FunctionalBiquadFilter.FilterType.BELL, 1.0)
        eq.addBand(10000f, config.toneTreble.coerceIn(-15f, 15f), FunctionalBiquadFilter.FilterType.HIGH_SHELF, 0.707)
        return eq
    }

    fun applyConfig(config: DspConfig, autoHeadroomDb: Float = 0f, autoGainDb: Float = 0f) {
        val safe = config.validate()
        lastConfig = safe

        val dp = dynamicsProcessing ?: return
        if (!isAvailable || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        try {
            val previous = appliedConfig

            if (dp.enabled != safe.dspEnabled) {
                dp.setEnabled(safe.dspEnabled)
            }

            if (!safe.dspEnabled) {
                appliedConfig = safe
                return
            }

            applyInputGain(safe, autoHeadroomDb, autoGainDb)

            val dspStateChanged = previous == null || previous.dspEnabled != safe.dspEnabled
            val eqChanged = dspStateChanged ||
                previous?.eqMode != safe.eqMode ||
                previous?.activeEqGains() != safe.activeEqGains() ||
                previous?.toneBass != safe.toneBass ||
                previous?.toneMid != safe.toneMid ||
                previous?.toneTreble != safe.toneTreble

            val mdrcChanged = dspStateChanged ||
                previous?.mdrcEnabled != safe.mdrcEnabled ||
                previous?.mdrcBand1 != safe.mdrcBand1 ||
                previous?.mdrcBand2 != safe.mdrcBand2 ||
                previous?.mdrcBand3 != safe.mdrcBand3 ||
                previous?.mdrcBand4 != safe.mdrcBand4 ||
                previous?.mdrcCutoff1 != safe.mdrcCutoff1 ||
                previous?.mdrcCutoff2 != safe.mdrcCutoff2 ||
                previous?.mdrcCutoff3 != safe.mdrcCutoff3 ||
                previous?.mdrcCutoff4 != safe.mdrcCutoff4

            val limiterChanged = dspStateChanged ||
                previous?.limiterEnabled != safe.limiterEnabled ||
                previous?.limiterThreshold != safe.limiterThreshold ||
                previous?.limiterAttack != safe.limiterAttack ||
                previous?.limiterRelease != safe.limiterRelease ||
                previous?.limiterRatio != safe.limiterRatio

            if (mdrcChanged) applyMdrc(dp, safe)
            if (limiterChanged) applyLimiter(dp, safe)
            if (eqChanged) scheduleEqWrite(safe)

            appliedConfig = safe
        } catch (e: Throwable) {
            Log.e(TAG, "Error aplicando configuración DP", e)
        }
    }

    /**
     * Actualiza únicamente las ganancias globales de entrada. AutoGain usa este
     * camino para no reescribir EQ/MDRC/limiter en cada medición RMS.
     */
    fun applyInputGain(config: DspConfig, autoHeadroomDb: Float = 0f, autoGainDb: Float = 0f) {
        val dp = dynamicsProcessing ?: return
        if (!isAvailable || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        try {
            val safe = config.validate()
            if (!safe.dspEnabled) return

            val master = safe.masterGain
            val leftBalance = if (safe.balance > 0f) -40f * safe.balance else 0f
            val rightBalance = if (safe.balance < 0f) 40f * safe.balance else 0f
            val baseGain = (safe.preGain + autoHeadroomDb + autoGainDb + master).coerceIn(-40f, 12f)

            if (backendInfo.channelCount >= 2) {
                dp.setInputGainbyChannel(0, (baseGain + leftBalance).coerceIn(-40f, 12f))
                dp.setInputGainbyChannel(1, (baseGain + rightBalance).coerceIn(-40f, 12f))
            } else {
                dp.setInputGainAllChannelsTo(baseGain)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error actualizando ganancias de entrada DP: ${e.message}")
        }
    }

    private fun scheduleEqWrite(config: DspConfig) {
        pendingEqConfig.set(config)
        pendingEqWrite?.let(eqWorker::removeCallbacks)

        val job = Runnable {
            synchronized(effectLock) {
                val latest = pendingEqConfig.getAndSet(null) ?: return@synchronized
                val dp = dynamicsProcessing ?: return@synchronized
                try {
                    val count = dp.getConfig().preEqBandCount
                    if (count <= 0) return@synchronized
                    ParametricToDpConverter.setNumBands(count)
                    val converted = ParametricToDpConverter.convertFeatureAware(buildParametricEq(latest))
                    val n = minOf(count, converted.cutoffs.size, converted.gains.size)
                    val leftEq = DynamicsProcessing.Eq(true, true, count)
                    val rightEq = DynamicsProcessing.Eq(true, true, count)
                    for (band in 0 until n) {
                        val b = DynamicsProcessing.EqBand(latest.dspEnabled, converted.cutoffs[band], converted.gains[band])
                        leftEq.setBand(band, b)
                        rightEq.setBand(band, b)
                    }
                    // Preserve any remaining physical slots as neutral filters.
                    for (band in n until count) {
                        val b = DynamicsProcessing.EqBand(latest.dspEnabled, 20000f, 0f)
                        leftEq.setBand(band, b)
                        rightEq.setBand(band, b)
                    }
                    dp.setPreEqByChannelIndex(0, leftEq)
                    dp.setPreEqByChannelIndex(1, rightEq)
                    lastEqWriteMs = SystemClock.uptimeMillis()
                } catch (e: Throwable) {
                    Log.e(TAG, "Error escribiendo EQ paramétrico DP", e)
                } finally {
                    pendingEqWrite = null
                    if (pendingEqConfig.get() != null) scheduleEqWrite(pendingEqConfig.get()!!)
                }
            }
        }
        pendingEqWrite = job
        val delay = (lastEqWriteMs + MIN_EQ_WRITE_SPACING_MS - SystemClock.uptimeMillis())
            .coerceIn(0L, MIN_EQ_WRITE_SPACING_MS)
        eqWorker.postDelayed(job, delay)
    }

    private fun applyMdrc(dp: DynamicsProcessing, config: DspConfig) {
        val count = dp.getConfig().mbcBandCount.coerceAtMost(MBC_BAND_COUNT)
        if (count <= 0) return
        val bands = config.mdrcBands()
        val cutoffs = config.mdrcCutoffs()

        for (channel in 0 until dp.channelCount) {
            for (band in 0 until count) {
                dp.setMbcBandByChannelIndex(
                    channel,
                    band,
                    createNativeMdrcBand(bands[band], cutoffs[band], config.mdrcEnabled)
                )
            }
        }
    }

    private fun createNativeMdrcBand(
        source: MdrcBandConfig,
        cutoff: Float,
        enabled: Boolean
    ): DynamicsProcessing.MbcBand = DynamicsProcessing.MbcBand(
        enabled && source.enabled,
        cutoff.coerceIn(MIN_CUTOFF_HZ, MAX_CUTOFF_HZ),
        source.attackTime.coerceIn(0.5f, 500f),
        source.releaseTime.coerceIn(5f, 1500f),
        source.ratio.coerceIn(1f, 20f),
        source.threshold.coerceIn(-60f, 0f),
        source.kneeWidth.coerceIn(0f, 24f),
        -80f,
        1f,
        source.preGain.coerceIn(-20f, 20f),
        source.postGain.coerceIn(-20f, 20f)
    )

    private fun applyLimiter(dp: DynamicsProcessing, config: DspConfig) {
        val limiter = createNativeLimiter(config)
        for (channel in 0 until dp.channelCount) {
            dp.setLimiterByChannelIndex(channel, limiter)
        }
    }

    private fun createNativeLimiter(config: DspConfig): DynamicsProcessing.Limiter = DynamicsProcessing.Limiter(
        config.limiterEnabled,
        config.limiterEnabled,
        0,
        config.limiterAttack.coerceIn(0.1f, 100f),
        config.limiterRelease.coerceIn(5f, 1000f),
        config.limiterRatio.coerceIn(1f, 50f),
        config.limiterThreshold.coerceIn(-24f, 0f),
        0f
    )

    fun isAlive(): Boolean {
        if (!isAvailable) return false
        return try {
            val dp = dynamicsProcessing ?: return false
            hasControl && dp.channelCount > 0
        } catch (_: Throwable) {
            false
        }
    }

    fun release() {
        pendingEqWrite?.let(eqWorker::removeCallbacks)
        pendingEqWrite = null
        pendingEqConfig.set(null)
        releaseEffectOnly()
    }

    private fun releaseEffectOnly() {
        reclaimHandler.removeCallbacksAndMessages(null)
        synchronized(effectLock) {
            try {
                dynamicsProcessing?.enabled = false
                dynamicsProcessing?.release()
            } catch (e: Throwable) {
                Log.w(TAG, "Error liberando DynamicsProcessing: ${e.message}")
            } finally {
                dynamicsProcessing = null
                isAvailable = false
                hasControl = false
                backendInfo = BackendInfo()
                appliedConfig = null
            }
        }
    }

    fun shutdown() {
        release()
        eqWorkerThread.quitSafely()
    }
}

private fun DspConfig.mdrcBands(): Array<MdrcBandConfig> = arrayOf(
    mdrcBand1,
    mdrcBand2,
    mdrcBand3,
    mdrcBand4
)

private fun DspConfig.mdrcCutoffs(): FloatArray = floatArrayOf(
    mdrcCutoff1,
    mdrcCutoff2,
    mdrcCutoff3,
    mdrcCutoff4
)
