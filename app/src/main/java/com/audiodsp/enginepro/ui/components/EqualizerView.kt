package com.audiodsp.enginepro.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiodsp.enginepro.dsp.equalizer.Equalizer32Band
import com.audiodsp.enginepro.ui.theme.DspBorder
import com.audiodsp.enginepro.ui.theme.DspPrimary
import com.audiodsp.enginepro.ui.theme.DspSurface
import com.audiodsp.enginepro.ui.theme.DspSurfaceVariant
import com.audiodsp.enginepro.ui.theme.DspTextMuted
import com.audiodsp.enginepro.ui.theme.DspTextPrimary
import com.audiodsp.enginepro.ui.theme.DspTextSecondary
import java.util.Locale
import kotlin.math.log10

@Composable
fun EqualizerView(
    equalizer: Equalizer32Band,
    onBandGainChange: (Int, Float) -> Unit,
    onPreGainChange: (Float) -> Unit,
    onResetFlat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DspSurface),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, DspBorder)
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
                        text = "32-BAND ISO GRAPHIC EQUALIZER",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DspTextPrimary,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Cascaded Biquad Filters (RBJ EQ Cookbook) • ±15.0 dB @ 0.5 dB step",
                        fontSize = 10.sp,
                        color = DspTextMuted
                    )
                }

                OutlinedButton(
                    onClick = onResetFlat,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DspPrimary),
                    border = BorderStroke(1.dp, DspBorder),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(text = "RESET FLAT", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Real Mathematical Frequency Response Curve
            FrequencyResponseCurve(
                equalizer = equalizer,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Pre-Gain Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(DspSurfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "PRE-GAIN",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = DspTextSecondary
                )
                Text(
                    text = String.format(Locale.US, "%+.1f dB", equalizer.preGainDb),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = DspPrimary
                )
                Slider(
                    value = equalizer.preGainDb,
                    onValueChange = onPreGainChange,
                    valueRange = -12f..12f,
                    steps = 47, // 0.5 dB steps
                    colors = SliderDefaults.colors(
                        thumbColor = DspPrimary,
                        activeTrackColor = DspPrimary,
                        inactiveTrackColor = DspBorder
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Scrollable 32-Band Faders Rack
            val scrollState = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (bandIdx in 0 until Equalizer32Band.BAND_COUNT) {
                    val freq = Equalizer32Band.FREQUENCIES[bandIdx]
                    val gain = equalizer.getBandGain(bandIdx)

                    BandFaderColumn(
                        bandIndex = bandIdx,
                        frequency = freq,
                        gainDb = gain,
                        onGainChange = { newGain -> onBandGainChange(bandIdx, newGain) },
                        onResetBand = { onBandGainChange(bandIdx, 0f) }
                    )
                }
            }
        }
    }
}

@Composable
fun BandFaderColumn(
    bandIndex: Int,
    frequency: Float,
    gainDb: Float,
    onGainChange: (Float) -> Unit,
    onResetBand: () -> Unit,
    modifier: Modifier = Modifier
) {
    val freqLabel = formatFrequency(frequency)

    Column(
        modifier = modifier
            .width(46.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(DspSurfaceVariant)
            .padding(vertical = 6.dp, horizontal = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Gain dB readout
        Text(
            text = String.format(Locale.US, "%+.1f", gainDb),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (gainDb != 0f) DspPrimary else DspTextMuted
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Vertical Fader represented via vertical slider / track
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(120.dp),
            contentAlignment = Alignment.Center
        ) {
            // Center 0 dB marker line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(DspBorder)
            )

            // Rotated slider for vertical orientation
            Slider(
                value = gainDb,
                onValueChange = onGainChange,
                valueRange = Equalizer32Band.MIN_GAIN_DB..Equalizer32Band.MAX_GAIN_DB,
                steps = 59, // 30 dB range / 0.5 = 60 steps -> 59 subdivisions
                colors = SliderDefaults.colors(
                    thumbColor = if (gainDb != 0f) DspPrimary else DspTextSecondary,
                    activeTrackColor = DspPrimary,
                    inactiveTrackColor = DspBorder
                ),
                modifier = Modifier
                    .width(120.dp)
                    .height(28.dp)
                    .rotate(-90f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Reset individual band on click
        OutlinedButton(
            onClick = onResetBand,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .width(36.dp)
                .height(18.dp),
            border = BorderStroke(0.5.dp, DspBorder),
            shape = RoundedCornerShape(3.dp)
        ) {
            Text(text = "0", fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = DspTextMuted)
        }

        Spacer(modifier = Modifier.height(3.dp))

        // Frequency label
        Text(
            text = freqLabel,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            color = DspTextSecondary
        )
    }
}

@Composable
fun FrequencyResponseCurve(
    equalizer: Equalizer32Band,
    modifier: Modifier = Modifier
) {
    // Generate test logarithmic frequencies from 20 Hz to 20 kHz
    val pointsCount = 64
    val testFreqs = remember {
        val freqs = FloatArray(pointsCount)
        val minLog = log10(20.0)
        val maxLog = log10(20000.0)
        for (i in 0 until pointsCount) {
            val ratio = i.toDouble() / (pointsCount - 1)
            freqs[i] = Math.pow(10.0, minLog + ratio * (maxLog - minLog)).toFloat()
        }
        freqs
    }

    val magResponse = remember(
        equalizer.preGainDb,
        equalizer.gainsDb.contentHashCode()
    ) {
        equalizer.calculateMagnitudeResponse(testFreqs)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(DspSurfaceVariant)
            .border(0.5.dp, DspBorder, RoundedCornerShape(6.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            // Grid lines: 0 dB center line, +12 dB, -12 dB
            val zeroY = h / 2f
            val plus12Y = h * 0.15f
            val minus12Y = h * 0.85f

            drawLine(DspBorder, Offset(0f, plus12Y), Offset(w, plus12Y), strokeWidth = 0.8f)
            drawLine(Color(0xFF37474F), Offset(0f, zeroY), Offset(w, zeroY), strokeWidth = 1.2f)
            drawLine(DspBorder, Offset(0f, minus12Y), Offset(w, minus12Y), strokeWidth = 0.8f)

            // Draw real curve path
            val path = Path()
            val dbRange = 36f // -18 dB to +18 dB

            for (i in 0 until pointsCount) {
                val x = (i.toFloat() / (pointsCount - 1)) * w
                val db = magResponse[i].coerceIn(-18f, 18f)
                // Map dB: +18 dB -> y=0, 0 dB -> y=h/2, -18 dB -> y=h
                val y = zeroY - (db / (dbRange / 2f)) * (h * 0.42f)

                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            drawPath(
                path = path,
                color = DspPrimary,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

private fun formatFrequency(f: Float): String {
    return when {
        f >= 1000f -> {
            val k = f / 1000f
            if (k % 1f == 0f) "${k.toInt()}k" else String.format(Locale.US, "%.1fk", k)
        }
        f % 1f == 0f -> "${f.toInt()}"
        else -> String.format(Locale.US, "%.1f", f)
    }
}
