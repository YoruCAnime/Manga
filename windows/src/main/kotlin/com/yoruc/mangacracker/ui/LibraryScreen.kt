package com.yoruc.mangacracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.sources.Sources
import com.yoruc.mangacracker.ui.theme.Mc
import com.yoruc.mangacracker.video.FavVideo
import com.yoruc.mangacracker.video.VideoRef
import com.yoruc.mangacracker.video.VideoRepo
import com.yoruc.mangacracker.video.VideoSources
import com.yoruc.mangacracker.video.WatchState
import com.yoruc.mangacracker.video.clock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.rounded.DeleteSweep

private val SORTS = listOf("Last read", "Recently added", "New chapters", "Title")

@Composable
fun LibraryScreen(nav: Nav) {
    val lib by Repo.library.collectAsState()
    val hist by Repo.history.collectAsState()
    val progress by Repo.progress.collectAsState()
    val updates by Repo.updates.collectAsState()
    val settings by Repo.settings.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sourceFilter by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(0) }
    var sortMenu by remember { mutableStateOf(false) }
    /** 0 = manga favourites, 1 = favourite videos, 2 = watched videos, 3 = saved authors / studios / searches */
    var tab by rememberSaveable { mutableStateOf(0) }
    var clearAsk by remember { mutableStateOf(false) }
    val favs by VideoRepo.favs.collectAsState()
    val watch by VideoRepo.watch.collectAsState()

    val list = remember(lib, hist, query, sourceFilter, sort, progress) {
        lib.values.asSequence()
            .filter { sourceFilter.isEmpty() || it.ref.source == sourceFilter }
            .filter { query.isBlank() || it.ref.title.contains(query, true) || it.authors.any { a -> a.contains(query, true) } || it.genres.any { g -> g.equals(query, true) } }
            .let { s ->
                when (sort) {
                    0 -> s.sortedByDescending { hist[it.ref.key]?.time ?: it.added }
                    1 -> s.sortedByDescending { it.added }
                    2 -> s.sortedByDescending { it.lastUpdate }
                    else -> s.sortedBy { it.ref.title.lowercase() }
                }
            }.toList()
    }
    val usedSources = remember(lib) { lib.values.map { it.ref.source }.distinct().mapNotNull { Sources.byId(it) } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (searching) {
                TextField(
                    value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text(if (tab == 0) "Title, author or genre" else "Title or series") },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Mc.Card, unfocusedContainerColor = Mc.Card,
                        focusedIndicatorColor = Mc.Bg, unfocusedIndicatorColor = Mc.Bg,
                    ),
                )
                IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.Rounded.Close, "Close", tint = Mc.Text2) }
            } else {
                Text("Library", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Mc.Text, modifier = Modifier.weight(1f))
                IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Search library", tint = Mc.Text2) }
                if (tab == 0) IconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.Sort, "Sort", tint = Mc.Text2) }
                if (tab == 2 && watch.isNotEmpty()) IconButton(onClick = { clearAsk = !clearAsk }) { Icon(Icons.Rounded.DeleteSweep, "Clear watched", tint = if (clearAsk) Mc.Pink else Mc.Text2) }
                DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }, containerColor = Mc.Card2) {
                    SORTS.forEachIndexed { i, s ->
                        DropdownMenuItem(text = { Text(s, color = if (i == sort) Mc.Blue else Mc.Text) }, onClick = { sort = i; sortMenu = false })
                    }
                }
            }
        }
        // the three shelves
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(Mc.Card).padding(4.dp)) {
            listOf("Manga" to lib.size, "Fav videos" to favs.size, "Watched" to watch.size, "Saved" to settings.saved.size).forEachIndexed { i, (label, n) ->
                val on = tab == i
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) Mc.Blue else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { tab = i }.padding(vertical = 9.dp),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = if (on) Mc.Bg else Mc.Text2, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (n > 0) Text("  $n", color = if (on) Mc.Bg.copy(alpha = 0.7f) else Mc.Text3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (tab == 1) { FavVideos(nav, favs.values.toList(), watch, query); return@Column }
        if (tab == 3) { SavedList(nav, settings.saved, query); return@Column }
        if (tab == 2) {
            // in-window confirm (no pop-up): the sweep button opens this row
            if (clearAsk && watch.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(12.dp)).background(Mc.Card).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Clear watched videos?", color = Mc.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("Removes the list and every resume point. Favourite videos stay.", color = Mc.Text3, fontSize = 12.sp)
                    }
                    Text("Keep", color = Mc.Text2, fontWeight = FontWeight.Bold, fontSize = 13.5.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { clearAsk = false }.padding(horizontal = 10.dp, vertical = 9.dp))
                    Text("Clear", color = Mc.Pink, fontWeight = FontWeight.Bold, fontSize = 13.5.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { VideoRepo.clearWatched(); clearAsk = false; Notes.show("Watched videos cleared") }
                            .padding(horizontal = 10.dp, vertical = 9.dp))
                }
            }
            WatchedVideos(nav, watch, query); return@Column
        }
        if (usedSources.size > 1) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterPill("All", sourceFilter.isEmpty()) { sourceFilter = "" } }
                items(usedSources, key = { it.id }) { s -> FilterPill(s.name, sourceFilter == s.id) { sourceFilter = if (sourceFilter == s.id) "" else s.id } }
            }
        }
        if (list.isEmpty()) {
            EmptyBox(
                if (lib.isEmpty()) "Your library is empty" else "Nothing matches",
                if (lib.isEmpty()) "Find something in Browse and tap the heart." else "Try another word or source."
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(coverWidth(settings.gridColumns)),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.ref.key }) { e ->
                    // the badge counts new chapters found by the update check that are not read yet
                    val unread = updates.count { it.ref.key == e.ref.key && progress["${e.ref.key}#${it.chapter.id}"]?.read != true }
                    val h = hist[e.ref.key]
                    MangaCard(
                        e.ref.copy(subtitle = h?.chapter?.name ?: e.latestChapter),
                        badge = if (unread > 0) "$unread new" else null,
                        progress = h?.state?.percent?.let { it / 100f },
                        showSource = sourceFilter.isEmpty() && usedSources.size > 1,
                        onLongClick = { if (h != null) nav.reader(e.ref, h.chapter) },
                    ) { nav.details(e.ref) }
                }
            }
        }
    }

}

