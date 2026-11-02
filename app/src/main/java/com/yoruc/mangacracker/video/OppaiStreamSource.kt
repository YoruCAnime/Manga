package com.yoruc.mangacracker.video

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.core.arr
import com.yoruc.mangacracker.core.get
import com.yoruc.mangacracker.core.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

/**
 * oppai.stream: server-rendered HTML, search is a form POST that answers HTML cards (15 a page).
 * One WebM per episode (1440p "4K"), English subtitles beside it. Spec: _dev_tools/specs/oppaistream.md.
 */
object OppaiStreamSource : VideoSource {
    override val id = "oppaistream"
    override val name = "Oppai.stream"
    override val baseUrl = "https://oppai.stream"
    override val blurb = "1440p WebM · English subs"
    override val color = 0xFFF2A33DL

    private val pace = Pace(900)
    /** Sent with every search; text search ignores it (a site bug), so [denied] covers that. */
    private const val BLACKLIST = "Loli,Shota"
    /** data-folder ids of episodes tagged Loli / Shota, loaded once per run (never shown). */
    private val denied = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var deniedLoaded = false
    private val watchPages = ConcurrentHashMap<String, Document>()

    private val GENRES = listOf(
        "4k", "3D", "Ahegao", "Beach", "Comedy", "Fantasy", "Harem", "Horror", "Incest", "Lactation", "Mind Break",
        "Mind Control", "Monster", "POV", "Plot", "Softcore", "Tentacle", "Uncensored", "Vanilla", "X-Ray", "Yuri",
        "Anal", "BlowJob", "BoobJob", "Cowgirl", "Cunnilingus", "Doggy", "FootJob", "Groping", "HandJob", "Masturbation",
        "Missionary", "Threesome", "Double Penetration", "BDSM", "Bondage", "Creampie", "Facial", "Gangbang",
        "Girls Only", "NTR", "Orgy", "Public Sex", "Rape", "Reverse Rape", "Toys", "Voyeurism",
        "Big Boobs", "Elf", "Vampire", "Demon", "Small Boobs", "Milf", "Dark Skin", "Dominant Girl", "Gyaru", "Cosplay",
        "Glasses", "Maid", "Nurse", "Succubus", "Short Hair", "Swimsuit", "Teacher", "Futanari", "Nekomimi", "Pregnant",
        "Tsundere", "Virgin", "Muscles", "Ponytail", "Tomboy", "Ugly Bastard",
    )

    override suspend fun genres(): List<Tag> = GENRES.map { Tag(it, it, "Genre") }

    private fun card(a: Element): VideoRef? {
        val folder = a.attr("data-folder").ifEmpty { return null }
        if (folder in denied) return null
        val href = a.attr("abs:href").substringBefore("/from/")
        val series = a.selectFirst("p.episode-title")?.text().orEmpty()
        val ep = a.selectFirst("p.episode-number")?.text().orEmpty().ifEmpty { a.attr("epid") }
        val img = a.selectFirst("img.cover")?.let { it.attr("abs:data-src").ifEmpty { it.attr("abs:src") } }
            ?: a.selectFirst("noscript img")?.attr("abs:src").orEmpty()
        val r = VideoRef(
            source = id, id = folder, title = listOf(series, ep).filter { it.isNotBlank() }.joinToString(" "), url = href,
            series = series, episode = ep, cover = img, duration = a.selectFirst(".ep-time p")?.text().orEmpty(),
            views = a.selectFirst(".views p")?.text().orEmpty(), lang = "EN",
            // fallbacks: the same picture without the cache-buster, then the noscript copy
            altCovers = listOfNotNull(img.substringBefore('?').takeIf { it != img }, a.selectFirst("noscript img")?.attr("abs:src")?.takeIf { it != img }),
        )
        return r.takeUnless { VideoSafety.blocked(it) }
    }

