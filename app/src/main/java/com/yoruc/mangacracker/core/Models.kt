package com.yoruc.mangacracker.core

import kotlinx.serialization.Serializable

/** A title as it shows in a grid: enough to draw the card and open the details. */
@Serializable
data class MangaRef(
    val source: String,
    val id: String,
    val title: String,
    val cover: String = "",
    /** Tags known from the listing (used for the safety filter and for recommendations). */
    val tags: List<String> = emptyList(),
    /** Small text under the title, e.g. "Ch. 120" or "24 pages". */
    val subtitle: String = "",
) {
    val key: String get() = "$source|$id"
}

@Serializable
data class TagGroup(val name: String, val tags: List<String>)

@Serializable
data class MangaDetails(
    val ref: MangaRef,
    val altTitles: List<String> = emptyList(),
    val description: String = "",
    val authors: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    /** Genres / plain tags; tapping one searches it. */
    val genres: List<String> = emptyList(),
    /** Extra namespaced tags (parody, character, group, ...) for galleries. */
    val tagGroups: List<TagGroup> = emptyList(),
    val status: String = "",
    val type: String = "",
    val url: String = "",
    val pageCount: Int = 0,
    val rating: String = "",
    val extra: List<Pair<String, String>> = emptyList(),
)

@Serializable
data class Chapter(
    val id: String,
    val name: String,
    val number: Float = -1f,
    val date: Long = 0L,
    val group: String = "",
    val pages: Int = 0,
)

/** One picture in the reader. [url] may be empty when the source must resolve it first (E-Hentai). */
data class Page(
    val index: Int,
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
    val pageUrl: String = "",
)

data class PageResult(val items: List<MangaRef>, val hasNext: Boolean)

/** A tag / genre the user can include or exclude in a search. [id] is what the site wants, [label] what we show. */
@Serializable
data class Tag(val id: String, val label: String, val group: String = "")

data class SelectDef(val key: String, val label: String, val options: List<Pair<String, String>>)

data class SearchQuery(
    val text: String = "",
    val include: List<Tag> = emptyList(),
    val exclude: List<Tag> = emptyList(),
    val selects: Map<String, String> = emptyMap(),
    /** Set when the user tapped an author / artist: sources search it the best way they can. */
    val artist: String = "",
) {
    val isEmpty: Boolean get() = text.isBlank() && include.isEmpty() && exclude.isEmpty() && artist.isBlank()
}

class SourceException(message: String) : Exception(message)
