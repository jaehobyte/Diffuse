package com.diffuse

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.diffuse.core.ui.theme.AppTheme
import com.diffuse.core.ui.theme.ThemeMode
import com.diffuse.feature.export.ExportSettingsStore
import com.diffuse.feature.export.Exporter
import com.diffuse.navigation.DiffuseNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var exporter: Exporter

    @Inject
    lateinit var exportSettingsStore: ExportSettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        // DESIGN.md §8: edge-to-edge with transparent bars. Both screens put a light bar under
        // the status bar and a light surface under the navigation bar, so the icons are dark —
        // fixed, not following the OS dark setting, because the app's own style does not.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            DiffuseApp(exporter = exporter, settingsStore = exportSettingsStore)
        }
    }
}

@Composable
private fun DiffuseApp(exporter: Exporter, settingsStore: ExportSettingsStore) {
    // Anything drawn outside a screen's own AppTheme — the export snackbar and dialog — still
    // gets the app's palette and type rather than Material's defaults.
    AppTheme(mode = ThemeMode.Browse) {
        Surface(modifier = Modifier.fillMaxSize()) {
            DiffuseNavHost(exporter = exporter, exportSettingsStore = settingsStore)
        }
    }
}
