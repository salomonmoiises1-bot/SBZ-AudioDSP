package com.audiodsp.enginepro.dsp.core

/**
 * Reusable audio buffer for zero-allocation real-time stereo DSP processing.
 * Keeps planar float arrays for Left and Right channels, and helper methods
 * to convert to/from 16-bit signed PCM buffers.
 */
class AudioBuffer(val capacityFrames: Int = 2048) {
    var frameCount: Int = 0

    // Planar normalized float audio buffers in range [-1.0f, 1.0f]
    val left: FloatArray = FloatArray(capacityFrames)
    val right: FloatArray = FloatArray(capacityFrames)

    /**
     * De-interleaves 16-bit signed PCM short array into normalized float arrays.
     */
    fun fromInterleavedShorts(source: ShortArray, shortOffset: Int, totalShorts: Int) {
        val frames = totalShorts / 2
        val safeFrames = if (frames > capacityFrames) capacityFrames else frames
        frameCount = safeFrames

        var srcIdx = shortOffset
        for (i in 0 until safeFrames) {
            left[i] = source[srcIdx++] * INV_SHORT_MAX
            right[i] = source[srcIdx++] * INV_SHORT_MAX
        }
    }

    /**
     * De-interleaves 16-bit signed PCM byte array (Little Endian) into normalized float arrays.
     */
    fun fromInterleavedBytes(source: ByteArray, byteOffset: Int, totalBytes: Int) {
        val frames = totalBytes / 4 // 2 bytes per sample * 2 channels
        val safeFrames = if (frames > capacityFrames) capacityFrames else frames
        frameCount = safeFrames

        var srcIdx = byteOffset
        for (i in 0 until safeFrames) {
            // Little-endian 16-bit signed integers
            val sampleL = (source[srcIdx].toInt() and 0xFF) or (source[srcIdx + 1].toInt() shl 8)
            val sampleR = (source[srcIdx + 2].toInt() and 0xFF) or (source[srcIdx + 3].toInt() shl 8)
            srcIdx += 4

            left[i] = sampleL.toShort() * INV_SHORT_MAX
            right[i] = sampleR.toShort() * INV_SHORT_MAX
        }
    }

    /**
     * Interleaves normalized float arrays into 16-bit signed PCM short array with hard-clipping protection.
     */
    fun toInterleavedShorts(destination: ShortArray, destOffset: Int = 0): Int {
        var dstIdx = destOffset
        val frames = frameCount
        for (i in 0 until frames) {
            val sampleL = left[i].coerceIn(-1.0f, 1.0f)
            val sampleR = right[i].coerceIn(-1.0f, 1.0f)
            destination[dstIdx++] = (sampleL * SHORT_MAX).toInt().toShort()
            destination[dstIdx++] = (sampleR * SHORT_MAX).toInt().toShort()
        }
        return frames * 2
    }

    /**
     * Interleaves normalized float arrays into 16-bit signed PCM byte array (Little Endian).
     */
    fun toInterleavedBytes(destination: ByteArray, destOffset: Int = 0): Int {
        var dstIdx = destOffset
        val frames = frameCount
        for (i in 0 until frames) {
            val sampleL = (left[i].coerceIn(-1.0f, 1.0f) * SHORT_MAX).toInt()
            val sampleR = (right[i].coerceIn(-1.0f, 1.0f) * SHORT_MAX).toInt()

            destination[dstIdx++] = (sampleL and 0xFF).toByte()
            destination[dstIdx++] = ((sampleL shr 8) and 0xFF).toByte()
            destination[dstIdx++] = (sampleR and 0xFF).toByte()
            destination[dstIdx++] = ((sampleR shr 8) and 0xFF).toByte()
        }
        return frames * 4
    }

    companion object {
        private const val SHORT_MAX = 32767.0f
        private const val INV_SHORT_MAX = 1.0f / 32768.0f
    }
}
