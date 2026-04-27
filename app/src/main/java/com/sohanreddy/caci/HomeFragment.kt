package com.sohanreddy.caci

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.sohanreddy.caci.databinding.FragmentHomeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Locale

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.cardGarbageTracker.setOnClickListener {
            startActivity(Intent(requireContext(), GarbageMapActivity::class.java))
        }
        binding.cardWaterUpdates.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.coming_soon), Toast.LENGTH_SHORT).show()
        }
        binding.cardKnowItAll.setOnClickListener {
            Toast.makeText(requireContext(), getString(R.string.coming_soon), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        loadUserProfile()
        updateLiveLocation()
    }

    private fun loadUserProfile() {
        val uid = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                val snap = firestore.collection("users").document(uid).get().await()
                val name = snap.getString("name") ?: "Citizen"
                val area = snap.getString("address") ?: getString(R.string.location_default)
                binding.textWelcome.text = getString(R.string.welcome_user, name)
                binding.textLocation.text = area
            } catch (_: Exception) {
                binding.textWelcome.text = getString(R.string.welcome_user, "Citizen")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun updateLiveLocation() {
        val ctx = context ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val uid = auth.currentUser?.uid ?: return
        val fused = LocationServices.getFusedLocationProviderClient(ctx)

        lifecycleScope.launch {
            try {
                var loc = fused.lastLocation.await()
                if (loc == null) loc = fused.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                if (loc == null) return@launch

                val areaName = withContext(Dispatchers.IO) {
                    try {
                        @Suppress("DEPRECATION")
                        val addrs = Geocoder(ctx, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)
                        if (!addrs.isNullOrEmpty()) listOfNotNull(addrs[0].subLocality, addrs[0].locality).joinToString(", ").ifEmpty { null }
                        else null
                    } catch (_: Exception) { null }
                }
                if (areaName != null) {
                    binding.textLocation.text = areaName
                    firestore.collection("users").document(uid).update(
                        mapOf("home_lat" to loc.latitude, "home_lng" to loc.longitude, "address" to areaName),
                    ).await()
                }
            } catch (_: Exception) { }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
