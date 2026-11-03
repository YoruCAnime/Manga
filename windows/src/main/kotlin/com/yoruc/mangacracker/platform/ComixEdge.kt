package com.yoruc.mangacracker.platform

import com.yoruc.mangacracker.core.CloudflareException
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.sources.ComixBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * comix.to on Windows: like the phone's hidden web view, an invisible Edge page keeps a comix page open and
 * cxlib.js calls the site's own (signed, encrypted) API module there; the method names are learnt from the page and
 * saved per site build (files/comix_methods.json). Started on the first comix request, closed with the app.
 */
object ComixEdge {
    private val edge = Edge.Instance("edge-comix", headless = true)
    private val lock = Mutex()
    private var lib = ""
    private var loadedUrl = ""
    private var methods = JsonObject(emptyMap())
    private var build = ""
    private val store by lazy { File(Desktop.ctx.filesDir, "comix_methods.json") }
    private val json get() = Http.json

    fun install() {
        if (!Edge.available) return
        runCatching {
            val j = json.parseToJsonElement(store.readText()).jsonObject
            build = (j["build"] as? JsonPrimitive)?.content.orEmpty()
            methods = j["methods"]?.jsonObject ?: JsonObject(emptyMap())
        }
        ComixBridge.bridge = { need, learnFrom, expr -> call(need, learnFrom, expr) }
    }

    fun close() = edge.close()

    private suspend fun load(url: String) {
        edge.goto(url)
        loadedUrl = url
        if (lib.isEmpty()) lib = ComixEdge::class.java.getResourceAsStream("/cxlib.js")!!.bufferedReader().readText()
        // wait until the app on the page has started (its query client is reachable)
        repeat(40) {
            delay(500)
            edge.eval(lib)
            if (edge.eval("!!(window.__cxlib && window.__cxlib.qc())") == "true") {
                if (edge.eval("location.pathname").contains("@waf")) throw CloudflareException("https://comix.to/")
                return
            }
        }
        if (edge.eval("location.pathname").contains("@waf") || edge.eval("document.title").contains("moment", true))
            throw CloudflareException("https://comix.to/")
        throw SourceException("comix didn't start (site changed or offline)")
    }

    private suspend fun learn() {
        val text = edge.eval("JSON.stringify(window.__cxlib.learn())")
        val learnt = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val b = runCatching { edge.eval("window.__cxlib.api().then(a=>a.build)", awaitPromise = true) }.getOrDefault(build)
        if (b != build) { methods = JsonObject(emptyMap()); build = b }
        methods = JsonObject(methods + learnt)
        runCatching { store.writeText(JsonObject(mapOf("build" to JsonPrimitive(build), "methods" to methods)).toString()) }
    }

    /** Same contract as the phone's bridge: run [expr] (a promise using window.__cxlib) and return its JSON text. */
    suspend fun call(need: String, learnFrom: String, expr: String): String = lock.withLock {
        if (!edge.running() || loadedUrl.isEmpty()) load(if (need in methods) "https://comix.to/browse" else learnFrom)
        edge.eval(lib)
        edge.eval("window.__cxlib.teach($methods)")
        if (need !in methods) {
            load(learnFrom)
            learn()
            // the page asks for some parts a few seconds later (the chapter list loads lazily)
            var tries = 0
            while (need !in methods && tries++ < 12) { delay(1000); learn() }
            if (need !in methods) throw SourceException("comix changed its site; this part needs an app update")
            edge.eval("window.__cxlib.teach($methods)")
        }
        val out = edge.eval("(async()=>JSON.stringify(await ($expr)))()", awaitPromise = true)
        if ("captcha_required" in out || "\"status\":403" in out) throw CloudflareException("https://comix.to/")
        out
    }
}
