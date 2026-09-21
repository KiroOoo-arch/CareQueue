package com.carequeue.plus.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueModelsTest {

    // --- Queue ---

    @Test
    fun `queue defaults represent a fresh open queue`() {
        val queue = Queue()
        assertEquals(Queue.STATUS_OPEN, queue.status)
        assertTrue(queue.isOpen)
        assertEquals(0, queue.currentNumber)
        assertEquals(1, queue.activeCounters)
        assertEquals(4.0, queue.averageServiceMinutes, 1e-9)
    }

    @Test
    fun `only OPEN queues accept customers`() {
        assertTrue(Queue(status = Queue.STATUS_OPEN).isOpen)
        assertFalse(Queue(status = Queue.STATUS_PAUSED).isOpen)
        assertFalse(Queue(status = Queue.STATUS_CLOSED).isOpen)
    }

    // --- QueueEntry ---

    @Test
    fun `waiting and called entries are active`() {
        assertTrue(QueueEntry(status = QueueEntry.STATUS_WAITING).isActive)
        assertTrue(QueueEntry(status = QueueEntry.STATUS_CALLED).isActive)
    }

    @Test
    fun `finished entries are not active`() {
        assertFalse(QueueEntry(status = QueueEntry.STATUS_SERVED).isActive)
        assertFalse(QueueEntry(status = QueueEntry.STATUS_SKIPPED).isActive)
        assertFalse(QueueEntry(status = QueueEntry.STATUS_CANCELLED).isActive)
    }

    @Test
    fun `new entry defaults to waiting with no call or serve timestamps`() {
        val entry = QueueEntry()
        assertEquals(QueueEntry.STATUS_WAITING, entry.status)
        assertEquals(null, entry.calledAt)
        assertEquals(null, entry.servedAt)
        assertTrue(entry.isActive)
    }
}
