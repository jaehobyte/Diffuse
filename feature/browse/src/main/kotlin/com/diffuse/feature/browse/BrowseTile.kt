package com.diffuse.feature.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
import com.diffuse.core.data.ProjectSummary
import com.diffuse.core.ui.theme.LocalAppColors
import com.diffuse.core.ui.theme.Typography

/** DESIGN.md §4 (Image tile). */
private val TileCorner = 16.dp
private val TileGap = 4.dp
private val IconCircleSize = 40.dp
private const val ICON_CIRCLE_SCRIM = 0.6f
private const val DEFAULT_ASPECT = 1f

fun tileTag(id: String): String = "BrowseTile:$id"

fun tileActionTag(id: String, action: String): String = "BrowseTile:$id:$action"

/**
 * DESIGN.md §4: 16dp corners, `surfaceCard` behind the image, no text over the tile, and
 * metadata below in `bodySm`. Long-press reveals the `iconCircle` actions.
 */
@Composable
fun BrowseTile(
    summary: ProjectSummary,
    nowMillis: Long,
    showActions: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnail: @Composable BoxScope.() -> Unit = {},
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val aspect = if (summary.heightPx > 0) {
        summary.widthPx.toFloat() / summary.heightPx
    } else {
        DEFAULT_ASPECT
    }

    Column(
        modifier = modifier.testTag(tileTag(summary.id)),
        verticalArrangement = Arrangement.spacedBy(TileGap),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect.coerceIn(MIN_ASPECT, MAX_ASPECT))
                .clip(RoundedCornerShape(TileCorner))
                // DESIGN.md §4: flat surfaceCard while loading, no skeleton shimmer.
                .background(colors.surfaceRaised)
                .pointerInput(summary.id) {
                    detectTapGestures(onTap = { onOpen() }, onLongPress = { onLongPress() })
                },
        ) {
            thumbnail()
            if (showActions) {
                Row(
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
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
        Text(
            text = RelativeTime.format(context, summary.updatedAt, nowMillis),
            style = Typography.bodySm,
            color = colors.inkSecondary,
        )
    }
}

@Composable
private fun Row(modifier: Modifier, action: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { action() }
}

/** DESIGN.md §4: 40dp circle over a photo, black at 60%. */
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
            .size(IconCircleSize)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = ICON_CIRCLE_SCRIM))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = Color.White)
    }
}

private const val MIN_ASPECT = 0.4f
private const val MAX_ASPECT = 2.5f

@Composable
internal fun BoxScope.FlatThumbnail() {
    Box(modifier = Modifier.fillMaxSize())
}
