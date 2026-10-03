package com.sbznext.dsp

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

/**
 * Global Android DSP engine.
 *
 * The production path is a single DynamicsProcessing effect on session 0.
 * No AudioRecord/AudioTrack/MediaProjection copy path is used, so the original
 * media signal is not mixed with a delayed software copy.
 *
 * The EQ is configured explicitly with 32 bands instead of accepting the device's
 * default band count. Android's Config.Builder allows the application to choose
 * the EQ band count at effect creation time.
 */
class DspEngine(private val sampleRate: Int) {

    companion object {
        const val GLOBAL_AUDIO_SESSION = 0

        private const val EFFECT_PRIORITY = 100
        private const val EQ_BANDS = 32
        private const val MDRC_BANDS = 4

        private const val MIN_FREQ = 20f
        private const val MIN_DB = -24f
        private const val MAX_DB = 24f
        private const val DEFAULT_FRAME_MS = 10f
    }

    private val nyquist =
        (sampleRate * 0.5f).coerceAtLeast(1000f)

    @Volatile
    private var config = DspConfig()

    @Volatile
    private var pendingConfig: DspConfig? = null

    private var dynamics: DynamicsProcessing? = null
    private var virtualizer: Virtualizer? = null
    private var updateHandler: Handler? = null

    @Volatile
    var activeEqBands: Int = 0
        private set

    @Volatile
    var lastAutoGainDb = 0f
        private set

    @Volatile
    var clipping = false
        private set

    init {
        config = sanitize(config)
    }

    @Synchronized
    fun start() {
        if (dynamics != null) return

        check(android.os.Build.VERSION.SDK_INT >= 28) {
            "DynamicsProcessing requiere Android 9+"
        }

        val channelCount = 2
        val dp = createDynamics(channelCount)

        dynamics = dp

        activeEqBands = dp.config.getPreEqBandCount()

        updateHandler =
            Handler(Looper.getMainLooper())

        runCatching {
            virtualizer = Virtualizer(
                EFFECT_PRIORITY,
                GLOBAL_AUDIO_SESSION
            )
        }

        applyNative(config)
    }

    private fun createDynamics(
        channelCount: Int
    ): DynamicsProcessing {
        var last: Throwable? = null

        for (bands in intArrayOf(EQ_BANDS, 16, 10, 5)) {
            try {
                val builder =
                    DynamicsProcessing.Config.Builder(
                        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                        channelCount,
                        true,
                        bands,
                        true,
                        MDRC_BANDS,
                        false,
                        0,
                        true
                    ).apply {
                        setPreferredFrameDuration(
                            DEFAULT_FRAME_MS
                        )
                    }

                return DynamicsProcessing(
                    EFFECT_PRIORITY,
                    GLOBAL_AUDIO_SESSION,
                    builder.build()
                )
            } catch (t: Throwable) {
                last = t
            }
        }

        throw last
            ?: IllegalStateException(
                "No se pudo crear DynamicsProcessing"
            )
    }

    @Synchronized
    fun release() {
        runCatching {
            virtualizer?.setEnabled(false)
        }

        runCatching {
            virtualizer?.release()
        }

        virtualizer = null

        runCatching {
            dynamics?.setEnabled(false)
        }

        runCatching {
            updateHandler?.removeCallbacksAndMessages(null)
        }

        updateHandler = null

        runCatching {
            dynamics?.release()
        }

        dynamics = null
    }

    fun isRunning(): Boolean =
        dynamics != null

    fun update(input: DspConfig) {
        val sanitized = sanitize(input)

        config = sanitized
        pendingConfig = sanitized

        if (dynamics != null) {
            val handler = updateHandler

            if (handler != null) {
                handler.removeCallbacks(applyRunnable)
                handler.postDelayed(
                    applyRunnable,
                    35L
                )
            } else {
                applyPending()
            }
        }
    }

    fun snapshot(): DspConfig =
        config

    private val applyRunnable =
        Runnable {
            applyPending()
        }

