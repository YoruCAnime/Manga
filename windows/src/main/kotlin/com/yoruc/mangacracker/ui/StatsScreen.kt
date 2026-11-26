package com.yoruc.mangacracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yoruc.mangacracker.core.DayLog
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.Stats
import com.yoruc.mangacracker.ui.theme.Mc
import com.yoruc.mangacracker.video.VideoRepo
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** "3 h 20 min", "45 min", "0 min". */
internal fun spent(ms: Long): String {
    val min = ms / 60_000
    return when {
        min >= 60 -> "${min / 60} h" + if (min % 60 > 0) " ${min % 60} min" else ""
        else -> "$min min"
    }
}

/** Your stats: streak, the last 7 days, activity, favourite genres, most read, all time. Same screen in both apps. */
@Composable
fun StatsScreen(onBack: () -> Unit, onOpen: (MangaRef) -> Unit) {
    val stats by Stats.data.collectAsState()
    val prog by Repo.progress.collectAsState()
    val lib by Repo.library.collectAsState()
    val hist by Repo.history.collectAsState()
    val watch by VideoRepo.watch.collectAsState()
    val favs by VideoRepo.favs.collectAsState()
    val days = stats.days
    val today = LocalDate.now()
    fun at(k: Int) = days[today.minusDays(k.toLong()).toString()] ?: DayLog()
    val week = remember(days) { (6 downTo 0).map { at(it) } }       // oldest first, today last
    val before = remember(days) { (13 downTo 7).map { at(it) } }

    // favourite genres: each title weighs the chapters you finished in it, +2 when it's in your library
    val mangaGenres = remember(prog, lib, hist) {
        val read = prog.entries.filter { it.value.read }.groupingBy { it.key.substringBefore('#') }.eachCount()
        val w = HashMap<String, Int>()
        (lib.keys + hist.keys).distinct().forEach { k ->
            val tags = lib[k]?.genres?.ifEmpty { null } ?: Repo.detailsCache[k]?.genres?.ifEmpty { null } ?: lib[k]?.ref?.tags ?: hist[k]?.ref?.tags ?: emptyList()
            val weight = (read[k] ?: 0) + if (k in lib) 2 else 0
            if (weight > 0) tags.forEach { t -> if (!Safety.isMinorTag(t) && !Safety.optTag(t)) w[nice(t)] = (w[nice(t)] ?: 0) + weight }
        }
        w.entries.sortedByDescending { it.value }.take(6).map { it.key to it.value }
    }
    val videoGenres = remember(watch, favs) {
        val w = HashMap<String, Int>()
        watch.values.forEach { s -> val k = if (s.durationMs > 0 && s.positionMs > s.durationMs * 0.8) 2 else 1; s.ref.tags.forEach { t -> if (!Safety.isMinorTag(t)) w[nice(t)] = (w[nice(t)] ?: 0) + k } }
        favs.values.forEach { f -> f.ref.tags.forEach { t -> if (!Safety.isMinorTag(t)) w[nice(t)] = (w[nice(t)] ?: 0) + 3 } }
        w.entries.sortedByDescending { it.value }.take(6).map { it.key to it.value }
    }
    // most read in the last 30 days
    val top = remember(days, lib, hist) {
        (0 until 30).flatMap { at(it).chapters }.groupingBy { it.substringBefore('#') }.eachCount()
            .entries.sortedByDescending { it.value }.mapNotNull { (k, n) -> (lib[k]?.ref ?: hist[k]?.ref ?: Repo.refs[k])?.let { it to n } }.take(5)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Mc.Text2) }
            Text("Your stats", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Mc.Text, modifier = Modifier.weight(1f))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            item { Streak(Stats.streak(days), Stats.longestStreak(days), at(0).active) }
            item {
                Card("Last 7 days") {
                    Tiles(listOf(
                        TileData("Chapters", week.sumOf { it.chapters.size }.toString(), delta(week.sumOf { it.chapters.size }, before.sumOf { it.chapters.size })),
                        TileData("Reading", spent(week.sumOf { it.readMs })),
                        TileData("Videos", week.sumOf { it.videos.size }.toString(), delta(week.sumOf { it.videos.size }, before.sumOf { it.videos.size })),
                        TileData("Watching", spent(week.sumOf { it.watchMs })),
                    ))
                    Spacer(Modifier.height(14.dp))
                    WeekBars(week, today)
                }
            }
            item { Card("Activity") { Heat(days, today) } }
            if (mangaGenres.isNotEmpty() || videoGenres.isNotEmpty()) item {
                Card("Favourite genres") {
                    if (mangaGenres.isNotEmpty()) GenreBars("Manga", mangaGenres)
                    if (mangaGenres.isNotEmpty() && videoGenres.isNotEmpty()) Spacer(Modifier.height(14.dp))
                    if (videoGenres.isNotEmpty()) GenreBars("Videos", videoGenres)
                }
            }
            if (top.isNotEmpty()) item {
                Card("Most read this month") {
                    top.forEachIndexed { i, (ref, n) ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onOpen(ref) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${i + 1}", color = Mc.Text3, fontWeight = FontWeight.Bold, modifier = Modifier.width(22.dp))
                            Box(Modifier.size(width = 40.dp, height = 56.dp).clip(RoundedCornerShape(5.dp))) { Cover(ref.cover, Modifier.fillMaxSize()) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(ref.title, color = Mc.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("$n chapter${if (n == 1) "" else "s"} this month", color = Mc.Text3, fontSize = 12.5.sp)
                            }
                        }
                    }
                }
            }
            item {
                Card("All time") {
                    val active = days.values.count { it.active }
                    Tiles(listOf(
                        TileData("Chapters read", prog.values.count { it.read }.toString()),
                        TileData("Titles read", hist.size.toString()),
                        TileData("Days active", active.toString()),
                        TileData("Videos watched", watch.size.toString()),
                        TileData("Time watching", spent(days.values.sumOf { it.watchMs })),
                        TileData("Time reading", spent(days.values.sumOf { it.readMs })),
                    ), wide = 3)
                }
            }
            item {
                val since = runCatching { LocalDate.parse(stats.since).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)) }.getOrNull()
                if (since != null) Text(
                    "Reading time is counted from $since. Days before that come from your history: chapters you finished and videos you watched.",
                    color = Mc.Text3, fontSize = 12.sp, modifier = Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
        }
    }
}

