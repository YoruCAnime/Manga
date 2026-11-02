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
import com.yoruc.mangacracker.core.str
import com.yoruc.mangacracker.core.urlEncode
import kotlinx.serialization.json.JsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

/**
 * HentaiFox through its HTML pages. Every title is one gallery. List thumbs carry numeric tag ids (data-tags), so
 * minors galleries are dropped from every list; the search also carries -slug exclusions. Spec: _dev_tools/specs/hentaifox.md
 */
object HentaiFoxSource : Source {
    override val id = "hentaifox"
    override val name = "HentaiFox"
    override val baseUrl = "https://hentaifox.com"
    override val blurb = "hentaifox.com · doujinshi, manga"
    override val color = 0xFF4F46E5
    override val nsfw = true
    override val hasChapters = false

    /** lolicon 8, shotacon 11, oppai loli 45, low lolicon 111, low shotacon 268, toddlercon 715, kodomo doushi 3321, kodomo only 3322, shota 1463, loli 1472 */
    private val HIDDEN_IDS = setOf("8", "11", "45", "111", "268", "715", "3321", "3322", "1463", "1472")
    private val HIDDEN_SLUGS = setOf("lolicon", "shotacon", "oppai-loli", "low-lolicon", "low-shotacon", "toddlercon",
        "kodomo-doushi", "kodomo-only", "shota", "loli")
    private val GUARD = HIDDEN_SLUGS.joinToString(" ") { "-$it" }

    init {
        Http.referers["hentaifox.com"] = "https://hentaifox.com/"
        // pages only: the image hosts (i., i2., i3.hentaifox.com) stay unthrottled
        listOf("gallery", "search", "pag", "page").forEach { Http.rateLimits["hentaifox.com/$it"] = 600L }
    }

    override val selects = listOf(
        SelectDef("sort", "Sort", listOf("" to "Newest", "popular" to "Popular")),
        SelectDef("lang", "Language", listOf("" to "Any", "english" to "English", "japanese" to "Japanese", "chinese" to "Chinese")),
    )

    // ---------- lists ----------

    private val GALLERY = Regex("""/gallery/(\d+)""")

    private fun item(t: Element): MangaRef? {
        val a = t.selectFirst("h2.g_title a") ?: return null // home-page parody / character cards have no g_title
        val gid = GALLERY.find(a.attr("href"))?.groupValues?.get(1) ?: return null
        val ids = t.attr("data-tags").split(' ').filter { it.isNotEmpty() }
        if (ids.any { it in HIDDEN_IDS }) return null
        val img = t.selectFirst(".inner_thumb img")
        val cover = img?.attr("abs:data-src")?.ifEmpty { null } ?: img?.attr("abs:src").orEmpty()
        return MangaRef(
            source = id, id = gid, title = a.text(),
            cover = if (cover.startsWith("http")) cover else "",
            // 3298 = "ai generated" (lists only carry tag ids; the name lets Safety's AI switch see it)
            tags = ids.map { "hf:$it" } + (if ("3298" in ids) listOf("ai generated") else emptyList()),
            subtitle = t.selectFirst(".g_cat")?.text().orEmpty(),
        )
    }

    private suspend fun list(path: String): PageResult {
        val (code, html) = Http.getRaw(baseUrl + path)
        if (code == 404) return PageResult(emptyList(), false)
        if (code !in 200..299) throw SourceException("HentaiFox answered $code")
        val doc = Jsoup.parse(html, baseUrl + path)
        val items = doc.select("div.thumb").mapNotNull { item(it) }
        // the last pagination item is "Next", greyed out (class disabled) on the last page
        val last = doc.select("ul.pagination li").lastOrNull()
        val next = last != null && last.text().trim().equals("Next", true) && !last.hasClass("disabled")
        return PageResult(items, next)
    }

    /** "words" + the minors guard; the site needs at least one positive word, so "english" stands in when there is none. */
    private fun searchPath(words: List<String>, page: Int, sort: String): String {
        val positive = words.ifEmpty { listOf("english") }
        val q = (positive + GUARD).joinToString(" ").urlEncode()
        return "/search/?q=$q" + (if (sort.isNotEmpty()) "&sort=$sort" else "") + (if (page > 1) "&page=$page" else "")
    }

