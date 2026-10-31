package com.limelight.preferences;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.view.Display;
import android.view.WindowManager;

import com.limelight.nvstream.jni.MoonBridge;

public class PreferenceConfiguration {
    public enum FormatOption {
        AUTO,
        FORCE_AV1,
        FORCE_HEVC,
        FORCE_H264,
    };

    public enum AnalogStickForScrolling {
        NONE,
        RIGHT,
        LEFT
    }

    private static final String LEGACY_RES_FPS_PREF_STRING = "list_resolution_fps";
    private static final String LEGACY_ENABLE_51_SURROUND_PREF_STRING = "checkbox_51_surround";

    static final String RESOLUTION_PREF_STRING = "list_resolution";
    static final String FPS_PREF_STRING = "list_fps";
    static final String BITRATE_PREF_STRING = "seekbar_bitrate_kbps";
    private static final String BITRATE_PREF_OLD_STRING = "seekbar_bitrate";
    private static final String STRETCH_PREF_STRING = "checkbox_stretch_video";
    // MoonCast, from Artemis: ultra low latency decoding, picture scale mode (fit / fill / stretch) and zoom
    private static final String ULTRA_LOW_LATENCY_PREF_STRING = "checkbox_ultra_low_latency";
    private static final String SCALE_MODE_PREF_STRING = "list_video_scale_mode";
    private static final String ZOOM_PREF_STRING = "seekbar_video_zoom";
    private static final String SOPS_PREF_STRING = "checkbox_enable_sops";
    private static final String DISABLE_TOASTS_PREF_STRING = "checkbox_disable_warnings";
    private static final String HOST_AUDIO_PREF_STRING = "checkbox_host_audio";
    private static final String DEADZONE_PREF_STRING = "seekbar_deadzone";
    private static final String OSC_OPACITY_PREF_STRING = "seekbar_osc_opacity";
    private static final String LANGUAGE_PREF_STRING = "list_languages";
    private static final String SMALL_ICONS_PREF_STRING = "checkbox_small_icon_mode";
    private static final String MULTI_CONTROLLER_PREF_STRING = "checkbox_multi_controller";
    static final String AUDIO_CONFIG_PREF_STRING = "list_audio_config";
    private static final String USB_DRIVER_PREF_SRING = "checkbox_usb_driver";
    private static final String VIDEO_FORMAT_PREF_STRING = "video_format";
    private static final String ONSCREEN_CONTROLLER_PREF_STRING = "checkbox_show_onscreen_controls";
    private static final String ONLY_L3_R3_PREF_STRING = "checkbox_only_show_L3R3";
    private static final String SHOW_GUIDE_BUTTON_PREF_STRING = "checkbox_show_guide_button";
    private static final String LEGACY_DISABLE_FRAME_DROP_PREF_STRING = "checkbox_disable_frame_drop";
    private static final String ENABLE_HDR_PREF_STRING = "checkbox_enable_hdr";
    private static final String ENABLE_PIP_PREF_STRING = "checkbox_enable_pip";
    private static final String ENABLE_PERF_OVERLAY_STRING = "checkbox_enable_perf_overlay";
    private static final String BIND_ALL_USB_STRING = "checkbox_usb_bind_all";
    private static final String MOUSE_EMULATION_STRING = "checkbox_mouse_emulation";
    private static final String ANALOG_SCROLLING_PREF_STRING = "analog_scrolling";
    private static final String MOUSE_NAV_BUTTONS_STRING = "checkbox_mouse_nav_buttons";
    static final String UNLOCK_FPS_STRING = "checkbox_unlock_fps";
    private static final String VIBRATE_OSC_PREF_STRING = "checkbox_vibrate_osc";
    private static final String VIBRATE_FALLBACK_PREF_STRING = "checkbox_vibrate_fallback";
    private static final String VIBRATE_FALLBACK_STRENGTH_PREF_STRING = "seekbar_vibrate_fallback_strength";
    private static final String FLIP_FACE_BUTTONS_PREF_STRING = "checkbox_flip_face_buttons";
    private static final String TOUCHSCREEN_TRACKPAD_PREF_STRING = "checkbox_touchscreen_trackpad";
    private static final String LATENCY_TOAST_PREF_STRING = "checkbox_enable_post_stream_toast";
    private static final String FRAME_PACING_PREF_STRING = "frame_pacing";
    private static final String ABSOLUTE_MOUSE_MODE_PREF_STRING = "checkbox_absolute_mouse_mode";
    private static final String ENABLE_AUDIO_FX_PREF_STRING = "checkbox_enable_audiofx";
    private static final String REDUCE_REFRESH_RATE_PREF_STRING = "checkbox_reduce_refresh_rate";
    private static final String FULL_RANGE_PREF_STRING = "checkbox_full_range";
    private static final String GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING = "checkbox_gamepad_touchpad_as_mouse";
    private static final String GAMEPAD_MOTION_SENSORS_PREF_STRING = "checkbox_gamepad_motion_sensors";
    private static final String GAMEPAD_MOTION_FALLBACK_PREF_STRING = "checkbox_gamepad_motion_fallback";

