package com.yoruc.mangacracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yoruc.mangacracker.core.Backup
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.UpdateWorker
import com.yoruc.mangacracker.ui.theme.Mc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** PC tests: the text of a "picked" backup file (the system picker can't run there). */
object BackupTestHooks { var openText: (() -> String)? = null }

private fun whenText(ms: Long): String = if (ms <= 0) "an unknown date" else
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))

/** Settings › Backup & restore: one file to Downloads (or a place you pick), restore with a preview, weekly backup. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupCard() {
    val ctx = LocalContext.current
    val s by Repo.settings.collectAsState()
    val scope = rememberCoroutineScope()
    /** In-window result line: text + ok (green) or not (pink). */
    var note by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Backup.Contents?>(null) }
    var replaceArmed by remember { mutableStateOf(false) }

    fun work(block: suspend () -> String) {
        if (busy) return
        busy = true; note = null
        scope.launch {
            runCatching { block() }
                .onSuccess { note = it to true }
                .onFailure { note = (it.message ?: "Something went wrong") to false }
            busy = false
        }
    }
    fun load(read: suspend () -> String) {
        if (busy) return
        busy = true; note = null; preview = null
        scope.launch {
            runCatching { withContext(Dispatchers.Default) { Backup.read(read()) } }
                .onSuccess { preview = it; replaceArmed = false }
                .onFailure { note = (it.message ?: "Couldn't read that file") to false }
            busy = false
        }
    }
    suspend fun text() = withContext(Dispatchers.Default) { Backup.export() }

    // Windows' own Save / Open boxes
    class Picker(val go: (String) -> Unit) { fun launch(arg: Any) = go(arg.toString()) }
    val saveAs = Picker { name ->
        com.yoruc.mangacracker.platform.Files.save("Save backup", name)?.let { f -> work { Backup.writeTo(ctx, f, text()); "Backup saved" } }
    }
    val open = Picker { _ ->
        com.yoruc.mangacracker.platform.Files.open("Open a backup", "*.json")?.let { f -> load { Backup.readFrom(ctx, f) } }
    }

    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Mc.Card).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Back up this phone's reading", color = Mc.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                "Library, history, reading progress, settings, favourite videos and resume points in one file. Logins and API keys are left out.",
                color = Mc.Text3, fontSize = 12.5.sp,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { work { "Saved to " + Backup.saveToDownloads(ctx, text(), Backup.fileName()) } },
                    colors = ButtonDefaults.buttonColors(containerColor = Mc.Blue, contentColor = Mc.Bg)) { Text("Back up now") }
                Button(enabled = !busy, onClick = { saveAs.launch(Backup.fileName()) },
                    colors = ButtonDefaults.buttonColors(containerColor = Mc.Card2, contentColor = Mc.Text)) { Text("Save to…") }
                Button(enabled = !busy, onClick = {
                    val hook = BackupTestHooks.openText
                    if (hook != null) load { hook() } else open.launch(arrayOf("*/*"))
                }, colors = ButtonDefaults.buttonColors(containerColor = Mc.Card2, contentColor = Mc.Text)) { Text("Restore…") }
            }
            note?.let { (t, ok) -> Text(t, color = if (ok) Mc.Green else Mc.Pink, fontSize = 13.sp) }
            SwitchRow(
                "Weekly backup", s.autoBackup,
                sub = "To Download/MangaCracker, keeps the last 4" + if (s.lastAutoBackup > 0) " · last ${timeAgo(s.lastAutoBackup)}" else "",
            ) { v ->
                Repo.updateSettings { it.copy(autoBackup = v) }
                if (v) Backup.autoIfDue(ctx)
            }
        }

        preview?.let { c ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Mc.Card).border(1.dp, Mc.BlueSoft, RoundedCornerShape(10.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Restore this backup?", color = Mc.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("Made ${whenText(c.created)}${if (c.appVersion.isNotEmpty()) " · MangaCracker ${c.appVersion}" else ""}", color = Mc.Text3, fontSize = 12.5.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CountChip(c.library.size, "in library")
                    CountChip(c.history.size, "titles read")
                    CountChip(c.chaptersRead, "chapters done")
                    CountChip(c.watch.size, "videos watched")
                    CountChip(c.favs.size, "favourite videos")
                    if (c.settings != null) CountChip(null, "settings")
                }
                if (c.newer) Text("Made by a newer MangaCracker: parts this version doesn't know are left out.", color = Mc.Orange, fontSize = 12.5.sp)
                if (c.skipped > 0) Text("${c.skipped} entr${if (c.skipped == 1) "y" else "ies"} couldn't be read and will be left out.", color = Mc.Orange, fontSize = 12.5.sp)
                if (c.isEmpty) Text("This backup is empty.", color = Mc.Text2, fontSize = 13.sp)
                Text(
                    "Merge adds everything; where both have the same title, chapter or video, the newer one wins. Replace swaps this phone's reading for the backup's. Logins and keys stay as they are.",
                    color = Mc.Text3, fontSize = 12.sp,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !c.isEmpty, onClick = {
                        Backup.apply(c, Backup.Mode.MERGE); preview = null; runCatching { UpdateWorker.schedule(ctx) }
                        note = "Restored: merged ${c.library.size} library titles, ${c.history.size} read titles, ${c.watch.size + c.favs.size} videos" to true
                    }, colors = ButtonDefaults.buttonColors(containerColor = Mc.Blue, contentColor = Mc.Bg)) { Text("Merge") }
                    Button(enabled = !c.isEmpty, onClick = {
                        if (!replaceArmed) { replaceArmed = true; return@Button }
                        Backup.apply(c, Backup.Mode.REPLACE); preview = null; runCatching { UpdateWorker.schedule(ctx) }
                        note = "Restored: replaced with the backup (${c.library.size} library titles, ${c.history.size} read titles)" to true
                    }, colors = ButtonDefaults.buttonColors(containerColor = if (replaceArmed) Mc.Pink else Mc.Card2, contentColor = if (replaceArmed) Color.White else Mc.Text)) {
                        Text(if (replaceArmed) "Tap again to replace" else "Replace")
                    }
                    TextButton(onClick = { preview = null }) { Text("Cancel", color = Mc.Text2) }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun CountChip(n: Int?, label: String) {
    Text(
        if (n == null) label else "$n $label", color = if (n == 0) Mc.Text3 else Mc.Text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Mc.Card2).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
