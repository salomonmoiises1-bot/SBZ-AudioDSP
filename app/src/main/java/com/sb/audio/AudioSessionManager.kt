package com.sb.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Seguimiento de sesiones únicamente para diagnóstico.
 *
 * SB funciona en modo global, igual que el modo system-wide de Equalizer314:
 * DynamicsProcessing se mantiene en sesión 0 y los cambios de una app concreta
 * no hacen que el motor salte a la sesión privada de esa app.
 */
class AudioSessionManager(private val context: Context) {
    companion object {
        private const val TAG = "SB_SessionManager"
        const val GLOBAL_SESSION_ID = 0
    }

    private val _activeSessions = MutableStateFlow<Set<Int>>(setOf(GLOBAL_SESSION_ID))
    val activeSessions: StateFlow<Set<Int>> = _activeSessions

    private val _currentTargetSession = MutableStateFlow(GLOBAL_SESSION_ID)
    val currentTargetSession: StateFlow<Int> = _currentTargetSession

    fun onSessionOpened(sessionId: Int, packageName: String?) {
        if (sessionId <= 0) return
        val updated = _activeSessions.value.toMutableSet()
        updated.add(sessionId)
        _activeSessions.value = updated
        Log.d(TAG, "Sesión observada: $sessionId pkg=$packageName; backend SB permanece en sesión 0")
    }

    fun onSessionClosed(sessionId: Int, packageName: String?) {
        if (sessionId <= 0) return
        val updated = _activeSessions.value.toMutableSet()
        updated.remove(sessionId)
        updated.add(GLOBAL_SESSION_ID)
        _activeSessions.value = updated
        Log.d(TAG, "Sesión cerrada: $sessionId pkg=$packageName; backend SB permanece en sesión 0")
    }

    fun selectSession(sessionId: Int) {
        if (sessionId == GLOBAL_SESSION_ID) {
            _currentTargetSession.value = GLOBAL_SESSION_ID
        } else {
            Log.w(TAG, "SB es global; se ignora selección de sesión privada $sessionId")
        }
    }
}