    /**
     * Deterministic offline/unit-test path.
     * Production audio does not enter here.
     */
    fun process(buf: FloatArray) {
        val c = pendingConfig ?: config

        pendingConfig = null
        config = c

        val master =
            dbToLinear(
                c.preGainDb +
                    c.masterGainDb +
                    autoCompensationDb(c)
            )

        val ceiling =
            dbToLinear(c.limiterCeilingDb)

        var peak = 0f
        var i = 0

        while (i + 1 < buf.size) {
            var l = buf[i] * master
            var r = buf[i + 1] * master

            if (c.spatialEnabled) {
                val mid = (l + r) * 0.5f
                val side =
                    (l - r) *
                        0.5f *
                        c.spatialWidth

                l = mid + side
                r = mid - side
            }

            val bal = c.balance

            if (bal > 0f) {
                l *= 1f - bal
            } else if (bal < 0f) {
                r *= 1f + bal
            }

            if (c.limiterEnabled) {
                l = softCeiling(
                    l,
                    ceiling
                )

                r = softCeiling(
                    r,
                    ceiling
                )
            }

            if (!l.isFinite()) {
                l = 0f
            }

            if (!r.isFinite()) {
                r = 0f
            }

            buf[i] =
                l.coerceIn(-1f, 1f)

            buf[i + 1] =
                r.coerceIn(-1f, 1f)

            val currentPeak =
                maxOf(
                    abs(buf[i]),
                    abs(buf[i + 1])
                )

            if (currentPeak > peak) {
                peak = currentPeak
            }

            i += 2
        }

        lastAutoGainDb =
            autoCompensationDb(c)

        clipping =
            peak >= 0.999f
    }

    private fun applyPending() {
        val next =
            pendingConfig ?: return

        pendingConfig = null

        applyNative(next)
    }

    private fun applyNative(c: DspConfig) {
        val dp =
            dynamics ?: return

        runCatching {
            val cfg = dp.config

            val channels =
                cfg.getChannelCount()
                    .coerceAtLeast(1)

            val bandCount =
                cfg.getPreEqBandCount()
                    .coerceAtMost(EQ_BANDS)

            dp.setEnabled(c.enabled)

            val compensation =
                if (c.autoGainEnabled) {
                    autoCompensationDb(c)
                } else {
                    0f
                }

            lastAutoGainDb =
                compensation

            val baseInput =
                (
                    c.preGainDb +
                        c.masterGainDb +
                        compensation
                    ).coerceIn(
                        MIN_DB,
                        MAX_DB
                    )

            for (ch in 0 until channels) {
                val gain =
                    when {
                        ch == 0 &&
                            c.balance > 0f ->
                            baseInput +
                                balanceDb(
                                    1f - c.balance
                                )

                        ch == 1 &&
                            c.balance < 0f ->
                            baseInput +
                                balanceDb(
                                    1f + c.balance
                                )

                        else ->
                            baseInput
                    }.coerceIn(
                        MIN_DB,
                        MAX_DB
                    )

                dp.setInputGainbyChannel(
                    ch,
                    gain
                )
            }

            /*
             * Map the 32 user EQ bands onto the available DP pre-EQ bands.
             * No second software EQ stage is introduced here.
             */
            for (i in 0 until bandCount) {
                val band =
                    cfg.getPreEqBandByChannelIndex(
                        0,
                        i
                    )

                val mapped =
                    if (bandCount <= 1) {
                        0
                    } else {
                        kotlin.math.round(
                            i *
                                DspConfig.FREQUENCIES.lastIndex
                                    .toFloat() /
                                (bandCount - 1)
                        ).toInt()
                    }

                val hz =
                    DspConfig.FREQUENCIES[
                        mapped.coerceIn(
                            0,
                            DspConfig.FREQUENCIES.lastIndex
                        )
                    ].coerceIn(
                        MIN_FREQ,
                        nyquist - 50f
                    )

                band.setCutoffFrequency(hz)

                band.setGain(
                    combinedEqGain(
                        c,
                        hz
                    ).coerceIn(
                        MIN_DB,
                        MAX_DB
                    )
                )

                band.setEnabled(
                    c.enabled &&
                        (
                            c.eqEnabled ||
                                hasToneControls(c)
                            )
                )

                for (ch in 0 until channels) {
                    dp.setPreEqBandByChannelIndex(
                        ch,
                        i,
                        band
                    )
                }
            }

            val mbcCount =
                cfg.getMbcBandCount()
                    .coerceAtMost(MDRC_BANDS)

            for (i in 0 until mbcCount) {
                val p =
                    c.mdrcBands[i]

                val band =
                    cfg.getMbcBandByChannelIndex(
                        0,
                        i
                    )

                band.setCutoffFrequency(
                    c.mdrcCutoffsHz[i].coerceIn(
                        MIN_FREQ,
                        nyquist - 50f
                    )
                )

                band.setEnabled(
                    c.enabled &&
                        c.mdrcEnabled
                )

                band.setAttackTime(
                    p.attackMs
                )

                band.setReleaseTime(
                    p.releaseMs
                )

                band.setRatio(
                    p.ratio
                )

                band.setThreshold(
                    p.thresholdDb
                )

                band.setPreGain(0f)

                band.setPostGain(
                    p.makeupDb
                )

                for (ch in 0 until channels) {
                    dp.setMbcBandByChannelIndex(
                        ch,
                        i,
                        band
                    )
                }
            }

            for (ch in 0 until channels) {
                val limiter =
                    cfg.getLimiterByChannelIndex(
                        ch
                    )

                limiter.setEnabled(
                    c.enabled &&
                        c.limiterEnabled
                )

                limiter.setThreshold(
                    c.limiterCeilingDb
                )

                limiter.setRatio(20f)
                limiter.setAttackTime(1f)
                limiter.setReleaseTime(80f)
                limiter.setPostGain(0f)

                dp.setLimiterByChannelIndex(
                    ch,
                    limiter
                )
            }

            dp.setEnabled(c.enabled)
        }

        runCatching {
            val v =
                virtualizer
                    ?: return@runCatching

            v.setEnabled(
                c.enabled &&
                    c.spatialEnabled
            )

            if (v.strengthSupported) {
                v.setStrength(
                    (
                        c.spatialWidth
                            .coerceIn(0f, 1f) *
                            1000f
                        ).toInt()
                        .toShort()
                )
            }
        }
    }

