package com.yoruc.mangacracker.ui

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
    fun settings() = c.navigate("settings")
    fun sourceTest() = c.navigate("sourcetest")
    fun tab(route: String) = c.navigate(route) {
        popUpTo(c.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** A search handed from one screen to the browse screen it opens. */
object Pending { var query: SearchQuery? = null }

@Composable
fun AppRoot(open: String?) {
    val c = rememberNavController()
    val nav = Nav(c)
    val entry by c.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: "home"
    val updates by Repo.updates.collectAsState()
    val showBar = tabs.any { it.route == route }
    val appUpd by AppUpdate.state.collectAsState()
    val settings by Repo.settings.collectAsState()
    var updateAsked by remember { mutableStateOf(open == "appupdate") }

    LaunchedEffect(open) {
        if (open == "updates") nav.tab("updates")
        if (open == "downloads") { nav.tab("more"); nav.downloads() }
    }
    LaunchedEffect(Unit) { if (settings.autoAppUpdates) AppUpdate.check() }

    // the update pop-up: on the main tabs only (never over the reader), once per version unless opened from the notification
    val newRel = appUpd.release
    if (newRel != null && (updateAsked || (showBar && settings.autoAppUpdates && newRel.version != settings.dismissedAppVersion))) {
        AppUpdatePopup(onClose = {
            updateAsked = false
            Repo.updateSettings { it.copy(dismissedAppVersion = newRel.version) }
        })
    }

    Scaffold(
        containerColor = Mc.Bg,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = Mc.Card, tonalElevation = 0.dp) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { nav.tab(t.route) },
                            icon = {
                                if (t.route == "updates" && updates.isNotEmpty()) {
                                    BadgedBox(badge = { Badge(containerColor = Mc.Pink) { Text(if (updates.size > 99) "99+" else updates.size.toString()) } }) {
                                        Icon(t.icon, t.label)
                                    }
                                } else if (t.route == "more" && newRel != null) {
                                    BadgedBox(badge = { Badge(containerColor = Mc.Pink) }) { Icon(t.icon, t.label) }
                                } else Icon(t.icon, t.label)
                            },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Mc.Blue, selectedTextColor = Mc.Blue,
                                indicatorColor = Mc.Blue.copy(alpha = 0.12f),
                                unselectedIconColor = Mc.Text3, unselectedTextColor = Mc.Text3,
                            ),
                        )
                    }
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(bottom = if (showBar) pad.calculateBottomPadding() else 0.dp)) {
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
                composable("player/{key}") { b -> PlayerScreen(nav, b.arguments?.getString("key") ?: "") }
                composable("vseries/{key}") { b -> VideoSeriesScreen(nav, b.arguments?.getString("key") ?: "") }
                composable("updates") { UpdatesScreen(nav) }
                composable("more") { MoreScreen(nav) }
                composable("history") { HistoryScreen(nav) }
                composable("settings") { SettingsScreen(nav) }
                composable("sourcetest") { SourceTestScreen(nav) }
                composable("browse/{source}?search={search}") { b ->
                    BrowseScreen(nav, b.arguments?.getString("source") ?: "", b.arguments?.getString("search") == "1")
                }
                composable("search?q={q}") { b -> GlobalSearchScreen(nav, b.arguments?.getString("q") ?: "") }
                composable("details/{key}") { b -> DetailsScreen(nav, b.arguments?.getString("key") ?: "") }
                composable("reader/{key}/{chapter}?grid={grid}") { b ->
                    ReaderScreen(nav, b.arguments?.getString("key") ?: "", b.arguments?.getString("chapter") ?: "", b.arguments?.getString("grid") == "1")
                }
                composable("web?url={url}") { b -> WebScreen(nav, b.arguments?.getString("url") ?: "") }
                composable("othersites/{key}") { b -> OtherSitesScreen(nav, b.arguments?.getString("key") ?: "") }
                composable("downloads") { DownloadsScreen(nav) }
            }
            // in-window notes (Undo etc.), above the bottom bar
            NoteHost(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).then(if (showBar) Modifier else Modifier.navigationBarsPadding()))
        }
    }
}
