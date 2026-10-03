package com.sb.dsp

import android.util.Log
import kotlinx.coroutines.*

/**
 * DspWatchdog: Monitoreo continuo de la salud de los efectos de audio.
 * Detecta:
 * - Efecto muerto (Dead Object)
 * - Efecto liberado externamente por el framework
 * - Deshabilitación involuntaria
 * - Conflictos de sesión
 *
 * Ejecuta una recuperación limpia y segura sin perder DspConfig ni generar instancias fantasma.
 */
class DspWatchdog(
    private val scope: CoroutineScope,
    private val checkIntervalMs: Long = 3000L,
    private val onAnomalyDetected: (reason: String) -> Unit
) {
    companion object {
        private const val TAG = "SB_Watchdog"
    }

    private var watchdogJob: Job? = null
    var recoveryCount: Int = 0
        private set
    var lastRecoveryTimestamp: Long = 0L
        private set
    var lastAnomalyReason: String? = null
        private set

    fun start(healthCheck: () -> Boolean) {
        stop()
        watchdogJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(checkIntervalMs)
                try {
                    val isHealthy = healthCheck()
                    if (!isHealthy) {
                        Log.w(TAG, "Watchdog detectó anomalía en la cadena de efectos de audio.")
                        lastAnomalyReason = "Efecto desconectado o invalidado por Android"
                        recoveryCount++
                        lastRecoveryTimestamp = System.currentTimeMillis()
                        withContext(Dispatchers.Main) {
                            onAnomalyDetected(lastAnomalyReason ?: "Anomalía de hardware")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Excepción en sondeo de Watchdog: ${e.message}", e)
                    lastAnomalyReason = e.message ?: "Excepción desconocida"
                    recoveryCount++
                    lastRecoveryTimestamp = System.currentTimeMillis()
                    withContext(Dispatchers.Main) {
                        onAnomalyDetected(lastAnomalyReason ?: "Excepción de hardware")
                    }
                }
            }
        }
    }

    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
    }
}
