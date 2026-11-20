package com.yoruc.mangacracker.video

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.core.urlEncode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/*
 * Four live-action video sites behind Cloudflare's browser check (the app's check window passes it, as for other sites).
 * Their layouts follow the open-source Aniyomi extensions (MissAV, SupJav, Jav Guru) and yt-dlp's SpankBang reader;
 * written here from scratch. Not tested from the developer's machine (the check stops scripts): see HISTORY.
 */

/** Shared: paced page reads, cards from links + pictures, the minors filter on titles and tags. */
abstract class LiveSite(
    final override val id: String, final override val name: String, final override val baseUrl: String,
    final override val blurb: String, final override val color: Long,
) : VideoSource {
    private val pace = Pace(900)
    private val pages = ConcurrentHashMap<String, Document>()

    init { Http.referers[baseUrl.removePrefix("https://").removePrefix("www.").substringBefore('/')] = "$baseUrl/" }

    protected suspend fun page(url: String): Document {
        pages[url]?.let { return it }
        // a tag / page the site doesn't have (404) = an empty list, not an error
        val (code, body) = pace.run { Http.getRaw(url) }
        if (code == 404) return Jsoup.parse("", url)
        if (code !in 200..299) throw SourceException("$name answered $code")
        val doc = Jsoup.parse(body, url)
        if (pages.size > 40) pages.clear()
        pages[url] = doc
        return doc
    }

    protected fun keep(list: List<VideoRef>) = list.filter { it.title.isNotBlank() }.distinctBy { it.id }
        .filterNot { VideoSafety.blocked(it) || LiveHosts.minor(it) }

    protected fun img(el: Element?): String = el?.let { i ->
        listOf("data-src", "data-original", "data-lazy-src", "src").map { i.absUrl(it) }.firstOrNull { it.startsWith("http") && "data:" !in it }
    }.orEmpty()

    protected fun check(d: VideoDetails): VideoDetails {
        if (LiveHosts.minor(d.ref) || d.tags.any { LiveHosts.minor(it) }) throw SourceException("This video is hidden")
        VideoSafety.check(d)
        return d
    }

    protected fun slug(s: String) = s.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')
}

// ---------------------------------------------------------------------------------------------------------------------

object MissAvSource : LiveSite("missav", "MissAV", "https://missav.ws", "missav.ws · Japanese · HLS", 0xFFEC4899L) {
    private const val L = "/en"
    private val GENRES = ("Hd|Exclusive|Creampie|Big Breasts|Individual|Wife|Mature Woman|Pretty Girl|Ride|Oral Sex|Orgy|Slut|" +
        "Collection|Squirting|Fetish|Selfie|Tit Job|Slim|Bukkake|Beautiful Breasts|Masturbation|Restraint|Lesbian|Ntr|Cosplay|" +
        "Humiliation|Documentary|Hot Girl|Ol|Fingering|Adultery|Cunnilingus|Sm|Anus|Massage|Underwear|Big Ass|Swimsuit|" +
        "Female Teacher|Kimono|Nurse|Massage Oil|Fat Girl|Short Skirt|Nice Ass|Foot Fetish|Glasses Girl|Kiss|4K|Close Up|Pantyhose").split('|')

    override suspend fun genres() = listOf(Tag("uncensored-leak", "Uncensored leak", "Kind")) + GENRES.map { Tag("g:$it", it, "Genre") }

    private fun cards(doc: Document) = keep(doc.select("div.thumbnail").mapNotNull { el ->
        val a = el.selectFirst("a.text-secondary[href]") ?: el.selectFirst("a[href]") ?: return@mapNotNull null
        val href = a.attr("abs:href")
        VideoRef(id, href.substringAfterLast('/').substringBefore('?'), a.text().trim().ifEmpty { el.selectFirst("img")?.attr("alt").orEmpty() },
            href, cover = img(el.selectFirst("img")), duration = el.selectFirst("span.absolute.bottom-1, .duration")?.text()?.trim().orEmpty())
    })

    private suspend fun list(url: String): VideoPage {
        val doc = page(url)
        return VideoPage(cards(doc), doc.selectFirst("a[rel=next]") != null)
    }

    override suspend fun latest(page: Int) = list("$baseUrl$L/new?page=$page")
    override suspend fun popular(page: Int) = list("$baseUrl$L/today-hot?page=$page")

