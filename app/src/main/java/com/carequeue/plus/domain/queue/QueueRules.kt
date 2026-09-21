package com.carequeue.plus.domain.queue

import com.carequeue.plus.data.model.Queue
import com.carequeue.plus.data.model.QueueEntry

/**
 * Pure queue business rules, kept free of Firebase so they can be unit tested.
 * The repository's Firestore queries and transactions must stay in sync with
 * these rules (e.g. callNext's query mirrors [nextEntryToCall]).
 */
object QueueRules {

    /** Allowed status transitions; statuses absent from the map are terminal. */
    val VALID_TRANSITIONS: Map<String, Set<String>> = mapOf(
        QueueEntry.STATUS_WAITING to setOf(
            QueueEntry.STATUS_CALLED,
            QueueEntry.STATUS_SERVED,
            QueueEntry.STATUS_SKIPPED,
            QueueEntry.STATUS_CANCELLED
        ),
        QueueEntry.STATUS_CALLED to setOf(
            QueueEntry.STATUS_SERVED,
            QueueEntry.STATUS_SKIPPED
        )
    )

    /** The number the next joining customer receives. */
    fun nextQueueNumber(queue: Queue): Int = queue.currentNumber + 1

    /** Returns an error message when the queue cannot be joined, or null when it can. */
    fun validateJoin(queue: Queue?): String? = when {
        queue == null -> "Queue not found"
        !queue.isOpen -> "Queue is not open"
        else -> null
    }

    fun isValidTransition(from: String, to: String): Boolean =
        VALID_TRANSITIONS[from]?.contains(to) == true

    /** The next customer to call: the earliest-numbered entry still WAITING. */
    fun nextEntryToCall(entries: List<QueueEntry>): QueueEntry? =
        entries.filter { it.status == QueueEntry.STATUS_WAITING }
            .minByOrNull { it.queueNumber }

    /**
     * Returns [entry] advanced to [newStatus], stamping the relevant timestamps.
     * Throws [IllegalArgumentException] for transitions the rules do not allow.
     */
    fun applyTransition(
        entry: QueueEntry,
        newStatus: String,
        now: Long = System.currentTimeMillis()
    ): QueueEntry {
        require(isValidTransition(entry.status, newStatus)) {
            "Invalid status transition: ${entry.status} -> $newStatus"
        }
        return entry.copy(
            status = newStatus,
            calledAt = if (newStatus == QueueEntry.STATUS_CALLED) now else entry.calledAt,
            servedAt = if (newStatus == QueueEntry.STATUS_SERVED) now else entry.servedAt
        )
    }
}
