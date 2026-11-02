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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/**
 * hentaigasm.com: a WordPress site with its JSON API open (spec: _dev_tools/specs/hentaigasm.md).
 * One direct MP4 per episode; a post's category is its series.
 */
object HentaigasmSource : VideoSource {
    override val id = "hentaigasm"
    override val name = "Hentaigasm"
    override val baseUrl = "https://hentaigasm.com"
    override val blurb = "Subbed & raw episodes · MP4"
    override val color = 0xFFE85D9EL

    private val pace = Pace(900)
    private const val NEW_RELEASES = 1475
    private const val FIELDS = "id,date,slug,link,title,content,categories,tags,views"
    /** Series that are always hidden (minors; found by the research probe), plus what the start-up check adds. */
    private val blockedCats = mutableSetOf(1329)
    @Volatile private var checked = false

    init {
        // the post HTML holds the player (video + poster) only for a mobile browser; plain clients get it without.
        // Pinned, because some phones' web view agent strings may not count as one.
        Http.userAgents["hentaigasm.com"] = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        // its picture server answers 429 when a whole grid of posters comes at once: space them a little
        // (only the thumbnail paths; the video files themselves are not paced)
        for (h in listOf("hgasm1.com", "hgasm3.com")) Http.rateLimits["$h/thumbnail"] = 90L
    }

    // id -> slug (the complete genre list, 2026-11-02); "preview" left out
    private val GENRES = linkedMapOf(
        781 to "3D", 39 to "Anal", 6 to "Blowjob", 51 to "Bondage", 7 to "Boobjob", 138 to "Cheating", 10 to "Creampie",
        31 to "Demon", 789 to "Exclusive", 801 to "Fantasy", 58 to "Footjob", 94 to "Futanari", 29 to "Incest", 61 to "Maid",
        12 to "Masturbation", 93 to "Monster", 764 to "Motion Anime", 98 to "Nurse", 23 to "Rape", 799 to "School",
        1024 to "Season", 1034 to "Series", 48 to "Swimsuit", 60 to "Tentacle", 780 to "Uncensored", 319 to "Vanilla",
        8 to "Virgin", 767 to "X-Ray", 26 to "Yuri", 11 to "Exhibition", 19 to "Gangbang", 35 to "Harem", 27 to "Sex Toy",
    )

    override suspend fun genres(): List<Tag> = GENRES.map { (id, n) -> Tag(id.toString(), n, "Genre") }

    private suspend fun api(path: String): Pair<JsonElement, Int> = pace.run {
        val r = Http.client.newCall(Http.request("$baseUrl/wp-json/wp/v2/$path", mapOf("Accept" to "application/json"))).execute()
        r.use {
            if (it.code == 400) return@run Http.json.parseToJsonElement("[]") to 0 // past the last page
            if (!it.isSuccessful) throw SourceException("Hentaigasm answered ${it.code}")
            Http.json.parseToJsonElement(it.body!!.string()) to (it.header("X-WP-TotalPages")?.toIntOrNull() ?: 1)
        }
    }

    /** Adds any series named like minors content to the block list (once per run, 2 small calls). */
    private suspend fun safetyCheck() {
        if (checked) return
        checked = true
        for (w in listOf("loli", "shota")) runCatching {
            api("categories?search=$w&per_page=100&_fields=id").first.arr.forEach { blockedCats += it.int("id") }
        }
    }

    private fun excl() = "&categories_exclude=" + blockedCats.joinToString(",")

