package com.limelight.home

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Settings, Controllers, "Test a controller" (after Artemis' gamepad test page): every connected pad drawn live, its
 * buttons, sticks, triggers and D-pad, the last key with its key code and scan code (handy when a pad maps oddly), and
 * a rumble test. The TV remote's OK tries the rumble; Back on the remote, or holding Select + Start, leaves.
 */
class PadTestActivity : Activity() {
    private class Pad(val device: InputDevice) {
        val held = HashSet<Int>()
        val axes = HashMap<Int, Float>()
        var lastKey = ""
    }

    private val pads = LinkedHashMap<Int, Pad>()
    private lateinit var view: View
    private var leaveAt = 0L

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val oled = getSharedPreferences("mc_tv", Context.MODE_PRIVATE).getBoolean("oled", false)
        view = Board(this, oled)
        setContentView(view)
        scan()
    }

    /** Pick up pads that are connected but haven't been pressed yet. */
    private fun scan() {
        // Same filter as the Quick menu: real, numbered game controllers (not the TV's "virtual-search" input)
        QuickMenu.pads().forEach { d -> pads.getOrPut(d.id) { Pad(d) } }
    }

    private fun isPad(e: KeyEvent) = e.source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            e.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (!isPad(e)) {   // the TV remote: OK = rumble test, Back = leave
            if (e.action == KeyEvent.ACTION_UP) when (e.keyCode) {
                KeyEvent.KEYCODE_BACK -> finish()
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> rumbleAll()
            }
            return true
        }
        val d = e.device ?: return true
        if (QuickMenu.pads().none { it.id == d.id }) return true
        val p = pads.getOrPut(e.deviceId) { Pad(d) }
        if (e.action == KeyEvent.ACTION_DOWN) {
            p.held.add(e.keyCode)
            p.lastKey = "${KeyEvent.keyCodeToString(e.keyCode).removePrefix("KEYCODE_")}  (key ${e.keyCode}, scan ${e.scanCode})"
            if (p.held.contains(KeyEvent.KEYCODE_BUTTON_SELECT) && p.held.contains(KeyEvent.KEYCODE_BUTTON_START)) {
                leaveAt = System.currentTimeMillis() + 1500
                view.postDelayed({ if (leaveAt != 0L && System.currentTimeMillis() >= leaveAt) finish() }, 1550)
            }
            if (e.keyCode == KeyEvent.KEYCODE_BUTTON_Y && p.held.contains(KeyEvent.KEYCODE_BUTTON_X)) rumble(p.device, 255)
        } else if (e.action == KeyEvent.ACTION_UP) {
            p.held.remove(e.keyCode); leaveAt = 0L
        }
        view.invalidate()
        return true
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return super.dispatchGenericMotionEvent(e)
        val d = e.device ?: return true
        if (QuickMenu.pads().none { it.id == d.id }) return true
        val p = pads.getOrPut(e.deviceId) { Pad(d) }
        d.motionRanges.forEach { r -> p.axes[r.axis] = e.getAxisValue(r.axis) }
        view.invalidate()
        return true
    }

    private fun rumbleAll() = pads.values.forEach { rumble(it.device, 200) }

    private fun rumble(d: InputDevice, amp: Int) {
        try {
            @Suppress("DEPRECATION") val v = d.vibrator
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(400, amp)) else @Suppress("DEPRECATION") v.vibrate(400)
        } catch (e: Exception) { }
    }

    /** Draws each pad's state. Sizes follow the 1920-wide design grid of the TV home. */
    private inner class Board(ctx: Context, private val oled: Boolean) : View(ctx) {
        private val bg = if (oled) 0xFF000000.toInt() else 0xFF090D18.toInt()
        private val moon = 0xFFEEF1F7.toInt(); private val dust = 0xFF97A1B8.toInt()
        private val line = 0xFF263150.toInt(); private val beam = 0xFF4FD6E3.toInt(); private val corona = 0xFFF2B55C.toInt()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG)
        private var k = 1f
        private fun u(v: Number) = v.toFloat() * k

        override fun onDraw(c: Canvas) {
            k = width / 1920f
            c.drawColor(bg)
            label(c, "Test a controller", 96, 110, 52, moon, true)
            label(c, "Press anything on a pad. OK on the remote tries the rumble (or X + Y on the pad). Back, or hold Select + Start, to leave.",
                    96, 165, 24, dust)
            if (pads.isEmpty()) { label(c, "No controller connected. Pair one in Settings, Controllers.", 96, 300, 30, moon); return }
            pads.values.take(2).forEachIndexed { i, p -> drawPad(c, p, 96f + i * 880f, 236f) }
        }

        private fun label(c: Canvas, s: String, x: Number, y: Number, size: Number, color: Int, bold: Boolean = false) {
            text.color = color; text.textSize = u(size); text.isFakeBoldText = bold
            c.drawText(s, u(x), u(y), text)
        }

        private fun button(c: Canvas, s: String, x: Float, y: Float, on: Boolean, w: Float = 92f) {
            val r = RectF(u(x), u(y), u(x + w), u(y + 58))
            fill.color = if (on) beam else 0x00000000
            c.drawRoundRect(r, u(14), u(14), fill)
            stroke.color = if (on) beam else line; stroke.strokeWidth = u(2)
            c.drawRoundRect(r, u(14), u(14), stroke)
            text.color = if (on) bg else moon; text.textSize = u(24); text.isFakeBoldText = true
            val tw = text.measureText(s)
            c.drawText(s, r.centerX() - tw / 2, r.centerY() + u(9), text)
        }

        private fun stick(c: Canvas, s: String, cx: Float, cy: Float, x: Float, y: Float, pressed: Boolean) {
            stroke.color = if (pressed) beam else line; stroke.strokeWidth = u(3)
            c.drawCircle(u(cx), u(cy), u(70), stroke)
            fill.color = if (Math.abs(x) > 0.08f || Math.abs(y) > 0.08f) corona else dust
            c.drawCircle(u(cx + x * 56), u(cy + y * 56), u(16), fill)
            label(c, "$s  %+.2f %+.2f".format(x, y), cx - 70, cy + 110, 20, dust)
        }

        private fun bar(c: Canvas, s: String, x: Float, y: Float, v: Float) {
            stroke.color = line; stroke.strokeWidth = u(2)
            c.drawRoundRect(RectF(u(x), u(y), u(x + 220), u(y + 26)), u(13), u(13), stroke)
            fill.color = beam
            if (v > 0.01f) c.drawRoundRect(RectF(u(x), u(y), u(x + 220 * v.coerceIn(0f, 1f)), u(y + 26)), u(13), u(13), fill)
            label(c, "$s %.2f".format(v), x + 236, y + 21, 20, dust)
        }

        private fun drawPad(c: Canvas, p: Pad, x: Float, y: Float) {
            val d = p.device
            label(c, d.name, x, y, 30, moon, true)
            label(c, "Vendor %04x, product %04x".format(d.vendorId, d.productId), x, y + 36, 20, dust)
            fun h(vararg k: Int) = k.any { p.held.contains(it) }
            fun a(axis: Int) = p.axes[axis] ?: 0f
            val ty = y + 64
            button(c, "LB", x, ty, h(KeyEvent.KEYCODE_BUTTON_L1)); button(c, "RB", x + 620, ty, h(KeyEvent.KEYCODE_BUTTON_R1))
            button(c, "LT", x, ty + 70, h(KeyEvent.KEYCODE_BUTTON_L2) || a(MotionEvent.AXIS_LTRIGGER) > .5f || a(MotionEvent.AXIS_BRAKE) > .5f)
            button(c, "RT", x + 620, ty + 70, h(KeyEvent.KEYCODE_BUTTON_R2) || a(MotionEvent.AXIS_RTRIGGER) > .5f || a(MotionEvent.AXIS_GAS) > .5f)
            button(c, "View", x + 210, ty + 35, h(KeyEvent.KEYCODE_BUTTON_SELECT), 110f)
            button(c, "Home", x + 330, ty + 35, h(KeyEvent.KEYCODE_BUTTON_MODE), 110f)
            button(c, "Menu", x + 450, ty + 35, h(KeyEvent.KEYCODE_BUTTON_START), 110f)
            // D-pad: keys or hat axes
            val dy = ty + 160
            button(c, "↑", x + 70, dy, h(KeyEvent.KEYCODE_DPAD_UP) || a(MotionEvent.AXIS_HAT_Y) < -.5f, 64f)
            button(c, "←", x, dy + 66, h(KeyEvent.KEYCODE_DPAD_LEFT) || a(MotionEvent.AXIS_HAT_X) < -.5f, 64f)
            button(c, "→", x + 140, dy + 66, h(KeyEvent.KEYCODE_DPAD_RIGHT) || a(MotionEvent.AXIS_HAT_X) > .5f, 64f)
            button(c, "↓", x + 70, dy + 132, h(KeyEvent.KEYCODE_DPAD_DOWN) || a(MotionEvent.AXIS_HAT_Y) > .5f, 64f)
            // Face buttons
            button(c, "Y", x + 580, dy, h(KeyEvent.KEYCODE_BUTTON_Y), 64f)
            button(c, "X", x + 510, dy + 66, h(KeyEvent.KEYCODE_BUTTON_X), 64f)
            button(c, "B", x + 650, dy + 66, h(KeyEvent.KEYCODE_BUTTON_B), 64f)
            button(c, "A", x + 580, dy + 132, h(KeyEvent.KEYCODE_BUTTON_A), 64f)
            // Sticks
            stick(c, "Left", x + 180, dy + 290, a(MotionEvent.AXIS_X), a(MotionEvent.AXIS_Y), h(KeyEvent.KEYCODE_BUTTON_THUMBL))
            stick(c, "Right", x + 520, dy + 290, a(MotionEvent.AXIS_Z), a(MotionEvent.AXIS_RZ), h(KeyEvent.KEYCODE_BUTTON_THUMBR))
            // Analog triggers, whichever axes this pad reports (DualSense on old kernels: RX / RY)
            val lt = maxOf(a(MotionEvent.AXIS_LTRIGGER), a(MotionEvent.AXIS_BRAKE))
            val rt = maxOf(a(MotionEvent.AXIS_RTRIGGER), a(MotionEvent.AXIS_GAS))
            bar(c, "LT", x, dy + 420, if (lt > 0f || p.axes[MotionEvent.AXIS_RX] == null) lt else (a(MotionEvent.AXIS_RX) + 1f) / 2f)
            bar(c, "RT", x + 400, dy + 420, if (rt > 0f || p.axes[MotionEvent.AXIS_RY] == null) rt else (a(MotionEvent.AXIS_RY) + 1f) / 2f)
            label(c, "Last: " + p.lastKey.ifBlank { "nothing yet" }, x, dy + 490, 22, moon)
            @Suppress("DEPRECATION") val rumble = try { d.vibrator.hasVibrator() } catch (e: Exception) { false }
            label(c, if (rumble) "Rumble: supported" else "Rumble: not reported by this pad", x, dy + 524, 20, dust)
        }
    }
}
