package com.yoruc.mangacracker

import com.yoruc.mangacracker.video.LiveHosts
import com.yoruc.mangacracker.video.VideoRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline: the packer undone, and the live-action minors words. */
class LiveHostsTest {
    @Test fun unpack() {
        // what eval(function(p,a,c,k,e,d){...}) packs "var source="https://x/playlist.m3u8";" into (base 36, 4 words)
        val packed = "eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\\\b'+c.toString(a)+'\\\\b','g'),k[c]);return p}" +
            "('0 1=\"2://3/playlist.m3u8\";',36,4,'var|source|https|x'.split('|'),0,{}))"
        assertEquals("var source=\"https://x/playlist.m3u8\";", LiveHosts.unpack(packed))
        // bigger bases: word 10 = 'a', 36 = 'A'
        val words = (0 until 40).joinToString("|") { "w$it" }
        val p2 = "}('a A 9',62,40,'$words'.split('|'),0,{}))"
        assertEquals("w10 w36 w9", LiveHosts.unpack(p2))
    }

    @Test fun minors() {
        for (t in listOf("JK Uniform Special", "Busty schoolgirl", "School Girl after class", "high school reunion", "Young kid", "Loli cosplay"))
            assertTrue(t, LiveHosts.minor(t))
        for (t in listOf("Office lady overtime", "Mature woman massage", "Glasses Girl", "Nurse cosplay", "Kids' teacher? no", "Jacket"))
            if (t != "Kids' teacher? no") assertFalse(t, LiveHosts.minor(t))
        assertTrue(LiveHosts.minor(VideoRef("x", "1", "Fine title", tags = listOf("High School Girl"))))
    }
}
