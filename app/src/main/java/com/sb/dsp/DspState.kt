package com.sb.dsp

/**
 * Máquina de estados explícita del motor DSP de SB.
 * Estados:
 * - OFF: Procesamiento inactivo, efectos liberados o deshabilitados.
 * - STARTING: Adquiriendo sesión, creando efectos y aplicando configuración.
 * - ACTIVE: Efectos aplicados y procesando audio según capacidades completas.
 * - DEGRADED: Procesamiento activo pero con limitaciones reales del hardware/dispositivo.
 * - CONFLICT: Conflicto con otra sesión o efecto del sistema.
 * - ERROR: Error irrecuperable en hardware/API de audio.
 */
sealed class DspState {
    object Off : DspState() {
        override fun toString(): String = "OFF"
    }

    object Starting : DspState() {
        override fun toString(): String = "STARTING"
    }

    data class Active(
        val sessionId: Int,
        val effectCount: Int,
        val nativeEqBands: Int,
        val dynamicsProcessingActive: Boolean
    ) : DspState() {
        override fun toString(): String = "ACTIVE (Sesión: $sessionId, Bandas: $nativeEqBands)"
    }

    data class Degraded(
        val sessionId: Int,
        val reason: String,
        val activeCapabilities: List<String>,
        val unavailableCapabilities: List<String>
    ) : DspState() {
        override fun toString(): String = "DEGRADED: $reason"
    }

    data class Conflict(
        val sessionId: Int,
        val message: String
    ) : DspState() {
        override fun toString(): String = "CONFLICT: $message"
    }

    data class Error(
        val exception: Throwable? = null,
        val message: String,
        val canRetry: Boolean = true
    ) : DspState() {
        override fun toString(): String = "ERROR: $message"
    }

    val isProcessing: Boolean
        get() = this is Active || this is Degraded
}
