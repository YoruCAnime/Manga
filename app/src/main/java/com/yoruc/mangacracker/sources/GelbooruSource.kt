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
import com.yoruc.mangacracker.core.int
import com.yoruc.mangacracker.core.str
import com.yoruc.mangacracker.core.urlEncode
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap

/** Gelbooru through its HTML pages (its JSON API needs a personal key). Spec: _dev_tools/specs/gelbooru.md */
object GelbooruSource : Source {
    override val id = "gelbooru"
    override val name = "Gelbooru"
    override val baseUrl = "https://gelbooru.com"
    override val blurb = "gelbooru.com · huge art archive"
    override val color = 0xFF2A88D8
    override val nsfw = true
    override val hasChapters = false
    override val coverRatio = 0.78f
    private const val PER_PAGE = 42

    /** Always excluded in the query (Gelbooru has no tag limit), and filtered again in the app. */
    private const val GUARD = "-loli -shota -lolicon -shotacon -toddlercon -child -female_child -male_child -aged_down"
    /** The tags Settings hides too (the site drops them, so pages stay full). */
    private fun optGuard() = (if (Safety.hideFuta) " -futanari" else "") + (if (Safety.hideFurry) " -furry" else "")

    init {
        Http.referers["gelbooru.com"] = "https://gelbooru.com/"
        Http.userAgents["gelbooru.com"] = "MangaCracker/1.0 (Android)"
        Http.rateLimits["gelbooru.com"] = 600L
    }

    override val selects = listOf(
        SelectDef("sort", "Sort", listOf("" to "Newest", "sort:score" to "Top score", "sort:random" to "Random")),
        SelectDef("rating", "Rating", listOf("" to "Any", "rating:general" to "General", "rating:sensitive" to "Sensitive", "rating:questionable" to "Questionable", "rating:explicit" to "Explicit")),
    )

    /** post id -> (thumb, tags) learnt from lists */
    private val seen = ConcurrentHashMap<String, Pair<String, List<String>>>()

    private suspend fun list(tags: String, page: Int): PageResult {
        val pid = (page - 1) * PER_PAGE
        if (pid > 20000) return PageResult(emptyList(), false)
        val doc = Http.getDoc("$baseUrl/index.php?page=post&s=list&tags=${"$tags $GUARD${optGuard()}".trim().urlEncode()}&pid=$pid")
        val items = doc.select("article.thumbnail-preview").mapNotNull { a ->
            val link = a.selectFirst("a[id^=p]") ?: return@mapNotNull null
            val pidStr = link.id().removePrefix("p")
            val img = a.selectFirst("img") ?: return@mapNotNull null
            val words = img.attr("title").trim().split(Regex("\\s+"))
            val tagList = words.filter { !it.startsWith("score:") && !it.startsWith("rating:") }
            val rating = words.firstOrNull { it.startsWith("rating:") }.orEmpty()
            val score = words.firstOrNull { it.startsWith("score:") }?.removePrefix("score:").orEmpty()
            val thumb = img.attr("abs:src")
            seen[pidStr] = thumb to tagList
            // the 250px thumbnail is small: use the 850px sample for crisp grid covers
            val md5 = Regex("thumbnail_([0-9a-f]{32})").find(thumb)?.groupValues?.get(1)
            val sample = if (md5 != null) thumb.replace("/thumbnails/", "/samples/").replace("thumbnail_", "sample_") else thumb
            MangaRef(id, pidStr, title(tagList), cover = sample, tags = tagList + rating, subtitle = "★ $score")
        }
        val next = doc.selectFirst("#paginator a[alt=next]") != null
        return PageResult(items, next)
    }

    private fun title(tags: List<String>): String {
        val t = tags.filter { it.isNotEmpty() && it !in setOf("1girl", "solo", "highres", "absurdres", "1boy", "commentary_request", "translated") }
        return t.take(3).joinToString(", ") { it.replace('_', ' ') }.ifEmpty { "Picture" }
    }

    private fun tagLine(q: SearchQuery): String = buildList {
        q.include.forEach { add(it.id) }
        q.exclude.forEach { add("-" + it.id) }
        if (q.artist.isNotBlank()) add(q.artist.trim().replace(' ', '_'))
        if (q.text.isNotBlank()) addAll(q.text.trim().split(Regex("\\s+")))
        q.selects["sort"]?.takeIf { it.isNotEmpty() }?.let { add(it) }
        q.selects["rating"]?.takeIf { it.isNotEmpty() }?.let { add(it) }
    }.joinToString(" ")

