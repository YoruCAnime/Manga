package com.yoruc.mangacracker.video

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.core.arr
import com.yoruc.mangacracker.core.bool
import com.yoruc.mangacracker.core.get
import com.yoruc.mangacracker.core.int
import com.yoruc.mangacracker.core.long
import com.yoruc.mangacracker.core.str
import com.yoruc.mangacracker.core.text
import com.yoruc.mangacracker.core.urlEncode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap

/**
 * hentaihaven.xxx: a Next.js front on a WordPress catalogue. Lists of series come as JSON from /api/manga/ (24 a
 * page: sort, genre id, search); a series page lists its episodes, an episode page names its HLS playlist
 * (octopusmanifest.org) in the player's data. In the UK the site asks for a real age verification (AgeVerif) before
 * any video: its own location check says so, and then we stop and say so instead of reading on (works on a VPN).
 * Spec: _dev_tools/specs/hentaihaven.md.
 */
object HentaiHavenSource : VideoSource {
    override val id = "hentaihaven"
    override val name = "HentaiHaven"
    override val baseUrl = "https://hentaihaven.xxx"
    override val blurb = "hentaihaven.xxx · series, English subs (VPN in the UK)"
    override val color = 0xFFD946EFL

    private val pace = Pace(900)
    private const val CMS = "https://cms.hentaihaven.xxx/api/v1"
    private const val IMG = "https://img.hentaihaven.xxx/"
    /** Series refs (every list is a list of series) carry this id prefix; their details() play episode 1. */
    private const val SERIES = "tv:"

    init {
        // the browser sends the site as Referer to the playlist host
        Http.referers["octopusmanifest.org"] = "$baseUrl/"
    }

    // ---------- the age check ----------

    @Volatile private var openAt = 0L

    /**
     * The site's own location check (its player asks the same before it loads a video). Where it wants an age
     * verification we stop; an "open" answer is kept for ten minutes, a refusal is asked again every time.
     */
    private suspend fun gate() {
        if (System.currentTimeMillis() - openAt < 10 * 60_000L) return
        val (code, body) = pace.run {
            Http.getRaw("$CMS/user-status", mapOf("Accept" to "application/json", "Origin" to baseUrl, "Referer" to "$baseUrl/", "Cache-Control" to "no-cache"))
        }
        val d = runCatching { Http.json.parseToJsonElement(body)["data"] }.getOrNull()
        if (code != 200 || d == null) throw SourceException("HentaiHaven did not answer its location check (HTTP $code). Try again in a moment.")
        if (d.bool("isBlocked") && d.bool("requiresAgeVerification") && !d.bool("isAgeVerified")) {
            // connections made before the VPN came on keep going around it: the next try starts fresh ones
            runCatching { Http.client.connectionPool.evictAll() }
            throw SourceException("HentaiHaven asks for an age check in your country. Turn your VPN on and refresh. " +
                "If your VPN only covers some apps (split tunnelling), connect it first, then close and reopen MangaCracker.")
        }
        openAt = System.currentTimeMillis()
    }

    // ---------- genres + minors filter ----------