    // the home page pages oddly: /, /page/2/, then /pag/3/, /pag/4/ ... (/pag/2/ goes back to /)
    override suspend fun latest(page: Int) = list(when (page) { 1 -> "/"; 2 -> "/page/2/"; else -> "/pag/$page/" })

    override suspend fun popular(page: Int) = list(searchPath(listOf("english"), page, "popular"))

    override suspend fun search(query: SearchQuery, page: Int): PageResult {
        val words = mutableListOf<String>()
        if (query.text.isNotBlank()) words += query.text.trim()
        // a tag is a quoted phrase; an exclusion must be the hyphenated slug ("-big breasts" would exclude only "big")
        query.include.forEach { words += "\"" + phrase(it) + "\"" }
        if (query.artist.isNotBlank()) words += "\"" + query.artist.trim() + "\""
        query.selects["lang"]?.takeIf { it.isNotEmpty() }?.let { words += it }
        val excludes = query.exclude.map { "-" + slug(it.id.ifEmpty { it.label }) }.filter { it.length > 1 }
        val all = if (words.isEmpty()) listOf("english") + excludes else words + excludes
        return list(searchPath(all, page, query.selects["sort"].orEmpty()))
    }

    private fun slug(s: String) = s.substringAfter(':').trim().lowercase().replace(Regex("""\s+"""), "-").replace(Regex("""[^a-z0-9\-]"""), "")
    private fun phrase(t: Tag) = t.id.substringAfter(':').ifEmpty { t.label }.replace('-', ' ').replace("\"", "").trim()

    // ---------- tags ----------

    /** The site's 472 most-used tags (>= 150 galleries) with counts, from /tags/ on 2026-11-02 (minors tags left out). */
    override suspend fun tags(): List<Tag> = TAGS

    private val TAGS: List<Tag> by lazy {
        TAG_TABLE.trim().lines().mapNotNull { line ->
            val p = line.trim().split('|', limit = 3)
            if (p.size < 3) null else Tag(p[0], p[2], "Tag", p[1].toIntOrNull() ?: 0)
        }.let { Safety.cleanTags(it) }
    }

