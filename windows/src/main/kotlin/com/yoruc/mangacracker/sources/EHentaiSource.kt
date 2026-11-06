package com.yoruc.mangacracker.sources

import com.yoruc.mangacracker.core.Chapter
import com.yoruc.mangacracker.core.ExLogin
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.MangaDetails
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Page
import com.yoruc.mangacracker.core.PageResult
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SelectDef
import com.yoruc.mangacracker.core.Source
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.core.TagGroup
import com.yoruc.mangacracker.core.WebCookieJar
import com.yoruc.mangacracker.core.get
import com.yoruc.mangacracker.core.obj
import com.yoruc.mangacracker.core.str
import com.yoruc.mangacracker.core.urlEncode
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.ConcurrentHashMap

/**
 * E-Hentai / ExHentai (ExHentai when signed in and switched on in Settings).
 * One gallery = one chapter. Spec: _dev_tools/specs/ehentai.md
 */
object EHentaiSource : Source {
    override val id = "ehentai"
    override val name = "E-Hentai"
    override val blurb = "e-hentai.org / exhentai.org · doujinshi, manga, CG"
    override val color = 0xFFE0566B
    override val nsfw = true
    override val hasChapters = false
    override val baseUrl: String get() = if (Repo.settings.value.useExHentai) "https://exhentai.org" else "https://e-hentai.org"

    private const val GUARD = "-lolicon -shotacon -toddlercon"
    /** [GUARD], plus the AI / futanari / furry tags while Settings hides them (the site drops them, so pages stay full). */
    private val guard get() = GUARD + (if (Safety.hideAi) " -other:\"ai generated$\"" else "") +
        (if (Safety.hideFuta) " -female:futanari$ -male:\"dickgirl on male$\"" else "") +
        (if (Safety.hideFurry) " -female:furry$ -male:furry$" else "")
    private const val UK_BLOCK = "E-Hentai blocks its lists and search in the UK (Online Safety Act). They work on a VPN. " +
        "Galleries from your library or a pasted gallery link still open."
    private const val EX_SIGN_IN = "ExHentai needs you signed in: Settings › Logins › ExHentai › Sign in"

    init {
        Http.rateLimits["e-hentai.org"] = 700L
        Http.rateLimits["exhentai.org"] = 700L
        listOf("https://e-hentai.org" to ".e-hentai.org", "https://exhentai.org" to ".exhentai.org").forEach { (u, d) ->
            WebCookieJar.set(u, "nw", "1", d)   // skip the content warning page
            WebCookieJar.set(u, "sl", "dm_2", d) // extended list rows (all tags, page count, rating)
        }
    }

    private val CATS = listOf("Doujinshi" to 2, "Manga" to 4, "Artist CG" to 8, "Game CG" to 16, "Western" to 512,
        "Non-H" to 256, "Image Set" to 32, "Cosplay" to 64, "Asian Porn" to 128, "Misc" to 1)

    override val selects = listOf(
        SelectDef("cat", "Category", listOf("" to "All") + CATS.map { it.second.toString() to it.first }),
        SelectDef("lang", "Language", listOf("" to "Any", "english" to "English", "japanese" to "Japanese", "chinese" to "Chinese", "korean" to "Korean")),
        SelectDef("rating", "Minimum rating", listOf("" to "Any", "3" to "★ 3+", "4" to "★ 4+", "5" to "★ 5")),
    )

    // ---------- lists ----------

    /** list url (without the cursor) -> page number -> gid to continue from */
    private val cursors = ConcurrentHashMap<String, MutableMap<Int, String>>()

    private suspend fun fetch(url: String): Document {
        val ex = url.contains("exhentai.org")
        // the login goes on exhentai.org first: without it the site bounces through the forums' Cloudflare check
        if (ex && !ExLogin.carryOver()) throw SourceException(EX_SIGN_IN)
        val (code, body) = Http.getRaw(url)
        if (ex && (code !in 200..299 || body.isBlank())) {
            val s = ExLogin.read(code, body, WebCookieJar.get(ExLogin.EX, "igneous"))
            throw SourceException(s.text + " (Settings › Logins › ExHentai)")
        }
        if (code == 451) throw SourceException(UK_BLOCK)
        if (code !in 200..299) throw SourceException("E-Hentai answered $code")
        if ("temporarily banned" in body) throw SourceException("E-Hentai paused you for loading too fast. Try again in a few minutes.")
        return Jsoup.parse(body, url)
    }

