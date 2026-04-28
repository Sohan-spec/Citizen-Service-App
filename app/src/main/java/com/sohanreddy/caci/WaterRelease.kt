package com.sohanreddy.caci

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp

data class WaterRelease(
    @DocumentId val id: String = "",
    val locality: String = "",
    val scheduledTime: Timestamp? = null,
    val duration: String = "",
    val note: String = "",
    val officerId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    val status: String = "scheduled",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)
