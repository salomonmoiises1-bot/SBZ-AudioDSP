package com.sb.dsp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Autoridad única del motor DSP.
 *
 * Para audio externo la ruta real es AudioEffect sobre sesión 0, con
 * DynamicsProcessing como backend principal de la cadena DSP. Si el fabricante
 * rechaza DynamicsProcessing en sesión 0, se activa el Equalizer nativo como
 * backend de compatibilidad para que el DSP no quede inerte.
 */
class DspEngine(
    private val context: Context,
    private val externalScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    companion object {
        private const val TAG = "SB_DspEngine"
        const val GLOBAL_SESSION_ID = 0
    }

    private val engineMutex = Mutex()
    private val _state = MutableStateFlow<DspState>(DspState.Off)
    val state: StateFlow<DspState> = _state.asStateFlow()

    private val _config = MutableStateFlow(DspConfig.DEFAULT)
    val config: StateFlow<DspConfig> = _config.asStateFlow()

    private val _capabilities = MutableStateFlow(DspCapabilities())
    val capabilities: StateFlow<DspCapabilities> = _capabilities.asStateFlow()

    private val dynamicsProcessingManager = DynamicsProcessingManager()
    private val equalizerManager = EqualizerManager()
    private val bassBoostManager = BassBoostManager()
    private val virtualizerManager = VirtualizerManager()
    private val autoGainManager = AutoGainManager()
    private val audioMeterManager = AudioMeterManager()

    @Volatile
    private var activeSessionId = GLOBAL_SESSION_ID
    @Volatile
    private var isInitialized = false
    @Volatile
    private var usingDynamicsProcessing = false
    @Volatile
    private var lastAppliedAutoGainDb = Float.NaN

    private val watchdog = DspWatchdog(externalScope) { reason ->
        Log.w(TAG, "Watchdog activó recuperación: $reason")
        recover("Watchdog: $reason")
    }

    private val configUpdateFlow = MutableSharedFlow<DspConfig>(replay = 1)

    init {
        externalScope.launch {
            configUpdateFlow.collectLatest { pending ->
                // Coalescer corto: el último movimiento del control gana y no se
                // generan decenas de transacciones DP durante un drag.
                delay(12L)
                engineMutex.withLock {
                    applyConfigInternal(pending)
                }
            }
        }

        externalScope.launch {
            while (true) {
                delay(100L)
                autoGainManager.updateMeasuredRms(audioMeterManager.readRmsDb())
                val cfg = _config.value

                // AutoGain debe seguir la medición RMS aunque ningún control de UI
                // cambie. Sólo se toca la ganancia de entrada, no toda la cadena.
                if (cfg.dspEnabled && cfg.autoGainEnabled && isInitialized && usingDynamicsProcessing) {
                    val headroomDb = HeadroomManager.calculateRequiredHeadroomDb(cfg)
                    val autoGainDb = autoGainManager.calculateEffectiveGain(cfg, headroomDb)
                    if (!lastAppliedAutoGainDb.isFinite() ||
                        kotlin.math.abs(autoGainDb - lastAppliedAutoGainDb) >= 0.02f
                    ) {
                        engineMutex.withLock {
                            if (isInitialized && usingDynamicsProcessing) {
                                dynamicsProcessingManager.applyInputGain(cfg, headroomDb, autoGainDb)
                                lastAppliedAutoGainDb = autoGainDb
                            }
                        }
                    }
                }

            }
        }
    }

    /**
     * Inicia SIEMPRE el backend global de SB. El proyecto no ofrece un modo de
     * sesión privada: el objetivo es utilizar el camino de procesamiento system-wide.
     */
    fun start(@Suppress("UNUSED_PARAMETER") sessionId: Int = GLOBAL_SESSION_ID) {
        externalScope.launch {
            engineMutex.withLock {
                startInternal(GLOBAL_SESSION_ID)
            }
        }
    }

    private suspend fun startInternal(sessionId: Int) {
        if (isInitialized && activeSessionId == sessionId) {
            applyConfigInternal(_config.value)
            return
        }

        _state.value = DspState.Starting
        if (isInitialized) releaseInternal()
        activeSessionId = sessionId

        try {
            val dpOk = dynamicsProcessingManager.initialize(
                sessionId,
                initialConfig = _config.value
            )
            usingDynamicsProcessing = dpOk

            if (!dpOk) {
                // Fallback real: algunos HAL/vendor no permiten crear
                // DynamicsProcessing en sesión 0 aunque sí permitan Equalizer.
                // En ese caso mantenemos una ruta de procesamiento funcional.
                val eqOk = equalizerManager.initialize(GLOBAL_SESSION_ID)
                if (!eqOk) {
                    throw IllegalStateException("No hay backend DSP disponible en sesión 0")
                }
                Log.w(TAG, "DynamicsProcessing no disponible; usando Equalizer nativo como fallback")
            }

            // BassBoost/Virtualizer son efectos complementarios que, cuando el
            // dispositivo los admite, se insertan sobre la misma sesión global.
            // El EQ, MDRC, limiter y ganancias permanecen exclusivamente en DP.
            bassBoostManager.initialize(GLOBAL_SESSION_ID)
            virtualizerManager.initialize(GLOBAL_SESSION_ID)
            audioMeterManager.initialize(GLOBAL_SESSION_ID)

            _capabilities.value = DspCapabilities(
                sessionId = GLOBAL_SESSION_ID,
                isSessionZeroSupported = true,
                hasEqualizer = !dpOk && equalizerManager.isAvailable,
                nativeEqBands = if (!dpOk) equalizerManager.numberOfBands.toInt() else dynamicsProcessingManager.backendInfo.preEqBandCount,
                nativeEqMinLevelMb = if (!dpOk) equalizerManager.minLevelMb else (-1500).toShort(),
                nativeEqMaxLevelMb = if (!dpOk) equalizerManager.maxLevelMb else 1500.toShort(),
                nativeEqCenterFreqsHz = if (!dpOk) equalizerManager.centerFrequenciesHz else emptyList(),
                hasDynamicsProcessing = dpOk,
                dpChannelCount = if (dpOk) dynamicsProcessingManager.backendInfo.channelCount else 2,
                hasPreEq = dpOk && dynamicsProcessingManager.backendInfo.preEqBandCount > 0,
                preEqBandCount = if (dpOk) dynamicsProcessingManager.backendInfo.preEqBandCount else 0,
                hasMbc = dpOk && dynamicsProcessingManager.backendInfo.mbcBandCount > 0,
                mbcBandCount = if (dpOk) dynamicsProcessingManager.backendInfo.mbcBandCount else 0,
                hasPostEq = dpOk && dynamicsProcessingManager.backendInfo.postEqBandCount > 0,
                postEqBandCount = if (dpOk) dynamicsProcessingManager.backendInfo.postEqBandCount else 0,
                hasLimiter = dpOk,
                hasBassBoost = bassBoostManager.isAvailable,
                isBassBoostStrengthSupported = bassBoostManager.isStrengthSupported,
                hasVirtualizer = virtualizerManager.status != VirtualizerStatus.UNAVAILABLE,
                isVirtualizerStrengthSupported = virtualizerManager.isStrengthSupported
            )

            isInitialized = true
            applyConfigInternal(_config.value)
            watchdog.start { checkHardwareLiveness() }

            val effectCount = 1 +
                (if (bassBoostManager.isAvailable) 1 else 0) +
                (if (virtualizerManager.status != VirtualizerStatus.UNAVAILABLE) 1 else 0)

            _state.value = DspState.Active(
                sessionId = GLOBAL_SESSION_ID,
                effectCount = effectCount,
                nativeEqBands = if (dpOk) dynamicsProcessingManager.backendInfo.preEqBandCount else equalizerManager.numberOfBands.toInt(),
                dynamicsProcessingActive = dpOk
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Fallo al iniciar DSP", e)
            releaseInternal()
            _state.value = DspState.Error(e, e.message ?: "Fallo al inicializar audio", true)
        }
    }

    fun updateConfig(newConfig: DspConfig) {
        val sanitized = newConfig.validate()
        _config.value = sanitized
        configUpdateFlow.tryEmit(sanitized)
    }

    private suspend fun applyConfigInternal(config: DspConfig) {
        if (!isInitialized) return
        val headroomDb = HeadroomManager.calculateRequiredHeadroomDb(config)
        val autoGainDb = autoGainManager.calculateEffectiveGain(config, headroomDb)

        // DynamicsProcessing es el backend principal. Si no está disponible,
        // el EQ nativo sigue recibiendo la configuración para mantener una ruta
        // de procesamiento real en vez de dejar la interfaz sin efecto audible.
        if (usingDynamicsProcessing) {
            dynamicsProcessingManager.applyConfig(config, headroomDb, autoGainDb)
            lastAppliedAutoGainDb = autoGainDb
        } else {
            equalizerManager.applyConfig(config)
            lastAppliedAutoGainDb = Float.NaN
        }

        bassBoostManager.applyConfig(config)
        virtualizerManager.applyConfig(config)
    }

    private fun checkHardwareLiveness(): Boolean {
        if (!isInitialized || !_config.value.dspEnabled) return true
        return activeSessionId == GLOBAL_SESSION_ID &&
            if (usingDynamicsProcessing) dynamicsProcessingManager.isAlive() else equalizerManager.isAvailable
    }

    fun recover(reason: String) {
        externalScope.launch {
            engineMutex.withLock {
                Log.w(TAG, "Recuperando DSP: $reason")
                _state.value = DspState.Starting
                releaseInternal()
                delay(300L)
                startInternal(GLOBAL_SESSION_ID)
            }
        }
    }

    fun stop() {
        externalScope.launch {
            engineMutex.withLock {
                watchdog.stop()
                releaseInternal()
                _state.value = DspState.Off
            }
        }
    }

    private fun releaseInternal() {
        watchdog.stop()
        dynamicsProcessingManager.release()
        equalizerManager.release()
        bassBoostManager.release()
        virtualizerManager.release()
        audioMeterManager.release()
        autoGainManager.reset()
        lastAppliedAutoGainDb = Float.NaN
        isInitialized = false
        usingDynamicsProcessing = false
    }
}
