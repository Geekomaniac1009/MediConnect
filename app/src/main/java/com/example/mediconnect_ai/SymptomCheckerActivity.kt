package com.example.mediconnect_ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Base64
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.mediconnect_ai.database.AppDatabase
import com.example.mediconnect_ai.database.ChatMessage
import com.example.mediconnect_ai.database.ChatMessageDao
import com.example.mediconnect_ai.databinding.ActivitySymptomCheckerBinding
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
import java.util.Locale

class SymptomCheckerActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySymptomCheckerBinding
    // Database variables
    private lateinit var db: AppDatabase
    private lateinit var chatMessageDao: ChatMessageDao

    private val SPEECH_REQUEST_CODE = 123
    private val RECORD_AUDIO_PERMISSION_CODE = 1
    private var guidedCategory: String = "maternal"
    private var guidedStep: Int = 0
    private var isGuidedFlowActive: Boolean = false
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySymptomCheckerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize the database and DAO
        db = AppDatabase.getInstance(applicationContext)
        chatMessageDao = db.chatMessageDao()

        setupLanguageSpinner()

        // Load previous chat history when the screen opens
        loadChatHistory()

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

        binding.btnVoiceAsk.setOnClickListener {
            val question = binding.etSymptomInput.text.toString().trim()
            if (question.isBlank()) {
                Toast.makeText(this, "Type or speak a question first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            addMessageToChat(question, true)
            answerVoiceQuestion(question)
            binding.etSymptomInput.text.clear()
        }

        binding.btnGuidedVitals.setOnClickListener {
            if (!isGuidedFlowActive) {
                guidedStep = 0
                isGuidedFlowActive = true
                addMessageToChat("Starting guided vitals for $guidedCategory.", false)
            }
            requestGuidedPrompt()
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
                    getSymptomSuggestion(message)
                }
            }

            override fun onFailure(call: Call<ChatResponse>, t: Throwable) {
                removeLastChatBubbleIfPresent()
                getSymptomSuggestion(message)
            }
        })
    }

    private fun runTriageExplainDemo(category: String) {
        val supportedCategory = when (category) {
            "maternal", "child", "tb", "general" -> category
            else -> "maternal"
        }

        addMessageToChat("Running triage demo for $supportedCategory...", false)

        val triageRequest = TriageRequest(
            category = supportedCategory,
            patientId = currentUserId() ?: "DEMO_001",
            history = demoHistoryForCategory(supportedCategory),
        )

        RetrofitClient.instance.triage(triageRequest).enqueue(object : Callback<TriageResponse> {
            override fun onResponse(call: Call<TriageResponse>, response: Response<TriageResponse>) {
                if (!response.isSuccessful || response.body() == null) {
                    addMessageToChat("Triage demo failed. Please check backend availability.", false)
                    return
                }

                val triage = response.body()!!
                addMessageToChat(
                    "Triage risk: ${triage.riskLevel} | anomaly score: ${"%.3f".format(triage.anomalyScore)}",
                    false,
                )

                val explainRequest = ExplainRequest(
                    explanationContext = triage.explanationContext,
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
                addMessageToChat("Triage demo failed. Please check backend availability.", false)
            }
        })
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
                    "ask_question", "check_patient" -> answerVoiceQuestion(transcript)
                    "report_symptom" -> handleAssistantQuery(transcript)
                    else -> getChatResponse(transcript)
                }
            }

            override fun onFailure(call: Call<IntentResponse>, t: Throwable) {
                removeLastChatBubbleIfPresent()
                getChatResponse(transcript)
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
        val request = VoiceAnswerRequest(
            question = question,
            language = selectedLanguageCode(),
            patientContext = buildProfileContext(),
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
                binding.etSymptomInput.setText(it)
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