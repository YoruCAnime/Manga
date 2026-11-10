package com.yoruc.mangacracker.sources

import com.yoruc.mangacracker.core.Chapter
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.MangaDetails
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Page
import com.yoruc.mangacracker.core.PageResult
import com.yoruc.mangacracker.core.Safety
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
import com.yoruc.mangacracker.core.urlEncode
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

/**
 * Pururin (pururin.me) through its HTML pages and its tag-name API. Every title is one gallery. List cards carry no
 * tags, so every list is the site's search with the minors tags (and what the switches hide) excluded by tag id; the
 * title page checks again by id and by name. Spec: _dev_tools/specs/pururin.md
 */
object PururinSource : Source {
    override val id = "pururin"
    override val name = "Pururin"
    override val baseUrl = "https://pururin.me"
    override val blurb = "pururin.me · doujinshi, manga"
    override val color = 0xFFE83E8C
    override val nsfw = true
    override val hasChapters = false

    /** loli 1556, shotacon 1577, toddlercon 2648, oppai loli 1827, straight shota 2742, randoseru 1683, age regression 1785 */
    private val HIDDEN_IDS = listOf("1556", "1577", "2648", "1827", "2742", "1683", "1785")
    /** futanari 1570, sole dickgirl 1712, futanari on female 2753 / on futanari 1886 / on male 1817, male on futanari 1818, futanari only 19243, futanarization 39020 */
    private val FUTA_IDS = listOf("1570", "1712", "2753", "1886", "1817", "1818", "19243", "39020")
    /** furry 2554, furry on furry 24321, human on furry 1850 */
    private val FURRY_IDS = listOf("2554", "24321", "1850")
    /** the categories "AI CG" 39500 and "AI Comic" 39499 */
    private val AI_IDS = listOf("39500", "39499")

    private val KINDS = setOf("artist", "circle", "parody", "character", "content", "language", "scanlator", "convention", "category", "collection")
    /** Start of a row label in the title page's table ("Artist", "Parody", "Contents", "Category" ...) -> the site's tag kind. */
    private val ROWS = listOf("artist" to "artist", "circle" to "circle", "parod" to "parody", "character" to "character",
        "content" to "content", "language" to "language", "scanlator" to "scanlator", "convention" to "convention",
        "categor" to "category", "collection" to "collection")

    /** "kind:name" (lower case) -> the site's tag id, and name -> kind, learnt from title pages and the tag API. */
    private val ids = ConcurrentHashMap<String, String>()
    private val kinds = ConcurrentHashMap<String, String>()

    init {
        // pages only: the image host (i.pururin.me) stays unthrottled
        listOf("search", "gallery", "read", "api").forEach { Http.rateLimits["pururin.me/$it"] = 700L }
    }

    override val selects = listOf(
        SelectDef("sort", "Sort", listOf("" to "Newest", "most-popular" to "Most popular", "highest-rated" to "Highest rated",
            "most-viewed" to "Most viewed", "title" to "Title")),
        // the values are the language tags' ids
        SelectDef("lang", "Language", listOf("" to "Any", "13010" to "English", "13011" to "Japanese")),
    )

    private val NONE = PageResult(emptyList(), false)

    // ---------- lists ----------

    private val GALLERY = Regex("""/gallery/(\d+)""")
    private val TAG_LINK = Regex("""/browse/tags/([a-z]+)/(\d+)""")
    private val PAGES = Regex("""(\d+)\s+Pages?""", RegexOption.IGNORE_CASE)
    private val WORDS = Regex("""[^\p{L}]+""")
    /** "loli…" at the start of a word (not "hololive"), "shota" anywhere ("oneshota"). */
    private val MINOR_WORD = Regex("""\bloli|shota""")

    private suspend fun doc(path: String): Document? {
        val (code, html) = Http.getRaw(baseUrl + path)
        if (code == 404) return null
        if (code !in 200..299) throw SourceException("Pururin answered $code")
        return Jsoup.parse(html, baseUrl + path)
    }

