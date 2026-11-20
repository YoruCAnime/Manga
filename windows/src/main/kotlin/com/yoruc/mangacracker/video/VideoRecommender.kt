package com.yoruc.mangacracker.video

import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.Tag
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Videos → "Recommended for you": the tags of what you favourite, finish and watch for a while (quick peeks don't
 * count), searched on the sites you watch most, plus one or two sites of the same kind you haven't tried that have
 * your top tags. Every site's genres take part. Anything watched or favourited and other episodes of shows you've
 * seen are left out; one card per show, the lists mixed so no site or tag fills the row.
 */
object VideoRecommender {
    /** Anime sites (the rest are adult): "discover" only suggests sites of the kind you already watch. */
    private val ANIME = setOf("anikoto", "animeheaven")
    /** Words that say nothing about taste. */
    private val PLAIN = setOf("hentai", "anime", "video", "videos", "english", "subbed", "sub", "dub", "dubbed", "raw", "episode", "hd",
        "1080p", "720p", "new", "tv", "ova", "ona", "movie", "special", "uncategorized", "preview")

    private var cache: List<VideoRef> = emptyList()
    /** Tags of liked videos fetched for this (not VideoRepo.details: those carry a stream link that expires). */
    private val fetchedTags = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private var cachedAt = 0L

    /** Filtered again on the way out: a switch turned on later (futanari / furry) applies to a list made before it. */
    fun cached(): List<VideoRef> = fresh(cache)

    /** Leaves out what was watched or favourited since the list was made (and other episodes of those shows), and
     *  anything a safety switch hides now; the row stays current without a new search. */
    fun fresh(list: List<VideoRef>): List<VideoRef> {
        val seen = VideoRepo.favs.value.values.map { it.ref } + VideoRepo.watch.value.values.map { it.ref }
        val keys = seen.mapTo(HashSet()) { it.key }
        val shows = seen.map { showKey(it) }.filter { it.length >= 3 }.toHashSet()
        return list.filterNot { r -> VideoSafety.blocked(r) || r.key in keys || showKey(r).let { it.length >= 3 && it in shows } }
    }

    /** A tag as a taste word: no "series:" / "cat:" markers, ids, plain words or hidden kinds. */
    private fun taste(t: String): String? {
        if (':' in t || t.all { it.isDigit() }) return null
        val n = Safety.norm(t)
        if (n.length < 2 || n in PLAIN || VideoSafety.badTag(t) || Safety.optTag(t, ai = false)) return null
        return n
    }

    /** Seasons, parts and sequels of a show count as the same show ("Shield Hero Season 2" = "Shield Hero"). */
    private val SEASON = Regex("""\b(season|part|cour)\s*\d+\b|\b\d+(st|nd|rd|th)\s+season\b|\s(ii|iii|iv|v|vi)\s*$|\s\d\s*$|\buncensored\b""", RegexOption.IGNORE_CASE)
    private fun showKey(r: VideoRef) = SeriesCovers.norm(SEASON.replace(r.series.ifEmpty { r.title }.substringBefore(':'), " "))

    /** Languages that only come up when you watch them (Spanish, raw, previews). */
    private val OPT_LANGS = setOf("ES", "RAW", "PV")

