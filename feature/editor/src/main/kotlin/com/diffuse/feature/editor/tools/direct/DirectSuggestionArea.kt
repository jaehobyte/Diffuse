package com.diffuse.feature.editor.tools.direct

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.diffuse.core.ai.PromptSuggestionId
import com.diffuse.core.ai.speech.SpeechInput
import com.diffuse.core.ai.speech.SpeechState
import com.diffuse.core.ui.components.TertiaryPill
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R

const val DirectSuggestionsTestTag = "DirectSuggestions"
const val DirectSuggestionFindTestTag = "DirectSuggestionFind"
const val DirectSuggestionCancelTestTag = "DirectSuggestionCancel"
const val DirectSuggestionHideTestTag = "DirectSuggestionHide"

/**
 * specs/vibe_edit.md §14. The area under the 지시 prompt bar: the general examples, the explicit
 * request for tailored ones, and the pills. A pill hands its catalog sentence to [onPick] and does
 * nothing else; nothing here sends, plans or edits.
 */
@Composable
fun DirectSuggestionArea(
    state: DirectState,
    speech: SpeechInput,
    onFind: () -> Unit,
    onCancel: () -> Unit,
    onHide: () -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    // §14: listening is about to write into the bar, so the list steps aside while it does.
    val speechState by speech.state.collectAsState()
    if (speechState is SpeechState.Listening) return
    val suggestions = state.suggestions
    val tailored = suggestions.phase == SuggestionPhase.Tailored
    Column(
        modifier = modifier.testTag(DirectSuggestionsTestTag),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    if (tailored) R.string.direct_suggest_tailored_title else R.string.direct_suggest_examples_title,
                ),
                style = Typography.bodySm,
                color = colors.inkSecondary,
                modifier = Modifier.weight(1f),
            )
            TertiaryPill(
                text = stringResource(R.string.direct_suggest_hide),
                onClick = onHide,
                modifier = Modifier.testTag(DirectSuggestionHideTestTag),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (if (tailored) suggestions.ids else GeneralSuggestions).forEach { id ->
                SuggestionPill(id = id, enabled = !state.running, onPick = onPick)
            }
        }
        StatusLine(state, onFind, onCancel)
    }
}

/** §14: the one line below the pills — which of the six states the area is in. */
@Composable
private fun StatusLine(state: DirectState, onFind: () -> Unit, onCancel: () -> Unit) {
    val colors = LocalAppColors.current
    val suggestions = state.suggestions
    when {
        // A pick during loading must not hide the request still running or its 취소.
        suggestions.phase == SuggestionPhase.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            Helper(stringResource(R.string.direct_suggest_working), Modifier.weight(1f))
            val description = stringResource(R.string.direct_suggest_cancel_description)
            TertiaryPill(
                text = stringResource(R.string.direct_suggest_cancel),
                onClick = onCancel,
                modifier = Modifier
                    .testTag(DirectSuggestionCancelTestTag)
                    .semantics { contentDescription = description },
            )
        }
        state.requestSource == RequestSource.Suggestion && state.request.isNotEmpty() -> Helper(
            stringResource(R.string.direct_suggest_editable),
        )
        suggestions.phase == SuggestionPhase.Tailored -> Unit
        else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (suggestions.phase == SuggestionPhase.Empty) {
                Text(
                    text = stringResource(R.string.direct_suggest_empty),
                    style = Typography.bodySm,
                    color = colors.ink,
                )
            }
            val retry = suggestions.phase == SuggestionPhase.Failed || suggestions.phase == SuggestionPhase.Empty
            TertiaryPill(
                text = stringResource(if (retry) R.string.direct_suggest_retry else R.string.direct_suggest_find),
                onClick = onFind,
                enabled = suggestions.frameReady && !state.working,
                modifier = Modifier.testTag(DirectSuggestionFindTestTag),
            )
            Helper(
                stringResource(
                    if (suggestions.frameReady) R.string.direct_suggest_notice else R.string.direct_suggest_preparing,
                ),
            )
        }
    }
}

@Composable
private fun Helper(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = Typography.bodySm,
        color = LocalAppColors.current.inkSecondary,
        modifier = modifier,
    )
}

/**
 * DESIGN.md §4: a pill on `editSurfaceRaised`, 40dp tall inside a 48dp touch target, ink text — the
 * sheet's one accent stays on 적용. TalkBack hears the label, the condition and what a tap does.
 */
@Composable
private fun SuggestionPill(id: PromptSuggestionId, enabled: Boolean, onPick: (String) -> Unit) {
    val colors = LocalAppColors.current
    val label = stringResource(id.labelRes())
    val request = stringResource(id.requestRes())
    val description = stringResource(R.string.direct_suggest_pill_description, label, stringResource(id.hintRes()))
    Box(
        modifier = Modifier
            .testTag(id.wire)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Button) { onPick(request) }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = Typography.bodyStrong,
            color = colors.ink.copy(alpha = if (enabled) 1f else DISABLED_ALPHA),
            modifier = Modifier
                .heightIn(min = 40.dp)
                .background(colors.surfaceRaised, RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** DESIGN.md §4: disabled is 38% alpha, colour unchanged. */
private const val DISABLED_ALPHA = 0.38f
