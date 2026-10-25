package com.limelight.home

import android.content.SharedPreferences

/**
 * Settings, PlayStation and Settings, Xbox: their own picture settings (Saleh). Each starts as "Same as Picture", so
 * nothing changes until it's set; the players ask here instead of reading Picture's keys directly.
 * [kind] is "ps5" or "xbox": keys <kind>_fsr ("same", "off", "fsr1"), <kind>_fsr_sharp (-1 = same, else 0..100),
 * <kind>_shape ("same", "fit", "fill", "stretch"); ps5_pacing ("same", "latency", "balanced", "smoothness").
 */
object ConsolePicture {
    @JvmStatic
    fun fsrOn(p: SharedPreferences, kind: String): Boolean = when (p.getString("${kind}_fsr", "same")) {
        "fsr1" -> true; "off" -> false
        else -> com.limelight.binding.video.FsrRenderer.enabled(p)
    }

    /** Sharpening, 0..100. */
    @JvmStatic
    fun sharpness(p: SharedPreferences, kind: String): Int = p.getInt("${kind}_fsr_sharp", -1).takeIf { it >= 0 }
        ?: p.getInt(com.limelight.binding.video.FsrRenderer.SHARPNESS_PREF, com.limelight.binding.video.FsrRenderer.DEFAULT_SHARPNESS)

    /** "fit", "fill" or "stretch". */
    @JvmStatic
    fun shape(p: SharedPreferences, kind: String): String = p.getString("${kind}_shape", "same")?.takeIf { it != "same" }
        ?: p.getString("list_video_scale_mode", if (p.getBoolean("checkbox_stretch_video", false)) "stretch" else "fit") ?: "fit"

    /** PS5 frame pacing: "latency", "balanced" or "smoothness" (Picture's "cap-fps" counts as balanced). */
    @JvmStatic
    fun pacing(p: SharedPreferences): String = p.getString("ps5_pacing", "same")?.takeIf { it != "same" }
        ?: when (p.getString("frame_pacing", "latency")) { "balanced", "cap-fps" -> "balanced"; "smoothness" -> "smoothness"; else -> "latency" }

    /** Every key above, for the streaming profiles. */
    val KEYS = listOf("ps5_fsr", "ps5_fsr_sharp", "ps5_shape", "ps5_pacing", "xbox_fsr", "xbox_fsr_sharp", "xbox_shape")
}
