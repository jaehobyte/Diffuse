package com.diffuse.core.ai.monet

import com.diffuse.core.common.AppError
import com.diffuse.core.ai.gemini.geminiErrorOf
import com.diffuse.core.ai.gemini.geminiStatusError
import kotlinx.serialization.json.Json

/**
 * specs/auto_enhance.md §4: "Error mapping is generative_erase.md §6 row for row."
 *
 * So it is that function, not a second copy of that table. The name says Gemini because the table
 * was written for it; the rows are HTTP status codes and an `{"error": {"message": …}}` envelope,
 * which is what an OpenAI-compatible server answers with too. A second table is how two paths
 * come to disagree about what a 429 means.
 */
private val errorJson = Json { ignoreUnknownKeys = true }

internal fun monetStatusError(code: Int, body: String): AppError =
    geminiStatusError(code, geminiErrorOf(errorJson, body))
