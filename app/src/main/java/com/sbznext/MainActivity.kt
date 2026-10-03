package com.sbznext

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.sbznext.audio.DspRuntime
import com.sbznext.audio.PlaybackDspService
import com.sbznext.data.DspPresets
import com.sbznext.dsp.DspConfig
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var serviceRunning by mutableStateOf(false)

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        DspRuntime.configure(this)
        requestNotificationPermission()
        setContent { SbzApp() }
    }

    override fun onResume() {
        super.onResume()

        // DspRuntime no expone isRunning() en esta versión.
        // El estado se actualiza directamente al iniciar/detener el servicio.
        // No se consulta una API inexistente.
    }

    override fun onStop() {
        DspRuntime.save(this)
        super.onStop()
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100
            )
        }
    }

    @Composable
    private fun SbzApp() {
        var c by remember { mutableStateOf(DspRuntime.config()) }

        fun update(next: DspConfig) {
            c = next
            DspRuntime.update(next)
        }

        MaterialTheme(colorScheme = darkColorScheme()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                Text(
                    "sBz Next",
                    style = MaterialTheme.typography.headlineMedium
                )

                Text(
                    "DSP global · EQ32 · MDRC · Limiter",
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            startForegroundService(
                                Intent(
                                    this@MainActivity,
                                    PlaybackDspService::class.java
                                )
                            )
                            serviceRunning = true
                        }
                    ) {
                        Text("Iniciar DSP")
                    }

                    Spacer(Modifier.width(8.dp))

                    OutlinedButton(
                        onClick = {
                            stopService(
                                Intent(
                                    this@MainActivity,
                                    PlaybackDspService::class.java
                                )
                            )
                            serviceRunning = false
                        }
                    ) {
                        Text("Detener")
                    }

                    Spacer(Modifier.width(12.dp))

                    Text(
                        if (serviceRunning) "ACTIVO" else "DETENIDO"
                    )
                }

                Spacer(Modifier.height(20.dp))

                Text(
                    "Ganancia y salida",
                    style = MaterialTheme.typography.titleLarge
                )

                Control(
                    "Pre-Gain",
                    c.preGainDb,
                    -12f,
                    12f
                ) {
                    update(c.copy(preGainDb = it))
                }

                Control(
                    "Bass Boost",
                    c.bassBoostDb,
                    -12f,
                    12f
                ) {
                    update(c.copy(bassBoostDb = it))
                }

                Control(
                    "Master",
                    c.masterGainDb,
                    -12f,
                    12f
                ) {
                    update(c.copy(masterGainDb = it))
                }

                Control(
                    "Balance",
                    c.balance,
                    -1f,
                    1f
                ) {
                    update(c.copy(balance = it))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = c.spatialEnabled,
                        onCheckedChange = {
                            update(c.copy(spatialEnabled = it))
                        }
                    )

                    Text("Spatial / Stereo Width")
                }

                if (c.spatialEnabled) {
                    Control(
                        "Width",
                        c.spatialWidth,
                        0f,
                        1f
                    ) {
                        update(c.copy(spatialWidth = it))
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    "Tone",
                    style = MaterialTheme.typography.titleLarge
                )

                Control(
                    "Bass",
                    c.toneBassDb,
                    -12f,
                    12f
                ) {
                    update(c.copy(toneBassDb = it))
                }

                Control(
                    "Mid",
                    c.toneMidDb,
                    -12f,
                    12f
                ) {
                    update(c.copy(toneMidDb = it))
                }

                Control(
                    "Treble",
                    c.toneTrebleDb,
                    -12f,
                    12f
                ) {
                    update(c.copy(toneTrebleDb = it))
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = c.eqEnabled,
                        onCheckedChange = {
                            update(c.copy(eqEnabled = it))
                        }
                    )

                    Text("EQ32 (32 bandas)")
                }

                Spacer(Modifier.height(4.dp))

                Text(
                    "20 Hz — 20 kHz · filtros configurados en el DSP global",
                    style = MaterialTheme.typography.bodySmall
                )

                Column(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .height(270.dp)
                ) {
                    Row {
                        DspConfig.FREQUENCIES.indices.forEach { idx ->
                            Column(
                                Modifier.width(42.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    label(DspConfig.FREQUENCIES[idx]),
                                    style = MaterialTheme.typography.labelSmall
                                )

                                Slider(
                                    value = c.eqGainsDb[idx],
                                    onValueChange = { value ->
                                        val gains = c.eqGainsDb.copyOf()
                                        gains[idx] = value
                                        update(
                                            c.copy(
                                                eqGainsDb = gains
                                            )
                                        )
                                    },
                                    valueRange = -12f..12f,
                                    steps = 47,
                                    modifier = Modifier.height(220.dp)
                                )

                                Text(
                                    "${c.eqGainsDb[idx].toInt()}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = c.mdrcEnabled,
                        onCheckedChange = {
                            update(c.copy(mdrcEnabled = it))
                        }
                    )

                    Text("MDRC 4 bandas")
                }

                Text(
                    "Cortes MDRC",
                    style = MaterialTheme.typography.titleMedium
                )

                c.mdrcCutoffsHz.forEachIndexed { idx, value ->
                    val lo =
                        if (idx == 0) {
                            20f
                        } else {
                            c.mdrcCutoffsHz[idx - 1] + 20f
                        }

                    val hi =
                        if (idx == 3) {
                            22000f
                        } else {
                            c.mdrcCutoffsHz[idx + 1] - 20f
                        }

                    Control(
                        "Crossover ${idx + 1}",
                        value,
                        lo.coerceAtMost(hi),
                        hi.coerceAtLeast(lo)
                    ) { next ->
                        val cuts = c.mdrcCutoffsHz.copyOf()
                        cuts[idx] = next.coerceIn(lo, hi)

                        update(
                            c.copy(
                                mdrcCutoffsHz = cuts
                            )
                        )
                    }
                }

                c.mdrcBands.forEachIndexed { idx, band ->
                    Text(
                        "Banda ${idx + 1}",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Control(
                        "Threshold",
                        band.thresholdDb,
                        -60f,
                        0f
                    ) { value ->
                        val bands = c.mdrcBands.copyOf()
                        bands[idx] = band.copy(
                            thresholdDb = value
                        )

                        update(
                            c.copy(
                                mdrcBands = bands
                            )
                        )
                    }

                    Control(
                        "Ratio",
                        band.ratio,
                        1f,
                        20f
                    ) { value ->
                        val bands = c.mdrcBands.copyOf()
                        bands[idx] = band.copy(
                            ratio = value
                        )

                        update(
                            c.copy(
                                mdrcBands = bands
                            )
                        )
                    }

                    Control(
                        "Attack",
                        band.attackMs,
                        0.5f,
                        100f
                    ) { value ->
                        val bands = c.mdrcBands.copyOf()
                        bands[idx] = band.copy(
                            attackMs = value
                        )

                        update(
                            c.copy(
                                mdrcBands = bands
                            )
                        )
                    }

                    Control(
                        "Release",
                        band.releaseMs,
                        1f,
                        500f
                    ) { value ->
                        val bands = c.mdrcBands.copyOf()
                        bands[idx] = band.copy(
                            releaseMs = value
                        )

                        update(
                            c.copy(
                                mdrcBands = bands
                            )
                        )
                    }

                    Control(
                        "Makeup",
                        band.makeupDb,
                        -12f,
                        12f
                    ) { value ->
                        val bands = c.mdrcBands.copyOf()
                        bands[idx] = band.copy(
                            makeupDb = value
                        )

                        update(
                            c.copy(
                                mdrcBands = bands
                            )
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = c.autoGainEnabled,
                        onCheckedChange = {
                            update(c.copy(autoGainEnabled = it))
                        }
                    )

                    Text("AutoGain / Headroom")
                }

                if (c.autoGainEnabled) {
                    Control(
                        "Headroom",
                        c.autoGainHeadroomDb,
                        0f,
                        6f
                    ) {
                        update(
                            c.copy(
                                autoGainHeadroomDb = it
                            )
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Switch(
                        checked = c.limiterEnabled,
                        onCheckedChange = {
                            update(c.copy(limiterEnabled = it))
                        }
                    )

                    Text("Limiter anti-clipping")
                }

                if (c.limiterEnabled) {
                    Control(
                        "Ceiling",
                        c.limiterCeilingDb,
                        -6f,
                        -0.1f
                    ) {
                        update(
                            c.copy(
                                limiterCeilingDb = it
                            )
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    "Presets",
                    style = MaterialTheme.typography.titleLarge
                )

                Row(
                    Modifier.horizontalScroll(
                        rememberScrollState()
                    )
                ) {
                    Button(
                        onClick = {
                            update(DspPresets.flat())
                        }
                    ) {
                        Text("Flat")
                    }

                    Spacer(Modifier.width(8.dp))

                    Button(
                        onClick = {
                            update(DspPresets.clarity())
                        }
                    ) {
                        Text("Clarity")
                    }

                    Spacer(Modifier.width(8.dp))

                    Button(
                        onClick = {
                            update(DspPresets.smooth())
                        }
                    ) {
                        Text("Smooth")
                    }

                    Spacer(Modifier.width(8.dp))

                    OutlinedButton(
                        onClick = {
                            DspRuntime.save(this@MainActivity)
                        }
                    ) {
                        Text("Guardar")
                    }
                }

                Spacer(Modifier.height(24.dp))

                Text(
                    "Ruta global: Android mezcla el audio normalmente y sBz aplica un único DynamicsProcessing en sesión 0. No usa captura, AudioRecord ni AudioTrack.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }

    @Composable
    private fun Control(
        name: String,
        value: Float,
        min: Float,
        max: Float,
        onChange: (Float) -> Unit
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(name)

                Text(
                    String.format(
                        Locale.US,
                        "%.1f",
                        value
                    )
                )
            }

            Slider(
                value = value,
                onValueChange = onChange,
                valueRange = min..max
            )
        }
    }

    private fun label(f: Float): String =
        when {
            f >= 1000f && f % 1000f == 0f ->
                "${(f / 1000).toInt()}k"

            f >= 1000f ->
                String.format(
                    Locale.US,
                    "%.1fk",
                    f / 1000f
                )

            else ->
                f.toInt().toString()
        }
}
