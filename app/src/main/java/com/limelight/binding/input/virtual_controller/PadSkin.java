package com.limelight.binding.input.virtual_controller;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/**
 * MoonCast's drawn skins for the on-screen controller (#74). The engine (touch, sliding between
 * buttons, what's sent to the PC) is Moonlight's; a skin only changes how the keys look and where
 * they sit. PlayStation: dark keys over faint see-through plates, laid out on Delta's positions.
 */
public final class PadSkin {
    public static final String PREF = "mc_osc_style";
    public static final String NONE = "classic";
    public static final String PS = "ps";
    public static final String XBOX = "xbox";
    /** How visible the skin is, in percent (50 = half see-through). */
    public static final String OPACITY_PREF = "mc_osc_opacity";
    public static final int DEFAULT_OPACITY = 50;

    public static int opacity(Context context) {
        return android.preference.PreferenceManager.getDefaultSharedPreferences(context).getInt(OPACITY_PREF, DEFAULT_OPACITY);
    }

    // what a button is drawn as
    public static final int TRIANGLE = 1, CIRCLE = 2, SQUARE = 3, CROSS = 4;
    public static final int SHOULDER_L = 5, SHOULDER = 6, SHOULDER_R = 7;
    public static final int CREATE = 8, OPTIONS = 9, TOUCHPAD = 10, MENU = 11, PSHOME = 12;
    // Xbox
    public static final int XA = 13, XB = 14, XX = 15, XY = 16, TRIGGER_L = 17, TRIGGER_R = 18, XHOME = 19, VIEW = 20, XMENU = 21, SHARE = 22;

    private static final int KEY_TOP = 0xFF55565B, KEY_MID = 0xFF3B3C40, KEY_BOTTOM = 0xFF2A2B2E;
    private static final int KEY_EDGE = 0xFF1D1E21, LABEL = 0xFFD2D4D9;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Vibrator vibrator;
    /** Xbox look (A B X Y, one-piece d-pad, triggers) instead of PlayStation. */
    final boolean xbox;

    PadSkin(Context context, boolean xbox) {
        this.xbox = xbox;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setStrokeCap(Paint.Cap.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    /** A very light tick under the finger, like a key on the phone's keyboard. */
    void tick() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(6, vibrator.hasAmplitudeControl() ? 50 : VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(6);
            }
        } catch (RuntimeException ignored) {
        }
    }

    // ---- shared pieces

    private void keyFill(float top, float bottom, boolean pressed) {
        int lift = pressed ? 0x00202020 : 0;
        fill.setShader(new LinearGradient(0, top, 0, bottom,
                new int[]{KEY_TOP + lift, KEY_MID + lift, KEY_BOTTOM + lift}, new float[]{0, .55f, 1}, Shader.TileMode.CLAMP));
        fill.setShadowLayer(pressed ? 2 : 6, 0, pressed ? 1 : 4, 0x73000000);
    }

    private void capFill(float cx, float cy, float r, boolean pressed) {
        int lift = pressed ? 0x00242424 : 0;
        fill.setShader(new RadialGradient(cx - r * .1f, cy - r * .3f, r * 1.5f,
                new int[]{KEY_TOP + lift, 0xFF36373B + lift, 0xFF202124 + lift}, new float[]{0, .7f, 1}, Shader.TileMode.CLAMP));
        fill.setShadowLayer(pressed ? 2 : 6, 0, pressed ? 1 : 4, 0x73000000);
    }

    private void plainFill(int color) {
        fill.setShader(null);
        fill.clearShadowLayer();
        fill.setColor(color);
    }

    private void edge(Canvas c, int color, float width) {
        line.setColor(color);
        line.setStrokeWidth(width);
        c.drawPath(path, line);
    }

    private void label(Canvas c, String s, float x, float y, float size, int color) {
        text.setTextSize(size);
        text.setColor(color);
        if (color == LABEL) text.setShadowLayer(Math.max(2, size * .12f), 0, 1, 0xE6000000); else text.clearShadowLayer();
        Paint.FontMetrics m = text.getFontMetrics();
        c.drawText(s, x, y - (m.ascent + m.descent) / 2, text);
    }

