package com.limelight.home

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Streaming status for the PS5 and Xbox players: one line of live numbers in the top-left corner while playing
 * (the PC player has Moonlight's own). It follows Settings' "Stats while playing", and the Quick menu flips it.
 * [stats] gives the Quick menu's (value, label) tiles; the line shows them once the stream has its first numbers.
 */
class StatusCorner(private val act: Activity, private val info: () -> String = { "" },
                   private val stats: () -> List<Pair<String, String>>) {
    private val ui = Handler(Looper.getMainLooper())
    private var view: TextView? = null
    private var level = StatsLevel.OFF
    private val tick = object : Runnable {
        override fun run() {
            val v = view ?: return
            val line = line()
            if (v.text.toString() != line.toString()) v.text = line   // a new text lays the player out again: only when it changed
            val vis = if (line.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
            if (v.visibility != vis) v.visibility = vis
            ui.postDelayed(this, 1000)
        }
    }

    /** Show it as Settings say (call once the player's screen is up). */
    fun apply() = set(StatsLevel.get(act))

    /** The Quick menu's Streaming status: Off, Lite, Detailed, Off… now, and saves the choice. */
    fun toggle() {
        val next = StatsLevel.next(StatsLevel.get(act))
        StatsLevel.set(act, next)
        set(next)
    }

    fun stop() { ui.removeCallbacks(tick) }

    private fun set(lv: String) {
        level = lv
        val on = lv != StatsLevel.OFF
        view?.let { if (on) { it.text = ""; StatsPill.place(it, lv); return } }
        if (on == (view != null)) return
        if (!on) {
            ui.removeCallbacks(tick)
            (view?.parent as? FrameLayout)?.removeView(view)
            view = null
            return
        }
        val root = act.findViewById<FrameLayout>(android.R.id.content) ?: return
        val d = act.resources.displayMetrics.density
        view = TextView(act).apply { visibility = android.view.View.GONE }
        root.addView(view, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = (12 * d).toInt(); topMargin = (12 * d).toInt()
        })
        StatsPill.style(view!!)
        StatsPill.place(view!!, lv)
        ui.post(tick)
    }

    private fun line(): CharSequence {
        val s = stats()
        if (s.firstOrNull()?.second != "Frames per second") return ""   // still connecting
        var rtt = 0.0; var loss = 0.0
        val known = s.filter { it.first != "…" }
        known.forEach { (v, label) -> if (label == "Network delay") rtt = StatsPill.num(v); if (label == "Lost packets") loss = StatsPill.num(v) }
        if (level == StatsLevel.LITE) {
            val lite = known.mapNotNull { (v, label) ->
                when (label) {
                    "Frames per second" -> StatsPill.Chip(v, "fps")
                    "Network delay" -> StatsPill.Chip(v.removeSuffix(" ms"), "ms")
                    else -> null
                }
            }
            return StatsPill.text(lite, StatsPill.health(rtt, loss))
        }
        if (level == StatsLevel.DETAILED) {
            val rows = known.map { (v, label) ->
                when (label) {
                    "Frames per second" -> StatsPill.Chip("$v fps", "shown")
                    "Network delay" -> StatsPill.Chip(v, "network delay")
                    "Picture quality" -> StatsPill.Chip(v, "bitrate")
                    "Lost packets" -> StatsPill.Chip(v, "packets lost on the network")
                    "Decode time" -> StatsPill.Chip(v, "decode")
                    else -> StatsPill.Chip(v, label.lowercase())
                }
            }
            return StatsPill.detailed(info().ifEmpty { "Streaming" }, rows, StatsPill.health(rtt, loss))
        }
        val chips = known.map { (v, label) ->
            when (label) {
                "Frames per second" -> StatsPill.Chip(v, "fps")
                "Network delay" -> { rtt = StatsPill.num(v); StatsPill.Chip(v.removeSuffix(" ms"), "ms delay") }
                "Lost packets" -> { loss = StatsPill.num(v); StatsPill.Chip(v, "lost") }
                "Decode time" -> StatsPill.Chip(v.removeSuffix(" ms"), "ms decode")
                "Picture quality" -> StatsPill.Chip(v.removeSuffix(" Mbps"), "Mbps")
                else -> StatsPill.Chip(v, "")
            }
        }
        return StatsPill.text(chips, StatsPill.health(rtt, loss))
    }

    companion object {
        /** Settings' "Stats while playing" is Lite or Detailed (one setting for PC, PS5 and Xbox). */
        fun enabled(c: Context) = StatsLevel.get(c) != StatsLevel.OFF
    }
}
