package com.carequeue.plus.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Date formatting helpers.
 *
 * [SimpleDateFormat] is *not* thread-safe, so no instance is ever shared: a fresh
 * formatter is created per call. These are called from both the main thread and
 * coroutines (e.g. the Firestore listeners), so a cached singleton here would
 * eventually produce garbled output or throw under concurrent use.
 */
object DateUtils {
    private const val DISPLAY_PATTERN = "MMM dd, yyyy HH:mm"
    private const val TIME_PATTERN = "HH:mm"
    private const val DATE_PATTERN = "MMM dd, yyyy"

    private fun formatter(pattern: String): SimpleDateFormat =
        SimpleDateFormat(pattern, Locale.getDefault())

    fun formatDateTime(timestamp: Long): String =
        formatter(DISPLAY_PATTERN).format(Date(timestamp))

    fun formatTime(timestamp: Long): String =
        formatter(TIME_PATTERN).format(Date(timestamp))

    fun formatDate(timestamp: Long): String =
        formatter(DATE_PATTERN).format(Date(timestamp))

    fun timeAgo(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val minutes = diff / (60 * 1000)
        val hours = minutes / 60
        val days = hours / 24

        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            else -> "${days}d ago"
        }
    }
}
