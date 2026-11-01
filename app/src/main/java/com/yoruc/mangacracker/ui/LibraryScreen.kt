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
                    value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Title, author or genre") },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Mc.Card, unfocusedContainerColor = Mc.Card,
                        focusedIndicatorColor = Mc.Bg, unfocusedIndicatorColor = Mc.Bg,
                    ),
                )
                IconButton(onClick = { searching = false; query = "" }) { Icon(Icons.Rounded.Close, "Close", tint = Mc.Text2) }
            } else {
                Text("Library", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Mc.Text, modifier = Modifier.weight(1f))
                Text("${lib.size}", color = Mc.Text3, modifier = Modifier.padding(end = 4.dp))
                IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Search library", tint = Mc.Text2) }
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.Sort, "Sort", tint = Mc.Text2) }
                DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }, containerColor = Mc.Card2) {
                    SORTS.forEachIndexed { i, s ->
                        DropdownMenuItem(text = { Text(s, color = if (i == sort) Mc.Blue else Mc.Text) }, onClick = { sort = i; sortMenu = false })
                    }
                }
            }
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
                columns = GridCells.Fixed(settings.gridColumns.coerceIn(2, 5)),
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

@Composable
fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        color = if (selected) Mc.Bg else Mc.Text2,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Mc.Blue else Mc.Card)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp)
    )
}
