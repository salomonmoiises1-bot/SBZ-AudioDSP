package com.sb.dsp

import android.media.audiofx.BassBoost
import android.util.Log

/**
 * BassBoostManager: Gestiona el efecto nativo android.media.audiofx.BassBoost.
 * Mantiene la verificación de disponibilidad, límites reales (0-1000) y lectura de vuelta (readback).
 */
class BassBoostManager {
    companion object {
        private const val TAG = "SB_BassBoost"
    }

    private var bassBoost: BassBoost? = null
    var isAvailable: Boolean = false
        private set
    var isStrengthSupported: Boolean = false
        private set

    /**
     * Inicializa el efecto BassBoost para la sesión de audio especificada.
     */
    fun initialize(audioSessionId: Int, priority: Int = 1000): Boolean {
        release()
        return try {
            val bb = BassBoost(priority, audioSessionId)
            isAvailable = true
            isStrengthSupported = bb.strengthSupported
            bassBoost = bb
            Log.d(TAG, "BassBoost inicializado exitosamente en sesión $audioSessionId (Fuerza soportada: $isStrengthSupported)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "BassBoost no disponible en sesión $audioSessionId: ${e.message}")
            isAvailable = false
            bassBoost = null
            false
        }
    }

    /**
     * Aplica la configuración en tiempo real sin reconstruir el efecto.
     */
    fun applyConfig(config: DspConfig) {
        val effect = bassBoost ?: return
        try {
            val shouldEnable = config.dspEnabled && config.bassBoostEnabled && config.bassBoostStrength > 0
            if (effect.enabled != shouldEnable) {
                effect.enabled = shouldEnable
            }

            if (shouldEnable && isStrengthSupported) {
                val clampedStrength = config.bassBoostStrength.coerceIn(0, 1000).toShort()
                effect.setStrength(clampedStrength)

                // Readback para verificar valor real aplicado por el driver de audio
                val actualStrength = effect.roundedStrength
                Log.v(TAG, "BassBoost aplicado: solicitado=${config.bassBoostStrength}, actual=$actualStrength")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Fallo al aplicar BassBoost: ${e.message}", e)
        }
    }

    fun release() {
        try {
            bassBoost?.enabled = false
            bassBoost?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error al liberar BassBoost: ${e.message}")
        } finally {
            bassBoost = null
            isAvailable = false
        }
    }
}
