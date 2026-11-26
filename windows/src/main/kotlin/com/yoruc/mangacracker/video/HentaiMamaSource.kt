package com.yoruc.mangacracker.video

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.core.arr
import com.yoruc.mangacracker.core.text
import com.yoruc.mangacracker.core.urlEncode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

/**
 * hentaimama.io: WordPress (theme DooPlay) HTML, 24 a page. Lists of episodes (latest) and of series (popular,
 * genres, search). An episode's player is an iframe per mirror, asked for with admin-ajax `get_player_contents`;
 * the "r2-mp4" mirror is a plain 1080p MP4 on gdvid.info that never expires. Subtitles are burned in.
 * Spec: _dev_tools/specs/hentaimama.md.
 */
object HentaiMamaSource : VideoSource {
    override val id = "hentaimama"
    override val name = "HentaiMama"
    override val baseUrl = "https://hentaimama.io"
    override val blurb = "hentaimama.io · episodes, English subs"
    override val color = 0xFF38BDF8L

    // a play is up to 4 page calls (episode, series, admin-ajax, embed): Cloudflare-cached, so a shorter gap
    private val pace = Pace(700)
    private const val AJAX = "https://hentaimama.io/wp-admin/admin-ajax.php"
    /** Series refs (popular / genre / search cards) carry this id prefix; their details() play episode 1. */
    private const val SERIES = "tv:"

    init {
        // the browser sends the site as Referer to the file servers; gdvid.info plays with or without it (tested)
        for (h in listOf("gdvid.info", "hentaidoge.org")) Http.referers[h] = "https://hentaimama.io/"
    }

    // ---------- minors filter ----------

