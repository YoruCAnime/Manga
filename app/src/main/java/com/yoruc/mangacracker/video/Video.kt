package com.yoruc.mangacracker.video

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient

/** One episode / video in a list. [url] = its page on the site. */
@Serializable
data class VideoRef(
    val source: String,
    val id: String,
    val title: String,
    val url: String = "",
    val series: String = "",
    val episode: String = "",
    val cover: String = "",
    val duration: String = "",
    val views: String = "",
    /** "EN", "RAW", "ES", "PV" or "" */
    val lang: String = "",
    val tags: List<String> = emptyList(),
    /** Other addresses of the same picture (another server / size), tried when [cover] is refused. */
    val altCovers: List<String> = emptyList(),
) {
    val key: String get() = "$source|$id"
    /** "Series · Episode 3" style second line. */
    val subtitle: String get() = listOfNotNull(episode.ifEmpty { null }?.let { "Episode $it" }, lang.ifEmpty { null }, duration.ifEmpty { null }).joinToString(" · ")
}

data class VideoSub(val url: String, val lang: String, val label: String)

/**
 * What the player needs. [kind]: "mp4", "webm" or "hls". [local] = the video key when this is a download on the phone
 * (see VideoDownloads); [streamKeys] = (group, index) of the HLS variant / audio that was downloaded.
 */
data class VideoStream(
    val url: String,
    val kind: String,
    val subs: List<VideoSub> = emptyList(),
    val local: String = "",
    val streamKeys: List<Pair<Int, Int>> = emptyList(),
)

data class VideoDetails(
    val ref: VideoRef,
    val stream: VideoStream,
    /** Every episode of the same series (same language), in order, including this one. */
    val episodes: List<VideoRef> = emptyList(),
    val tags: List<String> = emptyList(),
    val description: String = "",
)

data class VideoPage(val items: List<VideoRef>, val hasNext: Boolean)

/** A video site. */
interface VideoSource {
    val id: String
    val name: String
    val baseUrl: String
    val blurb: String
    val color: Long

    suspend fun genres(): List<Tag>
    suspend fun latest(page: Int): VideoPage
    suspend fun popular(page: Int): VideoPage
    suspend fun search(q: SearchQuery, page: Int): VideoPage
    /** Stream, tags and the series' episodes. Throws when the video is blocked (minors). */
    suspend fun details(ref: VideoRef): VideoDetails
    /** Every video of [ref]'s series in every language (Raw / subbed / Spanish / preview), by episode. */
    suspend fun series(ref: VideoRef): List<VideoRef> = details(ref).episodes
    /** Called before playing / recording (muchohentai: unlock the stream key). */
    suspend fun beforePlay() {}
    /** HTTP client for the player and the clip recorder (no cache; keeps the site's cookies). */
    val playerClient: OkHttpClient get() = Http.imageClient
    fun webUrl(ref: VideoRef): String = ref.url.ifEmpty { baseUrl }
}

object VideoSources {
    val all: List<VideoSource> = listOf(HentaigasmSource, OppaiStreamSource, MuchoHentaiSource, HentaiMamaSource, NsfwR34Source, PorcoreSource, SfmCompileSource, Rule34VideoSource)
    fun byId(id: String) = all.firstOrNull { it.id == id }
}

/** Minors filter for videos: tag slugs, title words, and each source's own block lists on top. */
object VideoSafety {
    private val TAGS = setOf("loli", "lolicon", "shota", "shotacon", "shoutacon", "pettanko", "toddlercon", "toddler")
    // romaji age words too; not bare "shoujo" ("bishoujo" = pretty girl)
    private val WORDS = Regex(
        "\\b(loli|lolicon|lolis|shota|shotacon|shoutacon|shotas|toddler|toddlercon|child|children|kids?|elementary|shougaku\\w*|shogaku\\w*|youjo|yojo|chuugaku\\w*|chugaku\\w*|pettanko)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun badTag(t: String): Boolean {
        val n = t.trim().lowercase().replace(' ', '-').replace('_', '-')
        return n in TAGS || n.removePrefix("tag-") in TAGS || Safety.isMinorTag(t)
    }

    fun badText(s: String): Boolean = WORDS.containsMatchIn(s)

    /** Settings "Hide futanari" / "Hide furry" (not the AI switch: that one is for reading only). */
    private fun switched(r: VideoRef): Boolean =
        Safety.optTitle(r.title, ai = false) || Safety.optTitle(r.series, ai = false) || r.tags.any { Safety.optTag(it, ai = false) }

    fun blocked(r: VideoRef): Boolean = badText(r.title) || badText(r.series) || r.tags.any { badTag(it) } || switched(r)

    fun check(d: VideoDetails) {
        if (badText(d.ref.title) || badText(d.ref.series) || (d.ref.tags + d.tags).any { badTag(it) }) throw SourceException("This video is hidden")
        if (switched(d.ref) || d.tags.any { Safety.optTag(it, ai = false) }) throw SourceException("This video has a kind you've hidden in Settings (futanari / furry)")
    }
}

/**
 * Covers that loaded, by series name (any source): when a site refuses a picture, the card shows the same series'
 * cover from another episode or another site instead of an empty box.
 */
object SeriesCovers {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun norm(series: String) = series.lowercase().replace(Regex("[^a-z0-9]+"), "")
    fun put(series: String, url: String) { norm(series).takeIf { it.length >= 3 }?.let { map[it] = url } }
    fun get(series: String): String? = map[norm(series)]
}

/** At most one request per [ms] for a site's pages (video files are not paced). */
class Pace(private val ms: Long) {
    private val lock = Mutex()
    private var last = 0L
    suspend fun <T> run(block: suspend () -> T): T {
        lock.withLock {
            val wait = last + ms - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            last = System.currentTimeMillis()
        }
        return block()
    }
}

/** "1:05:03" / "20:17" from seconds. */
fun clock(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Episode number from a title ("… 6 Subbed", "… Episode 4 English Subbed"). */
fun episodeOf(title: String): String =
    Regex("(?:Episode|Ep\\.?)\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)
        ?: Regex("\\s(\\d{1,3})(?:\\s+3D)?\\s+(?:Subbed|Raw|Dubbed|Preview|Uncensored)\\b", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)
        ?: ""

fun episodeNumber(e: String): Float = e.toFloatOrNull() ?: Float.MAX_VALUE

/** Series lists: by episode, then English, Raw, Spanish, preview. */
val seriesOrder: Comparator<VideoRef> = compareBy({ episodeNumber(it.episode) }, { listOf("EN", "RAW", "ES", "PV", "").indexOf(it.lang).let { i -> if (i < 0) 9 else i } })

/** Language badge names in full. */
fun langName(l: String) = when (l) { "EN" -> "English"; "RAW" -> "Raw"; "ES" -> "Español"; "PV" -> "Preview"; else -> l }