private fun matches(r: VideoRef, q: String) = q.isBlank() || r.title.contains(q, true) || r.series.contains(q, true)

/** Opens a saved author / studio / search again: the source's list for manga, the Videos tab for videos. */
fun openSaved(nav: Nav, s: com.yoruc.mangacracker.core.Saved) {
    val q = com.yoruc.mangacracker.core.SearchQuery(text = s.text, include = s.include, exclude = s.exclude, artist = s.artist)
    if (s.video) {
        val src = VideoSources.byId(s.source) ?: return
        VideosKeep.vm.pick(src)
        VideosKeep.vm.search(q)
        nav.tab("videos")
    } else {
        val src = Sources.byId(s.source) ?: return
        nav.browse(src.id, when { s.group -> src.groupQuery(s.name); s.artist.isNotEmpty() -> src.artistQuery(s.artist); else -> q })
    }
}

/** Library → Saved: the authors, artists, circles, studios and searches you starred, manga first, newest first. */
@Composable
private fun SavedList(nav: Nav, saved: List<com.yoruc.mangacracker.core.Saved>, query: String) {
    val list = remember(saved, query) { saved.filter { query.isBlank() || it.name.contains(query, true) || it.kind.contains(query, true) } }
    if (list.isEmpty()) {
        EmptyBox(
            if (saved.isEmpty()) "Nothing saved yet" else "Nothing matches",
            if (saved.isEmpty()) "Tap ☆ next to an author on a title's page, or ☆ Save on a search (a studio, a tag) in Browse or Videos." else "Try another word.",
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        listOf(false to "Manga", true to "Videos").forEach { (video, head) ->
            val part = list.filter { it.video == video }
            if (part.isEmpty()) return@forEach
            item(key = "head-$head") { SectionHeader("$head · ${part.size}") }
            items(part, key = { it.key }) { s ->
                val site = if (s.video) VideoSources.byId(s.source)?.let { it.name to it.color } else Sources.byId(s.source)?.let { it.name to it.color }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(Mc.Card).clickable { openSaved(nav, s) }
                        .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.kind, color = Mc.Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Mc.Blue.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 3.dp))
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(s.name, color = Mc.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(site?.first ?: s.source, color = site?.let { androidx.compose.ui.graphics.Color(it.second) } ?: Mc.Text3, fontSize = 12.sp, maxLines = 1)
                    }
                    IconButton(onClick = { Repo.toggleSaved(s) }) { Icon(Icons.Rounded.Close, "Remove", tint = Mc.Text3) }
                }
            }
        }
    }
}

