package com.diffuse.feature.editor

/**
 * What the vibe speak control should do with the existing direct planner.
 * Listening opens 지시 and starts the recogniser the sheet already consumes.
 * Stopping only ends recognition; the sheet stays so a partial can still be sent.
 */
data class VibeSpeechCommand(
    val openDirect: Boolean,
    val startSpeech: Boolean,
    val stopSpeech: Boolean,
)

fun vibeSpeechCommand(listening: Boolean): VibeSpeechCommand =
    if (listening) {
        VibeSpeechCommand(openDirect = true, startSpeech = true, stopSpeech = false)
    } else {
        VibeSpeechCommand(openDirect = false, startSpeech = false, stopSpeech = true)
    }

/**
 * One line on the speech overlay. Planner status (planning, running, not understood,
 * failure) wins over the request the sheet already holds; a blank pair falls back to
 * the hint. No second planner — both strings come from DirectState.
 */
fun vibeOverlayLine(status: String, transcript: String, hint: String): String =
    status.ifBlank { transcript.ifBlank { hint } }
