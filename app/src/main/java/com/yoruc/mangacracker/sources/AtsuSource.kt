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
import com.yoruc.mangacracker.core.long
import com.yoruc.mangacracker.core.str
import com.yoruc.mangacracker.core.strings
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.ConcurrentHashMap

/** atsu.moe (Atsumaru): JSON API + its Typesense search. Spec: _dev_tools/specs/atsu.md */
object AtsuSource : Source {
    override val id = "atsu"
    override val name = "Atsumaru"
    override val baseUrl = "https://atsu.moe"
    override val blurb = "atsu.moe · manga, manhwa, manhua"
    override val color = 0xFF8B7CF6
    override val nsfw = false
    private const val CDN = "https://cdn.atsu.moe"

    /** Minor-related tag ids, excluded from every query. */
    private val HIDDEN = listOf("28", "35", "1769", "1770", "3255", "6205", "6711", "3992", "4006", "5178", "6121", "5516",
        "6779", "2190", "4731", "4075", "4455", "3636", "6608")

    init { Http.rateLimits["atsu.moe"] = 450L }

    override val selects = listOf(
        SelectDef("sort", "Sort", listOf(
            "views:desc" to "Popular", "trending:desc" to "Trending", "dateAdded:desc" to "Recently added",
            "releaseDate:desc" to "Release date", "mbRating:desc" to "Top rated", "chapterCount:desc" to "Most chapters",
            "title:asc" to "Title", "" to "Best match",
        )),
        SelectDef("type", "Type", listOf("" to "Any", "Manga" to "Manga", "Manwha" to "Manhwa", "Manhua" to "Manhua", "OEL" to "OEL")),
        SelectDef("status", "Status", listOf("" to "Any", "Ongoing" to "Ongoing", "Completed" to "Completed", "Hiatus" to "Hiatus", "Canceled" to "Cancelled")),
        SelectDef("adult", "18+ titles", listOf("safe" to "Hide", "mix" to "Show too", "only" to "Only 18+")),
    )

    // ---------- images ----------

    private fun img(p: String?): String {
        if (p.isNullOrEmpty()) return ""
        if (p.startsWith("http")) return p
        if (p.startsWith("//")) return "https:$p"
        val rel = p.removePrefix("$CDN/static/").let { if (it.startsWith("/static/")) it.drop(8) else it.removePrefix("/") }
        return "$CDN/static/$rel"
    }

    /** Grid cover: the 400x600 picture, as its webp twin (decodes on every Android). */
    private fun gridCover(large: String?, fallback: String?): String =
        if (!large.isNullOrEmpty()) img(large.replace(".avif", ".webp")) else img(fallback)

    private fun row(j: JsonElement): MangaRef? {
        if (j.str("medium") == "Novel") return null
        return MangaRef(
            source = id, id = j.str("id"), title = j.str("title"),
            cover = gridCover(j.str("largeImage"), j.str("image")),
            tags = if (j.bool("isAdult")) listOf("adult") else emptyList(),
            subtitle = typeName(j.str("type")),
        )
    }

    private fun typeName(t: String) = if (t == "Manwha") "Manhwa" else t

    // ---------- listings ----------

    private suspend fun home(rowName: String, page: Int, extra: String = ""): PageResult {
        val limit = 40
        val url = "$baseUrl/api/home2/$rowName?offset=${(page - 1) * limit}&limit=$limit&types=Manga,Manwha,Manhua,OEL&mediums=Comic" +
            "&excludedTags=${HIDDEN.joinToString(",")}$extra"
        val items = Http.getJson(url)["items"].arr
        return PageResult(items.mapNotNull { row(it) }, items.size >= limit)
    }

    override suspend fun popular(page: Int) = home("popular", page, "&timeframe=weekly")
    override suspend fun latest(page: Int) = home("recentlyUpdated", page)

