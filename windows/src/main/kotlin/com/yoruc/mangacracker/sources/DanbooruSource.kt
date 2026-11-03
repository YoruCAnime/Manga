package com.yoruc.mangacracker.sources

import com.yoruc.mangacracker.core.Chapter
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.MangaDetails
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Page
import com.yoruc.mangacracker.core.PageResult
import com.yoruc.mangacracker.core.Repo
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
import com.yoruc.mangacracker.core.isoToMillis
import com.yoruc.mangacracker.core.str
import com.yoruc.mangacracker.core.urlEncode
import kotlinx.serialization.json.JsonElement
import java.util.concurrent.ConcurrentHashMap

/**
 * Danbooru: posts (one picture = one title) and series pools (a pool = one chapter in order).
 * Needs an app-style User-Agent (a browser one gets a Cloudflare wall). Spec: _dev_tools/specs/danbooru.md
 */
object DanbooruSource : Source {
    override val id = "danbooru"
    override val name = "Danbooru"
    override val baseUrl = "https://danbooru.donmai.us"
    override val blurb = "danbooru.donmai.us · art and comic pools"
    override val color = 0xFF0075F8
    override val nsfw = true
    override val hasChapters = false
    override val coverRatio = 0.78f

    init {
        Http.userAgents["donmai.us"] = "MangaCracker/1.0 (Android)"
        Http.rateLimits["danbooru.donmai.us"] = 450L
    }

    override val selects = listOf(
        SelectDef("kind", "Show", listOf("posts" to "Pictures", "pools" to "Comic pools")),
        SelectDef("sort", "Sort", listOf("" to "Newest", "order:rank" to "Hot", "order:score" to "Top score", "order:favcount" to "Most favourited")),
        SelectDef("rating", "Rating", listOf("" to "Any", "rating:g,s" to "Safe", "rating:q" to "Questionable", "rating:e" to "Explicit", "rating:q,e" to "18+ only")),
    )

    private fun auth(): String {
        val s = Repo.settings.value
        return if (s.danbooruUser.isNotBlank() && s.danbooruKey.isNotBlank()) "&login=${s.danbooruUser.urlEncode()}&api_key=${s.danbooruKey.urlEncode()}" else ""
    }

    private fun loggedIn() = auth().isNotEmpty()

    private suspend fun api(path: String): JsonElement {
        val (code, body) = Http.getRaw("$baseUrl$path${if ('?' in path) "" else "?"}${auth()}", mapOf("Accept" to "application/json"))
        val j = runCatching { Http.json.parseToJsonElement(body) }.getOrNull()
        if (code !in 200..299) throw SourceException(j?.str("message")?.ifEmpty { null } ?: "Danbooru answered $code")
        return j ?: throw SourceException("Danbooru sent something unreadable")
    }

    private val posts = ConcurrentHashMap<String, JsonElement>()

    private fun variant(p: JsonElement, type: String): String =
        p["media_asset"]?.get("variants").arr.firstOrNull { it.str("type") == type }?.str("url").orEmpty()

