package com.sohanreddy.caci

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.sohanreddy.caci.databinding.ActivityOfficerLoginBinding

class OfficerLoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOfficerLoginBinding

    companion object {
        // Hardcoded dummy credentials for testing
        private const val VALID_OFFICER_ID = "officer123"
        private const val VALID_PASSWORD = "admin@water"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOfficerLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonBack.setOnClickListener { finish() }

        binding.buttonLogin.setOnClickListener {
            val officerId = binding.editOfficerId.text?.toString().orEmpty().trim()
            val password = binding.editPassword.text?.toString().orEmpty().trim()

            // Hide keyboard
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(binding.editPassword.windowToken, 0)

            if (officerId == VALID_OFFICER_ID && password == VALID_PASSWORD) {
                binding.textError.isVisible = false
                val intent = Intent(this, AdminDashboardActivity::class.java)
                intent.putExtra("officer_id", officerId)
                startActivity(intent)
                finish()
            } else {
                binding.textError.isVisible = true
            }
        }
    }
}
