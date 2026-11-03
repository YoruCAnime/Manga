package com.yoruc.mangacracker.platform

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.awt.Desktop as AwtDesktop
import java.io.File

/**
 * What the Windows app has instead of Android's services: where files live, a scope for background work (it lives as
 * long as the app), in-window toasts, Windows notifications from the tray icon, and the folders pictures are saved in.
 */
object Desktop {
    /** %LOCALAPPDATA%\MangaCracker (or ~/.mangacracker elsewhere) */
    val root: File = File(System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: (System.getProperty("user.home") + "/.local/share"), "MangaCracker")
        .apply { mkdirs() }

    val ctx: Context by lazy { Context(root) }

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Pictures\MangaCracker */
    val pictures: File get() = File(System.getProperty("user.home"), "Pictures/MangaCracker").apply { mkdirs() }

    /** Videos\MangaCracker */
    val videos: File get() = File(System.getProperty("user.home"), "Videos/MangaCracker").apply { mkdirs() }

    /** Where Record clips go: the folder chosen in Settings, else Videos\MangaCracker. */
    val clips: File get() = com.yoruc.mangacracker.core.Repo.settings.value.clipFolder.takeIf { it.isNotBlank() }
        ?.let { File(it) }?.takeIf { it.isDirectory || it.mkdirs() } ?: videos

    /** Where player snapshots go: the folder chosen in Settings, else Pictures\MangaCracker\Snapshots. */
    val snapshots: File get() = com.yoruc.mangacracker.core.Repo.settings.value.snapshotFolder.takeIf { it.isNotBlank() }
        ?.let { File(it) }?.takeIf { it.isDirectory || it.mkdirs() } ?: File(pictures, "Snapshots").apply { mkdirs() }

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Short messages shown at the bottom of the window (no pop-up windows). */
    val toasts = _toasts.asSharedFlow()
    fun toast(text: String) { _toasts.tryEmit(text) }

    /** Set by the window: full screen on / off (F11, the player's button). */
    @Volatile var fullscreen: () -> Unit = {}
    fun toggleFullscreen() = fullscreen()

    /** Set by the window: shows a Windows notification from the tray icon. */
    @Volatile var notifier: ((title: String, text: String) -> Unit)? = null
    fun notify(title: String, text: String) { runCatching { notifier?.invoke(title, text) } }

    /** Shows a file or folder in Explorer. */
    fun reveal(f: File) {
        runCatching {
            if (f.isFile) ProcessBuilder("explorer.exe", "/select,", f.absolutePath).start()
            else AwtDesktop.getDesktop().open(f)
        }
    }

    fun openUrl(url: String) { runCatching { AwtDesktop.getDesktop().browse(java.net.URI(url)) } }

    fun clean(s: String, fallback: String = "untitled") =
        s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(80).trimEnd('.', ' ').ifEmpty { fallback }

    /** name.ext in [dir], with " (2)", " (3)" ... when taken. */
    fun freeFile(dir: File, name: String, ext: String): File {
        var f = File(dir, "$name.$ext"); var n = 2
        while (f.exists()) { f = File(dir, "$name ($n).$ext"); n++ }
        return f
    }
}
