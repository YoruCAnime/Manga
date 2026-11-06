package com.yoruc.mangacracker.platform

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.DoubleByReference
import java.io.File

/**
 * mpv (libmpv-2.dll) drawn into a panel of the app window ("wid"). Found next to the app (resources), in the app's
 * data folder, or in Reaction Watcher's folder. Only the calls the player needs.
 */
object Mpv {
    @Suppress("FunctionName")
    interface Lib : Library {
        fun mpv_create(): Pointer?
        fun mpv_initialize(ctx: Pointer): Int
        fun mpv_terminate_destroy(ctx: Pointer)
        fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
        fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
        fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
        fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: DoubleByReference): Int
        fun mpv_command(ctx: Pointer, args: Array<String?>): Int
        fun mpv_free(data: Pointer)
        fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
        fun mpv_error_string(error: Int): String
    }

    /** mpv_event: id, error, reply, data */
    class Event(p: Pointer) : Structure(p) {
        @JvmField var event_id: Int = 0
        @JvmField var error: Int = 0
        @JvmField var reply_userdata: Long = 0
        @JvmField var data: Pointer? = null
        init { read() }
        override fun getFieldOrder() = listOf("event_id", "error", "reply_userdata", "data")
    }

    const val EVENT_NONE = 0
    const val EVENT_SHUTDOWN = 1
    const val EVENT_END_FILE = 7
    const val EVENT_FILE_LOADED = 8
    private const val FORMAT_DOUBLE = 5

    private fun candidates(): List<File> {
        val res = System.getProperty("compose.application.resources.dir")?.let { File(it, "libmpv-2.dll") }
        return listOfNotNull(
            res,
            File(Desktop.root, "mpv/libmpv-2.dll"),
            File(System.getProperty("user.dir"), "appResources/windows/libmpv-2.dll"),
            File(System.getenv("LOCALAPPDATA") ?: "", "Programs/Reaction Watcher/_internal/libmpv-2.dll"),
        )
    }

    val dll: File? by lazy { candidates().firstOrNull { it.isFile } }

    val lib: Lib? by lazy {
        val f = dll ?: return@lazy null
        runCatching { Native.load(f.absolutePath, Lib::class.java, mapOf(com.sun.jna.Library.OPTION_STRING_ENCODING to "UTF-8")) }.getOrNull()
    }

    val available: Boolean get() = lib != null

    /** One player drawn into the native window [hwnd]. */
    class Player(hwnd: Long) {
        private val l = lib ?: throw IllegalStateException("mpv (libmpv-2.dll) wasn't found")
        private val h: Pointer = l.mpv_create() ?: throw IllegalStateException("mpv didn't start")
        @Volatile private var alive = true

        init {
            opt("wid", hwnd.toString())
            opt("vo", "gpu-next")
            opt("hwdec", "auto-safe")
            opt("keep-open", "yes")          // stay on the last frame at the end (the app moves on)
            opt("osc", "no")
            opt("input-default-bindings", "no")
            opt("input-vo-keyboard", "no")
            opt("cursor-autohide", "no")
            opt("demuxer-max-bytes", "32MiB") // keep the forward buffer modest (memory)
            opt("demuxer-max-back-bytes", "16MiB")
            opt("cache", "yes")
            opt("sub-auto", "no")
            // HLS pieces named .jpg / .png (anime CDNs): ffmpeg refuses odd piece names unless told not to be picky
            opt("demuxer-lavf-o", "extension_picky=0")
            opt("ytdl", "no")
            opt("audio-client-name", "MangaCracker")
            // test runs (off-screen): never any sound
            if (System.getenv("MC_OFFSCREEN") == "1") opt("ao", "null")
            try { check(l.mpv_initialize(h), "initialize") } catch (e: Throwable) { alive = false; l.mpv_terminate_destroy(h); throw e }
        }

        private fun check(r: Int, what: String) { if (r < 0) throw IllegalStateException("mpv $what: ${l.mpv_error_string(r)}") }
        fun opt(name: String, value: String) = l.mpv_set_option_string(h, name, value)
        fun set(name: String, value: String) { if (alive) l.mpv_set_property_string(h, name, value) }
        fun cmd(vararg args: String): Boolean = alive && l.mpv_command(h, arrayOf(*args, null)) >= 0

        fun getString(name: String): String? {
            if (!alive) return null
            val p = l.mpv_get_property_string(h, name) ?: return null
            return try { p.getString(0, "UTF-8") } finally { l.mpv_free(p) }
        }

        fun getDouble(name: String): Double? {
            if (!alive) return null
            val r = DoubleByReference()
            return if (l.mpv_get_property(h, name, FORMAT_DOUBLE, r) >= 0) r.value else null
        }

        /** Next event (waits up to [timeout] s); null after shutdown. */
        fun event(timeout: Double): Int? {
            if (!alive) return null
            return Event(l.mpv_wait_event(h, timeout)).event_id
        }

        fun load(url: String, startSec: Double = 0.0) {
            if (startSec > 1) cmd("loadfile", url, "replace", "0", "start=$startSec") else cmd("loadfile", url, "replace")
        }

        fun destroy() {
            if (!alive) return
            alive = false
            l.mpv_terminate_destroy(h)
        }
    }
}
