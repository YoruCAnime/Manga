package com.yoruc.mangacracker.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Updating while the app is open. Windows won't let a running program's files be replaced, so `_dev_tools/install.py`
 * puts each build in a new folder next to the running one ("MangaCracker App\v<time>") and writes its name to
 * "MangaCracker App\current.txt". This notices that (a small file read every 30 s), and the app then switches over
 * by itself at a quiet moment (AppRoot decides when): the new copy starts, opens on the same tab in the same place,
 * and this one closes. [handoff] carries the window and the screen across.
 */
object SelfUpdate {
    /** The folder this copy runs from (…\MangaCracker App\v…) when it was installed that way, else null (tests, zips). */
    private val myDir: File? by lazy {
        val exe = System.getProperty("jpackage.app-path")?.let(::File) ?: return@lazy null
        exe.parentFile?.takeIf { it.name.startsWith("v") && File(it.parentFile, "current.txt").isFile }
    }

    private val _ready = MutableStateFlow<File?>(null)
    /** The newer copy's MangaCracker.exe, once one is installed. */
    val ready: StateFlow<File?> = _ready

    fun start() {
        val dir = myDir ?: return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) {
                runCatching {
                    val cur = File(dir.parentFile, "current.txt").readText().trim()
                    val exe = File(File(dir.parentFile, cur), "MangaCracker.exe")
                    if (cur.isNotEmpty() && cur != dir.name && exe.isFile && exe != failed) _ready.value = exe
                }
                delay(30_000)
            }
        }
    }

    /** What the new copy needs to look the same: the window and the screen. */
    @Serializable
    data class Handoff(
        val x: Int, val y: Int, val w: Int, val h: Int, val maximized: Boolean, val minimized: Boolean,
        val route: String, val key: String = "", val at: Long = System.currentTimeMillis(),
    )

    private val file get() = File(Desktop.root, "restart.json")
    private val json = Json { ignoreUnknownKeys = true }

    /** A newer copy that didn't start: not tried again this run. */
    @Volatile private var failed: File? = null

    /**
     * Starts the new copy with [h] waiting for it and waits until it has taken the handoff (it deletes the file as it
     * starts up), up to 30 s; the caller then closes this one. False = it didn't start: this copy stays open (the
     * user is never left with no app).
     */
    suspend fun switchTo(exe: File, h: Handoff): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val p = runCatching {
            file.writeText(json.encodeToString(Handoff.serializer(), h))
            ProcessBuilder(exe.path).directory(exe.parentFile).start()
        }.getOrNull()
        if (p != null) {
            val until = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < until) {
                if (!file.exists()) return@withContext true
                if (!p.isAlive) break
                delay(200)
            }
        }
        file.delete()
        if (p?.isAlive == true) return@withContext true   // slow to start but running: let it come up
        failed = exe; _ready.value = null
        false
    }

    /** The handoff left by the copy that just closed (read once; ignored when older than two minutes). */
    val handoff: Handoff? by lazy {
        runCatching {
            val f = file
            if (!f.isFile) return@runCatching null
            val h = json.decodeFromString(Handoff.serializer(), f.readText())
            f.delete()
            h.takeIf { System.currentTimeMillis() - it.at < 120_000 }
        }.getOrNull()
    }
}
