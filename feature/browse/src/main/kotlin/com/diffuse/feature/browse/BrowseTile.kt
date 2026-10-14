package com.diffuse.feature.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.ui.theme.Dimens
import com.diffuse.core.ui.theme.LocalAppColors

/** DESIGN.md §4 (Image tile): the Gallery's square, square-cornered thumbnail. */
private val IconCircleSize = 40.dp
private const val ICON_CIRCLE_SCRIM = 0.6f
private const val SQUARE = 1f

fun tileTag(id: String): String = "BrowseTile:$id"

fun tileActionTag(id: String, action: String): String = "BrowseTile:$id:$action"

/**
 * DESIGN.md §4: a square tile with no corner radius and no text, `surfaceRaised` behind the
 * centre-cropped image, as in the Gallery grid. Long-press reveals the `iconCircle` actions.
 */
@Composable
fun BrowseTile(
    summary: ProjectSummary,
    showActions: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnail: @Composable BoxScope.() -> Unit = {},
) {
    val colors = LocalAppColors.current

    Box(
        modifier = modifier
            .testTag(tileTag(summary.id))
            .fillMaxWidth()
            .aspectRatio(SQUARE)
            // DESIGN.md §4: flat surfaceRaised while loading, no skeleton shimmer.
            .background(colors.surfaceRaised)
            .pointerInput(summary.id) {
                detectTapGestures(onTap = { onOpen() }, onLongPress = { onLongPress() })
            },
    ) {
        thumbnail()
        if (showActions) {
            Row(
                // The 48dp targets carry 4dp of their own margin around the 40dp circles.
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                action = {
                    IconCircle(
                        icon = Icons.Rounded.ContentCopy,
                        labelRes = R.string.browse_duplicate,
                        testTag = tileActionTag(summary.id, "duplicate"),
                        onClick = onDuplicate,
                    )
                    IconCircle(
                        icon = Icons.Rounded.DeleteOutline,
                        labelRes = R.string.browse_delete,
                        testTag = tileActionTag(summary.id, "delete"),
                        onClick = onDelete,
                    )
                },
            )
        }
    }
}

@Composable
private fun Row(modifier: Modifier, action: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) { action() }
}

/** DESIGN.md §4: a 40dp circle over a photo, black at 60%, inside a 48dp touch target. */
@Composable
private fun IconCircle(
    icon: ImageVector,
    labelRes: Int,
    testTag: String,
    onClick: () -> Unit,
) {
    val label = stringResource(labelRes)
    Box(
        modifier = Modifier
            .testTag(testTag)
            .size(Dimens.touchTarget)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(IconCircleSize)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = ICON_CIRCLE_SCRIM)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = Color.White)
        }
    }
}

@Composable
internal fun BoxScope.FlatThumbnail() {
    Box(modifier = Modifier.fillMaxSize())
}
