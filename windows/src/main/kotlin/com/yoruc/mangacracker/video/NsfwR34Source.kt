package com.yoruc.mangacracker.video

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.core.arr
import com.yoruc.mangacracker.core.get
import com.yoruc.mangacracker.core.int
import com.yoruc.mangacracker.core.str
import com.yoruc.mangacracker.core.text
import com.yoruc.mangacracker.core.urlEncode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.util.concurrent.ConcurrentHashMap

/**
 * nsfwr34.com: WordPress + the StreamTube theme (spec: _dev_tools/specs/nsfwr34.md). Lists come from the HTML search
 * page (views, duration, sort by views with time ranges); tag filters go through the open REST API. Streams are plain
 * HLS on Bunny CDN (no Referer, no cookies), a few old posts a direct MP4.
 */
object NsfwR34Source : VideoSource {
    override val id = "nsfwr34"
    override val name = "NSFW R34"
    override val baseUrl = "https://nsfwr34.com"
    override val blurb = "nsfwr34.com · 3D / 2D rule 34 · up to 1080p"
    override val color = 0xFF4FA3E0L

    private val pace = Pace(1000)
    private const val API = "https://nsfwr34.com/wp-json/wp/v2/"
    /** The Bunny Stream pull zone of every playable video (direct HLS and the newer iframe library). */
    private const val ZONE = "https://vz-2222604c-50b.b-cdn.net"
    /** The old Bunny library: its embeds answer a 404 page and the files are not on [ZONE] (checked 2026-11-02). */
    private const val DEAD_LIBRARY = "18848"
    private const val FIELDS = "id,title,link,playbacks,class_list,_links,_embedded"

    // No Http rules needed: pages, pictures and the CDN answered without Referer / cookies / a special agent.

    /** Sorts of the site's search page (key = its `orderby`); read from `SearchQuery.selects["sort"]`. */
    val SORTS = linkedMapOf("date" to "Newest", "post_view" to "Most viewed", "relevance" to "Relevance", "post_like" to "Most liked", "comment_count" to "Most comments")
    /** Time ranges (key = its `date`), any sort; read from `SearchQuery.selects["range"]`. */
    val RANGES = linkedMapOf("" to "All time", "today" to "Today", "7daysago" to "Last 7 days", "30daysago" to "Last 30 days", "this_month" to "This month", "this_year" to "This year")

    // minors: the site has "lolicon"; two artist names spell out lolis. Whole words only ("hololive" holds "loli").
    private val HIDDEN = setOf("loli", "lolicon", "lolis", "shota", "shotacon", "toddlercon", "deathbylolis", "nekololisama")
    private val HIDDEN_PART = Regex("(^|-)(loli|lolicon|lolis|shota|shotacon|toddlercon)(-|$)")
    private fun hiddenSlug(s: String) = s in HIDDEN || HIDDEN_PART.containsMatchIn(s) || VideoSafety.badTag(s)
    /** Tag ids sent as video_tag_exclude on API lists (lolicon = 36237), plus what the start-up lookup finds. */
    private val hiddenIds = ConcurrentHashMap.newKeySet<Int>().apply { add(36237) }
    @Volatile private var checked = false

    /** The 5 categories (2026-11-02): id, slug, name, count. */
    private val CATS = listOf(
        Triple(129, "3d-porn", "3D Rule 34" to 3665), Triple(140, "2d-rule-34", "2D Rule 34" to 1349),
        Triple(261, "4k", "4K" to 1441), Triple(389, "vertical-videos", "Vertical Videos" to 274), Triple(142, "featured", "Featured" to 61),
    )
    private val catIds = ConcurrentHashMap<String, Int>().apply { CATS.forEach { put(it.second, it.first) } }
    private val tagIdsBySlug = ConcurrentHashMap<String, Int>()
    @Volatile private var tagCache: List<Tag>? = null

    // ---------- requests ----------

    private suspend fun api(path: String): Pair<JsonElement, Int> = pace.run {
        withContext(Dispatchers.IO) {
            Http.client.newCall(Http.request(API + path, mapOf("Accept" to "application/json"))).execute().use {
                if (it.code == 400) return@use Http.json.parseToJsonElement("[]") to 0 // past the last page
                if (!it.isSuccessful) throw SourceException("NSFW R34 answered ${it.code}")
                Http.json.parseToJsonElement(it.body!!.string()) to (it.header("X-WP-TotalPages")?.toIntOrNull() ?: 1)
            }
        }
    }

