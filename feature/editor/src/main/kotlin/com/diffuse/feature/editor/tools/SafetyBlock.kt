package com.diffuse.feature.editor.tools

import com.diffuse.core.common.AppError

/**
 * specs/generative_erase.md §9: a safety block is an `Invalid` with a recognizable prefix, not
 * its own `AppError`. The prefix is duplicated from `core:ai` rather than imported: the clients
 * that write it are `internal` there, which is the boundary working as intended — the feature
 * knows the contract, not the client.
 */
internal fun AppError.isSafetyBlock(): Boolean = this is AppError.Invalid && detail.startsWith(BLOCKED_PREFIX)

private const val BLOCKED_PREFIX = "blocked:"
