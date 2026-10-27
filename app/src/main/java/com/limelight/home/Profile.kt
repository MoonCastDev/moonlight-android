package com.limelight.home

import android.content.Context
import android.preference.PreferenceManager

/**
 * Home and Away streaming profiles, shared by the TV and handheld apps (issue #35). Home uses the Home settings,
 * Away uses the Away settings. Auto picks Away only when the console is reached through PlayStation Network.
 * Games, consoles, pairing and sign-ins are not part of a profile; only streaming settings are.
 */
object Profile {
    const val PREF = "stream_profile"

    /** Every profile: Home, Away and three of your own (Saleh). */
    val MODES = listOf("home", "away", "custom1", "custom2", "custom3")

    /** "home", "away", "custom1", "custom2" or "custom3", as chosen in Settings. Anything else (an old "auto") is Home. */
    @JvmStatic
    fun mode(ctx: Context): String =
        PreferenceManager.getDefaultSharedPreferences(ctx).getString(PREF, "home")?.takeIf { it in MODES } ?: "home"

    /** A profile's name: Home, Away, or a custom one's own (renamed in Settings, Picture). */
    @JvmStatic
    fun name(ctx: Context, mode: String = mode(ctx)): String = when (mode) {
        "home" -> "Home"; "away" -> "Away"
        else -> PreferenceManager.getDefaultSharedPreferences(ctx).getString("mc_profile_name_$mode", null)?.takeIf { it.isNotBlank() }
            ?: "Custom ${mode.removePrefix("custom")}"
    }

    @JvmStatic
    fun rename(ctx: Context, mode: String, name: String) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString("mc_profile_name_$mode", name.trim().take(24)).apply()
    }

    /** (name, key) for a choice row. */
    @JvmStatic
    fun choices(ctx: Context): List<Pair<String, String>> = MODES.map { name(ctx, it) to it }

    @JvmStatic
    fun isCustom(ctx: Context) = mode(ctx).startsWith("custom")

    /** True when the stream should use the Away settings. */
    @JvmStatic
    fun isAway(ctx: Context): Boolean = mode(ctx) == "away"

    /**
     * Added with the custom profiles: a profile saved before has no value for these, so entering it keeps what's set
     * now instead of clearing them (a PS5 bitrate set at home survives the first switch).
     */
    private val NEW_KEYS = listOf("mc_super_resolution", "mc_fsr_sharpness",
        "ps5_resolution", "ps5_fps_home", "ps5_bitrate_home", "ps5_bitrate_home_custom", "ps5_codec",
        "ps5_away_quality", "away_fps", "away_bitrate", "away_bitrate_custom", "away_codec", "ps5_decoder_latency",
        "xbox_bitrate") + ConsolePicture.KEYS

    /** Streaming settings that belong to a profile (PC, audio and Xbox picture). A switch saves and restores these. */
    private val PC_KEYS = listOf(
        // Moonlight video
        "list_resolution", "list_fps", "seekbar_bitrate_kbps", "video_format", "checkbox_enable_hdr", "checkbox_full_range", "checkbox_yuv444", "checkbox_pyrowave", "spatial_dithering", "checkbox_front_buffer", "checkbox_pyrowave_gpu_boost", "checkbox_pyrowave_444", "pyrowave_late_frames", "pyrowave_fec_percent",
        "list_video_scale_mode", "checkbox_stretch_video", "seekbar_video_zoom", "frame_pacing", "checkbox_ultra_low_latency",
        "checkbox_enable_sops", "checkbox_unlock_fps", "checkbox_reduce_refresh_rate",
        // audio
        "list_audio_config", "checkbox_host_audio", "checkbox_enable_audiofx",
        // Xbox picture
        "xbox_resolution",
        // MoonCast picture (Picture's FSR) and the consoles' own settings, so each profile keeps them
        ) + NEW_KEYS

    private const val APPLIED = "pc_profile_applied"

    /**
     * Keeps the PC's video settings in step with the General profile. Leaving a profile saves its values; entering one
     * restores the values saved for it (or Moonlight's defaults the first time). The PC has no PlayStation-style route,
     * so Auto means Home here, and Away only when it is chosen.
     */
    @JvmStatic
    fun syncPcSettings(ctx: Context) {
        val p = PreferenceManager.getDefaultSharedPreferences(ctx)
        val want = mode(ctx)
        val applied = p.getString(APPLIED, "home") ?: "home"
        if (applied == want) return
        val ed = p.edit()
        for (k in PC_KEYS) {
            val v = p.all[k]
            ed.putString("pcprof_${applied}_$k", when (v) { is Int -> "i:$v"; is Boolean -> "b:$v"; is String -> "s:$v"; else -> "" })
        }
        // a custom profile used for the first time starts as a copy of the one you were in
        val fresh = want.startsWith("custom") && !p.contains("pcprof_${want}_list_resolution")
        for (k in PC_KEYS) {
            if (fresh) break
            val s = p.getString("pcprof_${want}_$k", null)
            when {
                s == null && k in NEW_KEYS -> { }   // saved before these keys were in profiles: keep what's set
                s == null || s.isEmpty() -> ed.remove(k)
                s.startsWith("i:") -> s.substring(2).toIntOrNull()?.let { ed.putInt(k, it) } ?: ed.remove(k)
                s.startsWith("s:") -> ed.putString(k, s.substring(2))
                s.startsWith("b:") -> ed.putBoolean(k, s.substring(2).toBoolean())
                else -> ed.remove(k)
            }
        }
        ed.putString(APPLIED, want).apply()
    }
}
