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
 * Updating while the app is open, from ONE fixed place ("MangaCracker App\app\MangaCracker.exe", so a VPN's app list or
 * a firewall rule keeps working after updates). Windows won't let a running program's files be replaced, so
 * `_dev_tools/install.py` puts the new build in "MangaCracker App\next" while this copy runs from "app", and writes
 * "next" to "MangaCracker App\current.txt". This notices that (a small file read every 30 s) and switches over by
 * itself at a quiet moment (AppRoot decides when): it leaves the handoff, starts a small hidden helper and closes; the
 * helper waits until nothing runs from "app", swaps "next" in as "app" and starts it, and the new copy opens on the
 * same tab in the same place. If the swap can't be done the helper starts the copy in "next" (the app never goes
 * missing). A copy from the older layout (a "v<time>" folder per build) moves into "app" the same way.
 * [handoff] carries the window and the screen across.
 */
object SelfUpdate {
    /** The folder this copy runs from (…\MangaCracker App\app, next or v…) when it was installed, else null (tests, zips). */
    private val myDir: File? by lazy {
        val exe = System.getProperty("jpackage.app-path")?.let(::File) ?: return@lazy null
        exe.parentFile?.takeIf { File(it.parentFile, "current.txt").isFile }
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
                    // running from "next" (a swap that couldn't be done): move into "app" at the next quiet moment
                    else if (dir.name == "next" && cur == "next") File(dir, "MangaCracker.exe").takeIf { it != failed }?.let { _ready.value = it }
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
        // the fixed layout: the new copy waits in "next" and has to become "app" (only once this copy has closed)
        if (exe.parentFile?.name == "next") {
            if (swapIn(exe.parentFile!!.parentFile, h)) return@withContext true
            failed = exe; _ready.value = null
            return@withContext false
        }
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

    /**
     * Leaves [h] and starts the helper that, once this copy has closed, swaps "next" in as "app" in [root] and starts it.
     * True = the helper runs (the caller closes this copy now); false = it couldn't start (this copy stays open).
     */
    private fun swapIn(root: File, h: Handoff): Boolean = runCatching {
        file.writeText(json.encodeToString(Handoff.serializer(), h))
        val script = File(root, "switch.ps1")
        script.writeText(SWAP_SCRIPT)
        File(root, "switch.log").delete()
        // started IN the install folder: a helper standing inside "app" or "next" would block its own rename
        ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
            "-File", script.path, "-Root", root.path)
            .directory(root).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectErrorStream(true).start()
        true
    }.getOrElse { file.delete(); false }

    /**
     * The helper: waits (up to 60 s) until no program runs from "app" or "next"; then next -> app.new (proves nothing in
     * it is open, else nothing has changed yet), app -> app.old, app.new -> app, each tried again for ~30 s while Windows
     * still holds a file; current.txt = app, starts it, deletes app.old. Whatever fails: the newest whole copy is started
     * (the app never goes missing).
     */
    private val SWAP_SCRIPT = """
        param([string]${'$'}Root)
        Set-Location -LiteralPath ${'$'}Root
        ${'$'}app = Join-Path ${'$'}Root 'app'; ${'$'}next = Join-Path ${'$'}Root 'next'; ${'$'}new = Join-Path ${'$'}Root 'app.new'; ${'$'}old = Join-Path ${'$'}Root 'app.old'
        ${'$'}log = Join-Path ${'$'}Root 'switch.log'
        function Log([string]${'$'}s) { Add-Content -LiteralPath ${'$'}log -Value "${'$'}((Get-Date).ToString('HH:mm:ss.fff')) ${'$'}s" }
        function Busy { @(Get-CimInstance Win32_Process -Filter "Name='MangaCracker.exe'" | Where-Object { ${'$'}_.ExecutablePath -and (${'$'}_.ExecutablePath.StartsWith(${'$'}app + '\') -or ${'$'}_.ExecutablePath.StartsWith(${'$'}next + '\')) }).Count -gt 0 }
        function Retry([scriptblock]${'$'}what, [string]${'$'}name) {
            for (${'$'}i = 0; ${'$'}i -lt 60; ${'$'}i++) { try { & ${'$'}what; return ${'$'}true } catch { if (${'$'}i % 10 -eq 0) { Log "${'$'}name, try ${'$'}i : ${'$'}(${'$'}_.Exception.Message)" }; Start-Sleep -Milliseconds 500 } }
            return ${'$'}false
        }
        function Start-App([string]${'$'}dir) { Log "start ${'$'}dir"; Start-Process (Join-Path ${'$'}dir 'MangaCracker.exe') -WorkingDirectory ${'$'}dir }
        Log 'waiting for the app to close'
        ${'$'}until = (Get-Date).AddSeconds(60)
        while ((Busy) -and (Get-Date) -lt ${'$'}until) { Start-Sleep -Milliseconds 300 }
        Log 'closed'
        if (Test-Path -LiteralPath ${'$'}new) { Remove-Item -LiteralPath ${'$'}new -Recurse -Force -ErrorAction SilentlyContinue }
        if (Test-Path -LiteralPath ${'$'}old) { Remove-Item -LiteralPath ${'$'}old -Recurse -Force -ErrorAction SilentlyContinue }
        if (-not (Retry { Rename-Item -LiteralPath ${'$'}next 'app.new' -ErrorAction Stop } 'next -> app.new')) { Log 'next stays in use'; Start-App ${'$'}next; exit }
        if ((Test-Path -LiteralPath ${'$'}app) -and -not (Retry { Rename-Item -LiteralPath ${'$'}app 'app.old' -ErrorAction Stop } 'app -> app.old')) {
            Rename-Item -LiteralPath ${'$'}new 'next' -ErrorAction SilentlyContinue; Log 'app stays in use'; Start-App ${'$'}next; exit
        }
        if (-not (Retry { Rename-Item -LiteralPath ${'$'}new 'app' -ErrorAction Stop } 'app.new -> app')) { Log 'app.new stays'; Start-App ${'$'}new; exit }
        [IO.File]::WriteAllText((Join-Path ${'$'}Root 'current.txt'), 'app')
        Log 'swapped'
        Start-App ${'$'}app
        Retry { if (Test-Path -LiteralPath ${'$'}old) { Remove-Item -LiteralPath ${'$'}old -Recurse -Force -ErrorAction Stop } } 'remove app.old' | Out-Null
        Log 'done'
    """.trimIndent()

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
