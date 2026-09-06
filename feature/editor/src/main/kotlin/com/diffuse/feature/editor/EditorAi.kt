package com.diffuse.feature.editor

import com.diffuse.core.ai.EraseProvider
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
class EditorAi @Inject constructor(
    val segmentation: SegmentationProvider,
    val erase: EraseProvider,
    val speech: SpeechInput,
    val sam3Settings: Sam3Settings,
    val geminiSettings: GeminiSettings,
)
