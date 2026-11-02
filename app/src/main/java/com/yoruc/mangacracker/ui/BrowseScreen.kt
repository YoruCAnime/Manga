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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Public
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yoruc.mangacracker.core.ReadMarks
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.Source
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.sources.Sources
import com.yoruc.mangacracker.ui.theme.Mc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BrowseVM(val src: Source, start: SearchQuery?) : ViewModel() {
    /** 0 = popular, 1 = latest, 2 = search */
    var mode by mutableStateOf(if (start != null) 2 else 0); private set
    var query by mutableStateOf(start ?: SearchQuery()); private set
    var allTags by mutableStateOf<List<Tag>>(emptyList()); private set

    val paged = Paged(viewModelScope) { page ->
        when (mode) {
            0 -> src.popular(page)
            1 -> src.latest(page)
            else -> src.search(query, page)
        }
    }

    init {
        paged.reset()
        viewModelScope.launch { allTags = Safety.cleanTags(withContext(Dispatchers.IO) { runCatching { src.tags() }.getOrDefault(emptyList()) }) }
    }

    fun switchMode(m: Int) { if (m != mode) { mode = m; paged.reset() } }

    fun search(q: SearchQuery) {
        query = q
        mode = if (q.isEmpty && q.selects.isEmpty()) 0 else 2
        paged.reset()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(nav: Nav, sourceId: String, startSearch: Boolean) {
    val src = Sources.byId(sourceId) ?: return
    val start = remember { if (startSearch) Pending.query.also { Pending.query = null } else null }
    val vm: BrowseVM = viewModel(key = "browse-$sourceId-${start?.hashCode()}") { BrowseVM(src, start) }
    val lib by Repo.library.collectAsState()
    val settings by Repo.settings.collectAsState()
    var text by remember { mutableStateOf(vm.query.text.ifEmpty { vm.query.artist }) }
    var showFilters by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    // autocomplete for the word being typed (ignore it by typing on)
    var focused by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<Tag>>(emptyList()) }
    val word = text.substringAfterLast(' ')
    LaunchedEffect(word, focused) {
        if (!focused || word.trim().length < 2) { suggestions = emptyList(); return@LaunchedEffect }
        delay(220)
        val got = withContext(Dispatchers.IO) { runCatching { src.suggestTags(word) }.getOrDefault(emptyList()) }
        suggestions = rankTags(word, Safety.cleanTags(got).filter { it.group != "typed" })
            .distinctBy { it.label.lowercase() }
            .filter { s -> vm.query.include.none { it.id == s.id } }.take(8)
    }
    fun pick(t: Tag) {
        val rest = text.dropLast(word.length).trimEnd()
        text = rest
        suggestions = emptyList()
        vm.search(vm.query.copy(text = rest, include = (vm.query.include + t).distinctBy { it.id }, exclude = vm.query.exclude.filter { it.id != t.id }))
    }

    // Settings › "Hide titles I've finished"
    val marks by ReadMarks.state.collectAsState()
    val hide = settings.hideFinished
    val shown by remember(hide, marks) { derivedStateOf { if (hide) ReadMarks.dropFinished(vm.paged.items.toList(), marks) { it.key } else vm.paged.items } }

    // load more as the end comes near (also when a whole page was hidden)
    LaunchedEffect(grid) {
        snapshotFlow { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to vm.paged.items.size }.collectLatest { (last, _) ->
            if (last >= shown.size - 9) vm.paged.more()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Mc.Text2) }
            TextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                placeholder = { Text("Search ${src.name}") },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Mc.Text3) },
                trailingIcon = { if (text.isNotEmpty()) IconButton(onClick = { text = ""; vm.search(vm.query.copy(text = "", artist = "")) }) { Icon(Icons.Rounded.Close, "Clear", tint = Mc.Text3) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { suggestions = emptyList(); vm.search(vm.query.copy(text = text, artist = if (text == vm.query.artist) text else "")) }),
                modifier = Modifier.weight(1f).heightIn(min = 50.dp).onFocusChanged { focused = it.isFocused }, shape = RoundedCornerShape(10.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Mc.Card, unfocusedContainerColor = Mc.Card,
                    focusedIndicatorColor = Mc.Bg, unfocusedIndicatorColor = Mc.Bg,
                ),
            )
            val active = vm.query.include.size + vm.query.exclude.size + vm.query.selects.count { (k, v) -> src.selects.firstOrNull { it.key == k }?.options?.firstOrNull()?.first != v }
            Box {
                IconButton(onClick = { showFilters = true }) { Icon(Icons.Rounded.Tune, "Filters", tint = if (active > 0) Mc.Blue else Mc.Text2) }
                if (active > 0) Text(
                    active.toString(), color = Mc.Bg, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).clip(CircleShape).background(Mc.Blue).padding(horizontal = 5.dp)
                )
            }
            IconButton(onClick = { nav.web(src.baseUrl) }) { Icon(Icons.Rounded.Public, "Open site", tint = Mc.Text2) }
        }
        if (suggestions.isNotEmpty()) TagSuggestions(suggestions, ::pick)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterPill("Popular", vm.mode == 0) { vm.switchMode(0) } }
            item { FilterPill("Latest", vm.mode == 1) { vm.switchMode(1) } }
            if (vm.mode == 2) item { FilterPill("Results", true) {} }
            if (vm.query.artist.isNotEmpty()) item { FilterPill("✎ ${vm.query.artist}", true) { vm.search(vm.query.copy(artist = "")); text = "" } }
            items(vm.query.include, key = { "i" + it.id }) { t -> FilterPill("✓ ${t.label}", true) { vm.search(vm.query.copy(include = vm.query.include - t)) } }
            items(vm.query.exclude, key = { "e" + it.id }) { t -> FilterPill("✕ ${t.label}", false) { vm.search(vm.query.copy(exclude = vm.query.exclude - t)) } }
        }
        val cols = if (src.coverRatio > 0.9f) settings.gridColumns.coerceIn(2, 5) + 0 else settings.gridColumns.coerceIn(2, 5)
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Fixed(cols),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(shown, key = { _, r -> r.key }) { _, r ->
                MangaCard(r, ratio = src.coverRatio, inLibrary = r.key in lib) { nav.details(r) }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                when {
                    vm.paged.error != null -> ErrorBox(vm.paged.error!!, onRetry = { vm.paged.more() }, onOpenWeb = { nav.web(it) })
                    vm.paged.loading -> Loading(Modifier.height(120.dp))
                    vm.paged.items.isEmpty() -> EmptyBox("Nothing found", "Try other words or fewer filters.")
                    shown.isEmpty() -> EmptyBox("All finished", "You've read everything here. Titles you finished are hidden (Settings).")
                    !vm.paged.hasNext -> Text("· end ·", color = Mc.Text3, modifier = Modifier.fillMaxWidth().padding(16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }

    if (showFilters) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showFilters = false }, sheetState = sheet, containerColor = Mc.Card) {
            FilterSheet(src, vm.query.copy(text = text), vm.allTags) { q ->
                showFilters = false
                vm.search(q)
            }
        }
    }
}

