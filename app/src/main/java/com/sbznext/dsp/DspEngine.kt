package com.sbznext.dsp

import kotlin.math.*

/**
 * Real-time stereo PCM DSP engine.
 *
 * Configuration changes are coalesced through pendingConfig and applied only at
 * the audio-thread boundary. process() performs no heap allocations.
 */
class DspEngine(private val sampleRate: Int) {
    companion object {
        private const val EQ_Q = 4.318f
        private const val MIN_DB = -96f
        private const val MAX_DB = 24f
        private const val EPS = 1e-8f
    }

    private val nyquist = (sampleRate * 0.5f)
    val frequencies = DspConfig.FREQUENCIES

    private val eq = Array(32) { Biquad(sampleRate.toFloat()) }
    private val bass = Biquad(sampleRate.toFloat())
    private val toneMid = Biquad(sampleRate.toFloat())
    private val treble = Biquad(sampleRate.toFloat())

    // MDRC crossover: one LPF per boundary. Bands are reconstructed as
    // LP(0), LP(1)-LP(0), LP(2)-LP(1), LP(3)-LP(2), input-LP(3).
    private val mdrcLp = Array(4) { Biquad(sampleRate.toFloat()) }
    private val comps = Array(4) { Compressor(sampleRate.toFloat()) }

    @Volatile private var config = DspConfig()
    @Volatile private var pendingConfig: DspConfig? = null

    @Volatile var lastPeak = 0f
        private set
    @Volatile var lastAutoGainDb = 0f
        private set
    @Volatile var clipping = false
        private set

    private var autoEnvelope = 0f
    private var autoGain = 1f

    init {
        apply(config)
    }

    fun update(c: DspConfig) {
        pendingConfig = sanitize(c)
    }

    fun snapshot(): DspConfig = config

    private fun sanitize(input: DspConfig): DspConfig {
        val eqGains = FloatArray(32)
        for (i in eqGains.indices) {
            eqGains[i] = input.eqGainsDb.getOrElse(i) { 0f }.coerceIn(-24f, 24f)
        }

        val cuts = FloatArray(4)
        var previous = 19f
        for (i in cuts.indices) {
            val maxCut = if (i == 3) nyquist - 50f else nyquist - 100f
            cuts[i] = input.mdrcCutoffsHz.getOrElse(i) { DspConfig().mdrcCutoffsHz[i] }
                .coerceIn(previous + 1f, maxCut)
            previous = cuts[i]
        }

        val bands = Array(4) { i ->
            val b = input.mdrcBands.getOrElse(i) { MdrcBand() }
            b.copy(
                thresholdDb = b.thresholdDb.coerceIn(-60f, 0f),
                ratio = b.ratio.coerceIn(1f, 20f),
                attackMs = b.attackMs.coerceIn(0.5f, 500f),
                releaseMs = b.releaseMs.coerceIn(1f, 2000f),
                makeupDb = b.makeupDb.coerceIn(-24f, 24f)
            )
        }

        return input.copy(
            preGainDb = input.preGainDb.coerceIn(-24f, 24f),
            bassBoostDb = input.bassBoostDb.coerceIn(-24f, 24f),
            toneBassDb = input.toneBassDb.coerceIn(-24f, 24f),
            toneMidDb = input.toneMidDb.coerceIn(-24f, 24f),
            toneTrebleDb = input.toneTrebleDb.coerceIn(-24f, 24f),
            eqGainsDb = eqGains,
            mdrcCutoffsHz = cuts,
            mdrcBands = bands,
            autoGainHeadroomDb = input.autoGainHeadroomDb.coerceIn(0f, 12f),
            limiterCeilingDb = input.limiterCeilingDb.coerceIn(-24f, -0.1f),
            spatialWidth = input.spatialWidth.coerceIn(0f, 1f),
            masterGainDb = input.masterGainDb.coerceIn(-24f, 24f),
            balance = input.balance.coerceIn(-1f, 1f)
        )
    }

    private fun apply(c: DspConfig) {
        for (i in eq.indices) {
            eq[i].peaking(frequencies[i], EQ_Q, if (c.eqEnabled) c.eqGainsDb[i] else 0f)
        }

        bass.lowShelf(100f.coerceAtMost(nyquist - 100f), 0.8f, c.bassBoostDb + c.toneBassDb)
        toneMid.peaking(1000f.coerceAtMost(nyquist - 100f), 0.9f, c.toneMidDb)
        treble.highShelf(6000f.coerceAtMost(nyquist - 100f), 0.8f, c.toneTrebleDb)

        for (i in mdrcLp.indices) {
            mdrcLp[i].lowPass(c.mdrcCutoffsHz[i].coerceIn(20f, nyquist - 50f))
        }

        autoEnvelope = 0f
        autoGain = 1f
        lastAutoGainDb = 0f
    }

