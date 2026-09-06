package com.diffuse.core.ai.monet

import com.diffuse.core.ai.AutoStyle

/**
 * What the app asks MonetGPT for. English `internal` constants: they are wire payload, not copy a
 * person reads, so DESIGN.md §9's "Korean, in strings.xml" does not apply — the rule
 * generative_erase.md §5 states and specs/auto_enhance.md inherits.
 *
 * The three style paragraphs and the value scale are MonetGPT's own
 * (`configs/inference_config.yaml`, `inference/core.py`); repeating them here rather than
 * paraphrasing is deliberate, because the model was fine-tuned against these words.
 */
internal val AutoStyle.instruction: String
    get() = when (this) {
        AutoStyle.Balanced ->
            "You should be aiming for balanced edits. This means you should avoid overdoing " +
                "(or underdoing) any single parameter and maintain a natural, true-to-life look. " +
                "Colors should remain realistic, and tonal adjustments should enhance detail " +
                "without looking overly dramatic."

        AutoStyle.Vibrant ->
            "You should be aiming for vibrant and punchy colors, especially suitable for social " +
                "media. This means you can use bolder saturation, deeper contrast, and more " +
                "dramatic shifts in color, as long as the final result looks appealing and " +
                "eye-catching."

        AutoStyle.Retro ->
            "You should aim for a nostalgic retro vibe, with muted tones and soft, washed-out " +
                "colors reminiscent of film photography from the 70s or 80s. This means " +
                "reducing the lights, faded highlights, and warm color shifts, creating a " +
                "timeless, vintage aesthetic that's subtle yet eye-catching."
    }

/**
 * specs/auto_enhance.md §3. "All adjustment values are scaled between -100 and +100" is
 * MonetGPT's own sentence, and the one number this whole integration hangs on.
 *
 * The operation names are listed because the server answers whatever it likes otherwise, and a
 * name we do not recognise costs the user a whole boost rather than one slider.
 */
internal fun monetInstruction(style: AutoStyle, operations: List<String>): String =
    "Critique this photograph and tell the optimal adjustment values needed to edit it, in " +
        "JSON format. All adjustment values are scaled between -100 and +100.\n" +
        "Use only these operation names, and only the ones that are relevant to this photo — " +
        "you do not have to use all of them: ${operations.joinToString(", ")}.\n" +
        "Answer with a single JSON object mapping operation names to numbers, and one short " +
        "English sentence before it saying what you changed and why.\n" +
        style.instruction
