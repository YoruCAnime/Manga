package com.yoruc.mangacracker.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.LoginKind
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.UpdateWorker
import com.yoruc.mangacracker.core.WebCookieJar
import com.yoruc.mangacracker.sources.Sources
import com.yoruc.mangacracker.ui.theme.Mc
import kotlinx.coroutines.launch

@Composable
private fun TopTitle(title: String, nav: Nav?, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = if (nav == null) 16.dp else 0.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (nav != null) IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Mc.Text2) }
        Text(title, fontSize = if (nav == null) 24.sp else 20.sp, fontWeight = FontWeight.ExtraBold, color = Mc.Text, modifier = Modifier.weight(1f))
        actions()
    }
}

// ---------------- Updates ----------------

@Composable
fun UpdatesScreen(nav: Nav) {
    val updates by Repo.updates.collectAsState()
    val settings by Repo.settings.collectAsState()
    val lib by Repo.library.collectAsState()
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val ctx = LocalContext.current

    Column(Modifier.fillMaxSize()) {
        TopTitle("Updates", null) {
            IconButton(enabled = checking == null && lib.isNotEmpty(), onClick = {
                checking = 0 to 1
                scope.launch {
                    val found = runCatching { UpdateWorker.checkLibrary { d, t -> checking = d to t } }.getOrDefault(emptyList())
                    checking = null
                    Toast.makeText(ctx, if (found.isEmpty()) "No new chapters" else "${found.size} new chapter(s)", Toast.LENGTH_SHORT).show()
                }
            }) { Icon(Icons.Rounded.Refresh, "Check now", tint = Mc.Text2) }
            if (updates.isNotEmpty()) IconButton(onClick = { Repo.clearUpdates() }) { Icon(Icons.Rounded.DeleteSweep, "Clear", tint = Mc.Text2) }
        }
        checking?.let { (d, t) ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("Checking $d of $t…", color = Mc.Text3, fontSize = 12.sp)
                LinearProgressIndicator(progress = { if (t == 0) 0f else d / t.toFloat() }, Modifier.fillMaxWidth().padding(top = 4.dp), color = Mc.Blue, trackColor = Mc.Card)
            }
        }
        if (settings.lastLibraryCheck > 0) Text("Last checked ${timeAgo(settings.lastLibraryCheck)}", color = Mc.Text3, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
        if (updates.isEmpty()) {
            EmptyBox("No new chapters", if (lib.isEmpty()) "Add titles to your library and new chapters will appear here." else "Tap ⟳ to check now. The app also checks in the background.")
        } else {
            // grouped by day, newest first
            val groups = updates.groupBy { dayLabel(it.found) }
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                groups.forEach { (day, list) ->
                    item(key = "d$day") { SectionHeader(day) }
                    items(list, key = { "u" + it.ref.key + it.chapter.id }) { u -> UpdateRow(u, onCover = { nav.details(u.ref) }) { nav.reader(u.ref, u.chapter) } }
                }
            }
        }
    }
}

private fun dayLabel(ms: Long): String {
    val d = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val today = java.time.LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))
    }
}

// ---------------- More ----------------

@Composable
fun MoreScreen(nav: Nav) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { TopTitle("More", null) }
        item {
            Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Mc.Card).padding(18.dp)) {
                Text("MangaCracker", color = Mc.Text, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                val lib by Repo.library.collectAsState()
                val hist by Repo.history.collectAsState()
                val prog by Repo.progress.collectAsState()
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Stat(lib.size.toString(), "in library")
                    Stat(hist.size.toString(), "titles read")
                    Stat(prog.values.count { it.read }.toString(), "chapters done")
                }
            }
        }
        item { MoreRow(Icons.Rounded.History, "History", "Everything you've read") { nav.history() } }
        item { MoreRow(Icons.Rounded.Settings, "Settings", "Reader, updates, sources, hidden tags, logins") { nav.settings() } }
        item { MoreRow(Icons.Rounded.Info, "About", "Version 1.0.0 · sources: atsu, comix, nhentai, E-Hentai, Danbooru, Gelbooru") {} }
    }
}

@Composable
private fun Stat(n: String, label: String) {
    Column {
        Text(n, color = Mc.Blue, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(label, color = Mc.Text3, fontSize = 12.sp)
    }
}

@Composable
private fun MoreRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Mc.Blue)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Mc.Text, fontSize = 16.sp)
            Text(sub, color = Mc.Text3, fontSize = 12.5.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Mc.Text3)
    }
}

