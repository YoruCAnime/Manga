package com.yoruc.mangacracker.core

/** One site. Every call is a suspend function run on the IO dispatcher by the caller. */
interface Source {
    val id: String
    val name: String
    val baseUrl: String
    /** Short tagline under the name on the Sources page. */
    val blurb: String
    /** Brand-ish colour for the source badge. */
    val color: Long
    val nsfw: Boolean
    /** false = every title is a single gallery (doujins, booru pools / posts). */
    val hasChapters: Boolean get() = true
    /** Picture shape in grids: 2:3 posters for manga, free for booru posts. */
    val coverRatio: Float get() = 0.70f

    /** Drop-downs in the filter sheet (sort, type, status ...). The first option of each is the default. */
    val selects: List<SelectDef> get() = emptyList()
    /** Whether the filter sheet lets a tag be excluded as well as included. */
    val canExclude: Boolean get() = true

    /** The full tag list to pick from (empty when the site only offers suggestions). */
    suspend fun tags(): List<Tag> = emptyList()
    /** Tag suggestions while typing in the filter sheet. */
    suspend fun suggestTags(query: String): List<Tag> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return tags().filter { it.label.contains(q, ignoreCase = true) }.sortedBy { !it.label.startsWith(q, true) }.take(40)
    }

    suspend fun popular(page: Int): PageResult
    suspend fun latest(page: Int): PageResult
    suspend fun search(query: SearchQuery, page: Int): PageResult

    suspend fun details(ref: MangaRef): MangaDetails
    /** Newest first (the reader's "next chapter" is the one before in this list). */
    suspend fun chapters(ref: MangaRef): List<Chapter>
    /** The chapter list as it fills in (sources that page slowly emit after each page). */
    fun chaptersFlow(ref: MangaRef): kotlinx.coroutines.flow.Flow<List<Chapter>> = kotlinx.coroutines.flow.flow { emit(chapters(ref)) }
    /** Just enough of the newest chapters for the update check. */
    suspend fun latestChapters(ref: MangaRef): List<Chapter> = chapters(ref)
    suspend fun pages(ref: MangaRef, chapter: Chapter): List<Page>
    /** The real image address for a page whose [Page.url] is empty. */
    suspend fun imageUrl(page: Page): String = page.url

    /** "More like this" for the details page. */
    suspend fun related(ref: MangaRef, details: MangaDetails): List<MangaRef> = emptyList()

    /** A search for one genre / tag name as shown on the details page. */
    fun tagQuery(name: String): SearchQuery = SearchQuery(include = listOf(Tag(name, name)))
    fun artistQuery(name: String): SearchQuery = SearchQuery(artist = name)

    /** Web page of a title (open in the in-app browser). */
    fun webUrl(ref: MangaRef): String

    /** Account or key the source can use, shown in Settings. Null = nothing to set. */
    val login: LoginKind? get() = null
}

enum class LoginKind { EXHENTAI_COOKIES, GELBOORU_KEY, DANBOORU_KEY }
