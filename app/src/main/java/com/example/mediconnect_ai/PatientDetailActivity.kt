package com.example.mediconnect_ai

import android.app.DatePickerDialog
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.example.mediconnect_ai.database.AppDatabase
import com.example.mediconnect_ai.database.Patient
import com.example.mediconnect_ai.database.Pregnancy
import com.example.mediconnect_ai.database.Task
import com.example.mediconnect_ai.databinding.ActivityPatientDetailBinding
import com.example.mediconnect_ai.utils.AgeUtils
import com.example.mediconnect_ai.utils.PNCUtils
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import com.google.firebase.firestore.FirebaseFirestore // Import this
import android.widget.ArrayAdapter // Import this
import android.widget.Spinner // Import this


class PatientDetailActivity : BaseActivity() {

    private lateinit var binding: ActivityPatientDetailBinding
    private var currentPatient: Patient? = null
    private var patientId: Long = -1L
    private val firestoreDb = FirebaseFirestore.getInstance() // Initialize Firestore

    companion object {
        const val EXTRA_PATIENT_ID = "patient_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPatientDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        patientId = intent.getLongExtra(EXTRA_PATIENT_ID, -1L)

        // 1. Delete
        binding.btnDeletePatient.setOnClickListener { showDeleteConfirmationDialog() }

        // 2. Edit
        binding.btnEditPatient.setOnClickListener {
            val intent = Intent(this, NewPatientActivity::class.java).apply {
                putExtra(NewPatientActivity.EXTRA_PATIENT_ID, patientId)
            }
            startActivity(intent)
        }

        // 3. Report
        binding.btnDownloadReport.setOnClickListener { generateAndSharePdfReport() }

        // ✅ View Full Medical History (GLOBAL BUTTON)
        binding.btnViewFullHistory.setOnClickListener {
            val intent = Intent(this, PatientHistoryActivity::class.java)
            intent.putExtra(PatientHistoryActivity.EXTRA_PATIENT_ID, patientId)
            startActivity(intent)
        }

        binding.btnViewVitalsHistory.setOnClickListener {
            val intent = Intent(this, PatientVitalsHistoryActivity::class.java)
            intent.putExtra(PatientVitalsHistoryActivity.EXTRA_PATIENT_ID, patientId.toString())
            startActivity(intent)
        }

        // --- NEW: Log Symptoms Button ---
        val btnLogSymptoms = findViewById<Button>(R.id.btnLogSymptoms)
        btnLogSymptoms?.setOnClickListener {
            showSymptomDialog()
        }

        // 4. Register Pregnancy
        binding.btnRegisterPregnancy.setOnClickListener {
            val intent = Intent(this, AddPregnancyActivity::class.java).apply {
                putExtra("PATIENT_ID", patientId)
                putExtra("PATIENT_NAME", currentPatient?.fullName)
            }
            startActivity(intent)
        }

        // 5. Refer
        binding.btnReferPatient.setOnClickListener {
            currentPatient?.let { patient ->
                val intent = Intent(this, ReferPatientActivity::class.java).apply {
                    putExtra(ReferPatientActivity.EXTRA_PATIENT_ID, patient.id)
                }
                startActivity(intent)
            } ?: run {
                Toast.makeText(this, "Patient data not loaded yet.", Toast.LENGTH_SHORT).show()
            }
        }

        // 6. Emergency SOS Button
        binding.fabEmergencySos.setOnClickListener {
            if (patientId != -1L) {
                val intent = Intent(this, EmergencySosActivity::class.java).apply {
                    putExtra(EmergencySosActivity.EXTRA_PATIENT_ID, patientId)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Error: Patient not loaded.", Toast.LENGTH_SHORT).show()
            }
        }

        // 7. NEW: View PNC Schedule button
        binding.btnViewPncSchedule.setOnClickListener {
            if (patientId != -1L) {
                val intent = Intent(this, PNCScheduleActivity::class.java).apply {
                    putExtra(PNCScheduleActivity.EXTRA_PATIENT_ID, patientId)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Error: Patient not loaded.", Toast.LENGTH_SHORT).show()
            }
        }

        // 8. NEW: TB Register Button (Make sure btnRegisterTB exists in XML)
        val btnRegTB = findViewById<Button>(R.id.btnRegisterTB)
        btnRegTB?.setOnClickListener {
            if (patientId != -1L) {
                val intent = Intent(this, RegisterTBActivity::class.java)
                intent.putExtra(RegisterTBActivity.EXTRA_PATIENT_ID, patientId)
                startActivity(intent)
            }
        }
    }



    override fun onResume() {
        super.onResume()
        if (patientId != -1L) {
            loadPatientDetails(patientId)
        }
    }

    private fun loadPatientDetails(patientId: Long) {
        val db = AppDatabase.getInstance(applicationContext)

        lifecycleScope.launch {
            val patient = db.patientDao().getPatientById(patientId)
            val pregnancy = db.pregnancyDao().getActivePregnancyForPatient(patientId)
            val hasPncVisits = db.pncVisitDao().getPNCVisitsForPatient(patientId).isNotEmpty()

            // --- TB Module Integration ---
            val tbProfile = db.tbDao().getActiveTBProfile(patientId)
            val cardTB = findViewById<View>(R.id.cardTBDetails)
            val btnRegTB = findViewById<View>(R.id.btnRegisterTB)
            val tvTBPhase = findViewById<TextView>(R.id.tvTBPhase)

            // Handle TB Card Visibility
            if (tbProfile != null) {
                // Patient has active TB -> Show Card, Hide Register Button
                cardTB?.visibility = View.VISIBLE
                btnRegTB?.visibility = View.GONE

                tvTBPhase?.text = "Phase: ${tbProfile.treatmentPhase}"

                // Set Click Listener for Card
                cardTB?.setOnClickListener {
                    val intent = Intent(this@PatientDetailActivity, TBScheduleActivity::class.java)
                    intent.putExtra("PATIENT_ID", patientId)
                    startActivity(intent)
                }
            } else {
                // No active TB -> Hide Card, Show Register Button
                cardTB?.visibility = View.GONE
                btnRegTB?.visibility = View.VISIBLE
            }

            currentPatient = patient

            patient?.let {
                binding.tvPatientNameDetail.text = it.fullName
                binding.tvPatientIdDetail.text = "ID: ${it.id}"

                val sdf = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
                val dobString = if (it.dob > 0) sdf.format(Date(it.dob)) else "N/A"
                val formattedAge = if (it.dob > 0) AgeUtils.getFullAge(it.dob) else "N/A"
                binding.tvPatientAgeDetail.text = "Age: $formattedAge (DOB: $dobString)"

                binding.tvPatientGenderDetail.text = it.gender
                binding.tvPatientContactDetail.text = it.contactNumber.ifEmpty { "N/A" }
                binding.tvPatientAddressDetail.text = it.address.ifEmpty { "N/A" }
                binding.tvPatientAadhaarDetail.text = it.aadhaarNumber.ifEmpty { "N/A" }

                // ✅ POPULATE NEW FAMILY FIELDS
                binding.tvPatientFamilyId.text = it.familyId ?: "N/A"
                binding.tvPatientHouseholdSize.text = it.householdSize?.toString() ?: "Unknown"
                binding.tvPatientHeadOfFamily.text = if (it.isHeadOfFamily) "Yes" else "No"
                binding.tvPatientConditionsDetail.text = it.preExistingConditions.ifEmpty { "None" }

                // Show pregnancy registration button only if eligible
                if (it.gender.equals("Female", ignoreCase = true)
                    && AgeUtils.getYears(it.dob) in 18..45
                    && pregnancy == null
                ) {
                    binding.btnRegisterPregnancy.visibility = View.VISIBLE
                } else {
                    binding.btnRegisterPregnancy.visibility = View.GONE
                }
            }



            if (pregnancy != null) {
                // Active pregnancy -> show card, hide PNC button
                displayPregnancyDetails(pregnancy)
                binding.btnViewPncSchedule.visibility = View.GONE
            } else {
                // No active pregnancy
                binding.cardPregnancyDetails.visibility = View.GONE

                // If there are PNC visits, show the button
                binding.btnViewPncSchedule.visibility =
                    if (hasPncVisits) View.VISIBLE else View.GONE
            }
        }
    }

    private fun displayPregnancyDetails(pregnancy: Pregnancy) {
        binding.cardPregnancyDetails.visibility = View.VISIBLE

        val sdf = SimpleDateFormat("dd MMMM yyyy", Locale.getDefault())
        binding.tvLmpDate.text = "LMP: ${sdf.format(Date(pregnancy.lmpDate))}"
        binding.tvEdd.text = "EDD: ${sdf.format(Date(pregnancy.edd))}"

        val daysSinceLmp =
            TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - pregnancy.lmpDate)
        val trimester = when {
            daysSinceLmp <= 90 -> "1st"
            daysSinceLmp <= 180 -> "2nd"
            else -> "3rd"
        }
        binding.tvTrimester.text = "Current Trimester: $trimester"

        // Open ANC Schedule when clicking the green card
        binding.cardPregnancyDetails.setOnClickListener {
            val intent = Intent(this, ANCScheduleActivity::class.java).apply {
                putExtra(ANCScheduleActivity.EXTRA_PATIENT_ID, patientId)
            }
            startActivity(intent)
        }
        // ✅ View ANC Schedule button
        binding.btnViewAncSchedule.setOnClickListener {
            val intent = Intent(this, ANCScheduleActivity::class.java).apply {
                putExtra(ANCScheduleActivity.EXTRA_PATIENT_ID, patientId)
            }
            startActivity(intent)
        }


        // Register Delivery button
        binding.btnRegisterDelivery.setOnClickListener {
            showDeliveryDatePicker(pregnancy)
        }
    }

    private fun showDeliveryDatePicker(pregnancy: Pregnancy) {
        val calendar = Calendar.getInstance()

        val dialog = DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val chosenCal = Calendar.getInstance().apply {
                    set(Calendar.YEAR, year)
                    set(Calendar.MONTH, month)
                    set(Calendar.DAY_OF_MONTH, dayOfMonth)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val deliveryDateMillis = chosenCal.timeInMillis
                registerDeliveryAndCreatePNC(pregnancy, deliveryDateMillis)
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )

        dialog.datePicker.maxDate = System.currentTimeMillis()
        dialog.datePicker.minDate = pregnancy.lmpDate
        dialog.show()
    }

    private fun registerDeliveryAndCreatePNC(pregnancy: Pregnancy, deliveryDateMillis: Long) {
        val db = AppDatabase.getInstance(applicationContext)

        lifecycleScope.launch {
            // 1. Mark pregnancy as inactive + set deliveryDate
            val updatedPregnancy = pregnancy.copy(
                isActive = false,
                deliveryDate = deliveryDateMillis
            )
            db.pregnancyDao().update(updatedPregnancy)

            // 2. Generate 7 PNC visits
            val pncVisits = PNCUtils.generatePNCVisits(
                patientId = pregnancy.patientId,
                deliveryDate = deliveryDateMillis
            )
            // ⚠️ Make sure your DAO method name matches this:
            db.pncVisitDao().insertAll(pncVisits)

            // 3. UI feedback
            Toast.makeText(
                this@PatientDetailActivity,
                "Delivery registered. PNC visits created.",
                Toast.LENGTH_LONG
            ).show()

            // Hide pregnancy card and show PNC button
            binding.cardPregnancyDetails.visibility = View.GONE
            binding.btnViewPncSchedule.visibility = View.VISIBLE

            // Reload details (age, buttons, etc.)
            loadPatientDetails(patientId)
        }
    }

    // ================= OUTBREAK FEATURE =================

    private fun showSymptomDialog() {
        val patient = currentPatient ?: return

        val symptoms = arrayOf(
            "High Fever",
            "Severe Diarrhea",
            "Skin Rash",
            "Jaundice",
            "Cough > 2 Weeks",
            "Dengue-like Symptoms"
        )

        AlertDialog.Builder(this)
            .setTitle("Log Symptoms for Outbreak Detection")
            .setSingleChoiceItems(symptoms, -1) { dialog, which ->
                logOutbreakDataToFirestore(symptoms[which])
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun logOutbreakDataToFirestore(symptom: String) {
        val patient = currentPatient ?: return

        if (patient.latitude == 0.0 || patient.longitude == 0.0) {
            Toast.makeText(this, "Patient location not available", Toast.LENGTH_SHORT).show()
            return
        }

        val data = hashMapOf(
            "patientId" to patient.id,
            "symptom" to symptom,
            "lat" to patient.latitude,   // CHANGE "latitude" -> "lat"
            "lng" to patient.longitude,  // CHANGE "longitude" -> "lng"
            "timestamp" to System.currentTimeMillis()
        )

        firestoreDb.collection("symptom_logs")
            .add(data)
            .addOnSuccessListener {
                Toast.makeText(this, "Symptom logged for outbreak analysis", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Error logging symptom", Toast.LENGTH_SHORT).show()
            }
    }

    // ----------------- PDF REPORT -----------------

    private fun generateAndSharePdfReport() {
        if (currentPatient == null) {
            Toast.makeText(this, "Patient data not loaded yet.", Toast.LENGTH_SHORT).show()
            return
        }

        val taskDao = AppDatabase.getInstance(applicationContext).taskDao()
        lifecycleScope.launch {
            val tasks = taskDao.getTasksForPatient(patientId)
            val patient = currentPatient!!

            try {
                val pdfFile = createPdf(patient, tasks)
                sharePdf(pdfFile)
            } catch (e: Exception) {
                Toast.makeText(
                    this@PatientDetailActivity,
                    "Error creating PDF: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun createPdf(patient: Patient, tasks: List<Task>): File {
        val document = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        val paint = Paint()
        var yPosition = 40f

        paint.textSize = 20f
        paint.isFakeBoldText = true
        canvas.drawText("MediConnect AI - Patient Report", 40f, yPosition, paint)
        yPosition += 40

        paint.textSize = 14f
        paint.isFakeBoldText = false
        canvas.drawText("Name: ${patient.fullName}", 40f, yPosition, paint)
        yPosition += 20
        canvas.drawText("Age: ${AgeUtils.getFullAge(patient.dob)}", 40f, yPosition, paint)
        yPosition += 20
        canvas.drawText("Contact: ${patient.contactNumber.ifEmpty { "N/A" }}", 40f, yPosition, paint)
        yPosition += 20
        canvas.drawText("Address: ${patient.address.ifEmpty { "N/A" }}", 40f, yPosition, paint)
        yPosition += 20
        canvas.drawText("Aadhaar: ${patient.aadhaarNumber.ifEmpty { "N/A" }}", 40f, yPosition, paint)
        yPosition += 20
        canvas.drawText("Family ID: ${patient.familyId ?: "N/A"}", 40f, yPosition, paint)
        yPosition += 20
        canvas.drawText("Household Size: ${patient.householdSize ?: "Unknown"}", 40f, yPosition, paint)
        yPosition += 20
        canvas.drawText("Head of Family: ${if (patient.isHeadOfFamily) "Yes" else "No"}", 40f, yPosition, paint)
        yPosition += 20
        canvas.drawText("Conditions: ${patient.preExistingConditions.ifEmpty { "None" }}", 40f, yPosition, paint)
        yPosition += 40


        paint.textSize = 16f
        paint.isFakeBoldText = true
        canvas.drawText("Scheduled Health Events", 40f, yPosition, paint)
        yPosition += 25
        paint.textSize = 12f
        paint.isFakeBoldText = false

        val sdf = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
        if (tasks.isEmpty()) {
            canvas.drawText("- No scheduled events found.", 40f, yPosition, paint)
        } else {
            for (task in tasks) {
                val dateString = sdf.format(Date(task.dueDate))
                canvas.drawText(
                    "Date: $dateString - Event: ${task.taskDescription}",
                    40f,
                    yPosition,
                    paint
                )
                yPosition += 18
            }
        }

        document.finishPage(page)

        val file = File(cacheDir, "patient_report_${patient.id}.pdf")
        document.writeTo(FileOutputStream(file))
        document.close()
        return file
    }

    private fun sharePdf(file: File) {
        val uri =
            FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Report via..."))
    }

    // ----------------- DELETE LOGIC -----------------

    private fun showDeleteConfirmationDialog() {
        currentPatient?.let { patientToDelete ->
            AlertDialog.Builder(this)
                .setTitle("Delete Patient")
                .setMessage("Are you sure you want to delete the record for ${patientToDelete.fullName}?")
                .setPositiveButton("Yes, Delete") { _, _ -> deletePatient(patientToDelete) }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun deletePatient(patient: Patient) {
        val patientDao = AppDatabase.getInstance(applicationContext).patientDao()
        lifecycleScope.launch {
            patientDao.delete(patient)
            Toast.makeText(
                this@PatientDetailActivity,
                "Patient record deleted",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }
}