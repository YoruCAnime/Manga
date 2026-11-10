package com.yoruc.mangacracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yoruc.mangacracker.core.Ambient
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.platform.Files
import com.yoruc.mangacracker.ui.theme.Mc
import java.io.File
import kotlin.math.roundToInt

/** "Sounds while reading": off / the built-in loops / your own audio file, and the volume. Changes apply at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SoundPicker(inReader: Boolean) {
    val s by Repo.settings.collectAsState()
    val err by Ambient.error.collectAsState()
    // Windows' Open box (in place of Android's document picker): the settings keep the file's path
    val pick = {
        val old = s.readerSoundUri.takeIf { it.isNotEmpty() }?.let { File(it).parentFile }
        Files.open("Choose a sound to play while reading", "*.mp3;*.m4a;*.ogg;*.opus;*.wav;*.flac;*.aac", old)?.let { f ->
            Repo.updateSettings { it.copy(readerSound = Ambient.OWN, readerSoundUri = f.absolutePath, readerSoundName = f.nameWithoutExtension.take(28)) }
        }
    }
    Column {
        Text("Sounds while reading", color = Mc.Text, fontSize = 15.sp)
        Text(
            if (inReader) "A quiet loop under the pages. It fades out when you leave the reader." else "A quiet loop that plays while you read (the 🎧 button in the reader too)",
            color = Mc.Text3, fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterPill("Off", s.readerSound == "") { Repo.updateSettings { it.copy(readerSound = "") } }
            Ambient.sounds.forEach { snd ->
                FilterPill(snd.name, s.readerSound == snd.id) { Repo.updateSettings { it.copy(readerSound = snd.id) } }
            }
            FilterPill(if (s.readerSoundUri.isNotEmpty()) "♪ " + s.readerSoundName.ifEmpty { "My sound" } else "My own sound…", s.readerSound == Ambient.OWN) {
                if (s.readerSoundUri.isEmpty()) pick() else Repo.updateSettings { it.copy(readerSound = Ambient.OWN) }
            }
        }
        if (s.readerSoundUri.isNotEmpty()) {
            Text(
                "Choose another file", color = Mc.Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp).clickable { pick() }
            )
        }
        if (err != null) Text(err!!, color = Mc.Pink, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        if (s.readerSound != "") {
            Spacer(Modifier.height(10.dp))
            Text("Sound volume · ${(s.readerSoundVolume * 100).roundToInt()}%", color = Mc.Text2, fontSize = 13.sp)
            Slider(
                s.readerSoundVolume, { v -> Repo.updateSettings { it.copy(readerSoundVolume = (v * 20).roundToInt() / 20f) } }, valueRange = 0.05f..1f,
                colors = SliderDefaults.colors(thumbColor = Mc.Blue, activeTrackColor = Mc.Blue, inactiveTrackColor = Mc.Card2),
            )
        }
    }
}
