package com.carequeue.plus.data.model

data class Queue(
    val queueId: String = "",
    val businessId: String = "",
    val name: String = "",
    val status: String = STATUS_OPEN,
    val currentNumber: Int = 0,
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
