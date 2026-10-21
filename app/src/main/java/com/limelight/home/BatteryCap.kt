package com.limelight.home

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.limelight.preferences.PreferenceConfiguration

/**
 * Settings, Handheld, "On battery, stream at" (#65): while the device isn't plugged in, streams are capped at that
 * height. Plugged in, the Picture settings apply. Only the handheld app sets it; elsewhere it stays "same".
 */
object BatteryCap {
    const val PREF = "hh_battery_res"

    /** The height to cap a stream at now, or 0: no cap (the setting is Same, or the device is plugged in). */
    @JvmStatic
    fun height(context: Context): Int {
        val cap = context.getSharedPreferences("mc_tv", Context.MODE_PRIVATE).getString(PREF, "same")?.toIntOrNull() ?: return 0
        val status = try { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (e: RuntimeException) { null } ?: return 0
        return if (status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0) cap else 0
    }

    /** Caps a PC stream's size, keeping its shape (width a multiple of 8). True if it was capped. */
    @JvmStatic
    fun apply(context: Context, config: PreferenceConfiguration): Boolean {
        val cap = height(context)
        if (cap <= 0 || config.height <= cap) return false
        val w = (Math.round(config.width.toDouble() * cap / config.height / 8.0) * 8).toInt()
        Log.i("MoonCast", "on battery: stream ${config.width}x${config.height} capped to ${w}x$cap")
        config.width = w
        config.height = cap
        return true
    }
}