    // id|slug|name of the /genres/ page (2026-11-10); the two hidden ones are left out
    private val GENRES = ("2245|3d-hentai|3D Hentai;11709|ahegao|Ahegao;222|anal|Anal;2433|bbw|BBW;133|bdsm|BDSM;11713|big-boobs|Big Boobs;" +
        "11719|big-breasts|Big Breasts;11730|big-tits|Big Tits;11711|blow-job|Blow Job;11720|blowjob|Blowjob;11712|censored|Censored;" +
        "11729|cheating|Cheating;11725|comedy|Comedy;11726|corruption|Corruption;11710|creampie|Creampie;2594|ecchi|Ecchi;11734|elf|Elf;" +
        "11738|erotic-game|Erotic Game;11718|exhibitionism|Exhibitionism;11716|fantasy|Fantasy;2673|femboy|FemBoy;2676|femdom|Femdom;" +
        "165|furry|Furry;229|futanari|Futanari;2582|gender-bender-hentai|Gender Bender;11740|group-sex|Group Sex;11736|gyaru|Gyaru;" +
        "105|harem|Harem;11721|hd|HD;815|hentai-school|Hentai School;1606|horror|Horror;11743|housewife|Housewife;11735|impregnation|Impregnation;" +
        "64|incest|Incest;11715|masturbation|Masturbation;208|milf|Milf;11733|mind-break|Mind Break;11746|mind-control|Mind Control;" +
        "290|monster|Monster;11741|monster-girl|Monster Girl;11744|netorare|Netorare;11728|ntr|NTR;11717|nudity|Nudity;" +
        "11727|office-lady|Office Lady;11722|paizuri|Paizuri;103|romance|Romance;11714|school|School;11732|schoolgirl|Schoolgirl;" +
        "11731|sex-toys|Sex Toys;2627|softcore|Softcore;244|tentacle|Tentacle;11742|threesome|Threesome;106|tsundere|Tsundere;" +
        "2605|umemaro-3d|Umemaro 3D;107|uncensored|Uncensored;11745|virgin|Virgin;104|yaoi|Yaoi;102|yuri|Yuri")
        .split(';').map { it.split('|') }.map { Triple(it[0].toInt(), it[1], it[2]) }
    private val slugById = GENRES.associate { it.first to it.second }
    private val idBySlug = GENRES.associate { it.second to it.first }

    /** The site's genre for young-looking characters, "Young Hentai" (67): never listed, never played. */
    private val HIDDEN_IDS = setOf(67)
    private val HIDDEN = setOf("young", "loli", "lolicon", "shota", "shotacon")
    /** Series seen with a hidden genre (their episode pages show no genres). */
    private val blockedSlugs = ConcurrentHashMap.newKeySet<String>()

    override suspend fun genres(): List<Tag> = GENRES.map { Tag(it.second, it.third, "Genre") }

    // ---------- lists ----------

    private fun unescape(s: String) = org.jsoup.parser.Parser.unescapeEntities(s, false).trim()

    private fun shortViews(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.0fK".format(n / 1_000.0)
        n > 0 -> n.toString()
        else -> ""
    }

    /** One series of /api/manga/: WordPress post fields + genre ids; views sit in the answer's `engagement` by post id. */
    private fun seriesRef(o: JsonElement, engagement: JsonElement?): VideoRef? {
        val slug = o.str("slug").ifEmpty { return null }
        val name = unescape(o["title"].str("rendered")).ifEmpty { return null }
        val ids = o["wp-manga-genre"].arr.mapNotNull { it.text.toIntOrNull() }
        if (ids.any { it in HIDDEN_IDS }) { blockedSlugs += slug; return null }
        if (slug in blockedSlugs) return null
        val thumb = o["meta"].str("vraven_remote_thumbnail")
        val cover = when {
            thumb.isEmpty() -> ""
            thumb.startsWith("http") -> thumb
            else -> IMG + thumb.trimStart('/')
        }
        val r = VideoRef(
            source = id, id = SERIES + slug, title = name, url = "$baseUrl/watch/$slug/", series = name, cover = cover,
            // "s_" marks the small copy of the same picture
            altCovers = listOfNotNull(cover.replace("/s_", "/").takeIf { it != cover }),
            views = shortViews(engagement[o.str("id")].long("views")), lang = "EN",
            tags = ids.mapNotNull { slugById[it] } + "series:$slug",
        )
        return r.takeUnless { VideoSafety.blocked(it) }
    }

    private suspend fun catalogue(params: List<String>, page: Int): VideoPage {
        gate()
        val url = "$baseUrl/api/manga/?" + (listOf("page=$page", "per_page=24", "live=1", "locale=en") + params).joinToString("&")
        val j = pace.run { Http.getJson(url, mapOf("Referer" to "$baseUrl/watch/")) }
        // the site answers "restricted" instead of results for some words
        if (j.bool("restricted")) return VideoPage(emptyList(), false)
        val engagement = j["engagement"]
        return VideoPage(j["data"].arr.mapNotNull { seriesRef(it, engagement) }, page < j.int("totalPages"))
    }

    /** Series by their newest upload (the site's "Latest" tab). */
    override suspend fun latest(page: Int) = catalogue(listOf("sort=latest"), page)
    override suspend fun popular(page: Int) = catalogue(listOf("sort=views"), page)

