package com.yoruc.mangacracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import com.yoruc.mangacracker.music.REPEAT_ALL
import com.yoruc.mangacracker.music.REPEAT_OFF
import com.yoruc.mangacracker.music.REPEAT_ONE
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yoruc.mangacracker.music.Album
import com.yoruc.mangacracker.music.Music
import com.yoruc.mangacracker.music.MusicAnime
import com.yoruc.mangacracker.music.Track
import com.yoruc.mangacracker.ui.theme.Mc
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Music tab (the same file in both apps): search an anime, then play its full songs (all, or only OP / ED / other
 * songs) or its soundtrack albums, in order or shuffled; or the LISTEN.moe radio for anime songs from anywhere.
 * Audio only; it keeps playing on other tabs and with the app in the background ([MusicBar] controls it).
 */
/** Test runs: the search the Music tab opens with; [musicOpenFirst] = then open the first suggestion. */
internal var musicStartQuery = ""
internal var musicOpenFirst = false

/**
 * The Music tab (the same file in both apps): type an anime and pick it from the suggestions (AniList, as you type),
 * then play its openings and endings in full (this season or all seasons, all or only OP / ED, in order or shuffled)
 * or its soundtrack albums; or the LISTEN.moe radio for anime songs from anywhere. Audio only; it keeps playing on
 * other tabs and with the app in the background ([MusicBar] controls it).
 */
