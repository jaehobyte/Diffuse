package com.diffuse.feature.editor

import com.diffuse.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Unconfined on both, so a `EditorViewModel` test sees the work a plan run does without
 * pumping a second scheduler.
 */
object TestDispatchers : DispatcherProvider {
    override val default: CoroutineDispatcher get() = Dispatchers.Unconfined
    override val io: CoroutineDispatcher get() = Dispatchers.Unconfined
}
