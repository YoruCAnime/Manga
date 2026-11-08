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
 * - anime: AniList (search as you type, covers, every season through its sequel / prequel links);
 * - songs: every opening and ending of each season from its MyAnimeList page, played in FULL from YouTube (YouTube
 *   Music's official audio first, see [YouTube]); the audio address is found when the song plays ([resolve]);
 * - soundtrack albums: archive.org uploads, the best file of each track (FLAC, else the highest MP3), quality shown;
 * - radio: LISTEN.moe (anime songs, 192 kbps Opus), with what's on now.
 */

/** One thing to play. [kind]: "OP", "ED", "OST", "Radio". [url] empty = found on YouTube when it plays. */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val anime: String,
    val kind: String,
    val url: String,
    val order: Int = 0,
    val durationS: Int = 0,
    /** "YouTube · Opus 160 kbps", "FLAC", "MP3 320 kbps", "Opus 192 kbps" */
    val quality: String = "",
    val cover: String = "",
    val live: Boolean = false,
    /** A YouTube link MyAnimeList gives for the song (used first when there is one). */
    val ytUrl: String = "",
    /** The season it is from (all-seasons lists show it). */
    val season: String = "",
)

/** An anime (one season) on AniList. */
data class MusicAnime(
    val id: Int,
    val name: String,
    val english: String,
    val mal: Int?,
    val format: String = "",
    val year: Int? = null,
    val cover: String = "",
    /** The Japanese title (ゼロの使い魔): official soundtracks are named so. */
    val native: String = "",
) {
    val title: String get() = english.ifEmpty { name }
}

