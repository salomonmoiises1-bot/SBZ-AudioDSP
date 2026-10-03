package com.sb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sb.dsp.CapabilityAdapter
import com.sb.ui.MainViewModel
import com.sb.ui.theme.*

@Composable
fun EqualizerScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val config by viewModel.dspConfig.collectAsState()
    val capabilities by viewModel.dspCapabilities.collectAsState()

    val frequencies = config.activeEqFrequencies()
    val gains = config.activeEqGains()

    val auditDescription = CapabilityAdapter.getMappingAuditDescription(capabilities)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // ENCABEZADO Y SELECTOR DE MODOS (10 / 20 / 32 BANDAS)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "ECUALIZADOR GRÁFICO",
                    style = MaterialTheme.typography.titleLarge,
                    color = AccentCyan
                )
                Text(
                    text = "Ecualizador EQ32 · 32 bandas",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            OutlinedButton(
                onClick = { viewModel.resetEqGains() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentAmber)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Aplanar", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Aplanar", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // EQ32 fijo: 32 bandas reales aplicadas 1:1 al Post-EQ de DynamicsProcessing.

        Spacer(modifier = Modifier.height(12.dp))

        // PANEL DE AUDITORÍA Y TRANSPARENCIA HARDWARE
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Auditoría de Enrutamiento DSP",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = AccentAmber
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = auditDescription,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // RACK DE BANDAS CON FADERS PRECISOS
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Bandas Espectrales Activas (${frequencies.size} frecuencias)",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Lista de faders lineales
                for (i in frequencies.indices) {
                    val freq = frequencies[i]
                    val gain = if (i < gains.size) gains[i] else 0f
                    val freqLabel = if (freq >= 1000f) "${(freq / 1000f).toInt()} kHz" else "${freq.toInt()} Hz"

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = freqLabel,
                            modifier = Modifier.width(68.dp),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextSecondary
                        )

                        Slider(
                            value = gain,
                            onValueChange = { viewModel.setEqGain(i, it) },
                            valueRange = -15f..15f,
                            enabled = config.dspEnabled,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = AccentCyan,
                                activeTrackColor = AccentCyan,
                                inactiveTrackColor = DividerColor
                            )
                        )

                        Text(
                            text = String.format("%+.1f dB", gain),
                            modifier = Modifier.width(62.dp),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (gain > 0f) AccentCyan else if (gain < 0f) AccentAmber else TextTertiary
                        )
                    }
                }
            }
        }
    }
}
