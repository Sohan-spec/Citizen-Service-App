package com.sohanreddy.caci

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.sohanreddy.caci.databinding.ActivityGarbageMapBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

class GarbageMapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityGarbageMapBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private var googleMap: GoogleMap? = null
    private var homeMarker: Marker? = null
    private var truckMarker: Marker? = null
    private var simulationJob: Job? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            setupWithCurrentLocation()
        } else {
            Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGarbageMapBinding.inflate(layoutInflater)
        setContentView(binding.root)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val mapFragment = (supportFragmentManager.findFragmentById(R.id.mapContainer) as? SupportMapFragment)
            ?: SupportMapFragment.newInstance().also { fragment ->
                supportFragmentManager.beginTransaction()
                    .replace(R.id.mapContainer, fragment)
                    .commit()
            }

        binding.mapContainer.post {
            mapFragment.getMapAsync(this)
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isTiltGesturesEnabled = false
        map.uiSettings.isRotateGesturesEnabled = false

        if (hasLocationPermission()) {
            setupWithCurrentLocation()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun setupWithCurrentLocation() {
        val map = googleMap ?: return
        if (!hasLocationPermission()) return

        map.isMyLocationEnabled = true

        lifecycleScope.launch {
            try {
                var location = fusedLocationClient.lastLocation.await()
                if (location == null) {
                    location = fusedLocationClient
                        .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                        .await()
                }

                if (location == null) {
                    Toast.makeText(this@GarbageMapActivity, "Unable to get location", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val userLatLng = LatLng(location.latitude, location.longitude)

                map.animateCamera(CameraUpdateFactory.newLatLngZoom(userLatLng, 15f))

                // Draw home marker + 500m radius
                homeMarker?.remove()
                homeMarker = map.addMarker(
                    MarkerOptions()
                        .position(userLatLng)
                        .title("Your Location")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)),
                )

                map.addCircle(
                    CircleOptions()
                        .center(userLatLng)
                        .radius(500.0)
                        .strokeWidth(2f)
                        .strokeColor(0xAAFF3B30.toInt())
                        .fillColor(0x33FF3B30),
                )

                // Update Firestore with current location
                val uid = auth.currentUser?.uid
                if (uid != null) {
                    val updates = hashMapOf<String, Any>(
                        "home_lat" to location.latitude,
                        "home_lng" to location.longitude,
                    )
                    val address = reverseGeocode(location.latitude, location.longitude)
                    if (address != null) updates["address"] = address
                    firestore.collection("users").document(uid).update(updates).await()
                }

                // Start local simulation around user's location
                startLocalSimulation(location.latitude, location.longitude)

            } catch (e: Exception) {
                Toast.makeText(this@GarbageMapActivity, "Location error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Client-side truck simulation: ticks every 3 seconds, writes to Firestore
     * (which triggers the cloud function for notifications), and animates smoothly.
     */
    private fun startLocalSimulation(centerLat: Double, centerLng: Double) {
        simulationJob?.cancel()

        val mPerDegLat = 111320.0
        val mPerDegLng = 111320.0 * cos(Math.toRadians(centerLat))

        // Pass-through route: outside → inside circle → back out
        val distances = intArrayOf(600, 450, 350, 200, 100, 200, 350, 500, 650)
        val waypoints = mutableListOf<LatLng>()

        for (i in distances.indices) {
            val dist = distances[i].toDouble()
            // First half approach from SW, second half exit to NE
            val angle = if (i < distances.size / 2) Math.toRadians(225.0) else Math.toRadians(45.0)
            val dLat = (dist * cos(angle)) / mPerDegLat
            val dLng = (dist * sin(angle)) / mPerDegLng
            waypoints.add(LatLng(centerLat + dLat, centerLng + dLng))
        }

        simulationJob = lifecycleScope.launch {
            for (i in waypoints.indices) {
                val wp = waypoints[i]

                // Write to Firestore → triggers checkProximityAndAlert cloud function
                try {
                    firestore.collection("trucks").document("truck_001").set(
                        mapOf(
                            "latitude" to wp.latitude,
                            "longitude" to wp.longitude,
                            "waypoint_index" to i,
                            "truck_id" to "truck_001",
                            "truck_type" to "Municipal Waste",
                            "updated_at" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                        ),
                    ).await()
                } catch (_: Exception) { }

                // Animate marker smoothly on map
                val map = googleMap ?: continue
                if (truckMarker == null) {
                    val truckIcon = createTruckMarkerIcon()
                    truckMarker = map.addMarker(
                        MarkerOptions()
                            .position(wp)
                            .title("truck_001")
                            .snippet("Type: Municipal Waste")
                            .icon(truckIcon ?: BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ORANGE)),
                    )
                    truckMarker?.showInfoWindow()
                } else {
                    MarkerAnimation.animateMarkerTo(truckMarker!!, wp)
                    truckMarker?.showInfoWindow()
                }

                // Wait 3 seconds before next step
                delay(3000L)
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun reverseGeocode(lat: Double, lng: Double): String? {
        return withContext(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(this@GarbageMapActivity, Locale.getDefault())
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    listOfNotNull(addr.subLocality, addr.locality).joinToString(", ").ifEmpty { null }
                } else null
            } catch (_: Exception) { null }
        }
    }

    private fun createTruckMarkerIcon() = try {
        val drawable = AppCompatResources.getDrawable(this, R.drawable.ic_truck_marker)
            ?: return null
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 96
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 96
        drawable.setBounds(0, 0, width, height)

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.draw(canvas)
        BitmapDescriptorFactory.fromBitmap(bitmap)
    } catch (_: Exception) { null }

    override fun onDestroy() {
        simulationJob?.cancel()
        simulationJob = null
        super.onDestroy()
    }
}
