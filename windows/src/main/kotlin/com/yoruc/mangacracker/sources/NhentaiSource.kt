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
import com.yoruc.mangacracker.core.get
import com.yoruc.mangacracker.core.int
import com.yoruc.mangacracker.core.long
import com.yoruc.mangacracker.core.str
import com.yoruc.mangacracker.core.strings
import com.yoruc.mangacracker.core.text
import com.yoruc.mangacracker.core.urlEncode
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * nhentai through its v2 JSON API (the website itself is blocked in the UK; the API and images are not).
 * Its limits are low (search 10 a minute), so everything is spaced and cached. Spec: _dev_tools/specs/nhentai.md
 */
object NhentaiSource : Source {
    override val id = "nhentai"
    override val name = "nhentai"
    override val baseUrl = "https://nhentai.net"
    override val blurb = "nhentai.net · doujinshi"
    override val color = 0xFFED2553
    override val nsfw = true
    override val hasChapters = false
    private const val API = "https://nhentai.net/api/v2"

    /** Minor-related tag ids and names, never shown. */
    private val HIDDEN_IDS = setOf("19440", "32341", "159421", "25663", "14362", "27598", "152252")
    /** Tag ids the switches care about: AI, futanari, dickgirl on male / dickgirl, human on furry. */
    private val NAMED = mapOf("145703" to "ai generated", "779" to "futanari", "35968" to "dickgirl on male",
        "35970" to "dickgirl on dickgirl", "36616" to "human on furry")
    private val GUARD = listOf("lolicon", "shotacon", "toddlercon", "oppai loli", "low lolicon", "low shotacon", "kodomo doushi")
        .joinToString(" ") { "-tag:\"$it\"" }
    /** [GUARD] plus the tags Settings hides (the site drops them, so pages stay full). */
    private val guard get() = GUARD +
        (if (Safety.hideFuta) " -tag:\"futanari\" -tag:\"dickgirl on male\" -tag:\"dickgirl on dickgirl\"" else "") +
        (if (Safety.hideFurry) " -tag:\"furry\" -tag:\"human on furry\"" else "")

    init {
        Http.userAgents["nhentai.net"] = "MangaCracker/1.0 (personal Android reader)"
        Http.rateLimits["nhentai.net/api/v2/search"] = 6200L
        Http.rateLimits["nhentai.net/api/v2/galleries/popular"] = 7600L
        Http.rateLimits["nhentai.net/api/v2"] = 3100L
    }

    override val selects = listOf(
        SelectDef("sort", "Sort", listOf("date" to "Newest", "popular-today" to "Popular today", "popular-week" to "Popular this week",
            "popular-month" to "Popular this month", "popular" to "All-time popular")),
        SelectDef("lang", "Language", listOf("" to "Any", "english" to "English", "japanese" to "Japanese", "chinese" to "Chinese")),
    )

    // ---------- small cache: the limits make every call precious ----------

    private data class Cached(val at: Long, val json: JsonElement)
    private val cache = ConcurrentHashMap<String, Cached>()

    private suspend fun api(path: String, ttlMs: Long = 5 * 60_000L): JsonElement {
        cache[path]?.takeIf { System.currentTimeMillis() - it.at < ttlMs }?.let { return it.json }
        val (code, body) = Http.getRaw("$API$path", mapOf("Accept" to "application/json"))
        val j = runCatching { Http.json.parseToJsonElement(body) }.getOrNull()
        when {
            code == 429 -> throw SourceException("nhentai asks to slow down. Give it a minute.")
            code == 404 -> throw SourceException(j?.str("error")?.ifEmpty { null } ?: "Not found")
            code !in 200..299 -> throw SourceException(j?.str("error")?.ifEmpty { null } ?: "nhentai answered $code")
        }
        j ?: throw SourceException("nhentai sent something unreadable")
        cache[path] = Cached(System.currentTimeMillis(), j)
        return j
    }

    private var servers: Pair<List<String>, List<String>>? = null
    private suspend fun cdn(): Pair<List<String>, List<String>> {
        servers?.let { return it }
        val j = runCatching { api("/cdn", 24 * 3600_000L) }.getOrNull()
        val s = (j?.get("image_servers").strings().ifEmpty { listOf("https://i1.nhentai.net") }) to
            (j?.get("thumb_servers").strings().ifEmpty { listOf("https://t1.nhentai.net") })
        servers = s
        return s
    }

    private suspend fun thumb(path: String) = if (path.startsWith("http")) path else cdn().second.random() + "/" + path.removePrefix("/")
    private suspend fun image(path: String, server: String) = if (path.startsWith("http")) path else server + "/" + path.removePrefix("/")

    private suspend fun item(j: JsonElement): MangaRef? {
        val tagIds = j["tag_ids"].arr.map { it.text }
        if (tagIds.any { it in HIDDEN_IDS }) return null
        return MangaRef(
            source = id, id = j.str("id"),
            title = j.str("english_title").ifEmpty { j.str("japanese_title") },
            cover = thumb(j.str("thumbnail")),
            // lists only carry tag ids; the names let Safety's switches see them
            tags = tagIds.map { "nh:$it" } + tagIds.mapNotNull { NAMED[it] },
            subtitle = "${j.int("num_pages")} pages",
        )
    }

    private suspend fun list(j: JsonElement, page: Int): PageResult {
        val items = j["result"].arr.mapNotNull { item(it) }
        val pages = j.int("num_pages")
        return PageResult(items, if (pages > 0) page < pages else items.isNotEmpty())
    }

    override suspend fun popular(page: Int): PageResult =
        list(api("/search?query=${guard.urlEncode()}&sort=popular-week&page=$page", 15 * 60_000L), page)

