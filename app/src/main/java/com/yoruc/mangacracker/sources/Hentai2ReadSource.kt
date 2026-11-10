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
import com.yoruc.mangacracker.core.str
import com.yoruc.mangacracker.core.text
import com.yoruc.mangacracker.core.urlEncode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Hentai2Read (rebuilt in Oct 2026 on Laravel + Inertia): every page carries its data as JSON, and with the
 * X-Inertia headers the site sends only that JSON. Lists carry no tags, so the minors guard is in every query
 * (without=6,23) and in details(). Spec: _dev_tools/specs/hentai2read.md
 */
object Hentai2ReadSource : Source {
    override val id = "hentai2read"
    override val name = "Hentai2Read"
    override val baseUrl = "https://hentai2read.com"
    override val blurb = "hentai2read.com · hentai manga, doujinshi"
    override val color = 0xFFC026D3
    override val nsfw = true

    /** Lolicon (6) and Shota (23): excluded in every list query and refused in details. */
    private val HIDDEN_IDS = setOf("6", "23")
    private const val GUARD = "6,23"
    /** Ids of the tags Settings hides, looked up by name once ("" = the site has no such tag). */
    private val optIds = ConcurrentHashMap<String, String>()

    /** [GUARD] plus the tags Settings hides, for every list query's "without". */
    private suspend fun without(): String {
        val hide = (if (Safety.hideFuta) listOf("Futanari", "Dickgirl on Male") else emptyList()) + (if (Safety.hideFurry) listOf("Furry") else emptyList())
        val ids = hide.map { n -> optIds[n] ?: (resolve("", n).orEmpty()).also { optIds[n] = it } }.filter { it.isNotEmpty() }
        return (listOf(GUARD) + ids).joinToString(",")
    }

    init {
        Http.referers["hentaicdn.com"] = "https://hentai2read.com/"
        Http.rateLimits["hentai2read.com"] = 700L
    }

    override val selects = listOf(
        SelectDef("sort", "Sort", listOf("" to "Newest", "latest" to "Recently updated", "most-viewed" to "Most read",
            "top-rated" to "Highest rated", "alphabetical" to "A to Z", "most-chapters" to "Most chapters",
            "most-pages" to "Most pages", "fewest-pages" to "Fewest pages", "oldest" to "Oldest")),
        SelectDef("type", "Type", listOf("" to "Any", "original" to "Original", "doujinshi" to "Doujinshi", "imageset" to "Image sets")),
        SelectDef("status", "Status", listOf("" to "Any", "ongoing" to "Ongoing", "completed" to "Completed")),
        SelectDef("match", "Included tags", listOf("" to "All of them", "any" to "Any of them")),
    )

    // ---------- Inertia pages ----------