    private fun item(a: Element): MangaRef? {
        val gid = a.attr("data-gid").ifEmpty { GALLERY.find(a.attr("href"))?.groupValues?.get(1).orEmpty() }
        if (gid.isEmpty()) return null
        // the card's h2 is "English <br> Japanese"; the link's own title also carries [artist] [parody]
        val title = a.selectFirst(".title h2")?.textNodes()?.firstOrNull { it.text().isNotBlank() }?.text()?.trim().orEmpty()
            .ifEmpty { a.attr("title").substringBefore(" / ").trim() }
        val img = a.selectFirst("img")
        val cover = img?.attr("abs:data-src")?.ifEmpty { null } ?: img?.attr("abs:src").orEmpty()
        return MangaRef(
            source = id, id = gid, title = title,
            cover = if (cover.startsWith("http")) cover else "",
            // "Original Hentai Doujinshi by Name, English, 33 Pages"
            subtitle = PAGES.find(a.selectFirst(".info")?.text().orEmpty())?.let { "${it.groupValues[1]} pages" }.orEmpty(),
        )
    }

    /** The ids the site must leave out of every list: minors tags always, and what the switches hide. */
    private fun guard(): List<String> = HIDDEN_IDS + (if (Safety.hideFuta) FUTA_IDS else emptyList()) +
        (if (Safety.hideFurry) FURRY_IDS else emptyList()) + (if (Safety.hideAi) AI_IDS else emptyList())

    /** The search form's tag fields are JSON: [{"id":1563},{"id":1582}] (the site's own form adds the names; ids are enough). */
    private fun idList(list: List<String>) = list.distinct().joinToString(",", "[", "]") { "{\"id\":$it}" }.urlEncode()

    private fun searchPath(text: String, include: List<String>, exclude: List<String>, sort: String, page: Int): String {
        val p = mutableListOf<String>()
        if (text.isNotEmpty()) p += "q=" + text.urlEncode()
        if (include.isNotEmpty()) p += "included_tags=" + idList(include)
        p += "excluded_tags=" + idList(guard() + exclude)
        if (sort.isNotEmpty()) p += "sort=$sort"
        if (page > 1) p += "page=$page"
        return "/search?" + p.joinToString("&")
    }

    private suspend fun list(text: String, include: List<String>, exclude: List<String>, sort: String, page: Int): PageResult {
        var doc = doc(searchPath(text, include, exclude, sort, page)) ?: return NONE
        if (text.isNotEmpty()) {
            // words that are exactly a tag's name are sent on to that tag's own list (/browse/tags/content/1582/glasses),
            // which knows no exclusions: ask again for that tag by id, with the guard
            val own = doc.selectFirst("a[href*=\"/browse/tags/\"][href*=\"?sort=\"]")?.attr("href")?.let { TAG_LINK.find(it) }?.groupValues?.get(2)
            if (own != null) {
                if (own in guard()) return NONE
                doc = doc(searchPath("", include + own, exclude, sort, page)) ?: return NONE
            }
        }
        // title pages also have a small "related" row; lists have the one big row
        val items = doc.select("a.card.card-gallery").filter { a -> a.parents().none { it.hasClass("row-gallery-small") } }.mapNotNull { item(it) }
        return PageResult(items, doc.selectFirst("ul.pagination a[rel=next]") != null)
    }

    override suspend fun popular(page: Int) = list("", emptyList(), emptyList(), "most-popular", page)

    override suspend fun latest(page: Int) = list("", emptyList(), emptyList(), "", page)

    /** A name nobody should search or be offered here (minors words, also inside longer names). */
    private fun unsafe(name: String): Boolean {
        val n = name.lowercase()
        return Safety.isMinorTag(n) || MINOR_WORD.containsMatchIn(n) || n.split(WORDS).any { Safety.isMinorTag(it) }
    }

    override suspend fun search(query: SearchQuery, page: Int): PageResult {
        val text = query.text.trim() // the site searches titles only
        val wanted = query.include.map { it.id.ifEmpty { it.label } } + (if (query.artist.isNotBlank()) listOf("artist:" + query.artist.trim()) else emptyList())
        if ((text.isNotEmpty() && unsafe(text)) || query.include.any { unsafe(it.label) }) return NONE
        // a name the site does not know finds nothing
        val include = wanted.map { resolve(it) ?: return NONE }.toMutableList()
        query.selects["lang"]?.takeIf { it.isNotEmpty() }?.let { include += it }
        if (include.any { it in HIDDEN_IDS }) return NONE
        val exclude = query.exclude.mapNotNull { resolve(it.id.ifEmpty { it.label }) }
        return list(text, include, exclude, query.selects["sort"].orEmpty(), page)
    }

    // ---------- tags ----------

    /** POST /api/get/tags/search {text} -> [{"id":1563,"name":"Stockings [Content]"}]; /api/get/tags {type, text} -> plain names. */
    private suspend fun api(path: String, fields: Map<String, String>): List<Pair<String, String>> {
        val body = buildJsonObject { fields.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }.toString()
        return Http.json.parseToJsonElement(Http.postJson(baseUrl + path, body, mapOf("Accept" to "application/json"))).arr
            .mapNotNull { e -> e.str("id").ifEmpty { null }?.let { it to e.str("name") } }
    }

