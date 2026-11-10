package com.yoruc.mangacracker.video

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

/**
 * rule34video.com: 3D / animated clips on KVS (Kernel Video Sharing). Spec: _dev_tools/specs/rule34video.md.
 * In the UK the site puts an age check (ageverif.com) in front of every page: when a page carries it we stop and say
 * so instead of reading on (works on a VPN). Streams are the player's flashvars (video_url, video_alt_url...), plain
 * or KVS-scrambled ("function/0/..." + license_code), MP4 up to 1080p / 4K; the file host wants the page as Referer.
 */
object Rule34VideoSource : VideoSource {
    override val id = "rule34video"
    override val name = "Rule34Video"
    override val baseUrl = "https://rule34video.com"
    override val blurb = "rule34video.com · 3D / animated · MP4 up to 4K (VPN in the UK)"
    override val color = 0xFF9BCB3CL

    private val pace = Pace(900)
    private val pages = ConcurrentHashMap<String, Document>()

    init {
        Http.referers["rule34video.com"] = "https://rule34video.com/"
    }

    private suspend fun page(url: String): Document {
        pages[url]?.let { return it }
        val doc = pace.run { Http.getDoc(url) }
        if (doc.select("script[src*=ageverif]").isNotEmpty() || doc.select("[class*=ageverif], #ageverif").isNotEmpty()) {
            // connections made before the VPN came on keep going around it: the next try starts fresh ones
            runCatching { Http.client.connectionPool.evictAll() }
            throw SourceException("Rule34Video asks for an age check in your country. Turn your VPN on and refresh. " +
                "If your VPN only covers some apps (split tunnelling), connect it first, then close and reopen MangaCracker.")
        }
        if (pages.size > 40) pages.clear()
        pages[url] = doc
        return doc
    }

    /** Main kinds and well-used tags (slugs as in /tags/<slug>/). */
    private val GENRES = listOf(
        "c:2109" to "Straight", "c:15" to "Futa", "c:192" to "Gay", "c:4747" to "Music (PMV / HMV)", "c:1821" to "Iwara",
    ).map { Tag(it.first, it.second, "Type") } + ("3d animated blender sfm 2d sound voiced big-breasts big-ass anal oral " +
        "creampie futanari monster tentacles overwatch genshin-impact pokemon league-of-legends final-fantasy " +
        "resident-evil tifa-lockhart 2b nier-automata elf furry mmd koikatsu pov dancing hentai").split(' ')
        .map { Tag(it, it.replace('-', ' '), "Tag") }

    override suspend fun genres(): List<Tag> = GENRES

    private fun cards(doc: Document, scope: Element = doc): List<VideoRef> = scope.select("div.item.thumb:has(a.th[href*=/video])").mapNotNull { el ->
        val a = el.selectFirst("a.th") ?: return@mapNotNull null
        val href = a.attr("abs:href")
        val vid = Regex("/videos?/(\\d+)").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
        val title = a.attr("title").ifBlank { a.selectFirst(".thumb_title")?.text().orEmpty() }.trim()
        if (title.isEmpty()) return@mapNotNull null
        val img = a.selectFirst("img")
        val thumbs = listOf("data-rd-jpg", "data-rd-src", "data-original", "data-src", "src")
            .mapNotNull { k -> img?.absUrl(k)?.takeIf { it.isNotBlank() && "placeholder" !in it && "blank" !in it && !it.startsWith("data:") } }
            .distinct()
        VideoRef(
            source = id, id = vid, title = title, url = href,
            cover = thumbs.firstOrNull().orEmpty(), altCovers = thumbs.drop(1),
            duration = el.selectFirst(".time, .duration")?.text()?.trim().orEmpty(),
            views = el.selectFirst(".views")?.text()?.trim().orEmpty(),
        )
    }.filterNot { VideoSafety.blocked(it) }

    private fun hasNext(doc: Document) = doc.selectFirst("nav.fs-pager a[rel=next], div.item.pager.next a, .pagination a.next, li.next a") != null

    private suspend fun list(url: String): VideoPage {
        val doc = page(url)
        return VideoPage(cards(doc), hasNext(doc))
    }

    override suspend fun latest(page: Int) = list("$baseUrl/latest-updates/$page/")
    override suspend fun popular(page: Int) = list("$baseUrl/most-popular/$page/")

    private fun slug(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "-").trim('-')

    override suspend fun search(q: SearchQuery, page: Int): VideoPage {
        val kind = q.include.firstOrNull { it.id.startsWith("c:") }?.id?.removePrefix("c:")
        val tag = q.include.firstOrNull { !it.id.startsWith("c:") }
        val text = listOf(q.text, q.artist).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val sort = q.selects["sort"].orEmpty()
        val params = buildList {
            kind?.let { add("flag1=$it") }
            if (sort.isNotEmpty()) add("sort_by=$sort")
        }
        val url = when {
            // a tag page lists that tag; text, when there is some, wins (the site has no "tag + words" search)
            text.isEmpty() && tag != null -> "$baseUrl/tags/${slug(tag.id)}/" + (if (page > 1) "$page/" else "") + params.q()
            else -> "$baseUrl/search/" + (if (text.isNotEmpty()) "${slug(text)}/" else "") +
                (params + listOfNotNull(if (page > 1) "from_videos=$page" else null)).q()
        }
        val r = list(url)
        // excluded kinds / tags can only be checked on the video page: drop the ones whose title names them
        val out = q.exclude.map { it.label.lowercase() }
        return if (out.isEmpty()) r else r.copy(items = r.items.filterNot { v -> out.any { v.title.lowercase().contains(it) } })
    }

    private fun List<String>.q() = if (isEmpty()) "" else joinToString("&", "?")

