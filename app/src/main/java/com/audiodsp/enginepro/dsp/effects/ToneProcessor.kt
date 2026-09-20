package com.audiodsp.enginepro.dsp.effects

import com.audiodsp.enginepro.dsp.core.AudioBuffer
import com.audiodsp.enginepro.dsp.filters.BiquadFilter
import com.audiodsp.enginepro.dsp.filters.FilterType

/**
 * 3-Band Tone Stack (Bass, Mid, Treble) using real RBJ Biquad filters:
 * - Bass: Low-Shelf at 150 Hz
 * - Mid: Peaking Bell at 1000 Hz, Q = 1.0
 * - Treble: High-Shelf at 6000 Hz
 */
class ToneProcessor(
    private val sampleRate: Float = 48000f
) {
    var isEnabled: Boolean = true

    var bassDb: Float = 0.0f
        private set
    var midDb: Float = 0.0f
        private set
    var trebleDb: Float = 0.0f
        private set

    private val bassFilter = BiquadFilter(sampleRate, FilterType.LOW_SHELF, 150f, 0.9f, 0f)
    private val midFilter = BiquadFilter(sampleRate, FilterType.PEAKING, 1000f, 1.0f, 0f)
    private val trebleFilter = BiquadFilter(sampleRate, FilterType.HIGH_SHELF, 6000f, 0.9f, 0f)

    fun setBass(gainDb: Float) {
        bassDb = gainDb.coerceIn(-12.0f, 12.0f)
        bassFilter.setGain(bassDb)
    }

    fun setMid(gainDb: Float) {
        midDb = gainDb.coerceIn(-12.0f, 12.0f)
        midFilter.setGain(midDb)
    }

    fun setTreble(gainDb: Float) {
        trebleDb = gainDb.coerceIn(-12.0f, 12.0f)
        trebleFilter.setGain(trebleDb)
    }

    fun reset() {
        setBass(0f)
        setMid(0f)
        setTreble(0f)
        bassFilter.resetStates()
        midFilter.resetStates()
        trebleFilter.resetStates()
    }

    fun process(buffer: AudioBuffer) {
        if (!isEnabled) return
        if (bassDb == 0f && midDb == 0f && trebleDb == 0f) return

        val frames = buffer.frameCount
        val left = buffer.left
        val right = buffer.right

        for (i in 0 until frames) {
            var l = left[i]
            var r = right[i]

            if (bassDb != 0f) {
                l = bassFilter.processSampleLeft(l)
                r = bassFilter.processSampleRight(r)
            }
            if (midDb != 0f) {
                l = midFilter.processSampleLeft(l)
                r = midFilter.processSampleRight(r)
            }
            if (trebleDb != 0f) {
                l = trebleFilter.processSampleLeft(l)
                r = trebleFilter.processSampleRight(r)
            }

            left[i] = l
            right[i] = r
        }
    }

    fun resetStates() {
        bassFilter.resetStates()
        midFilter.resetStates()
        trebleFilter.resetStates()
    }
}
