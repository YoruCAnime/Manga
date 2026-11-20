package com.yoruc.mangacracker.video

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.core.urlEncode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap

/**
 * Two big live-action tube sites built the same way (one company): HTML lists of "thumb-block" cards, a video page whose
 * player script names the stream (html5player.setVideoHLS / setVideoUrlHigh) and lists related videos (video_related).
 * In the UK both pages hold the stream back behind a third-party age check: we stop there with the VPN note.
 */
abstract class TubeSite(
    final override val id: String,
    final override val name: String,
    final override val baseUrl: String,
    final override val blurb: String,
    final override val color: Long,
    private val cdn: String,
) : VideoSource {
    private val pace = Pace(800)
    private val pages = ConcurrentHashMap<String, Document>()
    /** The next page's address of a list, as the page's own "next" link has it (lists page oddly: /best/<month>/1 ...). */
    private val nextOf = ConcurrentHashMap<String, String>()

    init {
        Http.referers[cdn] = "$baseUrl/"
    }

    protected suspend fun page(url: String): Document {
        pages[url]?.let { return it }
        val doc = pace.run { Http.getDoc(url) }
        if (pages.size > 40) pages.clear()
        pages[url] = doc
        return doc
    }

    // ---------- lists ----------

    private fun cards(doc: Document): List<VideoRef> = doc.select("div.thumb-block:not(.thumb-cat)").mapNotNull { el ->
        val a = el.selectFirst("div.thumb a[href*=/video], .thumb-inside a[href*=/video]") ?: return@mapNotNull null
        val href = a.attr("abs:href").ifEmpty { return@mapNotNull null }
        val vid = Regex("/video[.-]([a-z0-9]+)").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
        val title = el.selectFirst(".thumb-under a[title]")?.attr("title")?.trim()?.ifEmpty { null }
            ?: el.selectFirst(".thumb-under a")?.text()?.trim().orEmpty()
        if (title.isEmpty()) return@mapNotNull null
        val img = a.selectFirst("img")
        val thumbs = listOf("data-src", "data-sfwthumb").mapNotNull { k -> img?.attr(k)?.takeIf { it.startsWith("http") } }.distinct()
        val dur = el.selectFirst(".thumb-under .duration")?.text()?.trim()
            ?: Regex("(\\d+\\s*(?:min|sec|h)\\w*)").find(el.selectFirst(".thumb-under .metadata")?.text().orEmpty())?.value.orEmpty()
        VideoRef(
            source = id, id = vid, title = title, url = href,
            cover = thumbs.firstOrNull().orEmpty(), altCovers = thumbs.drop(1), duration = dur,
            series = el.selectFirst(".uploader .name, .thumb-under .name")?.text()?.trim().orEmpty(),
        )
    }.distinctBy { it.id }.filterNot { VideoSafety.blocked(it) }

    /** A list from [first] (its first page); later pages follow each page's own "next" link. */
    protected suspend fun list(first: String, page: Int): VideoPage {
        val url = if (page <= 1) first else nextOf["$first#$page"] ?: run {
            // not opened in order (a refresh on page 3): walk the next links from the first page
            var u = first
            for (p in 2..page) { val d = page(u); u = nextLink(d) ?: return VideoPage(emptyList(), false); nextOf["$first#$p"] = u }
            u
        }
        val doc = page(url)
        val next = nextLink(doc)
        if (next != null) nextOf["$first#${page + 1}"] = next
        return VideoPage(cards(doc), next != null)
    }

    private fun nextLink(doc: Document): String? =
        doc.selectFirst("div.pagination a.next-page[href], div.pagination a.no-page.next-page[href], .pagination li.next a[href]")?.attr("abs:href")
            ?: doc.select("div.pagination a[href]").firstOrNull { it.text().trim().lowercase().startsWith("next") }?.attr("abs:href")

    protected fun slug(s: String) = s.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')

    // ---------- the video ----------

    override suspend fun details(ref: VideoRef): VideoDetails {
        val url = ref.url.ifEmpty { throw SourceException("This video has no address") }
        val doc = page(url)
        val html = doc.html()
        val title = Regex("setVideoTitle\\('((?:[^'\\\\]|\\\\.)*)'\\)").find(html)?.groupValues?.get(1)?.replace("\\'", "'")?.ifEmpty { null } ?: ref.title
        val tags = (doc.select("a[href^=/tags/]").map { it.text().trim() } + doc.select("a.is-keyword").map { it.text().trim() })
            .filter { it.isNotEmpty() && it.length < 40 }.distinct()
        val maker = doc.selectFirst("a.uploader-tag .name, li.main-uploader a .name, .video-title .uploader-tag")?.text()?.trim()
            ?: doc.selectFirst("a.uploader-tag, li.main-uploader a")?.text()?.trim().orEmpty()
        val poster = Regex("setThumbUrl169\\('([^']+)'\\)|setThumbUrl\\('([^']+)'\\)").find(html)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }.orEmpty()
        val me = ref.copy(title = title, series = maker.ifEmpty { ref.series }, cover = ref.cover.ifEmpty { poster }, tags = tags)
        val d = VideoDetails(me, stream(html), listOf(me) + related(html), tags)
        VideoSafety.check(d)
        return d
    }

    private fun stream(html: String): VideoStream {
        Regex("setVideoHLS\\('([^']+)'\\)").find(html)?.groupValues?.get(1)?.let { return VideoStream(it, "hls") }
        (Regex("setVideoUrlHigh\\('([^']+)'\\)").find(html) ?: Regex("setVideoUrlLow\\('([^']+)'\\)").find(html))
            ?.groupValues?.get(1)?.let { return VideoStream(it, "mp4") }
        if ("agego" in html || "setFakePlayer(true)" in html || "\"isAgeVerified\":false" in html) {
            runCatching { Http.client.connectionPool.evictAll() }
            throw SourceException("$name asks for an age check in your country. Turn your VPN on and refresh. " +
                "If your VPN only covers some apps (split tunnelling), connect it first, then close and reopen MangaCracker.")
        }
        throw SourceException("No playable video on this page")
    }

    /** The page's related videos (var video_related = [...]). */
    private fun related(html: String): List<VideoRef> {
        val raw = Regex("var video_related\\s*=\\s*(\\[.*?\\]);").find(html)?.groupValues?.get(1) ?: return emptyList()
        val arr = runCatching { Http.json.parseToJsonElement(raw) as JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = runCatching { o[k]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            val u = s("u").ifEmpty { return@mapNotNull null }
            val vid = Regex("/video[.-]([a-z0-9]+)").find(u)?.groupValues?.get(1) ?: return@mapNotNull null
            VideoRef(id, vid, Jsoup.parse(s("tf").ifEmpty { s("t") }).text(), baseUrl + u, series = s("pn"),
                cover = s("i").ifEmpty { s("il") }, duration = s("d"))
        }.filter { it.title.isNotEmpty() }.distinctBy { it.id }.filterNot { VideoSafety.blocked(it) }.take(30)
    }

    /** More from the same uploader when the page names one, else the related videos. */
    override suspend fun series(ref: VideoRef): List<VideoRef> = details(ref).episodes
}

