"""
LLM Explainability Layer
========================
Takes the structured output from the Isolation Forest detector and uses
Gemini (free tier via google-generativeai) to produce:
  1. A plain-language risk summary for ASHA workers
  2. A detailed clinical explanation for doctors
  3. Actionable next-step recommendations
  4. Multi-lingual translation if requested

This is the "explainability" component referenced in the poster.
"""

import os
import json
from typing import Dict, Any, Optional, cast

try:
    import google.generativeai as _genai
    genai = cast(Any, _genai)
except ImportError:
    genai = None

# Supported languages (ISO 639-1 code → locale name for prompt)
SUPPORTED_LANGUAGES = {
    "en": "English",
    "hi": "Hindi (हिन्दी)",
    "mr": "Marathi (मराठी)",
    "bn": "Bengali (বাংলা)",
    "te": "Telugu (తెలుగు)",
    "ta": "Tamil (தமிழ்)",
    "gu": "Gujarati (ગુજરાતી)",
    "kn": "Kannada (ಕನ್ನಡ)",
    "ml": "Malayalam (മലയാളം)",
    "pa": "Punjabi (ਪੰਜਾਬੀ)",
}

CATEGORY_CONTEXT = {
    "maternal": "pregnant woman being tracked for antenatal care (ANC)",
    "child":    "infant/child under 5 being monitored for growth and immunization",
    "tb":       "tuberculosis (TB) patient on NTEP treatment follow-up",
    "general":  "general patient being monitored for chronic conditions",
}

RISK_URGENCY = {
    "LOW":    "routine follow-up is sufficient",
    "MEDIUM": "the ASHA worker should increase visit frequency and monitor closely",
    "HIGH":   "immediate escalation to the PHC doctor or referral is strongly recommended",
}


def _build_asha_prompt(ctx: Dict, language: str) -> str:
    lang_name = SUPPORTED_LANGUAGES.get(language, "English")
    cat_desc = CATEGORY_CONTEXT.get(ctx["category"], "patient")
    urgency = RISK_URGENCY.get(ctx["risk_level"], "")
    rules = ctx.get("rule_violations", [])
    drifts = ctx.get("top_drifting_features", [])

    rule_str = ""
    if rules:
        rule_str = "CLINICAL ALERTS:\n" + "\n".join(
            f"  - {r['label']}: {r['value']} (threshold: {r['threshold']}) — {r['message']}"
            for r in rules
        )

    drift_str = ""
    if drifts:
        drift_str = "TREND DRIFT DETECTED (EWMA):\n" + "\n".join(
            f"  - {d['feature']}: drift = {d['drift']:+.2f}"
            for d in drifts
        )

    vitals_str = json.dumps(ctx.get("latest_vitals", {}), indent=2)

    prompt = f"""You are a concise AI health assistant for ASHA (frontline healthcare) workers in rural India.

PATIENT CONTEXT:
- Patient type: {cat_desc}
- Number of visits recorded: {ctx['visit_count']}
- Risk Level determined by AI: {ctx['risk_level']}
- Anomaly Score: {ctx['anomaly_score']} (0=normal, 1=highly anomalous)
- Action required: {urgency}

LATEST VITALS:
{vitals_str}

{rule_str}

{drift_str}

INSTRUCTIONS:
1. Write a brief ASHA-level summary (2-3 sentences, simple language) of why this patient needs attention.
2. List 2-4 specific actions the ASHA worker should take NOW.
3. State whether a doctor referral is needed (Yes/No with reason).
4. End with: "Disclaimer: AI-generated clinical support tool. Always consult a qualified doctor."

Respond ENTIRELY in {lang_name}. Keep it simple enough for a community health worker to understand.
Do not use complex medical jargon. Use bullet points for the action list."""

    return prompt


