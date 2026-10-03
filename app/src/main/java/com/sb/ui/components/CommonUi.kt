package com.sb.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sb.dsp.DspState
import com.sb.ui.theme.*

@Composable
fun StatusBadge(state: DspState, modifier: Modifier = Modifier) {
    val (bgColor, textColor, text) = when (state) {
        is DspState.Active -> Triple(AccentCyan.copy(alpha = 0.15f), AccentCyan, "ACTIVO (Sesión ${state.sessionId})")
        is DspState.Degraded -> Triple(AccentAmber.copy(alpha = 0.2f), AccentAmber, "DEGRADADO (Modo Compatible)")
        is DspState.Starting -> Triple(Color(0xFF2979FF).copy(alpha = 0.2f), Color(0xFF2979FF), "INICIANDO…")
        is DspState.Conflict -> Triple(AccentRed.copy(alpha = 0.2f), AccentRed, "CONFLICTO DE SESIÓN")
        is DspState.Error -> Triple(AccentRed.copy(alpha = 0.25f), AccentRed, "ERROR DE HARDWARE")
        is DspState.Off -> Triple(Color(0xFF37474F).copy(alpha = 0.3f), TextTertiary, "DESACTIVADO")
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .border(1.dp, textColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun PreciseSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String = "",
    enabled: Boolean = true,
    accentColor: Color = AccentCyan,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) TextPrimary else TextTertiary
            )
            Text(
                text = String.format("%.1f %s", value, unit),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (enabled) accentColor else TextTertiary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = DividerColor
            )
        )
    }
}
