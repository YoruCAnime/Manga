package com.yoruc.mangacracker.video

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.core.WebCookieJar
import com.yoruc.mangacracker.core.arr
import com.yoruc.mangacracker.core.get
import com.yoruc.mangacracker.core.str
import com.yoruc.mangacracker.core.text
import com.yoruc.mangacracker.core.urlEncode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

/**
 * hstream.moe: Laravel + Livewire, server-rendered HTML, 25 episodes a page; every list is the /search page with
 * query parameters (order, search, tags[], blacklist[], page). An episode's files are named by the page's own player
 * call, POST /player/api {episode_id} (needs the XSRF cookie of the page as a header): a plain 720p H.264 MP4 and
 * English subtitles as .ass next to it. The 1080p / 4K streams are AV1 DASH (manifest.mpd), which the relay does not
 * carry, so the MP4 is played. Spec: _dev_tools/specs/hstream.md.
 */
object HstreamSource : VideoSource {
    override val id = "hstream"
    override val name = "Hstream"
    override val baseUrl = "https://hstream.moe"
    override val blurb = "hstream.moe · 720p MP4 · English subs"
    override val color = 0xFFE11D48L

    // list pages are heavy (about 450 KB: every card carries its preview gallery)
    private val pace = Pace(1000)

    // ---------- minors filter ----------

    /** The site has no such genres (2026-11-10); kept in case one appears. */
    private val HIDDEN = setOf("loli", "lolicon", "shota", "shotacon", "shoutacon")
    /**
     * Series hidden by name: the site does not tag them, so this is the list of series other sites file under their
     * minors genre (romaji slugs, compared without "the animation" and punctuation).
     */
    private val BLOCKED = ("toshi-densetsu-series nuki-nuki-zupposism gaki-ni-modotte-yarinaoshi juvenile-pornography-animation " +
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
        "valkyrie-hazard fushigi-no-kuni-no-succubus shoujo-ramune").split(' ').map { key(it) }.toSet()

    private fun key(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "").removeSuffix("theanimation").removeSuffix("animation")

    /** By slug or name; a long blocked name also covers its sequels ("<name>-2", "<name>-s2"). */
    private fun seriesBlocked(seriesSlug: String, name: String): Boolean {
        val a = key(seriesSlug)
        val b = key(name)
        return a in BLOCKED || b in BLOCKED || BLOCKED.any { it.length >= 14 && (a.startsWith(it) || b.startsWith(it)) }
    }

    // ---------- genres ----------

    // slug -> name (the search page's filter, 2026-11-10)
    private val GENRES = linkedMapOf(
        "ahegao" to "Ahegao", "bestiality" to "Bestiality", "bondage" to "Bondage", "creampie" to "Creampie", "gore" to "Gore",
        "harem" to "Harem", "incest" to "Incest", "lactation" to "Lactation", "lq" to "Low quality", "mind-break" to "Mind Break",
        "mind-control" to "Mind Control", "orc" to "Orc", "scat" to "Scat", "tentacle" to "Tentacle", "toys" to "Toys",
        "tsundere" to "Tsundere", "virgin" to "Virgin", "yuri" to "Yuri", "anal" to "Anal", "bdsm" to "BDSM", "facial" to "Facial",
        "blow-job" to "Blow Job", "boob-job" to "Boob Job", "foot-job" to "Foot Job", "hand-job" to "Hand Job", "rimjob" to "Rimjob",
        "inflation" to "Inflation", "masturbation" to "Masturbation", "public-sex" to "Public Sex", "rape" to "Rape",
        "reverse-rape" to "Reverse Rape", "threesome" to "Threesome", "orgy" to "Orgy", "gangbang" to "Gangbang", "milf" to "MILF",
        "futanari" to "Futanari", "big-boobs" to "Big Boobs", "small-boobs" to "Small Boobs", "dark-skin" to "Dark Skin",
        "cosplay" to "Cosplay", "elf" to "Elf", "maid" to "Maid", "nekomimi" to "Nekomimi", "nurse" to "Nurse",
        "school-girl" to "School Girl", "succubus" to "Succubus", "teacher" to "Teacher", "trap" to "Trap", "pregnant" to "Pregnant",
        "glasses" to "Glasses", "swim-suit" to "Swimsuit", "ugly-bastard" to "Ugly Bastard", "monster" to "Monster", "3d" to "3D",
        "4k" to "4K", "48fps" to "48 fps", "4k-48fps" to "4K 48 fps", "censored" to "Censored", "uncensored" to "Uncensored",
        "comedy" to "Comedy", "fantasy" to "Fantasy", "horror" to "Horror", "vanilla" to "Vanilla", "ntr" to "NTR", "pov" to "POV",
        "filmed" to "Filmed", "x-ray" to "X-Ray",
    )