    /** An HTML page; null for a 404 (past the last page, a removed post). */
    private suspend fun html(url: String): Document? = pace.run {
        val (code, body) = Http.getRaw(url)
        when {
            code == 404 -> null
            code !in 200..299 -> throw SourceException("NSFW R34 answered $code")
            else -> Jsoup.parse(body, url)
        }
    }

    /** Adds the ids of minors tags to [hiddenIds] (once per run, up to 3 small calls). */
    private suspend fun safetyCheck() {
        if (checked) return
        checked = true
        runCatching {
            api("video_tag?slug=${HIDDEN.joinToString(",")}&per_page=100&_fields=id,slug").first.arr.forEach { hiddenIds += it.int("id") }
        }
        for (w in listOf("loli", "shota")) runCatching {
            api("video_tag?search=$w&per_page=100&_fields=id,slug").first.arr.forEach { if (hiddenSlug(it.str("slug"))) hiddenIds += it.int("id") }
        }
    }

    // ---------- helpers ----------

    private fun enc(u: String) = u.toHttpUrlOrNull()?.toString() ?: u

    /** "106 views" / "1.1M views" -> "106" / "1.1M". */
    private fun views(t: String?): String = t?.lowercase()?.replace("views", "")?.replace("view", "")?.trim()?.uppercase().orEmpty()

