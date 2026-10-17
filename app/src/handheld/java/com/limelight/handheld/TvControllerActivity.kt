package com.limelight.handheld

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.limelight.home.FeelSounds
import com.limelight.home.FocusGlide
import com.limelight.home.MoveToTv
import com.limelight.home.QuickMenu

/**
 * Controller mode: a game plays on a MoonCast TV and this handheld is its controller. It sends the game (POST /play),
 * then serves this device's buttons and sticks to the TV over [PadServer] until you leave.
 *
 * Every pad press goes to the TV game, including B. The buttons on screen (Play here instead, Stop) take a touch, or
 * the pad after Select + Start + LB + RB (or the device's Back key); B or Back again returns the pad to the game.
 */
class TvControllerActivity : Activity() {
    companion object {
        const val EXTRA_TV = "tv_id"
        const val EXTRA_DELAY = "send_delay_ms"
        private val COMBO = intArrayOf(4, 5, 8, 9)   // LB RB Select Start
    }

    private enum class State { SENDING, PLAYING, FAILED }

    private lateinit var sw: Sw
    private lateinit var root: FrameLayout
    private lateinit var glide: FocusGlide
    private lateinit var where: TextView
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var buttons: LinearLayout
    private val ui = Handler(Looper.getMainLooper())

    private var target: MoveToTv.Target? = null
    private var tv: TvFinder.Tv? = null
    private var server: PadServer? = null
    private var padKey = ""
    private var state = State.SENDING
    private var error: String? = null
    private var menu = false          // the pad drives the on-screen buttons instead of the game
    private var comboPending = false
    private var attempt = 0

    // the pad, W3C Standard Gamepad order
    private val b = FloatArray(PadServer.BUTTONS)
    private val a = FloatArray(PadServer.AXES)
    private var keyL2 = 0f; private var keyR2 = 0f; private var axL2 = 0f; private var axR2 = 0f
    private var lastHatX = 0f; private var lastHatY = 0f

