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
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/** manhwa18.cc: plain HTML pages, every chapter on the title page. Spec: _dev_tools/specs/manhwa18cc.md */
object Manhwa18CcSource : Source {
    override val id = "manhwa18cc"
    override val name = "Manhwa18.cc"
    override val baseUrl = "https://manhwa18.cc"
    override val blurb = "manhwa18.cc · adult manhwa"
    override val color = 0xFF22B3A0
    override val nsfw = true
    /** One genre at a time, no exclusion (the site has no combined filter). */
    override val canExclude = false

    init {
        Http.referers["manhwa18.cc"] = "$baseUrl/"
        // page requests only: covers (manhwa18.cc/manga/...) and pages (img0N.manhwa18.cc) stay fast
        Http.rateLimits["manhwa18.cc/webtoon"] = 400L
        Http.rateLimits["manhwa18.cc/search"] = 400L
    }

    override val selects = listOf(
        SelectDef("sort", "Sort", listOf("" to "Latest update", "trending" to "Trending", "rating" to "Rating", "latest" to "New titles", "alphabet" to "A-Z")),
        // separate feeds on the site; used when there is no text or genre
        SelectDef("list", "Show", listOf("webtoons" to "All", "raw" to "Raw (Korean)", "completed" to "Completed")),
    )

    // ---------- lists ----------

    private fun slugOf(href: String) = href.substringBefore('?').trimEnd('/').substringAfterLast('/')

    private suspend fun list(path: String, sort: String = ""): PageResult {
        val sep = if ('?' in path) "&" else "?"
        val doc = Http.getDoc(if (sort.isNotEmpty()) "$baseUrl$path${sep}orderby=$sort" else baseUrl + path)
        val items = doc.select("div.manga-lists div.manga-item").mapNotNull { el ->
            val a = el.selectFirst("div.thumb a[href]") ?: return@mapNotNull null
            val img = el.selectFirst("div.thumb img")
            MangaRef(
                source = id, id = slugOf(a.attr("href")),
                title = a.attr("title").ifEmpty { el.selectFirst("h3 a")?.text().orEmpty() }.trim(),
                cover = img?.attr("abs:data-src")?.ifEmpty { null } ?: img?.attr("abs:src").orEmpty(),
                subtitle = el.selectFirst("div.chapter-item a")?.text()?.trim().orEmpty(),
            )
        }
        // past the last page the site repeats the last page, so trust only the "next" arrow
        return PageResult(items, doc.selectFirst("ul.pagination li.next a") != null)
    }

    override suspend fun popular(page: Int) = list("/webtoons/$page", "trending")
    override suspend fun latest(page: Int) = list("/webtoons/$page")