    private fun hasToneControls(
        c: DspConfig
    ): Boolean =
        c.bassBoostDb != 0f ||
            c.toneBassDb != 0f ||
            c.toneMidDb != 0f ||
            c.toneTrebleDb != 0f

    private fun combinedEqGain(
        c: DspConfig,
        hz: Float
    ): Float {
        val eq =
            if (c.eqEnabled) {
                interpolatedEqGain(
                    c.eqGainsDb,
                    hz
                )
            } else {
                0f
            }

        return (
            eq +
                toneGain(
                    c,
                    hz
                )
            ).coerceIn(
                MIN_DB,
                MAX_DB
            )
    }

    private fun interpolatedEqGain(
        gains: FloatArray,
        hz: Float
    ): Float {
        if (
            hz <=
            DspConfig.FREQUENCIES.first()
        ) {
            return gains.firstOrNull()
                ?: 0f
        }

        if (
            hz >=
            DspConfig.FREQUENCIES.last()
        ) {
            return gains.lastOrNull()
                ?: 0f
        }

        var i = 0

        while (
            i <
            DspConfig.FREQUENCIES.lastIndex &&
            DspConfig.FREQUENCIES[i + 1] < hz
        ) {
            i++
        }

        val lo =
            DspConfig.FREQUENCIES[i]

        val hi =
            DspConfig.FREQUENCIES[i + 1]

        val denominator =
            ln(hi.toDouble()) -
                ln(lo.toDouble())

        val numerator =
            ln(hz.toDouble()) -
                ln(lo.toDouble())

        val t =
            (numerator / denominator)
                .toFloat()
                .coerceIn(0f, 1f)

        return gains[i] +
            (gains[i + 1] - gains[i]) *
            t
    }

    private fun toneGain(
        c: DspConfig,
        hz: Float
    ): Float {

        val bassWeight =
            when {
                hz <= 80f ->
                    1f

                hz >= 250f ->
                    0f

                else -> {
                    val t =
                        ln(
                            (hz / 80f)
                                .toDouble()
                        ) /
                            ln(
                                (250f / 80f)
                                    .toDouble()
                            )

                    (1.0 - t)
                        .toFloat()
                }
            }.coerceIn(
                0f,
                1f
            )

        val midWeight =
            when {
                hz <= 250f ||
                    hz >= 3000f ->
                    0f

                hz < 1000f -> {
                    (
                        ln(
                            (hz / 250f)
                                .toDouble()
                        ) /
                            ln(
                                (1000f / 250f)
                                    .toDouble()
                            )
                        ).toFloat()
                }

                else -> {
                    (
                        1.0 -
                            ln(
                                (hz / 1000f)
                                    .toDouble()
                            ) /
                            ln(
                                (3000f / 1000f)
                                    .toDouble()
                            )
                        ).toFloat()
                }
            }.coerceIn(
                0f,
                1f
            )

        val trebleWeight =
            when {
                hz <= 3000f ->
                    0f

                hz >= 10000f ->
                    1f

                else -> {
                    (
                        ln(
                            (hz / 3000f)
                                .toDouble()
                        ) /
                            ln(
                                (10000f / 3000f)
                                    .toDouble()
                            )
                        ).toFloat()
                }
            }.coerceIn(
                0f,
                1f
            )

        return c.bassBoostDb *
            bassWeight +
            c.toneBassDb *
            bassWeight +
            c.toneMidDb *
            midWeight +
            c.toneTrebleDb *
            trebleWeight
    }

