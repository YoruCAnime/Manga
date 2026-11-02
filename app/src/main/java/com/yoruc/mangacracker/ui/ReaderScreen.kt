package com.yoruc.mangacracker.ui

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Dimension
import com.yoruc.mangacracker.core.Chapter
import com.yoruc.mangacracker.core.ImageSaver
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Page
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Source
import com.yoruc.mangacracker.sources.Sources
import com.yoruc.mangacracker.ui.theme.Mc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOn
import java.util.concurrent.ConcurrentHashMap

/** One row of the endless strip. */
sealed class ReaderItem {
    abstract val key: String
    data class Header(val chapter: Chapter, val first: Boolean) : ReaderItem() { override val key = "h" + chapter.id }
    data class PageItem(val chapter: Chapter, val page: Page, val total: Int) : ReaderItem() { override val key = "p" + chapter.id + "#" + page.index }
    data class Footer(val chapter: Chapter, val next: Chapter?) : ReaderItem() { override val key = "f" + chapter.id }
}

class ReaderVM(val src: Source, val ref: MangaRef, startId: String) : ViewModel() {
    var chapters by mutableStateOf(Repo.chaptersCache[ref.key].orEmpty()); private set
    val items = mutableStateListOf<ReaderItem>()
    var error by mutableStateOf<Throwable?>(null); private set
    var loadingNext by mutableStateOf(false); private set
    /** Index to scroll to once the first chapter is in (with its pixel offset). */
    var startAt by mutableStateOf<Pair<Int, Int>?>(null); private set
    private val loaded = HashSet<String>()
    /** Remembered page shapes so heights do not jump when scrolling back. */
    val ratios = ConcurrentHashMap<String, Float>()
    /** Resolved addresses for sources that look pictures up per page. */
    val resolved = ConcurrentHashMap<String, String>()
    private var startChapterId = startId

    init { start() }

