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
    private var lastStrength = -1
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

            val strength = config.bassBoostStrength.coerceIn(0, 1000)
            if (shouldEnable && isStrengthSupported && strength != lastStrength) {
                effect.setStrength(strength.toShort())
                Log.v(TAG, "BassBoost aplicado: solicitado=$strength, actual=${effect.roundedStrength}")
            }

            lastStrength = if (shouldEnable) strength else -1
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
            isStrengthSupported = false
            lastStrength = -1
        }
    }
}
