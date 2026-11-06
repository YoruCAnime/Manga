package com.yoruc.mangacracker.core

import com.yoruc.mangacracker.sources.Sources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Recommended for you": the genres you favourite and read most, searched on the sources you use,
 * plus "more like this" of what you read last. Titles you already have are left out.
 */
object Recommender {
    /** Image boards (single pictures) never show up in recommendations. */
    private val IMAGE_BOARDS = setOf("danbooru", "gelbooru")

    private var cache: List<MangaRef> = emptyList()
    private var cachedAt = 0L

    /** Filtered again on the way out: a filter switched on (hidden tags, AI) applies to a list made before it. */
    fun cached(): List<MangaRef> = cache.filterNot { Safety.blocked(it) }

    suspend fun load(force: Boolean = false): List<MangaRef> = withContext(Dispatchers.IO) {
        if (!force && cache.isNotEmpty() && System.currentTimeMillis() - cachedAt < 3 * 3600_000L) return@withContext cached()
        val lib = Repo.library.value.values
        val hist = Repo.history.value.values.sortedByDescending { it.time }
        val owned = (lib.map { it.ref.key } + hist.map { it.ref.key }).toHashSet()

        // taste: genre -> weight per source (favourites count double)
        val taste = HashMap<String, HashMap<String, Int>>()
        fun add(src: String, tags: Collection<String>, w: Int) {
            val m = taste.getOrPut(src) { HashMap() }
            // never steer towards minor tags, nor AI tags while those are hidden
            tags.forEach { t -> if (!Safety.isMinorTag(t) && !Safety.optTag(t)) m[t.lowercase()] = (m[t.lowercase()] ?: 0) + w }
        }
        lib.forEach { add(it.ref.source, it.genres.ifEmpty { it.ref.tags }, 2) }
        hist.take(30).forEach { h -> add(h.ref.source, Repo.detailsCache[h.ref.key]?.genres ?: h.ref.tags, 1) }

        val enabled = Sources.enabled().filter { it.id !in IMAGE_BOARDS }
        val usedSources = (lib.map { it.ref.source } + hist.map { it.ref.source })
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
            .mapNotNull { id -> enabled.firstOrNull { it.id == id } }
            .ifEmpty { enabled.filter { !it.nsfw }.ifEmpty { enabled } }
            .take(3)

        val lists = coroutineScope {
            val jobs = mutableListOf<kotlinx.coroutines.Deferred<List<MangaRef>>>()
            for (src in usedSources) {
                val top = taste[src.id].orEmpty().entries.sortedByDescending { it.value }.map { it.key }
                val known = runCatching { src.tags() }.getOrDefault(emptyList())
                val picks = top.mapNotNull { g ->
                    known.firstOrNull { it.label.equals(g, true) || it.id.equals(g, true) } ?: if (known.isEmpty()) Tag(g, g) else null
                }.take(4)
                // two searches per source: the top 2 genres together, then the next ones
                listOf(picks.take(2), picks.drop(2).take(2)).filter { it.isNotEmpty() }.forEach { inc ->
                    jobs += async { withTimeoutOrNull(20_000) { runCatching { src.search(SearchQuery(include = inc), 1).items }.getOrNull() }.orEmpty() }
                }
                if (picks.isEmpty()) jobs += async { withTimeoutOrNull(20_000) { runCatching { src.popular(1).items }.getOrNull() }.orEmpty() }
            }
            // more like the last three things read
            // (an AI title read earlier would bring "more like it": skipped while AI is hidden)
            hist.filter { h ->
                h.ref.source !in IMAGE_BOARDS && !Safety.blocked(h.ref) && Repo.detailsCache[h.ref.key]?.let { Safety.switchReason(it) } == null
            }.take(3).forEach { h ->
                val src = Sources.byId(h.ref.source) ?: return@forEach
                val d = Repo.detailsCache[h.ref.key]
                jobs += async {
                    withTimeoutOrNull(20_000) {
                        runCatching { src.related(h.ref, d ?: src.details(h.ref)) }.getOrNull()
                    }.orEmpty()
                }
            }
            jobs.awaitAll()
        }
        // interleave the lists so one genre does not fill the row
        val out = LinkedHashMap<String, MangaRef>()
        val its = lists.map { it.iterator() }
        var any = true
        while (any && out.size < 40) {
            any = false
            for (i in its) if (i.hasNext()) {
                any = true
                val r = i.next()
                if (r.key !in owned && r.source !in IMAGE_BOARDS && !Safety.blocked(r)) out.putIfAbsent(r.key, r)
            }
        }
        cache = out.values.toList()
        cachedAt = System.currentTimeMillis()
        cache
    }
}
