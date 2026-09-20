package com.audiodsp.enginepro.dsp.equalizer

import com.audiodsp.enginepro.dsp.core.AudioBuffer
import com.audiodsp.enginepro.dsp.filters.BiquadFilter
import com.audiodsp.enginepro.dsp.filters.FilterType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real 32-Band Graphic Equalizer with cascade Biquad peaking filters (RBJ Cookbook).
 * Preserves filter states across audio blocks for stereo Left and Right channels.
 */
class Equalizer32Band(
    private val sampleRate: Float = 48000f
) {
    companion object {
        const val BAND_COUNT = 32
        const val MIN_GAIN_DB = -15.0f
        const val MAX_GAIN_DB = 15.0f
        const val STEP_DB = 0.5f

        val FREQUENCIES = floatArrayOf(
            16f, 20f, 25f, 31.5f, 40f, 50f, 63f, 80f,
            100f, 125f, 160f, 200f, 250f, 315f, 400f, 500f,
            630f, 800f, 1000f, 1250f, 1600f, 2000f, 2500f, 3150f,
            4000f, 5000f, 6300f, 8000f, 10000f, 12500f, 16000f, 20000f
        )

        // Standard 1/3 octave Q factor ~ 4.318, tuned slightly to 2.87 for smooth inter-band summation
        const val DEFAULT_Q = 3.0f
    }

    var isEnabled: Boolean = true
    var preGainDb: Float = 0.0f
        private set
    private var preGainLinear: Float = 1.0f

    val gainsDb = FloatArray(BAND_COUNT) { 0.0f }
    private val filters = Array(BAND_COUNT) { index ->
        BiquadFilter(
            sampleRate = sampleRate,
            type = FilterType.PEAKING,
            frequency = FREQUENCIES[index],
            q = DEFAULT_Q,
            gainDb = 0f
        )
    }

    fun setPreGain(gainDb: Float) {
        this.preGainDb = gainDb.coerceIn(-24f, 24f)
        this.preGainLinear = 10.0.pow(this.preGainDb / 20.0).toFloat()
    }

    fun setBandGain(bandIndex: Int, gainDb: Float) {
        if (bandIndex in 0 until BAND_COUNT) {
            val clamped = (Math.round(gainDb.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB) / STEP_DB) * STEP_DB)
            gainsDb[bandIndex] = clamped
            filters[bandIndex].setGain(clamped)
        }
    }

    fun getBandGain(bandIndex: Int): Float {
        return if (bandIndex in 0 until BAND_COUNT) gainsDb[bandIndex] else 0f
    }

    fun resetBand(bandIndex: Int) {
        setBandGain(bandIndex, 0f)
    }

    fun resetFlat() {
        for (i in 0 until BAND_COUNT) {
            gainsDb[i] = 0f
            filters[i].setGain(0f)
            filters[i].resetStates()
        }
        setPreGain(0f)
    }

    /**
     * In-place stereo processing over the audio buffer.
     * Zero memory allocation in real-time path.
     */
    fun process(buffer: AudioBuffer) {
        if (!isEnabled) return

        val frames = buffer.frameCount
        val left = buffer.left
        val right = buffer.right

        // 1. Apply real Pre-Gain
        if (preGainLinear != 1.0f) {
            val pg = preGainLinear
            for (i in 0 until frames) {
                left[i] *= pg
                right[i] *= pg
            }
        }

        // 2. Cascade 32 biquad filters
        for (b in 0 until BAND_COUNT) {
            val filter = filters[b]
            if (gainsDb[b] == 0f) continue // Fast path optimization for flat bands

            for (i in 0 until frames) {
                left[i] = filter.processSampleLeft(left[i])
                right[i] = filter.processSampleRight(right[i])
            }
        }
    }

    /**
     * Calculates the real cumulative frequency magnitude response in dB across a set of test frequencies.
     * Used by Compose UI to render the authentic mathematical curve.
     */
    fun calculateMagnitudeResponse(testFreqs: FloatArray): FloatArray {
        val result = FloatArray(testFreqs.size)
        for (i in testFreqs.indices) {
            val f = testFreqs[i]
            val w = 2.0 * PI * f / sampleRate
            val cosW = cos(w)
            val sinW = sin(w)
            val cos2W = cos(2.0 * w)
            val sin2W = sin(2.0 * w)

            var totalMagnitudeSq = preGainLinear.toDouble().pow(2.0)

            for (b in 0 until BAND_COUNT) {
                if (gainsDb[b] == 0f) continue
                val filter = filters[b]
                val b0 = filter.b0.toDouble()
                val b1 = filter.b1.toDouble()
                val b2 = filter.b2.toDouble()
                val a1 = filter.a1.toDouble()
                val a2 = filter.a2.toDouble()

                // H(e^jw) = (b0 + b1*e^-jw + b2*e^-j2w) / (1 + a1*e^-jw + a2*e^-j2w)
                val numReal = b0 + b1 * cosW + b2 * cos2W
                val numImag = -b1 * sinW - b2 * sin2W
                val denReal = 1.0 + a1 * cosW + a2 * cos2W
                val denImag = -a1 * sinW - a2 * sin2W

                val numMagSq = numReal * numReal + numImag * numImag
                val denMagSq = denReal * denReal + denImag * denImag
                if (denMagSq > 1e-12) {
                    totalMagnitudeSq *= (numMagSq / denMagSq)
                }
            }

            val totalMag = sqrt(totalMagnitudeSq)
            result[i] = (20.0 * log10(totalMag.coerceAtLeast(1e-6))).toFloat()
        }
        return result
    }

    fun resetStates() {
        for (filter in filters) {
            filter.resetStates()
        }
    }
}
