package com.carequeue.plus.data.firebase

import com.carequeue.plus.util.NotificationHelper
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class CareQueueMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        // Data payloads are the primary path; fall back to a notification payload
        // if the server sent one with the message.
        val title = remoteMessage.data["title"]
            ?: remoteMessage.notification?.title
            ?: "CareQueue+"
        val body = remoteMessage.data["body"]
            ?: remoteMessage.notification?.body

        if (body.isNullOrBlank()) return

        NotificationHelper.showQueueNotification(this, title, body)
    }

    override fun onNewToken(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        scope.launch { TokenManager.saveTokenForUser(uid, token) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
