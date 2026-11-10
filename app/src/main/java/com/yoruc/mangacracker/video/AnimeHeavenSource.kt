package com.yoruc.mangacracker.video

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * animeheaven.me: server-rendered HTML, no paging (lists come whole, paged here). One 720p MP4 per episode with the
 * English subs burned in; no dub. Episode -> video: gate.php with the episode's key as a cookie names the MP4.
 */
object AnimeHeavenSource : VideoSource {
    override val id = "animeheaven"
    override val name = "AnimeHeaven"
    override val baseUrl = "https://animeheaven.me"
    override val blurb = "Anime · 720p · English subs (no dub)"
    override val color = 0xFF7E57C2L

    private const val PER_PAGE = 30
    private val pace = Pace(700)
    private val lists = ConcurrentHashMap<String, Pair<Long, List<VideoRef>>>()
    private val shows = ConcurrentHashMap<String, Pair<Long, Document>>()
    /** gate.php needs only our `key` cookie, never the shared jar's */
    private val gateClient by lazy { Http.client.newBuilder().cookieJar(CookieJar.NO_COOKIES).cache(null).build() }

    private val GENRES = listOf(
        "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Harem", "Historical", "Horror", "Isekai", "Magic",
        "Martial Arts", "Mecha", "Military", "Music", "Mystery", "Psychological", "Romance", "School", "Sci-Fi",
        "Seinen", "Shoujo", "Shounen", "Slice of Life", "Space", "Sports", "Super Power", "Supernatural", "Thriller",
        "Vampire",
    )

    override suspend fun genres(): List<Tag> = GENRES.map { Tag(it, it, "Genre") }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    private fun showId(href: String) = href.substringAfter("anime.php?", "").take(5)

    private suspend fun doc(url: String): Document = pace.run { Http.getDoc(url) }

    /** A whole list, kept 10 minutes, then paged here. */
    private suspend fun cached(key: String, load: suspend () -> List<VideoRef>): List<VideoRef> {
        lists[key]?.takeIf { System.currentTimeMillis() - it.first < 600_000 }?.let { return it.second }
        return load().filter { !VideoSafety.blocked(it) }.also { lists[key] = System.currentTimeMillis() to it }
    }

    private fun page(all: List<VideoRef>, page: Int) =
        VideoPage(all.drop((page - 1) * PER_PAGE).take(PER_PAGE), page * PER_PAGE < all.size)

    /** Rows of new.php / popular.php: with [episodes] each row is that episode, else the show. */
    private fun chartRows(d: Document, episodes: Boolean): List<VideoRef> = d.select("div.chart").mapNotNull { row ->
        val a = row.selectFirst(".chartimg a[href*=anime.php]") ?: return@mapNotNull null
        val sid = showId(a.attr("href")).ifEmpty { return@mapNotNull null }
        val title = row.selectFirst(".charttitle a")?.text().orEmpty().ifEmpty { a.selectFirst("img")?.attr("alt").orEmpty() }
        val cover = row.selectFirst("img.coverimg")?.attr("abs:src").orEmpty()
        val ep = row.selectFirst(".chartep")?.text()?.trim().orEmpty()
        if (episodes && ep.isNotEmpty()) {
            VideoRef(id, "$sid/$ep", "$title Episode $ep", "$baseUrl/anime.php?$sid", series = title, episode = ep, cover = cover, lang = "SUB")
        } else {
            VideoRef(id, sid, title, "$baseUrl/anime.php?$sid", series = title, cover = cover, lang = "SUB",
                duration = ep.ifEmpty { null }?.let { "$it eps" }.orEmpty(), show = true)
        }
    }

    /** Cards of search.php / tags.php. */
    private fun gridRows(d: Document): List<VideoRef> = d.select("div.similarimg").mapNotNull { box ->
        val a = box.selectFirst("a[href*=anime.php]") ?: return@mapNotNull null
        val sid = showId(a.attr("href")).ifEmpty { return@mapNotNull null }
        val title = box.selectFirst(".similarname a")?.text().orEmpty().ifEmpty { box.selectFirst("img")?.attr("alt").orEmpty() }
        VideoRef(id, sid, title, "$baseUrl/anime.php?$sid", series = title, cover = box.selectFirst("img.coverimg")?.attr("abs:src").orEmpty(),
            lang = "SUB", show = true)
    }.distinctBy { it.id }

