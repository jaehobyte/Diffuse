package com.diffuse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
        // DESIGN.md §8: edge-to-edge, transparent navigation bar.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DiffuseApp(exporter = exporter, settingsStore = exportSettingsStore)
        }
    }
}

@Composable
private fun DiffuseApp(exporter: Exporter, settingsStore: ExportSettingsStore) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            DiffuseNavHost(exporter = exporter, exportSettingsStore = settingsStore)
        }
    }
}
