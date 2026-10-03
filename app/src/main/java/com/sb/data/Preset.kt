package com.sb.data

import com.sb.dsp.DspConfig
import java.util.UUID

/**
 * Representa un preset completo de SB.
 * Guarda TODO el estado DSP (no solo el ecualizador).
 */
data class Preset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val version: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val isFactory: Boolean = false,
    val config: DspConfig
)