    override suspend fun latest(page: Int) = page(cached("new") { chartRows(doc("$baseUrl/new.php"), episodes = true) }, page)
    override suspend fun popular(page: Int) = page(cached("popular") { chartRows(doc("$baseUrl/popular.php"), episodes = false) }, page)

    override suspend fun search(q: SearchQuery, page: Int): VideoPage {
        val text = q.text.trim()
        val tag = q.include.firstOrNull()?.id
        val all = when {
            text.isNotEmpty() -> cached("s:$text") { gridRows(doc("$baseUrl/search.php?s=${enc(text)}")) }
            tag != null -> cached("t:$tag") { gridRows(doc("$baseUrl/tags.php?tag=${enc(tag).replace("+", "%20")}")) }
            else -> return popular(page)
        }
        return page(all, page)
    }

    private suspend fun showPage(sid: String): Document {
        shows[sid]?.takeIf { System.currentTimeMillis() - it.first < 300_000 }?.let { return it.second }
        return doc("$baseUrl/anime.php?$sid").also { shows[sid] = System.currentTimeMillis() to it }
    }

    /** Every episode of the show, oldest first; [id] = "<show>/<episode>", the 32-hex gate key in [VideoRef.url]'s fragment. */
    private fun episodes(sid: String, d: Document): List<VideoRef> {
        val title = d.selectFirst("div.infotitle")?.text().orEmpty()
        val poster = d.selectFirst("img.posterimg")?.attr("abs:src").orEmpty()
        val tags = d.select("div.infotags a[href*=tags.php]").map { it.text().trim() }.filter { it.isNotEmpty() }
        return d.select("a[onclick*=gatea]").mapNotNull { a ->
            val key = a.attr("id").trim().ifEmpty { Regex("[0-9a-f]{32}").find(a.attr("onclick"))?.value.orEmpty() }
            val ep = a.selectFirst(".watch2")?.text()?.trim().orEmpty()
            if (key.isEmpty() || ep.isEmpty()) return@mapNotNull null
            VideoRef(id, "$sid/$ep", "$title Episode $ep", "$baseUrl/anime.php?$sid#$key", series = title, episode = ep,
                cover = poster, lang = "SUB", tags = tags)
        }.distinctBy { it.id }.sortedWith(seriesOrder)
    }

    override suspend fun series(ref: VideoRef): List<VideoRef> {
        val sid = ref.id.substringBefore('/')
        return episodes(sid, showPage(sid)).filter { !VideoSafety.blocked(it) }
    }

    override suspend fun details(ref: VideoRef): VideoDetails {
        val sid = ref.id.substringBefore('/')
        val d = showPage(sid)
        val eps = episodes(sid, d)
        if (eps.isEmpty()) throw SourceException("AnimeHeaven lists no episodes for this show yet")
        // a show card plays its first episode
        val me = eps.firstOrNull { it.id == ref.id } ?: if (ref.show || '/' !in ref.id) eps.first() else
            throw SourceException("Episode ${ref.episode} isn't on AnimeHeaven any more")
        val key = me.url.substringAfter('#')
        val gate = pace.run {
            withContext(Dispatchers.IO) {
                gateClient.newCall(Http.request("$baseUrl/gate.php", mapOf("Cookie" to "key=$key", "Referer" to "$baseUrl/anime.php?$sid"))).execute().use { r ->
                    if (!r.isSuccessful) throw SourceException("AnimeHeaven answered ${r.code}")
                    Jsoup.parse(r.body!!.string(), baseUrl)
                }
            }
        }
        // comments hold a promo <source> for another key; Jsoup leaves comments out of select()
        val src = gate.select("source[type=video/mp4]").map { it.attr("abs:src") }.firstOrNull { key in it && it.startsWith("http") }
            ?: throw SourceException("AnimeHeaven didn't give a video for this episode")
        val tags = me.tags
        val out = VideoDetails(
            ref = me,
            stream = VideoStream(src, "mp4"),
            episodes = eps,
            tags = tags,
            description = d.selectFirst("div.infodes")?.text().orEmpty(),
        )
        VideoSafety.check(out)
        return out
    }

    override fun webUrl(ref: VideoRef): String = "$baseUrl/anime.php?${ref.id.substringBefore('/')}"
}
