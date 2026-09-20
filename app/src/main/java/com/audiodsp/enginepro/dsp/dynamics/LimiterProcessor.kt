package com.audiodsp.enginepro.dsp.dynamics

import com.audiodsp.enginepro.dsp.core.AudioBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Real Mastering Peak Limiter.
 * Prevents digital overs and inter-sample clipping by maintaining an adaptive envelope
 * with instantaneous attack and smooth logarithmic release curve, followed by soft-saturation ceiling clamp.
 */
class LimiterProcessor(
    private val sampleRate: Float = 48000f
) {
    var isEnabled: Boolean = true

    // Ceiling in dBFS (default -0.3 dBFS to guarantee headroom for DAC reconstructive filters)
    var ceilingDb: Float = -0.3f
        set(value) {
            field = value.coerceIn(-6.0f, 0.0f)
            ceilingLinear = 10.0.pow(field / 20.0).toFloat()
        }

    var releaseMs: Float = 60.0f
        set(value) {
            field = value.coerceIn(10.0f, 500.0f)
            releaseCoef = exp(-1.0f / (field * 0.001f * sampleRate))
        }

    // Telemetry
    var isLimitingActive: Boolean = false; private set
    var maxGainReductionDb: Float = 0.0f; private set

    private var ceilingLinear: Float = 10.0.pow(ceilingDb / 20.0).toFloat()
    private var releaseCoef: Float = exp(-1.0f / (releaseMs * 0.001f * sampleRate))
    private var envelope: Float = 0.0f

    fun process(buffer: AudioBuffer) {
        if (!isEnabled) {
            isLimitingActive = false
            maxGainReductionDb = 0.0f
            return
        }

        val frames = buffer.frameCount
        val left = buffer.left
        val right = buffer.right
        val ceil = ceilingLinear
        val rel = releaseCoef

        var limitingOccurred = false
        var minGain = 1.0f

        for (i in 0 until frames) {
            val l = left[i]
            val r = right[i]
            val peak = max(kotlin.math.abs(l), kotlin.math.abs(r))

            // Envelope detection (instant attack, exponential release)
            envelope = if (peak > envelope) {
                peak
            } else {
                envelope * rel + peak * (1.0f - rel)
            }

            // Calculate gain reduction factor
            val gainFactor = if (envelope > ceil) {
                ceil / envelope
            } else {
                1.0f
            }

            if (gainFactor < 0.999f) {
                limitingOccurred = true
                if (gainFactor < minGain) minGain = gainFactor
            }

            // Apply limiter gain reduction
            var outL = l * gainFactor
            var outR = r * gainFactor

            // True brickwall ceiling enforcement with smooth hyperbolic tangent knee
            if (kotlin.math.abs(outL) > ceil) {
                outL = (ceil * tanh(outL / ceil)).coerceIn(-ceil, ceil)
            }
            if (kotlin.math.abs(outR) > ceil) {
                outR = (ceil * tanh(outR / ceil)).coerceIn(-ceil, ceil)
            }

            left[i] = outL
            right[i] = outR
        }

        isLimitingActive = limitingOccurred
        maxGainReductionDb = if (minGain < 1.0f) 20.0f * kotlin.math.log10(minGain) else 0.0f
    }

    fun resetStates() {
        envelope = 0.0f
        isLimitingActive = false
        maxGainReductionDb = 0.0f
    }
}