    /** The faint cross-shaped plate the d-pad and the shape buttons sit on (see-through). */
    void plate(Canvas c, float cx, float cy, float r) {
        float a = r * .48f;
        path.reset();
        path.moveTo(cx - a, cy - r);
        path.lineTo(cx + a, cy - r); path.lineTo(cx + a, cy - a); path.lineTo(cx + r, cy - a);
        path.lineTo(cx + r, cy + a); path.lineTo(cx + a, cy + a); path.lineTo(cx + a, cy + r);
        path.lineTo(cx - a, cy + r); path.lineTo(cx - a, cy + a); path.lineTo(cx - r, cy + a);
        path.lineTo(cx - r, cy - a); path.lineTo(cx - a, cy - a); path.close();
        plainFill(0x61B0B2B7);
        c.drawPath(path, fill);
        edge(c, 0x73FFFFFF, Math.max(2, r * .012f));
    }

    // ---- d-pad: four arrow keys on the plate

    void dpad(Canvas c, float w, float h, int direction) {
        if (xbox) {
            xdpad(c, w, h, direction);
            return;
        }
        float cx = w / 2, cy = h / 2, r = Math.min(w, h) / 2 / 1.36f;
        plate(c, cx, cy, r * 1.32f);
        for (int i = 0; i < 4; i++) {
            // the small moulded arrows at the plate's ends
            c.save();
            c.rotate(90 * i, cx, cy);
            path.reset();
            path.moveTo(cx, cy - r * 1.24f);
            path.lineTo(cx - r * .12f, cy - r * 1.12f);
            path.lineTo(cx + r * .12f, cy - r * 1.12f);
            path.close();
            plainFill(0xCCC8CACE);
            c.drawPath(path, fill);
            c.restore();
        }
        int[] bit = {DigitalPad.DIGITAL_PAD_DIRECTION_UP, DigitalPad.DIGITAL_PAD_DIRECTION_RIGHT,
                DigitalPad.DIGITAL_PAD_DIRECTION_DOWN, DigitalPad.DIGITAL_PAD_DIRECTION_LEFT};
        for (int i = 0; i < 4; i++) {
            boolean down = (direction & bit[i]) != 0;
            c.save();
            c.rotate(90 * i, cx, cy);
            if (down) c.translate(0, r * .03f);
            float kw = r * .30f, top = cy - r * .98f, side = cy - r * .92f + r * .62f * .62f;
            path.reset();
            path.moveTo(cx - kw, top + r * .06f);
            path.quadTo(cx - kw, top, cx - kw + r * .06f, top);
            path.lineTo(cx + kw - r * .06f, top);
            path.quadTo(cx + kw, top, cx + kw, top + r * .06f);
            path.lineTo(cx + kw, side);
            path.lineTo(cx, cy - r * .18f);
            path.lineTo(cx - kw, side);
            path.close();
            keyFill(top, cy, down);
            c.drawPath(path, fill);
            edge(c, KEY_EDGE, Math.max(2, r * .02f));
            c.restore();
        }
    }

    // ---- buttons

    void button(Canvas c, int shape, float w, float h, boolean pressed, String text) {
        switch (shape) {
            case TRIANGLE: shapeKey(c, w, h, shape, 0xFF3FD3A0, pressed); break;
            case CIRCLE: shapeKey(c, w, h, shape, 0xFFF25C66, pressed); break;
            case SQUARE: shapeKey(c, w, h, shape, 0xFFE58BD8, pressed); break;
            case CROSS: shapeKey(c, w, h, shape, 0xFF7BA4F2, pressed); break;
            case SHOULDER_L: case SHOULDER: case SHOULDER_R: shoulder(c, shape, w, h, pressed, text); break;
            case CREATE: create(c, w, h, pressed); break;
            case OPTIONS: options(c, w, h, pressed); break;
            case TOUCHPAD: touchpad(c, w, h, pressed); break;
            case MENU: menu(c, w, h, pressed); break;
            case PSHOME: psHome(c, w, h, pressed); break;
            case XA: letterKey(c, w, h, "A", 0xFF5FCB5A, pressed); break;
            case XB: letterKey(c, w, h, "B", 0xFFF0574F, pressed); break;
            case XX: letterKey(c, w, h, "X", 0xFF4D96FF, pressed); break;
            case XY: letterKey(c, w, h, "Y", 0xFFF2C230, pressed); break;
            case TRIGGER_L: case TRIGGER_R: trigger(c, shape == TRIGGER_L, w, h, pressed, text); break;
            case XHOME: xHome(c, w, h, pressed); break;
            case VIEW: case XMENU: smallKey(c, shape, w, h, pressed); break;
            case SHARE: share(c, w, h, pressed); break;
        }
    }

