package com.yoruc.mangacracker.video

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.core.arr
import com.yoruc.mangacracker.core.get
import com.yoruc.mangacracker.core.str
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * anikototv.to: HTML lists (30 a page) and its ajax calls (need X-Requested-With). Episode -> server list (sub / dub
 * apart) -> megaplay.buzz embed -> getSources, whose `enc` is AES-256-CBC with the key megaplay's own app.main.js
 * carries -> HLS master (1080p). Playlists, pieces and subtitles need Referer megaplay.buzz (the CDN hosts rotate).
 */
object AnikotoSource : VideoSource {
    override val id = "anikoto"
    override val name = "Anikoto"
    override val baseUrl = "https://anikototv.to"
    override val blurb = "Anime · 1080p · sub and dub · subtitles in 9 languages"
    override val color = 0xFF29B6F6L

    private const val PLAYER = "https://megaplay.buzz"
    private val pace = Pace(500)
    private val XHR = mapOf("X-Requested-With" to "XMLHttpRequest")
    /** slug -> (anime id, watch page) */
    private val shows = ConcurrentHashMap<String, Pair<Long, Document>>()
    /** anime id -> episode list html */
    private val episodeLists = ConcurrentHashMap<String, Pair<Long, Document>>()
    /** megaplay's key / iv (app.main.js DOMAINS_ENC_KEY / _IV); read again from the script when these stop working */
    @Volatile private var aesKey = "i?LMTAx0Q6,:}50U"
    @Volatile private var aesIv = "W0;27ToaUpl_P%'c"

    // the site's menu genres (adult ones left out: this is the plain anime site)
    private val GENRES = listOf(
        "action", "adventure", "cars", "comedy", "dementia", "demons", "drama", "fantasy", "game", "harem", "historical",
        "horror", "isekai", "josei", "kids", "magic", "martial-arts", "mecha", "military", "music", "mystery", "parody",
        "police", "psychological", "romance", "samurai", "school", "sci-fi", "seinen", "shoujo", "shoujo-ai", "shounen",
        "shounen-ai", "slice-of-life", "space", "sports", "super-power", "supernatural", "thriller", "vampire",
    )

