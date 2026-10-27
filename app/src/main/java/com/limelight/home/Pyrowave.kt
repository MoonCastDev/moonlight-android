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
    @JvmField val KEYS = listOf(PREF, DITHER_PREF, FRONT_BUFFER_PREF, GPU_BOOST_PREF)

    @JvmStatic
    fun wanted(ctx: Context): Boolean = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(PREF, false)

    /** PyrowaveRenderer.DITHER_*: fine moving noise that hides banding, most visible in HDR gradients. */
    @JvmStatic
    fun ditherMode(ctx: Context): Int = when (PreferenceManager.getDefaultSharedPreferences(ctx).getString(DITHER_PREF, "off")) {
        "low" -> PyrowaveRenderer.DITHER_LOW
        "high" -> PyrowaveRenderer.DITHER_HIGH
        else -> PyrowaveRenderer.DITHER_OFF
    }

    /** Draw each frame into the image on screen (lower delay; may tear). Off by default in MoonCast. */
    @JvmStatic
    fun frontBuffer(ctx: Context): Boolean = PreferenceManager.getDefaultSharedPreferences(ctx).getBoolean(FRONT_BUFFER_PREF, false)

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
