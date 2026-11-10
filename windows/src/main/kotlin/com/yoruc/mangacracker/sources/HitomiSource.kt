package com.yoruc.mangacracker.sources

import com.yoruc.mangacracker.core.Chapter
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.MangaDetails
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Page
import com.yoruc.mangacracker.core.PageResult
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SelectDef
import com.yoruc.mangacracker.core.Source
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.core.TagGroup
import com.yoruc.mangacracker.core.arr
import com.yoruc.mangacracker.core.get
import com.yoruc.mangacracker.core.int
import com.yoruc.mangacracker.core.str
import com.yoruc.mangacracker.core.text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * hitomi.la through its data host (the website itself answers 404 from the UK; the data host and image hosts do not).
 * Lists are .nozomi files (big-endian int32 gallery ids, paged with HTTP Range), gallery info is galleries/<id>.js,
 * image hosts come from gg.js (changes every hour, so it is fetched at run time). Spec: _dev_tools/specs/hitomi.md
 */
object HitomiSource : Source {
    override val id = "hitomi"
    override val name = "hitomi.la"
    override val baseUrl = "https://hitomi.la"
    override val blurb = "hitomi.la · doujinshi"
    override val color = 0xFFF59E0B
    override val nsfw = true
    override val hasChapters = false

    private const val DOMAIN2 = "gold-usergeneratedcontent.net"
    private const val LTN = "https://ltn.$DOMAIN2"
    private const val TAGINDEX = "https://tagindex.hitomi.la"
    private const val PER_PAGE = 25

    /** Tags about minors: their galleries are subtracted from every list and refused in details(). */
    private val MINOR_LISTS = listOf(
        "female:loli", "male:shota", "female:toddlercon", "male:toddlercon", "female:oppai loli",
        "tag:kodomo doushi", "female:kodomo doushi", "male:kodomo doushi", "tag:kodomo only",
    )
    private val MINOR_NAMES = setOf("loli", "shota", "toddlercon", "oppai loli", "kodomo doushi", "kodomo only", "lolicon", "shotacon")

    init {
        // images (w1/w2, a1/a2, tn) and the data host all want the site's Referer; without it they answer 404
        Http.referers[DOMAIN2] = "https://hitomi.la/"
        Http.referers["tagindex.hitomi.la"] = "https://hitomi.la/"
        Http.rateLimits["ltn.$DOMAIN2"] = 100L
        Http.rateLimits["tagindex.hitomi.la"] = 250L
    }

    override val selects = listOf(
        SelectDef("sort", "Sort", listOf("added" to "Newest", "published" to "Newest published", "popular-today" to "Popular today",
            "popular-week" to "Popular this week", "popular-month" to "Popular this month", "popular-year" to "Popular this year")),
        SelectDef("lang", "Language", listOf("all" to "Any", "english" to "English", "japanese" to "Japanese", "chinese" to "Chinese",
            "korean" to "Korean", "spanish" to "Spanish", "portuguese" to "Portuguese", "french" to "French", "german" to "German",
            "russian" to "Russian", "vietnamese" to "Vietnamese", "indonesian" to "Indonesian", "thai" to "Thai")),
        SelectDef("type", "Type", listOf("" to "Any", "doujinshi" to "Doujinshi", "manga" to "Manga", "artistcg" to "Artist CG",
            "gamecg" to "Game CG", "imageset" to "Image set")),
    )

    // ---------- raw reads ----------

    private fun guard(code: Int) {
        if (code == 451) throw SourceException("hitomi.la's data host is blocked on this connection (451)")
    }

    /** bytes [from, to] of a file + the file's full length (0 bytes / 0 when missing). */
    private suspend fun range(url: String, from: Long, to: Long): Pair<ByteArray, Long> = withContext(Dispatchers.IO) {
        val req = Http.request(url, mapOf("Range" to "bytes=$from-$to", "Cache-Control" to "no-store"))
        Http.client.newCall(req).execute().use { r ->
            guard(r.code)
            when (r.code) {
                404, 416 -> ByteArray(0) to 0L
                200, 206 -> {
                    val b = r.body?.bytes() ?: ByteArray(0)
                    val total = r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: b.size.toLong()
                    // a server that ignores Range sends the whole file: cut our slice out of it
                    val slice = if (r.code == 200 && b.size > to - from + 1) b.copyOfRange(from.toInt().coerceAtMost(b.size), (to + 1).toInt().coerceAtMost(b.size)) else b
                    slice to total
                }
                else -> throw SourceException("hitomi.la answered ${r.code}")
            }
        }
    }

