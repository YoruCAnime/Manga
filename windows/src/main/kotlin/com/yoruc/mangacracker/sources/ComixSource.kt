package com.yoruc.mangacracker.sources

import com.yoruc.mangacracker.core.Chapter
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.MangaDetails
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Page
import com.yoruc.mangacracker.core.PageResult
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SelectDef
import com.yoruc.mangacracker.core.Source
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.core.TagGroup
import com.yoruc.mangacracker.core.arr
import com.yoruc.mangacracker.core.bool
import com.yoruc.mangacracker.core.float
import com.yoruc.mangacracker.core.get
import com.yoruc.mangacracker.core.int
import com.yoruc.mangacracker.core.obj
import com.yoruc.mangacracker.core.str
import com.yoruc.mangacracker.core.strings
import com.yoruc.mangacracker.core.urlEncode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jsoup.Jsoup
import java.util.concurrent.ConcurrentHashMap

/**
 * comix.to: details, home rows and recommendations from the page's own initial-data (plain HTTP);
 * search, paging, chapter lists and page lists through [ComixBridge]. Spec: _dev_tools/specs/comix.md
 */
object ComixSource : Source {
    override val id = "comix"
    override val name = "Comix"
    override val baseUrl = "https://comix.to"
    override val blurb = "comix.to · manga, manhwa, manhua"
    override val color = 0xFF22C55E
    override val nsfw = false

    /** Minor-related tag ids, excluded on every list call. */
    private val HIDDEN = listOf(101462, 113446, 102568, 102407, 104264, 105051, 107660, 100753, 113898, 102228, 102229,
        103278, 108794, 101093, 101092, 99608)
    /** Fine in normal stories, hidden on adult titles. */
    private val HIDDEN_ADULT = setOf("Child Protagonist", "Child/ren", "Elementary School Student/s", "Elementary School", "Lolita/s")

    init { Http.rateLimits["comix.to"] = 900L }

    private val GENRES = listOf("Action" to 6, "Adventure" to 7, "Boys Love" to 8, "Comedy" to 9, "Crime" to 10, "Drama" to 11,
        "Fantasy" to 12, "Girls Love" to 13, "Harem" to 40, "Historical" to 14, "Horror" to 15, "Isekai" to 16, "Magical Girls" to 17,
        "Mecha" to 18, "Medical" to 19, "Mystery" to 20, "Philosophical" to 21, "Psychological" to 22, "Romance" to 23, "Sci-Fi" to 24,
        "Slice of Life" to 25, "Sports" to 26, "Superhero" to 27, "Thriller" to 28, "Tragedy" to 29, "Wuxia" to 30,
        "Ecchi" to 87265, "Mature" to 87267, "Adult" to 87264, "Smut" to 87268, "Hentai" to 87266,
        "Full Color" to 93172, "Long Strip" to 93170, "Oneshot" to 93169, "Web Comic" to 93171, "4-Koma" to 93164,
        "Adaptation" to 93167, "Anthology" to 93165, "Award Winning" to 93166, "Doujinshi" to 93168)

    override val selects = listOf(
        SelectDef("sort", "Sort", listOf("views_30d" to "Popular this month", "views_7d" to "Popular this week", "chapter_updated_at" to "Latest update",
            "follows_total" to "Most followed", "score" to "Top rated", "created_at" to "Recently added", "views_total" to "Most viewed", "title" to "Title", "relevance" to "Best match")),
        SelectDef("type", "Type", listOf("" to "Any", "manga" to "Manga", "manhwa" to "Manhwa", "manhua" to "Manhua")),
        SelectDef("status", "Status", listOf("" to "Any", "releasing" to "Ongoing", "finished" to "Finished", "on_hiatus" to "On hiatus", "discontinued" to "Discontinued")),
        SelectDef("demo", "Demographic", listOf("" to "Any", "2" to "Shounen", "4" to "Seinen", "1" to "Shoujo", "3" to "Josei")),
        SelectDef("adult", "18+ titles", listOf("safe" to "Hide", "all" to "Show too", "only" to "Only 18+")),
    )

    override suspend fun tags(): List<Tag> = GENRES.map { Tag(it.second.toString(), it.first, "Genre") }

