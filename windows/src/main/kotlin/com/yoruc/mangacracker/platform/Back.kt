package com.yoruc.mangacracker.platform

/** Back presses on Windows: screens register handlers (newest first); with none enabled, navigation goes back. */
object Back {
    class Handler(val enabled: () -> Boolean, val onBack: () -> Unit)

    private val handlers = java.util.concurrent.CopyOnWriteArrayList<Handler>()

    /** Set by the app: go back one screen. */
    @Volatile var fallback: () -> Unit = {}

    fun add(h: Handler) { handlers += h }
    fun remove(h: Handler) { handlers -= h }

    fun press() {
        handlers.asReversed().firstOrNull { it.enabled() }?.onBack?.invoke() ?: fallback()
    }
}
