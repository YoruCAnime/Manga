package com.yoruc.mangacracker.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yoruc.mangacracker.ui.theme.Mc
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * In-window notes ("toasts") at the bottom of the app, with an optional action such as Undo. No pop-ups: the note
 * slides in over the screen and goes by itself.
 */
object Notes {
    data class Note(val text: String, val action: String?, val onAction: (() -> Unit)?, val ms: Long, val id: Long)

    val current = MutableStateFlow<Note?>(null)
    private var n = 0L

    fun show(text: String, action: String? = null, ms: Long = if (action != null) 6000L else 2800L, onAction: (() -> Unit)? = null) {
        current.value = Note(text, action, onAction, ms, ++n)
    }

    fun hide() { current.value = null }
}

@Composable
fun NoteHost(modifier: Modifier = Modifier) {
    val note by Notes.current.collectAsState()
    LaunchedEffect(note?.id) {
        val nt = note ?: return@LaunchedEffect
        delay(nt.ms)
        if (Notes.current.value?.id == nt.id) Notes.hide()
    }
    // keep the last text while it slides away
    val last = remember { arrayOfNulls<Notes.Note>(1) }
    if (note != null) last[0] = note
    AnimatedVisibility(note != null, modifier = modifier, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
        val nt = last[0] ?: return@AnimatedVisibility
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).widthIn(max = 560.dp).clip(RoundedCornerShape(12.dp)).background(Mc.Card2)
                .padding(start = 16.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(nt.text, color = Mc.Text, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false).padding(vertical = 10.dp))
            if (nt.action != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    nt.action, color = Mc.Blue, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { Notes.hide(); nt.onAction?.invoke() }.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            } else Spacer(Modifier.width(10.dp))
        }
    }
}
