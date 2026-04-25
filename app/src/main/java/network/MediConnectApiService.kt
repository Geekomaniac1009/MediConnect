package com.example.mediconnect_ai.network

import retrofit2.Call
import retrofit2.http.*

interface MediConnectApiService {

    // ── Legacy (unchanged, keeps SymptomCheckerActivity working) ──────────────
    @POST("check_symptom")
    fun checkSymptom(@Body request: SymptomRequest): Call<SymptomResponse>

    // ── EWMA + Isolation Forest Triage ────────────────────────────────────────
    @POST("api/triage")
    fun triage(@Body request: TriageRequest): Call<TriageResponse>

    // ── LLM Explainability ────────────────────────────────────────────────────
    @POST("api/explain")
    fun explain(@Body request: ExplainRequest): Call<ExplainResponse>

    // ── Multilingual Chatbot ──────────────────────────────────────────────────
    @POST("api/chat")
    fun chat(@Body request: ChatRequest): Call<ChatResponse>

    // ── Voice: Text-to-Speech ─────────────────────────────────────────────────
    @POST("api/voice/tts")
    fun textToSpeech(@Body request: TtsRequest): Call<TtsResponse>

    // ── Voice: Intent Parsing ─────────────────────────────────────────────────
    @POST("api/voice/intent")
    fun parseVoiceIntent(@Body request: IntentRequest): Call<IntentResponse>

    // ── Voice: Guided Vitals Workflow ─────────────────────────────────────────
    @GET("api/voice/guided/{category}/{step}")
    fun getGuidedPrompt(
        @Path("category") category: String,
        @Path("step") step: Int,
        @Query("lang") language: String = "en",
    ): Call<GuidedVitalsResponse>

    // ── Voice: Answer Health Question ─────────────────────────────────────────
    @POST("api/voice/answer")
    fun answerVoiceQuestion(@Body request: VoiceAnswerRequest): Call<VoiceAnswerResponse>
}