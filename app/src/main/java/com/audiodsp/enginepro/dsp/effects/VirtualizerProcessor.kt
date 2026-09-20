package com.audiodsp.enginepro.dsp.effects

import com.audiodsp.enginepro.dsp.core.AudioBuffer
import com.audiodsp.enginepro.dsp.filters.BiquadFilter
import com.audiodsp.enginepro.dsp.filters.FilterType

/**
 * Real DSP Stereo Virtualizer & Spatializer.
 * Employs Mid/Side (M/S) matrix decomposition, Haas effect psychoacoustic delay line (~0.6ms),
 * and high-frequency dispersion filtering on the Side channel to expand the perceptual soundstage.
 */
class VirtualizerProcessor(
    private val sampleRate: Float = 48000f
) {
    var isEnabled: Boolean = true
    var strength: Float = 0.0f
        private set

    // Circular delay buffer for Haas interaural cross-phase effect (~0.65ms = 31 samples @ 48kHz)
    private val maxDelaySamples = (sampleRate * 0.002f).toInt().coerceAtLeast(64)
    private val delayBufferL = FloatArray(maxDelaySamples)
    private val delayBufferR = FloatArray(maxDelaySamples)
    private var writeIndex: Int = 0
    private var delaySamples: Int = (sampleRate * 0.00065f).toInt().coerceIn(1, maxDelaySamples - 1)

    // Side channel HRTF body shaping filter (gentle high-frequency spatializer filter)
    private val sideFilter = BiquadFilter(
        sampleRate = sampleRate,
        type = FilterType.HIGH_SHELF,
        frequency = 3500.0f,
        q = 0.707f,
        gainDb = 2.0f
    )

    fun setStrength(value: Float) {
        this.strength = value.coerceIn(0.0f, 1.0f)
    }

    fun process(buffer: AudioBuffer) {
        if (!isEnabled || strength <= 0.001f) return

        val frames = buffer.frameCount
        val left = buffer.left
        val right = buffer.right
        val width = 1.0f + strength * 1.5f // Expansion factor: 1.0 to 2.5
        val crossBlend = strength * 0.35f

        for (i in 0 until frames) {
            val l = left[i]
            val r = right[i]

            // Mid/Side Decomposition
            val mid = 0.5f * (l + r)
            var side = 0.5f * (l - r)

            // Filter side channel for acoustic spatial presence
            side = sideFilter.processSampleLeft(side)

            // Write to delay buffer
            delayBufferL[writeIndex] = l
            delayBufferR[writeIndex] = r

            val readIndex = (writeIndex - delaySamples + maxDelaySamples) % maxDelaySamples
            val delayedL = delayBufferL[readIndex]
            val delayedR = delayBufferR[readIndex]

            writeIndex = (writeIndex + 1) % maxDelaySamples

            // Spatial re-composition with Haas cross-feed
            val outL = mid + width * side - crossBlend * delayedR
            val outR = mid - width * side - crossBlend * delayedL

            left[i] = outL
            right[i] = outR
        }
    }

    fun resetStates() {
        delayBufferL.fill(0f)
        delayBufferR.fill(0f)
        writeIndex = 0
        sideFilter.resetStates()
    }
}
