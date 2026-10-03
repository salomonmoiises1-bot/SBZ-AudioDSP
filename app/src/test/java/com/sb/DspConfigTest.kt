package com.sb

import com.sb.dsp.*
import org.junit.Assert.*
import org.junit.Test

class DspConfigTest {

    @Test
    fun testDefaultConfigSanity() {
        val config = DspConfig.DEFAULT
        assertTrue(config.dspEnabled)
        assertEquals(0f, config.preGain, 0.001f)
        assertEquals(EqMode.EQ10, config.eqMode)
        assertEquals(10, config.eq10Gains.size)
        assertEquals(20, config.eq20Gains.size)
        assertEquals(32, config.eq32Gains.size)
    }

    @Test
    fun testValidationClampsNaNAndInfinity() {
        val corruptedConfig = DspConfig(
            preGain = Float.NaN,
            toneBass = Float.POSITIVE_INFINITY,
            toneTreble = -999f,
            eq10Gains = listOf(Float.NaN, 50f, -50f),
            mdrcCutoff1 = Float.NaN,
            mdrcCutoff2 = 100f, // Inválido respecto a cutoff1
            limiterThreshold = Float.NaN
        ).validate()

        assertFalse(corruptedConfig.preGain.isNaN())
        assertEquals(0f, corruptedConfig.preGain, 0.001f)
        assertEquals(0f, corruptedConfig.toneBass, 0.001f)
        assertEquals(-15f, corruptedConfig.toneTreble, 0.001f)

        // Verificación estricta de orden en Crossovers MDRC
        assertTrue(corruptedConfig.mdrcCutoff1 < corruptedConfig.mdrcCutoff2)
        assertTrue(corruptedConfig.mdrcCutoff2 < corruptedConfig.mdrcCutoff3)
        assertTrue(corruptedConfig.mdrcCutoff3 < corruptedConfig.mdrcCutoff4)
    }

    @Test
    fun testIndependentEqGainsRetention() {
        val config = DspConfig(
            eqMode = EqMode.EQ32,
            eq10Gains = List(10) { 1f },
            eq20Gains = List(20) { 2f },
            eq32Gains = List(32) { 3f }
        )

        assertEquals(3f, config.activeEqGains()[0], 0.001f)
        assertEquals(32, config.activeEqGains().size)

        val switchedTo10 = config.copy(eqMode = EqMode.EQ10)
        assertEquals(1f, switchedTo10.activeEqGains()[0], 0.001f)
        assertEquals(10, switchedTo10.activeEqGains().size)

        // Cambiar de nuevo a EQ32 conserva los valores originales
        val restored32 = switchedTo10.copy(eqMode = EqMode.EQ32)
        assertEquals(3f, restored32.activeEqGains()[0], 0.001f)
        assertEquals(32, restored32.activeEqGains().size)
    }

    @Test
    fun testExact32FrequenciesPresent() {
        assertEquals(32, DspConfig.FREQUENCIES_EQ32.size)
        // La frecuencia 32 debe ser 16 Hz (anclaje sub-grave 1/3 octava ISO)
        assertEquals(16f, DspConfig.FREQUENCIES_EQ32[0], 0.001f)
        assertEquals(20f, DspConfig.FREQUENCIES_EQ32[1], 0.001f)
        assertEquals(20000f, DspConfig.FREQUENCIES_EQ32.last(), 0.001f)
    }
}