@Composable
fun MusicScreen() {
    var query by rememberSaveable { mutableStateOf(musicStartQuery.also { musicStartQuery = "" }) }
    var results by remember { mutableStateOf<List<MusicAnime>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<MusicAnime?>(null) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = null; error = null; return@LaunchedEffect }
        delay(300)
        searching = results == null; error = null
        results = runCatching { Music.searchAnime(q) }.onFailure { error = "Couldn't search: ${it.message}" }.getOrNull()
        searching = false
        if (musicOpenFirst) { musicOpenFirst = false; results?.firstOrNull()?.let { open = it } }
    }
    val o = open
    if (o != null) { AnimeMusic(o) { open = null }; return }
    Column(Modifier.fillMaxSize()) {
        Text("Music", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Mc.Text,
            modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, top = 10.dp, bottom = 6.dp))
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().widthIn(max = 900.dp).padding(horizontal = 16.dp),
            placeholder = { Text("Type an anime (e.g. zero no)", color = Mc.Text3) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = Mc.Text3) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear", tint = Mc.Text3) } },
            singleLine = true, shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Mc.Text, unfocusedTextColor = Mc.Text, focusedBorderColor = Mc.Blue,
                unfocusedBorderColor = Mc.Line, cursorColor = Mc.Blue, focusedContainerColor = Mc.Card, unfocusedContainerColor = Mc.Card),
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp)) {
            if (results == null && !searching) item { RadioCard() }
            if (searching) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Mc.Blue) } }
            error?.let { e -> item { Text(e, color = Mc.Orange, modifier = Modifier.padding(16.dp)) } }
            val list = results
            if (list != null && !searching && list.isEmpty()) item { EmptyBox("Nothing found", "Try the Japanese or the English name, or a shorter part of it.", height = 180.dp) }
            if (list != null) items(list, key = { it.id }) { a ->
                Row(Modifier.fillMaxWidth().clickable { open = a }.padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 46.dp, height = 64.dp).clip(RoundedCornerShape(6.dp)).background(Mc.Card2)) { if (a.cover.isNotEmpty()) Cover(a.cover, Modifier.fillMaxSize()) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.title, color = Mc.Text, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(a.name.takeIf { it != a.title && it.isNotEmpty() }, a.format.ifEmpty { null }, a.year?.toString()).joinToString(" · "),
                            color = Mc.Text3, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (results == null && !searching) item {
                Text("Type an anime and pick it: play all its openings and endings in full (this season or every season in one " +
                    "list), or its soundtrack albums. Everything plays as sound only and keeps playing on other tabs and with the app in the background.",
                    color = Mc.Text3, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
    }
}

/** LISTEN.moe: anime songs all day; shows what's on while this card is on screen. */
@Composable
private fun RadioCard() {
    DisposableEffect(Unit) { Music.watchRadio(); onDispose { if (Music.player.now.value?.live != true) Music.unwatchRadio() } }
    val now by Music.radioNow.collectAsState()
    val playing by Music.player.now.collectAsState()
    Row(
        Modifier.fillMaxWidth().widthIn(max = 900.dp).padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(Mc.Card)
            .clickable { Music.player.play(listOf(Music.radioTrack())) }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(Mc.Card2), contentAlignment = Alignment.Center) {
            val c = now?.cover.orEmpty()
            if (c.isNotEmpty()) Cover(c, Modifier.fillMaxSize()) else Icon(Icons.Rounded.Radio, null, tint = Mc.Blue, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Random anime songs · LISTEN.moe radio", color = Mc.Text, fontWeight = FontWeight.Bold)
            Text(now?.let { "Now: ${it.title}" + (if (it.artist.isNotEmpty()) " · ${it.artist}" else "") + (if (it.anime.isNotEmpty()) " (${it.anime})" else "") }
                ?: "Anime songs from everywhere, all day · Opus 192 kbps", color = Mc.Text2, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(if (playing?.live == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play the radio", tint = Mc.Blue, modifier = Modifier.size(34.dp))
    }
}

/** One anime: its openings and endings (this season or all seasons), and soundtrack albums. */
@Composable
private fun AnimeMusic(a: MusicAnime, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    var seasons by remember(a.id) { mutableStateOf<List<MusicAnime>?>(null) }
    var own by remember(a.id) { mutableStateOf<List<Track>?>(null) }
    var every by remember(a.id) { mutableStateOf<List<Track>?>(null) }
    var albums by remember(a.id) { mutableStateOf<List<Album>?>(null) }
    var error by remember(a.id) { mutableStateOf<String?>(null) }
    var allSeasons by rememberSaveable(a.id) { mutableStateOf(false) }
    var filter by rememberSaveable(a.id) { mutableStateOf("All") }
    var album by remember(a.id) { mutableStateOf<Album?>(null) }
    var gathering by remember(a.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(a.id) {
        coroutineScope {
            launch { own = runCatching { Music.songs(a) }.onFailure { error = "Couldn't load the songs: ${it.message}" }.getOrDefault(emptyList()) }
            launch { albums = runCatching { Music.albums(a.english, a.name, a.native) }.getOrDefault(emptyList()) }
            launch {
                val s = runCatching { Music.seasons(a) }.getOrDefault(listOf(a))
                seasons = s
                // every season's songs, filled in season by season
                val acc = ArrayList<Track>()
                for (x in s) { acc += runCatching { Music.songs(x) }.getOrDefault(emptyList()); every = acc.distinctBy { Music.songKey(it) } }
                every = acc.distinctBy { Music.songKey(it) }
            }
        }
    }
    val al = album
    if (al != null) { AlbumPage(al, a) { album = null }; return }
    val base = (if (allSeasons) every else own).orEmpty()
    val shown = when (filter) { "OP" -> base.filter { it.kind == "OP" }; "ED" -> base.filter { it.kind == "ED" }; else -> base }
    val loading = if (allSeasons) every == null || (seasons?.size ?: 0) > shown.map { it.season }.distinct().size && every?.size != base.size else own == null
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Mc.Text2) }
            Text(a.title, color = Mc.Text, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(width = 84.dp, height = 118.dp).clip(RoundedCornerShape(8.dp)).background(Mc.Card2)) { if (a.cover.isNotEmpty()) Cover(a.cover, Modifier.fillMaxSize()) }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(listOfNotNull(a.name.takeIf { it != a.title && it.isNotEmpty() }, a.format.ifEmpty { null }, a.year?.toString()).joinToString(" · "),
                            color = Mc.Text3, fontSize = 13.sp, maxLines = 2)
                        Text(when {
                            base.isEmpty() && loading -> "Looking for its songs…"
                            base.isEmpty() -> "No openings or endings listed"
                            else -> base.count { it.kind == "OP" }.let { "$it opening" + if (it == 1) "" else "s" } + " · " + base.count { it.kind == "ED" }.let { "$it ending" + if (it == 1) "" else "s" } +
                                if (allSeasons) " · ${seasons?.size ?: 1} seasons" else ""
                        }, color = Mc.Text2, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("Play", Icons.Rounded.PlayArrow, enabled = shown.isNotEmpty()) { Music.player.play(shown) }
                            Pill("Shuffle", Icons.Rounded.Shuffle, enabled = shown.size > 1) { Music.player.play(shown, shown.indices.random(), shuffled = true) }
                        }
                    }
                }
            }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Chip("This season", !allSeasons) { allSeasons = false } }
                    item { Chip("All seasons" + (seasons?.let { " · ${it.size}" } ?: ""), allSeasons) { allSeasons = true } }
                }
            }
            if (base.isNotEmpty()) item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(listOf("All" to base.size, "OP" to base.count { it.kind == "OP" }, "ED" to base.count { it.kind == "ED" }).filter { it.second > 0 || it.first == "All" }) { (f, n) ->
                        Chip(when (f) { "All" -> "Openings & endings · $n"; "OP" -> "Openings · $n"; else -> "Endings · $n" }, filter == f) { filter = f }
                    }
                }
            }
            error?.let { e -> item { Text(e, color = Mc.Orange, modifier = Modifier.padding(16.dp)) } }
            var lastSeason = ""
            shown.forEachIndexed { i, t ->
                if (allSeasons && t.season != lastSeason) {
                    lastSeason = t.season
                    item(key = "s:" + t.season + i) { Text(t.season, color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp)) }
                }
                item(key = t.id) { TrackRow(t) { Music.player.play(shown, i) } }
            }
            if (loading) item { Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Mc.Blue, modifier = Modifier.size(28.dp)) } }
            val als = albums.orEmpty()
            if (als.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Soundtracks & albums · ${als.size}", color = Mc.Text, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        // every album's tracks in one list (up to 12 albums)
                        Pill(if (gathering) "Loading…" else "Play all albums", Icons.Rounded.PlayArrow, enabled = !gathering) {
                            gathering = true
                            scope.launch {
                                val all = ArrayList<Track>()
                                for (x in als.take(12)) all += runCatching { Music.albumTracks(x, a.title, a.cover) }.getOrDefault(emptyList())
                                gathering = false
                                if (all.isNotEmpty()) Music.player.play(all)
                            }
                        }
                    }
                }
                items(als, key = { "al:" + it.id }) { b ->
                    Row(Modifier.fillMaxWidth().clickable { album = b }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Album, null, tint = Mc.Blue)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.title, color = Mc.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (b.source == "youtube") "YouTube Music" + (if (b.artist.isNotEmpty()) " · ${b.artist}" else "") else "archive.org · ${b.downloads} plays",
                                color = Mc.Text3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumPage(b: Album, a: MusicAnime, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(onBack = onBack)
    var tracks by remember(b.id) { mutableStateOf<List<Track>?>(null) }
    var error by remember(b.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(b.id) { tracks = runCatching { Music.albumTracks(b, a.title, a.cover) }.onFailure { error = "Couldn't open the album: ${it.message}" }.getOrDefault(emptyList()) }
    val list = tracks.orEmpty()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(top = 6.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Mc.Text2) }
            Text(b.title, color = Mc.Text, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("Play", Icons.Rounded.PlayArrow, enabled = list.isNotEmpty()) { Music.player.play(list) }
            Pill("Shuffle", Icons.Rounded.Shuffle, enabled = list.size > 1) { Music.player.play(list, list.indices.random(), shuffled = true) }
            if (list.isNotEmpty()) Text("${list.size} tracks · " + if (b.source == "youtube") "YouTube Music, full tracks" else list.groupingBy { it.quality.substringBefore(' ') }.eachCount().entries.joinToString { "${it.value} ${it.key}" },
                color = Mc.Text3, fontSize = 12.sp)
        }
        when {
            tracks == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Mc.Blue) }
            error != null -> Text(error.orEmpty(), color = Mc.Orange, modifier = Modifier.padding(16.dp))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                itemsIndexed(list, key = { _, t -> t.id }) { i, t -> TrackRow(t) { Music.player.play(list, i) } }
            }
        }
    }
}

