"""
Synthetic patient vitals data generator for MediConnect AI.
Simulates longitudinal health data for ASHA-monitored patients:
  - Maternal health (BP, weight, hemoglobin)
  - Child growth (weight-for-age, MUAC)
  - TB follow-up (weight, symptom severity)
  - General vitals (temp, SpO2, pulse)

Generates both normal trajectories and anomalous "drift" patterns
to train the Isolation Forest model.
"""

import numpy as np
import pandas as pd
import json
import os

np.random.seed(42)

def generate_maternal_vitals(n_patients=200, visits_per_patient=4, anomaly_rate=0.15):
    records = []
    for pid in range(n_patients):
        is_anomalous = np.random.rand() < anomaly_rate
        base_systolic = np.random.normal(115, 8)
        base_diastolic = np.random.normal(75, 5)
        base_hb = np.random.normal(11.5, 1.2)       # g/dL
        base_weight = np.random.normal(58, 8)        # kg
        base_week = np.random.randint(8, 16)

        for v in range(visits_per_patient):
            week = base_week + v * 6
            # Normal drift: BP slightly rises toward end of pregnancy
            systolic = base_systolic + v * 1.5 + np.random.normal(0, 4)
            diastolic = base_diastolic + v * 1.0 + np.random.normal(0, 3)
            hb = base_hb - v * 0.15 + np.random.normal(0, 0.3)
            weight = base_weight + week * 0.35 + np.random.normal(0, 1)
            spo2 = np.random.normal(98, 0.8)
            pulse = np.random.normal(82, 8)
            temp = np.random.normal(36.7, 0.2)

            # Inject anomaly: pre-eclampsia pattern — sudden BP spike, low Hb
            if is_anomalous and v >= 2:
                systolic += np.random.normal(30, 8)   # sudden hypertension
                diastolic += np.random.normal(18, 5)
                hb -= np.random.normal(2.5, 0.5)      # severe anemia
                spo2 -= np.random.normal(4, 1)
                pulse += np.random.normal(25, 5)

            records.append({
                "patient_id": f"MAT_{pid:04d}",
                "category": "maternal",
                "visit_number": v + 1,
                "gestational_week": int(week),
                "systolic_bp": round(systolic, 1),
                "diastolic_bp": round(diastolic, 1),
                "hemoglobin": round(hb, 2),
                "weight_kg": round(weight, 1),
                "spo2": round(min(spo2, 100), 1),
                "pulse": round(pulse, 0),
                "temperature": round(temp, 1),
                "is_anomalous": int(is_anomalous and v >= 2),
            })
    return pd.DataFrame(records)


def generate_child_vitals(n_patients=200, visits_per_patient=5, anomaly_rate=0.12):
    records = []
    # WHO weight-for-age median (months → kg) rough approximation
    who_median = {0: 3.3, 2: 5.6, 4: 6.7, 6: 7.9, 9: 8.9, 12: 9.6, 18: 10.9, 24: 12.2}

    for pid in range(n_patients):
        is_anomalous = np.random.rand() < anomaly_rate
        start_age_months = np.random.randint(0, 12)
        base_weight = np.interp(start_age_months, list(who_median.keys()), list(who_median.values()))
        base_weight += np.random.normal(0, 0.5)
        muac = np.random.normal(14.5, 1.2)   # cm

        for v in range(visits_per_patient):
            age_months = start_age_months + v * 2
            expected = np.interp(age_months, list(who_median.keys()), list(who_median.values()))
            weight = base_weight + (age_months - start_age_months) * 0.28 + np.random.normal(0, 0.2)
            height = 50 + age_months * 0.85 + np.random.normal(0, 1.2)
            temp = np.random.normal(36.8, 0.3)
            spo2 = np.random.normal(98.5, 0.7)
            muac_v = muac + v * 0.1 + np.random.normal(0, 0.3)

            if is_anomalous and v >= 2:
                # SAM pattern: weight faltering
                weight -= np.random.normal(1.2, 0.4)
                muac_v -= np.random.normal(2.0, 0.5)
                temp += np.random.normal(1.5, 0.4)

            waz = (weight - expected) / 1.1   # rough Z-score approximation

            records.append({
                "patient_id": f"CHD_{pid:04d}",
                "category": "child",
                "visit_number": v + 1,
                "age_months": int(age_months),
                "weight_kg": round(weight, 2),
                "height_cm": round(height, 1),
                "muac_cm": round(muac_v, 1),
                "waz_score": round(waz, 2),
                "spo2": round(min(spo2, 100), 1),
                "temperature": round(temp, 1),
                "is_anomalous": int(is_anomalous and v >= 2),
            })
    return pd.DataFrame(records)