    /** The site's id for a tag picked in the app: a number as it is, "kind:name" or a bare name through the tag API (exact name). */
    private suspend fun resolve(tag: String): String? {
        if (tag.isNotEmpty() && tag.all { it.isDigit() }) return tag
        val kind = tag.substringBefore(':', "").takeIf { it in KINDS }
        val name = (if (kind != null) tag.substringAfter(':') else tag).trim()
        if (name.isEmpty()) return null
        val low = name.lowercase()
        (if (kind != null) ids["$kind:$low"] else KINDS.firstNotNullOfOrNull { ids["$it:$low"] })?.let { return it }
        val found = runCatching {
            if (kind != null) api("/api/get/tags", mapOf("type" to kind, "text" to name)) else api("/api/get/tags/search", mapOf("text" to name))
        }.getOrDefault(emptyList())
        return found.firstOrNull { it.second.substringBeforeLast(" [").equals(name, true) }?.first
            ?.also { if (kind != null) ids["$kind:$low"] = it }
    }

    /** The site's 294 most-used content tags (>= 190 galleries) with ids and counts, from /tags/content?order=uses on 2026-11-10 (minors tags left out). */
    override suspend fun tags(): List<Tag> = TAGS

    private val TAGS: List<Tag> by lazy {
        TAG_TABLE.trim().lines().mapNotNull { line ->
            val p = line.trim().split('|', limit = 3)
            if (p.size < 3 || p[0] in HIDDEN_IDS) null else Tag(p[0], p[2], "Tag", p[1].toIntOrNull() ?: 0)
        }.let { Safety.cleanTags(it) }
    }

    override suspend fun suggestTags(query: String): List<Tag> {
        val q = query.trim()
        if (q.isEmpty() || unsafe(q)) return emptyList()
        val local = TAGS.filter { it.label.contains(q, true) }
            .sortedWith(compareBy<Tag> { !it.label.startsWith(q, true) }.thenByDescending { it.count }).take(40)
        // the site's own tag search knows every kind of name: "Stockings [Content]", "Ento [Artist]", "Original [Parody]"
        val remote = runCatching { api("/api/get/tags/search", mapOf("text" to q)) }.getOrDefault(emptyList()).mapNotNull { (tid, full) ->
            val kind = full.substringAfterLast(" [", "").removeSuffix("]").lowercase()
            val plain = full.substringBeforeLast(" [")
            if (tid in HIDDEN_IDS || kind == "uploader" || unsafe(plain)) null
            else Tag(tid, if (kind.isEmpty() || kind == "content") plain else "$plain ($kind)", kind.replaceFirstChar { it.uppercase() })
        }
        return Safety.cleanTags((local + remote).distinctBy { it.id })
    }

    override fun tagQuery(name: String): SearchQuery {
        val ns = name.substringBefore(':', "").takeIf { it in KINDS }
        val plain = if (ns != null) name.substringAfter(':') else name
        val kind = ns ?: kinds[plain.lowercase()] ?: "content"
        return SearchQuery(include = listOf(Tag(ids["$kind:${plain.lowercase()}"] ?: "$kind:$plain", plain)))
    }

    override fun artistQuery(name: String) = SearchQuery(include = listOf(Tag(ids["artist:${name.lowercase()}"] ?: "artist:$name", name)))
    override val authorsAreGroups get() = true
    override fun groupQuery(name: String) = SearchQuery(include = listOf(Tag(ids["circle:${name.lowercase()}"] ?: "circle:$name", name)))

    // ---------- gallery ----------

    private class Gallery(val doc: Document, val names: Map<String, List<String>>, val tagIds: Set<String>)
    private val cache = ConcurrentHashMap<String, Gallery>()

