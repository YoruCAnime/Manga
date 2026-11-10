package com.yoruc.mangacracker.video

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.core.arr
import com.yoruc.mangacracker.core.get
import com.yoruc.mangacracker.core.str
import com.yoruc.mangacracker.core.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
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
 * anitaku.io: the site that carries the Anitaku name now (WordPress, server-rendered HTML; the old Gogoanime
 * addresses are parked). Sub only. One player per episode, named by the episode page's iframe:
 *  - blogger.com/video.g (most episodes): Blogger's own player call (batchexecute, rpc WcwnYd) names the MP4s
 *    (720p / 360p on googlevideo, no Referer needed, links last some hours);
 *  - megaplay.buzz (some): the same steps as Anikoto -> HLS master (1080p) + soft subs, Referer megaplay.buzz.
 * Older posts point at players that want a browser (embtaku, vkspeed): those say so instead of playing.
 * Spec: _dev_tools/specs/anitaku.md.
 */
object AnitakuSource : VideoSource {
    override val id = "anitaku"
    override val name = "Anitaku"
    override val baseUrl = "https://anitaku.io"
    override val blurb = "Anime · 720p / 1080p · English subs (no dub) · newer shows"
    override val color = 0xFFFFB300L

    private const val BLOGGER = "https://www.blogger.com"
    private const val PLAYER = "https://megaplay.buzz"
    private val pace = Pace(700)
    private val XHR = mapOf("X-Requested-With" to "XMLHttpRequest")
    /** series slug -> series page */
    private val shows = ConcurrentHashMap<String, Pair<Long, Document>>()
    /** episode post address -> its page */
    private val episodePages = ConcurrentHashMap<String, Pair<Long, Document>>()
    /** megaplay's key / iv (app.main.js DOMAINS_ENC_KEY / _IV); read again from the script when these stop working */
    @Volatile private var aesKey = "i?LMTAx0Q6,:}50U"
    @Volatile private var aesIv = "W0;27ToaUpl_P%'c"

    // the filter form's genres (adult and small-children ones left out)
    private val GENRES = listOf(
        "action", "adult-cast", "adventure", "anthropomorphic", "avant-garde", "boys-love", "cgdct", "combat-sports",
        "comedy", "crossdressing", "delinquents", "detective", "drama", "ecchi", "fantasy", "gag-humor", "girls-love",
        "gore", "gourmet", "harem", "high-stakes-game", "historical", "horror", "isekai", "iyashikei", "josei",
        "love-polygon", "mahou-shoujo", "martial-arts", "mecha", "medical", "military", "music", "mystery", "mythology",
        "organized-crime", "otaku-culture", "parody", "performing-arts", "pets", "psychological", "racing",
        "reincarnation", "reverse-harem", "romance", "samurai", "school", "sci-fi", "seinen", "shoujo", "shounen",
        "showbiz", "slice-of-life", "space", "sports", "strategy-game", "super-power", "supernatural", "survival",
        "suspense", "team-sports", "time-travel", "urban-fantasy", "vampire", "video-game", "villainess", "visual-arts",
        "workplace",
    )

