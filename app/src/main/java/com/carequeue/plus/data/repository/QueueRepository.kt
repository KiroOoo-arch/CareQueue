package com.carequeue.plus.data.repository

import android.util.Log
import com.carequeue.plus.data.firebase.FirebaseConfig
import com.carequeue.plus.data.model.Business
import com.carequeue.plus.data.model.Queue
import com.carequeue.plus.data.model.QueueEntry
import com.carequeue.plus.domain.analytics.QueueAnalytics
import com.carequeue.plus.domain.analytics.ServiceSummary
import com.carequeue.plus.domain.queue.QueueRules
import com.carequeue.plus.domain.smartereturn.SmartReturn
import com.google.firebase.firestore.DocumentSnapshot
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class QueueRepository {
    private val businessesRef = FirebaseConfig.db.collection(FirebaseConfig.BUSINESSES)
    private val queuesRef = FirebaseConfig.db.collection(FirebaseConfig.QUEUES)
    private val entriesRef = FirebaseConfig.db.collection(FirebaseConfig.QUEUE_ENTRIES)

    /** Thrown when another admin claimed the entry between our query and our write. */
    private class EntryClaimedException : Exception("Entry already claimed")

    /**
     * Snapshot to QueueEntry, tolerating timestamp-shaped fields.
     * Firestore's default mapper crashes the listener (main thread) if any
     * document carries a Timestamp where a Long is expected — e.g. data
     * written by the Firebase Console or another client. Parsing manually
     * keeps one bad field from taking down the whole app.
     *
     * Accepts a plain [DocumentSnapshot] so the same parser serves queries,
     * listeners and transactions.
     */
    private fun parseEntry(doc: DocumentSnapshot?): QueueEntry? {
        if (doc == null || !doc.exists()) return null
        return try {
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
                queueNumber = (doc.getLong("queueNumber")
                    ?: (doc.get("queueNumber") as? Number)?.toLong()
                    ?: 0L).toInt(),
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
    }

    // Business operations
    suspend fun getActiveBusinesses(): Result<List<Business>> {
        return try {
            Log.d("QueueRepository", "Fetching all businesses from Firestore...")
            val snapshot = businessesRef.get().await()
            Log.d("QueueRepository", "Got ${snapshot.size()} documents from businesses collection")
            // The document ID is authoritative, matching getQueuesForBusiness.
            val businesses = snapshot.documents.mapNotNull { doc ->
                doc.toObject(Business::class.java)?.copy(businessId = doc.id)
            }
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

    /**
     * Emits [Result.success] with the queue (or null when the document is gone),
     * and [Result.failure] when the listener itself errors. Surfacing the
     * difference matters: a failed listener used to look identical to "still
     * loading", leaving the screen on a spinner forever.
     */
    fun observeQueue(queueId: String): Flow<Result<Queue?>> = callbackFlow {
        val listener = queuesRef.document(queueId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                trySend(Result.failure(error))
                return@addSnapshotListener
            }
            // Keep the document ID authoritative, matching getQueuesForBusiness.
            trySend(Result.success(snapshot?.toObject(Queue::class.java)?.copy(queueId = snapshot.id)))
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

            // Snapshot the SmartReturn estimate onto the entry so the recommendation
            // survives the app being closed. The live value is still recomputed on
            // screen; these fields were previously parsed but never written.
            persistSmartReturn(entry)

            Result.success(entry)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Best-effort: a failed snapshot write must never fail a successful join. */
    private suspend fun persistSmartReturn(entry: QueueEntry) {
        try {
            val queue = getQueue(entry.queueId) ?: return
            val peopleAhead = getPeopleAhead(entry.queueId, entry.queueNumber)
            val estimate = SmartReturn.calculateSmartReturn(peopleAhead, queue)
            entriesRef.document(entry.entryId).update(
                mapOf(
                    "estimatedWaitMinutes" to estimate.estimatedWaitMinutes,
                    "recommendedReturnAt" to estimate.recommendedReturnAt
                )
            ).await()
        } catch (e: Exception) {
            Log.w("QueueRepository", "Could not persist SmartReturn for ${entry.entryId}: ${e.message}")
        }
    }

    /**
     * Queues belonging to the businesses this admin created, so one admin cannot see
     * (or manage) another business's line. Businesses without a matching owner are
     * simply not shown to anybody.
     */
    suspend fun getQueuesForAdmin(adminId: String): Result<List<Queue>> {
        return try {
            val ownedBusinessIds = businessesRef
                .whereEqualTo("createdBy", adminId)
                .get()
                .await()
                .documents
                .map { it.id }

            if (ownedBusinessIds.isEmpty()) {
                return Result.success(emptyList())
            }

            val queues = queuesRef
                .whereIn("businessId", ownedBusinessIds)
                .get()
                .await()
                .documents
                .map { doc -> (doc.toObject(Queue::class.java) ?: Queue()).copy(queueId = doc.id) }

            Result.success(queues)
        } catch (e: Exception) {
            Log.e("QueueRepository", "ERROR fetching queues for admin $adminId: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** Real service statistics computed from this queue's entry history. */
    suspend fun getQueueStats(queueId: String): Result<ServiceSummary> {
        return try {
            // Equality-only query (no composite index required).
            val snapshot = entriesRef.whereEqualTo("queueId", queueId).get().await()
            Result.success(QueueAnalytics.summarize(snapshot.documents.mapNotNull(::parseEntry)))
        } catch (e: Exception) {
            Log.e("QueueRepository", "ERROR computing stats for $queueId: ${e.message}", e)
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
            snapshot.documents.firstNotNullOfOrNull(::parseEntry)
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
                    Log.e("QueueRepository", "Active-entry listener failed: ${error.message}", error)
                    trySend(null)
                    return@addSnapshotListener
                }
                trySend(snapshot?.documents?.firstNotNullOfOrNull(::parseEntry))
            }
        awaitClose { listener.remove() }
    }

    fun observeWaitingEntries(queueId: String): Flow<List<QueueEntry>> = callbackFlow {
        // Equality-only query (no composite index required); ordering applied client-side.
        val listener = entriesRef
            .whereEqualTo("queueId", queueId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("QueueRepository", "Waiting-list listener failed: ${error.message}", error)
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val entries = snapshot?.documents
                    ?.mapNotNull(::parseEntry)
                    ?.filter { it.status == QueueEntry.STATUS_WAITING || it.status == QueueEntry.STATUS_CALLED }
                    ?.sortedBy { it.queueNumber }
                    ?: emptyList()
                trySend(entries)
            }
        awaitClose { listener.remove() }
    }

    /**
     * Calls the earliest waiting customer.
     *
     * The candidate is picked by a query (transactions cannot query), then claimed
     * with a compare-and-set inside a transaction: if another admin called the same
     * entry in the meantime the write is discarded and we re-query. Without this,
     * two admins tapping "Call Next" at the same moment would both mark the same
     * customer CALLED and silently skip nobody.
     */
    suspend fun callNext(queueId: String): Result<QueueEntry?> {
        return try {
            repeat(CALL_NEXT_ATTEMPTS) {
                val queue = getQueue(queueId)
                    ?: return Result.failure(Exception("Queue not found"))

                val snapshot = entriesRef.whereEqualTo("queueId", queueId).get().await()
                val candidate = QueueRules.nextEntryToCall(snapshot.documents.mapNotNull(::parseEntry))
                    ?: return Result.success(null)

                val claimed: QueueEntry? = try {
                    FirebaseConfig.db.runTransaction { transaction ->
                        val ref = entriesRef.document(candidate.entryId)
                        val current = parseEntry(transaction.get(ref))
                            ?: throw EntryClaimedException()
                        if (current.status != QueueEntry.STATUS_WAITING) {
                            throw EntryClaimedException()
                        }
                        val updated = QueueRules.applyTransition(
                            current,
                            QueueEntry.STATUS_CALLED,
                            System.currentTimeMillis()
                        )
                        transaction.set(ref, updated)
                        // Persist who is being served so the customer-facing screen
                        // keeps showing #N after that entry leaves the waiting list.
                        transaction.update(
                            queuesRef.document(queueId),
                            "nowServing",
                            updated.queueNumber
                        )
                        updated
                    }.await()
                } catch (e: EntryClaimedException) {
                    Log.d("QueueRepository", "Entry ${candidate.entryId} taken; retrying")
                    null
                }

                if (claimed != null) return Result.success(claimed)
            }
            Result.failure(Exception("Could not call the next customer. Please try again."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Writes [newStatus] for one entry, validating against [QueueRules] and doing the
     * read-modify-write inside a transaction. Going through the shared state machine
     * means the tested transition rules are the same ones production enforces.
     */
    private suspend fun transitionEntry(entryId: String, newStatus: String): Result<QueueEntry> {
        return try {
            val updated = FirebaseConfig.db.runTransaction { transaction ->
                val ref = entriesRef.document(entryId)
                val entry = parseEntry(transaction.get(ref))
                    ?: throw Exception("Queue entry not found")

                // Repeated taps (or a stale list) must not surface as an error.
                if (entry.status == newStatus) return@runTransaction entry

                val next = QueueRules.applyTransition(entry, newStatus, System.currentTimeMillis())
                transaction.set(ref, next)
                next
            }.await()
            Result.success(updated)
        } catch (e: Exception) {
            Log.e("QueueRepository", "ERROR transitioning $entryId -> $newStatus: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun markServed(entryId: String): Result<QueueEntry> =
        transitionEntry(entryId, QueueEntry.STATUS_SERVED)

    suspend fun skipEntry(entryId: String): Result<QueueEntry> =
        transitionEntry(entryId, QueueEntry.STATUS_SKIPPED)

    /**
     * Cancels the caller's own entry.
     *
     * [userId] is verified against the stored document: without it any signed-in
     * user could cancel (or skip) somebody else's place in the line just by
     * knowing an entry ID.
     */
    suspend fun cancelEntry(entryId: String, userId: String): Result<QueueEntry> {
        return try {
            val result = FirebaseConfig.db.runTransaction { transaction ->
                val ref = entriesRef.document(entryId)
                val entry = parseEntry(transaction.get(ref))
                    ?: throw Exception("Queue entry not found")

                if (entry.userId != userId) {
                    throw Exception("You can only cancel your own queue entry")
                }
                if (entry.status == QueueEntry.STATUS_CANCELLED) {
                    return@runTransaction entry
                }

                val next = QueueRules.applyTransition(
                    entry,
                    QueueEntry.STATUS_CANCELLED,
                    System.currentTimeMillis()
                )
                transaction.set(ref, next)
                next
            }.await()
            Result.success(result)
        } catch (e: Exception) {
            Log.e("QueueRepository", "ERROR cancelling $entryId: ${e.message}", e)
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
                .mapNotNull(::parseEntry)
                .count {
                    it.isActive && it.queueNumber < queueNumber
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
                .mapNotNull(::parseEntry)
                // Sort client-side by the most relevant timestamp: docs whose
                // servedAt is null (skipped/cancelled) would be dropped by a
                // server-side orderBy("servedAt").
                .sortedByDescending { it.servedAt ?: it.calledAt ?: it.joinedAt }
        } catch (e: Exception) {
            Log.e("QueueRepository", "ERROR fetching history for $userId: ${e.message}", e)
            emptyList()
        }
    }

    private companion object {
        /** How many times callNext re-queries after losing a race for an entry. */
        const val CALL_NEXT_ATTEMPTS = 3
    }
}
