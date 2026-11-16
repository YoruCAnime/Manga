package com.yoruc.mangacracker.sources

import com.yoruc.mangacracker.core.Chapter
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
import com.yoruc.mangacracker.core.urlEncode
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

/**
 * nhentai.xxx through its HTML pages. Every title is one gallery. Lists only carry numeric tag ids (data-tags), so minors
 * galleries are dropped by id there and by name on the title page. The site asks visitors to confirm they are adults
 * with a button: the app asks the same once ([ageNotice]) and reads nothing from the site before that.
 */
object NhentaiXxxSource : Source {
    override val id = "nhentaixxx"
    override val name = "nhentai.xxx"
    override val baseUrl = "https://nhentai.xxx"
    override val blurb = "nhentai.xxx · doujinshi"
    override val color = 0xFFE8457A
    override val nsfw = true
    override val hasChapters = false
    /** "word -word" finds nothing on this site, so the filter sheet offers no exclusions. */
    override val canExclude = false
    override val ageNotice = "nhentai.xxx is for adults aged 18 and over, and asks you to confirm your age before you continue."

    /** lolicon 3, shotacon 40, low shotacon 138, low lolicon 141, oppai loli 202, kindergarten uniform 208, age regression 256, randoseru 345, kodomo doushi 726 */
    private val HIDDEN_IDS = setOf("3", "40", "138", "141", "202", "208", "256", "345", "726")
    /** Tag ids the switches care about: AI, futanari, dickgirl on male / dickgirl, furry, human on furry. */
    private val NAMED = mapOf("718" to "ai generated", "67" to "futanari", "232" to "dickgirl on male",
        "100" to "dickgirl on dickgirl", "348" to "furry", "479" to "human on furry")
    private val KINDS = setOf("artist", "group", "parody", "character", "language", "category")
    private val LIST_KINDS = mapOf("Parodies" to "parody", "Characters" to "character", "Tags" to "tag", "Artists" to "artist",
        "Groups" to "group", "Languages" to "language", "Category" to "category")

    /** The site's own address for a name seen on a title page, and what kind of name it is. */
    private val slugs = ConcurrentHashMap<String, String>()
    private val kinds = ConcurrentHashMap<String, String>()

    init {
        // pages only: the image host (nhentaimg.com) stays unthrottled
        Http.rateLimits["nhentai.xxx"] = 800L
    }

    override val selects = listOf(
        SelectDef("sort", "Sort", listOf("" to "Newest", "popular" to "Popular")),
        SelectDef("lang", "Language", listOf("" to "Any", "english" to "English", "japanese" to "Japanese", "chinese" to "Chinese")),
    )

    private suspend fun page(path: String): Document? {
        if (id !in Repo.settings.value.ageConfirmed) throw SourceException("Open nhentai.xxx in Browse and confirm your age first.")
        val (code, html) = Http.getRaw(baseUrl + path)
        if (code == 404) return null
        if (code !in 200..299) throw SourceException("nhentai.xxx answered $code")
        return Jsoup.parse(html, baseUrl + path)
    }

    // ---------- lists ----------

    private val GALLERY = Regex("""/g/(\d+)""")

    private fun item(t: Element): MangaRef? {
        val a = t.selectFirst("a[href*=/g/]") ?: return null
        val gid = GALLERY.find(a.attr("href"))?.groupValues?.get(1) ?: return null
        val ids = t.attr("data-tags").split(' ').filter { it.isNotEmpty() }
        if (ids.any { it in HIDDEN_IDS }) return null
        val cover = t.selectFirst("img")?.attr("data-src").orEmpty()
        return MangaRef(
            source = id, id = gid, title = a.attr("title").ifEmpty { t.selectFirst(".caption")?.text().orEmpty() },
            cover = if (cover.startsWith("http")) cover else "",
            // lists only carry tag ids; the names let Safety's switches see them
            tags = ids.map { "nx:$it" } + ids.mapNotNull { NAMED[it] },
        )
    }

    private suspend fun list(path: String): PageResult {
        val doc = page(path) ?: return PageResult(emptyList(), false)
        // the first page of the home has "Popular Now" before "New Uploads": the last box is the real list
        val items = doc.select("div.galleries_box").lastOrNull()?.select("div.gallery_item")?.mapNotNull { item(it) }.orEmpty()
        val last = doc.select("ul.pagination li").lastOrNull()
        return PageResult(items, last != null && last.text().trim().startsWith("Next", true) && !last.hasClass("disabled"))
    }

