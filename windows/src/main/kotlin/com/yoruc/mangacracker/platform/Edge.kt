package com.yoruc.mangacracker.platform

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.WebCookieJar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Microsoft Edge (part of Windows) in place of Android's web view, driven over the DevTools protocol, each instance
 * with its own profile in the app's folder (never the user's own Edge profile) and the app's User-Agent (so a
 * Cloudflare pass made in Edge also works for the app):
 *  - [Edge] itself: an app-style window for sign-ins, Cloudflare checks and "Open site"; its cookies are copied into
 *    the app's cookie jar.
 *  - [Edge.Instance] with headless = true: an invisible page for comix's scripts.
 */
object Edge {
    private val exe: File? by lazy {
        listOf(System.getenv("ProgramFiles(x86)"), System.getenv("ProgramFiles"), System.getenv("LOCALAPPDATA"))
            .filterNotNull().map { File(it, "Microsoft/Edge/Application/msedge.exe") }.firstOrNull { it.exists() }
    }
    val available: Boolean get() = exe != null

    private val ws = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    /** One Edge process with its own profile folder. */
    class Instance(private val profileName: String, private val headless: Boolean) {
        private val profile get() = File(Desktop.root, profileName)
        private var proc: Process? = null
        var cdp: Cdp? = null; private set

        fun running(): Boolean = proc?.isAlive == true

        /** Starts Edge if needed ([offScreen]: the window sits far off the screen). */
        fun connect(offScreen: Boolean = false): Cdp? {
            cdp?.takeIf { it.open && proc?.isAlive == true }?.let { return it }
            close()
            val edge = exe ?: return null
            profile.mkdirs()
            File(profile, "DevToolsActivePort").delete()
            val args = mutableListOf(edge.path, "--user-data-dir=${profile.path}", "--remote-debugging-port=0",
                "--no-first-run", "--no-default-browser-check", "--disable-sync", "--hide-crash-restore-bubble",
                "--user-agent=${Http.userAgent}", "--window-size=480,820")
            if (headless) args += listOf("--headless=new", "--mute-audio")
            if (offScreen) args += "--window-position=-32000,-32000"
            args += "about:blank"
            proc = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            val portFile = File(profile, "DevToolsActivePort")
            val until = System.currentTimeMillis() + 15_000
            while (!portFile.exists() && System.currentTimeMillis() < until) Thread.sleep(150)
            val lines = runCatching { portFile.readLines() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
            val wsUrl = "ws://127.0.0.1:${lines[0].trim()}${lines.getOrNull(1)?.trim().orEmpty()}"
            return Cdp(ws, wsUrl).also { cdp = it }
        }

        fun close() {
            runCatching { cdp?.call0("Browser.close") }
            cdp?.close(); cdp = null
            proc?.let { p -> if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() }
            proc = null
            session = null
        }

        // ---------- one page driven by the app ----------

        private var session: String? = null

        /** Opens (or reuses) the instance's page and loads [url]; waits for the document to finish loading. */
        suspend fun goto(url: String, timeoutMs: Long = 40_000) = withContext(Dispatchers.IO) {
            val c = connect() ?: throw IllegalStateException("Microsoft Edge wasn't found")
            if (session == null || !c.open) {
                val t = c.call("Target.createTarget", buildJsonObject { put("url", "about:blank") })["targetId"]!!.jsonPrimitive.content
                session = c.call("Target.attachToTarget", buildJsonObject { put("targetId", t); put("flatten", true) })["sessionId"]!!.jsonPrimitive.content
                // a phone-sized screen with touch, like the phone app's hidden web view (sites lay out and load the same)
                if (headless) runCatching {
                    c.call("Emulation.setDeviceMetricsOverride", buildJsonObject {
                        put("width", 412); put("height", 915); put("deviceScaleFactor", 2.625); put("mobile", true)
                    }, session)
                    c.call("Emulation.setTouchEmulationEnabled", buildJsonObject { put("enabled", true); put("maxTouchPoints", 5) }, session)
                }
            }
            // a mark on the old page: "complete" only counts once the new page has replaced it
            runCatching { eval("window.__mcOld = 1") }
            c.call("Page.navigate", buildJsonObject { put("url", url) }, session)
            val until = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < until) {
                delay(300)
                if (runCatching { eval("window.__mcOld === undefined && document.readyState") }.getOrNull() == "complete") return@withContext
            }
            throw IllegalStateException("The page didn't load")
        }

