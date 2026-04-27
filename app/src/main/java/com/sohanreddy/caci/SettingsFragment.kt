package com.sohanreddy.caci

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.sohanreddy.caci.databinding.FragmentSettingsBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> refreshPermissionStatus() }

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> refreshPermissionStatus() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonRequestLocation.setOnClickListener {
            locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        binding.buttonRequestNotification.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding.buttonResetDemo.setOnClickListener {
            lifecycleScope.launch {
                try {
                    val db = FirebaseFirestore.getInstance()
                    db.collection("alert_log").document("truck_001")
                        .set(mapOf("alerted_500m" to false), com.google.firebase.firestore.SetOptions.merge())
                        .await()
                    db.collection("trucks").document("truck_001")
                        .update(mapOf("waypoint_index" to 0, "route_center" to ""))
                        .await()
                    Toast.makeText(requireContext(), "Demo reset! Open Garbage Tracker to run again", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Reset failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.buttonLogout.setOnClickListener {
            FirebaseAuth.getInstance().signOut()
            val intent = Intent(requireContext(), LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }

        refreshPermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionStatus()
    }

    private fun refreshPermissionStatus() {
        val ctx = context ?: return

        // Location
        val locGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        binding.textLocationStatus.text = if (locGranted) "Status: Granted ✓" else "Status: Denied"
        binding.buttonRequestLocation.isEnabled = !locGranted

        // Notifications
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            binding.textNotificationStatus.text = if (notifGranted) "Status: Granted ✓" else "Status: Denied"
            binding.buttonRequestNotification.isEnabled = !notifGranted
        } else {
            binding.textNotificationStatus.text = "Status: Granted ✓ (pre-Android 13)"
            binding.buttonRequestNotification.isEnabled = false
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
