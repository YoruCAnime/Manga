package com.yoruc.mangacracker.core

import kotlinx.serialization.Serializable

/** Kept so settings and backups from the phone read the same; the Windows app is local only and never updates itself. */
@Serializable
data class AppRelease(
    val version: String,
    val notes: String = "",
    val apkUrl: String = "",
    val size: Long = 0L,
    val page: String = "",
    val published: Long = 0L,
)

object AppUpdate {
    const val current: String = "1.0.5 (Windows)"
}