    override suspend fun search(query: SearchQuery, page: Int): PageResult {
        val sort = query.selects["sort"].orEmpty()
        val text = query.text.trim()
        if (query.artist.isNotBlank() && text.isEmpty() && query.include.isEmpty()) {
            // /author/<slug> and /artist/<slug> list every work on one page
            if (page > 1) return PageResult(emptyList(), false)
            val path = people[query.artist.lowercase()] ?: ("artist/" + slugify(query.artist))
            val r = runCatching { list("/$path") }.getOrNull()
            if (r != null && r.items.isNotEmpty()) return r.copy(hasNext = false)
        }
        val words = listOf(text, query.artist.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        if (words.isNotEmpty()) return list("/search?q=${words.urlEncode()}&page=$page")
        if (query.include.isNotEmpty() && tagCache == null) runCatching { tags() }
        val genre = query.include.firstOrNull()?.let { genreSlug(it) }
        if (genre != null) return list("/webtoon-genre/$genre/$page", sort)
        val feed = query.selects["list"].orEmpty().ifEmpty { "webtoons" }
        return list("/$feed/$page", if (feed == "webtoons") sort else "")
    }

    private fun slugify(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    // ---------- tags ----------

    @Volatile private var tagCache: List<Tag>? = null

    override suspend fun tags(): List<Tag> {
        tagCache?.let { return it }
        val doc = Http.getDoc("$baseUrl/webtoons")
        val list = doc.select("a[href*='/webtoon-genre/']").map { Tag(slugOf(it.attr("href")), it.text().trim(), "Genre") }
            .filter { it.id.isNotEmpty() && it.label.isNotEmpty() }.distinctBy { it.id }.sortedBy { it.label.lowercase() }
        return Safety.cleanTags(list).also { if (it.isNotEmpty()) tagCache = it }
    }

    /** Picker tags carry the slug; a genre tapped on a details page carries its name. */
    private fun genreSlug(t: Tag): String =
        tagCache?.firstOrNull { it.id == t.id || it.label.equals(t.label, true) }?.id ?: slugify(t.id)

    // ---------- details ----------

    /** author / artist name (lower case) -> "author/<slug>" or "artist/<slug>", learnt from details pages */
    private val people = ConcurrentHashMap<String, String>()
    private val docs = ConcurrentHashMap<String, Pair<Long, Document>>()

    private suspend fun titlePage(slug: String, fresh: Boolean = false): Document {
        val hit = docs[slug]
        if (!fresh && hit != null && System.currentTimeMillis() - hit.first < 60_000) return hit.second
        val doc = Http.getDoc("$baseUrl/webtoon/$slug")
        if (doc.selectFirst("div.post-title") == null) throw SourceException("Title not found on manhwa18.cc")
        docs[slug] = System.currentTimeMillis() to doc
        return doc
    }

    override suspend fun details(ref: MangaRef): MangaDetails {
        val doc = titlePage(ref.id, fresh = true)
        val info = doc.select("div.post-content_item").associate { item ->
            item.selectFirst("div.summary-heading")?.text().orEmpty().trim().removeSuffix(":").lowercase() to item.selectFirst("div.summary-content")
        }
        fun links(key: String): List<String> = info[key]?.select("a")?.map { a ->
            a.text().trim().also { name ->
                val path = a.attr("abs:href").substringAfter(baseUrl).trim('/')
                if (path.startsWith("author/") || path.startsWith("artist/")) people[name.lowercase()] = path
            }
        }?.filter { it.isNotEmpty() }.orEmpty()
        fun text(key: String): String = info[key]?.text()?.trim().orEmpty()

        val h1 = doc.selectFirst("div.post-title h1")?.clone()?.apply { select("span").remove() }?.text()?.trim().orEmpty()
        val title = h1.ifEmpty { ref.title }
        val genres = links("genre(s)")
        val desc = doc.select("div.panel-story-description div.dsct p").map { it.text().trim() }.filter { it.isNotEmpty() }
        val d = MangaDetails(
            ref = MangaRef(id, ref.id, title,
                cover = doc.selectFirst("meta[property='og:image']")?.attr("content")?.ifEmpty { null } ?: ref.cover,
                tags = genres, subtitle = ref.subtitle),
            altTitles = text("alternative").split(Regex("\\s*[/;]\\s*")).map { it.trim() }.filter { it.isNotEmpty() && !it.equals(title, true) },
            description = desc.joinToString("\n\n").ifEmpty { doc.selectFirst("div.panel-story-description div.dsct")?.text().orEmpty() },
            authors = links("author(s)"),
            artists = links("artist(s)"),
            genres = genres,
            status = text("status").let { if (it.equals("OnGoing", true)) "Ongoing" else it },
            type = text("type"),
            url = webUrl(ref),
            rating = doc.selectFirst("span.avgrate")?.text()?.trim()?.toFloatOrNull()?.takeIf { it > 0 }?.let { "★ %.2f".format(it) }.orEmpty(),
            extra = listOfNotNull(text("release").takeIf { it.isNotEmpty() }?.let { "Release" to it }),
        )
        if (Safety.blocked(d)) throw SourceException("This title is hidden")
        return d
    }

    override suspend fun chapters(ref: MangaRef): List<Chapter> {
        val now = System.currentTimeMillis()
        return titlePage(ref.id).select("ul.row-content-chapter li").mapNotNull { li ->
            val a = li.selectFirst("a.chapter-name") ?: return@mapNotNull null
            val name = a.text().trim()
            Chapter(
                id = slugOf(a.attr("href")),
                name = name,
                number = chapterNumber(name) ?: -1f,
                // the newest chapters show a "new" picture instead of a date
                date = li.selectFirst("span.chapter-time")?.text()?.let { parseDate(it) } ?: now,
            )
        }
    }

    override suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page> {
        val doc = Http.getDoc("$baseUrl/webtoon/${ref.id}/${chapter.id}")
        val urls = doc.select("div.read-content img").map { imgUrl(it) }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) throw SourceException("No pages in this chapter")
        return urls.mapIndexed { i, u -> Page(i, u) }
    }

    private fun imgUrl(img: Element): String = img.attr("abs:data-src").ifEmpty { img.attr("abs:src") }.trim()

    override fun webUrl(ref: MangaRef) = "$baseUrl/webtoon/${ref.id}"

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /** "16 Oct 2026" -> epoch ms (0 when it does not parse). */
    private fun parseDate(s: String): Long {
        val p = s.trim().split(Regex("\\s+"))
        if (p.size < 3) return 0L
        val m = MONTHS.indexOf(p[1].take(3).lowercase()) + 1
        val d = p[0].toIntOrNull() ?: return 0L
        val y = p[2].toIntOrNull() ?: return 0L
        if (m == 0) return 0L
        return runCatching { LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrDefault(0L)
    }

    private fun chapterNumber(name: String): Float? =
        (Regex("(?i)(?:chapter|chap|ch|ep|episode)\\.?\\s*(\\d+(?:\\.\\d+)?)").find(name) ?: Regex("(\\d+(?:\\.\\d+)?)").find(name))
            ?.groupValues?.get(1)?.toFloatOrNull()
}
