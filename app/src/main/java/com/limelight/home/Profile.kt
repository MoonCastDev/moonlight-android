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

    /** "auto", "home" or "away", as chosen in Settings. */
    @JvmStatic
    fun mode(ctx: Context): String =
        PreferenceManager.getDefaultSharedPreferences(ctx).getString(PREF, "auto") ?: "auto"

    /** True when the stream should use the Away settings. [viaPsn] is true when the route is PlayStation Network. */
    @JvmStatic
    fun isAway(ctx: Context, viaPsn: Boolean): Boolean = when (mode(ctx)) {
        "away" -> true
        "home" -> false
        else -> viaPsn
    }

    /** The PC's video settings that belong to a profile: Moonlight reads these keys, so a switch swaps their values. */
    private val PC_KEYS = listOf("list_resolution", "list_fps", "seekbar_bitrate_kbps", "video_format")
    private const val APPLIED = "pc_profile_applied"

    /**
     * Keeps the PC's video settings in step with the General profile. Leaving a profile saves its values; entering one
     * restores the values saved for it (or Moonlight's defaults the first time). The PC has no PlayStation-style route,
     * so Auto means Home here, and Away only when it is chosen.
     */
    @JvmStatic
    fun syncPcSettings(ctx: Context) {
        val p = PreferenceManager.getDefaultSharedPreferences(ctx)
        val want = if (mode(ctx) == "away") "away" else "home"
        val applied = p.getString(APPLIED, "home") ?: "home"
        if (applied == want) return
        val ed = p.edit()
        for (k in PC_KEYS) {
            val v = p.all[k]
            ed.putString("pcprof_${applied}_$k", when (v) { is Int -> "i:$v"; is String -> "s:$v"; else -> "" })
        }
        for (k in PC_KEYS) {
            val s = p.getString("pcprof_${want}_$k", null)
            when {
                s == null || s.isEmpty() -> ed.remove(k)
                s.startsWith("i:") -> s.substring(2).toIntOrNull()?.let { ed.putInt(k, it) } ?: ed.remove(k)
                s.startsWith("s:") -> ed.putString(k, s.substring(2))
                else -> ed.remove(k)
            }
        }
        ed.putString(APPLIED, want).apply()
    }
}