    private fun postRef(p: JsonElement): MangaRef? {
        if (p.str("file_url").isEmpty() && p.str("large_file_url").isEmpty()) return null
        val tags = p.str("tag_string").split(' ').filter { it.isNotEmpty() }
        val id = p.str("id")
        posts[id] = p
        val artist = p.str("tag_string_artist").split(' ').firstOrNull().orEmpty().replace('_', ' ')
        val copyright = p.str("tag_string_character").split(' ').firstOrNull()?.takeIf { it.isNotEmpty() }
            ?: p.str("tag_string_copyright").split(' ').firstOrNull().orEmpty()
        return MangaRef(
            source = this.id, id = "p$id",
            title = listOf(copyright.replace('_', ' '), artist.ifEmpty { null }?.let { "by $it" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" ").ifEmpty { "Post $id" },
            cover = variant(p, "720x720").ifEmpty { variant(p, "360x360") }.ifEmpty { p.str("preview_file_url") },
            tags = tags + "rating:" + p.str("rating"),
            subtitle = "★ ${p.int("score")} · ${p.str("image_width")}×${p.str("image_height")}",
        )
    }

    /** Builds the tag line within Danbooru's 2-tag limit (ratings are free). */
    private fun tagLine(q: SearchQuery, sortAllowed: Boolean = true): String {
        val counted = mutableListOf<String>()
        q.include.forEach { counted += it.id }
        q.exclude.forEach { counted += "-" + it.id }
        if (q.artist.isNotBlank()) counted += q.artist.trim().replace(' ', '_')
        if (q.text.isNotBlank()) counted += q.text.trim().split(Regex("\\s+")).map { it.replace(' ', '_') }
        val sort = q.selects["sort"].orEmpty()
        if (sort.isNotEmpty() && sortAllowed) counted += sort
        if (!loggedIn() && counted.size > 2) {
            if (sort.isNotEmpty() && counted.size == 3) counted.remove(sort)
            else throw SourceException("Danbooru allows 2 tags per search without a login. Add your Danbooru key in Settings for more.")
        }
        val free = listOfNotNull(q.selects["rating"]?.ifEmpty { null })
        return (counted + free).joinToString(" ")
    }

    private suspend fun postList(tags: String, page: Int): PageResult {
        val r = api("/posts.json?limit=60&page=$page&tags=${tags.urlEncode()}").arr
        return PageResult(r.mapNotNull { postRef(it) }, r.size >= 60 && page < 1000)
    }

    override suspend fun popular(page: Int) = postList("order:rank", page)
    override suspend fun latest(page: Int) = postList("", page)

    override suspend fun search(query: SearchQuery, page: Int): PageResult {
        if (query.selects["kind"] == "pools") return pools(query, page)
        return postList(tagLine(query), page)
    }

    private suspend fun pools(q: SearchQuery, page: Int): PageResult {
        val name = (q.text + " " + q.artist).trim().replace(' ', '_')
        val order = when (q.selects["sort"]) { "order:score", "order:favcount", "order:rank" -> "post_count"; else -> "updated_at" }
        val list = api("/pools.json?limit=40&page=$page&search[category]=series&search[order]=$order" +
            (if (name.isNotEmpty()) "&search[name_contains]=${name.urlEncode()}" else "")).arr
            .filter { !Safety.blocked(listOf(it.str("name"))) && it.str("name").lowercase().let { n -> "loli" !in n && "shota" !in n } }
        // covers: the first post of each pool, all in one call
        val firsts = list.mapNotNull { it["post_ids"].arr.firstOrNull()?.let { f -> it.str("id") to (f as kotlinx.serialization.json.JsonPrimitive).content } }
        val covers = if (firsts.isEmpty()) emptyMap() else
            api("/posts.json?limit=100&tags=${("id:" + firsts.joinToString(",") { it.second }).urlEncode()}").arr
                .associateBy { it.str("id") }
        val refs = list.mapNotNull { pool ->
            val first = firsts.firstOrNull { it.first == pool.str("id") }?.second
            val cp = first?.let { covers[it] }
            if (cp != null && Safety.blocked(cp.str("tag_string").split(' '))) return@mapNotNull null
            MangaRef(id, "pool" + pool.str("id"), pool.str("name").replace('_', ' '),
                cover = cp?.let { variant(it, "720x720").ifEmpty { variant(it, "360x360") } }.orEmpty(),
                tags = cp?.str("tag_string")?.split(' ').orEmpty(),
                subtitle = "${pool.int("post_count")} pages")
        }
        return PageResult(refs, list.size >= 40)
    }

    override suspend fun suggestTags(query: String): List<Tag> {
        if (query.trim().length < 2) return emptyList()
        return api("/autocomplete.json?search[query]=${query.trim().replace(' ', '_').urlEncode()}&search[type]=tag_query&limit=20").arr
            .filter { it.str("value").isNotEmpty() && ':' !in it.str("value") }
            .map { Tag(it.str("value"), it.str("value").replace('_', ' '), when (it.int("category")) { 1 -> "Artist"; 3 -> "Series"; 4 -> "Character"; else -> "Tag" }, it.int("post_count")) }
    }

    override suspend fun tags(): List<Tag> = listOf(
        "1girl", "solo", "long_hair", "breasts", "smile", "looking_at_viewer", "blush", "short_hair", "large_breasts", "blue_eyes",
        "comic", "4koma", "translated", "english_text", "monochrome", "greyscale", "swimsuit", "bikini", "thighhighs", "maid",
        "school_uniform", "kimono", "glasses", "animal_ears", "cat_ears", "fox_ears", "wings", "tail", "scenery", "landscape",
        "night", "sky", "flower", "beach", "rain", "sword", "armor", "dress", "nude", "sex", "highres", "absurdres",
    ).map { Tag(it, it.replace('_', ' ')) }

    override fun tagQuery(name: String) = SearchQuery(include = listOf(Tag(name.substringAfter(':').trim().replace(' ', '_'), name.substringAfter(':').trim())))
    override fun artistQuery(name: String) = SearchQuery(include = listOf(Tag(name.trim().replace(' ', '_'), name.trim())))

    // ---------- details ----------

    private suspend fun post(id: String): JsonElement = posts[id] ?: api("/posts/$id.json").also { posts[id] = it }

    private fun split(s: String) = s.split(' ').filter { it.isNotEmpty() }.map { it.replace('_', ' ') }

    override suspend fun details(ref: MangaRef): MangaDetails {
        if (ref.id.startsWith("pool")) {
            val pool = api("/pools/${ref.id.removePrefix("pool")}.json")
            return MangaDetails(
                ref = ref.copy(title = pool.str("name").replace('_', ' ')),
                description = pool.str("description").replace(Regex("\\[/?[a-z]+]|\\{\\{|}}|\"([^\"]+)\":\\S+"), "$1").trim(),
                pageCount = pool.int("post_count"),
                type = "Comic pool",
                url = webUrl(ref),
                extra = listOf("Updated" to pool.str("updated_at").take(10)),
            )
        }
        val p = post(ref.id.removePrefix("p"))
        val tags = p.str("tag_string").split(' ')
        return MangaDetails(
            ref = ref.copy(cover = p.str("large_file_url").ifEmpty { ref.cover }, tags = tags + "rating:" + p.str("rating")),
            artists = split(p.str("tag_string_artist")),
            genres = split(p.str("tag_string_general")).take(40),
            tagGroups = listOfNotNull(
                split(p.str("tag_string_copyright")).takeIf { it.isNotEmpty() }?.let { TagGroup("Series", it) },
                split(p.str("tag_string_character")).takeIf { it.isNotEmpty() }?.let { TagGroup("Character", it) },
            ),
            type = "Picture",
            rating = "★ ${p.int("score")}",
            url = webUrl(ref),
            pageCount = 1,
            extra = listOf(
                "Size" to "${p.str("image_width")} × ${p.str("image_height")} · ${p.str("file_ext")}",
                "Rating" to when (p.str("rating")) { "g" -> "General"; "s" -> "Sensitive"; "q" -> "Questionable"; else -> "Explicit" },
                "Posted" to p.str("created_at").take(10),
            ) + (p.str("source").takeIf { it.startsWith("http") }?.let { listOf("Source" to it) } ?: emptyList()),
        )
    }

    override suspend fun chapters(ref: MangaRef): List<Chapter> =
        if (ref.id.startsWith("pool")) listOf(Chapter("all", "Read pool", 1f))
        else {
            val p = runCatching { post(ref.id.removePrefix("p")) }.getOrNull()
            listOf(Chapter("all", "Picture", 1f, date = p?.str("created_at")?.let { isoToMillis(it) } ?: 0L, pages = 1))
        }

    override suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page> {
        if (!ref.id.startsWith("pool")) {
            val p = post(ref.id.removePrefix("p"))
            return listOf(pageOf(0, p) ?: throw SourceException("This post is a video - open it on the site"))
        }
        val id = ref.id.removePrefix("pool")
        val out = mutableListOf<JsonElement>()
        var page = 1
        while (page <= 20) {
            val r = api("/posts.json?limit=200&page=$page&tags=${"ordpool:$id".urlEncode()}").arr
            out.addAll(r)
            if (r.size < 200) break
            page++
        }
        return out.filter { it.str("large_file_url").isNotEmpty() && !Safety.blocked(it.str("tag_string").split(' ')) }
            .mapNotNull { pageOf(0, it) }.mapIndexed { i, pg -> pg.copy(index = i) }
    }

    /** Animated posts (GIF, animated PNG / WebP) need the original file: the sample is a still JPEG. Videos: null. */
    private fun pageOf(i: Int, p: JsonElement): Page? {
        val ext = p.str("file_ext").lowercase()
        if (ext in setOf("mp4", "webm", "zip", "swf")) return null
        val tags = p.str("tag_string").split(' ')
        val animated = ext == "gif" || "animated" in tags || "animated_png" in tags || "animated_gif" in tags
        val original = p.str("file_url")
        return if (animated && original.isNotEmpty()) Page(i, original, p.int("image_width"), p.int("image_height"))
        else Page(i, p.str("large_file_url").ifEmpty { original }, sampleW(p), sampleH(p))
    }

    private fun sampleW(p: JsonElement) = p["media_asset"]?.get("variants").arr.firstOrNull { it.str("type") == "sample" }?.int("width") ?: p.int("image_width")
    private fun sampleH(p: JsonElement) = p["media_asset"]?.get("variants").arr.firstOrNull { it.str("type") == "sample" }?.int("height") ?: p.int("image_height")

    override suspend fun related(ref: MangaRef, details: MangaDetails): List<MangaRef> {
        val artist = details.artists.firstOrNull() ?: return emptyList()
        return runCatching { postList(artist.replace(' ', '_') + " order:score", 1).items }.getOrDefault(emptyList()).filter { it.key != ref.key }
    }

    override fun webUrl(ref: MangaRef) =
        if (ref.id.startsWith("pool")) "$baseUrl/pools/${ref.id.removePrefix("pool")}" else "$baseUrl/posts/${ref.id.removePrefix("p")}"
}
