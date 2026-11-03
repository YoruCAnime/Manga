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
import com.yoruc.mangacracker.ui.AppRoot
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
        val icon = TrayIcon(img, "MangaCracker").apply { isImageAutoSize = true }
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
        val state = rememberWindowState(size = DpSize(1280.dp, 860.dp),
            position = if (off) WindowPosition.Absolute((-6000).dp, (-6000).dp) else WindowPosition.Aligned(androidx.compose.ui.Alignment.Center))
        CompositionLocalProvider(androidx.compose.ui.window.LocalWindowExceptionHandlerFactory provides
            androidx.compose.ui.window.WindowExceptionHandlerFactory { _ -> androidx.compose.ui.window.WindowExceptionHandler { e -> crashed(e) } }) {
        Window(
            focusable = !off,
            onCloseRequest = { Edge.close(); exitApplication() },
            state = state,
            title = "MangaCracker",
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
            MangaCrackerTheme {
                BoxWithConstraints(Modifier.onPointerEvent(PointerEventType.Press) { if (it.buttons.isBackPressed) Back.press() }) {
                    CompositionLocalProvider(LocalConfiguration provides Configuration(maxWidth.value.toInt(), maxHeight.value.toInt())) {
                        AppRoot(System.getenv("MC_OPEN"))
                    }
                }
            }
        }
        }
}
