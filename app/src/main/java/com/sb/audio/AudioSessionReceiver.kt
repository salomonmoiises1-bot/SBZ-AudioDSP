package com.sb.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.util.Log

/**
 * AudioSessionReceiver: Escucha los eventos globales del sistema cuando un reproductor
 * multimedia abre o cierra una sesión de efectos de audio (Action Open/Close Audio Effect Control Session).
 */
class AudioSessionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SB_AudioSessionRecv"
        var sessionListener: ((sessionId: Int, isOpen: Boolean, packageName: String?) -> Unit)? = null
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null) return

        val action = intent.action
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)

        when (action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                Log.d(TAG, "AudioEffect OPEN recibido: sessionId=$sessionId, pkg=$packageName")
                sessionListener?.invoke(sessionId, true, packageName)
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                Log.d(TAG, "AudioEffect CLOSE recibido: sessionId=$sessionId, pkg=$packageName")
                sessionListener?.invoke(sessionId, false, packageName)
            }
        }
    }
}