    override suspend fun latest(page: Int): PageResult = list(api("/galleries?page=$page&per_page=25", 2 * 60_000L), page)

    override suspend fun search(query: SearchQuery, page: Int): PageResult {
        val parts = mutableListOf<String>()
        if (query.text.isNotBlank()) parts += query.text.trim()
        if (query.artist.isNotBlank()) parts += "artist:\"${query.artist.trim()}\""
        query.include.forEach { parts += part(it.id) }
        query.exclude.forEach { parts += "-" + part(it.id) }
        query.selects["lang"]?.takeIf { it.isNotEmpty() }?.let { parts += "language:$it" }
        parts += guard
        val sort = query.selects["sort"] ?: "date"
        return list(api("/search?query=${parts.joinToString(" ").urlEncode()}&sort=$sort&page=$page"), page)
    }

    /** "artist:name" / "parody:name" / plain tag name -> query part */
    private fun part(tag: String): String {
        val ns = tag.substringBefore(':', "")
        val t = tag.substringAfter(':')
        return when (ns) {
            "artist", "group", "parody", "character", "language", "category" -> "$ns:\"$t\""
            else -> "tag:\"$t\""
        }
    }

    override fun tagQuery(name: String): SearchQuery {
        val ns = name.substringBefore(':', "")
        val n = if (ns in setOf("artist", "group", "parody", "character", "language", "category")) name else name.substringAfter(':')
        return SearchQuery(include = listOf(Tag(n, n.substringAfter(':'))))
    }

    override fun artistQuery(name: String) = SearchQuery(include = listOf(Tag("artist:$name", name)))

    override suspend fun tags(): List<Tag> = listOf(
        "big breasts", "sole female", "sole male", "nakadashi", "stockings", "glasses", "schoolgirl uniform", "full color",
        "ahegao", "anal", "milf", "paizuri", "blowjob", "x-ray", "defloration", "dark skin", "swimsuit", "bikini", "maid",
        "netorare", "mind break", "femdom", "yuri", "yaoi", "futanari", "tentacles", "monster girl", "elf", "kemonomimi",
        "catgirl", "bunny girl", "huge breasts", "small breasts", "pantyhose", "ponytail", "twintails", "uncensored",
        "mosaic censorship", "story arc", "harem", "cheating", "vanilla", "group", "muscle", "tall girl", "tanlines",
        "demon girl", "nurse", "teacher", "office lady", "kimono", "cosplaying",
    ).map { Tag(it, it) }

    override suspend fun suggestTags(query: String): List<Tag> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        // nhentai has no tag-name search: offer matches from the list, and the typed words as they are
        val local = tags().filter { it.label.contains(q, true) }
        // group "typed": the filter sheet offers them, the search box's autocomplete leaves them out
        val typed = listOf(Tag(q, "$q (tag)", "typed"), Tag("artist:$q", "artist: $q", "typed"), Tag("parody:$q", "parody: $q", "typed"), Tag("character:$q", "character: $q", "typed"))
        return (local + typed).distinctBy { it.id }
    }

    // ---------- gallery ----------

    private suspend fun gallery(id: String): JsonElement = api("/galleries/$id?include=related", 24 * 3600_000L)

    override suspend fun details(ref: MangaRef): MangaDetails {
        val g = gallery(ref.id)
        val tags = g["tags"].arr
        if (tags.any { it.str("id") in HIDDEN_IDS }) throw SourceException("This gallery is hidden")
        fun of(type: String) = tags.filter { it.str("type") == type }.map { it.str("name") }
        val t = g["title"]
        return MangaDetails(
            ref = MangaRef(id, ref.id, t.str("english").ifEmpty { t.str("pretty") }.ifEmpty { ref.title },
                cover = thumb(g["cover"].str("path")).ifEmpty { ref.cover },
                tags = tags.map { it.str("type") + ":" + it.str("name") }, subtitle = "${g.int("num_pages")} pages"),
            altTitles = listOfNotNull(t.str("japanese").ifEmpty { null }, t.str("pretty").ifEmpty { null }),
            authors = of("group"),
            artists = of("artist"),
            genres = of("tag"),
            tagGroups = listOf("parody" to "Parody", "character" to "Character", "language" to "Language", "category" to "Category")
                .mapNotNull { (k, label) -> of(k).takeIf { it.isNotEmpty() }?.let { TagGroup(label, it) } },
            status = "Complete",
            type = of("category").firstOrNull()?.replaceFirstChar { it.uppercase() }.orEmpty(),
            pageCount = g.int("num_pages"),
            rating = "♥ ${g.int("num_favorites")}",
            url = webUrl(ref),
        )
    }

    override suspend fun chapters(ref: MangaRef): List<Chapter> {
        val g = gallery(ref.id)
        return listOf(Chapter("g", "Gallery · ${g.int("num_pages")} pages", 1f, g.long("upload_date") * 1000, g.str("scanlator"), g.int("num_pages")))
    }

    override suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page> {
        val g = gallery(ref.id)
        if (g["tags"].arr.any { it.str("id") in HIDDEN_IDS }) throw SourceException("This gallery is hidden")
        val server = cdn().first.random()
        return g["pages"].arr.mapIndexed { i, p -> Page(i, image(p.str("path"), server), p.int("width"), p.int("height")) }
    }

    override suspend fun related(ref: MangaRef, details: MangaDetails): List<MangaRef> =
        gallery(ref.id)["related"].arr.mapNotNull { item(it) }

    override fun webUrl(ref: MangaRef) = "$baseUrl/g/${ref.id}/"
}
