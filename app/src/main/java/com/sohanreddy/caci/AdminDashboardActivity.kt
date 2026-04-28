package com.sohanreddy.caci

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.location.Geocoder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.sohanreddy.caci.databinding.ActivityAdminDashboardBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AdminDashboardActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityAdminDashboardBinding
    private lateinit var viewModel: WaterReleaseViewModel
    private val repository = WaterReleaseRepository()

    private var googleMap: GoogleMap? = null
    private var officerId: String = "officer123"

    private val localities = mapOf(
        "Koramangala" to LatLng(12.9352, 77.6245),
        "Indiranagar" to LatLng(12.9719, 77.6412),
        "Whitefield" to LatLng(12.9698, 77.7500),
        "Jayanagar" to LatLng(12.9250, 77.5938),
        "HSR Layout" to LatLng(12.9116, 77.6389),
    )

    private var currentLocationName: String? = null
    private var currentLocationLatLng: LatLng? = null
    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private val fullTimeFormat = SimpleDateFormat("hh:mm a, dd MMM", Locale.getDefault())

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) fetchCurrentLocation()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[WaterReleaseViewModel::class.java]

        officerId = intent.getStringExtra("officer_id") ?: "officer123"
        binding.textOfficerInfo.text = getString(R.string.water_dashboard_subtitle, officerId)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupLocalityCards()
        setupActionButtons()
        setupMap()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            fetchCurrentLocation()
        } else {
            locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun setupLocalityCards() {
        binding.cardKoramangala.setOnClickListener { showScheduleDialog("Koramangala") }
        binding.cardIndiranagar.setOnClickListener { showScheduleDialog("Indiranagar") }
        binding.cardWhitefield.setOnClickListener { showScheduleDialog("Whitefield") }
        binding.cardJayanagar.setOnClickListener { showScheduleDialog("Jayanagar") }
        binding.cardHSR.setOnClickListener { showScheduleDialog("HSR Layout") }
        binding.cardCurrentLocation.setOnClickListener {
            val name = currentLocationName
            if (name != null) {
                showScheduleDialog(name)
            } else {
                Toast.makeText(this, "Fetching current location…", Toast.LENGTH_SHORT).show()
                fetchCurrentLocation()
            }
        }
    }

    private fun setupActionButtons() {
        // My Schedules — shows all releases by this officer
        binding.buttonMySchedules.setOnClickListener { showMySchedules() }

        // View as Resident — officer can also see the full schedule like a citizen
        binding.buttonViewAsResident.setOnClickListener {
            startActivity(Intent(this, ResidentWaterActivity::class.java))
        }
    }

    // ──────────────────────────── My Schedules ────────────────────────────

    private fun showMySchedules() {
        val dialog = BottomSheetDialog(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        // Title
        container.addView(TextView(this).apply {
            text = getString(R.string.my_schedules)
            setTextColor(ContextCompat.getColor(this@AdminDashboardActivity, R.color.caci_text_primary))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        // Subtitle
        container.addView(TextView(this).apply {
            text = getString(R.string.water_dashboard_subtitle, officerId)
            setTextColor(ContextCompat.getColor(this@AdminDashboardActivity, R.color.caci_text_secondary))
            textSize = 13f
            setPadding(0, 8, 0, 32)
        })

        dialog.setContentView(container)
        dialog.show()

        // Load releases async and populate
        lifecycleScope.launch {
            try {
                val releases = repository.getReleasesForOfficer(officerId)

                if (releases.isEmpty()) {
                    container.addView(TextView(this@AdminDashboardActivity).apply {
                        text = getString(R.string.no_schedules_yet)
                        setTextColor(ContextCompat.getColor(this@AdminDashboardActivity, R.color.caci_text_secondary))
                        textSize = 14f
                        setPadding(0, 32, 0, 32)
                    })
                } else {
                    val dateFormat = SimpleDateFormat("hh:mm a, dd MMM yyyy", Locale.getDefault())
                    for (release in releases) {
                        val cardView = LayoutInflater.from(this@AdminDashboardActivity)
                            .inflate(R.layout.item_water_release, container, false)

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

                        container.addView(cardView)
                    }
                }
            } catch (_: Exception) {
                container.addView(TextView(this@AdminDashboardActivity).apply {
                    text = "Failed to load schedules"
                    setTextColor(ContextCompat.getColor(this@AdminDashboardActivity, R.color.caci_error))
                    textSize = 14f
                })
            }
        }
    }

    // ──────────────────────────── Map ────────────────────────────

    private fun setupMap() {
        val mapFragment = (supportFragmentManager.findFragmentById(R.id.mapContainer)
            as? SupportMapFragment)
            ?: SupportMapFragment.newInstance().also {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.mapContainer, it)
                    .commit()
            }
        binding.mapContainer.post { mapFragment.getMapAsync(this) }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isZoomControlsEnabled = true
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(12.9716, 77.5946), 12f))
    }

    // ──────────────────────────── GPS ────────────────────────────

    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocation() {
        val fused = LocationServices.getFusedLocationProviderClient(this)
        lifecycleScope.launch {
            try {
                var loc = fused.lastLocation.await()
                if (loc == null) {
                    loc = fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                }
                if (loc == null) return@launch

                currentLocationLatLng = LatLng(loc.latitude, loc.longitude)

                val name = withContext(Dispatchers.IO) {
                    try {
                        @Suppress("DEPRECATION")
                        val addrs = Geocoder(
                            this@AdminDashboardActivity, Locale.getDefault()
                        ).getFromLocation(loc.latitude, loc.longitude, 1)
                        if (!addrs.isNullOrEmpty()) {
                            listOfNotNull(addrs[0].subLocality, addrs[0].locality)
                                .joinToString(", ").ifEmpty { "Current Location" }
                        } else "Current Location"
                    } catch (_: Exception) { "Current Location" }
                }

                currentLocationName = name
                binding.textCurrentLocality.text = name
            } catch (_: Exception) { }
        }
    }

    // ──────────────────────────── Schedule Dialog ────────────────────────────

    private fun showScheduleDialog(locality: String) {
        val dialog = BottomSheetDialog(this)
        val dialogView = LayoutInflater.from(this)
            .inflate(R.layout.dialog_schedule_water, null)
        dialog.setContentView(dialogView)

        val textLocality = dialogView.findViewById<TextView>(R.id.textLocality)
        val editTime = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editReleaseTime)
        val dropdownDuration = dialogView.findViewById<AutoCompleteTextView>(R.id.dropdownDuration)
        val editNote = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editNote)
        val buttonNotify = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.buttonNotify)

        textLocality.text = locality

        val durations = listOf("30 minutes", "1 hour", "1.5 hours", "2 hours")
        dropdownDuration.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, durations)
        )

        var selectedTimeMillis = 0L

        editTime.setOnClickListener {
            val cal = Calendar.getInstance()
            val picker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_12H)
                .setHour(cal.get(Calendar.HOUR_OF_DAY) + 1)
                .setMinute(0)
                .setTitleText(getString(R.string.release_time))
                .build()

            picker.addOnPositiveButtonClickListener {
                cal.set(Calendar.HOUR_OF_DAY, picker.hour)
                cal.set(Calendar.MINUTE, picker.minute)
                cal.set(Calendar.SECOND, 0)
                val minTime = System.currentTimeMillis() + 3600_000L
                if (cal.timeInMillis < minTime) {
                    cal.timeInMillis = minTime
                }
                selectedTimeMillis = cal.timeInMillis
                editTime.setText(fullTimeFormat.format(Date(selectedTimeMillis)))
            }
            picker.show(supportFragmentManager, "time_picker")
        }

        buttonNotify.setOnClickListener {
            val duration = dropdownDuration.text?.toString().orEmpty().trim()
            val note = editNote.text?.toString().orEmpty().trim()

            if (selectedTimeMillis == 0L) {
                Toast.makeText(this, "Please select a release time", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (duration.isEmpty()) {
                Toast.makeText(this, "Please select a duration", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            buttonNotify.isEnabled = false
            buttonNotify.text = "Checking availability…"

            val durationMinutes = WaterReleaseRepository.parseDurationToMinutes(duration)
            val endMillis = selectedTimeMillis + durationMinutes * 60_000L

            // ───── Overlap validation ─────
            lifecycleScope.launch {
                try {
                    val conflict = repository.findOverlappingRelease(selectedTimeMillis, endMillis)

                    if (conflict != null) {
                        // Show conflict dialog
                        val conflictStart = conflict.scheduledTime?.toDate()
                        val conflictDur = WaterReleaseRepository.parseDurationToMinutes(conflict.duration)
                        val conflictEnd = Date((conflictStart?.time ?: 0L) + conflictDur * 60_000L)

                        val startStr = conflictStart?.let { timeFormat.format(it) } ?: "?"
                        val endStr = timeFormat.format(conflictEnd)

                        MaterialAlertDialogBuilder(this@AdminDashboardActivity)
                            .setTitle(getString(R.string.time_conflict_title))
                            .setMessage(getString(
                                R.string.time_conflict_message,
                                conflict.locality,
                                startStr,
                                endStr,
                            ))
                            .setPositiveButton("OK", null)
                            .show()

                        buttonNotify.isEnabled = true
                        buttonNotify.text = getString(R.string.notify_residents)
                        return@launch
                    }

                    // No conflict — proceed with scheduling
                    val coords = localities[locality]
                        ?: currentLocationLatLng
                        ?: LatLng(12.9716, 77.5946)

                    viewModel.scheduleRelease(
                        locality = locality,
                        scheduledTimeMillis = selectedTimeMillis,
                        duration = duration,
                        note = note,
                        officerId = officerId,
                        latitude = coords.latitude,
                        longitude = coords.longitude,
                    )

                    sendTopicNotification(locality, selectedTimeMillis, duration)
                    dropWaterMarker(locality, coords)
                    dialog.dismiss()

                    Snackbar.make(
                        binding.root,
                        getString(R.string.notification_sent, locality),
                        Snackbar.LENGTH_LONG,
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this@AdminDashboardActivity,
                        "Error: ${e.localizedMessage}",
                        Toast.LENGTH_SHORT,
                    ).show()
                    buttonNotify.isEnabled = true
                    buttonNotify.text = getString(R.string.notify_residents)
                }
            }
        }

        dialog.show()
    }

    // ──────────────────────────── FCM + Map ────────────────────────────

    private fun sendTopicNotification(locality: String, timeMillis: Long, duration: String) {
        val topic = FCMHelper.getTopicForLocality(locality)
        val timeStr = timeFormat.format(Date(timeMillis))

        val notifData = hashMapOf(
            "topic" to topic,
            "locality" to locality,
            "title" to "Water Supply Alert",
            "body" to "Water will be released at $timeStr in $locality. Duration: $duration. — Bharat Citizen Portal",
            "scheduledTime" to Timestamp(Date(timeMillis)),
            "createdAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
        )

        lifecycleScope.launch {
            try {
                FirebaseFirestore.getInstance()
                    .collection("waterNotifications")
                    .add(notifData).await()
            } catch (_: Exception) { }
        }
    }

    private fun dropWaterMarker(locality: String, position: LatLng) {
        val map = googleMap ?: return

        val markerIcon = try {
            val drawable = AppCompatResources.getDrawable(this, R.drawable.ic_water_tanker) ?: return
            val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
            val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
            drawable.setBounds(0, 0, w, h)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawable.draw(Canvas(bitmap))
            BitmapDescriptorFactory.fromBitmap(bitmap)
        } catch (_: Exception) {
            BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
        }

        val timeStr = timeFormat.format(Date(System.currentTimeMillis() + 3600_000L))

        val marker = map.addMarker(
            MarkerOptions()
                .position(position)
                .title(locality)
                .snippet("Release: $timeStr")
                .icon(markerIcon),
        )
        marker?.showInfoWindow()
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 14f))
        addRippleAnimation(position)
    }

    private fun addRippleAnimation(center: LatLng) {
        val map = googleMap ?: return
        val circle = map.addCircle(
            CircleOptions()
                .center(center)
                .radius(50.0)
                .strokeWidth(3f)
                .strokeColor(Color.parseColor("#661565C0"))
                .fillColor(Color.parseColor("#221565C0")),
        )

        val animator = ValueAnimator.ofFloat(50f, 800f)
        animator.duration = 2000L
        animator.repeatCount = 3
        animator.interpolator = AccelerateDecelerateInterpolator()
        animator.addUpdateListener { animation ->
            val value = animation.animatedValue as Float
            circle.radius = value.toDouble()
            val alpha = ((1f - animation.animatedFraction) * 100).toInt()
            circle.strokeColor = Color.argb(alpha, 21, 101, 192)
            circle.fillColor = Color.argb(alpha / 3, 21, 101, 192)
        }
        animator.start()

        lifecycleScope.launch {
            delay(8000L)
            circle.remove()
        }
    }
}
