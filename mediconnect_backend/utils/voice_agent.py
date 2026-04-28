"""
Voice Agent Module
==================
Provides:
  1. Text-to-speech synthesis endpoint (via gTTS — free, multilingual)
  2. Voice intent parsing: turns transcribed speech into structured health queries
  3. ASHA voice workflow: guided voice-based vitals recording

Architecture:
  Android App → records audio → sends transcript text → this module → LLM response → TTS audio
  (Full ASR is handled client-side via Android SpeechRecognizer API, which supports Indian languages)
  (This backend handles NLU + response generation + TTS)
"""

import os
import io
import base64
import json
import re
from typing import Dict, Any, Optional, List, cast

try:
    import google.generativeai as _genai
    genai = cast(Any, _genai)
except ImportError:
    genai = None

gTTS = None
try:
    from gtts import gTTS as _gTTS
    gTTS = _gTTS
    GTTS_AVAILABLE = True
except ImportError:
    GTTS_AVAILABLE = False

# Language code mapping (Android locale → gTTS locale)
GTTS_LANG_MAP = {
    "en": "en",
    "hi": "hi",
    "mr": "mr",
    "bn": "bn",
    "te": "te",
    "ta": "ta",
    "gu": "gu",
    "kn": "kn",
    "ml": "ml",
    "pa": "pa",
}

# Voice-based vitals collection workflow
VITALS_PROMPTS = {
    "maternal": {
        "en": [
            "What is the patient's blood pressure today? Please say systolic over diastolic.",
            "What is the patient's hemoglobin reading?",
            "What is the patient's current weight in kilograms?",
            "What is the oxygen saturation (SpO2) reading?",
            "What is the patient's pulse rate?",
        ],
        "hi": [
            "आज मरीज का रक्तचाप क्या है? सिस्टोलिक और डायस्टोलिक बताएं।",
            "मरीज का हीमोग्लोबिन कितना है?",
            "मरीज का वर्तमान वजन किलोग्राम में बताएं।",
            "ऑक्सीजन सैचुरेशन (SpO2) क्या है?",
            "मरीज की नाड़ी की दर क्या है?",
        ],
        "mr": [
            "आज रुग्णाचा रक्तदाब किती आहे? सिस्टोलिक आणि डायस्टोलिक सांगा.",
            "रुग्णाचे हिमोग्लोबिन किती आहे?",
            "रुग्णाचे सध्याचे वजन किलोग्राम मध्ये सांगा.",
            "ऑक्सिजन सॅच्युरेशन (SpO2) किती आहे?",
            "रुग्णाची नाडी किती आहे?",
        ],
    },
    "child": {
        "en": [
            "What is the child's current weight in kilograms?",
            "What is the mid-upper arm circumference (MUAC) in centimetres?",
            "What is the child's temperature?",
            "What is the oxygen saturation reading?",
        ],
        "hi": [
            "बच्चे का वर्तमान वजन किलोग्राम में क्या है?",
            "मध्य-ऊपरी बाजू की परिधि (MUAC) सेंटीमीटर में बताएं।",
            "बच्चे का तापमान क्या है?",
            "ऑक्सीजन सैचुरेशन क्या है?",
        ],
    },
    "tb": {
        "en": [
            "What is the patient's current weight in kilograms?",
            "On a scale of 0 to 10, how severe is the patient's cough today?",
            "On a scale of 0 to 10, how severe are night sweats?",
            "How many doses were missed this week?",
            "What is the patient's temperature?",
        ],
        "hi": [
            "मरीज का वर्तमान वजन किलोग्राम में क्या है?",
            "0 से 10 के पैमाने पर, आज खांसी कितनी गंभीर है?",
            "0 से 10 के पैमाने पर, रात को पसीना कितना आता है?",
            "इस सप्ताह कितनी खुराकें चूक गईं?",
            "मरीज का तापमान क्या है?",
        ],
    },
}

# Regex patterns to extract numeric values from spoken text
NUMBER_PATTERNS = [
    r'\b(\d{1,3}(?:\.\d{1,2})?)\b',
    r'(\d+)\s*(?:over|upon|slash)\s*(\d+)',  # for BP: "120 over 80"
]


def extract_bp_from_speech(text: str):
    """Extract BP pair from speech like '120 over 80' or '120/80'."""
    patterns = [
        r'(\d{2,3})\s*(?:over|upon|slash|by|/)\s*(\d{2,3})',
        r'(\d{2,3})\s+(\d{2,3})',
    ]
    for p in patterns:
        m = re.search(p, text, re.IGNORECASE)
        if m:
            return int(m.group(1)), int(m.group(2))
    return None, None


def extract_single_number(text: str) -> Optional[float]:
    """Extract first numeric value from speech."""
    m = re.search(r'\b(\d{1,3}(?:\.\d{1,2})?)\b', text)
    return float(m.group(1)) if m else None