@Composable
private fun TrackRow(t: Track, onClick: () -> Unit) {
    val now by Music.player.now.collectAsState()
    val on = now?.id == t.id
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).background(if (on) Mc.Blue.copy(alpha = 0.10f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(44.dp).clip(RoundedCornerShape(6.dp)).background(if (t.kind == "OP") Mc.Blue.copy(alpha = 0.18f) else if (t.kind == "ED") Mc.Pink.copy(alpha = 0.18f) else Mc.Card2).padding(vertical = 4.dp),
            contentAlignment = Alignment.Center) {
            Text(if (t.kind == "OST") "${t.order}" else t.kind.let { if (it == "Song") "♪" else it + if (t.order > 0) t.order else "" },
                color = if (t.kind == "OP") Mc.Blue else if (t.kind == "ED") Mc.Pink else Mc.Text2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = if (on) Mc.Blue else Mc.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(t.artist.ifEmpty { null }, (if (on) now?.quality else null)?.ifEmpty { null } ?: t.quality.ifEmpty { null } ?: if (t.url.isEmpty() && !t.live) "full song · YouTube" else null).joinToString(" · "), color = Mc.Text3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (t.durationS > 0) Text("%d:%02d".format(t.durationS / 60, t.durationS % 60), color = Mc.Text3, fontSize = 12.sp)
    }
}

@Composable
private fun Pill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(if (enabled) Mc.Blue else Mc.Card2).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (enabled) Mc.Bg else Mc.Text3, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (enabled) Mc.Bg else Mc.Text3, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) Mc.Blue else Mc.Card2).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, color = if (on) Mc.Bg else Mc.Text, fontSize = 12.5.sp)
    }
}

