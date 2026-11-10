package com.yoruc.mangacracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yoruc.mangacracker.core.HistoryEntry
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.ReadMarks
import com.yoruc.mangacracker.core.Recommender
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.Source
import com.yoruc.mangacracker.core.UpdateItem
import com.yoruc.mangacracker.core.UpdateWorker
import com.yoruc.mangacracker.sources.Sources
import com.yoruc.mangacracker.ui.theme.Mc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeVM : ViewModel() {
    var recs by mutableStateOf(Recommender.cached()); private set
    var recsLoading by mutableStateOf(false); private set
    var refreshing by mutableStateOf(false); private set
    /** source id -> popular titles for the discovery rows */
    val rows = androidx.compose.runtime.mutableStateMapOf<String, List<MangaRef>>()
    val rowErrors = androidx.compose.runtime.mutableStateMapOf<String, Boolean>()

    init {
        loadRecs(false)
        // a gentle check for new chapters when the app opens (at most every 2 hours)
        if (System.currentTimeMillis() - Repo.settings.value.lastLibraryCheck > 2 * 3600_000L && Repo.library.value.isNotEmpty()) {
            viewModelScope.launch { runCatching { UpdateWorker.checkLibrary() } }
        }
    }

    fun loadRecs(force: Boolean) {
        recsLoading = true
        viewModelScope.launch {
            recs = runCatching { Recommender.load(force) }.getOrDefault(recs)
            recsLoading = false
        }
    }

    private val asked = HashSet<String>()

    /** A source's row loads when it first scrolls into view (not every site at once when the app opens). */
    fun showRow(src: Source) { if (asked.add(src.id)) loadRow(src) }

    fun loadRow(src: Source) {
        asked.add(src.id)
        rowErrors.remove(src.id)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { src.popular(1).items } }
            r.onSuccess { rows[src.id] = Safety.filter(it).take(20) }.onFailure { rowErrors[src.id] = true }
        }
    }

    fun refresh() {
        refreshing = true
        viewModelScope.launch {
            runCatching { UpdateWorker.checkLibrary() }
            loadRecs(true)
            // the rows already on the page; the others load fresh when they come into view
            val seen = asked.toSet()
            rows.keys.filter { it !in seen }.forEach { rows.remove(it) }
            Sources.enabled().filter { it.id in seen }.forEach { loadRow(it) }
            refreshing = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(nav: Nav) {
    val vm: HomeVM = viewModel { HomeVM() }
    val history by Repo.history.collectAsState()
    val updates by Repo.updates.collectAsState()
    val library by Repo.library.collectAsState()
    val settings by Repo.settings.collectAsState()
    val continueList = remember(history) { history.values.sortedByDescending { it.time }.take(15) }

    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Mc.Card, Mc.Bg)))
                        .windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("MangaCracker", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Mc.Text)
                        Text(greeting(), color = Mc.Text3, fontSize = 13.sp)
                    }
                    // new chapters, recommendations and the rows below, again
                    if (vm.refreshing) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator(color = Mc.Blue, strokeWidth = 2.5.dp, modifier = Modifier.size(20.dp))
                    } else IconButton(onClick = { vm.refresh() }) { Icon(Icons.Rounded.Refresh, "Refresh", tint = Mc.Text2) }
                    IconButton(onClick = { nav.globalSearch() }) { Icon(Icons.Rounded.Search, "Search everywhere", tint = Mc.Text2) }
                }
            }

            // ---- Continue reading ----
            if (continueList.isNotEmpty()) {
                item { SectionHeader("Continue reading", action = "History", onAction = { nav.history() }) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(continueList, key = { it.ref.key }) { h -> ContinueCard(h, onChapters = { nav.details(h.ref) }) { nav.reader(h.ref, h.chapter) } }
                    }
                }
            }

            // ---- Updates for you ----
            item { SectionHeader("Updates for you", action = if (updates.isNotEmpty()) "See all" else null, onAction = { nav.tab("updates") }) }
            if (updates.isEmpty()) {
                item {
                    HintCard(
                        if (library.isEmpty()) "Tap ♥ on a title to add it to your library. New chapters show up here."
                        else "No new chapters yet. Pull down to check now."
                    )
                }
            } else {
                items(updates.take(6), key = { "u" + it.ref.key + it.chapter.id }) { u ->
                    UpdateRow(u, onCover = { nav.details(u.ref) }) { nav.reader(u.ref, u.chapter) }
                }
            }

            // ---- Recommended ----
            item { SectionHeader("Recommended for you", action = "Refresh", onAction = { vm.loadRecs(true) }) }
            item {
                if (vm.recs.isEmpty() && vm.recsLoading) ShimmerRow()
                else if (vm.recs.isEmpty()) HintCard("Read or favourite a few titles and recommendations will follow your taste.")
                else CardRow(vm.recs, nav, showSource = true)
            }

            // ---- Popular per source ----
            Sources.enabled().forEach { src ->
                item(key = "row-" + src.id) {
                    Column {
                        SectionHeader("Popular on ${src.name}", action = "More", onAction = { nav.browse(src.id) })
                        val list = vm.rows[src.id]
                        androidx.compose.runtime.LaunchedEffect(src.id) { vm.showRow(src) }
                        when {
                            list != null -> CardRow(list, nav, ratio = src.coverRatio.coerceAtLeast(0.66f))
                            vm.rowErrors[src.id] == true -> Text(
                                "Couldn't load ${src.name} · tap to retry", color = Mc.Text3, fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).clickable { vm.loadRow(src) }
                            )
                            else -> ShimmerRow()
                        }
                    }
                }
            }
            if (settings.disabledSources.isNotEmpty()) item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

