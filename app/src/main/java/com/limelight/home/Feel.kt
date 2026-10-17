package com.limelight.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.provider.Settings
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import com.limelight.R

/**
 * The feel engine: menu sounds, a focus highlight that glides between items, and a small shake at the end of a row.
 * Shared by both apps; the craft rules come from ps5-homebrew-ui's CRAFT.md and SOUND.md.
 *
 * - Sounds are cues ("focus", "select"), not files. Each cue rotates through its recordings, detunes by up to 3 % so a
 *   held direction never sounds mechanical, and pans a little towards where the focus is on screen.
 * - Focus ticks are the quietest and most frequent sound; refusals are soft and a held direction stays quiet.
 * - One sound per action: [FeelSounds.serial] lets a caller tell whether something already answered a press.
 */
object FeelSounds {
    enum class Cue(val files: IntArray, val gain: Float) {
        FOCUS(intArrayOf(R.raw.mc_focus_01, R.raw.mc_focus_02), 0.55f),
        SELECT(intArrayOf(R.raw.mc_select_01), 0.8f),
        BACK(intArrayOf(R.raw.mc_back_01), 0.8f),
        OPEN(intArrayOf(R.raw.mc_modal_open_01), 0.8f),
        CLOSE(intArrayOf(R.raw.mc_modal_close_01), 0.8f),
        ERROR(intArrayOf(R.raw.mc_error_01), 0.35f),
        LAUNCH(intArrayOf(R.raw.mc_launch_01), 0.8f),
        TOGGLE(intArrayOf(R.raw.mc_toggle_01, R.raw.mc_toggle_02, R.raw.mc_toggle_03), 0.8f),
        TAB(intArrayOf(R.raw.mc_tab_01), 0.7f),
    }

    const val PREF = "menu_sounds"
    private const val PREFS_FILE = "mc_tv"
    private const val MAX_PAN = 0.55f

    @Volatile private var pool: SoundPool? = null
    @Volatile private var loading = false
    private val ids = HashMap<Cue, IntArray>()            // sound ids per cue, 0 until loaded
    private val ready = HashSet<Int>()                     // sound ids that finished loading
    private val rot = HashMap<Cue, Int>()
    private var app: Context? = null

    /** Bumps every time a cue is asked for (played or not), so callers can avoid stacking two sounds on one press. */
    @Volatile var serial = 0
        private set

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE).getBoolean(PREF, true)

    /** Start loading the sounds in the background. Safe to call often; play() calls it too. */
    @JvmStatic fun warm(ctx: Context) {
        if (pool != null || loading) return
        loading = true
        val c = ctx.applicationContext
        app = c
        Thread({
            try {
                val attrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                val p = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attrs).build()
                p.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(ready) { ready.add(id) } }
                for (cue in Cue.values()) {
                    ids[cue] = cue.files.map { res ->
                        try { p.load(c, res, 1) } catch (e: Exception) { 0 }   // a missing file just leaves the cue quiet
                    }.toIntArray()
                }
                pool = p
            } catch (e: Throwable) {
                // no sound on this device; the UI works the same without it
            } finally { loading = false }
        }, "mc-sounds").start()
    }

    /** Pan (-1 left .. 1 right) for a view, from where its centre sits on the screen. */
    @JvmStatic fun panFor(v: View?): Float {
        if (v == null || v.width == 0) return 0f
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val w = v.resources.displayMetrics.widthPixels.coerceAtLeast(1)
        return (((loc[0] + v.width / 2f) / w) - 0.5f) * 1.2f
    }

    @JvmStatic @JvmOverloads fun play(ctx: Context, cue: Cue, pan: Float = 0f, gain: Float = cue.gain) {
        serial++
        if (!enabled(ctx)) return
        val p = pool ?: run { warm(ctx); return }
        val list = ids[cue] ?: return
        val loaded = synchronized(ready) { list.filter { it != 0 && it in ready } }
        if (loaded.isEmpty()) return
        val i = ((rot[cue] ?: -1) + 1) % loaded.size
        rot[cue] = i
        val pn = pan.coerceIn(-MAX_PAN, MAX_PAN)
        val g = gain.coerceIn(0f, 1f)
        val left = g * (if (pn > 0) 1f - pn else 1f)
        val right = g * (if (pn < 0) 1f + pn else 1f)
        val rate = 1f + (Math.random().toFloat() * 2f - 1f) * 0.03f
        try { p.play(loaded[i], left, right, 1, 0, rate) } catch (e: Exception) { }
    }

    /** The focus tick for a view, panned by where it is. */
    @JvmStatic fun focus(v: View) = play(v.context, Cue.FOCUS, panFor(v))
}

/**
 * One highlight that glides from item to item instead of each item lighting up on its own. It lives as a full-size
 * overlay in the activity's root FrameLayout and draws a rounded ring at the focused view's bounds.
 *
 * In [ghost] mode it complements the items' own focus rings: it travels from the old item to the new one and fades as
 * it lands, so the move reads as one motion while the item keeps its usual look. Without ghost mode it stays put and is
 * the focus ring.
 */