    private val prefs by lazy { getSharedPreferences("mc_tv", Context.MODE_PRIVATE) }
    private val deadzone by lazy {
        android.preference.PreferenceManager.getDefaultSharedPreferences(this).getInt("seekbar_deadzone", 7).coerceIn(0, 30) / 100f
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        MoveToTv.handler = TvSender
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        build()
        begin(intent)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        begin(i)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        server?.stop(); server = null
        glide.detach()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        @Suppress("DEPRECATION")
        if (hasFocus) window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    // ---------------------------------------------------------------- sending

    private fun begin(i: Intent) {
        val t = MoveToTv.Target.from(i)
        val tv0 = TvFinder.byId(this, i.getStringExtra(EXTRA_TV))
        if (t == null || tv0 == null) { finish(); return }
        target = t; tv = tv0
        server?.stop()
        padKey = TvSender.newPadKey()
        server = PadServer(padKey).also { s ->
            s.onClients = { runOnUiThread { refresh() } }
            s.start()   // up before /play: the TV connects to it as soon as it starts the game
        }
        b.fill(0f); a.fill(0f)
        title.text = t.title
        attempt++
        send(i.getLongExtra(EXTRA_DELAY, 0L), retried = false)
    }

    private fun send(delayMs: Long, retried: Boolean) {
        val t = target ?: return; val tv0 = tv ?: return
        state = State.SENDING; error = null
        setMenu(true)
        refresh()
        val mine = attempt
        ui.postDelayed({
            Thread({
                // the TV's address may have changed: look for it again before giving up
                if (!retried && TvFinder.ask(tv0.ip) == null) {
                    runOnUiThread {
                        if (mine != attempt) return@runOnUiThread
                        status.text = "Looking for ${tv0.name} on this network…"
                        TvFinder.sweep(this) {
                            if (mine != attempt) return@sweep
                            val again = TvFinder.byId(this, tv0.id)
                            if (again != null && TvFinder.isHere(again)) { tv = again; send(0, retried = true) }
                            else fail("${tv0.name} isn't on this network. Open MoonCast on the TV, on the same Wi-Fi as this device.")
                        }
                    }
                    return@Thread
                }
                val err = TvSender.send(this, tv0, t, padKey)
                runOnUiThread {
                    if (mine != attempt || isFinishing) return@runOnUiThread
                    if (err != null) fail(err)
                    else {
                        TvFinder.setLast(this, tv0)
                        state = State.PLAYING
                        FeelSounds.play(this, FeelSounds.Cue.LAUNCH)
                        setMenu(false)
                        refresh()
                    }
                }
            }, "tv-send").start()
        }, delayMs)
    }

    private fun fail(msg: String) {
        state = State.FAILED; error = msg
        FeelSounds.play(this, FeelSounds.Cue.ERROR)
        setMenu(true)
        refresh()
    }

    // ---------------------------------------------------------------- screen

    private fun build() {
        val dm = resources.displayMetrics
        val portrait = dm.heightPixels > dm.widthPixels
        sw = Sw(this, false, portrait)
        root = FrameLayout(this).apply { setBackgroundColor(0xFF050506.toInt()) }
        val col = sw.col().apply { gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
        where = sw.text("", 28, sw.sub, 700).apply { gravity = Gravity.CENTER }
        title = sw.text("", if (portrait) 56 else 64, sw.ink, 800, 2).apply { gravity = Gravity.CENTER }
        status = sw.text("", 28, sw.sub, 600, 4).apply { gravity = Gravity.CENTER }
        col.addView(where, sw.lp(-1, -2))
        col.addView(title, sw.lp(-1, -2).apply { topMargin = sw.px(16) })
        col.addView(status, sw.lp(-1, -2).apply { topMargin = sw.px(26) })
        buttons = sw.row().apply { gravity = Gravity.CENTER; clipChildren = false }
        col.addView(buttons, sw.lp(-1, 112).apply { topMargin = sw.px(64) })
        root.addView(col, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { leftMargin = sw.px(80); rightMargin = sw.px(80) })
        hint = sw.text("", 24, 0xFF8A8F98.toInt(), 600, 2).apply { gravity = Gravity.CENTER }
        root.addView(hint, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            bottomMargin = sw.px(if (portrait) 90 else 48); leftMargin = sw.px(60); rightMargin = sw.px(60) })
        setContentView(root)
        glide = FocusGlide(root).apply {
            ghost = false; strokeColor = sw.ring; strokeWidth = sw.pxf(7); gapColor = 0xFF050506.toInt(); gapWidth = sw.pxf(6)
            outset = sw.pxf(13); cornerRadius = 9999f; duration = 200; breathe = true
            radiusFor = { 9999f }
        }.attach()
        FeelSounds.warm(this)
    }

    private fun pill(label: String, main: Boolean, onClick: () -> Unit): View = sw.text(label, 30, if (main) 0xFF111111.toInt() else sw.ink, 800).apply {
        gravity = Gravity.CENTER
        setPadding(sw.px(48), 0, sw.px(48), 0)
        background = sw.round(if (main) sw.ink else 0xFF26282C.toInt(), 56)
        sw.focusable(this)
        setOnClickListener { FeelSounds.play(this@TvControllerActivity, FeelSounds.Cue.SELECT); onClick() }
    }

    private fun refresh() {
        val t = target ?: return; val tvName = tv?.name ?: "the TV"
        where.text = when (state) { State.SENDING -> "Sending to $tvName"; State.PLAYING -> "Playing on $tvName"; State.FAILED -> "Couldn't play on $tvName" }
        status.setTextColor(if (state == State.FAILED) Sw.DANGER else sw.sub)
        status.text = when (state) {
            State.SENDING -> "Starting ${t.title} on $tvName…"
            State.FAILED -> error ?: "Something went wrong."
            State.PLAYING -> if ((server?.clientCount ?: 0) > 0) "This ${deviceShort()} is the controller."
                             else "Waiting for $tvName to connect to this ${deviceShort()}'s controller…"
        }
        val focusedLabel = (currentFocus as? TextView)?.text?.toString()
        buttons.removeAllViews()
        val list = ArrayList<Pair<String, () -> Unit>>()
        if (state == State.FAILED) list += "Try again" to { attempt++; send(0, retried = false) }
        list += "Play here instead" to { playHere() }
        list += (if (state == State.FAILED) "Back" else "Stop") to { stopAndLeave() }
        list.forEachIndexed { i, (label, fn) ->
            buttons.addView(pill(label, i == 0, fn), sw.lp(-2, 112).apply { if (i > 0) leftMargin = sw.px(28) })
        }
        hint.text = when {
            state != State.PLAYING -> ""
            menu -> "B or Back: back to the game"
            else -> "Every button plays the game on the TV. ${QuickMenu.COMBO_LABEL}, or Back, for these buttons."
        }
        applyMenuLook(focusedLabel)
    }

    private fun applyMenuLook(focusLabel: String? = null) {
        buttons.alpha = if (menu || state != State.PLAYING) 1f else 0.45f
        if (menu) {
            val again = (0 until buttons.childCount).map { buttons.getChildAt(it) }.firstOrNull { (it as? TextView)?.text?.toString() == focusLabel }
            (again ?: buttons.getChildAt(0))?.let { v -> v.requestFocus(); ui.post { glide.moveTo(v) } }
        } else {
            currentFocus?.clearFocus()
            glide.hide()
        }
        dim(state == State.PLAYING && !menu && prefs.getBoolean("hh_dim_on_tv", true))
    }

    private fun setMenu(on: Boolean) {
        if (menu == on) return
        menu = on
        if (on) { b.fill(0f); a.fill(0f); keyL2 = 0f; keyR2 = 0f; axL2 = 0f; axR2 = 0f; push() }   // let go on the TV while the pad drives this screen
        buttons.descendantFocusability = if (on) LinearLayout.FOCUS_AFTER_DESCENDANTS else LinearLayout.FOCUS_BLOCK_DESCENDANTS
        if (state == State.PLAYING) refresh() else applyMenuLook()
    }

    /** Settings, Handheld, "Dim the screen on a TV": the screen goes almost dark while you play on the TV. */
    private fun dim(on: Boolean) {
        val lp = window.attributes
        val v = if (on) 0.02f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (lp.screenBrightness != v) { lp.screenBrightness = v; window.attributes = lp }
        root.alpha = if (on) 0.6f else 1f
    }

    private fun deviceShort(): String =
        if (android.os.Build.MANUFACTURER.equals("AYN", true) || android.os.Build.MODEL.contains("Odin", true)) "Odin" else "device"

    private fun playHere() {
        val t = target ?: return
        server?.stop(); server = null
        attempt++
        startActivity(Intent(this, HandheldHomeActivity::class.java).putExtra(HandheldHomeActivity.EXTRA_PLAY_HERE, t.key)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun stopAndLeave() {
        server?.stop(); server = null
        attempt++
        FeelSounds.play(this, FeelSounds.Cue.BACK)
        finish()
    }

    // ---------------------------------------------------------------- input -> the TV

    private fun push() { server?.update(b, a) }

    private fun isPad(source: Int) = (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK

    private fun index(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> 0; KeyEvent.KEYCODE_BUTTON_B -> 1; KeyEvent.KEYCODE_BUTTON_X -> 2; KeyEvent.KEYCODE_BUTTON_Y -> 3
        KeyEvent.KEYCODE_BUTTON_L1 -> 4; KeyEvent.KEYCODE_BUTTON_R1 -> 5; KeyEvent.KEYCODE_BUTTON_L2 -> 6; KeyEvent.KEYCODE_BUTTON_R2 -> 7
        KeyEvent.KEYCODE_BUTTON_SELECT -> 8; KeyEvent.KEYCODE_BUTTON_START -> 9
        KeyEvent.KEYCODE_BUTTON_THUMBL -> 10; KeyEvent.KEYCODE_BUTTON_THUMBR -> 11
        KeyEvent.KEYCODE_DPAD_UP -> 12; KeyEvent.KEYCODE_DPAD_DOWN -> 13; KeyEvent.KEYCODE_DPAD_LEFT -> 14; KeyEvent.KEYCODE_DPAD_RIGHT -> 15
        KeyEvent.KEYCODE_BUTTON_MODE -> 16
        else -> -1
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        val fromPad = isPad(e.source)
        if (code == KeyEvent.KEYCODE_BACK && !fromPad) {   // the device's own Back key: these buttons, or back to the game
            if (e.action == KeyEvent.ACTION_UP) onBack()
            return true
        }
        if (menu) {
            when (code) {
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> { if (e.action == KeyEvent.ACTION_UP) onBack(); return true }
                KeyEvent.KEYCODE_BUTTON_A -> { if (e.action == KeyEvent.ACTION_UP) { glide.pop(); currentFocus?.performClick() }; return true }
            }
            val dir = com.limelight.home.Feel.direction(code)
            if (dir != 0 && e.action == KeyEvent.ACTION_DOWN) { moveFocus(dir); return true }
            if (fromPad || KeyEvent.isGamepadButton(code)) return true
            return super.dispatchKeyEvent(e)
        }
        // a pad's Back (some pads send it for Select) is Select
        val idx = if (code == KeyEvent.KEYCODE_BACK) 8 else index(code)
        if (idx < 0) return if (fromPad || KeyEvent.isGamepadButton(code)) true else super.dispatchKeyEvent(e)
        if (e.repeatCount > 0) return true
        val v = when (e.action) { KeyEvent.ACTION_DOWN -> 1f; KeyEvent.ACTION_UP -> 0f; else -> return true }
        when (idx) {
            6 -> { keyL2 = v; b[6] = maxOf(keyL2, axL2) }
            7 -> { keyR2 = v; b[7] = maxOf(keyR2, axR2) }
            else -> b[idx] = v
        }
        checkCombo()
        if (!comboPending) push()
        return true
    }

    /** Select + Start + LB + RB, as for the Quick menu: once they're all let go, the pad drives these buttons. */
    private fun checkCombo() {
        if (state != State.PLAYING) return
        if (COMBO.all { b[it] > .5f }) {
            if (!comboPending) { comboPending = true; server?.update(FloatArray(PadServer.BUTTONS), FloatArray(PadServer.AXES)) }
        }
        if (comboPending && b.all { it == 0f }) { comboPending = false; FeelSounds.play(this, FeelSounds.Cue.OPEN); setMenu(true) }
    }

    private fun onBack() {
        if (state != State.PLAYING) { if (!menu) setMenu(true); return }   // sending or failed: the buttons are already up
        FeelSounds.play(this, if (menu) FeelSounds.Cue.CLOSE else FeelSounds.Cue.OPEN)
        setMenu(!menu)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = onBack()   // never leaves by itself: Stop does

    private fun moveFocus(dir: Int) {
        val cur = currentFocus
        if (cur == null || cur.parent !== buttons) { buttons.getChildAt(0)?.let { it.requestFocus(); glide.moveTo(it) }; return }
        val next = cur.focusSearch(dir)
        if (next != null && next !== cur && next.parent === buttons && next.requestFocus()) { FeelSounds.focus(next); glide.moveTo(next) }
        else com.limelight.home.Feel.edgeNudge(cur, dir, null, sw.pxf(14))
    }

    private fun stick(v: Float): Float {
        val m = Math.abs(v)
        if (m < deadzone) return 0f
        return v.coerceIn(-1f, 1f)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        val joy = (e.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (!joy || e.action != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(e)
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X); val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (menu) {   // the d-pad as a hat moves between the buttons
            if (hx != lastHatX || hy != lastHatY) {
                if (hx < -.5f) moveFocus(View.FOCUS_LEFT) else if (hx > .5f) moveFocus(View.FOCUS_RIGHT)
                lastHatX = hx; lastHatY = hy
            }
            return true
        }
        val d = e.device
        // right stick: Z/RZ on most pads, RX/RY on some
        val useRx = d != null && d.getMotionRange(MotionEvent.AXIS_Z, e.source) == null && d.getMotionRange(MotionEvent.AXIS_RX, e.source) != null
        a[0] = stick(e.getAxisValue(MotionEvent.AXIS_X))
        a[1] = stick(e.getAxisValue(MotionEvent.AXIS_Y))
        a[2] = stick(e.getAxisValue(if (useRx) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z))
        a[3] = stick(e.getAxisValue(if (useRx) MotionEvent.AXIS_RY else MotionEvent.AXIS_RZ))
        axL2 = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)).coerceIn(0f, 1f)
        axR2 = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)).coerceIn(0f, 1f)
        b[6] = maxOf(keyL2, axL2); b[7] = maxOf(keyR2, axR2)
        if (hx != 0f || hy != 0f || lastHatX != 0f || lastHatY != 0f) {
            b[12] = if (hy < -.5f) 1f else 0f; b[13] = if (hy > .5f) 1f else 0f
            b[14] = if (hx < -.5f) 1f else 0f; b[15] = if (hx > .5f) 1f else 0f
        }
        lastHatX = hx; lastHatY = hy
        checkCombo()
        if (!comboPending) push()
        return true
    }
}
