package com.carequeue.plus.data.firebase

import com.google.firebase.firestore.FirebaseFirestore

object FirebaseConfig {
    val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    // Collection names
    const val USERS = "users"
    const val BUSINESSES = "businesses"
    const val QUEUES = "queues"
    const val QUEUE_ENTRIES = "queueEntries"
    const val SERVICE_STATS = "serviceStats"
}