    override suspend fun suggestTags(query: String): List<Tag> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val local = TAGS.filter { it.label.contains(q, true) || it.id.contains(slug(q)) }
            .sortedWith(compareBy<Tag> { !it.label.startsWith(q, true) }.thenByDescending { it.count }).take(40)
        // no tag-search on the site: the typed words also work as a phrase (artist, parody, character, group ...)
        val typed = Tag(slug(q), "$q (any name)", "typed")
        return Safety.cleanTags(local + typed).distinctBy { it.id }
    }

    override fun tagQuery(name: String) = SearchQuery(include = listOf(Tag(slug(name), name)))

    // ---------- gallery ----------

    private data class Cached(val at: Long, val doc: Document)
    private val galleries = ConcurrentHashMap<String, Cached>()

    private suspend fun gallery(gid: String): Document {
        galleries[gid]?.takeIf { System.currentTimeMillis() - it.at < 30 * 60_000L }?.let { return it.doc }
        val (code, html) = Http.getRaw("$baseUrl/gallery/$gid/")
        if (code == 404) throw SourceException("Gallery not found")
        if (code !in 200..299) throw SourceException("HentaiFox answered $code")
        val doc = Jsoup.parse(html, "$baseUrl/gallery/$gid/")
        if (doc.selectFirst("div.gallery_right div.info") == null) throw SourceException("Gallery not found")
        galleries[gid] = Cached(System.currentTimeMillis(), doc)
        return doc
    }

    /** (name, slug) of one tag list in the info box: tags, artists, groups, parodies, characters, languages, categories. */
    private fun group(doc: Document, cls: String): List<Pair<String, String>> =
        doc.select("div.gallery_right div.info ul.$cls a.tag_btn").map { a ->
            a.ownText().trim() to a.attr("href").trimEnd('/').substringAfterLast('/')
        }

    private fun hidden(doc: Document) = group(doc, "tags").any { it.second in HIDDEN_SLUGS }

    private fun info(doc: Document, label: String): String =
        doc.select("div.gallery_right div.info span.i_text.pages").map { it.text() }
            .firstOrNull { it.startsWith("$label:", true) }?.substringAfter(':')?.trim().orEmpty()

    override suspend fun details(ref: MangaRef): MangaDetails {
        val doc = gallery(ref.id)
        if (hidden(doc)) throw SourceException("This title is hidden")
        val title = doc.selectFirst("div.gallery_right div.info h1")?.text().orEmpty().ifEmpty { ref.title }
        val tags = group(doc, "tags").map { it.first }
        val names = listOf("parodies" to "Parody", "characters" to "Character", "languages" to "Language", "categories" to "Category")
        val pages = info(doc, "Pages").toIntOrNull() ?: 0
        val likes = doc.selectFirst("#thumbs_up")?.text()?.trim().orEmpty()
        val d = MangaDetails(
            ref = ref.copy(
                title = title,
                cover = doc.selectFirst("div.gallery_left div.cover img")?.attr("abs:src")?.ifEmpty { null } ?: ref.cover,
                tags = tags + listOf("parodies", "characters", "artists", "groups", "languages", "categories").flatMap { c -> group(doc, c).map { it.first } },
                subtitle = if (pages > 0) "$pages pages" else ref.subtitle,
            ),
            altTitles = title.split(" | ").map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.size > 1 }.orEmpty(),
            authors = group(doc, "groups").map { it.first },
            artists = group(doc, "artists").map { it.first },
            genres = tags,
            tagGroups = names.mapNotNull { (cls, label) -> group(doc, cls).map { it.first }.takeIf { it.isNotEmpty() }?.let { TagGroup(label, it) } },
            status = "Complete",
            type = group(doc, "categories").firstOrNull()?.first?.replaceFirstChar { it.uppercase() }.orEmpty(),
            pageCount = pages,
            url = webUrl(ref),
            extra = listOfNotNull(
                info(doc, "Posted").ifEmpty { null }?.let { "Posted" to it },
                likes.ifEmpty { null }?.let { "Likes" to it },
            ),
        )
        if (Safety.blocked(d)) throw SourceException("This title is hidden")
        return d
    }

    override suspend fun chapters(ref: MangaRef): List<Chapter> {
        val doc = gallery(ref.id)
        val n = info(doc, "Pages").toIntOrNull() ?: doc.selectFirst("#load_pages")?.attr("value")?.toIntOrNull() ?: 0
        return listOf(Chapter("g", "Gallery · $n pages", 1f, posted(info(doc, "Posted")), group(doc, "groups").firstOrNull()?.first.orEmpty(), n))
    }

    private val G_TH = Regex("""g_th\s*=\s*\$\.parseJSON\('(.*?)'\)""", RegexOption.DOT_MATCHES_ALL)
    private val EXT = mapOf("j" to "jpg", "p" to "png", "b" to "bmp", "g" to "gif", "w" to "webp")

    /** Built like the site's reader.min.js: //<host>/<load_dir>/<load_id>/<n>.<ext>, ext and size from g_th. */
    override suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page> {
        val doc = gallery(ref.id)
        if (hidden(doc)) throw SourceException("This title is hidden")
        val loadId = doc.selectFirst("#load_id")?.attr("value").orEmpty()
        val dir = doc.selectFirst("#load_dir")?.attr("value").orEmpty()
        if (loadId.isEmpty() || dir.isEmpty()) throw SourceException("No pages found")
        // galleries above 140236 live on i3; older ones on i or i2 (both serve them, i3 does not)
        val host = if ((ref.id.toLongOrNull() ?: 0L) > 140236L) "i3.hentaifox.com" else listOf("i.hentaifox.com", "i2.hentaifox.com").random()
        val th = doc.select("script").firstNotNullOfOrNull { s -> G_TH.find(s.data())?.groupValues?.get(1) }
            ?.let { runCatching { Http.json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        if (th == null || th.isEmpty()) {
            // GUESS fallback: page count + jpg
            val n = doc.selectFirst("#load_pages")?.attr("value")?.toIntOrNull() ?: 0
            if (n == 0) throw SourceException("No pages found")
            return (1..n).map { Page(it - 1, "https://$host/$dir/$loadId/$it.jpg") }
        }
        return th.keys.mapNotNull { it.toIntOrNull() }.sorted().mapIndexed { i, n ->
            val parts = th.str(n.toString()).split(',')
            val ext = EXT[parts.getOrNull(0)] ?: "jpg"
            Page(i, "https://$host/$dir/$loadId/$n.$ext", parts.getOrNull(1)?.toIntOrNull() ?: 0, parts.getOrNull(2)?.toIntOrNull() ?: 0)
        }
    }

    override suspend fun related(ref: MangaRef, details: MangaDetails): List<MangaRef> =
        gallery(ref.id).select("div.related_galleries div.thumb").mapNotNull { item(it) }.filter { it.id != ref.id }

    override fun webUrl(ref: MangaRef) = "$baseUrl/gallery/${ref.id}/"

    // ---------- "Posted: 3 years ago (Updated 6 years ago)" / "just now" ----------

    private val RELATIVE = Regex("""(\d+|an?|one)\s+(second|minute|hour|day|week|month|year)s?\s+ago""", RegexOption.IGNORE_CASE)

    private fun posted(s: String): Long {
        val m = RELATIVE.find(s) ?: return if (s.contains("just now", true)) System.currentTimeMillis() else 0L
        val n = m.groupValues[1].toLongOrNull() ?: 1L
        val unit = when (m.groupValues[2].lowercase()) {
            "second" -> 1_000L; "minute" -> 60_000L; "hour" -> 3_600_000L; "day" -> 86_400_000L
            "week" -> 7 * 86_400_000L; "month" -> 30 * 86_400_000L; else -> 365 * 86_400_000L
        }
        return System.currentTimeMillis() - n * unit
    }

    // slug|count|name, generated by _dev_tools/probe_hentaifox/make_tags.py
    private const val TAG_TABLE = """
big-breasts|62745|big breasts
sole-female|57244|sole female
sole-male|53708|sole male
nakadashi|43823|nakadashi
anal|33927|anal
blowjob|33800|blowjob
group|31592|group
stockings|26925|stockings
full-color|22083|full color
rape|21782|rape
schoolgirl-uniform|21566|schoolgirl uniform
ahegao|20305|ahegao
glasses|19263|glasses
multi-work-series|18439|multi-work series
incest|18172|incest
x-ray|17391|x-ray
milf|15840|milf
yaoi|15839|yaoi
mosaic-censorship|15671|mosaic censorship
defloration|15366|defloration
bondage|14173|bondage
males-only|13468|males only
big-penis|13385|big penis
paizuri|13067|paizuri
femdom|12793|femdom
dark-skin|12716|dark skin
netorare|12506|netorare
uncensored|12279|uncensored
impregnation|11945|impregnation
double-penetration|11339|double penetration
sex-toys|11250|sex toys
rough-translation|11072|rough translation
hairy|10952|hairy
big-ass|10572|big ass
cheating|10490|cheating
ffm-threesome|10057|ffm threesome
mind-break|9808|mind break
futanari|9499|futanari
sweating|9426|sweating
twintails|9280|twintails
collar|8990|collar
muscle|8980|muscle
dilf|8957|dilf
ponytail|8908|ponytail
kissing|8825|kissing
yuri|8641|yuri
swimsuit|8573|swimsuit
sister|8371|sister
full-censorship|7895|full censorship
bbm|7846|bbm
masturbation|7635|masturbation
mind-control|7093|mind control
exhibitionism|6787|exhibitionism
kemonomimi|6774|kemonomimi (animal ears)
anal-intercourse|6680|anal intercourse
schoolboy-uniform|6673|schoolboy uniform
bikini|6593|bikini
huge-breasts|6485|huge breasts
mother|6334|mother
tomgirl|6293|tomgirl
crossdressing|6215|crossdressing
schoolgirl|6153|schoolgirl
story-arc|6071|story arc
teacher|5968|teacher
pantyhose|5966|pantyhose
unusual-pupils|5954|unusual pupils
mmf-threesome|5900|mmf threesome
handjob|5871|handjob
lactation|5787|lactation
fingering|5677|fingering
tentacles|5532|tentacles
females-only|5311|females only
cunnilingus|5246|cunnilingus
condom|5061|condom
urination|5018|urination
harem|4875|harem
pregnant|4854|pregnant
drugs|4836|drugs
virginity|4750|virginity
prostitution|4711|prostitution
beauty-mark|4691|beauty mark
lingerie|4538|lingerie
small-breasts|4508|small breasts
tankoubon|4434|tankoubon
gloves|4393|gloves
big-areolae|4094|big areolae
filming|4040|filming
blackmail|4010|blackmail
horns|3878|horns
maid|3843|maid
stomach-deformation|3831|stomach deformation
bbw|3787|bbw
rimjob|3573|rimjob
demon-girl|3546|demon girl
footjob|3454|footjob
bukkake|3444|bukkake
squirting|3435|squirting
furry|3399|furry
deepthroat|3398|deepthroat
tail|3341|tail
bald|3157|bald
very-long-hair|3143|very long hair
gyaru|3100|gyaru
piercing|2980|piercing
sole-dickgirl|2973|sole dickgirl
catgirl|2965|catgirl
elf|2963|elf
gag|2949|gag
inflation|2888|inflation
garter-belt|2866|garter belt
already-uploaded|2808|already uploaded
blindfold|2788|blindfold
gender-bender|2749|gender bender
sleeping|2711|sleeping
slave|2705|slave
tanlines|2685|tanlines
tomboy|2671|tomboy
monster|2555|monster
bunny-girl|2551|bunny girl
inseki|2521|inseki
humiliation|2496|humiliation
no-penetration|2444|no penetration
monster-girl|2376|monster girl
scat|2340|scat
breast-feeding|2333|breast feeding
gender-change|2322|gender change
corruption|2297|corruption
snuff|2206|snuff
kimono|2203|kimono
tall-girl|2203|tall girl
smell|2097|smell
daughter|2093|daughter
transformation|2077|transformation
nipple-stimulation|2076|nipple stimulation
webtoon|2066|webtoon
thigh-high-boots|2063|thigh high boots
scanmark|2054|scanmark
inverted-nipples|2041|inverted nipples
leg-lock|2010|leg lock
bisexual|1980|bisexual
prostate-massage|1950|prostate massage
dick-growth|1936|dick growth
blowjob-face|1907|blowjob face
cervix-penetration|1888|cervix penetration
business-suit|1856|business suit
eye-covering-bang|1831|eye-covering bang
bestiality|1825|bestiality
crotch-tattoo|1822|crotch tattoo
drunk|1816|drunk
leotard|1812|leotard
halo|1793|halo
breast-expansion|1759|breast expansion
bodysuit|1733|bodysuit
schoolboy|1716|schoolboy
dickgirl-on-dickgirl|1699|dickgirl on dickgirl
hidden-sex|1686|hidden sex
old-man|1678|old man
gokkun|1677|gokkun
bdsm|1649|bdsm
big-nipples|1648|big nipples
shemale|1641|shemale
dickgirl-on-male|1619|dickgirl on male
wings|1611|wings
latex|1603|latex
bloomers|1578|bloomers
urethra-insertion|1504|urethra insertion
facesitting|1466|facesitting
school-swimsuit|1451|school swimsuit
orgasm-denial|1447|orgasm denial
magical-girl|1445|magical girl
fox-girl|1435|fox girl
hairy-armpits|1416|hairy armpits
voyeurism|1415|voyeurism
strap-on|1405|strap-on
huge-penis|1399|huge penis
apron|1385|apron
incomplete|1384|incomplete
oyakodon|1383|oyakodon
ryona|1382|ryona
vore|1378|vore
possession|1363|possession
enema|1343|enema
feminization|1339|feminization
guro|1310|guro
giantess|1303|giantess
small-penis|1294|small penis
pixie-cut|1279|pixie cut
spanking|1275|spanking
comic|1273|comic
guys-only|1260|guys only
chikan|1234|chikan
military|1212|military
hair-buns|1206|hair buns
cumflation|1201|cumflation
cosplaying|1200|cosplaying
multiple-orgasms|1191|multiple orgasms
facial-hair|1172|facial hair
piss-drinking|1170|piss drinking
torture|1168|torture
yandere|1162|yandere
tribadism|1153|tribadism
shibari|1136|shibari
dickgirl-on-female|1134|dickgirl on female
forbidden-content|1133|forbidden content
forbidden-content|1133|forbidden content
smegma|1120|smegma
body-modification|1119|body modification
emotionless-sex|1112|emotionless sex
hotpants|1087|hotpants
masked-face|1085|masked face
blood|1084|blood
cousin|1069|cousin
nurse|1053|nurse
moral-degeneration|1044|moral degeneration
male-on-dickgirl|1038|male on dickgirl
tail-plug|1026|tail plug
miniguy|1023|miniguy
tall-man|1022|tall man
twins|1022|twins
bike-shorts|1013|bike shorts
chastity-belt|1012|chastity belt
tiara|1007|tiara
fisting|1002|fisting
miko|989|miko
cbt|981|cbt
catboy|973|catboy
leash|968|leash
cowgirl|963|cowgirl
gaping|962|gaping
pegging|961|pegging
nun|960|nun
aunt|959|aunt
birth|959|birth
vtuber|959|vtuber
foot-licking|943|foot licking
sumata|936|sumata
big-balls|909|big balls
rewrite|897|rewrite
triple-penetration|893|triple penetration
chloroform|889|chloroform
clothed-female-nude-male|887|clothed female nude male
solo-action|887|solo action
scar|883|scar
tracksuit|876|tracksuit
armpit-licking|853|armpit licking
gyaru-oh|851|gyaru-oh
asphyxiation|850|asphyxiation
replaced|845|replaced
swinging|842|swinging
oni|834|oni
public-use|816|public use
body-swap|814|body swap
demon|798|demon
3d|794|3d
frottage|794|frottage
large-insertions|792|large insertions
focus-anal|788|focus anal
nipple-fuck|783|nipple fuck
phimosis|783|phimosis
farting|782|farting
thick-eyebrows|782|thick eyebrows
body-writing|776|body writing
pasties|775|pasties
ball-sucking|773|ball sucking
exposed-clothing|771|exposed clothing
brother|766|brother
milking|754|milking
human-on-furry|720|human on furry
eyepatch|719|eyepatch
onahole|719|onahole
smalldom|703|smalldom
lab-coat|688|lab coat
freckles|687|freckles
amputee|686|amputee
doujinshi|686|doujinshi
mesuiki|684|mesuiki
girls-only|679|girls only
compilation|678|compilation
robot|676|robot
gender-morph|670|gender morph
witch|665|witch
dog-girl|661|dog girl
niece|657|niece
unusual-teeth|648|unusual teeth
poor-grammar|646|poor grammar
eggs|645|eggs
fishnets|636|fishnets
orc|635|orc
double-vaginal|627|double vaginal
full-packaged-futanari|625|full-packaged futanari
widow|625|widow
dog|624|dog
double-blowjob|624|double blowjob
big-clit|616|big clit
parasite|613|parasite
large-tattoo|605|large tattoo
long-tongue|604|long tongue
double-anal|599|double anal
machine|599|machine
shimapan|573|shimapan (striped panties)
josou-seme|569|josou seme
domination-loss|565|domination loss
manga|562|manga
multimouth-blowjob|562|multimouth blowjob
dog-boy|561|dog boy
petplay|561|petplay
cheerleader|549|cheerleader
ghost|548|ghost
tutor|545|tutor
prolapse|543|prolapse
slime|542|slime
dickgirls-only|540|dickgirls only
clit-stimulation|539|clit stimulation
eyemask|538|eyemask
unbirth|538|unbirth
vaginal-birth|533|vaginal birth
chinese-dress|523|chinese dress
sunglasses|523|sunglasses
kunoichi|522|kunoichi
shimaidon|521|shimaidon
multiple-paizuri|519|multiple paizuri
human-pet|518|human pet
age-progression|515|age progression
clothed-paizuri|512|clothed paizuri
crying|511|crying
bride|503|bride
corset|500|corset
nose-hook|497|nose hook
mouth-mask|492|mouth mask
armpit-sex|490|armpit sex
ai-generated|486|ai generated
goblin|483|goblin
vampire|483|vampire
waitress|481|waitress
metal-armor|479|metal armor
vomit|475|vomit
wolf-girl|473|wolf girl
first-person-perspective|467|first person perspective
coach|464|coach
tickling|462|tickling
skinsuit|452|skinsuit
sundress|448|sundress
forced-exposure|447|forced exposure
shrinking|446|shrinking
wolf-boy|437|wolf boy
netorase|434|netorase
high-heels|433|high heels
minigirl|431|minigirl
clothed-male-nude-female|430|clothed male nude female
stuck-in-wall|428|stuck in wall
selfcest|425|selfcest
dark-sclera|423|dark sclera
assjob|417|assjob
nipple-piercing|415|nipple piercing
painted-nails|415|painted nails
coprophagia|412|coprophagia
diaper|412|diaper
pubic-stubble|404|pubic stubble
cum-swap|402|cum swap
christmas|398|christmas
saliva|391|saliva
bandages|387|bandages
makeup|378|makeup
all-the-way-through|375|all the way through
shaved-head|371|shaved head
futanarization|369|futanarization
drill-hair|367|drill hair
mmm-threesome|367|mmm threesome
horse|365|horse
fundoshi|364|fundoshi (loincloth)
variant-set|362|variant set
angel|358|angel
insect|356|insect
human-cattle|351|human cattle
dark-nipples|350|dark nipples
father|341|father
closed-eyes|338|closed eyes
oil|333|oil
living-clothes|326|living clothes
bunny-boy|322|bunny boy
wrestling|322|wrestling
time-stop|321|time stop
weight-gain|321|weight gain
multiple-penises|315|multiple penises
big-lips|308|big lips
omorashi|305|omorashi
policewoman|304|policewoman
snake-girl|300|snake girl
dougi|290|dougi
mesugaki|289|mesugaki
ttf-threesome|288|ttf threesome
school-gym-uniform|286|school gym uniform
layer-cake|285|layer cake
out-of-order|282|out of order
slime-girl|275|slime girl
growth|273|growth
futanari-on-male|272|futanari on male
electric-shocks|271|electric shocks
gothic-lolita|271|gothic lolita
watermarked|271|watermarked
focus-blowjob|269|focus blowjob
petrification|265|petrification
futanari-on-futanari|263|futanari on futanari
fairy|259|fairy
gymshorts|257|gymshorts
midget|256|midget
penis-enlargement|255|penis enlargement
underwater|254|underwater
clit-growth|252|clit growth
absorption|248|absorption
clone|246|clone
whip|246|whip
zombie|246|zombie
soushuuhen|237|soushuuhen
heterochromia|234|heterochromia
brain-fuck|233|brain fuck
wormhole|233|wormhole
forniphilia|232|forniphilia
lizard-guy|232|lizard guy
detached-sleeves|230|detached sleeves
sample|230|sample
mtf-threesome|228|mtf threesome
harness|226|harness
shared-senses|224|shared senses
pig|219|pig
necrophilia|215|necrophilia
catfight|211|catfight
dragon|211|dragon
focus-paizuri|211|focus paizuri
smoking|211|smoking
trampling|211|trampling
redraw|210|redraw
gigantic-breasts|209|gigantic breasts
old-lady|209|old lady
fft-threesome|208|fft threesome
mecha-girl|208|mecha girl
bear-boy|206|bear boy
lizard-girl|203|lizard girl
wet-clothes|203|wet clothes
anthology|201|anthology
fox-boy|199|fox boy
cannibalism|198|cannibalism
pig-man|198|pig man
tights|198|tights
big-muscles|195|big muscles
nudity-only|195|nudity only
stirrup-legwear|195|stirrup legwear
autofellatio|188|autofellatio
butler|188|butler
male-on-futanari|188|male on futanari
gang-rape|184|gang rape
vaginal-sticker|179|vaginal sticker
burping|178|burping
low-scat|178|low scat
crown|176|crown
pillory|176|pillory
fff-threesome|175|fff threesome
ssbbw|172|ssbbw
low-bestiality|171|low bestiality
ttm-threesome|170|ttm threesome
c79|168|c79
alien|166|alien
glory-hole|166|glory hole
cowman|161|cowman
syringe|160|syringe
tailjob|160|tailjob
multipanel-sequence|157|multipanel sequence
invisible|156|invisible
nakadashi-creampie|154|nakadashi creampie
bodystocking|151|bodystocking
tube|151|tube
unusual-insertions|150|unusual insertions
"""
}
