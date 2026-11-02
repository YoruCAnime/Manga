package com.yoruc.mangacracker.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class LibraryEntry(
    val ref: MangaRef,
    val added: Long,
    val genres: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    val knownChapters: List<String> = emptyList(),
    val chapterCount: Int = 0,
    val latestChapter: String = "",
    val lastChecked: Long = 0L,
    /** When a new chapter last appeared. */
    val lastUpdate: Long = 0L,
)

/** Where the user is in one chapter. */
@Serializable
data class ReadState(
    val page: Int,
    val pages: Int,
    val offset: Int = 0,
    val read: Boolean = false,
    val time: Long = 0L,
) {
    val percent: Int get() = when {
        read -> 100
        pages <= 0 -> 0
        else -> ((page + 1) * 100 / pages).coerceIn(0, 100)
    }
}

/** The last thing read in a title (one per title, newest first in History / Continue reading). */
@Serializable
data class HistoryEntry(
    val ref: MangaRef,
    val chapter: Chapter,
    val state: ReadState,
    val time: Long,
    /** How many chapters the title had when it was last read (0 = unknown); read badges use it. */
    val chapterCount: Int = 0,
)

@Serializable
data class UpdateItem(val ref: MangaRef, val chapter: Chapter, val found: Long)

@Serializable
data class Settings(
    val disabledSources: Set<String> = emptySet(),
    val hiddenTags: List<String> = emptyList(),
    val updateHours: Int = 6,
    val notifyUpdates: Boolean = true,
    val readerGapDp: Int = 0,
    val readerSidePaddingDp: Int = 0,
    /** 0 = black, 1 = navy, 2 = paper white */
    val readerBackground: Int = 0,
    val keepScreenOn: Boolean = true,
    val readerAutoNext: Boolean = true,
    val showReaderPageNumber: Boolean = true,
    /** Auto-scroll speed, 1 = 60 dp a second. */
    val autoScrollSpeed: Float = 1f,
    /** Night filter: dim (0-80 %) and warm tint (0-100 %) over the pages; never the system brightness. */
    val readerNight: Boolean = false,
    val readerDim: Int = 30,
    val readerWarm: Int = 40,
    /** Two pages side by side when the phone is landscape; right-to-left order for Japanese books. */
    val readerTwoPage: Boolean = false,
    val readerRtl: Boolean = true,
    val gridColumns: Int = 3,
    /** Browse / Home / search grids leave out titles whose every known chapter is read. */
    val hideFinished: Boolean = false,
    /** Weekly backup file in Download/MangaCracker. */
    val autoBackup: Boolean = false,
    val lastAutoBackup: Long = 0L,
    val gelbooruUser: String = "",
    val gelbooruKey: String = "",
    val danbooruUser: String = "",
    val danbooruKey: String = "",
    val useExHentai: Boolean = false,
    val lastLibraryCheck: Long = 0L,
    /** App updates from GitHub releases */
    val autoAppUpdates: Boolean = true,
    val lastAppCheck: Long = 0L,
    /** What the last check found (the latest release, newer or not). */
    val appRelease: AppRelease? = null,
    /** "Later" on the update pop-up: no pop-up again for this version (it stays in Settings). */
    val dismissedAppVersion: String = "",
    /** Background check: one notification per version. */
    val notifiedAppVersion: String = "",
    /** Hide manga / pictures marked AI-generated (title words or AI tags); videos are not filtered. */
    val hideAi: Boolean = true,
    /** Videos tab: the source picked last; player subtitles on / off. */
    val lastVideoSource: String = "",
    val videoSubtitles: Boolean = true,
    /** Offline downloads: only on Wi-Fi (unmetered); delete a downloaded chapter once it is read; HLS video height. */
    val downloadWifiOnly: Boolean = true,
    val deleteAfterReading: Boolean = false,
    val videoDownloadHeight: Int = 720,
)

object Repo {
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _library = MutableStateFlow<Map<String, LibraryEntry>>(emptyMap())
    val library: StateFlow<Map<String, LibraryEntry>> = _library
    private val _history = MutableStateFlow<Map<String, HistoryEntry>>(emptyMap())
    val history: StateFlow<Map<String, HistoryEntry>> = _history
    /** "<manga key>#<chapter id>" -> state */
    private val _progress = MutableStateFlow<Map<String, ReadState>>(emptyMap())
    val progress: StateFlow<Map<String, ReadState>> = _progress
    private val _updates = MutableStateFlow<List<UpdateItem>>(emptyList())
    val updates: StateFlow<List<UpdateItem>> = _updates
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings

