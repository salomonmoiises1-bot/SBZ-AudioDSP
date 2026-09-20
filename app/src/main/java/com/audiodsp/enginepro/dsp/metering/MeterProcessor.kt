package com.audiodsp.enginepro.dsp.metering

import com.audiodsp.enginepro.dsp.core.AudioBuffer
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Real-time audio metering processor.
 * Computes exact Peak and RMS levels (in dBFS and linear normalized) for Left and Right channels,
 * and tracks true digital clipping (samples reaching >= 0.999f / 0 dBFS).
 */
class MeterProcessor {

    data class MeterValues(
        val peakL: Float,
        val peakR: Float,
        val rmsL: Float,
        val rmsR: Float,
        val peakDbL: Float,
        val peakDbR: Float,
        val rmsDbL: Float,
        val rmsDbR: Float,
        val isClippingL: Boolean,
        val isClippingR: Boolean
    )

    @Volatile
    var currentValues: MeterValues = MeterValues(
        peakL = 0f, peakR = 0f,
        rmsL = 0f, rmsR = 0f,
        peakDbL = -96f, peakDbR = -96f,
        rmsDbL = -96f, rmsDbR = -96f,
        isClippingL = false, isClippingR = false
    )
        private set

    // Peak decay ballistics
    private var decayPeakL = 0.0f
    private var decayPeakR = 0.0f
    private val decayFactor = 0.85f

    fun analyze(buffer: AudioBuffer) {
        val frames = buffer.frameCount
        if (frames <= 0) return

        val left = buffer.left
        val right = buffer.right

        var maxL = 0.0f
        var maxR = 0.0f
        var sumSquaresL = 0.0
        var sumSquaresR = 0.0
        var clipL = false
        var clipR = false

        for (i in 0 until frames) {
            val sampleL = left[i]
            val sampleR = right[i]

            val absL = kotlin.math.abs(sampleL)
            val absR = kotlin.math.abs(sampleR)

            if (absL > maxL) maxL = absL
            if (absR > maxR) maxR = absR

            sumSquaresL += (sampleL * sampleL).toDouble()
            sumSquaresR += (sampleR * sampleR).toDouble()

            if (absL >= 0.999f) clipL = true
            if (absR >= 0.999f) clipR = true
        }

        decayPeakL = max(maxL, decayPeakL * decayFactor)
        decayPeakR = max(maxR, decayPeakR * decayFactor)

        val rmsL = sqrt(sumSquaresL / frames).toFloat()
        val rmsR = sqrt(sumSquaresR / frames).toFloat()

        val peakDbL = if (decayPeakL > 1e-5f) 20.0f * log10(decayPeakL) else -96.0f
        val peakDbR = if (decayPeakR > 1e-5f) 20.0f * log10(decayPeakR) else -96.0f
        val rmsDbL = if (rmsL > 1e-5f) 20.0f * log10(rmsL) else -96.0f
        val rmsDbR = if (rmsR > 1e-5f) 20.0f * log10(rmsR) else -96.0f

        currentValues = MeterValues(
            peakL = decayPeakL.coerceIn(0f, 1f),
            peakR = decayPeakR.coerceIn(0f, 1f),
            rmsL = rmsL.coerceIn(0f, 1f),
            rmsR = rmsR.coerceIn(0f, 1f),
            peakDbL = peakDbL.coerceIn(-96f, 6f),
            peakDbR = peakDbR.coerceIn(-96f, 6f),
            rmsDbL = rmsDbL.coerceIn(-96f, 6f),
            rmsDbR = rmsDbR.coerceIn(-96f, 6f),
            isClippingL = clipL,
            isClippingR = clipR
        )
    }

    fun reset() {
        decayPeakL = 0f
        decayPeakR = 0f
        currentValues = MeterValues(
            0f, 0f, 0f, 0f,
            -96f, -96f, -96f, -96f,
            isClippingL = false, isClippingR = false
        )
    }
}
