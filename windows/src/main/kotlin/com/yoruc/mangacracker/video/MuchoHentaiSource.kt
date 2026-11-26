package com.yoruc.mangacracker.video

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.core.arr
import com.yoruc.mangacracker.core.str
import com.yoruc.mangacracker.core.urlEncode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

/**
 * muchohentai.com: WordPress HTML (its JSON API is firewalled), 24 a page. Streams are AES-128 HLS whose key needs
 * the cookies of an anonymous POST to the site's keyserver (12 min). Spec: _dev_tools/specs/muchohentai.md.
 */
object MuchoHentaiSource : VideoSource {
    override val id = "muchohentai"
    override val name = "MuchoHentai"
    override val baseUrl = "https://muchohentai.com"
    override val blurb = "Raw / English / Spanish · up to 1080p"
    override val color = 0xFF7BD555L

    private val pace = Pace(1000)
    private const val AUTHORIZE = "https://keyserver.muchohentai.com/authorize"
    private val HIDDEN = setOf("lolicon", "shotacon", "pettanko")
    private val PSEUDO = setOf("raw", "eng-subbed", "n", "pv", "h-mv")
    /** Series seen with a hidden tag: every post of them is hidden (new posts often have no genres yet). */
    private val blockedSeries = ConcurrentHashMap.newKeySet<String>()
    private val pages = ConcurrentHashMap<String, Document>()
    private val authLock = Mutex()
    @Volatile private var authAt = 0L
    /** Cookie names the last unlock set (for the PC test's log). */
    @Volatile var lastAuthCookies: List<String> = emptyList()
        private set

    private val GENRES = ("ahegao anal bdsm big-breasts blackmail bukakke censored cgi chikan classic comedy cosplay creampie " +
        "cross-dressing cunnilingus dark-skin drugs ecchi eroge exhibitionism facial fantasy fellatio filmed foot-job futanari " +
        "gangbang gender-bender harem historical horror hypnosis idol impregnation incest inflation kemonomimi lactation " +
        "large-insertion maid masturbation mecha milf mind-break monster ninja ntr nun nurse orgy paizuri plot pov public-sex " +
        "public-use rape rimjob school sci-fi seinen softcore sports stockings succubus supernatural superpower swimsuit " +
        "threesome toys ugly-bastard uncensored vanilla virgin voyeur waitress watersports x-ray yaoi yuri").split(' ')

