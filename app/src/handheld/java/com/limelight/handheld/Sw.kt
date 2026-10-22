package com.limelight.handheld

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.limelight.home.Tv

/**
 * The handheld's Switch-style kit, from mooncast-handheld-v2.html: flat charcoal (or light grey), white type, one
 * cyan double ring. Sizes are design px on a 1920 x 1080 canvas (1080 x 1920 upright), fitted to the screen by [tv].
 */
class Sw(ctx: Context, val light: Boolean, val portrait: Boolean, val size: Size = Size.NORMAL) {
    /**
     * Screen size. NORMAL lays out on a 1920 x 1080 canvas (Odin, phones); COMPACT on 1240 x 1080 for small or square
     * screens (Retroid Pocket Mini: the 1920 canvas shrank everything to 65%); LARGE is COMPACT a step bigger.
     */
    enum class Size { NORMAL, COMPACT, LARGE }

    /**
     * Compact sizes (edges, tiles, side menu). Upright is always compact: portrait shows the same screens as landscape,
     * only fitted to a narrower, taller screen.
     */
    val compact get() = size != Size.NORMAL || portrait
    /** Space between the screen edge and the content, in design px. */
    val edge get() = if (portrait) 40 else if (compact) 44 else 96

    // Upright: the width sets the scale (1080 wide, or 940 for Large), so text stays the size it is in landscape
    // compact and the height is whatever the screen has (see [designHeight]). A wide upright screen (a foldable open,
    // a tablet: 600 dp and up) gets a wider canvas, so things keep a phone's real size and more of them fit.
    private val upright = portraitCanvas(ctx)
    private val wide = ctx.resources.configuration.smallestScreenWidthDp >= 600
    val tv = when (size) {
        // on its side a big screen likewise keeps a phone's real size (the 1920 x 1080 canvas blew it up 1.3 times)
        Size.NORMAL -> if (portrait) Tv(ctx, false, upright, 0)
                       else if (wide) ctx.resources.configuration.let { Tv(ctx, false, Math.round(it.screenWidthDp / 0.38f), Math.round(it.screenHeightDp / 0.38f)) }
                       else Tv(ctx, false, 1920, 1080)
        Size.COMPACT -> if (portrait) Tv(ctx, false, upright, 0) else Tv(ctx, false, 1240, 1080)
        Size.LARGE -> if (portrait) Tv(ctx, false, upright * 940 / 1080, 0) else Tv(ctx, false, 1080, 940)
    }
    /**
     * A phone with no game controller: tabs, chips, Back and the settings groups get at least Android's 48 dp touch
     * size (on a 1080p phone the canvas made them 23-35 dp). Handhelds with built-in controls (the Odin) keep their look.
     */
    var touchFirst = false
    private val dp48 = (48 * ctx.resources.displayMetrics.density).toInt()
    /** A height in design px, at least 48 dp on a touch-first phone. */
    fun tap(h: Number): Int = if (touchFirst) maxOf(px(h), dp48) else px(h)
    /** Android's Font size on the usual layout (compact layouts already step up with it), up to 1.3x. */
    private val fontScale = if (size == Size.NORMAL) ctx.resources.configuration.fontScale.coerceIn(1f, 1.3f) else 1f

    /** The status bar, navigation and cutout, in px: the menus are laid out inside them (set by the activity). */
    var bars = Rect()
    /** The design canvas height this screen really has for the app, inside the system bars. */
    val designHeight: Float get() = (c.resources.displayMetrics.heightPixels - bars.top - bars.bottom) / tv.pxf(1)
    /** Extra space between a row's title and its description on the compact layout. */
    val lineGap get() = if (compact) 12 else 8
    val c = ctx

