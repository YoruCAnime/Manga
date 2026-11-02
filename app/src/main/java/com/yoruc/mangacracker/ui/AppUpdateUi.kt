package com.yoruc.mangacracker.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yoruc.mangacracker.core.AppRelease
import com.yoruc.mangacracker.core.AppUpdate
import com.yoruc.mangacracker.core.AppUpdateState
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.ui.theme.Mc

private fun mb(bytes: Long) = if (bytes <= 0) "" else "%.1f MB".format(bytes / 1048576.0)

/** GitHub release notes (Markdown) as plain readable lines. */
fun plainNotes(md: String): String = md.lines().joinToString("\n") { l ->
    l.trimEnd().replace("**", "").replace("`", "").replace(Regex("^#+\\s*"), "").replace(Regex("^\\s*[-*]\\s+"), "• ")
}.trim()

/** The main button for the current state ("Update now", "Install", "Try again"), or null while busy. */
@Composable
private fun UpdateButton(st: AppUpdateState, rel: AppRelease, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val label = when (st) {
        is AppUpdateState.Ready -> "Install"
        is AppUpdateState.Failed -> "Try again"
        is AppUpdateState.Downloading, AppUpdateState.Checking -> null
        else -> "Update now"
    } ?: return
    Button(
        onClick = {
            if (st is AppUpdateState.Ready && st.file.exists()) runCatching { AppUpdate.install(ctx, st.file) }
            else AppUpdate.downloadAndInstall(ctx, rel)
        },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = Mc.Blue, contentColor = Mc.Bg),
        shape = RoundedCornerShape(10.dp),
    ) { Text(label, fontWeight = FontWeight.Bold) }
}

@Composable
private fun DownloadBar(st: AppUpdateState) {
    if (st is AppUpdateState.Downloading) {
        Column {
            LinearProgressIndicator(
                progress = { st.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = Mc.Blue, trackColor = Mc.Card2,
            )
            Spacer(Modifier.height(6.dp))
            Text("Downloading… ${(st.progress * 100).toInt()}%", color = Mc.Text2, fontSize = 12.5.sp)
        }
    }
    if (st is AppUpdateState.Checking) LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)), color = Mc.Blue, trackColor = Mc.Card2)
}

/** The "Update ready" pop-up. "Later" hides it for this version; the update stays in Settings. */
@Composable
fun AppUpdatePopup(onClose: () -> Unit) {
    val st by AppUpdate.state.collectAsState()
    val rel = st.release ?: return
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 24.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Mc.Card).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Mc.Blue.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.SystemUpdate, null, tint = Mc.Blue)
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    val title = when (st) { is AppUpdateState.Downloading -> "Downloading update"; is AppUpdateState.Ready -> "Ready to install"; else -> "Update ready" }
                    Text(title, color = Mc.Text, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
                    Text("${AppUpdate.current}  →  ${rel.version}" + mb(rel.size).let { if (it.isEmpty()) "" else "  ·  $it" }, color = Mc.Text2, fontSize = 13.sp)
                }
            }
            val notes = plainNotes(rel.notes)
            if (notes.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).clip(RoundedCornerShape(10.dp)).background(Mc.Bg).padding(12.dp)) {
                    Text(notes, color = Mc.Text2, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.verticalScroll(rememberScrollState()))
                }
            }
            DownloadBar(st)
            if (st is AppUpdateState.Failed) Text((st as AppUpdateState.Failed).message, color = Mc.Pink, fontSize = 13.sp)
            if (st is AppUpdateState.Ready) Text("Android asks once to allow installs from MangaCracker; your library and reading spots stay.", color = Mc.Text3, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text(if (st is AppUpdateState.Downloading) "Hide" else "Later", color = Mc.Text2) }
                Spacer(Modifier.width(8.dp))
                UpdateButton(st, rel)
            }
        }
    }
}

/** Settings → App updates. */
@Composable
fun AppUpdateCard() {
    val ctx = LocalContext.current
    val st by AppUpdate.state.collectAsState()
    val s by Repo.settings.collectAsState()
    val rel = st.release
    var showNotes by remember { mutableStateOf(false) }
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Mc.Card).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SystemUpdate, null, tint = if (rel != null) Mc.Pink else Mc.Blue)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("MangaCracker ${AppUpdate.current}", color = Mc.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                val line = when (val x = st) {
                    AppUpdateState.Checking -> "Checking GitHub…"
                    is AppUpdateState.UpToDate -> "Up to date · checked ${timeAgo(x.at)}"
                    is AppUpdateState.Available -> "Version ${x.rel.version} is ready" + mb(x.rel.size).let { if (it.isEmpty()) "" else " · $it" }
                    is AppUpdateState.Downloading -> "Downloading ${x.rel.version}"
                    is AppUpdateState.Ready -> "Version ${x.rel.version} downloaded · tap Install"
                    is AppUpdateState.Failed -> x.message
                    AppUpdateState.Idle -> if (s.lastAppCheck > 0) "Up to date · checked ${timeAgo(s.lastAppCheck)}" else "Not checked yet"
                }
                Text(line, color = if (st is AppUpdateState.Failed) Mc.Pink else Mc.Text3, fontSize = 12.5.sp)
            }
        }
        DownloadBar(st)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (rel != null) UpdateButton(st, rel)
            Button(
                onClick = { AppUpdate.checkInBackground(force = true) },
                enabled = st !is AppUpdateState.Checking && st !is AppUpdateState.Downloading,
                colors = ButtonDefaults.buttonColors(containerColor = Mc.Card2, contentColor = Mc.Text),
                shape = RoundedCornerShape(10.dp),
            ) { Text("Check now") }
        }
        if (rel != null && rel.notes.isNotBlank()) {
            Text(if (showNotes) "Hide what's new" else "What's new in ${rel.version}", color = Mc.Blue, fontSize = 13.5.sp, modifier = Modifier.clickable { showNotes = !showNotes })
            if (showNotes) Text(plainNotes(rel.notes), color = Mc.Text2, fontSize = 13.sp, lineHeight = 19.sp)
        }
        SwitchRow("Check for app updates automatically", s.autoAppUpdates, sub = "Pop-up when a new version is out (Later keeps it here)") { v ->
            Repo.updateSettings { it.copy(autoAppUpdates = v) }
        }
        Text("All versions on GitHub", color = Mc.Text3, fontSize = 12.5.sp, modifier = Modifier.clickable {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdate.RELEASES)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        })
    }
}