    // the filter shows no per-genre counts, so count stays 0
    override suspend fun genres(): List<Tag> = GENRES.map { (s, n) -> Tag(s, n, "Genre") }

    // ---------- pages + cards ----------

    private suspend fun page(url: String, headers: Map<String, String> = emptyMap()): Document = pace.run { Http.getDoc(url, headers) }

    private fun slugOf(href: String) = href.substringBefore('?').trimEnd('/').substringAfterLast('/')

    /** "ntr-kishi-1" -> "ntr-kishi" (episode slugs are the series slug + "-<number>"). */
    private fun seriesSlugOf(slug: String) = if (slug.substringAfterLast('-').toIntOrNull() != null) slug.substringBeforeLast('-') else slug

    /** `div.group > a` of every list: title "Series - 3", a thumbnail, views. */
    private fun card(a: Element): VideoRef? {
        val url = a.attr("abs:href")
        val slug = slugOf(url)
        val img = a.selectFirst("img") ?: return null
        val full = a.selectFirst("h3")?.text()?.ifEmpty { null } ?: img.attr("alt")
        if (slug.isEmpty() || full.isEmpty()) return null
        val ep = full.substringAfterLast(" - ", "").takeIf { it.toFloatOrNull() != null }.orEmpty()
        val series = if (ep.isNotEmpty()) full.substringBeforeLast(" - ") else full
        if (seriesBlocked(seriesSlugOf(slug), series)) return null
        val cover = img.attr("abs:src")
        val r = VideoRef(
            source = id, id = slug, url = url, series = series, episode = ep,
            title = if (ep.isNotEmpty()) "$series Episode $ep" else full,
            cover = cover,
            // the same frame full size, then the series poster
            altCovers = listOfNotNull(
                cover.replace("-thumbnail.webp", ".webp").takeIf { it != cover },
                cover.substringBeforeLast('/', "").takeIf { it.isNotEmpty() && ep.isNotEmpty() }?.let { "$it/cover-ep-${ep.substringBefore('.')}.webp" },
            ),
            views = a.selectFirst("span:has(i.fa-eye)")?.text()?.trim().orEmpty().uppercase(),
            lang = "EN",
        )
        return r.takeUnless { VideoSafety.blocked(it) }
    }

    private fun cards(doc: Document): List<VideoRef> = doc.select("div.group > a[href*='/hentai/']:has(img)").mapNotNull { card(it) }

    /** Livewire's pager: a "Next" control (wire:click="nextPage") or a link to the page after this one. */
    private fun hasNext(doc: Document, page: Int) = doc.select("nav[role=navigation] a, nav[role=navigation] button").any {
        it.attr("wire:click") == "nextPage" || it.attr("aria-label") == "Go to page ${page + 1}"
    }

    private suspend fun list(order: String, page: Int, text: String = "", tags: List<String> = emptyList(), out: List<String> = emptyList()): VideoPage {
        val params = buildList {
            if (text.isNotEmpty()) add("search=${text.urlEncode()}")
            add("order=$order")
            tags.forEachIndexed { i, t -> add("tags%5B$i%5D=${t.urlEncode()}") }
            out.forEachIndexed { i, t -> add("blacklist%5B$i%5D=${t.urlEncode()}") }
            if (page > 1) add("page=$page")
        }
        val doc = page("$baseUrl/search?" + params.joinToString("&"))
        return VideoPage(cards(doc), hasNext(doc, page))
    }

    override suspend fun latest(page: Int) = list("recently-uploaded", page)
    override suspend fun popular(page: Int) = list("view-count", page)

    /** Words, genres (all of them must match) and left-out genres go to the site together. */
    override suspend fun search(q: SearchQuery, page: Int): VideoPage {
        val text = listOf(q.text, q.artist).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val inc = q.include.map { it.id }.filter { it !in HIDDEN }
        val exc = q.exclude.map { it.id }
        return list("recently-uploaded", page, text, inc, exc)
    }

    // ---------- series ----------

    private val seriesCache = ConcurrentHashMap<String, List<VideoRef>>()

    /** /hentai/<series>: the series' episode cards (the page also shows other series' new episodes). */
    private suspend fun seriesEpisodes(seriesSlug: String): List<VideoRef> = seriesCache[seriesSlug] ?: run {
        val doc = page("$baseUrl/hentai/$seriesSlug")
        cards(doc).filter { it.id.startsWith("$seriesSlug-") && it.id.removePrefix("$seriesSlug-").toIntOrNull() != null }
            .distinctBy { it.id }.sortedBy { episodeNumber(it.episode) }
            .also { if (seriesCache.size > 60) seriesCache.clear(); seriesCache[seriesSlug] = it }
    }

    override suspend fun series(ref: VideoRef): List<VideoRef> {
        val slug = seriesSlugOf(ref.id)
        if (seriesBlocked(slug, ref.series)) return emptyList()
        return runCatching { seriesEpisodes(slug) }.getOrNull()?.ifEmpty { null } ?: listOf(ref)
    }

