package com.limelight.home

import android.os.Build

/** This device's chip by its marketing name ("Snapdragon 8 Gen 2"), for the Ultra low latency hint in both apps. */
object Chip {
    private val QUALCOMM = mapOf(
            "8750" to "Snapdragon 8 Elite", "8650" to "Snapdragon 8 Gen 3", "8635" to "Snapdragon 8s Gen 3",
            "8550" to "Snapdragon 8 Gen 2", "8475" to "Snapdragon 8+ Gen 1", "8450" to "Snapdragon 8 Gen 1",
            "8350" to "Snapdragon 888", "8250" to "Snapdragon 865", "8150" to "Snapdragon 855",
            "7675" to "Snapdragon 7+ Gen 3", "7550" to "Snapdragon 7 Gen 3", "7475" to "Snapdragon 7+ Gen 2",
            "7450" to "Snapdragon 7 Gen 1", "6375" to "Snapdragon 695")
    private val MEDIATEK = mapOf(
            "MT6991" to "Dimensity 9400", "MT6989" to "Dimensity 9300", "MT6985" to "Dimensity 9200",
            "MT6983" to "Dimensity 9000", "MT6897" to "Dimensity 8300", "MT6896" to "Dimensity 8200",
            "MT6895" to "Dimensity 8100", "MT6893" to "Dimensity 1200")

    /** The chip's name, with its model id in brackets when it maps ("Snapdragon 8 Gen 2 (QCS8550)"); else the raw id. */
    @JvmStatic fun name(): String {
        val model = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.trim() else ""
        val maker = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER.trim() else ""
        if (model.isBlank() || model.equals("unknown", true)) return Build.HARDWARE
        val up = model.uppercase()
        // SM8550, QCS8550, SM8550P, SM8750-AB: the four digits after the letters name the chip
        Regex("^(SM|QCS|QCM|SDM)(\\d{4})").find(up)?.let { m -> QUALCOMM[m.groupValues[2]]?.let { return "$it ($model)" } }
        MEDIATEK.entries.firstOrNull { up.startsWith(it.key) }?.let { return "${it.value} ($model)" }
        val m = when { maker.equals("QTI", true) -> "Qualcomm"; else -> maker }
        return "$m $model".trim()
    }
}
