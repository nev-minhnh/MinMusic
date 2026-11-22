package com.metrolist.core

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.internal.MainDispatcherFactory
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/** The single `core-main` thread that plays the role of the UI thread for code written against Dispatchers.Main. */
object CoreMain {
    @Volatile
    var thread: Thread? = null
        private set

    private val executor =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "core-main").apply {
                isDaemon = true
                thread = this
            }
        }

    fun execute(block: Runnable) {
        try {
            executor.execute(block)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Shutting down.
        }
    }
}

@OptIn(InternalCoroutinesApi::class)
internal class CoreMainDispatcher(
    private val immediateMode: Boolean,
) : MainCoroutineDispatcher() {
    override val immediate: MainCoroutineDispatcher = if (immediateMode) this else CoreMainDispatcher(true)

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !immediateMode || Thread.currentThread() !== CoreMain.thread

    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        CoreMain.execute(block)
    }

    override fun toString(): String = "CoreMain"
}

/** Registered through META-INF/services; wins over every other factory (architecture 3.1, headless prerequisite 1). */
@OptIn(InternalCoroutinesApi::class)
class CoreMainDispatcherFactory : MainDispatcherFactory {
    override val loadPriority: Int get() = Int.MAX_VALUE

    override fun createDispatcher(allFactories: List<MainDispatcherFactory>): MainCoroutineDispatcher = CoreMainDispatcher(false)
}
