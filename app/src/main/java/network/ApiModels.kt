package com.example.mediconnect_ai.network

import com.google.gson.annotations.SerializedName

// ─── Legacy symptom checker (unchanged) ───────────────────────────────────────
//data class SymptomRequest(val symptom: String, val language: String = "English")
//data class SymptomResponse(val suggestion: String)

// ─── Triage (EWMA + Isolation Forest) ─────────────────────────────────────────

data class VitalsVisit(
    @SerializedName("visit_number") val visitNumber: Int,
    // Maternal
    @SerializedName("systolic_bp")  val systolicBp: Double? = null,
    @SerializedName("diastolic_bp") val diastolicBp: Double? = null,
    val hemoglobin: Double? = null,
    @SerializedName("weight_kg")    val weightKg: Double? = null,
    val spo2: Double? = null,
    val pulse: Double? = null,
    @SerializedName("gestational_week") val gestationalWeek: Int? = null,
    // Child
    @SerializedName("muac_cm")   val muacCm: Double? = null,
    @SerializedName("waz_score") val wazScore: Double? = null,
    val temperature: Double? = null,
    @SerializedName("age_months") val ageMonths: Int? = null,
    // TB
    @SerializedName("cough_severity")     val coughSeverity: Double? = null,
    @SerializedName("night_sweats_score") val nightSweatsScore: Double? = null,
    @SerializedName("missed_doses_week")  val missedDosesWeek: Int? = null,
    @SerializedName("treatment_month")    val treatmentMonth: Int? = null,
    // General
    @SerializedName("fasting_glucose") val fastingGlucose: Double? = null,
    val bmi: Double? = null,
)

data class TriageRequest(
    val category: String,
    @SerializedName("patient_id") val patientId: String,
    val history: List<VitalsVisit>,
)

data class RuleFlag(
    val feature: String,
    val label: String,
    val value: Double,
    val threshold: Double,
    val severity: String,
    val message: String,
)

data class TriageResponse(
    @SerializedName("risk_level")    val riskLevel: String,
    @SerializedName("anomaly_score") val anomalyScore: Double,
    @SerializedName("if_raw_score")  val ifRawScore: Double,
    @SerializedName("rule_flags")    val ruleFlags: List<RuleFlag>,
    @SerializedName("drift_summary") val driftSummary: Map<String, Double>,
    @SerializedName("explanation_context") val explanationContext: Map<String, Any>,
    @SerializedName("patient_id")    val patientId: String? = null,
)

// ─── Explain ──────────────────────────────────────────────────────────────────

data class ExplainRequest(
    @SerializedName("explanation_context") val explanationContext: Map<String, Any>,
    val audience: String = "asha",
    val language: String = "en",
)

data class ExplainResponse(
    val explanation: String,
    val audience: String,
    val language: String,
    @SerializedName("risk_level") val riskLevel: String?,
)

// ─── Chat ─────────────────────────────────────────────────────────────────────

data class ChatTurn(val role: String, val parts: List<String>)

data class ChatRequest(
    val message: String,
    val language: String = "en",
    @SerializedName("patient_context") val patientContext: Map<String, Any>? = null,
    val history: List<ChatTurn> = emptyList(),
)

data class ChatResponse(
    val response: String,
    val language: String,
    @SerializedName("tts_audio_b64") val ttsAudioB64: String?,
)

// ─── Voice ────────────────────────────────────────────────────────────────────

data class TtsRequest(val text: String, val language: String = "en")
data class TtsResponse(
    @SerializedName("audio_b64") val audioB64: String?,
    val language: String,
    val format: String,
)

data class IntentRequest(val transcript: String, val language: String = "en")
data class IntentResponse(
    val intent: String,
    val entities: Map<String, Any>?,
    val language: String?,
    val confidence: Double?,
)

data class GuidedVitalsResponse(
    val done: Boolean,
    val step: Int?,
    @SerializedName("total_steps") val totalSteps: Int?,
    val message: String,
    @SerializedName("tts") val ttsAudioB64: String?,
)

data class VoiceAnswerRequest(
    val question: String,
    val language: String = "en",
    @SerializedName("patient_context") val patientContext: Map<String, Any>? = null,
)

data class VoiceAnswerResponse(
    val answer: String,
    @SerializedName("tts") val ttsAudioB64: String?,
)