    private suspend fun post(fields: Map<String, String>): Document = pace.run {
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
            val req = Request.Builder().url("$baseUrl/api/search.php").post(body)
                .header("Referer", "$baseUrl/search").header("X-Requested-With", "XMLHttpRequest").build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw SourceException("Oppai.stream answered ${r.code}")
                Jsoup.parse(r.body!!.string(), baseUrl)
            }
        }
    }

    private suspend fun searchPage(text: String, tags: List<String>, exclude: List<String>, order: String, page: Int): VideoPage {
        val doc = post(mapOf(
            "text" to text, "tags" to tags.joinToString(","), "blacklist" to (listOf(BLACKLIST) + exclude).joinToString(","),
            "studios" to "", "order" to order, "page" to page.toString(), "limit" to "15", "character_filters" to "{}",
        ))
        val total = doc.selectFirst(".results-num")?.text()?.trim()?.toIntOrNull() ?: 0
        val items = doc.select("a.episode[data-folder]").mapNotNull { card(it) }
        return VideoPage(items, page * 15 < total)
    }

    /** Folder ids of every Loli / Shota-tagged episode (read only the ids). */
    private suspend fun loadDenied() {
        if (deniedLoaded) return
        for (tag in listOf("Shota", "Loli")) {
            var page = 1
            while (page <= 12) {
                val doc = runCatching {
                    post(mapOf("text" to "", "tags" to tag, "blacklist" to "", "studios" to "", "order" to "recent",
                        "page" to page.toString(), "limit" to "15", "character_filters" to "{}"))
                }.getOrNull() ?: break
                val f = doc.select("a.episode[data-folder]").map { it.attr("data-folder") }
                denied += f
                val total = doc.selectFirst(".results-num")?.text()?.trim()?.toIntOrNull() ?: 0
                if (f.isEmpty() || page * 15 >= total) break
                page++
            }
        }
        deniedLoaded = true
    }

    override suspend fun latest(page: Int) = searchPage("", emptyList(), emptyList(), "recent", page)
    override suspend fun popular(page: Int) = searchPage("", emptyList(), emptyList(), "views", page)

    override suspend fun search(q: SearchQuery, page: Int): VideoPage {
        val text = q.text.trim()
        if (text.length >= 3) {
            // text + tags together returns nothing on this site, and text ignores the blacklist: filter here
            loadDenied()
            val r = searchPage(text, emptyList(), emptyList(), "recent", page)
            return r.copy(items = r.items.filter { it.id !in denied })
        }
        return searchPage("", q.include.map { it.id }, q.exclude.map { it.id }, "recent", page)
    }

    private suspend fun watch(ref: VideoRef): Document {
        watchPages[ref.id]?.let { return it }
        val url = ref.url.ifEmpty { "$baseUrl/watch/${ref.id}" }
        return pace.run { Http.getDoc(url) }.also { watchPages[ref.id] = it }
    }

    override suspend fun details(ref: VideoRef): VideoDetails {
        if (ref.id in denied) throw SourceException("This video is hidden")
        val doc = watch(ref)
        val tags = doc.select(".wrap-genres.tags a[href*='?src=episode-tag']").map {
            it.attr("href").substringAfter("/category/").substringBefore("?").lowercase()
        }
        if (tags.any { it == "loli" || it == "shota" }) { denied += ref.id; throw SourceException("This video is hidden") }

        var stream = ""
        var thumb = ""
        doc.select("script[type=application/ld+json]").forEach { s ->
            runCatching {
                val j = Http.json.parseToJsonElement(s.data())
                val graph = j["@graph"].arr.ifEmpty { listOf(j) }
                graph.forEach { g ->
                    if (stream.isEmpty()) stream = g.str("contentUrl")
                    if (thumb.isEmpty()) thumb = g["thumbnailUrl"].arr.firstOrNull()?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()
                }
            }
        }
        if (stream.isEmpty()) {
            val folder = Regex("var folder = \"([^\"]+)\"").find(doc.html())?.groupValues?.get(1) ?: ref.id
            val res = Regex("var res = \"([^\"]+)\"").find(doc.html())?.groupValues?.get(1).orEmpty()
            stream = "$baseUrl/videos/${java.net.URLEncoder.encode(folder, "UTF-8").replace("+", "%20")}/ep." + if ("4k-1" in res) "mp4" else "webm"
        }
        val sub = doc.selectFirst("track#sub-en[src], track[kind=subtitles][src]")?.attr("abs:src")
        val eps = doc.select("div.all-episodes a.episode[data-folder]").mapNotNull { card(it) }
            .sortedBy { episodeNumber(it.episode) }
        val title = doc.selectFirst("h1#episode-title")?.text().orEmpty()
        val me = ref.copy(
            title = ref.title.ifEmpty { title }, cover = ref.cover.ifEmpty { thumb }, tags = tags,
            series = ref.series.ifEmpty { title.substringBefore(" Episode") },
        )
        val d = VideoDetails(
            ref = me,
            stream = VideoStream(stream, if (stream.endsWith(".mp4")) "mp4" else "webm", listOfNotNull(sub?.let { VideoSub(it, "en", "English") })),
            episodes = eps.ifEmpty { listOf(me) }.let { l -> if (l.none { it.id == me.id }) (l + me).sortedBy { episodeNumber(it.episode) } else l },
            tags = tags,
            description = doc.selectFirst("#episode-description p.s5")?.text().orEmpty(),
        )
        VideoSafety.check(d)
        return d
    }
}
