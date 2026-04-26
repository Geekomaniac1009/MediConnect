package com.example.mediconnect_ai.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "patient_vitals_table",
    indices = [
        Index(value = ["patientId", "category", "recordedAt"]),
        Index(value = ["userId", "recordedAt"]),
    ],
)
data class PatientVitalRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userId: String? = null,
    val patientId: String,
    val category: String,
    val recordedAt: Long,
    val source: String = "manual_entry",
    val systolicBp: Double? = null,
    val diastolicBp: Double? = null,
    val hemoglobin: Double? = null,
    val weightKg: Double? = null,
    val spo2: Double? = null,
    val pulse: Double? = null,
    val gestationalWeek: Int? = null,
    val muacCm: Double? = null,
    val wazScore: Double? = null,
    val temperature: Double? = null,
    val ageMonths: Int? = null,
    val coughSeverity: Double? = null,
    val nightSweatsScore: Double? = null,
    val missedDosesWeek: Int? = null,
    val treatmentMonth: Int? = null,
    val fastingGlucose: Double? = null,
    val bmi: Double? = null,
    val notes: String? = null,
)