    private suspend fun listPage(base: String, page: Int): PageResult {
        val next = if (page > 1) cursors[base]?.get(page) ?: return PageResult(emptyList(), false) else null
        val url = if (next == null) base else base + (if ('?' in base) "&" else "?") + "next=$next"
        val doc = fetch(url)
        val items = parseRows(doc)
        val nextLink = doc.selectFirst("a#unext")?.attr("href")
        val nextGid = nextLink?.let { Regex("[?&]next=(\\d+)").find(it)?.groupValues?.get(1) }
        if (nextGid != null) cursors.getOrPut(base) { ConcurrentHashMap() }[page + 1] = nextGid
        return PageResult(items, nextGid != null)
    }

    private val gidRx = Regex("/g/(\\d+)/([0-9a-f]+)")

    private fun parseRows(doc: Document): List<MangaRef> = doc.select("table.itg tr").mapNotNull { tr ->
        if (tr.selectFirst("th") != null || tr.selectFirst(".itd") != null) return@mapNotNull null
        val link = tr.selectFirst("a[href*=/g/]") ?: return@mapNotNull null
        val m = gidRx.find(link.attr("href")) ?: return@mapNotNull null
        val title = tr.selectFirst(".glink")?.text() ?: return@mapNotNull null
        val img = tr.selectFirst(".gl1e img") ?: tr.selectFirst(".glthumb img") ?: tr.selectFirst("img")
        val cover = img?.attr("data-src")?.ifEmpty { null } ?: img?.attr("abs:src").orEmpty()
        val tags = tr.select("div[title*=:]").map { it.attr("title") }
        val pages = tr.select("div").firstOrNull { Regex("^\\d+ pages?$").matches(it.ownText()) }?.ownText().orEmpty()
        val cat = tr.selectFirst(".cn")?.text().orEmpty()
        MangaRef(id, "${m.groupValues[1]}/${m.groupValues[2]}", title, cover, tags, listOf(cat, pages).filter { it.isNotEmpty() }.joinToString(" · "))
    }

    override suspend fun popular(page: Int): PageResult =
        if (page > 1) PageResult(emptyList(), false) else PageResult(parseRows(fetch("$baseUrl/popular")), false)

    override suspend fun latest(page: Int) = listPage("$baseUrl/?f_search=${guard.urlEncode()}", page)

    override suspend fun search(query: SearchQuery, page: Int): PageResult {
        // a pasted gallery link opens that gallery
        gidRx.find(query.text)?.let { m ->
            if (page > 1) return PageResult(emptyList(), false)
            val ref = MangaRef(id, "${m.groupValues[1]}/${m.groupValues[2]}", "Gallery ${m.groupValues[1]}")
            val d = details(ref)
            return PageResult(listOf(d.ref), false)
        }
        val terms = mutableListOf<String>()
        if (query.text.isNotBlank()) terms += query.text.trim()
        if (query.artist.isNotBlank()) terms += "artist:\"${query.artist.trim()}$\""
        query.include.forEach { terms += term(it.id) }
        query.exclude.forEach { terms += "-" + term(it.id) }
        query.selects["lang"]?.takeIf { it.isNotEmpty() }?.let { terms += "language:$it$" }
        terms += guard
        var url = "$baseUrl/?f_search=${terms.joinToString(" ").urlEncode()}"
        query.selects["cat"]?.toIntOrNull()?.let { url += "&f_cats=${1023 - it}" }
        query.selects["rating"]?.toIntOrNull()?.let { url += "&f_sr=on&f_srdd=$it" }
        return listPage(url, page)
    }

    private fun term(tag: String): String {
        val ns = tag.substringBefore(':', "")
        val t = tag.substringAfter(':')
        return if (ns.isNotEmpty()) "$ns:\"$t$\"" else "\"$t$\""
    }

