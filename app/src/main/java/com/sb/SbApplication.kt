package com.sb

import android.app.Application
import com.sb.data.PresetRepository
import com.sb.dsp.DspEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

        // La restauración de la configuración se realiza en SbAudioService antes
        // de crear los efectos, evitando una carrera entre DataStore y el backend.
    }
}
