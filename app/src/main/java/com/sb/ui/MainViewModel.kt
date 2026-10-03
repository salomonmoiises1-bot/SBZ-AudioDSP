package com.sb.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sb.SbApplication
import com.sb.data.Preset
import com.sb.data.PresetRepository
import com.sb.diagnostics.AudioEffectsDiagnostics
import com.sb.diagnostics.DiagnosticsReport
import com.sb.dsp.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * MainViewModel: Conexión entre Jetpack Compose y el motor DspEngine / PresetRepository.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SbApplication
    private val dspEngine: DspEngine = app.dspEngine
    private val presetRepository: PresetRepository = app.presetRepository

    val dspState: StateFlow<DspState> = dspEngine.state
    val dspConfig: StateFlow<DspConfig> = dspEngine.config
    val dspCapabilities: StateFlow<DspCapabilities> = dspEngine.capabilities

    // Medición en tiempo real de Gain Reduction de las 4 bandas MDRC
    val mdrcGainReduction: StateFlow<FloatArray> = dspEngine.mdrcGainReduction

    private val _presets = MutableStateFlow<List<Preset>>(PresetRepository.FACTORY_PRESETS)
    val presets: StateFlow<List<Preset>> = _presets.asStateFlow()

    private val _diagnostics = MutableStateFlow(
        AudioEffectsDiagnostics.generateReport(
            currentState = dspState.value,
            capabilities = dspCapabilities.value,
            activeSessions = listOf(0),
            recoveryCount = 0,
            lastRecoveryReason = null
        )
    )
    val diagnostics: StateFlow<DiagnosticsReport> = _diagnostics.asStateFlow()

    init {
        // Cargar presets de usuario y combinarlos con los presets de fábrica
        viewModelScope.launch {
            presetRepository.userPresetsFlow.collectLatest { userPresets ->
                _presets.value = PresetRepository.FACTORY_PRESETS + userPresets
            }
        }

        // Refrescar diagnósticos periódicamente
        viewModelScope.launch {
            combine(dspState, dspCapabilities) { state, caps ->
                AudioEffectsDiagnostics.generateReport(
                    currentState = state,
                    capabilities = caps,
                    activeSessions = listOf(caps.sessionId),
                    recoveryCount = 0,
                    lastRecoveryReason = null
                )
            }.collectLatest { report ->
                _diagnostics.value = report
            }
        }
    }

    // ==========================================
    // ACTUALIZACIONES DE PARÁMETROS DSP Y MDRC
    // ==========================================

    fun setDspEnabled(enabled: Boolean) {
        val updated = dspConfig.value.copy(dspEnabled = enabled)
        applyAndPersist(updated)
    }

    fun setPreGain(db: Float) {
        val updated = dspConfig.value.copy(preGain = db)
        applyAndPersist(updated)
    }

    fun setBassBoost(enabled: Boolean, strength: Int) {
        val updated = dspConfig.value.copy(
            bassBoostEnabled = enabled,
            bassBoostStrength = strength
        )
        applyAndPersist(updated)
    }

    fun setTone(bass: Float, mid: Float, treble: Float) {
        val updated = dspConfig.value.copy(
            toneBass = bass,
            toneMid = mid,
            toneTreble = treble
        )
        applyAndPersist(updated)
    }

    fun setEqMode(mode: EqMode) {
        val updated = dspConfig.value.copy(eqMode = mode)
        applyAndPersist(updated)
    }

    fun setEqGain(bandIndex: Int, gainDb: Float) {
        val current = dspConfig.value
        val updated = when (current.eqMode) {
            EqMode.EQ10 -> {
                val list = current.eq10Gains.toMutableList()
                if (bandIndex in list.indices) list[bandIndex] = gainDb
                current.copy(eq10Gains = list)
            }
            EqMode.EQ20 -> {
                val list = current.eq20Gains.toMutableList()
                if (bandIndex in list.indices) list[bandIndex] = gainDb
                current.copy(eq20Gains = list)
            }
            EqMode.EQ32 -> {
                val list = current.eq32Gains.toMutableList()
                if (bandIndex in list.indices) list[bandIndex] = gainDb
                current.copy(eq32Gains = list)
            }
        }
        applyAndPersist(updated)
    }

    fun resetEqGains() {
        val current = dspConfig.value
        val updated = when (current.eqMode) {
            EqMode.EQ10 -> current.copy(eq10Gains = List(10) { 0f })
            EqMode.EQ20 -> current.copy(eq20Gains = List(20) { 0f })
            EqMode.EQ32 -> current.copy(eq32Gains = List(32) { 0f })
        }
        applyAndPersist(updated)
    }

    // ==========================================
    // MDRC CONTROLS
    // ==========================================

    fun setMdrcEnabled(enabled: Boolean) {
        val updated = dspConfig.value.copy(mdrcEnabled = enabled)
        applyAndPersist(updated)
    }

    fun setMdrcBand(bandIndex: Int, bandConfig: MdrcBandConfig) {
        val current = dspConfig.value
        val updated = when (bandIndex) {
            0 -> current.copy(mdrcBand1 = bandConfig)
            1 -> current.copy(mdrcBand2 = bandConfig)
            2 -> current.copy(mdrcBand3 = bandConfig)
            3 -> current.copy(mdrcBand4 = bandConfig)
            else -> current
        }
        applyAndPersist(updated)
    }

    fun setMdrcCutoffs(c1: Float, c2: Float, c3: Float, c4: Float) {
        val updated = dspConfig.value.copy(
            mdrcCutoff1 = c1,
            mdrcCutoff2 = c2,
            mdrcCutoff3 = c3,
            mdrcCutoff4 = c4
        )
        applyAndPersist(updated)
    }

    fun setLimiter(enabled: Boolean, threshold: Float, release: Float) {
        val updated = dspConfig.value.copy(
            limiterEnabled = enabled,
            limiterThreshold = threshold,
            limiterRelease = release
        )
        applyAndPersist(updated)
    }

    fun setAutoGain(enabled: Boolean, targetDb: Float) {
        val updated = dspConfig.value.copy(
            autoGainEnabled = enabled,
            autoGainTarget = targetDb
        )
        applyAndPersist(updated)
    }

    fun setAutoHeadroom(enabled: Boolean) {
        val updated = dspConfig.value.copy(autoHeadroomEnabled = enabled)
        applyAndPersist(updated)
    }

    fun setVirtualizer(enabled: Boolean, strength: Int) {
        val updated = dspConfig.value.copy(
            virtualizerEnabled = enabled,
            virtualizerStrength = strength
        )
        applyAndPersist(updated)
    }

    fun setMasterGain(gainDb: Float) {
        val updated = dspConfig.value.copy(masterGain = gainDb)
        applyAndPersist(updated)
    }

    fun setBalance(balanceVal: Float) {
        val updated = dspConfig.value.copy(balance = balanceVal)
        applyAndPersist(updated)
    }

    // ==========================================
    // OPERACIONES DE PRESETS
    // ==========================================

    fun loadPreset(preset: Preset) {
        applyAndPersist(preset.config)
    }

    /**
     * Guarda un preset personalizado capturando el 100% de la configuración actual
     * del usuario (Preamp, Bass Boost, Tone, EQ, MDRC 4 bandas, Crossovers, Limiter, Master).
     */
    fun saveNewPreset(name: String) {
        viewModelScope.launch {
            val newPreset = Preset(
                name = name,
                version = 1,
                timestamp = System.currentTimeMillis(),
                isFactory = false,
                config = dspConfig.value
            )
            val currentList = _presets.value.filter { !it.isFactory }
            val updated = currentList + newPreset
            presetRepository.saveUserPresets(updated)
        }
    }

    fun deletePreset(presetId: String) {
        viewModelScope.launch {
            val filtered = _presets.value.filter { !it.isFactory && it.id != presetId }
            presetRepository.saveUserPresets(filtered)
        }
    }

    fun triggerRecovery() {
        dspEngine.recover("Recuperación manual solicitada por el usuario")
    }

    private fun applyAndPersist(config: DspConfig) {
        val sanitized = config.validate()
        dspEngine.updateConfig(sanitized)
        viewModelScope.launch {
            presetRepository.saveActiveConfig(sanitized)
        }
    }
}