        /** Runs [js] in the page; promises are awaited. Returns the value as text ("null" for nothing). */
        suspend fun eval(js: String, awaitPromise: Boolean = false): String = withContext(Dispatchers.IO) {
            val c = cdp ?: throw IllegalStateException("Edge isn't running")
            val r = c.call("Runtime.evaluate", buildJsonObject {
                put("expression", js); put("awaitPromise", awaitPromise); put("returnByValue", true)
            }, session, timeoutMs = 50_000)
            r["exceptionDetails"]?.let { e ->
                val msg = e.jsonObject["exception"]?.jsonObject?.get("description")?.jsonPrimitive?.content ?: e.toString()
                throw IllegalStateException(msg.take(300))
            }
            val v = r["result"]?.jsonObject?.get("value") ?: return@withContext "null"
            (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: v.toString()
        }
    }

    private val window = Instance("edge-profile", headless = false)

    /** Opens [url] in a visible Edge app window (starting Edge if needed). */
    suspend fun open(url: String, offScreen: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val c = window.connect(offScreen) ?: return@withContext false
        runCatching { c.call("Target.createTarget", buildJsonObject { put("url", url); put("newWindow", true) }) }.isSuccess
    }

    /** Is the Edge window still open? */
    fun running(): Boolean = window.running()

    /** Copies every Edge cookie for [hosts] (empty = all) into the app's cookie jar. Returns how many. */
    suspend fun importCookies(hosts: List<String> = emptyList(), from: Instance = window): Int = withContext(Dispatchers.IO) {
        val c = from.cdp ?: return@withContext 0
        val r = runCatching { c.call("Storage.getCookies", buildJsonObject {}) }.getOrNull() ?: return@withContext 0
        val list = r["cookies"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
            val domain = s("domain").removePrefix(".")
            if (hosts.isNotEmpty() && hosts.none { h -> domain == h || domain.endsWith(".$h") || h.endsWith(".$domain") }) return@mapNotNull null
            val b = Cookie.Builder().name(s("name")).value(s("value")).path(s("path").ifEmpty { "/" })
            if (s("domain").startsWith(".")) b.domain(domain) else b.hostOnlyDomain(domain)
            val exp = s("expires").toDoubleOrNull() ?: -1.0
            b.expiresAt(if (exp > 0) (exp * 1000).toLong() else System.currentTimeMillis() + 7 * 24 * 3600_000L)
            if (s("secure") == "true") b.secure()
            if (s("httpOnly") == "true") b.httpOnly()
            runCatching { b.build() }.getOrNull()
        }
        WebCookieJar.putAll(list)
        list.size
    }

    /** Closes every Edge the app started. */
    fun close() { window.close(); com.yoruc.mangacracker.platform.ComixEdge.close() }

    /**
     * Cloudflare: opens [url] in an Edge window off to the side until cf_clearance arrives (25 s), then copies the
     * cookies. True when the pass came. Used by [Http.solver].
     */
    fun solveCloudflare(url: String): Boolean = runBlocking {
        if (!available) return@runBlocking false
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return@runBlocking false
        val before = WebCookieJar.get(url, "cf_clearance")
        if (!open(url, offScreen = true)) return@runBlocking false
        val until = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < until) {
            delay(700)
            importCookies(listOf(host.split('.').takeLast(2).joinToString(".")))
            val now = WebCookieJar.get(url, "cf_clearance")
            if (now != null && now != before) { window.close(); return@runBlocking true }
        }
        window.close()
        false
    }

    /** A tiny DevTools protocol client: numbered calls over one WebSocket. */
    class Cdp(client: OkHttpClient, url: String) {
        private val ids = AtomicInteger()
        private val waiting = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
        @Volatile var open = true; private set
        private val socket: WebSocket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { Http.json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val id = (o["id"] as? JsonPrimitive)?.content?.toIntOrNull() ?: return
                val d = waiting.remove(id) ?: return
                val err = o["error"]
                if (err != null) d.completeExceptionally(IllegalStateException(err.toString())) else d.complete(o["result"]?.jsonObject ?: JsonObject(emptyMap()))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { shut() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { shut() }
        })

        private fun shut() {
            open = false
            waiting.values.forEach { it.completeExceptionally(IllegalStateException("Edge closed")) }
            waiting.clear()
        }

        fun call(method: String, params: JsonObject, sessionId: String? = null, timeoutMs: Long = 30_000): JsonObject = runBlocking {
            val id = ids.incrementAndGet()
            val d = CompletableDeferred<JsonObject>()
            waiting[id] = d
            val msg = buildJsonObject {
                put("id", id); put("method", method); put("params", params)
                if (sessionId != null) put("sessionId", sessionId)
            }
            socket.send(msg.toString())
            withTimeout(timeoutMs) { d.await() }
        }

        fun call0(method: String) = runCatching { socket.send(buildJsonObject { put("id", ids.incrementAndGet()); put("method", method) }.toString()) }

        fun close() { runCatching { socket.close(1000, null) } }
    }
}
