package com.diffuse.core.ai.gemini

/**
 * What the app asks `gemini-2.5-flash-image` for. `GeminiEraseClient` knows how to talk to that
 * model; these say what to ask it, and each provider owns the one it sends
 * (specs/generative_fill.md §3).
 *
 * All of them are English `internal` constants. They are wire payload, not copy a person reads,
 * so DESIGN.md §9's "Korean, in strings.xml" does not apply — the rule generative_erase.md §5
 * states and generative_fill.md §3 inherits.
 */

/** specs/generative_erase.md §5, rewritten by T51 to close the answer that is still a hole. */
internal const val ERASE_INSTRUCTION =
    "You are editing a photograph. A solid pure-white patch has been painted over the " +
        "area to remove. Fill that entire patch with photorealistic content that " +
        "continues the scene behind it, matching the surrounding lighting, texture, " +
        "perspective, focus, grain and noise, so the result looks like one unedited " +
        "photograph that never contained the thing that was there. Requirements: no " +
        "white or near-white patch may remain where the painted area was; returning the " +
        "input image unchanged is not an acceptable answer; do not draw any new object, " +
        "person, text or watermark; do not alter anything outside the painted area; do " +
        "not add a border, frame or caption. Return only the edited image."

/**
 * T51: the hint says what was **removed**, not what to draw. It used to read "The white region
 * previously contained: <hint>." beside "Do not introduce any new object", which a model can
 * read as an instruction to paint the thing back in.
 */
internal fun eraseInstruction(hint: String?): String =
    if (hint.isNullOrBlank()) {
        ERASE_INSTRUCTION
    } else {
        "$ERASE_INSTRUCTION The painted area used to contain $hint, which has been removed on " +
            "purpose: reconstruct what was behind it and do not draw it again."
    }

/**
 * specs/generative_fill.md §3. The same whitened image the eraser sends, pointed the other way:
 * a person has named what should be there.
 *
 * The last sentence is the fallback that keeps a bad request from becoming a failed one. An
 * unfillable prompt degrades into an erase — a result the user can see and undo — rather than a
 * snackbar. `%s` is the user's own words, substituted verbatim: the one place in this feature
 * where a user-authored string reaches the wire.
 */
internal const val FILL_INSTRUCTION_TEMPLATE =
    "You are editing a photograph. A solid pure-white patch has been painted over an area of " +
        "it. Replace that entire patch with: %s. Render it photorealistically as part of the " +
        "surrounding scene, matching the scene's lighting, shadow direction, texture, " +
        "perspective, scale, focus and grain so the result looks like one unedited photograph. " +
        "Requirements: no white or near-white patch may remain where the painted area was; do " +
        "not alter anything outside the painted area; do not add text, a watermark, a border or " +
        "a caption. If the requested subject cannot plausibly occupy that area, fill the area " +
        "with a continuation of the surrounding scene instead. Return only the edited image."

internal fun fillInstruction(prompt: String): String =
    FILL_INSTRUCTION_TEMPLATE.format(prompt.trim())
