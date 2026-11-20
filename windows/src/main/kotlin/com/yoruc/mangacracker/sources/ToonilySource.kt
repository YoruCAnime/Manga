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
import com.yoruc.mangacracker.core.TagGroup
import com.yoruc.mangacracker.core.WebCookieJar
import com.yoruc.mangacracker.core.urlEncode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * toonily.com: WordPress with its own Madara-based theme, manhwa in English (all ages and 18+). Every chapter is on the
 * title page. The site keeps its 18+ titles out of the lists behind a "Family Mode" switch (cookie `toonily-mature`); the
 * app asks once ([ageNotice]) and then reads with the switch off. Spec: _dev_tools/specs/toonily.md
 */
object ToonilySource : Source {
    override val id = "toonily"
    override val name = "Toonily"
    override val baseUrl = "https://toonily.com"
    override val blurb = "toonily.com · manhwa & 18+ webtoons"
    override val color = 0xFFE6B17E
    override val nsfw = true
    /** The search form only adds genres (all / any of them), it cannot leave one out. */
    override val canExclude = false
    override val ageNotice = "Toonily keeps its 18+ titles behind a Family Mode switch. This source reads with Family Mode off, " +
        "so it is for adults aged 18 and over."

    private val DATE = DateTimeFormatter.ofPattern("MMM d, yy", Locale.ENGLISH)

    init {
        // chapter pictures (data.tnlycdn.com) answer 403 without the site as Referer; covers (static.tnlycdn.com) take it too
        Http.referers["tnlycdn.com"] = "$baseUrl/"
        // pages only: every picture is on tnlycdn.com
        Http.rateLimits["toonily.com"] = 500L
    }

    override val selects = listOf(
        SelectDef("sort", "Sort", listOf(
            "views" to "Most views", "latest" to "Latest update", "trending" to "Trending", "rating" to "Rating",
            "new-manga" to "New titles", "alphabet" to "A-Z", "" to "Best match",
        )),
        SelectDef("status", "Status", listOf("" to "Any", "on-going" to "Ongoing", "end" to "Completed", "on-hold" to "Hiatus", "canceled" to "Cancelled")),
        // several genres: all of them, or any of them (the form's "Match" switch)
        SelectDef("match", "Genres", listOf("all" to "All chosen", "any" to "Any chosen")),
    )

    // ---------- lists ----------

    /** Family Mode off (what the site's own switch stores), so lists carry the 18+ titles too. */
    private fun mature() {
        if (WebCookieJar.get(baseUrl, "toonily-mature") != "1") WebCookieJar.set(baseUrl, "toonily-mature", "1", "toonily.com")
    }

    private suspend fun fetch(path: String): Document {
        mature()
        return Http.getDoc(baseUrl + path)
    }

    private fun slugOf(href: String) = href.substringBefore('?').trimEnd('/').substringAfterLast('/')

    /** Grid covers are 175x238 with a 350x476 twin in srcset on most titles: take the bigger one when it is there. */
    private fun img(e: Element?): String {
        if (e == null) return ""
        val big = e.attr("srcset").split(',').map { it.trim().substringBefore(' ') }.lastOrNull { it.startsWith("http") }
        return big ?: listOf("abs:data-src", "abs:src").map { e.attr(it).trim() }.firstOrNull { it.startsWith("http") }.orEmpty()
    }

    private fun parseList(doc: Document): PageResult {
        val items = doc.select("div.page-item-detail").mapNotNull { el ->
            val a = el.selectFirst("div.item-thumb a[href*='/serie/']") ?: return@mapNotNull null
            val slug = slugOf(a.attr("href")).ifEmpty { return@mapNotNull null }
            MangaRef(
                source = id, id = slug,
                title = a.attr("title").ifEmpty { el.selectFirst("div.post-title a")?.text().orEmpty() }.trim(),
                cover = img(a.selectFirst("img")),
                // the search pages list no chapters
                subtitle = el.selectFirst("div.list-chapter div.chapter-item a")?.text()?.trim().orEmpty(),
            )
        }.distinctBy { it.id }
        return PageResult(items, doc.selectFirst("a.nextpostslink") != null)
    }

    private fun paged(path: String, page: Int) = if (page > 1) "$path/page/$page/" else "$path/"

