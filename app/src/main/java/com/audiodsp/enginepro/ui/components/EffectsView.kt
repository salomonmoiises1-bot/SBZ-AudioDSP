package com.audiodsp.enginepro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.audiodsp.enginepro.dsp.core.AudioDspEngine
import com.audiodsp.enginepro.ui.theme.DspBorder
import com.audiodsp.enginepro.ui.theme.DspPrimary
import com.audiodsp.enginepro.ui.theme.DspSurface
import com.audiodsp.enginepro.ui.theme.DspSurfaceVariant
import com.audiodsp.enginepro.ui.theme.DspTextMuted
import com.audiodsp.enginepro.ui.theme.DspTextPrimary
import com.audiodsp.enginepro.ui.theme.DspTextSecondary
import java.util.Locale

@Composable
fun EffectsView(
    engine: AudioDspEngine,
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
            Text(
                text = "DSP EFFECTS & ACOUSTIC SHAPING",
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = DspTextPrimary,
                letterSpacing = 0.5.sp
            )
            Text(
                text = "Sub-bass resonance, 3-band tone stack, Haas spatializer & brickwall limiter",
                fontSize = 10.sp,
                color = DspTextMuted
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 1. Bass Boost Card
            EffectControlCard(
                title = "BASS BOOST",
                badge = String.format(Locale.US, "+%.1f dB", engine.bassBoost.strengthDb),
                subtitle = "Sub-bass resonant shelf at 80 Hz with soft harmonic warmth",
                value = engine.bassBoost.strengthDb,
                valueRange = 0f..12f,
                onValueChange = {
                    engine.bassBoost.setStrength(it)
                    onParamChanged()
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 2. 3-Band Tone Stack Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(DspSurfaceVariant)
                    .border(0.5.dp, DspBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "TONE STACK",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DspTextPrimary
                    )
                    Text(
                        text = "Bass (150Hz) • Mid (1kHz) • Treble (6kHz)",
                        fontSize = 9.sp,
                        color = DspTextMuted
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CompactParamSlider(
                        label = "Bass",
                        valueStr = String.format(Locale.US, "%+.1fdB", engine.tone.bassDb),
                        value = engine.tone.bassDb,
                        valueRange = -12f..12f,
                        onValueChange = {
                            engine.tone.setBass(it)
                            onParamChanged()
                        },
                        modifier = Modifier.weight(1f)
                    )

                    CompactParamSlider(
                        label = "Mid",
                        valueStr = String.format(Locale.US, "%+.1fdB", engine.tone.midDb),
                        value = engine.tone.midDb,
                        valueRange = -12f..12f,
                        onValueChange = {
                            engine.tone.setMid(it)
                            onParamChanged()
                        },
                        modifier = Modifier.weight(1f)
                    )

                    CompactParamSlider(
                        label = "Treble",
                        valueStr = String.format(Locale.US, "%+.1fdB", engine.tone.trebleDb),
                        value = engine.tone.trebleDb,
                        valueRange = -12f..12f,
                        onValueChange = {
                            engine.tone.setTreble(it)
                            onParamChanged()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 3. Stereo Virtualizer
            EffectControlCard(
                title = "STEREO VIRTUALIZER",
                badge = String.format(Locale.US, "%.0f %%", engine.virtualizer.strength * 100f),
                subtitle = "Mid/Side spatializer matrix with Haas inter-aural cross-feed delay",
                value = engine.virtualizer.strength,
                valueRange = 0f..1f,
                onValueChange = {
                    engine.virtualizer.setStrength(it)
                    onParamChanged()
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 4. AutoGain (AGC) Card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(DspSurfaceVariant)
                    .border(0.5.dp, DspBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "AUTOGAIN • AGC LEVELER",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = DspTextPrimary
                        )
                        Text(
                            text = "Temporal RMS tracking with anti-pumping silence gate",
                            fontSize = 9.sp,
                            color = DspTextMuted
                        )
                    }

                    Switch(
                        checked = engine.autoGain.isEnabled,
                        onCheckedChange = {
                            engine.autoGain.isEnabled = it
                            onParamChanged()
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = DspPrimary,
                            checkedTrackColor = DspSurfaceVariant,
                            uncheckedThumbColor = DspTextMuted,
                            uncheckedTrackColor = DspSurfaceVariant
                        )
                    )
                }

                if (engine.autoGain.isEnabled) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CompactParamSlider(
                            label = "Target",
                            valueStr = String.format(Locale.US, "%.0f dBFS", engine.autoGain.targetLevelDb),
                            value = engine.autoGain.targetLevelDb,
                            valueRange = -24f..-10f,
                            onValueChange = {
                                engine.autoGain.targetLevelDb = it
                                onParamChanged()
                            },
                            modifier = Modifier.weight(1f)
                        )

                        CompactParamSlider(
                            label = "Max Boost",
                            valueStr = String.format(Locale.US, "+%.0f dB", engine.autoGain.maxBoostDb),
                            value = engine.autoGain.maxBoostDb,
                            valueRange = 0f..12f,
                            onValueChange = {
                                engine.autoGain.maxBoostDb = it
                                onParamChanged()
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 5. Limiter Card
            EffectControlCard(
                title = "BRICKWALL LIMITER",
                badge = String.format(Locale.US, "%.1f dBFS", engine.limiter.ceilingDb),
                subtitle = "Sub-millisecond peak ceiling prevention against DAC digital distortion",
                value = engine.limiter.ceilingDb,
                valueRange = -3.0f..0.0f,
                onValueChange = {
                    engine.limiter.ceilingDb = it
                    onParamChanged()
                }
            )
        }
    }
}

@Composable
fun EffectControlCard(
    title: String,
    badge: String,
    subtitle: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(DspSurfaceVariant)
            .border(0.5.dp, DspBorder, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = title,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = DspTextPrimary
                )
                Text(
                    text = subtitle,
                    fontSize = 9.sp,
                    color = DspTextMuted
                )
            }
            Text(
                text = badge,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = DspPrimary
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = DspPrimary,
                activeTrackColor = DspPrimary,
                inactiveTrackColor = DspBorder
            ),
            modifier = Modifier.height(24.dp)
        )
    }
}
