package com.audiodsp.enginepro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiodsp.enginepro.AudioDspApplication
import com.audiodsp.enginepro.audio.service.AudioDspService
import com.audiodsp.enginepro.data.preferences.DspPreferencesRepository
import com.audiodsp.enginepro.data.presets.DspPreset
import com.audiodsp.enginepro.dsp.metering.MeterProcessor
import com.audiodsp.enginepro.ui.components.ControlsView
import com.audiodsp.enginepro.ui.components.EffectsView
import com.audiodsp.enginepro.ui.components.EqualizerView
import com.audiodsp.enginepro.ui.components.MdrcView
import com.audiodsp.enginepro.ui.components.MeterView
import com.audiodsp.enginepro.ui.components.PresetsView
import com.audiodsp.enginepro.ui.theme.DspBackground
import com.audiodsp.enginepro.ui.theme.DspBorder
import com.audiodsp.enginepro.ui.theme.DspPrimary
import com.audiodsp.enginepro.ui.theme.DspSurface
import com.audiodsp.enginepro.ui.theme.DspTextMuted
import com.audiodsp.enginepro.ui.theme.DspTextPrimary
import com.audiodsp.enginepro.ui.theme.DspTextSecondary
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    onStartRequested: () -> Unit,
    onStopRequested: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember { DspPreferencesRepository(context) }
    val scope = rememberCoroutineScope()

    // Service state
    val isServiceActive by AudioDspService.isServiceActive.collectAsState()
    val serviceError by AudioDspService.serviceError.collectAsState()

    // One shared engine for UI, persistence and the audio service.
    val activeEngine = (context.applicationContext as AudioDspApplication).dspEngine

    // State triggers to force recomposition when params update
    var dspStateVersion by remember { mutableIntStateOf(0) }
    var selectedPresetId by remember { mutableStateOf("flat") }
    var selectedTab by remember { mutableIntStateOf(0) }

    // Telemetry polling at 30 fps
    var liveMeters by remember {
        mutableStateOf(
            MeterProcessor.MeterValues(
                0f, 0f, 0f, 0f,
                -96f, -96f, -96f, -96f,
                isClippingL = false, isClippingR = false
            )
        )
    }
    var isLimitingActive by remember { mutableStateOf(false) }

    LaunchedEffect(isServiceActive) {
        while (isActive) {
            val engine = AudioDspService.getInstance()?.dspEngine ?: activeEngine
            liveMeters = engine.meter.currentValues
            isLimitingActive = engine.limiter.isLimitingActive
            delay(33) // ~30 fps UI refresh rate
        }
    }

    // Save preferences helper
    val persistState: () -> Unit = {
        dspStateVersion++
        AudioDspService.getInstance()?.syncNativeEffect()
        scope.launch {
            repository.saveEngineState(activeEngine, selectedPresetId)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = DspBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // App Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "AudioDSP Engine Pro",
                        fontSize = 18.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DspTextPrimary
                    )
                    Text(
                        text = "Native Android 14 • 32-Band EQ + MDRC + Limiter",
                        fontSize = 10.sp,
                        color = DspPrimary
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(DspSurface)
                        .border(0.5.dp, DspBorder, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "API 34 NATIVE",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = DspTextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 1. Real-time Telemetry & VU Meters
            MeterView(
                meterValues = liveMeters,
                isLimitingActive = isLimitingActive
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 2. Main Controls & Master Gain
            ControlsView(
                engine = activeEngine,
                isProcessingActive = isServiceActive,
                errorMessage = serviceError,
                onStartRequested = onStartRequested,
                onStopRequested = onStopRequested,
                onBypassToggled = {
                    activeEngine.isBypassGlobal = !activeEngine.isBypassGlobal
                    persistState()
                },
                onParamChanged = persistState
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Navigation Tabs
            val tabs = listOf("32-BAND EQ", "MDRC DYNAMICS", "EFFECTS & TONE", "PRESETS")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(DspSurface)
                    .border(1.dp, DspBorder, RoundedCornerShape(8.dp))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                tabs.forEachIndexed { index, title ->
                    val isSelected = selectedTab == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) DspPrimary.copy(alpha = 0.2f) else DspSurface)
                            .border(
                                0.5.dp,
                                if (isSelected) DspPrimary else androidx.compose.ui.graphics.Color.Transparent,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { selectedTab = index }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) DspPrimary else DspTextMuted,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Tab Content
            when (selectedTab) {
                0 -> {
                    EqualizerView(
                        equalizer = activeEngine.equalizer32,
                        onBandGainChange = { bandIndex, gainDb ->
                            activeEngine.equalizer32.setBandGain(bandIndex, gainDb)
                            selectedPresetId = "custom"
                            persistState()
                        },
                        onPreGainChange = { preGain ->
                            activeEngine.equalizer32.setPreGain(preGain)
                            persistState()
                        },
                        onResetFlat = {
                            activeEngine.equalizer32.resetFlat()
                            selectedPresetId = "flat"
                            persistState()
                        }
                    )
                }
                1 -> {
                    MdrcView(
                        mdrc = activeEngine.mdrc,
                        onEnabledChange = { enabled ->
                            activeEngine.mdrc.isEnabled = enabled
                            persistState()
                        },
                        onParamChange = persistState
                    )
                }
                2 -> {
                    EffectsView(
                        engine = activeEngine,
                        onParamChanged = persistState
                    )
                }
                3 -> {
                    PresetsView(
                        selectedPresetId = selectedPresetId,
                        onPresetSelected = { preset ->
                            selectedPresetId = preset.id
                            preset.applyToEngine(activeEngine)
                            persistState()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