private fun nice(t: String) = t.trim().lowercase().replaceFirstChar { it.titlecase() }

private fun delta(now: Int, before: Int): Pair<String, Boolean>? = when {
    now > before -> "+${now - before} vs last week" to true
    now < before -> "${now - before} vs last week" to false
    else -> null
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(Mc.Card).padding(16.dp)) {
        Text(title, color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(Modifier.height(12.dp))
        content()
    }
}

@Composable
private fun Streak(now: Int, best: Int, today: Boolean) {
    Row(
        Modifier.widthIn(max = 860.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(14.dp)).background(Mc.Card).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(50)).background(if (now > 0) Mc.Orange.copy(alpha = 0.18f) else Mc.Card2), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.LocalFireDepartment, null, tint = if (now > 0) Mc.Orange else Mc.Text3, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(if (now > 0) "$now-day streak" else "No streak yet", color = Mc.Text, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            Text(
                when {
                    now > 0 && today -> "Today counts. Longest: $best day${if (best == 1) "" else "s"}"
                    now > 0 -> "Read or watch something today to keep it going. Longest: $best day${if (best == 1) "" else "s"}"
                    best > 0 -> "Read a chapter or watch something to start one. Longest: $best day${if (best == 1) "" else "s"}"
                    else -> "Read a chapter or watch something to start one"
                },
                color = Mc.Text2, fontSize = 13.sp,
            )
        }
    }
}

private class TileData(val label: String, val value: String, val delta: Pair<String, Boolean>? = null)

