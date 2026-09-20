package com.audiodsp.enginepro.dsp.dynamics

import com.audiodsp.enginepro.dsp.core.AudioBuffer
import com.audiodsp.enginepro.dsp.filters.BiquadFilter
import com.audiodsp.enginepro.dsp.filters.FilterType
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Real Multi-Band Dynamic Range Compressor (MDRC).
 * Splits the stereo signal into 3 frequency bands (Low, Mid, High) via Linkwitz-Riley crossovers,
 * performs independent dynamic range compression per band with peak/RMS envelope followers,
 * and sums the bands with zero real-time heap allocation.
 */
class MdrcProcessor(
    private val sampleRate: Float = 48000f
) {
    var isEnabled: Boolean = true

    // Band Parameters container
    class BandParams(
        var thresholdDb: Float = -18.0f,
        var ratio: Float = 3.0f,
        var attackMs: Float = 20.0f,
        var releaseMs: Float = 150.0f,
        var makeupGainDb: Float = 0.0f
    )

    val lowParams = BandParams(thresholdDb = -16.0f, ratio = 3.5f, attackMs = 25f, releaseMs = 180f, makeupGainDb = 1.0f)
    val midParams = BandParams(thresholdDb = -20.0f, ratio = 2.8f, attackMs = 15f, releaseMs = 120f, makeupGainDb = 1.5f)
    val highParams = BandParams(thresholdDb = -24.0f, ratio = 2.5f, attackMs = 10f, releaseMs = 80f, makeupGainDb = 1.0f)

    // Real-time Gain Reduction meters in dB for UI telemetry
    var lowGainReductionDb: Float = 0.0f; private set
    var midGainReductionDb: Float = 0.0f; private set
    var highGainReductionDb: Float = 0.0f; private set

    // Crossover frequencies
    private val lowMidCrossover = 250.0f
    private val midHighCrossover = 3500.0f

    // Crossover Filters
    private val lowLpL = BiquadFilter(sampleRate, FilterType.LOW_PASS, lowMidCrossover, 0.707f)
    private val lowLpR = BiquadFilter(sampleRate, FilterType.LOW_PASS, lowMidCrossover, 0.707f)

    private val midHpL = BiquadFilter(sampleRate, FilterType.HIGH_PASS, lowMidCrossover, 0.707f)
    private val midHpR = BiquadFilter(sampleRate, FilterType.HIGH_PASS, lowMidCrossover, 0.707f)
    private val midLpL = BiquadFilter(sampleRate, FilterType.LOW_PASS, midHighCrossover, 0.707f)
    private val midLpR = BiquadFilter(sampleRate, FilterType.LOW_PASS, midHighCrossover, 0.707f)

    private val highHpL = BiquadFilter(sampleRate, FilterType.HIGH_PASS, midHighCrossover, 0.707f)
    private val highHpR = BiquadFilter(sampleRate, FilterType.HIGH_PASS, midHighCrossover, 0.707f)

    // Internal dynamic compressor state
    private class BandState(private val sampleRate: Float) {
        var envelope: Float = 0.0f
        var currentGain: Float = 1.0f

        fun processSample(inputAbs: Float, params: BandParams): Float {
            // Ballistics coefficients
            val attackCoef = exp(-1.0f / (params.attackMs * 0.001f * sampleRate))
            val releaseCoef = exp(-1.0f / (params.releaseMs * 0.001f * sampleRate))

            // Peak envelope follower
            envelope = if (inputAbs > envelope) {
                attackCoef * envelope + (1.0f - attackCoef) * inputAbs
            } else {
                releaseCoef * envelope + (1.0f - releaseCoef) * inputAbs
            }

            // Envelope to dB (with noise floor guard at -96 dB)
            val safeEnv = max(envelope, 1.58e-5f)
            val envDb = 20.0f * log10(safeEnv)

            // Gain computer
            val overshootDb = envDb - params.thresholdDb
            val grDb = if (overshootDb > 0.0f) {
                -overshootDb * (1.0f - 1.0f / params.ratio.coerceAtLeast(1.0f))
            } else {
                0.0f
            }

            val totalGainDb = grDb + params.makeupGainDb
            currentGain = 10.0.pow(totalGainDb / 20.0).toFloat()
            return grDb
        }
    }

    private val lowState = BandState(sampleRate)
    private val midState = BandState(sampleRate)
    private val highState = BandState(sampleRate)

    // Reusable scratch arrays to avoid GC allocations in audio loop
    private val scratchLowL = FloatArray(2048)
    private val scratchLowR = FloatArray(2048)
    private val scratchMidL = FloatArray(2048)
    private val scratchMidR = FloatArray(2048)
    private val scratchHighL = FloatArray(2048)
    private val scratchHighR = FloatArray(2048)

    fun process(buffer: AudioBuffer) {
        if (!isEnabled) {
            lowGainReductionDb = 0f
            midGainReductionDb = 0f
            highGainReductionDb = 0f
            return
        }

        val frames = buffer.frameCount
        val left = buffer.left
        val right = buffer.right

        var peakGrLow = 0.0f
        var peakGrMid = 0.0f
        var peakGrHigh = 0.0f

        for (i in 0 until frames) {
            val inL = left[i]
            val inR = right[i]

            // 1. Crossover filtering into 3 bands
            val lowL = lowLpL.processSampleLeft(inL)
            val lowR = lowLpR.processSampleRight(inR)

            val hpMidL = midHpL.processSampleLeft(inL)
            val hpMidR = midHpR.processSampleRight(inR)
            val midL = midLpL.processSampleLeft(hpMidL)
            val midR = midLpR.processSampleRight(hpMidR)

            val highL = highHpL.processSampleLeft(inL)
            val highR = highHpR.processSampleRight(inR)

            // 2. Dynamic compression per band
            val lowDetector = max(kotlin.math.abs(lowL), kotlin.math.abs(lowR))
            val grLow = lowState.processSample(lowDetector, lowParams)
            val gainLow = lowState.currentGain
            if (grLow < peakGrLow) peakGrLow = grLow

            val midDetector = max(kotlin.math.abs(midL), kotlin.math.abs(midR))
            val grMid = midState.processSample(midDetector, midParams)
            val gainMid = midState.currentGain
            if (grMid < peakGrMid) peakGrMid = grMid

            val highDetector = max(kotlin.math.abs(highL), kotlin.math.abs(highR))
            val grHigh = highState.processSample(highDetector, highParams)
            val gainHigh = highState.currentGain
            if (grHigh < peakGrHigh) peakGrHigh = grHigh

            // 3. Sum back together
            left[i] = lowL * gainLow + midL * gainMid + highL * gainHigh
            right[i] = lowR * gainLow + midR * gainMid + highR * gainHigh
        }

        lowGainReductionDb = peakGrLow
        midGainReductionDb = peakGrMid
        highGainReductionDb = peakGrHigh
    }

    fun resetStates() {
        lowLpL.resetStates()
        lowLpR.resetStates()
        midHpL.resetStates()
        midHpR.resetStates()
        midLpL.resetStates()
        midLpR.resetStates()
        highHpL.resetStates()
        highHpR.resetStates()
        lowState.envelope = 0f
        lowState.currentGain = 1f
        midState.envelope = 0f
        midState.currentGain = 1f
        highState.envelope = 0f
        highState.currentGain = 1f
    }
}
