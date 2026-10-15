package com.diffuse.feature.editor.tools.select

/**
 * specs/selection_tool.md §4. Whether an incoming mask joins a selection or is taken out of
 * it. The [추가 | 빼기] toggle that chose this went with the selection sheet — a tap on the photo
 * always adds, and a long-press excludes inside the run — so only [MaskOps] still reads it.
 */
enum class MergeMode { Add, Subtract }