    fun start() {
        error = null
        viewModelScope.launch {
            try {
                var ch = chapters.firstOrNull { it.id == startChapterId }
                if (ch == null) {
                    // fill the list in the background; go as soon as the chapter we want is in it
                    val found = kotlinx.coroutines.CompletableDeferred<Chapter?>()
                    launch {
                        try {
                            src.chaptersFlow(ref).flowOn(Dispatchers.IO).collect { list ->
                                chapters = list; Repo.chaptersCache[ref.key] = list
                                list.firstOrNull { it.id == startChapterId }?.let { found.complete(it) }
                            }
                            found.complete(null)
                        } catch (e: Throwable) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            found.completeExceptionally(e)
                        }
                    }
                    ch = found.await() ?: Repo.lastRead(ref.key)?.chapter?.takeIf { it.id == startChapterId }
                        ?: Repo.updates.value.firstOrNull { it.ref.key == ref.key && it.chapter.id == startChapterId }?.chapter
                        ?: throw IllegalStateException("Chapter not found")
                }
                val pages = withContext(Dispatchers.IO) { src.pages(ref, ch) }
                if (pages.isEmpty()) throw IllegalStateException("This chapter has no pages")
                items.clear(); loaded.clear()
                append(ch, pages, first = true)
                val st = Repo.state(ref.key, ch.id)
                startAt = if (st != null && !st.read && st.page > 0) (1 + st.page.coerceAtMost(pages.size - 1)) to st.offset else 0 to 0
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e
            }
        }
    }

    private fun append(ch: Chapter, pages: List<Page>, first: Boolean) {
        loaded += ch.id
        items.removeAll { it is ReaderItem.Footer }
        items += ReaderItem.Header(ch, first)
        pages.forEach { items += ReaderItem.PageItem(ch, it, pages.size) }
        items += ReaderItem.Footer(ch, nextOf(ch))
    }

    fun nextOf(ch: Chapter): Chapter? {
        val i = chapters.indexOfFirst { it.id == ch.id }
        return if (i > 0) chapters[i - 1] else null
    }

    fun prevOf(ch: Chapter): Chapter? {
        val i = chapters.indexOfFirst { it.id == ch.id }
        return if (i >= 0 && i < chapters.size - 1) chapters[i + 1] else null
    }

    /** Add the next chapter under the current one (endless reading). */
    fun loadNext(after: Chapter) {
        val next = nextOf(after) ?: return
        if (loadingNext || next.id in loaded) return
        loadingNext = true
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { src.pages(ref, next) } }
                .onSuccess { if (it.isNotEmpty() && next.id !in loaded) append(next, it, first = false) }
            loadingNext = false
        }
    }

    suspend fun urlOf(page: Page): String {
        if (page.url.isNotEmpty()) return page.url
        resolved[page.pageUrl]?.let { return it }
        val u = withContext(Dispatchers.IO) { src.imageUrl(page) }
        resolved[page.pageUrl] = u
        return u
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(nav: Nav, key: String, chapterId: String, openGrid: Boolean = false) {
    val ref = Repo.refs[key] ?: Repo.library.value[key]?.ref ?: Repo.history.value[key]?.ref ?: return
    val src = Sources.byId(ref.source) ?: return
    val vm: ReaderVM = viewModel(key = "reader-$key-$chapterId") { ReaderVM(src, ref, chapterId) }
    val settings by Repo.settings.collectAsState()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val screenW = with(density) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    var overlay by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var pageMenu by remember { mutableStateOf<ReaderItem.PageItem?>(null) }
    var savingAll by remember { mutableStateOf<String?>(null) }
    var showGrid by rememberSaveable { mutableStateOf(openGrid) }

    // zoom state (whole strip)
    var scale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var pivot by remember { mutableStateOf(TransformOrigin.Center) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    // ---- full screen + keep awake ----
    DisposableEffect(overlay) {
        val win = (ctx as Activity).window
        val ctl = WindowCompat.getInsetsController(win, view)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (overlay) ctl.show(WindowInsetsCompat.Type.systemBars()) else ctl.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { }
    }
    DisposableEffect(Unit) {
        view.keepScreenOn = settings.keepScreenOn
        onDispose {
            view.keepScreenOn = false
            WindowCompat.getInsetsController((ctx as Activity).window, view).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- jump to the saved spot once ----
    LaunchedEffect(vm.startAt) {
        val s = vm.startAt ?: return@LaunchedEffect
        list.scrollToItem(s.first, s.second)
    }

    // ---- where are we: chapter, page, percent ----
    val current by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val vis = info.visibleItemsInfo
            if (vis.isEmpty() || vm.items.isEmpty()) return@derivedStateOf null
            // the page that holds the upper third of the screen
            val probe = info.viewportStartOffset + (info.viewportEndOffset - info.viewportStartOffset) / 3
            val hit = vis.lastOrNull { it.offset <= probe } ?: vis.first()
            var idx = hit.index
            var it = vm.items.getOrNull(idx)
            if (it is ReaderItem.Header) it = vm.items.getOrNull(idx + 1)
            if (it is ReaderItem.Footer) { idx--; it = vm.items.getOrNull(idx) }
            val p = it as? ReaderItem.PageItem ?: return@derivedStateOf null
            val atEnd = !list.canScrollForward
            val lastOfChapter = vm.items.getOrNull(hit.index + 1).let { n -> n is ReaderItem.Footer } ||
                vis.any { v -> (vm.items.getOrNull(v.index) as? ReaderItem.Footer)?.chapter?.id == p.chapter.id && v.offset < info.viewportEndOffset - 40 }
            val page = if (atEnd || lastOfChapter) p.total - 1 else p.page.index
            Triple(p, page, (vm.items.indexOf(p).let { i -> list.firstVisibleItemScrollOffset.takeIf { list.firstVisibleItemIndex == i } ?: 0 }))
        }
    }

    // save progress while reading (debounced) and pull in the next chapter near the end
    LaunchedEffect(vm) {
        snapshotFlow { current?.let { Triple(it.first.chapter, it.second, it.third) } }
            .distinctUntilChanged()
            .debounce(350)
            .collectLatest { c ->
                if (c == null) return@collectLatest
                val total = (vm.items.firstOrNull { it is ReaderItem.PageItem && it.chapter.id == c.first.id } as? ReaderItem.PageItem)?.total ?: 0
                Repo.saveRead(ref, c.first, c.second, total, c.third)
            }
    }
    LaunchedEffect(vm) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.distinctUntilChanged().collectLatest { last ->
            // preload the next pictures
            val loader = ctx.imageLoader
            for (i in last + 1..last + 4) {
                val p = vm.items.getOrNull(i) as? ReaderItem.PageItem ?: continue
                val u = runCatching { vm.urlOf(p.page) }.getOrNull() ?: continue
                loader.enqueue(ImageRequest.Builder(ctx).data(u).size(pageSize(screenW)).precision(coil.size.Precision.INEXACT).build())
            }
            if (settings.readerAutoNext && last >= vm.items.size - 4) {
                (vm.items.lastOrNull() as? ReaderItem.Footer)?.let { vm.loadNext(it.chapter) }
            }
        }
    }

    BackHandler(enabled = scale > 1f) { scale = 1f; panX = 0f }

    val bg = when (settings.readerBackground) { 1 -> Mc.Bg; 2 -> Color(0xFFF4F1EA); else -> Color.Black }
    val fg = if (settings.readerBackground == 2) Color(0xFF222222) else Mc.Text2

    Box(
        Modifier.fillMaxSize().background(bg).onSizeChanged { boxSize = it }
            // pinch to zoom, pan sideways while zoomed (vertical stays the list's own smooth scroll)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2) {
                            val zoom = event.calculateZoom()
                            val c = event.calculateCentroid()
                            if (scale == 1f && boxSize.width > 0) pivot = TransformOrigin(c.x / boxSize.width, c.y / boxSize.height)
                            scale = (scale * zoom).coerceIn(1f, 4f)
                            panX += event.calculatePan().x
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (scale > 1f) {
                            panX += event.calculatePan().x
                        }
                        val maxX = (scale - 1f) * boxSize.width / 2f + 1f
                        panX = panX.coerceIn(-maxX, maxX)
                        if (scale <= 1.02f) { scale = 1f; panX = 0f }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { overlay = !overlay },
                    onDoubleTap = { o ->
                        if (scale > 1f) { scale = 1f; panX = 0f }
                        else { pivot = TransformOrigin(o.x / boxSize.width, o.y / boxSize.height); scale = 2f }
                    },
                    onLongPress = { o ->
                        val y = o.y
                        val hit = list.layoutInfo.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size }
                        (hit?.let { vm.items.getOrNull(it.index) } as? ReaderItem.PageItem)?.let { pageMenu = it }
                    },
                )
            }
    ) {
        when {
            vm.error != null -> ErrorBox(vm.error!!, onRetry = { vm.start() }, onOpenWeb = { nav.web(it) }, modifier = Modifier.align(Alignment.Center))
            vm.items.isEmpty() -> Loading()
            else -> LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = panX; transformOrigin = pivot
                },
                contentPadding = PaddingValues(horizontal = settings.readerSidePaddingDp.dp),
                verticalArrangement = Arrangement.spacedBy(settings.readerGapDp.dp),
            ) {
                itemsIndexed(vm.items, key = { _, it -> it.key }, contentType = { _, it -> it::class }) { _, item ->
                    when (item) {
                        is ReaderItem.Header -> ChapterHeader(item, fg, vm, nav, ref)
                        is ReaderItem.PageItem -> PageView(item, vm, screenW, fg)
                        is ReaderItem.Footer -> ChapterFooter(item, fg, vm, settings.readerAutoNext)
                    }
                }
            }
        }

        // always-on tiny page counter
        val cur = current
        if (!overlay && cur != null && settings.showReaderPageNumber) {
            val pct = ((cur.second + 1) * 100 / cur.first.total.coerceAtLeast(1))
            Text(
                "${cur.second + 1} / ${cur.first.total} · $pct%",
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 3.dp)
            )
        }

        // ---- overlay ----
        AnimatedVisibility(overlay, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }, modifier = Modifier.align(Alignment.TopCenter)) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xF00B1622), Color(0xC00B1622))))
                    .windowInsetsPadding(WindowInsets.statusBars).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Mc.Text) }
                Column(Modifier.weight(1f)) {
                    Text(ref.title, color = Mc.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(cur?.first?.chapter?.name ?: "", color = Mc.Text3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { showGrid = true; overlay = false }) { Icon(Icons.Rounded.GridView, "All pages", tint = Mc.Text) }
                IconButton(onClick = {
                    val ch = cur?.first?.chapter ?: return@IconButton
                    val pages = vm.items.filterIsInstance<ReaderItem.PageItem>().filter { it.chapter.id == ch.id }
                    scope.launch {
                        var ok = 0
                        pages.forEachIndexed { i, p ->
                            savingAll = "Saving ${i + 1} / ${pages.size}"
                            runCatching { ImageSaver.save(ctx, vm.urlOf(p.page), "${ref.title}/${ch.name}", "%03d".format(p.page.index + 1)) }.onSuccess { ok++ }
                        }
                        savingAll = null
                        Toast.makeText(ctx, "Saved $ok pages to Pictures/MangaCracker", Toast.LENGTH_LONG).show()
                    }
                }) { Icon(Icons.Rounded.Download, "Save chapter", tint = Mc.Text) }
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Rounded.Settings, "Reader settings", tint = Mc.Text) }
            }
        }
        AnimatedVisibility(overlay, enter = fadeIn() + slideInVertically { it }, exit = fadeOut() + slideOutVertically { it }, modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xC00B1622), Color(0xF00B1622))))
                    .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                if (cur != null) {
                    val total = cur.first.total
                    val pct = (cur.second + 1) * 100 / total.coerceAtLeast(1)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.prevOf(cur.first.chapter)?.let { nav.back(); nav.reader(ref, it) } }, enabled = vm.prevOf(cur.first.chapter) != null) {
                            Icon(Icons.Rounded.SkipPrevious, "Previous chapter", tint = Mc.Text)
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Page ${cur.second + 1} of $total  ·  $pct%", color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            if (total > 1) {
                                var drag by remember(cur.first.chapter.id) { mutableStateOf<Float?>(null) }
                                Slider(
                                    value = drag ?: cur.second.toFloat(),
                                    onValueChange = { drag = it },
                                    onValueChangeFinished = {
                                        val target = drag?.toInt() ?: return@Slider
                                        drag = null
                                        val i = vm.items.indexOfFirst { it is ReaderItem.PageItem && it.chapter.id == cur.first.chapter.id && it.page.index == target }
                                        if (i >= 0) scope.launch { list.scrollToItem(i) }
                                    },
                                    valueRange = 0f..(total - 1).toFloat(),
                                    steps = (total - 2).coerceIn(0, 200),
                                    colors = SliderDefaults.colors(thumbColor = Mc.Blue, activeTrackColor = Mc.Blue, inactiveTrackColor = Mc.Card2, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
                                )
                            }
                        }
                        IconButton(onClick = { vm.nextOf(cur.first.chapter)?.let { nav.back(); nav.reader(ref, it) } }, enabled = vm.nextOf(cur.first.chapter) != null) {
                            Icon(Icons.Rounded.SkipNext, "Next chapter", tint = Mc.Text)
                        }
                    }
                }
            }
        }
        savingAll?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(10.dp)).background(Color(0xCC0B1622)).padding(16.dp))
        }

        // ---- every page as a grid: jump to one, or pick several to save ----
        if (showGrid && vm.items.isNotEmpty()) {
            val ch = cur?.first?.chapter ?: (vm.items.firstOrNull { it is ReaderItem.PageItem } as? ReaderItem.PageItem)?.chapter
            val pages = vm.items.filterIsInstance<ReaderItem.PageItem>().filter { it.chapter.id == ch?.id }
            DisposableEffect(Unit) {
                val ctl = WindowCompat.getInsetsController((ctx as Activity).window, view)
                ctl.show(WindowInsetsCompat.Type.systemBars())
                onDispose { if (!overlay) ctl.hide(WindowInsetsCompat.Type.systemBars()) }
            }
            PageGrid(
                title = if (src.hasChapters) "${ref.title} · ${ch?.name.orEmpty()}" else ref.title,
                folder = ref.title,
                pages = pages,
                current = if (cur?.first?.chapter?.id == ch?.id) cur?.second ?: 0 else 0,
                vm = vm,
                onJump = { p ->
                    showGrid = false
                    val i = vm.items.indexOfFirst { it.key == p.key }
                    if (i >= 0) scope.launch { list.scrollToItem(i) }
                },
                onClose = { showGrid = false },
            )
        }
    }

    // ---- long-press page menu ----
    pageMenu?.let { p ->
        ModalBottomSheet(onDismissRequest = { pageMenu = null }, containerColor = Mc.Card) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text("Page ${p.page.index + 1} · ${p.chapter.name}", color = Mc.Text2, fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                SheetAction(Icons.Rounded.Download, "Save image") {
                    pageMenu = null
                    scope.launch {
                        runCatching { ImageSaver.save(ctx, vm.urlOf(p.page), ref.title, "${p.chapter.name} - %03d".format(p.page.index + 1)) }
                            .onSuccess { Toast.makeText(ctx, "Saved to $it", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(ctx, "Couldn't save: ${it.message}", Toast.LENGTH_SHORT).show() }
                    }
                }
                SheetAction(Icons.Rounded.Share, "Share image") {
                    pageMenu = null
                    scope.launch { runCatching { ImageSaver.share(ctx, vm.urlOf(p.page)) }.onFailure { Toast.makeText(ctx, "Couldn't share", Toast.LENGTH_SHORT).show() } }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = Mc.Card) {
            ReaderSettings()
        }
    }
}

/** Decode at most screen-wide and 4096 px tall (the GPU texture limit), never larger than the file itself. */
private fun pageSize(screenW: Int) = coil.size.Size(screenW, 4096)

@Composable
private fun SheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 14.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = Mc.Blue)
        Spacer(Modifier.width(16.dp))
        Text(label, color = Mc.Text, fontSize = 16.sp)
    }
}

