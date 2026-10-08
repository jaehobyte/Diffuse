package com.diffuse.feature.editor

import com.diffuse.core.ai.speech.SpeechState
import com.diffuse.core.common.AppError

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VibeSpeechTest {

    @Test
    fun `listening opens the direct planner and starts speech`() {
        assertEquals(
            VibeSpeechCommand(openDirect = true, startSpeech = true, stopSpeech = false),
            vibeSpeechCommand(listening = true),
        )
    }

    @Test
    fun `stopping speech does not close the planner`() {
        assertEquals(
            VibeSpeechCommand(openDirect = false, startSpeech = false, stopSpeech = true),
            vibeSpeechCommand(listening = false),
        )
    }

    @Test
    fun `planner status wins over the transcript on the overlay`() {
        assertEquals(
            "뭐을 할지 생각하는 중",
            vibeOverlayLine(
                status = "뭐을 할지 생각하는 중",
                transcript = "나무를 더 푸르게",
                hint = "말로 편집하기",
            ),
        )
    }

    @Test
    fun `a quiet planner leaves the transcript, then the hint`() {
        assertEquals(
            "나무를 더 푸르게",
            vibeOverlayLine(status = "", transcript = "나무를 더 푸르게", hint = "말로 편집하기"),
        )
        assertEquals(
            "말로 편집하기",
            vibeOverlayLine(status = "  ", transcript = "", hint = "말로 편집하기"),
        )
    }

    @Test
    fun `the speech overlay yields while the direct sheet is open`() {
        assertFalse(vibeChromeVisible(sheetOpen = true))
        assertTrue(vibeChromeVisible(sheetOpen = false))
    }

    @Test
    fun `a finished utterance returns the speak control to idle`() {
        assertFalse(vibeListeningAfterSpeech(localListening = true, speech = SpeechState.Final("나무")))
        assertFalse(
            vibeListeningAfterSpeech(
                localListening = true,
                speech = SpeechState.Failed(AppError.Unavailable),
            ),
        )
    }

    @Test
    fun `a live partial keeps the speak control listening`() {
        assertTrue(vibeListeningAfterSpeech(localListening = false, speech = SpeechState.Listening("나")))
        assertTrue(vibeListeningAfterSpeech(localListening = true, speech = SpeechState.Idle))
    }
}
