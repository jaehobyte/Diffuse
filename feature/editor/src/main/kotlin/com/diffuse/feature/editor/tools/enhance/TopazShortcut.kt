package com.diffuse.feature.editor.tools.enhance

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diffuse.core.ui.components.SecondaryPill

const val TopazShortcutTestTag = "TopazShortcut"

/**
 * specs/topaz_enhancement.md §1: the one secondary action a manual sheet carries into the shared
 * Topaz sheet, with its purpose already chosen. It never applies, discards or uploads the
 * sheet's own values; whether it may switch is the ViewModel's call.
 */
@Composable
fun TopazShortcut(@StringRes labelRes: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SecondaryPill(
        text = stringResource(labelRes),
        onClick = onClick,
        modifier = modifier.testTag(TopazShortcutTestTag),
    )
}
