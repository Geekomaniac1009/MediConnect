# MediConnect Backend (EWMA + Isolation Forest + LLM + Voice)

This service provides the backend APIs for triage, explainability, chatbot, and voice support.

## Features

- EWMA + Isolation Forest triage over longitudinal vitals.
- Rule-based safety override for hard clinical thresholds.
- LLM explainability for ASHA workers and doctors.
- Chat endpoint for multilingual guidance.
- Voice support endpoints (TTS, intent parsing, guided prompts).

## Setup

1. Create and activate a Python virtual environment.
2. Install dependencies:
   - `pip install -r requirements.txt`
3. Create `.env` from `.env.example`.
4. Update `GEMINI_API_KEY` in `.env`.

## One-time model artifact generation

Run in order from this folder:

1. `python data/generate_synthetic_data.py`
2. `python models/train_models.py --eval`

This creates model files under `models/`:

- `if_model_maternal.pkl`
- `if_model_child.pkl`
- `if_model_tb.pkl`
- `if_model_general.pkl`
- `model_metadata.json`

## Run server

- `python app.py`

Server starts at `http://0.0.0.0:5000`.

## API summary

- `GET /health`
- `POST /check_symptom`
- `POST /api/triage`
- `POST /api/explain`
- `POST /api/chat`
- `POST /api/voice/tts`
- `POST /api/voice/intent`
- `GET /api/voice/guided/<category>/<step>?lang=en`
- `POST /api/voice/answer`

## Notes

- If Gemini key or package is missing, non-LLM endpoints still work.
- LLM endpoints return clear error details with TODO guidance.
- TTS uses gTTS (free model) and returns base64 MP3.