    @Volatile private var version: String? = null
    private val PAGE_JSON = Regex("""<script data-page="app" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    /** The page's Inertia JSON; null on 404 (past the last page, unknown id). */
    private suspend fun page(path: String): JsonElement? {
        val url = if (path.startsWith("http")) path else baseUrl + path
        version?.let { v ->
            val (code, body) = Http.getRaw(url, mapOf("X-Inertia" to "true", "X-Inertia-Version" to v, "X-Requested-With" to "XMLHttpRequest"))
            if (code == 404) return null
            if (code == 200 && body.trimStart().startsWith("{")) return Http.json.parseToJsonElement(body)
            // 409 = the site was updated since we read the version: read the HTML page once more
        }
        val (code, html) = Http.getRaw(url)
        if (code == 404) return null
        if (code !in 200..299) throw SourceException("Hentai2Read answered $code")
        val raw = PAGE_JSON.find(html)?.groupValues?.get(1) ?: throw SourceException("Hentai2Read sent a page we can't read")
        val j = Http.json.parseToJsonElement(raw)
        j.str("version").ifEmpty { null }?.let { version = it }
        return j
    }

    // ---------- lists ----------

    private fun item(d: JsonElement): MangaRef? {
        val bid = d.str("id")
        if (bid.isEmpty() || d.str("type") == "video") return null
        // only the home page "pick" has tags: [[id, name, slug, x], ...]
        val tags = d["tags"].arr.mapNotNull { (it as? JsonArray)?.getOrNull(1)?.text }
        if (d["tagIds"].arr.any { it.text in HIDDEN_IDS }) return null
        val ch = d.int("chapters")
        val pages = d.int("pageCount")
        val sub = listOfNotNull(d.str("typeLabel").ifEmpty { null }, if (ch > 1) "$ch ch" else null, if (pages > 0) "$pages pages" else null)
        return MangaRef(id, bid, d.str("title"), cover = abs(d.str("cover")), tags = tags, subtitle = sub.joinToString(" · "))
    }

    private suspend fun list(path: String): PageResult {
        val b = page(path)?.get("props")?.get("books") ?: return PageResult(emptyList(), false)
        val items = b["data"].arr.mapNotNull { item(it) }
        val next = b["links"].str("next").isNotEmpty() || b.int("page") < b.int("lastPage")
        return PageResult(items, next && b["data"].arr.isNotEmpty())
    }

    private fun abs(u: String) = when {
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> baseUrl + u
        else -> u
    }

    override suspend fun popular(page: Int) = list("/search?without=${without()}&sort=most-viewed&page=$page")
    override suspend fun latest(page: Int) = list("/search?without=${without()}&sort=latest&page=$page")

    override suspend fun search(query: SearchQuery, page: Int): PageResult {
        val inc = mutableListOf<String>()
        val exc = mutableListOf(without())
        val words = mutableListOf<String>()
        if (query.text.isNotBlank()) words += query.text.trim()
        query.include.forEach { t -> resolve(t.id, t.label)?.let { inc += it } ?: run { words += t.label } }
        query.exclude.forEach { t -> resolve(t.id, t.label)?.let { exc += it } }
        if (query.artist.isNotBlank()) resolve("", query.artist.trim(), "artist")?.let { inc += it } ?: run { words += query.artist.trim() }
        val params = buildList {
            if (words.isNotEmpty()) add("q=" + words.joinToString(" ").urlEncode())
            if (inc.isNotEmpty()) add("with=" + inc.distinct().joinToString(","))
            add("without=" + exc.distinct().joinToString(","))
            if (inc.size > 1 && query.selects["match"] == "any") add("match=any")
            listOf("type", "status", "sort").forEach { k -> query.selects[k]?.takeIf { it.isNotEmpty() }?.let { add("$k=$it") } }
            add("page=$page")
        }
        return list("/search?" + params.joinToString("&"))
    }

    // ---------- tags ----------

    /** lower-case tag name -> site id, learnt from the tag list, suggestions and details pages */
    private val names = ConcurrentHashMap<String, String>()
    /** lower-case artist name -> id */
    private val artists = ConcurrentHashMap<String, String>()
    @Volatile private var allTags: List<Tag>? = null

    /** Genres (category + content tags) with their counts: GET /search/tags. */
    override suspend fun tags(): List<Tag> {
        allTags?.let { return it }
        val j = Http.getJson("$baseUrl/search/tags")
        val list = j.arr.map { Tag(it.str("id"), it.str("name"), "Tag", it.int("count")) }
            .filter { it.id !in HIDDEN_IDS }
            .let { Safety.cleanTags(it) }
            .sortedByDescending { it.count }
        list.forEach { names[it.label.lowercase()] = it.id }
        allTags = list
        return list
    }

    /** Any tag type with counts: GET /tags?q= (Inertia page). */
    private suspend fun remoteTags(q: String): List<Tag> {
        val data = page("/tags?q=${q.urlEncode()}")?.get("props")?.get("tags")?.get("data").arr
        return data.mapNotNull { t ->
            val tid = t.str("id")
            if (tid.isEmpty() || tid in HIDDEN_IDS) return@mapNotNull null
            val type = t.str("type")
            names.putIfAbsent(t.str("name").lowercase(), tid)
            if (type == "artist") artists[t.str("name").lowercase()] = tid
            Tag(tid, t.str("name"), if (type == "category" || type == "content") "Tag" else t.str("typeLabel").ifEmpty { type }, t.int("count"))
        }
    }

    override suspend fun suggestTags(query: String): List<Tag> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val local = runCatching { tags() }.getOrDefault(emptyList()).filter { it.label.contains(q, true) }
        val remote = if (q.length >= 2) runCatching { remoteTags(q) }.getOrDefault(emptyList()) else emptyList()
        return Safety.cleanTags((remote + local).distinctBy { it.id })
            .sortedWith(compareBy<Tag> { !it.label.startsWith(q, true) }.thenByDescending { it.count })
            .take(40)
    }

    /** A tag id the site understands, from an id, a known name, the genre list or the tag search. */
    private suspend fun resolve(tagId: String, label: String, type: String = ""): String? {
        if (tagId.isNotEmpty() && tagId.all { it.isDigit() }) return tagId
        val name = (label.ifEmpty { tagId }).substringAfter(':').trim()
        if (name.isEmpty()) return null
        val key = name.lowercase()
        if (type == "artist") artists[key]?.let { return it } else names[key]?.let { return it }
        if (type.isEmpty()) runCatching { tags() }
        if (type.isEmpty()) names[key]?.let { return it }
        val hit = runCatching { remoteTags(name) }.getOrDefault(emptyList())
            .firstOrNull { it.label.equals(name, true) && (type.isEmpty() || it.group.equals(type, true)) }
        return hit?.id
    }

    override fun tagQuery(name: String): SearchQuery = SearchQuery(include = listOf(Tag(names[name.lowercase()] ?: name, name)))
    override val authorsAreGroups get() = true
    override fun groupQuery(name: String) = tagQuery(name)

    // ---------- details ----------

    private data class Cached(val at: Long, val json: JsonElement)
    private val books = ConcurrentHashMap<String, Cached>()

    private suspend fun book(bid: String): JsonElement {
        books[bid]?.takeIf { System.currentTimeMillis() - it.at < 10 * 60_000L }?.let { return it.json }
        // any type word works: the site 301s /original/<id> to the right /<type>/<id>-<slug>
        val p = page("/original/$bid")?.get("props") ?: throw SourceException("Not found on Hentai2Read")
        if (p["book"] == null) throw SourceException("Not found on Hentai2Read")
        books[bid] = Cached(System.currentTimeMillis(), p)
        return p
    }

    private fun hidden(p: JsonElement) = p["book"]["tags"].arr.any { it.str("id") in HIDDEN_IDS }

    override suspend fun details(ref: MangaRef): MangaDetails {
        val p = book(ref.id)
        if (hidden(p)) throw SourceException("This title is hidden")
        val b = p["book"]
        val genres = b["tags"].arr.map { t -> t.str("name").also { names[it.lowercase()] = t.str("id") } }
        val groups = b["groups"].arr.map { g ->
            val type = g.str("type")
            val list = g["tags"].arr.map { t ->
                t.str("name").also {
                    names.putIfAbsent(it.lowercase(), t.str("id"))
                    if (type == "artist") artists[it.lowercase()] = t.str("id")
                }
            }
            Triple(type, g.str("label").ifEmpty { type.replaceFirstChar { it.uppercase() } }, list)
        }
        fun of(type: String) = groups.filter { it.first == type }.flatMap { it.third }.filter { it != "Unknown" }
        val stats = p["stats"]
        val d = MangaDetails(
            ref = ref.copy(
                title = b.str("title").ifEmpty { ref.title },
                cover = abs(b.str("cover")).ifEmpty { ref.cover },
                tags = genres + groups.flatMap { it.third },
            ),
            altTitles = b.str("alt").split(" / ").map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() },
            description = b.str("description"),
            authors = of("circle"),
            artists = of("artist"),
            genres = genres,
            tagGroups = groups.filter { it.first != "artist" && it.first != "circle" && it.third.isNotEmpty() }.map { TagGroup(it.second, it.third) },
            status = b["status"].str("label"),
            type = b["type"].str("label"),
            url = webUrl(ref),
            rating = if (stats.int("ratings") > 0) "★ ${stats.str("rating")} (${stats.int("ratings")})" else "",
            extra = listOfNotNull(
                stats.int("views").takeIf { it > 0 }?.let { "Views" to "%,d".format(it) },
                stats.int("rank").takeIf { it > 0 }?.let { "Rank" to "#$it" },
                stats.str("updated").ifEmpty { null }?.let { "Updated" to it },
            ),
        )
        if (Safety.blocked(d)) throw SourceException("This title is hidden")
        return d
    }

    override suspend fun chapters(ref: MangaRef): List<Chapter> {
        val p = book(ref.id)
        if (hidden(p)) throw SourceException("This title is hidden")
        // newest first, as the site lists them
        return p["chapters"].arr.map { c ->
            val num = c.str("number")
            val title = c.str("title")
            val name = listOf(if (num.isNotEmpty()) "Ch. $num" else "", title).filter { it.isNotEmpty() }.joinToString(" · ") +
                (if (c.str("isMembers") == "true") " (members)" else "")
            Chapter(c.str("id"), name.ifEmpty { "Chapter" }, num.toFloatOrNull() ?: -1f, date(c.str("date")), pages = c.int("length"))
        }
    }

    override suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page> {
        if (hidden(book(ref.id))) throw SourceException("This title is hidden")
        val p = page("/reader/${ref.id}/${chapter.id}/page-1")?.get("props") ?: throw SourceException("Chapter not found")
        val sizes = p["sizes"].arr
        val avif = p["avif"].arr
        return p["images"].arr.mapIndexedNotNull { i, el ->
            val url = el.text.ifEmpty { avif.getOrNull(i)?.text.orEmpty() }
            if (url.isEmpty()) return@mapIndexedNotNull null
            val wh = sizes.getOrNull(i).arr
            Page(i, abs(url), wh.getOrNull(0)?.text?.toIntOrNull() ?: 0, wh.getOrNull(1)?.text?.toIntOrNull() ?: 0)
        }.mapIndexed { i, pg -> pg.copy(index = i) }
    }

    /** More by the same artist (the site's own "related" list carries no tags, so it can't be guarded). */
    override suspend fun related(ref: MangaRef, details: MangaDetails): List<MangaRef> {
        val p = book(ref.id)
        val aid = p["book"]["groups"].arr.firstOrNull { it.str("type") == "artist" }?.get("tags").arr
            .firstOrNull { it.str("name") != "Unknown" }?.str("id") ?: return emptyList()
        return runCatching { list("/search?with=$aid&without=${without()}&sort=most-viewed").items }.getOrDefault(emptyList())
            .filter { it.id != ref.id }
    }

    override fun webUrl(ref: MangaRef) = "$baseUrl/original/${ref.id}"

    // ---------- dates: "Jul 4, 2020", sometimes "3 days ago" ----------

    private val RELATIVE = Regex("""(\d+|an?|one)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE)

    private fun date(s: String): Long {
        val t = s.trim()
        if (t.isEmpty()) return 0L
        runCatching {
            return SimpleDateFormat("MMM d, yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(t)!!.time
        }
        val m = RELATIVE.find(t) ?: return if (t.contains("just now", true) || t.contains("today", true)) System.currentTimeMillis() else 0L
        val n = m.groupValues[1].toLongOrNull() ?: 1L
        val unit = when (m.groupValues[2].lowercase()) {
            "second" -> 1_000L; "minute" -> 60_000L; "hour" -> 3_600_000L; "day" -> 86_400_000L
            "week" -> 7 * 86_400_000L; "month" -> 30 * 86_400_000L; else -> 365 * 86_400_000L
        }
        return System.currentTimeMillis() - n * unit
    }
}
