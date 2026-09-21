package com.carequeue.plus.data.model

data class QueueEntry(
    val entryId: String = "",
    val queueId: String = "",
    val userId: String = "",
    val queueNumber: Int = 0,
    val status: String = STATUS_WAITING,
    val joinedAt: Long = System.currentTimeMillis(),
    val calledAt: Long? = null,
    val servedAt: Long? = null,
    val estimatedWaitMinutes: Double = 0.0,
    val recommendedReturnAt: Long? = null
) {
    companion object {
        const val STATUS_WAITING = "WAITING"
        const val STATUS_CALLED = "CALLED"
        const val STATUS_SERVED = "SERVED"
        const val STATUS_SKIPPED = "SKIPPED"
        const val STATUS_CANCELLED = "CANCELLED"
    }

    val isActive: Boolean
        get() = status == STATUS_WAITING || status == STATUS_CALLED
}
