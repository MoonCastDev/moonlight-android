package com.limelight.home

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The in-game Quick menu, shared by the PC player (Moonlight) and the PS5 player (chiaki): a panel on the right with
 * live stats and a short list of actions. Opened with Select + Start + LB + RB on any pad, or Back on the TV remote.
 *
 * The game keeps the screen, so the menu does its own input instead of relying on Android focus: the players eat
 * every pad event, and a pad's d-pad often arrives as hat motion, not keys. While the menu is open the player
 * forwards key and motion events here and sends nothing to the game.
 */
class QuickMenu(private val act: Activity) {
    class Item @JvmOverloads constructor(val icon: String, val label: String, val detail: String? = null,
                                         val danger: Boolean = false, val action: Runnable = Runnable { }) {
        /** The item flips something on or off (it plays the toggle sound instead of select). */
        var toggle = false
        fun asToggle(): Item = apply { toggle = true }
    }

    /** (value, label) pairs for the stat tiles, read every second while the menu is open. */
    fun interface Stats { fun get(): List<Pair<String, String>> }

    private val tv = Tv(act, act.getSharedPreferences("mc_tv", Context.MODE_PRIVATE).getBoolean("oled", false))
    private val ui = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var rows = listOf<View>()
    private var items = listOf<Item>()
    private var sel = 0
    private var lastMove = 0L
    private var stats: Stats? = null
    private var statsViews = listOf<TextView>()
    private var chosenAt = 0L

    val isShowing get() = root != null

