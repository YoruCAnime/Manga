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
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * manga18.club: plain HTML; a chapter's page list is a base64-encoded array in an inline script (`slides_p_path`).
 * Spec: _dev_tools/specs/manga18club.md
 */
object Manga18ClubSource : Source {
    override val id = "manga18club"
    override val name = "Manga18.club"
    override val baseUrl = "https://manga18.club"
    override val blurb = "manga18.club · adult manhwa & manga"
    override val color = 0xFFF2683A
    override val nsfw = true
    /** One genre at a time, no exclusion (the site has no combined filter). */
    override val canExclude = false

    init {
        Http.referers["manga18.club"] = "$baseUrl/"
        // page requests only: the picture host cdn.manga18.club stays fast
        Http.rateLimits["manga18.club/list-manga"] = 400L
        Http.rateLimits["manga18.club/manga-list"] = 400L
        Http.rateLimits["manga18.club/manhwa"] = 400L
    }

    override val selects = listOf(
        SelectDef("sort", "Sort", listOf("lastest" to "Latest update", "views" to "Most views", "name" to "A-Z")),
    )

    // ---------- lists ----------

    private fun slugOf(href: String) = href.substringBefore('?').trimEnd('/').substringAfterLast('/')

    private suspend fun list(url: String): PageResult {
        val doc = Http.getDoc(url)
        val items = doc.select("div.recoment_box div.story_item").mapNotNull { el ->
            val a = el.selectFirst("div.story_images a[href]") ?: return@mapNotNull null
            val img = el.selectFirst("div.story_images img")
            MangaRef(
                source = id, id = slugOf(a.attr("href")),
                title = a.attr("title").ifEmpty { el.selectFirst("div.mg_name a")?.text().orEmpty() }.trim(),
                cover = img?.attr("abs:data-src")?.ifEmpty { null } ?: img?.attr("abs:src").orEmpty(),
                subtitle = el.selectFirst("div.chapter_count a")?.text()?.trim().orEmpty(),
            )
        }
        // the "›" arrow is there while a next page exists (past the end the list is simply empty)
        val next = doc.select("div.section_pagination ul.pagination li a").any { it.text().trim() == "›" }
        return PageResult(items, next && items.isNotEmpty())
    }

    // page links drop order_by, so the URLs are always built here
    override suspend fun popular(page: Int) = list("$baseUrl/list-manga/$page?order_by=views")
    override suspend fun latest(page: Int) = list("$baseUrl/list-manga/$page?order_by=lastest")

