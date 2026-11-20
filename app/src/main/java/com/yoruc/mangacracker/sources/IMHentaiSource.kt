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
import com.yoruc.mangacracker.core.str
import com.yoruc.mangacracker.core.strings
import com.yoruc.mangacracker.core.urlEncode
import kotlinx.serialization.json.JsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.util.concurrent.ConcurrentHashMap

/**
 * IMHentai through its HTML pages (the same family as HentaiFox and nhentai.xxx). Every title is one gallery. List
 * thumbs carry numeric tag ids (data-tags), so minors galleries are dropped from every list; the tag search also carries
 * -tag:"name" exclusions. The site asks visitors to confirm they are adults with a button: the app asks the same once
 * ([ageNotice]) and reads nothing from the site before that. Spec: _dev_tools/specs/imhentai.md
 */
object IMHentaiSource : Source {
    override val id = "imhentai"
    override val name = "IMHentai"
    override val baseUrl = "https://imhentai.xxx"
    override val blurb = "imhentai.xxx · doujinshi, manga, western, CG sets"
    override val color = 0xFFDB2777
    override val nsfw = true
    override val hasChapters = false
    override val ageNotice = "IMHentai is reserved for adults aged 18 and over, and asks you to confirm your age before you continue."

    /** lolicon 4, shotacon 38, oppai loli 299, low lolicon 138, low shotacon 475, toddlercon 17936, kodomo doushi 17884, kodomo only 17885, loli 1374, shota 2202, randoseru 702, kindergarten uniform 695, age regression 245 */
    private val HIDDEN_IDS = setOf("4", "38", "299", "138", "475", "17936", "17884", "17885", "1374", "2202", "702", "695", "245")
    private val HIDDEN_SLUGS = setOf("lolicon", "shotacon", "oppai-loli", "low-lolicon", "low-shotacon", "toddlercon",
        "kodomo-doushi", "kodomo-only", "loli", "shota", "randoseru", "kindergarten-uniform", "age-regression")
    /** Tag ids the switches care about: AI, futanari, dickgirl on male / on dickgirl, furry, human on furry. */
    private val NAMED = mapOf("17861" to "ai generated", "68" to "futanari", "228" to "dickgirl on male",
        "102" to "dickgirl on dickgirl", "291" to "furry", "436" to "human on furry")
    /** The tag search only knows whole tag names in quotes (a name it does not know is ignored, not an error). */
    private val GUARD = HIDDEN_SLUGS.joinToString(" ") { "-tag:\"" + it.replace('-', ' ') + "\"" }

    /** What the tag search can ask for by name. */
    private val KINDS = setOf("tag", "artist", "group", "parody", "character")
    private val LIST_KINDS = mapOf("Parodies" to "parody", "Characters" to "character", "Tags" to "tag", "Artists" to "artist",
        "Groups" to "group", "Languages" to "language", "Category" to "category")
    /** The search forms' on / off switches: languages and categories (all on = any). */
    private val LANGS = listOf("en" to "English", "jp" to "Japanese", "es" to "Spanish", "fr" to "French", "kr" to "Korean",
        "de" to "German", "ru" to "Russian")
    private val CATS = listOf("m" to "Manga", "d" to "Doujinshi", "w" to "Western", "i" to "Image Set", "a" to "Artist CG", "g" to "Game CG")

    /** What kind of name it is, for names seen on a title page. */
    private val kinds = ConcurrentHashMap<String, String>()

    init {
        // covers answer 403 to a foreign Referer
        Http.referers["imhentai.xxx"] = "https://imhentai.xxx/"
        // pages only: the image hosts (m1 … m11.imhentai.xxx) stay unthrottled
        listOf("gallery", "search", "advsearch").forEach { Http.rateLimits["imhentai.xxx/$it"] = 800L }
    }

    override val selects = listOf(
        SelectDef("sort", "Sort", listOf("" to "Latest", "pp" to "Popular", "tr" to "Top rated", "dl" to "Most downloaded")),
        SelectDef("lang", "Language", listOf("" to "Any") + LANGS),
        SelectDef("type", "Type", listOf("" to "Any") + CATS),
    )

    private val NONE = PageResult(emptyList(), false)

