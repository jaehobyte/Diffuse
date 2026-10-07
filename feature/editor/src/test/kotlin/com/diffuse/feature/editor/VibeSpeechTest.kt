package com.diffuse.feature.editor

import org.junit.Assert.assertEquals
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
}
