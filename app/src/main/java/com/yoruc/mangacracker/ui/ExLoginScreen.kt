package com.yoruc.mangacracker.ui

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yoruc.mangacracker.core.ExLogin
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.WebCookieJar
import com.yoruc.mangacracker.ui.theme.Mc
import kotlinx.coroutines.launch

/**
 * ExHentai sign-in inside the app: the E-Hentai forum login page, then exhentai.org in the same view so the site hands
 * out its own cookie, then a check. Or cookies copied from a browser where the account is signed in.
 * (A login in the phone's browser never reaches the app: each app keeps its own cookies.)
 */
@OptIn(ExperimentalLayoutApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ExLoginScreen(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(if (ExLogin.signedIn()) ExLogin.Status(ExLogin.State.CHECKING, "Checking…") else ExLogin.Status(ExLogin.State.SIGNED_OUT, "Not signed in")) }
    var mode by remember { mutableStateOf(if (ExLogin.signedIn()) "" else "web") }
    var loading by remember { mutableStateOf(false) }

    fun done(s: ExLogin.Status) {
        status = s
        if (s.state == ExLogin.State.OK) {
            Repo.updateSettings { it.copy(useExHentai = true) }
            Toast.makeText(ctx, "Signed in. E-Hentai now uses ExHentai.", Toast.LENGTH_SHORT).show()
            mode = ""
        }
    }
    fun check() {
        status = ExLogin.Status(ExLogin.State.CHECKING, "Checking…")
        scope.launch { done(ExLogin.check()) }
    }
    LaunchedEffect(Unit) { if (ExLogin.signedIn()) check() }

    Column(Modifier.fillMaxSize().background(Mc.Bg).imePadding()) {
        TopBar("ExHentai sign-in", nav)
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val tint = when (status.state) {
                ExLogin.State.OK -> Mc.Green
                ExLogin.State.CHECKING, ExLogin.State.SIGNED_OUT -> Mc.Text2
                else -> Mc.Orange
            }
            Text(status.text, color = tint, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Mc.Card).padding(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Sign-in page", mode == "web") { mode = if (mode == "web") "" else "web" }
                Pill("Use cookies", mode == "cookies") { mode = if (mode == "cookies") "" else "cookies" }
                if (ExLogin.signedIn()) Pill("Check", false) { check() }
                if (ExLogin.signedIn()) Pill("Sign out", false) {
                    ExLogin.signOut(); status = ExLogin.Status(ExLogin.State.SIGNED_OUT, "Signed out"); mode = "web"
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        when (mode) {
            "web" -> {
                Text("Sign in with your E-Hentai account below. The app then opens ExHentai once and checks it works.",
                    color = Mc.Text3, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 16.dp))
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp).height(2.dp), color = Mc.Blue, trackColor = Mc.Card)
                AndroidView(
                    modifier = Modifier.fillMaxSize().padding(top = 6.dp).navigationBarsPadding(),
                    factory = { c ->
                        WebView(c).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = Http.userAgent
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            var exCode = 200
                            webViewClient = object : WebViewClient() {
                                override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, res: WebResourceResponse) {
                                    if (req.isForMainFrame && req.url.host?.endsWith("exhentai.org") == true) exCode = res.statusCode
                                }
                                override fun onPageStarted(view: WebView, u: String, favicon: android.graphics.Bitmap?) {
                                    loading = true
                                    if ("exhentai.org" in u) exCode = 200
                                }
                                override fun onPageFinished(view: WebView, u: String) {
                                    loading = false
                                    WebCookieJar.flush()
                                    val host = runCatching { java.net.URI(u).host }.getOrNull().orEmpty()
                                    if (host.endsWith("exhentai.org")) {
                                        // what the site shows: an empty page = no access, the list = signed in
                                        view.evaluateJavascript("(document.body ? document.body.innerHTML.length : 0) + '|' + (document.querySelector('.itg, #searchbox') ? 1 : 0)") { r ->
                                            val parts = r.trim('"').split('|')
                                            val body = if (parts.getOrNull(1) == "1") "itg" else if ((parts.getOrNull(0)?.toIntOrNull() ?: 0) < 20) "" else "?"
                                            done(ExLogin.read(exCode, body, WebCookieJar.get(ExLogin.EX, "igneous")))
                                        }
                                    } else if (host.endsWith("e-hentai.org") && ExLogin.carryOver()) {
                                        status = ExLogin.Status(ExLogin.State.CHECKING, "Signed in to E-Hentai. Opening ExHentai…")
                                        view.loadUrl(ExLogin.EX + "/")
                                    }
                                }
                            }
                            loadUrl(if (ExLogin.signedIn()) ExLogin.EX + "/" else ExLogin.LOGIN_PAGE)
                        }
                    },
                )
            }
            "cookies" -> CookieForm { id, hash, ig ->
                ExLogin.useCookies(id, hash, ig)
                check()
            }
            else -> Text(
                if (status.state == ExLogin.State.OK) "E-Hentai in the app now shows ExHentai (Settings › Logins › Use ExHentai)." else
                    "A login in your phone's browser doesn't reach the app: sign in here, or copy the cookies from a browser where ExHentai works.",
                color = Mc.Text3, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
private fun CookieForm(onSave: (String, String, String) -> Unit) {
    var id by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    var ig by remember { mutableStateOf("") }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("On a computer or browser where ExHentai opens: site settings › cookies for exhentai.org. Copy these three values.",
            color = Mc.Text3, fontSize = 12.5.sp)
        listOf(Triple("ipb_member_id", id) { v: String -> id = v }, Triple("ipb_pass_hash", hash) { v: String -> hash = v },
            Triple("igneous (optional)", ig) { v: String -> ig = v }).forEach { (label, value, set) ->
            TextField(value, set, singleLine = true, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                colors = TextFieldDefaults.colors(focusedContainerColor = Mc.Card, unfocusedContainerColor = Mc.Card, focusedIndicatorColor = Mc.Bg, unfocusedIndicatorColor = Mc.Bg))
        }
        Button(enabled = id.isNotBlank() && hash.isNotBlank(), onClick = { onSave(id, hash, ig) },
            colors = ButtonDefaults.buttonColors(containerColor = Mc.Blue, contentColor = Mc.Bg)) { Text("Save and check") }
    }
}

@Composable
private fun Pill(label: String, on: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (on) Mc.Blue else Mc.Card2, contentColor = if (on) Mc.Bg else Mc.Text)) {
        Text(label, fontSize = 13.sp)
    }
}
