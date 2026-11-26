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
import com.yoruc.mangacracker.core.bool
import com.yoruc.mangacracker.core.float
import com.yoruc.mangacracker.core.get
import com.yoruc.mangacracker.core.int
import com.yoruc.mangacracker.core.isoToMillis
import com.yoruc.mangacracker.core.long
import com.yoruc.mangacracker.core.obj
import com.yoruc.mangacracker.core.str
import com.yoruc.mangacracker.core.text
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * mangadex.org through its public API (api.mangadex.org): English chapters, pages from the MangaDex@Home server the API
 * names for each chapter. Spec: _dev_tools/specs/mangadex.md
 */
object MangaDexSource : Source {
    override val id = "mangadex"
    override val name = "MangaDex"
    override val baseUrl = "https://mangadex.org"
    override val blurb = "mangadex.org · manga, manhwa, manhua · scanlations"
    override val color = 0xFFFF6740
    override val nsfw = true
    private const val API = "https://api.mangadex.org"
    private const val COVERS = "https://uploads.mangadex.org/covers"
    private const val LIMIT = 32
    /** The API serves nothing past offset + limit = 10 000. */
    private const val MAX_OFFSET = 10_000

    /** Minor-related tag ids ("Loli", "Shota"), excluded from every query and never offered. */
    private val HIDDEN = listOf("2d1f5d56-a1e5-4d0d-a961-2193588b08ec", "ddefd648-5140-4e5f-ba18-4eca4071d19b")
    private val RATINGS = listOf("safe", "suggestive", "erotica", "pornographic")
    private val ADULT = setOf("erotica", "pornographic")

    init {
        // the API and the cover host answer a browser-like agent with a bot check page: they want the app's own name
        Http.userAgents["mangadex.org"] = "MangaCracker/1.0"
        // the API allows about 5 requests a second, and 40 a minute for /at-home/server
        Http.rateLimits["api.mangadex.org"] = 250L
        Http.rateLimits["api.mangadex.org/at-home"] = 1600L
    }

    override val selects = listOf(
        SelectDef("sort", "Sort", listOf(
            "followedCount" to "Popular", "latestUploadedChapter" to "Latest update", "rating" to "Top rated",
            "createdAt" to "Recently added", "year" to "Release year", "title" to "Title", "relevance" to "Best match",
        )),
        SelectDef("type", "Type", listOf("" to "Any", "ja" to "Manga", "ko" to "Manhwa", "zh,zh-hk" to "Manhua", "en" to "English original")),
        SelectDef("status", "Status", listOf("" to "Any", "ongoing" to "Ongoing", "completed" to "Completed", "hiatus" to "Hiatus", "cancelled" to "Cancelled")),
        SelectDef("demographic", "Demographic", listOf("" to "Any", "shounen" to "Shounen", "shoujo" to "Shoujo", "seinen" to "Seinen", "josei" to "Josei", "none" to "None")),
        SelectDef("content", "Content rating", listOf(
            "" to "All", "safe" to "Safe only", "safe,suggestive" to "Safe + suggestive", "erotica,pornographic" to "Erotica + 18+",
            "pornographic" to "18+ only",
        )),
    )

    // ---------- API ----------

    private suspend fun api(url: String): JsonElement {
        val (code, body) = Http.getRaw(url, mapOf("Accept" to "application/json"))
        if (code == 404) throw SourceException("Not found on MangaDex")
        if (code == 429) throw SourceException("MangaDex is busy (too many requests): try again in a moment")
        if (code !in 200..299) throw SourceException("HTTP $code from MangaDex")
        return Http.json.parseToJsonElement(body)
    }

    private fun HttpUrl.Builder.all(name: String, values: Collection<String>): HttpUrl.Builder {
        values.forEach { addQueryParameter(name, it) }
        return this
    }

    /** A localised text ({"en": ..., "ja-ro": ...}): English when there is one, else the first. */
    private fun local(j: JsonElement?): String {
        val o = j.obj ?: return ""
        return (o["en"] ?: o.values.firstOrNull()).text
    }

