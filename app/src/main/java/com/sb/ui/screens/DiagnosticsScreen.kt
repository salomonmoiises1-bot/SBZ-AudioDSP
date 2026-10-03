package com.sb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sb.ui.MainViewModel
import com.sb.ui.theme.*

@Composable
fun DiagnosticsScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val diagnostics by viewModel.diagnostics.collectAsState()
    val state by viewModel.dspState.collectAsState()
    val capabilities by viewModel.dspCapabilities.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // ENCABEZADO
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "DIAGNÓSTICO TÉCNICO",
                    style = MaterialTheme.typography.titleLarge,
                    color = AccentCyan
                )
                Text(
                    text = "Auditoría de hardware y efectos de audio",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Button(
                onClick = { viewModel.triggerRecovery() },
                colors = ButtonDefaults.buttonColors(containerColor = AccentAmber, contentColor = BgDark)
            ) {
                Icon(Icons.Default.Build, contentDescription = "Reiniciar", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Recuperar DSP", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // INFORMACIÓN DEL DISPOSITIVO
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Dispositivo Android", style = MaterialTheme.typography.titleMedium, color = AccentCyan)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Fabricante / Modelo: ${diagnostics.deviceManufacturer} ${diagnostics.deviceModel}", color = TextSecondary)
                Text("Versión Android: ${diagnostics.androidVersion} (API ${diagnostics.sdkInt})", color = TextSecondary)
                Text("Estado Actual del DSP: $state", color = AccentGreen, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // CAPACIDADES DE PROCESAMIENTO DETECTADAS
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Capacidades Reales de AudioFX", style = MaterialTheme.typography.titleMedium, color = AccentCyan)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Sesión 0 Global: ${if (capabilities.isSessionZeroSupported) "Soportada" else "Restringida / No nativa"}", color = TextSecondary)
                Text("DynamicsProcessing (API 28+): ${if (capabilities.hasDynamicsProcessing) "SOPORTADO (${capabilities.mbcBandCount} bandas MBC, Limiter)" else "NO DISPONIBLE"}", color = if (capabilities.hasDynamicsProcessing) AccentGreen else AccentAmber)
                Text("Ecualizador Nativo: ${if (capabilities.hasEqualizer) "${capabilities.nativeEqBands} bandas físicas (${capabilities.nativeEqMinLevelMb/100}dB a +${capabilities.nativeEqMaxLevelMb/100}dB)" else "No disponible"}", color = TextSecondary)
                Text("BassBoost: ${if (capabilities.hasBassBoost) "Disponible" else "No soportado"}", color = TextSecondary)
                Text("Virtualizador: ${if (capabilities.hasVirtualizer) "Disponible" else "No soportado"}", color = TextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // WATCHDOG Y SALUD
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Watchdog & Recuperación Automática", style = MaterialTheme.typography.titleMedium, color = AccentCyan)
                Spacer(modifier = Modifier.height(8.dp))
                Text("Recuperaciones ejecutadas: ${diagnostics.recoveryCount}", color = TextSecondary)
                Text("Última causa registrada: ${diagnostics.lastRecoveryReason ?: "Ninguna anomalía detectada"}", color = TextSecondary)
                Text("Sesiones detectadas en sistema: ${diagnostics.activeSessions.joinToString(", ")}", color = TextSecondary)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // DESCRIPTORES DE EFECTOS DE AUDIO DEL KERNEL
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Efectos Registrados en Audio HAL (${diagnostics.effectDescriptors.size})", style = MaterialTheme.typography.titleMedium, color = AccentCyan)
                Spacer(modifier = Modifier.height(8.dp))

                if (diagnostics.effectDescriptors.isEmpty()) {
                    Text("No se detectaron efectos adicionales en el catálogo global de AudioEffect.", fontSize = 12.sp, color = TextTertiary)
                } else {
                    diagnostics.effectDescriptors.forEach { desc ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text("${desc.name} (${desc.implementor})", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text("UUID: ${desc.uuid}", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = TextTertiary)
                        }
                    }
                }
            }
        }
    }
}