    val bg = if (light) 0xFFEBEBEB.toInt() else 0xFF2B2D31.toInt()
    val panel = if (light) 0xFFFFFFFF.toInt() else 0xFF36393F.toInt()
    val tile = if (light) 0xFFDADCE0.toInt() else 0xFF3E4249.toInt()
    val line = if (light) 0xFFCDCFD4.toInt() else 0xFF474B53.toInt()
    val ink = if (light) 0xFF2D2D2D.toInt() else 0xFFFFFFFF.toInt()
    val sub = if (light) 0xFF6E727A.toInt() else 0xFFB5BAC3.toInt()
    val ring = if (light) 0xFF00A9C2.toInt() else 0xFF2FD3E6.toInt()
    /** Button labels: between [ink] and [sub], so they read at arm's length without shouting. */
    val label = if (light) 0xFF3A3D42.toInt() else 0xFFD9DCE1.toInt()

    /** Corner radius of game tiles, in design px. */
    val tileCorner = 16

    companion object {
        /** The upright design width: 1080, or wider on a screen 600 dp and more across (same real size as a phone). */
        fun portraitCanvas(ctx: Context): Int {
            val wdp = ctx.resources.configuration.screenWidthDp
            return if (wdp >= 600) maxOf(1080, Math.round(wdp / 0.38f)) else 1080
        }

        /**
         * The size for this screen. [choice] is the "Screen size" setting: auto, compact, normal or large. Auto goes
         * compact when the normal 1920-wide layout would be drawn below 80% of its size (a small or near-square
         * screen), and a step larger when Android's own Font size is set large. Pixels and Android's own scaling are
         * used, not the panel's reported dpi, which some handhelds get wrong.
         */
        fun pick(ctx: Context, portrait: Boolean, choice: String?): Size {
            when (choice) {
                "normal" -> return Size.NORMAL
                "compact" -> return Size.COMPACT
                "large" -> return Size.LARGE
            }
            val m = ctx.resources.displayMetrics
            val w = m.widthPixels.coerceAtLeast(1).toFloat(); val h = m.heightPixels.coerceAtLeast(1).toFloat()
            val fit = if (portrait) minOf(w / 1080f, h / 1920f) else minOf(w / 1920f, h / 1080f)
            if (fit >= 0.8f) return Size.NORMAL
            return if (ctx.resources.configuration.fontScale >= 1.15f) Size.LARGE else Size.COMPACT
        }

        const val PS = 0xFF2F5DF0.toInt()
        const val XB = 0xFF107C10.toInt()
        const val PC = 0xFF5B6170.toInt()
        const val LIVE = 0xFF2DBE5A.toInt()
        const val DANGER = 0xFFE9605A.toInt()
        // the round buttons' icon colours (c1..c6 in the mockup)
        const val C_CYAN = 0xFF2FD3E6.toInt()
        const val C_AMBER = 0xFFF2B55C.toInt()
        const val C_BLUE = 0xFF5C8DF6.toInt()
        const val C_GREEN = 0xFF2DBE5A.toInt()
        const val C_RED = 0xFFE9605A.toInt()

        /** Cover colours when there's no box art: shared with the TV (Covers), so a game looks the same on both. */
        val ART = com.limelight.home.Covers.ART

        /** PlayStation is always blue, Xbox always green, anything else picks by name. */
        fun artIndex(seed: String): Int = com.limelight.home.Covers.index(seed)
    }

    fun px(v: Number) = tv.px(v)
    fun pxf(v: Number) = tv.pxf(v)
    /** The design canvas width this screen really has (wider than 1920 on a 20:9 phone). */
    val designWidth: Float get() = (c.resources.displayMetrics.widthPixels - bars.left - bars.right) / tv.pxf(1)

    fun ui(w: Int = 700) = tv.ui(w)
    fun text(s: CharSequence, size: Number, color: Int = ink, weight: Int = 700, lines: Int = 1): TextView =
        tv.text(s, size.toFloat() * fontScale, color, tv.ui(weight), lines)

    fun round(fill: Int, radius: Number, stroke: Int = 0, strokeW: Number = 2) = tv.round(fill, radius, stroke, strokeW)
    fun row(g: Int = Gravity.CENTER_VERTICAL) = tv.row(g)
    fun col() = tv.col()
    fun grow() = tv.grow()
    fun lp(w: Number, h: Number) = tv.lp(w, h)
    fun flp(w: Number, h: Number, g: Int = Gravity.NO_GRAVITY) = tv.flp(w, h, g)

