package com.yoruc.mangacracker.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yoruc.mangacracker.core.OpenTab
import com.yoruc.mangacracker.core.Tabs
import com.yoruc.mangacracker.ui.theme.Mc

/** Tells what adding a tab did. */
fun tabAdded(ctx: Context, t: OpenTab?) {
    Toast.makeText(ctx, if (t == null) "That's ${Tabs.MAX} tabs: close one first" else "Added to tabs", Toast.LENGTH_SHORT).show()
}

/** The number of open tabs in a little box, for the reader's and the player's top bars. */
@Composable
fun TabsButton(tint: Color = Mc.Text) {
    val list by Tabs.list.collectAsState()
    IconButton(onClick = { Tabs.sheetOpen.value = true }, modifier = Modifier.semantics { contentDescription = "Open tabs" }) { TabsBox(list.size, tint) }
}

@Composable
private fun TabsBox(n: Int, tint: Color) {
    Box(Modifier.size(22.dp).border(1.6.dp, tint, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
        Text(if (n > 99) "99" else n.toString(), color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** The same button floating over the other screens (only while tabs are open). */
@Composable
fun TabsPill(modifier: Modifier = Modifier) {
    val list by Tabs.list.collectAsState()
    if (list.isEmpty()) return
    Box(
        modifier.size(44.dp).clip(CircleShape).background(Mc.Card2).clickable { Tabs.sheetOpen.value = true }.semantics { contentDescription = "Open tabs" },
        contentAlignment = Alignment.Center,
    ) { TabsBox(list.size, Mc.Blue) }
}

/** The list of open tabs: tap one to go there, swipe it away or press X to close it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabsSheet(nav: Nav) {
    val open by Tabs.sheetOpen.collectAsState()
    if (!open) return
    val list by Tabs.list.collectAsState()
    val active by Tabs.active.collectAsState()
    val here by Tabs.here.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun hide() { Tabs.sheetOpen.value = false }
    ModalBottomSheet(onDismissRequest = { hide() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Mc.Card) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Open tabs (${list.size})", color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                if (list.isNotEmpty()) Text("Close all", color = Mc.Text3, fontSize = 13.sp, modifier = Modifier.clickable { hide(); nav.closeAllTabs() }.padding(8.dp))
            }
            if (here != null && active == 0L) Text(
                "+ Keep this page as a tab", color = Mc.Blue, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().clickable { tabAdded(ctx, Tabs.pinHere()) }.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            if (list.isEmpty()) Text(
                "Hold down on a cover, video or chapter and choose Open in new tab.", color = Mc.Text3, fontSize = 13.5.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 18.dp),
            )
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                items(list, key = { it.id }) { t -> TabRow(t, t.id == active, nav) { hide() } }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabRow(t: OpenTab, on: Boolean, nav: Nav, hide: () -> Unit) {
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v != SwipeToDismissBoxValue.Settled) { nav.closeTab(t); true } else false
    })
    SwipeToDismissBox(swipe, backgroundContent = { Box(Modifier.fillMaxSize().background(Mc.Bg)) }) {
        Row(
            Modifier.fillMaxWidth().background(if (on) Mc.Blue.copy(alpha = 0.14f) else Mc.Card).clickable { hide(); nav.openTab(t) }
                .padding(start = 16.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start,
        ) {
            Box(Modifier.size(width = 42.dp, height = 58.dp).clip(RoundedCornerShape(5.dp)).background(Mc.Card2)) {
                val v = t.video
                if (v != null) VideoCover(v, Modifier.fillMaxSize()) else Cover(t.cover, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, color = if (on) Mc.Blue else Mc.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val sub = Tabs.subtitle(t)
                if (sub.isNotEmpty()) Text(sub, color = Mc.Text3, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { nav.closeTab(t) }) { Icon(Icons.Rounded.Close, "Close tab", tint = Mc.Text3) }
        }
    }
}
