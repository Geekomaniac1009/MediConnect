import os
from pathlib import Path
from typing import Any, Dict, List

from dotenv import load_dotenv
from flask import Flask, jsonify, request

try:
	# Package import path when loaded as mediconnect_backend.app
	from .utils.anomaly_detector import AnomalyDetector
	from .utils.llm_explainer import LLMExplainer
	from .utils.voice_agent import VoiceAgent
except ImportError:
	# Script import path when running python app.py from this folder
	from utils.anomaly_detector import AnomalyDetector
	from utils.llm_explainer import LLMExplainer
	from utils.voice_agent import VoiceAgent


BASE_DIR = Path(__file__).resolve().parent
MODEL_DIR = BASE_DIR / "models"
ENV_PATH = BASE_DIR / ".env"

# Load env from mediconnect_backend/.env if present.
load_dotenv(dotenv_path=ENV_PATH if ENV_PATH.exists() else None)

app = Flask(__name__)

_explainer_cache = None
_voice_agent_cache = None


def _model_files_status() -> Dict[str, bool]:
	categories = ["maternal", "child", "tb", "general"]
	return {
		category: (MODEL_DIR / f"if_model_{category}.pkl").exists()
		for category in categories
	}


def _safe_llm_explainer() -> LLMExplainer:
	global _explainer_cache
	if _explainer_cache is None:
		_explainer_cache = LLMExplainer()
	return _explainer_cache


def _safe_voice_agent() -> VoiceAgent:
	global _voice_agent_cache
	if _voice_agent_cache is None:
		_voice_agent_cache = VoiceAgent()
	return _voice_agent_cache


def _json_error(message: str, status_code: int = 400, details: Any = None):
	payload: Dict[str, Any] = {"error": message}
	if details is not None:
		payload["details"] = details
	return jsonify(payload), status_code


