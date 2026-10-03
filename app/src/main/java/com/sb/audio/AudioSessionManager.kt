package com.sb.audio

import android.content.Context
import android.media.audiofx.AudioEffect
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AudioSessionManager: Supervisa y gestiona las sesiones de audio del sistema.
 * Registra sesiones notificadas por reproductores multimedia externos a través
 * del broadcast de Android, ofreciendo alternativa si la sesión 0 global está bloqueada.
 */
class AudioSessionManager(private val context: Context) {
    companion object {
        private const val TAG = "SB_SessionManager"
    }

    private val _activeSessions = MutableStateFlow<Set<Int>>(setOf(0))
    val activeSessions: StateFlow<Set<Int>> = _activeSessions.asStateFlow()

    private val _currentTargetSession = MutableStateFlow(0)
    val currentTargetSession: StateFlow<Int> = _currentTargetSession.asStateFlow()

    fun onSessionOpened(sessionId: Int, packageName: String?) {
        if (sessionId <= 0) return
        Log.i(TAG, "Nueva sesión de audio abierta: $sessionId desde app: $packageName")
        val updated = _activeSessions.value.toMutableSet()
        updated.add(sessionId)
        _activeSessions.value = updated
        _currentTargetSession.value = sessionId
    }

    fun onSessionClosed(sessionId: Int, packageName: String?) {
        if (sessionId <= 0) return
        Log.i(TAG, "Sesión de audio cerrada: $sessionId desde app: $packageName")
        val updated = _activeSessions.value.toMutableSet()
        updated.remove(sessionId)
        _activeSessions.value = updated

        // Si la sesión actual se cerró, retroceder a la siguiente sesión activa o a 0
        if (_currentTargetSession.value == sessionId) {
            _currentTargetSession.value = updated.firstOrNull() ?: 0
        }
    }

    fun selectSession(sessionId: Int) {
        _currentTargetSession.value = sessionId
    }
}