    private fun slugify(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private val NOT_ARTIST = setOf(
        "4k", "8k", "2k", "full", "hd", "uhd", "fhd", "fps", "60fps", "120fps", "30fps", "1080p", "1440p", "2160p", "720p",
        "update", "updated", "animated", "old", "work", "comp", "compilation", "remastered", "remaster", "version", "loop",
        "sound", "audio", "new", "part", "vertical", "edit", "short", "extended", "uncensored", "ver", "v2", "v3",
    )

    /** The animator: the title's last "[...]" that is not a quality mark ("[4K]", "[4K 120 FPS]"). */
    fun artist(title: String): String = Regex("\\[([^\\]]+)]").findAll(title).map { it.groupValues[1].trim() }.toList()
        .lastOrNull { b ->
            val w = b.lowercase().split(Regex("[\\s/,&+._-]+")).filter { it.isNotEmpty() }
            b.length in 2..40 && w.isNotEmpty() && !w.all { it in NOT_ARTIST || it.all(Char::isDigit) }
        }.orEmpty()

    /** "https://vz-….b-cdn.net/<guid>" of a playbacks value / iframe address (null for the dead library). */
    private fun bunnyBase(s: String): String? {
        Regex("https://(vz-[a-z0-9-]+\\.b-cdn\\.net)/([0-9a-f-]{36})").find(s)?.let { return "https://${it.groupValues[1]}/${it.groupValues[2]}" }
        Regex("mediadelivery\\.net/embed/(\\d+)/([0-9a-f-]{36})").find(s)?.let { if (it.groupValues[1] != DEAD_LIBRARY) return "$ZONE/${it.groupValues[2]}" }
        return null
    }

    // ---------- lists ----------

    /** One HTML card (article.type-video), null when hidden. */
    private fun card(a: Element): VideoRef? {
        val classes = a.classNames()
        val pid = classes.firstOrNull { it.matches(Regex("post-\\d+")) }?.removePrefix("post-") ?: return null
        val tags = classes.filter { it.startsWith("video_tag-") }.map { it.removePrefix("video_tag-") }
        if (tags.any { hiddenSlug(it) }) return null
        val cats = classes.filter { it.startsWith("categories-") }.map { "cat:" + it.removePrefix("categories-") }
        val link = a.selectFirst("a.post-permalink") ?: a.selectFirst(".post-title a") ?: return null
        val title = link.attr("title").ifEmpty { link.text() }.trim()
        val img = a.selectFirst(".post-thumbnail img")?.attr("abs:src").orEmpty()
        // fallbacks: the full-size poster, Bunny's still frame, Bunny's moving preview
        val prev = a.attr("data-thumbnail-image-2")
        val alts = listOf(img.replace(Regex("-\\d+x\\d+(?=\\.\\w+$)"), ""), prev.replace("preview.webp", "thumbnail.jpg"), prev)
            .filter { it.isNotEmpty() && it != img }.distinct().map { enc(it) }
        val r = VideoRef(
            source = id, id = pid, title = title, url = link.attr("abs:href"), series = artist(title),
            cover = enc(img).ifEmpty { alts.firstOrNull().orEmpty() },
            duration = a.selectFirst(".video-length")?.text()?.trim().orEmpty(),
            views = views(a.selectFirst(".post-meta__views")?.text()),
            tags = tags + cats, altCovers = alts,
        )
        return r.takeUnless { VideoSafety.blocked(it) }
    }

    private suspend fun htmlList(url: String): VideoPage {
        val doc = html(url) ?: return VideoPage(emptyList(), false)
        val items = doc.select("article.type-video").mapNotNull { card(it) }.distinctBy { it.id }
        return VideoPage(items, doc.selectFirst("link[rel=next]") != null)
    }

    /** The site's search page: 16 a page, any sort and time range. */
    private fun searchUrl(page: Int, text: String, sort: String, range: String = ""): String =
        "$baseUrl/" + (if (page > 1) "page/$page/" else "") + "?s=" + text.urlEncode() + "&post_type=video&orderby=" + sort +
            (if (range.isNotEmpty()) "&date=$range" else "")

    /** One REST API video, null when hidden (no duration / views in the API). */
    private fun apiRef(p: JsonElement): VideoRef? {
        val classes = p["class_list"].arr.map { it.text }
        val tags = classes.filter { it.startsWith("video_tag-") }.map { it.removePrefix("video_tag-") }
        if (tags.any { hiddenSlug(it) }) return null
        val cats = classes.filter { it.startsWith("categories-") }.map { "cat:" + it.removePrefix("categories-") }
        val title = Parser.unescapeEntities(p["title"].str("rendered"), false).trim()
        val fm = p["_embedded"]["wp:featuredmedia"].arr.firstOrNull()
        val medium = fm["media_details"]["sizes"]["streamtube-image-medium"].str("source_url")
        val full = fm.str("source_url")
        val still = bunnyBase(p.str("playbacks"))?.let { "$it/thumbnail.jpg" }.orEmpty()
        val cover = medium.ifEmpty { full }.ifEmpty { still }
        val r = VideoRef(
            source = id, id = p.str("id"), title = title, url = p.str("link"), series = artist(title), cover = enc(cover),
            tags = tags + cats, altCovers = listOf(full, still).filter { it.isNotEmpty() && it != cover }.distinct().map { enc(it) },
        )
        return r.takeUnless { VideoSafety.blocked(it) }
    }

    /** Tag ids for [tags]: numeric ids as they are, slugs / names looked up (cached). */
    private suspend fun tagIds(tags: List<Tag>): List<Int> {
        val out = mutableListOf<Int>()
        val missing = mutableListOf<Pair<String, String>>() // slug, label
        for (t in tags) {
            val n = t.id.toIntOrNull()
            if (n != null) { out += n; continue }
            val slug = slugify(t.id.ifEmpty { t.label })
            val known = tagIdsBySlug[slug]
            if (known != null) out += known else missing += slug to t.label
        }
        if (missing.isNotEmpty()) {
            runCatching {
                api("video_tag?slug=${missing.joinToString(",") { it.first }}&per_page=100&_fields=id,slug").first.arr
                    .forEach { tagIdsBySlug[it.str("slug")] = it.int("id") }
            }
            for ((slug, label) in missing) {
                val found = tagIdsBySlug[slug] ?: runCatching {
                    // a tag tapped by its name ("Final Fantasy Rule 34"): the tag search, exact name first
                    val l = api("video_tag?search=${label.ifEmpty { slug }.urlEncode()}&per_page=20&_fields=id,slug,name").first.arr
                    (l.firstOrNull { Parser.unescapeEntities(it.str("name"), false).equals(label, true) } ?: l.firstOrNull())?.int("id")
                }.getOrNull()
                if (found != null && found > 0) out += found
            }
        }
        return out.distinct()
    }

    private fun catId(t: Tag): Int? = t.id.removePrefix("cat:").let { it.toIntOrNull() ?: catIds[it] }

    /** The REST API: text + tags (AND) + categories + exclusions, newest first, [perPage] a page. */
    private suspend fun apiSearch(text: String, inc: List<Tag>, exc: List<Tag>, page: Int, perPage: Int = 20): VideoPage {
        safetyCheck()
        val incCats = inc.filter { it.id.startsWith("cat:") }.mapNotNull { catId(it) }
        val incTags = tagIds(inc.filter { !it.id.startsWith("cat:") })
        // a tag we could not find: no results rather than every video
        if (inc.isNotEmpty() && incCats.size + incTags.size < inc.size) return VideoPage(emptyList(), false)
        val excCats = exc.filter { it.id.startsWith("cat:") }.mapNotNull { catId(it) }
        val excTags = tagIds(exc.filter { !it.id.startsWith("cat:") }) + hiddenIds
        val sb = StringBuilder("video?per_page=$perPage&page=$page&_embed=wp:featuredmedia&_fields=$FIELDS")
        if (text.isNotEmpty()) sb.append("&search=").append(text.urlEncode())
        if (incTags.isNotEmpty()) {
            sb.append("&video_tag%5Bterms%5D=").append(incTags.joinToString(","))
            if (incTags.size > 1) sb.append("&video_tag%5Boperator%5D=AND")
        }
        if (incCats.isNotEmpty()) {
            sb.append("&video_category%5Bterms%5D=").append(incCats.joinToString(","))
            if (incCats.size > 1) sb.append("&video_category%5Boperator%5D=AND")
        }
        if (excTags.isNotEmpty()) sb.append("&video_tag_exclude=").append(excTags.distinct().joinToString(","))
        if (excCats.isNotEmpty()) sb.append("&video_category_exclude=").append(excCats.joinToString(","))
        val (j, pages) = api(sb.toString())
        return VideoPage(j.arr.mapNotNull { apiRef(it) }, page < pages)
    }

    override suspend fun latest(page: Int): VideoPage = htmlList(searchUrl(page, "", "date"))

    /** Most viewed of all time. */
    override suspend fun popular(page: Int): VideoPage = popular(page, "")

    /** Most viewed in a time range (a key of [RANGES]). */
    suspend fun popular(page: Int, range: String): VideoPage = htmlList(searchUrl(page, "", "post_view", range.takeIf { it in RANGES }.orEmpty()))

    override suspend fun search(q: SearchQuery, page: Int): VideoPage {
        val text = q.text.trim().ifEmpty { q.artist.trim() }
        if (q.include.isNotEmpty() || q.exclude.isNotEmpty()) return apiSearch(text, q.include, q.exclude, page)
        val sort = q.selects["sort"]?.takeIf { it in SORTS } ?: if (text.isEmpty()) "date" else "relevance"
        val range = q.selects["range"]?.takeIf { it in RANGES }.orEmpty()
        return htmlList(searchUrl(page, text, sort, range))
    }

    // ---------- tags ----------

    /** Categories, then the 200 most used tags with counts (3 calls, cached for the run). */
    override suspend fun genres(): List<Tag> {
        tagCache?.let { return it }
        val cats = runCatching {
            api("video_category?per_page=100&_fields=id,name,slug,count").first.arr.map {
                catIds[it.str("slug")] = it.int("id")
                Tag("cat:${it.int("id")}", Parser.unescapeEntities(it.str("name"), false), "Category", it.int("count"))
            }
        }.getOrNull()?.ifEmpty { null } ?: CATS.map { Tag("cat:${it.first}", it.third.first, "Category", it.third.second) }
        val tags = mutableListOf<Tag>()
        runCatching {
            for (p in 1..2) {
                val (j, pages) = api("video_tag?per_page=100&page=$p&orderby=count&order=desc&_fields=id,name,slug,count")
                j.arr.forEach {
                    val slug = it.str("slug")
                    val name = Parser.unescapeEntities(it.str("name"), false).trim()
                    tagIdsBySlug[slug] = it.int("id")
                    if (!hiddenSlug(slug) && !VideoSafety.badTag(name) && !VideoSafety.badText(name)) tags += Tag(it.int("id").toString(), name, "Tag", it.int("count"))
                }
                if (p >= pages) break
            }
        }
        return (cats + tags).also { if (tags.isNotEmpty()) tagCache = it }
    }

    // ---------- one video ----------

    private fun kindOf(url: String, type: String) = when {
        "mpegurl" in type.lowercase() || ".m3u8" in url -> "hls"
        "webm" in type.lowercase() || url.substringBefore('?').endsWith(".webm") -> "webm"
        else -> "mp4"
    }

    /** The player's source; old posts hold an iframe (Bunny library / rule34video) instead. */
    private fun stream(doc: Document): VideoStream {
        doc.selectFirst("video-js[data-settings]")?.attr("data-settings")?.takeIf { it.isNotEmpty() }?.let { s ->
            val src = runCatching { Http.json.parseToJsonElement(s)["sources"].arr.firstOrNull() }.getOrNull()
            val u = src.str("src")
            if (u.isNotEmpty()) return VideoStream(enc(u), kindOf(u, src.str("type")))
        }
        doc.selectFirst(".player-embed video source[src], .player-embed video[src]")?.attr("abs:src")?.takeIf { it.isNotEmpty() }?.let {
            return VideoStream(enc(it), kindOf(it, ""))
        }
        val frame = doc.selectFirst(".player-embed iframe[src]")?.attr("abs:src").orEmpty()
        when {
            // rule34video has an age-verification wall here: not opened from the app
            "rule34video.com" in frame -> throw SourceException("This video only plays on rule34video.com")
            "mediadelivery.net/embed/" in frame ->
                return bunnyBase(frame)?.let { VideoStream("$it/playlist.m3u8", "hls") } ?: throw SourceException("This old video is gone from the site")
        }
        throw SourceException("No video on this post")
    }

    override suspend fun details(ref: VideoRef): VideoDetails {
        val doc = html(ref.url.ifEmpty { "$baseUrl/?p=${ref.id}" }) ?: throw SourceException("This video is gone from the site")
        val main = doc.selectFirst("article#post-${ref.id}") ?: doc.selectFirst("article.type-video")
        val classes = main?.classNames().orEmpty()
        val tagLinks = doc.select(".post-tags a[rel=tag]")
        val slugs = (classes.filter { it.startsWith("video_tag-") }.map { it.removePrefix("video_tag-") } +
            tagLinks.map { it.attr("href").trimEnd('/').substringAfterLast('/') }).distinct()
        if (slugs.any { hiddenSlug(it) }) throw SourceException("This video is hidden")
        val cats = classes.filter { it.startsWith("categories-") }.map { "cat:" + it.removePrefix("categories-") }
        val tagNames = tagLinks.map { it.text().trim() }.filter { it.isNotEmpty() }
        val catNames = doc.select(".post-bottom__meta .post-categories a[rel=tag]").map { it.text().trim() }.filter { it.isNotEmpty() }
        val title = doc.selectFirst("h1.post-title")?.text()?.trim()?.ifEmpty { null } ?: ref.title
        val dur = Regex("\"duration\"\\s*:\\s*\"P\\d*DT(\\d+)H(\\d+)M(\\d+)S\"").find(doc.html())?.groupValues
            ?.let { g -> clock(g[1].toLong() * 3600 + g[2].toLong() * 60 + g[3].toLong()) }
        val poster = runCatching {
            Http.json.parseToJsonElement(doc.selectFirst("video-js[data-settings]")?.attr("data-settings").orEmpty()).str("poster")
        }.getOrDefault("")
        val related = doc.select("div.widget").firstOrNull { it.selectFirst(".widget-title")?.text()?.contains("Related", true) == true }
            ?.select("article.type-video")?.mapNotNull { card(it) }.orEmpty().filter { it.id != ref.id }.distinctBy { it.id }
        val me = ref.copy(
            title = title,
            url = doc.selectFirst("link[rel=canonical]")?.attr("abs:href")?.ifEmpty { null } ?: ref.url.ifEmpty { "$baseUrl/?p=${ref.id}" },
            cover = ref.cover.ifEmpty { enc(poster) },
            duration = dur ?: ref.duration,
            views = views(doc.selectFirst(".post-bottom__meta .post-meta__views")?.text()).ifEmpty { ref.views },
            series = artist(title),
            tags = (slugs + cats).ifEmpty { ref.tags },
        )
        val d = VideoDetails(
            ref = me,
            stream = stream(doc),
            episodes = related,
            tags = (catNames + tagNames).distinct(),
            description = doc.selectFirst(".post-content .js-read")?.text()?.trim().orEmpty(),
        )
        VideoSafety.check(d)
        return d
    }

    /** More by the same animator: their tag when the post has one, else a text search for the name; else related. */
    override suspend fun series(ref: VideoRef): List<VideoRef> {
        val name = ref.series.ifEmpty { artist(ref.title) }
        if (name.isEmpty()) return details(ref).episodes
        val flat = slugify(name).replace("-", "")
        val tag = ref.tags.firstOrNull { !it.startsWith("cat:") && it.replace("-", "") == flat }
        val list = if (tag != null) apiSearch("", listOf(Tag(tag, name)), emptyList(), 1, perPage = 50).items
        else htmlList(searchUrl(1, name, "relevance")).items.filter { it.series.equals(name, true) }
        return list.ifEmpty { listOf(ref) }
    }
}