def _normalize_history(history: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
	# Ensure chronological order if visit_number is provided.
	if history and isinstance(history[0], dict) and "visit_number" in history[0]:
		return sorted(history, key=lambda v: v.get("visit_number", 0))
	return history


@app.get("/health")
def health_check():
	model_status = _model_files_status()
	gemini_key_present = bool(os.getenv("GEMINI_API_KEY"))

	return jsonify(
		{
			"status": "ok",
			"service": "mediconnect_backend",
			"models": model_status,
			"all_models_available": all(model_status.values()),
			"gemini_key_present": gemini_key_present,
			"todo": {
				"GEMINI_API_KEY": "Set GEMINI_API_KEY in mediconnect_backend/.env",
			},
		}
	)


@app.post("/check_symptom")
def check_symptom_endpoint():
	"""
	Legacy endpoint retained for Android backward compatibility.
	"""
	data = request.get_json(silent=True) or {}
	symptom = str(data.get("symptom", "")).strip()
	language = str(data.get("language", "English")).strip()

	if not symptom:
		return _json_error("Symptom not provided.", 400)

	prompt = (
		f"Please provide your response in {language}. "
		"You are a helpful AI health assistant for MediConnect. "
		"Analyze the symptom in simple language, suggest 2-3 actionable home-care tips, "
		"and end with this disclaimer on a new line: "
		"'Disclaimer: This is an AI-generated suggestion and not a substitute for professional "
		"medical advice. Please consult a doctor.' "
		f"Symptom: {symptom}"
	)

	try:
		explainer = _safe_llm_explainer()
		suggestion = explainer._call(prompt, max_tokens=400)
	except Exception as exc:
		suggestion = (
			"AI service is not configured. "
			"TODO: Set GEMINI_API_KEY in mediconnect_backend/.env. "
			f"Details: {exc}"
		)

	return jsonify({"suggestion": suggestion})


@app.post("/api/triage")
def triage_endpoint():
	data = request.get_json(silent=True) or {}
	category = str(data.get("category", "")).strip().lower()
	patient_id = str(data.get("patient_id", "")).strip() or None
	history = data.get("history", [])

	if not category:
		return _json_error("'category' is required.")
	if not isinstance(history, list) or not history:
		return _json_error("'history' must be a non-empty list of visit objects.")
	if not all(isinstance(v, dict) for v in history):
		return _json_error("Each item in 'history' must be an object.")

	normalized_history = _normalize_history(history)
	result = AnomalyDetector.predict(category=category, history=normalized_history)

	if "error" in result:
		model_status = _model_files_status()
		details = {
			"models": model_status,
			"hint": (
				"Run data/generate_synthetic_data.py, then models/train_models.py "
				"to generate model artifacts."
			),
		}
		return _json_error(result["error"], 400, details=details)

	if patient_id:
		result["patient_id"] = patient_id

	return jsonify(result)


@app.post("/api/explain")
def explain_endpoint():
	data = request.get_json(silent=True) or {}
	context = data.get("explanation_context")
	audience = str(data.get("audience", "asha")).strip().lower()
	language = str(data.get("language", "en")).strip().lower() or "en"

	if not isinstance(context, dict):
		return _json_error("'explanation_context' must be an object.")
	if audience not in {"asha", "doctor"}:
		return _json_error("'audience' must be either 'asha' or 'doctor'.")

	try:
		explainer = _safe_llm_explainer()
		if audience == "doctor":
			explanation = explainer.explain_for_doctor(context)
		else:
			explanation = explainer.explain_for_asha(context, language=language)
	except Exception as exc:
		return _json_error(
			"LLM explainability unavailable.",
			503,
			details={
				"todo": "Set GEMINI_API_KEY in mediconnect_backend/.env and install requirements.",
				"reason": str(exc),
			},
		)

	return jsonify(
		{
			"explanation": explanation,
			"audience": audience,
			"language": language,
			"risk_level": context.get("risk_level"),
		}
	)


@app.post("/api/chat")
def chat_endpoint():
	data = request.get_json(silent=True) or {}
	message = str(data.get("message", "")).strip()
	language = str(data.get("language", "en")).strip().lower() or "en"
	patient_context = data.get("patient_context")
	history = data.get("history", [])

	if not message:
		return _json_error("'message' is required.")
	if patient_context is not None and not isinstance(patient_context, dict):
		return _json_error("'patient_context' must be an object if provided.")
	if not isinstance(history, list):
		return _json_error("'history' must be a list if provided.")

	try:
		explainer = _safe_llm_explainer()
		response_text = explainer.chat_response(
			user_message=message,
			patient_context=patient_context,
			language=language,
			chat_history=history,
		)
		app.logger.info("AI response (%s): %s", language, response_text)
		print(f"[AI response {language}] {response_text}", flush=True)
	except Exception as exc:
		return _json_error(
			"Chat service unavailable.",
			503,
			details={
				"todo": "Set GEMINI_API_KEY in mediconnect_backend/.env and install requirements.",
				"reason": str(exc),
			},
		)

	return jsonify({"response": response_text, "language": language, "tts_audio_b64": None})


@app.post("/api/voice/tts")
def voice_tts_endpoint():
	data = request.get_json(silent=True) or {}
	text = str(data.get("text", "")).strip()
	language = str(data.get("language", "en")).strip().lower() or "en"

	if not text:
		return _json_error("'text' is required.")

	try:
		voice_agent = _safe_voice_agent()
		audio_b64 = voice_agent.text_to_speech_b64(text, language=language)
	except Exception as exc:
		return _json_error("Voice TTS unavailable.", 503, details=str(exc))

	return jsonify({"audio_b64": audio_b64, "language": language, "format": "mp3"})


@app.post("/api/voice/intent")
def voice_intent_endpoint():
	data = request.get_json(silent=True) or {}
	transcript = str(data.get("transcript", "")).strip()
	language = str(data.get("language", "en")).strip().lower() or "en"

	if not transcript:
		return _json_error("'transcript' is required.")

	try:
		voice_agent = _safe_voice_agent()
		parsed = voice_agent.parse_voice_intent(transcript=transcript, language=language)
	except Exception as exc:
		return _json_error("Voice intent parsing unavailable.", 503, details=str(exc))

	return jsonify(parsed)


@app.get("/api/voice/guided/<category>/<int:step>")
def voice_guided_endpoint(category: str, step: int):
	language = str(request.args.get("lang", "en")).strip().lower() or "en"
	if step < 0:
		return _json_error("'step' must be >= 0.")

	try:
		voice_agent = _safe_voice_agent()
		payload = voice_agent.guided_vitals_response(category=category, step=step, language=language)
	except Exception as exc:
		return _json_error("Guided vitals workflow unavailable.", 503, details=str(exc))

	return jsonify(payload)


@app.post("/api/voice/answer")
def voice_answer_endpoint():
	data = request.get_json(silent=True) or {}
	question = str(data.get("question", "")).strip()
	language = str(data.get("language", "en")).strip().lower() or "en"
	patient_context = data.get("patient_context")

	if not question:
		return _json_error("'question' is required.")
	if patient_context is not None and not isinstance(patient_context, dict):
		return _json_error("'patient_context' must be an object if provided.")

	try:
		voice_agent = _safe_voice_agent()
		payload = voice_agent.answer_health_question(
			question=question,
			patient_context=patient_context,
			language=language,
		)
	except Exception as exc:
		return _json_error("Voice answer service unavailable.", 503, details=str(exc))

	return jsonify(payload)


if __name__ == "__main__":
	app.run(host="0.0.0.0", port=5000, debug=True)