    fun process(buf: FloatArray) {
        val pending = pendingConfig
        if (pending != null) {
            config = pending
            pendingConfig = null
            apply(pending)
        }

        val c = config
        if (!c.enabled) return

        val n = buf.size and -2
        var peak = 0f
        var i = 0

        val pre = dbToLinear(c.preGainDb)
        val master = dbToLinear(c.masterGainDb)
        val ceiling = dbToLinear(c.limiterCeilingDb)

        while (i < n) {
            var l = buf[i] * pre
            var r = buf[i + 1] * pre

            l = bass.processL(l)
            r = bass.processR(r)
            l = toneMid.processL(l)
            r = toneMid.processR(r)
            l = treble.processL(l)
            r = treble.processR(r)

            if (c.eqEnabled) {
                for (f in eq.indices) {
                    l = eq[f].processL(l)
                    r = eq[f].processR(r)
                }
            }

            if (c.mdrcEnabled) {
                val p0L = mdrcLp[0].processL(l)
                val p0R = mdrcLp[0].processR(r)
                val p1L = mdrcLp[1].processL(l)
                val p1R = mdrcLp[1].processR(r)
                val p2L = mdrcLp[2].processL(l)
                val p2R = mdrcLp[2].processR(r)
                val p3L = mdrcLp[3].processL(l)
                val p3R = mdrcLp[3].processR(r)

                val b0Gain = comps[0].processGain(p0L, p0R, c.mdrcBands[0])
                val b1L = p1L - p0L
                val b1R = p1R - p0R
                val b1Gain = comps[1].processGain(b1L, b1R, c.mdrcBands[1])
                val b2L = p2L - p1L
                val b2R = p2R - p1R
                val b2Gain = comps[2].processGain(b2L, b2R, c.mdrcBands[2])
                val b3L = p3L - p2L
                val b3R = p3R - p2R
                val b3Gain = comps[3].processGain(b3L, b3R, c.mdrcBands[3])
                val b4L = l - p3L
                val b4R = r - p3R

                // The four user MDRC bands map to 0..x1, x1..x2,
                // x2..x3 and x3..high; the final high band is left uncompressed.
                l = p0L * b0Gain + b1L * b1Gain + b2L * b2Gain + b3L * b3Gain + b4L
                r = p0R * b0Gain + b1R * b1Gain + b2R * b2Gain + b3R * b3Gain + b4R
            }

            if (c.autoGainEnabled) {
                val inst = max(abs(l), abs(r))
                val attack = exp((-1f / (10f * 0.001f * sampleRate)).toDouble()).toFloat()
                val release = exp((-1f / (250f * 0.001f * sampleRate)).toDouble()).toFloat()
                autoEnvelope = if (inst > autoEnvelope) {
                    attack * autoEnvelope + (1f - attack) * inst
                } else {
                    release * autoEnvelope + (1f - release) * inst
                }

                val levelDb = 20f * log10(autoEnvelope.coerceAtLeast(EPS))
                val desiredDb = (-c.autoGainHeadroomDb - levelDb)
                    .coerceIn(-24f, 0f)
                val target = dbToLinear(desiredDb)
                val smoothing = if (target < autoGain) 0.985f else 0.999f
                autoGain = smoothing * autoGain + (1f - smoothing) * target
                l *= autoGain
                r *= autoGain
            }

            if (c.spatialEnabled) {
                val mid = (l + r) * 0.5f
                val side = (l - r) * 0.5f * c.spatialWidth
                l = mid + side
                r = mid - side
            }

            val bal = c.balance
            val lg = if (bal > 0f) 1f - bal else 1f
            val rg = if (bal < 0f) 1f + bal else 1f
            l *= lg * master
            r *= rg * master

            if (c.limiterEnabled) {
                l = fastLimiter(l, ceiling)
                r = fastLimiter(r, ceiling)
            }

            if (!l.isFinite()) l = 0f
            if (!r.isFinite()) r = 0f

            // Final safety clamp only; the limiter above handles normal operation.
            l = l.coerceIn(-1f, 1f)
            r = r.coerceIn(-1f, 1f)

            peak = max(peak, max(abs(l), abs(r)))
            buf[i] = l
            buf[i + 1] = r
            i += 2
        }

        lastPeak = peak
        lastAutoGainDb = linearToDb(autoGain)
        clipping = peak >= 0.999f
    }

    private fun fastLimiter(x: Float, ceiling: Float): Float {
        if (ceiling <= 0f || !ceiling.isFinite()) return 0f
        // Smooth saturation with a strict asymptotic ceiling. It avoids the
        // hard discontinuity of a sample-by-sample clamp.
        return (tanh(x / ceiling) * ceiling).coerceIn(-ceiling, ceiling)
    }

    private fun dbToLinear(db: Float): Float =
        10.0.pow((db.coerceIn(MIN_DB, MAX_DB)) / 20.0).toFloat()

    private fun linearToDb(value: Float): Float =
        20f * log10(value.coerceAtLeast(EPS))
}
