package com.diffuse.feature.editor

import com.diffuse.core.ai.speech.SpeechState

/**
 * What the vibe speak control should do with the existing direct planner.
 * Listening opens 지시 and arms the mic on the sheet's VoicePromptBar.
 * That bar already asks for permission and submits a final utterance.
 * Speak does not start recognition itself. Stopping only ends recognition;
 * the sheet stays so a partial can still be sent.
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
 * [VibeSpeechCommand.startSpeech] arms the direct sheet's mic. The recogniser
 * starts inside VoicePromptBar so permission and submit stay one path.
 */
fun vibeSpeechArmsSheetMic(listening: Boolean): Boolean =
    vibeSpeechCommand(listening).startSpeech && vibeSpeechCommand(listening).openDirect

/**
 * One line on the speech overlay. Planner status (planning, running, not understood,
 * failure) wins over the request the sheet already holds; a blank pair falls back to
 * the hint. No second planner — both strings come from DirectState.
 */
fun vibeOverlayLine(status: String, transcript: String, hint: String): String =
    status.ifBlank { transcript.ifBlank { hint } }

/**
 * Speak opens the direct sheet, which already owns [VoicePromptBar]. The overlay sits
 * above that sheet, so it yields while the slot has measured height and returns when
 * the slot is empty. [EditorRoute] always passes a sheet lambda; an unselected tool
 * is [ToolSheetHost] at zero height and must not hide the speak control.
 */
fun vibeChromeVisible(sheetOpen: Boolean): Boolean = !sheetOpen

/**
 * The speak control follows the recogniser the direct sheet already owns.
 * A live partial keeps it on 듣는 중. A final or a failure returns it to 말하기
 * so the overlay does not stay listening after [VoicePromptBar] has consumed the utterance.
 * Idle leaves the local toggle alone — start() has not reported yet.
 */
fun vibeListeningAfterSpeech(localListening: Boolean, speech: SpeechState): Boolean =
    when (speech) {
        is SpeechState.Listening -> true
        is SpeechState.Final, is SpeechState.Failed -> false
        SpeechState.Idle -> localListening
    }

/**
 * The overlay line opens 지시 so the user can type on the sheet that already submits.
 * It does not arm the mic and does not stop a recognition speak already started.
 */
fun vibePromptCommand(): VibeSpeechCommand =
    VibeSpeechCommand(openDirect = true, startSpeech = false, stopSpeech = false)
