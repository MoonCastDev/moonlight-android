package com.limelight.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The MoonCast TV design kit: tokens from the Big Picture mockup, and small builders for the views every screen uses.
 *
 * Everything is sized in "design pixels" on a 1920-wide canvas and scaled to the real screen, so a 720p stick and a
 * 4K TV show the same layout. OLED mode swaps to true black, drops large white fills and dims the static chrome.
 */
class Tv(val ctx: Context, val oled: Boolean) {
    class Palette(val night: Int, val deep: Int, val raise: Int, val line: Int, val moon: Int, val dust: Int, val soft: Int)

    companion object {
        const val CORONA = 0xFFF2B55C.toInt()   // focus, the one accent
        const val BEAM = 0xFF4FD6E3.toInt()     // live / connected only
        const val DANGER = 0xFFFF8F84.toInt()
        val STANDARD = Palette(0xFF090D18.toInt(), 0xFF111829.toInt(), 0xFF18213A.toInt(), 0xFF263150.toInt(),
                0xFFEEF1F7.toInt(), 0xFF97A1B8.toInt(), 0xFFCBD2E1.toInt())
        val OLED = Palette(0xFF000000.toInt(), 0xFF0A0C11.toInt(), 0xFF141822.toInt(), 0xFF1D2230.toInt(),
                0xFFE2E5EC.toInt(), 0xFF858EA3.toInt(), 0xFFB9C0CE.toInt())

        // controller face-button colours (A green, B red, X blue, Y yellow)
        val FACE = mapOf("A" to 0xFF7FD97A.toInt(), "B" to 0xFFF27979.toInt(), "X" to 0xFF79A9F2.toInt(), "Y" to 0xFFF2D479.toInt())

        private val fonts = HashMap<String, Typeface>()
    }

    val p = if (oled) OLED else STANDARD
    private val scale = ctx.resources.displayMetrics.widthPixels.coerceAtLeast(1) / 1920f

    /** design px (1920-wide canvas) -> real px */
    fun px(v: Number): Int = Math.round(v.toFloat() * scale)
    fun pxf(v: Number): Float = v.toFloat() * scale

