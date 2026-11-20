package com.yoruc.mangacracker.core

import android.webkit.CookieManager

/**
 * ExHentai sign-in. ExHentai takes the E-Hentai forum login (ipb_member_id + ipb_pass_hash) and hands out its own
 * `igneous` cookie on the first visit; `igneous=mystery` or an empty page means the account has no ExHentai access.
 * Without the login cookies on exhentai.org the site bounces through forums.e-hentai.org (behind a Cloudflare check),
 * so the login is copied onto exhentai.org before any request.
 */
object ExLogin {
    enum class State { SIGNED_OUT, CHECKING, OK, NO_ACCESS, BLOCKED, ERROR }
    data class Status(val state: State, val text: String)

    private const val EH = "https://e-hentai.org"
    private const val FORUMS = "https://forums.e-hentai.org"
    const val EX = "https://exhentai.org"
    const val LOGIN_PAGE = "https://forums.e-hentai.org/index.php?act=Login&CODE=00"

    private fun cookie(name: String) =
        WebCookieJar.get(EX, name) ?: WebCookieJar.get(EH, name) ?: WebCookieJar.get(FORUMS, name)

    fun memberId() = cookie("ipb_member_id")?.takeIf { it.isNotBlank() && it != "0" }
    private fun passHash() = cookie("ipb_pass_hash")?.takeIf { it.isNotBlank() }
    fun signedIn() = memberId() != null && passHash() != null

    /** Puts the forum login on both sites (a year long), so it survives the app closing. */
    fun carryOver(): Boolean {
        val id = memberId() ?: return false
        val hash = passHash() ?: return false
        if (WebCookieJar.get(EX, "ipb_member_id") == id && WebCookieJar.get(EX, "ipb_pass_hash") == hash) return true
        listOf(EX to ".exhentai.org", EH to ".e-hentai.org").forEach { (u, d) ->
            WebCookieJar.set(u, "ipb_member_id", id, d)
            WebCookieJar.set(u, "ipb_pass_hash", hash, d)
        }
        WebCookieJar.flush()
        return true
    }

    /** Cookies copied from a browser where the account is signed in. */
    fun useCookies(memberId: String, passHash: String, igneous: String) {
        val id = memberId.trim().substringAfter("=").trim()
        val hash = passHash.trim().substringAfter("=").trim()
        val ig = igneous.trim().substringAfter("=").trim()
        listOf(EX to ".exhentai.org", EH to ".e-hentai.org").forEach { (u, d) ->
            WebCookieJar.set(u, "ipb_member_id", id, d)
            WebCookieJar.set(u, "ipb_pass_hash", hash, d)
        }
        if (ig.isNotEmpty() && ig != "mystery") WebCookieJar.set(EX, "igneous", ig, ".exhentai.org")
        WebCookieJar.flush()
    }

    fun signOut() {
        runCatching {
            CookieManager.getInstance().apply {
                listOf(EH, EX, FORUMS).forEach { u ->
                    getCookie(u)?.split(";")?.forEach { c ->
                        val n = c.substringBefore("=").trim()
                        if (n.isNotEmpty() && n != "nw" && n != "sl") {
                            setCookie(u, "$n=; Max-Age=0; Path=/")
                            setCookie(u, "$n=; Max-Age=0; Path=/; Domain=." + java.net.URI(u).host.removePrefix("forums."))
                        }
                    }
                }
                flush()
            }
        }
        Repo.updateSettings { it.copy(useExHentai = false) }
    }

    /** What ExHentai answers for this login right now (one request). */
    suspend fun check(): Status {
        if (!signedIn()) return Status(State.SIGNED_OUT, "Not signed in")
        carryOver()
        return runCatching {
            val (code, body) = Http.getRaw("$EX/")
            read(code, body, WebCookieJar.get(EX, "igneous"))
        }.getOrElse { Status(State.ERROR, "Couldn't reach ExHentai: ${it.message ?: it.javaClass.simpleName}") }
    }

    /** Reads an exhentai.org answer (the app's request or the sign-in page's). */
    fun read(code: Int, body: String, igneous: String?): Status = when {
        code == 451 -> Status(State.BLOCKED, "ExHentai is blocked where you are. Turn your VPN on, then press Check.")
        igneous == "mystery" -> Status(State.NO_ACCESS, "Signed in, but ExHentai doesn't open for this account yet (E-Hentai gives access to accounts after a while).")
        code in 200..299 && body.isBlank() -> Status(State.NO_ACCESS, "Signed in, but ExHentai shows an empty page for this account. Sign out and in again, or use the cookies from a browser where ExHentai works.")
        code in 200..299 && ("itg" in body || "searchbox" in body || "gl1t" in body) -> Status(State.OK, "Signed in · ExHentai works")
        code in 200..299 -> Status(State.ERROR, "ExHentai answered, but not with its list. Press Check again in a moment.")
        else -> Status(State.ERROR, "ExHentai answered $code")
    }
}
