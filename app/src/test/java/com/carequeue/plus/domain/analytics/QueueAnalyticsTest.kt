package com.carequeue.plus.domain.analytics

import com.carequeue.plus.data.model.QueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class QueueAnalyticsTest {

    private val utc = TimeZone.getTimeZone("UTC")

    private fun utcMillis(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long {
        val c = Calendar.getInstance(utc)
        c.clear()
        c.set(year, month - 1, day, hour, minute, 0)
        return c.timeInMillis
    }

    private fun entry(
        number: Int = 1,
        status: String = QueueEntry.STATUS_SERVED,
        joinedAt: Long = utcMillis(2026, 1, 1, 9),
        calledAt: Long? = null,
        servedAt: Long? = null
    ) = QueueEntry(
        entryId = "e$number",
        queueId = "q1",
        userId = "u1",
        queueNumber = number,
        status = status,
        joinedAt = joinedAt,
        calledAt = calledAt,
        servedAt = servedAt
    )

    @Test
    fun `empty history produces an empty summary`() {
        val summary = QueueAnalytics.summarize(emptyList(), utc)

        assertEquals(0, summary.served)
        assertEquals(0, summary.skipped)
        assertEquals(0, summary.cancelled)
        assertEquals(0.0, summary.averageServiceMinutes, 0.001)
        assertEquals(0.0, summary.averageWaitMinutes, 0.001)
        assertNull(summary.peakHour)
        assertFalse(summary.hasData)
    }

    @Test
    fun `counts entries by terminal status`() {
        val summary = QueueAnalytics.summarize(
            listOf(
                entry(1, QueueEntry.STATUS_SERVED),
                entry(2, QueueEntry.STATUS_SERVED),
                entry(3, QueueEntry.STATUS_SKIPPED),
                entry(4, QueueEntry.STATUS_CANCELLED),
                entry(5, QueueEntry.STATUS_WAITING)
            ),
            utc
        )

        assertEquals(2, summary.served)
        assertEquals(1, summary.skipped)
        assertEquals(1, summary.cancelled)
        // Waiting entries are not part of the history totals.
        assertEquals(4, summary.total)
        assertEquals(3, summary.completed)
        // WAITING entries are still counted as arrivals for the peak hour.
        assertTrue(summary.hasData)
    }

    @Test
    fun `average service time is measured from called to served`() {
        val summary = QueueAnalytics.summarize(
            listOf(
                entry(
                    number = 1,
                    joinedAt = utcMillis(2026, 1, 1, 9),
                    calledAt = utcMillis(2026, 1, 1, 9, 10),
                    servedAt = utcMillis(2026, 1, 1, 9, 20) // 10 min of service
                ),
                entry(
                    number = 2,
                    joinedAt = utcMillis(2026, 1, 1, 9),
                    calledAt = utcMillis(2026, 1, 1, 9, 30),
                    servedAt = utcMillis(2026, 1, 1, 9, 50) // 20 min of service
                )
            ),
            utc
        )

        assertEquals(15.0, summary.averageServiceMinutes, 0.001)
    }

    @Test
    fun `average wait is measured from joined to called`() {
        val summary = QueueAnalytics.summarize(
            listOf(
                entry(
                    number = 1,
                    joinedAt = utcMillis(2026, 1, 1, 9, 0),
                    calledAt = utcMillis(2026, 1, 1, 9, 5), // waited 5
                    servedAt = utcMillis(2026, 1, 1, 9, 6)
                ),
                entry(
                    number = 2,
                    joinedAt = utcMillis(2026, 1, 1, 9, 0),
                    calledAt = utcMillis(2026, 1, 1, 9, 25), // waited 25
                    servedAt = utcMillis(2026, 1, 1, 9, 26)
                )
            ),
            utc
        )

        assertEquals(15.0, summary.averageWaitMinutes, 0.001)
    }

    @Test
    fun `entries without timestamps are excluded from averages`() {
        val summary = QueueAnalytics.summarize(
            listOf(
                // Served but never stamped: must not poison the average with a 0.
                entry(1, QueueEntry.STATUS_SERVED, calledAt = null, servedAt = null),
                entry(
                    number = 2,
                    calledAt = utcMillis(2026, 1, 1, 9, 0),
                    servedAt = utcMillis(2026, 1, 1, 9, 8)
                )
            ),
            utc
        )

        assertEquals(2, summary.served)
        assertEquals(8.0, summary.averageServiceMinutes, 0.001)
        // Only the second entry has a calledAt, so only it contributes a wait.
        assertEquals(0.0, summary.averageWaitMinutes, 0.001)
    }

    @Test
    fun `skipped and cancelled entries contribute no service time`() {
        val summary = QueueAnalytics.summarize(
            listOf(
                entry(1, QueueEntry.STATUS_SKIPPED, calledAt = utcMillis(2026, 1, 1, 9), servedAt = null),
                entry(2, QueueEntry.STATUS_CANCELLED, calledAt = null, servedAt = null)
            ),
            utc
        )

        assertEquals(0.0, summary.averageServiceMinutes, 0.001)
        assertEquals(0, summary.served)
    }

    @Test
    fun `negative service duration is clamped to zero`() {
        // Clock skew or a Console edit could stamp servedAt before calledAt.
        val summary = QueueAnalytics.summarize(
            listOf(
                entry(
                    joinedAt = utcMillis(2026, 1, 1, 10),
                    calledAt = utcMillis(2026, 1, 1, 10),
                    servedAt = utcMillis(2026, 1, 1, 9)
                )
            ),
            utc
        )

        assertEquals(0.0, summary.averageServiceMinutes, 0.001)
        // The wait is joined -> called, which is a genuine zero here.
        assertEquals(0.0, summary.averageWaitMinutes, 0.001)
    }

    @Test
    fun `peak hour is the busiest arrival hour`() {
        val summary = QueueAnalytics.summarize(
            listOf(
                entry(1, joinedAt = utcMillis(2026, 1, 1, 10, 5)),
                entry(2, joinedAt = utcMillis(2026, 1, 1, 10, 45)),
                entry(3, joinedAt = utcMillis(2026, 1, 1, 14, 0)),
                entry(4, joinedAt = utcMillis(2026, 1, 1, 11, 0))
            ),
            utc
        )

        assertEquals(10, summary.peakHour)
        assertEquals("10:00 - 11:00", QueueAnalytics.formatHour(summary.peakHour!!))
    }

    @Test
    fun `peak hour ties resolve to the earliest hour`() {
        val summary = QueueAnalytics.summarize(
            listOf(
                entry(1, joinedAt = utcMillis(2026, 1, 1, 15)),
                entry(2, joinedAt = utcMillis(2026, 1, 1, 9))
            ),
            utc
        )

        assertEquals(9, summary.peakHour)
    }

    @Test
    fun `peak hour respects the given time zone`() {
        // 23:30 UTC is 07:30 the next day in UTC+8.
        val entries = listOf(entry(1, joinedAt = utcMillis(2026, 1, 1, 23, 30)))

        assertEquals(23, QueueAnalytics.summarize(entries, utc).peakHour)
        assertEquals(
            7,
            QueueAnalytics.summarize(entries, TimeZone.getTimeZone("Asia/Singapore")).peakHour
        )
    }

    @Test
    fun `midnight wraps in the formatted range`() {
        assertEquals("23:00 - 00:00", QueueAnalytics.formatHour(23))
    }

    @Test
    fun `minutes format without decimals`() {
        assertEquals("18 min", QueueAnalytics.formatMinutes(18.4))
        assertEquals("0 min", QueueAnalytics.formatMinutes(0.0))
    }
}
