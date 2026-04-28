package com.sohanreddy.caci

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.firestore.FirebaseFirestore
import com.sohanreddy.caci.databinding.ActivityResidentWaterBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Displays scheduled water releases for residents on a map with 1km radius circles.
 * Officers can still view the classic schedule list.
 */
class ResidentWaterActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityResidentWaterBinding
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    private val dateFormat = SimpleDateFormat("hh:mm a, dd MMM yyyy", Locale.getDefault())
    private var viewMode: String = VIEW_MODE_RESIDENT

    private var googleMap: GoogleMap? = null
    private val releaseMarkers = mutableListOf<Marker>()
    private val releaseCircles = mutableListOf<Circle>()
    private var releases: List<WaterRelease> = emptyList()
    private var currentLocation: LatLng? = null
    private var selectedRelease: WaterRelease? = null
    private var isInfoExpanded = false

    private val fusedLocationClient by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            enableMyLocation()
            fetchCurrentLocation()
        } else {
            moveCameraToFallback()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResidentWaterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewMode = intent.getStringExtra(EXTRA_VIEW_MODE) ?: VIEW_MODE_RESIDENT

        binding.toolbar.setNavigationOnClickListener { finish() }

        if (viewMode == VIEW_MODE_OFFICER) {
            showListMode()
            loadWaterScheduleList()
        } else {
            showMapMode()
            setupMap()
        }
    }

    private fun showListMode() {
        binding.mapContainer.isVisible = false
        binding.cardReleaseInfo.isVisible = false
        binding.scrollReleases.isVisible = false
        binding.layoutEmpty.isVisible = false
    }

    private fun showMapMode() {
        binding.mapContainer.isVisible = true
        binding.scrollReleases.isVisible = false
        binding.layoutEmpty.isVisible = false
        binding.cardReleaseInfo.isVisible = false

        binding.cardReleaseInfo.setOnClickListener {
            val release = selectedRelease ?: return@setOnClickListener
            if (!isInfoExpanded) {
                showReleaseInfo(release, expanded = true)
            }
        }

        binding.buttonInfoClose.setOnClickListener {
            hideInfoCard()
        }
    }

    private fun setupMap() {
        val mapFragment = (supportFragmentManager.findFragmentById(R.id.mapContainer) as? SupportMapFragment)
            ?: SupportMapFragment.newInstance().also { fragment ->
                supportFragmentManager.beginTransaction()
                    .replace(R.id.mapContainer, fragment)
                    .commit()
            }

        binding.mapContainer.post { mapFragment.getMapAsync(this) }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isTiltGesturesEnabled = false
        map.uiSettings.isRotateGesturesEnabled = false
        map.uiSettings.isMyLocationButtonEnabled = true

        map.setOnMarkerClickListener { marker ->
            val release = marker.tag as? WaterRelease ?: return@setOnMarkerClickListener false
            showReleaseInfo(release, expanded = false)
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, 14f))
            true
        }

        map.setOnCircleClickListener { circle ->
            val release = circle.tag as? WaterRelease ?: return@setOnCircleClickListener
            showReleaseInfo(release, expanded = false)
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(circle.center, 14f))
        }

        if (hasLocationPermission()) {
            enableMyLocation()
            fetchCurrentLocation()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        loadWaterScheduleMap()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun enableMyLocation() {
        val map = googleMap ?: return
        if (!hasLocationPermission()) return
        map.isMyLocationEnabled = true
    }

    @SuppressLint("MissingPermission")
    private fun fetchCurrentLocation() {
        if (!hasLocationPermission()) return

        lifecycleScope.launch {
            try {
                var location = fusedLocationClient.lastLocation.await()
                if (location == null) {
                    location = fusedLocationClient
                        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .await()
                }

                if (location == null) {
                    moveCameraToFallback()
                    return@launch
                }

                val latLng = LatLng(location.latitude, location.longitude)
                currentLocation = latLng
                googleMap?.animateCamera(
                    CameraUpdateFactory.newLatLngZoom(latLng, 14f),
                )

                maybeAutoSelectRelease()
            } catch (e: Exception) {
                Toast.makeText(this@ResidentWaterActivity, "Location error: ${e.message}", Toast.LENGTH_SHORT).show()
                moveCameraToFallback()
            }
        }
    }

    private fun moveCameraToFallback() {
        val fallback = LatLng(12.9716, 77.5946)
        googleMap?.moveCamera(CameraUpdateFactory.newLatLngZoom(fallback, 12f))
    }

    private fun loadWaterScheduleMap() {
        lifecycleScope.launch {
            try {
                val query = firestore.collection("waterReleases")
                    .whereEqualTo("status", "scheduled")
                    .get().await()

                releases = query.toObjects(WaterRelease::class.java)
                    .sortedBy { it.scheduledTime?.toDate()?.time ?: Long.MAX_VALUE }

                binding.layoutEmpty.isVisible = releases.isEmpty()
                renderReleaseOverlays(releases)
                maybeAutoSelectRelease()
            } catch (_: Exception) {
                binding.layoutEmpty.isVisible = true
            }
        }
    }

    private fun renderReleaseOverlays(releases: List<WaterRelease>) {
        val map = googleMap ?: return

        releaseMarkers.forEach { it.remove() }
        releaseCircles.forEach { it.remove() }
        releaseMarkers.clear()
        releaseCircles.clear()

        for (release in releases) {
            if (release.latitude == 0.0 && release.longitude == 0.0) continue

            val position = LatLng(release.latitude, release.longitude)

            val marker = map.addMarker(
                MarkerOptions()
                    .position(position)
                    .title(release.locality)
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)),
            )
            marker?.tag = release
            marker?.let { releaseMarkers.add(it) }

            val circle = map.addCircle(
                CircleOptions()
                    .center(position)
                    .radius(1000.0)
                    .strokeWidth(2f)
                    .strokeColor(0xCCFF3B30.toInt())
                    .fillColor(0x33FF3B30)
                    .clickable(true),
            )
            circle.tag = release
            releaseCircles.add(circle)
        }
    }

    private fun maybeAutoSelectRelease() {
        val location = currentLocation ?: return
        if (releases.isEmpty() || selectedRelease != null) return

        var bestRelease: WaterRelease? = null
        var bestDistance = Float.MAX_VALUE

        for (release in releases) {
            if (release.latitude == 0.0 && release.longitude == 0.0) continue
            val distance = distanceMeters(location, LatLng(release.latitude, release.longitude))
            if (distance <= 1000f && distance < bestDistance) {
                bestRelease = release
                bestDistance = distance
            }
        }

        bestRelease?.let { showReleaseInfo(it, expanded = false) }
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Float {
        val results = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
        return results[0]
    }

    private fun showReleaseInfo(release: WaterRelease, expanded: Boolean) {
        selectedRelease = release
        binding.cardReleaseInfo.isVisible = true

        binding.textInfoLocality.text = release.locality

        val timeText = release.scheduledTime?.toDate()?.let { dateFormat.format(it) }
            ?: "Time not set"
        binding.textInfoTime.text = getString(R.string.scheduled_at, timeText)
        binding.textInfoDuration.text = getString(R.string.duration_label, release.duration)

        val noteText = release.note.trim()
        binding.textInfoNote.text = if (noteText.isNotEmpty()) {
            noteText
        } else {
            getString(R.string.no_additional_notes)
        }

        binding.textInfoNote.isVisible = expanded
        binding.buttonInfoClose.isVisible = expanded
        isInfoExpanded = expanded
    }

    private fun hideInfoCard() {
        selectedRelease = null
        binding.cardReleaseInfo.isVisible = false
        binding.textInfoNote.isVisible = false
        binding.buttonInfoClose.isVisible = false
        isInfoExpanded = false
    }

    private fun loadWaterScheduleList() {
        lifecycleScope.launch {
            try {
                val query = firestore.collection("waterReleases")
                    .whereEqualTo("status", "scheduled")
                    .get().await()

                val list = query.toObjects(WaterRelease::class.java)
                    .sortedBy { it.scheduledTime?.toDate()?.time ?: Long.MAX_VALUE }

                if (list.isEmpty()) {
                    binding.layoutEmpty.isVisible = true
                    binding.scrollReleases.isVisible = false
                } else {
                    binding.layoutEmpty.isVisible = false
                    binding.scrollReleases.isVisible = true
                    binding.containerReleases.removeAllViews()

                    for (release in list) {
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

    companion object {
        const val EXTRA_VIEW_MODE = "view_mode"
        const val VIEW_MODE_RESIDENT = "resident_map"
        const val VIEW_MODE_OFFICER = "officer_list"
    }
}
