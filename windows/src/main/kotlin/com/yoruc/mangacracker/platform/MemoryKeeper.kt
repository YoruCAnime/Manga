package com.yoruc.mangacracker.platform

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pictures are decoded into native (Skia) memory that is handed back only when Java's garbage collector runs, and
 * with the small heap that hardly happens on its own. This checks every 8 s and runs a quick collection when the
 * app's memory passes [LIMIT_MB] (a few ms with the serial collector), so old pages really go.
 */
object MemoryKeeper {
    private const val LIMIT_MB = 280L
    private val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean

    /** The process's committed memory in MB (about Task Manager's "memory"). */
    fun usedMb(): Long = (os?.committedVirtualMemorySize ?: 0L) / (1024 * 1024)

    fun start() {
        Desktop.scope.launch {
            while (true) {
                delay(8_000)
                if (usedMb() > LIMIT_MB) System.gc()
            }
        }
    }

    /** After a picture-heavy screen closes (the reader). */
    fun release() { Desktop.scope.launch { delay(500); System.gc() } }
}