    fun focusable(v: View) {
        tv.focusableView(v)   // with menu sounds on, MoonCast's sound replaces the system click
        if (android.os.Build.VERSION.SDK_INT >= 26) v.defaultFocusHighlightEnabled = false   // the cyan ring is the highlight
    }

    /** A touch presses the view in a little (tiles, round buttons, pills), and lets go when the finger lifts. */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun pressable(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start()
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            false   // the click itself still happens as usual
        }
    }

    /** A 2 px divider along the bottom edge of a settings row. */
    fun bottomLine(): Drawable = object : Drawable() {
        private val p = Paint().apply { color = line }
        override fun draw(cv: Canvas) { val h = px(2).coerceAtLeast(1); cv.drawRect(bounds.left.toFloat(), (bounds.bottom - h).toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), p) }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(f: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    fun topLine(): Drawable = object : Drawable() {
        private val p = Paint().apply { color = line }
        override fun draw(cv: Canvas) { cv.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.top + px(2).coerceAtLeast(1).toFloat(), p) }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(f: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** The round face-button key in the hint bar: ink disc, background-coloured letter. */
    fun key(k: String, size: Number = 46): TextView = text(k, size.toFloat() * 0.52f, bg, 800).apply {
        gravity = Gravity.CENTER
        background = round(ink, size.toFloat() / 2)
    }

    fun hint(k: String, label: String): LinearLayout = row().apply {
        addView(key(k), lp(46, 46).apply { rightMargin = px(14) })
        addView(text(label, 28, ink, 700))
    }

    /** On/off switch: 96 x 52, cyan when on. */
    fun toggle(on: Boolean): View = FrameLayout(c).apply {
        background = round(if (on) ring else line, 26)
        addView(View(c).apply { background = round(0xFFFFFFFF.toInt(), 20) },
                flp(40, 40, Gravity.CENTER_VERTICAL or (if (on) Gravity.END else Gravity.START)).apply { leftMargin = px(6); rightMargin = px(6) })
    }

    /** A small source badge on a tile ("PC", "PS5", "Xbox"). */
    fun badge(label: String, size: Number = 20): TextView = text(label, size, 0xFFFFFFFF.toInt(), 800).apply {
        gravity = Gravity.CENTER
        setPadding(px(14), 0, px(14), 0)
        background = round(0x73000000, size.toFloat())
    }

    fun live(size: Number = 20): LinearLayout = row().apply {
        setPadding(px(14), 0, px(14), 0)
        background = round(0xFFFFFFFF.toInt(), size.toFloat())
        addView(View(c).apply { background = round(LIVE, 6) }, lp(12, 12).apply { rightMargin = px(8) })
        addView(text("Running", size, 0xFF111111.toInt(), 800))
    }

    /** Cover art for things without box art: the mockup's radial gradients. */
    fun art(seed: String, radius: Number = tileCorner): Drawable = Art(ART[artIndex(seed)], pxf(radius))

    /** A cover in one colour (an app's own, from its icon): lighter top, the colour, darker bottom. */
    fun artFrom(color: Int, radius: Number = tileCorner): Drawable {
        fun shade(f: Float): Int {
            val hsv = FloatArray(3); android.graphics.Color.colorToHSV(color, hsv)
            hsv[2] = (hsv[2] * f).coerceIn(0f, 1f); if (f > 1f) hsv[1] = (hsv[1] * 0.85f)
            return android.graphics.Color.HSVToColor(hsv)
        }
        return Art(intArrayOf(shade(1.25f), color, shade(0.45f)), pxf(radius))
    }

    /** The tint colour a cover sets for the background wash. */
    fun tint(seed: String): Int = ART[artIndex(seed)][1]

    private class Art(val cs: IntArray, val r: Float) : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(cv: Canvas) {
            val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
            if (w <= 0 || h <= 0) return
            p.shader = RadialGradient(w * 0.55f, h * 0.28f, maxOf(w, h) * 1.05f, cs, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            cv.drawRoundRect(RectF(0f, 0f, w, h), r, r, p)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(f: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }

    /** The "All games" tile: a 3 x 3 grid of small squares. */
    fun allArt(): Drawable = object : Drawable() {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun draw(cv: Canvas) {
            val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
            p.color = tile; cv.drawRoundRect(RectF(0f, 0f, w, h), pxf(tileCorner), pxf(tileCorner), p)
            p.color = sub
            val s = w * 36f / 380f; val g = w * 12f / 380f
            val x0 = (w - 3 * s - 2 * g) / 2; val y0 = (h - 3 * s - 2 * g) / 2
            for (i in 0 until 3) for (j in 0 until 3)
                cv.drawRoundRect(RectF(x0 + j * (s + g), y0 + i * (s + g), x0 + j * (s + g) + s, y0 + i * (s + g) + s), s / 6, s / 6, p)
        }
        override fun setAlpha(a: Int) {}
        override fun setColorFilter(f: ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}

/** The round buttons' line icons, drawn from the mockup's 48 x 48 SVGs. */
class Icon(ctx: Context, private val kind: String, private val color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(cv: Canvas) {
        val k = width / 48f
        cv.save(); cv.scale(k, k)
        p.color = color; p.strokeWidth = 4f
        when (kind) {
            "consoles" -> { rr(cv, 6f, 10f, 36f, 22f, 3f); line(cv, 18f, 38f, 30f, 38f); line(cv, 24f, 32f, 24f, 38f) }
            "tv" -> {
                rr(cv, 4f, 9f, 40f, 26f, 3f); line(cv, 16f, 41f, 32f, 41f)
                cv.drawPath(Path().apply { moveTo(20f, 18f); lineTo(28f, 22f); lineTo(20f, 26f); close() }, p)
            }
            "pad" -> {
                cv.drawPath(Path().apply {
                    moveTo(10f, 16f); lineTo(38f, 16f); quadTo(44f, 16f, 44f, 22f); lineTo(42f, 34f)
                    quadTo(41f, 39f, 35f, 36f); lineTo(31f, 31f); lineTo(17f, 31f); lineTo(13f, 36f)
                    quadTo(7f, 39f, 6f, 34f); lineTo(4f, 22f); quadTo(4f, 16f, 10f, 16f); close()
                }, p)
                line(cv, 14f, 21f, 14f, 27f); line(cv, 11f, 24f, 17f, 24f)
            }
            "net" -> { arc(cv, 8f, 34f, 40f, 34f, 22f); arc(cv, 14f, 28f, 34f, 28f, 14f); arc(cv, 20f, 22f, 28f, 22f, 6f) }
            "gear" -> {
                cv.drawCircle(24f, 24f, 6f, p)
                line(cv, 24f, 6f, 24f, 12f); line(cv, 24f, 36f, 24f, 42f); line(cv, 6f, 24f, 12f, 24f); line(cv, 36f, 24f, 42f, 24f)
                line(cv, 11f, 11f, 15f, 15f); line(cv, 33f, 33f, 37f, 37f); line(cv, 11f, 37f, 15f, 33f); line(cv, 33f, 15f, 37f, 11f)
            }
            "power" -> {
                line(cv, 24f, 6f, 24f, 22f)
                // the open circle: radius 15 through (14,12) and (34,12), round the bottom
                val cy = 12f + Math.sqrt(15.0 * 15 - 10.0 * 10).toFloat()
                cv.drawArc(RectF(24f - 15, cy - 15, 24f + 15, cy + 15), -48.2f, 276.4f, false, p)
            }
            "home" -> cv.drawPath(Path().apply { moveTo(8f, 22f); lineTo(24f, 9f); lineTo(40f, 22f); lineTo(40f, 40f); lineTo(8f, 40f); close() }, p)
            "grid" -> { rr(cv, 8f, 8f, 13f, 13f, 2f); rr(cv, 27f, 8f, 13f, 13f, 2f); rr(cv, 8f, 27f, 13f, 13f, 2f); rr(cv, 27f, 27f, 13f, 13f, 2f) }
            "back" -> { line(cv, 29f, 10f, 15f, 24f); line(cv, 15f, 24f, 29f, 38f) }
        }
        cv.restore()
    }

    private fun rr(cv: Canvas, x: Float, y: Float, w: Float, h: Float, r: Float) = cv.drawRoundRect(RectF(x, y, x + w, y + h), r, r, p)
    private fun line(cv: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) = cv.drawLine(x1, y1, x2, y2, p)

    /** An arc over the top from (x1,y1) to (x2,y2) with radius r (the wifi bars). */
    private fun arc(cv: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, r: Float) {
        val half = (x2 - x1) / 2f
        val cx = x1 + half
        val cy = y1 + Math.sqrt((r * r - half * half).toDouble()).toFloat()
        val a1 = Math.toDegrees(Math.atan2((y1 - cy).toDouble(), (x1 - cx).toDouble())).toFloat()
        val a2 = Math.toDegrees(Math.atan2((y2 - cy).toDouble(), (x2 - cx).toDouble())).toFloat()
        cv.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), a1, a2 - a1, false, p)
    }
}

/** The controller glyph in the hint bar and the battery in the top bar, drawn in the text colour. */
class Glyph(ctx: Context, private val kind: String, private val color: Int, var level: Int = 100) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = h / 38f
        p.color = color
        when (kind) {
            "pad" -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 4 * s
                cv.drawRoundRect(RectF(2 * s, 2 * s, w - 2 * s, h - 2 * s), h / 2, h / 2, p)
                p.style = Paint.Style.FILL
                cv.drawCircle(w * 0.28f, h / 2, 6 * s, p); cv.drawCircle(w * 0.72f, h / 2, 6 * s, p)
            }
            "battery" -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = 3 * s
                val body = RectF(1.5f * s, 1.5f * s, w - 6 * s, h - 1.5f * s)
                cv.drawRoundRect(body, 5 * s, 5 * s, p)
                p.style = Paint.Style.FILL
                cv.drawRoundRect(RectF(w - 5 * s, h * 0.32f, w - 1 * s, h * 0.68f), 1.5f * s, 1.5f * s, p)
                val inner = RectF(body.left + 4 * s, body.top + 4 * s, body.right - 4 * s, body.bottom - 4 * s)
                inner.right = inner.left + inner.width() * (level.coerceIn(0, 100) / 100f)
                cv.drawRoundRect(inner, 2 * s, 2 * s, p)
            }
        }
    }
}

