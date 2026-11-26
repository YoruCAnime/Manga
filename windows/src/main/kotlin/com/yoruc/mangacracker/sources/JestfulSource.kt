package com.yoruc.mangacracker.sources

import com.yoruc.mangacracker.core.Chapter
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.MangaDetails
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Page
import com.yoruc.mangacracker.core.PageResult
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SelectDef
import com.yoruc.mangacracker.core.Source
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.core.urlEncode
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** jestful.net ("JF"): Japanese raw manga, plain HTML pages. Spec: _dev_tools/specs/jestful.md */
object JestfulSource : Source {
    override val id = "jestful"
    override val name = "Jestful"
    override val baseUrl = "https://jestful.net"
    override val blurb = "jestful.net · Japanese raw manga"
    override val color = 0xFF0EA5A4
    // a general raw site: a few Adult / Smut / Mature titles, the rest all-ages
    override val nsfw = false

    /** Chapter times on the site are local to UTC+7 (checked against its "12 minutes ago"). */
    private val SITE_TZ = ZoneOffset.ofHours(7)
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** URL prefixes the site puts before slugs ("hwms-<slug>.html", "bsaq-<slug>-chapter-N.html"); learnt again from lists. */
    @Volatile private var mangaPrefix = "hwms"

    init {
        // lists only: covers come from jestful.net/app/... too and must not queue behind pages
        Http.rateLimits["jestful.net/manga-list"] = 500L
    }

    override val selects = listOf(
        SelectDef("sort", "Sort", listOf(
            "views|DESC" to "Most read", "last_update|DESC" to "Latest update", "name|ASC" to "A-Z", "name|DESC" to "Z-A",
        )),
        SelectDef("status", "Status", listOf("" to "Any", "2" to "Ongoing", "1" to "Completed")),
    )

    // ---------- lists ----------

    /** The widest entry of a srcset (cover.php URLs are signed, so the width cannot be changed). */
    private fun bestSrc(img: Element?): String {
        if (img == null) return ""
        val best = img.attr("srcset").split(",").mapNotNull { part ->
            val bits = part.trim().split(Regex("\\s+"))
            val w = bits.getOrNull(1)?.removeSuffix("w")?.toIntOrNull() ?: return@mapNotNull null
            w to bits[0]
        }.maxByOrNull { it.first }?.second
        val src = best ?: img.attr("src")
        return when {
            src.isEmpty() -> ""
            src.startsWith("http") -> src
            src.startsWith("//") -> "https:$src"
            else -> baseUrl + "/" + src.removePrefix("/")
        }
    }

    private fun learnPrefix(href: String) {
        val p = href.removePrefix(baseUrl).trimStart('/').substringBefore('-')
        if (p.isNotEmpty() && p.length <= 8 && p.all { it.isLetterOrDigit() }) mangaPrefix = p
    }

    private fun parseList(doc: Document): PageResult {
        val items = doc.select("article.manga-update-card").mapNotNull { card ->
            val a = card.selectFirst("a.update-cover-link") ?: return@mapNotNull null
            val slug = a.attr("data-manga-slug").ifEmpty { return@mapNotNull null }
            learnPrefix(a.attr("href"))
            val title = card.selectFirst("h2.update-title a")?.let { it.attr("title").ifEmpty { it.text() } } ?: slug
            val ch = card.selectFirst("a.update-chapter-link")?.text()?.replace("◈", "")?.trim().orEmpty()
            MangaRef(id, slug, title.trim(), cover = bestSrc(a.selectFirst("img")), subtitle = ch)
        }
        val next = doc.selectFirst("nav.modern-pagination a[title=Next Page]") != null
        return PageResult(items, next)
    }

    private suspend fun list(params: String, page: Int): PageResult =
        parseList(Http.getDoc("$baseUrl/manga-list.html?page=$page$params"))

    override suspend fun popular(page: Int) = list("&sort=views&sort_type=DESC", page)
    override suspend fun latest(page: Int) = list("&sort=last_update&sort_type=DESC", page)