class FocusGlide(private val host: FrameLayout) : View(host.context) {
    var strokeColor = Tv.CORONA
        set(v) { field = v; paint.color = v; invalidate() }
    var strokeWidth = 4f
        set(v) { field = v; paint.strokeWidth = v; invalidate() }
    /** Corner radius in px; it never exceeds half the ring's height, so pills stay pills. */
    var cornerRadius = 16f
    /** Per-view radius (px), for screens whose items have different shapes. Return null to use [cornerRadius]. */
    var radiusFor: ((View) -> Float?)? = null
    /** Grow the ring a little past the item, in px (covers grow on focus). */
    var outset = 0f
    var ghost = false
    var duration = 190L
    /**
     * Handheld (Switch) style: a band of [gapWidth] px in [gapColor] inside the ring, so the ring reads as a double
     * ring standing off the item. 0 (the TV) draws the single ring only.
     */
    var gapWidth = 0f
    var gapColor = 0
    /** The ring breathes slowly between its colour and halfway to [gapColor] (handheld style). Off on the TV. */
    var breathe = false
        set(v) { field = v; if (v) startBreath() else { breath?.cancel(); breath = null; paint.color = strokeColor; invalidate() } }
    /** Return false for views the glide should leave alone (text fields, etc.). */
    var accept: (View) -> Boolean = { true }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = strokeColor; strokeWidth = this@FocusGlide.strokeWidth }
    private val interp: Interpolator = if (Build.VERSION.SDK_INT >= 21) PathInterpolator(0.2f, 0.9f, 0.3f, 1.12f) else OvershootInterpolator(0.6f)
    private val cur = RectF()
    private val from = RectF()
    private var radius = 16f
    private var target: View? = null
    private var anim: ValueAnimator? = null
    private var shown = false
    private var breath: ValueAnimator? = null
    private var popScale = 1f
    private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, new -> moveTo(new) }
    private val follow = ViewTreeObserver.OnScrollChangedListener { track() }
    private val layoutFollow = ViewTreeObserver.OnGlobalLayoutListener {
        val p = pending
        if (p != null) { pending = null; if (p.isFocused) moveTo(p) } else track()
    }
    private var pending: View? = null

    init {
        isFocusable = false; isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        alpha = 0f
    }

    /** Add the overlay on top of everything in [host] and start following focus. */
    fun attach(): FocusGlide {
        if (parent == null) host.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val vto = host.viewTreeObserver
        vto.addOnGlobalFocusChangeListener(focusListener)
        vto.addOnScrollChangedListener(follow)
        vto.addOnGlobalLayoutListener(layoutFollow)
        return this
    }

    fun detach() {
        val vto = host.viewTreeObserver
        if (vto.isAlive) {
            vto.removeOnGlobalFocusChangeListener(focusListener)
            vto.removeOnScrollChangedListener(follow)
            vto.removeOnGlobalLayoutListener(layoutFollow)
        }
        anim?.cancel()
        breath?.cancel(); breath = null
        (parent as? ViewGroup)?.removeView(this)
    }

    private fun startBreath() {
        breath?.cancel(); breath = null
        if (reduced()) return
        breath = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a ->
                paint.color = mix(strokeColor, gapColor, (a.animatedValue as Float) * 0.5f)
                if (shown) invalidate()
            }
            start()
        }
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, s: Int) = (x shr s) and 0xFF
        fun lerp(s: Int) = (ch(a, s) + (ch(b, s) - ch(a, s)) * t).toInt() and 0xFF
        return (0xFF shl 24) or (lerp(16) shl 16) or (lerp(8) shl 8) or lerp(0)
    }

    /** The select answer: the ring swells a little and settles (360 ms). */
    fun pop() {
        if (!shown || reduced()) return
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                popScale = 1f + 0.05f * (if (t < 0.3f) t / 0.3f else (1f - t) / 0.7f)
                invalidate()
            }
            start()
        }
    }

    /** Keep the overlay the last child, after something else was added to the host. */
    fun bringUp() { if (parent === host && host.indexOfChild(this) != host.childCount - 1) bringToFront() }

    private fun reduced(): Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (e: Exception) { false }

    /** The view's bounds in the overlay's coordinates, or false when it's not on screen. */
    private fun boundsOf(v: View, out: RectF): Boolean {
        if (!v.isAttachedToWindow || !v.isShown || v.width == 0) return false
        val a = IntArray(2); val b = IntArray(2)
        v.getLocationInWindow(a); getLocationInWindow(b)
        // getLocationInWindow follows transforms, so a lifted (scaled) cover reports its grown top-left
        val w = v.width * v.scaleX; val h = v.height * v.scaleY
        out.set(a[0] - b[0] - outset, a[1] - b[1] - outset, a[0] - b[0] + w + outset, a[1] - b[1] + h + outset)
        return true
    }

    fun moveTo(v: View?) {
        if (v == null || v === this || v.isInTouchMode || !accept(v) || !isDescendant(v)) { hide(); return }
        val next = RectF()
        if (!boundsOf(v, next)) {
            hide()
            // focused before its first layout (a dialog that just opened, a page rebuilt on resume): land on it then
            if (v.isAttachedToWindow && v.isFocused) pending = v
            return
        }
        pending = null
        target = v
        radius = (radiusFor?.invoke(v) ?: cornerRadius)
        anim?.cancel()
        if (!shown || reduced() || duration <= 0) {
            cur.set(next); shown = true
            animate().cancel()
            alpha = if (ghost) 0f else 1f
            invalidate()
            return
        }
        from.set(cur)
        animate().cancel()
        alpha = 1f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = this@FocusGlide.duration
            interpolator = interp
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                val to = RectF()
                // re-read the target every frame, so a row that scrolls while we glide is followed, not missed
                if (target?.let { boundsOf(it, to) } != true) to.set(next)
                cur.set(from.left + (to.left - from.left) * t, from.top + (to.top - from.top) * t,
                        from.right + (to.right - from.right) * t, from.bottom + (to.bottom - from.bottom) * t)
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    if (anim !== a) return
                    anim = null
                    track()
                    if (ghost) animate().alpha(0f).setDuration(140).start()
                }
            })
            start()
        }
    }

    private fun isDescendant(v: View): Boolean {
        var p = v.parent
        while (p != null) { if (p === host) return true; p = p.parent }
        return false
    }

    /** Snap to the focused view after a scroll or layout (not while gliding, the animator does it then). */
    private fun track() {
        if (anim != null || !shown) return
        val t = target ?: return
        if (!boundsOf(t, cur)) { hide(); return }
        invalidate()
    }

    fun hide() {
        anim?.cancel(); anim = null
        target = null; shown = false
        animate().cancel(); alpha = 0f
    }

    override fun onDraw(c: Canvas) {
        if (!shown) return
        val r = radius.coerceAtMost(cur.height() / 2f)
        val half = strokeWidth / 2f
        if (popScale != 1f) { c.save(); c.scale(popScale, popScale, cur.centerX(), cur.centerY()) }
        c.drawRoundRect(cur.left + half, cur.top + half, cur.right - half, cur.bottom - half, r, r, paint)
        if (gapWidth > 0f) {
            gapPaint.color = gapColor; gapPaint.strokeWidth = gapWidth
            val g = strokeWidth + gapWidth / 2f
            val gr = (r - strokeWidth).coerceAtLeast(0f)
            c.drawRoundRect(cur.left + g, cur.top + g, cur.right - g, cur.bottom - g, gr, gr, gapPaint)
        }
        if (popScale != 1f) c.restore()
    }
}