    private void shapeKey(Canvas c, float w, float h, int shape, int color, boolean pressed) {
        float cx = w / 2, cy = h / 2 + (pressed ? 1.5f : 0), r = Math.min(w, h) / 2 * .9f;
        capFill(cx, cy, r, pressed);
        c.drawCircle(cx, cy, r, fill);
        line.setColor(0xFF141518); line.setStrokeWidth(Math.max(2, r * .04f));
        c.drawCircle(cx, cy, r, line);
        line.setColor(0x14FFFFFF); line.setStrokeWidth(Math.max(2, r * .04f));
        c.drawCircle(cx, cy, r * .93f, line);
        float q = r * .46f;
        int col = pressed ? (color | 0xFF000000) : color;
        line.setColor(col);
        line.setStrokeWidth(r * .11f);
        path.reset();
        switch (shape) {
            case TRIANGLE:
                path.moveTo(cx, cy - q * 1.15f); path.lineTo(cx + q * 1.1f, cy + q * .75f); path.lineTo(cx - q * 1.1f, cy + q * .75f); path.close();
                c.drawPath(path, line); break;
            case CIRCLE:
                c.drawCircle(cx, cy, q * 1.05f, line); break;
            case SQUARE:
                c.drawRect(cx - q * .95f, cy - q * .95f, cx + q * .95f, cy + q * .95f, line); break;
            case CROSS:
                c.drawLine(cx - q, cy - q, cx + q, cy + q, line); c.drawLine(cx + q, cy - q, cx - q, cy + q, line); break;
        }
        if (pressed) {
            // the symbol glows a little while held
            plainFill((col & 0x00FFFFFF) | 0x26000000);
            c.drawCircle(cx, cy, r, fill);
        }
    }

    private void shoulder(Canvas c, int shape, float w, float h, boolean pressed, String s) {
        float pad = h * .1f, x = pad, y = pad + (pressed ? 1.5f : 0), kw = w - 2 * pad, kh = h - 2 * pad;
        float rl = shape == SHOULDER_L ? kh * .5f : kh * .2f, rr = shape == SHOULDER_R ? kh * .5f : kh * .2f, rb = kh * .18f;
        path.reset();
        path.moveTo(x + rl, y);
        path.lineTo(x + kw - rr, y); path.quadTo(x + kw, y, x + kw, y + rr);
        path.lineTo(x + kw, y + kh - rb); path.quadTo(x + kw, y + kh, x + kw - rb, y + kh);
        path.lineTo(x + rb, y + kh); path.quadTo(x, y + kh, x, y + kh - rb);
        path.lineTo(x, y + rl); path.quadTo(x, y, x + rl, y); path.close();
        keyFill(y, y + kh, pressed);
        c.drawPath(path, fill);
        edge(c, KEY_EDGE, Math.max(2, kh * .03f));
        label(c, s, x + kw / 2, y + kh / 2 + 1.5f, kh * .42f, 0xFF151517);
        label(c, s, x + kw / 2, y + kh / 2, kh * .42f, LABEL);
    }

    private void create(Canvas c, float w, float h, boolean pressed) {
        float s = Math.min(w / 2.2f, h / 2.2f), cx = w / 2, cy = h * .36f + (pressed ? 1.5f : 0);
        rect.set(cx - s * .7f, cy - s * .4f, cx + s * .7f, cy + s * .4f);
        keyFill(rect.top, rect.bottom, pressed);
        c.drawRoundRect(rect, s * .18f, s * .18f, fill);
        line.setColor(KEY_EDGE); line.setStrokeWidth(2);
        c.drawRoundRect(rect, s * .18f, s * .18f, line);
        label(c, "CREATE", cx, h * .36f + s * 1.05f, s * .5f, LABEL);
    }