    private suspend fun page(path: String): Document? {
        if (id !in Repo.settings.value.ageConfirmed) throw SourceException("Open IMHentai in Browse and confirm your age first.")
        val (code, html) = Http.getRaw(baseUrl + path)
        if (code == 404) return null
        if (code !in 200..299) throw SourceException("IMHentai answered $code")
        return Jsoup.parse(html, baseUrl + path)
    }

    // ---------- lists ----------

    private val GALLERY = Regex("""/gallery/(\d+)""")

    private fun item(t: Element): MangaRef? {
        // the title link sits in .caption, inside an h2 on the home page and bare on search pages
        val a = t.selectFirst(".caption a[href*=/gallery/]") ?: return null
        val gid = GALLERY.find(a.attr("href"))?.groupValues?.get(1) ?: return null
        val ids = t.attr("data-tags").split(' ').filter { it.isNotEmpty() }
        if (ids.any { it in HIDDEN_IDS }) return null
        val img = t.selectFirst(".inner_thumb img")
        val cover = img?.attr("abs:data-src")?.ifEmpty { null } ?: img?.attr("abs:src").orEmpty()
        return MangaRef(
            // search pages escape titles twice ("Can&amp;#039;t")
            source = id, id = gid, title = Parser.unescapeEntities(a.text(), false),
            cover = if (cover.startsWith("http")) cover else "",
            // lists only carry tag ids; the names let Safety's switches see them
            tags = ids.map { "im:$it" } + ids.mapNotNull { NAMED[it] },
            subtitle = t.selectFirst(".thumb_cat")?.text().orEmpty(),
        )
    }

    private suspend fun list(path: String): PageResult {
        var doc = page(path) ?: return NONE
        // the tag search once came back as a bare page (no thumbs, no "(N) results found", no error): ask once more
        if (path.startsWith("/advsearch/") && doc.selectFirst("div.thumb, h2.headingadv, div.alert") == null) doc = page(path) ?: return NONE
        val items = doc.select("div.thumb").mapNotNull { item(it) }
        // the last pagination item is "Next", greyed out (class disabled) on the last page
        val last = doc.select("ul.pagination li").lastOrNull()
        return PageResult(items, last != null && last.text().trim().startsWith("Next", true) && !last.hasClass("disabled"))
    }

    /** lt / pp / dl / tr = sort (one of them 1), m d w i a g = categories, en jp es fr kr de ru = languages. */
    private fun flags(sort: String, lang: String, type: String): String =
        (listOf("lt", "pp", "dl", "tr").map { it to (it == sort.ifEmpty { "lt" }) } +
            CATS.map { it.first to (type.isEmpty() || it.first == type) } +
            LANGS.map { it.first to (lang.isEmpty() || it.first == lang) })
            .joinToString("&") { (k, on) -> "$k=${if (on) 1 else 0}" }

    private fun tail(page: Int) = if (page > 1) "&page=$page" else ""

    /** The tag search: +kind:"name" / -kind:"name" terms only (no free words), always with the minors guard. */
    private fun advPath(terms: List<String>, sort: String, lang: String, type: String, page: Int): String =
        "/advsearch/?" + flags(sort, lang, type) + "&apply=search&key=" + (terms + GUARD).joinToString(" ").urlEncode() + tail(page)

    private fun term(sign: Char, tag: String): String? {
        val ns = tag.substringBefore(':', "").takeIf { it in KINDS }
        val name = (if (ns != null) tag.substringAfter(':') else tag).replace("\"", "").trim().lowercase()
        return if (name.isEmpty()) null else "$sign${ns ?: "tag"}:\"$name\""
    }

    private fun slug(s: String) = s.substringAfter(':').trim().lowercase().replace(Regex("""\s+"""), "-")

    private fun unsafe(name: String) = Safety.isMinorTag(name) || slug(name) in HIDDEN_SLUGS

    // newest through the tag search too: the home page repeats its popular block on every page
    override suspend fun latest(page: Int) = list(advPath(emptyList(), "lt", "", "", page))

    // all-time popular through the tag search, which keeps the minors tags out on the site's side
    override suspend fun popular(page: Int) = list(advPath(emptyList(), "pp", "", "", page))

