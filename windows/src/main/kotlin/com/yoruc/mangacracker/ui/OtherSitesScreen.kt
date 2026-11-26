package com.yoruc.mangacracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yoruc.mangacracker.core.Chapter
import com.yoruc.mangacracker.core.CheckStatus
import com.yoruc.mangacracker.core.Downloads
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.Source
import com.yoruc.mangacracker.core.SourceCheck
import com.yoruc.mangacracker.core.SourceMatch
import com.yoruc.mangacracker.sources.Sources
import com.yoruc.mangacracker.ui.theme.Mc
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** One candidate on another site. [chapters] null = still loading (or a gallery: one chapter). */
data class SiteMatch(
    val src: Source,
    val ref: MangaRef,
    val score: Float,
    val chapters: List<Chapter>? = null,
    val chapterNote: String = "",
)

/** Picture boorus: posts, not titles (no "Other sites" for them or on them). */
val NOT_TITLE_SOURCES = setOf("danbooru", "gelbooru")

enum class SiteState { SEARCHING, DONE, NONE, VPN, BROWSER, BROKEN, SKIPPED }

data class SiteResult(val src: Source, val state: SiteState = SiteState.SEARCHING, val note: String = "", val matches: List<SiteMatch> = emptyList()) {
    val best: Float get() = matches.maxOfOrNull { it.score } ?: 0f
}

/**
 * "Other sites": the title (and its alt titles) searched on every other enabled manga site, candidates ranked by
 * title similarity, each with its chapter count and latest chapter. A site that is down or wants a VPN says so in its
 * own row. Moving carries the library entry, history and read marks over by chapter number (SourceMatch.carry).
 */
class OtherSitesVM(val key: String) : ViewModel() {
    val ref: MangaRef? = Repo.refs[key] ?: Repo.library.value[key]?.ref ?: Repo.history.value[key]?.ref ?: Downloads.titles.value[key]?.ref
    /** The title first, then alt titles (Latin script first: most sites search those best). */
    val names: List<String> = run {
        val d = Repo.detailsCache[key] ?: Downloads.savedDetails(key)
        val alts = d?.altTitles.orEmpty().filter { it.isNotBlank() }
        (listOfNotNull(ref?.title, d?.ref?.title) + alts.sortedBy { a -> if (a.any { it.code > 0x2FF }) 1 else 0 })
            .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { SourceMatch.norm(it) }.take(4)
    }
    val sites = mutableStateMapOf<String, SiteResult>()
    var moving by mutableStateOf<String?>(null); private set

    init { start() }

    fun start() {
        val r = ref ?: return
        // picture boorus have posts, not titles
        val list = Sources.enabled().filter { it.id != r.source && it.id !in NOT_TITLE_SOURCES }
        list.forEach { sites[it.id] = SiteResult(it) }
        val gate = Semaphore(4)
        list.forEach { s ->
            viewModelScope.launch {
                SourceCheck.unavailable[s.id]?.let { why -> sites[s.id] = SiteResult(s, SiteState.SKIPPED, why); return@launch }
                gate.withPermit { search(s) }
            }
        }
    }

