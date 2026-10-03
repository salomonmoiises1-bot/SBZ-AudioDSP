package com.sb.dsp

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * DspEngine: Autoridad central del motor DSP de SB.
 *
 * Responsabilidades:
 * - Coordinar el ciclo de vida de todos los efectos de audio.
 * - Sincronizar llamadas concurrentes con Mutex.
 * - Conectar el procesamiento PCM real (Pre-Gain -> Bass Boost -> Tone -> EQ32 -> MDRC -> AutoGain -> Limiter -> Spatial -> Master Gain -> Balance).
 * - Coalescing de configuración.
 * - Detección de capacidades y watchdog de recuperación.
 * - Proporcionar mediciones de reducción de ganancia en tiempo real para el MDRC.
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

    // Estados expuestos reactivamente mediante StateFlow
    private val _state = MutableStateFlow<DspState>(DspState.Off)
    val state: StateFlow<DspState> = _state.asStateFlow()

    private val _config = MutableStateFlow(DspConfig.DEFAULT)
    val config: StateFlow<DspConfig> = _config.asStateFlow()

    private val _capabilities = MutableStateFlow(DspCapabilities())
    val capabilities: StateFlow<DspCapabilities> = _capabilities.asStateFlow()

    // Pipeline PCM propio en tiempo real
    val pcmPipeline = PcmAudioPipeline()

    // Flujo continuo de Gain Reduction para medidores UI (LOW, LOW-MID, MID-HIGH, HIGH)
    private val _mdrcGainReduction = MutableStateFlow(floatArrayOf(0f, 0f, 0f, 0f))
    val mdrcGainReduction: StateFlow<FloatArray> = _mdrcGainReduction.asStateFlow()

    // Managers de efectos hardware/HAL
    private val dynamicsProcessingManager = DynamicsProcessingManager()
    private val equalizerManager = EqualizerManager()
    private val bassBoostManager = BassBoostManager()
    private val virtualizerManager = VirtualizerManager()
    private val autoGainManager = AutoGainManager()
    private val toneManager = ToneManager()

    private var activeSessionId: Int = GLOBAL_SESSION_ID
    private var isInitialized = false

    // Watchdog
    private val watchdog = DspWatchdog(externalScope) { reason ->
        Log.w(TAG, "Watchdog activó recuperación: $reason")
        recover("Watchdog: $reason")
    }

    // Coalescing de configuración para evitar saturar el hardware
    private val configUpdateFlow = MutableSharedFlow<DspConfig>(replay = 1)

    init {
        externalScope.launch {
            configUpdateFlow
                .collectLatest { pendingConfig ->
                    applyConfigInternal(pendingConfig)
                }
        }

        // Tarea de sondeo de vúmetros de MDRC a 30 fps para la interfaz
        externalScope.launch {
            while (isActive) {
                delay(33L)
                if (_config.value.dspEnabled && _config.value.mdrcEnabled) {
                    val gr = pcmPipeline.mdrcProcessor.getGainReductionDb()
                    _mdrcGainReduction.value = gr
                } else if (_mdrcGainReduction.value[0] != 0f || _mdrcGainReduction.value[1] != 0f) {
                    _mdrcGainReduction.value = floatArrayOf(0f, 0f, 0f, 0f)
                }
            }
        }
    }

    /**
     * Inicia el procesamiento DSP en la sesión de audio especificada (por defecto sesión 0 global).
     */
    fun start(sessionId: Int = GLOBAL_SESSION_ID) {
        externalScope.launch {
            engineMutex.withLock {
                _state.value = DspState.Starting
                activeSessionId = sessionId
                Log.i(TAG, "Iniciando motor DSP en sesión: $sessionId")

                try {
                    // 1. Inicializar efectos HAL (si están disponibles)
                    val dpOk = dynamicsProcessingManager.initialize(sessionId, initialConfig = _config.value)
                    val eqOk = equalizerManager.initialize(sessionId)
                    val bbOk = bassBoostManager.initialize(sessionId)
                    val virtOk = virtualizerManager.initialize(sessionId)

                    // 2. Detección exhaustiva de capacidades reales
                    val detectedCapabilities = DspCapabilities(
                        sessionId = sessionId,
                        isSessionZeroSupported = sessionId == GLOBAL_SESSION_ID && (dpOk || eqOk),
                        hasEqualizer = eqOk,
                        nativeEqBands = equalizerManager.numberOfBands.toInt(),
                        nativeEqMinLevelMb = equalizerManager.minLevelMb,
                        nativeEqMaxLevelMb = equalizerManager.maxLevelMb,
                        nativeEqCenterFreqsHz = equalizerManager.centerFrequenciesHz,
                        hasDynamicsProcessing = dpOk,
                        dpChannelCount = 2,
                        hasPreEq = dpOk,
                        preEqBandCount = DynamicsProcessingManager.PRE_EQ_BAND_COUNT,
                        hasMbc = dpOk,
                        mbcBandCount = DynamicsProcessingManager.MBC_BAND_COUNT,
                        hasLimiter = dpOk,
                        hasBassBoost = bbOk,
                        isBassBoostStrengthSupported = bassBoostManager.isStrengthSupported,
                        hasVirtualizer = virtOk,
                        isVirtualizerStrengthSupported = virtualizerManager.isStrengthSupported
                    )
                    _capabilities.value = detectedCapabilities

                    isInitialized = true

                    // 3. Aplicar configuración actual al pipeline PCM y a los managers
                    applyConfigInternal(_config.value)

                    // 4. Iniciar Watchdog
                    watchdog.start {
                        checkHardwareLiveness()
                    }

                    _state.value = DspState.Active(
                        sessionId = sessionId,
                        effectCount = 4,
                        nativeEqBands = equalizerManager.numberOfBands.toInt(),
                        dynamicsProcessingActive = dpOk
                    )

                    Log.i(TAG, "Motor DSP iniciado exitosamente. Estado: ${_state.value}")

                } catch (e: Exception) {
                    Log.e(TAG, "Fallo al iniciar DSP: ${e.message}", e)
                    _state.value = DspState.Error(
                        exception = e,
                        message = e.message ?: "Fallo al inicializar hardware de audio",
                        canRetry = true
                    )
                }
            }
        }
    }

    /**
     * Procesa un bloque estéreo PCM directamente con la cadena completa.
     * Cero allocations en el loop.
     */
    fun processPcm(left: FloatArray, right: FloatArray, count: Int) {
        val cfg = _config.value
        if (!cfg.dspEnabled) return

        val headroomDb = HeadroomManager.calculateRequiredHeadroomDb(cfg)
        val autoGainDb = autoGainManager.calculateEffectiveGain(cfg, headroomDb)

        pcmPipeline.processBlock(left, right, 0, count, cfg, headroomDb, autoGainDb)
    }

    /**
     * Procesa un buffer estéreo intercalado [L0, R0, L1, R1, ...].
     */
    fun processInterleaved(buffer: FloatArray, frameCount: Int) {
        val cfg = _config.value
        if (!cfg.dspEnabled) return
        pcmPipeline.processInterleaved(buffer, frameCount, cfg)
    }

    /**
     * Aplica una nueva configuración con coalescing.
     */
    fun updateConfig(newConfig: DspConfig) {
        val sanitized = newConfig.validate()
        _config.value = sanitized
        configUpdateFlow.tryEmit(sanitized)
    }

    private suspend fun applyConfigInternal(config: DspConfig) {
        if (!isInitialized) return

        try {
            // 1. Auto Headroom
            val headroomDb = HeadroomManager.calculateRequiredHeadroomDb(config)

            // 2. Auto Gain
            val autoGainDb = autoGainManager.calculateEffectiveGain(config, headroomDb)

            // 3. Aplicar al pipeline PCM nativo (MDRC, EQ32, etc.)
            pcmPipeline.mdrcProcessor.updateConfig(config)
            pcmPipeline.eqProcessor.updateConfig(config)
            pcmPipeline.toneManager.applyConfig(config)

            // 4. Aplicar a DynamicsProcessing HAL si está activo
            dynamicsProcessingManager.applyConfig(config, autoHeadroomDb = headroomDb, autoGainDb = autoGainDb)

            // 5. Aplicar a Equalizer nativo
            equalizerManager.applyConfig(config)

            // 6. Aplicar Bass Boost nativo
            bassBoostManager.applyConfig(config)

            // 7. Aplicar Virtualizer nativo
            virtualizerManager.applyConfig(config)

            // 8. Tone
            toneManager.applyConfig(config)

        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando configuración DSP: ${e.message}", e)
        }
    }

    private fun checkHardwareLiveness(): Boolean {
        if (!isInitialized) return true
        if (!_config.value.dspEnabled) return true

        return if (dynamicsProcessingManager.isAvailable) {
            true
        } else if (equalizerManager.isAvailable) {
            equalizerManager.numberOfBands > 0
        } else {
            true
        }
    }

    fun recover(reason: String) {
        externalScope.launch {
            engineMutex.withLock {
                Log.w(TAG, "Ejecutando procedimiento de recuperación DSP: $reason")
                _state.value = DspState.Starting
                releaseInternal()
                delay(300)
                start(activeSessionId)
            }
        }
    }

    fun stop() {
        externalScope.launch {
            engineMutex.withLock {
                Log.i(TAG, "Deteniendo motor DSP...")
                watchdog.stop()
                releaseInternal()
                _state.value = DspState.Off
            }
        }
    }

    private fun releaseInternal() {
        dynamicsProcessingManager.release()
        equalizerManager.release()
        bassBoostManager.release()
        virtualizerManager.release()
        autoGainManager.reset()
        toneManager.reset()
        pcmPipeline.mdrcProcessor.reset()
        isInitialized = false
    }
}
