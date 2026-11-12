package com.dlss5.optimizer.util

import java.util.concurrent.atomic.AtomicLong

/** Monotonic clock helpers that never throw and never go backwards. */
object Time {

    private val bootOriginNanos = AtomicLong(System.nanoTime())

    fun nowMs(): Long = System.currentTimeMillis()

    /** Milliseconds since this process started using our own monotonic base. */
    fun uptimeMs(): Long = (System.nanoTime() - bootOriginNanos.get()) / 1_000_000L

    fun epochSeconds(): Long = System.currentTimeMillis() / 1000L

    fun isoNowUtc(): String = isoFromEpochMs(System.currentTimeMillis())

    fun isoFromEpochMs(ms: Long): String {
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date(ms))
    }

    fun humanDuration(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> "%dh %02dm".format(h, m)
            m > 0 -> "%dm %02ds".format(m, s)
            else -> "%ds".format(s)
        }
    }
}
