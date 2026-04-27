package com.sohanreddy.caci

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
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
import com.google.firebase.firestore.ListenerRegistration
import com.sohanreddy.caci.databinding.ActivityGarbageMapBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class GarbageMapActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityGarbageMapBinding
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private var googleMap: GoogleMap? = null
    private var homeMarker: Marker? = null
    private var truckMarker: Marker? = null
    private var truckListener: ListenerRegistration? = null

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            enableMyLocationAndCenter()
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

        val mapFragment = SupportMapFragment.newInstance()
        supportFragmentManager.beginTransaction()
            .replace(R.id.mapContainer, mapFragment)
            .commitNow()

        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isTiltGesturesEnabled = false
        map.uiSettings.isRotateGesturesEnabled = false

        if (hasLocationPermission()) {
            enableMyLocationAndCenter()
        } else {
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        drawHomeLocationAndRadius()
        subscribeToTruckUpdates()
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun enableMyLocationAndCenter() {
        val map = googleMap ?: return

        if (!hasLocationPermission()) {
            return
        }

        map.isMyLocationEnabled = true

        lifecycleScope.launch {
            try {
                val location = fusedLocationClient.lastLocation.await()
                val target = if (location != null) {
                    LatLng(location.latitude, location.longitude)
                } else {
                    LatLng(12.9352, 77.6245)
                }
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 15f))
            } catch (_: Exception) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(12.9352, 77.6245), 15f))
            }
        }
    }

    private fun drawHomeLocationAndRadius() {
        val uid = auth.currentUser?.uid ?: return

        lifecycleScope.launch {
            try {
                val userDoc = firestore.collection("users").document(uid).get().await()
                val lat = userDoc.getDouble("home_lat") ?: return@launch
                val lng = userDoc.getDouble("home_lng") ?: return@launch
                val homeLatLng = LatLng(lat, lng)

                val map = googleMap ?: return@launch
                homeMarker?.remove()
                homeMarker = map.addMarker(
                    MarkerOptions()
                        .position(homeLatLng)
                        .title("Your Home")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)),
                )

                map.addCircle(
                    CircleOptions()
                        .center(homeLatLng)
                        .radius(500.0)
                        .strokeWidth(2f)
                        .strokeColor(0xAAFF3B30.toInt())
                        .fillColor(0x33FF3B30),
                )
            } catch (e: Exception) {
                Toast.makeText(this@GarbageMapActivity, "Unable to load home location", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun subscribeToTruckUpdates() {
        truckListener?.remove()
        truckListener = firestore.collection("trucks").document("truck_001")
            .addSnapshotListener { snapshot, error ->
                if (error != null || snapshot == null || !snapshot.exists()) {
                    return@addSnapshotListener
                }

                val lat = snapshot.getDouble("latitude") ?: return@addSnapshotListener
                val lng = snapshot.getDouble("longitude") ?: return@addSnapshotListener
                val truckId = snapshot.getString("truck_id") ?: "truck_001"
                val truckType = snapshot.getString("truck_type") ?: "Unknown"
                val newPosition = LatLng(lat, lng)
                val map = googleMap ?: return@addSnapshotListener

                if (truckMarker == null) {
                    truckMarker = map.addMarker(
                        MarkerOptions()
                            .position(newPosition)
                            .title("$truckId")
                            .snippet("Type: $truckType")
                            .icon(BitmapDescriptorFactory.fromResource(R.drawable.ic_truck_marker)),
                    )
                    truckMarker?.showInfoWindow()
                } else {
                    truckMarker?.title = truckId
                    truckMarker?.snippet = "Type: $truckType"
                    MarkerAnimation.animateMarkerTo(truckMarker!!, newPosition)
                    truckMarker?.showInfoWindow()
                }
            }
    }

    override fun onDestroy() {
        truckListener?.remove()
        truckListener = null
        super.onDestroy()
    }
}
