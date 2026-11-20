package com.yoruc.mangacracker.ui

import android.app.Application
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yoruc.mangacracker.core.CheckRow
import com.yoruc.mangacracker.core.CheckStatus
import com.yoruc.mangacracker.core.SourceCheck
import com.yoruc.mangacracker.sources.Sources
import com.yoruc.mangacracker.ui.theme.Mc
import com.yoruc.mangacracker.video.VideoSources
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** "Test all sources": every enabled manga source and every video source, two at a time, cancellable. */
class SourceTestVM(app: Application) : AndroidViewModel(app) {
    val rows = mutableStateListOf<CheckRow>()
    var running by mutableStateOf(false); private set
    private var job: Job? = null

    init { start() }

    fun start() {
        job?.cancel()
        rows.clear()
        Sources.enabled().forEach { rows += CheckRow(it.id, it.name, false) }
        VideoSources.all.forEach { rows += CheckRow(it.id, it.name, true) }
        running = true
        job = viewModelScope.launch {
            // a few at a time so no site gets a burst (each source keeps its own rate limits too)
            val gate = Semaphore(2)
            rows.indices.map { i ->
                launch {
                    gate.withPermit {
                        val r = rows[i]
                        rows[i] = r.copy(status = CheckStatus.RUNNING)
                        val ctx = getApplication<Application>()
                        rows[i] = if (r.video) SourceCheck.video(ctx, VideoSources.byId(r.id)!!) else SourceCheck.manga(ctx, Sources.byId(r.id)!!)
                    }
                }
            }.forEach { it.join() }
            running = false
            lastResults = rows.toList()
        }
    }

    companion object {
        /** The finished table of the last run (the PC test prints it). */
        @Volatile var lastResults: List<CheckRow> = emptyList()
    }

    fun cancel() {
        job?.cancel(); running = false
        for (i in rows.indices) if (rows[i].status == CheckStatus.WAITING || rows[i].status == CheckStatus.RUNNING)
            rows[i] = rows[i].copy(status = CheckStatus.SKIPPED, note = "Cancelled")
    }
}

private fun statusLook(s: CheckStatus): Pair<String, Color> = when (s) {
    CheckStatus.WAITING -> "Waiting" to Mc.Text3
    CheckStatus.RUNNING -> "Checking" to Mc.Blue
    CheckStatus.OK -> "OK" to Mc.Green
    CheckStatus.SLOW -> "Slow" to Mc.Orange
    CheckStatus.VPN -> "Needs VPN" to Color(0xFFC063FF)
    CheckStatus.BROWSER -> "Browser check" to Mc.Orange
    CheckStatus.BROKEN -> "Broken" to Mc.Pink
    CheckStatus.SKIPPED -> "Skipped" to Mc.Text3
}

@Composable
fun SourceTestScreen(nav: Nav) {
    val vm: SourceTestVM = viewModel()
    val done = vm.rows.count { it.status != CheckStatus.WAITING && it.status != CheckStatus.RUNNING }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Mc.Text2) }
                Text("Test sources", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Mc.Text, modifier = Modifier.weight(1f))
            }
        }
        item {
            Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Mc.Card).padding(16.dp)) {
                Text(
                    "Opens each site's latest page, its first cover and one title (videos: list, cover, video page). Two sites at a time, so none gets hammered.",
                    color = Mc.Text3, fontSize = 12.5.sp,
                )
                Spacer(Modifier.height(10.dp))
                val count = { s: CheckStatus -> vm.rows.count { it.status == s } }
                Text(
                    listOfNotNull(
                        "Checked $done of ${vm.rows.size}",
                        count(CheckStatus.OK).takeIf { it > 0 }?.let { "$it OK" },
                        count(CheckStatus.SLOW).takeIf { it > 0 }?.let { "$it slow" },
                        count(CheckStatus.VPN).takeIf { it > 0 }?.let { "$it need a VPN" },
                        count(CheckStatus.BROWSER).takeIf { it > 0 }?.let { "$it want a browser check" },
                        count(CheckStatus.BROKEN).takeIf { it > 0 }?.let { "$it broken" },
                    ).joinToString(" · "),
                    color = Mc.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
                if (vm.running) LinearProgressIndicator(
                    progress = { if (vm.rows.isEmpty()) 0f else done / vm.rows.size.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = Mc.Blue, trackColor = Mc.Card2, drawStopIndicator = {},
                )
                Spacer(Modifier.height(12.dp))
                if (vm.running) Button(onClick = { vm.cancel() }, colors = ButtonDefaults.buttonColors(containerColor = Mc.Card2, contentColor = Mc.Text)) { Text("Cancel") }
                else Button(onClick = { vm.start() }, colors = ButtonDefaults.buttonColors(containerColor = Mc.Blue, contentColor = Mc.Bg)) { Text("Test again") }
            }
        }
        item { SectionHeader("Manga") }
        items(vm.rows.filter { !it.video }, key = { "m" + it.id }) { CheckRowView(it) }
        item { SectionHeader("Videos") }
        items(vm.rows.filter { it.video }, key = { "v" + it.id }) { CheckRowView(it) }
    }
}

@Composable
private fun CheckRowView(r: CheckRow) {
    val (label, color) = statusLook(r.status)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (r.status == CheckStatus.RUNNING) CircularProgressIndicator(color = Mc.Blue, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            else Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, color = Mc.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (r.ms > 0) Text("%.1f s".format(r.ms / 1000f), color = Mc.Text3, fontSize = 12.sp)
            }
            Text(
                listOf(label, r.note).filter { it.isNotEmpty() }.joinToString(" · "),
                color = if (r.status == CheckStatus.OK) Mc.Text2 else color, fontSize = 12.5.sp, maxLines = 3,
            )
        }
    }
}
