/**
 * Created by Karim Mreisi.
 */

package com.limelight.binding.input.virtual_controller;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.DisplayMetrics;

import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.preferences.PreferenceConfiguration;

import org.json.JSONException;
import org.json.JSONObject;

public class VirtualControllerConfigurationLoader {
    public static final String OSC_PREFERENCE = "OSC";

    private static int getPercent(
            int percent,
            int total) {
        return (int) (((float) total / (float) 100) * (float) percent);
    }

    // The default controls are specified using a grid of 128*72 cells at 16:9
    private static int screenScale(int units, int height) {
        return (int) (((float) height / (float) 72) * (float) units);
    }

    private static DigitalPad createDigitalPad(
            final VirtualController controller,
            final Context context) {

        DigitalPad digitalPad = new DigitalPad(controller, context);
        digitalPad.addDigitalPadListener(new DigitalPad.DigitalPadListener() {
            @Override
            public void onDirectionChange(int direction) {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();

                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_LEFT) != 0) {
                    inputContext.inputMap |= ControllerPacket.LEFT_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.LEFT_FLAG;
                }
                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_RIGHT) != 0) {
                    inputContext.inputMap |= ControllerPacket.RIGHT_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.RIGHT_FLAG;
                }
                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_UP) != 0) {
                    inputContext.inputMap |= ControllerPacket.UP_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.UP_FLAG;
                }
                if ((direction & DigitalPad.DIGITAL_PAD_DIRECTION_DOWN) != 0) {
                    inputContext.inputMap |= ControllerPacket.DOWN_FLAG;
                }
                else {
                    inputContext.inputMap &= ~ControllerPacket.DOWN_FLAG;
                }

                controller.sendControllerInputContext();
            }
        });

        return digitalPad;
    }

    private static DigitalButton createDigitalButton(
            final int elementId,
            final int keyShort,
            final int keyLong,
            final int layer,
            final String text,
            final int icon,
            final VirtualController controller,
            final Context context) {
        DigitalButton button = new DigitalButton(controller, elementId, layer, context);
        button.setText(text);
        button.setIcon(icon);

        button.addDigitalButtonListener(new DigitalButton.DigitalButtonListener() {
            @Override
            public void onClick() {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap |= keyShort;

                controller.sendControllerInputContext();
            }

            @Override
            public void onLongClick() {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap |= keyLong;

                controller.sendControllerInputContext();
            }

            @Override
            public void onRelease() {
                VirtualController.ControllerInputContext inputContext =
                        controller.getControllerInputContext();
                inputContext.inputMap &= ~keyShort;
                inputContext.inputMap &= ~keyLong;

                controller.sendControllerInputContext();
            }
        });

        return button;
    }

    private static DigitalButton createLeftTrigger(
            final int layer,
            final String text,
            final int icon,
            final VirtualController controller,
            final Context context) {
        LeftTrigger button = new LeftTrigger(controller, layer, context);
        button.setText(text);
        button.setIcon(icon);
        return button;
    }

    private static DigitalButton createRightTrigger(
            final int layer,
            final String text,
            final int icon,
            final VirtualController controller,
            final Context context) {
        RightTrigger button = new RightTrigger(controller, layer, context);
        button.setText(text);
        button.setIcon(icon);
        return button;
    }

    private static AnalogStick createLeftStick(
            final VirtualController controller,
            final Context context) {
        return new LeftAnalogStick(controller, context);
    }

    private static AnalogStick createRightStick(
            final VirtualController controller,
            final Context context) {
        return new RightAnalogStick(controller, context);
    }


    private static final int TRIGGER_L_BASE_X = 1;
    private static final int TRIGGER_R_BASE_X = 92;
    private static final int TRIGGER_DISTANCE = 23;
    private static final int TRIGGER_BASE_Y = 31;
    private static final int TRIGGER_WIDTH = 12;
    private static final int TRIGGER_HEIGHT = 9;

    // Face buttons are defined based on the Y button (button number 9)
    private static final int BUTTON_BASE_X = 106;
    private static final int BUTTON_BASE_Y = 1;
    private static final int BUTTON_SIZE = 10;

    private static final int DPAD_BASE_X = 4;
    private static final int DPAD_BASE_Y = 41;
    private static final int DPAD_SIZE = 30;

    private static final int ANALOG_L_BASE_X = 6;
    private static final int ANALOG_L_BASE_Y = 4;
    private static final int ANALOG_R_BASE_X = 98;
    private static final int ANALOG_R_BASE_Y = 42;
    private static final int ANALOG_SIZE = 26;

    private static final int L3_R3_BASE_Y = 60;

    private static final int START_X = 83;
    private static final int BACK_X = 34;
    private static final int START_BACK_Y = 64;
    private static final int START_BACK_WIDTH = 12;
    private static final int START_BACK_HEIGHT = 7;

    // Make the Guide Menu be in the center of START and BACK menu
    private static final int GUIDE_X = START_X-BACK_X;
    private static final int GUIDE_Y = START_BACK_Y;

    public static void createDefaultLayout(final VirtualController controller, final Context context) {

        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);

        // Displace controls on the right by this amount of pixels to account for different aspect ratios
        int rightDisplacement = screen.widthPixels - screen.heightPixels * 16 / 9;

        int height = screen.heightPixels;

        // NOTE: Some of these getPercent() expressions seem like they can be combined
        // into a single call. Due to floating point rounding, this isn't actually possible.

        if (!config.onlyL3R3)
        {
            controller.addElement(createDigitalPad(controller, context),
                    screenScale(DPAD_BASE_X, height),
                    screenScale(DPAD_BASE_Y, height),
                    screenScale(DPAD_SIZE, height),
                    screenScale(DPAD_SIZE, height)
            );

            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_A,
                    !config.flipFaceButtons ? ControllerPacket.A_FLAG : ControllerPacket.B_FLAG, 0, 1,
                    !config.flipFaceButtons ? "A" : "B", -1, controller, context),
                    screenScale(BUTTON_BASE_X, height) + rightDisplacement,
                    screenScale(BUTTON_BASE_Y + 2 * BUTTON_SIZE, height),
                    screenScale(BUTTON_SIZE, height),
                    screenScale(BUTTON_SIZE, height)
            );

            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_B,
                    config.flipFaceButtons ? ControllerPacket.A_FLAG : ControllerPacket.B_FLAG, 0, 1,
                    config.flipFaceButtons ? "A" : "B", -1, controller, context),
                    screenScale(BUTTON_BASE_X + BUTTON_SIZE, height) + rightDisplacement,
                    screenScale(BUTTON_BASE_Y + BUTTON_SIZE, height),
                    screenScale(BUTTON_SIZE, height),
                    screenScale(BUTTON_SIZE, height)
            );

            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_X,
                    !config.flipFaceButtons ? ControllerPacket.X_FLAG : ControllerPacket.Y_FLAG, 0, 1,
                    !config.flipFaceButtons ? "X" : "Y", -1, controller, context),
                    screenScale(BUTTON_BASE_X - BUTTON_SIZE, height) + rightDisplacement,
                    screenScale(BUTTON_BASE_Y + BUTTON_SIZE, height),
                    screenScale(BUTTON_SIZE, height),
                    screenScale(BUTTON_SIZE, height)
            );

            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_Y,
                    config.flipFaceButtons ? ControllerPacket.X_FLAG : ControllerPacket.Y_FLAG, 0, 1,
                    config.flipFaceButtons ? "X" : "Y", -1, controller, context),
                    screenScale(BUTTON_BASE_X, height) + rightDisplacement,
                    screenScale(BUTTON_BASE_Y, height),
                    screenScale(BUTTON_SIZE, height),
                    screenScale(BUTTON_SIZE, height)
            );

            controller.addElement(createLeftTrigger(
                    1, "LT", -1, controller, context),
                    screenScale(TRIGGER_L_BASE_X, height),
                    screenScale(TRIGGER_BASE_Y, height),
                    screenScale(TRIGGER_WIDTH, height),
                    screenScale(TRIGGER_HEIGHT, height)
            );

            controller.addElement(createRightTrigger(
                    1, "RT", -1, controller, context),
                    screenScale(TRIGGER_R_BASE_X + TRIGGER_DISTANCE, height) + rightDisplacement,
                    screenScale(TRIGGER_BASE_Y, height),
                    screenScale(TRIGGER_WIDTH, height),
                    screenScale(TRIGGER_HEIGHT, height)
            );

            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_LB,
                    ControllerPacket.LB_FLAG, 0, 1, "LB", -1, controller, context),
                    screenScale(TRIGGER_L_BASE_X + TRIGGER_DISTANCE, height),
                    screenScale(TRIGGER_BASE_Y, height),
                    screenScale(TRIGGER_WIDTH, height),
                    screenScale(TRIGGER_HEIGHT, height)
            );

            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_RB,
                    ControllerPacket.RB_FLAG, 0, 1, "RB", -1, controller, context),
                    screenScale(TRIGGER_R_BASE_X, height) + rightDisplacement,
                    screenScale(TRIGGER_BASE_Y, height),
                    screenScale(TRIGGER_WIDTH, height),
                    screenScale(TRIGGER_HEIGHT, height)
            );

            controller.addElement(createLeftStick(controller, context),
                    screenScale(ANALOG_L_BASE_X, height),
                    screenScale(ANALOG_L_BASE_Y, height),
                    screenScale(ANALOG_SIZE, height),
                    screenScale(ANALOG_SIZE, height)
            );

            controller.addElement(createRightStick(controller, context),
                    screenScale(ANALOG_R_BASE_X, height) + rightDisplacement,
                    screenScale(ANALOG_R_BASE_Y, height),
                    screenScale(ANALOG_SIZE, height),
                    screenScale(ANALOG_SIZE, height)
            );

            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_BACK,
                    ControllerPacket.BACK_FLAG, 0, 2, "BACK", -1, controller, context),
                    screenScale(BACK_X, height),
                    screenScale(START_BACK_Y, height),
                    screenScale(START_BACK_WIDTH, height),
                    screenScale(START_BACK_HEIGHT, height)
            );

            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_START,
                    ControllerPacket.PLAY_FLAG, 0, 3, "START", -1, controller, context),
                    screenScale(START_X, height) + rightDisplacement,
                    screenScale(START_BACK_Y, height),
                    screenScale(START_BACK_WIDTH, height),
                    screenScale(START_BACK_HEIGHT, height)
            );
        }
        else {
            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_LSB,
                    ControllerPacket.LS_CLK_FLAG, 0, 1, "L3", -1, controller, context),
                    screenScale(TRIGGER_L_BASE_X, height),
                    screenScale(L3_R3_BASE_Y, height),
                    screenScale(TRIGGER_WIDTH, height),
                    screenScale(TRIGGER_HEIGHT, height)
            );

            controller.addElement(createDigitalButton(
                    VirtualControllerElement.EID_RSB,
                    ControllerPacket.RS_CLK_FLAG, 0, 1, "R3", -1, controller, context),
                    screenScale(TRIGGER_R_BASE_X + TRIGGER_DISTANCE, height) + rightDisplacement,
                    screenScale(L3_R3_BASE_Y, height),
                    screenScale(TRIGGER_WIDTH, height),
                    screenScale(TRIGGER_HEIGHT, height)
            );
        }

        if(config.showGuideButton){
            controller.addElement(createDigitalButton(VirtualControllerElement.EID_GDB,
                            ControllerPacket.SPECIAL_BUTTON_FLAG, 0, 1, "GUIDE", -1, controller, context),
                    screenScale(GUIDE_X, height)+ rightDisplacement,
                    screenScale(GUIDE_Y, height),
                    screenScale(START_BACK_WIDTH, height),
                    screenScale(START_BACK_HEIGHT, height)
            );
        }

        controller.setOpacity(config.oscOpacity);
    }

    // ---- MoonCast PlayStation skin: Delta's landscape positions, as fractions of the screen

    private static void place(VirtualController controller, VirtualControllerElement element, float cx, float cy, float w, float h) {
        controller.addElement(element, Math.round(cx - w / 2), Math.round(cy - h / 2), Math.round(w), Math.round(h));
    }

    private static DigitalButton skinned(DigitalButton button, int shape) {
        button.setSkinShape(shape);
        return button;
    }

    public static void createPlayStationLayout(final VirtualController controller, final Context context) {
        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(context);
        float W = Math.max(screen.widthPixels, screen.heightPixels), H = Math.min(screen.widthPixels, screen.heightPixels);
        if (screen.heightPixels > screen.widthPixels) {
            // upright: same keys, over the lower part of the screen
            W = screen.widthPixels;
            H = screen.heightPixels;
        }
        float u = Math.min(W, H);

        // shoulders stacked in the top corners: L2 over L1, R2 over R1 (the drawn key sits inside a small margin)
        float sh = u * .096f * 1.1f, sw = W * .12f * 1.1f, gap = H * .017f + sh * .05f, m = sh * .125f;
        float top = H * .045f + sh / 2, low = top + sh + gap, lx = W * .024f + sw / 2, rx = W * .976f - sw / 2;
        place(controller, skinned(createLeftTrigger(1, "L2", -1, controller, context), PadSkin.SHOULDER_L), lx, top, sw + 2 * m, sh + 2 * m);
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_LB, ControllerPacket.LB_FLAG, 0, 1, "L1", -1, controller, context), PadSkin.SHOULDER_L),
                lx, low, sw + 2 * m, sh + 2 * m);
        place(controller, skinned(createRightTrigger(1, "R2", -1, controller, context), PadSkin.SHOULDER_R), rx, top, sw + 2 * m, sh + 2 * m);
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_RB, ControllerPacket.RB_FLAG, 0, 1, "R1", -1, controller, context), PadSkin.SHOULDER_R),
                rx, low, sw + 2 * m, sh + 2 * m);

        // MoonCast's menu key, top middle: opens the Quick menu
        DigitalButton menu = skinned(createDigitalButton(VirtualControllerElement.EID_MENU, 0, 0, 4, "", -1, controller, context), PadSkin.MENU);
        menu.addDigitalButtonListener(new DigitalButton.DigitalButtonListener() {
            @Override public void onClick() {}
            @Override public void onLongClick() {}
            @Override public void onRelease() {
                if (context instanceof com.limelight.Game) ((com.limelight.Game) context).openQuickMenu();
            }
        });
        place(controller, menu, W * .5f, H * .085f, u * .075f, u * .075f);

        // d-pad, left; 10% bigger than Delta's so a thumb lands on one direction
        float dr = u * .118f * 1.1f;
        place(controller, createDigitalPad(controller, context), W * .125f, H * .60f, 2 * 1.36f * dr, 2 * 1.36f * dr);

        // shape buttons on their plate, right: 10% bigger and 5% further apart. Triangle up, circle right, cross down, square left
        float fr = u * .107f, fx = W * .875f, fy = H * .60f, d = fr * .98f * 1.05f, br = fr * .46f * 1.1f, b = 2 * br / .9f;
        float plate = 2 * (d + br * 1.12f) / .97f;
        place(controller, new PadPlate(controller, context, VirtualControllerElement.EID_PLATE), fx, fy, plate, plate);
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_Y, ControllerPacket.Y_FLAG, 0, 1, "Y", -1, controller, context), PadSkin.TRIANGLE), fx, fy - d, b, b);
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_B, ControllerPacket.B_FLAG, 0, 1, "B", -1, controller, context), PadSkin.CIRCLE), fx + d, fy, b, b);
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_A, ControllerPacket.A_FLAG, 0, 1, "A", -1, controller, context), PadSkin.CROSS), fx, fy + d, b, b);
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_X, ControllerPacket.X_FLAG, 0, 1, "X", -1, controller, context), PadSkin.SQUARE), fx - d, fy, b, b);

        // sticks low, close in under the clusters; the touch area is wider than the drawn well. Double tap = L3 / R3
        float st = 2 * 1.9f * u * .075f;
        place(controller, createLeftStick(controller, context), W * .28f, H * .82f, st, st);
        place(controller, createRightStick(controller, context), W * .72f, H * .82f, st, st);

        // PS button between the sticks, above the touchpad, as on the DualSense
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_PSHOME, ControllerPacket.SPECIAL_BUTTON_FLAG, 0, 2, "PS", -1, controller, context), PadSkin.PSHOME),
                W * .5f, H * .79f, u * .06f, u * .06f);

        // Create, touchpad, Options along the bottom middle
        float k = u * .03f * 2.6f;
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_BACK, ControllerPacket.BACK_FLAG, 0, 2, "CREATE", -1, controller, context), PadSkin.CREATE),
                W * .405f, H * .875f + k * .14f, k, k);
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_TOUCHPAD, ControllerPacket.TOUCHPAD_FLAG, 0, 2, "TOUCHPAD", -1, controller, context), PadSkin.TOUCHPAD),
                W * .5f, H * .90f, W * .095f + u * .01f, u * .062f / .84f);
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_START, ControllerPacket.PLAY_FLAG, 0, 3, "OPTIONS", -1, controller, context), PadSkin.OPTIONS),
                W * .595f, H * .875f + k * .14f, k, k);

        controller.setOpacity(PadSkin.opacity(context));
    }

    // ---- MoonCast Xbox skin: the real Xbox arrangement (left stick up, d-pad down beside it), same sizes as the PlayStation skin

    public static void createXboxLayout(final VirtualController controller, final Context context) {
        DisplayMetrics screen = context.getResources().getDisplayMetrics();
        float W = Math.max(screen.widthPixels, screen.heightPixels), H = Math.min(screen.widthPixels, screen.heightPixels);
        if (screen.heightPixels > screen.widthPixels) {
            W = screen.widthPixels;
            H = screen.heightPixels;
        }
        float u = Math.min(W, H);

        // LT over LB, RT over RB
        float sh = u * .096f * 1.1f, sw = W * .12f * 1.1f, gap = H * .017f + sh * .05f, m = sh * .125f;
        float top = H * .045f + sh / 2, low = top + sh + gap, lx = W * .024f + sw / 2, rx = W * .976f - sw / 2;
        place(controller, skinned(createLeftTrigger(1, "LT", -1, controller, context), PadSkin.TRIGGER_L), lx, top, sw + 2 * m, sh + 2 * m);
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_LB, ControllerPacket.LB_FLAG, 0, 1, "LB", -1, controller, context), PadSkin.SHOULDER_L),
                lx, low, sw + 2 * m, sh + 2 * m);
        place(controller, skinned(createRightTrigger(1, "RT", -1, controller, context), PadSkin.TRIGGER_R), rx, top, sw + 2 * m, sh + 2 * m);
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_RB, ControllerPacket.RB_FLAG, 0, 1, "RB", -1, controller, context), PadSkin.SHOULDER_R),
                rx, low, sw + 2 * m, sh + 2 * m);

        DigitalButton menu = skinned(createDigitalButton(VirtualControllerElement.EID_MENU, 0, 0, 4, "", -1, controller, context), PadSkin.MENU);
        menu.addDigitalButtonListener(new DigitalButton.DigitalButtonListener() {
            @Override public void onClick() {}
            @Override public void onLongClick() {}
            @Override public void onRelease() {
                if (context instanceof com.limelight.Game) ((com.limelight.Game) context).openQuickMenu();
            }
        });
        place(controller, menu, W * .5f, H * .085f, u * .075f, u * .075f);

        // left stick up on the left (a little bigger: the thumb rests there), d-pad lower, beside it
        float ls = 2 * 1.9f * u * .08f;
        place(controller, createLeftStick(controller, context), W * .125f, H * .60f, ls, ls);
        float dr = u * .105f;
        place(controller, createDigitalPad(controller, context), W * .285f, H * .78f, 2 * 1.14f * dr, 2 * 1.14f * dr);

        // A B X Y on their plate, right: Y up, B right, A down, X left
        float fr = u * .107f, fx = W * .875f, fy = H * .60f, d = fr * .98f * 1.05f, br = fr * .46f * 1.1f, b = 2 * br / .9f;
        float plate = 2 * (d + br * 1.12f) / .97f;
        place(controller, new PadPlate(controller, context, VirtualControllerElement.EID_PLATE), fx, fy, plate, plate);
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_Y, ControllerPacket.Y_FLAG, 0, 1, "Y", -1, controller, context), PadSkin.XY), fx, fy - d, b, b);
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_B, ControllerPacket.B_FLAG, 0, 1, "B", -1, controller, context), PadSkin.XB), fx + d, fy, b, b);
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_A, ControllerPacket.A_FLAG, 0, 1, "A", -1, controller, context), PadSkin.XA), fx, fy + d, b, b);
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_X, ControllerPacket.X_FLAG, 0, 1, "X", -1, controller, context), PadSkin.XX), fx - d, fy, b, b);

        float st = 2 * 1.9f * u * .075f;
        place(controller, createRightStick(controller, context), W * .72f, H * .82f, st, st);

        // Xbox (guide) button between the sticks; View, Share, Menu along the bottom middle
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_PSHOME, ControllerPacket.SPECIAL_BUTTON_FLAG, 0, 2, "", -1, controller, context), PadSkin.XHOME),
                W * .5f, H * .79f, u * .065f, u * .065f);
        float k = u * .09f, ky = H * .875f + u * .012f;
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_BACK, ControllerPacket.BACK_FLAG, 0, 2, "VIEW", -1, controller, context), PadSkin.VIEW), W * .405f, ky, k, k);
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_SHARE, ControllerPacket.MISC_FLAG, 0, 2, "SHARE", -1, controller, context), PadSkin.SHARE), W * .5f, ky, k, k);
        place(controller, skinned(createDigitalButton(VirtualControllerElement.EID_START, ControllerPacket.PLAY_FLAG, 0, 3, "MENU", -1, controller, context), PadSkin.XMENU), W * .595f, ky, k, k);

        controller.setOpacity(PadSkin.opacity(context));
    }

    public static void saveProfile(final VirtualController controller,
                                   final Context context) {
        SharedPreferences.Editor prefEditor = context.getSharedPreferences(OSC_PREFERENCE, Activity.MODE_PRIVATE).edit();

        for (VirtualControllerElement element : controller.getElements()) {
            String prefKey = ""+element.elementId;
            try {
                prefEditor.putString(prefKey, element.getConfiguration().toString());
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        prefEditor.apply();
    }

    public static void loadFromPreferences(final VirtualController controller, final Context context) {
        SharedPreferences pref = context.getSharedPreferences(OSC_PREFERENCE, Activity.MODE_PRIVATE);

        for (VirtualControllerElement element : controller.getElements()) {
            String prefKey = ""+element.elementId;

            String jsonConfig = pref.getString(prefKey, null);
            if (jsonConfig != null) {
                try {
                    element.loadConfiguration(new JSONObject(jsonConfig));
                } catch (JSONException e) {
                    e.printStackTrace();

                    // Remove the corrupt element from the preferences
                    pref.edit().remove(prefKey).apply();
                }
            }
        }
    }
}
