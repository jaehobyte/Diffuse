package com.diffuse.feature.editor

import com.diffuse.core.imaging.model.EditDocument

/**
 * specs/editor_shell.md: compare is disabled when the document has no operations, because
 * the preview and the source are then the same image.
 */
fun EditDocument.canCompare(): Boolean = operations.isNotEmpty()
