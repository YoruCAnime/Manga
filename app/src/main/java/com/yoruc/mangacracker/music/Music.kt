package com.yoruc.mangacracker.music

import com.yoruc.mangacracker.core.Http
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder

/**
 * The Music tab's sources (the same file in the phone and the Windows app; each app plays them its own way, see
 * [MusicPlayer]). Audio only:
 * - songs: kara.moe's FULL versions (no TV size) of an anime's openings, endings and other songs: 320 kbps MP3s or full
 *   music videos played as sound;
 * - soundtrack albums: archive.org uploads, the best file of each track (FLAC, else the highest MP3), quality shown;
 * - radio: LISTEN.moe (anime songs, 192 kbps Opus), with what's on now.
 * Covers come from AniList (kara.moe names each anime's AniList id).
 */

/** One thing to play. [kind]: "OP", "ED", "Song" (inserts, character / image songs), "OST", "Radio". */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val anime: String,
    val kind: String,
    val url: String,
    val order: Int = 0,
    val durationS: Int = 0,
    /** "MP3 320 kbps", "FLAC", "music video", "Opus 192 kbps" */
    val quality: String = "",
    val cover: String = "",
    val live: Boolean = false,
)

/** An anime on kara.moe; [songs] = how many karaoke entries it has (full versions are a part of them). */
data class MusicAnime(val id: String, val name: String, val english: String, val anilist: Int?, val songs: Int, val cover: String = "")

/** A soundtrack album on archive.org. */
data class Album(val id: String, val title: String, val downloads: Int)

/** What each app's player does; the Music screen and the player bar only talk to this. */
interface MusicPlayer {
    val now: StateFlow<Track?>
    val playing: StateFlow<Boolean>
    /** Milliseconds; duration 0 = unknown / live. */
    val position: StateFlow<Long>
    val duration: StateFlow<Long>
    val queue: StateFlow<List<Track>>
    val index: StateFlow<Int>
    val shuffle: StateFlow<Boolean>
    /** [REPEAT_ALL] (the default: the list starts over after the last), [REPEAT_ONE] (this track again), [REPEAT_OFF]. */
    val repeat: StateFlow<Int>
    fun setRepeat(mode: Int)
    /** Plays [list] from [start]; [shuffled] = in a random order (the one started first stays first). */
    fun play(list: List<Track>, start: Int = 0, shuffled: Boolean = false)
    fun toggle()
    /** Pauses (a video starting, the reader's own sounds): nothing when already paused. */
    fun pause()
    fun next()
    fun previous()
    fun seek(ms: Long)
    fun setShuffle(on: Boolean)
    /** Stops and empties the queue (the player bar goes). */
    fun stop()
    /** 0..1, kept in Settings. */
    fun setVolume(v: Float)
}

const val REPEAT_OFF = 0
const val REPEAT_ALL = 1
const val REPEAT_ONE = 2

object Music {
    /** Set by each app at start. */
    lateinit var player: MusicPlayer
    val ready get() = ::player.isInitialized