    static final String DEFAULT_RESOLUTION = "1280x720";
    static final String DEFAULT_FPS = "60";
    private static final boolean DEFAULT_STRETCH = false;
    private static final boolean DEFAULT_SOPS = true;
    private static final boolean DEFAULT_DISABLE_TOASTS = false;
    private static final boolean DEFAULT_HOST_AUDIO = false;
    private static final int DEFAULT_DEADZONE = 7;
    private static final int DEFAULT_OPACITY = 90;
    public static final String DEFAULT_LANGUAGE = "default";
    private static final boolean DEFAULT_MULTI_CONTROLLER = true;
    private static final boolean DEFAULT_USB_DRIVER = true;
    private static final String DEFAULT_VIDEO_FORMAT = "auto";

    private static final boolean ONSCREEN_CONTROLLER_DEFAULT = false;
    private static final boolean ONLY_L3_R3_DEFAULT = false;
    private static final boolean SHOW_GUIDE_BUTTON_DEFAULT = true;
    private static final boolean DEFAULT_ENABLE_HDR = false;
    private static final boolean DEFAULT_ENABLE_PIP = false;
    private static final boolean DEFAULT_ENABLE_PERF_OVERLAY = false;
    private static final boolean DEFAULT_BIND_ALL_USB = false;
    private static final boolean DEFAULT_MOUSE_EMULATION = true;
    private static final String DEFAULT_ANALOG_STICK_FOR_SCROLLING = "right";
    private static final boolean DEFAULT_MOUSE_NAV_BUTTONS = false;
    private static final boolean DEFAULT_UNLOCK_FPS = false;
    private static final boolean DEFAULT_VIBRATE_OSC = true;
    private static final boolean DEFAULT_VIBRATE_FALLBACK = false;
    private static final int DEFAULT_VIBRATE_FALLBACK_STRENGTH = 100;
    private static final boolean DEFAULT_FLIP_FACE_BUTTONS = false;
    private static final boolean DEFAULT_TOUCHSCREEN_TRACKPAD = true;
    private static final String DEFAULT_AUDIO_CONFIG = "2"; // Stereo
    private static final boolean DEFAULT_LATENCY_TOAST = false;
    private static final String DEFAULT_FRAME_PACING = "latency";
    private static final boolean DEFAULT_ABSOLUTE_MOUSE_MODE = false;
    private static final boolean DEFAULT_ENABLE_AUDIO_FX = false;
    private static final boolean DEFAULT_REDUCE_REFRESH_RATE = false;
    private static final boolean DEFAULT_FULL_RANGE = false;
    private static final boolean DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE = false;
    private static final boolean DEFAULT_GAMEPAD_MOTION_SENSORS = true;
    private static final boolean DEFAULT_GAMEPAD_MOTION_FALLBACK = false;

    public static final int FRAME_PACING_MIN_LATENCY = 0;
    public static final int FRAME_PACING_BALANCED = 1;
    public static final int FRAME_PACING_CAP_FPS = 2;
    public static final int FRAME_PACING_MAX_SMOOTHNESS = 3;

    public static final String RES_360P = "640x360";
    public static final String RES_480P = "854x480";
    public static final String RES_720P = "1280x720";
    public static final String RES_1080P = "1920x1080";
    public static final String RES_1440P = "2560x1440";
    public static final String RES_4K = "3840x2160";
    public static final String RES_NATIVE = "Native";

    public int width, height, fps;
    public int bitrate;
    public FormatOption videoFormat;
    public int deadzonePercentage;
    public int oscOpacity;
    public boolean stretchVideo, enableSops, playHostAudio, disableWarnings;
    public boolean ultraLowLatency, fillVideo;
    public int videoZoomPercent;
    public String language;
    public boolean smallIconMode, multiController, usbDriver, flipFaceButtons;
    public boolean onscreenController;
    public boolean onlyL3R3;
    public boolean showGuideButton;
    public boolean enableHdr;
    public boolean enablePip;
    public boolean enablePerfOverlay;
    public boolean enableLatencyToast;
    public boolean bindAllUsb;
    public boolean mouseEmulation;
    public AnalogStickForScrolling analogStickForScrolling;
    public boolean mouseNavButtons;
    public boolean unlockFps;
    public boolean vibrateOsc;
    public boolean vibrateFallbackToDevice;
    public int vibrateFallbackToDeviceStrength;
    public boolean touchscreenTrackpad;
    public MoonBridge.AudioConfiguration audioConfiguration;
    public int framePacing;
    public boolean absoluteMouseMode;
    public boolean enableAudioFx;
    public boolean reduceRefreshRate;
    public boolean fullRange;
    public boolean gamepadMotionSensors;
    public boolean gamepadTouchpadAsMouse;
    public boolean gamepadMotionSensorsFallbackToDevice;

