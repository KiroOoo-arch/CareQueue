package com.carequeue.plus.data.repository

import android.util.Log
import com.carequeue.plus.data.firebase.FirebaseConfig
import com.carequeue.plus.data.model.Business
import com.carequeue.plus.data.model.Queue
import com.carequeue.plus.data.model.QueueEntry
import com.carequeue.plus.domain.queue.QueueRules
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class QueueRepository {
    private val businessesRef = FirebaseConfig.db.collection(FirebaseConfig.BUSINESSES)
    private val queuesRef = FirebaseConfig.db.collection(FirebaseConfig.QUEUES)
    private val entriesRef = FirebaseConfig.db.collection(FirebaseConfig.QUEUE_ENTRIES)

    /**
     * Snapshot to QueueEntry, tolerating timestamp-shaped fields.
     * Firestore's default mapper crashes the listener (main thread) if any
     * document carries a Timestamp where a Long is expected — e.g. data
     * written by the Firebase Console or another client. Parsing manually
     * keeps one bad field from taking down the whole app.
     */
    private fun parseEntry(doc: com.google.firebase.firestore.QueryDocumentSnapshot): QueueEntry? =
        try {
            fun epoch(field: String): Long? = when (val v = doc.get(field)) {
                null -> null
                is com.google.firebase.Timestamp -> v.toDate().time
                is Number -> v.toLong()
                else -> null
            }
            QueueEntry(
                entryId = doc.id,
                queueId = doc.getString("queueId") ?: "",
                userId = doc.getString("userId") ?: "",
                queueNumber = (doc.getLong("queueNumber") ?: doc.get("queueNumber")?.let { (it as? Number)?.toLong() } ?: 0L).toInt(),
                status = doc.getString("status") ?: QueueEntry.STATUS_WAITING,
                joinedAt = epoch("joinedAt") ?: System.currentTimeMillis(),
                calledAt = epoch("calledAt"),
                servedAt = epoch("servedAt"),
                estimatedWaitMinutes = (doc.get("estimatedWaitMinutes") as? Number)?.toDouble() ?: 0.0,
                recommendedReturnAt = epoch("recommendedReturnAt")
            )
        } catch (e: Exception) {
            Log.e("QueueRepository", "Skipping unparsable entry ${doc.id}: ${e.message}")
            null
        }

    // Business operations
    suspend fun getActiveBusinesses(): Result<List<Business>> {
        return try {
            Log.d("QueueRepository", "Fetching all businesses from Firestore...")
            val snapshot = businessesRef.get().await()
            Log.d("QueueRepository", "Got ${snapshot.size()} documents from businesses collection")
            val businesses = snapshot.toObjects(Business::class.java)
            businesses.forEach { b ->
                Log.d("QueueRepository", "  Business: ${b.name} (${b.businessId}) category=${b.category} isOpen=${b.isOpen}")
            }
            Result.success(businesses)
        } catch (e: Exception) {
            Log.e("QueueRepository", "ERROR fetching businesses: ${e.message}", e)
            Result.failure(e)
        }
    }

    // Queue operations
    suspend fun getAllQueues(): Result<List<Queue>> {
        return try {
            val snapshot = queuesRef.get().await()
            Result.success(
                snapshot.documents.map { doc ->
                    (doc.toObject(Queue::class.java) ?: Queue()).copy(queueId = doc.id)
                }
            )
        } catch (e: Exception) {
            Log.e("QueueRepository", "ERROR fetching all queues: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getQueuesForBusiness(businessId: String): Result<List<Queue>> {
        return try {
            Log.d("QueueRepository", "Fetching queues for business: $businessId")
            val snapshot = queuesRef.whereEqualTo("businessId", businessId).get().await()
            Log.d("QueueRepository", "Got ${snapshot.size()} queues")
            // The Firestore document ID is the authoritative queue identifier:
            // seed data can carry a queueId field that differs from the doc ID,
            // which would silently break every queuesRef.document(queueId) lookup.
            val queues = snapshot.documents.map { doc ->
                val queue = doc.toObject(Queue::class.java) ?: Queue()
                Log.d("QueueRepository", "  Queue doc=${doc.id} field=${queue.queueId}")
                queue.copy(queueId = doc.id)
            }
            Result.success(queues)
        } catch (e: Exception) {
            Log.e("QueueRepository", "ERROR fetching queues: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun getQueue(queueId: String): Queue? {
        return try {
            val doc = queuesRef.document(queueId).get().await()
            doc.toObject(Queue::class.java)?.copy(queueId = doc.id)
        } catch (e: Exception) {
            Log.e("QueueRepository", "ERROR fetching queue $queueId: ${e.message}", e)
            null
        }
    }

    fun observeQueue(queueId: String): Flow<Queue?> = callbackFlow {
        val listener = queuesRef.document(queueId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                trySend(null)
                return@addSnapshotListener
            }
            // Keep the document ID authoritative, matching getQueuesForBusiness.
            trySend(snapshot?.toObject(Queue::class.java)?.copy(queueId = snapshot.id))
        }
        awaitClose { listener.remove() }
    }

    // Queue entry operations
    suspend fun joinQueue(queueId: String, userId: String): Result<QueueEntry> {
        return try {
            // Check if user already has an active entry in this queue.
            // Firestore transactions cannot run queries, so this check runs first.
            val existingEntries = entriesRef
                .whereEqualTo("queueId", queueId)
                .whereEqualTo("userId", userId)
                .whereIn("status", listOf(QueueEntry.STATUS_WAITING, QueueEntry.STATUS_CALLED))
                .get()
                .await()

            if (!existingEntries.isEmpty) {
                return Result.failure(Exception("You already have an active queue entry"))
            }

            val entryId = entriesRef.document().id

            // Read the queue, increment the number, and write the entry inside a
            // single transaction so concurrent joins can never get the same number.
            val entry = FirebaseConfig.db.runTransaction { transaction ->
                val queue = transaction.get(queuesRef.document(queueId))
                    .toObject(Queue::class.java)

                if (queue == null) throw Exception("Queue not found")
                QueueRules.validateJoin(queue)?.let { throw Exception(it) }

                val nextNumber = QueueRules.nextQueueNumber(queue)
                val newEntry = QueueEntry(
                    entryId = entryId,
                    queueId = queueId,
                    userId = userId,
                    queueNumber = nextNumber,
                    status = QueueEntry.STATUS_WAITING,
                    joinedAt = System.currentTimeMillis()
                )

                transaction.set(entriesRef.document(entryId), newEntry)
                transaction.update(queuesRef.document(queueId), "currentNumber", nextNumber)
                newEntry
            }.await()

            Result.success(entry)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUserActiveEntry(queueId: String, userId: String): QueueEntry? {
        return try {
            val snapshot = entriesRef
                .whereEqualTo("queueId", queueId)
                .whereEqualTo("userId", userId)
                .whereIn("status", listOf(QueueEntry.STATUS_WAITING, QueueEntry.STATUS_CALLED))
                .get()
                .await()
            snapshot.documents
                .firstOrNull()
                ?.let { (it as? com.google.firebase.firestore.QueryDocumentSnapshot)?.let(::parseEntry) }
        } catch (e: Exception) {
            Log.e(
                "QueueRepository",
                "ERROR fetching active entry for $userId in $queueId: ${e.message}",
                e
            )
            null
        }
    }

    fun observeUserActiveEntry(queueId: String, userId: String): Flow<QueueEntry?> = callbackFlow {
        val listener = entriesRef
            .whereEqualTo("queueId", queueId)
            .whereEqualTo("userId", userId)
            .whereIn("status", listOf(QueueEntry.STATUS_WAITING, QueueEntry.STATUS_CALLED))
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(null)
                    return@addSnapshotListener
                }
                trySend(
                    snapshot?.documents
                        ?.mapNotNull { (it as? com.google.firebase.firestore.QueryDocumentSnapshot)?.let(::parseEntry) }
                        ?.firstOrNull()
                )
            }
        awaitClose { listener.remove() }
    }

    fun observeWaitingEntries(queueId: String): Flow<List<QueueEntry>> = callbackFlow {
        // Equality-only query (no composite index required); ordering applied client-side.
        val listener = entriesRef
            .whereEqualTo("queueId", queueId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val entries = snapshot?.documents
                    ?.mapNotNull { (it as? com.google.firebase.firestore.QueryDocumentSnapshot)?.let(::parseEntry) }
                    ?.filter { it.status == QueueEntry.STATUS_WAITING || it.status == QueueEntry.STATUS_CALLED }
                    ?.sortedBy { it.queueNumber }
                    ?: emptyList()
                trySend(entries)
            }
        awaitClose { listener.remove() }
    }

    suspend fun callNext(queueId: String): Result<QueueEntry?> {
        return try {
            val queue = getQueue(queueId) ?: return Result.failure(Exception("Queue not found"))
            // Single-equality query (no composite index required); the selection
            // rule lives in QueueRules.nextEntryToCall.
            val snapshot = entriesRef
                .whereEqualTo("queueId", queueId)
                .get()
                .await()
            val nextEntry = QueueRules.nextEntryToCall(
                snapshot.documents.mapNotNull { (it as? com.google.firebase.firestore.QueryDocumentSnapshot)?.let(::parseEntry) }
            )

            if (nextEntry == null) {
                return Result.success(null)
            }

            // Update entry status to CALLED via the shared transition rules
            val updatedEntry = QueueRules.applyTransition(
                nextEntry,
                QueueEntry.STATUS_CALLED,
                System.currentTimeMillis()
            )
            entriesRef.document(nextEntry.entryId).set(updatedEntry).await()

            Result.success(updatedEntry)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun markServed(entryId: String): Result<Unit> {
        return try {
            entriesRef.document(entryId).update(
                mapOf(
                    "status" to QueueEntry.STATUS_SERVED,
                    "servedAt" to System.currentTimeMillis()
                )
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun skipEntry(entryId: String): Result<Unit> {
        return try {
            entriesRef.document(entryId).update("status", QueueEntry.STATUS_SKIPPED).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun cancelEntry(entryId: String): Result<Unit> {
        return try {
            entriesRef.document(entryId).update("status", QueueEntry.STATUS_CANCELLED).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPeopleAhead(queueId: String, queueNumber: Int): Int {
        return try {
            // Single-equality query (no composite index required); counted client-side.
            val snapshot = entriesRef
                .whereEqualTo("queueId", queueId)
                .get()
                .await()
            snapshot.documents
                .mapNotNull { (it as? com.google.firebase.firestore.QueryDocumentSnapshot)?.let(::parseEntry) }
                .count {
                    it != null &&
                        (it.status == QueueEntry.STATUS_WAITING || it.status == QueueEntry.STATUS_CALLED) &&
                        it.queueNumber < queueNumber
                }
        } catch (e: Exception) {
            Log.e(
                "QueueRepository",
                "ERROR counting people ahead in $queueId before #$queueNumber: ${e.message}",
                e
            )
            0
        }
    }

    suspend fun getUserHistory(userId: String): List<QueueEntry> {
        return try {
            val snapshot = entriesRef
                .whereEqualTo("userId", userId)
                .whereIn(
                    "status",
                    listOf(
                        QueueEntry.STATUS_SERVED,
                        QueueEntry.STATUS_SKIPPED,
                        QueueEntry.STATUS_CANCELLED
                    )
                )
                .get()
                .await()
            snapshot.documents
                .mapNotNull { (it as? com.google.firebase.firestore.QueryDocumentSnapshot)?.let(::parseEntry) }
                // Sort client-side by the most relevant timestamp: docs whose
                // servedAt is null (skipped/cancelled) would be dropped by a
                // server-side orderBy("servedAt").
                .sortedByDescending { it.servedAt ?: it.calledAt ?: it.joinedAt }
        } catch (e: Exception) {
            Log.e("QueueRepository", "ERROR fetching history for $userId: ${e.message}", e)
            emptyList()
        }
    }
}