/** A horizontal row that keeps a margin round the focused item when it scrolls, so its ring is never cut. */
class RowScroll(ctx: Context, private val margin: Int) : HorizontalScrollView(ctx) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int =
        super.computeScrollDeltaToGetChildRectOnScreen(Rect(rect.left - margin, rect.top, rect.right + margin, rect.bottom))
}

class ColScroll(ctx: Context, private val margin: Int) : ScrollView(ctx) {
    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int =
        super.computeScrollDeltaToGetChildRectOnScreen(Rect(rect.left, rect.top - margin, rect.right, rect.bottom + margin))
}

/**
 * Holds one child a little above the middle: the space it doesn't need goes [bias] above it and the rest below, so
 * a tall screen doesn't open one big gap over the content (centred looked like it had slid down).
 */
class BiasFrame(ctx: Context, private val bias: Float) : FrameLayout(ctx) {
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val c = getChildAt(0) ?: return
        val free = (b - t - paddingTop - paddingBottom - c.measuredHeight).coerceAtLeast(0)
        val top = paddingTop + (free * bias).toInt()
        c.layout(paddingLeft, top, paddingLeft + c.measuredWidth, top + c.measuredHeight)
    }
}

/** A radial wash of the selected game's colour behind everything, as in the feel mockup. */
class Wash(ctx: Context) : View(ctx) {
    var color = 0
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        if (color == 0 || width == 0) return
        val a = (0.26f * 255).toInt()
        val c0 = (a shl 24) or (color and 0xFFFFFF)
        p.shader = RadialGradient(width * 0.3f, height * 0.4f, maxOf(width, height) * 0.6f, intArrayOf(c0, color and 0xFFFFFF), null, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
    }
}

