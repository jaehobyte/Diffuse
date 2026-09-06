package com.diffuse.feature.editor

import com.diffuse.core.ai.EditPlanProvider
import com.diffuse.core.ai.EraseProvider
import com.diffuse.core.ai.FillProvider
import com.diffuse.core.ai.OutpaintProvider
import com.diffuse.core.ai.SegmentationProvider
import com.diffuse.core.ai.gemini.GeminiSettings
import com.diffuse.core.ai.sam3.Sam3Settings
import com.diffuse.core.ai.speech.SpeechInput
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The editor's whole AI surface, in one injectable. `EditorViewModel` builds a controller per
 * tool from these; bundling them keeps its constructor about the screen rather than about the
 * model boundary, and gives the tools one thing to be handed.
 */
@Singleton
// The bundle is the point: one injectable per screen rather than one constructor parameter per
// provider, which is exactly the shape detekt's parameter ceiling is aimed at elsewhere.
@Suppress("LongParameterList")
class EditorAi @Inject constructor(
    val segmentation: SegmentationProvider,
    val erase: EraseProvider,
    val fill: FillProvider,
    val outpaint: OutpaintProvider,
    val plan: EditPlanProvider,
    val speech: SpeechInput,
    val sam3Settings: Sam3Settings,
    val geminiSettings: GeminiSettings,
)
