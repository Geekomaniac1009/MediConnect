"""
AnomalyDetector: EWMA + Isolation Forest inference engine
==========================================================
Given a patient's longitudinal vitals history, this module:
  1. Computes EWMA features (smoothed trend + drift from trend)
  2. Runs the appropriate Isolation Forest model
  3. Returns an anomaly score, risk level, and feature-level explanations
     that are later passed to the LLM for natural language summarization.
"""

import os
import pickle
import json
import numpy as np
from typing import Dict, Any, List, Optional

MODEL_DIR = os.path.join(os.path.dirname(__file__), "../models")
ALPHA = 0.3   # EWMA smoothing factor (higher = more weight on recent values)

# Which raw features each category uses (must match training feature set)
RAW_FEATURES = {
    "maternal": ["systolic_bp", "diastolic_bp", "hemoglobin", "weight_kg",
                 "spo2", "pulse", "gestational_week"],
    "child":    ["weight_kg", "muac_cm", "waz_score", "spo2", "temperature", "age_months"],
    "tb":       ["weight_kg", "cough_severity", "night_sweats_score",
                 "missed_doses_week", "spo2", "temperature", "treatment_month"],
    "general":  ["systolic_bp", "diastolic_bp", "fasting_glucose",
                 "spo2", "pulse", "bmi"],
}

# Human-readable names for features
FEATURE_LABELS = {
    "systolic_bp":         "Systolic Blood Pressure",
    "diastolic_bp":        "Diastolic Blood Pressure",
    "hemoglobin":          "Hemoglobin (Hb)",
    "weight_kg":           "Body Weight",
    "spo2":                "Oxygen Saturation (SpO2)",
    "pulse":               "Pulse Rate",
    "muac_cm":             "Mid-Upper Arm Circumference",
    "waz_score":           "Weight-for-Age Z-score",
    "temperature":         "Body Temperature",
    "cough_severity":      "Cough Severity",
    "night_sweats_score":  "Night Sweats",
    "missed_doses_week":   "Missed Doses (weekly)",
    "fasting_glucose":     "Fasting Blood Glucose",
    "bmi":                 "Body Mass Index",
}

# Clinical thresholds for direct rule-based flagging (first-line safety net)
CLINICAL_RULES = {
    "maternal": {
        "systolic_bp":  (">=", 140, "HIGH", "Hypertensive range (possible pre-eclampsia)"),
        "diastolic_bp": (">=", 90,  "HIGH", "Hypertensive range"),
        "hemoglobin":   ("<=", 7.0, "HIGH", "Severe anemia"),
        "spo2":         ("<=", 94,  "HIGH", "Hypoxia"),
    },
    "child": {
        "waz_score":    ("<=", -3.0, "HIGH",   "Severe acute malnutrition"),
        "muac_cm":      ("<=", 11.5, "HIGH",   "SAM threshold"),
        "temperature":  (">=", 38.5, "MEDIUM", "Fever"),
        "spo2":         ("<=", 94,   "HIGH",   "Hypoxia"),
    },
    "tb": {
        "spo2":              ("<=", 94, "HIGH",   "Hypoxia — possible treatment failure"),
        "missed_doses_week": (">=", 4,  "HIGH",   "High default risk"),
        "cough_severity":    (">=", 7,  "MEDIUM", "Worsening cough"),
    },
    "general": {
        "systolic_bp":    (">=", 160, "HIGH",   "Hypertensive crisis"),
        "fasting_glucose":(">=", 200, "HIGH",   "Probable uncontrolled diabetes"),
        "spo2":           ("<=", 94,  "HIGH",   "Hypoxia"),
    },
}