// ---------------- History ----------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryScreen(nav: Nav) {
    val hist by Repo.history.collectAsState()
    val list = remember(hist) { hist.values.sortedByDescending { it.time } }
    Column(Modifier.fillMaxSize()) {
        TopTitle("History", nav) {
            if (list.isNotEmpty()) IconButton(onClick = { Repo.clearHistory() }) { Icon(Icons.Rounded.DeleteSweep, "Clear history", tint = Mc.Text2) }
        }
        if (list.isEmpty()) EmptyBox("Nothing read yet", "Titles you open in the reader show up here.")
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(list, key = { it.ref.key }) { h ->
                Row(
                    Modifier.fillMaxWidth().combinedClickable(onClick = { nav.reader(h.ref, h.chapter) }, onLongClick = { nav.details(h.ref) })
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(width = 50.dp, height = 70.dp).clip(RoundedCornerShape(5.dp)).clickable { nav.details(h.ref) }) { Cover(h.ref.cover, Modifier.fillMaxSize()) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.ref.title, color = Mc.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(h.chapter.name, color = Mc.Text2, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Page ${h.state.page + 1}/${h.state.pages} · ${h.state.percent}% · ${timeAgo(h.time)}", color = Mc.Text3, fontSize = 12.sp)
                    }
                    IconButton(onClick = { Repo.removeHistory(h.ref.key) }) { Icon(Icons.Rounded.Close, "Remove", tint = Mc.Text3) }
                }
            }
        }
    }
}

// ---------------- Settings ----------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(nav: Nav) {
    val s by Repo.settings.collectAsState()
    val ctx = LocalContext.current
    var newTag by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { TopTitle("Settings", nav) }

        item { SectionHeader("Library") }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Check for new chapters", color = Mc.Text, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0 to "Off", 3 to "3 h", 6 to "6 h", 12 to "12 h", 24 to "Daily").forEach { (h, l) ->
                        FilterPill(l, s.updateHours == h) { Repo.updateSettings { it.copy(updateHours = h) }; UpdateWorker.schedule(ctx) }
                    }
                }
                SwitchRow("Notify me about new chapters", s.notifyUpdates) { v -> Repo.updateSettings { it.copy(notifyUpdates = v) } }
                Text("Covers per row", color = Mc.Text, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (2..5).forEach { n -> FilterPill("$n", s.gridColumns == n) { Repo.updateSettings { it.copy(gridColumns = n) } } }
                }
            }
        }

        item { SectionHeader("Reader") }
        item { Box(Modifier.padding(horizontal = 0.dp)) { ReaderSettings() } }

        item { SectionHeader("Sources") }
        items(Sources.all, key = { "src" + it.id }) { src ->
            Box(Modifier.padding(horizontal = 16.dp)) {
                SwitchRow(src.name, src.id !in s.disabledSources, sub = src.blurb) { on ->
                    Repo.updateSettings { it.copy(disabledSources = if (on) it.disabledSources - src.id else it.disabledSources + src.id) }
                }
            }
        }

        item { SectionHeader("Logins & keys") }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LoginCard(
                    "ExHentai",
                    if (WebCookieJar.get("https://exhentai.org", "ipb_member_id") != null || WebCookieJar.get("https://e-hentai.org", "ipb_member_id") != null)
                        "Signed in" else "Sign in on the E-Hentai forums to use ExHentai",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { nav.web("https://forums.e-hentai.org/index.php?act=Login&CODE=00") }, colors = ButtonDefaults.buttonColors(containerColor = Mc.Blue, contentColor = Mc.Bg)) { Text("Sign in") }
                        Button(onClick = {
                            CookieManager.getInstance().apply {
                                listOf("https://e-hentai.org", "https://exhentai.org", "https://forums.e-hentai.org").forEach { u ->
                                    getCookie(u)?.split(";")?.forEach { c -> setCookie(u, c.substringBefore("=").trim() + "=; Max-Age=0; Path=/") }
                                }
                                flush()
                            }
                            Repo.updateSettings { it.copy(useExHentai = false) }
                        }, colors = ButtonDefaults.buttonColors(containerColor = Mc.Card2, contentColor = Mc.Text)) { Text("Sign out") }
                    }
                    SwitchRow("Use ExHentai instead of E-Hentai", s.useExHentai) { v -> Repo.updateSettings { it.copy(useExHentai = v) } }
                }
                LoginCard("Danbooru (optional)", "Your login name and API key lift the 2-tag search limit") {
                    KeyField("Login", s.danbooruUser) { v -> Repo.updateSettings { it.copy(danbooruUser = v.trim()) } }
                    KeyField("API key", s.danbooruKey) { v -> Repo.updateSettings { it.copy(danbooruKey = v.trim()) } }
                }
            }
        }

        item { SectionHeader("Hidden tags") }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("Titles with these tags never show up. Titles involving minors are always hidden.", color = Mc.Text3, fontSize = 12.5.sp)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        newTag, { newTag = it }, singleLine = true, placeholder = { Text("Add a tag, e.g. gore") }, modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = TextFieldDefaults.colors(focusedContainerColor = Mc.Card, unfocusedContainerColor = Mc.Card, focusedIndicatorColor = Mc.Bg, unfocusedIndicatorColor = Mc.Bg),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val t = newTag.trim()
                        if (t.isNotEmpty()) Repo.updateSettings { it.copy(hiddenTags = (it.hiddenTags + t).distinct()) }
                        newTag = ""
                    }, colors = ButtonDefaults.buttonColors(containerColor = Mc.Blue, contentColor = Mc.Bg)) { Text("Add") }
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    s.hiddenTags.forEach { t -> TagChip("$t  ✕", -1) { Repo.updateSettings { it.copy(hiddenTags = it.hiddenTags - t) } } }
                }
            }
        }
        item { SectionHeader("Storage") }
        item {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    ctx.cacheDir.resolve("images").deleteRecursively()
                    Toast.makeText(ctx, "Picture cache cleared", Toast.LENGTH_SHORT).show()
                }, colors = ButtonDefaults.buttonColors(containerColor = Mc.Card2, contentColor = Mc.Text)) { Text("Clear picture cache") }
            }
        }
    }
}

