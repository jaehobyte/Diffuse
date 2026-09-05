package com.diffuse.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * specs/architecture.md §5.4. Injected so tests can substitute test dispatchers.
 *
 * There is no `main`: §5.4 reserves the main thread for Compose, which schedules onto it
 * itself, and exposing it here would invite core modules to touch it.
 */
interface DispatcherProvider {

    /** Rendering and op math. */
    val default: CoroutineDispatcher

    /** File and database work. */
    val io: CoroutineDispatcher
}

object DefaultDispatcherProvider : DispatcherProvider {
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val io: CoroutineDispatcher get() = Dispatchers.IO
}