    /** Titles opened this run (for routes that only carry the key). */
    val refs = ConcurrentHashMap<String, MangaRef>()
    val detailsCache = ConcurrentHashMap<String, MangaDetails>()
    val chaptersCache = ConcurrentHashMap<String, List<Chapter>>()

    private val saveJobs = ConcurrentHashMap<String, Job>()

    private val libSer = MapSerializer(String.serializer(), LibraryEntry.serializer())
    private val histSer = MapSerializer(String.serializer(), HistoryEntry.serializer())
    private val progSer = MapSerializer(String.serializer(), ReadState.serializer())
    private val updSer = ListSerializer(UpdateItem.serializer())

    fun init(context: Context) {
        dir = File(context.filesDir, "data").apply { mkdirs() }
        _library.value = load("library.json", libSer) ?: emptyMap()
        _history.value = load("history.json", histSer) ?: emptyMap()
        _progress.value = load("progress.json", progSer) ?: emptyMap()
        _updates.value = load("updates.json", updSer) ?: emptyList()
        _settings.value = load("settings.json", Settings.serializer()) ?: Settings()
        Safety.userHidden = _settings.value.hiddenTags.map { Safety.norm(it) }.toSet()
        Safety.hideAi = _settings.value.hideAi
        (_library.value.values.map { it.ref } + _history.value.values.map { it.ref }).forEach { refs[it.key] = it }
    }

    private fun <T> load(name: String, ser: KSerializer<T>): T? = runCatching {
        val f = File(dir, name)
        if (f.exists()) Http.json.decodeFromString(ser, f.readText()) else null
    }.getOrNull()

