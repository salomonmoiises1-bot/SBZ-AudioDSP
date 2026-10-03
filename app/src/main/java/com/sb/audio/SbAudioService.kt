package com.sb.audio

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sb.MainActivity
import com.sb.R
import com.sb.SbApplication
import com.sb.dsp.DspConfig
import com.sb.dsp.DspEngine
import com.sb.dsp.DspState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

/**
 * SbAudioService: Servicio en primer plano persistente para mantener el motor DSP
 * ejecutándose continuamente sin interrupciones por optimizaciones agresivas de batería.
 */
class SbAudioService : Service() {

    companion object {
        private const val TAG = "SB_AudioService"
        private const val NOTIFICATION_ID = 31401
        private const val CHANNEL_ID = "sb_dsp_channel"

        const val ACTION_START = "com.sb.action.START"
        const val ACTION_STOP = "com.sb.action.STOP"
        const val ACTION_TOGGLE_DSP = "com.sb.action.TOGGLE_DSP"

        fun startService(context: Context) {
            val intent = Intent(context, SbAudioService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, SbAudioService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private lateinit var dspEngine: DspEngine
    private lateinit var sessionManager: AudioSessionManager
    private lateinit var startupReady: CompletableDeferred<Unit>

    inner class LocalBinder : Binder() {
        fun getService(): SbAudioService = this@SbAudioService
        fun getEngine(): DspEngine = dspEngine
        fun getSessionManager(): AudioSessionManager = sessionManager
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Creando SbAudioService...")

        val app = application as SbApplication
        dspEngine = app.dspEngine
        sessionManager = AudioSessionManager(this)
        startupReady = CompletableDeferred()

        createNotificationChannel()

        // Iniciar servicio en primer plano
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(dspEngine.state.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(dspEngine.state.value))
        }

        // Observar estado del motor para actualizar notificación dinámicamente
        serviceScope.launch {
            dspEngine.state.collectLatest { state ->
                val notification = buildNotification(state)
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            }
        }

        // Conectar AudioSessionReceiver
        AudioSessionReceiver.sessionListener = { sessionId, isOpen, pkg ->
            if (isOpen) {
                sessionManager.onSessionOpened(sessionId, pkg)
            } else {
                sessionManager.onSessionClosed(sessionId, pkg)
            }
        }

        // Primero restaura la configuración persistida y recién después crea
        // los efectos. Así el backend nunca arranca un instante con DEFAULT y
        // luego cambia bruscamente al preset/configuración guardada.
        serviceScope.launch(Dispatchers.Default) {
            try {
                val savedConfig = app.presetRepository.activeConfigFlow.first()
                dspEngine.updateConfig(savedConfig)
            } catch (e: Throwable) {
                Log.w(TAG, "No se pudo restaurar configuración persistida: ${e.message}")
            } finally {
                dspEngine.start(AudioSessionManager.GLOBAL_SESSION_ID)
                startupReady.complete(Unit)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_DSP -> {
                val app = application as SbApplication
                serviceScope.launch {
                    startupReady.await()
                    val currentConfig = dspEngine.config.value
                    val newConfig = currentConfig.copy(dspEnabled = !currentConfig.dspEnabled).validate()
                    dspEngine.updateConfig(newConfig)
                    app.presetRepository.saveActiveConfig(newConfig)
                }
            }
            ACTION_STOP -> {
                Log.i(TAG, "Deteniendo SbAudioService por acción del usuario.")
                dspEngine.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                serviceScope.launch {
                    startupReady.await()
                    if (dspEngine.state.value == DspState.Off) {
                        dspEngine.start(AudioSessionManager.GLOBAL_SESSION_ID)
                    }
                }
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(state: DspState): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingOpenApp = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val toggleIntent = Intent(this, SbAudioService::class.java).apply {
            action = ACTION_TOGGLE_DSP
        }
        val pendingToggle = PendingIntent.getService(
            this,
            1,
            toggleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, SbAudioService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stateText = when (state) {
            is DspState.Active -> "Procesando audio (Sesión ${state.sessionId})"
            is DspState.Degraded -> "Modo compatible: ${state.reason}"
            is DspState.Starting -> "Iniciando procesador DSP..."
            is DspState.Conflict -> "Conflicto de sesión detectado"
            is DspState.Error -> "Error: ${state.message}"
            is DspState.Off -> "DSP Desactivado"
        }

        val toggleLabel = if (dspEngine.config.value.dspEnabled) "Desactivar" else "Activar"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SB - Procesador DSP")
            .setContentText(stateText)
            .setSmallIcon(R.drawable.ic_dsp_notification)
            .setContentIntent(pendingOpenApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, toggleLabel, pendingToggle)
            .addAction(0, "Cerrar", pendingStop)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Destruyendo SbAudioService...")
        AudioSessionReceiver.sessionListener = null
        dspEngine.stop()
        serviceScope.cancel()
    }
}
