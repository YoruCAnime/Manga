package com.yoruc.mangacracker.core

/**
 * Content guard. Titles tagged as sexual content involving minors are always hidden (not a setting);
 * the user's own hidden tags from Settings are added on top.
 */
object Safety {
    /** Always hidden, on every source (after lower-casing, '_' and '-' read as spaces, namespace dropped). */
    private val HARD = setOf(
        "loli", "lolicon", "shota", "shotacon", "toddlercon", "oppai loli", "petite loli", "low loli", "low shotacon",
        "low lolicon", "ijime loli", "underage", "underage uke", "underage seme", "kodomo doushi", "legal loli", "legal shota",
        "loli protagonist", "loli female lead", "lolicon protagonist", "shota protagonist", "shota uke", "shota seme",
        "shota character/s", "loli/grooming/child porn", "child rape", "child sexual abuse", "child pornography", "child prostitute",
    )

    /**
     * Words that also describe ordinary stories ("Age Regression" isekai, a "Child Protagonist"): they hide a title
     * only when it is 18+, and always on the 18+ picture / doujin sources.
     */
    private val SOFT = setOf(
        "child", "children", "child/ren", "kid", "kids", "minor", "young", "young girl", "young boy", "infant", "baby",
        "toddler", "lolita", "lolita/s", "elementary school student", "elementary school student/s", "elementary schooler",
        "elementary school", "preschooler", "kindergartener", "kindergarten", "cub", "aged down", "age regression",
        "female child", "male child", "kindergarten uniform", "randoseru", "child protagonist",
    )

    /** Mainstream manga sources, where SOFT words only count on 18+ titles. */
    private val MAINSTREAM = setOf("atsu", "comix")
    private val ADULT_MARKS = setOf("adult", "hentai", "smut", "pornographic", "erotica", "rating:e", "rating:q", "rating:explicit", "rating:questionable")

    @Volatile var userHidden: Set<String> = emptySet()

    fun norm(tag: String): String =
        tag.substringAfter(':').lowercase().replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ").trim()

    /** For tag pickers: never offer any of these. */
    fun isMinorTag(tag: String): Boolean = norm(tag).let { it in HARD || it in SOFT }

    private fun isAdult(tags: Collection<String>) = tags.any { t -> t.lowercase() in ADULT_MARKS || norm(t) in ADULT_MARKS }

    fun blocked(tags: Collection<String>, source: String = ""): Boolean {
        val strictSoft = source !in MAINSTREAM || isAdult(tags)
        return tags.any { t ->
            val n = norm(t)
            n in HARD || n in userHidden || (strictSoft && n in SOFT)
        }
    }

    fun blocked(ref: MangaRef): Boolean = blocked(ref.tags, ref.source)

    fun blocked(d: MangaDetails): Boolean =
        blocked(d.genres + d.ref.tags + d.tagGroups.flatMap { it.tags }, d.ref.source)

    fun filter(items: List<MangaRef>): List<MangaRef> = items.filterNot { blocked(it) }
    fun filter(r: PageResult): PageResult = r.copy(items = filter(r.items))

    /** Tags offered in filter sheets: never list the minor ones. */
    fun cleanTags(tags: List<Tag>): List<Tag> = tags.filterNot { isMinorTag(it.label) || isMinorTag(it.id) }
}