    // ---------- details + stream ----------

    override suspend fun details(ref: VideoRef): VideoDetails {
        if (seriesBlocked(seriesSlugOf(ref.id), ref.series)) throw SourceException("This video is hidden")
        val url = ref.url.ifEmpty { "$baseUrl/hentai/${ref.id}" }
        var doc = page(url)
        val seriesSlug = doc.selectFirst("h1 a[href*='/hentai/']")?.attr("href")?.let { slugOf(it) }?.ifEmpty { null } ?: seriesSlugOf(ref.id)
        val full = doc.selectFirst("h1")?.text().orEmpty()
        val ep = ref.episode.ifEmpty { full.substringAfterLast(" - ", "").takeIf { it.toFloatOrNull() != null }.orEmpty() }
        val series = (if (" - " in full && full.substringAfterLast(" - ").toFloatOrNull() != null) full.substringBeforeLast(" - ") else full).ifEmpty { ref.series }
        val tags = (doc.select("h2:contains(Genres) + div a[href]").ifEmpty { doc.select("a[href*='tags%5B0%5D=']") })
            .map { it.attr("href").substringAfter("tags%5B0%5D=", "").substringBefore('&') }.filter { it.isNotEmpty() }.distinct()
        if (tags.any { it in HIDDEN } || seriesBlocked(seriesSlug, series)) throw SourceException("This video is hidden")

        var eid = doc.selectFirst("input#e_id")?.attr("value").orEmpty()
        if (eid.isEmpty()) throw SourceException("No video on this page")
        var data = playerData(eid, url)
        if (data == null) {
            // the session cookie ran out (the page came from the cache): a fresh page, then once more
            doc = page(url, mapOf("Cache-Control" to "no-cache"))
            eid = doc.selectFirst("input#e_id")?.attr("value").orEmpty().ifEmpty { eid }
            data = playerData(eid, url) ?: throw SourceException("Hstream did not hand out the video (session refused)")
        }

        val me = ref.copy(
            title = if (ep.isNotEmpty() && series.isNotEmpty()) "$series Episode $ep" else ref.title.ifEmpty { full },
            series = series, episode = ep, url = url, lang = "EN",
            cover = ref.cover.ifEmpty { doc.selectFirst("meta[property=og:image]")?.attr("content").orEmpty() },
            tags = tags,
        )
        val eps = runCatching { seriesEpisodes(seriesSlug) }.getOrDefault(emptyList()).map { if (it.id == me.id) me else it }
            .let { l -> if (l.none { it.id == me.id }) (l + me).sortedBy { episodeNumber(it.episode) } else l }
        val d = VideoDetails(
            ref = me, stream = stream(data), episodes = eps, tags = tags,
            description = doc.selectFirst("h2:contains(Description) + p")?.text().orEmpty(),
        )
        VideoSafety.check(d)
        return d
    }

    /**
     * The player's own call. Laravel wants the XSRF-TOKEN cookie (set by the episode page) back as a header;
     * null = "CSRF token mismatch" (HTTP 419), the caller reloads the page and asks again.
     */
    private suspend fun playerData(episodeId: String, referer: String): JsonElement? = pace.run {
        withContext(Dispatchers.IO) {
            val xsrf = WebCookieJar.get(baseUrl, "XSRF-TOKEN")?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: return@withContext null
            val body = "{\"episode_id\":\"$episodeId\"}".toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$baseUrl/player/api").post(body)
                .header("X-XSRF-TOKEN", xsrf).header("X-Requested-With", "XMLHttpRequest").header("Accept", "application/json")
                .header("Origin", baseUrl).header("Referer", referer).build()
            Http.client.newCall(req).execute().use { r ->
                if (r.code == 419 || r.code == 401) return@use null
                if (!r.isSuccessful) throw SourceException("Hstream answered ${r.code}")
                Http.json.parseToJsonElement(r.body!!.string())
            }
        }
    }

    /**
     * `<server>/<stream_url>/x264.720p.mp4` (H.264 + AAC, Range works, no Referer needed) and `eng.ass` beside it.
     */
    private fun stream(data: JsonElement): VideoStream {
        val path = data.str("stream_url").trim('/').replace(" ", "%20")
        val server = data["stream_domains"].arr.firstNotNullOfOrNull { it.text.ifEmpty { null } }
            ?: data["asia_stream_domains"].arr.firstNotNullOfOrNull { it.text.ifEmpty { null } }
        if (path.isEmpty() || server == null) throw SourceException("No playable video on this page")
        val base = "${server.trimEnd('/')}/$path"
        // `extra_subtitles` names machine translations (autotrans/<lang>.ass), but those files answered 404: English only
        return VideoStream("$base/x264.720p.mp4", "mp4", listOf(VideoSub("$base/eng.ass", "en", "English")))
    }
}
