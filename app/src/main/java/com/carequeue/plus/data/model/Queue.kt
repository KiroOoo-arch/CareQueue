package com.carequeue.plus.data.model

data class Queue(
    val queueId: String = "",
    val businessId: String = "",
    val name: String = "",
    val status: String = STATUS_OPEN,
    val currentNumber: Int = 0,
    /**
     * The number currently being served, persisted so "Now Serving" survives a
     * customer being marked served (the live waiting list no longer contains them).
     * 0 means nobody has been called yet today.
     */
    val nowServing: Int = 0,
    val averageServiceMinutes: Double = 4.0,
    val activeCounters: Int = 1,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_OPEN = "OPEN"
        const val STATUS_PAUSED = "PAUSED"
        const val STATUS_CLOSED = "CLOSED"
    }

    val isOpen: Boolean get() = status == STATUS_OPEN
}