/** A soundtrack / song album: on YouTube Music ([url] its playlist, [artist]) or archive.org ([id], [downloads]). */
data class Album(val id: String, val title: String, val downloads: Int, val source: String = "archive", val url: String = "", val artist: String = "")

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

    // ---------------- AniList: search, seasons ----------------

    private const val MEDIA = "id idMal format seasonYear startDate{year month day} title{romaji english native} coverImage{large}"

    private fun media(m: JsonObject) = MusicAnime(
        id = m["id"].int() ?: 0,
        name = m["title"].obj()?.get("romaji").str().orEmpty(),
        english = m["title"].obj()?.get("english").str().orEmpty(),
        mal = m["idMal"].int(),
        format = m["format"].str().orEmpty().replace('_', ' ').let { if (it == "TV SHORT") "TV short" else it },
        year = m["seasonYear"].int() ?: m["startDate"].obj()?.get("year").int(),
        cover = m["coverImage"].obj()?.get("large").str().orEmpty(),
        native = m["title"].obj()?.get("native").str().orEmpty(),
    )

    private suspend fun aniList(query: String, vars: JsonObject): JsonObject? {
        val body = buildJsonObject { put("query", query); put("variables", vars) }
        return Http.json.parseToJsonElement(Http.postJson("https://graphql.anilist.co", body.toString())).obj()?.get("data").obj()
    }

    /** Anime whose name (any language) matches [q], best match first: shown while typing. */
    suspend fun searchAnime(q: String): List<MusicAnime> {
        val d = aniList("query(\$q:String){Page(perPage:12){media(search:\$q,type:ANIME,sort:[SEARCH_MATCH,POPULARITY_DESC]){$MEDIA}}}",
            buildJsonObject { put("q", q.trim()) })
        return d?.get("Page").obj()?.get("media").arr().mapNotNull { it.obj() }.map(::media).filter { it.id > 0 }
    }

    private val seasonCache = java.util.concurrent.ConcurrentHashMap<Int, List<MusicAnime>>()

    /**
     * Every season of [a]'s show in order: its prequels and sequels on AniList, followed both ways (TV, films, OVAs and
     * specials in that chain; side stories and spin-offs are left out).
     */
    suspend fun seasons(a: MusicAnime): List<MusicAnime> {
        seasonCache[a.id]?.let { return it }
        val found = LinkedHashMap<Int, Pair<MusicAnime, Long>>()
        val todo = ArrayDeque(listOf(a.id))
        while (todo.isNotEmpty() && found.size < 30) {
            val id = todo.removeFirst()
            if (id in found) continue
            val m = aniList("query(\$id:Int){Media(id:\$id,type:ANIME){$MEDIA relations{edges{relationType node{id type}}}}}",
                buildJsonObject { put("id", id) })?.get("Media").obj() ?: continue
            val sd = m["startDate"].obj()
            val sort = ((sd?.get("year").int() ?: 9999) * 10000L) + ((sd?.get("month").int() ?: 12) * 100L) + (sd?.get("day").int() ?: 31)
            found[id] = media(m) to sort
            m["relations"].obj()?.get("edges").arr().mapNotNull { it.obj() }.forEach { e ->
                val n = e["node"].obj()
                if (e["relationType"].str() in setOf("SEQUEL", "PREQUEL") && n?.get("type").str() == "ANIME") n?.get("id").int()?.let { if (it !in found) todo.addLast(it) }
            }
        }
        val list = found.values.sortedBy { it.second }.map { it.first }
        list.forEach { seasonCache[it.id] = list }
        return list
    }

    // ---------------- MyAnimeList: the openings and endings ----------------

    private val themeCache = java.util.concurrent.ConcurrentHashMap<Int, List<Track>>()
    private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"

    /** Every opening and ending of season [a] as MyAnimeList lists them (title, singer; played in full from YouTube). */
    suspend fun songs(a: MusicAnime): List<Track> {
        val mal = a.mal ?: return emptyList()
        themeCache[mal]?.let { return it }
        // the desktop page (a phone browser gets MyAnimeList's mobile page, which lists the songs differently)
        val html = Http.get("https://myanimelist.net/anime/$mal", mapOf("User-Agent" to DESKTOP_UA))
        val out = ArrayList<Track>()
        for ((kind, marker) in listOf("OP" to "js-theme-songs opnening", "ED" to "js-theme-songs ending")) {
            val start = html.indexOf(marker).takeIf { it >= 0 } ?: continue
            // up to the next section (the openings' block starts with a big pop-up's markup before its rows)
            val end = html.indexOf("js-theme-songs", start + marker.length).takeIf { it > start } ?: (start + 60_000)
            val block = html.substring(start, minOf(end, html.length))
            Regex("""<td width="84%">(.*?)<span class="theme-song-artist">\s*by\s*(.*?)</span>(.*?)(?=<td width="8%"|</table>|$)""", RegexOption.DOT_MATCHES_ALL)
                .findAll(block).forEachIndexed { i, m ->
                    fun clean(s: String) = org.jsoup.parser.Parser.unescapeEntities(s.replace(Regex("<[^>]+>"), " "), false).replace(Regex("\\s+"), " ").trim()
                    val raw = clean(m.groupValues[1]).replace(Regex("^#?\\d+:\\s*"), "")
                    // the first quoted name (a row can hold two songs: "A" by X (eps 1) 2: "B" by Y)
                    val title = (Regex("\"(.+?)\"").find(raw)?.groupValues?.get(1) ?: raw).trim()
                    val artist = clean(m.groupValues[2]).substringBefore(" (eps").trim()
                    val yt = Regex("""id="youtube_url_\d+" value="([^"]*)"""").find(m.groupValues[3])?.groupValues?.get(1).orEmpty()
                    if (title.isNotEmpty()) out += Track(
                        id = "mal:$mal:$kind${i + 1}", title = title, artist = artist, anime = a.title, kind = kind, url = "",
                        order = i + 1, cover = a.cover, ytUrl = yt.takeIf { it.startsWith("http") }.orEmpty(), season = a.title,
                    )
                }
        }
        // a song listed as opening and ending (Gurenge) plays once
        val once = out.distinctBy { songKey(it) }
        out.clear(); out += once
        themeCache[mal] = out
        out.forEach { known[it.id] = it }
        return out
    }

    /** All the openings and endings of every season of [a]'s show, season by season. */
    suspend fun allSongs(a: MusicAnime): List<Track> =
        seasons(a).flatMap { s -> runCatching { songs(s) }.getOrDefault(emptyList()) }.distinctBy { songKey(it) }   // a film's ED that is also the arc's: once

    /** The same song wherever it's listed: its name (the romaji part) and first singer, letters and digits only. */
    fun songKey(t: Track) = (t.title.substringBefore(" (") + "|" + t.artist.substringBefore(",").substringBefore(" (")).lowercase().filter { it.isLetterOrDigit() || it == '|' }

    // ---------------- YouTube: the full song, found when it plays ----------------

    /** Tracks by id (the phone's player asks for one by its id when it starts it). */
    val known = java.util.concurrent.ConcurrentHashMap<String, Track>()
    private val resolved = java.util.concurrent.ConcurrentHashMap<String, Pair<Track, Long>>()

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private val notTheSong = Regex("\\b(cover|covered|remix|8 ?bit|instrumental|karaoke|piano|nightcore|guitar|violin|lyrics?|english ver|tv ?size|off ?vocal|sped up|slowed|reaction|amv|1 hour|ost)\\b", RegexOption.IGNORE_CASE)

    /** How well a YouTube result fits the song: its title, its singer, a full length; covers / remixes / TV size lose. */
    private fun score(t: Track, h: YouTube.Hit): Int {
        val ht = norm(h.title + " " + h.channel)
        val names = listOf(t.title.substringBefore(" (").trim(), t.title.substringAfter(" (", "").substringBefore(")")).map(::norm).filter { it.length > 1 }
        val singers = t.artist.split(Regex("\\(|\\)|,|&|×| feat\\.? | and |/")).map(::norm).filter { it.length > 1 }
        var s = 0
        if (names.any { n -> n in ht || n.split(' ').filter { it.length > 2 }.let { w -> w.isNotEmpty() && w.all { it in ht } } }) s += 4
        if (singers.any { it in ht }) s += 3
        if (notTheSong.containsMatchIn(h.title) && !notTheSong.containsMatchIn(t.title)) s -= 6
        s += when (h.seconds) { in 150..480 -> 2; in 120..149 -> 0; else -> -4 }
        return s
    }

    /**
     * [t] with a playable address: as it is (albums, radio), or its full song from YouTube (MyAnimeList's own link, else
     * the best YouTube Music song, else the best plain YouTube video). Remembered for 4 hours. Blocking: call it off
     * the main thread.
     */
    fun resolve(t: Track): Track {
        if (t.url.isNotEmpty()) return t
        resolved[t.id]?.let { (r, at) -> if (System.currentTimeMillis() - at < 4 * 3600_000L) return r }
        val q = "${t.title.substringBefore(" (")} ${t.artist.substringBefore(" (")}".trim()
        val pick = t.ytUrl.takeIf { it.isNotEmpty() }?.let { YouTube.Hit(it, t.title, t.artist, if (t.durationS > 0) t.durationS.toLong() else 200) }
            ?: runCatching { YouTube.musicSongs(q) }.getOrDefault(emptyList()).maxByOrNull { score(t, it) }?.takeIf { score(t, it) >= 3 }
            ?: runCatching { YouTube.videos("$q full") }.getOrDefault(emptyList()).maxByOrNull { score(t, it) }?.takeIf { score(t, it) >= 3 }
            ?: throw IllegalStateException("\"${t.title}\" wasn't found on YouTube")
        val (url, quality) = YouTube.audio(pick.url)
        val r = t.copy(url = url, quality = quality, durationS = if (pick.seconds > 0) pick.seconds.toInt() else t.durationS)
        resolved[t.id] = r to System.currentTimeMillis()
        return r
    }

    /** Forgets [id]'s address (it stopped working): the next [resolve] looks again. */
    fun forget(id: String) { resolved.remove(id) }

    // ---------------- soundtrack albums: YouTube Music, archive.org ----------------

    /**
     * Soundtrack and song albums of an anime: YouTube Music's (official OSTs, character-song and image albums; only
     * albums whose title carries the anime's own name or its Japanese title), then archive.org's (titles with the name
     * and OST / soundtrack, most downloaded first; looked for with the English name, the Japanese one and its most telling
     * word: "Frieren: Beyond Journey's End" alone finds 1 of 4, archive.org splits "Journey's" its own way).
     */
    suspend fun albums(english: String, japanese: String = "", native: String = ""): List<Album> {
        val ours = belongsTo(english, japanese, native)
        val yt = runCatching { youTubeAlbums(english, japanese, native, ours) }.getOrDefault(emptyList())
        return yt + archiveAlbums(english, japanese).filter { ours(it.title) }
    }

    private val generic = setOf("the", "and", "season", "movie", "part", "final", "2nd", "3rd", "4th", "new", "original", "soundtrack",
        "collection", "special", "edition", "series", "anime", "tv", "no", "wa", "ga", "to", "of", "zero", "one", "with", "from")

    /**
     * Whether an album title is this anime's: a telling word of its romaji name ("tsukaima"), its Japanese title
     * (ゼロの使い魔), or ALL the telling words of its English name ("attack" + "titan"; one English word alone, like
     * "familiar", also names other shows).
     */
    private fun belongsTo(english: String, japanese: String, native: String): (String) -> Boolean {
        fun words(s: String) = s.lowercase().replace(Regex("['’`]"), "").replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(' ')
            .filter { it.length > 3 && it !in generic }
        val romaji = words(japanese)
        val eng = words(english).filter { it !in romaji }
        val nativeCore = native.replace(Regex("[\\s　:：!！?？]"), "").take(6)
        return { t ->
            val low = " " + t.lowercase().replace(Regex("['’`]"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ") + " "
            romaji.any { " $it " in low } || (eng.size >= 2 && eng.all { " $it " in low }) ||
                (nativeCore.length >= 2 && nativeCore in t.replace(Regex("[\\s　:：!！?？]"), ""))
        }
    }

    /** Fan albums that aren't the show's music: lo-fi / piano / 8-bit / orchestral takes, covers, "(from …)" singles. */
    private val fanAlbum = Regex("lo-?fi|chill|piano|8 ?-?bit|music ?box|orchestral|epic|cover|remix|nightcore|sped up|slowed|guitar|violin|" +
        "lyrical legends|カバー|インスト|inspired|\\(from |from \"|in the style of|tribute|karaoke|instrumental version|relaxing|sleep|study", RegexOption.IGNORE_CASE)
    private val officialAlbum = Regex("original (sound ?track|soundtrack)|\\bost\\b|sound ?track|サウンドトラック|オリジナル|character song|キャラクターソング|" +
        "theme song|主題歌|image album|best album|music collection", RegexOption.IGNORE_CASE)

    private fun youTubeAlbums(english: String, japanese: String, native: String, ours: (String) -> Boolean): List<Album> {
        val queries = listOfNotNull("$japanese soundtrack".takeIf { japanese.isNotBlank() }, "$english original soundtrack".takeIf { english.isNotBlank() },
            native.takeIf { it.isNotBlank() }?.let { "$it サウンドトラック" }, native.takeIf { it.isNotBlank() }).distinct()
        val out = LinkedHashMap<String, Album>()
        for (q in queries) runCatching { YouTube.musicAlbums(q) }.getOrDefault(emptyList())
            .filter { ours(it.title) && !fanAlbum.containsMatchIn(it.title + " " + it.artist) }
            .forEach { out.putIfAbsent(it.url, Album("yt:" + it.url, it.title, 0, "youtube", it.url, it.artist)) }
        // the official soundtracks first, then singles / other albums
        return out.values.sortedByDescending { officialAlbum.containsMatchIn(it.title) }
    }

    private suspend fun archiveAlbums(english: String, japanese: String): List<Album> {
        fun words(s: String) = s.replace(Regex("['’`]"), " ").replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(' ')
            .filter { it.length > 2 && it.lowercase() !in setOf("the", "and", "season", "movie", "part", "2nd", "3rd") }
        val tries = listOf(words(english).take(4), words(japanese).take(4), listOfNotNull(words(english).maxByOrNull { it.length }))
            .filter { it.isNotEmpty() }.distinct()
        val all = LinkedHashMap<String, Album>()
        // any music upload with the show's name: soundtracks, character-song CDs, singles, full-version collections
        // (talk is left out: drama CDs, podcasts, radio shows, audiobooks)
        val talk = Regex("drama|podcast|radio|\\bcast\\b|episode|audiobook|chapter|novel|review|reaction|interview|special talk|ドラマ", RegexOption.IGNORE_CASE)
        val music = Regex("\\bost\\b|soundtrack|sound ?track|bgm|music|song|single|album|\\bcd\\b|opening|ending|\\bop\\b|\\bed\\b|theme|サウンド|キャラクター|主題歌", RegexOption.IGNORE_CASE)
        for (w in tries) {
            val q = "title:(${w.joinToString(" AND ")}) AND mediatype:audio"
            val d = runCatching { Http.json.parseToJsonElement(Http.get("https://archive.org/advancedsearch.php?q=${enc(q)}&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=downloads&sort%5B%5D=downloads+desc&rows=50&output=json")).obj() }.getOrNull()
            d?.get("response").obj()?.get("docs").arr().mapNotNull { it.obj() }.forEach {
                val id = it["identifier"].str().orEmpty()
                val title = it["title"].str().orEmpty()
                if (id.isNotEmpty() && music.containsMatchIn(title) && !talk.containsMatchIn(title)) all.putIfAbsent(id, Album(id, title, it["downloads"].int() ?: 0))
            }
        }
        return all.values.sortedByDescending { it.downloads }
    }

    /**
     * The tracks of [album]: a YouTube Music album's (each played in full from YouTube), or for archive.org per track the
     * best file (FLAC, else the MP3 / OGG with the most kbps).
     */
    suspend fun albumTracks(album: Album, anime: String, cover: String): List<Track> {
        if (album.source == "youtube") {
            return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { YouTube.albumTracks(album.url) }.mapIndexed { i, h ->
                Track(id = "yta:" + h.url, title = h.title, artist = h.channel.removeSuffix(" - Topic").ifEmpty { album.artist }, anime = anime, kind = "OST",
                    url = "", order = i + 1, durationS = h.seconds.toInt(), cover = cover, ytUrl = h.url, season = album.title)
            }.also { l -> l.forEach { known[it.id] = it } }
        }
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
