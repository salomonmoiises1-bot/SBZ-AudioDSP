package com.sb.dsp

import android.media.audiofx.Virtualizer
import android.util.Log

enum class VirtualizerStatus {
    UNAVAILABLE,
    AVAILABLE,
    ACTIVE,
    ERROR
}

/**
 * VirtualizerManager: Gestiona el efecto nativo android.media.audiofx.Virtualizer.
 * Informa con rigor el estado real: si el efecto es rechazado por el hardware,
 * nunca muestra 'ACTIVE'.
 */
class VirtualizerManager {
    companion object {
        private const val TAG = "SB_Virtualizer"
    }

    private var virtualizer: Virtualizer? = null
    private var lastStrength = -1
    var status: VirtualizerStatus = VirtualizerStatus.UNAVAILABLE
        private set
    var isStrengthSupported: Boolean = false
        private set

    fun initialize(audioSessionId: Int, priority: Int = Int.MAX_VALUE): Boolean {
        release()
        return try {
            val v = Virtualizer(priority, audioSessionId)
            isStrengthSupported = v.strengthSupported
            virtualizer = v
            status = VirtualizerStatus.AVAILABLE
            Log.d(TAG, "Virtualizer inicializado exitosamente en sesión $audioSessionId (Fuerza soportada: $isStrengthSupported)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Virtualizer no disponible en sesión $audioSessionId: ${e.message}")
            status = VirtualizerStatus.UNAVAILABLE
            virtualizer = null
            false
        }
    }

    fun applyConfig(config: DspConfig) {
        val effect = virtualizer ?: return
        try {
            val shouldEnable = config.dspEnabled && config.virtualizerEnabled && config.virtualizerStrength > 0
            if (effect.enabled != shouldEnable) {
                effect.enabled = shouldEnable
            }

            if (shouldEnable) {
                if (isStrengthSupported) {
                    val strength = config.virtualizerStrength.coerceIn(0, 1000)
                    if (strength != lastStrength) {
                        effect.setStrength(strength.toShort())
                        lastStrength = strength
                    }
                }
                status = VirtualizerStatus.ACTIVE
            } else {
                status = VirtualizerStatus.AVAILABLE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando Virtualizer: ${e.message}", e)
            status = VirtualizerStatus.ERROR
        }
    }

    fun release() {
        try {
            virtualizer?.enabled = false
            virtualizer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error liberando Virtualizer: ${e.message}")
        } finally {
            virtualizer = null
            status = VirtualizerStatus.UNAVAILABLE
            isStrengthSupported = false
            lastStrength = -1
        }
    }
}