    private suspend fun search(s: Source) {
        try {
            val found = LinkedHashMap<String, Pair<MangaRef, Float>>()
            for ((i, q) in names.take(3).withIndex()) {
                // the alt titles only when the main one found nothing close
                if (i > 0 && (found.values.maxOfOrNull { it.second } ?: 0f) >= 0.8f) break
                val items = withContext(Dispatchers.IO) { withTimeout(30_000) { s.search(SearchQuery(text = SourceMatch.core(q).ifBlank { q }), 1).items } }
                Safety.filter(items).forEach { m ->
                    val sc = SourceMatch.best(names, m.title)
                    if (sc > (found[m.key]?.second ?: -1f)) found[m.key] = m to sc
                }
            }
            val top = found.values.filter { it.second >= 0.5f }.sortedByDescending { it.second }.take(3)
            if (top.isEmpty()) { sites[s.id] = SiteResult(s, SiteState.NONE, "No title like this here"); return }
            sites[s.id] = SiteResult(s, SiteState.DONE, matches = top.map { (m, sc) -> SiteMatch(s, m, sc) })
            // chapter counts for the two best (galleries are one chapter)
            for ((m, sc) in top.take(2).filter { it.second >= 0.6f }) {
                val match = if (!s.hasChapters) SiteMatch(s, m, sc, listOf(Chapter("1", "Gallery", 1f)), m.subtitle)
                else try {
                    val ch = withContext(Dispatchers.IO) { withTimeout(30_000) { s.chapters(m) } }
                    Repo.chaptersCache[m.key] = ch
                    SiteMatch(s, m, sc, ch)
                } catch (e: CancellationException) {
                    if (e is TimeoutCancellationException) SiteMatch(s, m, sc, null, "Chapters didn't load") else throw e
                } catch (e: Throwable) { SiteMatch(s, m, sc, null, "Chapters didn't load") }
                sites[s.id] = sites[s.id]!!.let { r -> r.copy(matches = r.matches.map { if (it.ref.key == m.key) match else it }) }
            }
        } catch (e: TimeoutCancellationException) {
            sites[s.id] = SiteResult(s, SiteState.BROKEN, "No answer within 30 s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val (st, why) = SourceCheck.classify(e)
            sites[s.id] = SiteResult(s, when (st) { CheckStatus.VPN -> SiteState.VPN; CheckStatus.BROWSER -> SiteState.BROWSER; else -> SiteState.BROKEN }, why)
        }
    }

    /** Sites in the order shown: best matches first, then the ones still searching, then empty / failing ones. */
    fun ordered(): List<SiteResult> = sites.values.sortedWith(compareBy<SiteResult>({
        when (it.state) { SiteState.DONE -> 0; SiteState.SEARCHING -> 1; SiteState.NONE -> 2; else -> 3 }
    }, { -it.best }, { it.src.name }))

    /** Moves the library entry, history and read marks to [m]; [done] gets the note to show and the undo. */
    fun move(m: SiteMatch, done: (String, Repo.MoveUndo?) -> Unit) {
        if (moving != null) return
        moving = m.ref.key
        viewModelScope.launch {
            try {
                val details = runCatching { withContext(Dispatchers.IO) { m.src.details(m.ref) } }.getOrNull()
                if (details != null && Safety.blocked(details)) { done("That title is hidden (it has a tag you've hidden)", null); return@launch }
                // galleries: the source's own one chapter (its real id)
                val newChapters = m.chapters?.takeIf { m.src.hasChapters && it.isNotEmpty() } ?: withContext(Dispatchers.IO) { m.src.chapters(m.ref) }
                if (newChapters.isEmpty()) { done("Couldn't move: no chapters on ${m.src.name}", null); return@launch }
                val newRef = details?.ref?.let { d -> m.ref.copy(title = d.title.ifEmpty { m.ref.title }, cover = d.cover.ifEmpty { m.ref.cover }) } ?: m.ref
                if (details != null) Repo.detailsCache[newRef.key] = details
                Repo.chaptersCache[newRef.key] = newChapters
                val oldChapters = Repo.chaptersCache[key] ?: Downloads.savedChapters(key) ?: emptyList()
                val oldProgress = Repo.progress.value.filterKeys { it.startsWith("$key#") }.mapKeys { it.key.removePrefix("$key#") }
                val carry = SourceMatch.carry(oldChapters, oldProgress, Repo.history.value[key], newRef, newChapters)
                val undo = Repo.moveTitle(key, newRef, details, newChapters, carry)
                val more = listOfNotNull(
                    carry.read.takeIf { it > 0 }?.let { "$it chapter${if (it == 1) "" else "s"} marked read" },
                    carry.unmatched.takeIf { it > 0 }?.let { "$it not on ${m.src.name}" },
                )
                done("Moved to ${m.src.name}" + (if (more.isNotEmpty()) " · " + more.joinToString(" · ") else ""), undo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                done("Couldn't move: ${SourceCheck.classify(e).second}", null)
            } finally { moving = null }
        }
    }
}

@Composable
fun OtherSitesScreen(nav: Nav, key: String) {
    val vm: OtherSitesVM = viewModel(key = "other-$key") { OtherSitesVM(key) }
    val ref = vm.ref
    val lib by Repo.library.collectAsState()
    val hist by Repo.history.collectAsState()
    // a move only means something for a title you keep or have read
    val canMove = key in lib || key in hist
    val oldSrc = ref?.let { Sources.byId(it.source) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Other sites", nav)
        if (ref == null) { EmptyBox("Nothing to look for", "Open a title first."); return@Column }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(ref.title, color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "Now on ${oldSrc?.name ?: ref.source}" + (if (vm.names.size > 1) " · also searched as ${vm.names.drop(1).joinToString(", ") { "\"$it\"" }}" else ""),
                        color = Mc.Text3, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (canMove) Text(
                        "Move here = your library entry, history and read chapters go to that site (chapter numbers are matched). You can undo it.",
                        color = Mc.Text3, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            vm.ordered().forEach { r ->
                item(key = "s" + r.src.id) { SiteBlock(r, vm, nav, canMove) }
            }
        }
    }
}

@Composable
private fun SiteBlock(r: SiteResult, vm: OtherSitesVM, nav: Nav, canMove: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).clip(RoundedCornerShape(10.dp)).background(Mc.Card).padding(vertical = 8.dp)) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(r.src.color)))
            Spacer(Modifier.width(8.dp))
            Text(r.src.name, color = Mc.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, modifier = Modifier.weight(1f))
            val (label, color) = when (r.state) {
                SiteState.SEARCHING -> "Searching…" to Mc.Text3
                SiteState.DONE -> "${r.matches.size} found" to Mc.Green
                SiteState.NONE -> "Not found" to Mc.Text3
                SiteState.VPN -> "Needs VPN" to Mc.Orange
                SiteState.BROWSER -> "Browser check" to Mc.Orange
                SiteState.BROKEN -> "Not working" to Mc.Pink
                SiteState.SKIPPED -> "Skipped" to Mc.Text3
            }
            if (r.state == SiteState.SEARCHING) CircularProgressIndicator(Modifier.size(14.dp).padding(end = 2.dp), color = Mc.Blue, strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        if (r.note.isNotEmpty() && r.state != SiteState.DONE) {
            Text(r.note, color = Mc.Text3, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
            if (r.state == SiteState.BROWSER) Text("Open site", color = Mc.Blue, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp).clip(RoundedCornerShape(6.dp)).clickable { nav.web(r.src.baseUrl) }.padding(6.dp))
        }
        r.matches.forEach { m -> MatchRow(m, vm, nav, canMove) }
    }
}

