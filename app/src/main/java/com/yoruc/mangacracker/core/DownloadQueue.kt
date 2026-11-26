package com.yoruc.mangacracker.core

import com.yoruc.mangacracker.video.VideoRef
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

enum class DlState { QUEUED, RUNNING, PAUSED, FAILED }

/**
 * One download in the queue: a manga chapter (galleries = their one chapter) or a video. Finished downloads leave the
 * queue; what is on disk is the record of them (see [Downloads]).
 */
@Serializable
data class DlTask(
    val id: String,
    val manga: MangaRef? = null,
    val chapter: Chapter? = null,
    val video: VideoRef? = null,
    val state: DlState = DlState.QUEUED,
    /** Pages done / pages (chapters) or per mille (videos). */
    val done: Int = 0,
    val total: Int = 0,
    val bytes: Long = 0L,
    val attempts: Int = 0,
    /** A failed try waits until then (backoff). */
    val retryAt: Long = 0L,
    val error: String = "",
    val added: Long = 0L,
) {
    val isVideo: Boolean get() = video != null
    /** Source id (manga or video site): one download per site at a time. */
    val site: String get() = video?.source ?: manga?.source.orEmpty()
    val title: String get() = video?.let { v -> v.series.ifEmpty { v.title } + (if (v.episode.isNotEmpty()) " · Ep. ${v.episode}" else "") }
        ?: (manga?.title.orEmpty() + (chapter?.let { c -> " · ${c.name}" } ?: ""))
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f

    companion object {
        fun chapterId(mangaKey: String, chapterId: String) = "m|$mangaKey#$chapterId"
        fun videoId(videoKey: String) = "v|$videoKey"
    }
}

/**
 * The queue's rules, apart from Android so they can be tested: FIFO, one task per site at a time, pause / resume /
 * cancel, and failed tries retried with backoff (15 s, 1 min, 4 min) before the task shows as failed.
 */
class DownloadQueue(private val now: () -> Long = System::currentTimeMillis) {
    private val _tasks = MutableStateFlow<List<DlTask>>(emptyList())
    val tasks: StateFlow<List<DlTask>> = _tasks

    /** Called after every change (the app saves the queue). */
    var onChange: (List<DlTask>) -> Unit = {}

    private fun change(f: (List<DlTask>) -> List<DlTask>) { _tasks.update(f); onChange(_tasks.value) }
    private fun edit(id: String, f: (DlTask) -> DlTask) = change { l -> l.map { if (it.id == id) f(it) else it } }

    operator fun get(id: String): DlTask? = _tasks.value.firstOrNull { it.id == id }

    /** Put back a saved queue: anything that was running when the app stopped is waiting again. */
    fun load(saved: List<DlTask>) {
        _tasks.value = saved.map { if (it.state == DlState.RUNNING) it.copy(state = DlState.QUEUED) else it }
    }

    /** Adds what is not queued yet (a failed one is tried again). Returns how many were added or re-queued. */
    fun add(new: List<DlTask>): Int {
        var n = 0
        change { l ->
            val have = l.associateBy { it.id }
            val out = l.toMutableList()
            for (t in new) {
                val old = have[t.id]
                when {
                    old == null -> { out += t.copy(state = DlState.QUEUED, added = if (t.added > 0) t.added else now()); n++ }
                    old.state == DlState.FAILED || old.state == DlState.PAUSED -> {
                        out[out.indexOfFirst { it.id == t.id }] = old.copy(state = DlState.QUEUED, attempts = 0, retryAt = 0, error = ""); n++
                    }
                }
            }
            out
        }
        return n
    }

    fun pause(id: String) = edit(id) { if (it.state == DlState.QUEUED || it.state == DlState.RUNNING) it.copy(state = DlState.PAUSED) else it }
    fun resume(id: String) = edit(id) { if (it.state == DlState.PAUSED || it.state == DlState.FAILED) it.copy(state = DlState.QUEUED, retryAt = 0, attempts = if (it.state == DlState.FAILED) 0 else it.attempts, error = "") else it }
    fun pauseAll() = change { l -> l.map { if (it.state == DlState.QUEUED || it.state == DlState.RUNNING) it.copy(state = DlState.PAUSED) else it } }
    fun resumeAll() = change { l -> l.map { if (it.state == DlState.PAUSED || it.state == DlState.FAILED) it.copy(state = DlState.QUEUED, retryAt = 0, attempts = 0, error = "") else it } }
    fun cancel(id: String) = change { l -> l.filterNot { it.id == id } }
    fun cancelAll() = change { emptyList() }

    /**
     * The next task to start: the oldest waiting one whose retry time has come, from a site with nothing running.
     * It is marked RUNNING.
     */
    fun take(busySites: Set<String>): DlTask? {
        val t = now()
        val next = _tasks.value.firstOrNull { it.state == DlState.QUEUED && it.retryAt <= t && it.site !in busySites } ?: return null
        edit(next.id) { it.copy(state = DlState.RUNNING, error = "") }
        return this[next.id]
    }

    fun progress(id: String, done: Int, total: Int, bytes: Long) = edit(id) { it.copy(done = done, total = total, bytes = bytes) }

    /** Finished: off the queue. */
    fun finished(id: String) = cancel(id)

    /**
     * A try failed. [permanent] (the title is hidden, the site is gone, the disk is full) stops at once; otherwise
     * up to [MAX_TRIES] tries with growing waits. A task paused or cancelled meanwhile stays as it is.
     */
    fun failed(id: String, error: String, permanent: Boolean = false) = edit(id) {
        if (it.state != DlState.RUNNING) return@edit it
        val n = it.attempts + 1
        if (permanent || n >= MAX_TRIES) it.copy(state = DlState.FAILED, attempts = n, error = error)
        else it.copy(state = DlState.QUEUED, attempts = n, retryAt = now() + backoff(n), error = error)
    }

    /** The worker stopped (system, no network): a running task waits again, keeping its tries. */
    fun interrupted(id: String) = edit(id) { if (it.state == DlState.RUNNING) it.copy(state = DlState.QUEUED) else it }

    /** Something can start now or after a backoff wait. */
    fun hasWaiting(): Boolean = _tasks.value.any { it.state == DlState.QUEUED }

    /** The earliest retry time among waiting tasks that are not due yet (null = none). */
    fun nextRetry(): Long? = _tasks.value.filter { it.state == DlState.QUEUED && it.retryAt > now() }.minOfOrNull { it.retryAt }

    companion object {
        const val MAX_TRIES = 4
        fun backoff(attempt: Int): Long = 15_000L * (1L shl (2 * (attempt - 1)).coerceIn(0, 8))
    }
}