    suspend fun load(force: Boolean = false): List<VideoRef> = withContext(Dispatchers.IO) {
        if (!force && cache.isNotEmpty() && System.currentTimeMillis() - cachedAt < 3 * 3600_000L) return@withContext cached()
        val favs = VideoRepo.favs.value.values.map { it.ref }
        val watched = VideoRepo.watched().take(60)
        // how much each one says about you: favourite 4, finished 3, most of it 2, a minute or more 1, a peek 0
        val liked = LinkedHashMap<String, Pair<VideoRef, Int>>()
        favs.forEach { liked[it.key] = it to 4 }
        watched.forEach { w ->
            val k = when { w.done -> 3; w.percent >= 40 -> 2; w.positionMs >= 60_000 -> 1; else -> 0 }
            if (k > 0 && w.ref.key !in liked) liked[w.ref.key] = w.ref to k
        }
        val langs = liked.values.map { it.first.lang }.toSet()
        val seenKeys = (favs.map { it.key } + VideoRepo.watch.value.keys).toHashSet()
        val seenShows = (favs + VideoRepo.watch.value.values.map { it.ref }).map { showKey(it) }.filter { it.length >= 3 }.toHashSet()
        if (liked.isEmpty()) {
            cache = emptyList(); cachedAt = System.currentTimeMillis()
            return@withContext cache
        }

        // tags from the lists, from details opened this run, or (a few recent ones without any) fetched now
        fun tagsOf(r: VideoRef) = (r.tags + VideoRepo.details[r.key]?.tags.orEmpty() + fetchedTags[r.key].orEmpty()).mapNotNull { taste(it) }.distinct()
        coroutineScope {
            liked.values.map { it.first }.filter { tagsOf(it).isEmpty() }.take(4).map { r ->
                async {
                    val src = VideoSources.byId(r.source) ?: return@async
                    withTimeoutOrNull(15_000) { runCatching { src.details(r) }.getOrNull() }?.let { fetchedTags[r.key] = it.tags }
                }
            }.awaitAll()
        }

        val tagWeight = HashMap<String, Int>()
        val siteWeight = HashMap<String, Int>()
        liked.values.forEach { (r, k) ->
            tagsOf(r).forEach { tagWeight[it] = (tagWeight[it] ?: 0) + k }
            siteWeight[r.source] = (siteWeight[r.source] ?: 0) + k
        }
        val top = tagWeight.entries.sortedByDescending { it.value }.map { it.key }

        val used = siteWeight.entries.sortedByDescending { it.value }.mapNotNull { VideoSources.byId(it.key) }.take(4)
        val kinds = used.map { it.id in ANIME }.toSet()
        val others = VideoSources.all.filter { it !in used && (it.id in ANIME) in kinds }

        val lists = coroutineScope {
            // each site's genres, matched to your top tags by name or id
            suspend fun picks(src: VideoSource, n: Int): List<Tag> {
                val known = withTimeoutOrNull(15_000) { runCatching { src.genres() }.getOrNull() }.orEmpty()
                    .filter { !VideoSafety.badTag(it.label) && !Safety.optTag(it.label, ai = false) }
                return top.mapNotNull { t -> known.firstOrNull { Safety.norm(it.label) == t || Safety.norm(it.id) == t } }.distinctBy { it.id }.take(n)
            }
            fun search(src: VideoSource, tag: Tag?) = async {
                withTimeoutOrNull(20_000) {
                    runCatching { if (tag == null) src.popular(1).items else src.search(SearchQuery(include = listOf(tag)), 1).items }.getOrNull()
                }.orEmpty()
            }
            val jobs = mutableListOf<Deferred<List<VideoRef>>>()
            // sites you use: one search per top tag it has (single tags: some sites only take one), else its popular list
            used.map { src -> async { src to picks(src, 3) } }.awaitAll().forEach { (src, p) ->
                if (p.isEmpty()) jobs += search(src, null) else p.forEach { jobs += search(src, it) }
            }
            // sites you haven't tried: only when two of your top tags are there
            others.map { src -> async { src to picks(src, 2) } }.awaitAll().filter { it.second.size >= 2 }.take(2).forEach { (src, p) ->
                jobs += search(src, p.first())
            }
            jobs.awaitAll()
        }

        // mix the lists so one site or tag does not fill the row; one card per show
        val out = LinkedHashMap<String, VideoRef>()
        val shows = HashSet<String>()
        val its = lists.map { it.iterator() }
        var any = true
        while (any && out.size < 40) {
            any = false
            for (i in its) {
                while (i.hasNext()) {
                    any = true
                    val r = i.next()
                    val show = showKey(r)
                    if (r.key in seenKeys || VideoSafety.blocked(r) || (r.lang in OPT_LANGS && r.lang !in langs)) continue
                    if (show.length >= 3 && (show in seenShows || !shows.add(show))) continue
                    out.putIfAbsent(r.key, r)
                    break
                }
            }
        }
        cache = out.values.take(40)
        cachedAt = System.currentTimeMillis()
        cache
    }
}
