package com.yoruc.mangacracker.core

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.yoruc.mangacracker.video.VideoDownloads
import com.yoruc.mangacracker.video.VideoRef
import com.yoruc.mangacracker.video.VideoSafety
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/** A finished chapter on disk (chapter.json, written last: no file = not finished). */
@Serializable
data class SavedChapter(val chapter: Chapter, val files: List<String>, val bytes: Long, val time: Long)

/** title.json: what the title page and the reader need with no network. */
@Serializable
data class SavedTitle(val ref: MangaRef, val details: MangaDetails? = null, val chapters: List<Chapter> = emptyList(), val cover: String = "")

/** A downloaded title in memory: its folder and finished chapters. */
data class TitleDownloads(
    val ref: MangaRef,
    val dir: File,
    val details: MangaDetails?,
    /** The whole chapter list as last seen (for the title page offline); [saved] = the downloaded ones. */
    val chapters: List<Chapter>,
    val saved: Map<String, SavedChapter>,
    val cover: File?,
) {
    val bytes: Long get() = saved.values.sumOf { it.bytes } + (cover?.length() ?: 0L)
}

/** Thrown for downloads that must not be tried again (hidden title, site gone, disk full). */
class PermanentDownloadError(message: String) : Exception(message)

/**
 * Offline downloads. Layout (app-private, never in the phone's gallery, left out of Android's cloud backup):
 * noBackupFilesDir/downloads/manga/<site>/<title>/title.json + cover.* + <chapter>/0001.jpg ... chapter.json, and
 * downloads/videos/<site>/<video>/ (see [VideoDownloads]). The queue lives in data/downloads.json and is worked by
 * [DownloadWorker] (WorkManager, Wi-Fi only by default).
 */
object Downloads {
    const val WORK = "downloads"
    /** Below this much free space nothing new starts; a running queue pauses under [MIN_FREE_RUN]. */
    const val MIN_FREE_START = 300L * 1024 * 1024
    const val MIN_FREE_RUN = 150L * 1024 * 1024

    lateinit var root: File
        private set
    private var queueFile: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null

    val queue = DownloadQueue()
    /** Running tasks (pause / cancel stop them at once). */
    val jobs = ConcurrentHashMap<String, Job>()

    private val _titles = MutableStateFlow<Map<String, TitleDownloads>>(emptyMap())
    /** manga key -> downloaded chapters. */
    val titles: StateFlow<Map<String, TitleDownloads>> = _titles

    /** Tests: false = queued work waits for the test to run [DownloadRunner] itself (no WorkManager). */
    @Volatile var autoStart = true

    /** Tests: pretend the disk has this much room. */
    @Volatile var freeOverride: (() -> Long)? = null

    fun init(context: Context) {
        val dir = File(context.noBackupFilesDir, "downloads")
        setup(dir, File(context.filesDir, "data/downloads.json"))
    }

    /** The JVM tests: everything under [dir]. */
    fun initForTests(dir: File) = setup(File(dir, "downloads"), File(dir, "downloads.json"))

    private fun setup(dir: File, qf: File) {
        root = dir.apply { mkdirs() }
        queueFile = qf.apply { parentFile?.mkdirs() }
        val saved = runCatching { Http.json.decodeFromString(ListSerializer(DlTask.serializer()), qf.readText()) }.getOrDefault(emptyList())
        queue.load(saved)
        queue.onChange = { persistQueue() }
        rescan()
        VideoDownloads.rescan()
    }

