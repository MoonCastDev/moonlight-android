package com.limelight.handheld

import android.content.Context

/**
 * What you played last on this handheld, newest first, so Home can lead with it. Keys: "pc:<uuid>:<appId>",
 * "ps:<host>", "xb:<console id>". Stored with the app's own settings (mc_tv).
 */
object Recents {
    private const val KEY = "hh_recent"
    private const val MAX = 24

    fun list(ctx: Context): List<String> =
        (prefs(ctx).getString(KEY, "") ?: "").split('\n').filter { it.isNotBlank() }

    fun touch(ctx: Context, key: String) {
        val l = listOf(key) + list(ctx).filter { it != key }
        prefs(ctx).edit().putString(KEY, l.take(MAX).joinToString("\n")).apply()
    }

    fun forget(ctx: Context, prefix: String) {
        prefs(ctx).edit().putString(KEY, list(ctx).filter { !it.startsWith(prefix) }.joinToString("\n")).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("mc_tv", Context.MODE_PRIVATE)
}