/** Library → Favourite videos: newest first, long-press a card to remove it. */
@Composable
private fun FavVideos(nav: Nav, favs: List<FavVideo>, watch: Map<String, WatchState>, query: String) {
    val list = remember(favs, query) { favs.sortedByDescending { it.added }.map { it.ref }.filter { matches(it, query) } }
    if (list.isEmpty()) {
        EmptyBox(if (favs.isEmpty()) "No favourite videos yet" else "Nothing matches", if (favs.isEmpty()) "Tap ♥ in the player, or long-press a video." else "Try another word.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(170.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(list, key = { it.key }) { v -> VideoCard(v, watch[v.key], onSeries = { nav.videoSeries(v) }) { nav.player(v) } }
    }
}

/** Library → Watched: every video you started, newest first, with how far you got. */
@Composable
private fun WatchedVideos(nav: Nav, watch: Map<String, WatchState>, query: String) {
    val list = remember(watch, query) { watch.values.sortedByDescending { it.time }.filter { matches(it.ref, query) } }
    if (list.isEmpty()) {
        EmptyBox(if (watch.isEmpty()) "Nothing watched yet" else "Nothing matches", if (watch.isEmpty()) "Videos you play show up here, ready to resume." else "Try another word.")
        return
    }
    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
        items(list, key = { it.ref.key }) { w ->
            Row(
                Modifier.fillMaxWidth().tabClick { added(com.yoruc.mangacracker.core.Tabs.addVideo(w.ref)) }.clickable { nav.player(w.ref) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(138.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Mc.Card)) {
                    VideoCover(w.ref, Modifier.fillMaxSize())
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(androidx.compose.ui.graphics.Color(0x55000000))) {
                        Box(Modifier.fillMaxWidth(if (w.done) 1f else w.percent / 100f).fillMaxHeight().background(if (w.done) Mc.Green else Mc.Pink))
                    }
                }
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(w.ref.series.ifEmpty { w.ref.title } + "  ›", color = Mc.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.clickable { nav.videoSeries(w.ref) })
                    Text(listOfNotNull(w.ref.episode.ifEmpty { null }?.let { "Episode $it" }, VideoSources.byId(w.ref.source)?.name).joinToString(" · "),
                        color = Mc.Text2, fontSize = 12.5.sp, maxLines = 1)
                    Text((if (w.done) "Watched" else "${w.percent}% · ${clock((w.durationMs - w.positionMs) / 1000)} left") + " · " + timeAgo(w.time),
                        color = if (w.done) Mc.Green else Mc.Text3, fontSize = 12.sp, maxLines = 1)
                }
                IconButton(onClick = { VideoRepo.remove(w.ref.key) }) { Icon(Icons.Rounded.Close, "Remove", tint = Mc.Text3) }
            }
        }
    }
}

@Composable
fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        color = if (selected) Mc.Bg else Mc.Text2,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Mc.Blue else Mc.Card)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)
    )
}