    override suspend fun search(query: SearchQuery, page: Int): PageResult {
        val text = query.text.trim()
        val all = query.include.map { it.id.ifEmpty { it.label } } + (if (query.artist.isNotBlank()) listOf("artist:" + query.artist.trim()) else emptyList())
        if (all.any { unsafe(it) } || unsafe(text) || text.split(' ').any { unsafe(it) }) return NONE
        // a language or category tapped on a title page is one of the form's switches
        fun pick(kind: String, from: List<Pair<String, String>>) = all.firstNotNullOfOrNull { t ->
            if (t.startsWith("$kind:")) from.firstOrNull { it.second.equals(t.substringAfter(':'), true) }?.first else null
        }
        val lang = pick("language", LANGS) ?: query.selects["lang"].orEmpty()
        val type = pick("category", CATS) ?: query.selects["type"].orEmpty()
        val sort = query.selects["sort"].orEmpty()
        val include = all.filterNot { it.startsWith("language:") || it.startsWith("category:") }
        if (text.isNotEmpty()) {
            // the word search knows no tag terms and no exclusions: tag names go in as more words (lists are still filtered by id)
            val key = (listOf(text) + include.map { it.substringAfter(':') }).joinToString(" ")
            return list("/search/?key=" + key.urlEncode() + "&" + flags(sort, lang, type) + tail(page))
        }
        val terms = include.mapNotNull { term('+', it) } + query.exclude.mapNotNull { term('-', it.id.ifEmpty { it.label }) }
        return list(advPath(terms, sort, lang, type, page))
    }

    override fun tagQuery(name: String): SearchQuery {
        val ns = name.substringBefore(':', "").takeIf { it in LIST_KINDS.values }
        val plain = if (ns != null) name.substringAfter(':') else name
        val kind = ns ?: kinds[plain.lowercase()]
        return SearchQuery(include = listOf(Tag(if (kind != null && kind != "tag") "$kind:$plain" else plain, plain)))
    }

    override fun artistQuery(name: String) = SearchQuery(include = listOf(Tag("artist:$name", name)))
    override val authorsAreGroups get() = true
    override fun groupQuery(name: String) = SearchQuery(include = listOf(Tag("group:$name", name)))

    // ---------- tags ----------

    /** The site's 297 most-used tags (>= 4 500 galleries) with counts, from /tags/popular/ on 2026-11-10 (minors tags left out). */
    override suspend fun tags(): List<Tag> = TAGS

    private val TAGS: List<Tag> by lazy {
        TAG_TABLE.trim().lines().mapNotNull { line ->
            val p = line.trim().split('|', limit = 3)
            if (p.size < 3 || p[0] in HIDDEN_SLUGS) null else Tag(p[2], p[2], "Tag", p[1].toIntOrNull() ?: 0)
        }.let { Safety.cleanTags(it) }
    }

    /** The name lists behind the site's own tag search boxes: /info/<tags|parodies|characters>_src_v2.json = [{"label":"Glasses"}]. */
    private val nameLists = ConcurrentHashMap<String, List<String>>()

    private suspend fun names(file: String): List<String> {
        nameLists[file]?.let { return it }
        if (id !in Repo.settings.value.ageConfirmed) return emptyList()
        val list = runCatching { Http.getJson("$baseUrl/info/${file}_src_v2.json").strings("label") }.getOrDefault(emptyList())
        if (list.isNotEmpty()) nameLists[file] = list
        return list
    }

    override suspend fun suggestTags(query: String): List<Tag> {
        val q = query.trim()
        if (q.length < 2 || unsafe(q)) return emptyList()
        val local = TAGS.filter { it.label.contains(q, true) }
            .sortedWith(compareBy<Tag> { !it.label.startsWith(q, true) }.thenByDescending { it.count }).take(30)
        val more = listOf("tags" to "tag", "parodies" to "parody", "characters" to "character").flatMap { (file, kind) ->
            names(file).filter { it.contains(q, true) }.sortedBy { !it.startsWith(q, true) }.take(15).map { n ->
                val low = n.lowercase()
                if (kind == "tag") Tag(low, low, "Tag") else Tag("$kind:$low", "$low ($kind)", kind.replaceFirstChar { it.uppercase() })
            }
        }
        // artists and groups are long lists on the site: the typed name as it is (group "typed": only the filter sheet offers them)
        val typed = listOf(Tag("artist:$q", "artist: $q", "typed"), Tag("group:$q", "group: $q", "typed"))
        return Safety.cleanTags(local + more + typed).filterNot { unsafe(it.id) }.distinctBy { it.id }
    }

