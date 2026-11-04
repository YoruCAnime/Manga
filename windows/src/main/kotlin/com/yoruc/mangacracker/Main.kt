package com.yoruc.mangacracker

import android.content.res.Configuration
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.yoruc.mangacracker.core.Backup
import com.yoruc.mangacracker.core.Downloads
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.UpdateWorker
import com.yoruc.mangacracker.platform.Back
import com.yoruc.mangacracker.platform.Desktop
import com.yoruc.mangacracker.platform.Edge
import com.yoruc.mangacracker.platform.Files
import com.yoruc.mangacracker.core.DlState
import com.yoruc.mangacracker.platform.SelfUpdate
import com.yoruc.mangacracker.ui.AppRoot
import com.yoruc.mangacracker.ui.Here
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.yoruc.mangacracker.ui.theme.MangaCrackerTheme
import com.yoruc.mangacracker.video.VideoRepo
import okio.Path.Companion.toOkioPath
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.SystemTray
import java.awt.TrayIcon
import java.io.File

/** What App.onCreate does on the phone: storage, network, library, downloads, background chapter checks. */
private fun startUp() {
    val ctx = Desktop.ctx
    Http.init(ctx)
    Http.solver = { url -> Edge.solveCloudflare(url) }
    com.yoruc.mangacracker.platform.ComixEdge.install()
    // Edge instances the app started never outlive it (normal exit, crash, Windows sign-out)
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { Edge.close() } })
    Repo.init(ctx)
    VideoRepo.init(ctx)
    Downloads.init(ctx)
    if (Downloads.queue.hasWaiting()) Downloads.kick(ctx)
    UpdateWorker.schedule(ctx)
    Backup.autoIfDue(ctx)
    com.yoruc.mangacracker.platform.MemoryKeeper.start()
    SelfUpdate.start()
    // the last mouse / key use anywhere in the window (the update switch waits for a quiet moment)
    java.awt.Toolkit.getDefaultToolkit().addAWTEventListener({ Here.lastInput = System.currentTimeMillis() },
        java.awt.AWTEvent.MOUSE_EVENT_MASK or java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK or java.awt.AWTEvent.MOUSE_WHEEL_EVENT_MASK or java.awt.AWTEvent.KEY_EVENT_MASK)
}

/** "Update ready · Switch now": a newer copy is installed; the app also switches by itself at a quiet moment. */
@androidx.compose.runtime.Composable
private fun UpdateBar(modifier: Modifier, onSwitch: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier.padding(14.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(com.yoruc.mangacracker.ui.theme.Mc.Card2).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text("Update ready", color = com.yoruc.mangacracker.ui.theme.Mc.Text, fontSize = 13.sp)
        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        androidx.compose.material3.TextButton(onClick = onSwitch) {
            androidx.compose.material3.Text("Switch now", color = com.yoruc.mangacracker.ui.theme.Mc.Blue, fontSize = 13.sp)
        }
    }
}

/** The app icon (the phone app's), from the resources. */
private val iconImage: java.awt.image.BufferedImage? by lazy {
    runCatching { javax.imageio.ImageIO.read(object {}.javaClass.getResourceAsStream("/icon.png")) }.getOrNull()
}

/** Windows notifications from a tray icon (shown only while the app runs). */
private fun tray() {
    if (!SystemTray.isSupported()) return
    runCatching {
        val img = iconImage ?: return
        val icon = TrayIcon(img, "MangaCracker ${Desktop.version}").apply { isImageAutoSize = true }
        SystemTray.getSystemTray().add(icon)
        Desktop.notifier = { title, text -> icon.displayMessage(title, text, TrayIcon.MessageType.NONE) }
    }
}

/** Errors go to %LOCALAPPDATA%\MangaCracker\crash.log (never a pop-up box); a Windows note says where. */
private fun crashed(e: Throwable) {
    runCatching {
        File(Desktop.root, "crash.log").appendText("---- ${java.time.LocalDateTime.now()}\n${e.stackTraceToString()}\n")
    }
    if (System.getenv("MC_OFFSCREEN") != "1") Desktop.notify("MangaCracker stopped", "The error is in crash.log in the app's data folder")
    Thread.sleep(300)
    kotlin.system.exitProcess(1)
}

@OptIn(ExperimentalComposeUiApi::class)
fun main() = try {
    Thread.setDefaultUncaughtExceptionHandler { _, e -> crashed(e) }
    startUp()
    tray()
    run()
} catch (e: Throwable) { crashed(e) }

