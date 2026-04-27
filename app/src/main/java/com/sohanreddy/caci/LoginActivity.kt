package com.sohanreddy.caci

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.sohanreddy.caci.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var firestore: FirebaseFirestore

    private var verificationId: String? = null
    private var resendToken: PhoneAuthProvider.ForceResendingToken? = null

    private val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
        override fun onVerificationCompleted(credential: PhoneAuthCredential) {
            signInWithCredential(credential)
        }

        override fun onVerificationFailed(e: FirebaseException) {
            binding.buttonSendOtp.isEnabled = true
            Toast.makeText(this@LoginActivity, e.localizedMessage ?: "Verification failed", Toast.LENGTH_LONG).show()
        }

        override fun onCodeSent(
            verificationId: String,
            token: PhoneAuthProvider.ForceResendingToken,
        ) {
            this@LoginActivity.verificationId = verificationId
            resendToken = token

            binding.layoutOtpContainer.isVisible = true
            binding.buttonSendOtp.isEnabled = true
            Toast.makeText(this@LoginActivity, "OTP sent", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        firestore = FirebaseFirestore.getInstance()

        binding.layoutOtpContainer.isVisible = false

        binding.buttonSendOtp.setOnClickListener {
            val phone = normalizePhone(binding.editPhone.text?.toString().orEmpty())
            if (phone == null) {
                Toast.makeText(this, "Enter a valid phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            // Hide keyboard
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(binding.editPhone.windowToken, 0)
            binding.editPhone.clearFocus()
            startPhoneVerification(phone)
        }

        binding.buttonVerifyOtp.setOnClickListener {
            val code = binding.editOtp.text?.toString().orEmpty().trim()
            val id = verificationId

            if (id.isNullOrEmpty() || code.length < 6) {
                Toast.makeText(this, "Enter valid OTP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val credential = PhoneAuthProvider.getCredential(id, code)
            signInWithCredential(credential)
        }

        // Navigate to Sign Up screen
        binding.textSignUp.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun normalizePhone(raw: String): String? {
        val trimmed = raw.trim().replace(" ", "")
        if (trimmed.startsWith("+") && trimmed.length >= 12) {
            return trimmed
        }

        val digitsOnly = trimmed.filter { it.isDigit() }
        if (digitsOnly.length == 10) {
            return "+91$digitsOnly"
        }
        return null
    }

    private fun startPhoneVerification(phoneNumber: String) {
        binding.buttonSendOtp.isEnabled = false

        val options = PhoneAuthOptions.newBuilder(auth)
            .setPhoneNumber(phoneNumber)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setActivity(this)
            .setCallbacks(callbacks)
            .build()

        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    private fun signInWithCredential(credential: PhoneAuthCredential) {
        lifecycleScope.launch {
            try {
                auth.signInWithCredential(credential).await()
                val uid = auth.currentUser?.uid
                if (uid.isNullOrEmpty()) {
                    Toast.makeText(this@LoginActivity, "Authentication failed", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val userDoc = firestore.collection("users").document(uid).get().await()
                if (userDoc.exists()) {
                    startActivity(Intent(this@LoginActivity, HomeActivity::class.java))
                } else {
                    startActivity(Intent(this@LoginActivity, RegisterActivity::class.java))
                }
                finish()
            } catch (e: Exception) {
                Toast.makeText(
                    this@LoginActivity,
                    e.localizedMessage ?: "OTP verification failed",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}
