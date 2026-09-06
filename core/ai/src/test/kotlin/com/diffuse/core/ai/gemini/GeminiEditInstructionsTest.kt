package com.diffuse.core.ai.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * specs/generative_erase.md §5 and specs/generative_fill.md §3. What the app asks the image model
 * for. These moved out of `GeminiEraseClientTest` in T60, when the client stopped owning a
 * sentence and started taking one.
 */
class GeminiEditInstructionsTest {

    // ---- 지우기 -----------------------------------------------------------

    @Test
    fun `a hint appends one sentence, and a blank one appends nothing`() {
        assertEquals(
            ERASE_INSTRUCTION +
                " The painted area used to contain a car, which has been removed on purpose: " +
                "reconstruct what was behind it and do not draw it again.",
            eraseInstruction("a car"),
        )
        assertEquals(ERASE_INSTRUCTION, eraseInstruction("  "))
        assertEquals(ERASE_INSTRUCTION, eraseInstruction(null))
    }

    /** T51: the two sentences that close the "it came back white" failure the device showed. */
    @Test
    fun `the erase instruction forbids leaving white and forbids echoing the input`() {
        assertTrue(ERASE_INSTRUCTION.contains("no white or near-white patch may remain"))
        assertTrue(
            ERASE_INSTRUCTION
                .contains("returning the input image unchanged is not an acceptable answer"),
        )
        assertTrue(ERASE_INSTRUCTION.contains("do not draw any new object"))
    }

    @Test
    fun `the hint says the thing was removed, never what to draw`() {
        val instruction = eraseInstruction("a bus")

        assertTrue(instruction.contains("has been removed on purpose"))
        assertTrue(instruction.contains("do not draw it again"))
    }

    // ---- 채우기 -----------------------------------------------------------

    @Test
    fun `the prompt is substituted verbatim`() {
        val instruction = fillInstruction("빨간 우산")

        assertTrue(instruction.contains("Replace that entire patch with: 빨간 우산."))
    }

    @Test
    fun `a padded prompt is trimmed rather than sent with its whitespace`() {
        assertEquals(fillInstruction("빨간 우산"), fillInstruction("  빨간 우산  "))
    }

    /**
     * §3: an unfillable prompt has to degrade into an erase, so the user gets a result they can
     * see and undo rather than a snackbar.
     */
    @Test
    fun `the fill instruction falls back to continuing the scene`() {
        val instruction = fillInstruction("a unicorn")

        assertTrue(
            instruction.contains("cannot plausibly occupy that area"),
        )
        assertTrue(instruction.contains("continuation of the surrounding scene"))
    }

    @Test
    fun `the fill instruction keeps the erase's guards on white and on the surroundings`() {
        val instruction = fillInstruction("a red umbrella")

        assertTrue(instruction.contains("no white or near-white patch may remain"))
        assertTrue(instruction.contains("do not alter anything outside the painted area"))
    }

    /** 채우기 asks for a new thing; forbidding new objects would contradict the whole tool. */
    @Test
    fun `the fill instruction does not forbid drawing a new object`() {
        assertFalse(fillInstruction("a red umbrella").contains("do not draw any new object"))
    }
}
