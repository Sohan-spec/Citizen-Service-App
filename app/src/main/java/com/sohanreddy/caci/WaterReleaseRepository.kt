package com.sohanreddy.caci

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.Date

class WaterReleaseRepository {

    private val firestore = FirebaseFirestore.getInstance()
    private val releasesCollection = firestore.collection("waterReleases")

    companion object {
        /** Converts duration display text to minutes. */
        fun parseDurationToMinutes(duration: String): Long = when (duration) {
            "30 minutes" -> 30L
            "1 hour" -> 60L
            "1.5 hours" -> 90L
            "2 hours" -> 120L
            else -> 60L
        }
    }

    suspend fun scheduleRelease(release: WaterRelease): String {
        val data = hashMapOf(
            "locality" to release.locality,
            "scheduledTime" to release.scheduledTime,
            "duration" to release.duration,
            "note" to release.note,
            "officerId" to release.officerId,
            "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
            "status" to "scheduled",
            "latitude" to release.latitude,
            "longitude" to release.longitude,
        )
        val docRef = releasesCollection.add(data).await()
        return docRef.id
    }

    suspend fun getReleasesForLocality(locality: String): List<WaterRelease> {
        val snapshot = releasesCollection
            .whereEqualTo("locality", locality)
            .whereEqualTo("status", "scheduled")
            .get()
            .await()
        return snapshot.toObjects(WaterRelease::class.java)
    }

    suspend fun getAllScheduledReleases(): List<WaterRelease> {
        val snapshot = releasesCollection
            .whereEqualTo("status", "scheduled")
            .get()
            .await()
        return snapshot.toObjects(WaterRelease::class.java)
            .sortedBy { it.scheduledTime?.toDate()?.time ?: Long.MAX_VALUE }
    }

    /** Returns all releases by this officer, sorted by newest first. */
    suspend fun getReleasesForOfficer(officerId: String): List<WaterRelease> {
        val snapshot = releasesCollection
            .whereEqualTo("officerId", officerId)
            .get()
            .await()
        return snapshot.toObjects(WaterRelease::class.java)
            .sortedByDescending { it.scheduledTime?.toDate()?.time ?: 0L }
    }

    /**
     * Checks for scheduling conflicts.
     * Returns the conflicting [WaterRelease] if the proposed [startMillis, endMillis] range
     * overlaps with any existing scheduled release, or null if the slot is free.
     */
    suspend fun findOverlappingRelease(startMillis: Long, endMillis: Long): WaterRelease? {
        val all = getAllScheduledReleases()
        for (existing in all) {
            val existStart = existing.scheduledTime?.toDate()?.time ?: continue
            val existDurationMin = parseDurationToMinutes(existing.duration)
            val existEnd = existStart + existDurationMin * 60_000L

            // Two intervals [a, a+d1] and [b, b+d2] overlap if a < b+d2 AND b < a+d1
            if (startMillis < existEnd && existStart < endMillis) {
                return existing
            }
        }
        return null
    }

    suspend fun getUserLocality(userId: String): String? {
        val doc = firestore.collection("users").document(userId).get().await()
        return doc.getString("locality") ?: doc.getString("address")
    }
}
