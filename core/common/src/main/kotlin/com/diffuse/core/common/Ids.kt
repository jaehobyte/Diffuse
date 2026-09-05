package com.diffuse.core.common

import java.util.UUID

/**
 * specs/architecture.md §3. Ids for documents and operations (specs/edit_model.md);
 * they are persisted, so they must be stable and collision-free across processes.
 */
fun newId(): String = UUID.randomUUID().toString()
