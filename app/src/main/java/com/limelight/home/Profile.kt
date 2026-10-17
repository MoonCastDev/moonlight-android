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
}
