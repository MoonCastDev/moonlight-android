package com.limelight.home

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.content.Context
import android.preference.PreferenceManager
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Settings, Picture, "Stats while playing": Off, Lite or Detailed (the Quick menu's Streaming status cycles them).
 * Moonlight's own on/off switch stays in step (on = Lite or Detailed), since the PC player only measures while it's on;
 * someone who had it on before gets Detailed.
 */
object StatsLevel {
    const val PREF = "mc_stats_level"
    const val OLD = "checkbox_enable_perf_overlay"
    const val OFF = "off"
    const val LITE = "lite"
    const val DETAILED = "detailed"

    @JvmStatic
    fun get(c: Context): String {
        val p = PreferenceManager.getDefaultSharedPreferences(c)
        p.getString(PREF, null)?.let { return it }
        val v = if (p.getBoolean(OLD, false)) DETAILED else OFF
        p.edit().putString(PREF, v).apply()
        return v
    }

    @JvmStatic
    fun set(c: Context, v: String) {
        PreferenceManager.getDefaultSharedPreferences(c).edit().putString(PREF, v).putBoolean(OLD, v != OFF).apply()
    }

    /** Off, Lite, Detailed, Off… */
    @JvmStatic
    fun next(v: String): String = when (v) { OFF -> LITE; LITE -> DETAILED; else -> OFF }

    @JvmStatic
    fun label(v: String): String = when (v) { LITE -> "Lite"; DETAILED -> "Detailed"; else -> "Off" }
}

/**
 * "Stats while playing", the same compact card on PC, PS5 and Xbox: a rounded dark pill in the top-left corner with
 * one row of numbers (big bold value, small dim label) and a dot that is green, amber or red by delay and loss.
 */
object StatsPill {
    class Chip(val value: String, val label: String)

    private const val GREEN = 0xFF3DDC84.toInt()
    private const val AMBER = 0xFFFFB020.toInt()
    private const val RED = 0xFFFF5A5F.toInt()
    private const val DIM = 0xFF9AA3B2.toInt()

    /** Lays [v] out for [level]: Lite is a small pill centred at the top, Detailed a panel in the top-left corner. */
    @JvmStatic
    fun place(v: TextView, level: String) {
        val detailed = level == StatsLevel.DETAILED
        v.maxLines = if (detailed) 12 else 1
        v.isSingleLine = !detailed
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (detailed) 12.5f else 13f)
        v.setLineSpacing(0f, if (detailed) 1.25f else 1f)
        (v.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            val g = Gravity.TOP or if (detailed) Gravity.START else Gravity.CENTER_HORIZONTAL
            if (lp.gravity != g) { lp.gravity = g; v.layoutParams = lp }
        }
        v.requestApplyInsets()
    }

    /** Makes [v] the card: background, type, padding, and clear of a camera cutout. */
    @JvmStatic
    fun style(v: TextView) {
        val d = v.resources.displayMetrics.density
        v.background = GradientDrawable().apply { setColor(0xCC0C0E12.toInt()); cornerRadius = 16 * d }
        v.setTextColor(0xFFF2F4F8.toInt())
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        v.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        v.setPadding((14 * d).toInt(), (7 * d).toInt(), (14 * d).toInt(), (8 * d).toInt())
        v.maxLines = 1
        v.isSingleLine = true
        val base = (12 * d).toInt()
        v.setOnApplyWindowInsetsListener { view, ins ->
            var left = 0; var top = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ins.displayCutout?.let { left = it.safeInsetLeft; top = it.safeInsetTop }
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                // centred at the top (Lite): no side margin, so it sits in the middle
                val centred = (lp as? FrameLayout.LayoutParams)?.gravity?.and(Gravity.HORIZONTAL_GRAVITY_MASK) == Gravity.CENTER_HORIZONTAL
                val l = if (centred) 0 else base + left
                if (lp.leftMargin != l || lp.topMargin != base + top) {
                    lp.leftMargin = l; lp.marginStart = l; lp.topMargin = base + top
                    view.layoutParams = lp
                }
            }
            ins
        }
        v.requestApplyInsets()
    }

    /** 0 good, 1 so-so, 2 poor: by network delay (ms) and lost frames or packets (%). Unknown counts as good. */
    @JvmStatic
    fun health(rttMs: Double, lossPct: Double): Int = when {
        rttMs > 60 || lossPct >= 5 -> 2
        rttMs > 25 || lossPct >= 1 -> 1
        else -> 0
    }

    /** The card's text. */
    @JvmStatic
    fun text(chips: List<Chip>, health: Int): CharSequence {
        val sb = SpannableStringBuilder()
        val dot = sb.length
        sb.append("●  ")
        sb.setSpan(ForegroundColorSpan(when (health) { 0 -> GREEN; 1 -> AMBER; else -> RED }), dot, dot + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        chips.forEachIndexed { i, c ->
            if (i > 0) sb.append("    ")
            val a = sb.length
            sb.append(c.value)
            sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(1.2f), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (c.label.isNotEmpty()) {
                val b = sb.length
                sb.append(" ").append(c.label)
                sb.setSpan(ForegroundColorSpan(DIM), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(0.85f), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    /** The Detailed panel: a [title] line with the health dot, then one line per (value, label) row. */
    @JvmStatic
    fun detailed(title: String, rows: List<Chip>, health: Int): CharSequence {
        val sb = SpannableStringBuilder()
        sb.append("●  ")
        sb.setSpan(ForegroundColorSpan(when (health) { 0 -> GREEN; 1 -> AMBER; else -> RED }), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val t = sb.length
        sb.append(title)
        sb.setSpan(StyleSpan(Typeface.BOLD), t, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(RelativeSizeSpan(1.1f), t, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        for (r in rows) {
            sb.append('\n')
            val a = sb.length
            sb.append(r.value)
            sb.setSpan(StyleSpan(Typeface.BOLD), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (r.label.isNotEmpty()) {
                val b = sb.length
                sb.append("  ").append(r.label)
                sb.setSpan(ForegroundColorSpan(DIM), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                sb.setSpan(RelativeSizeSpan(0.9f), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sb
    }

    /** "1.5%"-style numbers back to a double; 0 when it isn't one. */
    @JvmStatic
    fun num(s: String): Double = s.trimEnd('%', ' ', 'm', 's').replace(',', '.').toDoubleOrNull() ?: 0.0
}