/** [wide] tiles to a row (all of them when that's not set), two on a narrow screen. */
@Composable
private fun Tiles(list: List<TileData>, wide: Int = list.size) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val per = if (maxWidth < 560.dp) 2 else wide
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            list.chunked(per).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { Tile(it.label, it.value, it.delta, Modifier.weight(1f)) }
                    repeat(per - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, delta: Pair<String, Boolean>?, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Mc.Card2).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(value, color = Mc.Blue, fontWeight = FontWeight.Bold, fontSize = 19.sp, maxLines = 1)
        Text(label, color = Mc.Text2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (delta != null) Text(delta.first, color = if (delta.second) Mc.Green else Mc.Text3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The last 7 days as bars: chapters + videos, or reading + watching time. */
@Composable
private fun WeekBars(week: List<DayLog>, today: LocalDate) {
    var mode by remember { mutableIntStateOf(0) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ModeChip("Chapters & videos", mode == 0) { mode = 0 }
        ModeChip("Time", mode == 1) { mode = 1 }
    }
    Spacer(Modifier.height(12.dp))
    val parts = week.map { d -> if (mode == 0) d.chapters.size.toFloat() to d.videos.size.toFloat() else d.readMs / 60_000f to d.watchMs / 60_000f }
    val max = parts.maxOf { it.first + it.second }.coerceAtLeast(1f)
    val (cA, cB) = if (mode == 0) Mc.Blue to Mc.Pink else Mc.Blue to Mc.Orange
    Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEachIndexed { i, (a, b) ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                val total = a + b
                Text(if (total <= 0f) "" else if (mode == 0) total.toInt().toString() else spent(((a + b) * 60_000).toLong()),
                    color = Mc.Text2, fontSize = 11.sp, maxLines = 1)
                Canvas(Modifier.fillMaxWidth().weight(1f).padding(top = 3.dp)) {
                    val w = size.width.coerceAtMost(36.dp.toPx()); val x = (size.width - w) / 2
                    val hb = size.height * b / max; val ha = size.height * a / max
                    val r = CornerRadius(5.dp.toPx())
                    drawRoundRect(Color(0x14FFFFFF), Offset(x, 0f), Size(w, size.height), r)
                    if (b > 0f) drawRoundRect(cB, Offset(x, size.height - hb - ha), Size(w, hb + ha), r)
                    if (a > 0f) drawRoundRect(cA, Offset(x, size.height - ha), Size(w, ha), r)
                }
                val d = today.minusDays((6 - i).toLong())
                Text(if (i == 6) "Today" else d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH),
                    color = if (i == 6) Mc.Text else Mc.Text3, fontSize = 11.5.sp, fontWeight = if (i == 6) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Legend(cA, if (mode == 0) "Chapters" else "Reading")
        Spacer(Modifier.width(16.dp))
        Legend(cB, if (mode == 0) "Videos" else "Watching")
    }
}

@Composable
private fun ModeChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) Mc.Blue else Mc.Card2).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, color = if (on) Mc.Bg else Mc.Text, fontSize = 12.5.sp)
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(c))
    Spacer(Modifier.width(6.dp))
    Text(label, color = Mc.Text2, fontSize = 12.sp)
}

/** The last weeks as squares, a column per week (Monday on top), brighter = more done that day. */
@Composable
private fun Heat(days: Map<String, DayLog>, today: LocalDate) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // as many weeks as fit (at most half a year), the squares grown to fill the card
        val gap = 3.dp
        val weeks = ((maxWidth + gap) / (13.dp + gap)).toInt().coerceIn(8, 26)
        val cell = ((maxWidth + gap) / weeks - gap).coerceAtMost(26.dp)
        val firstMonday = today.minusDays((today.dayOfWeek.value - 1).toLong()).minusWeeks((weeks - 1).toLong())
        val levels = Array(weeks) { w ->
            IntArray(7) { r ->
                val d = firstMonday.plusDays((w * 7 + r).toLong())
                if (d.isAfter(today)) -1 else {
                    val l = days[d.toString()]
                    val score = if (l == null) 0f else l.chapters.size + l.videos.size + (l.readMs + l.watchMs) / 600_000f
                    when { l == null || !l.active -> 0; score < 3 -> 1; score < 7 -> 2; score < 14 -> 3; else -> 4 }
                }
            }
        }
        val shades = listOf(Mc.Card2, Mc.BlueSoft.copy(alpha = 0.55f), Mc.BlueSoft, Color(0xFF3393C8), Mc.Blue)
        Column {
            Canvas(Modifier.width((cell + gap) * weeks).height((cell + gap) * 7)) {
                val c = cell.toPx(); val g = gap.toPx()
                for (w in 0 until weeks) for (r in 0 until 7) {
                    val l = levels[w][r]
                    if (l < 0) continue
                    drawRoundRect(shades[l], Offset(w * (c + g), r * (c + g)), Size(c, c), CornerRadius(3.dp.toPx()))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${weeks} weeks · Less", color = Mc.Text3, fontSize = 11.5.sp)
                Spacer(Modifier.width(6.dp))
                shades.forEach { Box(Modifier.padding(horizontal = 1.5.dp).size(10.dp).clip(RoundedCornerShape(2.dp)).background(it)) }
                Spacer(Modifier.width(6.dp))
                Text("More", color = Mc.Text3, fontSize = 11.5.sp)
            }
        }
    }
}

@Composable
private fun GenreBars(label: String, list: List<Pair<String, Int>>) {
    Text(label, color = Mc.Text2, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    val max = list.maxOf { it.second }.coerceAtLeast(1)
    list.forEach { (name, n) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = Mc.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(120.dp))
            Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Mc.Card2)) {
                Box(Modifier.fillMaxWidth(n.toFloat() / max).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(Mc.genreColor(name)))
            }
        }
    }
}