    override suspend fun search(q: SearchQuery, page: Int): VideoPage {
        val text = listOf(q.text, q.artist).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val g = q.include.firstOrNull()
        return when {
            text.isNotEmpty() -> list("$baseUrl$L/search/${text.urlEncode().replace("+", "%20")}?page=$page")
            g != null && g.id == "uncensored-leak" -> list("$baseUrl$L/uncensored-leak?page=$page")
            // a genre from the list, or a typed one: the site's genre pages go by the name in Title Case
            g != null -> list("$baseUrl$L/genres/" + g.id.removePrefix("g:").replace('-', ' ').split(' ')
                .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }.urlEncode().replace("+", "%20") + "?page=$page")
            else -> latest(page)
        }
    }

    override suspend fun details(ref: VideoRef): VideoDetails {
        val doc = page(ref.url)
        fun info(part: String) = doc.select("div.text-secondary > a[href*=$part]").map { it.text().trim() }
        val tags = info("/genres/")
        val cast = info("/actresses/")
        val packed = doc.select("script").map { it.data() }.firstOrNull { "eval(function(p,a,c,k,e,d)" in it }
            ?: throw SourceException("No playable video on this page")
        val js = LiveHosts.unpack(packed)
        val m3u8 = js.substringAfter("source=\"", "").substringBefore("\"").ifEmpty { null }
            ?: Regex("https://[^\"';\\s]+playlist\\.m3u8").find(js)?.value
            ?: throw SourceException("No playable video on this page")
        Http.referers[okhttp3.HttpUrl.Companion.run { m3u8.toHttpUrlOrNull() }?.host ?: "surrit.com"] = "$baseUrl/"
        val me = ref.copy(title = doc.selectFirst("h1")?.text()?.trim()?.ifEmpty { null } ?: ref.title, series = cast.firstOrNull().orEmpty(),
            cover = ref.cover.ifEmpty { doc.selectFirst("video.player")?.attr("abs:data-poster").orEmpty() }, tags = tags)
        return check(VideoDetails(me, VideoStream(m3u8, "hls"), listOf(me) + cards(doc).filter { it.id != me.id }.take(24), tags + cast))
    }
}

// ---------------------------------------------------------------------------------------------------------------------

object SupJavSource : LiveSite("supjav", "SupJav", "https://supjav.com", "supjav.com · Japanese · several players", 0xFF14B8A6L) {
    private const val PROTECT = "https://lk1.supremejav.com/supjav.php"
    private val TAGS = "creampie|big-tits|amateur|married-woman|mature|cosplay|lesbian|massage|nurse|office-lady|beautiful-girl|uncensored|chinese-subtitles|threesome|squirting|big-ass|glasses|swimsuit|pantyhose|bukkake".split('|')

    private val CATS = listOf("censored-jav" to "Censored", "uncensored-jav" to "Uncensored", "amateur" to "Amateur",
        "chinese-subtitles" to "Chinese subtitles", "english-subtitles" to "English subtitles", "reducing-mosaic" to "Reduced mosaic")

    override suspend fun genres() = CATS.map { Tag("c:${it.first}", it.second, "Category") } + TAGS.map { Tag(it, it.replace('-', ' '), "Tag") }

    private fun cards(doc: Document) = keep(doc.select("div.posts > div.post > a[href], div.post > a[href]").mapNotNull { a ->
        val href = a.attr("abs:href")
        val im = a.selectFirst("img")
        VideoRef(id, href.trimEnd('/').substringAfterLast('/'), im?.attr("alt")?.trim().orEmpty().ifEmpty { a.attr("title") }, href, cover = img(im))
    }.filterNot { "[Discontinued]" in it.title })   // taken down: their pages have no players left

    private suspend fun list(url: String): VideoPage {
        val doc = page(url)
        val next = doc.selectFirst("div.pagination li.active + li a[href], div.pagination a.next, a.next.page-numbers") != null
        return VideoPage(cards(doc), next)
    }

    // the home page holds the newest of every kind (one page); more = the newest censored ones, page by page
    override suspend fun latest(page: Int) =
        if (page <= 1) list("$baseUrl/").copy(hasNext = true) else list("$baseUrl/category/censored-jav/page/$page")
    override suspend fun popular(page: Int) = list("$baseUrl/popular/page/$page")

