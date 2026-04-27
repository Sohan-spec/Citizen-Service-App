package com.sohanreddy.caci

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.sohanreddy.caci.databinding.ActivityHomeBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.cardGarbageTracker.setOnClickListener {
            startActivity(Intent(this, GarbageMapActivity::class.java))
        }

        binding.cardWaterUpdates.setOnClickListener {
            Toast.makeText(this, "Coming Soon", Toast.LENGTH_SHORT).show()
        }

        binding.cardKnowItAll.setOnClickListener {
            Toast.makeText(this, "Coming Soon", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        loadUserName()
        refreshFcmToken()
    }

    private fun loadUserName() {
        val uid = auth.currentUser?.uid ?: return

        lifecycleScope.launch {
            try {
                val snapshot = firestore.collection("users").document(uid).get().await()
                val name = snapshot.getString("name") ?: "Citizen"
                binding.textWelcome.text = getString(R.string.welcome_user, name)
            } catch (e: Exception) {
                binding.textWelcome.text = getString(R.string.welcome_user, "Citizen")
            }
        }
    }

    private fun refreshFcmToken() {
        val uid = auth.currentUser?.uid ?: return

        lifecycleScope.launch {
            try {
                val token = FirebaseMessaging.getInstance().token.await()
                firestore.collection("users").document(uid)
                    .update("fcm_token", token)
                    .await()
            } catch (_: Exception) {
                // Fail silently; token refresh can happen in messaging service too.
            }
        }
    }
}