/**
 * The now-playing bar (both apps, on every screen but the reader and the video player): cover, title, previous /
 * play-pause / next, stop; a thin line shows how far in (click / tap on it to jump there).
 */
@Composable
fun MusicBar(modifier: Modifier = Modifier) {
    if (!Music.ready) return
    val p = Music.player
    val now by p.now.collectAsState()
    val t = now ?: return
    val playing by p.playing.collectAsState()
    val pos by p.position.collectAsState()
    val dur by p.duration.collectAsState()
    val queue by p.queue.collectAsState()
    val radio by Music.radioNow.collectAsState()
    val shown = if (t.live) radio ?: t else t
    val scope = rememberCoroutineScope()
    val rep by p.repeat.collectAsState()
    /** a short note in place of the artist line (what the loop button now does) */
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) { if (hint != null) { delay(2500); hint = null } }
    Column(modifier.fillMaxWidth().background(Mc.Card)) {
        // progress (live radio: none)
        val frac = if (dur > 0 && !t.live) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(10.dp).pointerInput(dur) {
            detectTapPosition { x -> if (dur > 0) scope.launch { p.seek((x / size.width * dur).toLong()) } }
        }) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(Mc.Line))
            Box(Modifier.fillMaxWidth(frac).height(3.dp).background(Mc.Blue))
        }
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)).background(Mc.Card2), contentAlignment = Alignment.Center) {
                if (shown.cover.isNotEmpty()) Cover(shown.cover, Modifier.fillMaxSize()) else Icon(if (t.live) Icons.Rounded.Radio else Icons.Rounded.Album, null, tint = Mc.Blue)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(shown.title, color = Mc.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(hint ?: (listOfNotNull(shown.artist.ifEmpty { null }, shown.anime.ifEmpty { null }).joinToString(" · ") +
                    (if (t.live) " · radio" else if (dur > 0) " · ${clockMs(pos)} / ${clockMs(dur)}" else "")),
                    color = if (hint != null) Mc.Blue else Mc.Text3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!t.live) IconButton(onClick = { p.previous() }) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = Mc.Text2) }
            IconButton(onClick = { p.toggle() }) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = Mc.Text, modifier = Modifier.size(30.dp)) }
            if (!t.live) IconButton(onClick = { p.next() }, enabled = queue.size > 1) { Icon(Icons.Rounded.SkipNext, "Next", tint = if (queue.size > 1) Mc.Text2 else Mc.Text3) }
            // loop: the whole list (default) -> this one track -> no loop
            if (!t.live) IconButton(onClick = {
                val m = when (rep) { REPEAT_ALL -> REPEAT_ONE; REPEAT_ONE -> REPEAT_OFF; else -> REPEAT_ALL }
                p.setRepeat(m)
                hint = when (m) { REPEAT_ONE -> "Loop: this song again and again"; REPEAT_OFF -> "No loop: stops after the last one"; else -> "Loop: the whole list starts over at the end" }
            }) {
                Icon(if (rep == REPEAT_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    when (rep) { REPEAT_ONE -> "Loop this song"; REPEAT_OFF -> "No loop"; else -> "Loop the list" },
                    tint = if (rep == REPEAT_OFF) Mc.Text3 else Mc.Blue)
            }
            IconButton(onClick = { p.stop() }) { Icon(Icons.Rounded.Close, "Stop the music", tint = Mc.Text3) }
        }
    }
}

private fun clockMs(ms: Long) = (ms / 1000).let { "%d:%02d".format(it / 60, it % 60) }

/** Settings › Music: the Music tab's own volume (separate from videos and the reader's sounds), kept. */
@Composable
fun MusicSettings() {
    val s by com.yoruc.mangacracker.core.Repo.settings.collectAsState()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("Music volume · ${(s.musicVolume * 100).toInt()}%", color = Mc.Text2, fontSize = 13.sp)
        androidx.compose.material3.Slider(
            s.musicVolume, { v -> val r = (v * 20).toInt() / 20f; if (Music.ready) Music.player.setVolume(r) else com.yoruc.mangacracker.core.Repo.updateSettings { it.copy(musicVolume = r) } },
            valueRange = 0f..1f,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Mc.Blue, activeTrackColor = Mc.Blue, inactiveTrackColor = Mc.Card2),
        )
        Text("The Music tab's songs and radio (videos and the reader's sounds have their own).", color = Mc.Text3, fontSize = 12.sp)
    }
}

/** A tap / click anywhere on the element: its x. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapPosition(onTap: (Float) -> Unit) =
    detectTapGestures { onTap(it.x) }