    override suspend fun search(q: SearchQuery, page: Int): VideoPage {
        val text = listOf(q.text, q.artist).firstOrNull { it.isNotBlank() }?.trim().orEmpty().take(200)
        val inc = q.include.map { it.id }.filter { it !in HIDDEN }
        val exc = q.exclude.map { it.id }.toSet()
        val genre = inc.firstNotNullOfOrNull { idBySlug[it] }
        val params = buildList {
            if (text.isNotEmpty()) { add("orderby=date"); add("order=desc"); add("search=${text.urlEncode()}") } else add("sort=views")
            genre?.let { add("genre=$it") }
        }
        val r = catalogue(params, page)
        // the site takes one genre: the others (and the left-out ones) are checked on each series' own genres
        return r.copy(items = r.items.filter { v -> inc.all { it in v.tags } && v.tags.none { it in exc } })
    }

    // ---------- series ----------

    private class SeriesInfo(val slug: String, val name: String, val genres: List<String>, val cover: String, val episodes: List<VideoRef>)

    private val seriesCache = ConcurrentHashMap<String, SeriesInfo>()

    private suspend fun page(url: String): Document = pace.run { Http.getDoc(url) }

    /** /watch/<series>/: name, genres (links to /series/<genre>/) and an episode card per /watch/<series>/<episode>/. */
    private suspend fun seriesInfo(slug: String): SeriesInfo = seriesCache[slug] ?: run {
        val doc = page("$baseUrl/watch/$slug/")
        val name = doc.selectFirst("h1")?.text().orEmpty().ifEmpty { slug }
        val genres = doc.select("main a[href^='/series/']").map { it.attr("href").trim('/').substringAfterLast('/') }.distinct()
        if (genres.any { it in HIDDEN }) { blockedSlugs += slug; throw SourceException("This video is hidden") }
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        val eps = doc.select("a[href^='/watch/$slug/']:has(img)").mapNotNull { a ->
            val seg = a.attr("href").removePrefix("/watch/$slug/").trim('/')
            if (seg.isEmpty() || '/' in seg) return@mapNotNull null
            val ep = Regex("(\\d+(?:\\.\\d+)?)").find(seg)?.groupValues?.get(1).orEmpty()
            val cover = a.selectFirst("img")?.attr("abs:src").orEmpty()
            VideoRef(
                source = id, id = "$slug/$seg", url = "$baseUrl/watch/$slug/$seg/", series = name, episode = ep,
                title = if (ep.isNotEmpty()) "$name Episode $ep" else name, lang = "EN",
                cover = cover.ifEmpty { poster },
                altCovers = listOfNotNull(cover.replace("/s_thumbnail", "/thumbnail").takeIf { it != cover && it.isNotEmpty() }, poster.takeIf { it.isNotEmpty() && cover.isNotEmpty() }),
                tags = genres + "series:$slug",
            ).takeUnless { VideoSafety.blocked(it) }
        }.distinctBy { it.id }.sortedBy { episodeNumber(it.episode) }
        if (seriesCache.size > 80) seriesCache.clear()
        SeriesInfo(slug, name, genres, poster, eps).also { seriesCache[slug] = it }
    }

    private fun seriesSlug(ref: VideoRef) = if (ref.id.startsWith(SERIES)) ref.id.removePrefix(SERIES) else ref.id.substringBefore('/')

    override suspend fun series(ref: VideoRef): List<VideoRef> {
        gate()
        val slug = seriesSlug(ref)
        if (slug in blockedSlugs) return emptyList()
        return seriesInfo(slug).episodes
    }

    // ---------- details + stream ----------

