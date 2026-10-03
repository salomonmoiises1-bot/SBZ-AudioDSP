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

    /** Software MDRC is retained only for direct PCM callers and diagnostics. */
    val softwareMdrc = MdrcProcessor()

    private var appliedCutoffs = floatArrayOf(160f, 800f, 4000f, 20000f)
    private var lastConfig: DspConfig = DspConfig.DEFAULT
    private var lastAutoHeadroomDb = 0f
    private var lastAutoGainDb = 0f

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
        currentSessionId = audioSessionId
        val safeConfig = initialConfig.validate()
        softwareMdrc.updateConfig(safeConfig)
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
                appliedCutoffs = floatArrayOf(
                    safeConfig.mdrcCutoff1,
                    safeConfig.mdrcCutoff2,
                    safeConfig.mdrcCutoff3,
                    safeConfig.mdrcCutoff4
                )

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

            // Tone controls are part of the same EQ stage so they are not
            // accidentally applied twice by a second software filter.
            gain += CapabilityAdapter.interpolateGainAtFrequency(
                physicalFreqs[i],
                listOf(100f, 1000f, 10000f),
                listOf(lastConfig.toneBass, lastConfig.toneMid, lastConfig.toneTreble)
            )
            result[i] = gain.coerceIn(-15f, 15f)
        }
        return result
    }

    fun applyConfig(config: DspConfig, autoHeadroomDb: Float = 0f, autoGainDb: Float = 0f) {
        val safe = config.validate()
        lastConfig = safe
        lastAutoHeadroomDb = autoHeadroomDb
        lastAutoGainDb = autoGainDb
        softwareMdrc.updateConfig(safe)

        val dp = dynamicsProcessing ?: return
        if (!isAvailable || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return

        try {
            dp.setEnabled(safe.dspEnabled)
            if (!safe.dspEnabled) return

            // Input gain is the only global gain stage available in the DP path.
            // Master gain and balance are therefore applied here per channel.
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

            applyMdrc(dp, safe)
            applyLimiter(dp, safe)
            scheduleEqWrite(safe)
        } catch (e: Throwable) {
            Log.e(TAG, "Error aplicando configuración DP", e)
        }
    }

    private fun scheduleEqWrite(config: DspConfig) {
        pendingEqConfig.set(config)
        pendingEqWrite?.let(eqWorker::removeCallbacks)

        val job = Runnable {
            val latest = pendingEqConfig.getAndSet(null) ?: return@Runnable
            val dp = dynamicsProcessing ?: return@Runnable
            try {
                val count = dp.getConfig().preEqBandCount
                if (count <= 0) return@Runnable
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
                if (pendingEqConfig.get() != null) scheduleEqWrite(pendingEqConfig.get()!!)
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
        appliedCutoffs = cutoffs.copyOf()
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

    fun getGainReductionDb(): FloatArray = softwareMdrc.getGainReductionDb()

    fun release() {
        pendingEqWrite?.let(eqWorker::removeCallbacks)
        pendingEqWrite = null
        pendingEqConfig.set(null)
        releaseEffectOnly()
        softwareMdrc.reset()
    }

    private fun releaseEffectOnly() {
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