    private fun typeName(lang: String) = when (lang) {
        "ja" -> "Manga"; "ko" -> "Manhwa"; "zh", "zh-hk" -> "Manhua"; else -> ""
    }

    private fun coverFile(m: JsonElement): String =
        m["relationships"].arr.firstOrNull { it.str("type") == "cover_art" }?.get("attributes").str("fileName")

    /** Covers come in the upload's size, or as 256 / 512 px wide jpg thumbnails. */
    private fun cover(mangaId: String, file: String, size: Int): String = if (file.isEmpty()) "" else "$COVERS/$mangaId/$file.$size.jpg"

    /** Tag names, plus the rating when it is an 18+ one (for the safety filter). */
    private fun tagNames(a: JsonElement?): List<String> =
        a["tags"].arr.map { t -> local(t["attributes"]["name"]).also { if (it.isNotEmpty()) tagIds[it.lowercase()] = t.str("id") } }.filter { it.isNotEmpty() } +
            listOf(a.str("contentRating")).filter { it in ADULT }

    private fun row(m: JsonElement): MangaRef? {
        val a = m["attributes"]
        val mangaId = m.str("id").ifEmpty { return null }
        return MangaRef(
            source = id, id = mangaId, title = local(a["title"]),
            cover = cover(mangaId, coverFile(m), 256),
            tags = tagNames(a),
            subtitle = listOf(typeName(a.str("originalLanguage")), a.str("year").takeIf { it != "0" }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "),
        )
    }

    // ---------- listings ----------

    private suspend fun list(page: Int, ratings: List<String> = RATINGS, more: HttpUrl.Builder.() -> Unit): PageResult {
        val offset = (page - 1) * LIMIT
        if (offset + LIMIT > MAX_OFFSET) return PageResult(emptyList(), false)
        val b = "$API/manga".toHttpUrl().newBuilder()
            .addQueryParameter("limit", LIMIT.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("includes[]", "cover_art")
            // only titles that have English chapters to read here
            .addQueryParameter("availableTranslatedLanguage[]", "en")
            .addQueryParameter("hasAvailableChapters", "true")
            .all("contentRating[]", ratings)
            .addQueryParameter("excludedTagsMode", "OR")
        b.more()
        val r = api(b.build().toString())
        val total = minOf(r.int("total"), MAX_OFFSET)
        return PageResult(r["data"].arr.mapNotNull { row(it) }, offset + LIMIT < total)
    }

    override suspend fun popular(page: Int) = list(page) { all("excludedTags[]", HIDDEN); addQueryParameter("order[followedCount]", "desc") }
    override suspend fun latest(page: Int) = list(page) { all("excludedTags[]", HIDDEN); addQueryParameter("order[latestUploadedChapter]", "desc") }

    override suspend fun search(query: SearchQuery, page: Int): PageResult {
        // a name tapped on a details page (or typed): the API searches people by id
        val person = if (query.artist.isNotBlank()) authorId(query.artist.trim()) else null
        val text = listOf(query.text, if (person == null) query.artist else "").filter { it.isNotBlank() }.joinToString(" ").trim()
        if (query.include.isNotEmpty() || query.exclude.isNotEmpty()) runCatching { tags() }
        val inc = query.include.mapNotNull { resolve(it) }.filter { it !in HIDDEN }
        val exc = (query.exclude.mapNotNull { resolve(it) } + HIDDEN).distinct()
        val sort = (query.selects["sort"] ?: if (text.isEmpty()) "followedCount" else "relevance")
            .let { if (it == "relevance" && text.isEmpty()) "followedCount" else it }
        val ratings = query.selects["content"].orEmpty().split(',').filter { it in RATINGS }.ifEmpty { RATINGS }
        return list(page, ratings) {
            if (text.isNotEmpty()) addQueryParameter("title", text)
            if (person != null) addQueryParameter("authorOrArtist", person)
            // several tags = all of them
            all("includedTags[]", inc)
            if (inc.size > 1) addQueryParameter("includedTagsMode", "AND")
            all("excludedTags[]", exc)
            all("originalLanguage[]", query.selects["type"].orEmpty().split(',').filter { it.isNotEmpty() })
            query.selects["status"]?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("status[]", it) }
            query.selects["demographic"]?.takeIf { it.isNotEmpty() }?.let { addQueryParameter("publicationDemographic[]", it) }
            addQueryParameter("order[$sort]", if (sort == "title") "asc" else "desc")
        }
    }

    /** author / artist name (lower case) -> id, learnt from details pages and from the API's name search */
    private val people = ConcurrentHashMap<String, String>()

    private suspend fun authorId(name: String): String? {
        people[name.lowercase()]?.let { return it }
        val url = "$API/author".toHttpUrl().newBuilder().addQueryParameter("name", name).addQueryParameter("limit", "10").build().toString()
        val found = runCatching { api(url)["data"].arr }.getOrNull() ?: return null
        val hit = found.firstOrNull { it["attributes"].str("name").equals(name, true) } ?: found.firstOrNull() ?: return null
        return hit.str("id").ifEmpty { null }?.also { people[name.lowercase()] = it }
    }

    // ---------- tags ----------

    @Volatile private var tagCache: List<Tag>? = null
    /** tag name (lower case) -> id, from the tag list and from every title seen */
    private val tagIds = ConcurrentHashMap<String, String>()
    private val GROUPS = listOf("genre", "theme", "format", "content")

    override suspend fun tags(): List<Tag> {
        tagCache?.let { return it }
        val list = api("$API/manga/tag")["data"].arr.filter { it.str("id") !in HIDDEN }
            .sortedWith(compareBy<JsonElement> { GROUPS.indexOf(it["attributes"].str("group")).let { i -> if (i < 0) GROUPS.size else i } }
                .thenBy { local(it["attributes"]["name"]).lowercase() })
            .mapNotNull { t ->
                val label = local(t["attributes"]["name"]).ifEmpty { return@mapNotNull null }
                tagIds[label.lowercase()] = t.str("id")
                Tag(t.str("id"), label, t["attributes"].str("group").replaceFirstChar { it.uppercase() })
            }
        return Safety.cleanTags(list).also { if (it.isNotEmpty()) tagCache = it }
    }

    override fun tagQuery(name: String): SearchQuery {
        val tagId = tagIds[name.lowercase()] ?: return SearchQuery(text = name)
        return SearchQuery(include = listOf(Tag(tagId, name)))
    }

    /** A tag from the picker or tagQuery (its id), or just a name: -> the id. */
    private fun resolve(t: Tag): String? =
        if (t.id.length == 36 && t.id.count { it == '-' } == 4) t.id else tagIds[t.label.lowercase()] ?: tagIds[t.id.lowercase()]

    // ---------- details ----------

    /** manga id -> its API entry (details, the chapter order and related share it) */
    private val titles = ConcurrentHashMap<String, JsonElement>()

    private suspend fun entry(mangaId: String, fresh: Boolean = false): JsonElement {
        if (!fresh) titles[mangaId]?.let { return it }
        val url = "$API/manga/$mangaId".toHttpUrl().newBuilder().all("includes[]", listOf("cover_art", "author", "artist")).build().toString()
        val m = api(url)["data"] ?: throw SourceException("Title not found on MangaDex")
        if (titles.size > 60) titles.clear()
        titles[mangaId] = m
        return m
    }

    /** The story without the site's markdown: links keep their words, the "---" footer (links, notes) is dropped. */
    private fun plain(md: String): String =
        md.replace("\r", "").split(Regex("\n-{3,}\\s*(\n|$)")).first()
            .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2")
            .replace(Regex("\n{3,}"), "\n\n").trim()

    override suspend fun details(ref: MangaRef): MangaDetails {
        val m = entry(ref.id, fresh = true)
        val a = m["attributes"]
        val rels = m["relationships"].arr
        fun names(type: String): List<String> = rels.filter { it.str("type") == type }.mapNotNull { r ->
            r["attributes"].str("name").ifEmpty { null }?.also { people[it.lowercase()] = r.str("id") }
        }.distinct()
        fun group(g: String): List<String> = a["tags"].arr.filter { it["attributes"].str("group") == g && it.str("id") !in HIDDEN }
            .map { local(it["attributes"]["name"]) }.filter { it.isNotEmpty() }

        val title = local(a["title"]).ifEmpty { ref.title }
        val lang = a.str("originalLanguage")
        // English names, the romanised ones and the original spelling; the dozens of other translations are left out
        val alt = a["altTitles"].arr.mapNotNull { it.obj?.entries?.firstOrNull() }
            .filter { (k, _) -> k == "en" || k.endsWith("-ro") || k == lang }.map { it.value.text.trim() }
            .filter { it.isNotEmpty() && !it.equals(title, true) }.distinct().take(12)
        val rating = a.str("contentRating")
        // rating and follows are a second call; the page still opens without them
        val stats = runCatching { api("$API/statistics/manga/${ref.id}")["statistics"][ref.id] }.getOrNull()
        val d = MangaDetails(
            ref = MangaRef(id, ref.id, title, cover = cover(ref.id, coverFile(m), 512).ifEmpty { ref.cover }, tags = tagNames(a), subtitle = ref.subtitle),
            altTitles = alt,
            description = plain(local(a["description"])),
            authors = names("author"),
            artists = names("artist"),
            genres = group("genre"),
            tagGroups = listOfNotNull(
                group("theme").takeIf { it.isNotEmpty() }?.let { TagGroup("Themes", it) },
                group("format").takeIf { it.isNotEmpty() }?.let { TagGroup("Format", it) },
                group("content").takeIf { it.isNotEmpty() }?.let { TagGroup("Content", it) },
            ),
            status = a.str("status").replaceFirstChar { it.uppercase() },
            type = typeName(lang),
            url = webUrl(ref),
            rating = stats?.get("rating").float("bayesian").takeIf { it > 0 }?.let { "★ %.1f".format(it) }.orEmpty(),
            extra = listOfNotNull(
                a.str("year").takeIf { it.isNotEmpty() && it != "0" }?.let { "Year" to it },
                a.str("publicationDemographic").takeIf { it.isNotEmpty() }?.let { "Demographic" to it.replaceFirstChar { c -> c.uppercase() } },
                rating.takeIf { it.isNotEmpty() }?.let { "Content rating" to it.replaceFirstChar { c -> c.uppercase() } },
                stats.long("follows").takeIf { it > 0 }?.let { "Follows" to "%,d".format(it) },
            ),
        )
        if (Safety.blocked(d)) throw SourceException("This title is hidden")
        return d
    }

    // ---------- chapters ----------

    /** One upload of a chapter (several groups may translate the same one). */
    private class Upload(val chapter: Chapter, val volume: String, val numbered: Boolean)

    private suspend fun feed(mangaId: String, limit: Int, offset: Int, newestFirst: Boolean): JsonElement {
        val b = "$API/manga/$mangaId/feed".toHttpUrl().newBuilder()
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("translatedLanguage[]", "en")
            .addQueryParameter("includes[]", "scanlation_group")
            .all("contentRating[]", RATINGS)
            // not the chapters that only link to a publisher's site, are empty, or are not out yet
            .addQueryParameter("includeExternalUrl", "0")
            .addQueryParameter("includeEmptyPages", "0")
            .addQueryParameter("includeFuturePublishAt", "0")
        if (newestFirst) b.addQueryParameter("order[readableAt]", "desc")
        else b.addQueryParameter("order[volume]", "desc").addQueryParameter("order[chapter]", "desc")
        return api(b.build().toString())
    }

    private fun uploads(data: List<JsonElement>): List<Upload> = data.mapNotNull { c ->
        val a = c["attributes"]
        val pages = a.int("pages")
        // an external chapter has no pages here (the switches above already drop them; this is the second check)
        if (pages == 0) return@mapNotNull null
        val vol = a.str("volume")
        val num = a.str("chapter")
        val head = listOf(if (vol.isNotEmpty()) "Vol. $vol" else "", if (num.isNotEmpty()) "Chapter $num" else "").filter { it.isNotEmpty() }.joinToString(" ")
        val groups = c["relationships"].arr.filter { it.str("type") == "scanlation_group" }.map { it["attributes"].str("name") }.filter { it.isNotEmpty() }
        Upload(
            Chapter(
                id = c.str("id"),
                name = listOf(head, a.str("title").trim()).filter { it.isNotEmpty() }.joinToString(" - ").ifEmpty { "Oneshot" },
                number = num.toFloatOrNull() ?: -1f,
                date = isoToMillis(a.str("readableAt").ifEmpty { a.str("publishAt") }),
                group = groups.joinToString(" & "),
                pages = pages,
            ),
            vol, num.toFloatOrNull() != null,
        )
    }

    /** One upload per chapter number (the group with the most chapters of this title, then the newest), newest first. */
    private fun pick(ups: List<Upload>, byVolume: Boolean): List<Chapter> {
        val perGroup = ups.groupingBy { it.chapter.group }.eachCount()
        val numbered = ups.filter { it.numbered }.groupBy { (if (byVolume) it.volume + "|" else "") + it.chapter.number }.values
            .map { same -> same.sortedWith(compareByDescending<Upload> { perGroup[it.chapter.group] ?: 0 }.thenByDescending { it.chapter.date }).first() }
        return (numbered + ups.filter { !it.numbered })
            // a title whose numbers restart in every volume is ordered by volume first (no volume yet = the newest)
            .sortedWith(compareByDescending<Upload> { if (byVolume) it.volume.toFloatOrNull() ?: Float.MAX_VALUE else 0f }
                .thenByDescending { it.chapter.number }.thenByDescending { it.chapter.date })
            .map { it.chapter }
    }

    private suspend fun byVolume(mangaId: String): Boolean =
        runCatching { entry(mangaId)["attributes"].bool("chapterNumbersResetOnNewVolume") }.getOrDefault(false)

    override suspend fun chapters(ref: MangaRef): List<Chapter> {
        val rows = ArrayList<JsonElement>()
        var offset = 0
        while (offset < MAX_OFFSET) {
            val r = feed(ref.id, 500, offset, newestFirst = false)
            val data = r["data"].arr
            rows.addAll(data)
            offset += 500
            if (data.isEmpty() || offset >= r.int("total")) break
        }
        return pick(uploads(rows), byVolume(ref.id))
    }

    /** The 60 newest uploads are plenty for the update check. */
    override suspend fun latestChapters(ref: MangaRef): List<Chapter> =
        pick(uploads(feed(ref.id, 60, 0, newestFirst = true)["data"].arr), byVolume(ref.id))

    override suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page> {
        // the API names a MangaDex@Home server for this chapter; its addresses stay good for some minutes
        val r = api("$API/at-home/server/${chapter.id}")
        val base = r.str("baseUrl")
        val hash = r["chapter"].str("hash")
        val files = r["chapter"]["data"].arr.map { it.text }.filter { it.isNotEmpty() }
        if (base.isEmpty() || hash.isEmpty() || files.isEmpty()) throw SourceException("No pages in this chapter")
        return files.mapIndexed { i, f -> Page(i, "$base/data/$hash/$f") }
    }

    /** The titles the site links to this one (sequels, spin-offs, the coloured version, ...). */
    override suspend fun related(ref: MangaRef, details: MangaDetails): List<MangaRef> {
        val m = runCatching { entry(ref.id) }.getOrNull() ?: return emptyList()
        val ids = m["relationships"].arr.filter { it.str("type") == "manga" }.map { it.str("id") }.filter { it.isNotEmpty() }.distinct().take(20)
        if (ids.isEmpty()) return emptyList()
        val url = "$API/manga".toHttpUrl().newBuilder()
            .addQueryParameter("limit", ids.size.toString())
            .addQueryParameter("includes[]", "cover_art")
            .all("ids[]", ids)
            .all("contentRating[]", RATINGS)
            .all("excludedTags[]", HIDDEN)
            .addQueryParameter("excludedTagsMode", "OR")
            .build().toString()
        // an all-ages title does not lead to its 18+ doujinshi
        val adultOk = details.ref.tags.any { it in ADULT }
        return api(url)["data"].arr.mapNotNull { row(it) }.filter { r -> adultOk || r.tags.none { it in ADULT } }
    }

    override fun webUrl(ref: MangaRef) = "$baseUrl/title/${ref.id}"
}
