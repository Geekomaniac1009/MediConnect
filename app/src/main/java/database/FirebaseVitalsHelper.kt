package com.example.mediconnect_ai.firestore

import android.util.Log
import com.example.mediconnect_ai.database.PatientVitalRecord
import com.google.firebase.firestore.FirebaseFirestore

object FirebaseVitalsHelper {

    private const val TAG = "FirebaseVitalsHelper"
    private val db = FirebaseFirestore.getInstance()

    fun saveVitalRecord(record: PatientVitalRecord, callback: (Boolean, String?) -> Unit = { _, _ -> }) {
        val safeUserId = record.userId?.takeIf { it.isNotBlank() } ?: "unknown"
        val docId = "${safeUserId}_${record.patientId}_${record.recordedAt}_${record.id}"

        db.collection("patient_vitals")
            .document(docId)
            .set(record)
            .addOnSuccessListener {
                Log.d(TAG, "Vitals synced. docId=$docId")
                callback(true, null)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to sync vitals: ${e.localizedMessage}")
                callback(false, e.localizedMessage)
            }
    }
}
