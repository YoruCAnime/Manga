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
import com.yoruc.mangacracker.core.arr
import com.yoruc.mangacracker.core.float
import com.yoruc.mangacracker.core.get
import com.yoruc.mangacracker.core.int
import com.yoruc.mangacracker.core.isoToMillis
import com.yoruc.mangacracker.core.long
import com.yoruc.mangacracker.core.str
import com.yoruc.mangacracker.core.urlEncode
import kotlinx.serialization.json.JsonElement
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap

/**
 * manhwa18.net: a Laravel + Inertia site, so every page carries its data as JSON in `#app[data-page]`.
 * Spec: _dev_tools/specs/manhwa18net.md
 */
object Manhwa18NetSource : Source {
    override val id = "manhwa18net"
    override val name = "Manhwa18.net"
    override val baseUrl = "https://manhwa18.net"
    override val blurb = "manhwa18.net · adult manhwa"
    override val color = 0xFFE0457B
    override val nsfw = true

    /** Genre ids of the video-only titles (a poster + a player, no pages): left out of every list. */
    private val VIDEO_GENRES = mapOf("165" to "Animation", "166" to "Hentai Anime")

    init {
        Http.referers["manhwa18.net"] = "$baseUrl/"
        // page requests only (the paths), so the picture host min.manhwa18.net is not slowed down
        Http.rateLimits["manhwa18.net/manga"] = 400L
        Http.rateLimits["manhwa18.net/tim-kiem"] = 400L
        Http.rateLimits["manhwa18.net/genre"] = 400L
    }

    override val selects = listOf(
        SelectDef("sort", "Sort", listOf(
            "update" to "Latest update", "top" to "Most views", "new" to "Newest", "like" to "Most liked", "az" to "A-Z", "za" to "Z-A",
        )),
        // only the plain lists and single-genre lists can filter by status (the text search ignores it)
        SelectDef("status", "Status", listOf("" to "Any", "Ongoing" to "Ongoing", "completed" to "Completed", "Onhold" to "Dropped")),
    )

    // ---------- page data ----------

    private suspend fun props(path: String): JsonElement {
        val doc = Http.getDoc(if (path.startsWith("http")) path else baseUrl + path)
        val raw = doc.selectFirst("#app[data-page]")?.attr("data-page") ?: throw SourceException("manhwa18.net changed its page layout")
        return Http.json.parseToJsonElement(raw)["props"] ?: throw SourceException("manhwa18.net sent an empty page")
    }

    private fun abs(u: String): String = when {
        u.isEmpty() -> ""
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> baseUrl + u
        else -> u
    }

    /** Grid cover: the site's own 320 px webp when it has one, else the uploaded picture. */
    private fun cover(m: JsonElement?): String {
        val v = m?.get("cover_image")?.get("variants").arr.firstOrNull { it.int("width") >= 300 }
        return abs(v?.str("url").orEmpty().ifEmpty { m.str("cover_url") }.ifEmpty { m.str("thumb_url") })
    }

    private fun row(m: JsonElement): MangaRef? {
        val genres = m["genres"].arr.map { it.str("name") }
        if (genres.any { it in VIDEO_GENRES.values }) return null
        return MangaRef(id, m.str("slug"), m.str("name"), cover = cover(m), tags = genres, subtitle = m["latest_chapter"].str("name"))
    }

    /** A Laravel paginator (`paginate` on lists, `mangas` on the search page). */
    private fun paged(p: JsonElement?, keep: (MangaRef) -> Boolean = { true }): PageResult {
        val items = p["data"].arr.mapNotNull { row(it) }.filter(keep)
        return PageResult(items, p.int("current_page") < p.int("last_page"))
    }

    private fun listQuery(sort: String, status: String, page: Int): String = buildString {
        append("?sort=").append(sort.ifEmpty { "update" })
        if (status.isNotEmpty()) append('&').append(status).append("=1")
        append("&page=").append(page)
    }

    override suspend fun popular(page: Int) = paged(props("/manga-list" + listQuery("top", "", page))["paginate"])
    override suspend fun latest(page: Int) = paged(props("/manga-list" + listQuery("update", "", page))["paginate"])

    // ---------- tags ----------

    @Volatile private var genreCache: List<Tag>? = null
    /** genre id -> slug, for the /genre/<slug> lists */
    private val genreSlugs = ConcurrentHashMap<String, String>()
    /** tag / artist name (lower case) -> slug, learnt from details pages */
    private val tagSlugs = ConcurrentHashMap<String, String>()
    private val artistSlugs = ConcurrentHashMap<String, String>()

