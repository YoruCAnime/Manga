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
import com.yoruc.mangacracker.core.arr
import com.yoruc.mangacracker.core.get
import com.yoruc.mangacracker.core.text
import com.yoruc.mangacracker.core.urlEncode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** hentai20.io: WordPress "MangaReader" (Themesia) theme, 18+ manhwa / manga. Spec: _dev_tools/specs/hentai20.md */
object Hentai20Source : Source {
    override val id = "hentai20"
    override val name = "Hentai20"
    override val baseUrl = "https://hentai20.io"
    override val blurb = "hentai20.io · 18+ manhwa & manga"
    override val color = 0xFFE0457B
    override val nsfw = true

    private val DATE = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)

    init {
        // pages only (covers sit on hentai20.io/wp-content and must not queue behind them)
        Http.rateLimits["hentai20.io/manga/"] = 500L
        Http.rateLimits["hentai20.io/page/"] = 500L
    }

    // The site sets no status / type on its titles (every filter by them comes back empty), so only the order is offered.
    override val selects = listOf(
        SelectDef("order", "Sort", listOf(
            "popular" to "Popular", "update" to "Latest update", "latest" to "Recently added", "title" to "A-Z", "titlereverse" to "Z-A",
        )),
    )

    // ---------- lists ----------

    /** Covers are plain src today; lazy-load attributes are read first in case the theme turns them on. */
    private fun img(e: Element?): String =
        if (e == null) "" else listOf("abs:data-src", "abs:data-lazy-src", "abs:src").map { e.attr(it) }.firstOrNull { it.startsWith("http") }.orEmpty()

    private fun parseList(doc: Document): PageResult {
        val items = doc.select(".listupd .bs .bsx > a").mapNotNull { a ->
            val slug = a.attr("href").trimEnd('/').substringAfterLast('/').ifEmpty { return@mapNotNull null }
            val title = a.attr("title").ifEmpty { a.selectFirst(".tt")?.text().orEmpty() }.trim()
            MangaRef(id, slug, title, cover = img(a.selectFirst("img")), subtitle = a.selectFirst(".epxs")?.text()?.trim().orEmpty())
        }.distinctBy { it.id }
        val next = doc.selectFirst(".pagination a.next, .hpage a.r") != null
        return PageResult(items, next)
    }

    private suspend fun list(params: String, page: Int): PageResult =
        parseList(Http.getDoc("$baseUrl/manga/?page=$page$params"))

    override suspend fun popular(page: Int) = list("&order=popular", page)
    override suspend fun latest(page: Int) = list("&order=update", page)

    override suspend fun search(query: SearchQuery, page: Int): PageResult {
        // text search (WordPress ?s=) cannot be combined with the genre filter; the site lists no authors, so a tapped
        // name is searched as text
        val text = listOf(query.text, query.artist).filter { it.isNotBlank() }.joinToString(" ").trim()
        if (text.isNotEmpty()) return parseList(Http.getDoc("$baseUrl/page/$page/?s=${text.urlEncode()}"))
        val p = StringBuilder()
        // several genres = all of them (AND); a minus id excludes it (both checked live)
        query.include.forEach { p.append("&genre%5B%5D=").append(it.id) }
        query.exclude.forEach { p.append("&genre%5B%5D=-").append(it.id) }
        p.append("&order=").append(query.selects["order"] ?: "popular")
        return list(p.toString(), page)
    }

    // ---------- tags ----------

    /** The filter form's genres (term id, name), 2026-11. None of them is minor-related. */
    private val GENRES = listOf(
        "16" to "Action", "2274" to "Adult", "2232" to "Adventure", "3" to "Comedy", "3594" to "Comics", "4647" to "Doujinshi",
        "4" to "Drama", "2285" to "Ecchi", "5" to "Fantasy", "3423" to "Gender Bender", "2297" to "GL", "6" to "Harem",
        "3351" to "Hentai", "3348" to "Historical", "3349" to "Horror", "2891" to "Isekai", "2250" to "Josei", "4694" to "Kimono",
        "4973" to "Magic", "20" to "Manga Hentai", "3346" to "Manhwa", "21" to "Manhwa Hentai", "2246" to "Martial Arts",
        "12" to "Mature", "2236" to "Mystery", "2719" to "Psychological", "3735" to "Raw", "7" to "Romance", "146" to "School life",
        "3462" to "Sci-Fi", "3345" to "Seinen", "3438" to "Shoujo", "3352" to "Shounen", "9" to "Slice of Life", "14" to "Smut",
        "3350" to "Sports", "1673" to "Supernatural", "3353" to "Thriller", "3356" to "Tragedy", "4761" to "Uncensored", "2724" to "Yuri",
    ).map { Tag(it.first, it.second, "Genre") }

    @Volatile private var tagCache: List<Tag>? = null

    override suspend fun tags(): List<Tag> {
        tagCache?.let { return it }
        val live = runCatching {
            val doc = Http.getDoc("$baseUrl/manga/")
            doc.select(".quickfilter input[name^=genre]").mapNotNull { i ->
                val label = doc.selectFirst("label[for=${i.id()}]")?.text()?.trim().orEmpty()
                if (i.id().isEmpty() || label.isEmpty() || i.attr("value").toIntOrNull() == null) null else Tag(i.attr("value"), label, "Genre")
            }
        }.getOrDefault(emptyList())
        return Safety.cleanTags(live.ifEmpty { GENRES }).also { tagCache = it }
    }

    override fun tagQuery(name: String): SearchQuery {
        val t = (tagCache ?: GENRES).firstOrNull { it.label.equals(name, true) } ?: return SearchQuery(text = name)
        return SearchQuery(include = listOf(t))
    }

    // ---------- details ----------

    /** slug -> (fetched at, page): details and chapters share one download for a minute. */
    private val docs = ConcurrentHashMap<String, Pair<Long, Document>>()

    private suspend fun mangaPage(slug: String, fresh: Boolean = false): Document {
        val hit = docs[slug]
        if (!fresh && hit != null && System.currentTimeMillis() - hit.first < 60_000) return hit.second
        return Http.getDoc("$baseUrl/manga/$slug/").also { docs[slug] = System.currentTimeMillis() to it }
    }

    private fun info(doc: Document, label: String): String =
        doc.select("table.infotable tr").firstOrNull { it.selectFirst("td")?.text()?.trim().equals(label, true) }
            ?.select("td")?.lastOrNull()?.text()?.trim().orEmpty()

    private fun names(s: String) = s.split(Regex(",\\s*")).map { it.trim() }.filter { it.isNotEmpty() && it != "-" && it != "?" }

    override suspend fun details(ref: MangaRef): MangaDetails {
        val doc = mangaPage(ref.id, fresh = true)
        val title = doc.selectFirst(".seriestuheader h1.entry-title, h1.entry-title")?.text()?.trim().orEmpty().ifEmpty { ref.title }
        if (doc.selectFirst("#chapterlist, .seriestucon") == null) throw SourceException("Title not found on Hentai20")
        val genres = doc.select(".seriestugenre a, .mgen a").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        // these rows are empty on most titles today; read them when the site fills them
        val alt = doc.selectFirst(".seriestualt")?.text()?.trim().orEmpty().split(Regex("\\s*[,;|]\\s+")).filter { it.isNotBlank() && it != title }
        val rating = doc.selectFirst(".rating .num[itemprop=ratingValue], .rating .num")?.text()?.trim()?.toFloatOrNull()
        val d = MangaDetails(
            ref = MangaRef(id, ref.id, title, cover = img(doc.selectFirst(".seriestucontl .thumb img")).ifEmpty { ref.cover },
                tags = genres, subtitle = ref.subtitle),
            altTitles = alt,
            description = doc.selectFirst(".seriestuhead .entry-content, .entry-content[itemprop=description]")?.text()?.trim().orEmpty(),
            authors = names(info(doc, "Author")),
            artists = names(info(doc, "Artist")),
            genres = genres,
            status = info(doc, "Status"),
            type = info(doc, "Type"),
            url = webUrl(ref),
            rating = rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }.orEmpty(),
        )
        if (Safety.blocked(d)) throw SourceException("This title is hidden")
        return d
    }

    // ---------- chapters ----------

    private fun date(s: String): Long =
        runCatching { LocalDate.parse(s.trim(), DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrDefault(0L)

    override suspend fun chapters(ref: MangaRef): List<Chapter> = parseChapters(mangaPage(ref.id))
    override suspend fun latestChapters(ref: MangaRef): List<Chapter> = parseChapters(mangaPage(ref.id, fresh = true))

    private fun parseChapters(doc: Document): List<Chapter> =
        // every chapter is in the page (no AJAX), newest first
        doc.select("#chapterlist li").mapNotNull { li ->
            val a = li.selectFirst("a[href]") ?: return@mapNotNull null
            val slug = a.attr("href").trimEnd('/').substringAfterLast('/').ifEmpty { return@mapNotNull null }
            val name = li.selectFirst(".chapternum")?.text()?.trim().orEmpty()
            Chapter(
                id = slug,
                name = name.ifEmpty { "Chapter ${li.attr("data-num")}" },
                number = li.attr("data-num").toFloatOrNull()
                    ?: Regex("(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f,
                date = date(li.selectFirst(".chapterdate")?.text().orEmpty()),
            )
        }.distinctBy { it.id }

    override suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page> {
        val html = Http.get("$baseUrl/${chapter.id}/")
        // the reader's own JSON: ts_reader.run({..."sources":[{"source":"Server 1","images":[...]}]})
        val json = Regex("""ts_reader\.run\((\{.*?\})\);""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
        var urls = json?.let { j ->
            runCatching { Http.json.parseToJsonElement(j)["sources"].arr.firstOrNull()?.get("images").arr.map { it.text } }.getOrNull()
        }.orEmpty().filter { it.startsWith("http") }
        if (urls.isEmpty()) {
            val doc = org.jsoup.Jsoup.parse(html, "$baseUrl/")
            urls = doc.select("#readerarea img").map { img(it) }.filter { it.isNotEmpty() }
        }
        if (urls.isEmpty()) throw SourceException("No pages in this chapter")
        return urls.mapIndexed { i, u -> Page(i, u) }
    }

    override fun webUrl(ref: MangaRef) = "$baseUrl/manga/${ref.id}/"
}