    /** Show the menu. [stats] is polled every second while it's open: (value, label) pairs, up to four. */
    fun show(title: String, subtitle: String, stats: Stats, items: List<Item>) {
        FeelSounds.warm(act)
        // one sound per action: an item that reopens the menu (a sub-list) already played its own
        if (!isShowing && SystemClock.uptimeMillis() - chosenAt > 400) FeelSounds.play(act, FeelSounds.Cue.OPEN, PAN)
        if (isShowing) close()
        this.items = items; this.stats = stats; sel = 0
        if (switchStyle) { showSwitch(title, subtitle, stats, items); return }

        val panel = tv.col().apply {
            background = tv.round(tv.p.deep, 0)
            setPadding(tv.px(52), tv.px(64), tv.px(52), tv.px(48))
        }
        panel.addView(tv.text(title, 30, tv.p.moon, tv.display(600), 2))
        panel.addView(tv.text(subtitle, 21, tv.p.dust, tv.ui(500), 2), tv.lp(-1, -2).apply { topMargin = tv.px(8) })

        val grid = GridLayout(act).apply { columnCount = 2 }
        val first = stats.get()
        statsViews = (0 until minOf(4, first.size)).map { i ->
            val n = tv.text(first[i].first, 34, tv.p.moon, tv.display(600))
            grid.addView(tv.col().apply {
                background = tv.round(tv.p.night, 16, tv.p.line, 1)
                setPadding(tv.px(18), tv.px(14), tv.px(18), tv.px(14))
                addView(n)
                addView(tv.text(first[i].second, 18, tv.p.dust, tv.ui(600)), tv.lp(-2, -2).apply { topMargin = tv.px(4) })
            }, GridLayout.LayoutParams().apply {
                width = tv.px(272); rightMargin = if (i % 2 == 0) tv.px(12) else 0; bottomMargin = tv.px(12)
            })
            n
        }
        if (statsViews.isNotEmpty()) panel.addView(grid, tv.lp(-1, -2).apply { topMargin = tv.px(28) })

        val list = tv.col()
        rows = items.mapIndexed { i, it ->
            tv.row().apply {
                setPadding(tv.px(20), 0, tv.px(20), 0)
                addView(tv.text(it.icon, 19, tv.p.moon, tv.ui(700)).apply {
                    gravity = Gravity.CENTER; background = tv.round(tv.p.raise, 10, tv.p.line, 1)
                }, tv.lp(44, 44).apply { rightMargin = tv.px(18) })
                addView(tv.text(it.label, 24, if (it.danger) Tv.DANGER else tv.p.moon, tv.ui(700)), LinearLayout.LayoutParams(0, -2, 1f))
                if (it.detail != null) addView(tv.text(it.detail, 19, tv.p.dust, tv.ui(600)))
                setOnClickListener { _ -> choose(i) }   // touch screens (Odin)
                list.addView(this, tv.lp(-1, 80))
            }
        }
        panel.addView(list, tv.lp(-1, -2).apply { topMargin = tv.px(16) })
        panel.addView(View(act), LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(tv.row().apply {
            addView(tv.hint("A", "Select")); addView(tv.hint("B", "Back to the game"))
        })

        val r = FrameLayout(act).apply {
            setBackgroundColor(0x99000000.toInt())
            isClickable = true
            setOnClickListener { hide() }
            addView(panel, FrameLayout.LayoutParams(tv.px(660), -1, Gravity.END).apply { })
            panel.isClickable = true
        }
        (act.window.decorView as ViewGroup).addView(r, ViewGroup.LayoutParams(-1, -1))
        root = r
        paint()
        ui.post(tick)
    }

    // ---- the handheld's look: the Switch-style quick settings of mooncast-handheld-v2.html ----

    /** The handheld app (not the TV) uses the Switch look: a right panel in landscape, a bottom sheet upright. */
    private val switchStyle = !com.limelight.BuildConfig.TV_APP
    private val light = act.getSharedPreferences("mc_tv", Context.MODE_PRIVATE).getString("hh_theme", "dark") == "light"
    private val sBg = if (light) 0xFFEBEBEB.toInt() else 0xFF2B2D31.toInt()
    private val sPanel = if (light) 0xFFFFFFFF.toInt() else 0xFF36393F.toInt()
    private val sInk = if (light) 0xFF2D2D2D.toInt() else 0xFFFFFFFF.toInt()
    private val sSub = if (light) 0xFF6E727A.toInt() else 0xFFB5BAC3.toInt()
    private val sRing = if (light) 0xFF00A9C2.toInt() else 0xFF2FD3E6.toInt()
    private var st: Tv = tv

    private fun showSwitch(title: String, subtitle: String, stats: Stats, items: List<Item>) {
        val dm = act.resources.displayMetrics
        val upright = dm.heightPixels > dm.widthPixels
        st = Tv(act, false, if (upright) 1080 else 1920, if (upright) 1920 else 1080)
        val t = st
        fun txt(v: CharSequence, size: Number, color: Int, w: Int, lines: Int = 1) = t.text(v, size, color, t.ui(w), lines)

        val panel = t.col().apply {
            background = if (upright) android.graphics.drawable.GradientDrawable().apply {
                setColor(sPanel); val r = t.pxf(36); cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f) }
                else android.graphics.drawable.ColorDrawable(sPanel)
            setPadding(t.px(44), t.px(if (upright) 26 else 44), t.px(44), t.px(if (upright) 60 else 40))
        }
        if (upright) panel.addView(View(act).apply { background = t.round(if (light) 0xFFCDCFD4.toInt() else 0xFF474B53.toInt(), 5) },
                LinearLayout.LayoutParams(t.px(120), t.px(10)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = t.px(30) })
        panel.addView(t.row().apply {
            addView(txt(title, 36, sInk, 800), LinearLayout.LayoutParams(0, -2, 1f))
            addView(txt(subtitle, 24, sSub, 700), t.lp(-2, -2).apply { leftMargin = t.px(16) })
        })

        val first = stats.get()
        val statRow = t.row()
        statsViews = (0 until minOf(4, first.size)).map { i ->
            val n = txt(first[i].first, 34, sInk, 800)
            statRow.addView(t.col().apply {
                background = t.round(sBg, 10)
                setPadding(t.px(16), t.px(18), t.px(16), t.px(18))
                addView(n)
                addView(txt(first[i].second, 20, sSub, 600), t.lp(-2, -2).apply { topMargin = t.px(4) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = t.px(8) })
            n
        }
        if (statsViews.isNotEmpty()) panel.addView(statRow, t.lp(-1, -2).apply { topMargin = t.px(30); bottomMargin = t.px(20) })

        val list = t.col()
        rows = items.mapIndexed { i, it ->
            t.row().apply {
                setPadding(t.px(24), t.px(14), t.px(24), t.px(14))
                minimumHeight = t.px(100)
                addView(txt(it.icon, 18, 0xFFFFFFFF.toInt(), 800).apply {
                    gravity = Gravity.CENTER; background = t.round(if (it.danger) 0xFFE9605A.toInt() else 0xFF5B6170.toInt(), 12)
                }, t.lp(52, 52).apply { rightMargin = t.px(22) })
                addView(t.col().apply {
                    addView(txt(it.label, 28, sInk, 800))
                    if (it.detail != null) addView(txt(it.detail, 21, sSub, 600), t.lp(-2, -2).apply { topMargin = t.px(4) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
                setOnClickListener { _ -> choose(i) }
                list.addView(this, t.lp(-1, -2).apply { topMargin = t.px(10) })
            }
        }
        val scroll = android.widget.ScrollView(act).apply { isVerticalScrollBarEnabled = false; addView(list) }
        if (upright) panel.addView(scroll, t.lp(-1, -2)) else panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        panel.addView(t.row().apply {
            addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
            listOf("B" to "Back to the game", "A" to "OK").forEach { (k, l) ->
                addView(txt(k, 24, sBg, 800).apply { gravity = Gravity.CENTER; background = t.round(sInk, 23) }, t.lp(46, 46).apply { leftMargin = t.px(32); rightMargin = t.px(12) })
                addView(txt(l, 24, sInk, 700))
            }
        }, t.lp(-1, -2).apply { topMargin = t.px(24) })

        val r = FrameLayout(act).apply {
            setBackgroundColor(0x80000000.toInt())
            isClickable = true
            setOnClickListener { hide() }
            if (upright) addView(panel, FrameLayout.LayoutParams(-1, (dm.heightPixels * 0.62f).toInt(), Gravity.BOTTOM))
            else addView(panel, FrameLayout.LayoutParams(t.px(680), -1, Gravity.END))
            panel.isClickable = true
        }
        (act.window.decorView as ViewGroup).addView(r, ViewGroup.LayoutParams(-1, -1))
        root = r
        paint()
        ui.post(tick)
    }

    /** The selected row in the Switch look: the cyan double ring (a gap in the panel colour, then cyan). */
    private fun switchRow(on: Boolean): android.graphics.drawable.Drawable {
        val t = st
        if (!on) return t.round(sBg, 10)
        return android.graphics.drawable.LayerDrawable(arrayOf(t.round(sRing, 10), t.round(sPanel, 8), t.round(sBg, 6))).apply {
            setLayerInset(1, t.px(6), t.px(6), t.px(6), t.px(6))
            setLayerInset(2, t.px(11), t.px(11), t.px(11), t.px(11))
        }
    }

    /** Close the menu, with its close sound. */
    fun hide() {
        if (isShowing) FeelSounds.play(act, FeelSounds.Cue.CLOSE, PAN)
        close()
    }

    private fun close() {
        ui.removeCallbacks(tick)
        root?.let { (it.parent as? ViewGroup)?.removeView(it) }
        root = null
    }

    private val tick = object : Runnable {
        override fun run() {
            val s = stats?.get() ?: return
            statsViews.forEachIndexed { i, v -> if (i < s.size) v.text = s[i].first }
            ui.postDelayed(this, 1000)
        }
    }

    private fun paint() {
        rows.forEachIndexed { i, v ->
            v.background = if (switchStyle) switchRow(i == sel) else if (i == sel) tv.round(tv.p.raise, 16, Tv.CORONA, 3) else null
        }
    }

    private fun move(d: Int) {
        if (rows.isEmpty()) return
        sel = (sel + d + rows.size) % rows.size
        paint()
        FeelSounds.play(act, FeelSounds.Cue.FOCUS, PAN)
    }

    private fun choose(i: Int) {
        val it = items.getOrNull(i) ?: return
        FeelSounds.play(act, if (it.toggle) FeelSounds.Cue.TOGGLE else FeelSounds.Cue.SELECT, PAN)
        chosenAt = SystemClock.uptimeMillis()
        close()
        it.action.run()
    }

    /** Key events while the menu is open. Always consumes them. */
    fun onKey(e: KeyEvent): Boolean {
        if (e.action != KeyEvent.ACTION_DOWN) return true
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> move(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (e.repeatCount == 0) choose(sel)
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> if (e.repeatCount == 0) hide()
        }
        return true
    }

    /** Pad d-pad (hat) and left stick, for pads that don't send d-pad keys. Always consumes them. */
    fun onMotion(e: MotionEvent): Boolean {
        if ((e.source and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK) return true
        val y = e.getAxisValue(MotionEvent.AXIS_HAT_Y).let { if (Math.abs(it) > .5f) it else e.getAxisValue(MotionEvent.AXIS_Y) }
        val now = SystemClock.uptimeMillis()
        if (Math.abs(y) > .6f && now - lastMove > 220) { lastMove = now; move(if (y > 0) 1 else -1) }
        if (Math.abs(y) < .3f) lastMove = 0
        return true
    }

    companion object {
        /** The panel sits on the right, so its sounds lean right. */
        private const val PAN = 0.4f

        /** Physical game pads connected now (not the TV remote). */
        @JvmStatic fun pads(): List<InputDevice> = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }.filter { d ->
            // A real pad has sticks (joystick source) or at least a maker id; the TV's "virtual-search" input claims
            // to be a numbered gamepad on some sticks (Mi stick, Android 9) but has neither.
            val joystick = (d.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            val gamepad = (d.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
            !d.isVirtual && d.controllerNumber > 0 && (joystick || (gamepad && d.vendorId != 0))
        }

        /** "80 %" where Android knows the pad's battery (Android 12+), else null. */
        @JvmStatic fun battery(d: InputDevice): String? = try {
            if (android.os.Build.VERSION.SDK_INT >= 31 && d.batteryState.isPresent) "${Math.round(d.batteryState.capacity * 100)} %" else null
        } catch (e: Exception) { null }

        @JvmStatic fun pairController(a: Activity) {
            for (action in listOf(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS, android.provider.Settings.ACTION_SETTINGS))
                try { a.startActivity(android.content.Intent(action)); return } catch (e: Exception) { }
        }

        @JvmStatic fun stat(value: String, label: String) = Pair(value, label)

        /** The combo that opens the menu in both players. */
        const val COMBO_LABEL = "Select + Start + LB + RB"

        /** Remote Back (not a pad's B button) opens the menu. */
        @JvmStatic fun isRemoteBack(e: KeyEvent): Boolean {
            if (e.keyCode != KeyEvent.KEYCODE_BACK) return false
            val s = e.source
            return (s and InputDevice.SOURCE_GAMEPAD) != InputDevice.SOURCE_GAMEPAD &&
                   (s and InputDevice.SOURCE_JOYSTICK) != InputDevice.SOURCE_JOYSTICK &&
                   (s and InputDevice.SOURCE_MOUSE) != InputDevice.SOURCE_MOUSE
        }
    }
}

/** Live numbers from the PC player's decoder, for the Quick menu (filled whether or not the overlay is on). */
object StreamStats {
    @Volatile var fps = 0f
    @Volatile var rttMs = 0
    @Volatile var lossPct = 0f
    @Volatile var size = ""
    @JvmStatic fun update(fps: Float, rttMs: Int, lossPct: Float, size: String) {
        this.fps = fps; this.rttMs = rttMs; this.lossPct = lossPct; this.size = size
    }
}
