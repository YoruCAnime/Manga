package com.yoruc.mangacracker.ui

import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.NewReleases
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
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
    fun reader(ref: MangaRef, chapter: Chapter) {
        Repo.remember(ref)
        c.navigate("reader/${Uri.encode(ref.key)}/${Uri.encode(chapter.id)}") { launchSingleTop = true }
    }
    fun browse(sourceId: String, query: SearchQuery? = null) {
        Pending.query = query
        c.navigate("browse/$sourceId?search=${if (query != null) 1 else 0}")
    }
    fun globalSearch(text: String = "") = c.navigate("search?q=${Uri.encode(text)}")
    fun web(url: String) = c.navigate("web?url=${Uri.encode(url)}")
    fun history() = c.navigate("history")
    fun settings() = c.navigate("settings")
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

    LaunchedEffect(open) { if (open == "updates") nav.tab("updates") }

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
                composable("updates") { UpdatesScreen(nav) }
                composable("more") { MoreScreen(nav) }
                composable("history") { HistoryScreen(nav) }
                composable("settings") { SettingsScreen(nav) }
                composable("browse/{source}?search={search}") { b ->
                    BrowseScreen(nav, b.arguments?.getString("source") ?: "", b.arguments?.getString("search") == "1")
                }
                composable("search?q={q}") { b -> GlobalSearchScreen(nav, b.arguments?.getString("q") ?: "") }
                composable("details/{key}") { b -> DetailsScreen(nav, b.arguments?.getString("key") ?: "") }
                composable("reader/{key}/{chapter}") { b ->
                    ReaderScreen(nav, b.arguments?.getString("key") ?: "", b.arguments?.getString("chapter") ?: "")
                }
                composable("web?url={url}") { b -> WebScreen(nav, b.arguments?.getString("url") ?: "") }
            }
        }
    }
}
