package com.sohanreddy.caci

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.firebase.firestore.FirebaseFirestore
import com.sohanreddy.caci.databinding.ActivityResidentWaterBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Displays ALL scheduled water releases across every locality, sorted by time.
 * Accessible to both residents and officers (via "View as Resident" button).
 */
class ResidentWaterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResidentWaterBinding
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResidentWaterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        loadWaterSchedule()
    }

    private fun loadWaterSchedule() {
        lifecycleScope.launch {
            try {
                // Fetch ALL scheduled releases — transparency for every citizen
                val query = firestore.collection("waterReleases")
                    .whereEqualTo("status", "scheduled")
                    .get().await()

                val releases = query.toObjects(WaterRelease::class.java)
                    .sortedBy { it.scheduledTime?.toDate()?.time ?: Long.MAX_VALUE }

                if (releases.isEmpty()) {
                    binding.layoutEmpty.isVisible = true
                    binding.scrollReleases.isVisible = false
                } else {
                    binding.layoutEmpty.isVisible = false
                    binding.scrollReleases.isVisible = true
                    binding.containerReleases.removeAllViews()

                    val dateFormat = SimpleDateFormat("hh:mm a, dd MMM yyyy", Locale.getDefault())

                    for (release in releases) {
                        val cardView = LayoutInflater.from(this@ResidentWaterActivity)
                            .inflate(R.layout.item_water_release, binding.containerReleases, false)

                        cardView.findViewById<TextView>(R.id.textLocality).text = release.locality

                        val timeText = release.scheduledTime?.toDate()?.let { dateFormat.format(it) }
                            ?: "Time not set"
                        cardView.findViewById<TextView>(R.id.textTime).text =
                            getString(R.string.scheduled_at, timeText)

                        cardView.findViewById<TextView>(R.id.textDuration).text =
                            getString(R.string.duration_label, release.duration)

                        val noteView = cardView.findViewById<TextView>(R.id.textNote)
                        if (release.note.isNotEmpty()) {
                            noteView.text = release.note
                            noteView.isVisible = true
                        }

                        binding.containerReleases.addView(cardView)
                    }
                }
            } catch (_: Exception) {
                binding.layoutEmpty.isVisible = true
                binding.scrollReleases.isVisible = false
            }
        }
    }
}