    private suspend fun gallery(gid: String): Gallery {
        cache[gid]?.let { return it }
        val doc = doc("/gallery/$gid") ?: throw SourceException("Gallery not found")
        if (doc.selectFirst("div.gallery-wrapper") == null) throw SourceException("Gallery not found")
        val names = LinkedHashMap<String, List<String>>()
        val found = HashSet<String>()
        doc.select("table.table-info tr").forEach { tr ->
            val label = tr.selectFirst("td")?.text()?.trim()?.lowercase().orEmpty()
            val kind = ROWS.firstOrNull { label.startsWith(it.first) }?.second ?: return@forEach
            names[kind] = tr.select("a[href*=/browse/tags/]").mapNotNull { a ->
                val n = a.text().trim().ifEmpty { return@mapNotNull null }
                TAG_LINK.find(a.attr("href"))?.groupValues?.get(2)?.let { ids["$kind:${n.lowercase()}"] = it; found += it }
                kinds[n.lowercase()] = kind
                n
            }
        }
        return Gallery(doc, names, found).also { if (cache.size > 200) cache.clear(); cache[gid] = it }
    }

    private fun hidden(g: Gallery) = g.tagIds.any { it in HIDDEN_IDS }

    private fun count(g: Gallery) = g.doc.selectFirst("table.table-info [itemprop=numberOfPages]")?.text()?.trim()?.toIntOrNull() ?: 0

    override suspend fun details(ref: MangaRef): MangaDetails {
        val g = gallery(ref.id)
        if (hidden(g)) throw SourceException("This title is hidden")
        fun of(k: String) = g.names[k].orEmpty()
        // the h1 is "English / Japanese", the line under it the romanised title
        val parts = g.doc.selectFirst("div.gallery-wrapper div.title h1")?.text().orEmpty().split(" / ").map { it.trim() }.filter { it.isNotEmpty() }
        val title = parts.firstOrNull() ?: ref.title
        val romaji = g.doc.selectFirst("div.gallery-wrapper div.title-secondary")?.text()?.trim().orEmpty()
        val pages = count(g)
        val d = MangaDetails(
            ref = MangaRef(id, ref.id, title,
                cover = g.doc.selectFirst("div.cover-wrapper img")?.attr("abs:src").orEmpty().ifEmpty { ref.cover },
                // the AI categories are "AI CG" and "AI Comic": the plain name lets Safety's switch see both
                tags = g.names.flatMap { (k, v) -> v.map { "$k:$it" } } + (if (g.tagIds.any { it in AI_IDS }) listOf("ai generated") else emptyList()),
                subtitle = if (pages > 0) "$pages pages" else ref.subtitle),
            altTitles = (parts.drop(1) + romaji).filter { it.isNotEmpty() && it != title }.distinct(),
            authors = of("circle"),
            artists = of("artist"),
            genres = of("content"),
            tagGroups = listOf("parody" to "Parody", "character" to "Character", "language" to "Language", "category" to "Category",
                "collection" to "Collection", "convention" to "Convention", "scanlator" to "Scanlator")
                .mapNotNull { (k, label) -> of(k).takeIf { it.isNotEmpty() }?.let { TagGroup(label, it) } },
            status = "Complete",
            type = of("category").firstOrNull().orEmpty(),
            pageCount = pages,
            url = webUrl(ref),
        )
        if (Safety.blocked(d)) throw SourceException("This title is hidden")
        return d
    }

    override suspend fun chapters(ref: MangaRef): List<Chapter> {
        val g = gallery(ref.id)
        // the title page shows no upload date
        return listOf(Chapter("g", "Gallery · ${count(g)} pages", 1f, 0L, g.names["circle"]?.firstOrNull().orEmpty(), count(g)))
    }

    /** The reader page lists every file: data-svr = image host, data-img = {"directory":"…","images":[{"page":1,"filename":"1.jpg"}]}. */
    override suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page> {
        val g = gallery(ref.id)
        if (hidden(g) || g.names.values.flatten().any { Safety.blocked(listOf(it), id) }) throw SourceException("This title is hidden")
        val view = doc("/read/${ref.id}/01")?.selectFirst("div.img-viewer") ?: throw SourceException("No pages found")
        val data = runCatching { Http.json.parseToJsonElement(view.attr("data-img")) }.getOrNull()
        val host = view.attr("data-svr").ifEmpty { "https://i.pururin.me" }.trimEnd('/')
        val dir = data.str("directory")
        val list = data["images"].arr.mapNotNull { e ->
            val n = e.int("page")
            val file = e.str("filename")
            if (n < 1 || file.isEmpty()) null else Page(n - 1, "$host/$dir/$file")
        }.sortedBy { it.index }
        if (dir.isEmpty() || list.isEmpty()) throw SourceException("Pururin sent no pages for this title")
        return list
    }

