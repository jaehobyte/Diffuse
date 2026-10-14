package com.diffuse.feature.editor.tools.select

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.diffuse.core.ai.sam3.Sam3Config
import com.diffuse.core.ui.components.EditSheet
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R

const val Sam3SettingsSheetTestTag = "Sam3SettingsSheet"
const val Sam3BaseUrlFieldTestTag = "Sam3BaseUrl"
const val Sam3TokenFieldTestTag = "Sam3Token"
const val GeminiKeyFieldTestTag = "GeminiKey"
const val TopazKeyFieldTestTag = "TopazKey"

/**
 * specs/segmentation.md §6 and generative_erase.md §8. One 서버 설정 sheet for every provider
 * that needs configuring: the app has no settings screen, and a sheet per field would be worse
 * than one sheet with four. It opens from whichever tool needs it.
 */
@Composable
fun Sam3SettingsSheet(
    config: Sam3Config,
    geminiApiKey: String,
    onSave: (baseUrl: String, token: String, geminiApiKey: String, topazApiKey: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    topazApiKey: String = "",
) {
    var baseUrl by remember(config) { mutableStateOf(config.baseUrl) }
    var token by remember(config) { mutableStateOf(config.token) }
    var apiKey by remember(geminiApiKey) { mutableStateOf(geminiApiKey) }
    var topazKey by remember(topazApiKey) { mutableStateOf(topazApiKey) }

    EditSheet(
        title = stringResource(R.string.sam3_settings_title),
        onCancel = onCancel,
        onApply = { onSave(baseUrl, token, apiKey, topazKey) },
        applyLabel = stringResource(R.string.sam3_settings_save),
        // Always enabled: Gemini is usable without SAM 3 and SAM 3 without Gemini, so gating on
        // either field would lock the other out — and clearing a value is a save too.
        modifier = modifier.testTag(Sam3SettingsSheetTestTag),
    ) {
        Sam3BaseUrlField(value = baseUrl, onValueChange = { baseUrl = it })
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text(stringResource(R.string.sam3_settings_token)) },
            singleLine = true,
            modifier = Modifier.testTag(Sam3TokenFieldTestTag).fillMaxWidth(),
        )
        // generative_erase.md §8: masked, so a shoulder-surfer and a screenshot see dots.
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(stringResource(R.string.gemini_settings_api_key)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.testTag(GeminiKeyFieldTestTag).fillMaxWidth(),
        )
        // specs/topaz_enhancement.md §4: the user's own key, masked like Gemini's.
        OutlinedTextField(
            value = topazKey,
            onValueChange = { topazKey = it },
            label = { Text(stringResource(R.string.topaz_settings_api_key)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.testTag(TopazKeyFieldTestTag).fillMaxWidth(),
        )
    }
}

/** The one field with a hint under it, lifted out to keep the sheet under detekt's ceiling. */
@Composable
private fun Sam3BaseUrlField(value: String, onValueChange: (String) -> Unit) {
    val colors = LocalAppColors.current
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.sam3_settings_base_url)) },
            singleLine = true,
            modifier = Modifier.testTag(Sam3BaseUrlFieldTestTag).fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.sam3_settings_base_url_hint),
            style = Typography.bodySm,
            color = colors.inkSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
