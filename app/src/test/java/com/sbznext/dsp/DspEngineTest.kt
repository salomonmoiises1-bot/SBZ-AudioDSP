package com.sbznext.dsp

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.pow

class DspEngineTest {
    @Test
    fun flatConfigPreservesFiniteSamples() {
        val e = DspEngine(48000)
        val b = FloatArray(4096) { if (it and 1 == 0) 0.2f else -0.2f }
        e.process(b)
        assertTrue(b.all { it.isFinite() })
    }

    @Test
    fun limiterKeepsOutputAtOrBelowUnity() {
        val e = DspEngine(48000)
        val b = FloatArray(4096) { if (it and 1 == 0) 4f else -4f }
        e.update(DspConfig(autoGainEnabled = false, limiterCeilingDb = -1f))
        e.process(b)
        val ceiling = 10.0.pow(-1.0 / 20.0)
        assertTrue(b.all { abs(it) <= ceiling + 1e-4 })
    }

    @Test
    fun eqChangesARelevantTone() {
        val e = DspEngine(48000)
        val gains = FloatArray(32)
        gains[17] = 12f // 1 kHz
        e.update(DspConfig(eqGainsDb = gains, autoGainEnabled = false))
        val b = FloatArray(4800) { i ->
            sin(2.0 * PI * 1000.0 * (i / 2) / 48000.0).toFloat() * 0.1f
        }
        val beforeRms = kotlin.math.sqrt(b.map { it.toDouble() * it }.average())
        e.process(b)
        val afterRms = kotlin.math.sqrt(b.map { it.toDouble() * it }.average())
        assertTrue(abs(afterRms - beforeRms) > 1e-3)
    }

    @Test
    fun mdrcCutoffsAreSanitizedAndOutputRemainsFinite() {
        val e = DspEngine(48000)
        val bad = DspConfig(
            mdrcCutoffsHz = floatArrayOf(22000f, 100f, 500f, 30000f),
            mdrcBands = Array(4) { MdrcBand(ratio = 100f) }
        )
        e.update(bad)
        val b = FloatArray(4096) { 0.1f }
        e.process(b)
        assertTrue(b.all { it.isFinite() && abs(it) <= 1f })
    }

    @Test
    fun autoGainDoesNotCreatePositiveGain() {
        val e = DspEngine(48000)
        val b = FloatArray(48000) { 0.8f }
        e.update(DspConfig(autoGainEnabled = true, autoGainHeadroomDb = 3f, limiterEnabled = false))
        e.process(b)
        assertTrue(e.lastAutoGainDb <= 0.05f)
    }
}