    override suspend fun tags(): List<Tag> {
        genreCache?.let { return it }
        val list = props("/tim-kiem")["genres"].arr.filter { it.str("id") !in VIDEO_GENRES }.map {
            genreSlugs[it.str("id")] = it.str("slug")
            Tag(it.str("id"), it.str("name"), "Genre")
        }
        return Safety.cleanTags(list).also { genreCache = it }
    }

    override fun tagQuery(name: String): SearchQuery {
        genreCache?.firstOrNull { it.label.equals(name, true) }?.let { return SearchQuery(include = listOf(it)) }
        tagSlugs[name.lowercase()]?.let { return SearchQuery(include = listOf(Tag("tag:$it", name))) }
        return SearchQuery(include = listOf(Tag(name, name)))
    }

    /** A tag from the picker (numeric id), from tagQuery ("tag:<slug>"), or just a name: -> genre id or "tag:<slug>". */
    private suspend fun resolve(t: Tag): String? {
        if (t.id.startsWith("tag:") || t.id.all { it.isDigit() }) return t.id
        val all = runCatching { tags() }.getOrDefault(emptyList())
        all.firstOrNull { it.label.equals(t.label, true) || it.label.equals(t.id, true) }?.let { return it.id }
        return tagSlugs[t.label.lowercase()]?.let { "tag:$it" }
    }

    // ---------- search ----------

    override suspend fun search(query: SearchQuery, page: Int): PageResult {
        val sort = query.selects["sort"].orEmpty()
        val status = query.selects["status"].orEmpty()
        val text = query.text.trim()
        val inc = query.include.mapNotNull { resolve(it) }
        val exc = query.exclude.mapNotNull { resolve(it) }.filter { !it.startsWith("tag:") }

        // an artist tapped on a details page: their own list
        if (query.artist.isNotBlank() && text.isEmpty() && inc.isEmpty()) {
            val slug = artistSlugs[query.artist.lowercase()] ?: slugify(query.artist)
            val r = runCatching { paged(props("/tac-gia/$slug?page=$page")["mangas"]) }.getOrNull()
            if (r != null && (r.items.isNotEmpty() || page > 1)) return r
        }
        val words = listOf(text, query.artist.trim()).filter { it.isNotEmpty() }.joinToString(" ")

        if (words.isEmpty()) {
            // one tag page (tags are not genres and have no search filter)
            inc.firstOrNull { it.startsWith("tag:") }?.let { tag ->
                return paged(props("/tag/${tag.removePrefix("tag:")}" + listQuery(sort, status, page))["paginate"])
            }
            if (inc.isEmpty() && exc.isEmpty()) return paged(props("/manga-list" + listQuery(sort, status, page))["paginate"])
            if (inc.isNotEmpty()) {
                // the site ORs several genres, so list the first one and keep titles that have all the others (AND)
                if (genreSlugs.isEmpty()) runCatching { tags() }
                val slug = genreSlugs[inc.first()] ?: throw SourceException("Unknown genre")
                val wanted = inc.drop(1).mapNotNull { gid -> genreCache?.firstOrNull { it.id == gid }?.label }
                val unwanted = exc.mapNotNull { gid -> genreCache?.firstOrNull { it.id == gid }?.label }
                return paged(props("/genre/$slug" + listQuery(sort, status, page))["paginate"]) { ref ->
                    wanted.all { it in ref.tags } && unwanted.none { it in ref.tags }
                }
            }
        }
        // the text search: genres to include are matched as "any of", excluded ones are left out by the site
        val reject = (exc + VIDEO_GENRES.keys.filter { it !in inc }).distinct()
        val url = buildString {
            append("/tim-kiem?page=").append(page)
            if (words.isNotEmpty()) append("&q=").append(words.urlEncode())
            val g = inc.filter { !it.startsWith("tag:") }
            if (g.isNotEmpty()) append("&accept_genres=").append(g.joinToString(","))
            append("&reject_genres=").append(reject.joinToString(","))
            if (sort.isNotEmpty()) append("&sort=").append(sort)
        }
        return paged(props(url)["mangas"])
    }

