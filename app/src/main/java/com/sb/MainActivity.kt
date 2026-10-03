package com.sb

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.sb.audio.SbAudioService
import com.sb.ui.MainViewModel
import com.sb.ui.screens.*
import com.sb.ui.theme.AccentCyan
import com.sb.ui.theme.BgDark
import com.sb.ui.theme.SBTheme
import com.sb.ui.theme.SurfaceDark

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Main : Screen("main", "Principal", Icons.Default.Tune)
    object Eq : Screen("eq", "EQ", Icons.Default.GraphicEq)
    object Dynamics : Screen("dynamics", "Dinámica", Icons.Default.Compress)
    object Presets : Screen("presets", "Presets", Icons.Default.Bookmarks)
    object Diagnostics : Screen("diagnostics", "Diagnóstico", Icons.Default.Info)
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Iniciar servicio en primer plano persistente
        SbAudioService.startService(this)

        setContent {
            SBTheme {
                val screens = listOf(
                    Screen.Main,
                    Screen.Eq,
                    Screen.Dynamics,
                    Screen.Presets,
                    Screen.Diagnostics
                )
                var currentScreen by remember { mutableStateOf<Screen>(Screen.Main) }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = BgDark,
                    bottomBar = {
                        NavigationBar(
                            containerColor = SurfaceDark,
                            contentColor = AccentCyan
                        ) {
                            screens.forEach { screen ->
                                NavigationBarItem(
                                    icon = { Icon(screen.icon, contentDescription = screen.title) },
                                    label = { Text(screen.title) },
                                    selected = currentScreen == screen,
                                    onClick = { currentScreen = screen },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = AccentCyan,
                                        selectedTextColor = AccentCyan,
                                        indicatorColor = SurfaceDark
                                    )
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    when (currentScreen) {
                        Screen.Main -> MainDspScreen(viewModel, Modifier.padding(innerPadding))
                        Screen.Eq -> EqualizerScreen(viewModel, Modifier.padding(innerPadding))
                        Screen.Dynamics -> DynamicsScreen(viewModel, Modifier.padding(innerPadding))
                        Screen.Presets -> PresetsScreen(viewModel, Modifier.padding(innerPadding))
                        Screen.Diagnostics -> DiagnosticsScreen(viewModel, Modifier.padding(innerPadding))
                    }
                }
            }
        }
    }
}
