package com.example.mediconnect_ai.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PatientVitalRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: PatientVitalRecord): Long

    @Query(
        """
        SELECT * FROM patient_vitals_table
        WHERE patientId = :patientId AND category = :category
        ORDER BY recordedAt DESC
        LIMIT :limit
        """
    )
    suspend fun getLatestForPatientCategory(
        patientId: String,
        category: String,
        limit: Int = 10,
    ): List<PatientVitalRecord>

    @Query(
        """
        SELECT * FROM patient_vitals_table
        WHERE patientId = :patientId
        ORDER BY recordedAt DESC
        LIMIT :limit
        """
    )
    suspend fun getLatestForPatient(
        patientId: String,
        limit: Int = 10,
    ): List<PatientVitalRecord>
}
