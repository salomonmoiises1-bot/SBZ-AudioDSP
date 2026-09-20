package com.audiodsp.enginepro.dsp.filters

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time stereo Biquad Filter implemented in Transposed Direct Form II.
 * Formulated with Robert Bristow-Johnson (RBJ) Audio EQ Cookbook equations.
 * Maintains independent state registers for Left and Right channels across buffers.
 */
class BiquadFilter(
    private var sampleRate: Float = 48000f,
    private var type: FilterType = FilterType.PEAKING,
    private var frequency: Float = 1000f,
    private var q: Float = 1.414f,
    private var gainDb: Float = 0f
) {
    // Normalized filter coefficients
    var b0: Float = 1f; private set
    var b1: Float = 0f; private set
    var b2: Float = 0f; private set
    var a1: Float = 0f; private set
    var a2: Float = 0f; private set

    // Transposed Direct Form II delay registers for Left channel
    private var s1L: Float = 0f
    private var s2L: Float = 0f

    // Transposed Direct Form II delay registers for Right channel
    private var s1R: Float = 0f
    private var s2R: Float = 0f

    init {
        recalculateCoefficients()
    }

    /**
     * Updates the filter parameters and recomputes biquad coefficients.
     */
    fun update(
        sampleRate: Float = this.sampleRate,
        type: FilterType = this.type,
        frequency: Float = this.frequency,
        q: Float = this.q,
        gainDb: Float = this.gainDb
    ) {
        this.sampleRate = sampleRate
        this.type = type
        this.frequency = frequency
        this.q = q
        this.gainDb = gainDb
        recalculateCoefficients()
    }

    fun setGain(newGainDb: Float) {
        if (this.gainDb != newGainDb) {
            this.gainDb = newGainDb
            recalculateCoefficients()
        }
    }

    fun setFrequency(newFrequency: Float) {
        if (this.frequency != newFrequency) {
            this.frequency = newFrequency
            recalculateCoefficients()
        }
    }

    fun setQ(newQ: Float) {
        if (this.q != newQ) {
            this.q = newQ
            recalculateCoefficients()
        }
    }

    fun getGain(): Float = gainDb
    fun getFrequency(): Float = frequency
    fun getQ(): Float = q
    fun getType(): FilterType = type

    /**
     * Computes the normalized biquad coefficients according to RBJ Cookbook.
     */
    fun recalculateCoefficients() {
        // Clamp frequency to Nyquist limit with safety margin
        val nyquist = sampleRate * 0.495f
        val f0 = frequency.coerceIn(10f, nyquist)
        val safeQ = q.coerceAtLeast(0.01f)

        val w0 = (2.0 * PI * f0 / sampleRate).toFloat()
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        val alpha = sinW0 / (2.0f * safeQ)
        val aLinear = 10.0.pow(gainDb / 40.0).toFloat() // A = 10^(dB/40) for RBJ EQ

        var rawB0 = 1f
        var rawB1 = 0f
        var rawB2 = 0f
        var rawA0 = 1f
        var rawA1 = 0f
        var rawA2 = 0f

        when (type) {
            FilterType.PEAKING -> {
                rawB0 = 1.0f + alpha * aLinear
                rawB1 = -2.0f * cosW0
                rawB2 = 1.0f - alpha * aLinear
                rawA0 = 1.0f + alpha / aLinear
                rawA1 = -2.0f * cosW0
                rawA2 = 1.0f - alpha / aLinear
            }
            FilterType.LOW_SHELF -> {
                val sqrtA = sqrt(aLinear)
                rawB0 = aLinear * ((aLinear + 1.0f) - (aLinear - 1.0f) * cosW0 + 2.0f * sqrtA * alpha)
                rawB1 = 2.0f * aLinear * ((aLinear - 1.0f) - (aLinear + 1.0f) * cosW0)
                rawB2 = aLinear * ((aLinear + 1.0f) - (aLinear - 1.0f) * cosW0 - 2.0f * sqrtA * alpha)
                rawA0 = (aLinear + 1.0f) + (aLinear - 1.0f) * cosW0 + 2.0f * sqrtA * alpha
                rawA1 = -2.0f * ((aLinear - 1.0f) + (aLinear + 1.0f) * cosW0)
                rawA2 = (aLinear + 1.0f) + (aLinear - 1.0f) * cosW0 - 2.0f * sqrtA * alpha
            }
            FilterType.HIGH_SHELF -> {
                val sqrtA = sqrt(aLinear)
                rawB0 = aLinear * ((aLinear + 1.0f) + (aLinear - 1.0f) * cosW0 + 2.0f * sqrtA * alpha)
                rawB1 = -2.0f * aLinear * ((aLinear - 1.0f) + (aLinear + 1.0f) * cosW0)
                rawB2 = aLinear * ((aLinear + 1.0f) + (aLinear - 1.0f) * cosW0 - 2.0f * sqrtA * alpha)
                rawA0 = (aLinear + 1.0f) - (aLinear - 1.0f) * cosW0 + 2.0f * sqrtA * alpha
                rawA1 = 2.0f * ((aLinear - 1.0f) - (aLinear + 1.0f) * cosW0)
                rawA2 = (aLinear + 1.0f) - (aLinear - 1.0f) * cosW0 - 2.0f * sqrtA * alpha
            }
            FilterType.LOW_PASS -> {
                rawB0 = (1.0f - cosW0) / 2.0f
                rawB1 = 1.0f - cosW0
                rawB2 = (1.0f - cosW0) / 2.0f
                rawA0 = 1.0f + alpha
                rawA1 = -2.0f * cosW0
                rawA2 = 1.0f - alpha
            }
            FilterType.HIGH_PASS -> {
                rawB0 = (1.0f + cosW0) / 2.0f
                rawB1 = -(1.0f + cosW0)
                rawB2 = (1.0f + cosW0) / 2.0f
                rawA0 = 1.0f + alpha
                rawA1 = -2.0f * cosW0
                rawA2 = 1.0f - alpha
            }
            FilterType.BAND_PASS -> {
                rawB0 = alpha
                rawB1 = 0f
                rawB2 = -alpha
                rawA0 = 1.0f + alpha
                rawA1 = -2.0f * cosW0
                rawA2 = 1.0f - alpha
            }
            FilterType.ALL_PASS -> {
                rawB0 = 1.0f - alpha
                rawB1 = -2.0f * cosW0
                rawB2 = 1.0f + alpha
                rawA0 = 1.0f + alpha
                rawA1 = -2.0f * cosW0
                rawA2 = 1.0f - alpha
            }
            FilterType.NOTCH -> {
                rawB0 = 1.0f
                rawB1 = -2.0f * cosW0
                rawB2 = 1.0f
                rawA0 = 1.0f + alpha
                rawA1 = -2.0f * cosW0
                rawA2 = 1.0f - alpha
            }
        }

        // Normalize coefficients by a0
        val invA0 = 1.0f / rawA0
        b0 = rawB0 * invA0
        b1 = rawB1 * invA0
        b2 = rawB2 * invA0
        a1 = rawA1 * invA0
        a2 = rawA2 * invA0
    }

    /**
     * Processes a single sample for the Left channel.
     * Zero allocations, using Transposed Direct Form II.
     */
    fun processSampleLeft(x: Float): Float {
        val y = b0 * x + s1L
        s1L = b1 * x - a1 * y + s2L
        s2L = b2 * x - a2 * y
        // Denormal protection
        if (s1L.isNaN() || s1L.isInfinite() || (s1L > -1e-15f && s1L < 1e-15f)) s1L = 0f
        if (s2L.isNaN() || s2L.isInfinite() || (s2L > -1e-15f && s2L < 1e-15f)) s2L = 0f
        return y
    }

    /**
     * Processes a single sample for the Right channel.
     * Zero allocations, using Transposed Direct Form II.
     */
    fun processSampleRight(x: Float): Float {
        val y = b0 * x + s1R
        s1R = b1 * x - a1 * y + s2R
        s2R = b2 * x - a2 * y
        // Denormal protection
        if (s1R.isNaN() || s1R.isInfinite() || (s1R > -1e-15f && s1R < 1e-15f)) s1R = 0f
        if (s2R.isNaN() || s2R.isInfinite() || (s2R > -1e-15f && s2R < 1e-15f)) s2R = 0f
        return y
    }

    /**
     * Resets the internal delay states to zero.
     */
    fun resetStates() {
        s1L = 0f
        s2L = 0f
        s1R = 0f
        s2R = 0f
    }
}
