package com.sb.dsp

import kotlin.math.pow

/**
 * MasterGainManager: Control de ganancia maestro y balance estéreo.
 * Aplica una ley de balance precisa con atenuación y amplificación transparente.
 */
object MasterGainManager {

    /**
     * Calcula los factores lineales de ganancia estéreo (Left, Right)
     * a partir de masterGain (dB) y balance (-1.0f a +1.0f).
     */
    fun calculateStereoGains(
        masterGainDb: Float,
        balance: Float,
        autoHeadroomDb: Float = 0f,
        autoGainDb: Float = 0f
    ): Pair<Float, Float> {
        // Ganancia neta combinada
        val netGainDb = (masterGainDb + autoHeadroomDb + autoGainDb).coerceIn(-40f, 15f)
        val linearMaster = 10.0.pow(netGainDb / 20.0).toFloat()

        val clampedBalance = balance.coerceIn(-1.0f, 1.0f)

        // Ley de panorama estéreo lineal balanceada
        val panLeft = if (clampedBalance > 0f) 1.0f - clampedBalance else 1.0f
        val panRight = if (clampedBalance < 0f) 1.0f + clampedBalance else 1.0f

        val finalLeft = (linearMaster * panLeft).coerceIn(0f, 4f)
        val finalRight = (linearMaster * panRight).coerceIn(0f, 4f)

        return Pair(finalLeft, finalRight)
    }
}