    override suspend fun search(q: SearchQuery, page: Int): VideoPage {
        val text = listOf(q.text, q.artist).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val g = q.include.firstOrNull()
        return when {
            text.isNotEmpty() -> list("$baseUrl" + (if (page > 1) "/page/$page" else "") + "/?s=" + text.urlEncode())
            g != null && g.id.startsWith("c:") -> list("$baseUrl/category/${g.id.removePrefix("c:")}" + (if (page > 1) "/page/$page" else ""))
            g != null -> list("$baseUrl/tag/${slug(g.id)}" + (if (page > 1) "/page/$page" else ""))
            else -> latest(page)
        }
    }

    override suspend fun details(ref: VideoRef): VideoDetails {
        val doc = page(ref.url)
        val meta = doc.selectFirst("div.content > div.post-meta")
        val title = meta?.selectFirst("h2")?.text()?.trim()?.ifEmpty { null } ?: ref.title
        val tags = meta?.select("div.tags > a")?.map { it.text().trim() }.orEmpty()
        val cast = meta?.select("div.cats p:contains(Cast) > a")?.map { it.text().trim() }.orEmpty()
        val me = ref.copy(title = title, series = cast.firstOrNull().orEmpty(), cover = ref.cover.ifEmpty { img(meta?.selectFirst("img")) }, tags = tags)
        val d = VideoDetails(me, VideoStream("", "mp4"), listOf(me) + cards(doc).filter { it.id != me.id }.take(20), tags + cast)
        check(d)
        // the players, best first: EVS / LUC (HLS that answered in the 2026-11-20 tests), then the older TV / FST / ST
        // a video in parts (CD 1 / CD 2) keeps each part's players in a div.cd-server: the first part plays
        val group = doc.selectFirst("div.cd-server") ?: doc.selectFirst("div.btnst:has(a[data-link])")
        val players = group?.select("a[data-link]").orEmpty().map { it.text().trim() to it.attr("data-link").reversed() }
            .sortedBy { listOf("EVS", "LUC", "TV", "FST", "VAS", "ST").indexOf(it.first).let { i -> if (i < 0) 9 else i } }
        for ((kind, code) in players) {
            val host = runCatching { LiveHosts.location("$PROTECT?c=$code", "https://lk1.supremejav.com/") }.getOrNull() ?: continue
            val s = if (kind == "TV") runCatching { LiveHosts.urlPlay(host) }.getOrNull() else LiveHosts.stream(host, "$baseUrl/")
            if (s != null) return d.copy(stream = s)
        }
        throw SourceException("None of this video's players answered (${players.joinToString { it.first }.ifEmpty { "none listed" }})")
    }
}

// ---------------------------------------------------------------------------------------------------------------------

object JavGuruSource : LiveSite("javguru", "Jav Guru", "https://jav.guru", "jav.guru · Japanese · several players", 0xFFF59E0BL) {
    private val TAGS = "creampie|big-tits|married-woman|mature-woman|cosplay|lesbian|massage|nurse|beautiful-girl|uncensored|threesome|squirting|big-ass|glasses|pantyhose|bukkake|amateur|slender|ntr|female-teacher".split('|')
    @Volatile private var ranked: List<VideoRef> = emptyList()

    override suspend fun genres() = TAGS.map { Tag(it, it.replace('-', ' '), "Tag") }

    private fun idOf(href: String) = Regex("jav\\.guru/(\\d+)/").find(href)?.groupValues?.get(1) ?: href.trimEnd('/').substringAfterLast('/')

    private fun cards(doc: Document) = keep(doc.select("div.site-content div.inside-article").mapNotNull { el ->
        val a = el.selectFirst("h2 a[href]") ?: el.selectFirst("a[href]") ?: return@mapNotNull null
        val href = a.attr("abs:href")
        VideoRef(id, idOf(href), (el.selectFirst("h2 a")?.text() ?: a.text()).trim(), href, cover = img(el.selectFirst("img")))
    })

    private suspend fun list(url: String): VideoPage {
        val doc = page(url)
        val next = doc.selectFirst("div.wp-pagenavi a.nextpostslink, a.next.page-numbers, div.wp-pagenavi a[rel=next]") != null
        return VideoPage(cards(doc), next)
    }

