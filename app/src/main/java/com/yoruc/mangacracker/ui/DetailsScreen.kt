package com.yoruc.mangacracker.ui

import android.content.Intent
import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yoruc.mangacracker.core.Chapter
import com.yoruc.mangacracker.core.DlState
import com.yoruc.mangacracker.core.Downloads
import com.yoruc.mangacracker.core.MangaDetails
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.Source
import com.yoruc.mangacracker.sources.Sources
import com.yoruc.mangacracker.ui.theme.Mc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOn

class DetailsVM(val src: Source, val ref: MangaRef) : ViewModel() {
    var details by mutableStateOf(Repo.detailsCache[ref.key]); private set
    var chapters by mutableStateOf(Repo.chaptersCache[ref.key]); private set
    var error by mutableStateOf<Throwable?>(null); private set
    var loading by mutableStateOf(false); private set
    var related by mutableStateOf<List<MangaRef>>(emptyList()); private set
    /** Set when the site failed but downloaded chapters (or chapters seen earlier) are shown. */
    var offline by mutableStateOf<Throwable?>(null); private set

    init { load() }

    fun load() {
        loading = true; error = null; offline = null
        viewModelScope.launch {
            try {
                val d = withContext(Dispatchers.IO) { src.details(ref) }
                details = d; Repo.detailsCache[ref.key] = d
                Repo.remember(d.ref.copy(tags = (d.genres + d.tagGroups.flatMap { it.tags }).take(60)))
                launch {
                    related = Safety.filter(withContext(Dispatchers.IO) { runCatching { src.related(ref, d) }.getOrDefault(emptyList()) })
                        .filter { it.key != ref.key }
                }
                var last: List<Chapter>? = null
                src.chaptersFlow(ref).flowOn(Dispatchers.IO).collect { c ->
                    last = c
                    chapters = c; Repo.chaptersCache[ref.key] = c
                }
                Repo.refreshEntry(ref, d, last)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // no network / site down: what was saved with the downloads
                val saved = Downloads.savedChapters(ref.key)
                if (chapters.isNullOrEmpty() && saved != null) {
                    chapters = saved
                    if (details == null) details = Downloads.savedDetails(ref.key)
                    offline = e
                } else if (chapters.isNullOrEmpty()) error = e
                else offline = e
            } finally { loading = false }
        }
    }
}

