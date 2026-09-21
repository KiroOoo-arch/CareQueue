package com.carequeue.plus.data.repository

import com.carequeue.plus.data.firebase.FirebaseConfig
import com.carequeue.plus.data.firebase.TokenManager
import com.carequeue.plus.data.model.User
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class AuthRepository {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val usersRef = FirebaseConfig.db.collection(FirebaseConfig.USERS)

    val currentUserId: String? get() = auth.currentUser?.uid
    val isLoggedIn: Boolean get() = auth.currentUser != null

    suspend fun login(email: String, password: String): Result<User> {
        return try {
            val result = auth.signInWithEmailAndPassword(email, password).await()
            val uid = result.user?.uid ?: return Result.failure(Exception("Login failed"))
            val user = getUser(uid)
            if (user != null) {
                syncFcmTokenInBackground()
                Result.success(user)
            } else {
                Result.failure(Exception("User data not found"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun register(name: String, email: String, password: String, role: String = User.ROLE_CUSTOMER): Result<User> {
        return try {
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val uid = result.user?.uid ?: return Result.failure(Exception("Registration failed"))

            val user = User(
                uid = uid,
                name = name,
                email = email,
                role = role,
                createdAt = System.currentTimeMillis()
            )
            try {
                usersRef.document(uid).set(user).await()
            } catch (e: Exception) {
                // Roll back the just-created auth account; otherwise the user is
                // stuck with an account that can never log in ("User data not found").
                try {
                    result.user?.delete()?.await()
                } catch (_: Exception) {
                    // Rollback is best-effort; surface the original failure.
                }
                return Result.failure(e)
            }
            syncFcmTokenInBackground()
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUser(uid: String): User? {
        return try {
            val doc = usersRef.document(uid).get().await()
            doc.toObject(User::class.java)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getCurrentUser(): User? {
        val uid = currentUserId ?: return null
        return getUser(uid)
    }

    fun logout() {
        auth.signOut()
    }

    /** Saves the device's FCM token to the user document without blocking the auth flow. */
    private fun syncFcmTokenInBackground() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                TokenManager.syncCurrentUserToken()
            } catch (_: Exception) {
                // Best-effort only.
            }
        }
    }
}
