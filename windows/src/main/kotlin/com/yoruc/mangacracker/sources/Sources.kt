package com.yoruc.mangacracker.sources

import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Source

object Sources {
    val all: List<Source> by lazy {
        listOf(
            AtsuSource, ComixSource, NhentaiSource, EHentaiSource, DanbooruSource, GelbooruSource,
            HitomiSource, JestfulSource, Hentai20Source, Manhwa18NetSource, Manhwa18CcSource, Manga18ClubSource,
            Hentai2ReadSource, HentaiFoxSource, NhentaiXxxSource,
        )
    }

    fun byId(id: String): Source? = all.firstOrNull { it.id == id }

    /** Switched on, and not waiting for an age confirmation: what Home, search-everywhere and the like may call. */
    fun enabled(): List<Source> = Repo.settings.value.let { s -> all.filter { it.id !in s.disabledSources && (it.ageNotice == null || it.id in s.ageConfirmed) } }
}
