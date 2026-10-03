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
 * DynamicsProcessing como backend principal. Equalizer/BassBoost/Virtualizer
 * sólo se usan como compatibilidad cuando DP no está disponible; nunca se crea
 * un segundo ecualizador nativo en paralelo con DP.
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

    // API pública conservada para procesamiento PCM directo. No está conectada al
    // audio de otras apps y por tanto no se ejecuta automáticamente en paralelo.
    val pcmPipeline = PcmAudioPipeline()

    private val _mdrcGainReduction = MutableStateFlow(floatArrayOf(0f, 0f, 0f, 0f))
    val mdrcGainReduction: StateFlow<FloatArray> = _mdrcGainReduction.asStateFlow()

    private val dynamicsProcessingManager = DynamicsProcessingManager()
    private val equalizerManager = EqualizerManager()
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
                    // Este medidor sólo representa el procesador PCM directo si un
                    // consumidor llama processPcm/processInterleaved. No se presenta
                    // como lectura del MBC HAL porque Android no expone ese GR.
                    dynamicsProcessingManager.getGainReductionDb()
                } else {
                    floatArrayOf(0f, 0f, 0f, 0f)
                }
            }
        }
    }

    fun start(sessionId: Int = GLOBAL_SESSION_ID) {
        externalScope.launch {
            engineMutex.withLock {
                startInternal(sessionId)
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

            // BassBoost/Virtualizer are complementary effects. Native Equalizer is
            // fallback-only because DP already owns the EQ stage when available.
            if (!dpOk) equalizerManager.initialize(sessionId)
            bassBoostManager.initialize(sessionId)
            virtualizerManager.initialize(sessionId)
            audioMeterManager.initialize(sessionId)

            _capabilities.value = DspCapabilities(
                sessionId = sessionId,
                isSessionZeroSupported = sessionId == GLOBAL_SESSION_ID && (dpOk || equalizerManager.isAvailable),
                hasEqualizer = equalizerManager.isAvailable,
                nativeEqBands = equalizerManager.numberOfBands.toInt(),
                nativeEqMinLevelMb = equalizerManager.minLevelMb,
                nativeEqMaxLevelMb = equalizerManager.maxLevelMb,
                nativeEqCenterFreqsHz = equalizerManager.centerFrequenciesHz,
                hasDynamicsProcessing = dpOk,
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

            val effectCount = (if (dpOk) 1 else if (equalizerManager.isAvailable) 1 else 0) +
                (if (bassBoostManager.isAvailable) 1 else 0) +
                (if (virtualizerManager.status != VirtualizerStatus.UNAVAILABLE) 1 else 0)

            _state.value = if (dpOk || equalizerManager.isAvailable) {
                DspState.Active(
                    sessionId = sessionId,
                    effectCount = effectCount,
                    nativeEqBands = if (dpOk) dynamicsProcessingManager.backendInfo.preEqBandCount else equalizerManager.numberOfBands.toInt(),
                    dynamicsProcessingActive = dpOk
                )
            } else {
                DspState.Error(message = "No hay un backend AudioEffect compatible", canRetry = true)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Fallo al iniciar DSP", e)
            releaseInternal()
            _state.value = DspState.Error(e, e.message ?: "Fallo al inicializar audio", true)
        }
    }

    fun processPcm(left: FloatArray, right: FloatArray, count: Int) {
        val cfg = _config.value
        if (!cfg.dspEnabled) return
        val headroomDb = HeadroomManager.calculateRequiredHeadroomDb(cfg)
        val autoGainDb = autoGainManager.calculateEffectiveGain(cfg, headroomDb)
        pcmPipeline.processBlock(left, right, 0, count, cfg, headroomDb, autoGainDb)
    }

    fun processInterleaved(buffer: FloatArray, frameCount: Int) {
        val cfg = _config.value
        if (!cfg.dspEnabled) return
        pcmPipeline.processInterleaved(buffer, frameCount, cfg)
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

        // Mantener el pipeline PCM coherente para llamadas directas, sin conectarlo
        // a la ruta global AudioEffect.
        pcmPipeline.mdrcProcessor.updateConfig(config)
        pcmPipeline.eqProcessor.updateConfig(config)

        if (usingDynamicsProcessing) {
            dynamicsProcessingManager.applyConfig(config, headroomDb, autoGainDb)
        } else {
            equalizerManager.applyConfig(config)
        }

        bassBoostManager.applyConfig(config)
        virtualizerManager.applyConfig(config)
    }

    private fun checkHardwareLiveness(): Boolean {
        if (!isInitialized || !_config.value.dspEnabled) return true
        return if (usingDynamicsProcessing) {
            dynamicsProcessingManager.isAlive()
        } else {
            equalizerManager.isAvailable && equalizerManager.numberOfBands > 0
        }
    }

    fun recover(reason: String) {
        externalScope.launch {
            engineMutex.withLock {
                Log.w(TAG, "Recuperando DSP: $reason")
                _state.value = DspState.Starting
                val session = activeSessionId
                releaseInternal()
                delay(300L)
                startInternal(session)
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
        pcmPipeline.mdrcProcessor.reset()
        isInitialized = false
        usingDynamicsProcessing = false
    }
}
