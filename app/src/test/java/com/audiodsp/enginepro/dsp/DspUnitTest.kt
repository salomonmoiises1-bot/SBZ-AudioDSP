package com.audiodsp.enginepro.dsp

import com.audiodsp.enginepro.data.presets.DspPreset
import com.audiodsp.enginepro.dsp.core.AudioBuffer
import com.audiodsp.enginepro.dsp.dynamics.LimiterProcessor
import com.audiodsp.enginepro.dsp.dynamics.MdrcProcessor
import com.audiodsp.enginepro.dsp.equalizer.Equalizer32Band
import com.audiodsp.enginepro.dsp.filters.BiquadFilter
import com.audiodsp.enginepro.dsp.filters.FilterType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

class DspUnitTest {

    private val sampleRate = 48000f

    // 1. BIQUAD FILTER TESTS
    @Test
    fun biquadCoefficients_peakingGainZero_yieldsPassThrough() {
        val filter = BiquadFilter(
            sampleRate = sampleRate,
            type = FilterType.PEAKING,
            frequency = 1000f,
            q = 1.414f,
            gainDb = 0f
        )
        // With 0dB gain, b0 should equal 1.0 (within float precision), a1 equals b1, a2 equals b2
        assertEquals(1.0f, filter.b0, 1e-4f)
        assertEquals(filter.a1, filter.b1, 1e-4f)
        assertEquals(filter.a2, filter.b2, 1e-4f)
    }

    @Test
    fun biquadStability_stepResponse_doesNotDiverge() {
        val filter = BiquadFilter(
            sampleRate = sampleRate,
            type = FilterType.PEAKING,
            frequency = 1000f,
            q = 3.0f,
            gainDb = 12f
        )
        // Pass a unit impulse through 2000 samples
        var output = filter.processSampleLeft(1.0f)
        assertTrue("Impulse response initial output must be finite", !output.isNaN() && !output.isInfinite())

        for (i in 0 until 2000) {
            output = filter.processSampleLeft(0.0f)
            assertTrue("Filter output diverged at sample $i", !output.isNaN() && !output.isInfinite())
        }
        // At 2000 samples (~41ms), ringing should have decayed towards zero
        assertTrue("Filter output failed to decay to zero", abs(output) < 0.01f)
    }

    @Test
    fun biquadConstantSignal_peakingGainZero_preservesDcSignal() {
        val filter = BiquadFilter(
            sampleRate = sampleRate,
            type = FilterType.PEAKING,
            frequency = 1000f,
            q = 2.0f,
            gainDb = 0f
        )
        val dcValue = 0.5f
        for (i in 0 until 500) {
            val y = filter.processSampleLeft(dcValue)
            if (i > 100) {
                assertEquals(dcValue, y, 1e-3f)
            }
        }
    }

    // 2. EQUALIZER TESTS
    @Test
    fun equalizer_flat_doesNotAlterSignal() {
        val eq = Equalizer32Band(sampleRate)
        eq.resetFlat()

        val buffer = AudioBuffer(512)
        buffer.frameCount = 512
        for (i in 0 until 512) {
            val sample = sin(2.0 * PI * 1000.0 * i / sampleRate).toFloat() * 0.5f
            buffer.left[i] = sample
            buffer.right[i] = sample
        }

        eq.process(buffer)

        for (i in 0 until 512) {
            val expected = sin(2.0 * PI * 1000.0 * i / sampleRate).toFloat() * 0.5f
            assertEquals(expected, buffer.left[i], 1e-4f)
            assertEquals(expected, buffer.right[i], 1e-4f)
        }
    }

    @Test
    fun equalizer_positiveGain_amplifiesTargetFrequency() {
        val eq = Equalizer32Band(sampleRate)
        eq.resetFlat()

        // Find band closest to 1000 Hz (index 18)
        val band1k = 18
        eq.setBandGain(band1k, 12.0f)

        val buffer = AudioBuffer(1024)
        buffer.frameCount = 1024
        var inputRms = 0.0
        for (i in 0 until 1024) {
            val sample = sin(2.0 * PI * 1000.0 * i / sampleRate).toFloat() * 0.2f
            buffer.left[i] = sample
            buffer.right[i] = sample
            inputRms += (sample * sample).toDouble()
        }
        inputRms = sqrt(inputRms / 1024)

        eq.process(buffer)

        var outputRms = 0.0
        // Measure after transient settles
        for (i in 256 until 1024) {
            outputRms += (buffer.left[i] * buffer.left[i]).toDouble()
        }
        outputRms = sqrt(outputRms / (1024 - 256))

        // Amplification should be significantly greater than input RMS
        assertTrue("Output RMS ($outputRms) should be higher than input ($inputRms)", outputRms > inputRms * 1.5)
    }

