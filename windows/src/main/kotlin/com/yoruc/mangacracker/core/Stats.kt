package com.yoruc.mangacracker.core

import android.content.Context
import com.yoruc.mangacracker.video.VideoRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One day of reading and watching (the day in this device's own time zone). */
@Serializable
data class DayLog(
    /** Time spent in the reader (page moves less than [Stats.READ_GAP] apart). */
    val readMs: Long = 0L,
    /** Time a video really played. */
    val watchMs: Long = 0L,
    /** Chapters finished that day ("<title key>#<chapter id>"). */
    val chapters: List<String> = emptyList(),
    /** Videos watched that day (their keys). */
    val videos: List<String> = emptyList(),
) {
    val active: Boolean get() = chapters.isNotEmpty() || videos.isNotEmpty() || readMs >= 60_000L || watchMs >= 60_000L
}

@Serializable
data class StatsFile(
    /** The first day with measured reading time; days before it were filled in from the history (chapters, videos). */
    val since: String = "",
    val days: Map<String, DayLog> = emptyMap(),
)

/**
 * Reading and watching stats, kept per day in data/stats.json. Fed by [Repo.saveRead] (each page move) and
 * [VideoRepo.save] (every few seconds while a video plays). On the first start the past is filled in from what the app
 * already knew: chapters finished (by when they were last read; chapters marked read in bulk are left out) and
 * videos watched (with how far in you got).
 */
object Stats {
    /** Longer than this between two page moves is a break, not reading. */
    const val READ_GAP = 120_000L
    /** At most this much play time from one save to the next (saves come every few seconds while a video plays). */
    private const val WATCH_STEP = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var file: File? = null
    private var saveJob: Job? = null
    private val _data = MutableStateFlow(StatsFile())
    val data: StateFlow<StatsFile> = _data.asStateFlow()

    private var lastRead = 0L
    private var lastWatchKey = ""
    private var lastWatchPos = 0L
    private var lastWatchAt = 0L

    fun day(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    fun today(): String = LocalDate.now().toString()

    /** After [Repo.init] and [VideoRepo.init]: the history fills in the days before stats were kept. */
    fun init(context: Context) {
        val f = File(context.filesDir, "data/stats.json").apply { parentFile?.mkdirs() }
        file = f
        val loaded = runCatching { Http.json.decodeFromString(StatsFile.serializer(), f.readText()) }.getOrNull()
        if (loaded != null) _data.value = loaded
        else { _data.value = StatsFile(today(), backfill()); save(now = true) }
    }

    /** The days already in the history, without reading times (those were never measured). */
    private fun backfill(): Map<String, DayLog> = runCatching {
        val m = HashMap<String, DayLog>()
        val prog = Repo.progress.value
        // "mark all read" stamps many chapters with the same moment: those weren't read that day
        val bulk = prog.values.groupingBy { it.time }.eachCount().filterValues { it > 3 }.keys
        prog.forEach { (k, s) ->
            if (s.read && s.time > 0 && s.time !in bulk) {
                val d = day(s.time); val l = m[d] ?: DayLog()
                m[d] = l.copy(chapters = l.chapters + k)
            }
        }
        VideoRepo.watched().forEach { w ->
            if (w.time > 0 && w.positionMs >= 30_000L) {
                val d = day(w.time); val l = m[d] ?: DayLog()
                m[d] = l.copy(videos = l.videos + w.ref.key, watchMs = l.watchMs + minOf(w.positionMs, w.durationMs))
            }
        }
        m
    }.getOrDefault(emptyMap())

    /** A page move in the reader; [chapterKey] = "<title key>#<chapter id>", [finished] = that chapter was just finished. */
    fun reading(chapterKey: String, finished: Boolean) {
        val now = System.currentTimeMillis()
        val gap = now - lastRead
        val add = if (lastRead > 0 && gap in 1..READ_GAP) gap else 0L
        lastRead = now
        if (add == 0L && !finished) return
        edit { d -> d.copy(readMs = d.readMs + add, chapters = if (finished && chapterKey !in d.chapters) d.chapters + chapterKey else d.chapters) }
    }

    /** A video's place, saved every few seconds while it plays: only time that really played counts. */
    fun watching(key: String, posMs: Long) {
        val now = System.currentTimeMillis()
        val add = if (key == lastWatchKey && lastWatchAt > 0) minOf(now - lastWatchAt, posMs - lastWatchPos, WATCH_STEP).coerceAtLeast(0L) else 0L
        lastWatchKey = key; lastWatchPos = posMs; lastWatchAt = now
        if (add <= 0L) return
        edit { d -> d.copy(watchMs = d.watchMs + add, videos = if (key in d.videos) d.videos else d.videos + key) }
    }

    private fun edit(f: (DayLog) -> DayLog) {
        val t = today()
        _data.update { s -> s.copy(days = s.days + (t to f(s.days[t] ?: DayLog()))) }
        save()
    }

    /** Written at most every 10 s (page moves come often), at once for the first file. */
    private fun save(now: Boolean = false) {
        if (saveJob?.isActive == true && !now) return
        saveJob = scope.launch {
            if (!now) delay(10_000)
            runCatching {
                val f = file ?: return@launch
                val tmp = File(f.path + ".tmp")
                tmp.writeText(Http.json.encodeToString(StatsFile.serializer(), _data.value))
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        }
    }

    /** Days in a row with some reading or watching, up to today (or yesterday, while today is still empty). */
    fun streak(days: Map<String, DayLog>): Int {
        var d = LocalDate.now()
        if (days[d.toString()]?.active != true) d = d.minusDays(1)
        var n = 0
        while (days[d.toString()]?.active == true) { n++; d = d.minusDays(1) }
        return n
    }

    fun longestStreak(days: Map<String, DayLog>): Int {
        var best = 0; var run = 0; var prev: LocalDate? = null
        days.filterValues { it.active }.keys.sorted().forEach { k ->
            val d = LocalDate.parse(k)
            run = if (prev != null && prev!!.plusDays(1) == d) run + 1 else 1
            if (run > best) best = run
            prev = d
        }
        return best
    }
}
