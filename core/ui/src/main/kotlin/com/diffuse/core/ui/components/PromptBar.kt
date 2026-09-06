package com.diffuse.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.diffuse.core.ui.R
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.core.ui.theme.Typography

/** DESIGN.md §4 (Prompt bar). */
private val BarHeight = 48.dp
private val BarRadius = 16.dp
private val IconSize = 24.dp
private val TouchTarget = 48.dp
private val HorizontalPadding = 12.dp
private val IconGap = 8.dp

const val PromptBarTestTag = "PromptBar"
const val PromptFieldTestTag = "PromptField"
const val PromptSendTestTag = "PromptSend"
const val PromptMicTestTag = "PromptMic"

/**
 * specs/prompt_input.md §2. One text field that carries a short noun phrase to whichever tool
 * is open, with the device's speech recogniser behind the mic.
 *
 * Neither icon is `accent`: DESIGN.md §1 allows one accent per surface at rest, and in a sheet
 * that is the Apply pill. Send reads as actionable through its enabled state and the IME's
 * Done key instead. The mic turns accent *while listening* only, as §1's transient exception.
 */
@Composable
fun PromptBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Null hides the mic entirely: no recogniser on the device, or the permission is gone. */
    onMicClick: (() -> Unit)? = null,
    listening: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = LocalAppColors.current
    val canSend = enabled && value.isNotBlank()
    val submit = { if (canSend) onSubmit(value.trim()) }

    Row(
        modifier = modifier
            .testTag(PromptBarTestTag)
            .fillMaxWidth()
            .height(BarHeight)
            .clip(RoundedCornerShape(BarRadius))
            .background(colors.surfaceRaised)
            .padding(horizontal = HorizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onMicClick != null) {
            BarIcon(
                testTag = PromptMicTestTag,
                icon = if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                description = stringResource(
                    if (listening) R.string.prompt_stop else R.string.prompt_mic,
                ),
                // A fill change only — DESIGN.md §7 forbids glow, and nothing pulses.
                tint = if (listening) Tokens.accent else colors.ink,
                enabled = enabled,
                onClick = onMicClick,
            )
        }
        Box(
            modifier = Modifier.padding(horizontal = IconGap).weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    text = stringResource(R.string.prompt_placeholder),
                    style = Typography.bodyMd,
                    color = colors.inkSecondary,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = Typography.bodyMd.copy(color = colors.ink),
                cursorBrush = SolidColor(colors.ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.testTag(PromptFieldTestTag).fillMaxWidth(),
            )
        }
        BarIcon(
            testTag = PromptSendTestTag,
            icon = Icons.AutoMirrored.Rounded.Send,
            description = stringResource(R.string.prompt_send),
            tint = colors.ink,
            enabled = canSend,
            onClick = submit,
        )
    }
}

/** DESIGN.md §5: even a 24dp icon gets a 48dp hit area. */
@Composable
private fun BarIcon(
    testTag: String,
    icon: ImageVector,
    description: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag(testTag).size(TouchTarget),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) tint else tint.copy(alpha = DISABLED_ALPHA),
            modifier = Modifier.size(IconSize),
        )
    }
}
