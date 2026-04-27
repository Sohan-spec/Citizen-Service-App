package com.sohanreddy.caci

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.sohanreddy.caci.databinding.ActivityRegisterBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            submitRegistration()
        } else {
            Toast.makeText(this, "Location permission is required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Material3 Exposed Dropdown for Gender
        val genderOptions = listOf("Male", "Female", "Other")
        val genderAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            genderOptions,
        )
        binding.dropdownGender.setAdapter(genderAdapter)

        binding.buttonRegister.setOnClickListener {
            if (hasLocationPermission()) {
                submitRegistration()
            } else {
                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun submitRegistration() {
        val name = binding.editName.text?.toString().orEmpty().trim()
        val address = binding.editAddress.text?.toString().orEmpty().trim()
        val ageText = binding.editAge.text?.toString().orEmpty().trim()
        val age = ageText.toIntOrNull()
        val gender = binding.dropdownGender.text?.toString().orEmpty().trim()

        if (name.isEmpty() || address.isEmpty() || age == null || age <= 0 || gender.isEmpty()) {
            Toast.makeText(this, "Please fill all fields correctly", Toast.LENGTH_SHORT).show()
            return
        }

        val currentUser = auth.currentUser
        if (currentUser == null) {
            Toast.makeText(this, "User not signed in", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        lifecycleScope.launch {
            binding.buttonRegister.isEnabled = false
            try {
                val coords = getCurrentCoordinates()
                if (coords == null) {
                    Toast.makeText(
                        this@RegisterActivity,
                        "Unable to fetch location. Try again.",
                        Toast.LENGTH_LONG,
                    ).show()
                    binding.buttonRegister.isEnabled = true
                    return@launch
                }

                val fcmToken = FirebaseMessaging.getInstance().token.await()

                val payload = hashMapOf(
                    "uid" to currentUser.uid,
                    "name" to name,
                    "phone" to (currentUser.phoneNumber ?: ""),
                    "address" to address,
                    "age" to age,
                    "gender" to gender,
                    "home_lat" to coords.first,
                    "home_lng" to coords.second,
                    "fcm_token" to fcmToken,
                    "alert_sent_today" to false,
                )

                firestore.collection("users").document(currentUser.uid).set(payload).await()

                startActivity(Intent(this@RegisterActivity, HomeActivity::class.java))
                finish()
            } catch (e: Exception) {
                Toast.makeText(
                    this@RegisterActivity,
                    e.localizedMessage ?: "Registration failed",
                    Toast.LENGTH_LONG,
                ).show()
                binding.buttonRegister.isEnabled = true
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun getCurrentCoordinates(): Pair<Double, Double>? {
        val lastKnown = fusedLocationClient.lastLocation.await()
        if (lastKnown != null) {
            return lastKnown.latitude to lastKnown.longitude
        }

        val current = fusedLocationClient
            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .await()

        return current?.latitude?.let { lat ->
            current.longitude.let { lng -> lat to lng }
        }
    }
}
