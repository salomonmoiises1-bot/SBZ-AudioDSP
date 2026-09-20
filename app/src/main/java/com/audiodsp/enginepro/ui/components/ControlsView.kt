package com.audiodsp.enginepro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiodsp.enginepro.dsp.core.AudioDspEngine
import com.audiodsp.enginepro.ui.theme.DspBorder
import com.audiodsp.enginepro.ui.theme.DspGreen
import com.audiodsp.enginepro.ui.theme.DspOrange
import com.audiodsp.enginepro.ui.theme.DspPrimary
import com.audiodsp.enginepro.ui.theme.DspRed
import com.audiodsp.enginepro.ui.theme.DspSurface
import com.audiodsp.enginepro.ui.theme.DspSurfaceVariant
import com.audiodsp.enginepro.ui.theme.DspTextMuted
import com.audiodsp.enginepro.ui.theme.DspTextPrimary
import com.audiodsp.enginepro.ui.theme.DspTextSecondary
import java.util.Locale

@Composable
fun ControlsView(
    engine: AudioDspEngine,
    isProcessingActive: Boolean,
    errorMessage: String?,
    onStartRequested: () -> Unit,
    onStopRequested: () -> Unit,
    onBypassToggled: () -> Unit,
    onParamChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DspSurface),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DspBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Engine Status Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusDotColor = when {
                        errorMessage != null -> DspRed
                        isProcessingActive -> DspGreen
                        else -> DspTextMuted
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            errorMessage != null -> "ERROR: CAPTURE FAILED"
                            isProcessingActive -> "ENGINE LIVE • 48 kHz PCM STEREO"
                            else -> "ENGINE STOPPED"
                        },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DspTextPrimary
                    )
                }

                // Global Bypass status badge
                if (engine.isBypassGlobal) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(DspOrange.copy(alpha = 0.2f))
                            .border(0.5.dp, DspOrange, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "BYPASS ACTIVE",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DspOrange
                        )
                    }
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(DspRed.copy(alpha = 0.15f))
                        .border(0.5.dp, DspRed, RoundedCornerShape(6.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = errorMessage,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFFFF8A80)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Master Control Buttons: START/STOP and BYPASS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (isProcessingActive) onStopRequested() else onStartRequested()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isProcessingActive) DspRed else DspPrimary,
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1.2f)
                        .height(44.dp)
                ) {
                    Text(
                        text = if (isProcessingActive) "STOP PROCESSING" else "START CAPTURE",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                OutlinedButton(
                    onClick = onBypassToggled,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (engine.isBypassGlobal) DspOrange else DspTextPrimary
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (engine.isBypassGlobal) DspOrange else DspBorder
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Text(
                        text = if (engine.isBypassGlobal) "BYPASS ON" else "BYPASS OFF",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Master Gain & Stereo Balance
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Master Gain
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DspSurfaceVariant)
                        .padding(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "MASTER GAIN", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = DspTextSecondary)
                        Text(
                            text = String.format(Locale.US, "%+.1f dB", engine.masterGainDb),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DspPrimary
                        )
                    }
                    Slider(
                        value = engine.masterGainDb,
                        onValueChange = {
                            engine.masterGainDb = it
                            onParamChanged()
                        },
                        valueRange = -36f..12f,
                        colors = SliderDefaults.colors(thumbColor = DspPrimary, activeTrackColor = DspPrimary, inactiveTrackColor = DspBorder),
                        modifier = Modifier.height(26.dp)
                    )
                }

                // Balance
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DspSurfaceVariant)
                        .padding(10.dp)
                ) {
                    val balText = when {
                        engine.balance < -0.05f -> String.format(Locale.US, "L %.0f%%", -engine.balance * 100f)
                        engine.balance > 0.05f -> String.format(Locale.US, "R %.0f%%", engine.balance * 100f)
                        else -> "CENTER"
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "BALANCE", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = DspTextSecondary)
                        Text(
                            text = balText,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DspPrimary
                        )
                    }
                    Slider(
                        value = engine.balance,
                        onValueChange = {
                            engine.balance = it
                            onParamChanged()
                        },
                        valueRange = -1f..1f,
                        colors = SliderDefaults.colors(thumbColor = DspPrimary, activeTrackColor = DspPrimary, inactiveTrackColor = DspBorder),
                        modifier = Modifier.height(26.dp)
                    )
                }
            }
        }
    }
}