    override suspend fun popular(page: Int) = list("sort:score score:>=50", page)
    override suspend fun latest(page: Int) = list("", page)
    override suspend fun search(query: SearchQuery, page: Int) = list(tagLine(query), page)

    override suspend fun suggestTags(query: String): List<Tag> {
        if (query.trim().length < 2) return emptyList()
        val j = Http.getJson("$baseUrl/index.php?page=autocomplete2&term=${query.trim().replace(' ', '_').urlEncode()}&type=tag_query&limit=20")
        return j.arr.map {
            val group = when (it.str("category")) { "artist" -> "Artist"; "character" -> "Character"; "copyright" -> "Series"; "metadata" -> "Meta"; else -> "Tag" }
            Tag(it.str("value"), it.str("label"), group, it.int("post_count"))
        }
    }

    override suspend fun tags(): List<Tag> = DanbooruSource.tags()
    override fun tagQuery(name: String) = DanbooruSource.tagQuery(name)
    override fun artistQuery(name: String) = DanbooruSource.artistQuery(name)

    // ---------- details ----------

    private val docs = ConcurrentHashMap<String, Document>()
    private suspend fun page(id: String): Document = docs[id] ?: Http.getDoc("$baseUrl/index.php?page=post&s=view&id=$id").also { docs[id] = it }

    private fun tagsOf(doc: Document, type: String) =
        doc.select("#tag-list li.tag-type-$type").mapNotNull { li -> li.select("a").lastOrNull { it.attr("href").contains("tags=") }?.text() }

    override suspend fun details(ref: MangaRef): MangaDetails {
        val doc = page(ref.id)
        val sec = doc.selectFirst("section.image-container") ?: throw SourceException("Post not found")
        val all = sec.attr("data-tags").trim().split(' ')
        val sample = doc.selectFirst("#image")?.attr("abs:src").orEmpty()
        return MangaDetails(
            ref = ref.copy(cover = sample.ifEmpty { ref.cover }, tags = all + ("rating:" + sec.attr("data-rating"))),
            artists = tagsOf(doc, "artist"),
            genres = tagsOf(doc, "general").take(40),
            tagGroups = listOfNotNull(
                tagsOf(doc, "copyright").takeIf { it.isNotEmpty() }?.let { TagGroup("Series", it) },
                tagsOf(doc, "character").takeIf { it.isNotEmpty() }?.let { TagGroup("Character", it) },
            ),
            type = "Picture",
            rating = "★ " + sec.attr("data-score"),
            pageCount = 1,
            url = webUrl(ref),
            extra = listOf(
                "Size" to "${sec.attr("data-width")} × ${sec.attr("data-height")}",
                "Rating" to sec.attr("data-rating").replaceFirstChar { it.uppercase() },
            ) + (sec.attr("data-source").takeIf { it.startsWith("http") }?.let { listOf("Source" to it) } ?: emptyList()),
        )
    }

    override suspend fun chapters(ref: MangaRef) = listOf(Chapter("all", "Picture", 1f, pages = 1))

    override suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page> {
        val doc = page(ref.id)
        val sec = doc.selectFirst("section.image-container")
        val video = doc.selectFirst("video source[src]")?.attr("abs:src")
        if (video != null) throw SourceException("This post is a video - open it on the site")
        val url = doc.selectFirst("#image")?.attr("abs:src")?.replace("//samples/", "/samples/")?.replace("//images/", "/images/")
            ?: throw SourceException("No picture on this post")
        // animated posts: the sample is a still JPEG, so play the original file
        val original = doc.select("a[href*='/images/']").firstOrNull { it.text().contains("original", true) }?.attr("abs:href")
        val tags = sec?.attr("data-tags").orEmpty().split(' ')
        if (original != null && (original.substringAfterLast('.').lowercase() in setOf("gif", "webp", "apng") ||
                "animated" in tags || "animated_gif" in tags || "animated_png" in tags)
        ) return listOf(Page(0, original, sec?.attr("data-width")?.toIntOrNull() ?: 0, sec?.attr("data-height")?.toIntOrNull() ?: 0))
        val w = sec?.attr("data-large-width")?.toIntOrNull() ?: 0
        val h = sec?.attr("data-large-height")?.toIntOrNull() ?: 0
        return listOf(Page(0, url, w, h))
    }

    override suspend fun related(ref: MangaRef, details: MangaDetails): List<MangaRef> {
        val artist = details.artists.firstOrNull() ?: return emptyList()
        return runCatching { list(artist.replace(' ', '_') + " sort:score", 1).items }.getOrDefault(emptyList()).filter { it.key != ref.key }
    }

    override fun webUrl(ref: MangaRef) = "$baseUrl/index.php?page=post&s=view&id=${ref.id}"
}