    // ---------- gallery ----------

    private class Gallery(val doc: Document, val names: Map<String, List<String>>, val slugs: Set<String>)
    private val cache = ConcurrentHashMap<String, Gallery>()

    private suspend fun gallery(gid: String): Gallery {
        cache[gid]?.let { return it }
        val doc = page("/gallery/$gid/") ?: throw SourceException("Gallery not found")
        if (doc.selectFirst("div.right_details") == null) throw SourceException("Gallery not found")
        val names = LinkedHashMap<String, List<String>>()
        val slugs = HashSet<String>()
        doc.select("div.right_details ul.galleries_info li").forEach { li ->
            val kind = LIST_KINDS[li.selectFirst("span.tags_text")?.text()?.trim()?.removeSuffix(":")] ?: return@forEach
            names[kind] = li.select("a.tag").mapNotNull { a ->
                // the link's own text, without the count badge
                val n = a.ownText().trim().ifEmpty { return@mapNotNull null }
                if (kind == "tag") slugs += a.attr("href").trimEnd('/').substringAfterLast('/')
                kinds[n.lowercase()] = kind
                n
            }
        }
        return Gallery(doc, names, slugs).also { if (cache.size > 200) cache.clear(); cache[gid] = it }
    }

    private fun hidden(g: Gallery) = g.slugs.any { it in HIDDEN_SLUGS }

    private fun count(g: Gallery) = g.doc.selectFirst("ul.galleries_info li.pages")?.text()?.substringAfter(':')?.trim()?.toIntOrNull()
        ?: g.doc.selectFirst("#load_pages")?.attr("value")?.toIntOrNull() ?: 0

    override suspend fun details(ref: MangaRef): MangaDetails {
        val g = gallery(ref.id)
        if (hidden(g)) throw SourceException("This title is hidden")
        fun of(k: String) = g.names[k].orEmpty()
        val title = g.doc.selectFirst("div.right_details h1")?.text().orEmpty().ifEmpty { ref.title }
        val pages = count(g)
        val cover = g.doc.selectFirst("div.left_cover img")
        val d = MangaDetails(
            ref = MangaRef(id, ref.id, title,
                cover = cover?.attr("abs:data-src")?.ifEmpty { null } ?: cover?.attr("abs:src")?.takeIf { it.startsWith("http") } ?: ref.cover,
                tags = g.names.flatMap { (k, v) -> v.map { "$k:$it" } },
                subtitle = if (pages > 0) "$pages pages" else ref.subtitle),
            // the line under the title is the original (Japanese) one
            altTitles = listOfNotNull(g.doc.selectFirst("div.right_details p.subtitle")?.text()?.trim()?.ifEmpty { null }).filter { it != title },
            authors = of("group"),
            artists = of("artist"),
            genres = of("tag"),
            tagGroups = listOf("parody" to "Parody", "character" to "Character", "language" to "Language", "category" to "Category")
                .mapNotNull { (k, label) -> of(k).takeIf { it.isNotEmpty() }?.let { TagGroup(label, it) } },
            status = "Complete",
            type = of("category").firstOrNull()?.replaceFirstChar { it.uppercase() }.orEmpty(),
            pageCount = pages,
            url = webUrl(ref),
            extra = listOfNotNull(posted(g).ifEmpty { null }?.let { "Posted" to it }),
        )
        if (Safety.blocked(d)) throw SourceException("This title is hidden")
        return d
    }

    /** "Posted: 4 months ago" (the page keeps it in a hidden list item). */
    private fun posted(g: Gallery) = g.doc.selectFirst("ul.galleries_info li.posted")?.text()?.substringAfter(':')?.trim().orEmpty()

    override suspend fun chapters(ref: MangaRef): List<Chapter> {
        val g = gallery(ref.id)
        return listOf(Chapter("g", "Gallery · ${count(g)} pages", 1f, ago(posted(g)), g.names["group"]?.firstOrNull().orEmpty(), count(g)))
    }