    override suspend fun genres(): List<Tag> =
        GENRES.filter { it != "kids" }.map { g -> Tag(g, g.split('-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }, "Genre") }

    /** Every playlist, piece and subtitle needs megaplay as the Referer; the CDN names change, so all requests get it. */
    override val playerClient: OkHttpClient by lazy {
        Http.imageClient.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Referer", "$PLAYER/").build())
        }.build()
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    private fun slugOf(href: String) = href.substringAfter("/watch/", "").substringBefore('/').substringBefore('?')

    private suspend fun doc(url: String, headers: Map<String, String> = emptyMap()): Document = pace.run { Http.getDoc(url, headers) }

    /** `{"status":200,"result":...}` of an ajax call */
    private suspend fun ajax(url: String): kotlinx.serialization.json.JsonElement {
        val j = pace.run { Http.getJson(url, XHR + ("Referer" to "$baseUrl/")) }
        if (j.str("status") != "200") throw SourceException("Anikoto answered: ${j.str("result").take(60).ifEmpty { j.str("status") }}")
        return j["result"] ?: throw SourceException("Anikoto sent nothing")
    }

    private fun langOf(sub: Boolean, dub: Boolean) = when { sub && dub -> "SUB · DUB"; dub -> "DUB"; else -> "SUB" }

    /** A list card: [latest] = its newest episode (sub when there is one), else the whole show. */
    private fun card(item: Element, latest: Boolean): VideoRef? {
        val a = item.selectFirst("a[href*=/watch/]") ?: return null
        val slug = slugOf(a.attr("abs:href")).ifEmpty { return null }
        val title = item.selectFirst("a.name.d-title, a.d-title")?.text().orEmpty().ifEmpty { item.selectFirst("img")?.attr("alt").orEmpty() }
        val cover = item.selectFirst("img")?.let { it.attr("abs:src").ifEmpty { it.attr("abs:data-src") } }.orEmpty()
        val sub = item.selectFirst(".ep-status.sub span")?.text()?.trim().orEmpty()
        val dub = item.selectFirst(".ep-status.dub span")?.text()?.trim().orEmpty()
        val tags = item.select(".genre a").map { it.text().trim() }
        val type = item.selectFirst(".meta .right")?.text()?.trim().orEmpty()
        val r = if (latest && (sub.isNotEmpty() || dub.isNotEmpty())) {
            val (ep, lang) = if (sub.isNotEmpty()) sub to "sub" else dub to "dub"
            VideoRef(id, "$slug/$ep/$lang", "$title Episode $ep", "$baseUrl/watch/$slug/ep-$ep", series = title, episode = ep,
                cover = cover, lang = lang.uppercase(), tags = tags)
        } else {
            VideoRef(id, slug, title, "$baseUrl/watch/$slug", series = title, cover = cover, lang = langOf(sub.isNotEmpty(), dub.isNotEmpty()),
                duration = listOfNotNull(type.ifEmpty { null }, (sub.ifEmpty { dub }).ifEmpty { null }?.let { "$it eps" }).joinToString(" · "),
                tags = tags, show = true)
        }
        return r.takeUnless { VideoSafety.blocked(it) }
    }

    private suspend fun list(url: String, page: Int, latest: Boolean): VideoPage {
        val d = doc(url + (if ('?' in url) "&" else "?") + "page=$page")
        val items = d.select("div.item").mapNotNull { card(it, latest) }.distinctBy { it.id }
        val last = d.select("ul.pagination a[href*=page=]").mapNotNull { Regex("page=(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: page
        return VideoPage(items, page < last)
    }

    override suspend fun latest(page: Int) = list("$baseUrl/latest-updated", page, latest = true)
    override suspend fun popular(page: Int) = list("$baseUrl/most-viewed", page, latest = false)

    override suspend fun search(q: SearchQuery, page: Int): VideoPage {
        val text = q.text.trim()
        val genre = q.include.firstOrNull()?.id
        return when {
            text.isNotEmpty() -> list("$baseUrl/filter?keyword=${enc(text)}", page, latest = false)
            genre != null -> list("$baseUrl/genre/$genre", page, latest = false)
            else -> popular(page)
        }
    }

    private suspend fun watchPage(slug: String): Document {
        shows[slug]?.takeIf { System.currentTimeMillis() - it.first < 600_000 }?.let { return it.second }
        return doc("$baseUrl/watch/$slug").also { shows[slug] = System.currentTimeMillis() to it }
    }

    private suspend fun episodeList(animeId: String): Document {
        episodeLists[animeId]?.takeIf { System.currentTimeMillis() - it.first < 300_000 }?.let { return it.second }
        val html = (ajax("$baseUrl/ajax/episode/list/$animeId?vrf=") as? JsonPrimitive)?.content.orEmpty()
        return Jsoup.parse(html, baseUrl).also { episodeLists[animeId] = System.currentTimeMillis() to it }
    }

    private class Show(val title: String, val cover: String, val tags: List<String>, val description: String, val eps: List<Element>)

    private suspend fun show(slug: String): Show {
        val w = watchPage(slug)
        val animeId = w.selectFirst("#watch-main[data-id]")?.attr("data-id").orEmpty()
            .ifEmpty { throw SourceException("Anikoto doesn't have this show any more") }
        val info = w.selectFirst("#w-info")
        val tags = info?.select(".bmeta .meta div")?.firstOrNull { it.text().startsWith("Genres") }?.select("a")?.map { it.text().trim() }.orEmpty()
        return Show(
            title = info?.selectFirst("h1.title")?.text().orEmpty(),
            cover = info?.selectFirst(".poster img")?.attr("abs:src").orEmpty(),
            tags = tags,
            description = info?.selectFirst(".synopsis .content")?.text().orEmpty(),
            eps = episodeList(animeId).select("ul.ep-range li a[data-ids]"),
        )
    }

    /** Each episode once per language it has: id "<slug>/<number>/<sub|dub>". */
    private fun refs(slug: String, s: Show): List<VideoRef> = s.eps.flatMap { a ->
        val num = a.attr("data-num").trim()
        listOf("sub", "dub").filter { a.attr("data-$it") == "1" }.map { lang ->
            VideoRef(id, "$slug/$num/$lang", "${s.title} Episode $num", "$baseUrl/watch/$slug/ep-$num", series = s.title, episode = num,
                cover = s.cover, lang = lang.uppercase(), tags = s.tags)
        }
    }.sortedWith(seriesOrder)

    override suspend fun series(ref: VideoRef): List<VideoRef> {
        val slug = ref.id.substringBefore('/')
        return refs(slug, show(slug)).filter { !VideoSafety.blocked(it) }
    }

    override suspend fun details(ref: VideoRef): VideoDetails {
        val slug = ref.id.substringBefore('/')
        val s = show(slug)
        val all = refs(slug, s)
        if (all.isEmpty()) throw SourceException("Anikoto lists no episodes for this show yet")
        // a show card plays its first episode, sub first
        val me = all.firstOrNull { it.id == ref.id } ?: if (ref.show || ref.id.count { it == '/' } < 2) all.first() else
            throw SourceException("Episode ${ref.episode} (${ref.lang.lowercase()}) isn't on Anikoto")
        val lang = me.id.substringAfterLast('/')
        val num = me.episode
        val ep = s.eps.firstOrNull { it.attr("data-num").trim() == num } ?: throw SourceException("Episode $num isn't on Anikoto")
        val stream = stream(ep.attr("data-ids"), lang)
        val out = VideoDetails(me, stream, all.filter { it.lang == me.lang }, s.tags, s.description)
        VideoSafety.check(out)
        return out
    }

    /** Server order: Vidstream (plain pieces, everyday DNS), HD-2, then the rest; HD-1 wraps pieces in a PNG, so last. */
    private fun serverRank(name: String) = when (name.trim().lowercase()) {
        "vidstream-2" -> 0; "vidstream-1" -> 1; "hd-2" -> 2; "hd-1" -> 9; else -> 5
    }

    private suspend fun stream(dataIds: String, lang: String): VideoStream {
        val html = (ajax("$baseUrl/ajax/server/list?servers=${enc(dataIds)}") as? JsonPrimitive)?.content.orEmpty()
        val servers = Jsoup.parse(html).select("div.type[data-type=$lang] li[data-link-id]").sortedBy { serverRank(it.text()) }
        if (servers.isEmpty()) throw SourceException("Anikoto has no ${lang} servers for this episode")
        var last: Throwable? = null
        for (sv in servers) {
            try {
                return fromServer(sv.attr("data-link-id"))
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
            }
        }
        throw SourceException("Anikoto's servers didn't give this episode (${last?.message})")
    }

    private suspend fun fromServer(linkId: String): VideoStream {
        val embed = ajax("$baseUrl/ajax/server?get=${enc(linkId)}").str("url").ifEmpty { throw SourceException("no player") }
        val page = doc(embed, mapOf("Referer" to "$baseUrl/"))
        val fileId = page.selectFirst("#megaplay-player[data-id]")?.attr("data-id").orEmpty().ifEmpty { throw SourceException("no file id") }
        val prefix = if ("/videojs/" in embed) "$PLAYER/videojs" else PLAYER
        val s = embed.substringAfter("?s=", "").substringBefore('&')
        val j = pace.run { Http.getJson("$prefix/stream/getSources?id=$fileId" + (if (s.isNotEmpty()) "&s=$s" else ""), XHR + ("Referer" to embed)) }
        val master = j.str("enc").takeIf { it.isNotEmpty() }?.let { masterOf(it) }
            ?: j["sources"].let { it.str("file").ifEmpty { it.arr.firstOrNull().str("file") } }.ifEmpty { null }
            // the master sits beside the preview picture
            ?: j.str("image").takeIf { "/preview/" in it }?.let { it.substringBefore("/preview/") + "/master.m3u8" }
            ?: throw SourceException("no stream")
        val subs = j["tracks"].arr.filter { it.str("kind") == "captions" || it.str("kind").isEmpty() }
            .sortedByDescending { it.str("default") == "true" }
            .map { t -> VideoSub(t.str("file"), langCode(t.str("label")), t.str("label")) }
            .filter { it.url.startsWith("http") }
        return VideoStream(master, "hls", subs)
    }

    /** `enc` = base64url(AES-256-CBC(JSON {"file": master})) */
    private suspend fun masterOf(enc: String): String? {
        decrypt(enc)?.let { return it }
        // new key? read it from megaplay's script once
        runCatching {
            val js = pace.run { Http.get("$PLAYER/lib/app.main.js") }
            Regex("DOMAINS_ENC_KEY\\s*=\\s*\"([^\"]*)\"").find(js)?.groupValues?.get(1)?.let { aesKey = it }
            Regex("DOMAINS_ENC_IV\\s*=\\s*\"([^\"]*)\"").find(js)?.groupValues?.get(1)?.let { aesIv = it }
        }
        return decrypt(enc)
    }

    private fun decrypt(enc: String): String? = runCatching {
        val key = aesKey.toByteArray().copyOf(32)
        val iv = aesIv.toByteArray().copyOf(16)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
        val text = String(c.doFinal(Base64.getUrlDecoder().decode(enc.trim().trimEnd('='))), Charsets.UTF_8)
        Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)?.replace("\\/", "/")
    }.getOrNull()

    private fun langCode(label: String): String = when (label.lowercase().substringBefore(' ').substringBefore('(').substringBefore('-')) {
        "english" -> "en"; "spanish" -> "es"; "portuguese" -> "pt"; "french" -> "fr"; "german" -> "de"; "italian" -> "it"
        "russian" -> "ru"; "arabic" -> "ar"; "indonesian" -> "id"; "thai" -> "th"; "vietnamese" -> "vi"; "turkish" -> "tr"
        "malay" -> "ms"; "japanese" -> "ja"; "chinese" -> "zh"; "korean" -> "ko"; "hindi" -> "hi"
        else -> label.lowercase().take(2)
    }

    override fun webUrl(ref: VideoRef): String = ref.url.ifEmpty { "$baseUrl/watch/${ref.id.substringBefore('/')}" }
}
