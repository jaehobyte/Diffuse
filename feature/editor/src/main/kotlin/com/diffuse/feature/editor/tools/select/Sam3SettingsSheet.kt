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

/**
 * specs/segmentation.md §6 and generative_erase.md §8. One 서버 설정 sheet for both providers:
 * the app has no settings screen, and a second sheet for one field would be worse than one
 * sheet with three. It opens from whichever tool needs it.
 */
@Composable
fun Sam3SettingsSheet(
    config: Sam3Config,
    geminiApiKey: String,
    onSave: (baseUrl: String, token: String, geminiApiKey: String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    var baseUrl by remember(config) { mutableStateOf(config.baseUrl) }
    var token by remember(config) { mutableStateOf(config.token) }
    var apiKey by remember(geminiApiKey) { mutableStateOf(geminiApiKey) }

    EditSheet(
        title = stringResource(R.string.sam3_settings_title),
        onCancel = onCancel,
        onApply = { onSave(baseUrl, token, apiKey) },
        applyLabel = stringResource(R.string.sam3_settings_save),
        applyEnabled = baseUrl.isNotBlank(),
        modifier = modifier.testTag(Sam3SettingsSheetTestTag),
    ) {
        Column {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
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
    }
}
