package com.diffuse.feature.editor.tools.multishot

import com.diffuse.core.imaging.model.ImageRef
import com.diffuse.core.imaging.model.MultiShotMode
import com.diffuse.core.imaging.model.NormPoint
import com.diffuse.core.imaging.model.Shot
import com.diffuse.core.imaging.model.ShotPlacement

/**
 * A time layout ready to apply, an afterimage selected and the layout step shown: what the sheet
 * test and its golden show.
 */
internal fun timedHero() = MultiShotState(
    open = true,
    mode = MultiShotMode.Timeline,
    items = listOf(
        ShotItem("a", ShotStatus.Ready, placement = ShotPlacement(opacity = 0.25f), anchor = NormPoint(0.5f, 1f)),
        ShotItem("b", ShotStatus.Ready, placement = ShotPlacement(opacity = 0.7f), anchor = NormPoint(0.5f, 1f)),
    ),
    timeOrder = listOf("a", "b"),
    orderConfirmed = true,
    laidOutOrder = listOf("a", "b"),
    hero = ShotItem(MultiShotState.HERO_KEY, ShotStatus.Ready),
    heroAnchor = NormPoint(0.3f, 0.9f),
    canvasWidth = 40,
    canvasHeight = 30,
    selectedKey = "a",
    panel = TimelinePanel.Layout,
    draftShots = listOf(Shot("a", ImageRef("/p/shot_a.png"), 40, 30), Shot("b", ImageRef("/p/shot_b.png"), 40, 30)),
)