    @Test
    fun equalizer_negativeGain_attenuatesTargetFrequency() {
        val eq = Equalizer32Band(sampleRate)
        eq.resetFlat()

        val band1k = 18
        eq.setBandGain(band1k, -12.0f)

        val buffer = AudioBuffer(1024)
        buffer.frameCount = 1024
        var inputRms = 0.0
        for (i in 0 until 1024) {
            val sample = sin(2.0 * PI * 1000.0 * i / sampleRate).toFloat() * 0.5f
            buffer.left[i] = sample
            buffer.right[i] = sample
            inputRms += (sample * sample).toDouble()
        }
        inputRms = sqrt(inputRms / 1024)

        eq.process(buffer)

        var outputRms = 0.0
        for (i in 256 until 1024) {
            outputRms += (buffer.left[i] * buffer.left[i]).toDouble()
        }
        outputRms = sqrt(outputRms / (1024 - 256))

        // Attenuation should reduce output RMS significantly
        assertTrue("Output RMS ($outputRms) should be lower than input ($inputRms)", outputRms < inputRms * 0.75)
    }

    // 3. COMPRESSOR / MDRC TESTS
    @Test
    fun mdrc_signalAboveThreshold_isCompressed() {
        val mdrc = MdrcProcessor(sampleRate)
        mdrc.lowParams.thresholdDb = -12.0f
        mdrc.lowParams.ratio = 4.0f
        mdrc.lowParams.attackMs = 1.0f
        mdrc.lowParams.releaseMs = 100.0f
        mdrc.lowParams.makeupGainDb = 0.0f

        val buffer = AudioBuffer(1024)
        buffer.frameCount = 1024
        // High level signal at 100 Hz (in Low band) well above -12 dBFS (~0.25 linear)
        for (i in 0 until 1024) {
            val s = sin(2.0 * PI * 100.0 * i / sampleRate).toFloat() * 0.9f
            buffer.left[i] = s
            buffer.right[i] = s
        }

        mdrc.process(buffer)

        // Gain reduction should be negative (compression active)
        assertTrue("MDRC should trigger gain reduction on high signals", mdrc.lowGainReductionDb < -1.0f)
    }

    // 4. LIMITER TESTS
    @Test
    fun limiter_ceilingClamped_neverExceedsThreshold() {
        val limiter = LimiterProcessor(sampleRate)
        limiter.ceilingDb = -0.5f // -0.5 dBFS = ~0.944f linear

        val buffer = AudioBuffer(1024)
        buffer.frameCount = 1024
        // Input signal that attempts to exceed ceiling (amplitude 2.5f)
        for (i in 0 until 1024) {
            val s = sin(2.0 * PI * 440.0 * i / sampleRate).toFloat() * 2.5f
            buffer.left[i] = s
            buffer.right[i] = s
        }

        limiter.process(buffer)

        val maxAllowed = 10.0.pow(-0.5 / 20.0).toFloat() + 0.001f
        for (i in 0 until 1024) {
            assertTrue("Left sample ${buffer.left[i]} exceeded limiter ceiling $maxAllowed", abs(buffer.left[i]) <= maxAllowed)
            assertTrue("Right sample ${buffer.right[i]} exceeded limiter ceiling $maxAllowed", abs(buffer.right[i]) <= maxAllowed)
        }
        assertTrue("Limiter active flag should be set", limiter.isLimitingActive)
    }

    // 5. RMS & GAIN CALCULATION TESTS
    @Test
    fun dBToAmplitude_conversionAccuracy() {
        val gain0Db = 10.0.pow(0.0 / 20.0).toFloat()
        assertEquals(1.0f, gain0Db, 1e-5f)

        val gainPlus6Db = 10.0.pow(6.0206 / 20.0).toFloat()
        assertEquals(2.0f, gainPlus6Db, 1e-3f)

        val gainMinus6Db = 10.0.pow(-6.0206 / 20.0).toFloat()
        assertEquals(0.5f, gainMinus6Db, 1e-3f)
    }

    // 6. PRESET VALIDATION
    @Test
    fun presets_allHaveValid32BandsAndParameters() {
        for (preset in DspPreset.ALL_PRESETS) {
            assertEquals("Preset ${preset.name} must have exactly 32 band gains", 32, preset.bandGains.size)
            for (gain in preset.bandGains) {
                assertTrue("Gain $gain in ${preset.name} out of range", gain in -15f..15f)
            }
            assertTrue("Bass boost in ${preset.name} out of range", preset.bassBoostDb in 0f..15f)
            assertTrue("Virtualizer in ${preset.name} out of range", preset.virtualizerStrength in 0f..1f)
        }
    }
}
