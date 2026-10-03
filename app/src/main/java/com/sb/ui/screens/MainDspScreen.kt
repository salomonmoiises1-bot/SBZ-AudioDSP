package com.sb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sb.dsp.HeadroomManager
import com.sb.ui.MainViewModel
import com.sb.ui.components.PreciseSlider
import com.sb.ui.components.StatusBadge
import com.sb.ui.theme.*

@Composable
fun MainDspScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val config by viewModel.dspConfig.collectAsState()
    val state by viewModel.dspState.collectAsState()
    val capabilities by viewModel.dspCapabilities.collectAsState()
    val currentHeadroom = HeadroomManager.calculateRequiredHeadroomDb(config)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // ENCABEZADO Y ESTADO DEL DSP
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SB AUDIO DSP",
                    style = MaterialTheme.typography.titleLarge,
                    color = AccentCyan
                )
                Text(
                    text = "Procesador de Audio Nativo",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            StatusBadge(state = state)
        }

        Spacer(modifier = Modifier.height(16.dp))

        // INTERRUPTOR MAESTRO
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Encendido Principal",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (config.dspEnabled) "Procesando flujo de audio" else "Efectos en bypass",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Switch(
                    checked = config.dspEnabled,
                    onCheckedChange = { viewModel.setDspEnabled(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AccentCyan,
                        checkedTrackColor = AccentCyan.copy(alpha = 0.5f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // PRE-GAIN Y AUTO HEADROOM
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Preamplificador y Auto Headroom",
                    style = MaterialTheme.typography.titleMedium,
                    color = AccentCyan
                )
                Spacer(modifier = Modifier.height(8.dp))

                PreciseSlider(
                    label = "Pre-Gain (Preamplificación)",
                    value = config.preGain,
                    range = -20f..20f,
                    unit = "dB",
                    enabled = config.dspEnabled,
                    onValueChange = { viewModel.setPreGain(it) }
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Auto Headroom Dinámico",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = if (config.autoHeadroomEnabled) "Margen de seguridad activo: ${String.format("%.1f dB", currentHeadroom)}" else "Desactivado (Riesgo de saturación)",
                            fontSize = 12.sp,
                            color = if (config.autoHeadroomEnabled) AccentGreen else AccentAmber
                        )
                    }
                    Switch(
                        checked = config.autoHeadroomEnabled,
                        onCheckedChange = { viewModel.setAutoHeadroom(it) },
                        enabled = config.dspEnabled
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // BASS BOOST NATIVO
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Bass Boost (Refuerzo de Graves)",
                            style = MaterialTheme.typography.titleMedium,
                            color = AccentCyan
                        )
                        Text(
                            text = if (capabilities.hasBassBoost) "Efecto hardware disponible" else "No soportado por hardware",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Switch(
                        checked = config.bassBoostEnabled,
                        onCheckedChange = { viewModel.setBassBoost(it, config.bassBoostStrength) },
                        enabled = config.dspEnabled && capabilities.hasBassBoost
                    )
                }

                if (config.bassBoostEnabled && capabilities.hasBassBoost) {
                    Spacer(modifier = Modifier.height(8.dp))
                    PreciseSlider(
                        label = "Fuerza de Refuerzo",
                        value = config.bassBoostStrength.toFloat(),
                        range = 0f..1000f,
                        unit = "/1000",
                        enabled = config.dspEnabled,
                        onValueChange = { viewModel.setBassBoost(config.bassBoostEnabled, it.toInt()) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // CONTROLES DE TONO (BASS, MID, TREBLE)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Control de Tono (3 Vías Independientes)",
                    style = MaterialTheme.typography.titleMedium,
                    color = AccentCyan
                )
                Spacer(modifier = Modifier.height(8.dp))

                PreciseSlider(
                    label = "Graves (Low-Shelf 100 Hz)",
                    value = config.toneBass,
                    range = -15f..15f,
                    unit = "dB",
                    enabled = config.dspEnabled,
                    onValueChange = { viewModel.setTone(it, config.toneMid, config.toneTreble) }
                )

                PreciseSlider(
                    label = "Medios (Peaking 1 kHz)",
                    value = config.toneMid,
                    range = -15f..15f,
                    unit = "dB",
                    enabled = config.dspEnabled,
                    onValueChange = { viewModel.setTone(config.toneBass, it, config.toneTreble) }
                )

                PreciseSlider(
                    label = "Agudos (High-Shelf 10 kHz)",
                    value = config.toneTreble,
                    range = -15f..15f,
                    unit = "dB",
                    enabled = config.dspEnabled,
                    onValueChange = { viewModel.setTone(config.toneBass, config.toneMid, it) }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // VIRTUALIZADOR ESPACIAL
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Virtualizador / Audio Espacial",
                            style = MaterialTheme.typography.titleMedium,
                            color = AccentCyan
                        )
                        Text(
                            text = if (capabilities.hasVirtualizer) "Disponible para auriculares" else "No soportado en este dispositivo",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Switch(
                        checked = config.virtualizerEnabled,
                        onCheckedChange = { viewModel.setVirtualizer(it, config.virtualizerStrength) },
                        enabled = config.dspEnabled && capabilities.hasVirtualizer
                    )
                }

                if (config.virtualizerEnabled && capabilities.hasVirtualizer) {
                    Spacer(modifier = Modifier.height(8.dp))
                    PreciseSlider(
                        label = "Fuerza Espacial",
                        value = config.virtualizerStrength.toFloat(),
                        range = 0f..1000f,
                        unit = "/1000",
                        enabled = config.dspEnabled,
                        onValueChange = { viewModel.setVirtualizer(config.virtualizerEnabled, it.toInt()) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // MASTER GAIN Y BALANCE ESTÉREO
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Salida Maestra y Balance",
                    style = MaterialTheme.typography.titleMedium,
                    color = AccentCyan
                )
                Spacer(modifier = Modifier.height(8.dp))

                PreciseSlider(
                    label = "Ganancia Maestra (Master Gain)",
                    value = config.masterGain,
                    range = -20f..12f,
                    unit = "dB",
                    enabled = config.dspEnabled,
                    onValueChange = { viewModel.setMasterGain(it) }
                )

                PreciseSlider(
                    label = "Balance Estéreo (Izq / Der)",
                    value = config.balance,
                    range = -1.0f..1.0f,
                    unit = if (config.balance < -0.05f) "L" else if (config.balance > 0.05f) "R" else "C",
                    enabled = config.dspEnabled,
                    accentColor = AccentAmber,
                    onValueChange = { viewModel.setBalance(it) }
                )
            }
        }
    }
}
