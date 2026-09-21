package com.carequeue.plus.util

import org.junit.Assert.assertEquals
import org.junit.Test

class DateUtilsTest {

    private fun now() = System.currentTimeMillis()

    @Test
    fun `under a minute reads as just now`() {
        assertEquals("Just now", DateUtils.timeAgo(now() - 30_000))
    }

    @Test
    fun `minutes are shown for the first hour`() {
        assertEquals("5m ago", DateUtils.timeAgo(now() - 5 * 60_000))
    }

    @Test
    fun `hours are shown for the first day`() {
        assertEquals("3h ago", DateUtils.timeAgo(now() - 3 * 60 * 60_000))
    }

    @Test
    fun `days are shown after a day`() {
        assertEquals("2d ago", DateUtils.timeAgo(now() - 2 * 24 * 60 * 60_000))
    }
}
