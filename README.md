# sBz Next 1.0

Proyecto Android nativo Kotlin/Compose con un motor DSP PCM propio.

## Motor

La ruta de audio es:

`AudioPlaybackCapture -> AudioRecord Float PCM estéreo -> DspEngine -> AudioTrack Float PCM estéreo`

El `DspEngine` procesa el mismo buffer PCM y contiene:

1. Pre-Gain
2. Bass Boost + Tone
3. EQ32 Constant-Q, 32 Biquads
4. MDRC de 4 bandas con crossovers configurables
5. AutoGain / headroom
6. Spatial / stereo width
7. Balance
8. Master Gain
9. Limiter / anti-clipping

Los parámetros se mantienen fuera del hilo de audio y se aplican al motor sin crear objetos dentro de `process()`.

## Android playback capture

La captura de reproducción usa `MediaProjection` + `AudioPlaybackCaptureConfiguration` + `AudioRecord`. Android requiere permiso `RECORD_AUDIO` y consentimiento del usuario. Además, la aplicación que produce el audio puede impedir la captura.

**Limitación de plataforma:** AudioPlaybackCapture copia el audio reproducido por otra aplicación; no reemplaza silenciosamente su salida original. Por ello este proyecto no afirma ser un reemplazo global del mezclador de Android.

## Compilación

GitHub Actions usa JDK 17 y Gradle 8.9 para `:app:assembleDebug`. El workflow publica `app-debug.apk` como artefacto.

## Pruebas

Hay pruebas unitarias para finitud del procesamiento, limitación de amplitud y respuesta del EQ.