    /** The page's own "related" cards carry no tags to check, so: more by the same artist (or circle), with the guard. */
    override suspend fun related(ref: MangaRef, details: MangaDetails): List<MangaRef> {
        val g = gallery(ref.id)
        val by = listOf("artist", "circle").firstNotNullOfOrNull { k -> g.names[k]?.firstOrNull()?.let { ids["$k:${it.lowercase()}"] } } ?: return emptyList()
        return list("", listOf(by), emptyList(), "", 1).items.filter { it.id != ref.id }
    }

    override fun webUrl(ref: MangaRef) = "$baseUrl/gallery/${ref.id}"

    // id|uses|name, generated by the probe's make_tags.py
    private const val TAG_TABLE = """
1539|23962|Big Breasts
1559|16269|Sole Female
1557|16010|Nakadashi
1560|15800|Sole Male
1576|13293|Anal
1571|12677|Blowjob
1563|12289|Stockings
1575|8900|Group Sex
1591|8791|Ahegao
1590|8563|Rape
1582|7702|Glasses
1540|7611|Full Color
1639|7392|Defloration
1572|7387|X-Ray
1548|7247|Incest
1567|6363|Paizuri
1550|6089|Schoolgirl Uniform
1617|5922|Bondage
1592|5250|Double Penetration
1568|5201|Impregnation
1618|5031|Femdom
1640|4922|MILF
1594|4698|Mind Break
1653|4623|Sex Toys
22664|4496|Gloves
1564|4493|Dark Skin
1693|4215|Big Penis
1541|4016|Swimsuit
1629|3911|Yaoi
1570|3789|Futanari
1604|3739|Netorare
1583|3603|Yuri
1549|3569|Sister
1584|3537|FFM Threesome
1637|3507|Sweating
1698|3476|Cheating
1714|3443|Big Ass
1677|3384|Collar
2341|3348|Happy Sex
2740|3124|Twintails
1692|3034|Uncensored
1700|2801|Hairy
1544|2755|Bikini
1589|2734|Kissing
1641|2731|Lactation
1595|2701|Masturbation
1553|2653|Mind Control
19232|2646|Ponytail
1606|2622|BBM
1578|2619|Crossdressing
1670|2619|Pantyhose
1612|2580|Exhibitionism
1671|2496|Unusual Pupils
33639|2487|Long Gloves
1708|2384|Fingering
1647|2379|Teacher
1587|2305|Cunnilingus
1684|2298|Handjob
1694|2293|Virginity
1605|2191|Pregnant
1722|2190|Drugs
1642|2152|Mother
1573|2135|Huge Breasts
1593|2121|Tentacles
1585|2003|MMF Threesome
1672|1985|Muscle
1558|1960|Urination
1734|1894|Lingerie
1743|1868|Prostitution
1691|1864|Pettanko
1777|1815|Tankoubon
1630|1802|Males Only
1586|1801|Condom
1588|1782|Schoolboy Uniform
1664|1773|Females Only
1746|1773|Footjob
1666|1738|Harem
1579|1706|Tomgirl
1705|1705|Maid
2751|1679|Kemonomimi
1710|1552|Deepthroat
1551|1528|DILF
1552|1478|Gender Bender
1667|1436|Bukkake
1718|1410|Stomach Deformation
1649|1375|Breast Feeding
1701|1342|Blackmail
1681|1334|BBW
1699|1331|Story Arc
1763|1328|Garter Belt
1764|1324|Rimjob
19256|1312|Beauty Mark
1634|1308|Catgirl
1569|1263|Demon Girl
1599|1262|Inflation
1772|1213|Squirting
1655|1152|Filming
1740|1145|Blindfold
1728|1093|Gag
1636|1079|Tanlines
2741|1064|Hat
1780|1064|Ugly Bastard
1779|1059|Piercing
1770|1056|Sleeping
1835|1046|Slave
1904|1039|Big Areolae
2342|1037|Imouto
1608|1033|Monster Girl
17670|1028|Horns
1648|1025|Scat
1747|1014|Gyaru
1581|1001|Bunny Girl
1795|997|Kimono
1755|967|Elf
1680|943|Bloomers
1622|913|Thigh High Boots
1574|905|Penis Enlargement
1723|895|Monster
1545|890|Bestiality
2181|883|Tail
1644|870|Tomboy
1943|865|Leg Lock
1554|843|Daughter
1596|839|Corruption
1757|837|Leotard
2749|827|Elder Sister
1682|819|Inverted Nipples
1686|816|Smell
1729|810|Military
1717|790|Inseki
1678|756|Bodysuit
1775|753|Gokkun
1912|741|Facesitting
2078|726|Blowjob Face
1600|722|Cervix Penetration
1807|707|Magical Girl
1679|704|Breast Expansion
1665|704|Strap-On
1841|698|Latex
1685|683|Prostate Massage
1798|669|Vaginal Birth
1812|668|Enema
1792|655|Orgasm Denial
20638|646|Bald
1862|644|School Swimsuit
20032|642|Short Hair
36922|638|Very Long Hair
2754|634|Licking
1749|633|Drunk
1712|633|Sole Dickgirl
2753|631|Futanari On Female
1609|627|Transformation
1643|626|Apron
1631|614|Business Suit
1820|597|Fox Girl
1821|576|Humiliation
1971|574|Cosplay
1886|544|Futanari On Futanari
20114|540|Exposed Clothing
1750|540|Hairy Armpits
1716|540|Tiara
1815|538|BDSM
1817|536|Futanari On Male
1793|533|Voyeurism
1735|532|Tall Girl
1638|531|Urethra Insertion
1858|525|Wings
1809|518|Nurse
21140|512|Crotch Tattoo
1944|511|Bisexual
1730|504|Bike Shorts
1610|500|Snuff
1731|497|Sumata
1804|492|Big Nipples
1711|488|Pegging
1675|485|Spanking
1566|480|Miko
1803|477|Piss Drinking
1654|476|Fisting
1748|472|Cousin
1776|472|Smegma
1713|471|Hotpants
1794|466|Non-H
1632|460|Chikan
1773|452|Yandere
1709|432|Tribadism
1813|423|Torture
1628|417|Twins
21192|413|Hidden Sex
1958|411|Bathroom Sex
1646|410|Oyakodon
2499|408|Reverse Rape
2554|402|Furry
1561|399|Petplay
1619|386|Shibari
1818|385|Male On Futanari
1623|381|Guro
1753|369|Body Modification
1915|365|Feminization
1962|362|Huge Penis
1836|362|Triple Penetration
1751|359|Nipple Fuck
1823|358|Shemale
1766|354|Aunt
1783|353|Possession
1819|349|Ryona
1715|347|Tail Plug
1952|344|Public Use
1597|341|Vore
2743|340|Sixty-Nine
2739|339|Reverse Harem
1771|336|Tracksuit
1869|331|Shimapan
1673|328|Phimosis
1656|326|Gaping
1719|320|Large Insertions
2139|320|Multimouth Blowjob
1562|319|Body Writing
1868|317|Foot Licking
1689|314|Blood
1906|314|Onahole
1885|312|CBT
1669|312|Emotionless Sex
2758|312|Spitroast
1601|310|Double Vaginal
1756|308|Eyepatch
1620|308|Milking
1840|306|Chastity Belt
1855|305|Armpit Sex
2752|305|Idol
1768|299|Dog Girl
1842|292|Masked Face
2300|288|Comedy
2076|286|Ball Sucking
24194|286|Hair Buns
1889|285|Moral Degeneration
2755|279|Netori
1760|276|Pasties
1897|273|Armpit Licking
1706|271|Incomplete
26245|271|Shimaidon
1960|267|Asphyxiation
1839|267|Nun
1834|264|Multiple Paizuri
26946|262|Facial Hair
1848|261|Cheerleader
1657|260|Lab Coat
1825|260|Oni
25344|258|Leash
1781|256|Big Clit
1870|253|Body Swap
2746|252|Childhood Friend
1546|252|Dog
1791|245|Chloroform
1895|245|Egg Laying
1725|243|Orc
1702|241|Vomit
1908|240|Cowgirl
1787|239|Waitress
1621|239|Witch
1645|233|Widow
1767|231|Swinging
1658|230|Corset
1890|227|Freckles
1854|226|Kunoichi
1598|226|Parasite
1625|225|Bride
1921|225|Niece
22658|223|Fishnets
1786|223|Scar
2027|222|Big Balls
1893|222|Layer Cake
25437|222|No Penetration
1754|219|Machine
1724|219|Metal Armor
1736|214|Assjob
25578|214|Superhero
1830|213|Long Tongue
22655|212|Clothed Paizuri
2288|212|Gyaru-Oh
1624|211|Amputee
1661|211|Frottage
2011|208|Chinese Dress
1824|207|Prolapse
1837|205|Double Anal
1707|204|Sundress
1811|203|Nose Hook
1850|202|Human On Furry
1877|201|Age Progression
1774|201|Solo Action
1687|199|Eyemask
2750|194|Small Penis
1902|193|Sunglasses
1741|192|Tall Man
"""
}
