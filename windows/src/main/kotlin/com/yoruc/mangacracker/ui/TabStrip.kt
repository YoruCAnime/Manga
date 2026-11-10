package com.yoruc.mangacracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.yoruc.mangacracker.core.OpenTab
import com.yoruc.mangacracker.core.Tabs
import com.yoruc.mangacracker.platform.Desktop
import com.yoruc.mangacracker.ui.theme.Mc

/** The window's keys for tabs. [nav] is set by AppRoot. */
object TabKeys {
    var nav: Nav? = null
    /** Full screen: the strip steps aside. */
    var fullscreen by mutableStateOf(false)
    private val numbers = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

    /** Ctrl+1 to 9, Ctrl+Tab (Shift: back), Ctrl+W closes, Ctrl+T keeps the page as a tab. */
    fun handle(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return false
        val n = nav ?: return false
        val list = Tabs.list.value
        val i = numbers.indexOf(e.key)
        return when {
            i >= 0 -> list.getOrNull(i)?.let { n.openTab(it); true } ?: false
            e.key == Key.Tab -> Tabs.step(if (e.isShiftPressed) -1 else 1)?.let { n.openTab(it); true } ?: false
            e.key == Key.W -> list.firstOrNull { it.id == Tabs.active.value }?.let { n.closeTab(it); true } ?: false
            e.key == Key.T -> Tabs.here.value != null && run { added(Tabs.pinHere()); true }
            else -> false
        }
    }
}

/** What adding a tab says when it can't. */
fun added(t: OpenTab?) { if (t == null) Desktop.toast("That's ${Tabs.MAX} tabs: close one first") }

/** The right-click menu: where it is (window pixels, null = closed) and what its one line does. */
object TabMenu {
    var at by mutableStateOf<Offset?>(null)
    var act: () -> Unit = {}
}

/**
 * Ctrl+click or middle click = [open] instead of the normal click; a right click ([menu]) offers it as
 * "Open in new tab". Goes before the clickable in the chain.
 */
@Composable
fun Modifier.tabClick(menu: Boolean = true, open: () -> Unit): Modifier {
    val act by rememberUpdatedState(open)
    val place = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return onGloballyPositioned { place[0] = it }.pointerInput(menu) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(PointerEventPass.Initial)
                if (e.type != PointerEventType.Press) continue
                if (menu && e.buttons.isSecondaryPressed) {
                    val spot = e.changes.first().position
                    e.changes.forEach { it.consume() }
                    place[0]?.takeIf { it.isAttached }?.let { TabMenu.act = { act() }; TabMenu.at = it.localToWindow(spot) }
                } else if (e.buttons.isTertiaryPressed || (e.buttons.isPrimaryPressed && e.keyboardModifiers.isCtrlPressed)) {
                    e.changes.forEach { it.consume() }
                    act()
                }
            }
        }
    }
}

/** Draws the right-click menu; once, at the top of the app. */
@Composable
fun TabMenuHost() {
    val spot = TabMenu.at ?: return
    Popup(
        popupPositionProvider = object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) = IntOffset(
                spot.x.toInt().coerceAtMost(windowSize.width - popupContentSize.width).coerceAtLeast(0),
                spot.y.toInt().coerceAtMost(windowSize.height - popupContentSize.height).coerceAtLeast(0),
            )
        },
        onDismissRequest = { TabMenu.at = null },
        properties = PopupProperties(focusable = true),
    ) {
        Text(
            "Open in new tab", color = Mc.Text, fontSize = 13.5.sp,
            modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Mc.Card2).clickable { TabMenu.at = null; TabMenu.act() }.padding(horizontal = 14.dp, vertical = 9.dp),
        )
    }
}

/** The strip over the content: every open tab, the way home, and "keep this page". */
@Composable
fun TabStrip(nav: Nav) {
    val list by Tabs.list.collectAsState()
    val active by Tabs.active.collectAsState()
    val here by Tabs.here.collectAsState()
    if (list.isEmpty() && here == null) return
    val row = rememberLazyListState()
    LaunchedEffect(active) { list.indexOfFirst { it.id == active }.takeIf { it >= 0 }?.let { row.animateScrollToItem(it) } }
    Row(Modifier.fillMaxWidth().height(38.dp).background(Mc.Card), verticalAlignment = Alignment.CenterVertically) {
        if (here != null) Box(Modifier.size(38.dp).clickable { nav.home() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Home, "Home", tint = Mc.Text2, modifier = Modifier.size(19.dp))
        }
        LazyRow(Modifier.weight(1f, fill = false).fillMaxHeight(), state = row, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            items(list, key = { it.id }) { t -> TabChip(t, t.id == active, nav) }
        }
        if (here != null && active == 0L) Row(
            Modifier.padding(horizontal = 6.dp).clip(RoundedCornerShape(6.dp)).clickable { added(Tabs.pinHere()) }.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Rounded.Add, null, tint = Mc.Blue, modifier = Modifier.size(16.dp))
            Text("Keep as tab", color = Mc.Blue, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun TabChip(t: OpenTab, on: Boolean, nav: Nav) {
    Row(
        Modifier.fillMaxHeight().widthIn(min = 110.dp, max = 210.dp).padding(top = 4.dp)
            .clip(RoundedCornerShape(topStart = 7.dp, topEnd = 7.dp)).background(if (on) Mc.Bg else Mc.Card2)
            .tabClick(menu = false) { nav.closeTab(t) }.clickable { nav.openTab(t) }.padding(start = 5.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (t.isPlace) Icon(if (t.route == Tabs.VIDEOS) Icons.Rounded.SmartDisplay else Icons.Rounded.Explore, null, tint = Mc.Blue, modifier = Modifier.size(18.dp))
        else Box(Modifier.size(width = 18.dp, height = 25.dp).clip(RoundedCornerShape(3.dp)).background(Mc.Card)) {
            val v = t.video
            if (v != null) VideoCover(v, Modifier.fillMaxSize()) else Cover(t.cover, Modifier.fillMaxSize())
        }
        Text(
            t.title, color = if (on) Mc.Text else Mc.Text2, fontSize = 12.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).padding(start = 6.dp, end = 3.dp),
        )
        Box(Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).clickable { nav.closeTab(t) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, "Close tab", tint = Mc.Text3, modifier = Modifier.size(14.dp))
        }
        Box(Modifier.width(1.dp))
    }
}