    override suspend fun details(ref: VideoRef): VideoDetails {
        gate()
        val slug = seriesSlug(ref)
        if (slug in blockedSlugs) throw SourceException("This video is hidden")
        // the series page first: it is the genre check (an episode page shows no genres) and the episode list
        val info = seriesInfo(slug)
        if (ref.id.startsWith(SERIES)) {
            // a series card: play its first episode
            return details(info.episodes.firstOrNull() ?: throw SourceException("No episodes yet"))
        }
        val url = ref.url.ifEmpty { "$baseUrl/watch/${ref.id}/" }
        val html = pace.run { Http.get(url) }
        val doc = Jsoup.parse(html, url)

        var description = ""
        var duration = ""
        var thumb = ""
        var playlist = ""
        doc.select("script[type=application/ld+json]").forEach { s ->
            runCatching {
                val j = Http.json.parseToJsonElement(s.data())
                (j["@graph"].arr.ifEmpty { j.arr.ifEmpty { listOf(j) } }).filter { it.str("@type") == "VideoObject" }.forEach { g ->
                    if (description.isEmpty()) description = g.str("description")
                    if (duration.isEmpty()) duration = isoDuration(g.str("duration"))
                    if (thumb.isEmpty()) thumb = g["thumbnailUrl"].arr.firstOrNull()?.text ?: g.str("thumbnailUrl")
                    if (playlist.isEmpty()) playlist = g.str("contentUrl").takeIf { ".m3u8" in it }.orEmpty()
                }
            }
        }
        // the player's own source: an .m3u8 named in its data, else the site's stream call
        val named = Regex("indexableSource\\\\?\":\\\\?\"(https?:[^\"\\\\]+?\\.m3u8)").find(html)?.groupValues?.get(1)
        val stream = (named ?: playlist.ifEmpty { null })?.let { VideoStream(it.replace("/playlist_vp9.m3u8", "/playlist.m3u8"), "hls") }
            ?: askStream(html, url)

        val title = doc.selectFirst("h1")?.text().orEmpty()
        val ep = ref.episode.ifEmpty { Regex("Episode\\s+(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1).orEmpty() }
        val me = ref.copy(
            title = if (ep.isNotEmpty()) "${info.name} Episode $ep" else ref.title.ifEmpty { title },
            series = info.name, episode = ep, url = url, lang = "EN", duration = ref.duration.ifEmpty { duration },
            cover = ref.cover.ifEmpty { thumb.ifEmpty { info.cover } },
            tags = info.genres + "series:$slug",
        )
        val eps = info.episodes.map { if (it.id == me.id) me else it }.let { l -> if (l.none { it.id == me.id }) (l + me).sortedBy { episodeNumber(it.episode) } else l }
        val d = VideoDetails(ref = me, stream = stream, episodes = eps, tags = info.genres, description = description)
        VideoSafety.check(d)
        return d
    }

    /** "PT16M43S" -> "16:43". */
    private fun isoDuration(s: String): String {
        val m = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matchEntire(s) ?: return ""
        val sec = (m.groupValues[1].toLongOrNull() ?: 0) * 3600 + (m.groupValues[2].toLongOrNull() ?: 0) * 60 + (m.groupValues[3].toLongOrNull() ?: 0)
        return if (sec > 0) clock(sec) else ""
    }

    /**
     * Episodes whose page names no playlist: the player posts its `slug`, `permalink` and `poster` to /api/stream/ and
     * gets `{status, data: {sources: [{src, type}], fallbackSources}}` (read from the site's player script).
     */
    private suspend fun askStream(html: String, referer: String): VideoStream {
        fun prop(k: String) = Regex("\\\\?\"$k\\\\?\":\\\\?\"([^\"\\\\]+)").find(html.substringAfter("\"videoId", html))?.groupValues?.get(1).orEmpty()
        val slug = prop("slug").ifEmpty { throw SourceException("No video on this page") }
        val body = "{\"slug\":\"$slug\",\"permalink\":\"${prop("permalink")}\",\"poster\":\"${prop("poster")}\"}"
        val j = pace.run {
            withContext(Dispatchers.IO) {
                val req = Request.Builder().url("$baseUrl/api/stream/").post(body.toRequestBody("application/json".toMediaType()))
                    .header("Origin", baseUrl).header("Referer", referer).header("Accept", "application/json").build()
                Http.client.newCall(req).execute().use { r ->
                    if (r.code == 404 || r.code == 410) throw SourceException("This episode's video is missing on HentaiHaven")
                    if (!r.isSuccessful) throw SourceException("HentaiHaven answered ${r.code}")
                    Http.json.parseToJsonElement(r.body!!.string())
                }
            }
        }
        val src = (j["data"]["sources"].arr + j["data"]["fallbackSources"].arr).firstNotNullOfOrNull { it.str("src").ifEmpty { null } }
            ?: throw SourceException("No playable video on this page")
        return VideoStream(src, if (".m3u8" in src) "hls" else "mp4")
    }
}