/** Sort / type selects and tri-state tags (tap: include ✓ → exclude ✕ → off). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(src: Source, start: SearchQuery, allTags: List<Tag>, onApply: (SearchQuery) -> Unit) {
    val selects = remember { mutableStateMapOf<String, String>().apply { putAll(start.selects) } }
    val include = remember { mutableStateListOf<Tag>().apply { addAll(start.include) } }
    val exclude = remember { mutableStateListOf<Tag>().apply { addAll(start.exclude) } }
    var find by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<Tag>>(emptyList()) }
    var artist by remember { mutableStateOf(start.artist) }

    LaunchedEffect(find) {
        if (find.isBlank()) { suggestions = emptyList(); return@LaunchedEffect }
        delay(250)
        suggestions = rankTags(find, Safety.cleanTags(withContext(Dispatchers.IO) { runCatching { src.suggestTags(find) }.getOrDefault(emptyList()) }))
    }
    val shown by remember(allTags, suggestions, find) {
        derivedStateOf { if (find.isNotBlank()) suggestions else allTags }
    }

    fun cycle(t: Tag) {
        when {
            include.any { it.id == t.id } -> { include.removeAll { it.id == t.id }; if (src.canExclude) exclude.add(t) }
            exclude.any { it.id == t.id } -> exclude.removeAll { it.id == t.id }
            else -> include.add(t)
        }
    }

    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 640.dp).navigationBarsPadding(), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 4.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filters", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Mc.Text, modifier = Modifier.weight(1f))
                Text("Reset", color = Mc.Text3, modifier = Modifier.clickable { selects.clear(); include.clear(); exclude.clear(); artist = "" }.padding(8.dp))
            }
        }
        src.selects.forEach { def ->
            item(key = "sel-" + def.key) {
                Column(Modifier.padding(top = 12.dp)) {
                    Text(def.label, color = Mc.Text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val cur = selects[def.key] ?: def.options.first().first
                        def.options.forEach { (id, label) -> FilterPill(label, cur == id) { selects[def.key] = id } }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(top = 14.dp)) {
                Text("Author / artist", color = Mc.Text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                TextField(
                    artist, { artist = it }, singleLine = true, placeholder = { Text("Name") }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Mc.Card2, unfocusedContainerColor = Mc.Card2, focusedIndicatorColor = Mc.Card, unfocusedIndicatorColor = Mc.Card),
                )
            }
        }
        item {
            Column(Modifier.padding(top = 14.dp)) {
                Text(
                    if (src.canExclude) "Genres & tags · tap once to include, twice to exclude" else "Genres & tags",
                    color = Mc.Text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                TextField(
                    find, { find = it }, singleLine = true, placeholder = { Text("Find a tag…") }, modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Mc.Text3) },
                    shape = RoundedCornerShape(8.dp),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Mc.Card2, unfocusedContainerColor = Mc.Card2, focusedIndicatorColor = Mc.Card, unfocusedIndicatorColor = Mc.Card),
                )
                if (include.isNotEmpty() || exclude.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        include.forEach { t -> TagChip(t.label, 1) { cycle(t) } }
                        exclude.forEach { t -> TagChip(t.label, -1) { cycle(t) } }
                    }
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.take(if (find.isBlank()) 400 else 60).forEach { t ->
                        val st = when { include.any { it.id == t.id } -> 1; exclude.any { it.id == t.id } -> -1; else -> 0 }
                        TagChip(t.label, st) { cycle(t) }
                    }
                }
                if (shown.isEmpty() && find.isBlank()) Text("Type to find tags", color = Mc.Text3, fontSize = 13.sp)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { onApply(SearchQuery()) }, modifier = Modifier.weight(1f)) { Text("Clear all") }
                Button(
                    onClick = { onApply(SearchQuery(start.text, include.toList(), exclude.toList(), selects.toMap(), artist.trim())) },
                    modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Mc.Blue)
                ) { Text("Apply") }
            }
        }
    }
}

/** Words that start with what was typed first, then the most used; otherwise the site's own order. */
fun rankTags(typed: String, tags: List<Tag>): List<Tag> {
    val q = typed.trim().replace(' ', '_')
    fun starts(t: Tag) = t.label.replace(' ', '_').startsWith(q, true) || t.id.substringAfter(':').startsWith(q, true)
    return tags.withIndex().sortedWith(compareBy({ !starts(it.value) }, { -it.value.count }, { it.index })).map { it.value }
}