    private fun tail(sort: String, page: Int): String {
        val p = listOfNotNull(sort.ifEmpty { null }?.let { "sort=$it" }, if (page > 1) "page=$page" else null)
        return if (p.isEmpty()) "" else "?" + p.joinToString("&")
    }

    private fun slug(name: String) = slugs[name.lowercase()] ?: name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    // the site has no "popular" page of its own: all-time popular among the English galleries
    override suspend fun popular(page: Int): PageResult = list("/language/english/" + tail("popular", page))

    override suspend fun latest(page: Int): PageResult = list("/" + tail("", page))

    override suspend fun search(query: SearchQuery, page: Int): PageResult {
        val tags = query.include.map { it.id } + (if (query.artist.isNotBlank()) listOf("artist:" + query.artist.trim()) else emptyList())
        val text = query.text.trim()
        if (text.split(' ').any { Safety.isMinorTag(it) } || Safety.isMinorTag(text) || tags.any { Safety.isMinorTag(it.substringAfter(':')) }) {
            return PageResult(emptyList(), false)
        }
        val lang = query.selects["lang"].orEmpty()
        val sort = query.selects["sort"].orEmpty()
        // one name alone has its own list; anything more goes through the word search
        if (text.isEmpty() && tags.size == 1 && lang.isEmpty()) {
            val ns = tags[0].substringBefore(':', "").takeIf { it in KINDS } ?: "tag"
            return list("/$ns/${slug(tags[0].substringAfter(':'))}/" + tail(sort, page))
        }
        if (text.isEmpty() && tags.isEmpty()) return list(if (lang.isEmpty()) "/" + tail(sort, page) else "/language/$lang/" + tail(sort, page))
        val key = (listOf(text) + tags.map { it.substringAfter(':') } + lang).filter { it.isNotEmpty() }.joinToString(" ")
        return list("/search/?key=" + key.urlEncode() + tail(sort, page).replace("?", "&"))
    }

    override fun tagQuery(name: String): SearchQuery {
        val plain = name.substringAfter(':')
        val ns = name.substringBefore(':', "").takeIf { it in KINDS } ?: kinds[plain.lowercase()]?.takeIf { it in KINDS }
        return SearchQuery(include = listOf(Tag(if (ns != null) "$ns:$plain" else plain, plain)))
    }

    override fun artistQuery(name: String) = SearchQuery(include = listOf(Tag("artist:$name", name)))
    override val authorsAreGroups get() = true
    override fun groupQuery(name: String) = SearchQuery(include = listOf(Tag("group:$name", name)))

    override suspend fun tags(): List<Tag> = listOf(
        "big breasts", "sole female", "sole male", "nakadashi", "stockings", "glasses", "schoolgirl uniform", "full color",
        "ahegao", "anal", "milf", "paizuri", "blowjob", "x-ray", "defloration", "dark skin", "swimsuit", "bikini", "maid",
        "netorare", "mind break", "femdom", "yuri", "yaoi", "futanari", "tentacles", "monster girl", "elf", "kemonomimi",
        "catgirl", "bunny girl", "huge breasts", "small breasts", "pantyhose", "ponytail", "twintails", "uncensored",
        "mosaic censorship", "story arc", "harem", "cheating", "group", "muscle", "tall girl", "tanlines",
        "demon girl", "nurse", "teacher", "office lady", "kimono", "cosplaying",
    ).map { Tag(it, it) }

