package com.sb.diagnostics

import android.media.audiofx.AudioEffect
import android.os.Build
import com.sb.dsp.DspCapabilities
import com.sb.dsp.DspState

/**
 * Modelo de datos con diagnóstico completo del entorno de audio del dispositivo.
 */
data class EffectDescriptorInfo(
    val type: String,
    val uuid: String,
    val name: String,
    val implementor: String
)

data class DiagnosticsReport(
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidVersion: String,
    val sdkInt: Int,
    val totalSystemEffects: Int,
    val effectDescriptors: List<EffectDescriptorInfo>,
    val currentState: DspState,
    val capabilities: DspCapabilities,
    val activeSessions: List<Int>,
    val recoveryCount: Int,
    val lastRecoveryReason: String?
)

/**
 * Recopila diagnósticos técnicos en profundidad para SB.
 */
object AudioEffectsDiagnostics {

    fun generateReport(
        currentState: DspState,
        capabilities: DspCapabilities,
        activeSessions: List<Int>,
        recoveryCount: Int,
        lastRecoveryReason: String?
    ): DiagnosticsReport {
        val descriptors = mutableListOf<EffectDescriptorInfo>()

        try {
            val queryResult = AudioEffect.queryEffects()
            if (queryResult != null) {
                for (desc in queryResult) {
                    descriptors.add(
                        EffectDescriptorInfo(
                            type = desc.type?.toString() ?: "Desconocido",
                            uuid = desc.uuid?.toString() ?: "N/A",
                            name = desc.name ?: "Sin nombre",
                            implementor = desc.implementor ?: "Genérico"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Manejo seguro si queryEffects falla en ROMs personalizadas
        }

        return DiagnosticsReport(
            deviceManufacturer = Build.MANUFACTURER,
            deviceModel = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            totalSystemEffects = descriptors.size,
            effectDescriptors = descriptors,
            currentState = currentState,
            capabilities = capabilities,
            activeSessions = activeSessions,
            recoveryCount = recoveryCount,
            lastRecoveryReason = lastRecoveryReason
        )
    }
}