    /** label -> "namespace:tag", learnt from galleries, so a tapped genre searches the exact tag */
    private val nsOf = ConcurrentHashMap<String, String>()

    override fun tagQuery(name: String): SearchQuery {
        val full = if (':' in name) name else nsOf[name] ?: name
        return SearchQuery(include = listOf(Tag(full, full.substringAfter(':'))))
    }

    override fun artistQuery(name: String) = SearchQuery(include = listOf(Tag("artist:$name", name)))

    override suspend fun tags(): List<Tag> = listOf(
        "female:big breasts", "female:sole female", "male:sole male", "female:stockings", "female:nakadashi", "female:glasses",
        "female:schoolgirl uniform", "female:swimsuit", "female:bikini", "female:maid", "female:kemonomimi", "female:elf",
        "female:dark skin", "female:milf", "female:ponytail", "female:twintails", "female:pantyhose", "female:femdom",
        "female:yuri", "female:futanari", "male:yaoi", "female:netorare", "female:anal", "female:paizuri", "female:ahegao",
        "female:x-ray", "female:mind break", "female:tentacles", "female:monster girl", "female:demon girl", "female:catgirl",
        "female:muscle", "female:tall girl", "female:huge breasts", "female:small breasts", "female:tanlines", "female:bunny girl",
        "other:full color", "other:uncensored", "other:mosaic censorship", "other:multi-work series", "other:story arc",
        "other:artbook", "language:translated", "language:english", "male:dilf", "mixed:group", "female:vanilla",
    ).map { Tag(it, it.substringAfter(':'), it.substringBefore(':')) }

