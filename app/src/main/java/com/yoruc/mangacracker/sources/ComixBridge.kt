package com.yoruc.mangacracker.sources

import android.annotation.SuppressLint
import android.content.Context
import android.os.Message
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.yoruc.mangacracker.core.CloudflareException
import com.yoruc.mangacracker.core.SourceException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * comix signs and encrypts its API inside its own scripts, so the app asks the site's own code:
 * a hidden web view keeps a comix page open, cxlib.js (assets) is injected, and calls go through it.
 * The api methods are learnt from pages that use them and remembered per site build.
 * See _dev_tools/specs/comix.md section 2.
 */
@SuppressLint("SetJavaScriptEnabled")
object ComixBridge {
    private lateinit var app: Context
    private var web: WebView? = null
    private val lock = Mutex()
    private val ids = AtomicInteger()
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private var pageDone: CompletableDeferred<Unit>? = null
    private var loadedUrl = ""
    private var lib = ""
    /** "manga,list" -> {"local":..,"path":[..]} as JSON text, learnt for [build] */
    private var methods: JsonObject = JsonObject(emptyMap())
    private var build = ""
    private val store by lazy { File(app.filesDir, "comix_methods.json") }

    fun init(context: Context) {
        app = context.applicationContext
        runCatching {
            val j = Json.parseToJsonElement(store.readText()).jsonObject
            build = (j["build"] as? JsonPrimitive)?.content.orEmpty()
            methods = j["methods"]?.jsonObject ?: JsonObject(emptyMap())
        }
    }

    private val Json get() = com.yoruc.mangacracker.core.Http.json

    private class Back {
        @JavascriptInterface
        fun done(id: Int, ok: Boolean, payload: String) {
            val d = waiting.remove(id) ?: return
            if (ok) d.complete(payload) else d.completeExceptionally(SourceException(payload))
        }
    }

    private fun view(): WebView = web ?: WebView(app).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.blockNetworkImage = true
        settings.loadsImagesAutomatically = false
        settings.setSupportMultipleWindows(true)
        settings.userAgentString = settings.userAgentString.replace("; wv", "")
        addJavascriptInterface(Back(), "MCBridge")
        webChromeClient = object : WebChromeClient() {
            // comix lays invisible ad links over the page: never open windows
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
        }
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) { pageDone?.complete(Unit) }
        }
        // a 1x1 layout so the page's scripts run as on a real screen
        layout(0, 0, 1080, 1920)
        web = this
    }

    private suspend fun eval(js: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> view().evaluateJavascript(js) { c.resume(it ?: "null") } }
    }

    private suspend fun load(url: String) {
        withContext(Dispatchers.Main) {
            pageDone = CompletableDeferred()
            view().loadUrl(url, mapOf("X-Requested-With" to "com.android.chrome"))
        }
        withTimeout(40_000) { pageDone!!.await() }
        loadedUrl = url
        if (lib.isEmpty()) lib = app.assets.open("cxlib.js").bufferedReader().readText()
        // wait until the app on the page has started (its query client is reachable)
        repeat(40) {
            delay(500)
            eval(lib)
            if (eval("!!(window.__cxlib && window.__cxlib.qc())") == "true") {
                if (eval("location.pathname").contains("@waf")) throw CloudflareException("https://comix.to/")
                return
            }
        }
        if (eval("location.pathname").contains("@waf") || eval("document.title").contains("moment", true))
            throw CloudflareException("https://comix.to/")
        throw SourceException("comix didn't start (site changed or offline)")
    }

    private suspend fun learn() {
        val text = eval("JSON.stringify(window.__cxlib.learn())")
        val learnt = runCatching { Json.parseToJsonElement(Json.parseToJsonElement(text).let { (it as JsonPrimitive).content }).jsonObject }.getOrNull() ?: return
        val b = runCatching {
            val d = CompletableDeferred<String>()
            val id = ids.incrementAndGet(); waiting[id] = d
            eval("window.__cxlib.api().then(a=>MCBridge.done($id,true,a.build)).catch(e=>MCBridge.done($id,false,String(e)))")
            withTimeout(20_000) { d.await() }
        }.getOrDefault(build)
        if (b != build) { methods = JsonObject(emptyMap()); build = b }
        methods = JsonObject(methods + learnt)
        runCatching { store.writeText(JsonObject(mapOf("build" to JsonPrimitive(build), "methods" to methods)).toString()) }
    }

    /**
     * Run `expr` (a JS expression giving a promise, using window.__cxlib) and return its JSON text.
     * [need] is the method key it uses; [learnFrom] a comix page that uses it, opened first when it isn't known yet.
     */
    suspend fun call(need: String, learnFrom: String, expr: String): String = lock.withLock {
        if (web == null || loadedUrl.isEmpty()) load(if (need in methods) "https://comix.to/browse" else learnFrom)
        eval(lib)
        eval("window.__cxlib.teach(${methods})")
        if (need !in methods) {
            load(learnFrom)
            learn()
            if (need !in methods) { delay(2500); learn() }
            if (need !in methods) throw SourceException("comix changed its site; this part needs an app update")
            eval("window.__cxlib.teach(${methods})")
        }
        val id = ids.incrementAndGet()
        val d = CompletableDeferred<String>()
        waiting[id] = d
        eval("""(async()=>{try{const r=await ($expr);MCBridge.done($id,true,JSON.stringify(r))}catch(e){MCBridge.done($id,false,String(e&&e.message||e))}})()""")
        val out = try { withTimeout(45_000) { d.await() } } finally { waiting.remove(id) }
        if ("captcha_required" in out || "\"status\":403" in out) throw CloudflareException("https://comix.to/")
        out
    }
}
