package com.yoruc.mangacracker.video

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.core.urlEncode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

/**
 * porcore.com: 3D / cartoon / hentai clips on a custom PHP site (spec: _dev_tools/specs/porcore.md).
 * Lists come as small "ajax" fragments, 19 a page; streams are plain HLS (up to 1080p) with no headers needed.
 * Lists carry no tags: tags are read on the video page.
 */
object PorcoreSource : VideoSource {
    override val id = "porcore"
    override val name = "Porcore"
    override val baseUrl = "https://porcore.com"
    override val blurb = "porcore.com · 3D / cartoon · HLS up to 1080p"
    override val color = 0xFFC084FCL

    private val pace = Pace(1000)
    /** The site's only tag naming an age (loli / shota searches are refused by the site itself). */
    private val HIDDEN = setOf("teenage-sex")
    /** Videos found with a hidden tag on their page: left out of every later list. */
    private val blockedIds = ConcurrentHashMap.newKeySet<String>()
    private val pages = ConcurrentHashMap<String, Document>()
    @Volatile private var genreCache: List<Tag>? = null

    init {
        // thumbnails come through the site's own resizer (res.php): space a grid of them a little
        Http.rateLimits["porcore.com/res.php"] = 50L
    }

    private suspend fun page(url: String): Document = pace.run { Http.getDoc(url) }

    /** Fallback when the tag cloud cannot be read (the most used tags, 2026-11-02). */
    private val FALLBACK = ("3d anal-sex big-ass big-dicks big-tits blowjob cartoons cosplay cowgirl creampie cumshot " +
        "doggy-style elves english-voice fantasy furry futanari group-sex hentai lesbians lingerie milf monsters " +
        "oral-sex overwatch parodies pov rape resident-evil shemales story tentacles threesome uncensored").split(' ')

    private fun label(slug: String) = slug.replace('-', ' ')

    /** Tags from the "All tags" cloud (no counts) + authors with their video counts, read once per run. */
    override suspend fun genres(): List<Tag> {
        genreCache?.let { return it }
        val read = runCatching {
            val doc = page("$baseUrl/")
            val tags = doc.select("div.tagcloud a[href^='/show/']").map { a ->
                val slug = a.attr("href").removePrefix("/show/").trimEnd('/')
                Tag(slug, a.text().ifEmpty { label(slug) }, "Tag")
            }
            val authors = doc.select("select.author option[value^='from:']").map { o ->
                Tag(o.attr("value"), o.text().trim(), "Author", Regex("\\d+").find(o.attr("data-subtext"))?.value?.toIntOrNull() ?: 0)
            }
            (tags.filter { it.id !in HIDDEN && !VideoSafety.badTag(it.id) } + authors).distinctBy { it.id }
        }.getOrNull().orEmpty()
        if (read.isNotEmpty()) genreCache = read
        return read.ifEmpty { FALLBACK.map { Tag(it, label(it), "Tag") } }
    }

    private fun shortViews(s: String): String {
        val n = s.filter { it.isDigit() }.toLongOrNull() ?: return ""
        return when {
            n >= 1_000_000 -> "%.1fM".format(n / 1e6)
            n >= 1_000 -> "%.0fK".format(n / 1e3)
            else -> n.toString()
        }
    }

    private fun item(e: Element): VideoRef? {
        val a = e.selectFirst("a.clip-link[href*='/video/']") ?: return null
        val vid = a.attr("data-id").ifEmpty { Regex("/video/(\\d+)").find(a.attr("href"))?.groupValues?.get(1).orEmpty() }
        if (vid.isEmpty() || vid in blockedIds) return null
        val img = e.selectFirst("img")?.attr("abs:src").orEmpty()
        // the raw picture behind the resizer, in case res.php refuses
        val raw = Regex("src=(media/thumbs/\\d+/i\\d+\\.jpg)").find(img)?.groupValues?.get(1)?.let { "$baseUrl/$it" }
        val r = VideoRef(
            source = id, id = vid, title = a.attr("title").ifEmpty { e.selectFirst("h5")?.text().orEmpty() },
            url = a.attr("abs:href").ifEmpty { "$baseUrl/video/$vid/" },
            cover = img.ifEmpty { "$baseUrl/media/thumbs/$vid/i101.jpg" },
            duration = e.selectFirst(".floatlefttop")?.text()?.trim().orEmpty(),
            views = shortViews(e.selectFirst(".rating .floatright")?.text().orEmpty()),
            altCovers = listOfNotNull(raw),
        )
        return r.takeUnless { VideoSafety.blocked(it) }
    }

    private fun parse(doc: Document): VideoPage =
        VideoPage(doc.select("div.onevideothumb").mapNotNull { item(it) }, doc.selectFirst("#page_nav a[href]") != null)

    // the next link drops "&sort=", so page URLs are always built here
    override suspend fun latest(page: Int) = parse(page("$baseUrl/&ajax&p=$page"))

    /** Most viewed of all time (the site has no time ranges). */
    override suspend fun popular(page: Int) = parse(page("$baseUrl/&ajax&p=$page&sort=mostviewed"))

    /**
     * One keyword search: the words, the picked tag slugs and author ("from:Name") all go into /show/<words>.
     * Excluded tags cannot be applied (list items carry no tags).
     */
    override suspend fun search(q: SearchQuery, page: Int): VideoPage {
        val words = (listOf(q.text.trim(), q.artist.trim().takeIf { it.isNotEmpty() }?.let { "from:" + it.replace(" ", "") }.orEmpty()) +
            q.include.map { it.id }).filter { it.isNotEmpty() }.joinToString(" ")
        if (words.isEmpty()) return latest(page)
        return parse(page("$baseUrl/show/${words.urlEncode()}?ajax&p=$page"))
    }

    private suspend fun videoPage(ref: VideoRef): Document =
        pages[ref.id] ?: page(ref.url.ifEmpty { "$baseUrl/video/${ref.id}/" }).also { pages[ref.id] = it }

    override suspend fun details(ref: VideoRef): VideoDetails {
        val doc = videoPage(ref)
        val tags = doc.select("a.label.label-default[href*='/show/']").map { it.attr("href").trimEnd('/').substringAfterLast('/') }
        if (tags.any { it in HIDDEN }) { blockedIds += ref.id; throw SourceException("This video is hidden") }
        val src = doc.selectFirst("video#currentvideo source[src]")?.attr("abs:src")
            ?: doc.selectFirst("video source[src*='.m3u8']")?.attr("abs:src")
            ?: throw SourceException("No video on this page")
        val kind = if (".m3u8" in src) "hls" else if (src.endsWith(".webm")) "webm" else "mp4"
        val poster = doc.selectFirst("video#currentvideo")?.attr("abs:poster").orEmpty()
        val me = ref.copy(
            title = doc.selectFirst("h1")?.text()?.ifEmpty { null } ?: ref.title,
            cover = ref.cover.ifEmpty { poster },
            tags = tags,
        )
        val related = doc.select(".related div.onevideothumb").mapNotNull { item(it) }.filter { it.id != me.id }
        val d = VideoDetails(
            ref = me,
            stream = VideoStream(src, kind),
            episodes = listOf(me) + related,
            tags = tags,
            description = doc.selectFirst("#longD")?.text().orEmpty(),
        )
        VideoSafety.check(d)
        return d
    }

    /** One-off clips: "more like this" = the page's related videos. */
    override suspend fun series(ref: VideoRef): List<VideoRef> = details(ref).episodes
}
