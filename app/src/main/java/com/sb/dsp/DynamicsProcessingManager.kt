package com.sb.dsp

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

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
        private val EQ_BAND_CANDIDATES = intArrayOf(
            MAX_PHYSICAL_EQ_BANDS, 24, 20, 16, 12, 10, 8, 6, 5, 4, 3, 2, 1
        )
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

    private val effectLock = Any()
    private val eqWorkerThread = HandlerThread("SB-DpEqWriter").apply { start() }
    private val eqWorker = Handler(eqWorkerThread.looper)
    private val pendingEqConfig = AtomicReference<DspConfig?>(null)
    private var pendingEqWrite: Runnable? = null
    private var lastEqWriteMs = 0L

    fun initialize(
        audioSessionId: Int,
        priority: Int = 1000,
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

        for (preEqCount in EQ_BAND_CANDIDATES) {
            try {
                val config = buildConfig(preEqCount, safeConfig)
                val dp = DynamicsProcessing(priority, audioSessionId, config)
                dp.setControlStatusListener { _, granted ->
                    hasControl = granted
                    Log.i(TAG, "Control DP ${if (granted) "obtenido" else "perdido"}: session=$audioSessionId")
                }
                hasControl = dp.hasControl()
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

    private fun buildConfig(preEqCount: Int, config: DspConfig): DynamicsProcessing.Config {
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

        val logicalFreqs = config.activeEqFrequencies()
        val logicalGains = config.activeEqGains()
        val physicalFreqs = buildPhysicalEqFrequencies(preEqCount)
        val mapped = mapLogicalGains(logicalFreqs, logicalGains, physicalFreqs)

        for (channel in 0 until 2) {
            val eq = DynamicsProcessing.Eq(true, true, preEqCount)
            for (band in 0 until preEqCount) {
                eq.setBand(
                    band,
                    DynamicsProcessing.EqBand(
                        config.dspEnabled,
                        physicalFreqs[band],
                        mapped[band]
                    )
                )
            }
            builder.setPreEqByChannelIndex(channel, eq)

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

    /**
     * Log-spaced physical centers used only for the actual DP bands that were
     * successfully created. Logical EQ frequencies remain independent.
     */
    private fun buildPhysicalEqFrequencies(count: Int): FloatArray {
        if (count <= 1) return floatArrayOf(1000f)
        val minLog = kotlin.math.ln(16f)
        val maxLog = kotlin.math.ln(20000f)
        return FloatArray(count) { index ->
            kotlin.math.exp(minLog + (maxLog - minLog) * index / (count - 1).toFloat())
        }
    }

    private fun mapLogicalGains(
        logicalFreqs: List<Float>,
        logicalGains: List<Float>,
        physicalFreqs: FloatArray
    ): FloatArray {
        val result = FloatArray(physicalFreqs.size)
        if (logicalFreqs.isEmpty() || logicalGains.isEmpty()) return result

        for (i in physicalFreqs.indices) {
            var gain = CapabilityAdapter.interpolateGainAtFrequency(
                physicalFreqs[i],
                logicalFreqs,
                logicalGains
            )

            // Tone controls must follow their actual filter shapes. A simple
            // linear interpolation between 100/1000/10000 Hz is not equivalent
            // to Bass (low-shelf), Mid (peaking) and Treble (high-shelf), and
            // made the three controls behave incorrectly on the physical DP EQ.
            // We therefore calculate the RBJ magnitude response of each tone
            // filter at every physical DP band and add that response to the
            // logical EQ gain. The Android DP EqBand still supplies the actual
            // processing; this only computes the gain needed at each band.
            gain += toneResponseDb(
                physicalFreqs[i],
                lastConfig.toneBass,
                lastConfig.toneMid,
                lastConfig.toneTreble
            )
            result[i] = gain.coerceIn(-15f, 15f)
        }
        return result
    }

    /**
     * Returns the combined frequency response, in dB, of SB's three tone
     * controls using the same RBJ definitions as ToneManager.
     */
    private fun toneResponseDb(
        frequencyHz: Float,
        bassDb: Float,
        midDb: Float,
        trebleDb: Float
    ): Float {
        val f = frequencyHz.coerceAtLeast(1f)
        return shelfResponseDb(f, 100f, bassDb, lowShelf = true, q = 0.707f) +
            peakingResponseDb(f, 1000f, midDb, q = 1.0f) +
            shelfResponseDb(f, 10000f, trebleDb, lowShelf = false, q = 0.707f)
    }

    private fun shelfResponseDb(
        frequencyHz: Float,
        centerHz: Float,
        gainDb: Float,
        lowShelf: Boolean,
        q: Float
    ): Float {
        if (abs(gainDb) < 0.0001f) return 0f
        val a = kotlin.math.pow(10.0, gainDb / 40.0)
        val w0 = 2.0 * Math.PI * centerHz / 48000.0
        val w = 2.0 * Math.PI * frequencyHz.coerceAtLeast(1f) / 48000.0
        val alpha = kotlin.math.sin(w0) / (2.0 * q)
        val cosW0 = kotlin.math.cos(w0)
        val sqrtA = kotlin.math.sqrt(a)
        val beta = 2.0 * sqrtA * alpha
        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double
        if (lowShelf) {
            b0 = a * ((a + 1) - (a - 1) * cosW0 + beta)
            b1 = 2 * a * ((a - 1) - (a + 1) * cosW0)
            b2 = a * ((a + 1) - (a - 1) * cosW0 - beta)
            a0 = (a + 1) + (a - 1) * cosW0 + beta
            a1 = -2 * ((a - 1) + (a + 1) * cosW0)
            a2 = (a + 1) + (a - 1) * cosW0 - beta
        } else {
            b0 = a * ((a + 1) + (a - 1) * cosW0 + beta)
            b1 = -2 * a * ((a - 1) + (a + 1) * cosW0)
            b2 = a * ((a + 1) + (a - 1) * cosW0 - beta)
            a0 = (a + 1) - (a - 1) * cosW0 + beta
            a1 = 2 * ((a - 1) - (a + 1) * cosW0)
            a2 = (a + 1) - (a - 1) * cosW0 - beta
        }
        return biquadMagnitudeDb(b0 / a0, b1 / a0, b2 / a0, 1.0, a1 / a0, a2 / a0, w)
    }

    private fun peakingResponseDb(
        frequencyHz: Float,
        centerHz: Float,
        gainDb: Float,
        q: Float
    ): Float {
        if (abs(gainDb) < 0.0001f) return 0f
        val a = kotlin.math.pow(10.0, gainDb / 40.0)
        val wc = 2.0 * Math.PI * centerHz / 48000.0
        val w = 2.0 * Math.PI * frequencyHz.coerceAtLeast(1f) / 48000.0
        val alpha = kotlin.math.sin(wc) / (2.0 * q)
        val cosWc = kotlin.math.cos(wc)
        val b0 = 1 + alpha * a
        val b1 = -2 * cosWc
        val b2 = 1 - alpha * a
        val a0 = 1 + alpha / a
        val a1 = -2 * cosWc
        val a2 = 1 - alpha / a
        return biquadMagnitudeDb(b0 / a0, b1 / a0, b2 / a0, 1.0, a1 / a0, a2 / a0, w)
    }

    private fun biquadMagnitudeDb(
        b0: Double, b1: Double, b2: Double,
        a0: Double, a1: Double, a2: Double,
        w: Double
    ): Float {
        val cosW = kotlin.math.cos(w)
        val cos2W = kotlin.math.cos(2.0 * w)
        val sinW = kotlin.math.sin(w)
        val sin2W = kotlin.math.sin(2.0 * w)
        val nr = b0 + b1 * cosW + b2 * cos2W
        val ni = -(b1 * sinW + b2 * sin2W)
        val dr = a0 + a1 * cosW + a2 * cos2W
        val di = -(a1 * sinW + a2 * sin2W)
        val mag = kotlin.math.sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
        return (20.0 * kotlin.math.log10(mag.coerceAtLeast(1e-9))).toFloat()
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
                    val physicalFreqs = buildPhysicalEqFrequencies(count)
                    val mapped = mapLogicalGains(
                        latest.activeEqFrequencies(),
                        latest.activeEqGains(),
                        physicalFreqs
                    )

                    for (channel in 0 until dp.channelCount) {
                        for (band in 0 until count) {
                            dp.setPreEqBandByChannelIndex(
                                channel,
                                band,
                                DynamicsProcessing.EqBand(
                                    latest.dspEnabled,
                                    physicalFreqs[band],
                                    mapped[band]
                                )
                            )
                        }
                    }
                    lastEqWriteMs = SystemClock.uptimeMillis()
                } catch (e: Throwable) {
                    Log.e(TAG, "Error escribiendo EQ DP", e)
                } finally {
                    pendingEqWrite = null
                    if (pendingEqConfig.get() != null) {
                        scheduleEqWrite(pendingEqConfig.get()!!)
                    }
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
