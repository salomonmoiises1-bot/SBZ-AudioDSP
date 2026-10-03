package com.sb.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Filtro Biquad digital estéreo de segundo orden basado en las fórmulas de Audio-EQ-Cookbook
 * de Robert Bristow-Johnson, con optimizaciones numéricas y protección total contra NaN/Denormales.
 */
class BiquadFilter(
    val type: Type,
    var frequency: Float,
    var sampleRate: Float = 48000f,
    var q: Float = 1.0f,
    var gainDb: Float = 0f
) {
    enum class Type {
        PEAKING,
        LOW_SHELF,
        HIGH_SHELF,
        LOW_PASS,
        HIGH_PASS
    }

    // Coeficientes normalizados del filtro
    private var b0 = 1.0f
    private var b1 = 0.0f
    private var b2 = 0.0f
    private var a1 = 0.0f
    private var a2 = 0.0f

    // Estado interno canal izquierdo
    private var x1L = 0.0f
    private var x2L = 0.0f
    private var y1L = 0.0f
    private var y2L = 0.0f

    // Estado interno canal derecho
    private var x1R = 0.0f
    private var x2R = 0.0f
    private var y1R = 0.0f
    private var y2R = 0.0f

    init {
        recalculateCoefficients()
    }

    /**
     * Recalcula los coeficientes biquad con salvaguardas contra frecuencias inválidas
     * y división por cero.
     */
    fun recalculateCoefficients() {
        val safeFs = if (sampleRate > 8000f) sampleRate else 48000f
        // Límite de Nyquist estricto con margen de seguridad del 95%
        val maxAllowedFreq = (safeFs * 0.475f).coerceAtLeast(100f)
        val safeFreq = frequency.coerceIn(10f, maxAllowedFreq)
        val safeQ = if (q.isNaN() || q <= 0.01f) 0.707f else q.coerceIn(0.1f, 40f)
        val safeGain = if (gainDb.isNaN() || gainDb.isInfinite()) 0f else gainDb.coerceIn(-30f, 30f)

        val omega = (2.0 * PI * safeFreq / safeFs).toFloat()
        val sn = sin(omega.toDouble()).toFloat()
        val cs = cos(omega.toDouble()).toFloat()
        val alpha = sn / (2.0f * safeQ)
        val aLinear = 10.0.pow(safeGain / 40.0).toFloat() // sqrt(A)

        var b0Temp: Float
        var b1Temp: Float
        var b2Temp: Float
        var a0Temp: Float
        var a1Temp: Float
        var a2Temp: Float

        when (type) {
            Type.PEAKING -> {
                b0Temp = 1.0f + alpha * aLinear
                b1Temp = -2.0f * cs
                b2Temp = 1.0f - alpha * aLinear
                a0Temp = 1.0f + alpha / aLinear
                a1Temp = -2.0f * cs
                a2Temp = 1.0f - alpha / aLinear
            }
            Type.LOW_SHELF -> {
                val sqrtA = sqrt(aLinear.toDouble()).toFloat()
                val twoSqrtAAlpha = 2.0f * sqrtA * alpha
                b0Temp = aLinear * ((aLinear + 1.0f) - (aLinear - 1.0f) * cs + twoSqrtAAlpha)
                b1Temp = 2.0f * aLinear * ((aLinear - 1.0f) - (aLinear + 1.0f) * cs)
                b2Temp = aLinear * ((aLinear + 1.0f) - (aLinear - 1.0f) * cs - twoSqrtAAlpha)
                a0Temp = (aLinear + 1.0f) + (aLinear - 1.0f) * cs + twoSqrtAAlpha
                a1Temp = -2.0f * ((aLinear - 1.0f) + (aLinear + 1.0f) * cs)
                a2Temp = (aLinear + 1.0f) + (aLinear - 1.0f) * cs - twoSqrtAAlpha
            }
            Type.HIGH_SHELF -> {
                val sqrtA = sqrt(aLinear.toDouble()).toFloat()
                val twoSqrtAAlpha = 2.0f * sqrtA * alpha
                b0Temp = aLinear * ((aLinear + 1.0f) + (aLinear - 1.0f) * cs + twoSqrtAAlpha)
                b1Temp = -2.0f * aLinear * ((aLinear - 1.0f) + (aLinear + 1.0f) * cs)
                b2Temp = aLinear * ((aLinear + 1.0f) + (aLinear - 1.0f) * cs - twoSqrtAAlpha)
                a0Temp = (aLinear + 1.0f) - (aLinear - 1.0f) * cs + twoSqrtAAlpha
                a1Temp = 2.0f * ((aLinear - 1.0f) - (aLinear + 1.0f) * cs)
                a2Temp = (aLinear + 1.0f) - (aLinear - 1.0f) * cs - twoSqrtAAlpha
            }
            Type.LOW_PASS -> {
                b0Temp = (1.0f - cs) / 2.0f
                b1Temp = 1.0f - cs
                b2Temp = (1.0f - cs) / 2.0f
                a0Temp = 1.0f + alpha
                a1Temp = -2.0f * cs
                a2Temp = 1.0f - alpha
            }
            Type.HIGH_PASS -> {
                b0Temp = (1.0f + cs) / 2.0f
                b1Temp = -(1.0f + cs)
                b2Temp = (1.0f + cs) / 2.0f
                a0Temp = 1.0f + alpha
                a1Temp = -2.0f * cs
                a2Temp = 1.0f - alpha
            }
        }

        val invA0 = if (a0Temp != 0f) 1.0f / a0Temp else 1.0f
        b0 = b0Temp * invA0
        b1 = b1Temp * invA0
        b2 = b2Temp * invA0
        a1 = a1Temp * invA0
        a2 = a2Temp * invA0
    }

    fun updateGain(newGainDb: Float) {
        if (this.gainDb != newGainDb) {
            this.gainDb = newGainDb
            recalculateCoefficients()
        }
    }

    /**
     * Procesa una muestra estéreo por forma directa II traspuesta / directa I con protección
     * contra denormales float.
     */
    fun processSample(inputL: Float, inputR: Float): Pair<Float, Float> {
        // Canal izquierdo
        var outL = b0 * inputL + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L
        if (outL.isNaN() || outL.isInfinite() || (outL > -1e-15f && outL < 1e-15f)) outL = 0f
        x2L = x1L
        x1L = inputL
        y2L = y1L
        y1L = outL

        // Canal derecho
        var outR = b0 * inputR + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R
        if (outR.isNaN() || outR.isInfinite() || (outR > -1e-15f && outR < 1e-15f)) outR = 0f
        x2R = x1R
        x1R = inputR
        y2R = y1R
        y1R = outR

        return Pair(outL, outR)
    }

    fun reset() {
        x1L = 0f; x2L = 0f; y1L = 0f; y2L = 0f
        x1R = 0f; x2R = 0f; y1R = 0f; y2R = 0f
    }
}
