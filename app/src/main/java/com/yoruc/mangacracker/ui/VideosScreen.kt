package com.yoruc.mangacracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.ui.theme.Mc
import com.yoruc.mangacracker.video.VideoPage
import com.yoruc.mangacracker.video.VideoRef
import com.yoruc.mangacracker.video.VideoRepo
import com.yoruc.mangacracker.video.VideoSafety
import com.yoruc.mangacracker.video.VideoSource
import com.yoruc.mangacracker.video.VideoSources
import com.yoruc.mangacracker.video.WatchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Endless video list; drops repeats and anything the minors filter blocks. */
class VideoPaged(private val scope: CoroutineScope, private val load: suspend (page: Int) -> VideoPage) {
    val items = mutableStateListOf<VideoRef>()
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<Throwable?>(null); private set
    var hasNext by mutableStateOf(true); private set
    private var page = 1
    private val seen = HashSet<String>()
    private var job: Job? = null

    fun reset() {
        job?.cancel()
        items.clear(); seen.clear(); page = 1; hasNext = true; error = null; loading = false
        more()
    }

    fun more() {
        if (loading || !hasNext) return
        loading = true; error = null
        job = scope.launch {
            try {
                var tries = 0
                while (true) {
                    val r = withContext(Dispatchers.IO) { load(page) }
                    page++
                    val fresh = r.items.filter { !VideoSafety.blocked(it) && seen.add(it.key) }
                    items.addAll(fresh)
                    hasNext = r.hasNext && r.items.isNotEmpty()
                    if (fresh.isNotEmpty() || !hasNext || ++tries >= 3) break
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e
            } finally {
                loading = false
            }
        }
    }
}

class VideosVM : ViewModel() {
    var source by mutableStateOf(VideoSources.byId(Repo.settings.value.lastVideoSource) ?: VideoSources.all.first()); private set
    /** 0 = latest, 1 = popular, 2 = search */
    var mode by mutableStateOf(0); private set
    var query by mutableStateOf(SearchQuery()); private set
    var genres by mutableStateOf<List<Tag>>(emptyList()); private set

    val paged = VideoPaged(viewModelScope) { page ->
        when (mode) {
            0 -> source.latest(page)
            1 -> source.popular(page)
            else -> source.search(query, page)
        }
    }

    init { paged.reset(); loadGenres() }

    private fun loadGenres() {
        val s = source
        viewModelScope.launch { genres = runCatching { s.genres() }.getOrDefault(emptyList()).filter { !VideoSafety.badTag(it.label) } }
    }

    fun pick(s: VideoSource) {
        if (s.id == source.id) return
        source = s
        Repo.updateSettings { it.copy(lastVideoSource = s.id) }
        query = SearchQuery(); mode = 0
        loadGenres(); paged.reset()
    }

    fun switchMode(m: Int) { if (m != mode) { mode = m; paged.reset() } }