    private fun autoCompensationDb(
        c: DspConfig
    ): Float {
        var maxGain = 0f

        for (i in DspConfig.FREQUENCIES.indices) {
            val gain =
                combinedEqGainWithoutAuto(
                    c,
                    i
                )

            if (gain > maxGain) {
                maxGain = gain
            }
        }

        return (
            -maxGain -
                c.autoGainHeadroomDb
            ).coerceIn(
                -24f,
                0f
            )
    }

    private fun combinedEqGainWithoutAuto(
        c: DspConfig,
        i: Int
    ): Float {
        val hz =
            DspConfig.FREQUENCIES[i]

        return (
            if (c.eqEnabled) {
                c.eqGainsDb[i]
            } else {
                0f
            }
            ) +
            toneGain(
                c,
                hz
            )
    }

    private fun balanceDb(
        linear: Float
    ): Float =
        20f *
            log10(
                linear.coerceIn(
                    0.001f,
                    1f
                )
            )

    private fun softCeiling(
        x: Float,
        ceiling: Float
    ): Float {
        if (ceiling <= 0f) {
            return 0f
        }

        return (
            kotlin.math.tanh(
                x / ceiling
            ) * ceiling
            ).coerceIn(
                -ceiling,
                ceiling
            )
    }

    private fun dbToLinear(
        db: Float
    ): Float =
        10.0.pow(
            db.coerceIn(
                -96f,
                24f
            ) / 20.0
        ).toFloat()

    private fun sanitize(
        input: DspConfig
    ): DspConfig {

        val eq =
            FloatArray(EQ_BANDS) { i ->
                input.eqGainsDb
                    .getOrElse(i) { 0f }
                    .coerceIn(
                        MIN_DB,
                        MAX_DB
                    )
            }

        val cuts =
            FloatArray(MDRC_BANDS)

        var previous =
            MIN_FREQ - 1f

        for (i in 0 until MDRC_BANDS) {
            val maxCut =
                (nyquist - 50f)
                    .coerceAtLeast(
                        previous + 2f
                    )

            val requested =
                input.mdrcCutoffsHz
                    .getOrElse(i) {
                        DspConfig()
                            .mdrcCutoffsHz[i]
                    }

            cuts[i] =
                requested.coerceIn(
                    previous + 1f,
                    maxCut
                )

            previous =
                cuts[i]
        }

        val bands =
            Array(MDRC_BANDS) { i ->
                val b =
                    input.mdrcBands
                        .getOrElse(i) {
                            MdrcBand()
                        }

                b.copy(
                    thresholdDb =
                        b.thresholdDb
                            .coerceIn(
                                -60f,
                                0f
                            ),

                    ratio =
                        b.ratio.coerceIn(
                            1f,
                            20f
                        ),

                    attackMs =
                        b.attackMs.coerceIn(
                            0.5f,
                            500f
                        ),

                    releaseMs =
                        b.releaseMs.coerceIn(
                            1f,
                            2000f
                        ),

                    makeupDb =
                        b.makeupDb.coerceIn(
                            -24f,
                            24f
                        )
                )
            }

        return input.copy(
            preGainDb =
                input.preGainDb.coerceIn(
                    -24f,
                    24f
                ),

            bassBoostDb =
                input.bassBoostDb.coerceIn(
                    -24f,
                    24f
                ),

            toneBassDb =
                input.toneBassDb.coerceIn(
                    -24f,
                    24f
                ),

            toneMidDb =
                input.toneMidDb.coerceIn(
                    -24f,
                    24f
                ),

            toneTrebleDb =
                input.toneTrebleDb.coerceIn(
                    -24f,
                    24f
                ),

            eqGainsDb = eq,

            mdrcCutoffsHz = cuts,

            mdrcBands = bands,

            autoGainHeadroomDb =
                input.autoGainHeadroomDb.coerceIn(
                    0f,
                    12f
                ),

            limiterCeilingDb =
                input.limiterCeilingDb.coerceIn(
                    -24f,
                    -0.1f
                ),

            spatialWidth =
                input.spatialWidth.coerceIn(
                    0f,
                    1f
                ),

            masterGainDb =
                input.masterGainDb.coerceIn(
                    -24f,
                    24f
                ),

            balance =
                input.balance.coerceIn(
                    -1f,
                    1f
                )
        )
    }
}