    override suspend fun search(query: SearchQuery, page: Int): PageResult {
        val p = StringBuilder()
        if (query.text.isNotBlank()) p.append("&title=").append(query.text.trim().urlEncode())
        if (query.artist.isNotBlank()) p.append("&author=").append(query.artist.trim().urlEncode())
        query.include.forEach { p.append("&include_genres%5B%5D=").append(it.id.urlEncode()) }
        query.exclude.forEach { p.append("&exclude_genres%5B%5D=").append(it.id.urlEncode()) }
        query.selects["status"]?.takeIf { it.isNotEmpty() }?.let { p.append("&m_status=").append(it) }
        val sort = (query.selects["sort"] ?: "views|DESC").split('|')
        p.append("&sort=").append(sort[0]).append("&sort_type=").append(sort.getOrElse(1) { "DESC" })
        return list(p.toString(), page)
    }

    // ---------- tags ----------

    /** The site's genre pills (slug, name), 2026-11. None of them is minor-related. */
    private val GENRES = listOf(
        "action" to "Action", "adult" to "Adult", "adventure" to "Adventure", "comedy" to "Comedy", "demons" to "Demons",
        "drama" to "Drama", "ecchi" to "Ecchi", "fantasy" to "Fantasy", "gender-bender" to "Gender Bender", "harem" to "Harem",
        "historical" to "Historical", "horror" to "Horror", "isekai" to "Isekai", "josei" to "Josei", "magic" to "Magic",
        "mature" to "Mature", "mecha" to "Mecha", "mystery" to "Mystery", "psychological" to "Psychological", "romance" to "Romance",
        "school-life" to "School Life", "sci-fi" to "Sci-fi", "seinen" to "Seinen", "shoujo" to "Shoujo", "shounen" to "Shounen",
        "shounen-ai" to "Shounen Ai", "slice-of-life" to "Slice of Life", "smut" to "Smut", "sports" to "Sports",
        "super-power" to "Super Power", "supernatural" to "Supernatural", "tragedy" to "Tragedy", "yaoi" to "Yaoi", "yuri" to "Yuri",
    ).map { Tag(it.first, it.second, "Genre") }

    @Volatile private var tagCache: List<Tag>? = null

    override suspend fun tags(): List<Tag> {
        tagCache?.let { return it }
        val live = runCatching {
            Http.getDoc("$baseUrl/manga-list.html").select("button.filter-genre-pill").map { Tag(it.attr("data-slug"), it.attr("data-name"), "Genre") }
        }.getOrDefault(emptyList()).filter { it.id.isNotEmpty() }
        return Safety.cleanTags(live.ifEmpty { GENRES }).also { tagCache = it }
    }

    override fun tagQuery(name: String): SearchQuery {
        val t = (tagCache ?: GENRES).firstOrNull { it.label.equals(name, true) }
            ?: Tag(name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-'), name)
        return SearchQuery(include = listOf(t))
    }

    // ---------- details ----------

    /** slug -> (fetched at, page): details, chapters and related share one download for a minute. */
    private val docs = ConcurrentHashMap<String, Pair<Long, Document>>()

    private suspend fun mangaPage(slug: String, fresh: Boolean = false): Document {
        val hit = docs[slug]
        if (!fresh && hit != null && System.currentTimeMillis() - hit.first < 60_000) return hit.second
        return fetchPage(slug).also { docs[slug] = System.currentTimeMillis() to it }
    }

    /** GET that relearns the slug prefix once when the site has changed it (old prefix = 404). */
    private suspend fun fetchPage(slug: String): Document {
        val (code, html) = Http.getRaw("$baseUrl/$mangaPrefix-$slug.html")
        if (code == 200) return Jsoup.parse(html, "$baseUrl/")
        if (code == 404) {
            runCatching { list("&sort=last_update&sort_type=DESC", 1) }
            val (c2, h2) = Http.getRaw("$baseUrl/$mangaPrefix-$slug.html")
            if (c2 == 200) return Jsoup.parse(h2, "$baseUrl/")
            throw SourceException("Title not found on Jestful")
        }
        throw SourceException("HTTP $code from jestful.net")
    }

    private fun metaRow(doc: Document, label: String): Element? =
        doc.select(".manga-metadata-grid .meta-row").firstOrNull { it.selectFirst("b")?.text()?.trim()?.removeSuffix(":") == label }

    override suspend fun details(ref: MangaRef): MangaDetails {
        val doc = mangaPage(ref.id, fresh = true)
        val title = doc.selectFirst("h1.manga-detail-title")?.text()?.trim().orEmpty().ifEmpty { ref.title }
        val alt = doc.selectFirst(".manga-detail-alt")?.text()?.removePrefix("Alternative:")?.trim().orEmpty()
            .split(Regex(",\\s+|;\\s*")).map { it.trim() }.filter { it.isNotEmpty() && it != title }
        val genres = doc.select(".manga-genres-cloud a.genre-tag-pill").map { it.text().trim() }.filter { it.isNotEmpty() }
        // the bookmark button carries the original cover file; cover.php 400w is the fallback
        val cover = doc.selectFirst("#btn-manga-bookmark")?.attr("data-cover")?.takeIf { it.startsWith("http") }
            ?: bestSrc(doc.selectFirst(".manga-detail-poster img")).ifEmpty { ref.cover }
        val score = doc.selectFirst("#manga-rating-stars")?.attr("data-score")?.toFloatOrNull()
        val d = MangaDetails(
            ref = MangaRef(id, ref.id, title, cover = cover, tags = genres, subtitle = ref.subtitle),
            altTitles = alt,
            description = doc.selectFirst("#synopsis-content")?.text()?.trim().orEmpty(),
            authors = metaRow(doc, "Author")?.select("a")?.map { it.text().trim() }.orEmpty(),
            artists = metaRow(doc, "Artist")?.select("a")?.map { it.text().trim() }.orEmpty(),
            genres = genres,
            status = doc.selectFirst(".badge-status")?.text()?.trim().orEmpty(),
            type = "Raw",
            url = webUrl(ref),
            rating = score?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }.orEmpty(),
            extra = listOfNotNull(metaRow(doc, "Magazine")?.selectFirst("a")?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let { "Magazine" to it }),
        )
        if (Safety.blocked(d)) throw SourceException("This title is hidden")
        return d
    }

