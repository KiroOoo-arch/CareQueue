package com.carequeue.plus.domain.queue

import com.carequeue.plus.data.model.Queue
import com.carequeue.plus.data.model.QueueEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueRulesTest {

    private fun openQueue(currentNumber: Int = 0) = Queue(
        status = Queue.STATUS_OPEN,
        currentNumber = currentNumber
    )

    private fun entry(
        queueNumber: Int,
        status: String,
        joinedAt: Long = 1000L,
        calledAt: Long? = null,
        servedAt: Long? = null
    ) = QueueEntry(
        queueNumber = queueNumber,
        status = status,
        joinedAt = joinedAt,
        calledAt = calledAt,
        servedAt = servedAt
    )

    // --- Queue numbering ---

    @Test
    fun `first customer gets number 1`() {
        assertEquals(1, QueueRules.nextQueueNumber(openQueue(currentNumber = 0)))
    }

    @Test
    fun `next number continues the current sequence`() {
        assertEquals(42, QueueRules.nextQueueNumber(openQueue(currentNumber = 41)))
    }

    // --- Join validation ---

    @Test
    fun `open queue can be joined`() {
        assertNull(QueueRules.validateJoin(openQueue()))
    }

    @Test
    fun `paused queue cannot be joined`() {
        assertEquals(
            "Queue is not open",
            QueueRules.validateJoin(openQueue().copy(status = Queue.STATUS_PAUSED))
        )
    }

    @Test
    fun `closed queue cannot be joined`() {
        assertEquals(
            "Queue is not open",
            QueueRules.validateJoin(openQueue().copy(status = Queue.STATUS_CLOSED))
        )
    }

    @Test
    fun `missing queue reports not found`() {
        assertEquals("Queue not found", QueueRules.validateJoin(null))
    }

    // --- Status transition rules ---

    @Test
    fun `waiting can move to called served skipped or cancelled`() {
        listOf(
            QueueEntry.STATUS_CALLED,
            QueueEntry.STATUS_SERVED,
            QueueEntry.STATUS_SKIPPED,
            QueueEntry.STATUS_CANCELLED
        ).forEach { target ->
            assertTrue(
                "WAITING -> $target should be valid",
                QueueRules.isValidTransition(QueueEntry.STATUS_WAITING, target)
            )
        }
    }

    @Test
    fun `called can move to served or skipped`() {
        assertTrue(
            QueueRules.isValidTransition(QueueEntry.STATUS_CALLED, QueueEntry.STATUS_SERVED)
        )
        assertTrue(
            QueueRules.isValidTransition(QueueEntry.STATUS_CALLED, QueueEntry.STATUS_SKIPPED)
        )
    }

    @Test
    fun `called cannot go back to waiting`() {
        assertFalse(
            QueueRules.isValidTransition(QueueEntry.STATUS_CALLED, QueueEntry.STATUS_WAITING)
        )
    }

    @Test
    fun `terminal states allow no further transitions`() {
        val allStatuses = listOf(
            QueueEntry.STATUS_WAITING,
            QueueEntry.STATUS_CALLED,
            QueueEntry.STATUS_SERVED,
            QueueEntry.STATUS_SKIPPED,
            QueueEntry.STATUS_CANCELLED
        )
        listOf(
            QueueEntry.STATUS_SERVED,
            QueueEntry.STATUS_SKIPPED,
            QueueEntry.STATUS_CANCELLED
        ).forEach { terminal ->
            allStatuses.forEach { target ->
                assertFalse(
                    "$terminal -> $target should be invalid",
                    QueueRules.isValidTransition(terminal, target)
                )
            }
        }
    }

    @Test
    fun `staying in the same state is not a transition`() {
        assertFalse(
            QueueRules.isValidTransition(QueueEntry.STATUS_WAITING, QueueEntry.STATUS_WAITING)
        )
        assertFalse(
            QueueRules.isValidTransition(QueueEntry.STATUS_CALLED, QueueEntry.STATUS_CALLED)
        )
    }

    // --- Selecting the next entry to call ---

    @Test
    fun `next to call is the lowest-numbered waiting entry`() {
        val next = QueueRules.nextEntryToCall(
            listOf(
                entry(3, QueueEntry.STATUS_WAITING),
                entry(1, QueueEntry.STATUS_SERVED),
                entry(2, QueueEntry.STATUS_WAITING),
                entry(4, QueueEntry.STATUS_CALLED),
                entry(5, QueueEntry.STATUS_CANCELLED)
            )
        )
        assertEquals(2, next?.queueNumber)
    }

    @Test
    fun `next to call is null when nobody is waiting`() {
        assertNull(
            QueueRules.nextEntryToCall(
                listOf(
                    entry(1, QueueEntry.STATUS_CALLED),
                    entry(2, QueueEntry.STATUS_SERVED)
                )
            )
        )
    }

    @Test
    fun `next to call is null for an empty list`() {
        assertNull(QueueRules.nextEntryToCall(emptyList()))
    }

    // --- Applying transitions ---

    @Test
    fun `calling an entry stamps calledAt and keeps joinedAt`() {
        val updated = QueueRules.applyTransition(
            entry(7, QueueEntry.STATUS_WAITING, joinedAt = 1000L),
            QueueEntry.STATUS_CALLED,
            now = 2000L
        )
        assertEquals(QueueEntry.STATUS_CALLED, updated.status)
        assertEquals(2000L, updated.calledAt)
        assertEquals(1000L, updated.joinedAt)
        assertNull(updated.servedAt)
    }

    @Test
    fun `serving a called entry stamps servedAt and keeps calledAt`() {
        val updated = QueueRules.applyTransition(
            entry(7, QueueEntry.STATUS_CALLED, joinedAt = 1000L, calledAt = 2000L),
            QueueEntry.STATUS_SERVED,
            now = 3000L
        )
        assertEquals(QueueEntry.STATUS_SERVED, updated.status)
        assertEquals(2000L, updated.calledAt)
        assertEquals(3000L, updated.servedAt)
    }

    @Test
    fun `serving directly from waiting stamps servedAt only`() {
        val updated = QueueRules.applyTransition(
            entry(7, QueueEntry.STATUS_WAITING),
            QueueEntry.STATUS_SERVED,
            now = 3000L
        )
        assertNull(updated.calledAt)
        assertEquals(3000L, updated.servedAt)
    }

    @Test
    fun `skipping an entry changes only its status`() {
        val updated = QueueRules.applyTransition(
            entry(7, QueueEntry.STATUS_WAITING, joinedAt = 1000L),
            QueueEntry.STATUS_SKIPPED,
            now = 2000L
        )
        assertEquals(QueueEntry.STATUS_SKIPPED, updated.status)
        assertNull(updated.calledAt)
        assertNull(updated.servedAt)
        assertEquals(1000L, updated.joinedAt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid transitions are rejected`() {
        QueueRules.applyTransition(
            entry(1, QueueEntry.STATUS_SERVED),
            QueueEntry.STATUS_CALLED,
            now = 0L
        )
    }
}
