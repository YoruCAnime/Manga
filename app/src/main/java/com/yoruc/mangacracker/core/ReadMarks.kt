package com.yoruc.mangacracker.core

import com.yoruc.mangacracker.sources.Sources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * How far a title is read, for the badges on covers: [read] chapters done of [total] known (0 = unknown),
 * [percent] = the page progress in the chapter opened last.
 */
data class ReadMark(val read: Int, val total: Int, val percent: Int, val finished: Boolean) {
    /** For the thin bar under the cover: chapters done, or the gallery's page progress. */
    val fraction: Float get() = if (total > 1) read.toFloat() / total else percent / 100f
}

/** Read marks of every title the app knows (library, history, chapters marked read), kept up to date. */
object ReadMarks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val state: StateFlow<Map<String, ReadMark>> by lazy {
        combine(Repo.library, Repo.history, Repo.progress) { l, h, p -> compute(l, h, p) }
            .stateIn(scope, SharingStarted.Eagerly, compute(Repo.library.value, Repo.history.value, Repo.progress.value))
    }

    private fun compute(l: Map<String, LibraryEntry>, h: Map<String, HistoryEntry>, p: Map<String, ReadState>) = compute(
        l, h, p,
        cached = { Repo.chaptersCache[it]?.size ?: 0 },
        single = { key -> Sources.byId(key.substringBefore('|'))?.hasChapters == false },
    )

    /**
     * [cached] = chapters in the list loaded this run, [single] = the title is one gallery (no chapters).
     * Pure, so it is tested on its own.
     */
    fun compute(
        library: Map<String, LibraryEntry>,
        history: Map<String, HistoryEntry>,
        progress: Map<String, ReadState>,
        cached: (String) -> Int,
        single: (String) -> Boolean,
    ): Map<String, ReadMark> {
        val known = HashSet<String>(library.keys).apply { addAll(history.keys) }
        // progress keys are "<title key>#<chapter id>": match the title key at any '#', a known title first
        val done = HashMap<String, Int>()
        for ((k, st) in progress) {
            if (!st.read) continue
            var title: String? = null
            var i = k.indexOf('#')
            val first = i
            while (i > 0 && title == null) { if (k.substring(0, i) in known) title = k.substring(0, i); i = k.indexOf('#', i + 1) }
            if (title == null && first > 0) title = k.substring(0, first)
            if (title != null) done[title] = (done[title] ?: 0) + 1
        }
        val out = HashMap<String, ReadMark>()
        for (key in known + done.keys) {
            val hist = history[key]
            val read = done[key] ?: 0
            if (hist == null && read == 0) continue
            val isSingle = single(key)
            val total = if (isSingle) 1 else maxOf(library[key]?.chapterCount ?: 0, hist?.chapterCount ?: 0, cached(key))
            val finished = (isSingle && (read > 0 || hist?.state?.read == true)) || (total > 0 && read >= total)
            out[key] = ReadMark(read, total, hist?.state?.percent ?: if (finished) 100 else 0, finished)
        }
        return out
    }

    fun finished(key: String): Boolean = state.value[key]?.finished == true

    /** "Hide titles I've finished" for Browse / Home / search lists (never Library or History). */
    fun <T> dropFinished(list: List<T>, marks: Map<String, ReadMark>, key: (T) -> String): List<T> =
        list.filterNot { marks[key(it)]?.finished == true }
}
