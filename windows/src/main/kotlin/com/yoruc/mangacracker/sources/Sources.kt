package com.yoruc.mangacracker.sources

import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Source

object Sources {
    val all: List<Source> by lazy {
        listOf(
            AtsuSource, ComixSource, NhentaiSource, EHentaiSource, DanbooruSource, GelbooruSource,
            HitomiSource, JestfulSource, Hentai20Source, Manhwa18NetSource, Manhwa18CcSource, Manga18ClubSource,
            Hentai2ReadSource, HentaiFoxSource, NhentaiXxxSource, MangaDexSource, ToonilySource, PururinSource, IMHentaiSource,
        )
    }

    fun byId(id: String): Source? = all.firstOrNull { it.id == id }

    /** The shelves of the Browse page, in order. */
    val categories = listOf("Manga", "Manhwa 18+", "Doujinshi 18+", "Pictures 18+")
    private val pictures = setOf("danbooru", "gelbooru")
    private val doujin = setOf("hentai2read")

    fun category(s: Source): String = when {
        s.id in pictures -> "Pictures 18+"
        !s.nsfw || s.id == "mangadex" -> "Manga"
        s.hasChapters && s.id !in doujin -> "Manhwa 18+"
        else -> "Doujinshi 18+"
    }

    /** Switched on, and not waiting for an age confirmation: what Home, search-everywhere and the like may call. */
    fun enabled(): List<Source> = Repo.settings.value.let { s -> all.filter { it.id !in s.disabledSources && (it.ageNotice == null || it.id in s.ageConfirmed) } }
}
