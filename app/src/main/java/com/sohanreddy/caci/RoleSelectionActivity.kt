package com.sohanreddy.caci

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.sohanreddy.caci.databinding.ActivityRoleSelectionBinding

class RoleSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoleSelectionBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoleSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonBack.setOnClickListener { finish() }

        binding.cardOfficer.setOnClickListener {
            startActivity(Intent(this, OfficerLoginActivity::class.java))
        }

        binding.cardResident.setOnClickListener {
            val intent = Intent(this, ResidentWaterActivity::class.java)
                .putExtra(
                    ResidentWaterActivity.EXTRA_VIEW_MODE,
                    ResidentWaterActivity.VIEW_MODE_RESIDENT,
                )
            startActivity(intent)
        }
    }
}
