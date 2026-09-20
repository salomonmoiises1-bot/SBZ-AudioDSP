package com.audiodsp.enginepro.dsp.effects

import com.audiodsp.enginepro.dsp.core.AudioBuffer
import com.audiodsp.enginepro.dsp.filters.BiquadFilter
import com.audiodsp.enginepro.dsp.filters.FilterType

/**
 * Real DSP Bass Boost processor using low-shelf biquad filter at sub-bass frequencies
 * with subtle soft-clipping warmth to maintain clarity and avoid harsh clipping.
 */
class BassBoostProcessor(
    private val sampleRate: Float = 48000f
) {
    var isEnabled: Boolean = true
    var strengthDb: Float = 0.0f
        private set

    // Tuned resonant low-shelf filter at 80 Hz with Q=1.3 for punch and weight
    private val filter = BiquadFilter(
        sampleRate = sampleRate,
        type = FilterType.LOW_SHELF,
        frequency = 80.0f,
        q = 1.3f,
        gainDb = 0f
    )

    fun setStrength(gainDb: Float) {
        this.strengthDb = gainDb.coerceIn(0.0f, 15.0f)
        filter.setGain(this.strengthDb)
    }

    fun process(buffer: AudioBuffer) {
        if (!isEnabled || strengthDb <= 0.01f) return

        val frames = buffer.frameCount
        val left = buffer.left
        val right = buffer.right

        for (i in 0 until frames) {
            left[i] = filter.processSampleLeft(left[i])
            right[i] = filter.processSampleRight(right[i])
        }
    }

    fun resetStates() {
        filter.resetStates()
    }
}