    private void options(Canvas c, float w, float h, boolean pressed) {
        float s = Math.min(w / 2.2f, h / 2.2f), cx = w / 2, cy = h * .36f + (pressed ? 1.5f : 0);
        path.reset();
        path.moveTo(cx - s * .7f, cy - s * .45f);
        path.lineTo(cx + s * .75f, cy);
        path.lineTo(cx - s * .7f, cy + s * .45f);
        path.close();
        keyFill(cy - s * .45f, cy + s * .45f, pressed);
        c.drawPath(path, fill);
        edge(c, KEY_EDGE, 2);
        label(c, "OPTIONS", cx, h * .36f + s * 1.05f, s * .5f, LABEL);
    }

    private void touchpad(Canvas c, float w, float h, boolean pressed) {
        float pad = h * .08f;
        rect.set(pad, pad + (pressed ? 1.5f : 0), w - pad, h - pad);
        float rr = rect.height() * .35f;
        plainFill(pressed ? 0xC0B4B6BB : 0x999EA0A5);
        fill.setShadowLayer(3, 0, 2, 0x2E000000);
        c.drawRoundRect(rect, rr, rr, fill);
        line.setColor(0x9986888D); line.setStrokeWidth(2);
        c.drawRoundRect(rect, rr, rr, line);
        label(c, "TOUCHPAD", w / 2, rect.centerY(), Math.min(rect.height() * .30f, rect.width() * .11f), LABEL);
    }

