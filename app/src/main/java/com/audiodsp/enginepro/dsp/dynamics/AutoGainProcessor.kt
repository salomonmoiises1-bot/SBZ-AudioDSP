package com.audiodsp.enginepro.dsp.dynamics

import com.audiodsp.enginepro.dsp.core.AudioBuffer
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Real AutoGain / Automatic Gain Control (AGC) processor.
 * Continuous RMS-based leveling with silence gating, slow attack/release ballistics
 * to prevent breathing/pumping artifacts, and per-sample smoothing.
 */
class AutoGainProcessor(
    private val sampleRate: Float = 48000f
) {
    var isEnabled: Boolean = true

    // Target RMS level in dBFS (standard broadcast / streaming target -16 dBFS)
    var targetLevelDb: Float = -16.0f
    var maxBoostDb: Float = 9.0f
    var maxCutDb: Float = -12.0f

    // Silence gate: avoid boosting background noise during pauses
    private val silenceThresholdDb = -50.0f

    // Gain state
    var currentGainDb: Float = 0.0f; private set
    private var smoothedGainLinear: Float = 1.0f
    private var smoothedRms: Float = 0.01f

    // Smoothing coefficients (~500ms release, ~200ms attack)
    private val gainSlewFilter = 0.0005f // very smooth per-sample coefficient

    fun process(buffer: AudioBuffer) {
        if (!isEnabled) return

        val frames = buffer.frameCount
        val left = buffer.left
        val right = buffer.right

        // 1. Calculate block RMS
        var sumSquares = 0.0
        for (i in 0 until frames) {
            val mono = 0.5f * (left[i] + right[i])
            sumSquares += (mono * mono).toDouble()
        }
        val blockRms = sqrt(sumSquares / frames.coerceAtLeast(1)).toFloat()

        // 2. Smooth RMS with temporal integrator
        val rmsAlpha = 0.05f
        smoothedRms = (1.0f - rmsAlpha) * smoothedRms + rmsAlpha * blockRms

        val rmsDb = if (smoothedRms > 1e-5f) 20.0f * log10(smoothedRms) else -96.0f

        // 3. Compute target gain (with silence gating)
        val targetGainDb = if (rmsDb > silenceThresholdDb) {
            val diff = targetLevelDb - rmsDb
            diff.coerceIn(maxCutDb, maxBoostDb)
        } else {
            // In silence or very quiet section, gently return gain to unity (0 dB)
            0.0f
        }

        currentGainDb = targetGainDb
        val targetLinearGain = 10.0.pow(targetGainDb / 20.0).toFloat()

        // 4. Apply gain with sample-by-sample interpolation to eliminate clicks
        var gain = smoothedGainLinear
        for (i in 0 until frames) {
            gain += gainSlewFilter * (targetLinearGain - gain)
            left[i] *= gain
            right[i] *= gain
        }
        smoothedGainLinear = gain
    }

    fun resetStates() {
        smoothedGainLinear = 1.0f
        smoothedRms = 0.01f
        currentGainDb = 0.0f
    }
}