/** Next 5 unread from the reading spot, every unread one, or all (oldest first; downloaded ones left out). */
fun chaptersToDownload(mode: Int, chapters: List<Chapter>, key: String, resume: Chapter?): List<Chapter> {
    val oldestFirst = chapters.asReversed()
    val p = Repo.progress.value
    val unread = oldestFirst.filter { p["$key#${it.id}"]?.read != true && !Downloads.isDownloaded(key, it.id) }
    return when (mode) {
        0 -> {
            val start = resume?.let { r -> oldestFirst.indexOfFirst { it.id == r.id } }?.takeIf { it >= 0 } ?: 0
            unread.filter { oldestFirst.indexOf(it) >= start }.take(5)
        }
        1 -> unread
        else -> oldestFirst.filter { !Downloads.isDownloaded(key, it.id) }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun DetailsScreen(nav: Nav, key: String) {
    val ref = Repo.refs[key] ?: Repo.library.value[key]?.ref ?: Repo.history.value[key]?.ref ?: Downloads.titles.value[key]?.ref ?: return
    val src = Sources.byId(ref.source) ?: return GoneSourcePage(nav, ref)
    val vm: DetailsVM = viewModel(key = "details-$key") { DetailsVM(src, ref) }
    val lib by Repo.library.collectAsState()
    val progress by Repo.progress.collectAsState()
    val history by Repo.history.collectAsState()
    val settings by Repo.settings.collectAsState()
    val dls by Downloads.titles.collectAsState()
    val queue by Downloads.queue.tasks.collectAsState()
    val ctx = LocalContext.current
    var newestFirst by rememberSaveable { mutableStateOf(true) }
    var descOpen by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var dlMenu by remember { mutableStateOf(false) }
    var chapterMenu by remember { mutableStateOf<Chapter?>(null) }

    val d = vm.details
    val shown = d?.ref ?: Repo.refs[key] ?: ref
    val chapters = vm.chapters.orEmpty()
    val inLib = key in lib
    val blocked = d != null && Safety.blocked(d)
    val saved = dls[key]?.saved.orEmpty()
    val queued = remember(queue, key) { queue.filter { it.manga?.key == key }.associateBy { it.chapter?.id } }
    // the site is switched off or failing: offer the same title elsewhere (for titles you keep or have read)
    val noNet = (vm.offline ?: vm.error).let { e -> e is java.net.UnknownHostException || e is java.net.ConnectException || e?.cause is java.net.UnknownHostException }
    val siteDown = (src.id in settings.disabledSources || ((vm.error != null || vm.offline != null) && !noNet)) && (inLib || key in history) && src.id !in NOT_TITLE_SOURCES

    // what the big button does: continue the last chapter, the next one if that was finished, or start at the first
    val last = history[key]
    val resume: Pair<Chapter, String>? = remember(chapters, last, progress) {
        if (chapters.isEmpty()) null
        else if (last != null) {
            val i = chapters.indexOfFirst { it.id == last.chapter.id }
            val st = progress["$key#${last.chapter.id}"]
            when {
                i < 0 -> last.chapter to "Continue · ${last.chapter.name}"
                st?.read == true && i > 0 -> chapters[i - 1] to "Next · ${chapters[i - 1].name}"
                st?.read == true -> chapters[i] to "Read again"
                else -> chapters[i] to "Continue · ${if (src.hasChapters) chapters[i].name + " · " else ""}${st?.percent ?: 0}%"
            }
        } else chapters.last() to (if (src.hasChapters) "Start reading" else "Read")
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        // ---- banner + cover ----
        item {
            Box(Modifier.fillMaxWidth()) {
              Box(Modifier.fillMaxWidth().height(250.dp)) {
                Cover(
                    shown.cover,
                    Modifier.fillMaxSize().graphicsLayer { alpha = 0.55f }
                        .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(18.dp) else Modifier)
                )
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x660B1622), Mc.Bg))))
              }
                Row(Modifier.windowInsetsPadding(WindowInsets.statusBars).fillMaxWidth().padding(4.dp)) {
                    RoundIcon({ nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    RoundIcon({ nav.web(src.webUrl(ref)) }) { Icon(Icons.Rounded.Public, "Open site", tint = Color.White) }
                    RoundIcon({
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, src.webUrl(ref)), "Share"))
                    }) { Icon(Icons.Rounded.Share, "Share", tint = Color.White) }
                    Box {
                        RoundIcon({ menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = Color.White) }
                        DropdownMenu(menu, { menu = false }, containerColor = Mc.Card2) {
                            DropdownMenuItem(text = { Text("Mark all read") }, onClick = { Repo.setRead(ref, chapters, true); menu = false })
                            DropdownMenuItem(text = { Text("Mark all unread") }, onClick = { Repo.setRead(ref, chapters, false); menu = false })
                            DropdownMenuItem(text = { Text("Remove from history") }, onClick = { Repo.removeHistory(key); menu = false })
                        }
                    }
                }
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 140.dp)) {
                Box(Modifier.width(118.dp).height(172.dp).clip(RoundedCornerShape(6.dp)).background(Mc.Card)) {
                    Cover(shown.cover, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f).padding(top = 64.dp)) {
                    Text(shown.title, color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 24.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    val people = (d?.authors.orEmpty() + d?.artists.orEmpty()).distinct()
                    if (people.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            people.take(6).forEach { p ->
                                Text(p, color = Mc.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.clickable { nav.browse(src.id, src.artistQuery(p)) }.padding(vertical = 2.dp))
                            }
                        }
                    }
                    val meta = listOfNotNull(src.name, d?.type?.ifEmpty { null }, d?.status?.ifEmpty { null },
                        d?.pageCount?.takeIf { it > 0 }?.let { "$it pages" }, d?.rating?.ifEmpty { null })
                    Text(meta.joinToString(" · "), color = Mc.Text3, fontSize = 12.5.sp)
                }
            }
            }
        }

        item {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { resume?.let { nav.reader(shown, it.first) } },
                        enabled = resume != null && !blocked,
                        modifier = Modifier.weight(1f).height(46.dp), shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Mc.Blue, contentColor = Mc.Bg)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text(resume?.second ?: if (vm.loading) "Loading…" else "No chapters", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                    }
                    // galleries (one chapter of many pages): open the page grid straight away
                    if (!src.hasChapters && (d?.pageCount ?: 2) > 1 && resume != null && !blocked) {
                        Box(
                            Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(Mc.Card2)
                                .clickable { nav.reader(shown, resume.first, grid = true) },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Rounded.GridView, "Gallery", tint = Mc.Text) }
                    }
                    Box(
                        Modifier.size(46.dp).clip(RoundedCornerShape(8.dp)).background(if (inLib) Mc.Pink else Mc.Card2)
                            .clickable {
                                if (inLib) Repo.removeFromLibrary(key) else Repo.addToLibrary(shown, d, vm.chapters)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(if (inLib) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Library", tint = Color.White)
                    }
                }
                if (siteDown) {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Mc.Orange.copy(alpha = 0.14f)).padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (src.id in settings.disabledSources) "${src.name} is switched off in Settings." else "${src.name} isn't working right now" +
                                (if (vm.offline != null) " (showing what's downloaded)." else "."),
                            color = Mc.Text, fontSize = 13.sp, modifier = Modifier.weight(1f),
                        )
                        Text("Find on other sites", color = Mc.Bg, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(Mc.Orange).clickable { nav.otherSites(key) }
                                .padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }
                if (!blocked) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (src.id !in NOT_TITLE_SOURCES) ActionChip(Icons.Rounded.SwapHoriz, "Other sites") { nav.otherSites(key) }
                        if (chapters.isNotEmpty()) {
                            if (!src.hasChapters) {
                                // a gallery is one chapter: one button
                                val c = chapters.first()
                                val q = queued[c.id]
                                when {
                                    c.id in saved -> ActionChip(Icons.Rounded.DownloadDone, "Downloaded", tint = Mc.Green) { nav.downloads() }
                                    q != null -> ActionChip(Icons.Rounded.Download, if (q.total > 0) "Downloading ${q.done}/${q.total}" else "Queued", tint = Mc.Blue) { nav.downloads() }
                                    else -> ActionChip(Icons.Rounded.Download, "Download") { Notes.show(Downloads.enqueueChapters(ctx, shown, listOf(c), d)) }
                                }
                            } else Box {
                                val busy = queued.values.count { it.state == DlState.QUEUED || it.state == DlState.RUNNING }
                                ActionChip(Icons.Rounded.Download, when {
                                    busy > 0 -> "Downloading · $busy left"
                                    saved.isNotEmpty() -> "Download · ${saved.size} saved"
                                    else -> "Download"
                                }, tint = if (busy > 0) Mc.Blue else Mc.Text) { dlMenu = true }
                                DropdownMenu(dlMenu, { dlMenu = false }, containerColor = Mc.Card2) {
                                    listOf("Next 5 chapters", "All unread", "All chapters").forEachIndexed { i, label ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = {
                                            dlMenu = false
                                            val pick = chaptersToDownload(i, chapters, key, resume?.first)
                                            Notes.show(if (pick.isEmpty()) "Nothing left to download there" else Downloads.enqueueChapters(ctx, shown, pick, d))
                                        })
                                    }
                                    if (saved.isNotEmpty() || queued.isNotEmpty()) DropdownMenuItem(text = { Text("Open Downloads") }, onClick = { dlMenu = false; nav.downloads() })
                                }
                            }
                        }
                    }
                }
                if (vm.offline != null && !siteDown) {
                    Spacer(Modifier.height(10.dp))
                    HintCard("Offline: showing the chapters saved on the phone.")
                }
                if (blocked) {
                    Spacer(Modifier.height(12.dp))
                    val why = d?.let { Safety.switchReason(it) }
                    HintCard(if (why != null) "This title is $why, which you've hidden (Settings › Hide $why)."
                        else "This title has a tag you've hidden (or one MangaCracker always hides), so it can't be opened.")
                }
                d?.description?.takeIf { it.isNotBlank() }?.let { desc ->
                    Spacer(Modifier.height(14.dp))
                    Text(
                        desc, color = Mc.Text2, fontSize = 14.sp, lineHeight = 21.sp,
                        maxLines = if (descOpen) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.animateContentSize().clickable { descOpen = !descOpen }
                    )
                }
                if (!d?.genres.isNullOrEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        d!!.genres.forEach { g -> GenrePill(g) { nav.browse(src.id, src.tagQuery(g)) } }
                    }
                }
                d?.tagGroups?.forEach { grp ->
                    Spacer(Modifier.height(10.dp))
                    Row {
                        Text(grp.name, color = Mc.Text3, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(76.dp).padding(top = 5.dp))
                        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            grp.tags.forEach { t ->
                                Text(
                                    t, color = Mc.Text2, fontSize = 12.5.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Mc.Card).clickable {
                                        val g = grp.name.lowercase()
                                        nav.browse(src.id, if (g == "artist") src.artistQuery(t) else src.tagQuery("$g:$t"))
                                    }.padding(horizontal = 9.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
                d?.extra?.forEach { (k, v) ->
                    Row(Modifier.padding(top = 6.dp)) {
                        Text(k, color = Mc.Text3, fontSize = 12.sp, modifier = Modifier.width(76.dp))
                        Text(v, color = Mc.Text2, fontSize = 12.5.sp)
                    }
                }
            }
        }

        // ---- errors / chapters ----
        if (vm.error != null) {
            item { ErrorBox(vm.error!!, onRetry = { vm.load() }, onOpenWeb = { nav.web(it) }, modifier = Modifier) }
        } else if (vm.chapters == null) {
            item { Loading(Modifier.height(120.dp)) }
        } else if (src.hasChapters && !blocked) {
            item {
                Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${chapters.size} chapters", color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { newestFirst = !newestFirst }) { Icon(Icons.Rounded.SwapVert, "Order", tint = Mc.Text2) }
                }
            }
            val ordered = if (newestFirst) chapters else chapters.asReversed()
            items(ordered, key = { "c" + it.id }) { c ->
                val st = progress["$key#${c.id}"]
                Box(Modifier) {
                    Row(
                        Modifier.fillMaxWidth().combinedClickable(onClick = { nav.reader(shown, c) }, onLongClick = { chapterMenu = c })
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.name, color = if (st?.read == true) Mc.Text3 else Mc.Text, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val sub = listOfNotNull(
                                c.date.takeIf { it > 0 }?.let { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(it)) },
                                c.group.ifEmpty { null },
                                st?.takeIf { !it.read && it.pages > 0 }?.let { "Page ${it.page + 1}/${it.pages}" },
                            )
                            if (sub.isNotEmpty()) Text(sub.joinToString(" · "), color = Mc.Text3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        // downloaded "↓" / in the queue
                        val q = queued[c.id]
                        when {
                            c.id in saved -> Icon(Icons.Rounded.DownloadDone, "Downloaded", tint = Mc.Green, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                            q != null && q.state == DlState.FAILED -> Text("↓ failed", color = Mc.Pink, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                            q != null && q.state == DlState.PAUSED -> Text("↓ paused", color = Mc.Text3, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                            q != null -> Text(if (q.state == DlState.RUNNING && q.total > 0) "↓ ${q.done}/${q.total}" else "↓ waiting", color = Mc.Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp))
                        }
                        when {
                            st?.read == true -> Icon(Icons.Rounded.CheckCircle, "Read", tint = Mc.Text3, modifier = Modifier.size(18.dp))
                            st != null -> Text("${st.percent}%", color = Mc.Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    DropdownMenu(chapterMenu == c, { chapterMenu = null }, containerColor = Mc.Card2) {
                        val q = queued[c.id]
                        when {
                            c.id in saved -> DropdownMenuItem(text = { Text("Delete download") }, onClick = {
                                Downloads.deleteChapter(key, c.id); chapterMenu = null
                            })
                            q != null -> DropdownMenuItem(text = { Text("Cancel download") }, onClick = { Downloads.cancel(q.id); chapterMenu = null })
                            else -> DropdownMenuItem(text = { Text("Download") }, onClick = {
                                Notes.show(Downloads.enqueueChapters(ctx, shown, listOf(c), d)); chapterMenu = null
                            })
                        }
                        DropdownMenuItem(text = { Text(if (st?.read == true) "Mark unread" else "Mark read") }, onClick = {
                            Repo.setRead(ref, listOf(c), st?.read != true); chapterMenu = null
                        })
                        DropdownMenuItem(text = { Text("Mark this and older read") }, onClick = {
                            val i = chapters.indexOf(c); Repo.setRead(ref, chapters.drop(i), true); chapterMenu = null
                        })
                    }
                }
            }
        }
        if (vm.related.isNotEmpty()) {
            item {
                Column(Modifier) {
                    SectionHeader("More like this")
                    CardRow(vm.related, nav, ratio = src.coverRatio.coerceAtLeast(0.66f))
                }
            }
        }
    }
}

/** A small rounded button under the big ones (Other sites, Download). */
@Composable
private fun ActionChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = Mc.Text, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Mc.Card2).clickable(onClick = onClick).padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A title whose site is no longer in the app: its name, cover and the way to the same title elsewhere. */
@Composable
private fun GoneSourcePage(nav: Nav, ref: MangaRef) {
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(16.dp)) {
        RoundIcon({ nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
        Spacer(Modifier.height(12.dp))
        Row {
            Box(Modifier.width(96.dp).height(140.dp).clip(RoundedCornerShape(6.dp)).background(Mc.Card)) { Cover(ref.cover, Modifier.fillMaxSize()) }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(ref.title, color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Text("Its site (${ref.source}) is no longer in MangaCracker.", color = Mc.Text3, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { nav.otherSites(ref.key) }, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Mc.Orange, contentColor = Mc.Bg),
        ) { Icon(Icons.Rounded.SwapHoriz, null); Spacer(Modifier.width(6.dp)); Text("Find on other sites", fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun RoundIcon(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.padding(4.dp).size(40.dp).clip(CircleShape).background(Color(0x55000000)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}