    private fun slugify(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    // ---------- details ----------

    /** slug -> (time, props of the title page): details and chapters share one fetch. */
    private val cache = ConcurrentHashMap<String, Pair<Long, JsonElement>>()

    private suspend fun titlePage(slug: String, fresh: Boolean = false): JsonElement {
        val hit = cache[slug]
        if (!fresh && hit != null && System.currentTimeMillis() - hit.first < 60_000) return hit.second
        val p = props("/manga/$slug")
        if (p["manga"] == null) throw SourceException("Title not found on manhwa18.net")
        cache[slug] = System.currentTimeMillis() to p
        return p
    }

    override suspend fun details(ref: MangaRef): MangaDetails {
        val m = titlePage(ref.id, fresh = true)["manga"]
        val genres = m["genres"].arr.map { it.str("name") }
        val tags = m["tags"].arr.map { t -> t.str("name").also { tagSlugs[it.lowercase()] = t.str("slug") } }
        val artists = m["artists"].arr.map { a -> a.str("name").also { artistSlugs[it.lowercase()] = a.str("slug") } }
        val characters = m["characters"].arr.map { it.str("name") }.filter { it.isNotBlank() }
        val title = m.str("name").ifEmpty { ref.title }
        val pilot = Jsoup.parse(m.str("pilot"))
        val paragraphs = pilot.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
        val d = MangaDetails(
            ref = MangaRef(id, ref.id, title, cover = abs(m.str("cover_url")).ifEmpty { ref.cover }, tags = genres + tags, subtitle = ref.subtitle),
            altTitles = m.str("other_name").split(Regex("\\s*[/,;]\\s*")).map { it.trim().removePrefix("(").removeSuffix(")").trim() }
                .filter { it.isNotEmpty() && !it.equals(title, true) }.distinct(),
            description = if (paragraphs.isNotEmpty()) paragraphs.joinToString("\n\n") else pilot.text(),
            artists = artists,
            genres = genres,
            tagGroups = listOfNotNull(
                tags.takeIf { it.isNotEmpty() }?.let { TagGroup("Tags", it) },
                characters.takeIf { it.isNotEmpty() }?.let { TagGroup("Characters", it) },
            ),
            status = when (m.int("status_id")) { 0 -> "Ongoing"; 1 -> "Completed"; 2 -> "Dropped"; else -> "" },
            type = genres.firstOrNull { it in setOf("Manhwa", "Manga", "Manhua", "Webtoon", "Webtoons", "Doujinshi") }?.removeSuffix("s").orEmpty(),
            url = webUrl(ref),
            rating = m.float("rating_average").takeIf { it > 0 }?.let { "★ %.2f".format(it) }.orEmpty(),
            extra = listOf("Views" to "%,d".format(m.long("view"))),
        )
        if (Safety.blocked(d)) throw SourceException("This title is hidden")
        return d
    }

    // already newest first (by the site's own `order`)
    override suspend fun chapters(ref: MangaRef): List<Chapter> =
        titlePage(ref.id)["chapters"].arr.map { c ->
            Chapter(
                id = c.str("slug"),
                name = c.str("name"),
                number = chapterNumber(c.str("name")) ?: c.float("order"),
                date = isoToMillis(c.str("created_at")),
            )
        }

    override suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page> {
        val p = props("/manga/${ref.id}/${chapter.id}")
        var urls = p["chapterImages"].arr.map { abs(it.str("src")) }.filter { it.isNotEmpty() }
        if (urls.isEmpty()) urls = Jsoup.parse(p.str("chapterContent"), baseUrl).select("img").map { it.attr("abs:src").ifEmpty { it.attr("abs:data-src") } }.filter { it.isNotEmpty() }
        if (urls.isNotEmpty() && urls.all { "/video-posters/" in it }) throw SourceException("This chapter is a video - open it on the site")
        if (urls.isEmpty()) throw SourceException("No pages in this chapter")
        return urls.mapIndexed { i, u -> Page(i, u) }
    }

    override fun webUrl(ref: MangaRef) = "$baseUrl/manga/${ref.id}"

    /** "Chapter 90.5", "chap 02", "EP6 - EPISODE 6" -> the number. */
    private fun chapterNumber(name: String): Float? =
        (Regex("(?i)(?:chapter|chap|ch|ep|episode)\\.?\\s*(\\d+(?:\\.\\d+)?)").find(name) ?: Regex("(\\d+(?:\\.\\d+)?)").find(name))
            ?.groupValues?.get(1)?.toFloatOrNull()
}