private fun greeting(): String {
    val h = java.time.LocalTime.now().hour
    return when (h) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..22 -> "Good evening"; else -> "Late-night reading?" } + " ✦"
}

@Composable
fun CardRow(list: List<MangaRef>, nav: Nav, ratio: Float = 0.70f, showSource: Boolean = false) {
    val lib by Repo.library.collectAsState()
    val settings by Repo.settings.collectAsState()
    val marks by ReadMarks.state.collectAsState()
    // Settings › "Hide titles I've finished" (discovery rows only: Library and History use their own lists)
    val shown = remember(list, marks, settings.hideFinished) { if (settings.hideFinished) ReadMarks.dropFinished(list, marks) { it.key } else list }
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(shown, key = { it.key }) { r ->
            MangaCard(r, Modifier.width(112.dp), ratio = ratio, inLibrary = r.key in lib, showSource = showSource) { nav.details(r) }
        }
    }
}

@Composable
fun ShimmerRow() {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), userScrollEnabled = false) {
        items(6) { Shimmer(Modifier.width(112.dp).height(160.dp).clip(CardShape)) }
    }
}

@Composable
fun HintCard(text: String) {
    Text(
        text, color = Mc.Text3, fontSize = 13.sp,
        modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Mc.Card).padding(14.dp)
    )
}

@Composable
private fun ContinueCard(h: HistoryEntry, onChapters: () -> Unit, onClick: () -> Unit) {
    val pct = h.state.percent
    Row(
        Modifier.width(290.dp).height(104.dp).clip(RoundedCornerShape(8.dp)).background(Mc.Card).tabClick { added(com.yoruc.mangacracker.core.Tabs.addChapter(h.ref, h.chapter)) }.clickable(onClick = onClick)
    ) {
        // the cover and the list button open the title page (every chapter); the rest of the card reads on
        Box(Modifier.width(72.dp).fillMaxSize().clickable(onClick = onChapters)) { Cover(h.ref.cover, Modifier.fillMaxSize()) }
        Column(Modifier.weight(1f).padding(start = 12.dp, top = 10.dp, end = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(h.ref.title, color = Mc.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                    Text(h.chapter.name, color = Mc.Text3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (Sources.byId(h.ref.source)?.hasChapters != false) Box(
                    Modifier.padding(start = 6.dp).size(30.dp).clip(RoundedCornerShape(7.dp)).background(Mc.Card2).clickable(onClick = onChapters), contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, "All chapters", tint = Mc.Blue, modifier = Modifier.size(18.dp)) }
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (h.state.pages > 0) "Page ${h.state.page + 1} / ${h.state.pages}" else "Started",
                        color = Mc.Text2, fontSize = 11.5.sp, modifier = Modifier.weight(1f)
                    )
                    Text("$pct%", color = Mc.Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = { pct / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = Mc.Blue, trackColor = Mc.Card2, drawStopIndicator = {},
                )
            }
        }
    }
}

@Composable
fun UpdateRow(u: UpdateItem, onCover: () -> Unit, onClick: () -> Unit) {
    val st = Repo.state(u.ref.key, u.chapter.id)
    val read = st?.read == true
    Row(
        Modifier.fillMaxWidth().tabClick { added(com.yoruc.mangacracker.core.Tabs.addChapter(u.ref, u.chapter)) }.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = 46.dp, height = 64.dp).clip(RoundedCornerShape(5.dp)).clickable(onClick = onCover)) {
            Cover(u.ref.cover, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(u.ref.title, color = if (read) Mc.Text3 else Mc.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(u.chapter.name, color = if (read) Mc.Text3 else Mc.Text2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(Sources.byId(u.ref.source)?.name, timeAgo(u.chapter.date.takeIf { it > 0 } ?: u.found).ifEmpty { null }).joinToString(" · "),
                color = Mc.Text3, fontSize = 11.5.sp
            )
        }
        if (!read) {
            Text(
                if (st != null) "${st.percent}%" else "NEW", color = if (st != null) Mc.Blue else Mc.Pink, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background((if (st != null) Mc.Blue else Mc.Pink).copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
    }
}
