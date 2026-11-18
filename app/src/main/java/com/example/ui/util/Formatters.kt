package com.example.ui.util

import com.example.domain.model.RegionDefaults
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formatters {

    fun formatMoney(amount: Double?, currencyCode: String): String {
        if (amount == null) return "Unknown"
        val symbol = RegionDefaults.symbolFor(currencyCode)
        val nf = NumberFormat.getNumberInstance(Locale.US).apply {
            minimumFractionDigits = if (currencyCode.equals("JPY", ignoreCase = true)) 0 else 2
            maximumFractionDigits = if (currencyCode.equals("JPY", ignoreCase = true)) 0 else 2
        }
        return "$symbol${nf.format(amount)}"
    }

    fun formatTime(millis: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(millis))
    }

    fun formatDateTime(date: Date?): String {
        if (date == null) return "Just now"
        val sdf = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
        return sdf.format(date)
    }
}
