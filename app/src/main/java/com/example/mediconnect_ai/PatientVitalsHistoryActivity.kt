package com.example.mediconnect_ai

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.mediconnect_ai.adapters.PatientVitalsAdapter
import com.example.mediconnect_ai.database.AppDatabase
import com.example.mediconnect_ai.databinding.ActivityPatientVitalsHistoryBinding
import kotlinx.coroutines.launch

class PatientVitalsHistoryActivity : BaseActivity() {

    private lateinit var binding: ActivityPatientVitalsHistoryBinding

    companion object {
        const val EXTRA_PATIENT_ID = "patient_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPatientVitalsHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()

        val patientId = intent.getStringExtra(EXTRA_PATIENT_ID).orEmpty()
        if (patientId.isBlank()) {
            Toast.makeText(this, "Error: Invalid Patient ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadVitals(patientId)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbarVitals)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbarVitals.setNavigationOnClickListener { finish() }
    }

    private fun loadVitals(patientId: String) {
        val dao = AppDatabase.getInstance(applicationContext).patientVitalRecordDao()

        lifecycleScope.launch {
            try {
                val records = dao.getAllForPatient(patientId)

                if (records.isEmpty()) {
                    binding.recyclerVitals.visibility = View.GONE
                    binding.tvNoVitals.visibility = View.VISIBLE
                    return@launch
                }

                binding.recyclerVitals.visibility = View.VISIBLE
                binding.tvNoVitals.visibility = View.GONE
                binding.recyclerVitals.layoutManager = LinearLayoutManager(this@PatientVitalsHistoryActivity)
                binding.recyclerVitals.adapter = PatientVitalsAdapter(records)
            } catch (e: Exception) {
                Toast.makeText(
                    this@PatientVitalsHistoryActivity,
                    "Failed to load vitals: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                e.printStackTrace()
            }
        }
    }
}