@Composable
private fun MatchRow(m: SiteMatch, vm: OtherSitesVM, nav: Nav, canMove: Boolean) {
    Row(
        Modifier.fillMaxWidth().clickable { nav.details(m.ref) }.padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(48.dp).height(68.dp).clip(RoundedCornerShape(5.dp)).background(Mc.Card2)) { Cover(m.ref.cover, Modifier.fillMaxSize()) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(m.ref.title, color = Mc.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val ch = m.chapters
            val info = when {
                !m.src.hasChapters -> listOfNotNull("Gallery", m.ref.subtitle.ifEmpty { null })
                ch == null && m.chapterNote.isNotEmpty() -> listOf(m.chapterNote)
                ch == null -> listOf("Chapters…")
                ch.isEmpty() -> listOf("No chapters")
                else -> listOf("${ch.size} chapter${if (ch.size == 1) "" else "s"}", "Latest: " + (ch.maxByOrNull { SourceMatch.number(it) ?: -1f } ?: ch.first()).name)
            }
            Text(info.joinToString(" · "), color = Mc.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${(m.score * 100).toInt()}% match", color = if (m.score >= 0.85f) Mc.Green else if (m.score >= 0.65f) Mc.Blue else Mc.Text3, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
        }
        if (canMove) {
            val busy = vm.moving == m.ref.key
            Text(
                if (busy) "Moving…" else "Move here", color = Mc.Bg, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(if (busy) Mc.BlueSoft else Mc.Blue)
                    .clickable(enabled = vm.moving == null) {
                        vm.move(m) { note, undo ->
                            if (undo == null) { Notes.show(note); return@move }
                            // off this page and the old title's page, onto the new title's
                            nav.c.popBackStack()
                            if (nav.c.currentBackStackEntry?.destination?.route == "details/{key}") nav.c.popBackStack()
                            nav.details(Repo.refs[undo.newKey] ?: m.ref)
                            val oldRef = vm.ref
                            Notes.show(note, "Undo") {
                                Repo.undoMove(undo)
                                // still on the new title's page: back to the old one
                                val here = nav.c.currentBackStackEntry
                                if (oldRef != null && here?.destination?.route == "details/{key}" && here.arguments?.getString("key") == undo.newKey) {
                                    nav.c.popBackStack(); nav.details(oldRef)
                                }
                                Notes.show("Move undone")
                            }
                        }
                    }.padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
}

/** A plain top bar: back + title (+ actions), like the More pages. */
@Composable
fun TopBar(title: String, nav: Nav, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Mc.Text2) }
        Text(title, color = Mc.Text, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, modifier = Modifier.weight(1f))
        actions()
    }
}
