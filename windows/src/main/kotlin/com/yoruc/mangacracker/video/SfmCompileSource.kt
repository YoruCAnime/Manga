package com.yoruc.mangacracker.video

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.core.arr
import com.yoruc.mangacracker.core.get
import com.yoruc.mangacracker.core.int
import com.yoruc.mangacracker.core.str
import com.yoruc.mangacracker.core.text
import com.yoruc.mangacracker.core.urlEncode
import kotlinx.serialization.json.JsonElement
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

/**
 * sfmcompile.club: SFM / Blender 3D clips on WordPress with its JSON API open (spec: _dev_tools/specs/sfmcompile.md).
 * One direct MP4 per post. Categories = game / franchise and character (used as the series); tags = acts, length
 * marks, artists. Popular is only sortable on the HTML pages (8 a page).
 */
object SfmCompileSource : VideoSource {
    override val id = "sfmcompile"
    override val name = "SFM Compile"
    override val baseUrl = "https://sfmcompile.club"
    override val blurb = "sfmcompile.club · SFM 3D clips · MP4"
    override val color = 0xFF00DBA3L

    private val pace = Pace(1000)
    private const val FIELDS = "id,date,link,title,content,categories,tags,yoast_head_json.og_image"

    private class Cat(val name: String, val slug: String, val parent: Int)
    private val tagSlugs = ConcurrentHashMap<Int, String>()
    private val cats = ConcurrentHashMap<Int, Cat>()
    /** post id -> MP4, from list pages (saves a call in details()). */
    private val videos = ConcurrentHashMap<String, String>()
    @Volatile private var genreCache: List<Tag>? = null

    init {
        // pictures and clips come from the site itself (Sucuri proxy): space a grid of pictures a little
        Http.rateLimits["sfmcompile.club/wp-content/uploads"] = 50L
    }

    private suspend fun api(path: String): Pair<JsonElement, Int> = pace.run {
        val r = Http.client.newCall(Http.request("$baseUrl/wp-json/wp/v2/$path", mapOf("Accept" to "application/json"))).execute()
        r.use {
            if (it.code == 400) return@run Http.json.parseToJsonElement("[]") to 0 // past the last page
            if (!it.isSuccessful) throw SourceException("SFM Compile answered ${it.code}")
            Http.json.parseToJsonElement(it.body!!.string()) to (it.header("X-WP-TotalPages")?.toIntOrNull() ?: 1)
        }
    }

    /** Fills the id -> slug / category caches for ids not seen yet (one small call each). */
    private suspend fun lookup(tagIds: Collection<Int>, catIds: Collection<Int>) {
        val t = tagIds.filter { !tagSlugs.containsKey(it) }.distinct()
        if (t.isNotEmpty()) runCatching {
            api("tags?per_page=100&_fields=id,slug&include=${t.take(100).joinToString(",")}").first.arr.forEach { tagSlugs[it.int("id")] = it.str("slug") }
        }
        val c = catIds.filter { !cats.containsKey(it) }.distinct()
        if (c.isNotEmpty()) runCatching {
            api("categories?per_page=100&_fields=id,name,slug,parent&include=${c.take(100).joinToString(",")}").first.arr.forEach {
                cats[it.int("id")] = Cat(Jsoup.parse(it.str("name")).text(), it.str("slug"), it.int("parent"))
            }
        }
    }

    /** The most specific category (a character before its game); "Others" is no series. */
    private fun seriesOf(catIds: List<Int>): Int? {
        val known = catIds.filter { cats[it]?.slug?.startsWith("others") == false }
        return known.firstOrNull { c -> known.none { cats[it]?.parent == c } && cats[c]?.parent != 0 } ?: known.firstOrNull()
    }

    /** "30secs" -> "30s+", "2mins" -> "2 min+" (the site's length tags). */
    private fun lengthOf(tags: List<String>): String = tags.firstNotNullOfOrNull { t ->
        Regex("^(\\d+)(secs?|mins?)$").find(t)?.let { m -> m.groupValues[1] + (if (m.groupValues[2].startsWith("sec")) "s+" else " min+") }
    }.orEmpty()

