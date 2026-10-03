package com.sb

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
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

        // Solicitar la única autorización de ejecución que Android 13+
        // presenta al usuario: notificaciones.
        // MODIFY_AUDIO_SETTINGS es un permiso normal: Android lo concede
        // automáticamente al instalar y no muestra diálogo runtime.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }

        // Iniciar servicio en primer plano persistente después de registrar la
        // solicitud de permiso. El servicio mantiene el DSP activo.
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
