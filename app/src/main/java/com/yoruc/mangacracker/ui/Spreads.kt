package com.yoruc.mangacracker.ui

/**
 * Two-page view: pairs pages in reading order. The first page (the cover) stays alone, a wide page (a two-page
 * spread already) stays alone, and a page whose partner would be wide stays alone too; the rest go two by two.
 */
fun <T> spreads(pages: List<T>, wide: (T) -> Boolean, firstAlone: Boolean = true): List<List<T>> {
    val out = ArrayList<List<T>>()
    var i = 0
    if (firstAlone && pages.isNotEmpty()) { out += listOf(pages[0]); i = 1 }
    while (i < pages.size) {
        val a = pages[i]
        if (wide(a) || i + 1 >= pages.size || wide(pages[i + 1])) { out += listOf(a); i++ }
        else { out += listOf(a, pages[i + 1]); i += 2 }
    }
    return out
}

/** Left-to-right screen order of a pair: right-to-left books put the first page on the right. */
fun <T> screenOrder(pair: List<T>, rtl: Boolean): List<T> = if (rtl) pair.asReversed() else pair
