"""
EWMA + Isolation Forest Anomaly Detection Model
================================================
Trains one Isolation Forest per patient category (maternal, child, tb, general).
Uses EWMA-derived features (drift vectors) as input, making the detector
sensitive to *gradual longitudinal drift* rather than one-off outliers.

Usage:
    python train_models.py          # trains + saves all models
    python train_models.py --eval   # also prints evaluation metrics
"""

import os
import sys
import json
import pickle
import argparse
import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import (classification_report, roc_auc_score,
                             precision_recall_curve, average_precision_score)
from sklearn.pipeline import Pipeline

DATA_DIR = os.path.join(os.path.dirname(__file__), "../data")
MODEL_DIR = os.path.join(os.path.dirname(__file__))

# Feature sets per category (raw + EWMA drift columns)
FEATURE_SETS = {
    "maternal": [
        "systolic_bp", "diastolic_bp", "hemoglobin", "weight_kg", "spo2", "pulse",
        "systolic_bp_drift", "diastolic_bp_drift", "hemoglobin_drift",
        "weight_kg_drift", "spo2_drift", "pulse_drift",
        "systolic_bp_ewma", "diastolic_bp_ewma", "hemoglobin_ewma",
        "gestational_week",
    ],
    "child": [
        "weight_kg", "muac_cm", "waz_score", "spo2", "temperature",
        "weight_kg_drift", "muac_cm_drift", "waz_score_drift",
        "spo2_drift", "temperature_drift",
        "weight_kg_ewma", "muac_cm_ewma", "waz_score_ewma",
        "age_months",
    ],
    "tb": [
        "weight_kg", "cough_severity", "night_sweats_score",
        "missed_doses_week", "spo2", "temperature",
        "weight_kg_drift", "cough_severity_drift", "night_sweats_score_drift",
        "spo2_drift", "temperature_drift",
        "weight_kg_ewma", "cough_severity_ewma",
        "treatment_month",
    ],
    "general": [
        "systolic_bp", "diastolic_bp", "fasting_glucose", "spo2", "pulse", "bmi",
        "systolic_bp_drift", "diastolic_bp_drift", "fasting_glucose_drift",
        "spo2_drift", "pulse_drift",
        "systolic_bp_ewma", "fasting_glucose_ewma",
    ],
}

# IF hyperparameters tuned per category
IF_PARAMS = {
    "maternal": dict(n_estimators=200, contamination=0.12, max_features=0.8, random_state=42),
    "child":    dict(n_estimators=200, contamination=0.10, max_features=0.8, random_state=42),
    "tb":       dict(n_estimators=200, contamination=0.15, max_features=0.9, random_state=42),
    "general":  dict(n_estimators=200, contamination=0.08, max_features=0.8, random_state=42),
}

FILES = {
    "maternal": "maternal_vitals.csv",
    "child":    "child_vitals.csv",
    "tb":       "tb_vitals.csv",
    "general":  "general_vitals.csv",
}


def load_and_prepare(category: str):
    path = os.path.join(DATA_DIR, FILES[category])
    df = pd.read_csv(path)
    features = [f for f in FEATURE_SETS[category] if f in df.columns]
    X = df[features].fillna(df[features].median())
    y = df["is_anomalous"].values
    return X, y, features


def train_category(category: str, evaluate: bool = False):
    print(f"\n{'='*50}")
    print(f"Training: {category.upper()}")
    X, y, features = load_and_prepare(category)

    pipeline = Pipeline([
        ("scaler", StandardScaler()),
        ("iso_forest", IsolationForest(**IF_PARAMS[category])),
    ])
    pipeline.fit(X)

    # Isolation Forest: -1 = anomaly, 1 = normal → remap to 0/1
    raw_pred = pipeline.predict(X)
    pred_labels = (raw_pred == -1).astype(int)
    scores = -pipeline.score_samples(X)    # higher = more anomalous

    model_info = {
        "category": category,
        "features": features,
        "n_samples": len(X),
        "n_anomalies_ground_truth": int(y.sum()),
        "n_anomalies_predicted": int(pred_labels.sum()),
        "contamination": IF_PARAMS[category]["contamination"],
    }

    if evaluate and y.sum() > 0:
        ap = average_precision_score(y, scores)
        auc = roc_auc_score(y, scores)
        model_info["avg_precision"] = round(ap, 4)
        model_info["roc_auc"] = round(auc, 4)
        print(f"  ROC-AUC: {auc:.4f}  |  Avg Precision: {ap:.4f}")
        print(classification_report(y, pred_labels, target_names=["Normal", "Anomaly"]))

    # Compute anomaly score thresholds for risk bucketing
    p75 = float(np.percentile(scores, 75))
    p90 = float(np.percentile(scores, 90))
    model_info["threshold_medium"] = round(p75, 4)
    model_info["threshold_high"] = round(p90, 4)

    return pipeline, model_info


def save_models(models: dict, infos: dict):
    os.makedirs(MODEL_DIR, exist_ok=True)
    for cat, pipeline in models.items():
        path = os.path.join(MODEL_DIR, f"if_model_{cat}.pkl")
        with open(path, "wb") as f:
            pickle.dump(pipeline, f)
        print(f"  Saved: {path}")

    meta_path = os.path.join(MODEL_DIR, "model_metadata.json")
    with open(meta_path, "w") as f:
        json.dump(infos, f, indent=2)
    print(f"  Saved metadata: {meta_path}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--eval", action="store_true", help="Print evaluation metrics")
    args = parser.parse_args()

    models, infos = {}, {}
    for category in ["maternal", "child", "tb", "general"]:
        pipeline, info = train_category(category, evaluate=args.eval)
        models[category] = pipeline
        infos[category] = info

    print("\nSaving models...")
    save_models(models, infos)
    print("\n✅ All models trained and saved successfully.")


if __name__ == "__main__":
    main()