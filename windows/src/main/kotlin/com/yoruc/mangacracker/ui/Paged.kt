package com.yoruc.mangacracker.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.PageResult
import com.yoruc.mangacracker.core.Safety
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Endless list fed page by page; drops repeats and blocked titles. */
class Paged(private val scope: CoroutineScope, private val load: suspend (page: Int) -> PageResult) {
    val items = mutableStateListOf<MangaRef>()
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<Throwable?>(null); private set
    var hasNext by mutableStateOf(true); private set
    private var page = 1
    private val seen = HashSet<String>()
    private var job: Job? = null

    fun reset() {
        job?.cancel()
        items.clear(); seen.clear(); page = 1; hasNext = true; error = null; loading = false
        more()
    }

    fun more() {
        if (loading || !hasNext) return
        loading = true; error = null
        job = scope.launch {
            try {
                var tries = 0
                // a page can be all repeats / blocked; keep going a little so the grid never stalls
                while (true) {
                    val r = withContext(Dispatchers.IO) { load(page) }
                    page++
                    val fresh = Safety.filter(r.items).filter { seen.add(it.key) }
                    items.addAll(fresh)
                    hasNext = r.hasNext && r.items.isNotEmpty()
                    if (fresh.isNotEmpty() || !hasNext || ++tries >= 3) break
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = e
            } finally {
                loading = false
            }
        }
    }
}
