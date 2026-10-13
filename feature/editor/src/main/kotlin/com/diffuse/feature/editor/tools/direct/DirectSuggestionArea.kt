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
import com.diffuse.core.ai.PlanMode
import com.diffuse.core.ai.speech.SpeechInput
import com.diffuse.core.ai.speech.SpeechState
import com.diffuse.core.ui.components.TertiaryPill
import com.diffuse.core.ui.theme.Dimens
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Shapes
import com.diffuse.core.ui.theme.Tokens
import com.diffuse.core.ui.theme.Typography
import com.diffuse.feature.editor.R

const val DirectSuggestionsTestTag = "DirectSuggestions"
const val DirectRecommendTestTag = "DirectRecommend"
const val DirectRecommendCancelTestTag = "DirectRecommendCancel"
const val DirectSuggestionHideTestTag = "DirectSuggestionHide"

/**
 * specs/vibe_edit.md §14. The area under the 지시 prompt bar: the example sentences — an input aid
 * whose pills hand their sentence to [onPick] and do nothing else — and the separate 보정 추천 받기
 * entry, which asks for a reviewed workflow. Nothing here edits; a recommendation only ever lands
 * in the review above 적용.
 */
@Composable
fun DirectSuggestionArea(
    state: DirectState,
    speech: SpeechInput,
    onRecommend: (again: Boolean) -> Unit,
    onCancel: () -> Unit,
    onHide: () -> Unit,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAppColors.current
    // §14: listening is about to write into the bar, so the area steps aside while it does.
    val speechState by speech.state.collectAsState()
    if (speechState is SpeechState.Listening) return
    Column(
        modifier = modifier.testTag(DirectSuggestionsTestTag),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.showExamples) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.direct_suggest_examples_title),
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
                ExampleSentence.entries.forEach { example ->
                    SuggestionPill(example = example, enabled = !state.running, onPick = onPick)
                }
            }
            if (state.requestSource == RequestSource.Suggestion && state.request.isNotEmpty()) {
                Helper(stringResource(R.string.direct_suggest_editable))
            }
        }
        RecommendLine(state, onRecommend, onCancel)
    }
}

/** §14: the 보정 추천 entry, in whichever of its states the request is. */
@Composable
private fun RecommendLine(state: DirectState, onRecommend: (Boolean) -> Unit, onCancel: () -> Unit) {
    if (state.recommending) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Helper(stringResource(R.string.direct_recommend_working), Modifier.weight(1f))
            val description = stringResource(R.string.direct_recommend_cancel_description)
            TertiaryPill(
                text = stringResource(R.string.direct_recommend_cancel),
                onClick = onCancel,
                modifier = Modifier
                    .testTag(DirectRecommendCancelTestTag)
                    .semantics { contentDescription = description },
            )
        }
        return
    }
    // A recommendation was answered: asking again is one explicit new request.
    val recommendMode = state.proposals.mode == PlanMode.Recommend
    val answered = recommendMode && state.proposal != null
    val failed = recommendMode && state.phase == ProposalPhase.Failed
    val label = when {
        failed -> R.string.direct_recommend_retry
        answered -> R.string.direct_recommend_again
        else -> R.string.direct_recommend
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.phase == ProposalPhase.Cancelled) Helper(stringResource(R.string.direct_recommend_cancelled))
        if (failed) {
            Text(
                text = stringResource(R.string.direct_recommend_failed),
                style = Typography.bodySm,
                color = LocalAppColors.current.ink,
            )
        }
        TertiaryPill(
            text = stringResource(label),
            onClick = { onRecommend(answered) },
            enabled = state.canRecommend,
            // DESIGN.md §4 `tertiary`: a link action, in the blue that keeps 4.5:1.
            color = LocalAppColors.current.link,
            modifier = Modifier.testTag(DirectRecommendTestTag),
        )
        val notice =
            if (state.proposals.frameReady) R.string.direct_recommend_notice else R.string.direct_recommend_preparing
        Helper(stringResource(notice))
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
 * DESIGN.md §2, §4: a suggested sentence is a `promptTint` pill, 40dp tall inside a 48dp touch
 * target, ink text — light blue marks it as a suggestion, not as a selection or the sheet's
 * primary. TalkBack hears the label, the condition and what a tap does.
 */
@Composable
private fun SuggestionPill(example: ExampleSentence, enabled: Boolean, onPick: (String) -> Unit) {
    val colors = LocalAppColors.current
    val label = stringResource(example.labelRes())
    val request = stringResource(example.requestRes())
    val description = stringResource(R.string.direct_suggest_pill_description, label, stringResource(example.hintRes()))
    Box(
        modifier = Modifier
            .testTag(example.tag)
            .heightIn(min = Dimens.touchTarget)
            .clip(Shapes.pill)
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
                .background(Tokens.promptTint, Shapes.pill)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** DESIGN.md §4: disabled is 38% alpha, colour unchanged. */
private const val DISABLED_ALPHA = 0.38f
