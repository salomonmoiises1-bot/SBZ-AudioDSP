package com.sb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sb.data.PresetRepository
import com.sb.dsp.MdrcBandConfig
import com.sb.ui.MainViewModel
import com.sb.ui.components.PreciseSlider
import com.sb.ui.theme.*

@Composable
fun DynamicsScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val config by viewModel.dspConfig.collectAsState()

    var selectedBandIndex by remember { mutableStateOf(0) }
    val bandNames = listOf("LOW", "LOW-MID", "MID-HIGH", "HIGH")
    val bandRanges = listOf(
        "0 Hz → ${config.mdrcCutoff1.toInt()} Hz",
        "${config.mdrcCutoff1.toInt()} Hz → ${config.mdrcCutoff2.toInt()} Hz",
        "${config.mdrcCutoff2.toInt()} Hz → ${config.mdrcCutoff3.toInt()} Hz",
        "${config.mdrcCutoff3.toInt()} Hz → 20 kHz"
    )

    val currentBand = when (selectedBandIndex) {
        0 -> config.mdrcBand1
        1 -> config.mdrcBand2
        2 -> config.mdrcBand3
        3 -> config.mdrcBand4
        else -> config.mdrcBand1
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // ENCABEZADO MDRC
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "MDRC — COMPRESIÓN MULTIBANDA",
                    style = MaterialTheme.typography.titleLarge,
                    color = AccentCyan
                )
                Text(
                    text = "Procesamiento PCM en 4 bandas Linkwitz-Riley LR4",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Switch(
                checked = config.mdrcEnabled,
                onCheckedChange = { viewModel.setMdrcEnabled(it) },
                enabled = config.dspEnabled,
                colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ACCESO RÁPIDO A PRESETS INICIALES DE MDRC
        Text("Presets de Fábrica (MDRC + EQ + Limiter):", fontSize = 12.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PresetRepository.FACTORY_PRESETS.take(4).forEach { preset ->
                Button(
                    onClick = { viewModel.loadPreset(preset) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated, contentColor = TextPrimary),
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                ) {
                    Text(preset.name.split(" ")[0], fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SELECTOR DE PESTAÑAS PARA LAS 4 BANDAS
        TabRow(
            selectedTabIndex = selectedBandIndex,
            containerColor = SurfaceDark,
            contentColor = AccentCyan
        ) {
            bandNames.forEachIndexed { index, name ->
                Tab(
                    selected = selectedBandIndex == index,
                    onClick = { selectedBandIndex = index },
                    text = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(name, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Text(bandRanges[index], fontSize = 9.sp, color = TextTertiary)
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // PARÁMETROS DINÁMICOS DE LA BANDA SELECCIONADA
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
                            text = "Banda ${bandNames[selectedBandIndex]} (${bandRanges[selectedBandIndex]})",
                            style = MaterialTheme.typography.titleMedium,
                            color = AccentCyan
                        )
                        Text(
                            text = if (currentBand.enabled) "Compresión dinámica activa" else "Banda en bypass (Lineal)",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    Switch(
                        checked = currentBand.enabled,
                        onCheckedChange = {
                            viewModel.setMdrcBand(selectedBandIndex, currentBand.copy(enabled = it))
                        },
                        enabled = config.dspEnabled && config.mdrcEnabled
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Threshold
                PreciseSlider(
                    label = "Umbral (Threshold)",
                    value = currentBand.threshold,
                    range = -60f..0f,
                    unit = "dB",
                    enabled = config.dspEnabled && config.mdrcEnabled && currentBand.enabled,
                    onValueChange = {
                        viewModel.setMdrcBand(selectedBandIndex, currentBand.copy(threshold = it))
                    }
                )

                // Ratio
                PreciseSlider(
                    label = "Relación (Ratio)",
                    value = currentBand.ratio,
                    range = 1f..20f,
                    unit = ":1",
                    enabled = config.dspEnabled && config.mdrcEnabled && currentBand.enabled,
                    onValueChange = {
                        viewModel.setMdrcBand(selectedBandIndex, currentBand.copy(ratio = it))
                    }
                )

                // Attack
                PreciseSlider(
                    label = "Ataque (Attack)",
                    value = currentBand.attackTime,
                    range = 0.5f..200f,
                    unit = "ms",
                    enabled = config.dspEnabled && config.mdrcEnabled && currentBand.enabled,
                    onValueChange = {
                        viewModel.setMdrcBand(selectedBandIndex, currentBand.copy(attackTime = it))
                    }
                )

                // Release
                PreciseSlider(
                    label = "Relajación (Release)",
                    value = currentBand.releaseTime,
                    range = 5f..800f,
                    unit = "ms",
                    enabled = config.dspEnabled && config.mdrcEnabled && currentBand.enabled,
                    onValueChange = {
                        viewModel.setMdrcBand(selectedBandIndex, currentBand.copy(releaseTime = it))
                    }
                )

                // Soft Knee
                PreciseSlider(
                    label = "Codo Suave (Soft Knee)",
                    value = currentBand.kneeWidth,
                    range = 0f..24f,
                    unit = "dB",
                    enabled = config.dspEnabled && config.mdrcEnabled && currentBand.enabled,
                    onValueChange = {
                        viewModel.setMdrcBand(selectedBandIndex, currentBand.copy(kneeWidth = it))
                    }
                )

                // Makeup Gain (Post-Gain)
                PreciseSlider(
                    label = "Ganancia de Compensación (Makeup Gain)",
                    value = currentBand.postGain,
                    range = -15f..15f,
                    unit = "dB",
                    enabled = config.dspEnabled && config.mdrcEnabled && currentBand.enabled,
                    accentColor = AccentAmber,
                    onValueChange = {
                        viewModel.setMdrcBand(selectedBandIndex, currentBand.copy(postGain = it))
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // CONTROL DE CROSSOVERS DIGITALES
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Filtros de Cruce (Crossovers LR4)",
                    style = MaterialTheme.typography.titleMedium,
                    color = AccentCyan
                )
                Text(
                    text = "Divisores Linkwitz-Riley 24 dB/octava con coherencia de fase perfecta",
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(modifier = Modifier.height(10.dp))

                PreciseSlider(
                    label = "Crossover 1 (LOW → LOW-MID)",
                    value = config.mdrcCutoff1,
                    range = 40f..400f,
                    unit = "Hz",
                    enabled = config.dspEnabled && config.mdrcEnabled,
                    onValueChange = {
                        viewModel.setMdrcCutoffs(it, config.mdrcCutoff2, config.mdrcCutoff3, config.mdrcCutoff4)
                    }
                )

                PreciseSlider(
                    label = "Crossover 2 (LOW-MID → MID-HIGH)",
                    value = config.mdrcCutoff2,
                    range = 400f..2500f,
                    unit = "Hz",
                    enabled = config.dspEnabled && config.mdrcEnabled,
                    onValueChange = {
                        viewModel.setMdrcCutoffs(config.mdrcCutoff1, it, config.mdrcCutoff3, config.mdrcCutoff4)
                    }
                )

                PreciseSlider(
                    label = "Crossover 3 (MID-HIGH → HIGH)",
                    value = config.mdrcCutoff3,
                    range = 2500f..10000f,
                    unit = "Hz",
                    enabled = config.dspEnabled && config.mdrcEnabled,
                    onValueChange = {
                        viewModel.setMdrcCutoffs(config.mdrcCutoff1, config.mdrcCutoff2, it, config.mdrcCutoff4)
                    }
                )
            }
        }
    }
}