class AnomalyDetector:
    _models = {}
    _metadata = {}

    @classmethod
    def _load(cls):
        if cls._models:
            return
        meta_path = os.path.join(MODEL_DIR, "model_metadata.json")
        if os.path.exists(meta_path):
            with open(meta_path) as f:
                cls._metadata = json.load(f)
        for cat in ["maternal", "child", "tb", "general"]:
            path = os.path.join(MODEL_DIR, f"if_model_{cat}.pkl")
            if os.path.exists(path):
                with open(path, "rb") as f:
                    cls._models[cat] = pickle.load(f)

    @classmethod
    def compute_ewma_features(cls, history: List[Dict], raw_cols: List[str]) -> Dict[str, float]:
        """
        Given an ordered list of visit dicts (oldest first), compute EWMA
        and drift for the latest visit.
        """
        if not history:
            return {}
        ewma_vals = {}
        for col in raw_cols:
            vals = [v.get(col) for v in history if v.get(col) is not None]
            if not vals:
                continue
            ewma = vals[0]
            for x in vals[1:]:
                ewma = ALPHA * x + (1 - ALPHA) * ewma
            current = vals[-1]
            ewma_vals[f"{col}_ewma"] = round(ewma, 4)
            ewma_vals[f"{col}_drift"] = round(current - ewma, 4)
        return ewma_vals

    @classmethod
    def predict(cls, category: str, history: List[Dict]) -> Dict[str, Any]:
        """
        Main inference call.

        Args:
            category: "maternal" | "child" | "tb" | "general"
            history: list of visit dicts (chronological order).
                     Each dict must contain the raw feature values.

        Returns dict with:
            risk_level: "LOW" | "MEDIUM" | "HIGH"
            anomaly_score: float (0–1, higher = more anomalous)
            rule_flags: list of clinical rule violations
            drift_summary: dict of feature drift values
            explanation_context: structured dict for LLM prompt
        """
        cls._load()

        if not history:
            return {"error": "No history provided"}
        if category not in cls._models:
            return {"error": f"No model for category: {category}"}

        latest = history[-1]
        raw_cols = RAW_FEATURES[category]
        meta = cls._metadata.get(category, {})

        # ── 1. Compute EWMA features ──────────────────────────────────────
        ewma_feats = cls.compute_ewma_features(history, raw_cols)

        # ── 2. Build feature vector matching training order ───────────────
        trained_features = meta.get("features", [])
        if not trained_features:
            trained_features = [f for f in raw_cols] + \
                               [f for f in ewma_feats.keys()]

        row = {}
        for f in trained_features:
            if f in latest:
                row[f] = latest[f]
            elif f in ewma_feats:
                row[f] = ewma_feats[f]
            else:
                row[f] = 0.0   # fallback

        X = np.array([[row.get(f, 0.0) for f in trained_features]])

        # ── 3. Isolation Forest score ──────────────────────────────────────
        raw_score = float(-cls._models[category].score_samples(X)[0])
        thresh_med  = meta.get("threshold_medium", 0.55)
        thresh_high = meta.get("threshold_high", 0.65)

        # Normalize to 0-1
        score_norm = min(1.0, max(0.0, (raw_score - 0.40) / 0.40))

        if raw_score >= thresh_high:
            if_level = "HIGH"
        elif raw_score >= thresh_med:
            if_level = "MEDIUM"
        else:
            if_level = "LOW"

        # ── 4. Clinical rule flags ─────────────────────────────────────────
        rule_flags = []
        cat_rules = CLINICAL_RULES.get(category, {})
        for feat, (op, threshold, sev, msg) in cat_rules.items():
            val = latest.get(feat)
            if val is None:
                continue
            triggered = (
                (op == ">=" and val >= threshold) or
                (op == "<=" and val <= threshold)
            )
            if triggered:
                rule_flags.append({
                    "feature": feat,
                    "label": FEATURE_LABELS.get(feat, feat),
                    "value": val,
                    "threshold": threshold,
                    "severity": sev,
                    "message": msg,
                })

        # ── 5. Overall risk level (rule override + IF score) ──────────────
        rule_max = max((r["severity"] for r in rule_flags),
                       key=lambda x: {"LOW": 0, "MEDIUM": 1, "HIGH": 2}.get(x, 0),
                       default="LOW") if rule_flags else "LOW"

        def level_int(l): return {"LOW": 0, "MEDIUM": 1, "HIGH": 2}.get(l, 0)
        risk_level = if_level if level_int(if_level) >= level_int(rule_max) else rule_max

        # ── 6. Top drifting features for explanation ───────────────────────
        drift_items = {k.replace("_drift", ""): v
                       for k, v in ewma_feats.items() if k.endswith("_drift")}
        top_drifts = sorted(drift_items.items(), key=lambda x: abs(x[1]), reverse=True)[:4]
        drift_summary = {k: v for k, v in top_drifts}

        # ── 7. Context for LLM explanation ────────────────────────────────
        explanation_context = {
            "category": category,
            "visit_count": len(history),
            "latest_vitals": {k: latest.get(k) for k in raw_cols if k in latest},
            "anomaly_score": round(score_norm, 3),
            "risk_level": risk_level,
            "if_raw_score": round(raw_score, 4),
            "top_drifting_features": [
                {"feature": FEATURE_LABELS.get(k, k), "drift": v}
                for k, v in top_drifts
            ],
            "rule_violations": rule_flags,
            "ewma_features": {k.replace("_ewma", ""): v
                              for k, v in ewma_feats.items() if k.endswith("_ewma")},
        }

        return {
            "risk_level": risk_level,
            "anomaly_score": round(score_norm, 3),
            "if_raw_score": round(raw_score, 4),
            "rule_flags": rule_flags,
            "drift_summary": drift_summary,
            "explanation_context": explanation_context,
        }


if __name__ == "__main__":
    # Quick smoke test
    detector = AnomalyDetector()

    # Test: pre-eclampsia pattern
    maternal_history = [
        {"systolic_bp": 115, "diastolic_bp": 75, "hemoglobin": 11.2,
         "weight_kg": 62, "spo2": 99, "pulse": 80, "gestational_week": 14},
        {"systolic_bp": 118, "diastolic_bp": 76, "hemoglobin": 11.0,
         "weight_kg": 63.5, "spo2": 98, "pulse": 82, "gestational_week": 20},
        {"systolic_bp": 145, "diastolic_bp": 95, "hemoglobin": 8.5,
         "weight_kg": 67, "spo2": 93, "pulse": 105, "gestational_week": 26},
    ]
    result = detector.predict("maternal", maternal_history)
    print(json.dumps(result, indent=2))