class VoiceAgent:
    def __init__(self):
        api_key = os.getenv("GEMINI_API_KEY")
        if api_key and genai is not None:
            genai.configure(api_key=api_key)
            #BINGUS
            # self.llm = genai.GenerativeModel("models/gemini-2.5-flash")
            self.llm = genai.GenerativeModel("models/gemini-3.1-flash-lite-preview")
        else:
            self.llm = None

    def text_to_speech_b64(self, text: str, language: str = "en") -> Optional[str]:
        """
        Convert text to speech. Returns base64-encoded MP3 or None.
        Uses gTTS (Google Text-to-Speech — free, works offline after install).
        """
        if not GTTS_AVAILABLE:
            return None
        if gTTS is None:
            return None
        lang = GTTS_LANG_MAP.get(language, "en")
        try:
            buf = io.BytesIO()
            tts = gTTS(text=text, lang=lang, slow=False)
            tts.write_to_fp(buf)
            buf.seek(0)
            return base64.b64encode(buf.read()).decode("utf-8")
        except Exception as e:
            print(f"TTS error: {e}")
            return None

    def parse_voice_intent(self, transcript: str, language: str = "en") -> Dict:
        """
        Parse a voice transcript into a structured health intent.
        Uses Gemini to extract intent + entities from free-form speech.
        """
        if not self.llm:
            return {"intent": "unknown", "raw": transcript}

        prompt = f"""You are a healthcare voice assistant NLU for ASHA workers in India.
Parse the following spoken text and return a JSON object with:
  - "intent": one of ["record_vitals", "check_patient", "ask_question", "report_symptom", "get_schedule", "emergency", "unknown"]
  - "entities": dict of extracted values (patient_name, vital_type, vital_value, symptom, etc.)
  - "language": detected language code (en/hi/mr/bn/te/ta/gu/kn/ml/pa)
  - "confidence": 0.0-1.0

Spoken text: "{transcript}"

Return ONLY valid JSON, no explanation."""

        try:
            resp = self.llm.generate_content(prompt)
            text = resp.text.strip()
            # Strip markdown fences
            text = re.sub(r'^```json\s*', '', text)
            text = re.sub(r'^```\s*', '', text)
            text = re.sub(r'\s*```$', '', text)
            return json.loads(text)
        except Exception as e:
            return {"intent": "unknown", "raw": transcript, "error": str(e)}

    def guided_vitals_response(self, category: str, step: int,
                               language: str = "en") -> Dict:
        """
        Returns the next prompt in the guided vitals collection workflow.
        """
        lang = language if language in ("en", "hi", "mr") else "en"
        cat_prompts = VITALS_PROMPTS.get(category, {})
        lang_prompts = cat_prompts.get(lang, cat_prompts.get("en", []))

        if step >= len(lang_prompts):
            done_msgs = {
                "en": "All vitals recorded. Submitting for analysis.",
                "hi": "सभी माप दर्ज हो गए। विश्लेषण के लिए भेज रहे हैं।",
                "mr": "सर्व माप नोंदवले. विश्लेषणासाठी पाठवत आहे.",
            }
            return {
                "done": True,
                "message": done_msgs.get(lang, done_msgs["en"]),
                "tts": self.text_to_speech_b64(done_msgs.get(lang, done_msgs["en"]), lang),
            }

        prompt_text = lang_prompts[step]
        return {
            "done": False,
            "step": step,
            "total_steps": len(lang_prompts),
            "message": prompt_text,
            "tts": self.text_to_speech_b64(prompt_text, lang),
        }

    def answer_health_question(self, question: str,
                               patient_context: Optional[Dict] = None,
                               language: str = "en") -> Dict:
        """
        Answers a free-form health question from the ASHA worker.
        """
        if not self.llm:
            return {"answer": "LLM not available.", "tts": None}

        lang_name_map = {
            "en": "English", "hi": "Hindi", "mr": "Marathi",
            "bn": "Bengali", "te": "Telugu", "ta": "Tamil",
        }
        lang_name = lang_name_map.get(language, "English")

        ctx_str = ""
        if patient_context:
            ctx_str = f"\nPatient context: {json.dumps(patient_context)}\n"

        prompt = (
            f"You are a rural health assistant for ASHA workers in India. "
            f"Respond in {lang_name}. Be brief (2-4 sentences). "
            f"Always add: 'Consult PHC doctor for confirmation.'"
            f"{ctx_str}\nQuestion: {question}"
        )

        try:
            resp = self.llm.generate_content(prompt)
            answer = resp.text.strip()
            tts = self.text_to_speech_b64(answer, language)
            return {"answer": answer, "tts": tts}
        except Exception as e:
            return {"answer": f"Error: {e}", "tts": None}