    override suspend fun popular(page: Int) = parseList(fetch(paged("/webtoons", page) + "?m_orderby=views"))
    override suspend fun latest(page: Int) = parseList(fetch(paged("/webtoons", page) + "?m_orderby=latest"))

    // ---------- search ----------

    /** The site's search address: words in lower case joined by dashes ("don't look" -> /search/don-t-look/). */
    private fun searchSlug(text: String) =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString("-") { it.urlEncode() }

    private fun slugify(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    /**
     * "artist/<slug>" or "writer/<slug>" for a name: known from a details page, else tried on the site (an unknown
     * name is a 404). The search form's own artist / writer fields are no help for a guess: a name they do not know is
     * ignored and every title comes back.
     */
    private suspend fun personPath(name: String): String? {
        val key = name.trim().lowercase()
        people[key]?.let { return it }
        val slug = slugify(name).ifEmpty { return null }
        for (path in listOf("artist/$slug", "writer/$slug")) {
            val found = runCatching { parseList(fetch("/$path/")).items.isNotEmpty() }.getOrDefault(false)
            if (found) { people[key] = path; return path }
        }
        return null
    }

    override suspend fun search(query: SearchQuery, page: Int): PageResult {
        val person = if (query.artist.isNotBlank()) personPath(query.artist) else null
        // a name the site has no page for is searched as words
        val text = listOf(query.text.trim(), if (person == null) query.artist.trim() else "").filter { it.isNotEmpty() }.joinToString(" ")
        val sort = query.selects["sort"] ?: if (text.isEmpty()) "views" else ""
        val status = query.selects["status"].orEmpty()
        val order = if (sort.isNotEmpty()) "?m_orderby=$sort" else ""

        if (query.include.isNotEmpty() && tagCache == null) runCatching { tags() }
        val inc = query.include.map { resolve(it) }
        val genres = inc.filter { !it.startsWith("tag:") }
        if (text.isEmpty() && genres.isEmpty() && status.isEmpty()) {
            // a name tapped on a details page: the writer's / artist's own list
            if (person != null) return parseList(fetch(paged("/$person", page) + order))
            // a tag page (tags are free words, not part of the search form)
            val tag = inc.firstOrNull { it.startsWith("tag:") }
            if (tag != null) return parseList(fetch(paged("/tag/${tag.removePrefix("tag:")}", page) + order))
            return parseList(fetch(paged("/webtoons", page) + order.ifEmpty { "?m_orderby=views" }))
        }

        val p = StringBuilder()
        genres.forEach { p.append("&genre%5B%5D=").append(it.urlEncode()) }
        if (genres.size > 1 && (query.selects["match"] ?: "all") == "all") p.append("&op=1")
        if (status.isNotEmpty()) p.append("&status%5B%5D=").append(status)
        if (person != null) p.append(if (person.startsWith("artist/")) "&artist=" else "&author=").append(query.artist.trim().urlEncode())
        if (sort.isNotEmpty()) p.append("&m_orderby=").append(sort)
        val path = searchSlug(text).let { if (it.isEmpty()) "/search" else "/search/$it" }
        val q = p.toString().removePrefix("&")
        return parseList(fetch(paged(path, page) + if (q.isEmpty()) "" else "?$q"))
    }

    // ---------- tags ----------

    /** The search form's genres (slug, name), 2026-11. None of them is minor-related. */
    private val GENRES = listOf(
        "action" to "Action", "adventure" to "Adventure", "comedy" to "Comedy", "crime" to "Crime", "drama" to "Drama",
        "fantasy" to "Fantasy", "gossip" to "Gossip", "historical" to "Historical", "horror" to "Horror", "isekai" to "Isekai",
        "josei" to "Josei", "magic" to "Magic", "mature" to "Mature", "mystery" to "Mystery", "psychological" to "Psychological",
        "romance" to "Romance", "school-life" to "School Life", "scifi-webtoon" to "Sci-Fi", "seinen" to "Seinen", "shoujo" to "Shoujo",
        "shounen" to "Shounen", "slice-of-life" to "Slice of Life", "sports" to "Sports", "supernatural" to "Supernatural",
        "thriller" to "Thriller", "tragedy" to "Tragedy", "villainess" to "Villainess", "wuxia" to "Wuxia", "yaoi" to "Yaoi", "yuri" to "Yuri",
    ).map { Tag(it.first, it.second, "Genre") }

    @Volatile private var tagCache: List<Tag>? = null
    /** tag name (lower case) -> slug of its /tag/<slug>/ page, learnt from details pages */
    private val tagSlugs = ConcurrentHashMap<String, String>()

    override suspend fun tags(): List<Tag> {
        tagCache?.let { return it }
        val live = runCatching {
            // "Action <span class=count>(171)</span>": the name is the link's own text
            fetch("/webtoons/").select("a[href*='/genre/']").map { Tag(slugOf(it.attr("href")), it.ownText().trim(), "Genre") }
                .filter { it.id.isNotEmpty() && it.label.isNotEmpty() }.distinctBy { it.id }.sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
        return Safety.cleanTags(live.ifEmpty { GENRES }).also { tagCache = it }
    }

    override fun tagQuery(name: String): SearchQuery {
        (tagCache ?: GENRES).firstOrNull { it.label.equals(name, true) }?.let { return SearchQuery(include = listOf(it)) }
        tagSlugs[name.lowercase()]?.let { return SearchQuery(include = listOf(Tag("tag:$it", name))) }
        return SearchQuery(text = name)
    }

    /** A genre from the picker (slug), a tag from tagQuery ("tag:<slug>"), or just a name: -> genre slug or "tag:<slug>". */
    private fun resolve(t: Tag): String {
        if (t.id.startsWith("tag:")) return t.id
        (tagCache ?: GENRES).firstOrNull { it.id == t.id || it.label.equals(t.label, true) }?.let { return it.id }
        return tagSlugs[t.label.lowercase()]?.let { "tag:$it" } ?: slugify(t.id)
    }

    // ---------- details ----------

    /** writer / artist name (lower case) -> "writer/<slug>" or "artist/<slug>", learnt from details pages */
    private val people = ConcurrentHashMap<String, String>()
    /** slug -> (fetched at, page): details, chapters and related share one download for a minute. */
    private val docs = ConcurrentHashMap<String, Pair<Long, Document>>()

    private suspend fun titlePage(slug: String, fresh: Boolean = false): Document {
        val hit = docs[slug]
        if (!fresh && hit != null && System.currentTimeMillis() - hit.first < 60_000) return hit.second
        val doc = fetch("/serie/$slug/")
        if (doc.selectFirst("div.post-title h1") == null) throw SourceException("Title not found on Toonily")
        if (docs.size > 40) docs.clear()
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
                if (path.startsWith("writer/") || path.startsWith("artist/")) people[name.lowercase()] = path
            }
        }?.filter { it.isNotEmpty() }.orEmpty()
        fun text(key: String): String = info[key]?.text()?.trim().orEmpty()

        val h1 = doc.selectFirst("div.post-title h1")?.clone()?.apply { select("span").remove() }?.text()?.trim().orEmpty()
        val title = h1.ifEmpty { ref.title }
        val genres = links("genre(s)")
        val tags = doc.select("div.wp-manga-tags-list a").map { a ->
            a.text().trim().removePrefix("#").trim().also { tagSlugs[it.lowercase()] = slugOf(a.attr("href")) }
        }.filter { it.isNotEmpty() }
        // the story comes first; after it the site adds stock paragraphs ("<title> ... is an ongoing Korean romance manhwa ...")
        val story = doc.select("div.summary__content p").takeWhile { p -> p.selectFirst("strong")?.text()?.trim()?.equals(title, true) != true }
            .map { it.text().trim() }.filter { it.isNotEmpty() }
        val views = doc.select("div.manga-rate-view-comment div.item").firstOrNull { it.selectFirst("i.ion-md-eye") != null }?.text()?.trim().orEmpty()
        val d = MangaDetails(
            ref = MangaRef(id, ref.id, title, cover = img(doc.selectFirst("div.summary_image img")).ifEmpty { ref.cover },
                tags = genres + tags, subtitle = ref.subtitle),
            altTitles = text("alt name(s)").split(Regex("\\s*[,;/]\\s+")).map { it.trim() }.filter { it.isNotEmpty() && !it.equals(title, true) },
            description = story.joinToString("\n\n").ifEmpty { doc.selectFirst("div.summary__content p")?.text()?.trim().orEmpty() },
            authors = links("writer(s)"),
            artists = links("artist(s)"),
            genres = genres,
            tagGroups = if (tags.isNotEmpty()) listOf(TagGroup("Tags", tags)) else emptyList(),
            status = text("status").let { if (it.equals("OnGoing", true)) "Ongoing" else it },
            type = "Manhwa",
            url = webUrl(ref),
            rating = doc.selectFirst("#averagerate")?.text()?.trim()?.toFloatOrNull()?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }.orEmpty(),
            extra = listOfNotNull(
                text("publisher").takeIf { it.isNotEmpty() }?.let { "Publisher" to it },
                views.takeIf { it.isNotEmpty() }?.let { "Views" to it },
            ),
        )
        if (Safety.blocked(d)) throw SourceException("This title is hidden")
        return d
    }

    // ---------- chapters ----------

    /** "Dec 9, 24" -> epoch ms; fresh chapters say "UP" or "2 days ago" instead. */
    private fun date(s: String): Long {
        val t = s.trim()
        runCatching { LocalDate.parse(t, DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        val now = System.currentTimeMillis()
        val m = Regex("(?i)(\\d+)\\s*(min|hour|day|week)").find(t) ?: return now
        val n = m.groupValues[1].toLongOrNull() ?: return now
        val unit = when (m.groupValues[2].lowercase()) { "min" -> 60_000L; "hour" -> 3_600_000L; "day" -> 86_400_000L; else -> 604_800_000L }
        return now - n * unit
    }

    override suspend fun chapters(ref: MangaRef): List<Chapter> = parseChapters(titlePage(ref.id))
    override suspend fun latestChapters(ref: MangaRef): List<Chapter> = parseChapters(titlePage(ref.id, fresh = true))

    private fun parseChapters(doc: Document): List<Chapter> =
        // every chapter is in the page (no AJAX), newest first
        doc.select("li.wp-manga-chapter").mapNotNull { li ->
            val a = li.selectFirst("a[href]") ?: return@mapNotNull null
            val slug = slugOf(a.attr("href")).ifEmpty { return@mapNotNull null }
            val name = a.text().trim()
            Chapter(
                id = slug,
                name = name.ifEmpty { slug.replace('-', ' ') },
                number = chapterNumber(name) ?: chapterNumber(slug.replace('-', ' ')) ?: -1f,
                date = date(li.selectFirst("span.chapter-release-date")?.text().orEmpty()),
            )
        }.distinctBy { it.id }

    override suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page> {
        val doc = fetch("/serie/${ref.id}/${chapter.id}/")
        val urls = doc.select("div.reading-content img").map { i ->
            listOf("abs:data-src", "abs:src").map { i.attr(it).trim() }.firstOrNull { it.startsWith("http") }.orEmpty()
        }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) throw SourceException("No pages in this chapter")
        return urls.mapIndexed { i, u -> Page(i, u) }
    }

    /** The page's "Similar Manhwa" row (its cards carry no genres). */
    override suspend fun related(ref: MangaRef, details: MangaDetails): List<MangaRef> {
        val doc = runCatching { titlePage(ref.id) }.getOrNull() ?: return emptyList()
        return doc.select("div.toonily-related-manga a.related-item").mapNotNull { a ->
            val slug = slugOf(a.attr("href")).ifEmpty { return@mapNotNull null }
            MangaRef(id, slug, a.attr("title").ifEmpty { a.selectFirst(".related-title")?.text().orEmpty() }.trim(), cover = img(a.selectFirst("img")))
        }.filter { it.id != ref.id }.distinctBy { it.id }
    }

    override fun webUrl(ref: MangaRef) = "$baseUrl/serie/${ref.id}/"

    /** "Chapter 90.5", "Chapter 258 - A Simple Life" -> the number. */
    private fun chapterNumber(name: String): Float? =
        (Regex("(?i)(?:chapter|chap|ch|ep|episode)\\.?\\s*(\\d+(?:\\.\\d+)?)").find(name) ?: Regex("(\\d+(?:\\.\\d+)?)").find(name))
            ?.groupValues?.get(1)?.toFloatOrNull()
}