    override suspend fun suggestTags(query: String): List<Tag> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val local = tags().filter { it.label.contains(q, true) }
        val remote = runCatching {
            val j = Http.json.parseToJsonElement(Http.postJson("https://api.e-hentai.org/api.php", """{"method":"tagsuggest","text":${kotlinx.serialization.json.JsonPrimitive(q).toString()}}"""))
            j["tags"].obj?.values?.map { t -> Tag("${t.str("ns")}:${t.str("tn")}", t.str("tn"), t.str("ns")) }.orEmpty()
        }.getOrDefault(emptyList())
        return (local + remote).distinctBy { it.id }.take(40)
    }

    // ---------- gallery ----------

    private val galleries = ConcurrentHashMap<String, Document>()

    private suspend fun gallery(ref: MangaRef, p: Int = 0): Document {
        val key = "${ref.id}#$p"
        galleries[key]?.let { return it }
        val doc = fetch("$baseUrl/g/${ref.id}/" + if (p > 0) "?p=$p" else "")
        if (doc.selectFirst("#gn") == null && doc.selectFirst("#gdt") == null) {
            throw SourceException(doc.body().text().take(160).ifBlank { "Gallery not available" })
        }
        galleries[key] = doc
        return doc
    }

    override suspend fun details(ref: MangaRef): MangaDetails {
        galleries.keys.removeAll { it.startsWith(ref.id + "#") }
        val doc = gallery(ref)
        val style = doc.selectFirst("#gd1 div")?.attr("style").orEmpty()
        val cover = Regex("url\\(([^)]+)\\)").find(style)?.groupValues?.get(1)?.trim('\'', '"').orEmpty()
        val groups = doc.select("#taglist tr").map { tr ->
            val ns = tr.selectFirst("td.tc")?.text()?.removeSuffix(":").orEmpty()
            ns to tr.select("td:eq(1) div").map { it.text() }
        }
        val all = groups.flatMap { (ns, ts) -> ts.map { "$ns:$it" } }
        all.forEach { nsOf[it.substringAfter(':')] = it }
        val info = doc.select("#gdd tr").associate { (it.selectFirst(".gdt1")?.text()?.removeSuffix(":")?.lowercase() ?: "") to (it.selectFirst(".gdt2")?.text() ?: "") }
        val title = doc.selectFirst("#gn")?.text().orEmpty().ifEmpty { ref.title }
        fun g(ns: String) = groups.firstOrNull { it.first == ns }?.second.orEmpty()
        val genreNs = setOf("female", "male", "mixed", "other")
        return MangaDetails(
            ref = MangaRef(id, ref.id, title, cover.ifEmpty { ref.cover }, all + doc.selectFirst("#gdc .cs")?.text().orEmpty(), ref.subtitle),
            altTitles = listOfNotNull(doc.selectFirst("#gj")?.text()?.ifEmpty { null }),
            authors = g("group"),
            artists = g("artist"),
            genres = groups.filter { it.first in genreNs }.flatMap { it.second },
            tagGroups = groups.filter { it.first !in genreNs && it.first != "artist" && it.first != "group" }.map { (ns, ts) -> TagGroup(ns.replaceFirstChar { it.uppercase() }, ts) },
            type = doc.selectFirst("#gdc .cs")?.text().orEmpty(),
            pageCount = info["length"]?.substringBefore(' ')?.toIntOrNull() ?: 0,
            rating = doc.selectFirst("#rating_label")?.text()?.removePrefix("Average:")?.trim()?.let { "★ $it" }.orEmpty(),
            url = webUrl(ref),
            extra = listOfNotNull(
                info["posted"]?.let { "Posted" to it },
                info["language"]?.let { "Language" to it.trim() },
                info["file size"]?.let { "Size" to it },
                doc.selectFirst("#gdn a")?.text()?.let { "Uploader" to it },
            ),
        )
    }

    override suspend fun chapters(ref: MangaRef): List<Chapter> {
        val doc = gallery(ref)
        val info = doc.select("#gdd tr").associate { (it.selectFirst(".gdt1")?.text()?.removeSuffix(":")?.lowercase() ?: "") to (it.selectFirst(".gdt2")?.text() ?: "") }
        val posted = info["posted"]?.let { runCatching { java.time.LocalDateTime.parse(it.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull() } ?: 0L
        val n = info["length"]?.substringBefore(' ')?.toIntOrNull() ?: 0
        return listOf(Chapter("g", "Gallery · $n pages", 1f, posted, doc.selectFirst("#gdn a")?.text().orEmpty(), n))
    }

    override suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page> {
        val doc = gallery(ref)
        if (Safety.blocked(doc.select("#taglist div").map { it.text() })) throw SourceException("Hidden by your tag filter")
        val first = doc.select("#gdt a[href*=/s/]").map { it.attr("abs:href") }
        val total = Regex("of ([\\d,]+) images").find(doc.selectFirst(".gpc")?.text().orEmpty())?.groupValues?.get(1)?.replace(",", "")?.toIntOrNull()
            ?: first.size
        val per = first.size.coerceAtLeast(1)
        return (0 until total).map { i ->
            if (i < first.size) Page(i, "", pageUrl = first[i])
            else Page(i, "", pageUrl = "thumb:${ref.id}:${i / per}:${i + 1}")
        }
    }

    override suspend fun imageUrl(page: Page): String {
        var s = page.pageUrl
        if (s.startsWith("thumb:")) {
            val (_, gid, token, p, n) = ("x:" + s.removePrefix("thumb:")).split(':').let { listOf(it[0], it[1].substringBefore('/'), it[1].substringAfter('/'), it[2], it[3]) }
            val doc = gallery(MangaRef(id, "$gid/$token", ""), p.toInt())
            s = doc.select("#gdt a[href*=/s/]").map { it.attr("abs:href") }.firstOrNull { it.endsWith("-$n") }
                ?: throw SourceException("Page $n not found")
        }
        val doc = fetch(s)
        val src = doc.selectFirst("#img")?.attr("abs:src") ?: throw SourceException("No picture on this page")
        if (src.endsWith("509.gif")) throw SourceException("E-Hentai's image limit is reached for now. Try again later.")
        return src
    }

    override suspend fun related(ref: MangaRef, details: MangaDetails): List<MangaRef> {
        val artist = details.artists.firstOrNull() ?: details.authors.firstOrNull() ?: return emptyList()
        val ns = if (details.artists.isNotEmpty()) "artist" else "group"
        return runCatching { search(SearchQuery(include = listOf(Tag("$ns:$artist", artist))), 1).items }.getOrDefault(emptyList()).filter { it.key != ref.key }
    }

    override fun webUrl(ref: MangaRef) = "$baseUrl/g/${ref.id}/"
}
