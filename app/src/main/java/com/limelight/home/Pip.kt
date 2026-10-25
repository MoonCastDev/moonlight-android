package com.limelight.home

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.os.Build
import android.preference.PreferenceManager
import android.util.Rational

/**
 * Settings, Handheld, Picture in picture (Moonlight's own key, so the PC player follows it too): leaving the app
 * during a PS5 or Xbox stream keeps it playing in a small 16:9 window. Android 12+ enters it by itself on Home;
 * 8-11 on the leave hint. Closing the window ends the stream (the players finish on stop); the game keeps running.
 */
object Pip {
    const val PREF = "checkbox_enable_pip"

    @JvmStatic
    fun enabled(a: Activity): Boolean = Build.VERSION.SDK_INT >= 26 &&
            PreferenceManager.getDefaultSharedPreferences(a).getBoolean(PREF, false) &&
            a.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    private fun params(auto: Boolean): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 31) { b.setAutoEnterEnabled(auto); b.setSeamlessResizeEnabled(true) }
        return b.build()
    }

    /** Once the stream is up: on Android 12+ the window opens by itself when you go Home. */
    @JvmStatic
    fun arm(a: Activity) {
        if (Build.VERSION.SDK_INT < 26) return
        try { params(enabled(a) && Build.VERSION.SDK_INT >= 31)?.let { a.setPictureInPictureParams(it) } } catch (e: Exception) { }
    }

    /** onUserLeaveHint: Android 8-11 open the window here. */
    @JvmStatic
    fun onLeave(a: Activity) {
        if (Build.VERSION.SDK_INT !in 26..30 || !enabled(a)) return
        try { params(false)?.let { a.enterPictureInPictureMode(it) } } catch (e: Exception) { }
    }
}
