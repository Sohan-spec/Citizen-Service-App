package com.sohanreddy.caci

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.sohanreddy.caci.databinding.FragmentProfileBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val firestore by lazy { FirebaseFirestore.getInstance() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val genderAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, listOf("Male", "Female", "Other"))
        binding.dropdownGender.setAdapter(genderAdapter)

        loadProfile()

        binding.buttonSave.setOnClickListener { saveProfile() }
    }

    private fun loadProfile() {
        val uid = auth.currentUser?.uid ?: return
        lifecycleScope.launch {
            try {
                val snap = firestore.collection("users").document(uid).get().await()
                binding.editName.setText(snap.getString("name") ?: "")
                binding.editAddress.setText(snap.getString("address") ?: "")
                val age = snap.getLong("age")
                if (age != null) binding.editAge.setText(age.toString())
                val gender = snap.getString("gender") ?: ""
                binding.dropdownGender.setText(gender, false)
            } catch (_: Exception) { }
        }
    }

    private fun saveProfile() {
        val uid = auth.currentUser?.uid ?: return
        val name = binding.editName.text?.toString().orEmpty().trim()
        val address = binding.editAddress.text?.toString().orEmpty().trim()
        val ageText = binding.editAge.text?.toString().orEmpty().trim()
        val gender = binding.dropdownGender.text?.toString().orEmpty().trim()

        if (name.isEmpty()) {
            Toast.makeText(requireContext(), "Name is required", Toast.LENGTH_SHORT).show()
            return
        }

        val updates = mutableMapOf<String, Any>(
            "name" to name,
            "address" to address,
            "locality" to address,
            "gender" to gender,
        )
        val age = ageText.toIntOrNull()
        if (age != null) updates["age"] = age

        binding.buttonSave.isEnabled = false
        lifecycleScope.launch {
            try {
                firestore.collection("users").document(uid).update(updates).await()
                // Re-subscribe to locality topic for water notifications
                if (address.isNotEmpty()) {
                    try { FCMHelper.subscribeToLocality(address) } catch (_: Exception) { }
                }
                Toast.makeText(requireContext(), "Profile updated", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Update failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            binding.buttonSave.isEnabled = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
