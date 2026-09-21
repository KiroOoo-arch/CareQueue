package com.carequeue.plus.data.model

data class ServiceStats(
    val queueId: String = "",
    val serviceDate: String = "",
    val completedCount: Int = 0,
    val avgServiceMinutes: Double = 0.0,
    val avgWaitMinutes: Double = 0.0
)
