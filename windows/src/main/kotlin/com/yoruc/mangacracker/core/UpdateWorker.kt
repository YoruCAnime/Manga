package com.yoruc.mangacracker.core

import android.content.Context
import com.yoruc.mangacracker.platform.Desktop
import com.yoruc.mangacracker.sources.Sources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Looks for new chapters of every library title (chaptered sources only) while the app is open: once the interval
 * (Settings) has passed since the last check, then again every interval. New chapters show a Windows notification.
 */
object UpdateWorker {
    private var loop: Job? = null

    fun schedule(ctx: Context) {
        loop?.cancel()
        loop = Desktop.scope.launch {
            while (isActive) {
                val hours = Repo.settings.value.updateHours
                if (hours <= 0) return@launch
                val gap = hours * 3600_000L
                val wait = (Repo.settings.value.lastLibraryCheck + gap - System.currentTimeMillis()).coerceAtLeast(60_000)
                delay(wait)
                val fresh = runCatching { checkLibrary() }.getOrDefault(emptyList())
                if (fresh.isNotEmpty() && Repo.settings.value.notifyUpdates) notify(ctx, fresh)
                Backup.autoIfDue(ctx)
            }
        }
    }

    /** Check every chaptered library title, 3 at a time. Returns the new chapters found. */
    suspend fun checkLibrary(onProgress: (Int, Int) -> Unit = { _, _ -> }): List<UpdateItem> = withContext(Dispatchers.IO) {
        val entries = Repo.library.value.values.filter { Sources.byId(it.ref.source)?.hasChapters == true }
        val gate = Semaphore(3)
        val done = java.util.concurrent.atomic.AtomicInteger()
        val found = coroutineScope {
            entries.map { e ->
                async {
                    gate.withPermit {
                        val src = Sources.byId(e.ref.source)!!
                        val chapters = runCatching { src.latestChapters(e.ref) }.getOrNull()
                        onProgress(done.incrementAndGet(), entries.size)
                        if (chapters.isNullOrEmpty()) emptyList()
                        else Repo.applyChapterCheck(e.ref.key, chapters).map { UpdateItem(e.ref, it, System.currentTimeMillis()) }
                    }
                }
            }.awaitAll().flatten()
        }
        Repo.updateSettings { it.copy(lastLibraryCheck = System.currentTimeMillis()) }
        found
    }

    @Suppress("UNUSED_PARAMETER")
    fun notify(ctx: Context, items: List<UpdateItem>) {
        val titles = items.groupBy { it.ref.title }
        val text = titles.entries.take(3).joinToString("\n") { (t, l) -> if (l.size == 1) "$t · ${l[0].chapter.name}" else "$t · ${l.size} new" }
        Desktop.notify("${items.size} new chapter${if (items.size == 1) "" else "s"}", text)
    }
}
