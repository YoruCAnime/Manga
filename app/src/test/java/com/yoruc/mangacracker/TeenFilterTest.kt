package com.yoruc.mangacracker

import com.yoruc.mangacracker.core.MangaDetails
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.video.HentaiHavenSource
import com.yoruc.mangacracker.video.VideoRef
import com.yoruc.mangacracker.video.VideoSafety
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Settings "Hide teen": off = teen titles show; on = hidden everywhere. Loli / shota stay hidden either way. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TeenFilterTest {
    @After fun reset() { Safety.hideTeen = false }

    @Test fun rules() {
        listOf("teen", "Teen Hentai", "teen-hentai", "female:teen", "teenager", "Teenagers", "teenage", "teens").forEach {
            assertTrue("teen tag $it", Safety.isTeenTag(it))
        }
        listOf("eighteen", "canteen", "nineteen", "steen", "teeny", "milf", "school").forEach { assertFalse("kept tag $it", Safety.isTeenTag(it)) }

        val tagged = MangaRef("nhentai", "1", "Ok", tags = listOf("teen"))
        val titled = MangaRef("nhentai", "2", "My Teen Days", tags = listOf("romance"))
        val plain = MangaRef("nhentai", "3", "Eighteen Summers", tags = listOf("romance"))
        val loli = MangaRef("nhentai", "4", "Ok", tags = listOf("lolicon"))
        val shota = MangaRef("nhentai", "5", "Ok", tags = listOf("shotacon"))
        val vTeen = VideoRef("hentaihaven", "1", "Clip", tags = listOf("teen-hentai"))

        Safety.hideTeen = false
        assertFalse(Safety.blocked(tagged)); assertFalse(Safety.blocked(titled)); assertFalse(VideoSafety.blocked(vTeen))
        assertTrue(Safety.blocked(loli)); assertTrue(Safety.blocked(shota))
        assertTrue(Safety.cleanTags(listOf(Tag("teen", "Teen"))).size == 1)

        Safety.hideTeen = true
        assertTrue(Safety.blocked(tagged)); assertTrue(Safety.blocked(titled)); assertTrue(VideoSafety.blocked(vTeen))
        assertFalse(Safety.blocked(plain))
        assertTrue(Safety.blocked(loli)); assertTrue(Safety.blocked(shota))
        assertEquals("teen", Safety.switchReason(MangaDetails(tagged)))
        assertTrue(Safety.cleanTags(listOf(Tag("teen", "Teen"))).isEmpty())
    }

    @Test fun hentaiHavenGenre() = runBlocking {
        Safety.hideTeen = false
        val off = HentaiHavenSource.genres().map { it.id }
        assertTrue("teen-hentai" in off); assertFalse("young" in off)
        Safety.hideTeen = true
        val on = HentaiHavenSource.genres().map { it.id }
        assertFalse("teen-hentai" in on); assertTrue("milf" in on)
    }
}
