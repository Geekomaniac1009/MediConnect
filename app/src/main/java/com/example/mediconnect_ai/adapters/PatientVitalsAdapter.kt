package com.example.mediconnect_ai.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.mediconnect_ai.R
import com.example.mediconnect_ai.database.PatientVitalRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PatientVitalsAdapter(private val records: List<PatientVitalRecord>) :
    RecyclerView.Adapter<PatientVitalsAdapter.VitalsViewHolder>() {

    class VitalsViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvVitalsTitle)
        val tvDate: TextView = view.findViewById(R.id.tvVitalsDate)
        val tvDetails: TextView = view.findViewById(R.id.tvVitalsDetails)
        val tvSource: TextView = view.findViewById(R.id.tvVitalsSource)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VitalsViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_vitals_record, parent, false)
        return VitalsViewHolder(view)
    }

    override fun onBindViewHolder(holder: VitalsViewHolder, position: Int) {
        val record = records[position]
        val category = record.category.ifBlank { "General" }
        val title = category.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }

        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        holder.tvTitle.text = "$title vitals"
        holder.tvDate.text = sdf.format(Date(record.recordedAt))
        holder.tvDetails.text = buildDetails(record)
        holder.tvSource.text = "Source: ${record.source}"
    }

    override fun getItemCount(): Int = records.size

    private fun buildDetails(record: PatientVitalRecord): String {
        val parts = mutableListOf<String>()

        val systolic = record.systolicBp
        val diastolic = record.diastolicBp
        if (systolic != null || diastolic != null) {
            val sysText = systolic?.let { formatNumber(it) } ?: "?"
            val diaText = diastolic?.let { formatNumber(it) } ?: "?"
            parts.add("BP: $sysText/$diaText")
        }

        record.hemoglobin?.let { parts.add("Hb: ${formatNumber(it)}") }
        record.weightKg?.let { parts.add("Weight: ${formatNumber(it)} kg") }
        record.spo2?.let { parts.add("SpO2: ${formatNumber(it)}") }
        record.pulse?.let { parts.add("Pulse: ${formatNumber(it)}") }
        record.temperature?.let { parts.add("Temp: ${formatNumber(it)}") }
        record.gestationalWeek?.let { parts.add("Week: $it") }
        record.muacCm?.let { parts.add("MUAC: ${formatNumber(it)}") }
        record.wazScore?.let { parts.add("WAZ: ${formatNumber(it)}") }
        record.ageMonths?.let { parts.add("Age: ${it} mo") }
        record.coughSeverity?.let { parts.add("Cough: ${formatNumber(it)}") }
        record.nightSweatsScore?.let { parts.add("Night sweats: ${formatNumber(it)}") }
        record.missedDosesWeek?.let { parts.add("Missed doses: $it") }
        record.treatmentMonth?.let { parts.add("Tx month: $it") }
        record.fastingGlucose?.let { parts.add("Glucose: ${formatNumber(it)}") }
        record.bmi?.let { parts.add("BMI: ${formatNumber(it)}") }
        record.notes?.takeIf { it.isNotBlank() }?.let { parts.add("Notes: $it") }

        return if (parts.isEmpty()) "No vitals captured." else parts.joinToString(" | ")
    }

    private fun formatNumber(value: Double): String {
        return if (value % 1.0 == 0.0) {
            value.toInt().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", value)
        }
    }
}
