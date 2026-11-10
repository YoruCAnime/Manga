package com.yoruc.mangacracker.ui

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.yoruc.mangacracker.core.AppUpdate
import com.yoruc.mangacracker.core.Chapter
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.ui.theme.Mc

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Rounded.Home),
    Tab("library", "Library", Icons.Rounded.CollectionsBookmark),
    Tab("sources", "Browse", Icons.Rounded.Explore),
    Tab("videos", "Videos", Icons.Rounded.SmartDisplay),
    Tab("music", "Music", Icons.Rounded.LibraryMusic),
    Tab("updates", "Updates", Icons.Rounded.NewReleases),
    Tab("more", "More", Icons.Rounded.MoreHoriz),
)

/** Everything screens need to move around. */
class Nav(val c: NavHostController) {
    fun back() { c.popBackStack() }
    fun details(ref: MangaRef) {
        Repo.remember(ref)
        c.navigate("details/${Uri.encode(ref.key)}")
    }
    fun reader(ref: MangaRef, chapter: Chapter, grid: Boolean = false) {
        Repo.remember(ref)
        c.navigate("reader/${Uri.encode(ref.key)}/${Uri.encode(chapter.id)}?grid=${if (grid) 1 else 0}") { launchSingleTop = true }
    }
    fun browse(sourceId: String, query: SearchQuery? = null) {
        Pending.query = query
        c.navigate("browse/$sourceId?search=${if (query != null) 1 else 0}")
    }
    fun globalSearch(text: String = "") = c.navigate("search?q=${Uri.encode(text)}")
    fun web(url: String) = c.navigate("web?url=${Uri.encode(url)}")
    fun player(v: com.yoruc.mangacracker.video.VideoRef) {
        // a whole show (anime lists) opens its episodes
        if (v.show) return videoSeries(v)
        com.yoruc.mangacracker.video.VideoRepo.remember(v)
        c.navigate("player/${Uri.encode(v.key)}") { launchSingleTop = true }
    }
    fun videoSeries(v: com.yoruc.mangacracker.video.VideoRef) {
        com.yoruc.mangacracker.video.VideoRepo.remember(v)
        c.navigate("vseries/${Uri.encode(v.key)}") { launchSingleTop = true }
    }
    fun otherSites(key: String) = c.navigate("othersites/${Uri.encode(key)}") { launchSingleTop = true }
    fun downloads() = c.navigate("downloads") { launchSingleTop = true }
    fun history() = c.navigate("history")
    fun gallery() = c.navigate("gallery")
    fun stats() = c.navigate("stats")
    fun settings() = c.navigate("settings")
    fun sourceTest() = c.navigate("sourcetest")
    fun exLogin() = c.navigate("exlogin") { launchSingleTop = true }
    /** Shows a tab: everything above Home goes (only the tab on screen is loaded); it picks up from its saved place. */
    fun openTab(t: com.yoruc.mangacracker.core.OpenTab) {
        val tabs = com.yoruc.mangacracker.core.Tabs
        val route = tabs.latestRoute(t)
        if (tabs.active.value == t.id && tabs.here.value == route) return
        t.manga?.let { Repo.remember(it) }
        t.video?.let { com.yoruc.mangacracker.video.VideoRepo.remember(it) }
        tabs.activate(t.id)
        val start = c.graph.findStartDestination().id
        // a main screen keeps its scroll for coming back; a title page is let go
        val keep = tabs.here.value == null
        val m = t.manga
        if (m != null && route.startsWith("reader/")) {
            // Back from the reader goes to the title's page, as it always does
            c.navigate(tabs.detailsRoute(m.key)) { popUpTo(start) { saveState = keep } }
            c.navigate(route)
        } else c.navigate(route) { popUpTo(start) { saveState = keep } }
    }
    fun closeTab(t: com.yoruc.mangacracker.core.OpenTab) {
        val tabs = com.yoruc.mangacracker.core.Tabs
        val showing = tabs.active.value == t.id
        val next = tabs.close(t.id)
        if (next != null) openTab(next) else if (showing) home()
    }
    fun closeAllTabs() {
        val tabs = com.yoruc.mangacracker.core.Tabs
        val showing = tabs.active.value != 0L
        tabs.clear()
        if (showing) home()
    }
    fun home() { c.popBackStack("home", false) }
    fun tab(route: String) = c.navigate(route) {
        popUpTo(c.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** A search handed from one screen to the browse screen it opens. */
object Pending { var query: SearchQuery? = null }

/** Where the app is now (for the update switch): the screen's route pattern, its key argument, the last input time. */
object Here {
    @Volatile var route = "home"
    @Volatile var key = ""
    @Volatile var lastInput = System.currentTimeMillis()
}

@Composable
fun AppRoot(open: String?) {
    val c = rememberNavController()
    val nav = Nav(c)
    val entry by c.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "home"
    val updates by Repo.updates.collectAsState()
    val showBar = tabs.any { it.route == route }
    val settings by Repo.settings.collectAsState()
    LaunchedEffect(entry) {
        val a = entry?.arguments
        com.yoruc.mangacracker.core.Tabs.noteScreen(entry?.destination?.route, a?.getString("key").orEmpty(), a?.getString("chapter").orEmpty(), a?.getString("grid") == "1")
    }

    LaunchedEffect(open) {
        if (open == "updates") nav.tab("updates")
        if (open == "downloads") { nav.tab("more"); nav.downloads() }
        // test runs (MC_OPEN): "demo:browse:<source>", "demo:read:<source>" = first popular title's first chapter
        if (open != null && open.startsWith("demo:")) {
            val (what, srcId) = open.removePrefix("demo:").split(':').let { it[0] to it.getOrElse(1) { "atsu" } }
            kotlinx.coroutines.delay(1500)
            if (what == "play" || what == "series") {
                val vs = com.yoruc.mangacracker.video.VideoSources.byId(srcId) ?: return@LaunchedEffect
                val v = runCatching { (if (what == "series") vs.popular(1) else vs.latest(1)).items.first() }.getOrNull() ?: return@LaunchedEffect
                if (what == "series") nav.videoSeries(v) else nav.player(v)
                return@LaunchedEffect
            }
            // test runs: MC_OPEN=demo:music:<search> opens the Music tab with that search; MC_MUSIC_PLAY=<anime>|radio plays it
            // and notes what plays (cache\music_test.txt): after 12 s, then Next and 8 s more
            if (what == "music") {
                musicStartQuery = srcId
                musicOpenFirst = System.getenv("MC_MUSIC_OPEN") == "1"
                nav.tab("music")
                System.getenv("MC_MUSIC_PLAY")?.let { q ->
                    val m = com.yoruc.mangacracker.music.Music
                    val log = java.io.File(com.yoruc.mangacracker.platform.Desktop.ctx.cacheDir, "music_test.txt").apply { writeText("") }
                    fun note(s: String) = log.appendText(s + System.lineSeparator())
                    val list = if (q == "radio") listOf(m.radioTrack()) else m.allSongs(m.searchAnime(q).first())
                    note("queue ${list.size}: " + list.joinToString(" | ") { it.kind + " " + it.title })
                    m.player.play(list)
                    kotlinx.coroutines.delay(12_000)
                    note("after 12 s: ${m.player.now.value?.title} playing=${m.player.playing.value} at ${m.player.position.value} of ${m.player.duration.value} ms; radio now ${m.radioNow.value?.title}")
                    if (q != "radio") { m.player.next(); kotlinx.coroutines.delay(8_000); note("after Next + 8 s: ${m.player.now.value?.title} at ${m.player.position.value} of ${m.player.duration.value} ms") }
                    m.player.seek(60_000); kotlinx.coroutines.delay(3_000); note("after seek to 60 s: at ${m.player.position.value} ms")
                    if (q != "radio") {
                        // loop this song: near its end it starts again
                        m.player.setRepeat(com.yoruc.mangacracker.music.REPEAT_ONE)
                        val one = m.player.now.value?.title
                        m.player.seek(m.player.duration.value - 3_000); kotlinx.coroutines.delay(7_000)
                        note("loop this song: was $one near the end, now ${m.player.now.value?.title} at ${m.player.position.value} ms")
                        // loop the list: from the last song back to the first
                        m.player.setRepeat(com.yoruc.mangacracker.music.REPEAT_ALL)
                        while (m.player.index.value != list.size - 1) { m.player.next(); kotlinx.coroutines.delay(1_500) }
                        kotlinx.coroutines.delay(3_000)
                        note("loop the list: last is ${m.player.now.value?.title}")
                        m.player.seek(m.player.duration.value - 3_000); kotlinx.coroutines.delay(7_000)
                        note("after the last: ${m.player.now.value?.title} (index ${m.player.index.value}) at ${m.player.position.value} ms")
                    }
                }
                return@LaunchedEffect
            }
            // test runs: MC_OPEN=demo:tabs:<video source> opens tabs, moves between them and notes the places (cache: tabs_test.txt)
            if (what == "tabs") {
                val tabs = com.yoruc.mangacracker.core.Tabs
                val log = java.io.File(com.yoruc.mangacracker.platform.Desktop.ctx.cacheDir, "tabs_test.txt").apply { writeText("") }
                fun note(s: String) = log.appendText(s + System.lineSeparator())
                val io = kotlinx.coroutines.Dispatchers.IO
                val atsu = com.yoruc.mangacracker.sources.Sources.byId("atsu") ?: return@LaunchedEffect
                val vs = com.yoruc.mangacracker.video.VideoSources.byId(srcId) ?: return@LaunchedEffect
                val found = kotlinx.coroutines.withContext(io) { atsu.popular(1).items }
                val vid = kotlinx.coroutines.withContext(io) { vs.latest(1).items.first().let { if (it.show) vs.series(it).first() else it } }
                val ch = kotlinx.coroutines.withContext(io) { atsu.chapters(found[1]).last() }
                val a = tabs.addManga(found[0])!!; val b = tabs.addChapter(found[1], ch)!!; val v = tabs.addVideo(vid)!!
                note("added ${tabs.list.value.size}: " + tabs.list.value.joinToString(" | ") { it.route.substringBefore('/') })
                fun state() = "active ${tabs.active.value} here ${tabs.here.value?.substringBefore('/')} tabs ${tabs.list.value.size}"
                fun at() = com.yoruc.mangacracker.video.VideoRepo.state(vid.key)?.positionMs
                kotlinx.coroutines.delay(3000); nav.openTab(a); kotlinx.coroutines.delay(6000); note("tab 1: " + state())
                nav.openTab(b); kotlinx.coroutines.delay(9000); note("tab 2: " + state() + " chapter state " + Repo.state(found[1].key, ch.id))
                nav.openTab(v); kotlinx.coroutines.delay(25000); note("video: " + state() + " saved " + at())
                nav.openTab(a); kotlinx.coroutines.delay(2500)
                note("left the video: " + state() + " saved at " + at())
                nav.openTab(v); kotlinx.coroutines.delay(12000); note("back on the video: " + state() + " saved " + at())
                nav.closeTab(v); kotlinx.coroutines.delay(5000); note("closed the video, next shown: " + state())
                nav.closeTab(b); kotlinx.coroutines.delay(1500); nav.home(); kotlinx.coroutines.delay(1500); note("home: " + state())
                return@LaunchedEffect
            }
            if (what == "gallery" || what == "stats") {
                if (what == "gallery") galleryStartItem = srcId.toIntOrNull()
                nav.tab("more"); if (what == "gallery") nav.gallery() else nav.stats()
                return@LaunchedEffect
            }
            if (what == "settings") {
                settingsStartItem = srcId.toIntOrNull() ?: 0
                nav.settings()
                return@LaunchedEffect
            }
            if (what == "videos") {
                com.yoruc.mangacracker.core.Repo.updateSettings { it.copy(lastVideoSource = srcId) }
                nav.tab("videos")
                return@LaunchedEffect
            }
            val src = com.yoruc.mangacracker.sources.Sources.byId(srcId) ?: return@LaunchedEffect
            when (what) {
                "browse" -> nav.browse(srcId)
                "details", "read" -> {
                    val ref = runCatching { src.popular(1).items.first() }.getOrNull() ?: return@LaunchedEffect
                    if (what == "details") nav.details(ref)
                    else {
                        val ch = runCatching { src.chapters(ref).last() }.getOrNull() ?: return@LaunchedEffect
                        nav.reader(ref, ch)
                    }
                }
                else -> nav.tab(what)
            }
        }
    }

    LaunchedEffect(Unit) { com.yoruc.mangacracker.platform.Back.fallback = { if (c.previousBackStackEntry != null) nav.back() } }

    SideEffect { Here.route = route; Here.key = entry?.arguments?.getString("key") ?: ""; TabKeys.nav = nav }
    // just switched to a newer copy: back on the same tab (and the same title / show page)
    LaunchedEffect(Unit) {
        val h = com.yoruc.mangacracker.platform.SelfUpdate.handoff ?: return@LaunchedEffect
        val tab = tabs.firstOrNull { it.route == h.route }?.route
        when {
            tab != null -> nav.tab(tab)
            h.route == "details/{key}" && h.key.isNotEmpty() -> c.navigate("details/${Uri.encode(h.key)}")
            h.route == "vseries/{key}" && h.key.isNotEmpty() -> { nav.tab("videos"); c.navigate("vseries/${Uri.encode(h.key)}") }
            h.route in setOf("history", "settings", "downloads", "gallery", "stats") -> { nav.tab("more"); c.navigate(h.route) }
            h.route.startsWith("browse/") -> nav.tab("sources")
        }
    }

    TabMenuHost()
    // Windows: the main tabs sit in a rail on the left (hidden in the reader / player, like the phone's bar)
    Row(Modifier.fillMaxSize().background(Mc.Bg)) {
        if (showBar) {
            NavigationRail(containerColor = Mc.Card, modifier = Modifier.fillMaxHeight()) {
                Spacer(Modifier.height(10.dp))
                tabs.forEach { t ->
                    NavigationRailItem(
                        selected = route == t.route,
                        onClick = { nav.tab(t.route) },
                        icon = {
                            if (t.route == "updates" && updates.isNotEmpty()) {
                                BadgedBox(badge = { Badge(containerColor = Mc.Pink) { Text(if (updates.size > 99) "99+" else updates.size.toString()) } }) {
                                    Icon(t.icon, t.label)
                                }
                            } else Icon(t.icon, t.label)
                        },
                        label = { Text(t.label) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = Mc.Blue, selectedTextColor = Mc.Blue,
                            indicatorColor = Mc.Blue.copy(alpha = 0.12f),
                            unselectedIconColor = Mc.Text3, unselectedTextColor = Mc.Text3,
                        ),
                    )
                }
            }
        }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f).fillMaxHeight()) {
        if (!TabKeys.fullscreen) TabStrip(nav)
        Box(Modifier.weight(1f).fillMaxSize()) {
            NavHost(
                c, startDestination = "home",
                enterTransition = { fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 12 } },
                exitTransition = { fadeOut(tween(140)) },
                popEnterTransition = { fadeIn(tween(180)) },
                popExitTransition = { fadeOut(tween(140)) + slideOutHorizontally(tween(200)) { it / 12 } },
            ) {
                composable("home") { HomeScreen(nav) }
                composable("library") { LibraryScreen(nav) }
                composable("sources") { SourcesScreen(nav) }
                composable("videos") { VideosScreen(nav) }
                composable("music") { MusicScreen() }
                composable("player/{key}") { b -> PlayerScreen(nav, b.arguments?.getString("key") ?: "") }
                composable("vseries/{key}") { b -> VideoSeriesScreen(nav, b.arguments?.getString("key") ?: "") }
                composable("updates") { UpdatesScreen(nav) }
                composable("more") { MoreScreen(nav) }
                composable("history") { HistoryScreen(nav) }
                composable("gallery") { GalleryScreen(nav) }
                composable("stats") { StatsScreen(onBack = { nav.back() }, onOpen = { nav.details(it) }) }
                composable("settings") { SettingsScreen(nav) }
                composable("sourcetest") { SourceTestScreen(nav) }
                composable("exlogin") { ExLoginScreen(nav) }
                composable("browse/{source}?search={search}") { b ->
                    BrowseScreen(nav, b.arguments?.getString("source") ?: "", b.arguments?.getString("search") == "1")
                }
                composable("search?q={q}") { b -> GlobalSearchScreen(nav, b.arguments?.getString("q") ?: "") }
                composable("details/{key}") { b -> DetailsScreen(nav, b.arguments?.getString("key") ?: "") }
                composable("reader/{key}/{chapter}?grid={grid}") { b ->
                    // Windows: the pages sit in a column of the chosen width, centred (pictures decode at that width)
                    val s by Repo.settings.collectAsState()
                    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
                    val colW = if (s.readerWidthDp >= 1600) cfg.screenWidthDp else minOf(cfg.screenWidthDp, s.readerWidthDp)
                    val bg = when (s.readerBackground) { 1 -> Mc.Bg; 2 -> Color(0xFFF4F1EA); else -> Color.Black }
                    Box(Modifier.fillMaxSize().background(bg), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                        androidx.compose.runtime.CompositionLocalProvider(
                            androidx.compose.ui.platform.LocalConfiguration provides android.content.res.Configuration(colW, cfg.screenHeightDp)
                        ) {
                            Box(Modifier.fillMaxHeight().width(colW.dp)) {
                                ReaderScreen(nav, b.arguments?.getString("key") ?: "", b.arguments?.getString("chapter") ?: "", b.arguments?.getString("grid") == "1")
                            }
                        }
                    }
                }
                composable("web?url={url}") { b -> WebScreen(nav, b.arguments?.getString("url") ?: "") }
                composable("othersites/{key}") { b -> OtherSitesScreen(nav, b.arguments?.getString("key") ?: "") }
                composable("downloads") { DownloadsScreen(nav) }
            }
            // in-window notes (Undo etc.) and short messages
            NoteHost(Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
            ToastHost(Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
        }
        // the music's bar under every screen but the reader and the video player
        if (!route.orEmpty().startsWith("player") && !route.orEmpty().startsWith("reader")) MusicBar()
        }
    }
}

/** Short messages from anywhere in the app (Windows' stand-in for Android toasts), bottom centre, 2.5 s each. */
@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        com.yoruc.mangacracker.platform.Desktop.toasts.collect { t -> text = t; kotlinx.coroutines.delay(2500); if (text == t) text = null }
    }
    androidx.compose.animation.AnimatedVisibility(text != null, modifier.padding(bottom = 28.dp), enter = fadeIn(), exit = fadeOut()) {
        Text(text.orEmpty(), color = Mc.Text, fontSize = 14.sp,
            modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(Color(0xF01C2838)).padding(horizontal = 18.dp, vertical = 10.dp))
    }
}
