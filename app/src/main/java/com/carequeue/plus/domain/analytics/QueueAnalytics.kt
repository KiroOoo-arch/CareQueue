package com.carequeue.plus.domain.analytics

import com.carequeue.plus.data.model.QueueEntry
import java.util.Calendar
import java.util.TimeZone

/**
 * Real service statistics derived from a queue's entry history.
 *
 * Kept free of Firebase (like [com.carequeue.plus.domain.queue.QueueRules]) so the
 * arithmetic can be unit tested. Before this existed the Analytics screen displayed
 * hardcoded placeholder numbers.
 */
data class ServiceSummary(
    val served: Int = 0,
    val skipped: Int = 0,
    val cancelled: Int = 0,
    val averageServiceMinutes: Double = 0.0,
    val averageWaitMinutes: Double = 0.0,
    /** Busiest hour of the day (0-23) by arrivals, or null when there is no data. */
    val peakHour: Int? = null
) {
    val completed: Int get() = served + skipped
    val total: Int get() = served + skipped + cancelled
    val hasData: Boolean get() = total > 0
}

object QueueAnalytics {

    fun summarize(
        entries: List<QueueEntry>,
        timeZone: TimeZone = TimeZone.getDefault()
    ): ServiceSummary {
        val served = entries.filter { it.status == QueueEntry.STATUS_SERVED }

        // How long serving actually took: called -> served.
        val serviceDurations = served.mapNotNull { entry ->
            val called = entry.calledAt ?: return@mapNotNull null
            val done = entry.servedAt ?: return@mapNotNull null
            (done - called).coerceAtLeast(0L) / 60_000.0
        }

        // How long people actually waited: joined -> called.
        val waitDurations = entries.mapNotNull { entry ->
            val called = entry.calledAt ?: return@mapNotNull null
            (called - entry.joinedAt).coerceAtLeast(0L) / 60_000.0
        }

        return ServiceSummary(
            served = served.size,
            skipped = entries.count { it.status == QueueEntry.STATUS_SKIPPED },
            cancelled = entries.count { it.status == QueueEntry.STATUS_CANCELLED },
            averageServiceMinutes = serviceDurations.averageOrZero(),
            averageWaitMinutes = waitDurations.averageOrZero(),
            peakHour = peakHour(entries, timeZone)
        )
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    /** The hour with the most arrivals. Ties resolve to the earliest hour. */
    private fun peakHour(entries: List<QueueEntry>, timeZone: TimeZone): Int? {
        if (entries.isEmpty()) return null
        val counts = IntArray(HOURS_PER_DAY)
        val calendar = Calendar.getInstance(timeZone)
        entries.forEach { entry ->
            calendar.timeInMillis = entry.joinedAt
            counts[calendar.get(Calendar.HOUR_OF_DAY)]++
        }
        val busiest = counts.maxOrNull() ?: return null
        if (busiest == 0) return null
        return counts.indexOfFirst { it == busiest }
    }

    /** e.g. hour 10 -> "10:00 - 11:00". */
    fun formatHour(hour: Int): String =
        "%02d:00 - %02d:00".format(hour, (hour + 1) % HOURS_PER_DAY)

    /** e.g. 18.4 -> "18 min"; 0.0 -> "0 min". */
    fun formatMinutes(minutes: Double): String = "${minutes.toInt()} min"

    private const val HOURS_PER_DAY = 24
}