def generate_tb_vitals(n_patients=150, visits_per_patient=6, anomaly_rate=0.20):
    records = []
    for pid in range(n_patients):
        is_anomalous = np.random.rand() < anomaly_rate
        base_weight = np.random.normal(52, 9)
        adherence_score = np.random.uniform(0.7, 1.0)   # fraction of doses taken

        for v in range(visits_per_patient):
            month = v + 1
            # Normal: weight gain ~0.5 kg/month on treatment
            weight = base_weight + month * 0.5 + np.random.normal(0, 0.5)
            cough_severity = max(0, np.random.normal(3 - month * 0.4, 0.5))  # 0-10
            night_sweats = max(0, np.random.normal(2 - month * 0.3, 0.4))
            sputum_positive = int(month <= 2)
            missed_doses = int(np.random.binomial(7, 1 - adherence_score))
            temp = np.random.normal(37.0, 0.4)
            spo2 = np.random.normal(96.5, 1.0)

            if is_anomalous and v >= 3:
                # Treatment failure / default pattern
                weight -= np.random.normal(2.0, 0.6)
                cough_severity += np.random.normal(3, 0.8)
                night_sweats += np.random.normal(2, 0.5)
                missed_doses += np.random.randint(3, 7)
                temp += np.random.normal(1.2, 0.3)
                spo2 -= np.random.normal(3, 0.7)

            records.append({
                "patient_id": f"TB_{pid:04d}",
                "category": "tb",
                "visit_number": v + 1,
                "treatment_month": month,
                "weight_kg": round(weight, 1),
                "cough_severity": round(min(max(cough_severity, 0), 10), 1),
                "night_sweats_score": round(min(max(night_sweats, 0), 10), 1),
                "sputum_positive": sputum_positive,
                "missed_doses_week": int(min(missed_doses, 7)),
                "temperature": round(temp, 1),
                "spo2": round(min(spo2, 100), 1),
                "is_anomalous": int(is_anomalous and v >= 3),
            })
    return pd.DataFrame(records)


def generate_general_vitals(n_patients=300, visits_per_patient=3, anomaly_rate=0.10):
    records = []
    for pid in range(n_patients):
        is_anomalous = np.random.rand() < anomaly_rate
        base_systolic = np.random.normal(120, 12)
        base_glucose = np.random.normal(95, 12)   # mg/dL fasting

        for v in range(visits_per_patient):
            systolic = base_systolic + v * 0.5 + np.random.normal(0, 5)
            diastolic = systolic * 0.65 + np.random.normal(0, 4)
            glucose = base_glucose + v * 1.5 + np.random.normal(0, 8)
            temp = np.random.normal(36.7, 0.3)
            spo2 = np.random.normal(98, 0.8)
            pulse = np.random.normal(75, 10)
            bmi = np.random.normal(23, 3.5)

            if is_anomalous and v >= 1:
                # Diabetic drift / hypertensive crisis
                glucose += np.random.normal(60, 15)
                systolic += np.random.normal(35, 8)
                diastolic += np.random.normal(20, 5)
                pulse += np.random.normal(20, 5)

            records.append({
                "patient_id": f"GEN_{pid:04d}",
                "category": "general",
                "visit_number": v + 1,
                "systolic_bp": round(systolic, 1),
                "diastolic_bp": round(diastolic, 1),
                "fasting_glucose": round(glucose, 1),
                "temperature": round(temp, 1),
                "spo2": round(min(spo2, 100), 1),
                "pulse": round(pulse, 0),
                "bmi": round(bmi, 1),
                "is_anomalous": int(is_anomalous and v >= 1),
            })
    return pd.DataFrame(records)


def compute_ewma_features(df, group_col, value_cols, alpha=0.3):
    """
    Computes EWMA (Exponentially Weighted Moving Average) for longitudinal features.
    Also computes EWMA-deviation (current - EWMA) to capture drift.
    """
    results = []
    for pid, group in df.groupby(group_col):
        group = group.sort_values("visit_number").copy()
        for col in value_cols:
            if col in group.columns:
                ewma = group[col].ewm(alpha=alpha, adjust=False).mean()
                group[f"{col}_ewma"] = ewma.round(3)
                group[f"{col}_drift"] = (group[col] - ewma).round(3)
        results.append(group)
    return pd.concat(results, ignore_index=True)


if __name__ == "__main__":
    out_dir = os.path.dirname(__file__)

    print("Generating maternal vitals...")
    mat = generate_maternal_vitals(300, 4, 0.15)
    mat_cols = ["systolic_bp", "diastolic_bp", "hemoglobin", "weight_kg", "spo2", "pulse"]
    mat = compute_ewma_features(mat, "patient_id", mat_cols)

    print("Generating child vitals...")
    chd = generate_child_vitals(300, 5, 0.12)
    chd_cols = ["weight_kg", "muac_cm", "waz_score", "spo2", "temperature"]
    chd = compute_ewma_features(chd, "patient_id", chd_cols)

    print("Generating TB vitals...")
    tb = generate_tb_vitals(200, 6, 0.20)
    tb_cols = ["weight_kg", "cough_severity", "night_sweats_score", "spo2", "temperature"]
    tb = compute_ewma_features(tb, "patient_id", tb_cols)

    print("Generating general vitals...")
    gen = generate_general_vitals(400, 3, 0.10)
    gen_cols = ["systolic_bp", "diastolic_bp", "fasting_glucose", "spo2", "pulse"]
    gen = compute_ewma_features(gen, "patient_id", gen_cols)

    mat.to_csv(os.path.join(out_dir, "maternal_vitals.csv"), index=False)
    chd.to_csv(os.path.join(out_dir, "child_vitals.csv"), index=False)
    tb.to_csv(os.path.join(out_dir, "tb_vitals.csv"), index=False)
    gen.to_csv(os.path.join(out_dir, "general_vitals.csv"), index=False)

    total = len(mat) + len(chd) + len(tb) + len(gen)
    anom = (mat["is_anomalous"].sum() + chd["is_anomalous"].sum() +
            tb["is_anomalous"].sum() + gen["is_anomalous"].sum())
    print(f"\nDataset generated: {total} records, {anom} anomalies ({100*anom/total:.1f}%)")
    print(f"Saved to: {out_dir}")