/** The end-of-row answer: a small shake, a soft refusal sound and, from a pad, a light rumble. */
object Feel {
    /** [by] is the shake distance in px (about 14 design px). Quiet and still for a held direction. */
    @JvmStatic fun edgeNudge(view: View?, direction: Int, e: KeyEvent?, by: Float) {
        if (view == null || (e != null && e.repeatCount > 0)) return
        FeelSounds.play(view.context, FeelSounds.Cue.ERROR)
        val horizontal = direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT
        val sign = if (direction == View.FOCUS_LEFT || direction == View.FOCUS_UP) -1f else 1f
        val reduced = try {
            Settings.Global.getFloat(view.context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (x: Exception) { false }
        if (!reduced) {
            // out, back past the start, settle: 260 ms in all
            val k = floatArrayOf(0f, by * sign, -by * 0.45f * sign, by * 0.2f * sign, 0f)
            ValueAnimator.ofFloat(*k).apply {
                duration = 260
                addUpdateListener { a -> val v = a.animatedValue as Float; if (horizontal) view.translationX = v else view.translationY = v }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) { view.translationX = 0f; view.translationY = 0f }
                    override fun onAnimationCancel(a: Animator) { view.translationX = 0f; view.translationY = 0f }
                })
                start()
            }
        }
        if (e != null) rumble(e)
    }

    /** A short, light buzz on the pad that pressed, if it has a motor. */
    @JvmStatic fun rumble(e: KeyEvent) {
        val pad = (e.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                  (e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (!pad) return
        try {
            @Suppress("DEPRECATION")
            val vib = e.device?.vibrator ?: return
            if (!vib.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) vib.vibrate(VibrationEffect.createOneShot(28, 60))
            else @Suppress("DEPRECATION") vib.vibrate(28)
        } catch (x: Exception) { }
    }

    /** Map a d-pad key to a focus direction, or 0. */
    @JvmStatic fun direction(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT -> View.FOCUS_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> View.FOCUS_RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
        else -> 0
    }
}