@Composable
private fun PageView(item: ReaderItem.PageItem, vm: ReaderVM, screenW: Int, fg: Color) {
    val ctx = LocalContext.current
    val known = item.page.pageUrl.ifEmpty { item.page.url }
    var url by remember(item.key) { mutableStateOf(item.page.url.ifEmpty { vm.resolved[item.page.pageUrl] ?: "" }) }
    var retry by remember(item.key) { mutableStateOf(0) }
    var failed by remember(item.key) { mutableStateOf(false) }
    var ratio by remember(item.key) {
        mutableStateOf(vm.ratios[known] ?: if (item.page.width > 0 && item.page.height > 0) item.page.width.toFloat() / item.page.height else 0f)
    }
    LaunchedEffect(item.key, retry) {
        if (url.isEmpty()) runCatching { url = vm.urlOf(item.page) }.onFailure { failed = true }
    }
    val boxMod = if (ratio > 0f) Modifier.fillMaxWidth().aspectRatio(ratio) else Modifier.fillMaxWidth().height(with(LocalDensity.current) { (screenW * 1.42f).toDp() })
    Box(boxMod, contentAlignment = Alignment.Center) {
        if (url.isNotEmpty() && !failed) {
            var state by remember(item.key, retry) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(url).size(pageSize(screenW)).precision(coil.size.Precision.INEXACT)
                    .setParameter("retry", retry).crossfade(120).build(),
                contentDescription = "Page ${item.page.index + 1}",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
                onState = { s ->
                    state = s
                    if (s is AsyncImagePainter.State.Success) {
                        val d = s.result.drawable
                        if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) {
                            val r = d.intrinsicWidth.toFloat() / d.intrinsicHeight
                            vm.ratios[known] = r
                            ratio = r
                        }
                    }
                    if (s is AsyncImagePainter.State.Error) failed = true
                },
            )
            if (state is AsyncImagePainter.State.Loading || state is AsyncImagePainter.State.Empty) PagePlaceholder(item, fg)
        } else if (failed) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable {
                failed = false; if (item.page.url.isEmpty()) { vm.resolved.remove(item.page.pageUrl); url = "" }; retry++
            }.padding(30.dp)) {
                Text("Page ${item.page.index + 1} didn't load", color = fg)
                Text("Tap to retry", color = Mc.Blue, fontWeight = FontWeight.SemiBold)
            }
        } else PagePlaceholder(item, fg)
    }
}