    fun search(q: SearchQuery) {
        query = q
        mode = if (q.isEmpty) 0 else 2
        paged.reset()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VideosScreen(nav: Nav) {
    val vm: VideosVM = viewModel()
    val watch by VideoRepo.watch.collectAsState()
    val cont = remember(watch) { VideoRepo.continueList() }
    var text by remember { mutableStateOf(vm.query.text) }
    var focused by remember { mutableStateOf(false) }
    var showGenres by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()

    val word = text.substringAfterLast(' ')
    val suggestions = remember(word, vm.genres, focused, vm.query) {
        if (!focused || word.trim().length < 2) emptyList()
        else rankTags(word, vm.genres.filter { g -> g.label.contains(word.trim(), true) && vm.query.include.none { it.id == g.id } }).take(6)
    }

    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collectLatest { last ->
            if (last >= vm.paged.items.size - 6) vm.paged.more()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Videos", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Mc.Text, modifier = Modifier.weight(1f))
            IconButton(onClick = { nav.web(vm.source.baseUrl) }) { Icon(Icons.Rounded.PlayArrow, "Open site", tint = Mc.Text3) }
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { Text("Search ${vm.source.name}") },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Mc.Text3) },
                trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = ""; vm.search(vm.query.copy(text = "")) }) { Icon(Icons.Rounded.Close, "Clear", tint = Mc.Text3) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(vm.query.copy(text = text.trim())) }),
                modifier = Modifier.weight(1f).heightIn(min = 50.dp).onFocusChanged { focused = it.isFocused },
                shape = RoundedCornerShape(10.dp),
                colors = TextFieldDefaults.colors(focusedContainerColor = Mc.Card, unfocusedContainerColor = Mc.Card, focusedIndicatorColor = Mc.Bg, unfocusedIndicatorColor = Mc.Bg),
            )
            val active = vm.query.include.size + vm.query.exclude.size
            Box {
                IconButton(onClick = { showGenres = true }) { Icon(Icons.Rounded.Tune, "Genres", tint = if (active > 0) Mc.Blue else Mc.Text2) }
                if (active > 0) Text(active.toString(), color = Mc.Bg, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(CircleShape).background(Mc.Blue).padding(horizontal = 5.dp))
            }
        }
        if (suggestions.isNotEmpty()) TagSuggestions(suggestions) { t ->
            val rest = text.dropLast(word.length).trimEnd()
            text = rest
            vm.search(vm.query.copy(text = rest, include = (vm.query.include + t).distinctBy { it.id }))
        }
        // sources
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(VideoSources.all, key = { it.id }) { s ->
                val on = s.id == vm.source.id
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(if (on) Color(s.color).copy(alpha = 0.22f) else Mc.Card)
                        .clickable { vm.pick(s); text = "" }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(s.color)))
                    Spacer(Modifier.width(8.dp))
                    Text(s.name, color = if (on) Mc.Text else Mc.Text2, fontSize = 14.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterPill("Latest", vm.mode == 0) { vm.switchMode(0) } }
            item { FilterPill("Popular", vm.mode == 1) { vm.switchMode(1) } }
            if (vm.mode == 2) item { FilterPill("Results", true) {} }
            items(vm.query.include, key = { "i" + it.id }) { t -> FilterPill("✓ ${t.label}", true) { vm.search(vm.query.copy(include = vm.query.include - t)) } }
            items(vm.query.exclude, key = { "e" + it.id }) { t -> FilterPill("✕ ${t.label}", false) { vm.search(vm.query.copy(exclude = vm.query.exclude - t)) } }
        }
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(170.dp),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (cont.isNotEmpty() && vm.mode != 2) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text("Continue watching", color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(cont, key = { it.ref.key }) { w -> ContinueCard(w) { nav.player(w.ref) } }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(if (vm.mode == 1) "Popular on ${vm.source.name}" else "New on ${vm.source.name}", color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                }
            }
            items(vm.paged.items, key = { it.key }) { v -> VideoCard(v, watch[v.key]) { nav.player(v) } }
            item(span = { GridItemSpan(maxLineSpan) }) {
                when {
                    vm.paged.error != null -> ErrorBox(vm.paged.error!!, onRetry = { vm.paged.more() }, onOpenWeb = { nav.web(it) })
                    vm.paged.loading -> Loading(Modifier.height(120.dp))
                    vm.paged.items.isEmpty() -> EmptyBox("Nothing found", "Try other words or fewer genres.")
                    !vm.paged.hasNext -> Text("· end ·", color = Mc.Text3, modifier = Modifier.fillMaxWidth().padding(16.dp), textAlign = TextAlign.Center)
                }
            }
        }
    }

    if (showGenres) {
        ModalBottomSheet(onDismissRequest = { showGenres = false }, containerColor = Mc.Card) {
            val inc = remember { mutableStateListOf<Tag>().apply { addAll(vm.query.include) } }
            val exc = remember { mutableStateListOf<Tag>().apply { addAll(vm.query.exclude) } }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp).navigationBarsPadding(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp)) {
                item {
                    Text("Genres", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Mc.Text)
                    Text("Tap once to include, twice to exclude", color = Mc.Text3, fontSize = 12.5.sp)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.genres.forEach { g ->
                            val st = when { inc.any { it.id == g.id } -> 1; exc.any { it.id == g.id } -> -1; else -> 0 }
                            TagChip(g.label, st) {
                                when (st) {
                                    1 -> { inc.removeAll { it.id == g.id }; exc.add(g) }
                                    -1 -> exc.removeAll { it.id == g.id }
                                    else -> inc.add(g)
                                }
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { showGenres = false; vm.search(vm.query.copy(include = emptyList(), exclude = emptyList())) }, modifier = Modifier.weight(1f)) { Text("Clear") }
                        Button(onClick = { showGenres = false; vm.search(vm.query.copy(include = inc.toList(), exclude = exc.toList())) },
                            modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Mc.Blue)) { Text("Apply") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier, bg: Color = Color(0xCC000000)) {
    Text(text, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(6.dp).clip(RoundedCornerShape(5.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp))
}

@Composable
fun VideoCard(v: VideoRef, w: WatchState?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Mc.Card)) {
            VideoCover(v, Modifier.fillMaxSize())
            if (v.lang.isNotEmpty()) Badge(v.lang, Modifier.align(Alignment.TopStart), if (v.lang == "EN") Mc.Blue.copy(alpha = 0.9f) else Color(0xCC000000))
            if (v.duration.isNotEmpty()) Badge(v.duration, Modifier.align(Alignment.BottomEnd))
            if (w != null && w.percent > 0) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x55000000))) {
                    Box(Modifier.fillMaxWidth(w.percent / 100f).fillMaxHeight().background(Mc.Pink))
                }
            }
        }
        Text(v.series.ifEmpty { v.title }, color = Mc.Text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, top = 6.dp, end = 2.dp))
        val sub = listOfNotNull(v.episode.ifEmpty { null }?.let { "Episode $it" }, v.views.ifEmpty { null }?.let { "$it views" }).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, color = Mc.Text3, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(horizontal = 2.dp))
    }
}

@Composable
private fun ContinueCard(w: WatchState, onClick: () -> Unit) {
    Column(Modifier.width(220.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Mc.Card)) {
            VideoCover(w.ref, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.Center).size(42.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White)
            }
            Badge(com.yoruc.mangacracker.video.clock((w.durationMs - w.positionMs) / 1000) + " left", Modifier.align(Alignment.BottomEnd))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x55000000))) {
                Box(Modifier.fillMaxWidth(w.percent / 100f).fillMaxHeight().background(Mc.Pink))
            }
        }
        Text(w.ref.series.ifEmpty { w.ref.title }, color = Mc.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 2.dp, top = 6.dp))
        Text(listOfNotNull(w.ref.episode.ifEmpty { null }?.let { "Episode $it" }, VideoSources.byId(w.ref.source)?.name).joinToString(" · "),
            color = Mc.Text3, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 2.dp))
    }
}
