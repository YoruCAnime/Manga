package com.yoruc.mangacracker.core

import okhttp3.Dns
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

/**
 * Names the PC's own lookup can't find (a home router's DNS failed for Anikoto's new video host, 2026-11-26, while
 * public DNS answered at once) are asked of public DNS-over-HTTPS (Cloudflare, then Google), by address so the asking
 * needs no lookup itself. The PC's own answer always comes first; answers are kept 10 minutes.
 */
object FallbackDns : Dns {
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private val SERVERS = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/resolve")

    override fun lookup(hostname: String): List<InetAddress> {
        try {
            return Dns.SYSTEM.lookup(hostname)
        } catch (e: UnknownHostException) {
            cache[hostname]?.takeIf { System.currentTimeMillis() - it.first < 600_000 }?.let { return it.second }
            val found = SERVERS.firstNotNullOfOrNull { s -> runCatching { ask(s, hostname) }.getOrNull()?.takeIf { it.isNotEmpty() } }
                ?: throw e
            cache[hostname] = System.currentTimeMillis() to found
            return found
        }
    }

    /** One DoH JSON question (A records). */
    internal fun ask(server: String, hostname: String): List<InetAddress> {
        val c = URI("$server?name=${java.net.URLEncoder.encode(hostname, Charsets.UTF_8)}&type=A").toURL().openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/dns-json")
        c.connectTimeout = 6_000; c.readTimeout = 6_000
        val text = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        return Regex("\"type\"\\s*:\\s*1\\s*,[^}]*?\"data\"\\s*:\\s*\"(\\d{1,3}(?:\\.\\d{1,3}){3})\"").findAll(text)
            .map { InetAddress.getByAddress(hostname, it.groupValues[1].split('.').map { b -> b.toInt().toByte() }.toByteArray()) }.toList()
    }
}