@Composable
private fun LoginCard(title: String, sub: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Mc.Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Mc.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        Text(sub, color = Mc.Text3, fontSize = 12.5.sp)
        content()
    }
}

@Composable
private fun KeyField(label: String, value: String, onSave: (String) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    TextField(
        v, { v = it; onSave(it) }, singleLine = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = TextFieldDefaults.colors(focusedContainerColor = Mc.Card2, unfocusedContainerColor = Mc.Card2, focusedIndicatorColor = Mc.Card, unfocusedIndicatorColor = Mc.Card),
    )
}

// ---------------- Web view (Cloudflare checks, logins, open site) ----------------

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(nav: Nav, url: String) {
    var web by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf(url) }
    var loading by remember { mutableStateOf(true) }
    BackHandler { if (web?.canGoBack() == true) web?.goBack() else nav.back() }
    Column(Modifier.fillMaxSize().background(Mc.Bg)) {
        Row(Modifier.fillMaxWidth().background(Mc.Card).windowInsetsPadding(WindowInsets.statusBars).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { WebCookieJar.flush(); nav.back() }) { Icon(Icons.Rounded.Close, "Close", tint = Mc.Text2) }
            Text(title, color = Mc.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontSize = 14.sp)
            IconButton(onClick = { web?.reload() }) { Icon(Icons.Rounded.Refresh, "Reload", tint = Mc.Text2) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = Mc.Blue, trackColor = Mc.Card)
        AndroidView(
            modifier = Modifier.fillMaxSize().navigationBarsPadding(),
            factory = { c ->
                WebView(c).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = Http.userAgent
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, u: String) {
                            loading = false; title = view.title ?: u
                            WebCookieJar.flush()
                            // after signing in on the forums, carry the login over to ExHentai
                            if (u.contains("e-hentai.org")) copyEhLogin()
                        }
                        override fun onPageStarted(view: WebView, u: String, favicon: android.graphics.Bitmap?) { loading = true }
                    }
                    loadUrl(url)
                    web = this
                }
            },
        )
    }
}

private fun copyEhLogin() {
    val id = WebCookieJar.get("https://e-hentai.org", "ipb_member_id") ?: WebCookieJar.get("https://forums.e-hentai.org", "ipb_member_id") ?: return
    val hash = WebCookieJar.get("https://e-hentai.org", "ipb_pass_hash") ?: WebCookieJar.get("https://forums.e-hentai.org", "ipb_pass_hash") ?: return
    WebCookieJar.set("https://exhentai.org", "ipb_member_id", id, ".exhentai.org")
    WebCookieJar.set("https://exhentai.org", "ipb_pass_hash", hash, ".exhentai.org")
    WebCookieJar.set("https://e-hentai.org", "ipb_member_id", id, ".e-hentai.org")
    WebCookieJar.set("https://e-hentai.org", "ipb_pass_hash", hash, ".e-hentai.org")
    WebCookieJar.flush()
}
