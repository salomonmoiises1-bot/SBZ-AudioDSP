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
 * DynamicsProcessing como único backend principal de la cadena DSP. No existe
 * fallback a android.media.audiofx.Equalizer ni una ruta PCM propia.
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

    private val _mdrcGainReduction = MutableStateFlow(floatArrayOf(0f, 0f, 0f, 0f))
    val mdrcGainReduction: StateFlow<FloatArray> = _mdrcGainReduction.asStateFlow()

    private val dynamicsProcessingManager = DynamicsProcessingManager()
    private val bassBoostManager = BassBoostManager()
    private val virtualizerManager = VirtualizerManager()
    private val autoGainManager = AutoGainManager()
    private val audioMeterManager = AudioMeterManager()

    private var activeSessionId = GLOBAL_SESSION_ID
    private var isInitialized = false
    private var usingDynamicsProcessing = false

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
                applyConfigInternal(pending)
            }
        }

        externalScope.launch {
            while (true) {
                delay(100L)
                autoGainManager.updateMeasuredRms(audioMeterManager.readRmsDb())
                val cfg = _config.value
                _mdrcGainReduction.value = if (cfg.dspEnabled && cfg.mdrcEnabled) {
                    // Android no expone el gain-reduction interno del MBC; este valor
                    // se conserva sólo para la visualización/diagnóstico existente.
                    dynamicsProcessingManager.getGainReductionDb()
                } else {
                    floatArrayOf(0f, 0f, 0f, 0f)
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
                throw IllegalStateException("DynamicsProcessing no disponible en sesión 0")
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
                // SB no longer creates/uses android.media.audiofx.Equalizer.
                // The physical EQ reported here is DynamicsProcessing Pre-EQ.
                hasEqualizer = false,
                nativeEqBands = 0,
                nativeEqMinLevelMb = (-1500).toShort(),
                nativeEqMaxLevelMb = 1500.toShort(),
                nativeEqCenterFreqsHz = emptyList(),
                hasDynamicsProcessing = true,
                dpChannelCount = dynamicsProcessingManager.backendInfo.channelCount,
                hasPreEq = dpOk && dynamicsProcessingManager.backendInfo.preEqBandCount > 0,
                preEqBandCount = dynamicsProcessingManager.backendInfo.preEqBandCount,
                hasMbc = dpOk && dynamicsProcessingManager.backendInfo.mbcBandCount > 0,
                mbcBandCount = dynamicsProcessingManager.backendInfo.mbcBandCount,
                hasPostEq = dpOk && dynamicsProcessingManager.backendInfo.postEqBandCount > 0,
                postEqBandCount = dynamicsProcessingManager.backendInfo.postEqBandCount,
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
                nativeEqBands = dynamicsProcessingManager.backendInfo.preEqBandCount,
                dynamicsProcessingActive = true
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

        // Toda la cadena principal (EQ32, Tone, MDRC, AutoGain, Limiter,
        // Master Gain y Balance) se aplica exclusivamente dentro de DP.
        if (usingDynamicsProcessing) {
            dynamicsProcessingManager.applyConfig(config, headroomDb, autoGainDb)
        }

        bassBoostManager.applyConfig(config)
        virtualizerManager.applyConfig(config)
    }

    private fun checkHardwareLiveness(): Boolean {
        if (!isInitialized || !_config.value.dspEnabled) return true
        return usingDynamicsProcessing &&
            activeSessionId == GLOBAL_SESSION_ID &&
            dynamicsProcessingManager.isAlive()
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
        bassBoostManager.release()
        virtualizerManager.release()
        audioMeterManager.release()
        autoGainManager.reset()
        isInitialized = false
        usingDynamicsProcessing = false
    }
}
