package com.yoruc.mangacracker.sources

import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Source

object Sources {
    val all: List<Source> by lazy {
        listOf(AtsuSource, ComixSource, NhentaiSource, EHentaiSource, DanbooruSource, GelbooruSource)
    }

    fun byId(id: String): Source? = all.firstOrNull { it.id == id }

    fun enabled(): List<Source> = all.filter { it.id !in Repo.settings.value.disabledSources }
}