    private fun persistQueue() {
        val f = queueFile ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(300)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(Http.json.encodeToString(ListSerializer(DlTask.serializer()), queue.tasks.value))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    // ---------- names on disk ----------

    /** A file name for any id: the readable part plus a checksum, so two ids never share a folder. */
    fun safe(s: String): String {
        val crc = CRC32().apply { update(s.toByteArray()) }.value
        return s.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_', '.').take(48).ifEmpty { "x" } + "-" + java.lang.Long.toHexString(crc)
    }

    fun mangaDir(ref: MangaRef) = File(root, "manga/${safe(ref.source)}/${safe(ref.id)}")
    fun chapterDir(ref: MangaRef, chapterId: String) = File(mangaDir(ref), safe(chapterId))

    /** Pictures from disk: the reader's page addresses start with this. */
    const val LOCAL = "file:"

    /** What Coil loads for a page address (a File for downloaded pages). */
    fun model(url: String): Any = if (url.startsWith(LOCAL)) File(url.removePrefix(LOCAL)) else url

    // ---------- the index ----------

    /** Reads every title.json / chapter.json (startup and tests). */
    fun rescan() {
        val out = HashMap<String, TitleDownloads>()
        File(root, "manga").listFiles()?.forEach { site ->
            site.listFiles()?.forEach { dir -> readTitle(dir)?.let { out[it.ref.key] = it } }
        }
        _titles.value = out
    }

    private fun readTitle(dir: File): TitleDownloads? {
        val t = runCatching { Http.json.decodeFromString(SavedTitle.serializer(), File(dir, "title.json").readText()) }.getOrNull() ?: return null
        val saved = HashMap<String, SavedChapter>()
        dir.listFiles()?.filter { it.isDirectory }?.forEach { cd ->
            runCatching { Http.json.decodeFromString(SavedChapter.serializer(), File(cd, "chapter.json").readText()) }.getOrNull()
                ?.let { saved[it.chapter.id] = it }
        }
        val cover = t.cover.takeIf { it.isNotEmpty() }?.let { File(dir, it) }?.takeIf { it.exists() }
        return TitleDownloads(t.ref, dir, t.details, t.chapters, saved, cover)
    }

    private fun refreshTitle(ref: MangaRef) {
        val t = readTitle(mangaDir(ref))
        _titles.update { if (t == null || t.saved.isEmpty()) it - ref.key else it + (ref.key to t) }
    }

    fun isDownloaded(mangaKey: String, chapterId: String): Boolean = _titles.value[mangaKey]?.saved?.containsKey(chapterId) == true

    /** The downloaded pages of a chapter (null = not downloaded): the reader opens them with no network. */
    fun localPages(mangaKey: String, chapterId: String): List<Page>? {
        val t = _titles.value[mangaKey] ?: return null
        val c = t.saved[chapterId] ?: return null
        val dir = File(t.dir, safe(chapterId))
        val files = c.files.map { File(dir, it) }
        if (files.isEmpty() || files.any { !it.exists() }) return null
        return files.mapIndexed { i, f -> Page(i, LOCAL + f.absolutePath) }
    }

    /** The chapter list saved with a downloaded title (the title page and reader offline). */
    fun savedChapters(mangaKey: String): List<Chapter>? = _titles.value[mangaKey]?.let { t ->
        t.chapters.ifEmpty { t.saved.values.map { it.chapter }.sortedByDescending { it.number } }
    }

    fun savedDetails(mangaKey: String): MangaDetails? = _titles.value[mangaKey]?.details

    // ---------- storage ----------

    fun freeBytes(): Long = freeOverride?.invoke() ?: runCatching { root.usableSpace }.getOrDefault(Long.MAX_VALUE)

    fun usedBytes(): Long = _titles.value.values.sumOf { it.bytes } + VideoDownloads.videos.value.values.sumOf { it.bytes }

    // ---------- queue actions ----------

    /** Queues chapters of [ref]; the answer is a short note for the in-window toast. */
    fun enqueueChapters(ctx: Context?, ref: MangaRef, chapters: List<Chapter>, details: MangaDetails? = null): String {
        if (Safety.blocked(ref) || (details != null && Safety.blocked(details))) return "This title is hidden, so it can't be downloaded"
        if (freeBytes() < MIN_FREE_START) return "Not enough free space on the phone (${size(freeBytes())} left)"
        val todo = chapters.filterNot { isDownloaded(ref.key, it.id) }
        if (todo.isEmpty()) return if (chapters.size == 1) "Already downloaded" else "Those chapters are downloaded already"
        Repo.remember(ref)
        // oldest first, so a series downloads in reading order
        val n = queue.add(todo.sortedBy { c -> chapters.indexOf(c).let { -it } }.map { c ->
            DlTask(DlTask.chapterId(ref.key, c.id), manga = Repo.refs[ref.key] ?: ref, chapter = c)
        })
        ctx?.let { kick(it) }
        return if (n == 0) "Already in the queue" else "Downloading $n chapter${if (n == 1) "" else "s"}"
    }

    fun enqueueVideo(ctx: Context?, ref: VideoRef): String {
        if (VideoSafety.blocked(ref)) return "This video is hidden"
        if (freeBytes() < MIN_FREE_START) return "Not enough free space on the phone (${size(freeBytes())} left)"
        if (VideoDownloads.videos.value.containsKey(ref.key)) return "Already downloaded"
        val n = queue.add(listOf(DlTask(DlTask.videoId(ref.key), video = ref)))
        ctx?.let { kick(it) }
        return if (n == 0) "Already in the queue" else "Downloading the video"
    }

    fun pause(id: String) { queue.pause(id); jobs[id]?.cancel() }
    fun resume(ctx: Context?, id: String) { queue.resume(id); ctx?.let { kick(it) } }
    fun pauseAll() { queue.pauseAll(); jobs.values.forEach { it.cancel() } }
    fun resumeAll(ctx: Context?) { queue.resumeAll(); ctx?.let { kick(it) } }

    /** Off the queue; a half-done download is deleted. */
    fun cancel(id: String) {
        val t = queue[id]
        queue.cancel(id)
        val job = jobs[id]
        if (job != null) job.cancel() else t?.let { removePartial(it) }
    }

    fun cancelAll() { queue.tasks.value.forEach { cancel(it.id) } }

    /** What a cancelled task left behind (a chapter without chapter.json, a video without video.json). */
    fun removePartial(t: DlTask) {
        val m = t.manga; val c = t.chapter; val v = t.video
        if (m != null && c != null && !isDownloaded(m.key, c.id)) {
            chapterDir(m, c.id).deleteRecursively()
            if (_titles.value[m.key] == null) mangaDir(m).deleteRecursively()
        }
        if (v != null) VideoDownloads.removePartial(v)
    }

    fun deleteChapter(mangaKey: String, chapterId: String) {
        val t = _titles.value[mangaKey] ?: return
        File(t.dir, safe(chapterId)).deleteRecursively()
        if (t.saved.keys == setOf(chapterId)) t.dir.deleteRecursively()
        refreshTitle(t.ref)
    }

    fun deleteTitle(mangaKey: String) {
        val t = _titles.value[mangaKey] ?: return
        queue.tasks.value.filter { it.manga?.key == mangaKey }.forEach { cancel(it.id) }
        t.dir.deleteRecursively()
        _titles.update { it - mangaKey }
    }

    /** Called by the worker when a chapter's pages are all on disk. */
    fun chapterFinished(ref: MangaRef, chapter: Chapter, files: List<String>, bytes: Long) {
        val dir = chapterDir(ref, chapter.id)
        writeJson(File(dir, "chapter.json"), Http.json.encodeToString(SavedChapter.serializer(), SavedChapter(chapter, files, bytes, System.currentTimeMillis())))
        saveTitleInfo(ref)
        refreshTitle(ref)
    }

    /** title.json with the best of what is known: the library / opened ref, details, the whole chapter list. */
    fun saveTitleInfo(ref: MangaRef, cover: String? = null) {
        val dir = mangaDir(ref).apply { mkdirs() }
        val f = File(dir, "title.json")
        val old = runCatching { Http.json.decodeFromString(SavedTitle.serializer(), f.readText()) }.getOrNull()
        val best = Repo.library.value[ref.key]?.ref ?: Repo.refs[ref.key] ?: ref
        val t = SavedTitle(
            ref = best,
            details = Repo.detailsCache[ref.key] ?: old?.details,
            chapters = Repo.chaptersCache[ref.key]?.takeIf { it.isNotEmpty() } ?: old?.chapters.orEmpty(),
            cover = cover ?: old?.cover.orEmpty(),
        )
        writeJson(f, Http.json.encodeToString(SavedTitle.serializer(), t))
    }

    fun writeJson(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    // ---------- the worker ----------

    /** Starts (or chains) the download worker. [replace] = the Wi-Fi setting changed. */
    fun kick(ctx: Context, replace: Boolean = false) {
        if (!autoStart) return
        val wifi = Repo.settings.value.downloadWifiOnly
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .build()
        runCatching {
            WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }

    /** Is the phone on a metered network (mobile data) or none? (for "Waiting for Wi-Fi") */
    fun onMeteredOrNone(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching true
        !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    fun size(b: Long): String = when {
        b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", b / (1024.0 * 1024 * 1024))
        b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.0f MB", b / (1024.0 * 1024))
        b >= 1024 -> "${b / 1024} KB"
        else -> "$b B"
    }
}