    // ---------- chapters ----------

    private fun time(s: String): Long =
        runCatching { LocalDateTime.parse(s.trim(), TIME).toInstant(SITE_TZ).toEpochMilli() }.getOrDefault(0L)

    override suspend fun chapters(ref: MangaRef): List<Chapter> = parseChapters(mangaPage(ref.id))
    override suspend fun latestChapters(ref: MangaRef): List<Chapter> = parseChapters(mangaPage(ref.id, fresh = true))

    private fun parseChapters(doc: Document): List<Chapter> {
        // the whole list is in the details page (no AJAX), newest first
        return doc.select("#chapter-rows-container a.chapter-row").mapNotNull { a ->
            val path = a.attr("href").removePrefix(baseUrl).trimStart('/').removeSuffix(".html")
            if (path.isEmpty()) return@mapNotNull null
            val num = a.attr("data-chapter").toFloatOrNull()
                ?: Regex("chapter-([\\d.]+)$").find(path)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
            Chapter(
                id = path,
                name = a.selectFirst(".chapter-name-text")?.text()?.trim().orEmpty().ifEmpty { "Chapter ${a.attr("data-chapter")}" },
                number = num,
                date = time(a.selectFirst(".chapter-meta-col span[title]")?.attr("title").orEmpty()),
            )
        }
    }

    override suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page> {
        var (code, html) = Http.getRaw("$baseUrl/${chapter.id}.html")
        // the site also serves every chapter under the old "read-" prefix
        if (code == 404) Http.getRaw("$baseUrl/read-${chapter.id.substringAfter('-')}.html").let { code = it.first; html = it.second }
        if (code != 200) throw SourceException("HTTP $code from jestful.net")
        val doc = Jsoup.parse(html, "$baseUrl/")
        val urls = doc.select("img.reader-img").map { img ->
            img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }
        }.filter { it.startsWith("http") }
        if (urls.isEmpty()) throw SourceException("No pages in this chapter")
        return urls.mapIndexed { i, u -> Page(i, u) }
    }

    override suspend fun related(ref: MangaRef, details: MangaDetails): List<MangaRef> {
        val doc = runCatching { mangaPage(ref.id) }.getOrNull() ?: return emptyList()
        return doc.select("a.related-card").mapNotNull { a ->
            val href = a.attr("href")
            val slug = href.removePrefix(baseUrl).trimStart('/').removeSuffix(".html").substringAfter('-').ifEmpty { return@mapNotNull null }
            MangaRef(id, slug, a.attr("title").ifEmpty { a.selectFirst(".related-card-title")?.text().orEmpty() }.trim(),
                cover = bestSrc(a.selectFirst("img")),
                subtitle = a.selectFirst(".related-card-chapter")?.text()?.trim().orEmpty())
        }.filter { it.id != ref.id }
    }

    override fun webUrl(ref: MangaRef) = "$baseUrl/$mangaPrefix-${ref.id}.html"
}