    /** The site's minors genre is spelled "shoutacon"; the rest never appeared but are cheap to keep. */
    private val HIDDEN = setOf("shoutacon", "shotacon", "shota", "loli", "lolicon")
    /** Series of the "shoutacon" genre (2026-11-02); the start-up check reads the live genre on top. */
    private val SEED = ("toshi-densetsu-series nuki-nuki-zupposism gaki-ni-modotte-yarinaoshi juvenile-pornography-animation " +
        "hachishaku-hachiwa-keraku-meguri-igyou-kaikitan-the-animation seikatsu-shuukan-animation " +
        "furyou-ni-hamerarete-jusei-suru-kyonyuu-okaa-san-the-animation uchi-no-otouto-maji-de-dekain-dakedo-mi-ni-konai " +
        "a-forbidden-time sakusei-byoutou-animation muma-no-machi-cornelica shishunki-sex cherry-gals " +
        "watashi-ga-toriko-ni-natte-yaru tennen-koi-iro-alcohol bokura-no-sex boku-to-misaki-sensei bakunyuu-bomb " +
        "energy-kyouka shounen-ga-otona-ni-natta-natsu buta-hime-sama bitch-gakuen-ga-seijun-na-hazu-ga-nai-animation " +
        "aisei-tenshi-love-mary-akusei-jutai-animation otome-domain-animation warau-kangofu-the-animation " +
        "torokase-orgasm-animation inmou no-waifu-no-life mayohiga-no-onee-san-the-animation secret-journey " +
        "kemonokko-tsuushin-animation boku-ni-harem-sexfriend-ga-dekita-riyuu aki-sora-yume-no-naka " +
        "monmusu-quest-side-story-succubus-illusion girls-rush-animation boku-no-risou-no-isekai-seikatsu brandish " +
        "oneshota-the-animation love-wa-gal-kara-hajimaru-unmei netoshizu-ova natsu-to-hako otoko-no-ko-ojou-sama " +
        "kohakuiro-no-hunter-animation majo-wa-kekkyoku-sono-kyaku-animation oni-chichi-harem ajin-ga-osuki-nan-desu-ne " +
        "valkyrie-hazard fushigi-no-kuni-no-succubus 3d-takarasagashi-no-natsuyasumi 3d-marika-shota " +
        "3d-overwhelming-hot-spring-accident").split(' ')
    private val blockedSlugs = ConcurrentHashMap.newKeySet<String>().apply { addAll(SEED) }
    /** Normalised series names (episode cards carry the name, not the series slug). */
    private val blockedNames = ConcurrentHashMap.newKeySet<String>().apply { SEED.forEach { add(norm(it)) } }
    private val checkLock = Mutex()
    @Volatile private var checked = false

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "")

    private fun block(slug: String, name: String) {
        if (slug.isNotEmpty()) blockedSlugs += slug
        norm(name).takeIf { it.isNotEmpty() }?.let { blockedNames += it }
    }

    /** Reads every series of the minors genre once per run (3 pages on 2026-11-02); retried while it fails. */
    private suspend fun safetyCheck() {
        if (checked) return
        checkLock.withLock {
            if (checked) return
            runCatching {
                for (p in 1..8) {
                    val doc = page("$baseUrl/genre/shoutacon/" + (if (p > 1) "page/$p/" else ""))
                    doc.select("article.series-card .sc-title a[href]").forEach { block(slugOf(it.attr("href")), it.text()) }
                    if (!hasNext(doc)) break
                }
                checked = true
            }
        }
    }

    private fun episodeBlocked(slug: String, series: String) =
        norm(series).let { it.isNotEmpty() && it in blockedNames } || blockedSlugs.any { slug == it || slug.startsWith("$it-episode") }

    // ---------- genres ----------

    // slug -> name (the genres-filter page, 2026-11-02; "shoutacon" left out)
    private val GENRES = linkedMapOf(
        "3d" to "3D", "action" to "Action", "adventure" to "Adventure", "ahegao" to "Ahegao", "anal" to "Anal",
        "animal-ears" to "Animal Girls", "armpit-fetish" to "Armpit fetish", "bdsm" to "BDSM", "blackmail" to "Blackmail",
        "blonde" to "Blonde", "blowjob" to "Blowjob", "bondage" to "Bondage", "brainwashed" to "Brainwashed",
        "cat-girl" to "Cat Girl", "comedy" to "Comedy", "condom" to "Condom", "cosplay" to "Cosplay", "creampie" to "Creampie",
        "cross-dressing" to "Cross-dressing", "cunnilingus" to "Cunnilingus", "cutefunny" to "Cute & Funny",
        "dark-skin" to "Dark Skin", "deepthroat" to "DeepThroat", "demi-human" to "Demi-Human", "demons" to "Demons",
        "doctor" to "Doctor", "domination" to "Domination", "double-penatration" to "Double Penetration", "drama" to "Drama",
        "dubbed" to "Dubbed", "ecchi" to "Ecchi", "elf" to "Elf", "facesitting" to "Facesitting", "facial" to "Facial",
        "family" to "Family", "fantasy" to "Fantasy", "female-doctor" to "Female Doctor", "female-teacher" to "Female Teacher",
        "femdom" to "Femdom", "filmed" to "Filmed", "fisting" to "Fisting", "footjob" to "Footjob", "furry" to "Furry",
        "futanari" to "Futanari", "gangbang" to "Gangbang", "gyaru" to "Gyaru", "hand-job" to "Hand Job", "harem" to "Harem",
        "historical" to "Historical", "horny-slut" to "Horny Slut", "horror" to "Horror", "housewife" to "Housewife",
        "humiliation" to "Humiliation", "incest" to "Incest", "inflation" to "Inflation", "internal-cumshot" to "Internal Cumshot",
        "lactation" to "Lactation", "large-breasts" to "Large Breasts", "magical-girls" to "Magical Girls", "maid" to "Maid",
        "martial-arts" to "Martial Arts", "masturbation" to "Masturbation", "megane" to "Megane", "milf" to "MILF",
        "mind-break" to "Mind Break", "molestation" to "Molestation", "monsters" to "Monsters", "nekomimi" to "Nekomimi",
        "nipple-fuck" to "Nipple Fuck", "non-japanese" to "Non-Japanese", "ntr" to "NTR", "nuns" to "Nuns", "nurses" to "Nurses",
        "office-ladies" to "Office Ladies", "orc" to "Orc/Goblin", "orgy" to "Orgy", "piercing" to "Piercing", "police" to "Police",
        "pov" to "POV", "pregnant" to "Pregnant", "princess" to "Princess", "public-sex" to "Public Sex", "rape" to "Rape",
        "rim-job" to "Rim job", "romance" to "Romance", "scat" to "Scat", "school-girls" to "School Girls", "sci-fi" to "Sci-Fi",
        "sci-fi-fantasy" to "Sci-Fi & Fantasy", "shimapan" to "Shimapan", "short" to "Short", "sleeping" to "Sleeping",
        "small-breasts" to "Small Breasts", "sports" to "Sports", "squirting" to "Squirting", "step-daughter" to "Step Daughter",
        "step-mother" to "Step Mother", "step-sister" to "Step Sister", "stocking" to "Stocking", "strap-on" to "Strap-on",
        "succubus" to "Succubus", "super-power" to "Super Power", "supernatural" to "Supernatural", "swimsuit" to "Swimsuit",
        "tentacles" to "Tentacles", "three-some" to "Three some", "tits-fuck" to "Tits Fuck", "toys" to "Toys",
        "train-molestation" to "Train Molestation", "trap" to "Trap", "tsundere" to "Tsundere", "ugly-bastard" to "Ugly Bastard",
        "uncensored" to "Uncensored", "urination" to "Urination", "vampire" to "Vampire", "vanilla" to "Vanilla",
        "virgins" to "Virgins", "watersports" to "Watersports", "widow" to "Widow", "womb-tattoo" to "Womb Tattoo",
        "x-ray" to "X-Ray", "yuri" to "Yuri",
    )

    // the site shows no per-genre counts, so count stays 0
    override suspend fun genres(): List<Tag> = GENRES.map { (s, n) -> Tag(s, n, "Genre") }

    // ---------- pages + cards ----------

    private suspend fun page(url: String, headers: Map<String, String> = emptyMap()): Document = pace.run { Http.getDoc(url, headers) }

    private fun slugOf(href: String) = href.trimEnd('/').substringAfterLast('/')

    private fun hasNext(doc: Document) = doc.selectFirst("a.dt-pg-next[href]") != null

    /** srcset entries other than [main], smallest last (fallbacks when a cover is refused). */
    private fun alts(img: Element?, main: String): List<String> =
        img?.attr("srcset").orEmpty().split(',').map { it.trim().substringBefore(' ') }.filter { it.isNotEmpty() && it != main }.reversed()

    private fun shortViews(s: String): String {
        val k = Regex("([\\d.]+)k", RegexOption.IGNORE_CASE).find(s)?.groupValues?.get(1)?.toDoubleOrNull() ?: return ""
        return if (k >= 1000) "%.1fM".format(k / 1000) else "%.0fK".format(k)
    }

    /** `article.item.se` of the episode lists: series name + "Episode N", no genres (the minors check goes by series). */
    private fun episodeCard(a: Element): VideoRef? {
        val link = a.selectFirst(".season_m a[href]") ?: a.selectFirst("a[href*='/episodes/']") ?: return null
        val url = link.attr("abs:href")
        val slug = slugOf(url)
        val series = a.selectFirst(".season_m .b")?.text().orEmpty()
        val ep = Regex("(\\d+(?:\\.\\d+)?)").find(a.selectFirst(".season_m .c")?.text().orEmpty())?.groupValues?.get(1).orEmpty()
        if (episodeBlocked(slug, series)) return null
        val img = a.selectFirst(".poster img")
        val cover = img?.attr("abs:src").orEmpty()
        val flags = a.select(".card-tags .tag").map { it.text().trim() }
        val r = VideoRef(
            source = id, id = slug, url = url, series = series, episode = ep,
            title = if (ep.isNotEmpty()) "$series Episode $ep" else img?.attr("alt")?.ifEmpty { null } ?: series,
            cover = cover, altCovers = alts(img, cover),
            lang = when { "RAW" in flags -> "RAW"; "SUB" in flags -> "EN"; else -> "" },
            tags = listOfNotNull("uncensored".takeIf { "UNC" in flags }),
        )
        return r.takeUnless { VideoSafety.blocked(it) }
    }

    /** `article.series-card` of the series lists (popular, genre, search): one series with its genres. */
    private fun seriesCard(c: Element): VideoRef? {
        val a = c.selectFirst(".sc-title a[href]") ?: return null
        val url = a.attr("abs:href")
        val slug = slugOf(url)
        val name = a.text()
        val genres = c.select(".sc-genres a[href*='/genre/']").map { slugOf(it.attr("href")) }
        if (genres.any { it in HIDDEN }) { block(slug, name); return null }
        if (slug in blockedSlugs || norm(name) in blockedNames) return null
        val img = c.selectFirst("img")
        val cover = img?.attr("abs:src").orEmpty()
        val r = VideoRef(
            source = id, id = SERIES + slug, title = name, url = url, series = name,
            cover = cover, altCovers = alts(img, cover),
            views = shortViews(c.select(".sc-meta .sc-tag").map { it.text() }.firstOrNull { "views" in it }.orEmpty()),
            tags = genres + "series:$slug",
        )
        return r.takeUnless { VideoSafety.blocked(it) }
    }

    private suspend fun episodes(url: String): VideoPage {
        safetyCheck()
        val doc = page(url)
        return VideoPage(doc.select("article.item.se").mapNotNull { episodeCard(it) }, hasNext(doc))
    }

    private suspend fun seriesList(url: String): VideoPage {
        safetyCheck()
        val doc = page(url)
        return VideoPage(doc.select("article.series-card").mapNotNull { seriesCard(it) }, hasNext(doc))
    }

    private fun pg(page: Int) = if (page > 1) "page/$page/" else ""

    override suspend fun latest(page: Int) = episodes("$baseUrl/episodes/${pg(page)}")

    /** Series by all-time views (the site also has ?filter=weekly / monthly / rating / recent). */
    override suspend fun popular(page: Int) = seriesList("$baseUrl/hentai-series/${pg(page)}?filter=alltime")

    override suspend fun search(q: SearchQuery, page: Int): VideoPage {
        val text = q.text.trim()
        val inc = q.include.map { it.id }
        val exc = q.exclude.map { it.id }.toSet()
        val r = when {
            text.isNotEmpty() -> seriesList("$baseUrl/${pg(page)}?s=${text.urlEncode()}")
            inc.isNotEmpty() -> seriesList("$baseUrl/genre/${inc.first()}/${pg(page)}?filter=alltime")
            else -> popular(page)
        }
        // the site browses one genre at a time (its advanced search answered nothing): the rest is checked on the cards
        val rest = if (text.isNotEmpty()) inc else inc.drop(1)
        return r.copy(items = r.items.filter { v -> rest.all { it in v.tags } && v.tags.none { it in exc } })
    }

    // ---------- series ----------

    private class SeriesInfo(val slug: String, val name: String, val genres: List<String>, val description: String, val episodes: List<VideoRef>)

    private val seriesCache = ConcurrentHashMap<String, SeriesInfo>()

    /** A tvshows page: name, genres and every episode in order (one page, no paging seen). */
    private suspend fun seriesInfo(slug: String): SeriesInfo = seriesCache[slug] ?: run {
        val doc = page("$baseUrl/tvshows/$slug/")
        val name = doc.selectFirst("h1.dsc-title")?.text().orEmpty().ifEmpty { slug }
        val genres = doc.select(".dsc-genres a[href*='/genre/']").map { slugOf(it.attr("href")) }
        if (genres.any { it in HIDDEN }) { block(slug, name); throw SourceException("This video is hidden") }
        val eps = doc.select("a.dt-se-item[href]").mapNotNull { a ->
            val url = a.attr("abs:href")
            val ep = Regex("(\\d+(?:\\.\\d+)?)").find(a.selectFirst(".dt-se-title")?.text().orEmpty())?.groupValues?.get(1).orEmpty()
            val img = a.selectFirst("img")
            val cover = img?.attr("abs:src").orEmpty()
            VideoRef(
                source = id, id = slugOf(url), url = url, series = name, episode = ep,
                title = if (ep.isNotEmpty()) "$name Episode $ep" else name,
                cover = cover, altCovers = alts(img, cover), tags = genres + "series:$slug",
            ).takeUnless { VideoSafety.blocked(it) }
        }.sortedBy { episodeNumber(it.episode) }
        SeriesInfo(slug, name, genres, doc.selectFirst(".dsc-desc")?.text().orEmpty(), eps).also { seriesCache[slug] = it }
    }

    private fun seriesSlug(ref: VideoRef) =
        if (ref.id.startsWith(SERIES)) ref.id.removePrefix(SERIES)
        else ref.tags.firstOrNull { it.startsWith("series:") }?.removePrefix("series:")?.ifEmpty { null }

    override suspend fun series(ref: VideoRef): List<VideoRef> {
        safetyCheck()
        val slug = seriesSlug(ref) ?: details(ref).ref.tags.firstOrNull { it.startsWith("series:") }?.removePrefix("series:") ?: return listOf(ref)
        if (slug in blockedSlugs) return emptyList()
        return seriesInfo(slug).episodes
    }

    // ---------- details + stream ----------

    override suspend fun details(ref: VideoRef): VideoDetails {
        safetyCheck()
        if (ref.id.startsWith(SERIES)) {
            // a series card: play its first episode
            val slug = ref.id.removePrefix(SERIES)
            if (slug in blockedSlugs) throw SourceException("This video is hidden")
            val first = seriesInfo(slug).episodes.firstOrNull() ?: throw SourceException("No episodes yet")
            return details(first)
        }
        val epUrl = ref.url.ifEmpty { "$baseUrl/episodes/${ref.id}/" }
        val doc = page(epUrl)
        val html = doc.html()
        val genres = doc.select(".sgeneros a[href*='/genre/']").map { slugOf(it.attr("href")) }
        val seriesA = doc.selectFirst(".episode-series-img a[href*='/tvshows/']") ?: doc.selectFirst("#pag_episodes a[href*='/tvshows/']")
        val slug = seriesA?.attr("href")?.let { slugOf(it) }.orEmpty()
        val seriesName = seriesA?.attr("title")?.ifEmpty { null } ?: ref.series
        if (genres.any { it in HIDDEN }) { block(slug, seriesName); throw SourceException("This video is hidden") }
        if (episodeBlocked(ref.id, seriesName) || slug in blockedSlugs) throw SourceException("This video is hidden")
        // the episode page lists only some of the series' genres: the series page is the full check (and the episode list)
        val info = if (slug.isEmpty()) null else seriesInfo(slug)

        val postId = Regex("get_player_contents',\\s*a:\\s*'(\\d+)'").find(html)?.groupValues?.get(1)
            ?: doc.selectFirst("link[rel=shortlink]")?.attr("href")?.substringAfter("?p=", "")?.ifEmpty { null }
            ?: throw SourceException("No video on this page")
        val opts = doc.select(".dt-mi-tabs a[href^='#option-']").mapNotNull { it.attr("href").removePrefix("#option-").toIntOrNull() }
        val stream = stream(postId, opts, epUrl)

        val title = doc.selectFirst("h1.epih1")?.text().orEmpty()
        val ep = ref.episode.ifEmpty { Regex("Episode\\s+(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1).orEmpty() }
        val allGenres = (genres + info?.genres.orEmpty()).distinct()
        val me = ref.copy(
            title = if (ep.isNotEmpty() && seriesName.isNotEmpty()) "$seriesName Episode $ep" else ref.title.ifEmpty { title },
            series = info?.name ?: seriesName, episode = ep, url = epUrl,
            cover = ref.cover.ifEmpty { doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty() },
            tags = (ref.tags.filter { !it.startsWith("series:") } + allGenres + listOfNotNull(slug.ifEmpty { null }?.let { "series:$it" })).distinct(),
        )
        val eps = info?.episodes.orEmpty().map { if (it.id == me.id) me else it }.let { l -> if (l.none { it.id == me.id }) (l + me).sortedBy { episodeNumber(it.episode) } else l }
        val d = VideoDetails(
            ref = me, stream = stream, episodes = eps.ifEmpty { listOf(me) }, tags = allGenres,
            description = doc.selectFirst("#info .episode-desc")?.text()?.ifEmpty { null } ?: info?.description.orEmpty(),
        )
        VideoSafety.check(d)
        return d
    }

    /** Embed kinds, best first: r2-mp4 / rtmp are plain MP4s on gdvid.info that do not expire; hls-mp4, hls and
     *  newjav hand out signed URLs that run out after about an hour. */
    private val PREFER = listOf("r2-mp4", "rtmp", "hls-mp4", "hls", "newjav")

    /** Asks admin-ajax for the mirrors one by one (each answer fills only its own slot), stopping at a lasting MP4. */
    private suspend fun stream(postId: String, opts: List<Int>, referer: String): VideoStream {
        // the tabs show 5, 3, 1 (mi-1..3); 4 and 2 answer too. 5 was "r2-mp4" on every episode tried.
        val tries = (listOf(5) + opts + listOf(1, 3, 4, 2)).distinct()
        val found = mutableListOf<Pair<String, String>>() // kind to embed URL
        for (i in tries) {
            val frame = runCatching { playerFrame(postId, i, referer) }.getOrNull() ?: continue
            val kind = Regex("dt_embed=([\\w-]+)").find(frame)?.groupValues?.get(1) ?: if ("newjav" in frame) "newjav" else "other"
            found += kind to frame
            if (kind == "r2-mp4" || kind == "rtmp") runCatching { embed(kind, frame, referer) }.getOrNull()?.let { return it }
        }
        for ((kind, frame) in found.sortedBy { (k, _) -> PREFER.indexOf(k).let { if (it < 0) 9 else it } }) {
            if (kind == "r2-mp4" || kind == "rtmp") continue // already tried above
            runCatching { embed(kind, frame, referer) }.getOrNull()?.let { return it }
        }
        throw SourceException("No working mirror for this episode")
    }

    /** The iframe src of mirror [i], or null when that slot is empty. */
    private suspend fun playerFrame(postId: String, i: Int, referer: String): String? = pace.run {
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder().add("action", "get_player_contents").add("a", postId).add("i", i.toString()).build()
            val req = Request.Builder().url(AJAX).post(body).header("Referer", referer).header("X-Requested-With", "XMLHttpRequest").build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw SourceException("HentaiMama answered ${r.code}")
                val arr = Http.json.parseToJsonElement(r.body!!.string()).arr
                val slot = arr.getOrNull(i - 1).text
                Regex("src=\"([^\"]+)\"").find(slot)?.groupValues?.get(1)?.replace("&#038;", "&")?.replace("&amp;", "&")
            }
        }
    }

    /** Reads the embed page's jwplayer sources (1080p first) or newjav's `file:`. */
    private suspend fun embed(kind: String, url: String, referer: String): VideoStream? {
        val html = runCatching { page(url, mapOf("Referer" to referer)).html() }.getOrNull()
        if (html == null) {
            // the r2 file sits at gdvid.info/hls/<base64 p> (seen on every episode tried)
            if (kind != "r2-mp4") return null
            val p = Regex("[?&]p=([^&]+)").find(url)?.groupValues?.get(1) ?: return null
            val path = runCatching { String(java.util.Base64.getDecoder().decode(p.trimEnd('=').let { it + "=".repeat((4 - it.length % 4) % 4) })) }.getOrNull() ?: return null
            return VideoStream("https://gdvid.info/hls/$path", "mp4")
        }
        val block = Regex("sources:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
        val files = if (block != null) {
            Regex("\\{[^}]*\\}").findAll(block).mapNotNull { o ->
                // JSON keys on the site's embeds, bare JS keys on newjav.php
                val file = Regex("\"?file\"?\\s*:\\s*\"([^\"]+)\"").find(o.value)?.groupValues?.get(1)?.replace("\\/", "/") ?: return@mapNotNull null
                val type = Regex("\"?type\"?\\s*:\\s*\"(\\w+)\"").find(o.value)?.groupValues?.get(1).orEmpty()
                file to (if (type == "hls" || ".m3u8" in file) "hls" else "mp4")
            }.toList()
        } else {
            Regex("file:\\s*\"([^\"]+\\.(?:mp4|m3u8)[^\"]*)\"").findAll(html).map { m ->
                val f = m.groupValues[1].replace("\\/", "/")
                f to (if (".m3u8" in f) "hls" else "mp4")
            }.toList()
        }
        val (file, k) = files.firstOrNull() ?: return null
        // subtitles are burned into the video (no caption tracks on any mirror)
        return VideoStream(file, k)
    }
}
