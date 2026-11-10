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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.sources.Sources
import com.yoruc.mangacracker.ui.theme.Mc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

@Composable
fun SourcesScreen(nav: Nav) {
    val settings by Repo.settings.collectAsState()
    val list = Sources.all.filter { it.id !in settings.disabledSources }
    var shelf by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(16.dp)) {
                Text("Browse", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Mc.Text)
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Mc.Card).clickable { nav.globalSearch() }.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Search, null, tint = Mc.Text3)
                    Spacer(Modifier.width(10.dp))
                    Text("Search every source", color = Mc.Text3)
                }
            }
        }
        // one shelf per kind of site; the pills show just one
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterPill("All", shelf.isEmpty()) { shelf = "" } }
                items(Sources.categories.filter { c -> list.any { Sources.category(it) == c } }) { c -> FilterPill(c, shelf == c) { shelf = if (shelf == c) "" else c } }
            }
        }
        Sources.categories.filter { shelf.isEmpty() || it == shelf }.forEach { c ->
            val of = list.filter { Sources.category(it) == c }
            if (of.isEmpty()) return@forEach
            item(key = "shelf-$c") { SectionHeader("$c · ${of.size}") }
            items(of, key = { it.id }) { s -> SourceRow(s.name, s.blurb, Color(s.color)) { nav.browse(s.id) } }
        }
    }
}

@Composable
private fun SourceRow(name: String, blurb: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(RoundedCornerShape(10.dp)).background(Mc.Card)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), color = color, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Mc.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(blurb, color = Mc.Text3, fontSize = 12.5.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Mc.Text3)
    }
}

/** One search across every enabled source, a row per source. */
@Composable
fun GlobalSearchScreen(nav: Nav, start: String) {
    var text by rememberSaveable { mutableStateOf(start) }
    var submitted by rememberSaveable { mutableStateOf(start) }
    val results = remember { mutableStateMapOf<String, List<MangaRef>>() }
    val errors = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val sources = Sources.enabled()

    LaunchedEffect(submitted) {
        results.clear(); errors.clear()
        if (submitted.isBlank()) return@LaunchedEffect
        sources.forEach { s ->
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { withTimeout(30_000) { s.search(SearchQuery(text = submitted), 1).items } } }
                    .onSuccess { results[s.id] = Safety.filter(it) }
                    .onFailure { errors[s.id] = it.message ?: "failed" }
            }
        }
    }
    LaunchedEffect(Unit) { if (start.isBlank()) runCatching { focus.requestFocus() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(end = 16.dp, top = 4.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Mc.Text2) }
                TextField(
                    text, { text = it }, singleLine = true, placeholder = { Text("Search everywhere") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitted = text.trim() }),
                    modifier = Modifier.weight(1f).heightIn(min = 50.dp).focusRequester(focus), shape = RoundedCornerShape(10.dp),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Mc.Card, unfocusedContainerColor = Mc.Card, focusedIndicatorColor = Mc.Bg, unfocusedIndicatorColor = Mc.Bg),
                )
            }
        }
        if (submitted.isNotBlank()) {
            sources.forEach { s ->
                item(key = s.id) {
                    Column {
                        SectionHeader(s.name, action = "More", onAction = { nav.browse(s.id, SearchQuery(text = submitted)) })
                        val r = results[s.id]
                        when {
                            r != null && r.isEmpty() -> Text("No results", color = Mc.Text3, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
                            r != null -> CardRow(r, nav, ratio = s.coverRatio.coerceAtLeast(0.66f))
                            errors[s.id] != null -> Text(errors[s.id]!!, color = Mc.Text3, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
                            else -> ShimmerRow()
                        }
                    }
                }
            }
        }
    }
}