fun shortCount(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0).replace(".0M", "M")
    n >= 1_000 -> "%.1fk".format(n / 1_000.0).replace(".0k", "k")
    n > 0 -> n.toString()
    else -> ""
}

/** The autocomplete list under a search box: tag, its kind, how often it's used. */
@Composable
fun TagSuggestions(tags: List<Tag>, onPick: (Tag) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Mc.Card)) {
        tags.forEach { t ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(t) }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = Mc.Text3, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(10.dp))
                Text(t.label, color = Mc.Text, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val kind = t.group.takeIf { it.isNotBlank() && !it.equals("tag", true) && !it.equals("genre", true) }
                if (kind != null) {
                    val c = when (kind.lowercase()) { "artist" -> Mc.Pink; "character" -> Mc.Green; "series", "parody" -> Mc.Blue; else -> Mc.Text3 }
                    Text(kind.replaceFirstChar { it.uppercase() }, color = c, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(5.dp)).background(c.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
                shortCount(t.count).takeIf { it.isNotEmpty() }?.let { Text(it, color = Mc.Text3, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp)) }
            }
        }
    }
}

@Composable
fun TagChip(label: String, state: Int, onClick: () -> Unit) {
    val (bg, fg, mark) = when (state) {
        1 -> Triple(Mc.Blue, Mc.Bg, "✓ ")
        -1 -> Triple(Mc.Pink, androidx.compose.ui.graphics.Color.White, "✕ ")
        else -> Triple(Mc.Card2, Mc.Text2, "")
    }
    Text(
        mark + label, color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp)
    )
}