    private fun ints(b: ByteArray): IntArray {
        val buf = ByteBuffer.wrap(b) // big-endian by default
        return IntArray(b.size / 4) { buf.getInt(it * 4) }
    }

    // ---------- gg.js: image hosts (changes every hour) ----------

    private class GG(val b: String, val default: Int, val cases: Map<Int, Int>) {
        fun m(g: Int) = cases[g] ?: default
    }
    private var ggCache: Pair<Long, GG>? = null
    private val ggLock = Mutex()

    private suspend fun gg(): GG = ggLock.withLock {
        ggCache?.takeIf { System.currentTimeMillis() - it.first < 5 * 60_000L }?.let { return it.second }
        val (code, js) = Http.getRaw("$LTN/gg.js?_=${System.currentTimeMillis()}", mapOf("Cache-Control" to "no-store"))
        guard(code)
        if (code != 200) throw SourceException("hitomi.la's gg.js answered $code")
        val default = Regex("var o = (\\d+)").find(js)?.groupValues?.get(1)?.toInt() ?: 0
        val cases = HashMap<Int, Int>()
        val pending = ArrayList<Int>()
        // "case 602: case 1976: ... o = 1; break;" in source order
        Regex("case (\\d+):|o = (\\d+);\\s*break;").findAll(js).forEach { m ->
            if (m.groupValues[1].isNotEmpty()) pending += m.groupValues[1].toInt()
            else { val o = m.groupValues[2].toInt(); pending.forEach { cases[it] = o }; pending.clear() }
        }
        val b = Regex("b: '(\\d+)/'").find(js)?.groupValues?.get(1) ?: throw SourceException("hitomi.la changed gg.js")
        val g = GG("$b/", default, cases)
        ggCache = System.currentTimeMillis() to g
        g
    }

    /** gg.s(): the last three hex digits, "abc" -> 0xcab */
    private fun s(hash: String): Int = (hash.last() + hash.substring(hash.length - 3, hash.length - 1)).toInt(16)

    /** Full page picture: https://w{1|2}.<domain>/<b><s>/<hash>.webp (avif would be a{1|2}, Android 12+ only). */
    private fun pageUrl(gg: GG, hash: String, ext: String = "webp"): String {
        val g = s(hash)
        return "https://${if (ext == "avif") "a" else "w"}${1 + gg.m(g)}.$DOMAIN2/${gg.b}$g/$hash.$ext"
    }

    /** Cover: the plain tn. host serves every thumbnail (no gg needed, so saved covers keep working). */
    private fun thumbUrl(hash: String): String =
        if (hash.length < 3) "" else "https://tn.$DOMAIN2/webpbigtn/${hash.last()}/${hash.substring(hash.length - 3, hash.length - 1)}/$hash.webp"

    // ---------- nozomi lists ----------

