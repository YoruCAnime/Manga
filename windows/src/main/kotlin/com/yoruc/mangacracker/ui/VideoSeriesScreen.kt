package com.yoruc.mangacracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yoruc.mangacracker.ui.theme.Mc
import com.yoruc.mangacracker.video.VideoRef
import com.yoruc.mangacracker.video.VideoRepo
import com.yoruc.mangacracker.video.VideoSafety
import com.yoruc.mangacracker.video.VideoSources
import com.yoruc.mangacracker.video.langName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoSeriesVM(key: String) : ViewModel() {
    val ref: VideoRef? = VideoRepo.refs[key]
    var items by mutableStateOf<List<VideoRef>?>(null); private set
    var error by mutableStateOf<Throwable?>(null); private set

    init { load() }

    fun load() {
        val r = ref ?: return
        val src = VideoSources.byId(r.source) ?: return
        error = null
        viewModelScope.launch {
            try {
                items = withContext(Dispatchers.IO) { src.series(r) }.filter { !VideoSafety.blocked(it) }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e
            }
        }
    }
}

/** Every video of one series: all episodes, every language (Raw / English / Español / previews). */
@Composable
fun VideoSeriesScreen(nav: Nav, key: String) {
    val vm: VideoSeriesVM = viewModel(key = "vseries-$key") { VideoSeriesVM(key) }
    val ref = vm.ref ?: run {
        // the show isn't known any more (an old link after a restore): say so, with a way back
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This show couldn't be opened again. Find it in Videos.", color = Mc.Text)
            Text("Back", color = Mc.Blue, modifier = Modifier.clickable { nav.back() })
        }
        return
    }
    val src = VideoSources.byId(ref.source)
    val watch by VideoRepo.watch.collectAsState()
    var lang by rememberSaveable { mutableStateOf("") }
    val items = vm.items
    val langs = items.orEmpty().map { it.lang }.filter { it.isNotEmpty() }.distinct()
    val shown = items.orEmpty().filter { lang.isEmpty() || it.lang == lang }
    val title = ref.series.ifEmpty { ref.title }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(170.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            // banner: the series' cover under a fade, name and counts on top
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 8f)) {
                VideoCover(items?.firstOrNull { it.cover.isNotEmpty() } ?: ref, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x990B1622), Color(0x330B1622), Mc.Bg))))
                IconButton(onClick = { nav.back() }, modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                }
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 6.dp, vertical = 4.dp)) {
                    Text(title, color = Mc.Text, fontWeight = FontWeight.ExtraBold, fontSize = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(src?.name, items?.let { l -> "${l.mapNotNull { it.episode.ifEmpty { null } }.distinct().size} episodes · ${l.size} videos" }).joinToString(" · "),
                        color = Mc.Text2, fontSize = 13.sp)
                }
            }
        }
        if (langs.size > 1) item(span = { GridItemSpan(maxLineSpan) }) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterPill("All", lang.isEmpty()) { lang = "" } }
                items(langs) { l -> FilterPill(langName(l), lang == l) { lang = if (lang == l) "" else l } }
            }
        }
        when {
            vm.error != null -> item(span = { GridItemSpan(maxLineSpan) }) { ErrorBox(vm.error!!, onRetry = { vm.load() }, onOpenWeb = { nav.web(it) }) }
            items == null -> item(span = { GridItemSpan(maxLineSpan) }) { Loading(Modifier.height(160.dp)) }
            shown.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) { EmptyBox("Nothing here", "This series has no videos in that language yet.") }
            else -> {
                // grouped by episode: a small header, then that episode's languages side by side
                shown.groupBy { it.episode }.forEach { (ep, vids) ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "h$ep") {
                        Text(if (ep.isNotEmpty()) "Episode $ep" else "Extras", color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                    }
                    items(vids, key = { it.key }) { v ->
                        VideoCard(v, watch[v.key], heading = listOfNotNull(langName(v.lang).ifEmpty { null }, v.episode.ifEmpty { null }?.let { "Episode $it" }).joinToString(" · ").ifEmpty { v.title }) { nav.player(v) }
                    }
                }
            }
        }
    }
}
