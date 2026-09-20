package com.audiodsp.enginepro.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiodsp.enginepro.data.presets.DspPreset
import com.audiodsp.enginepro.ui.theme.DspBorder
import com.audiodsp.enginepro.ui.theme.DspPrimary
import com.audiodsp.enginepro.ui.theme.DspSurface
import com.audiodsp.enginepro.ui.theme.DspSurfaceVariant
import com.audiodsp.enginepro.ui.theme.DspTextMuted
import com.audiodsp.enginepro.ui.theme.DspTextPrimary
import com.audiodsp.enginepro.ui.theme.DspTextSecondary

@Composable
fun PresetsView(
    selectedPresetId: String,
    onPresetSelected: (DspPreset) -> Unit,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "ACOUSTIC PRESETS",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = DspTextPrimary,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "Instant 32-Band Filter Recalculation",
                    fontSize = 10.sp,
                    color = DspTextMuted
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            val scrollState = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (preset in DspPreset.ALL_PRESETS) {
                    val isSelected = preset.id == selectedPresetId

                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) DspPrimary.copy(alpha = 0.15f) else DspSurfaceVariant)
                            .border(
                                1.dp,
                                if (isSelected) DspPrimary else DspBorder,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { onPresetSelected(preset) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = preset.name,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) DspPrimary else DspTextPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = preset.description,
                            fontSize = 9.sp,
                            color = DspTextSecondary,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
