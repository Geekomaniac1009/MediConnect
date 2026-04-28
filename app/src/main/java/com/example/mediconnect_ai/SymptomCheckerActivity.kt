package com.example.mediconnect_ai

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Build
import android.speech.RecognizerIntent
import android.text.Layout
import android.util.Base64
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.mediconnect_ai.database.AppDatabase
import com.example.mediconnect_ai.database.ChatMessage
import com.example.mediconnect_ai.database.ChatMessageDao
import com.example.mediconnect_ai.database.PatientVitalRecord
import com.example.mediconnect_ai.database.PatientVitalRecordDao
import com.example.mediconnect_ai.databinding.ActivitySymptomCheckerBinding
import com.example.mediconnect_ai.firestore.FirebaseVitalsHelper
import com.example.mediconnect_ai.network.ChatRequest
import com.example.mediconnect_ai.network.ChatResponse
import com.example.mediconnect_ai.network.ExplainRequest
import com.example.mediconnect_ai.network.ExplainResponse
import com.example.mediconnect_ai.network.GuidedVitalsResponse
import com.example.mediconnect_ai.network.IntentRequest
import com.example.mediconnect_ai.network.IntentResponse
import com.example.mediconnect_ai.network.RetrofitClient
import com.example.mediconnect_ai.network.SymptomRequest
import com.example.mediconnect_ai.network.SymptomResponse
import com.example.mediconnect_ai.network.TriageRequest
import com.example.mediconnect_ai.network.TriageResponse
import com.example.mediconnect_ai.network.VitalsVisit
import com.example.mediconnect_ai.network.VoiceAnswerRequest
import com.example.mediconnect_ai.network.VoiceAnswerResponse
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SymptomCheckerActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySymptomCheckerBinding
    // Database variables
    private lateinit var db: AppDatabase
    private lateinit var chatMessageDao: ChatMessageDao
    private lateinit var patientVitalRecordDao: PatientVitalRecordDao

    private val SPEECH_REQUEST_CODE = 123
    private val RECORD_AUDIO_PERMISSION_CODE = 1
    private var guidedCategory: String = "maternal"
    private var guidedStep: Int = 0
    private var isGuidedFlowActive: Boolean = false
    private var mediaPlayer: MediaPlayer? = null
    private val triageCategories = listOf("maternal", "child", "tb", "general")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySymptomCheckerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize the database and DAO
        db = AppDatabase.getInstance(applicationContext)
        chatMessageDao = db.chatMessageDao()
        patientVitalRecordDao = db.patientVitalRecordDao()

        setupLanguageSpinner()
        setupCategorySpinner()
        prefillContextInputs()

        // Load previous chat history when the screen opens
        loadChatHistory()

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    clearChatHistoryAndExit()
                }
            }
        )

        binding.btnSendSymptom.setOnClickListener {
            val symptomText = binding.etSymptomInput.text.toString()
            if (symptomText.isNotBlank()) {
                addMessageToChat(symptomText, true)
                handleAssistantQuery(symptomText)
                binding.etSymptomInput.text.clear()
            } else {
                Toast.makeText(this, "Please describe a symptom", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnVoiceInput.setOnClickListener {
            checkPermissionAndStartVoiceInput()
        }

        binding.btnRunTriage.setOnClickListener {
            runTriageExplainDemo(guidedCategory)
        }

        binding.btnGuidedVitals.setOnClickListener {
            if (!isGuidedFlowActive) {
                guidedStep = 0
                isGuidedFlowActive = true
                addMessageToChat("Starting guided vitals for $guidedCategory.", false)
            }
            requestGuidedPrompt()
        }

        binding.btnManualVitals.setOnClickListener {
            showManualVitalsDialog()
        }
    }

    // Function to load all messages from the database
    private fun loadChatHistory() {
        lifecycleScope.launch {
            val messages = chatMessageDao.getAllMessages()
            for (message in messages) {
                // Use a different function to add to UI without saving again
                addMessageToUi(message.message, message.isUserMessage)
            }
        }
    }

    // Function to save a new message to the database
    private fun saveMessageToDatabase(message: String, isUserMessage: Boolean) {
        lifecycleScope.launch {
            val chatMessage = ChatMessage(message = message, isUserMessage = isUserMessage)
            chatMessageDao.insert(chatMessage)
        }
    }

    // This function now saves the message after adding it to the UI
    private fun addMessageToChat(message: String, isUserMessage: Boolean) {
        addMessageToUi(message, isUserMessage)
        saveMessageToDatabase(message, isUserMessage)
    }

    private fun clearChatHistoryAndExit() {
        lifecycleScope.launch {
            chatMessageDao.clearAllMessages()
            binding.chatContainer.removeAllViews()
            mediaPlayer?.release()
            mediaPlayer = null
            finish()
        }
    }

    private fun handleAssistantQuery(message: String) {
        val normalized = message.trim()
        if (normalized.startsWith("/triage-demo", ignoreCase = true)) {
            val parts = normalized.split(" ")
            val category = parts.getOrNull(1)?.lowercase(Locale.ROOT) ?: "maternal"
            runTriageExplainDemo(category)
            return
        }
        getChatResponse(message)
    }

    private fun getChatResponse(message: String) {
        addMessageToChat("Thinking...", false)
        val languageCode = selectedLanguageCode()
        val request = ChatRequest(
            message = message,
            language = languageCode,
            patientContext = buildProfileContext(),
        )
        RetrofitClient.instance.chat(request).enqueue(object : Callback<ChatResponse> {
            override fun onResponse(call: Call<ChatResponse>, response: Response<ChatResponse>) {
                removeLastChatBubbleIfPresent()
                if (response.isSuccessful) {
                    val body = response.body()
                    val reply = body?.response ?: "Sorry, I couldn't generate a reply right now."
                    addMessageToChat(reply, false)
                    playAudioIfAvailable(body?.ttsAudioB64)
                } else {
                    val errorText = response.errorBody()?.string()?.takeIf { it.isNotBlank() }
                    val status = response.code()
                    val backendMsg = errorText ?: "No error details from server."
                    addMessageToChat("Chat API error ($status): $backendMsg", false)
                }
            }

            override fun onFailure(call: Call<ChatResponse>, t: Throwable) {
                removeLastChatBubbleIfPresent()
                val reason = t.localizedMessage ?: "Unknown network error"
                addMessageToChat("Chat request failed: $reason", false)
            }
        })
    }

    private fun runTriageExplainDemo(category: String) {
        val supportedCategory = when (category) {
            "maternal", "child", "tb", "general" -> category
            else -> "maternal"
        }

        val patientId = currentPatientIdForContext()
        if (patientId.isBlank()) {
            Toast.makeText(this, "Enter Patient ID before running triage", Toast.LENGTH_SHORT).show()
            return
        }

        addMessageToChat("Running triage demo for $supportedCategory...", false)

        lifecycleScope.launch {
            val manualHistory = loadManualTrajectory(patientId, supportedCategory)
            val triageHistory = if (manualHistory.isNotEmpty()) {
                manualHistory
            } else {
                demoHistoryForCategory(supportedCategory)
            }

            val dataSource = if (manualHistory.isNotEmpty()) "manual records" else "demo fallback"
            addMessageToChat(
                "Using ${triageHistory.size} visits from $dataSource for patient $patientId.",
                false,
            )

            val triageRequest = TriageRequest(
                category = supportedCategory,
                patientId = patientId,
                history = triageHistory,
            )

            RetrofitClient.instance.triage(triageRequest).enqueue(object : Callback<TriageResponse> {
                override fun onResponse(call: Call<TriageResponse>, response: Response<TriageResponse>) {
                    if (!response.isSuccessful || response.body() == null) {
                        addMessageToChat("Triage failed. Please check backend availability.", false)
                        return
                    }

                    val triage = response.body()!!
                    addMessageToChat(
                        "Triage risk: ${triage.riskLevel} | anomaly score: ${"%.3f".format(triage.anomalyScore)}",
                        false,
                    )

                    val explainContext = triage.explanationContext.toMutableMap()
                    explainContext["patient_id"] = patientId
                    explainContext["category"] = supportedCategory
                    explainContext["trajectory_visits"] = triageHistory.map {
                        mapOf(
                            "visit_number" to it.visitNumber,
                            "systolic_bp" to it.systolicBp,
                            "diastolic_bp" to it.diastolicBp,
                            "hemoglobin" to it.hemoglobin,
                            "weight_kg" to it.weightKg,
                            "spo2" to it.spo2,
                            "pulse" to it.pulse,
                            "gestational_week" to it.gestationalWeek,
                            "muac_cm" to it.muacCm,
                            "waz_score" to it.wazScore,
                            "temperature" to it.temperature,
                            "age_months" to it.ageMonths,
                            "cough_severity" to it.coughSeverity,
                            "night_sweats_score" to it.nightSweatsScore,
                            "missed_doses_week" to it.missedDosesWeek,
                            "treatment_month" to it.treatmentMonth,
                            "fasting_glucose" to it.fastingGlucose,
                            "bmi" to it.bmi,
                        )
                    }

                    val explainRequest = ExplainRequest(
                        explanationContext = explainContext,
                        audience = "asha",
                        language = selectedLanguageCode(),
                    )

                    RetrofitClient.instance.explain(explainRequest)
                        .enqueue(object : Callback<ExplainResponse> {
                            override fun onResponse(
                                call: Call<ExplainResponse>,
                                response: Response<ExplainResponse>,
                            ) {
                                if (response.isSuccessful && response.body() != null) {
                                    addMessageToChat(response.body()!!.explanation, false)
                                } else {
                                    addMessageToChat("Explainability step failed after triage.", false)
                                }
                            }

                            override fun onFailure(call: Call<ExplainResponse>, t: Throwable) {
                                addMessageToChat("Explainability step failed after triage.", false)
                            }
                        })
                }

                override fun onFailure(call: Call<TriageResponse>, t: Throwable) {
                    addMessageToChat("Triage failed. Please check backend availability.", false)
                }
            })
        }
    }

    private fun demoHistoryForCategory(category: String): List<VitalsVisit> {
        return when (category) {
            "maternal" -> listOf(
                VitalsVisit(
                    visitNumber = 1,
                    systolicBp = 118.0,
                    diastolicBp = 76.0,
                    hemoglobin = 10.8,
                    weightKg = 58.0,
                    spo2 = 98.0,
                    pulse = 84.0,
                    gestationalWeek = 18,
                ),
                VitalsVisit(
                    visitNumber = 2,
                    systolicBp = 146.0,
                    diastolicBp = 94.0,
                    hemoglobin = 8.1,
                    weightKg = 60.4,
                    spo2 = 93.0,
                    pulse = 104.0,
                    gestationalWeek = 24,
                ),
            )

            "child" -> listOf(
                VitalsVisit(
                    visitNumber = 1,
                    weightKg = 9.8,
                    muacCm = 12.8,
                    wazScore = -1.6,
                    spo2 = 98.0,
                    temperature = 37.0,
                    ageMonths = 20,
                ),
                VitalsVisit(
                    visitNumber = 2,
                    weightKg = 8.7,
                    muacCm = 11.1,
                    wazScore = -3.2,
                    spo2 = 93.0,
                    temperature = 38.6,
                    ageMonths = 21,
                ),
            )

            "tb" -> listOf(
                VitalsVisit(
                    visitNumber = 1,
                    weightKg = 55.0,
                    coughSeverity = 4.0,
                    nightSweatsScore = 2.0,
                    missedDosesWeek = 1,
                    spo2 = 97.0,
                    temperature = 37.0,
                    treatmentMonth = 3,
                ),
                VitalsVisit(
                    visitNumber = 2,
                    weightKg = 53.0,
                    coughSeverity = 8.0,
                    nightSweatsScore = 7.0,
                    missedDosesWeek = 5,
                    spo2 = 92.0,
                    temperature = 38.4,
                    treatmentMonth = 4,
                ),
            )

            else -> listOf(
                VitalsVisit(
                    visitNumber = 1,
                    systolicBp = 132.0,
                    diastolicBp = 84.0,
                    fastingGlucose = 132.0,
                    spo2 = 98.0,
                    pulse = 82.0,
                    bmi = 26.3,
                ),
                VitalsVisit(
                    visitNumber = 2,
                    systolicBp = 168.0,
                    diastolicBp = 102.0,
                    fastingGlucose = 236.0,
                    spo2 = 93.0,
                    pulse = 104.0,
                    bmi = 27.2,
                ),
            )
        }
    }

    private fun selectedLanguageCode(): String {
        val selected = binding.languageSpinner.selectedItem.toString()
        return when {
            selected.contains("English", ignoreCase = true) -> "en"
            selected.contains("Hindi", ignoreCase = true) || selected.contains("हिन्दी") -> "hi"
            selected.contains("Marathi", ignoreCase = true) || selected.contains("मराठी") -> "mr"
            selected.contains("Bengali", ignoreCase = true) || selected.contains("বাংলা") -> "bn"
            selected.contains("Telugu", ignoreCase = true) || selected.contains("తెలుగు") -> "te"
            selected.contains("Tamil", ignoreCase = true) || selected.contains("தமிழ்") -> "ta"
            selected.contains("Gujarati", ignoreCase = true) || selected.contains("ગુજરાતી") -> "gu"
            selected.contains("Kannada", ignoreCase = true) || selected.contains("ಕನ್ನಡ") -> "kn"
            selected.contains("Malayalam", ignoreCase = true) || selected.contains("മലയാളം") -> "ml"
            selected.contains("Punjabi", ignoreCase = true) || selected.contains("ਪੰਜਾਬੀ") -> "pa"
            else -> "en"
        }
    }

    private fun getSymptomSuggestion(symptom: String) {
        addMessageToChat("Analyzing your symptom...", false)
        val selectedLanguage = binding.languageSpinner.selectedItem.toString()
        val request = SymptomRequest(symptom = symptom, language = selectedLanguage)
        RetrofitClient.instance.checkSymptom(request).enqueue(object : Callback<SymptomResponse> {
            override fun onResponse(call: Call<SymptomResponse>, response: Response<SymptomResponse>) {
                removeLastChatBubbleIfPresent()
                if (response.isSuccessful) {
                    val suggestion = response.body()?.suggestion ?: "Sorry, I couldn't get a suggestion."
                    addMessageToChat(suggestion, false)
                } else {
                    addMessageToChat("Error: Could not get a valid response from the server.", false)
                }
            }
            override fun onFailure(call: Call<SymptomResponse>, t: Throwable) {
                removeLastChatBubbleIfPresent()
                addMessageToChat("Failed to connect to the server. Please check your internet connection.", false)
                Toast.makeText(applicationContext, t.message, Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun handleVoiceTranscript(transcript: String) {
        addMessageToChat("Parsing voice intent...", false)
        val request = IntentRequest(transcript = transcript, language = selectedLanguageCode())
        RetrofitClient.instance.parseVoiceIntent(request).enqueue(object : Callback<IntentResponse> {
            override fun onResponse(call: Call<IntentResponse>, response: Response<IntentResponse>) {
                removeLastChatBubbleIfPresent()
                if (!response.isSuccessful || response.body() == null) {
                    getChatResponse(transcript)
                    return
                }

                val payload = response.body()!!
                val categoryFromEntity = (payload.entities?.get("category") as? String)?.lowercase(Locale.ROOT)
                if (categoryFromEntity in setOf("maternal", "child", "tb", "general")) {
                    guidedCategory = categoryFromEntity!!
                }

                when (payload.intent.lowercase(Locale.ROOT)) {
                    "record_vitals" -> {
                        guidedStep = 0
                        isGuidedFlowActive = true
                        addMessageToChat("Detected vitals recording. Starting guided flow.", false)
                        requestGuidedPrompt()
                    }
                    "ask_question", "check_patient", "report_symptom" -> answerVoiceQuestion(transcript)
                    else -> answerVoiceQuestion(transcript)
                }
            }

            override fun onFailure(call: Call<IntentResponse>, t: Throwable) {
                removeLastChatBubbleIfPresent()
                answerVoiceQuestion(transcript)
            }
        })
    }

    private fun requestGuidedPrompt() {
        RetrofitClient.instance.getGuidedPrompt(
            category = guidedCategory,
            step = guidedStep,
            language = selectedLanguageCode(),
        ).enqueue(object : Callback<GuidedVitalsResponse> {
            override fun onResponse(
                call: Call<GuidedVitalsResponse>,
                response: Response<GuidedVitalsResponse>,
            ) {
                if (!response.isSuccessful || response.body() == null) {
                    addMessageToChat("Could not fetch guided prompt. Please try again.", false)
                    return
                }

                val prompt = response.body()!!
                addMessageToChat(prompt.message, false)
                playAudioIfAvailable(prompt.ttsAudioB64)

                if (prompt.done) {
                    isGuidedFlowActive = false
                    guidedStep = 0
                } else {
                    guidedStep = (prompt.step ?: guidedStep) + 1
                }
            }

            override fun onFailure(call: Call<GuidedVitalsResponse>, t: Throwable) {
                addMessageToChat("Could not fetch guided prompt. Please check backend connection.", false)
            }
        })
    }

    private fun answerVoiceQuestion(question: String) {
        addMessageToChat("Thinking...", false)
        lifecycleScope.launch {
            val patientContext = buildProfileContext().orEmpty().toMutableMap()
            val patientId = currentPatientIdForContext()
            if (patientId.isNotBlank()) {
                val trajectory = patientVitalRecordDao.getLatestForPatient(patientId, 5)
                    .sortedBy { it.recordedAt }
                    .map {
                        mapOf(
                            "category" to it.category,
                            "recorded_at" to it.recordedAt,
                            "systolic_bp" to it.systolicBp,
                            "diastolic_bp" to it.diastolicBp,
                            "hemoglobin" to it.hemoglobin,
                            "weight_kg" to it.weightKg,
                            "spo2" to it.spo2,
                            "temperature" to it.temperature,
                            "notes" to it.notes,
                        )
                    }
                patientContext["patient_id"] = patientId
                patientContext["recent_vitals"] = trajectory
            }

            val request = VoiceAnswerRequest(
                question = question,
                language = selectedLanguageCode(),
                patientContext = patientContext,
            )

            RetrofitClient.instance.answerVoiceQuestion(request)
                .enqueue(object : Callback<VoiceAnswerResponse> {
                    override fun onResponse(
                        call: Call<VoiceAnswerResponse>,
                        response: Response<VoiceAnswerResponse>,
                    ) {
                        removeLastChatBubbleIfPresent()
                        if (response.isSuccessful && response.body() != null) {
                            val body = response.body()!!
                            addMessageToChat(body.answer, false)
                            playAudioIfAvailable(body.ttsAudioB64)
                        } else {
                            addMessageToChat("Voice answer failed. Please try again.", false)
                        }
                    }

                    override fun onFailure(call: Call<VoiceAnswerResponse>, t: Throwable) {
                        removeLastChatBubbleIfPresent()
                        addMessageToChat("Voice answer failed. Please check backend connection.", false)
                    }
                })
        }
    }

    private fun currentUserId(): String? {
        val prefs = getSharedPreferences(ProfileActivity.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(ProfileActivity.KEY_USER_ID, null)
    }

    private fun buildProfileContext(): Map<String, Any>? {
        val prefs = getSharedPreferences(ProfileActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val context = mutableMapOf<String, Any>()

        prefs.getString(ProfileActivity.KEY_USER_ID, null)?.takeIf { it.isNotBlank() }?.let {
            context["user_id"] = it
        }
        prefs.getString(ProfileActivity.KEY_USER_NAME, null)?.takeIf { it.isNotBlank() }?.let {
            context["user_name"] = it
        }
        prefs.getString(ProfileActivity.KEY_USER_ROLE, null)?.takeIf { it.isNotBlank() }?.let {
            context["user_role"] = it
        }
        prefs.getString(ProfileActivity.KEY_ASSIGNED_AREA, null)?.takeIf { it.isNotBlank() }?.let {
            context["assigned_area"] = it
        }
        currentPatientIdForContext().takeIf { it.isNotBlank() }?.let {
            context["patient_id"] = it
        }
        context["category"] = guidedCategory
        context["language"] = selectedLanguageCode()

        return context.ifEmpty { null }
    }

    private fun removeLastChatBubbleIfPresent() {
        if (binding.chatContainer.childCount > 0) {
            binding.chatContainer.removeViewAt(binding.chatContainer.childCount - 1)
        }
    }

    private fun playAudioIfAvailable(audioB64: String?) {
        if (audioB64.isNullOrBlank()) return

        val tempFile = try {
            val audioBytes = Base64.decode(audioB64, Base64.DEFAULT)
            File.createTempFile("mediconnect_tts_", ".mp3", cacheDir).apply {
                writeBytes(audioBytes)
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Audio playback unavailable", Toast.LENGTH_SHORT).show()
            return
        }

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(tempFile.absolutePath)
            setOnCompletionListener {
                it.release()
                mediaPlayer = null
                tempFile.delete()
            }
            setOnErrorListener { mp, _, _ ->
                mp.release()
                mediaPlayer = null
                tempFile.delete()
                true
            }
            prepare()
            start()
        }
    }

    // This function only handles adding a message to the UI
    private fun addMessageToUi(message: String, isUserMessage: Boolean) {
        val textView = TextView(this)
        textView.text = message
        textView.isSingleLine = false
        textView.ellipsize = null
        textView.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            textView.breakStrategy = Layout.BREAK_STRATEGY_HIGH_QUALITY
            textView.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NORMAL
        }
        textView.setPadding(32, 16, 32, 16)
        val layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 16
        }
        if (isUserMessage) {
            layoutParams.gravity = Gravity.END
            textView.setBackgroundResource(R.drawable.user_chat_bubble_background)
            textView.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        } else {
            layoutParams.gravity = Gravity.START
            textView.setBackgroundResource(R.drawable.bot_chat_bubble_background)
            textView.setTextColor(ContextCompat.getColor(this, android.R.color.black))
        }
        textView.layoutParams = layoutParams
        binding.chatContainer.addView(textView)
        binding.chatScrollView.post { binding.chatScrollView.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    // --- The rest of the functions for Language Spinner, Permissions, and Voice Input are unchanged ---

    private fun setupLanguageSpinner() {
        ArrayAdapter.createFromResource(
            this,
            R.array.languages_array,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.languageSpinner.adapter = adapter
        }
    }

    private fun setupCategorySpinner() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            triageCategories,
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.categorySpinner.adapter = adapter
        binding.categorySpinner.setSelection(0)

        binding.categorySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long,
            ) {
                guidedCategory = triageCategories.getOrElse(position) { "maternal" }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {
                guidedCategory = "maternal"
            }
        }
    }

    private fun prefillContextInputs() {
        val savedUserId = currentUserId()
        if (!savedUserId.isNullOrBlank()) {
            binding.etPatientId.setText(savedUserId)
        }
    }

    private fun currentPatientIdForContext(): String {
        val inputPatientId = binding.etPatientId.text?.toString()?.trim().orEmpty()
        if (inputPatientId.isNotBlank()) return inputPatientId
        return currentUserId().orEmpty()
    }

    private fun showManualVitalsDialog() {
        val category = guidedCategory
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(42, 24, 42, 0)
        }

        fun addInput(label: String, hint: String): EditText {
            val title = TextView(this).apply { text = label }
            val input = EditText(this).apply {
                this.hint = hint
                setSingleLine(true)
            }
            dialogLayout.addView(title)
            dialogLayout.addView(input)
            return input
        }

        val patientIdInput = addInput("Patient ID", "Required")
        patientIdInput.setText(currentPatientIdForContext())

        val commonSpo2Input = addInput("SpO2", "Optional")
        val commonWeightInput = addInput("Weight (kg)", "Optional")
        val commonTempInput = addInput("Temperature (C)", "Optional")
        val notesInput = addInput("Notes", "Optional")

        val categoryInputs = when (category) {
            "maternal" -> mapOf(
                "systolic" to addInput("Systolic BP", "e.g. 120"),
                "diastolic" to addInput("Diastolic BP", "e.g. 80"),
                "hemoglobin" to addInput("Hemoglobin", "e.g. 10.5"),
                "pulse" to addInput("Pulse", "e.g. 84"),
                "gestationalWeek" to addInput("Gestational Week", "e.g. 24"),
            )

            "child" -> mapOf(
                "muac" to addInput("MUAC (cm)", "e.g. 12.3"),
                "waz" to addInput("WAZ Score", "e.g. -2.1"),
                "ageMonths" to addInput("Age (months)", "e.g. 21"),
            )

            "tb" -> mapOf(
                "cough" to addInput("Cough Severity (0-10)", "e.g. 7"),
                "nightSweats" to addInput("Night Sweats (0-10)", "e.g. 6"),
                "missedDoses" to addInput("Missed Doses (week)", "e.g. 2"),
                "treatmentMonth" to addInput("Treatment Month", "e.g. 4"),
            )

            else -> mapOf(
                "systolic" to addInput("Systolic BP", "e.g. 145"),
                "diastolic" to addInput("Diastolic BP", "e.g. 92"),
                "fastingGlucose" to addInput("Fasting Glucose", "e.g. 126"),
                "bmi" to addInput("BMI", "e.g. 27.4"),
                "pulse" to addInput("Pulse", "e.g. 90"),
            )
        }

        AlertDialog.Builder(this)
            .setTitle("Add Manual Vitals ($category)")
            .setView(dialogLayout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val patientId = patientIdInput.text.toString().trim()
                if (patientId.isBlank()) {
                    Toast.makeText(this, "Patient ID is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val timestamp = System.currentTimeMillis()
                val record = PatientVitalRecord(
                    userId = currentUserId(),
                    patientId = patientId,
                    category = category,
                    recordedAt = timestamp,
                    systolicBp = parseDouble(categoryInputs["systolic"]),
                    diastolicBp = parseDouble(categoryInputs["diastolic"]),
                    hemoglobin = parseDouble(categoryInputs["hemoglobin"]),
                    weightKg = parseDouble(commonWeightInput),
                    spo2 = parseDouble(commonSpo2Input),
                    pulse = parseDouble(categoryInputs["pulse"]),
                    gestationalWeek = parseInt(categoryInputs["gestationalWeek"]),
                    muacCm = parseDouble(categoryInputs["muac"]),
                    wazScore = parseDouble(categoryInputs["waz"]),
                    temperature = parseDouble(commonTempInput),
                    ageMonths = parseInt(categoryInputs["ageMonths"]),
                    coughSeverity = parseDouble(categoryInputs["cough"]),
                    nightSweatsScore = parseDouble(categoryInputs["nightSweats"]),
                    missedDosesWeek = parseInt(categoryInputs["missedDoses"]),
                    treatmentMonth = parseInt(categoryInputs["treatmentMonth"]),
                    fastingGlucose = parseDouble(categoryInputs["fastingGlucose"]),
                    bmi = parseDouble(categoryInputs["bmi"]),
                    notes = notesInput.text.toString().trim().ifBlank { null },
                )

                saveManualVitalRecord(record)
            }
            .show()
    }

    private fun parseDouble(input: EditText?): Double? {
        val raw = input?.text?.toString()?.trim().orEmpty()
        return raw.toDoubleOrNull()
    }

    private fun parseInt(input: EditText?): Int? {
        val raw = input?.text?.toString()?.trim().orEmpty()
        return raw.toIntOrNull()
    }

    private fun saveManualVitalRecord(record: PatientVitalRecord) {
        lifecycleScope.launch {
            val rowId = patientVitalRecordDao.insert(record)
            val savedRecord = record.copy(id = rowId)

            FirebaseVitalsHelper.saveVitalRecord(savedRecord)

            val timestamp = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                .format(Date(savedRecord.recordedAt))
            addMessageToChat(
                "Saved ${savedRecord.category} vitals for patient ${savedRecord.patientId} at $timestamp.",
                false,
            )
        }
    }

    private suspend fun loadManualTrajectory(
        patientId: String,
        category: String,
        limit: Int = 10,
    ): List<VitalsVisit> {
        val records = patientVitalRecordDao
            .getLatestForPatientCategory(patientId = patientId, category = category, limit = limit)
            .sortedBy { it.recordedAt }

        return records.mapIndexed { index, record ->
            VitalsVisit(
                visitNumber = index + 1,
                systolicBp = record.systolicBp,
                diastolicBp = record.diastolicBp,
                hemoglobin = record.hemoglobin,
                weightKg = record.weightKg,
                spo2 = record.spo2,
                pulse = record.pulse,
                gestationalWeek = record.gestationalWeek,
                muacCm = record.muacCm,
                wazScore = record.wazScore,
                temperature = record.temperature,
                ageMonths = record.ageMonths,
                coughSeverity = record.coughSeverity,
                nightSweatsScore = record.nightSweatsScore,
                missedDosesWeek = record.missedDosesWeek,
                treatmentMonth = record.treatmentMonth,
                fastingGlucose = record.fastingGlucose,
                bmi = record.bmi,
            )
        }
    }

    private fun checkPermissionAndStartVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceInput()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), RECORD_AUDIO_PERMISSION_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceInput()
            } else {
                Toast.makeText(this, "Microphone permission is required to use voice input", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startVoiceInput() {
        Toast.makeText(this, "Listening...", Toast.LENGTH_SHORT).show()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Please speak your symptoms")
        }
        try {
            startActivityForResult(intent, SPEECH_REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "Your device does not support voice input", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SPEECH_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            val spokenText: String? =
                data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.let { results ->
                    results[0]
                }
            spokenText?.let {
                addMessageToChat(it, true)
                handleVoiceTranscript(it)
            }
        }
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }
}