    private fun toRef(p: JsonElement): VideoRef? {
        val title = Jsoup.parse(p["title"].str("rendered")).text()
        val cats = p["categories"].arr.map { it.text.toIntOrNull() ?: 0 }
        if (cats.any { it in blockedCats }) return null
        val html = p["content"].str("rendered")
        // the poster sits next to the video as thumbnail/<same name>.jpg when the player script is missing
        val video = Regex("<source src=\"([^\"]+\\.mp4)\"").find(html)?.groupValues?.get(1).orEmpty()
        val poster = Regex("posterImage:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?: video.takeIf { it.isNotEmpty() }?.let { v -> v.substringBeforeLast('/') + "/thumbnail/" + v.substringAfterLast('/').removeSuffix(".mp4") + ".jpg" }
            ?: ""
        val tags = p["tags"].arr.mapNotNull { GENRES[it.text.toIntOrNull()] }
        val ep = episodeOf(title)
        val series = title.replace(Regex("\\s+(?:Season\\s+)?\\d+(?:\\s+3D)?\\s+(Subbed|Raw|Dubbed|Preview)\\s*$", RegexOption.IGNORE_CASE), "")
        val lang = when {
            title.endsWith("Raw", true) -> "RAW"
            title.endsWith("Preview", true) -> "PV"
            title.endsWith("Subbed", true) -> "EN"
            else -> ""
        }
        // fallbacks: the list preview (a moving .webp exists for every post, a .jpg for some); the other picture
        // server does not hold new posters (checked 2026-11-02)
        val name = video.substringAfterLast('/').removeSuffix(".mp4")
        val alts = if (name.isEmpty()) emptyList() else listOf("https://hgasm1.com/preview/$name.webp", "https://hgasm1.com/preview/$name.jpg").map { enc(it) }
        val r = VideoRef(
            source = id, id = p.str("id"), title = title, url = p.str("link"), series = series, episode = ep,
            cover = enc(poster), views = shortViews(p.int("views")), lang = lang,
            tags = tags + cats.map { "cat:$it" }, altCovers = alts,
        )
        return r.takeUnless { VideoSafety.blocked(it) }
    }

    private fun enc(u: String) = u.toHttpUrlOrNull()?.toString() ?: u

    private fun shortViews(n: Int) = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1e6)
        n >= 1_000 -> "%.0fK".format(n / 1e3)
        n > 0 -> n.toString()
        else -> ""
    }

    private suspend fun list(query: String, page: Int): VideoPage {
        safetyCheck()
        val (j, pages) = api("posts?per_page=30&page=$page&_fields=$FIELDS$query${excl()}")
        return VideoPage(j.arr.mapNotNull { toRef(it) }, page < pages)
    }

    /** Real new episodes (the date order is full of re-dated old posts). */
    override suspend fun latest(page: Int) = list("&categories=$NEW_RELEASES", page)

    /** Most viewed: only the HTML page can sort by views; the posts are then read through the API (for posters). */
    override suspend fun popular(page: Int): VideoPage {
        safetyCheck()
        val doc = pace.run { Http.getDoc("$baseUrl/page/$page/?orderby=views") }
        val ids = doc.select("#content .loop-content div.item.item-post").mapNotNull { it.id().removePrefix("post-").toIntOrNull() }
        if (ids.isEmpty()) return VideoPage(emptyList(), false)
        val (j, _) = api("posts?include=${ids.joinToString(",")}&per_page=${ids.size}&_fields=$FIELDS${excl()}")
        val byId = j.arr.mapNotNull { toRef(it) }.associateBy { it.id }
        return VideoPage(ids.mapNotNull { byId[it.toString()] }, doc.selectFirst(".wp-pagenavi a.nextpostslink") != null)
    }

    override suspend fun search(q: SearchQuery, page: Int): VideoPage {
        val parts = StringBuilder()
        if (q.text.isNotBlank()) parts.append("&search=").append(q.text.trim().urlEncode())
        if (q.include.isNotEmpty()) {
            parts.append("&tags%5Bterms%5D=").append(q.include.joinToString(",") { it.id })
            if (q.include.size > 1) parts.append("&tags%5Boperator%5D=AND")
        }
        if (q.exclude.isNotEmpty()) parts.append("&tags_exclude=").append(q.exclude.joinToString(",") { it.id })
        return list(parts.toString(), page)
    }

    private suspend fun post(id: String): JsonElement = api("posts/$id?_fields=$FIELDS").first

    override suspend fun details(ref: VideoRef): VideoDetails {
        safetyCheck()
        val p = post(ref.id)
        val me = toRef(p) ?: throw SourceException("This video is hidden")
        val html = Jsoup.parse(p["content"].str("rendered"))
        val src = html.selectFirst("video source[src]")?.attr("src")
            ?: html.selectFirst("a[download][href]")?.attr("href")
            ?: Regex("<source src=\"([^\"]+\\.mp4)\"").find(p["content"].str("rendered"))?.groupValues?.get(1)
            ?: throw SourceException("No video on this post")
        val series = p["categories"].arr.map { it.text }.firstOrNull { it != NEW_RELEASES.toString() }
        val eps = if (series == null) listOf(me) else runCatching {
            api("posts?categories=$series&per_page=100&_fields=$FIELDS${excl()}").first.arr.mapNotNull { toRef(it) }
                .filter { it.lang == me.lang || me.lang.isEmpty() }
                .sortedWith(compareBy({ episodeNumber(it.episode) }, { it.id.toIntOrNull() ?: 0 }))
        }.getOrDefault(listOf(me))
        val d = VideoDetails(
            ref = ref.copy(title = me.title, cover = me.cover.ifEmpty { ref.cover }, series = me.series, episode = me.episode, tags = me.tags, lang = me.lang),
            stream = VideoStream(enc(src), "mp4"),
            episodes = eps.ifEmpty { listOf(me) },
            tags = me.tags.filter { !it.startsWith("cat:") },
        )
        VideoSafety.check(d)
        return d
    }
}
