package com.limelight.home

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Streaming status for the PS5 and Xbox players: one line of live numbers in the top-left corner while playing
 * (the PC player has Moonlight's own). It follows Settings' "Stats while playing", and the Quick menu flips it.
 * [stats] gives the Quick menu's (value, label) tiles; the line shows them once the stream has its first numbers.
 */
class StatusCorner(private val act: Activity, private val stats: () -> List<Pair<String, String>>) {
    private val ui = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private val tick = object : Runnable {
        override fun run() {
            val v = view ?: return
            val line = line()
            v.text = line
            v.visibility = if (line.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            ui.postDelayed(this, 1000)
        }
    }

    /** Show or hide it as Settings say (call once the player's screen is up). */
    fun apply() = set(enabled(act))

    /** The Quick menu's toggle: flips it now and saves the choice. */
    fun toggle() {
        val on = !enabled(act)
        android.preference.PreferenceManager.getDefaultSharedPreferences(act).edit().putBoolean(PREF, on).apply()
        set(on)
    }

    fun stop() { ui.removeCallbacks(tick) }

    private fun set(on: Boolean) {
        if (on == (view != null)) return
        if (!on) {
            ui.removeCallbacks(tick)
            (view?.parent as? FrameLayout)?.removeView(view)
            view = null
            return
        }
        val root = act.findViewById<FrameLayout>(android.R.id.content) ?: return
        val d = act.resources.displayMetrics.density
        view = TextView(act).apply {
            setTextColor(0xFFE8ECFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setBackgroundColor(0x99000000.toInt())
            setPadding((10 * d).toInt(), (5 * d).toInt(), (10 * d).toInt(), (5 * d).toInt())
            visibility = android.view.View.GONE
        }
        root.addView(view, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = (12 * d).toInt(); topMargin = (12 * d).toInt()
        })
        ui.post(tick)
    }

    private fun line(): String {
        val s = stats()
        if (s.firstOrNull()?.second != "Frames per second") return ""   // still connecting
        return s.filter { it.first != "…" }.joinToString("   ") { (v, label) ->
            when (label) {
                "Frames per second" -> "$v fps"
                "Network delay" -> "$v delay"
                "Lost packets" -> "$v lost"
                "Decode time" -> "$v decode"
                else -> v
            }
        }
    }

    companion object {
        /** Moonlight's own "Stats while playing" key, so one switch covers PC, PS5 and Xbox. */
        const val PREF = "checkbox_enable_perf_overlay"
        fun enabled(c: Context) = android.preference.PreferenceManager.getDefaultSharedPreferences(c).getBoolean(PREF, false)
    }
}
