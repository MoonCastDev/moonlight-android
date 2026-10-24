package com.limelight.home

import android.content.Context
import android.preference.PreferenceManager
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.utils.CacheHelper
import java.io.StringReader

/**
 * After the Quick menu's Power, Hibernate: the PC wakes with the game still running, but Sunshine has forgotten it
 * (it reports no running game). Starting the game again would run its command once more, and a launcher like Steam
 * Big Picture then comes to the front over the game (found on Ahmed's PC with 1.0-beta2). So the PC's hibernation is
 * remembered, and the next start of a game there opens the PC's Desktop app instead: Windows simply shows what was
 * on screen, the game included. The mark goes once used, when the PC reports a running game, after 7 days, or when
 * the PC is restarted or shut down from the Quick menu.
 */
object PowerResume {
    private const val KEY = "mc_hibernated_"
    private const val FRESH_MS = 7L * 24 * 3600 * 1000

    private fun prefs(c: Context) = PreferenceManager.getDefaultSharedPreferences(c)

    /** The PC [uuid] was hibernated while playing [appName]. */
    @JvmStatic
    fun mark(c: Context, uuid: String?, appName: String?) {
        if (uuid.isNullOrEmpty()) return
        prefs(c).edit().putString(KEY + uuid, "${System.currentTimeMillis()}|${appName ?: ""}").apply()
    }

    @JvmStatic
    fun clear(c: Context, uuid: String?) {
        if (!uuid.isNullOrEmpty()) prefs(c).edit().remove(KEY + uuid).apply()
    }

    /**
     * Starting [app] on the PC [uuid], which reports [runningGameId]: the app to start instead (the PC's Desktop) when
     * the PC was hibernated from MoonCast and has forgotten the game, else null. Clears the mark either way.
     */
    @JvmStatic
    fun instead(c: Context, uuid: String?, runningGameId: Int, app: NvApp): NvApp? {
        if (uuid.isNullOrEmpty()) return null
        val v = prefs(c).getString(KEY + uuid, null) ?: return null
        clear(c, uuid)
        val at = v.substringBefore('|').toLongOrNull() ?: return null
        if (runningGameId != 0 || System.currentTimeMillis() - at > FRESH_MS) return null
        val apps = try {
            NvHTTP.getAppListByReader(StringReader(CacheHelper.readInputStreamToString(
                    CacheHelper.openCacheFileForInput(c.cacheDir, "applist", uuid))))
        } catch (e: Exception) {
            return null
        }
        val desktop = apps.firstOrNull { it.appName.equals("Desktop", true) } ?: return null
        return if (desktop.appId == app.appId) null else desktop
    }

    /** What the game that was playing is called, for "Resuming X". */
    @JvmStatic
    fun gameName(c: Context, uuid: String?): String? =
        uuid?.let { prefs(c).getString(KEY + it, null) }?.substringAfter('|', "")?.takeIf { it.isNotEmpty() }
}
