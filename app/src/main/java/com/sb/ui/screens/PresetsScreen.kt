package com.sb.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sb.data.Preset
import com.sb.ui.MainViewModel
import com.sb.ui.theme.*

@Composable
fun PresetsScreen(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    val presets by viewModel.presets.collectAsState()
    var showSaveDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(16.dp)
    ) {
        // ENCABEZADO
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "PRESETS Y PERFILES",
                    style = MaterialTheme.typography.titleLarge,
                    color = AccentCyan
                )
                Text(
                    text = "Configuración integral del DSP (EQ32 + MDRC + Preamplificador)",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Button(
                onClick = { showSaveDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgDark)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Guardar", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Guardar Preset Actual", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // LISTA DE PRESETS
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(presets, key = { it.id }) { preset ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                if (preset.isFactory) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(AccentAmber.copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("FÁBRICA", fontSize = 10.sp, color = AccentAmber, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(AccentCyan.copy(alpha = 0.2f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("USUARIO", fontSize = 10.sp, color = AccentCyan, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "MDRC: ${if (preset.config.mdrcEnabled) "ACTIVO" else "BYPASS"} | Modo: ${preset.config.eqMode.name} | Pre: ${preset.config.preGain}dB | Limiter: ${if (preset.config.limiterEnabled) "ON" else "OFF"}",
                                fontSize = 12.sp,
                                color = TextTertiary
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                onClick = { viewModel.loadPreset(preset) },
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated, contentColor = AccentCyan)
                            ) {
                                Text("Cargar")
                            }

                            if (!preset.isFactory) {
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = { viewModel.deletePreset(preset.id) }
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Eliminar", tint = AccentRed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // DIÁLOGO GUARDAR PRESET
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Guardar Configuración Completa", color = AccentCyan) },
            text = {
                Column {
                    Text("Guarda un preset con TODOS los ajustes actuales: Preamplificador, Bass Boost, Tono, EQ32, los 4 compresores MDRC, Crossovers, Headroom y Limitador:")
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newPresetName,
                        onValueChange = { newPresetName = it },
                        label = { Text("Nombre del Preset") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPresetName.isNotBlank()) {
                            viewModel.saveNewPreset(newPresetName.trim())
                            newPresetName = ""
                            showSaveDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = BgDark)
                ) {
                    Text("Guardar Preset")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancelar", color = TextSecondary)
                }
            },
            containerColor = SurfaceDark
        )
    }
}