    private val G_TH = Regex("""g_th\s*=\s*\$\.parseJSON\('(.*?)'\)""", RegexOption.DOT_MATCHES_ALL)
    private val EXT = mapOf("j" to "jpg", "p" to "png", "b" to "bmp", "g" to "gif", "w" to "webp")

    /** Built like the site's reader: https://m<load_server>.imhentai.xxx/<load_dir>/<load_id>/<n>.<ext>, ext and size from g_th. */
    override suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page> {
        val g = gallery(ref.id)
        if (hidden(g) || g.names.values.flatten().any { Safety.blocked(listOf(it), id) }) throw SourceException("This title is hidden")
        fun v(name: String) = g.doc.selectFirst("input#$name")?.attr("value").orEmpty()
        if (v("load_server").isEmpty() || v("load_dir").isEmpty() || v("load_id").isEmpty()) throw SourceException("No pages found")
        val host = "https://m${v("load_server")}.imhentai.xxx/${v("load_dir")}/${v("load_id")}"
        val th = g.doc.select("script").firstNotNullOfOrNull { s -> G_TH.find(s.data())?.groupValues?.get(1) }
            ?.let { runCatching { Http.json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        if (th == null || th.isEmpty()) {
            // GUESS fallback: page count + jpg
            val n = v("load_pages").toIntOrNull() ?: 0
            if (n == 0) throw SourceException("No pages found")
            return (1..n).map { Page(it - 1, "$host/$it.jpg") }
        }
        return th.keys.mapNotNull { it.toIntOrNull() }.sorted().mapIndexed { i, n ->
            val parts = th.str(n.toString()).split(',')
            val ext = EXT[parts.getOrNull(0)] ?: "jpg"
            Page(i, "$host/$n.$ext", parts.getOrNull(1)?.toIntOrNull() ?: 0, parts.getOrNull(2)?.toIntOrNull() ?: 0)
        }
    }

    override suspend fun related(ref: MangaRef, details: MangaDetails): List<MangaRef> =
        gallery(ref.id).doc.select("div.related div.thumb").mapNotNull { item(it) }.filter { it.id != ref.id }

    override fun webUrl(ref: MangaRef) = "$baseUrl/gallery/${ref.id}/"

    // ---------- "1 day ago" / "4 months ago" / "just now" ----------

    private val RELATIVE = Regex("""(\d+|an?|one)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE)

    private fun ago(s: String): Long {
        val m = RELATIVE.find(s) ?: return if (s.contains("just now", true)) System.currentTimeMillis() else 0L
        val n = m.groupValues[1].toLongOrNull() ?: 1L
        val unit = when (m.groupValues[2].lowercase()) {
            "second" -> 1_000L; "minute" -> 60_000L; "hour" -> 3_600_000L; "day" -> 86_400_000L
            "week" -> 7 * 86_400_000L; "month" -> 30 * 86_400_000L; else -> 365 * 86_400_000L
        }
        return System.currentTimeMillis() - n * unit
    }

    // slug|count|name, generated by the probe's make_tags.py
    private const val TAG_TABLE = """
big-breasts|458160|big breasts
sole-female|383494|sole female
sole-male|293905|sole male
nakadashi|230206|nakadashi
anal|226423|anal
blowjob|207533|blowjob
group|204788|group
stockings|189886|stockings
mosaic-censorship|160121|mosaic censorship
full-color|149242|full color
schoolgirl-uniform|134244|schoolgirl uniform
bondage|122079|bondage
dark-skin|120639|dark skin
glasses|117850|glasses
ahegao|113650|ahegao
yaoi|111865|yaoi
rape|111201|rape
variant-set|100861|variant set
big-penis|99490|big penis
paizuri|96415|paizuri
furry|95508|furry
x-ray|91527|x-ray
big-ass|88976|big ass
milf|88360|milf
incest|87691|incest
males-only|83165|males only
futanari|81875|futanari
comic|81057|comic
sex-toys|81010|sex toys
multi-work-series|78331|multi-work series
muscle|71027|muscle
double-penetration|69248|double penetration
swimsuit|67008|swimsuit
collar|66785|collar
yuri|65179|yuri
netorare|64507|netorare
impregnation|64410|impregnation
twintails|62978|twintails
defloration|62235|defloration
kemonomimi|61341|kemonomimi
huge-breasts|59295|huge breasts
ponytail|58619|ponytail
hairy|58615|hairy
femdom|57602|femdom
tentacles|57481|tentacles
pregnant|56597|pregnant
uncensored|55088|uncensored
ffm-threesome|54151|ffm threesome
anal-intercourse|52159|anal intercourse
bikini|51844|bikini
lactation|51716|lactation
pantyhose|46735|pantyhose
masturbation|46699|masturbation
western-imageset|46078|western imageset
dilf|43588|dilf
sweating|43505|sweating
bbm|42960|bbm
handjob|42754|handjob
kissing|42120|kissing
cheating|40966|cheating
gloves|40722|gloves
mind-control|39310|mind control
unusual-pupils|37233|unusual pupils
western-cg|37199|western cg
sister|36686|sister
urination|36156|urination
mind-break|35992|mind break
mmf-threesome|35847|mmf threesome
crossdressing|35527|crossdressing
tomgirl|35480|tomgirl
horns|34467|horns
tankoubon|34374|tankoubon
exhibitionism|34136|exhibitionism
very-long-hair|32476|very long hair
bukkake|31636|bukkake
piercing|31423|piercing
mother|31300|mother
full-censorship|30956|full censorship
catgirl|30869|catgirl
big-areolae|30356|big areolae
no-penetration|30252|no penetration
lingerie|30149|lingerie
maid|29872|maid
footjob|29652|footjob
stomach-deformation|29043|stomach deformation
gag|29019|gag
bunny-girl|28973|bunny girl
females-only|28803|females only
tail|28094|tail
elf|28044|elf
cunnilingus|27764|cunnilingus
bbw|27622|bbw
fingering|27406|fingering
demon-girl|26601|demon girl
beauty-mark|26300|beauty mark
shemale|26116|shemale
inflation|26077|inflation
harem|25746|harem
schoolboy-uniform|25649|schoolboy uniform
small-breasts|25330|small breasts
condom|25244|condom
bestiality|23978|bestiality
teacher|23644|teacher
scat|23527|scat
monster|23230|monster
blindfold|22989|blindfold
first-person-perspective|21979|first person perspective
monster-girl|21801|monster girl
garter-belt|21615|garter belt
rimjob|20665|rimjob
tanlines|20428|tanlines
filming|19677|filming
vtuber|19582|vtuber
sole-dickgirl|19292|sole dickgirl
story-arc|19263|story arc
prostitution|19010|prostitution
rough-translation|18890|rough translation
bald|18758|bald
deepthroat|18584|deepthroat
huge-penis|18339|huge penis
gyaru|17681|gyaru
drugs|17556|drugs
transformation|17510|transformation
big-balls|17430|big balls
squirting|17313|squirting
breast-expansion|17261|breast expansion
anthology|16778|anthology
big-nipples|16324|big nipples
gender-bender|16089|gender bender
bodysuit|15746|bodysuit
eye-covering-bang|15076|eye-covering bang
wings|14851|wings
halo|14804|halo
fox-girl|14541|fox girl
leotard|14535|leotard
kimono|14329|kimono
sleeping|13997|sleeping
snuff|13859|snuff
animated|13815|animated
blackmail|13710|blackmail
guro|13695|guro
thigh-high-boots|13465|thigh high boots
bloomers|13419|bloomers
latex|13373|latex
corruption|13317|corruption
vore|13201|vore
tall-girl|12947|tall girl
gender-change|12739|gender change
virginity|12715|virginity
breast-feeding|12457|breast feeding
nudity-only|12106|nudity only
humiliation|11935|humiliation
slave|11844|slave
inverted-nipples|11764|inverted nipples
blowjob-face|11752|blowjob face
spanking|11619|spanking
nipple-stimulation|11600|nipple stimulation
strap-on|11536|strap-on
crotch-tattoo|11298|crotch tattoo
hairy-armpits|11291|hairy armpits
tomboy|11248|tomboy
cumflation|11200|cumflation
shibari|11134|shibari
smell|10814|smell
bdsm|10797|bdsm
school-swimsuit|10745|school swimsuit
human-on-furry|10705|human on furry
daughter|10535|daughter
body-writing|10458|body writing
urethra-insertion|10452|urethra insertion
enema|10404|enema
bisexual|10275|bisexual
hair-buns|10060|hair buns
dickgirl-on-male|9931|dickgirl on male
dickgirl-on-dickgirl|9900|dickgirl on dickgirl
catboy|9804|catboy
gaping|9711|gaping
ryona|9636|ryona
business-suit|9562|business suit
inseki|9301|inseki
birth|9251|birth
dick-growth|9229|dick growth
torture|9223|torture
nurse|9205|nurse
ai-generated|9085|ai generated
old-man|8944|old man
magical-girl|8939|magical girl
cowgirl|8922|cowgirl
masked-face|8886|masked face
nun|8707|nun
sample|8674|sample
already-uploaded|8436|already uploaded
nipple-fuck|8432|nipple fuck
apron|8409|apron
cervix-penetration|8405|cervix penetration
facial-hair|8309|facial hair
gokkun|8299|gokkun
leash|8248|leash
facesitting|8211|facesitting
dickgirl-on-female|8022|dickgirl on female
makeup|7946|makeup
giantess|7919|giantess
focus-anal|7880|focus anal
leg-lock|7825|leg lock
prostate-massage|7820|prostate massage
pixie-cut|7601|pixie cut
fisting|7547|fisting
miko|7472|miko
small-penis|7379|small penis
dog-girl|7344|dog girl
robot|7243|robot
hidden-sex|7202|hidden sex
feminization|7109|feminization
chikan|7003|chikan
drunk|6997|drunk
horse-girl|6984|horse girl
tiara|6970|tiara
dog|6907|dog
milking|6864|milking
cosplaying|6854|cosplaying
solo-action|6817|solo action
male-on-dickgirl|6800|male on dickgirl
smegma|6787|smegma
dog-boy|6710|dog boy
3d|6701|3d
large-insertions|6579|large insertions
chastity-belt|6566|chastity belt
dark-nipples|6538|dark nipples
painted-nails|6531|painted nails
tall-man|6507|tall man
pasties|6494|pasties
amputee|6483|amputee
focus-blowjob|6428|focus blowjob
bike-shorts|6331|bike shorts
eggs|6321|eggs
large-tattoo|6285|large tattoo
blood|6265|blood
voyeurism|6205|voyeurism
eyepatch|6196|eyepatch
tail-plug|6187|tail plug
military|6167|military
body-modification|6100|body modification
tribadism|6050|tribadism
oyakodon|6032|oyakodon
incomplete|6027|incomplete
nipple-piercing|5855|nipple piercing
hotpants|5828|hotpants
freckles|5815|freckles
witch|5795|witch
horse|5791|horse
pegging|5744|pegging
themeless|5738|themeless
asphyxiation|5735|asphyxiation
prolapse|5731|prolapse
fishnets|5730|fishnets
scanmark|5680|scanmark
focus-paizuri|5669|focus paizuri
clothed-female-nude-male|5649|clothed female nude male
farting|5630|farting
exposed-clothing|5617|exposed clothing
novel|5603|novel
demon|5592|demon
piss-drinking|5585|piss drinking
wolf-girl|5563|wolf girl
orc|5538|orc
foot-licking|5503|foot licking
wolf-boy|5492|wolf boy
oni|5463|oni
triple-penetration|5384|triple penetration
possession|5381|possession
scar|5308|scar
artbook|5308|artbook
slime|5293|slime
cbt|5142|cbt
twins|5103|twins
multiple-orgasms|5093|multiple orgasms
lizard-guy|5055|lizard guy
chloroform|4984|chloroform
eyemask|4904|eyemask
gyaru-oh|4896|gyaru-oh
long-tongue|4822|long tongue
miniguy|4822|miniguy
high-heels|4810|high heels
sumata|4809|sumata
goblin|4771|goblin
christmas|4755|christmas
emotionless-sex|4731|emotionless sex
vaginal-birth|4706|vaginal birth
orgasm-denial|4702|orgasm denial
machine|4701|machine
tickling|4607|tickling
public-use|4598|public use
bride|4571|bride
lizard-girl|4557|lizard girl
nose-hook|4549|nose hook
unusual-teeth|4524|unusual teeth
petplay|4520|petplay
"""
}