    override suspend fun genres(): List<Tag> = GENRES.map { s ->
        Tag(s, s.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }.replace("Ntr", "NTR").replace("Pov", "POV").replace("Cgi", "CGI"), "Genre")
    }

    private suspend fun page(url: String): Document = pace.run { Http.getDoc(url) }

    private fun item(e: Element): VideoRef? {
        val classes = e.classNames()
        val tags = classes.filter { it.startsWith("tag-") }.map { it.removePrefix("tag-") }
        val cats = classes.filter { it.startsWith("category-") }.map { it.removePrefix("category-") }
        val series = cats.firstOrNull { it !in PSEUDO && !it.startsWith("%e3%80%90") }.orEmpty()
        if (tags.any { it in HIDDEN }) { if (series.isNotEmpty()) blockedSeries += series; return null }
        if (series in blockedSeries) return null
        val a = e.selectFirst("a.clip-link") ?: return null
        val title = a.attr("title").ifEmpty { e.selectFirst("h2.entry-title")?.text().orEmpty() }
        val lang = when {
            title.startsWith("【PV】") || "pv" in cats -> "PV"
            title.endsWith("English Subbed", true) || "eng-subbed" in cats -> "EN"
            title.endsWith("En Español", true) || "n" in cats -> "ES"
            title.endsWith("Raw", true) || "raw" in cats -> "RAW"
            else -> ""
        }
        // the 640x360 size is sharp in a 2-column grid and a fraction of the full picture (300-400 KB)
        val raw = e.selectFirst("img")?.attr("abs:src").orEmpty()
        val sized = Regex("-\\d+x\\d+(?=\\.\\w+$)")
        val img = raw.replace(sized, "-640x360")
        // fallbacks: the full-size picture, the small one, then what the list page itself showed
        val alts = if (sized.containsMatchIn(raw)) listOf(raw.replace(sized, ""), raw.replace(sized, "-320x180"), raw).distinct().filter { it != img } else emptyList()
        val r = VideoRef(
            source = id, id = a.attr("data-id").ifEmpty { e.id().removePrefix("post-") }, title = title, url = a.attr("abs:href"),
            series = title.removePrefix("【PV】").replace(Regex("\\s+Episode\\s+\\d.*$", RegexOption.IGNORE_CASE), "").trim(),
            episode = episodeOf(title), cover = img, views = e.selectFirst("p.stats .views .count")?.text().orEmpty(), lang = lang,
            tags = tags + ("series:$series"), altCovers = alts,
        )
        return r.takeUnless { VideoSafety.blocked(it) }
    }

    private fun parse(doc: Document): VideoPage {
        val items = doc.select("div.item.item-video").mapNotNull { item(it) }
        return VideoPage(items, doc.selectFirst("a.nextpostslink") != null)
    }

    private fun sortQ(order: String?) = if (order == null) "" else "?orderby=$order"

    override suspend fun latest(page: Int) =
        parse(page("$baseUrl/latest-hentai-posts" + (if (page > 1) "/page/$page" else "")))

    override suspend fun popular(page: Int) =
        parse(page("$baseUrl/latest-hentai-posts" + (if (page > 1) "/page/$page" else "") + sortQ("views")))

    override suspend fun search(q: SearchQuery, page: Int): VideoPage {
        val text = q.text.trim()
        val inc = q.include.map { it.id }
        val exc = q.exclude.map { it.id }.toSet()
        val r = when {
            text.isNotEmpty() -> parse(page("$baseUrl/" + (if (page > 1) "page/$page/" else "") + "?s=" + text.urlEncode()))
            inc.isNotEmpty() -> parse(page("$baseUrl/genre/${inc.first()}" + (if (page > 1) "/page/$page" else "")))
            else -> latest(page)
        }
        // the site filters one genre at a time; the rest is checked on each item's own tags
        val rest = if (text.isNotEmpty()) inc else inc.drop(1)
        return r.copy(items = r.items.filter { v -> rest.all { it in v.tags } && v.tags.none { it in exc } })
    }

    /** The anonymous key cookie (12 min), refreshed after 10 min or when a key answers 403. */
    private suspend fun authorize(force: Boolean = false) = authLock.withLock {
        if (!force && System.currentTimeMillis() - authAt < 10 * 60_000L) return@withLock
        withContext(Dispatchers.IO) {
            // same User-Agent as the key requests (the app's own), no override here
            val req = Request.Builder().url(AUTHORIZE).post(ByteArray(0).toRequestBody(null)).build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw SourceException("MuchoHentai key server answered ${r.code}")
                lastAuthCookies = r.headers("Set-Cookie").map { it.substringBefore('=') }
            }
        }
        authAt = System.currentTimeMillis()
    }

    override suspend fun beforePlay() = authorize()

    /** Player client: on a 403 for a key, unlock again once and retry (a seek after the 12 minutes ran out). */
    override val playerClient: OkHttpClient by lazy {
        Http.imageClient.newBuilder().addInterceptor(object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val req = chain.request()
                val res = chain.proceed(req)
                if (res.code != 403 || !req.url.encodedPath.endsWith("key.bin")) return res
                res.close()
                kotlinx.coroutines.runBlocking { authorize(force = true) }
                return chain.proceed(req)
            }
        }).build()
    }

    private suspend fun videoPage(ref: VideoRef): Document =
        pages[ref.id] ?: page(ref.url.ifEmpty { "$baseUrl/avH6Dh/${ref.id}" }).also { pages[ref.id] = it }

    override suspend fun details(ref: VideoRef): VideoDetails {
        val doc = videoPage(ref)
        val genres = doc.select("#extras a[href*='/genre/']").map { it.attr("href").trimEnd('/').substringAfterLast('/').lowercase() }
        val seriesLink = doc.select("#extras a[href*='/series/']").firstOrNull {
            val s = it.attr("href").trimEnd('/').substringAfterLast('/')
            s !in PSEUDO && !s.startsWith("%e3%80%90") && !it.text().startsWith("【")
        }
        val seriesSlug = seriesLink?.attr("href")?.trimEnd('/')?.substringAfterLast('/').orEmpty()
        if (genres.any { it in HIDDEN }) { if (seriesSlug.isNotEmpty()) blockedSeries += seriesSlug; throw SourceException("This video is hidden") }
        if (seriesSlug in blockedSeries) throw SourceException("This video is hidden")

        val html = doc.html()
        val files = Regex("var files = (\\[.*?\\]);").find(html)?.groupValues?.get(1)
            ?: throw SourceException("No video on this post")
        val file = Http.json.parseToJsonElement(files).arr.firstOrNull()?.str("file")?.ifEmpty { null }
            ?: throw SourceException("No video on this post")
        val servers = Regex("var servers = \\[([^\\]]*)\\]").find(html)?.groupValues?.get(1)
            ?.split(',')?.map { it.trim().trim('\'', '"') }?.filter { it.isNotEmpty() }.orEmpty().ifEmpty { listOf("va01", "va02") }
        val master = "https://${servers.random()}.edge.tmncdn.io$file"
        val subs = Regex("var subs = \"([^\"]+)\"").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
        val subName = Regex("var subName = \"([^\"]*)\"").find(html)?.groupValues?.get(1).orEmpty()
        val subList = if (subs.isNullOrEmpty()) emptyList()
        else listOf(VideoSub(baseUrl + subs, if (subs.endsWith("es.vtt")) "es" else "en", subName.ifEmpty { if (subs.endsWith("es.vtt")) "Español" else "English" }))

        val me = ref.copy(
            tags = (ref.tags.filter { !it.startsWith("series:") } + genres + listOfNotNull(seriesSlug.ifEmpty { null }?.let { "series:$it" })).distinct(),
            series = seriesLink?.text()?.ifEmpty { null } ?: ref.series,
        )
        val eps = if (seriesSlug.isEmpty()) listOf(me) else runCatching { seriesEpisodes(seriesSlug, me) }.getOrDefault(listOf(me))
        val d = VideoDetails(
            ref = me,
            stream = VideoStream(master, "hls", subList),
            episodes = eps,
            tags = genres,
        )
        VideoSafety.check(d)
        return d
    }

    private val seriesCache = ConcurrentHashMap<String, List<VideoRef>>()

    /** Every post of a series page (Raw, English, Spanish, previews), cached for the run. */
    private suspend fun seriesAll(slug: String): List<VideoRef> = seriesCache[slug] ?: run {
        val all = mutableListOf<VideoRef>()
        var p = 1
        while (p <= 5) {
            val r = parse(page("$baseUrl/series/$slug" + (if (p > 1) "/page/$p" else "") + "?orderby=title&order=asc"))
            all += r.items
            if (!r.hasNext) break
            p++
        }
        all.distinctBy { it.id }.also { seriesCache[slug] = it }
    }

    override suspend fun series(ref: VideoRef): List<VideoRef> {
        val slug = ref.tags.firstOrNull { it.startsWith("series:") }?.removePrefix("series:")?.ifEmpty { null }
            ?: details(ref).ref.tags.firstOrNull { it.startsWith("series:") }?.removePrefix("series:")?.ifEmpty { null }
            ?: return listOf(ref)
        if (slug in blockedSeries) return emptyList()
        return seriesAll(slug).sortedWith(seriesOrder)
    }

    /** All posts of the series in the same language as [me] (Raw when that episode has no such post yet). */
    private suspend fun seriesEpisodes(slug: String, me: VideoRef): List<VideoRef> {
        val all = seriesAll(slug)
        val byEp = all.filter { it.lang != "PV" && it.episode.isNotEmpty() }.groupBy { it.episode }
        return byEp.values.mapNotNull { posts -> posts.firstOrNull { it.lang == me.lang } ?: posts.firstOrNull { it.lang == "RAW" } ?: posts.firstOrNull() }
            .let { l -> if (l.none { it.id == me.id }) l + me else l }
            .sortedBy { episodeNumber(it.episode) }
    }
}
