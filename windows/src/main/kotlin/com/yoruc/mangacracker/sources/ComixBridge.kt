package com.yoruc.mangacracker.sources

import com.yoruc.mangacracker.core.SourceException

/**
 * comix.to signs and encrypts its API inside the page's own scripts, so the phone keeps a hidden web view open and
 * calls them there. On Windows that page runs in a hidden Edge window ([bridge], set by the app when Edge is found).
 */
object ComixBridge {
    /** (needed, page url, js expression) -> the expression's JSON result as text */
    @Volatile var bridge: (suspend (String, String, String) -> String)? = null

    suspend fun call(need: String, learnFrom: String, expr: String): String =
        bridge?.invoke(need, learnFrom, expr) ?: throw SourceException("comix needs Microsoft Edge to open its pages (not found)")
}