    /** Old posts point at a dead offload host: cdn.sfmcompile.club/.../YYYY/MM/<digits>/name -> the site itself. */
    private fun fixImg(u: String): String =
        Regex("^https?://cdn\\.sfmcompile\\.club(/wp-content/uploads/\\d{4}/\\d{2}/)\\d+/(.+)$").find(u)
            ?.let { "$baseUrl${it.groupValues[1]}${it.groupValues[2]}" } ?: u

    private val SIZED = Regex("-\\d+x\\d+(?=\\.\\w+$)")
    private fun sized(full: String) = if (full.isEmpty()) "" else full.replace(Regex("(\\.\\w+)$"), "-608x342$1")

    private fun makeRef(id: String, title: String, url: String, catIds: List<Int>, tags: List<String>, cover: String, alts: List<String>, views: String = ""): VideoRef? {
        val s = seriesOf(catIds)
        val r = VideoRef(
            source = this.id, id = id, title = title, url = url, series = s?.let { cats[it]?.name }.orEmpty(),
            cover = cover, duration = lengthOf(tags), views = views,
            tags = tags + catIds.map { "cat:$it" }, altCovers = alts.filter { it.isNotEmpty() && it != cover },
        )
        return r.takeUnless { VideoSafety.blocked(it) }
    }

    private fun mp4Of(html: String) = Regex("src=\"([^\"]+\\.(?:mp4|webm))\"").find(html)?.groupValues?.get(1)

    private fun toRef(p: JsonElement): VideoRef? {
        val id = p.str("id")
        val full = fixImg(p["yoast_head_json"]["og_image"].arr.firstOrNull().str("url"))
        mp4Of(p["content"].str("rendered"))?.let { videos[id] = it }
        val tags = p["tags"].arr.mapNotNull { it.text.toIntOrNull() }.map { tagSlugs[it] ?: it.toString() }
        val catIds = p["categories"].arr.mapNotNull { it.text.toIntOrNull() }
        return makeRef(id, Jsoup.parse(p["title"].str("rendered")).text(), p.str("link"), catIds, tags, sized(full), listOf(full))
    }

    private suspend fun list(query: String, page: Int): VideoPage {
        val (j, pages) = api("posts?per_page=24&page=$page&_fields=$FIELDS$query")
        val posts = j.arr
        lookup(posts.flatMap { p -> p["tags"].arr.mapNotNull { it.text.toIntOrNull() } }, posts.flatMap { p -> p["categories"].arr.mapNotNull { it.text.toIntOrNull() } })
        return VideoPage(posts.mapNotNull { toRef(it) }, page < pages)
    }

    override suspend fun latest(page: Int) = list("", page)

    /** Most viewed of all time: only the theme's HTML list sorts by views (8 posts a page). */
    override suspend fun popular(page: Int): VideoPage {
        val doc = pace.run { Http.getDoc("$baseUrl/" + (if (page > 1) "page/$page/" else "") + "?filter-by=popular") }
        val arts = doc.select("article.entry-tpl-stream")
        val catIdsOf = { a: Element -> a.select(".entry-categories a[class*='entry-category-item-']").mapNotNull { Regex("entry-category-item-(\\d+)").find(it.className())?.groupValues?.get(1)?.toIntOrNull() } }
        lookup(emptyList(), arts.flatMap { catIdsOf(it) })
        val items = arts.mapNotNull { a ->
            val t = a.selectFirst("h2.entry-title a") ?: return@mapNotNull null
            val id = a.id().removePrefix("post-")
            a.selectFirst("video source[src]")?.attr("abs:src")?.ifEmpty { null }?.let { videos[id] = it }
            // newer posts show the clip itself with a poster, older ones a lazy picture
            val poster = fixImg(a.selectFirst("video[data-poster-image]")?.attr("data-poster-image")?.ifEmpty { null }
                ?: a.selectFirst("video[poster]")?.attr("abs:poster")?.ifEmpty { null }
                ?: a.selectFirst(".entry-featured-media img[data-src]")?.attr("data-src").orEmpty())
            val tags = a.classNames().filter { it.startsWith("tag-") }.map { it.removePrefix("tag-") }
            makeRef(id, t.text(), t.attr("abs:href"), catIdsOf(a), tags, poster, listOf(poster.replace(SIZED, "")),
                views = a.selectFirst(".entry-views strong")?.text().orEmpty())
        }
        return VideoPage(items, doc.selectFirst("a.next[href]") != null)
    }

