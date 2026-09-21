package com.carequeue.plus.domain.smartereturn

import com.carequeue.plus.data.model.Queue
import kotlin.math.max

object SmartReturn {
    // Safety buffer in minutes (subtracted from estimated wait)
    private const val SAFETY_BUFFER_MINUTES = 5.0

    /**
     * Level 1 (Baseline): peopleAhead * averageServiceMinutes
     */
    fun calculateEstimatedWait(
        peopleAhead: Int,
        averageServiceMinutes: Double
    ): Double {
        return max(0.0, peopleAhead * averageServiceMinutes)
    }

    /**
     * Level 2 (Live adjustment): Adjusts baseline with active counters
     */
    fun calculateEstimatedWaitWithAdjustments(
        peopleAhead: Int,
        averageServiceMinutes: Double,
        activeCounters: Int
    ): Double {
        if (activeCounters <= 0) return calculateEstimatedWait(peopleAhead, averageServiceMinutes)
        val adjustedServiceTime = averageServiceMinutes / activeCounters
        return max(0.0, peopleAhead * adjustedServiceTime)
    }

    /**
     * Calculate recommended return time based on estimated wait
     */
    fun calculateRecommendedReturnTime(
        estimatedWaitMinutes: Double,
        safetyBufferMinutes: Double = SAFETY_BUFFER_MINUTES
    ): Long {
        val waitMillis = ((estimatedWaitMinutes - safetyBufferMinutes) * 60 * 1000).toLong()
        return System.currentTimeMillis() + max(0L, waitMillis)
    }

    /**
     * Full SmartReturn calculation combining wait estimation and return time
     */
    fun calculateSmartReturn(
        peopleAhead: Int,
        queue: Queue
    ): SmartReturnResult {
        val estimatedWait = calculateEstimatedWaitWithAdjustments(
            peopleAhead = peopleAhead,
            averageServiceMinutes = queue.averageServiceMinutes,
            activeCounters = queue.activeCounters
        )
        val recommendedReturnAt = calculateRecommendedReturnTime(estimatedWait)
        val safetyBuffer = SAFETY_BUFFER_MINUTES

        return SmartReturnResult(
            estimatedWaitMinutes = estimatedWait,
            recommendedReturnAt = recommendedReturnAt,
            safetyBufferMinutes = safetyBuffer,
            peopleAhead = peopleAhead,
            averageServiceMinutes = queue.averageServiceMinutes,
            activeCounters = queue.activeCounters
        )
    }

    /**
     * Format estimated wait for display
     */
    fun formatWaitTime(minutes: Double): String {
        return when {
            minutes < 1 -> "Less than 1 min"
            minutes < 60 -> "${minutes.toInt()} min"
            else -> {
                val hours = minutes.toInt() / 60
                val mins = minutes.toInt() % 60
                "${hours}h ${mins}m"
            }
        }
    }
}

data class SmartReturnResult(
    val estimatedWaitMinutes: Double,
    val recommendedReturnAt: Long,
    val safetyBufferMinutes: Double,
    val peopleAhead: Int,
    val averageServiceMinutes: Double,
    val activeCounters: Int
)