    override suspend fun suggestTags(query: String): List<Tag> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        // no tag-name search on the site: matches from the list, and the typed words as they are
        val local = tags().filter { it.label.contains(q, true) }
        val typed = listOf(Tag(q, "$q (tag)", "typed"), Tag("artist:$q", "artist: $q", "typed"), Tag("parody:$q", "parody: $q", "typed"), Tag("character:$q", "character: $q", "typed"))
        return (local + typed).distinctBy { it.id }
    }

    // ---------- gallery ----------

    private data class Gallery(val doc: Document, val names: Map<String, List<String>>)
    private val cache = ConcurrentHashMap<String, Gallery>()

    private suspend fun gallery(gid: String): Gallery {
        cache[gid]?.let { return it }
        val doc = page("/g/$gid/") ?: throw SourceException("Not found")
        val names = LinkedHashMap<String, List<String>>()
        doc.select("div.info li.tags").forEach { li ->
            val kind = LIST_KINDS[li.selectFirst("span.text")?.text()?.trim()?.removeSuffix(":")] ?: return@forEach
            names[kind] = li.select("a.tag_btn").mapNotNull { a ->
                val n = a.selectFirst("span.tag_name")?.text()?.trim().orEmpty().ifEmpty { return@mapNotNull null }
                a.attr("href").trim('/').substringAfter('/', "").ifEmpty { null }?.let { slugs[n.lowercase()] = it }
                kinds[n.lowercase()] = kind
                n
            }
        }
        return Gallery(doc, names).also { if (cache.size > 200) cache.clear(); cache[gid] = it }
    }

    private fun count(g: Gallery) = g.doc.selectFirst("span.tag_name.pages")?.text()?.trim()?.toIntOrNull() ?: 0

    override suspend fun details(ref: MangaRef): MangaDetails {
        val g = gallery(ref.id)
        fun of(k: String) = g.names[k].orEmpty()
        val full = g.doc.selectFirst("div.info h1")?.text().orEmpty()
        val d = MangaDetails(
            ref = MangaRef(id, ref.id, ref.title.ifEmpty { full }, cover = g.doc.selectFirst("div.cover img")?.attr("data-src").orEmpty().ifEmpty { ref.cover },
                tags = g.names.flatMap { (k, v) -> v.map { "$k:$it" } }, subtitle = "${count(g)} pages"),
            altTitles = listOfNotNull(full.ifEmpty { null }, g.doc.selectFirst("div.info h2")?.text()?.ifEmpty { null }).filter { it != ref.title },
            authors = of("group"),
            artists = of("artist"),
            genres = of("tag"),
            tagGroups = listOf("parody" to "Parody", "character" to "Character", "language" to "Language", "category" to "Category")
                .mapNotNull { (k, label) -> of(k).takeIf { it.isNotEmpty() }?.let { TagGroup(label, it) } },
            status = "Complete",
            type = of("category").firstOrNull()?.replaceFirstChar { it.uppercase() }.orEmpty(),
            pageCount = count(g),
            url = webUrl(ref),
        )
        if (Safety.blocked(d)) throw SourceException("This title is hidden")
        return d
    }

    override suspend fun chapters(ref: MangaRef): List<Chapter> {
        val g = gallery(ref.id)
        val at = runCatching { java.time.Instant.parse(g.doc.selectFirst("span.uploaded")?.attr("data-utc")).toEpochMilli() }.getOrDefault(0L)
        return listOf(Chapter("g", "Gallery · ${count(g)} pages", 1f, at, "", count(g)))
    }

    private val FILES = Regex(""""(\d+)":"([jpgw]),(\d+),(\d+)"""")
    private val EXT = mapOf("j" to "jpg", "p" to "png", "g" to "gif", "w" to "webp")

    override suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page> {
        val g = gallery(ref.id)
        if (g.names.values.flatten().any { Safety.blocked(listOf(it), id) }) throw SourceException("This title is hidden")
        fun v(name: String) = g.doc.selectFirst("input#$name")?.attr("value").orEmpty()
        val script = g.doc.select("script").firstOrNull { "var g_th" in it.data() }?.data().orEmpty()
        val files = script.substringAfter("\"fl\":{", "").substringBefore('}')
        val host = "https://i${v("load_server")}.nhentaimg.com/${v("load_dir")}/${v("load_id")}"
        val list = FILES.findAll(files).map { m ->
            val (n, e, w, h) = m.destructured
            Page(n.toInt() - 1, "$host/$n.${EXT[e]}", w.toInt(), h.toInt())
        }.sortedBy { it.index }.toList()
        if (list.isEmpty()) throw SourceException("nhentai.xxx sent no pages for this title")
        return list
    }

    override suspend fun related(ref: MangaRef, details: MangaDetails): List<MangaRef> =
        gallery(ref.id).doc.select("div.galleries_box div.gallery_item").mapNotNull { item(it) }

    override fun webUrl(ref: MangaRef) = "$baseUrl/g/${ref.id}/"
}