object XvSource : TubeSite("xvideos", "XVideos", "https://www.xvideos.com",
    "xvideos.com · live action · HLS up to 1080p+ (VPN in the UK)", 0xFFD32F2FL, "xvideos-cdn.com") {
    @Volatile private var cats: List<Tag>? = null

    /** The site's categories (from its menu) and well-used tags. */
    override suspend fun genres(): List<Tag> {
        cats?.let { return it }
        val read = runCatching {
            page("$baseUrl/").select("a[href^=/c/]").mapNotNull { a ->
                val path = a.attr("href").removePrefix("/c/").substringBefore('?')
                val label = a.text().trim().ifEmpty { path.substringBeforeLast('-').replace('_', ' ') }
                Tag("c:$path", label, "Category").takeIf { path.isNotEmpty() && label.isNotEmpty() && !VideoSafety.badTag(label) }
            }.distinctBy { it.id }
        }.getOrNull().orEmpty()
        val out = read + TAGS.map { Tag(it, it.replace('-', ' '), "Tag") }
        if (read.isNotEmpty()) cats = out
        return out
    }

    override suspend fun latest(page: Int) = list("$baseUrl/", page)
    override suspend fun popular(page: Int) = list("$baseUrl/best", page)

    override suspend fun search(q: SearchQuery, page: Int): VideoPage {
        val text = listOf(q.text, q.artist).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val tag = q.include.firstOrNull()
        val first = when {
            text.isEmpty() && tag != null && tag.id.startsWith("c:") -> "$baseUrl/c/${tag.id.removePrefix("c:")}"
            text.isEmpty() && tag != null -> "$baseUrl/tags/${slug(tag.id)}"
            else -> "$baseUrl/?k=" + (listOf(text) + q.include.map { it.label }).filter { it.isNotBlank() }.joinToString(" ").urlEncode()
        }
        return list(first, page)
    }

    private val TAGS = ("amateur anal asian big-ass big-tits blonde blowjob brunette creampie cosplay cumshot japanese " +
        "lesbian lingerie massage milf pov redhead stockings threesome big-cock homemade latina ebony fetish glasses").split(' ')
}

object XnSource : TubeSite("xnxx", "XNXX", "https://www.xnxx.com",
    "xnxx.com · live action · HLS up to 1080p+ (VPN in the UK)", 0xFF3B82F6L, "xnxx-cdn.com") {
    override suspend fun genres(): List<Tag> = TAGS.map { Tag(it, it.replace('-', ' '), "Tag") }

    // the site's newest picks; its "hits" are the most watched
    override suspend fun latest(page: Int) = list("$baseUrl/todays-selection", page)
    override suspend fun popular(page: Int) = list("$baseUrl/hits", page)

    override suspend fun search(q: SearchQuery, page: Int): VideoPage {
        val words = (listOf(q.text, q.artist) + q.include.map { it.label }).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (words.isEmpty()) return popular(page)
        return list("$baseUrl/search/" + words.urlEncode().replace("+", "%20"), page)
    }

    private val TAGS = ("amateur anal asian big-ass big-tits blonde blowjob brunette creampie cosplay cumshot japanese " +
        "lesbian lingerie massage milf pov redhead stockings threesome big-cock homemade latina ebony fetish glasses").split(' ')
}
