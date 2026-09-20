package com.audiodsp.enginepro.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiodsp.enginepro.dsp.metering.MeterProcessor
import com.audiodsp.enginepro.ui.theme.DspBorder
import com.audiodsp.enginepro.ui.theme.DspGreen
import com.audiodsp.enginepro.ui.theme.DspOrange
import com.audiodsp.enginepro.ui.theme.DspRed
import com.audiodsp.enginepro.ui.theme.DspSurface
import com.audiodsp.enginepro.ui.theme.DspSurfaceVariant
import com.audiodsp.enginepro.ui.theme.DspTextMuted
import com.audiodsp.enginepro.ui.theme.DspTextPrimary
import com.audiodsp.enginepro.ui.theme.DspTextSecondary
import com.audiodsp.enginepro.ui.theme.DspYellow
import java.util.Locale

@Composable
fun MeterView(
    meterValues: MeterProcessor.MeterValues,
    isLimitingActive: Boolean,
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
            // Header with status LEDs
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "REAL-TIME TELEMETRY",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = DspTextSecondary,
                    letterSpacing = 1.sp
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Limiter LED
                    StatusLed(
                        label = "LIMIT",
                        isActive = isLimitingActive,
                        activeColor = DspOrange
                    )

                    // Clip L LED
                    StatusLed(
                        label = "CLIP L",
                        isActive = meterValues.isClippingL,
                        activeColor = DspRed
                    )

                    // Clip R LED
                    StatusLed(
                        label = "CLIP R",
                        isActive = meterValues.isClippingR,
                        activeColor = DspRed
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Stereo Meters (Left & Right)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ChannelMeterBar(
                    channelLabel = "L",
                    peak = meterValues.peakL,
                    rms = meterValues.rmsL,
                    peakDb = meterValues.peakDbL,
                    rmsDb = meterValues.rmsDbL,
                    modifier = Modifier.weight(1f)
                )

                ChannelMeterBar(
                    channelLabel = "R",
                    peak = meterValues.peakR,
                    rms = meterValues.rmsR,
                    peakDb = meterValues.peakDbR,
                    rmsDb = meterValues.rmsDbR,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun ChannelMeterBar(
    channelLabel: String,
    peak: Float,
    rms: Float,
    peakDb: Float,
    rmsDb: Float,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CH $channelLabel",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = DspTextPrimary
            )
            Text(
                text = String.format(Locale.US, "PK: %+.1f dB | RMS: %+.1f dB", peakDb, rmsDb),
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = DspTextMuted
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Dual bar: Outer box is background track, inner bars show RMS and Peak
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(DspSurfaceVariant)
                .border(0.5.dp, DspBorder, RoundedCornerShape(3.dp))
        ) {
            // RMS Level (Solid block)
            val clampedRms = rms.coerceIn(0f, 1f)
            val rmsColor = when {
                clampedRms > 0.95f -> DspRed
                clampedRms > 0.80f -> DspYellow
                else -> DspGreen
            }
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction = clampedRms.coerceAtLeast(0.01f))
                    .clip(RoundedCornerShape(2.dp))
                    .background(rmsColor)
            )

            // Peak Indicator Needle
            val clampedPeak = peak.coerceIn(0f, 1f)
            if (clampedPeak > 0.02f) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = clampedPeak)
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(2.5.dp)
                            .fillMaxHeight()
                            .background(if (clampedPeak >= 0.99f) DspRed else Color.White)
                    )
                }
            }
        }
    }
}

@Composable
fun StatusLed(
    label: String,
    isActive: Boolean,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    val ledColor by animateColorAsState(
        targetValue = if (isActive) activeColor else DspSurfaceVariant,
        animationSpec = tween(durationMillis = 80),
        label = "ledColor"
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(ledColor)
                .border(0.5.dp, if (isActive) activeColor else DspBorder, CircleShape)
        )
        Text(
            text = label,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (isActive) DspTextPrimary else DspTextMuted
        )
    }
}