    private fun JsonElement?.obj() = this as? JsonObject
    private fun JsonElement?.arr() = (this as? JsonArray) ?: JsonArray(emptyList())
    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull
    private fun JsonElement?.int() = (this as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toInt()
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---------------- kara.moe ----------------

    private const val KARA = "https://kara.moe"

    /** Anime whose name (any language) has [q] in it. */
    suspend fun searchAnime(q: String): List<MusicAnime> {
        val d = Http.json.parseToJsonElement(Http.get("$KARA/api/karas/tags?type=1&filter=${enc(q.trim())}&from=0&size=40")).obj()
        val list = d?.get("content").arr().mapNotNull { it.obj() }.map { t ->
            val i18n = t["i18n"].obj()
            MusicAnime(
                id = t["tid"].str().orEmpty(),
                name = t["name"].str().orEmpty(),
                english = i18n?.get("eng").str() ?: t["name"].str().orEmpty(),
                anilist = t["external_database_ids"].obj()?.get("anilist").int(),
                songs = t["karacount"].obj()?.values?.sumOf { it.int() ?: 0 } ?: 0,
            )
        }.filter { it.id.isNotEmpty() }
        val covers = runCatching { aniListCovers(list.mapNotNull { it.anilist }) }.getOrDefault(emptyMap())
        return list.map { a -> a.anilist?.let { covers[it] }?.let { a.copy(cover = it) } ?: a }
    }

    /**
     * The full songs of anime [a]: full versions only (versions "Full", or a music video / audio of song length),
     * never TV size or instrumental ("Off Vocal"). Each is an OP / ED / Song as the TV-size entry it belongs to says;
     * one per song (audio files first: no picture to download).
     */
    suspend fun songs(a: MusicAnime): List<Track> {
        val d = Http.json.parseToJsonElement(Http.get("$KARA/api/karas/search?q=t:${a.id}~1&from=0&size=400")).obj()
        val all = d?.get("content").arr().mapNotNull { it.obj() }
        val byKid = all.associateBy { it["kid"].str() }
        fun names(k: JsonObject?, field: String) = k?.get(field).arr().mapNotNull { it.obj()?.get("name").str() }
        fun kindOf(types: List<String>) = when {
            "OP" in types -> "OP"
            "ED" in types -> "ED"
            else -> null
        }
        val full = all.filter { k ->
            val versions = names(k, "versions")
            val types = names(k, "songtypes")
            val dur = k["duration"].int() ?: 0
            "Off Vocal" !in versions && ("Full" in versions || (("MV" in types || "AUDIO" in types) && dur >= 150 && versions.isEmpty()))
        }
        val tracks = full.mapNotNull { k ->
            val file = k["mediafile"].str() ?: return@mapNotNull null
            val parent = k["parents"].arr().mapNotNull { byKid[it.str()] }.firstOrNull()
            val kind = kindOf(names(parent, "songtypes")) ?: kindOf(names(k, "songtypes")) ?: "Song"
            val titles = k["titles"].obj()
            val title = titles?.get(k["titles_default_language"].str() ?: "eng").str() ?: titles?.values?.firstOrNull().str() ?: "?"
            val dur = k["duration"].int() ?: 0
            val size = (k["mediasize"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
            val audio = file.substringAfterLast('.').lowercase() in setOf("mp3", "m4a", "ogg", "opus", "flac")
            Track(
                id = "kara:" + k["kid"].str(), title = title, artist = names(k, "singers").joinToString(", "),
                anime = a.english.ifEmpty { a.name }, kind = kind, url = "$KARA/downloads/medias/$file",
                order = (parent?.get("songorder") ?: k["songorder"]).int() ?: 0, durationS = dur,
                quality = if (audio && dur > 0) "MP3 ${(size * 8 / dur / 1000 / 32 * 32).coerceAtMost(320)} kbps" else "music video, sound only",
                cover = a.cover,
            ) to audio
        }
        // one per song: an audio file first, then the smallest
        return tracks.groupBy { (t, _) -> t.kind + "|" + t.title.lowercase().filter { it.isLetterOrDigit() } }
            .map { (_, v) -> v.sortedWith(compareByDescending<Pair<Track, Boolean>> { it.second }).first().first }
            .sortedWith(compareBy({ listOf("OP", "ED", "Song").indexOf(it.kind) }, { if (it.order == 0) 99 else it.order }, { it.title }))
    }

    // ---------------- archive.org soundtrack albums ----------------

    /**
     * Soundtrack albums of an anime on archive.org (titles with its name and OST / soundtrack), most downloaded first.
     * Tried with the English name, then the Japanese one, then its most telling word, until something is found
     * ("Frieren: Beyond Journey's End" itself finds nothing: archive.org splits "Journey's" its own way).
     */
    suspend fun albums(english: String, japanese: String = ""): List<Album> {
        fun words(s: String) = s.replace(Regex("['’`]"), " ").replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(' ')
            .filter { it.length > 2 && it.lowercase() !in setOf("the", "and", "season", "movie", "part", "2nd", "3rd") }
        val tries = listOf(words(english).take(4), words(japanese).take(4), listOfNotNull(words(english).maxByOrNull { it.length }))
            .filter { it.isNotEmpty() }.distinct()
        for (w in tries) {
            val q = "title:(${w.joinToString(" AND ")}) AND title:(OST OR soundtrack OR \"original soundtrack\" OR BGM) AND mediatype:audio"
            val d = Http.json.parseToJsonElement(Http.get("https://archive.org/advancedsearch.php?q=${enc(q)}&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=downloads&sort%5B%5D=downloads+desc&rows=30&output=json")).obj()
            val found = d?.get("response").obj()?.get("docs").arr().mapNotNull { it.obj() }.map {
                Album(it["identifier"].str().orEmpty(), it["title"].str().orEmpty(), it["downloads"].int() ?: 0)
            }.filter { it.id.isNotEmpty() }
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    /** The tracks of [album]: per track the best file (FLAC, else the MP3 / OGG with the most kbps). */
    suspend fun albumTracks(album: Album, anime: String, cover: String): List<Track> {
        val m = Http.json.parseToJsonElement(Http.get("https://archive.org/metadata/${album.id}")).obj() ?: return emptyList()
        val files = m["files"].arr().mapNotNull { it.obj() }.filter {
            val n = it["name"].str().orEmpty().lowercase()
            n.endsWith(".flac") || n.endsWith(".mp3") || n.endsWith(".ogg") || n.endsWith(".m4a") || n.endsWith(".opus")
        }
        fun kbps(f: JsonObject): Int {
            val len = f["length"].str()?.let { l -> if (':' in l) l.split(':').fold(0.0) { a, p -> a * 60 + (p.toDoubleOrNull() ?: 0.0) } else l.toDoubleOrNull() } ?: 0.0
            val size = f["size"].str()?.toLongOrNull() ?: 0L
            return if (len > 0) (size * 8 / len / 1000).toInt() else 0
        }
        fun rank(f: JsonObject): Int {
            val n = f["name"].str().orEmpty().lowercase()
            return when {
                n.endsWith(".flac") -> 10_000
                f["source"].str() == "derivative" -> kbps(f) - 1000   // archive.org's own lower copies last
                else -> kbps(f)
            }
        }
        val best = files.groupBy { it["name"].str().orEmpty().substringBeforeLast('.').removeSuffix("_64kb").removeSuffix("_vbr").lowercase() }
            .mapNotNull { (_, v) -> v.maxByOrNull(::rank) }
        return best.mapIndexed { i, f ->
            val name = f["name"].str().orEmpty()
            val len = f["length"].str()?.let { l -> if (':' in l) l.split(':').fold(0.0) { a, p -> a * 60 + (p.toDoubleOrNull() ?: 0.0) } else l.toDoubleOrNull() } ?: 0.0
            val title = f["title"].str()?.takeIf { it.isNotBlank() }
                ?: name.substringAfterLast('/').substringBeforeLast('.').replace(Regex("^\\d{1,3}[ ._-]+"), "").trim()
            val q = if (name.lowercase().endsWith(".flac")) "FLAC" else "${name.substringAfterLast('.').uppercase()} ${kbps(f)} kbps"
            Track(
                id = "ia:${album.id}/$name", title = title, artist = f["artist"].str() ?: f["creator"].str() ?: "", anime = anime,
                kind = "OST", url = "https://archive.org/download/${album.id}/" + name.split('/').joinToString("/") { enc(it) },
                order = f["track"].str()?.substringBefore('/')?.toIntOrNull() ?: (i + 1), durationS = len.toInt(), quality = q, cover = cover,
            )
        }.sortedWith(compareBy({ it.order }, { it.title }))
    }

    // ---------------- LISTEN.moe radio ----------------

    const val RADIO_URL = "https://listen.moe/opus"

    private val _radioNow = MutableStateFlow<Track?>(null)
    /** What the radio plays now (title, artist, anime), while [watchRadio] is on. */
    val radioNow: StateFlow<Track?> = _radioNow
    private var socket: WebSocket? = null

    fun radioTrack(): Track = _radioNow.value?.copy(url = RADIO_URL)
        ?: Track("radio", "LISTEN.moe radio", "Anime songs, all day", "Random anime", "Radio", RADIO_URL, quality = "Opus 192 kbps", live = true)

    /** Follows the radio's "now playing" (a small websocket) until [unwatchRadio]. */
    @Synchronized fun watchRadio() {
        if (socket != null) return
        val heart = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "listen-moe").apply { isDaemon = true } }
        socket = Http.client.newWebSocket(Http.request("wss://listen.moe/gateway_v2"), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = runCatching { Http.json.parseToJsonElement(text).obj() }.getOrNull() ?: return
                when (m["op"].int()) {
                    0 -> m["d"].obj()?.get("heartbeat").int()?.let { ms ->
                        heart.scheduleAtFixedRate({ runCatching { webSocket.send("{\"op\":9}") } }, ms.toLong(), ms.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
                    }
                    1 -> m["d"].obj()?.get("song").obj()?.let { s ->
                        val artists = s["artists"].arr().mapNotNull { it.obj()?.let { a -> a["nameRomaji"].str() ?: a["name"].str() } }
                        val source = s["sources"].arr().mapNotNull { it.obj()?.let { a -> a["nameRomaji"].str() ?: a["name"].str() } }.firstOrNull()
                        val image = s["albums"].arr().firstNotNullOfOrNull { it.obj()?.get("image").str() }
                        _radioNow.value = Track("radio", s["title"].str() ?: "LISTEN.moe radio", artists.joinToString(", "),
                            source ?: "LISTEN.moe radio", "Radio", RADIO_URL, durationS = s["duration"].int() ?: 0,
                            quality = "Opus 192 kbps", cover = image?.let { "https://cdn.listen.moe/covers/$it" }.orEmpty(), live = true)
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { heart.shutdownNow(); synchronized(this@Music) { socket = null } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { heart.shutdownNow(); synchronized(this@Music) { socket = null } }
        })
    }

    @Synchronized fun unwatchRadio() { socket?.close(1000, null); socket = null }

    // ---------------- AniList covers ----------------

    private val coverCache = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** Cover pictures of AniList ids (one request for all, remembered). */
    suspend fun aniListCovers(ids: List<Int>): Map<Int, String> {
        val missing = ids.distinct().filter { !coverCache.containsKey(it) }
        if (missing.isNotEmpty()) {
            val body = buildJsonObject {
                put("query", "query(\$ids:[Int]){Page(perPage:50){media(id_in:\$ids,type:ANIME){id coverImage{large}}}}")
                put("variables", buildJsonObject { put("ids", buildJsonArray { missing.take(50).forEach { add(JsonPrimitive(it)) } }) })
            }
            val d = Http.json.parseToJsonElement(Http.postJson("https://graphql.anilist.co", body.toString())).obj()
            d?.get("data").obj()?.get("Page").obj()?.get("media").arr().mapNotNull { it.obj() }.forEach { m ->
                val id = m["id"].int() ?: return@forEach
                m["coverImage"].obj()?.get("large").str()?.let { coverCache[id] = it }
            }
        }
        return ids.mapNotNull { id -> coverCache[id]?.let { id to it } }.toMap()
    }
}