    override suspend fun genres(): List<Tag> =
        GENRES.map { g -> Tag(g, g.split('-').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }, "Genre") }

    /** megaplay's playlists, pieces and subtitles need it as the Referer (its CDN names change); Blogger's MP4s need none. */
    override val playerClient: OkHttpClient by lazy {
        Http.imageClient.newBuilder().addInterceptor { chain ->
            val req = chain.request()
            if (req.url.host.endsWith("googlevideo.com")) chain.proceed(req)
            else chain.proceed(req.newBuilder().header("Referer", "$PLAYER/").build())
        }.build()
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    private fun seriesSlug(href: String) = href.substringAfter("/series/", "").substringBefore('/').substringBefore('?')
    private fun postSlug(href: String) = href.substringAfter("://").substringAfter('/', "").trim('/').substringBefore('/').substringBefore('?')

    private suspend fun doc(url: String, headers: Map<String, String> = emptyMap()): Document = pace.run { Http.getDoc(url, headers) }

    private val EP_POST = Regex("^(.+)-episode-(\\d+(?:-\\d+)?)-english-(subbed|dubbed)$")

    /** A list card: a show (href /series/<slug>/), or with [latest] its newest episode; the feed's cards are episode posts. */
    private fun card(item: Element, latest: Boolean): VideoRef? {
        val a = item.selectFirst("a[href]") ?: return null
        val href = a.attr("abs:href")
        val cover = item.selectFirst("img")?.let { it.attr("abs:src").ifEmpty { it.attr("abs:data-src") } }.orEmpty()
        val epx = item.selectFirst(".epx")?.text()?.trim().orEmpty()
        val lang = if (item.selectFirst(".sb")?.text()?.trim().equals("dub", true)) "DUB" else "SUB"
        val r = if ("/series/" in href) {
            val slug = seriesSlug(href).ifEmpty { return null }
            val title = a.attr("title").ifEmpty { item.selectFirst("h2")?.text().orEmpty() }
            val ep = Regex("^Ep\\s*(\\d+(?:\\.\\d+)?)$", RegexOption.IGNORE_CASE).find(epx)?.groupValues?.get(1).orEmpty()
            if (latest && ep.isNotEmpty()) {
                VideoRef(id, "$slug/$ep", "$title Episode $ep", "$baseUrl/series/$slug/", series = title, episode = ep, cover = cover, lang = lang)
            } else {
                // "Ongoing" / "Completed" (series lists) or "Ep 6" (the home list)
                VideoRef(id, slug, title, "$baseUrl/series/$slug/", series = title, cover = cover, lang = lang, duration = epx, show = true)
            }
        } else {
            // an episode post: "<series slug>-episode-<n>-english-subbed"; the series name is the text before the headline
            val post = postSlug(href).ifEmpty { return null }
            val m = EP_POST.find(post)
            val full = a.attr("title").ifEmpty { item.selectFirst("h2")?.text().orEmpty() }
            val ep = m?.groupValues?.get(2)?.replace('-', '.') ?: episodeOf(full).ifEmpty { epx.removePrefix("Ep").trim() }
            val series = item.selectFirst(".tt")?.ownText()?.trim().orEmpty().ifEmpty { full.substringBefore(" Episode ") }
            VideoRef(id, "${m?.groupValues?.get(1) ?: post}/$ep", "$series Episode $ep", href, series = series, episode = ep, cover = cover,
                lang = if (m?.groupValues?.get(3) == "dubbed") "DUB" else lang)
        }
        return r.takeUnless { VideoSafety.blocked(it) }
    }

    private fun hasNext(d: Document) = d.selectFirst(".hpage a.r, .pagination a.next") != null

    private suspend fun list(url: String): VideoPage {
        val d = doc(url)
        return VideoPage(d.select("div.listupd article.bs").mapNotNull { card(it, latest = false) }.distinctBy { it.id }, hasNext(d))
    }

    /** Page 1 = the home list (newest episode of each show); from page 2 the episode feed (/page/N/, 25 posts). */
    override suspend fun latest(page: Int): VideoPage {
        if (page <= 1) {
            val d = doc("$baseUrl/browse/")
            return VideoPage(d.select("div.listupd article.bs").mapNotNull { card(it, latest = true) }.distinctBy { it.id }, true)
        }
        val d = doc("$baseUrl/page/$page/")
        // the second list on these pages is a side block, not the feed
        val feed = d.selectFirst("div.listupd.normal") ?: d.selectFirst("div.listupd")
        return VideoPage(feed?.select("article.bs").orEmpty().mapNotNull { card(it, latest = true) }.distinctBy { it.id }, hasNext(d))
    }

    override suspend fun popular(page: Int) = list("$baseUrl/series/?page=$page&status=&type=&order=popular")

    override suspend fun search(q: SearchQuery, page: Int): VideoPage {
        val text = q.text.trim()
        val genre = q.include.firstOrNull()?.id
        return when {
            // "/?s=" on the home address loses the words in a redirect; the paged form keeps them
            text.isNotEmpty() -> list("$baseUrl/page/$page/?s=${enc(text)}")
            genre != null -> list("$baseUrl/series/?page=$page&genre%5B%5D=${enc(genre)}&status=&type=&order=popular")
            else -> popular(page)
        }
    }

    private suspend fun seriesPage(slug: String): Document {
        shows[slug]?.takeIf { System.currentTimeMillis() - it.first < 300_000 }?.let { return it.second }
        return doc("$baseUrl/series/$slug/").also { shows[slug] = System.currentTimeMillis() to it }
    }

    private suspend fun episodePage(url: String): Document {
        episodePages[url]?.takeIf { System.currentTimeMillis() - it.first < 300_000 }?.let { return it.second }
        if (episodePages.size > 60) episodePages.clear()
        return doc(url).also { episodePages[url] = System.currentTimeMillis() to it }
    }

    private class Show(val slug: String, val title: String, val cover: String, val tags: List<String>, val description: String, val eps: List<Element>)

    private suspend fun show(slug: String): Show {
        val d = seriesPage(slug)
        return Show(
            slug = slug,
            title = d.selectFirst("h1.entry-title")?.text().orEmpty(),
            cover = d.selectFirst("div.thumb img")?.let { it.attr("abs:src").ifEmpty { it.attr("abs:data-src") } }.orEmpty(),
            tags = d.select(".genxed a").map { it.text().trim() }.filter { it.isNotEmpty() },
            description = d.selectFirst(".entry-content[itemprop=description]")?.text().orEmpty(),
            eps = d.select("div.eplister li a[href]"),
        )
    }

    /** The show of [ref]: by the slug in its id; a feed card's slug is read off its post name, so its post page's "All Episodes" link settles it. */
    private suspend fun showOf(ref: VideoRef): Show {
        val slug = ref.id.substringBefore('/')
        val post = ref.url.takeIf { it.startsWith(baseUrl) && "/series/" !in it }
        if (post == null || shows.containsKey(slug)) return show(slug)
        val real = runCatching { episodePage(post) }.getOrNull()
            ?.selectFirst(".nvsc a[href*=/series/], .entry-info a[href*=/series/]")?.attr("abs:href")?.let { seriesSlug(it) }.orEmpty()
        return show(real.ifEmpty { slug })
    }

    /** Every episode, oldest first: id "<series slug>/<number>", [VideoRef.url] = the episode's post. */
    private fun refs(s: Show): List<VideoRef> = s.eps.mapNotNull { a ->
        val href = a.attr("abs:href")
        val num = a.selectFirst(".epl-num")?.text()?.trim().orEmpty().ifEmpty { episodeOf(a.selectFirst(".epl-title")?.text().orEmpty()) }
        if (href.isEmpty() || num.isEmpty()) return@mapNotNull null
        VideoRef(id, "${s.slug}/$num", "${s.title} Episode $num", href, series = s.title, episode = num, cover = s.cover,
            lang = if (postSlug(href).endsWith("-english-dubbed")) "DUB" else "SUB", tags = s.tags)
    }.distinctBy { it.id }.sortedWith(seriesOrder)

    override suspend fun series(ref: VideoRef): List<VideoRef> = refs(showOf(ref)).filter { !VideoSafety.blocked(it) }

    override suspend fun details(ref: VideoRef): VideoDetails {
        val s = showOf(ref)
        val all = refs(s)
        if (all.isEmpty()) throw SourceException("Anitaku lists no episodes for this show yet")
        // a show card plays its first episode
        val num = ref.id.substringAfter('/', "")
        val me = all.firstOrNull { it.episode == num } ?: if (ref.show || num.isEmpty()) all.first() else
            throw SourceException("Episode ${ref.episode} isn't on Anitaku any more")
        val out = VideoDetails(me, stream(me.url), all, s.tags, s.description)
        VideoSafety.check(out)
        return out
    }

    /** The episode page's player: the iframe in #pembed, else the first entry of the server menu (an iframe tag in base64). */
    private suspend fun stream(postUrl: String): VideoStream {
        val d = episodePage(postUrl)
        val embed = d.selectFirst("#pembed iframe[src]")?.attr("abs:src").orEmpty().ifEmpty {
            d.select("select.mirror option[value]").map { it.attr("value") }.filter { it.isNotEmpty() }.firstNotNullOfOrNull { v ->
                runCatching { Jsoup.parse(String(Base64.getDecoder().decode(v.trim()), Charsets.UTF_8)).selectFirst("iframe[src]")?.attr("src")?.trim() }.getOrNull()
            }.orEmpty()
        }
        val host = embed.substringAfter("://", "").substringBefore('/')
        return when {
            embed.isEmpty() -> throw SourceException("Anitaku has no player for this episode")
            host.endsWith("blogger.com") -> fromBlogger(embed)
            host.endsWith("megaplay.buzz") -> fromMegaplay(embed)
            else -> throw SourceException("This episode sits on a player that only works in a browser ($host). Use Open site, or try another source")
        }
    }

    /**
     * Blogger's player page carries the session id (FdrFJe) and build label (cfb2h) for its one data call; the answer
     * lists [address, [itag]] pairs: 22 = 720p, 18 = 360p.
     */
    private suspend fun fromBlogger(embed: String): VideoStream {
        val token = Regex("[?&]token=([^&]+)").find(embed)?.groupValues?.get(1) ?: throw SourceException("no video token")
        val page = pace.run { Http.get(embed, mapOf("Referer" to "$baseUrl/")) }
        val sid = Regex("\"FdrFJe\":\"([^\"]+)\"").find(page)?.groupValues?.get(1).orEmpty()
        val build = Regex("\"cfb2h\":\"([^\"]+)\"").find(page)?.groupValues?.get(1).orEmpty()
        if (sid.isEmpty() || build.isEmpty()) throw SourceException("Blogger's player page changed")
        val url = "$BLOGGER/_/BloggerVideoPlayerUi/data/batchexecute?rpcids=WcwnYd&source-path=%2Fvideo.g" +
            "&f.sid=${enc(sid)}&bl=${enc(build)}&hl=en-US&_reqid=${100000 + (System.currentTimeMillis() % 800000)}&rt=c"
        // f.req = [[["WcwnYd","[\"<token>\",\"\",0]",null,"generic"]]]
        val arg = "[\"" + token.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"\",0]"
        val freq = "[[[\"WcwnYd\",\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\",null,\"generic\"]]]"
        val body = pace.run {
            withContext(Dispatchers.IO) {
                val req = Http.request(url, mapOf("Origin" to BLOGGER, "Referer" to "$BLOGGER/")).newBuilder()
                    .post(FormBody.Builder().add("f.req", freq).build()).build()
                Http.client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw SourceException("Blogger answered ${r.code}")
                    r.body?.string().orEmpty()
                }
            }
        }
        // the reply is framed in lines; the one that starts [["wrb.fr" holds the answer as a JSON string
        val row = body.lineSequence().firstOrNull { it.startsWith("[[\"wrb.fr\"") } ?: throw SourceException("Blogger gave no video")
        val inner = Http.json.parseToJsonElement(row).arr.firstOrNull().arr.getOrNull(2).text
        if (inner.isEmpty()) throw SourceException("Blogger gave no video (removed?)")
        val files = Http.json.parseToJsonElement(inner).arr.getOrNull(2).arr.mapNotNull { f ->
            val u = f.arr.getOrNull(0).text
            if (u.startsWith("http")) u to f.arr.getOrNull(1).arr.firstOrNull().text else null
        }
        val best = files.firstOrNull { it.second == "22" } ?: files.firstOrNull { it.second != "18" } ?: files.firstOrNull()
            ?: throw SourceException("Blogger gave no video (removed?)")
        return VideoStream(best.first, "mp4")
    }

    /** megaplay embed -> getSources, whose `enc` is AES-256-CBC with the key megaplay's own app.main.js carries -> HLS master. */
    private suspend fun fromMegaplay(embed: String): VideoStream {
        val page = doc(embed, mapOf("Referer" to "$baseUrl/"))
        val fileId = page.selectFirst("#megaplay-player[data-id]")?.attr("data-id").orEmpty().ifEmpty { throw SourceException("no file id") }
        val prefix = if ("/videojs/" in embed) "$PLAYER/videojs" else PLAYER
        val s = embed.substringAfter("?s=", "").substringBefore('&')
        val j = pace.run { Http.getJson("$prefix/stream/getSources?id=$fileId" + (if (s.isNotEmpty()) "&s=$s" else ""), XHR + ("Referer" to embed)) }
        val master = j.str("enc").takeIf { it.isNotEmpty() }?.let { masterOf(it) }
            ?: j["sources"].let { it.str("file").ifEmpty { it.arr.firstOrNull().str("file") } }.ifEmpty { null }
            ?: throw SourceException("Anitaku's player didn't give this episode")
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
        "russian" -> "ru"; "arabic" -> "ar"; "indonesian" -> "id"; "japanese" -> "ja"; "chinese" -> "zh"; "korean" -> "ko"
        else -> label.lowercase().take(2)
    }

    override fun webUrl(ref: VideoRef): String = ref.url.ifEmpty { "$baseUrl/series/${ref.id.substringBefore('/')}/" }
}