    // ---- type ----
    private fun face(file: String, weight: Int): Typeface {
        val key = "$file:$weight"
        fonts[key]?.let { return it }
        val tf = try {
            if (Build.VERSION.SDK_INT >= 26)
                Typeface.Builder(ctx.assets, "fonts/$file").setFontVariationSettings("'wght' $weight").build()
            else Typeface.create(Typeface.SANS_SERIF, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        } catch (e: Exception) { Typeface.DEFAULT }
        fonts[key] = tf
        return tf
    }
    fun display(weight: Int = 700) = face("Unbounded.ttf", weight)
    fun ui(weight: Int = 600) = face("Figtree.ttf", weight)

    fun text(s: CharSequence, size: Number, color: Int = p.moon, tf: Typeface = ui(), lines: Int = 1): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_PX, pxf(size))
            setTextColor(color)
            typeface = tf
            includeFontPadding = false
            if (lines > 0) { maxLines = lines; ellipsize = android.text.TextUtils.TruncateAt.END }
            setLineSpacing(0f, 1.12f)
        }

    // ---- surfaces ----
    fun round(fill: Int, radius: Number, stroke: Int = 0, strokeW: Number = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill); cornerRadius = pxf(radius)
            if (stroke != 0) setStroke(px(strokeW).coerceAtLeast(1), stroke)
        }

    /** Background that shows the corona focus ring when the view has focus. */
    fun focusable(fill: Int, radius: Number, outline: Boolean = true, focusFill: Int = p.raise): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), round(focusFill, radius, CORONA, 3))
            addState(intArrayOf(android.R.attr.state_pressed), round(focusFill, radius, CORONA, 3))
            addState(intArrayOf(), round(fill, radius, if (outline) p.line else 0, 1))
        }

    /** A ring-only foreground, for covers whose content is an image. */
    fun focusRing(radius: Number): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), round(0, radius, CORONA, 4))
        addState(intArrayOf(), ColorDrawable(0))
    }

    /** Big Picture's small lift on focus: the item you're on grows a little. */
    fun lift(v: View, by: Float = 1.04f) {
        v.setOnFocusChangeListener { view, has ->
            view.animate().scaleX(if (has) by else 1f).scaleY(if (has) by else 1f).setDuration(120).start()
        }
    }

    fun focusableView(v: View) { v.isFocusable = true; v.isClickable = true }

    // ---- small pieces ----
    /** The round face-button glyph used in hints and buttons: A, B, X, Y, or a square menu key. */
    fun glyph(key: String, size: Number = 36): TextView = text(key, size.toFloat() * 0.42f, p.night, display(700)).apply {
        gravity = Gravity.CENTER
        val c = FACE[key]
        background = if (c != null) round(if (oled) dim(c) else c, size.toFloat() / 2)
                     else round(p.raise, 10, p.line, 1)
        if (c == null) setTextColor(p.moon)
        layoutParams = LinearLayout.LayoutParams(px(if (c == null) size.toFloat() * 1.22f else size), px(size))
    }

    private fun dim(c: Int): Int {
        val f = 0.82f
        return (c and 0xFF000000.toInt()) or
                ((((c shr 16) and 0xFF) * f).toInt() shl 16) or ((((c shr 8) and 0xFF) * f).toInt() shl 8) or ((c and 0xFF) * f).toInt()
    }

    /** A status pill: "live" (beam fill), "rest" (corona outline), or plain. */
    fun status(label: String, kind: String = ""): TextView = text(label, 18, p.moon, ui(700)).apply {
        gravity = Gravity.CENTER
        setPadding(px(14), 0, px(14), 0)
        when (kind) {
            "live" -> { background = round(BEAM, 17); setTextColor(p.night) }
            "rest" -> { background = round(0, 17, CORONA, 1.5f); setTextColor(CORONA) }
            else -> background = round(p.raise, 17, p.line, 1)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(34)).apply { rightMargin = px(8) }
    }

    /** A focusable action pill with a face-button glyph. Primary is filled white (outlined on OLED). */
    fun pill(label: String, key: String?, primary: Boolean, onClick: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(if (key != null) 20 else 32), 0, px(32), 0)
            val fill = if (primary && !oled) p.moon else p.raise
            val txt = if (primary && !oled) p.night else p.moon
            background = StateListDrawable().apply {
                val ringed = round(fill, 36, CORONA, 3)
                addState(intArrayOf(android.R.attr.state_focused), ringed)
                addState(intArrayOf(android.R.attr.state_pressed), ringed)
                addState(intArrayOf(), round(fill, 36, if (primary && oled) p.moon else p.line, if (primary && oled) 2 else 1))
            }
            if (key != null) addView(glyph(key), LinearLayout.LayoutParams(px(36), px(36)).apply { rightMargin = px(14) })
            addView(text(label, 26, txt, ui(800)))
            focusableView(this)
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(72)).apply { rightMargin = px(16) }
        }

    /** A hint for the bottom bar: glyph + what it does. */
    fun hint(key: String, label: String): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(glyph(key), LinearLayout.LayoutParams(px(if (FACE.containsKey(key)) 36 else 44), px(36)).apply { rightMargin = px(12) })
        addView(text(label, 21, p.dust, ui(600)))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = px(40) }
    }

    fun row(gravity: Int = Gravity.CENTER_VERTICAL) = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; this.gravity = gravity }
    fun col() = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    fun space(w: Number = 0, h: Number = 0) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(px(w), px(h)) }
    fun grow() = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    fun lp(w: Number, h: Number) = LinearLayout.LayoutParams(dim(w), dim(h))
    fun flp(w: Number, h: Number, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(dim(w), dim(h), gravity)
    private fun dim(v: Number) = when (v.toInt()) { -1 -> ViewGroup.LayoutParams.MATCH_PARENT; -2 -> ViewGroup.LayoutParams.WRAP_CONTENT; else -> px(v) }

    /** Two-tone cover art for things with no box art (PS5, unknown apps), as in the mockup. */
    fun twoTone(seed: String): Drawable {
        val pairs = intArrayOf(0xFFE5484D.toInt(), 0xFFB0232B.toInt(), 0xFF2F5DF0.toInt(), 0xFF1B37A8.toInt(),
                0xFF12A594.toInt(), 0xFF0B6F65.toInt(), 0xFFF5A623.toInt(), 0xFFB86A12.toInt(),
                0xFF8E5CF0.toInt(), 0xFF5A31B5.toInt(), 0xFF1E8FD6.toInt(), 0xFF12609A.toInt())
        val i = if (seed == "ps") 2 else (Math.abs(seed.hashCode()) % (pairs.size / 2)) * 2   // PlayStation is always blue
        return TwoTone(pairs[i], pairs[i + 1], pxf(16), if (oled) 0.9f else 1f)
    }

    private class TwoTone(val a: Int, val b: Int, val r: Float, val bright: Float) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(c: Canvas) {
            val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
            val clip = Path().apply { addRoundRect(0f, 0f, w, h, r, r, Path.Direction.CW) }
            c.save(); c.clipPath(clip)
            paint.color = a; c.drawRect(0f, 0f, w, h, paint)
            paint.color = b
            c.drawPath(Path().apply { moveTo(w, h * 0.16f); lineTo(w, h); lineTo(w * 0.1f, h); close() }, paint)
            paint.color = 0x29FFFFFF
            c.drawCircle(w * 0.9f, h * 0.48f, w * 0.5f, paint)
            if (bright < 1f) { paint.color = (((1f - bright) * 255).toInt() shl 24); c.drawRect(0f, 0f, w, h, paint) }
            c.restore()
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }
}

/** The MoonCast mark: a crescent. */
class Crescent(ctx: Context, private val color: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.saveLayer(0f, 0f, w, h, null)
        paint.color = color; paint.xfermode = null
        c.drawCircle(w / 2, h / 2, w / 2, paint)
        paint.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        c.drawCircle(w * 0.66f, h * 0.34f, w * 0.41f, paint)
        c.restore()
    }
}