    override suspend fun latest(page: Int) = list(baseUrl + if (page > 1) "/page/$page/" else "/")

    /** The site's most-watched ranking is one long page: 20 at a time from it. */
    override suspend fun popular(page: Int): VideoPage {
        if (page == 1 || ranked.isEmpty()) ranked = keep(page("$baseUrl/most-watched-rank/").select(".tabcontent li").mapNotNull { li ->
            val a = li.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("abs:href")
            VideoRef(id, idOf(href), a.text().trim(), href, cover = img(li.selectFirst("img")))
        })
        val from = (page - 1) * 20
        return VideoPage(ranked.drop(from).take(20), from + 20 < ranked.size)
    }

    override suspend fun search(q: SearchQuery, page: Int): VideoPage {
        val text = listOf(q.text, q.artist).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val g = q.include.firstOrNull()
        return when {
            text.isNotEmpty() -> list(baseUrl + (if (page > 1) "/page/$page/" else "/") + "?s=" + text.urlEncode())
            g != null -> list("$baseUrl/tag/${slug(g.id)}/" + if (page > 1) "page/$page/" else "")
            else -> latest(page)
        }
    }

    override suspend fun details(ref: VideoRef): VideoDetails {
        val doc = page(ref.url)
        val title = doc.selectFirst(".titl")?.text()?.trim()?.ifEmpty { null } ?: ref.title
        val tags = doc.select(".infoleft a[rel*=tag]").map { it.text().trim() }
        val cast = doc.select(".infoleft li:contains(actress) a").map { it.text().trim() }
        val me = ref.copy(title = title, series = cast.firstOrNull().orEmpty(), cover = ref.cover.ifEmpty { img(doc.selectFirst(".large-screenshot img")) }, tags = tags)
        val d = VideoDetails(me, VideoStream("", "mp4"), listOf(me), tags + cast)
        check(d)
        // the page's players: base64 iframe addresses -> each iframe's OLID (reversed) -> a redirect to the host
        val data = doc.select("script").map { it.data() }.firstOrNull { "iframe_url" in it } ?: throw SourceException("No players on this page")
        val frames = Regex("\"iframe_url\":\"([^\"]+)\"").findAll(data).mapNotNull { runCatching { String(Base64.getDecoder().decode(it.groupValues[1])) }.getOrNull() }.toList()
        for (f in frames) {
            val host = runCatching { hostOf(f) }.getOrNull() ?: continue
            LiveHosts.stream(host, "$baseUrl/")?.let { return d.copy(stream = it) }
        }
        throw SourceException("None of this video's players answered (${frames.size} tried)")
    }

    private suspend fun hostOf(frame: String): String? {
        val html = Http.get(frame, mapOf("Referer" to "$baseUrl/"))
        val script = Jsoup.parse(html).select("script").map { it.data() }.firstOrNull { "start_player" in it } ?: return null
        val olid = Regex("var OLID = '([^']+)'").find(script)?.groupValues?.get(1)?.reversed() ?: return null
        val src = Regex("src=\"([^\"]+)\"").find(script)?.groupValues?.get(1)?.substringBeforeLast("=") ?: return null
        return LiveHosts.location("$src=$olid", frame)?.takeIf { it.startsWith("http") }
    }
}

// ---------------------------------------------------------------------------------------------------------------------

object SpankBangSource : LiveSite("spankbang", "SpankBang", "https://spankbang.com", "spankbang.com · live action · MP4 / HLS", 0xFFF97316L) {
    private val TAGS = "amateur|anal|asian|big-tits|blonde|blowjob|brunette|creampie|cosplay|cumshot|japanese|lesbian|lingerie|massage|milf|pov|redhead|stockings|threesome|homemade|latina|ebony|glasses|big-ass".split('|')

    init {
        // the site shows its US version with this cookie (yt-dlp does the same)
        runCatching {
            com.yoruc.mangacracker.core.WebCookieJar.set("https://spankbang.com/", "country", "US", "spankbang.com")
        }
    }

    override suspend fun genres() = TAGS.map { Tag(it, it.replace('-', ' '), "Tag") }