    /** One list file. [area] null = the main index; sort = added | published | popular-<today|week|month|year>. */
    private fun nozomiPath(area: String?, tag: String, lang: String, sort: String): String {
        val key = sort.substringAfter("popular-", "")
        val p = when {
            area == null && sort == "published" -> "date/published-$lang"
            area == null && key.isNotEmpty() -> "popular/$key-$lang"
            area == null -> "index-$lang"
            sort == "published" -> "$area/date/published/$tag-$lang"
            key.isNotEmpty() -> "$area/popular/$key/$tag-$lang"
            else -> "$area/$tag-$lang"
        }
        return p.split('/').joinToString("/") { seg -> java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20").replace("%3A", ":") }
    }

    private suspend fun nozomiPage(path: String, page: Int): Pair<IntArray, Long> {
        val from = (page - 1L) * PER_PAGE * 4
        val (b, total) = range("$LTN/$path.nozomi", from, from + PER_PAGE * 4 - 1)
        return ints(b) to total / 4
    }

    private data class Whole(val at: Long, val ids: IntArray)
    private val wholeCache = object : LinkedHashMap<String, Whole>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Whole>?) = size > 10
    }

    /** A whole list (for set maths), through the gzip "n/" copy the site's own search uses. Cached 10 minutes. */
    private suspend fun nozomiAll(path: String): IntArray {
        synchronized(wholeCache) { wholeCache[path]?.takeIf { System.currentTimeMillis() - it.at < 10 * 60_000L }?.let { return it.ids } }
        val ids = withContext(Dispatchers.IO) {
            Http.client.newCall(Http.request("$LTN/n/$path.nozomi", mapOf("Cache-Control" to "no-store"))).execute().use { r ->
                guard(r.code)
                when (r.code) {
                    404 -> IntArray(0)
                    200 -> ints(r.body?.bytes() ?: ByteArray(0))
                    else -> throw SourceException("hitomi.la answered ${r.code}")
                }
            }
        }
        synchronized(wholeCache) { wholeCache[path] = Whole(System.currentTimeMillis(), ids) }
        return ids
    }

    // minors: every id under the MINOR_LISTS tags, sorted for binary search, refreshed every 6 hours
    private var minors: Pair<Long, IntArray>? = null
    private val minorLock = Mutex()

    private suspend fun minorIds(): IntArray = minorLock.withLock {
        minors?.takeIf { System.currentTimeMillis() - it.first < 6 * 3600_000L }?.let { return it.second }
        val all = MINOR_LISTS.map { t -> val (area, tag) = areaOf(t); runCatching { nozomiAll(nozomiPath(area, tag, "all", "added")) }.getOrNull() }
        val got = all.filterNotNull()
        val merged = IntArray(got.sumOf { it.size }).also { out -> var i = 0; got.forEach { it.copyInto(out, i); i += it.size } }
        merged.sort()
        // only remember a complete set; a partial one is used once (details() still checks every gallery's tags)
        if (got.size == all.size) minors = System.currentTimeMillis() to merged
        merged
    }

    private fun IntArray.has(id: Int) = java.util.Arrays.binarySearch(this, id) >= 0

    /** "female:big breasts" -> ("tag", "female:big breasts"), "artist:x" -> ("artist", "x"), "full color" -> ("tag", "full color") */
    private fun areaOf(id: String): Pair<String, String> {
        val ns = id.substringBefore(':', "").lowercase()
        val name = id.substringAfter(':').trim().lowercase().replace('_', ' ')
        return when (ns) {
            "female", "male" -> "tag" to "$ns:$name"
            "", "tag" -> "tag" to name
            "parody", "series" -> "series" to name
            else -> ns to name
        }
    }

    // ---------- text search: galleriesindex B-tree (as search.js does it) ----------

    private var indexVersion: Pair<Long, String>? = null

    private suspend fun galleriesVersion(): String {
        indexVersion?.takeIf { System.currentTimeMillis() - it.first < 10 * 60_000L }?.let { return it.second }
        val (code, v) = Http.getRaw("$LTN/galleriesindex/version?_=${System.currentTimeMillis()}", mapOf("Cache-Control" to "no-store"))
        guard(code)
        if (code != 200 || v.isBlank()) throw SourceException("hitomi.la's search index is not answering ($code)")
        indexVersion = System.currentTimeMillis() to v.trim()
        return v.trim()
    }

    private class Node(val keys: List<ByteArray>, val datas: List<Pair<Long, Int>>, val subs: LongArray)

    private fun decodeNode(b: ByteArray): Node {
        val buf = ByteBuffer.wrap(b)
        val keys = List(buf.getInt()) { val n = buf.getInt(); require(n in 1..32); ByteArray(n).also { buf.get(it) } }
        val datas = List(buf.getInt()) { buf.getLong() to buf.getInt() }
        val subs = LongArray(17) { buf.getLong() }
        return Node(keys, datas, subs)
    }

    private fun cmp(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i].toInt() and 0xff; val y = b[i].toInt() and 0xff
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }

    /** Gallery ids whose title / tags hold the word (newest first). */
    private suspend fun textIds(word: String): IntArray {
        val key = MessageDigest.getInstance("SHA-256").digest(word.toByteArray()).copyOf(4)
        val v = galleriesVersion()
        val path = "galleriesindex/galleries.$v"
        synchronized(wholeCache) { wholeCache["$path#$word"]?.takeIf { System.currentTimeMillis() - it.at < 10 * 60_000L }?.let { return it.ids } }
        var addr = 0L
        repeat(24) {
            val (b, _) = range("$LTN/$path.index", addr, addr + 463)
            if (b.isEmpty()) return IntArray(0)
            val node = runCatching { decodeNode(b) }.getOrNull() ?: return IntArray(0)
            var i = 0; var c = -1
            while (i < node.keys.size) { c = cmp(key, node.keys[i]); if (c <= 0) break; i++ }
            if (node.keys.isNotEmpty() && c == 0) {
                val (off, len) = node.datas[i]
                if (len <= 0 || len > 100_000_000) return IntArray(0)
                val (d, _) = range("$LTN/$path.data", off, off + len - 1)
                val all = ints(d)
                val n = all.firstOrNull() ?: 0
                val ids = if (n > 0 && all.size >= n + 1) all.copyOfRange(1, n + 1) else IntArray(0)
                synchronized(wholeCache) { wholeCache["$path#$word"] = Whole(System.currentTimeMillis(), ids) }
                return ids
            }
            if (node.subs.all { it == 0L } || node.subs[i] == 0L) return IntArray(0)
            addr = node.subs[i]
        }
        return IntArray(0)
    }

    // ---------- gallery info ----------

    private class FileInfo(val hash: String, val width: Int, val height: Int, val avif: Boolean)
    private class Info(
        val id: String, val title: String, val jtitle: String, val type: String, val language: String, val languageLocal: String,
        val tags: List<String>, val files: List<FileInfo>, val date: String, val related: List<Int>, val blocked: Boolean,
    ) {
        fun ns(prefix: String) = tags.filter { it.startsWith("$prefix:") }.map { it.substringAfter(':') }
        val minor: Boolean get() = tags.any { it.substringAfter(':') in MINOR_NAMES }
    }

    private val infos = ConcurrentHashMap<Int, Info>()
    private val gate = Semaphore(6)

    private suspend fun info(gid: Int): Info? {
        infos[gid]?.let { return it }
        val (code, body) = Http.getRaw("$LTN/galleries/$gid.js")
        guard(code)
        if (code == 404) return null
        if (code !in 200..299) throw SourceException("hitomi.la answered $code")
        val j = runCatching { Http.json.parseToJsonElement(body.substring(body.indexOf('{'))) }.getOrNull() ?: return null
        fun list(key: String, field: String, ns: String) = j[key].arr.mapNotNull { it.str(field).ifEmpty { null }?.let { n -> "$ns:$n" } }
        val tags = j["tags"].arr.map { t ->
            val ns = when { t.str("female") == "1" -> "female"; t.str("male") == "1" -> "male"; else -> "tag" }
            "$ns:${t.str("tag")}"
        } + list("artists", "artist", "artist") + list("groups", "group", "group") + list("parodys", "parody", "series") +
            list("characters", "character", "character") +
            listOfNotNull(j.str("language").ifEmpty { null }?.let { "language:$it" }, j.str("type").ifEmpty { null }?.let { "type:$it" })
        val info = Info(
            id = j.str("id").ifEmpty { gid.toString() },
            title = j.str("title"), jtitle = j.str("japanese_title"), type = j.str("type"),
            language = j.str("language"), languageLocal = j.str("language_localname"),
            tags = tags,
            files = j["files"].arr.map { FileInfo(it.str("hash"), it.int("width"), it.int("height"), it.int("hasavif") == 1) },
            date = j.str("date"),
            related = j["related"].arr.mapNotNull { it.text.toIntOrNull() },
            blocked = j.int("blocked") != 0,
        )
        if (infos.size > 800) infos.clear()
        infos[gid] = info
        return info
    }

    private fun toRef(i: Info) = MangaRef(
        source = id, id = i.id,
        title = i.title.ifEmpty { i.jtitle }.ifEmpty { "Gallery ${i.id}" },
        cover = i.files.firstOrNull()?.let { thumbUrl(it.hash) }.orEmpty(),
        tags = i.tags,
        subtitle = "${i.files.size} pages",
    )

    /** Galleries for these ids, 6 at a time; hidden, removed and video galleries dropped. */
    private suspend fun items(ids: List<Int>): List<MangaRef> = coroutineScope {
        ids.map { gid -> async { gate.withPermit { runCatching { info(gid) }.getOrNull() } } }.awaitAll()
            .filterNotNull().filter { !it.blocked && !it.minor && it.type != "anime" && it.files.isNotEmpty() }.map { toRef(it) }
    }

    // ---------- lists ----------

    /** A list paged straight from one file with Range reads (no set maths needed). */
    private suspend fun paged(path: String, page: Int): PageResult {
        val (ids, total) = nozomiPage(path, page)
        val hidden = runCatching { minorIds() }.getOrDefault(IntArray(0))
        return PageResult(items(ids.filterNot { hidden.has(it) }), page.toLong() * PER_PAGE < total)
    }

    override suspend fun latest(page: Int) = paged(nozomiPath(null, "index", "all", "added"), page)
    override suspend fun popular(page: Int) = paged(nozomiPath(null, "index", "all", "popular-week"), page)

    private data class Term(val area: String?, val tag: String, val word: String = "")

    private val results = object : LinkedHashMap<String, Whole>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Whole>?) = size > 6
    }

    override suspend fun search(query: SearchQuery, page: Int): PageResult {
        var lang = query.selects["lang"]?.ifEmpty { null } ?: "all"
        val sort = query.selects["sort"]?.ifEmpty { null } ?: "added"
        val pos = mutableListOf<Term>()
        val neg = mutableListOf<Term>()

        fun tagTerm(id: String): Term? {
            val (area, tag) = areaOf(id)
            if (area == "language") { lang = tag; return null }
            return Term(area, tag)
        }
        query.selects["type"]?.takeIf { it.isNotEmpty() }?.let { pos += Term("type", it) }
        if (query.artist.isNotBlank()) pos += Term("artist", query.artist.trim().lowercase())
        query.include.forEach { t -> tagTerm(t.id)?.let { pos += it } }
        query.exclude.forEach { t -> tagTerm(t.id)?.let { neg += it } }
        // typed words: "maid", "-yaoi", "female:big_breasts", "-male:yaoi"
        query.text.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it != "-" }.forEach { w ->
            val minus = w.startsWith("-")
            val t = w.removePrefix("-").replace('_', ' ')
            val term = if (':' in t) tagTerm(t) else Term(null, "", t)
            if (term != null) { if (minus) neg.add(term) else pos.add(term) }
        }

        // simple case: at most one list and nothing to subtract -> Range paging, no big downloads
        if (neg.isEmpty() && pos.size <= 1 && pos.none { it.word.isNotEmpty() }) {
            val t = pos.firstOrNull()
            return paged(nozomiPath(t?.area, t?.tag ?: "index", lang, sort), page)
        }

        val key = "$lang|$sort|" + pos.joinToString(",") + "|" + neg.joinToString(",")
        val all = synchronized(results) { results[key]?.takeIf { System.currentTimeMillis() - it.at < 10 * 60_000L }?.ids }
            ?: run {
                suspend fun idsOf(t: Term, ordered: Boolean): IntArray =
                    if (t.word.isNotEmpty()) textIds(t.word)
                    else nozomiAll(nozomiPath(t.area, t.tag, lang, if (ordered) sort else "added"))
                // the base decides the order: the first list term, else the index in the chosen sort (as results.js)
                val firstTag = pos.firstOrNull { it.word.isEmpty() }
                val rest = pos.toMutableList()
                var base: IntArray = when {
                    firstTag != null -> { rest.remove(firstTag); idsOf(firstTag, true) }
                    pos.isEmpty() || sort != "added" -> nozomiAll(nozomiPath(null, "index", lang, sort))
                    else -> { val w = rest.removeAt(0); textIds(w.word).also { if (lang != "all") rest += Term(null, "index") } }
                }
                for (t in rest) {
                    val other = if (t.area == null && t.word.isEmpty()) nozomiAll(nozomiPath(null, "index", lang, "added")) else idsOf(t, false)
                    val set = other.copyOf().also { it.sort() }
                    base = base.filter { set.has(it) }.toIntArray()
                }
                for (t in neg) {
                    val set = idsOf(t, false).copyOf().also { it.sort() }
                    base = base.filterNot { set.has(it) }.toIntArray()
                }
                val hidden = runCatching { minorIds() }.getOrDefault(IntArray(0))
                base = base.filterNot { hidden.has(it) }.toIntArray()
                synchronized(results) { results[key] = Whole(System.currentTimeMillis(), base) }
                base
            }
        val from = (page - 1) * PER_PAGE
        if (from >= all.size) return PageResult(emptyList(), false)
        val to = minOf(all.size, from + PER_PAGE)
        return PageResult(items(all.copyOfRange(from, to).toList()), to < all.size)
    }

    // ---------- tags ----------

    private fun label(ns: String, name: String) = when (ns) { "female" -> "$name ♀"; "male" -> "$name ♂"; else -> name }

    override suspend fun tags(): List<Tag> = listOf(
        "female:big breasts", "female:sole female", "male:sole male", "female:nakadashi", "female:stockings", "female:glasses",
        "female:schoolgirl uniform", "tag:full color", "female:ahegao", "female:anal", "female:milf", "female:paizuri",
        "female:blowjob", "female:x-ray", "female:defloration", "female:dark skin", "female:swimsuit", "female:bikini", "female:maid",
        "female:netorare", "female:mind break", "female:femdom", "female:yuri", "male:yaoi", "female:futanari", "female:tentacles",
        "female:monster girl", "female:elf", "female:kemonomimi", "female:catgirl", "female:bunny girl", "female:huge breasts",
        "female:small breasts", "female:pantyhose", "female:ponytail", "female:twintails", "tag:uncensored", "tag:story arc",
        "female:harem", "female:cheating", "tag:group", "female:muscle", "female:tall girl", "female:tanlines", "female:demon girl",
        "female:nurse", "female:teacher", "female:office lady", "female:kimono", "female:cosplaying", "tag:multi-work series",
    ).map { val ns = it.substringBefore(':'); val n = it.substringAfter(':'); Tag(it, label(ns, n), ns) }

    override suspend fun suggestTags(query: String): List<Tag> {
        val q = query.trim().lowercase().replace('_', ' ')
        if (q.isEmpty()) return emptyList()
        var field = "global"
        var term = q
        if (':' in q) {
            field = q.substringBefore(':').let { if (it == "parody") "series" else it }
            term = q.substringAfter(':')
        }
        if (term.isEmpty() || field !in setOf("global", "female", "male", "tag", "artist", "group", "series", "character", "language", "type")) return emptyList()
        // tagindex.hitomi.la/<field>/<c>/<h>/<a>/<r>.json: one path segment per character
        val path = term.map { c ->
            when (c) { ' ' -> "_"; '/' -> "slash"; '.' -> "dot"; else -> java.net.URLEncoder.encode(c.toString(), "UTF-8") }
        }.joinToString("/")
        val (code, body) = Http.getRaw("$TAGINDEX/$field/$path.json")
        if (code != 200) return emptyList()
        val arr = runCatching { Http.json.parseToJsonElement(body) }.getOrNull().arr
        return arr.mapNotNull { e ->
            val a = e.arr
            val n = a.getOrNull(0).text
            val ns = a.getOrNull(2).text.ifEmpty { "tag" }
            if (n.isEmpty() || n in MINOR_NAMES) null
            else Tag("$ns:$n", label(ns, n), ns, a.getOrNull(1).text.toIntOrNull() ?: 0)
        }
    }

    /** Genres come as "name ♀" / "name ♂" / "name"; groups as "parody:x", "character:x", "language:x" ... */
    override fun tagQuery(name: String): SearchQuery {
        val id = when {
            name.endsWith(" ♀") -> "female:" + name.removeSuffix(" ♀")
            name.endsWith(" ♂") -> "male:" + name.removeSuffix(" ♂")
            ':' in name && name.substringBefore(':') in setOf("female", "male", "tag", "artist", "group", "parody", "series", "character", "language", "type") -> name
            else -> "tag:$name"
        }
        val (ns, n) = id.substringBefore(':') to id.substringAfter(':')
        if (ns == "language") return SearchQuery(selects = mapOf("lang" to n))
        if (ns == "type") return SearchQuery(selects = mapOf("type" to n))
        return SearchQuery(include = listOf(Tag(id, label(ns, n), ns)))
    }

    override fun artistQuery(name: String) = SearchQuery(include = listOf(Tag("artist:$name", name, "artist")))
    override val authorsAreGroups get() = true
    override fun groupQuery(name: String) = SearchQuery(include = listOf(Tag("group:$name", name, "group")))

    // ---------- gallery ----------

    private suspend fun gallery(ref: MangaRef): Info {
        val gid = ref.id.toIntOrNull() ?: throw SourceException("Not a hitomi.la gallery")
        val i = info(gid) ?: throw SourceException("This gallery is gone")
        if (i.minor) throw SourceException("This gallery is hidden")
        if (i.blocked) throw SourceException("This gallery was taken down")
        return i
    }

    /** "2026-11-01 21:21:00-06" -> epoch ms */
    private fun dateMs(s: String): Long = runCatching {
        val m = Regex("(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2})(?:\\.\\d+)?([+-]\\d{2})").find(s)!!
        java.time.OffsetDateTime.parse("${m.groupValues[1]}T${m.groupValues[2]}${m.groupValues[3]}:00").toInstant().toEpochMilli()
    }.getOrDefault(0L)

    override suspend fun details(ref: MangaRef): MangaDetails {
        val i = gallery(ref)
        val r = toRef(i)
        return MangaDetails(
            ref = r,
            altTitles = listOfNotNull(i.jtitle.ifEmpty { null }?.takeIf { it != r.title }),
            authors = i.ns("group"),
            artists = i.ns("artist"),
            genres = i.tags.filter { it.substringBefore(':') in setOf("female", "male", "tag") }
                .map { label(it.substringBefore(':'), it.substringAfter(':')) },
            tagGroups = listOf("series" to "Parody", "character" to "Character", "language" to "Language", "type" to "Type")
                .mapNotNull { (k, l) -> i.ns(k).takeIf { it.isNotEmpty() }?.let { TagGroup(l, it) } },
            status = "Complete",
            type = i.type.replaceFirstChar { it.uppercase() },
            url = webUrl(ref),
            pageCount = i.files.size,
            extra = listOfNotNull(
                i.date.take(10).ifEmpty { null }?.let { "Added" to it },
                i.languageLocal.ifEmpty { i.language }.ifEmpty { null }?.let { "Language" to it },
            ),
        )
    }

    override suspend fun chapters(ref: MangaRef): List<Chapter> {
        val i = gallery(ref)
        return listOf(Chapter("g", "Gallery · ${i.files.size} pages", 1f, dateMs(i.date), "", i.files.size))
    }

    /** webp pages: Coil decodes webp on every Android version (avif only from 12). */
    override suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page> {
        val i = gallery(ref)
        val g = gg()
        return i.files.mapIndexed { n, f -> Page(n, pageUrl(g, f.hash), f.width, f.height) }
    }

    override suspend fun related(ref: MangaRef, details: MangaDetails): List<MangaRef> {
        val i = gallery(ref)
        val hidden = runCatching { minorIds() }.getOrDefault(IntArray(0))
        return items(i.related.filterNot { hidden.has(it) }.take(12))
    }

    override fun webUrl(ref: MangaRef) = "$baseUrl/galleries/${ref.id}.html"
}
