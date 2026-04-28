package com.sohanreddy.caci

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val title = message.notification?.title ?: message.data["title"] ?: "Civic Alert"
        val body = message.notification?.body ?: message.data["body"] ?: "A new update is available."

        // Determine channel based on topic or data
        val topic = message.from ?: ""
        val isWater = topic.contains("locality_") || message.data["type"] == "water"

        showNotification(title, body, isWater)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FirebaseFirestore.getInstance().collection("users").document(uid)
                    .update("fcm_token", token)
                    .await()
            } catch (_: Exception) {
                // Token update will be retried from app flows.
            }
        }
    }

    private fun showNotification(title: String, body: String, isWater: Boolean) {
        createNotificationChannels()

        val channelId = if (isWater) WATER_CHANNEL_ID else GARBAGE_CHANNEL_ID
        val notifId = if (isWater) WATER_NOTIFICATION_ID else GARBAGE_NOTIFICATION_ID

        val intent = if (isWater) {
            Intent(this, ResidentWaterActivity::class.java).putExtra(
                ResidentWaterActivity.EXTRA_VIEW_MODE,
                ResidentWaterActivity.VIEW_MODE_RESIDENT,
            )
        } else {
            Intent(this, GarbageMapActivity::class.java)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(this).notify(notifId, notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val garbageChannel = NotificationChannel(
                GARBAGE_CHANNEL_ID,
                "Garbage Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notifications for nearby garbage truck alerts"
            }
            manager.createNotificationChannel(garbageChannel)

            val waterChannel = NotificationChannel(
                WATER_CHANNEL_ID,
                "Water Supply Alerts",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notifications for water release schedules"
            }
            manager.createNotificationChannel(waterChannel)
        }
    }

    companion object {
        private const val GARBAGE_CHANNEL_ID = "garbage_alerts"
        private const val WATER_CHANNEL_ID = "water_alerts"
        private const val GARBAGE_NOTIFICATION_ID = 1001
        private const val WATER_NOTIFICATION_ID = 1002
    }
}