    override suspend fun details(ref: VideoRef): VideoDetails {
        val doc = page(ref.url.ifEmpty { "$baseUrl/video/${ref.id}/" })
        val tags = doc.select("div.vp-tags a.tag_item, div.row_spacer:has(div.label:contains(Tags)) a.tag_item:not(:contains(Suggest))").map { it.text().trim() }
        val cats = doc.select("div.col a[href*='/categories/'], div.vp-group a[href*='/categories/']").map { it.text().trim() }
        val artists = doc.select("div.col a[href*='/models/'], div.vp-group a[href*='/models/']").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        val title = doc.selectFirst("h1.title_video, h1.vp-title")?.text()?.trim()?.ifEmpty { null } ?: ref.title
        val poster = doc.selectFirst("video[poster]")?.attr("abs:poster")?.ifEmpty { null }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("abs:content").orEmpty()
        val me = ref.copy(title = title, series = artists.firstOrNull() ?: ref.series, cover = ref.cover.ifEmpty { poster },
            altCovers = (ref.altCovers + poster).filter { it.isNotEmpty() && it != ref.cover }.distinct(),
            tags = (tags + cats).distinct())
        val related = cards(doc).filter { it.id != ref.id }
        val d = VideoDetails(me, stream(doc), listOf(me) + related, (tags + cats).distinct(),
            doc.selectFirst("div.vp-desc, div.row:has(div.label > em) > div.label > em")?.text().orEmpty())
        VideoSafety.check(d)
        return d
    }

    /** The best file the player offers. */
    private fun stream(doc: Document): VideoStream {
        val script = doc.select("script").map { it.data() }.firstOrNull { "flashvars" in it && "video_url" in it }
        val found = mutableListOf<Pair<Int, String>>()
        if (script != null) {
            val license = Regex("license_code\\s*:\\s*['\"]([^'\"]+)['\"]").find(script)?.groupValues?.get(1).orEmpty()
            val texts = Regex("(video(?:_alt)?_url\\d*)_text\\s*:\\s*['\"]([^'\"]+)['\"]").findAll(script).associate { it.groupValues[1] to it.groupValues[2] }
            Regex("(video(?:_alt)?_url\\d*)\\s*:\\s*['\"]((?:function/0/)?https?:[^'\"]+)['\"]").findAll(script).forEach { m ->
                val url = kvsUrl(m.groupValues[2].replace("\\/", "/"), license)
                val q = Regex("(\\d{3,4})p").find(texts[m.groupValues[1]] ?: url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                found += q to url
            }
        }
        if (found.isEmpty()) doc.select("video source[src], video[src]").forEach { s ->
            val u = s.absUrl("src").ifEmpty { return@forEach }
            found += (Regex("(\\d{3,4})p").find(s.attr("title") + s.attr("label") + u)?.groupValues?.get(1)?.toIntOrNull() ?: 0) to u
        }
        if (found.isEmpty()) doc.select("a.tag_item_download[href], div.row:has(div.label:contains(Download)) a.tag_item[href]").forEach { a ->
            found += (Regex("(\\d{3,4})p").find(a.text())?.groupValues?.get(1)?.toIntOrNull() ?: 0) to a.absUrl("href").replace(Regex("[?&]download(_filename)?=[^&]*"), "")
        }
        // 1080p is plenty on a phone (4K files are huge on mobile data)
        val pick = found.filter { it.first in 1..1080 }.maxByOrNull { it.first } ?: found.maxByOrNull { it.first }
            ?: throw SourceException("No playable video on this page")
        return VideoStream(pick.second, if (".m3u8" in pick.second) "hls" else "mp4")
    }

    /** KVS scrambles the 32-char hash in "function/0/<url>" with a key made from license_code. */
    internal fun kvsUrl(raw: String, license: String): String {
        if (!raw.startsWith("function/0/")) return raw
        val url = raw.removePrefix("function/0/")
        if (license.isEmpty()) return url
        val scheme = url.substringBefore("://")
        val rest = url.substringAfter("://")
        val parts = ("/" + rest.substringAfter('/')).split('/').toMutableList()   // ["", a, b, hash..., ...]
        val host = rest.substringBefore('/')
        if (parts.size < 4 || parts[3].length < 32) return url
        val token = licenseToken(license)
        val hash = parts[3].substring(0, 32)
        val idx = IntArray(32) { it }
        var acc = 0
        for (src in 31 downTo 0) {
            acc += token.getOrElse(src) { 0 }
            val dest = (src + acc) % 32
            val t = idx[src]; idx[src] = idx[dest]; idx[dest] = t
        }
        parts[3] = idx.joinToString("") { hash[it].toString() } + parts[3].substring(32)
        return "$scheme://$host" + parts.joinToString("/")
    }

    private fun licenseToken(license: String): List<Int> {
        val lic = license.replace("$", "")
        val values = lic.map { it.digitToIntOrNull() ?: 0 }
        val mod = lic.replace('0', '1')
        val center = mod.length / 2
        val front = mod.substring(0, center + 1).toLong()
        val back = mod.substring(center).toLong()
        val m = (4 * kotlin.math.abs(front - back)).toString().take(center + 1)
        return m.flatMapIndexed { i, c -> (0 until 4).map { off -> (values.getOrElse(i + off) { 0 } + c.digitToInt()) % 10 } }
    }

    /** More from the same artist (their /models/ page), else the related videos. */
    override suspend fun series(ref: VideoRef): List<VideoRef> {
        val d = details(ref)
        val doc = page(ref.url.ifEmpty { "$baseUrl/video/${ref.id}/" })
        val artist = doc.selectFirst("div.col a[href*='/models/'], div.vp-group a[href*='/models/']")?.attr("abs:href")
        if (artist != null) runCatching { cards(page(artist)) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        return d.episodes
    }
}
