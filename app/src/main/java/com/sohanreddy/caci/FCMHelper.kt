package com.sohanreddy.caci

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/**
 * Handles FCM topic subscription for locality-based water release notifications.
 * Topic naming convention: locality_ + lowercase locality name with underscores.
 */
object FCMHelper {

    private fun localityToTopic(locality: String): String {
        return "locality_" + locality.lowercase()
            .replace(" ", "_")
            .replace(",", "")
            .trim()
    }

    suspend fun subscribeToLocality(locality: String) {
        val topic = localityToTopic(locality)
        FirebaseMessaging.getInstance().subscribeToTopic(topic).await()
    }

    suspend fun unsubscribeFromLocality(locality: String) {
        val topic = localityToTopic(locality)
        FirebaseMessaging.getInstance().unsubscribeFromTopic(topic).await()
    }

    fun getTopicForLocality(locality: String): String {
        return localityToTopic(locality)
    }
}