@OptIn(ExperimentalComposeUiApi::class)
private fun run() = application {
        // pictures: a small memory cache (the heap is capped), a big disk cache, decoded at the size they are shown
        setSingletonImageLoaderFactory { pc ->
            ImageLoader.Builder(pc)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { Http.imageClient })); add(com.yoruc.mangacracker.platform.Avif.CoilDecoder.Factory()) }
                .memoryCache { MemoryCache.Builder().maxSizeBytes(48L * 1024 * 1024).build() }
                .diskCache { DiskCache.Builder().directory(File(Desktop.ctx.cacheDir, "images").toOkioPath()).maxSizeBytes(600L * 1024 * 1024).build() }
                .crossfade(true)
                .build()
        }
        // MC_OFFSCREEN=1: tests run the real window far off the screen, never taking the focus
        val off = System.getenv("MC_OFFSCREEN") == "1"
        // after an update switch: the same place, size and state as the copy before
        val h = SelfUpdate.handoff
        val state = if (h != null && !off) rememberWindowState(
            placement = if (h.maximized) androidx.compose.ui.window.WindowPlacement.Maximized else androidx.compose.ui.window.WindowPlacement.Floating,
            isMinimized = h.minimized, position = WindowPosition(h.x.dp, h.y.dp), size = DpSize(h.w.dp, h.h.dp))
        else rememberWindowState(size = DpSize(1280.dp, 860.dp),
            position = if (off) WindowPosition.Absolute((-6000).dp, (-6000).dp) else WindowPosition.Aligned(androidx.compose.ui.Alignment.Center))
        CompositionLocalProvider(androidx.compose.ui.window.LocalWindowExceptionHandlerFactory provides
            androidx.compose.ui.window.WindowExceptionHandlerFactory { _ -> androidx.compose.ui.window.WindowExceptionHandler { e -> crashed(e) } }) {
        Window(
            focusable = !off,
            onCloseRequest = { Edge.close(); exitApplication() },
            state = state,
            title = "MangaCracker ${Desktop.version}",
            icon = iconImage?.let { androidx.compose.ui.graphics.painter.BitmapPainter(it.toComposeImageBitmap()) },
            onPreviewKeyEvent = { e ->
                // Esc and Alt+Left go back; F11 full screen
                if (e.type == KeyEventType.KeyDown && e.key == Key.F11) {
                    state.placement = if (state.placement == androidx.compose.ui.window.WindowPlacement.Fullscreen)
                        androidx.compose.ui.window.WindowPlacement.Floating else androidx.compose.ui.window.WindowPlacement.Fullscreen
                    true
                } else if (e.type == KeyEventType.KeyDown && (e.key == Key.Escape || (e.isAltPressed && e.key == Key.DirectionLeft))) {
                    if (e.key == Key.Escape && state.placement == androidx.compose.ui.window.WindowPlacement.Fullscreen)
                        state.placement = androidx.compose.ui.window.WindowPlacement.Floating
                    else Back.press()
                    true
                } else false
            },
        ) {
            Files.owner = window
            Desktop.fullscreen = {
                state.placement = if (state.placement == androidx.compose.ui.window.WindowPlacement.Fullscreen)
                    androidx.compose.ui.window.WindowPlacement.Floating else androidx.compose.ui.window.WindowPlacement.Fullscreen
            }
            window.minimumSize = java.awt.Dimension(560, 520)
            // a newer copy installed next to this one: switch at a quiet moment (or now, from the bar)
            val ready by SelfUpdate.ready.collectAsState()
            val switchScope = androidx.compose.runtime.rememberCoroutineScope()
            var switching by remember { mutableStateOf(false) }
            fun switchNow() {
                val exe = ready ?: return
                if (switching) return
                val pos = state.position
                val hand = SelfUpdate.Handoff(
                    x = if (pos is WindowPosition.Absolute) pos.x.value.toInt() else window.x, y = if (pos is WindowPosition.Absolute) pos.y.value.toInt() else window.y,
                    w = state.size.width.value.toInt(), h = state.size.height.value.toInt(),
                    maximized = state.placement == androidx.compose.ui.window.WindowPlacement.Maximized, minimized = state.isMinimized,
                    route = Here.route, key = Here.key)
                switching = true
                switchScope.launch {
                    if (SelfUpdate.switchTo(exe, hand)) { Edge.close(); exitApplication() }
                    else Desktop.toast("The update didn't start, so this version stays open")
                    switching = false
                }
            }
            LaunchedEffect(ready) {
                // tests (off-screen): MC_SWITCH_NOW=1 switches as soon as the newer copy is there
                if (ready != null && System.getenv("MC_SWITCH_NOW") == "1") { delay(1_000); switchNow(); return@LaunchedEffect }
                if (ready == null || off) return@LaunchedEffect
                while (true) {
                    delay(5_000)
                    // never while reading, watching (player, recording, GIF editor), signing in or downloading
                    val busy = Here.route.startsWith("reader/") || Here.route.startsWith("player/") || Here.route.startsWith("web") ||
                        Here.route == "exlogin" || Here.route == "sourcetest" ||
                        Downloads.queue.tasks.value.any { it.state == DlState.RUNNING || it.state == DlState.QUEUED }
                    if (busy) continue
                    // minimized: switch unseen (the new copy starts minimized too); in front: only after 3 untouched minutes.
                    // Behind other windows: wait (a new window could come up over them)
                    val idle = System.currentTimeMillis() - Here.lastInput
                    if (state.isMinimized || (window.isFocused && idle > 180_000)) { switchNow(); return@LaunchedEffect }
                }
            }
            MangaCrackerTheme {
                BoxWithConstraints(Modifier.onPointerEvent(PointerEventType.Press) { if (it.buttons.isBackPressed) Back.press() }) {
                    CompositionLocalProvider(LocalConfiguration provides Configuration(maxWidth.value.toInt(), maxHeight.value.toInt())) {
                        AppRoot(System.getenv("MC_OPEN"))
                    }
                    if (ready != null) UpdateBar(Modifier.align(androidx.compose.ui.Alignment.BottomEnd)) { switchNow() }
                }
            }
        }
        }
}