    private fun <T> save(name: String, ser: KSerializer<T>, value: () -> T) {
        saveJobs[name]?.cancel()
        saveJobs[name] = scope.launch {
            delay(400)
            val text = Http.json.encodeToString(ser, value())
            val tmp = File(dir, "$name.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(dir, name)).let { if (!it) { File(dir, name).delete(); tmp.renameTo(File(dir, name)) } }
        }
    }

    private fun saveLibrary() = save("library.json", libSer) { _library.value }
    private fun saveHistory() = save("history.json", histSer) { _history.value }
    private fun saveProgress() = save("progress.json", progSer) { _progress.value }
    private fun saveUpdates() = save("updates.json", updSer) { _updates.value }
    private fun saveSettings() = save("settings.json", Settings.serializer()) { _settings.value }

    fun remember(ref: MangaRef) {
        val old = refs[ref.key]
        // keep the best data we have (listings sometimes lack a cover or tags)
        refs[ref.key] = if (old == null) ref else ref.copy(
            cover = ref.cover.ifEmpty { old.cover },
            tags = ref.tags.ifEmpty { old.tags },
            subtitle = ref.subtitle.ifEmpty { old.subtitle },
        )
    }

    // ---------- library ----------

    fun inLibrary(key: String) = _library.value.containsKey(key)

    fun addToLibrary(ref: MangaRef, details: MangaDetails?, chapters: List<Chapter>?) {
        _library.update {
            it + (ref.key to LibraryEntry(
                ref = ref.copy(cover = details?.ref?.cover?.ifEmpty { null } ?: ref.cover),
                added = System.currentTimeMillis(),
                genres = details?.genres.orEmpty(),
                authors = (details?.authors.orEmpty() + details?.artists.orEmpty()).distinct(),
                knownChapters = chapters?.map { it.id }.orEmpty(),
                chapterCount = chapters?.size ?: 0,
                latestChapter = chapters?.maxByOrNull { it.number }?.name.orEmpty(),
                lastChecked = System.currentTimeMillis(),
            ))
        }
        saveLibrary()
    }

    fun removeFromLibrary(key: String) {
        _library.update { it - key }
        _updates.update { list -> list.filterNot { it.ref.key == key } }
        saveLibrary(); saveUpdates()
    }

    /** Refresh what we know of a library title after its page was opened. */
    fun refreshEntry(ref: MangaRef, details: MangaDetails?, chapters: List<Chapter>?) {
        val e = _library.value[ref.key] ?: return
        _library.update {
            it + (ref.key to e.copy(
                ref = e.ref.copy(title = details?.ref?.title ?: e.ref.title, cover = details?.ref?.cover?.ifEmpty { null } ?: e.ref.cover),
                genres = details?.genres ?: e.genres,
                authors = details?.let { d -> (d.authors + d.artists).distinct() } ?: e.authors,
                knownChapters = chapters?.map { c -> c.id } ?: e.knownChapters,
                chapterCount = chapters?.size ?: e.chapterCount,
                latestChapter = chapters?.maxByOrNull { c -> c.number }?.name ?: e.latestChapter,
            ))
        }
        saveLibrary()
    }

    /** Called by the update check: returns the chapters that are new. */
    fun applyChapterCheck(key: String, chapters: List<Chapter>): List<Chapter> {
        val e = _library.value[key] ?: return emptyList()
        val known = e.knownChapters.toHashSet()
        val fresh = if (known.isEmpty()) emptyList() else chapters.filter { it.id !in known }
        val now = System.currentTimeMillis()
        // the check may see only the newest chapters: keep everything known so far
        val all = e.knownChapters + fresh.map { it.id }
        _library.update {
            it + (key to e.copy(
                knownChapters = if (known.isEmpty()) chapters.map { c -> c.id } else all,
                chapterCount = maxOf(e.chapterCount + fresh.size, chapters.size),
                latestChapter = chapters.maxByOrNull { c -> c.number }?.name ?: e.latestChapter,
                lastChecked = now,
                lastUpdate = if (fresh.isNotEmpty()) now else e.lastUpdate,
            ))
        }
        if (fresh.isNotEmpty()) {
            _updates.update { list -> (fresh.map { UpdateItem(e.ref, it, now) } + list).take(500) }
            saveUpdates()
        }
        saveLibrary()
        return fresh
    }

    fun clearUpdates() { _updates.value = emptyList(); saveUpdates() }

    // ---------- moving a title to another site ----------

    /** Everything a move touched, as it was before (for Undo). */
    data class MoveUndo(
        val oldKey: String,
        val newKey: String,
        val library: Map<String, LibraryEntry?>,
        val history: Map<String, HistoryEntry?>,
        val progress: Map<String, ReadState>,
        val updates: List<UpdateItem>,
    )

    /**
     * Moves the library entry, history and read marks of [oldKey] to [newRef] (the same title on another site).
     * Read marks are carried by chapter number ([SourceMatch.carry]); the library entry keeps its added date.
     */
    fun moveTitle(oldKey: String, newRef: MangaRef, details: MangaDetails?, newChapters: List<Chapter>, carry: SourceMatch.Carry): MoveUndo {
        val newKey = newRef.key
        val undo = MoveUndo(
            oldKey, newKey,
            library = mapOf(oldKey to _library.value[oldKey], newKey to _library.value[newKey]),
            history = mapOf(oldKey to _history.value[oldKey], newKey to _history.value[newKey]),
            progress = _progress.value.filterKeys { it.startsWith("$oldKey#") || it.startsWith("$newKey#") },
            updates = _updates.value.filter { it.ref.key == oldKey || it.ref.key == newKey },
        )
        val now = System.currentTimeMillis()
        val old = _library.value[oldKey]
        if (old != null) {
            val existing = _library.value[newKey]
            _library.update {
                it - oldKey + (newKey to LibraryEntry(
                    ref = newRef.copy(cover = details?.ref?.cover?.ifEmpty { null } ?: newRef.cover.ifEmpty { old.ref.cover }),
                    added = minOf(old.added, existing?.added ?: Long.MAX_VALUE),
                    genres = details?.genres?.ifEmpty { null } ?: old.genres,
                    authors = details?.let { d -> (d.authors + d.artists).distinct() }?.ifEmpty { null } ?: old.authors,
                    knownChapters = newChapters.map { c -> c.id },
                    chapterCount = newChapters.size,
                    latestChapter = newChapters.maxByOrNull { c -> c.number }?.name.orEmpty(),
                    lastChecked = now,
                    lastUpdate = old.lastUpdate,
                ))
            }
        }
        _progress.update { m ->
            val n = m.filterKeys { !it.startsWith("$oldKey#") }.toMutableMap()
            carry.progress.forEach { (id, st) ->
                val k = pkey(newKey, id)
                // a mark the new title already had wins when it is further on
                val mine = n[k]
                if (mine == null || (!mine.read && (st.read || st.page > mine.page))) n[k] = st
            }
            n
        }
        _history.update { h ->
            val moved = carry.history
            val mine = h[newKey]
            val keep = if (moved != null && (mine == null || moved.time >= mine.time)) moved else mine
            (h - oldKey - newKey).let { if (keep != null) it + (newKey to keep) else it }
        }
        _updates.update { list -> list.filterNot { it.ref.key == oldKey } }
        remember(newRef)
        saveLibrary(); saveHistory(); saveProgress(); saveUpdates()
        return undo
    }

    /** Puts back what [moveTitle] changed. */
    fun undoMove(u: MoveUndo) {
        _library.update { m -> u.library.entries.fold(m) { acc, (k, v) -> if (v == null) acc - k else acc + (k to v) } }
        _history.update { m -> u.history.entries.fold(m) { acc, (k, v) -> if (v == null) acc - k else acc + (k to v) } }
        _progress.update { m -> m.filterKeys { !it.startsWith("${u.oldKey}#") && !it.startsWith("${u.newKey}#") } + u.progress }
        _updates.update { list -> (list.filterNot { it.ref.key == u.oldKey || it.ref.key == u.newKey } + u.updates).sortedByDescending { it.found } }
        saveLibrary(); saveHistory(); saveProgress(); saveUpdates()
    }

    // ---------- reading progress ----------

    private fun pkey(mangaKey: String, chapterId: String) = "$mangaKey#$chapterId"

    fun state(mangaKey: String, chapterId: String): ReadState? = _progress.value[pkey(mangaKey, chapterId)]

    fun saveRead(ref: MangaRef, chapter: Chapter, page: Int, pages: Int, offset: Int, chapterCount: Int = 0) {
        val now = System.currentTimeMillis()
        val k = pkey(ref.key, chapter.id)
        val old = _progress.value[k]
        val done = (old?.read == true) || (pages > 0 && page >= pages - 1)
        val st = ReadState(page, pages, offset, done, now)
        _progress.update { it + (k to st) }
        val count = chapterCount.takeIf { it > 0 } ?: _history.value[ref.key]?.chapterCount ?: 0
        _history.update { it + (ref.key to HistoryEntry(refs[ref.key] ?: ref, chapter, st.copy(read = page >= pages - 1 && pages > 0), now, count)) }
        saveProgress(); saveHistory()
    }

    fun setRead(ref: MangaRef, chapters: List<Chapter>, read: Boolean) {
        val now = System.currentTimeMillis()
        _progress.update { m ->
            val n = m.toMutableMap()
            chapters.forEach { c ->
                val k = pkey(ref.key, c.id)
                if (read) n[k] = (m[k] ?: ReadState(0, c.pages)).copy(read = true, time = now) else n.remove(k)
            }
            n
        }
        saveProgress()
    }

    fun lastRead(key: String): HistoryEntry? = _history.value[key]

    fun removeHistory(key: String) { _history.update { it - key }; saveHistory() }
    fun clearHistory() { _history.value = emptyMap(); saveHistory() }

    fun unreadCount(key: String, chapters: List<String>): Int {
        val p = _progress.value
        return chapters.count { p[pkey(key, it)]?.read != true }
    }

    // ---------- settings ----------

    fun updateSettings(f: (Settings) -> Settings) {
        _settings.update(f)
        Safety.userHidden = _settings.value.hiddenTags.map { Safety.norm(it) }.toSet()
        Safety.hideAi = _settings.value.hideAi
        saveSettings()
    }

    // ---------- backup ----------

    /** Puts restored data in place (see Backup): saved at once; update items of titles no longer in the library go. */
    fun restore(library: Map<String, LibraryEntry>, history: Map<String, HistoryEntry>, progress: Map<String, ReadState>, settings: Settings) {
        _library.value = library
        _history.value = history
        _progress.value = progress
        _updates.update { list -> list.filter { it.ref.key in library } }
        (library.values.map { it.ref } + history.values.map { it.ref }).forEach { refs[it.key] = it }
        saveLibrary(); saveHistory(); saveProgress(); saveUpdates()
        updateSettings { settings }
    }
}
