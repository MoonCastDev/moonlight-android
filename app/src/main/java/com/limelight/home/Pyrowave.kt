package com.limelight.home

import android.content.Context
import android.os.Build
import android.preference.PreferenceManager
import com.limelight.binding.video.PyrowaveRenderer

/**
 * PyroWave (Settings, Picture): an intra-only wavelet codec that decodes in Vulkan in a couple of milliseconds, for
 * the lowest delay on a wired or fast Wi-Fi link. It needs a lot of bitrate (hundreds of Mbps) and a PC running a
 * Sunshine build with PyroWave (Moonlight X's Sunshine, vibeshine). Off by default. When it's on but the PC doesn't
 * offer it, the stream uses the normal codec setting and says so once.
 *
 * Ported from Moonlight X (https://github.com/MoreOrLessSoftware/moonlight-android, GPL-3.0); the codec is
 * PyroWave by Hans-Kristian Arntzen (https://github.com/Themaister/pyrowave, MIT).
 */
object Pyrowave {
    const val PREF = "checkbox_pyrowave"

    // Where this device's answer is kept, so the Vulkan probe runs once per app version and system build
    private const val CACHE_PREF = "mc_pyrowave_probe"

    // Settings, Picture, PyroWave group (Moonlight X's keys): Vulkan options for the PyroWave renderer, and the GPU boost
    const val DITHER_PREF = "spatial_dithering"            // off / low / high
    const val FRONT_BUFFER_PREF = "checkbox_front_buffer"
    const val GPU_BOOST_PREF = "checkbox_pyrowave_gpu_boost"
    const val YUV444_PREF = "checkbox_pyrowave_444"
    const val LATE_FRAMES_PREF = "pyrowave_late_frames"   // off / sharp / balanced / smooth / smoothest
    const val FEC_PREF = "pyrowave_fec_percent"           // "host" or a percentage
    @JvmField val KEYS = listOf(PREF, DITHER_PREF, FRONT_BUFFER_PREF, GPU_BOOST_PREF, YUV444_PREF, LATE_FRAMES_PREF, FEC_PREF)

    /** PyroWave 4:4:4 (Settings, PyroWave): full colour detail, when the PC offers it. Its own setting, as it needs no HEVC decoder. */
    @JvmStatic
    fun yuv444(ctx: Context): Boolean = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(YUV444_PREF, false)

    /**
     * PyroWave late frames (Moonlight X's presets): [cut minimum % of a late frame that must have arrived (-1: frames
     * aren't cut), cut margin in us, minimum % of blocks a frame that lost packets needs to be shown].
     */
    @JvmStatic
    fun lateFrames(ctx: Context): IntArray = when (PreferenceManager.getDefaultSharedPreferences(ctx).getString(LATE_FRAMES_PREF, "balanced")) {
        "off" -> intArrayOf(-1, 0, 90)
        "sharp" -> intArrayOf(50, 1000, 90)
        "smooth" -> intArrayOf(25, 3000, 50)
        "smoothest" -> intArrayOf(10, 5000, 0)
        else -> intArrayOf(30, 2000, 75)
    }

    /** The FEC percentage to ask the PC for (x-ml-video.fecPercent), or -1 for the PC's own setting. */
    @JvmStatic
    fun fecPercent(ctx: Context): Int =
        PreferenceManager.getDefaultSharedPreferences(ctx).getString(FEC_PREF, "host")?.toIntOrNull()?.coerceIn(0, 255) ?: -1

    @JvmStatic
    fun wanted(ctx: Context): Boolean = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF, false)

    /** PyrowaveRenderer.DITHER_*: fine moving noise that hides banding, most visible in HDR gradients. */
    @JvmStatic
    fun ditherMode(ctx: Context): Int = when (PreferenceManager.getDefaultSharedPreferences(ctx).getString(DITHER_PREF, "off")) {
        "low" -> PyrowaveRenderer.DITHER_LOW
        "high" -> PyrowaveRenderer.DITHER_HIGH
        else -> PyrowaveRenderer.DITHER_OFF
    }

    /**
     * Draw each frame into the image on screen (lower delay; may tear). Off by default, except with Ultra low latency,
     * which turns it on unless the front buffer setting was switched off by hand (the rule: an explicit choice wins,
     * ULL decides when there's none).
     */
    @JvmStatic
    fun frontBuffer(ctx: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        if (prefs.contains(FRONT_BUFFER_PREF)) return prefs.getBoolean(FRONT_BUFFER_PREF, false)
        return prefs.getBoolean("checkbox_ultra_low_latency", false)
    }

    /** Light GPU work between frames so its clock stays up. Costs battery and heat. */
    @JvmStatic
    fun gpuBoost(ctx: Context): Boolean = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(GPU_BOOST_PREF, false)

    @Volatile private var able: Boolean? = null

    /** This device can decode PyroWave: arm64, Android 10+, and a Vulkan GPU PyroWave's decoder starts on. */
    @JvmStatic
    fun deviceCan(ctx: Context): Boolean {
        able?.let { return it }
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val key = "${com.limelight.BuildConfig.VERSION_CODE}/${Build.FINGERPRINT}"
        val cached = prefs.getString(CACHE_PREF, null)
        val result = if (cached != null && cached.substringBeforeLast('|') == key) {
            // "probing" left behind means the probe took the app down last time (a GPU driver crash): never again
            cached.substringAfterLast('|') == "1"
        } else {
            prefs.edit().putString(CACHE_PREF, "$key|probing").commit()
            PyrowaveRenderer.isSupported().also { prefs.edit().putString(CACHE_PREF, "$key|${if (it) "1" else "0"}").apply() }
        }
        able = result
        return result
    }

    /**
     * The bitrate PyroWave looks good at, in kbps. Unlike the other codecs every frame stands alone, so it grows with
     * both the pixels and the frame rate: 400 Mbps at 1440p120, 112 Mbps at 1080p60 (Moonlight X's recommendation).
     */
    @JvmStatic
    fun recommendedKbps(width: Int, height: Int, fps: Int): Int {
        val bitsPerPixel = 400_000_000.0 / (2560.0 * 1440 * 120)
        return (Math.round(width.toDouble() * height * fps * bitsPerPixel / 1_000_000) * 1000).toInt()
    }
}