    public static boolean isNativeResolution(int width, int height) {
        // It's not a native resolution if it matches an existing resolution option
        if (width == 640 && height == 360) {
            return false;
        }
        else if (width == 854 && height == 480) {
            return false;
        }
        else if (width == 1280 && height == 720) {
            return false;
        }
        else if (width == 1920 && height == 1080) {
            return false;
        }
        else if (width == 2560 && height == 1440) {
            return false;
        }
        else if (width == 3840 && height == 2160) {
            return false;
        }

        return true;
    }

    // If we have a screen that has semi-square dimensions, we may want to change our behavior
    // to allow any orientation and vertical+horizontal resolutions.
    public static boolean isSquarishScreen(int width, int height) {
        float longDim = Math.max(width, height);
        float shortDim = Math.min(width, height);

        // We just put the arbitrary cutoff for a square-ish screen at 1.3
        return longDim / shortDim < 1.3f;
    }

    public static boolean isSquarishScreen(Display display) {
        int width, height;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            width = display.getMode().getPhysicalWidth();
            height = display.getMode().getPhysicalHeight();
        }
        else {
            width = display.getWidth();
            height = display.getHeight();
        }

        return isSquarishScreen(width, height);
    }

    private static String convertFromLegacyResolutionString(String resString) {
        if (resString.equalsIgnoreCase("360p")) {
            return RES_360P;
        }
        else if (resString.equalsIgnoreCase("480p")) {
            return RES_480P;
        }
        else if (resString.equalsIgnoreCase("720p")) {
            return RES_720P;
        }
        else if (resString.equalsIgnoreCase("1080p")) {
            return RES_1080P;
        }
        else if (resString.equalsIgnoreCase("1440p")) {
            return RES_1440P;
        }
        else if (resString.equalsIgnoreCase("4K")) {
            return RES_4K;
        }
        else {
            // Should be unreachable
            return RES_720P;
        }
    }

    private static int getWidthFromResolutionString(String resString) {
        return Integer.parseInt(resString.split("x")[0]);
    }

    private static int getHeightFromResolutionString(String resString) {
        return Integer.parseInt(resString.split("x")[1]);
    }

    private static String getResolutionString(int width, int height) {
        switch (height) {
            case 360:
                return RES_360P;
            case 480:
                return RES_480P;
            default:
            case 720:
                return RES_720P;
            case 1080:
                return RES_1080P;
            case 1440:
                return RES_1440P;
            case 2160:
                return RES_4K;
        }
    }

    public static int getDefaultBitrate(String resString, String fpsString) {
        int width = getWidthFromResolutionString(resString);
        int height = getHeightFromResolutionString(resString);
        int fps = Integer.parseInt(fpsString);

        // This logic is shamelessly stolen from Moonlight Qt:
        // https://github.com/moonlight-stream/moonlight-qt/blob/master/app/settings/streamingpreferences.cpp

        // Don't scale bitrate linearly beyond 60 FPS. It's definitely not a linear
        // bitrate increase for frame rate once we get to values that high.
        double frameRateFactor = (fps <= 60 ? fps : (Math.sqrt(fps / 60.f) * 60.f)) / 30.f;

        // TODO: Collect some empirical data to see if these defaults make sense.
        // We're just using the values that the Shield used, as we have for years.
        int[] pixelVals = {
            640 * 360,
            854 * 480,
            1280 * 720,
            1920 * 1080,
            2560 * 1440,
            3840 * 2160,
            -1,
        };
        int[] factorVals = {
            1,
            2,
            5,
            10,
            20,
            40,
            -1
        };

        // Calculate the resolution factor by linear interpolation of the resolution table
        float resolutionFactor;
        int pixels = width * height;
        for (int i = 0; ; i++) {
            if (pixels == pixelVals[i]) {
                // We can bail immediately for exact matches
                resolutionFactor = factorVals[i];
                break;
            }
            else if (pixels < pixelVals[i]) {
                if (i == 0) {
                    // Never go below the lowest resolution entry
                    resolutionFactor = factorVals[i];
                }
                else {
                    // Interpolate between the entry greater than the chosen resolution (i) and the entry less than the chosen resolution (i-1)
                    resolutionFactor = ((float)(pixels - pixelVals[i-1]) / (pixelVals[i] - pixelVals[i-1])) * (factorVals[i] - factorVals[i-1]) + factorVals[i-1];
                }
                break;
            }
            else if (pixelVals[i] == -1) {
                // Never go above the highest resolution entry
                resolutionFactor = factorVals[i-1];
                break;
            }
        }

        return (int)Math.round(resolutionFactor * frameRateFactor) * 1000;
    }

    public static boolean getDefaultSmallMode(Context context) {
        PackageManager manager = context.getPackageManager();
        if (manager != null) {
            // TVs shouldn't use small mode by default
            if (manager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) {
                return false;
            }

            // API 21 uses LEANBACK instead of TELEVISION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                if (manager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) {
                    return false;
                }
            }
        }

        // Use small mode on anything smaller than a 7" tablet
        return context.getResources().getConfiguration().smallestScreenWidthDp < 500;
    }

    public static int getDefaultBitrate(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return getDefaultBitrate(
                prefs.getString(RESOLUTION_PREF_STRING, DEFAULT_RESOLUTION),
                prefs.getString(FPS_PREF_STRING, DEFAULT_FPS));
    }

    private static FormatOption getVideoFormatValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String str = prefs.getString(VIDEO_FORMAT_PREF_STRING, DEFAULT_VIDEO_FORMAT);
        if (str.equals("auto")) {
            return FormatOption.AUTO;
        }
        else if (str.equals("forceav1")) {
            return FormatOption.FORCE_AV1;
        }
        else if (str.equals("forceh265")) {
            return FormatOption.FORCE_HEVC;
        }
        else if (str.equals("neverh265")) {
            return FormatOption.FORCE_H264;
        }
        else {
            // Should never get here
            return FormatOption.AUTO;
        }
    }

    private static int getFramePacingValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        // Migrate legacy never drop frames option to the new location
        if (prefs.contains(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)) {
            boolean legacyNeverDropFrames = prefs.getBoolean(LEGACY_DISABLE_FRAME_DROP_PREF_STRING, false);
            prefs.edit()
                    .remove(LEGACY_DISABLE_FRAME_DROP_PREF_STRING)
                    .putString(FRAME_PACING_PREF_STRING, legacyNeverDropFrames ? "balanced" : "latency")
                    .apply();
        }

        String str = prefs.getString(FRAME_PACING_PREF_STRING, DEFAULT_FRAME_PACING);
        if (str.equals("latency")) {
            return FRAME_PACING_MIN_LATENCY;
        }
        else if (str.equals("balanced")) {
            return FRAME_PACING_BALANCED;
        }
        else if (str.equals("cap-fps")) {
            return FRAME_PACING_CAP_FPS;
        }
        else if (str.equals("smoothness")) {
            return FRAME_PACING_MAX_SMOOTHNESS;
        }
        else {
            // Should never get here
            return FRAME_PACING_MIN_LATENCY;
        }
    }

    private static AnalogStickForScrolling getAnalogStickForScrollingValue(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String str = prefs.getString(ANALOG_SCROLLING_PREF_STRING, DEFAULT_ANALOG_STICK_FOR_SCROLLING);
        if (str.equals("right")) {
            return AnalogStickForScrolling.RIGHT;
        }
        else if (str.equals("left")) {
            return AnalogStickForScrolling.LEFT;
        }
        else {
            return AnalogStickForScrolling.NONE;
        }
    }

    public static void resetStreamingSettings(Context context) {
        // We consider resolution, FPS, bitrate, HDR, and video format as "streaming settings" here
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .remove(BITRATE_PREF_STRING)
                .remove(BITRATE_PREF_OLD_STRING)
                .remove(LEGACY_RES_FPS_PREF_STRING)
                .remove(RESOLUTION_PREF_STRING)
                .remove(FPS_PREF_STRING)
                .remove(VIDEO_FORMAT_PREF_STRING)
                .remove(ENABLE_HDR_PREF_STRING)
                .remove(UNLOCK_FPS_STRING)
                .remove(FULL_RANGE_PREF_STRING)
                .apply();
    }

    public static void completeLanguagePreferenceMigration(Context context) {
        // Put our language option back to default which tells us that we've already migrated it
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(LANGUAGE_PREF_STRING, DEFAULT_LANGUAGE).apply();
    }

    public static boolean isShieldAtvFirmwareWithBrokenHdr() {
        // This particular Shield TV firmware crashes when using HDR
        // https://www.nvidia.com/en-us/geforce/forums/notifications/comment/155192/
        return Build.MANUFACTURER.equalsIgnoreCase("NVIDIA") &&
                Build.FINGERPRINT.contains("PPR1.180610.011/4079208_2235.1395");
    }

    /** MoonCast: when true, the stream frame rate is [nativeFps], this screen's fastest refresh rate. */
    public static final String NATIVE_FPS_PREF_STRING = "mc_native_fps";

    /**
     * MoonCast: Native frame rate, the fastest refresh rate the screen [context] is on can run at (the mode Game's
     * refresh-rate matching then picks), rounded to a whole number: 120 on a 120 Hz screen, 60 on most others.
     */
    public static int nativeFps(Context context) {
        float hz = 60f;
        try {
            Display d = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try { d = context.getDisplay(); } catch (UnsupportedOperationException ignored) { }
            }
            if (d == null) d = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
            hz = d.getRefreshRate();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                for (Display.Mode m : d.getSupportedModes()) hz = Math.max(hz, m.getRefreshRate());
            }
        } catch (RuntimeException ignored) { }
        int fps = Math.round(hz);
        return fps < 24 ? 60 : Math.min(fps, 240);
    }

    /** MoonCast: when true, the stream resolution is [nativeResolution] of the screen it starts on. */
    public static final String NATIVE_RES_PREF_STRING = "mc_native_res";
    /** True when the stream is Native: the whole screen in its own shape, so no bars (a near-square foldable too). */
    public static boolean nativeWholeScreen(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(NATIVE_RES_PREF_STRING, false);
    }

    /**
     * MoonCast: the PC streamed last (or streaming now) is Polaris (ComputerDetails.polarisServer), so Native is the
     * exact screen. Game sets it from its PC before reading the preferences; Settings shows Native for that PC.
     */
    public static final String EXACT_NATIVE_HOST_PREF_STRING = "mc_native_exact_host";

    public static void setExactNativeHost(Context context, boolean polaris) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.getBoolean(EXACT_NATIVE_HOST_PREF_STRING, false) != polaris)
            prefs.edit().putBoolean(EXACT_NATIVE_HOST_PREF_STRING, polaris).apply();
    }

    public static boolean exactNativeHost(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(EXACT_NATIVE_HOST_PREF_STRING, false);
    }

    /**
     * Native for the screen [context] is on.
     * Polaris (it makes its display at the size the client asks): exactly that screen's size, only made even, so the
     * stream fills it without bars (the Fold: 2448x1848 open, 1968x1248 folded); a fold or unfold mid-stream
     * reconnects at the other screen's size and Polaris resumes the game (Game).
     * Any other host (Sunshine, GFE): the screen's size rounded down to multiples of 8 for the PC's display modes, and
     * on a foldable the one shape between its two screens ([foldableCompromise]), so a fold only re-fits the picture.
     */
    public static String nativeResolution(Context context) {
        rememberScreen(context);
        if (!exactNativeHost(context)) {
            String shared = foldableCompromise(context);
            if (shared != null) return shared;
            int[] wh = screenSize(context);
            return (wh[0] & ~7) + "x" + (wh[1] & ~7);
        }
        return screenResolution(context);
    }

    /** The screen [context] is on, exactly (only made even), whatever the host. */
    public static String screenResolution(Context context) {
        int[] wh = screenSize(context);
        return (wh[0] & ~1) + "x" + (wh[1] & ~1);
    }

    /** Two sizes within this many pixels on both sides are the same screen (Native's even rounding, a stray pixel). */
    public static final int SAME_SCREEN_SLACK = 8;

    /** True when [a] and [b] ("WxH") are the same screen within [SAME_SCREEN_SLACK] pixels on both sides. */
    public static boolean sameScreen(String a, String b) {
        try {
            return Math.abs(getWidthFromResolutionString(a) - getWidthFromResolutionString(b)) <= SAME_SCREEN_SLACK
                    && Math.abs(getHeightFromResolutionString(a) - getHeightFromResolutionString(b)) <= SAME_SCREEN_SLACK;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** MoonCast: the screen sizes this device has run on ("2448x1848;1968x1248"): a foldable has two shapes. */
    public static final String SCREENS_PREF_STRING = "mc_screens";

    /** Note the screen [context] is on, so a foldable's two screens are known (a few at most). */
    public static void rememberScreen(Context context) {
        int[] wh = screenSize(context);
        if (wh[0] < 640 || wh[1] < 360) return;
        String me = wh[0] + "x" + wh[1];
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String all = prefs.getString(SCREENS_PREF_STRING, "");
        java.util.List<String> list = new java.util.ArrayList<>();
        for (String x : all.split(";")) if (!x.isEmpty()) list.add(x);
        if (list.contains(me)) return;
        list.add(0, me);
        while (list.size() > 4) list.remove(list.size() - 1);
        prefs.edit().putString(SCREENS_PREF_STRING, android.text.TextUtils.join(";", list)).apply();
    }

    /**
     * Not Polaris: a foldable (two screens of clearly different shapes, both seen here): one stream shape between the
     * two, so a fold or unfold only re-fits the picture (Picture shape: Fill trims a little, Stretch bends a little,
     * Fit leaves thin bars) instead of reconnecting or restarting the game. Its width is the biggest screen's long
     * side, its shape the geometric mean of the widest and the squarest (the Fold: 1.58 and 1.32 make 1.45,
     * 2448x1696). Null on a device with one screen shape. (Was foldableResolution.)
     */
    public static String foldableCompromise(Context context) {
        String all = PreferenceManager.getDefaultSharedPreferences(context).getString(SCREENS_PREF_STRING, "");
        double lo = 0, hi = 0; int wmax = 0;
        for (String x : all.split(";")) {
            int w, h;
            try { w = getWidthFromResolutionString(x); h = getHeightFromResolutionString(x); } catch (RuntimeException e) { continue; }
            if (w <= 0 || h <= 0) continue;
            double r = (double) w / h;
            if (lo == 0 || r < lo) lo = r;
            if (r > hi) hi = r;
            wmax = Math.max(wmax, w);
        }
        if (lo == 0 || hi / lo < 1.08) return null;
        double shape = Math.sqrt(lo * hi);
        int w = wmax & ~7;
        int h = (int) Math.round(w / shape / 8.0) * 8;
        return w + "x" + h;
    }

    /**
     * A foldable: two screens of clearly different shapes have been seen here (the Fold: 1.58 and 1.32). Native
     * streams each one at its own size and a fold or unfold reconnects; this only words the settings.
     */
    public static boolean hasTwoScreenShapes(Context context) {
        String all = PreferenceManager.getDefaultSharedPreferences(context).getString(SCREENS_PREF_STRING, "");
        double lo = 0, hi = 0;
        for (String x : all.split(";")) {
            int w, h;
            try { w = getWidthFromResolutionString(x); h = getHeightFromResolutionString(x); } catch (RuntimeException e) { continue; }
            if (w <= 0 || h <= 0) continue;
            double r = (double) w / h;
            if (lo == 0 || r < lo) lo = r;
            if (r > hi) hi = r;
        }
        return lo != 0 && hi / lo >= 1.08;
    }

    /** The physical size of the screen [context] is on, the long side first. */
    private static int[] screenSize(Context context) {
        // An activity's own window, full screen, follows a foldable's switch together with its configuration
        // (the display's mode can still report the other panel for a moment)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && context instanceof android.app.Activity) {
            android.graphics.Rect b = ((android.app.Activity) context).getWindowManager().getMaximumWindowMetrics().getBounds();
            int w = b.width(), h = b.height();
            if (w > 0 && h > 0) return new int[] { Math.max(w, h), Math.min(w, h) };
        }
        Display d = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { d = context.getDisplay(); } catch (UnsupportedOperationException ignored) { }
        }
        if (d == null) d = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        int w, h;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) { w = d.getMode().getPhysicalWidth(); h = d.getMode().getPhysicalHeight(); }
        else { android.graphics.Point p = new android.graphics.Point(); d.getRealSize(p); w = p.x; h = p.y; }
        if (h > w) { int t = w; w = h; h = t; }
        return new int[] { w, h };
    }

    public static PreferenceConfiguration readPreferences(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        PreferenceConfiguration config = new PreferenceConfiguration();

        // Migrate legacy preferences to the new locations
        if (prefs.contains(LEGACY_ENABLE_51_SURROUND_PREF_STRING)) {
            if (prefs.getBoolean(LEGACY_ENABLE_51_SURROUND_PREF_STRING, false)) {
                prefs.edit()
                        .remove(LEGACY_ENABLE_51_SURROUND_PREF_STRING)
                        .putString(AUDIO_CONFIG_PREF_STRING, "51")
                        .apply();
            }
        }

        String str = prefs.getString(LEGACY_RES_FPS_PREF_STRING, null);
        if (str != null) {
            if (str.equals("360p30")) {
                config.width = 640;
                config.height = 360;
                config.fps = 30;
            }
            else if (str.equals("360p60")) {
                config.width = 640;
                config.height = 360;
                config.fps = 60;
            }
            else if (str.equals("720p30")) {
                config.width = 1280;
                config.height = 720;
                config.fps = 30;
            }
            else if (str.equals("720p60")) {
                config.width = 1280;
                config.height = 720;
                config.fps = 60;
            }
            else if (str.equals("1080p30")) {
                config.width = 1920;
                config.height = 1080;
                config.fps = 30;
            }
            else if (str.equals("1080p60")) {
                config.width = 1920;
                config.height = 1080;
                config.fps = 60;
            }
            else if (str.equals("4K30")) {
                config.width = 3840;
                config.height = 2160;
                config.fps = 30;
            }
            else if (str.equals("4K60")) {
                config.width = 3840;
                config.height = 2160;
                config.fps = 60;
            }
            else {
                // Should never get here
                config.width = 1280;
                config.height = 720;
                config.fps = 60;
            }

            prefs.edit()
                    .remove(LEGACY_RES_FPS_PREF_STRING)
                    .putString(RESOLUTION_PREF_STRING, getResolutionString(config.width, config.height))
                    .putString(FPS_PREF_STRING, ""+config.fps)
                    .apply();
        }
        else {
            // Use the new preference location
            String resStr = prefs.getString(RESOLUTION_PREF_STRING, PreferenceConfiguration.DEFAULT_RESOLUTION);

            // Convert legacy resolution strings to the new style
            if (!resStr.contains("x")) {
                resStr = PreferenceConfiguration.convertFromLegacyResolutionString(resStr);
                prefs.edit().putString(RESOLUTION_PREF_STRING, resStr).apply();
            }

            config.width = PreferenceConfiguration.getWidthFromResolutionString(resStr);
            config.height = PreferenceConfiguration.getHeightFromResolutionString(resStr);
            config.fps = Integer.parseInt(prefs.getString(FPS_PREF_STRING, PreferenceConfiguration.DEFAULT_FPS));
        }

        // MoonCast: "Native" follows the screen the stream starts on (a foldable's cover or its inner screen),
        // instead of the size of whichever screen it was first set on.
        if (prefs.getBoolean(NATIVE_RES_PREF_STRING, false)) {
            String n = nativeResolution(context);
            config.width = getWidthFromResolutionString(n);
            config.height = getHeightFromResolutionString(n);
        }
        // MoonCast: Native frame rate, this screen's refresh rate (list_fps holds the same number for the settings pages)
        if (prefs.getBoolean(NATIVE_FPS_PREF_STRING, false)) {
            config.fps = nativeFps(context);
        }

        if (!prefs.contains(SMALL_ICONS_PREF_STRING)) {
            // We need to write small icon mode's default to disk for the settings page to display
            // the current state of the option properly
            prefs.edit().putBoolean(SMALL_ICONS_PREF_STRING, getDefaultSmallMode(context)).apply();
        }

        if (!prefs.contains(GAMEPAD_MOTION_SENSORS_PREF_STRING) && Build.VERSION.SDK_INT == Build.VERSION_CODES.S) {
            // Android 12 has a nasty bug that causes crashes when the app touches the InputDevice's
            // associated InputDeviceSensorManager (just calling getSensorManager() is enough).
            // As a workaround, we will override the default value for the gamepad motion sensor
            // option to disabled on Android 12 to reduce the impact of this bug.
            // https://cs.android.com/android/_/android/platform/frameworks/base/+/8970010a5e9f3dc5c069f56b4147552accfcbbeb
            prefs.edit().putBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, false).apply();
        }

        // This must happen after the preferences migration to ensure the preferences are populated
        config.bitrate = prefs.getInt(BITRATE_PREF_STRING, prefs.getInt(BITRATE_PREF_OLD_STRING, 0) * 1000);
        if (config.bitrate == 0) {
            config.bitrate = getDefaultBitrate(context);
        }

        String audioConfig = prefs.getString(AUDIO_CONFIG_PREF_STRING, DEFAULT_AUDIO_CONFIG);
        if (audioConfig.equals("71")) {
            config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_71_SURROUND;
        }
        else if (audioConfig.equals("51")) {
            config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_51_SURROUND;
        }
        else /* if (audioConfig.equals("2")) */ {
            config.audioConfiguration = MoonBridge.AUDIO_CONFIGURATION_STEREO;
        }

        config.videoFormat = getVideoFormatValue(context);
        config.framePacing = getFramePacingValue(context);

        config.analogStickForScrolling = getAnalogStickForScrollingValue(context);

        config.deadzonePercentage = prefs.getInt(DEADZONE_PREF_STRING, DEFAULT_DEADZONE);

        config.oscOpacity = prefs.getInt(OSC_OPACITY_PREF_STRING, DEFAULT_OPACITY);

        config.language = prefs.getString(LANGUAGE_PREF_STRING, DEFAULT_LANGUAGE);

        // Checkbox preferences
        config.disableWarnings = prefs.getBoolean(DISABLE_TOASTS_PREF_STRING, DEFAULT_DISABLE_TOASTS);
        config.enableSops = prefs.getBoolean(SOPS_PREF_STRING, DEFAULT_SOPS);
        config.stretchVideo = prefs.getBoolean(STRETCH_PREF_STRING, DEFAULT_STRETCH);
        config.ultraLowLatency = prefs.getBoolean(ULTRA_LOW_LATENCY_PREF_STRING, false);
        String scaleMode = prefs.getString(SCALE_MODE_PREF_STRING, config.stretchVideo ? "stretch" : "fit");
        config.stretchVideo = "stretch".equals(scaleMode);
        config.fillVideo = "fill".equals(scaleMode);
        config.videoZoomPercent = prefs.getInt(ZOOM_PREF_STRING, 100);
        config.playHostAudio = prefs.getBoolean(HOST_AUDIO_PREF_STRING, DEFAULT_HOST_AUDIO);
        config.smallIconMode = prefs.getBoolean(SMALL_ICONS_PREF_STRING, getDefaultSmallMode(context));
        config.multiController = prefs.getBoolean(MULTI_CONTROLLER_PREF_STRING, DEFAULT_MULTI_CONTROLLER);
        config.usbDriver = prefs.getBoolean(USB_DRIVER_PREF_SRING, DEFAULT_USB_DRIVER);
        config.onscreenController = prefs.getBoolean(ONSCREEN_CONTROLLER_PREF_STRING, ONSCREEN_CONTROLLER_DEFAULT);
        config.onlyL3R3 = prefs.getBoolean(ONLY_L3_R3_PREF_STRING, ONLY_L3_R3_DEFAULT);
        config.showGuideButton = prefs.getBoolean(SHOW_GUIDE_BUTTON_PREF_STRING, SHOW_GUIDE_BUTTON_DEFAULT);
        config.enableHdr = prefs.getBoolean(ENABLE_HDR_PREF_STRING, DEFAULT_ENABLE_HDR) && !isShieldAtvFirmwareWithBrokenHdr();
        config.enablePip = prefs.getBoolean(ENABLE_PIP_PREF_STRING, DEFAULT_ENABLE_PIP);
        config.enablePerfOverlay = prefs.getBoolean(ENABLE_PERF_OVERLAY_STRING, DEFAULT_ENABLE_PERF_OVERLAY);
        config.bindAllUsb = prefs.getBoolean(BIND_ALL_USB_STRING, DEFAULT_BIND_ALL_USB);
        config.mouseEmulation = prefs.getBoolean(MOUSE_EMULATION_STRING, DEFAULT_MOUSE_EMULATION);
        config.mouseNavButtons = prefs.getBoolean(MOUSE_NAV_BUTTONS_STRING, DEFAULT_MOUSE_NAV_BUTTONS);
        config.unlockFps = prefs.getBoolean(UNLOCK_FPS_STRING, DEFAULT_UNLOCK_FPS);
        config.vibrateOsc = prefs.getBoolean(VIBRATE_OSC_PREF_STRING, DEFAULT_VIBRATE_OSC);
        config.vibrateFallbackToDevice = prefs.getBoolean(VIBRATE_FALLBACK_PREF_STRING, DEFAULT_VIBRATE_FALLBACK);
        config.vibrateFallbackToDeviceStrength = prefs.getInt(VIBRATE_FALLBACK_STRENGTH_PREF_STRING, DEFAULT_VIBRATE_FALLBACK_STRENGTH);
        config.flipFaceButtons = prefs.getBoolean(FLIP_FACE_BUTTONS_PREF_STRING, DEFAULT_FLIP_FACE_BUTTONS);
        config.touchscreenTrackpad = prefs.getBoolean(TOUCHSCREEN_TRACKPAD_PREF_STRING, DEFAULT_TOUCHSCREEN_TRACKPAD);
        config.enableLatencyToast = prefs.getBoolean(LATENCY_TOAST_PREF_STRING, DEFAULT_LATENCY_TOAST);
        config.absoluteMouseMode = prefs.getBoolean(ABSOLUTE_MOUSE_MODE_PREF_STRING, DEFAULT_ABSOLUTE_MOUSE_MODE);
        config.enableAudioFx = prefs.getBoolean(ENABLE_AUDIO_FX_PREF_STRING, DEFAULT_ENABLE_AUDIO_FX);
        config.reduceRefreshRate = prefs.getBoolean(REDUCE_REFRESH_RATE_PREF_STRING, DEFAULT_REDUCE_REFRESH_RATE);
        config.fullRange = prefs.getBoolean(FULL_RANGE_PREF_STRING, DEFAULT_FULL_RANGE);
        config.gamepadTouchpadAsMouse = prefs.getBoolean(GAMEPAD_TOUCHPAD_AS_MOUSE_PREF_STRING, DEFAULT_GAMEPAD_TOUCHPAD_AS_MOUSE);
        config.gamepadMotionSensors = prefs.getBoolean(GAMEPAD_MOTION_SENSORS_PREF_STRING, DEFAULT_GAMEPAD_MOTION_SENSORS);
        config.gamepadMotionSensorsFallbackToDevice = prefs.getBoolean(GAMEPAD_MOTION_FALLBACK_PREF_STRING, DEFAULT_GAMEPAD_MOTION_FALLBACK);

        return config;
    }
}
