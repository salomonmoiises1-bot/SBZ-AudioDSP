# sBz Next 1.0

Proyecto Android nativo Kotlin/Compose con arquitectura global basada en `DynamicsProcessing`, siguiendo el enfoque de Equalizer314.

## Ruta de audio

La versión anterior usaba:

`AudioPlaybackCapture -> AudioRecord -> DspEngine -> AudioTrack`

Eso procesaba una copia del audio mientras la aplicación original seguía enviando el audio al mezclador de Android, por lo que podía aparecer eco/duplicación.

La versión actual elimina por completo `MediaProjection`, `AudioRecord` y `AudioTrack` de la ruta DSP. El servicio conecta `DynamicsProcessing` a la salida global de Android mediante la sesión 0. El audio original sigue teniendo una sola ruta dentro del mezclador y el DSP se inserta en esa infraestructura.

## Cadena sBz

La configuración mantiene:

1. Pre-Gain
2. Bass Boost + Tone
3. EQ32 lógico
4. MDRC de 4 bandas
5. Headroom
6. Spatial / Stereo Width
7. Balance
8. Master Gain
9. Limiter

Los 32 puntos del EQ siguen existiendo en `DspConfig`. La cantidad de bandas físicas que Android puede procesar depende del DSP del dispositivo. En equipos con menos bandas, la curva de 32 puntos se interpola hacia las bandas nativas disponibles; no se simula una segunda ruta PCM.

## Igual que Equalizer314

Equalizer314 utiliza `DynamicsProcessing` y `Visualizer` como infraestructura de audio, y su modo global utiliza la sesión 0. sBz Next toma `DynamicsProcessing` como base de integración, pero mantiene su propia configuración, presets y funciones. No se usa `AudioPlaybackCapture` para sustituir el mezclador.

## Permisos

La aplicación ya no solicita `RECORD_AUDIO` ni consentimiento de `MediaProjection`. Usa `MODIFY_AUDIO_SETTINGS`, servicio foreground de reproducción multimedia y notificaciones.

## Limitación importante

Android documenta que conectar efectos insert a la mezcla global mediante sesión 0 está deprecado, aunque es la infraestructura utilizada por aplicaciones de EQ global como Equalizer314. La disponibilidad y el número de bandas efectivos siguen dependiendo del firmware/OEM.

## Compilación

GitHub Actions usa JDK 17 y Gradle 8.9 para `:app:testDebugUnitTest` y `:app:assembleDebug`.
