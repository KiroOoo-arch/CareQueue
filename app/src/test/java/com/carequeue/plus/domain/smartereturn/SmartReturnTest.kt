package com.carequeue.plus.domain.smartereturn

import com.carequeue.plus.data.model.Queue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartReturnTest {

    private fun queue(
        averageServiceMinutes: Double = 4.0,
        activeCounters: Int = 1
    ) = Queue(
        averageServiceMinutes = averageServiceMinutes,
        activeCounters = activeCounters
    )

    // --- Baseline wait estimation ---

    @Test
    fun `baseline wait is zero for nobody ahead`() {
        assertEquals(0.0, SmartReturn.calculateEstimatedWait(0, 4.0), 1e-9)
    }

    @Test
    fun `baseline wait is peopleAhead times averageServiceMinutes`() {
        assertEquals(20.0, SmartReturn.calculateEstimatedWait(5, 4.0), 1e-9)
        assertEquals(7.5, SmartReturn.calculateEstimatedWait(3, 2.5), 1e-9)
    }

    @Test
    fun `baseline wait never goes negative`() {
        assertEquals(0.0, SmartReturn.calculateEstimatedWait(-3, 4.0), 1e-9)
    }

    // --- Live adjustment with active counters ---

    @Test
    fun `wait is divided across active counters`() {
        val wait = SmartReturn.calculateEstimatedWaitWithAdjustments(
            peopleAhead = 10,
            averageServiceMinutes = 4.0,
            activeCounters = 2
        )
        assertEquals(20.0, wait, 1e-9)
    }

    @Test
    fun `single counter matches the baseline`() {
        val wait = SmartReturn.calculateEstimatedWaitWithAdjustments(
            peopleAhead = 5,
            averageServiceMinutes = 4.0,
            activeCounters = 1
        )
        assertEquals(20.0, wait, 1e-9)
    }

    @Test
    fun `non-positive counters fall back to baseline`() {
        val wait = SmartReturn.calculateEstimatedWaitWithAdjustments(
            peopleAhead = 5,
            averageServiceMinutes = 4.0,
            activeCounters = 0
        )
        assertEquals(20.0, wait, 1e-9)
    }

    // --- Recommended return time ---

    @Test
    fun `recommended return subtracts the safety buffer`() {
        val before = System.currentTimeMillis()
        val result = SmartReturn.calculateRecommendedReturnTime(30.0)
        val after = System.currentTimeMillis()
        // 30 min wait - 5 min buffer = 25 min from now
        assertTrue(
            result in (before + 25L * 60 * 1000 - 500)..(after + 25L * 60 * 1000 + 500)
        )
    }

    @Test
    fun `waits shorter than the safety buffer return immediately`() {
        val before = System.currentTimeMillis()
        val result = SmartReturn.calculateRecommendedReturnTime(4.0)
        val after = System.currentTimeMillis()
        assertTrue(result in before..after)
    }

    // --- Full calculation ---

    @Test
    fun `full smart return combines wait estimation and return time`() {
        val before = System.currentTimeMillis()
        val result = SmartReturn.calculateSmartReturn(
            peopleAhead = 8,
            queue = queue(averageServiceMinutes = 5.0, activeCounters = 2)
        )
        val after = System.currentTimeMillis()

        // 8 people x (5.0 / 2 counters) = 20 min wait
        assertEquals(20.0, result.estimatedWaitMinutes, 1e-9)
        assertEquals(5.0, result.safetyBufferMinutes, 1e-9)
        assertEquals(8, result.peopleAhead)
        assertEquals(5.0, result.averageServiceMinutes, 1e-9)
        assertEquals(2, result.activeCounters)
        // (20 - 5) min from now
        assertTrue(
            result.recommendedReturnAt in (before + 15L * 60 * 1000 - 500)..(after + 15L * 60 * 1000 + 500)
        )
    }

    // --- Formatting ---

    @Test
    fun `wait under a minute is labeled as such`() {
        assertEquals("Less than 1 min", SmartReturn.formatWaitTime(0.5))
    }

    @Test
    fun `minutes are formatted plainly`() {
        assertEquals("30 min", SmartReturn.formatWaitTime(30.0))
    }

    @Test
    fun `hours and minutes are combined for long waits`() {
        assertEquals("1h 30m", SmartReturn.formatWaitTime(90.0))
    }
}