    override suspend fun search(query: SearchQuery, page: Int): PageResult {
        val slug = if (query.artist.isNotBlank()) slugs[query.artist.lowercase()] else null
        if (slug != null && query.text.isBlank() && query.include.isEmpty()) return authorWorks(slug, page)

        val text = listOf(query.text, if (slug == null) query.artist else "").filter { it.isNotBlank() }.joinToString(" ").trim()
        val f = mutableListOf("hidden:!=true", "medium:!=[`Novel`]")
        when (query.selects["adult"] ?: "safe") {
            "safe" -> { f += "isAdult:=false"; if (text.isEmpty()) f += "mbContentRating:!=[`Pornographic`]" }
            "only" -> f += "isAdult:=true"
        }
        query.include.forEach { t ->
            val (kind, v) = split(t.id)
            f += "${if (kind == "g") "genreIds" else "tagIds"}:=`$v`"
        }
        val exG = query.exclude.map { split(it.id) }.filter { it.first == "g" }.map { it.second }
        val exT = query.exclude.map { split(it.id) }.filter { it.first != "g" }.map { it.second } + HIDDEN
        if (exG.isNotEmpty()) f += "genreIds:!=[${exG.joinToString(",") { "`$it`" }}]"
        f += "tagIds:!=[${exT.joinToString(",") { "`$it`" }}]"
        query.selects["type"]?.takeIf { it.isNotEmpty() }?.let { f += "type:=[`$it`]" }
        query.selects["status"]?.takeIf { it.isNotEmpty() }?.let { f += "status:=[`$it`]" }
        val sort = query.selects["sort"] ?: if (text.isEmpty()) "views:desc" else ""
        if (sort == "views:desc") f += "views:>0"
        if (sort == "mbRating:desc") f += "mbRating:>0"

        val perPage = 40
        val b = "$baseUrl/collections/manga/documents/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", text.ifEmpty { "*" })
            .addQueryParameter("query_by", "title,englishTitle,otherNames,authors,acronyms")
            .addQueryParameter("query_by_weights", "4,3,2,2,1")
            .addQueryParameter("num_typos", "4,3,2,1,0")
            .addQueryParameter("prefix", "true,true,true,true,false")
            .addQueryParameter("include_fields", "id,title,poster,posterMedium,type,medium,isAdult,status,year")
            .addQueryParameter("filter_by", f.joinToString(" && "))
            .addQueryParameter("page", page.toString())
            .addQueryParameter("per_page", perPage.toString())
        if (text.isNotEmpty()) b.addQueryParameter("infix", "off,off,fallback,off,off")
        if (sort.isNotEmpty()) b.addQueryParameter("sort_by", sort)
        val r = Http.getJson(b.build().toString())
        val hits = r["hits"].arr.mapNotNull { h ->
            val d = h["document"] ?: return@mapNotNull null
            if (d.str("medium") == "Novel") return@mapNotNull null
            // the medium avif has no grid-size jpg; its 400x600 sibling is "-large"
            val poster = d.str("poster")
            val large = d.str("posterMedium").replace("-medium.avif", "-large.webp").ifEmpty { null }
            MangaRef(id, d.str("id"), d.str("title"), cover = if (large != null) img(large) else img(poster),
                tags = if (d.bool("isAdult")) listOf("adult") else emptyList(),
                subtitle = listOf(typeName(d.str("type")), d.str("year").takeIf { it != "0" }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "))
        }
        return PageResult(hits, page * perPage < r.int("found"))
    }

    private fun split(id: String): Pair<String, String> = if (id.length > 2 && id[1] == ':') id.substring(0, 1) to id.substring(2) else "g" to id

    private suspend fun authorWorks(slug: String, page: Int): PageResult {
        val items = Http.getJson("$baseUrl/api/browse/author?authorSlug=$slug&page=${page - 1}")["items"].arr
        return PageResult(items.mapNotNull { row(it) }, items.isNotEmpty())
    }

    // ---------- tags ----------

    @Volatile private var tagCache: List<Tag>? = null

    override suspend fun tags(): List<Tag> {
        tagCache?.let { return it }
        val j = Http.getJson("$baseUrl/api/explore/availableFilters")
        val genres = j["genres"].arr.map { Tag("g:" + it.str("id"), it.str("name"), "Genre") }
        val tags = j["tags"].arr.filter { it.str("id") !in HIDDEN && (it.int("safeCount") + it.int("adultCount")) > 0 }
            .sortedWith(compareByDescending<JsonElement> { it.str("group") == "Audience Demographics" }.thenByDescending { it.int("safeCount") + it.int("adultCount") })
            .map { Tag("t:" + it.str("id"), it.str("name"), it.str("group")) }
        return (genres + tags).also { tagCache = it }
    }

    override fun tagQuery(name: String): SearchQuery {
        val t = tagCache?.firstOrNull { it.label.equals(name, true) } ?: Tag("t:?", name)
        return if (t.id == "t:?") SearchQuery(text = name) else SearchQuery(include = listOf(t))
    }

    // ---------- details ----------

    private val pages = ConcurrentHashMap<String, JsonElement>()
    /** author name (lower case) -> slug, learnt from details pages */
    private val slugs = ConcurrentHashMap<String, String>()

    private suspend fun mangaPage(mangaId: String): JsonElement {
        pages[mangaId]?.let { return it }
        val p = Http.getJson("$baseUrl/api/manga/page?id=$mangaId")["mangaPage"] ?: throw SourceException("Title not found on atsu")
        pages[mangaId] = p
        return p
    }

    override suspend fun details(ref: MangaRef): MangaDetails {
        pages.remove(ref.id)
        val p = mangaPage(ref.id)
        val people = p["authors"].arr
        people.forEach { slugs[it.str("name").lowercase()] = it.str("slug") }
        val tags = p["tags"].arr.filter { !it.bool("isSpoiler") && it.str("id") !in HIDDEN }.map { it.str("name") }
        val poster = p["poster"]
        return MangaDetails(
            ref = MangaRef(id, ref.id, p.str("title"), cover = img(poster.str("image")).ifEmpty { ref.cover },
                tags = p["genres"].arr.map { it.str("name") } + tags + (if (p.bool("isAdult")) listOf("adult") else emptyList())),
            altTitles = (listOf(p.str("englishTitle")) + p["otherNames"].strings()).filter { it.isNotBlank() }.distinct(),
            description = p.str("synopsis"),
            authors = people.filter { it.str("type") != "Artist" }.map { it.str("name") }.distinct(),
            artists = people.filter { it.str("type") == "Artist" }.map { it.str("name") }.distinct(),
            genres = p["genres"].arr.map { it.str("name") },
            tagGroups = if (tags.isNotEmpty()) listOf(TagGroup("Tags", tags.take(30))) else emptyList(),
            status = p.str("status"),
            type = typeName(p.str("type")),
            url = webUrl(ref),
            rating = p.float("avgRating").takeIf { it > 0 }?.let { "★ %.1f".format(it) }.orEmpty(),
        )
    }

    override suspend fun chapters(ref: MangaRef): List<Chapter> {
        val p = runCatching { mangaPage(ref.id) }.getOrNull()
        val groups = p?.get("scanlators").arr.associate { it.str("id") to (it.str("name") to it.int("score")) }
        val all = Http.getJson("$baseUrl/api/manga/allChapters?mangaId=${ref.id}")["chapters"].arr.map { c ->
            val g = groups[c.str("scanlationMangaId")]
            Triple(
                Chapter(
                    id = c.str("id"),
                    name = c.str("title").ifEmpty { "Chapter " + c.float("number").let { n -> if (n % 1f == 0f) n.toInt().toString() else n.toString() } },
                    number = c.float("number"),
                    date = c.long("createdAt"),
                    group = g?.first.orEmpty(),
                    pages = c.int("pageCount"),
                ),
                g?.second ?: 0, c.long("createdAt")
            )
        }
        // one upload per chapter number: the best-voted group, then the newest
        return all.groupBy { it.first.number }.values
            .map { same -> same.sortedWith(compareByDescending<Triple<Chapter, Int, Long>> { it.second }.thenByDescending { it.third }).first().first }
            .sortedWith(compareByDescending<Chapter> { it.number }.thenByDescending { it.date })
    }

    override suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page> {
        val r = Http.getJson("$baseUrl/api/read/chapter?mangaId=${ref.id}&chapterId=${chapter.id}")["readChapter"]
            ?: throw SourceException("Chapter not found")
        return r["pages"].arr.mapIndexed { i, pg -> Page(i, img(pg.str("image")), pg.int("width"), pg.int("height")) }
    }

    override suspend fun related(ref: MangaRef, details: MangaDetails): List<MangaRef> {
        val p = runCatching { mangaPage(ref.id) }.getOrNull() ?: return emptyList()
        val adultOk = "adult" in details.ref.tags
        return (p["recommendations"].arr + p["similarManga"].arr).mapNotNull { row(it) }
            .filter { adultOk || "adult" !in it.tags }.distinctBy { it.key }
    }

    override fun webUrl(ref: MangaRef) = "$baseUrl/manga/${ref.id}"
}
