package com.audiodsp.enginepro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiodsp.enginepro.dsp.dynamics.MdrcProcessor
import com.audiodsp.enginepro.ui.theme.DspBorder
import com.audiodsp.enginepro.ui.theme.DspOrange
import com.audiodsp.enginepro.ui.theme.DspPrimary
import com.audiodsp.enginepro.ui.theme.DspSurface
import com.audiodsp.enginepro.ui.theme.DspSurfaceVariant
import com.audiodsp.enginepro.ui.theme.DspTextMuted
import com.audiodsp.enginepro.ui.theme.DspTextPrimary
import com.audiodsp.enginepro.ui.theme.DspTextSecondary
import java.util.Locale

@Composable
fun MdrcView(
    mdrc: MdrcProcessor,
    onEnabledChange: (Boolean) -> Unit,
    onParamChange: () -> Unit,
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
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "MDRC • MULTI-BAND COMPRESSOR",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DspTextPrimary,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "3-Band Crossover Dynamic Envelope Leveling (Low < 250Hz, Mid, High > 3.5kHz)",
                        fontSize = 10.sp,
                        color = DspTextMuted
                    )
                }

                Switch(
                    checked = mdrc.isEnabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = DspPrimary,
                        checkedTrackColor = DspSurfaceVariant,
                        uncheckedThumbColor = DspTextMuted,
                        uncheckedTrackColor = DspSurfaceVariant
                    )
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3 Compression Bands: Low, Mid, High
            BandCompressorCard(
                bandTitle = "LOW BAND (< 250 Hz)",
                params = mdrc.lowParams,
                gainReductionDb = mdrc.lowGainReductionDb,
                onParamChange = onParamChange
            )

            Spacer(modifier = Modifier.height(10.dp))

            BandCompressorCard(
                bandTitle = "MID BAND (250 Hz - 3.5 kHz)",
                params = mdrc.midParams,
                gainReductionDb = mdrc.midGainReductionDb,
                onParamChange = onParamChange
            )

            Spacer(modifier = Modifier.height(10.dp))

            BandCompressorCard(
                bandTitle = "HIGH BAND (> 3.5 kHz)",
                params = mdrc.highParams,
                gainReductionDb = mdrc.highGainReductionDb,
                onParamChange = onParamChange
            )
        }
    }
}

@Composable
fun BandCompressorCard(
    bandTitle: String,
    params: MdrcProcessor.BandParams,
    gainReductionDb: Float,
    onParamChange: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DspSurfaceVariant)
            .border(0.5.dp, DspBorder, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = bandTitle,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = DspTextPrimary
            )

            // Gain reduction readout & meter
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = String.format(Locale.US, "GR: %.1f dB", gainReductionDb),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (gainReductionDb < -0.5f) DspOrange else DspTextMuted
                )
                Spacer(modifier = Modifier.width(6.dp))
                // Tiny GR bar
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(DspSurface)
                ) {
                    val fraction = (-gainReductionDb / 18.0f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction)
                            .background(DspOrange)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Threshold and Ratio
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CompactParamSlider(
                label = "Thresh",
                valueStr = String.format(Locale.US, "%.0f dB", params.thresholdDb),
                value = params.thresholdDb,
                valueRange = -40f..0f,
                onValueChange = {
                    params.thresholdDb = it
                    onParamChange()
                },
                modifier = Modifier.weight(1f)
            )

            CompactParamSlider(
                label = "Ratio",
                valueStr = String.format(Locale.US, "%.1f:1", params.ratio),
                value = params.ratio,
                valueRange = 1f..16f,
                onValueChange = {
                    params.ratio = it
                    onParamChange()
                },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Attack, Release, Makeup
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CompactParamSlider(
                label = "Att",
                valueStr = String.format(Locale.US, "%.0fms", params.attackMs),
                value = params.attackMs,
                valueRange = 1f..100f,
                onValueChange = {
                    params.attackMs = it
                    onParamChange()
                },
                modifier = Modifier.weight(1f)
            )

            CompactParamSlider(
                label = "Rel",
                valueStr = String.format(Locale.US, "%.0fms", params.releaseMs),
                value = params.releaseMs,
                valueRange = 20f..500f,
                onValueChange = {
                    params.releaseMs = it
                    onParamChange()
                },
                modifier = Modifier.weight(1f)
            )

            CompactParamSlider(
                label = "Gain",
                valueStr = String.format(Locale.US, "%+.1fdB", params.makeupGainDb),
                value = params.makeupGainDb,
                valueRange = 0f..12f,
                onValueChange = {
                    params.makeupGainDb = it
                    onParamChange()
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun CompactParamSlider(
    label: String,
    valueStr: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = DspTextSecondary)
            Text(text = valueStr, fontSize = 9.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = DspPrimary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = DspPrimary,
                activeTrackColor = DspPrimary,
                inactiveTrackColor = DspBorder
            ),
            modifier = Modifier.height(22.dp)
        )
    }
}
