package com.limelight.binding.video;

import android.os.Build;
import android.view.Surface;

import com.limelight.LimeLog;

/**
 * MoonCast's PyroWave renderer (native, src/main/jni/pyrowave_renderer): decodes PyroWave frames on Vulkan and shows
 * each one on the stream's surface. Only used when the stream negotiated PyroWave; there's no MediaCodec then.
 * The decoder and the Vulkan setup are ported from Moonlight X (GPL-3.0); PyroWave itself is MIT.
 *
 * submit() comes from the decoder thread; destroy() may come from another, so they're synchronized and a destroyed
 * renderer drops frames.
 */
public class PyrowaveRenderer {
    private static final boolean LOADED;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("mooncast-pyrowave");
            loaded = true;
        } catch (Throwable t) {
            LimeLog.info("PyroWave renderer not available: " + t);
        }
        LOADED = loaded;
    }

    private long handle;
    private final long[] stats = new long[4];
    private final String description;

    private PyrowaveRenderer(long handle) {
        this.handle = handle;
        this.description = nativeDescribe(handle);
    }

    /** PyroWave can decode on this device (runs a Vulkan probe the first time; cache the answer). */
    public static boolean isSupported() {
        // libpyrowave-shared.so is built for arm64-v8a against Android 10
        if (!LOADED || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return false;
        }
        try {
            return nativeProbe();
        } catch (Throwable t) {
            LimeLog.warning("PyroWave probe failed: " + t);
            return false;
        }
    }

    /** Spatial dithering (Settings, Picture, PyroWave): off, low (the output's precision), high (8-bit precision). */
    public static final int DITHER_OFF = 0, DITHER_LOW = 1, DITHER_HIGH = 2;

    /**
     * Null if it can't start. colorspace is MoonBridge.COLORSPACE_*; tenBit and chroma444 follow the negotiated
     * VIDEO_FORMAT_PYROWAVE* format.
     */
    public static PyrowaveRenderer create(Surface surface, int width, int height, int colorspace, boolean centerChroma,
                                          boolean tenBit, boolean chroma444, int ditherMode, boolean frontBuffer,
                                          boolean gpuBoost) {
        if (!LOADED || surface == null || !surface.isValid()) {
            return null;
        }
        long handle = nativeCreate(surface, width, height, colorspace, centerChroma, tenBit, chroma444, ditherMode,
                frontBuffer, gpuBoost);
        return handle != 0 ? new PyrowaveRenderer(handle) : null;
    }

    /** The host's display switched HDR on or off (metadata: SS_HDR_METADATA, or null). */
    public synchronized void setHdrMode(boolean enabled, byte[] metadata) {
        if (handle != 0) {
            nativeSetHdrMode(handle, enabled, metadata);
        }
    }

    /** 1: drawing into the front buffer, 2: it was asked for but this device can't, 0: not asked for. */
    public synchronized int getFrontBufferState() {
        return handle != 0 ? nativeFrontBufferState(handle) : 0;
    }

    /** Decodes and shows one whole frame. False if it was dropped. */
    public synchronized boolean submit(byte[] data, int length) {
        return handle != 0 && nativeSubmit(handle, data, length);
    }

    /**
     * Counts since the last call: decode time in ns (CPU and GPU, without waits), frames decoded, frames shown,
     * frames dropped. Null once destroyed.
     */
    public synchronized long[] takeStats() {
        if (handle == 0) {
            return null;
        }
        nativeTakeStats(handle, stats);
        return stats.clone();
    }

    /** The GPU and the decoder's path, for the stats. */
    public String getDescription() {
        return description;
    }

    /** Lets go of the surface and the GPU. Safe to call more than once. */
    public synchronized void destroy() {
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
    }

    private static native boolean nativeProbe();
    private static native long nativeCreate(Surface surface, int width, int height, int colorspace, boolean centerChroma,
                                            boolean tenBit, boolean chroma444, int ditherMode, boolean frontBuffer,
                                            boolean gpuBoost);
    private static native void nativeSetHdrMode(long handle, boolean enabled, byte[] metadata);
    private static native int nativeFrontBufferState(long handle);
    private static native boolean nativeSubmit(long handle, byte[] data, int length);
    private static native void nativeTakeStats(long handle, long[] out);
    private static native String nativeDescribe(long handle);
    private static native void nativeDestroy(long handle);
}