    // tags (count order) + games / characters; a category id carries a "c" in front
    override suspend fun genres(): List<Tag> = genreCache ?: run {
        val tags = api("tags?per_page=100&orderby=count&order=desc&_fields=id,name,slug,count").first.arr.map {
            tagSlugs[it.int("id")] = it.str("slug")
            Tag(it.str("id"), Jsoup.parse(it.str("name")).text(), "Tag", it.int("count"))
        }
        val cs = api("categories?per_page=100&orderby=count&order=desc&_fields=id,name,slug,count,parent").first.arr.map {
            val name = Jsoup.parse(it.str("name")).text()
            cats[it.int("id")] = Cat(name, it.str("slug"), it.int("parent"))
            Tag("c" + it.str("id"), name, if (it.int("parent") == 0) "Game" else "Character", it.int("count"))
        }
        (cs + tags).filter { !VideoSafety.badTag(it.label) }.also { genreCache = it }
    }

    override suspend fun search(q: SearchQuery, page: Int): VideoPage {
        val parts = StringBuilder()
        if (q.text.isNotBlank()) parts.append("&search=").append(q.text.trim().urlEncode())
        val incTags = q.include.filter { !it.id.startsWith("c") }.map { it.id }.toMutableList()
        val incCats = q.include.filter { it.id.startsWith("c") }.map { it.id.removePrefix("c") }
        // an artist is a tag here (its slug = the name without spaces, lower case)
        if (q.artist.isNotBlank()) runCatching {
            val want = q.artist.lowercase().replace(Regex("[^a-z0-9]"), "")
            api("tags?search=${q.artist.trim().urlEncode()}&per_page=20&_fields=id,slug").first.arr
                .firstOrNull { it.str("slug").replace("-", "") == want }?.let { incTags += it.str("id") }
        }
        if (incTags.isNotEmpty()) {
            parts.append("&tags%5Bterms%5D=").append(incTags.joinToString(","))
            if (incTags.size > 1) parts.append("&tags%5Boperator%5D=AND")
        }
        if (incCats.isNotEmpty()) parts.append("&categories=").append(incCats.joinToString(","))
        val excTags = q.exclude.filter { !it.id.startsWith("c") }.map { it.id }
        val excCats = q.exclude.filter { it.id.startsWith("c") }.map { it.id.removePrefix("c") }
        if (excTags.isNotEmpty()) parts.append("&tags_exclude=").append(excTags.joinToString(","))
        if (excCats.isNotEmpty()) parts.append("&categories_exclude=").append(excCats.joinToString(","))
        return list(parts.toString(), page)
    }

    override suspend fun details(ref: VideoRef): VideoDetails {
        val (p, _) = api("posts/${ref.id}?_fields=$FIELDS")
        val catIds = p["categories"].arr.mapNotNull { it.text.toIntOrNull() }
        lookup(p["tags"].arr.mapNotNull { it.text.toIntOrNull() }, catIds)
        val me = toRef(p) ?: throw SourceException("This video is hidden")
        val html = p["content"].str("rendered")
        val src = mp4Of(html) ?: videos[ref.id] ?: throw SourceException("No video on this post")
        val body = Jsoup.parse(html)
        val artist = body.selectFirst("pre a[href*='/tag/']")?.text().orEmpty()
        val desc = listOfNotNull(artist.ifEmpty { null }?.let { "Artist: $it" }, body.selectFirst(".wpsm-content")?.ownText()?.trim()?.ifEmpty { null })
            .joinToString("\n")
        // "More from" the same character / game
        val related = seriesOf(catIds)?.let { c ->
            runCatching { list("&categories=$c&exclude=${ref.id}", 1).items }.getOrDefault(emptyList())
        }.orEmpty()
        val d = VideoDetails(
            ref = ref.copy(title = me.title, url = me.url, series = me.series, cover = ref.cover.ifEmpty { me.cover },
                altCovers = (ref.altCovers + me.cover + me.altCovers).distinct().filter { it != ref.cover && it.isNotEmpty() },
                duration = me.duration.ifEmpty { ref.duration }, tags = me.tags),
            stream = VideoStream(src, if (src.endsWith(".webm")) "webm" else "mp4"),
            episodes = listOf(me) + related,
            tags = me.tags.filter { !it.startsWith("cat:") },
            description = desc,
        )
        VideoSafety.check(d)
        return d
    }
}