@Composable
private fun PagePlaceholder(item: ReaderItem.PageItem, fg: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = Mc.Blue.copy(alpha = 0.7f), strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text("${item.page.index + 1}", color = fg.copy(alpha = 0.6f), fontSize = 13.sp)
    }
}

@Composable
private fun ChapterHeader(h: ReaderItem.Header, fg: Color, vm: ReaderVM, nav: Nav, ref: MangaRef) {
    Column(Modifier.fillMaxWidth().padding(top = if (h.first) 64.dp else 36.dp, bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (h.first) vm.prevOf(h.chapter)?.let { p ->
            Text("‹ ${p.name}", color = Mc.Blue, fontSize = 13.sp, modifier = Modifier.clickable { nav.back(); nav.reader(ref, p) }.padding(8.dp))
            Spacer(Modifier.height(10.dp))
        }
        Text(h.chapter.name, color = fg, fontWeight = FontWeight.Bold, fontSize = 17.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 24.dp))
        if (h.chapter.group.isNotEmpty()) Text(h.chapter.group, color = fg.copy(alpha = 0.6f), fontSize = 12.sp)
    }
}

@Composable
private fun ChapterFooter(f: ReaderItem.Footer, fg: Color, vm: ReaderVM, auto: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("End of ${f.chapter.name}", color = fg, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        when {
            f.next == null -> Text("You're all caught up ✦", color = fg.copy(alpha = 0.6f), fontSize = 13.sp)
            vm.loadingNext || auto -> CircularProgressIndicator(color = Mc.Blue, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
            else -> Button(onClick = { vm.loadNext(f.chapter) }, colors = ButtonDefaults.buttonColors(containerColor = Mc.Blue, contentColor = Mc.Bg)) {
                Text("Next: ${f.next.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(if (f.next == null) 120.dp else 40.dp))
    }
}

@Composable
fun ReaderSettings() {
    val s by Repo.settings.collectAsState()
    Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text("Reader", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Mc.Text)
        Spacer(Modifier.height(14.dp))
        Text("Background", color = Mc.Text2, fontSize = 13.sp)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Black", "Navy", "Paper").forEachIndexed { i, l -> FilterPill(l, s.readerBackground == i) { Repo.updateSettings { it.copy(readerBackground = i) } } }
        }
        Spacer(Modifier.height(14.dp))
        Text("Gap between pages · ${s.readerGapDp} dp", color = Mc.Text2, fontSize = 13.sp)
        Slider(s.readerGapDp.toFloat(), { v -> Repo.updateSettings { it.copy(readerGapDp = v.toInt()) } }, valueRange = 0f..24f,
            colors = SliderDefaults.colors(thumbColor = Mc.Blue, activeTrackColor = Mc.Blue, inactiveTrackColor = Mc.Card2))
        Text("Side margin · ${s.readerSidePaddingDp} dp", color = Mc.Text2, fontSize = 13.sp)
        Slider(s.readerSidePaddingDp.toFloat(), { v -> Repo.updateSettings { it.copy(readerSidePaddingDp = v.toInt()) } }, valueRange = 0f..64f,
            colors = SliderDefaults.colors(thumbColor = Mc.Blue, activeTrackColor = Mc.Blue, inactiveTrackColor = Mc.Card2))
        SwitchRow("Flow straight into the next chapter", s.readerAutoNext) { v -> Repo.updateSettings { it.copy(readerAutoNext = v) } }
        SwitchRow("Show page number", s.showReaderPageNumber) { v -> Repo.updateSettings { it.copy(showReaderPageNumber = v) } }
        SwitchRow("Keep the screen on", s.keepScreenOn) { v -> Repo.updateSettings { it.copy(keepScreenOn = v) } }
        Text("Pinch or double-tap to zoom · long-press a page to save it", color = Mc.Text3, fontSize = 12.sp, modifier = Modifier.padding(vertical = 14.dp))
    }
}

@Composable
fun SwitchRow(label: String, value: Boolean, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = Mc.Text, fontSize = 15.sp)
            if (sub != null) Text(sub, color = Mc.Text3, fontSize = 12.sp)
        }
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Mc.Blue, checkedThumbColor = Mc.Bg))
    }
}
