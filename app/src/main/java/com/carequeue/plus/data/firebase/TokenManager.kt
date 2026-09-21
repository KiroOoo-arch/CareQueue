package com.carequeue.plus.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

object TokenManager {

    /** Saves [token] to the signed-in user's document; no-op if nobody is signed in. */
    suspend fun saveTokenForCurrentUser(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        saveTokenForUser(uid, token)
    }

    suspend fun saveTokenForUser(uid: String, token: String) {
        try {
            FirebaseConfig.db.collection(FirebaseConfig.USERS)
                .document(uid)
                .update("fcmToken", token)
                .await()
        } catch (_: Exception) {
            // Best-effort: a missing or stale token must never break auth flows.
        }
    }

    /** Fetches the device's current FCM token and saves it for the signed-in user. */
    suspend fun syncCurrentUserToken() {
        val token = FirebaseMessaging.getInstance().token.await()
        saveTokenForCurrentUser(token)
    }
}