    private void menu(Canvas c, float w, float h, boolean pressed) {
        float cx = w / 2, cy = h / 2, r = Math.min(w, h) / 2 * .8f;
        keyFill(cy - r, cy + r, pressed);
        c.drawCircle(cx, cy, r, fill);
        line.setColor(KEY_EDGE); line.setStrokeWidth(2);
        c.drawCircle(cx, cy, r, line);
        plainFill(0xFFC9CBD0);
        for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * r * .32f, cy, r * .09f, fill);
    }

    private void psHome(Canvas c, float w, float h, boolean pressed) {
        float cx = w / 2, cy = h / 2 + (pressed ? 1.5f : 0), r = Math.min(w, h) / 2 * .85f;
        keyFill(cy - r, cy + r, pressed);
        c.drawCircle(cx, cy, r, fill);
        line.setColor(KEY_EDGE); line.setStrokeWidth(2);
        c.drawCircle(cx, cy, r, line);
        label(c, "PS", cx, cy + 1.5f, r * .78f, 0xFF151517);
        label(c, "PS", cx, cy, r * .78f, LABEL);
    }

    // ---- Xbox

    private void letterKey(Canvas c, float w, float h, String letter, int color, boolean pressed) {
        float cx = w / 2, cy = h / 2 + (pressed ? 1.5f : 0), r = Math.min(w, h) / 2 * .9f;
        capFill(cx, cy, r, pressed);
        c.drawCircle(cx, cy, r, fill);
        line.setColor(0xFF141518); line.setStrokeWidth(Math.max(2, r * .04f));
        c.drawCircle(cx, cy, r, line);
        line.setColor(0x14FFFFFF);
        c.drawCircle(cx, cy, r * .93f, line);
        label(c, letter, cx, cy + r * .04f, r * .95f, color);
        if (pressed) {
            plainFill((color & 0x00FFFFFF) | 0x26000000);
            c.drawCircle(cx, cy, r, fill);
        }
    }

    /** One-piece cross on a faint round base; the pressed arm darkens and dips. */
    private void xdpad(Canvas c, float w, float h, int direction) {
        float cx = w / 2, cy = h / 2, r = Math.min(w, h) / 2 / 1.14f;
        plainFill(0x598F9196);
        c.drawCircle(cx, cy, r * 1.12f, fill);
        line.setColor(0x73FFFFFF); line.setStrokeWidth(Math.max(2, r * .015f));
        c.drawCircle(cx, cy, r * 1.12f, line);
        float a = r * .34f, k = a * .4f;
        path.reset();
        path.moveTo(cx - a, cy - r + k);
        path.quadTo(cx - a, cy - r, cx - a + k, cy - r); path.lineTo(cx + a - k, cy - r); path.quadTo(cx + a, cy - r, cx + a, cy - r + k);
        path.lineTo(cx + a, cy - a); path.lineTo(cx + r - k, cy - a); path.quadTo(cx + r, cy - a, cx + r, cy - a + k);
        path.lineTo(cx + r, cy + a - k); path.quadTo(cx + r, cy + a, cx + r - k, cy + a); path.lineTo(cx + a, cy + a);
        path.lineTo(cx + a, cy + r - k); path.quadTo(cx + a, cy + r, cx + a - k, cy + r); path.lineTo(cx - a + k, cy + r);
        path.quadTo(cx - a, cy + r, cx - a, cy + r - k); path.lineTo(cx - a, cy + a); path.lineTo(cx - r + k, cy + a);
        path.quadTo(cx - r, cy + a, cx - r, cy + a - k); path.lineTo(cx - r, cy - a + k); path.quadTo(cx - r, cy - a, cx - r + k, cy - a);
        path.lineTo(cx - a, cy - a); path.close();
        keyFill(cy - r, cy + r, false);
        c.drawPath(path, fill);
        edge(c, KEY_EDGE, Math.max(2, r * .02f));
        int[] bit = {DigitalPad.DIGITAL_PAD_DIRECTION_UP, DigitalPad.DIGITAL_PAD_DIRECTION_RIGHT,
                DigitalPad.DIGITAL_PAD_DIRECTION_DOWN, DigitalPad.DIGITAL_PAD_DIRECTION_LEFT};
        for (int i = 0; i < 4; i++) {
            boolean down = (direction & bit[i]) != 0;
            c.save();
            c.rotate(90 * i, cx, cy);
            if (down) {
                // the pressed arm lightens, like the PlayStation keys
                plainFill(0x33FFFFFF);
                c.drawRect(cx - a, cy - r, cx + a, cy - a, fill);
            }
            path.reset();
            path.moveTo(cx, cy - r * .84f);
            path.lineTo(cx - r * .11f, cy - r * .69f);
            path.lineTo(cx + r * .11f, cy - r * .69f);
            path.close();
            plainFill(down ? 0xFFD2D4D9 : 0xFF8A8C91);
            c.drawPath(path, fill);
            c.restore();
        }
    }

    private void trigger(Canvas c, boolean left, float w, float h, boolean pressed, String s) {
        float pad = h * .1f, x = pad, y = pad + (pressed ? 1.5f : 0), kw = w - 2 * pad, kh = h - 2 * pad, k = kh * .55f;
        path.reset();
        if (left) {
            path.moveTo(x, y + kh); path.lineTo(x, y + k); path.quadTo(x, y, x + k, y);
            path.lineTo(x + kw - k * .3f, y); path.quadTo(x + kw, y, x + kw, y + kh * .5f); path.lineTo(x + kw, y + kh);
        } else {
            path.moveTo(x + kw, y + kh); path.lineTo(x + kw, y + k); path.quadTo(x + kw, y, x + kw - k, y);
            path.lineTo(x + k * .3f, y); path.quadTo(x, y, x, y + kh * .5f); path.lineTo(x, y + kh);
        }
        path.close();
        keyFill(y, y + kh, pressed);
        c.drawPath(path, fill);
        edge(c, KEY_EDGE, Math.max(2, kh * .03f));
        label(c, s, x + kw / 2, y + kh * .55f + 1.5f, kh * .38f, 0xFF151517);
        label(c, s, x + kw / 2, y + kh * .55f, kh * .38f, LABEL);
    }

    private void xHome(Canvas c, float w, float h, boolean pressed) {
        float cx = w / 2, cy = h / 2 + (pressed ? 1.5f : 0), r = Math.min(w, h) / 2 * .78f;
        line.setColor(pressed ? 0xE6FFFFFF : 0x8CFFFFFF); line.setStrokeWidth(r * .12f);
        c.drawCircle(cx, cy, r * 1.18f, line);
        keyFill(cy - r, cy + r, pressed);
        c.drawCircle(cx, cy, r, fill);
        line.setColor(KEY_EDGE); line.setStrokeWidth(2);
        c.drawCircle(cx, cy, r, line);
        line.setColor(LABEL); line.setStrokeWidth(r * .13f);
        c.drawCircle(cx, cy, r * .5f, line);
    }

    /** View (two windows) or Menu (three lines): a small round key with its name under it. */
    private void smallKey(Canvas c, int shape, float w, float h, boolean pressed) {
        float cx = w / 2, cy = h * .38f + (pressed ? 1.5f : 0), r = w * .27f;
        keyFill(cy - r, cy + r, pressed);
        c.drawCircle(cx, cy, r, fill);
        line.setColor(KEY_EDGE); line.setStrokeWidth(2);
        c.drawCircle(cx, cy, r, line);
        line.setColor(0xFFC9CBD0); line.setStrokeWidth(r * .1f);
        if (shape == VIEW) {
            rect.set(cx - r * .45f, cy - r * .36f, cx + r * .07f, cy + r * .06f);
            c.drawRoundRect(rect, 3, 3, line);
            rect.set(cx - r * .15f, cy - r * .08f, cx + r * .37f, cy + r * .34f);
            plainFill(0xFF3B3C40);
            c.drawRoundRect(rect, 3, 3, fill);
            c.drawRoundRect(rect, 3, 3, line);
        } else {
            for (int i = -1; i <= 1; i++) c.drawLine(cx - r * .38f, cy + i * r * .26f, cx + r * .38f, cy + i * r * .26f, line);
        }
        label(c, shape == VIEW ? "VIEW" : "MENU", cx, h * .85f, w * .2f, LABEL);
    }

    private void share(Canvas c, float w, float h, boolean pressed) {
        float cx = w / 2, cy = h * .38f + (pressed ? 1.5f : 0), pw = w * .62f, ph = w * .38f;
        rect.set(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2);
        keyFill(rect.top, rect.bottom, pressed);
        c.drawRoundRect(rect, ph / 2, ph / 2, fill);
        line.setColor(KEY_EDGE); line.setStrokeWidth(2);
        c.drawRoundRect(rect, ph / 2, ph / 2, line);
        line.setColor(LABEL); line.setStrokeWidth(ph * .09f);
        c.drawLine(cx, cy - ph * .28f, cx, cy + ph * .2f, line);
        c.drawLine(cx - ph * .18f, cy - ph * .1f, cx, cy - ph * .28f, line);
        c.drawLine(cx + ph * .18f, cy - ph * .1f, cx, cy - ph * .28f, line);
        label(c, "SHARE", cx, h * .85f, w * .2f, LABEL);
    }

    // ---- stick: a faint well and a dark cap that follows the thumb a little

    void stick(Canvas c, float w, float h, float thumbX, float thumbY, float travel, boolean pressed) {
        float cx = w / 2, cy = h / 2, r = Math.min(w, h) / 2 / 1.9f;
        plainFill(0x598F9196);
        c.drawCircle(cx, cy, r * 1.25f, fill);
        line.setColor(0x73FFFFFF); line.setStrokeWidth(Math.max(2, r * .02f));
        c.drawCircle(cx, cy, r * 1.25f, line);
        float k = travel > 0 ? r * .45f / travel : 0;
        float x = cx + (thumbX - cx) * k, y = cy + (thumbY - cy) * k;
        capFill(x, y, r, pressed);
        c.drawCircle(x, y, r, fill);
        line.setColor(0xFF121315); line.setStrokeWidth(Math.max(3, r * .05f));
        c.drawCircle(x, y, r, line);
        line.setColor(0x40000000); line.setStrokeWidth(r * .05f);
        c.drawCircle(x, y, r * .8f, line);
    }
}