    override suspend fun suggestTags(query: String): List<Tag> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val local = tags().filter { it.label.contains(q, true) }
        val remote = runCatching {
            Http.getJson("$baseUrl/api/v1/tags/search?type=tag&q=${q.urlEncode()}&limit=20")["result"].arr
                .filter { it.int("id") !in HIDDEN }.map { Tag(it.str("id"), it.str("label"), "Tag") }
        }.getOrDefault(emptyList())
        return (local + remote).distinctBy { it.id }
    }

    override fun tagQuery(name: String): SearchQuery {
        val g = GENRES.firstOrNull { it.first.equals(name, true) }
        return if (g != null) SearchQuery(include = listOf(Tag(g.second.toString(), g.first))) else SearchQuery(text = name)
    }

    // ---------- helpers ----------

    private fun ref(m: JsonElement): MangaRef? {
        val hid = m.str("hid").ifEmpty { return null }
        val rating = m.str("contentRating")
        return MangaRef(
            source = id, id = hid, title = m.str("title"),
            cover = m["poster"].str("large").ifEmpty { m["poster"].str("medium") },
            tags = (if (rating == "erotica" || rating == "pornographic") listOf("adult") else emptyList()) +
                m["genres"].arr.map { it.str("title") } + m["tags"].arr.map { it.str("title") },
            subtitle = listOf(m.str("type").replaceFirstChar { it.uppercase() }, m.str("latestChapter").takeIf { it.isNotEmpty() && it != "0" }?.let { "Ch. $it" }.orEmpty())
                .filter { it.isNotEmpty() }.joinToString(" · "),
        )
    }

    private fun usable(r: MangaRef) = !("adult" in r.tags && r.tags.any { it in HIDDEN_ADULT })

    /** The page's initial-data queries: decoded key array -> value */
    private suspend fun initialData(url: String): List<Pair<List<JsonElement>, JsonElement>> {
        val html = Http.get(url)
        val raw = Jsoup.parse(html).selectFirst("script#initial-data")?.data() ?: throw SourceException("comix page has no data")
        val q = Http.json.parseToJsonElement(raw)["queries"].obj ?: return emptyList()
        return q.entries.mapNotNull { (k, v) -> runCatching { (Http.json.parseToJsonElement(k) as JsonArray).toList() to v }.getOrNull() }
    }

    private fun key(k: List<JsonElement>, vararg parts: String) = parts.indices.all { (k.getOrNull(it) as? JsonPrimitive)?.content == parts[it] }

    private fun ratings(mode: String) = when (mode) {
        "all" -> listOf("safe", "suggestive", "erotica", "pornographic")
        "only" -> listOf("erotica", "pornographic")
        else -> listOf("safe", "suggestive")
    }

    private suspend fun bridgeList(params: kotlinx.serialization.json.JsonObject, page: Int): PageResult {
        val out = ComixBridge.call("manga,list", "$baseUrl/browse", "window.__cxlib.list($params)")
        val j = Http.json.parseToJsonElement(out)
        val res = j["result"]
        val items = res["items"].arr.mapNotNull { ref(it) }.filter { usable(it) }
        return PageResult(items, res["meta"]?.bool("hasNext") ?: (items.size >= 28))
    }

    private fun params(page: Int, sort: String, rating: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) = buildJsonObject {
        put("page", page); put("limit", 28)
        putJsonObject("order") { put(sort, if (sort == "title") "asc" else "desc") }
        putJsonArray("content_rating") { ratings(rating).forEach { add(JsonPrimitive(it)) } }
        putJsonArray("genres_ex") { HIDDEN.forEach { add(JsonPrimitive(it)) } }
        build()
    }

    // ---------- lists ----------

    override suspend fun popular(page: Int): PageResult {
        if (page == 1) runCatching {
            val home = initialData(baseUrl)
            val top = home.firstOrNull { key(it.first, "manga", "top") && (it.first.getOrNull(2)?.obj?.str("type") == "trending") }?.second
            val items = top.arr.mapNotNull { ref(it) }.filter { usable(it) }
            if (items.isNotEmpty()) return PageResult(items, true)
        }
        return bridgeList(params(page, "views_30d", "safe"), page)
    }

    override suspend fun latest(page: Int): PageResult {
        if (page == 1) runCatching {
            val home = initialData(baseUrl)
            val hot = home.firstOrNull { key(it.first, "manga", "list") && it.first.getOrNull(2)?.obj?.str("scope") == "hot" }?.second
            val items = hot["items"].arr.mapNotNull { ref(it) }.filter { usable(it) }
            if (items.isNotEmpty()) return PageResult(items, true)
        }
        return bridgeList(params(page, "chapter_updated_at", "safe"), page)
    }

    private val personIds = ConcurrentHashMap<String, Pair<String, Int>>()

    override suspend fun search(query: SearchQuery, page: Int): PageResult {
        val sort = query.selects["sort"] ?: if (query.text.isNotBlank()) "relevance" else "views_30d"
        // an author / artist search uses their id when we know it (or can look it up)
        var person: Pair<String, Int>? = null
        if (query.artist.isNotBlank()) {
            person = personIds[query.artist.lowercase()] ?: run {
                val hit = listOf("author", "artist").firstNotNullOfOrNull { type ->
                    runCatching {
                        Http.getJson("$baseUrl/api/v1/tags/search?type=$type&q=${query.artist.urlEncode()}&limit=10")["result"].arr
                            .firstOrNull { it.str("label").contains(query.artist, true) }?.let { type to it.int("id") }
                    }.getOrNull()
                }
                hit?.also { personIds[query.artist.lowercase()] = it }
            }
        }
        val p = params(page, sort, query.selects["adult"] ?: "safe") {
            val text = listOf(query.text, if (person == null) query.artist else "").filter { it.isNotBlank() }.joinToString(" ")
            if (text.isNotBlank()) put("keyword", text.trim())
            if (query.include.isNotEmpty()) {
                putJsonArray("genres_in") { query.include.mapNotNull { it.id.toIntOrNull() }.forEach { add(JsonPrimitive(it)) } }
                put("genres_mode", "and")
            }
            putJsonArray("genres_ex") { (HIDDEN + query.exclude.mapNotNull { it.id.toIntOrNull() }).forEach { add(JsonPrimitive(it)) } }
            query.selects["type"]?.takeIf { it.isNotEmpty() }?.let { t -> putJsonArray("types") { add(JsonPrimitive(t)) } }
            query.selects["status"]?.takeIf { it.isNotEmpty() }?.let { s -> putJsonArray("statuses") { add(JsonPrimitive(s)) } }
            query.selects["demo"]?.toIntOrNull()?.let { d -> putJsonArray("demographics") { add(JsonPrimitive(d)) } }
            person?.let { (type, pid) -> putJsonArray(if (type == "author") "authors" else "artists") { add(JsonPrimitive(pid)) } }
        }
        return bridgeList(p, page)
    }

    // ---------- details ----------

    private val pages = ConcurrentHashMap<String, List<Pair<List<JsonElement>, JsonElement>>>()

    private suspend fun titleData(hid: String) = pages[hid] ?: initialData("$baseUrl/title/$hid").also { pages[hid] = it }

    override suspend fun details(ref: MangaRef): MangaDetails {
        pages.remove(ref.id)
        val data = titleData(ref.id)
        val m = data.firstOrNull { key(it.first, "manga", "detail") }?.second ?: throw SourceException("Title not found on comix")
        val authors = m["authors"].arr; val artists = m["artists"].arr
        (authors.map { "author" to it } + artists.map { "artist" to it }).forEach { (t, p) -> personIds[p.str("title").lowercase()] = t to p.int("id") }
        val r = ref(m) ?: ref
        val tagNames = m["tags"].arr.filter { it.int("id") !in HIDDEN }.map { it.str("title") }
        return MangaDetails(
            ref = r.copy(cover = m["poster"].str("large").ifEmpty { r.cover }, tags = r.tags + tagNames),
            altTitles = m["altTitles"].strings(),
            description = m.str("synopsis").replace(Regex("[*_]{1,2}([^*_]+)[*_]{1,2}"), "$1").trim(),
            authors = authors.map { it.str("title") },
            artists = artists.map { it.str("title") },
            genres = (m["genres"].arr + m["demographics"].arr + m["formats"].arr).map { it.str("title") },
            tagGroups = if (tagNames.isNotEmpty()) listOf(TagGroup("Tags", tagNames.take(30))) else emptyList(),
            status = when (m.str("status")) { "releasing" -> "Ongoing"; "finished" -> "Completed"; "on_hiatus" -> "Hiatus"; "discontinued" -> "Cancelled"; "not_yet_released" -> "Upcoming"; else -> m.str("status") },
            type = m.str("type").replaceFirstChar { it.uppercase() },
            rating = m.float("ratedAvg").takeIf { it > 0 }?.let { "★ %.1f".format(it) }.orEmpty(),
            url = webUrl(ref),
            extra = listOfNotNull(m.str("year").takeIf { it.isNotEmpty() && it != "0" }?.let { "Year" to it }),
        )
    }

    override suspend fun related(ref: MangaRef, details: MangaDetails): List<MangaRef> {
        val data = titleData(ref.id)
        val rec = data.firstOrNull { key(it.first, "manga", "recommended") }?.second
        return rec["items"].arr.mapNotNull { ref(it) }.filter { usable(it) }
    }

    // ---------- chapters (encrypted: through the bridge, paced so the site stays happy) ----------

    private suspend fun chapterPage(hid: String, page: Int): List<JsonElement> {
        val p = buildJsonObject { put("page", page); put("limit", 100); putJsonObject("order") { put("number", "desc") } }
        val out = ComixBridge.call("manga,chapters", "$baseUrl/title/$hid", "window.__cxlib.chapters(${JsonPrimitive(hid)}, $p)")
        val j = Http.json.parseToJsonElement(out)
        if (j.int("status") == 403) throw SourceException("comix wants a quick check: open the site once")
        return j["items"].arr
    }

    private fun toChapters(raw: List<JsonElement>): List<Chapter> {
        val now = System.currentTimeMillis()
        return raw.groupBy { it.float("number") }.values.map { same ->
            same.sortedWith(compareByDescending<JsonElement> { it.bool("isOfficial") }.thenByDescending { it.int("votes") }.thenByDescending { it.int("id") }).first()
        }.map { c ->
            val n = c.float("number")
            val num = if (n % 1f == 0f) n.toInt().toString() else n.toString()
            Chapter(
                id = c.str("id"),
                name = "Chapter $num" + c.str("name").takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty(),
                number = n,
                date = relative(c.str("createdAtFormatted"), now),
                group = c["group"].str("name").ifEmpty { if (c.bool("isOfficial")) "Official" else "" },
            )
        }.sortedByDescending { it.number }
    }

    override fun chaptersFlow(ref: MangaRef): Flow<List<Chapter>> = flow {
        val all = mutableListOf<JsonElement>()
        var page = 1
        while (page <= 40) {
            if (page > 1) delay((2500..4800).random().toLong())
            val items = chapterPage(ref.id, page)
            all += items
            emit(toChapters(all))
            if (items.size < 100) break
            page++
        }
    }

    override suspend fun chapters(ref: MangaRef): List<Chapter> {
        var last = emptyList<Chapter>()
        chaptersFlow(ref).collect { last = it }
        return last
    }

    override suspend fun latestChapters(ref: MangaRef): List<Chapter> = toChapters(chapterPage(ref.id, 1))

    /**
     * The title's path part. Since 2026-11 comix's reader needs "<hid>-<slug>" (the title's name in the address,
     * "/title/zgk6j-unbeknownst-to-me.../11677446-chapter-43"); "/title/<hid>/..." is a 404 page.
     */
    private suspend fun titlePath(ref: MangaRef): String {
        val m = runCatching { titleData(ref.id).firstOrNull { key(it.first, "manga", "detail") }?.second }.getOrNull()
        val slug = m?.str("slug").orEmpty().ifEmpty {
            (m?.str("title").orEmpty().ifEmpty { ref.title }).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        }
        return if (slug.isNotEmpty()) "${ref.id}-$slug" else ref.id
    }

    override suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page> {
        val reader = "$baseUrl/title/${titlePath(ref)}/${chapter.id}-chapter-${chapter.number.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }}"
        val out = ComixBridge.call("chapters,detail", reader, "window.__cxlib.chapter(${chapter.id})")
        val list = Http.json.parseToJsonElement(out)["pages"].arr
        if (list.isEmpty()) throw SourceException("No pages came back for this chapter")
        return list.mapIndexed { i, p -> Page(i, p.str("url"), p.int("width"), p.int("height")) }
    }

    private fun relative(s: String, now: Long): Long {
        val m = Regex("(\\d+)\\s*(s|sec|m|min|h|hr|d|day|w|week|mo|mos|month|y|yr|yrs|year)s?", RegexOption.IGNORE_CASE).find(s) ?: return 0L
        val n = m.groupValues[1].toLong()
        val unit = when (m.groupValues[2].lowercase()) {
            "s", "sec" -> 1_000L; "m", "min" -> 60_000L; "h", "hr" -> 3_600_000L; "d", "day" -> 86_400_000L
            "w", "week" -> 604_800_000L; "mo", "mos", "month" -> 2_592_000_000L; else -> 31_536_000_000L
        }
        return now - n * unit
    }

    override fun webUrl(ref: MangaRef) = "$baseUrl/title/${ref.id}"
}
