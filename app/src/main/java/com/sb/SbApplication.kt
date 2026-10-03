package com.sb

import android.app.Application
import com.sb.data.PresetRepository
import com.sb.dsp.DspEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * SbApplication: Punto de entrada de la aplicación SB.
 * Mantiene las instancias singleton del motor DSP y repositorio de presets.
 */
class SbApplication : Application() {

    val applicationScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    lateinit var dspEngine: DspEngine
        private set
    lateinit var presetRepository: PresetRepository
        private set

    override fun onCreate() {
        super.onCreate()

        dspEngine = DspEngine(this, applicationScope)
        presetRepository = PresetRepository(this)

        // Restaurar estado persistido en el arranque
        applicationScope.launch {
            try {
                val savedConfig = presetRepository.activeConfigFlow.first()
                dspEngine.updateConfig(savedConfig)
            } catch (e: Exception) {
                // Si ocurre algún fallo de I/O, se mantiene DspConfig.DEFAULT
            }
        }
    }
}
