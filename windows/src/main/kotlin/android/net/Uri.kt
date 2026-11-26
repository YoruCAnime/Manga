package android.net

import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/** Windows stand-in for the bits of android.net.Uri the shared code uses (route encoding, file links). */
class Uri private constructor(private val s: String) {
    override fun toString() = s

    companion object {
        fun encode(s: String?): String = URLEncoder.encode(s ?: "", Charsets.UTF_8).replace("+", "%20")
        fun decode(s: String?): String = URLDecoder.decode(s ?: "", Charsets.UTF_8)
        fun parse(s: String) = Uri(s)
        fun fromFile(f: File) = Uri(f.toURI().toString())
    }
}