    /** Cards: any link to /<id>/video/<name> with a picture in it. */
    private fun cards(doc: Document) = keep(doc.select("a[href*=/video/]:has(img)").mapNotNull { a ->
        val href = a.attr("abs:href")
        val vid = Regex("spankbang\\.com/([a-z0-9]+)/video/").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
        val box = a.parents().firstOrNull { p -> p.select("a[href*=/video/]").size in 1..3 && p.text().length > 3 } ?: a
        val im = a.selectFirst("img")
        val title = a.attr("title").ifBlank { im?.attr("alt").orEmpty() }.ifBlank { box.selectFirst("p, .n, .name, span.title")?.text().orEmpty() }.trim()
        VideoRef(id, vid, title, href, cover = img(im), duration = box.selectFirst(".l, .length, [data-testid=video-item-length]")?.text()?.trim().orEmpty())
    })

    private suspend fun list(url: String): VideoPage {
        val doc = page(url)
        val next = doc.selectFirst("li.next a, a.next, a[rel=next], [data-testid=pagination-next]") != null
        return VideoPage(cards(doc), next)
    }

    override suspend fun latest(page: Int) = list("$baseUrl/new_videos/$page/")
    override suspend fun popular(page: Int) = list("$baseUrl/trending_videos/$page/")

    override suspend fun search(q: SearchQuery, page: Int): VideoPage {
        val words = (listOf(q.text, q.artist) + q.include.map { it.label }).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (words.isEmpty()) return popular(page)
        return list("$baseUrl/s/${words.urlEncode()}/$page/")
    }

    override suspend fun details(ref: VideoRef): VideoDetails {
        val doc = page(ref.url)
        if (doc.selectFirst("[id=video_removed], .video_removed") != null) throw SourceException("This video was removed")
        val html = doc.html()
        val title = doc.selectFirst("h1[title]")?.attr("title")?.trim()?.ifEmpty { null } ?: doc.selectFirst("h1")?.text()?.trim()?.ifEmpty { null } ?: ref.title
        val tags = doc.select("a[href^=/s/], a[href*=/tag/], a[href*=/category/]").map { it.text().trim() }.filter { it.isNotEmpty() && it.length < 40 }.distinct()
        val me = ref.copy(title = title, cover = ref.cover.ifEmpty { doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty() }, tags = tags)
        val d = VideoDetails(me, VideoStream("", "mp4"), listOf(me) + cards(doc).filter { it.id != me.id }.take(24), tags)
        check(d)
        return d.copy(stream = stream(html, ref.url))
    }

    /** stream_url_<quality> = '...' in the page, else the stream API with the page's data-streamkey. */
    private suspend fun stream(html: String, page: String): VideoStream {
        val found = Regex("stream_url_([^\\s=]+)\\s*=\\s*(['\"])(.+?)\\2").findAll(html).map { it.groupValues[1] to it.groupValues[3] }.toMutableList()
        if (found.isEmpty()) {
            val key = Regex("data-streamkey\\s*=\\s*['\"]([^'\"]+)['\"]").find(html)?.groupValues?.get(1)
            if (key != null) {
                val body = withContext(Dispatchers.IO) {
                    Http.client.newCall(Request.Builder().url("$baseUrl/api/videos/stream")
                        .post(FormBody.Builder().add("id", key).add("data", "0").build())
                        .header("Referer", page).header("X-Requested-With", "XMLHttpRequest").build()).execute().use { it.body?.string().orEmpty() }
                }
                (runCatching { Http.json.parseToJsonElement(body) as JsonObject }.getOrNull())?.forEach { (k, v) ->
                    val u = runCatching { v.jsonPrimitive.content }.getOrNull()
                        ?: runCatching { (v as kotlinx.serialization.json.JsonArray).firstOrNull()?.jsonPrimitive?.content }.getOrNull()
                    if (!u.isNullOrBlank() && u.startsWith("http")) found += k to u
                }
            }
        }
        found.firstOrNull { it.first.startsWith("m3u8") || ".m3u8" in it.second }?.let { return VideoStream(it.second, "hls") }
        val mp4 = found.filter { ".mp4" in it.second || it.first.endsWith("p") }
            .maxByOrNull { Regex("(\\d{3,4})p").find(it.first)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { q -> q <= 1080 } ?: 0 }
            ?: throw SourceException("No playable video on this page")
        return VideoStream(mp4.second, "mp4")
    }
}