def _build_doctor_prompt(ctx: Dict) -> str:
    cat_desc = CATEGORY_CONTEXT.get(ctx["category"], "patient")
    rules = ctx.get("rule_violations", [])
    drifts = ctx.get("top_drifting_features", [])
    ewma = ctx.get("ewma_features", {})

    return f"""You are a clinical AI assistant providing structured medical summaries.

PATIENT SUMMARY:
- Category: {cat_desc}
- Visits analysed: {ctx['visit_count']}
- Isolation Forest Anomaly Score: {ctx['anomaly_score']} (raw: {ctx.get('if_raw_score', 'N/A')})
- Risk Classification: {ctx['risk_level']}

LATEST VITALS: {json.dumps(ctx.get('latest_vitals', {}), indent=2)}

EWMA BASELINE ESTIMATES: {json.dumps(ewma, indent=2)}

CLINICAL RULE VIOLATIONS: {json.dumps(rules, indent=2)}

TOP TRENDING DEVIATIONS (EWMA drift): {json.dumps(drifts, indent=2)}

Provide a structured clinical note with:
1. PRIMARY FINDINGS: What the AI detected and why (reference specific vitals + drift values)
2. DIFFERENTIAL CONSIDERATIONS: 2-3 possible clinical scenarios consistent with these trends
3. RECOMMENDED INVESTIGATIONS: Lab/diagnostic tests to confirm
4. IMMEDIATE MANAGEMENT: Evidence-based first-line actions
5. FOLLOW-UP: Monitoring frequency and escalation criteria

Respond in English. Use standard clinical terminology."""


class LLMExplainer:
    def __init__(self):
        if genai is None:
            raise ValueError(
                "google-generativeai is not installed. Install requirements and retry."
            )
        api_key = os.getenv("GEMINI_API_KEY")
        if not api_key:
            raise ValueError("GEMINI_API_KEY not set in environment")
        genai.configure(api_key=api_key)
        self.model = genai.GenerativeModel("models/gemini-2.5-flash")

    def _call(self, prompt: str, max_tokens: int = 1024) -> str:
        if genai is None:
            return "LLM error: google-generativeai is not installed."
        try:
            resp = self.model.generate_content(
                prompt,
                generation_config=genai.types.GenerationConfig(
                    max_output_tokens=max_tokens,
                    temperature=0.4,
                )
            )
            return resp.text.strip() if resp.text else "Unable to generate explanation."
        except Exception as e:
            return f"LLM error: {str(e)}"

    def explain_for_asha(self, explanation_context: Dict,
                         language: str = "en") -> str:
        """Plain-language explanation for ASHA workers (optionally multilingual)."""
        prompt = _build_asha_prompt(explanation_context, language)
        return self._call(prompt, max_tokens=600)

    def explain_for_doctor(self, explanation_context: Dict) -> str:
        """Detailed clinical explanation for doctors."""
        prompt = _build_doctor_prompt(explanation_context)
        return self._call(prompt, max_tokens=900)

    def chat_response(self, user_message: str,
                      patient_context: Optional[Dict] = None,
                      language: str = "en",
                      chat_history: Optional[list] = None) -> str:
        """
        Conversational chatbot mode.
        patient_context: optional dict with patient risk summary attached to session.
        chat_history: list of {"role": "user"|"model", "parts": [str]} for multi-turn.
        """
        lang_name = SUPPORTED_LANGUAGES.get(language, "English")

        system_parts = [
            f"You are MediConnect AI — a multilingual health assistant for ASHA workers "
            f"and rural healthcare in India. Always respond in {lang_name}. "
            "Be concise, empathetic, and practical. Avoid complex medical jargon. "
            "If the question involves a patient with a HIGH risk flag, "
            "always recommend consulting the PHC doctor. "
            "End all medical advice with: 'Please consult a qualified doctor for confirmation.'"
        ]

        if patient_context:
            ctx_str = json.dumps(patient_context, indent=2)
            system_parts.append(
                f"\nCURRENT PATIENT CONTEXT (from AI triage):\n{ctx_str}"
            )

        history = chat_history or []
        # Build the conversation
        messages = []
        for turn in history:
            messages.append(turn)
        messages.append({
            "role": "user",
            "parts": ["\n".join(system_parts) + "\n\nUser: " + user_message]
        })

        try:
            chat = self.model.start_chat(history=messages[:-1])
            resp = chat.send_message(messages[-1]["parts"][0])
            return resp.text.strip()
        except Exception as e:
            return f"Chat error: {str(e)}"

    def translate(self, text: str, target_language: str) -> str:
        """Translate any text to the target language."""
        lang_name = SUPPORTED_LANGUAGES.get(target_language, target_language)
        prompt = (f"Translate the following healthcare text to {lang_name}. "
                  f"Keep medical terms accurate. Return ONLY the translated text:\n\n{text}")
        return self._call(prompt, max_tokens=800)