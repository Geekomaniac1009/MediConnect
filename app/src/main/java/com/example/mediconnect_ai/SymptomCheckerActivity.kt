package com.example.mediconnect_ai

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
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
import com.example.mediconnect_ai.network.RetrofitClient
import com.example.mediconnect_ai.network.SymptomRequest
import com.example.mediconnect_ai.network.SymptomResponse
import com.example.mediconnect_ai.network.TriageRequest
import com.example.mediconnect_ai.network.TriageResponse
import com.example.mediconnect_ai.network.VitalsVisit
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Locale

class SymptomCheckerActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySymptomCheckerBinding
    // Database variables
    private lateinit var db: AppDatabase
    private lateinit var chatMessageDao: ChatMessageDao

    private val SPEECH_REQUEST_CODE = 123
    private val RECORD_AUDIO_PERMISSION_CODE = 1

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
        val request = ChatRequest(message = message, language = languageCode)
        RetrofitClient.instance.chat(request).enqueue(object : Callback<ChatResponse> {
            override fun onResponse(call: Call<ChatResponse>, response: Response<ChatResponse>) {
                binding.chatContainer.removeViewAt(binding.chatContainer.childCount - 1)
                if (response.isSuccessful) {
                    val reply = response.body()?.response ?: "Sorry, I couldn't generate a reply right now."
                    addMessageToChat(reply, false)
                } else {
                    getSymptomSuggestion(message)
                }
            }

            override fun onFailure(call: Call<ChatResponse>, t: Throwable) {
                binding.chatContainer.removeViewAt(binding.chatContainer.childCount - 1)
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
            patientId = "DEMO_001",
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
                binding.chatContainer.removeViewAt(binding.chatContainer.childCount - 1)
                if (response.isSuccessful) {
                    val suggestion = response.body()?.suggestion ?: "Sorry, I couldn't get a suggestion."
                    addMessageToChat(suggestion, false)
                } else {
                    addMessageToChat("Error: Could not get a valid response from the server.", false)
                }
            }
            override fun onFailure(call: Call<SymptomResponse>, t: Throwable) {
                binding.chatContainer.removeViewAt(binding.chatContainer.childCount - 1)
                addMessageToChat("Failed to connect to the server. Please check your internet connection.", false)
                Toast.makeText(applicationContext, t.message, Toast.LENGTH_LONG).show()
            }
        })
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
            binding.etSymptomInput.setText(spokenText)
        }
    }
}