    override suspend fun search(query: SearchQuery, page: Int): PageResult {
        val sort = query.selects["sort"].orEmpty().ifEmpty { "lastest" }
        val text = query.text.trim()
        if (query.artist.isNotBlank() && text.isEmpty() && query.include.isEmpty()) {
            val r = list("$baseUrl/list-manga?artist=${query.artist.trim().urlEncode()}&page=$page&order_by=$sort")
            if (r.items.isNotEmpty() || page > 1) return r
            return list("$baseUrl/list-manga?author=${query.artist.trim().urlEncode()}&page=$page&order_by=$sort")
        }
        val words = listOf(text, query.artist.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        if (words.isNotEmpty()) return list("$baseUrl/list-manga?search=${words.urlEncode()}&page=$page&order_by=$sort")
        if (query.include.isNotEmpty() && tagCache == null) runCatching { tags() }
        val genre = query.include.firstOrNull()?.let { t -> tagCache?.firstOrNull { it.id == t.id || it.label.equals(t.label, true) }?.id ?: t.id }
        if (genre != null) return list("$baseUrl/manga-list/$genre/$page?order_by=$sort")
        return list("$baseUrl/list-manga/$page?order_by=$sort")
    }

    // ---------- tags ----------

    @Volatile private var tagCache: List<Tag>? = null

    /** The genre menu that every page carries (slugs keep the site's own capitals, e.g. "Manhwa"). */
    override suspend fun tags(): List<Tag> {
        tagCache?.let { return it }
        val doc = Http.getDoc("$baseUrl/list-manga")
        val list = doc.select("a[href*='/manga-list/']").map { Tag(slugOf(it.attr("href")), it.text().trim(), "Genre") }
            .filter { it.id.isNotEmpty() && it.label.isNotEmpty() }.distinctBy { it.id.lowercase() }.sortedBy { it.label.lowercase() }
        return Safety.cleanTags(list).also { if (it.isNotEmpty()) tagCache = it }
    }

    // ---------- details ----------

    private val docs = ConcurrentHashMap<String, Pair<Long, Document>>()

    private suspend fun titlePage(slug: String, fresh: Boolean = false): Document {
        val hit = docs[slug]
        if (!fresh && hit != null && System.currentTimeMillis() - hit.first < 60_000) return hit.second
        val doc = Http.getDoc("$baseUrl/manhwa/$slug")
        if (doc.selectFirst("div.detail_name") == null) throw SourceException("Title not found on manga18.club")
        docs[slug] = System.currentTimeMillis() to doc
        return doc
    }

    override suspend fun details(ref: MangaRef): MangaDetails {
        val doc = titlePage(ref.id, fresh = true)
        val info = doc.select("div.detail_listInfo div.item").associate { item ->
            item.selectFirst("div.info_label")?.text().orEmpty().trim().lowercase() to item.selectFirst("div.info_value")
        }
        fun links(key: String) = info[key]?.select("a")?.map { it.text().trim() }?.filter { it.isNotEmpty() }.orEmpty()
        fun text(key: String) = info[key]?.text()?.trim().orEmpty()

        val title = doc.selectFirst("div.detail_name h1")?.text()?.trim().orEmpty().ifEmpty { ref.title }
        val genres = links("categories")
        val d = MangaDetails(
            ref = MangaRef(id, ref.id, title, cover = doc.selectFirst("div.detail_avatar img")?.attr("abs:src")?.ifEmpty { null } ?: ref.cover,
                tags = genres, subtitle = ref.subtitle),
            altTitles = text("other name").split(Regex("\\s*[,;/]\\s*")).map { it.trim() }.filter { it.isNotEmpty() && !it.equals(title, true) },
            description = doc.selectFirst("div.detail_reviewContent")?.text()?.trim().orEmpty(),
            authors = links("author"),
            artists = links("artist"),
            genres = genres,
            status = text("status").let { if (it.equals("On Going", true)) "Ongoing" else it },
            type = genres.firstOrNull { it.equals("Manhwa", true) || it.equals("Manga", true) || it.equals("Manhua", true) }.orEmpty(),
            url = webUrl(ref),
            rating = doc.selectFirst("#mgRatingText")?.text()?.trim()?.substringBefore('/')?.toFloatOrNull()?.takeIf { it > 0 }?.let { "★ %.2f".format(it) }.orEmpty(),
            extra = listOfNotNull(text("views").takeIf { it.isNotEmpty() }?.let { "Views" to it }),
        )
        if (Safety.blocked(d)) throw SourceException("This title is hidden")
        return d
    }

    override suspend fun chapters(ref: MangaRef): List<Chapter> =
        titlePage(ref.id).select("div.chapter_box li").mapNotNull { li ->
            val a = li.selectFirst("a.chapter_num") ?: return@mapNotNull null
            val name = a.text().trim()
            Chapter(
                id = slugOf(a.attr("href")),
                name = name,
                number = chapterNumber(name) ?: -1f,
                date = parseDate(li.selectFirst("p.chapter_info")?.text().orEmpty()),
            )
        }

    override suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page> {
        val html = Http.get("$baseUrl/manhwa/${ref.id}/${chapter.id}")
        val arr = Regex("slides_p_path\\s*=\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: throw SourceException("No pages in this chapter")
        val urls = Regex("\"([^\"]+)\"").findAll(arr).mapNotNull { m ->
            runCatching { String(Base64.getDecoder().decode(m.groupValues[1].trim()), Charsets.UTF_8).trim() }.getOrNull()
        }.map { if (it.startsWith("//")) "https:$it" else it }.filter { it.startsWith("http") }.toList()
        if (urls.isEmpty()) throw SourceException("No pages in this chapter")
        return urls.mapIndexed { i, u -> Page(i, u) }
    }

    override fun webUrl(ref: MangaRef) = "$baseUrl/manhwa/${ref.id}"

    /** "30-10-2026" (day-month-year) -> epoch ms (0 when it does not parse). */
    private fun parseDate(s: String): Long {
        val p = s.trim().split('-', '/', '.')
        if (p.size != 3) return 0L
        val (d, m, y) = p.map { it.trim().toIntOrNull() ?: return 0L }
        return runCatching { LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrDefault(0L)
    }

    private fun chapterNumber(name: String): Float? =
        (Regex("(?i)(?:chapter|chap|ch|ep|episode)\\.?\\s*(\\d+(?:\\.\\d+)?)").find(name) ?: Regex("(\\d+(?:\\.\\d+)?)").find(name))
            ?.groupValues?.get(1)?.toFloatOrNull()
}
