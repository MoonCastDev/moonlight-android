package com.limelight.home

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView

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
                if (lp.leftMargin != base + left || lp.topMargin != base + top) {
                    lp.leftMargin = base + left; lp.marginStart = base + left; lp.topMargin = base + top
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

    /** "1.5%"-style numbers back to a double; 0 when it isn't one. */
    @JvmStatic
    fun num(s: String): Double = s.trimEnd('%', ' ', 'm', 's').replace(',', '.').toDoubleOrNull() ?: 0.0
}
