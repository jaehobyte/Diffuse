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
import com.diffuse.core.ai.monet.MonetConfig
import com.diffuse.core.ai.monet.isValidMonetBaseUrl
import com.diffuse.core.ai.sam3.Sam3Config
import com.diffuse.core.ui.components.EditSheet
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R

const val Sam3SettingsSheetTestTag = "Sam3SettingsSheet"
const val Sam3BaseUrlFieldTestTag = "Sam3BaseUrl"
const val Sam3TokenFieldTestTag = "Sam3Token"
const val GeminiKeyFieldTestTag = "GeminiKey"
const val MonetBaseUrlFieldTestTag = "MonetBaseUrl"
const val MonetTokenFieldTestTag = "MonetToken"

/**
 * specs/segmentation.md §6, generative_erase.md §8 and auto_enhance.md §4. One 서버 설정 sheet
 * for every provider that needs configuring: the app has no settings screen, and a sheet per
 * field would be worse than one sheet with five. It opens from whichever tool needs it.
 */
@Composable
fun Sam3SettingsSheet(
    config: Sam3Config,
    geminiApiKey: String,
    monetConfig: MonetConfig,
    onSave: (
        baseUrl: String,
        token: String,
        geminiApiKey: String,
        monetBaseUrl: String,
        monetToken: String,
    ) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var baseUrl by remember(config) { mutableStateOf(config.baseUrl) }
    var token by remember(config) { mutableStateOf(config.token) }
    var apiKey by remember(geminiApiKey) { mutableStateOf(geminiApiKey) }
    var monetBaseUrl by remember(monetConfig) { mutableStateOf(monetConfig.baseUrl) }
    var monetToken by remember(monetConfig) { mutableStateOf(monetConfig.token) }

    EditSheet(
        title = stringResource(R.string.sam3_settings_title),
        onCancel = onCancel,
        onApply = { onSave(baseUrl, token, apiKey, monetBaseUrl, monetToken) },
        applyLabel = stringResource(R.string.sam3_settings_save),
        // Either server is reason enough to save: 자동 보정 is usable without SAM 3, and SAM 3
        // without 자동 보정, so gating on SAM 3's address alone would lock one of them out.
        // specs/auto_enhance.md §4: a malformed 자동 보정 address is refused here rather than saved
        // and discovered on the next tap.
        applyEnabled = (baseUrl.isNotBlank() || monetBaseUrl.isNotBlank()) &&
            isValidMonetBaseUrl(monetBaseUrl),
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
        // specs/auto_enhance.md §4: MonetGPT's own server, beside SAM 3's rather than in a
        // second sheet. Its token is a self-hosted server's, like SAM 3's, so it is not masked.
        MonetBaseUrlField(value = monetBaseUrl, onValueChange = { monetBaseUrl = it })
        OutlinedTextField(
            value = monetToken,
            onValueChange = { monetToken = it },
            label = { Text(stringResource(R.string.monet_settings_token)) },
            singleLine = true,
            modifier = Modifier.testTag(MonetTokenFieldTestTag).fillMaxWidth(),
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

/**
 * specs/auto_enhance.md §4. The phone's own `127.0.0.1` is the address people paste by mistake —
 * it is the server's loopback only over `adb reverse` — so the hint says so, and a value OkHttp
 * could not build a request from is marked rather than saved.
 */
@Composable
private fun MonetBaseUrlField(value: String, onValueChange: (String) -> Unit) {
    val colors = LocalAppColors.current
    val invalid = !isValidMonetBaseUrl(value)
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(stringResource(R.string.monet_settings_base_url)) },
            singleLine = true,
            isError = invalid,
            modifier = Modifier.testTag(MonetBaseUrlFieldTestTag).fillMaxWidth(),
        )
        Text(
            text = stringResource(
                if (invalid) R.string.monet_settings_base_url_invalid
                else R.string.monet_settings_base_url_hint,
            ),
            style = Typography.bodySm,
            color = if (invalid) colors.error else colors.inkSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
