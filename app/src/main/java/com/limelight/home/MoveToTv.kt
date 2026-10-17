package com.limelight.home

import android.app.Activity
import android.content.Context
import android.content.Intent

/**
 * "Move to <TV>" in the in-game Quick menu. The players are shared by both apps, but only the handheld (the sender)
 * can send a game to a MoonCast TV, so the work is done by a [Handler] the handheld app registers; on the TV app it is
 * never set and the item never shows.
 */
object MoveToTv {
    /** What is playing, enough for the TV to start the same game. */
    class Target(
        @JvmField val kind: String,            // "pc", "ps" or "xb"
        @JvmField val title: String,
        @JvmField val pcUuid: String? = null, @JvmField val pcName: String? = null, @JvmField val appId: Int = 0,
        @JvmField val psHost: String? = null, @JvmField val registKey: String? = null, @JvmField val morning: String? = null,
        @JvmField val isPs5: Boolean = true,
        @JvmField val xboxId: String? = null,
    ) {
        /** The handheld home's key for this game ("pc:<uuid>:<appId>", "ps:<host>", "xb:<id>"). */
        val key: String get() = when (kind) { "pc" -> "pc:$pcUuid:$appId"; "ps" -> "ps:$psHost"; else -> "xb:$xboxId" }

        fun put(i: Intent): Intent = i.putExtra("mt_kind", kind).putExtra("mt_title", title)
                .putExtra("mt_pc_uuid", pcUuid).putExtra("mt_pc_name", pcName).putExtra("mt_app_id", appId)
                .putExtra("mt_ps_host", psHost).putExtra("mt_regist", registKey).putExtra("mt_morning", morning)
                .putExtra("mt_is_ps5", isPs5).putExtra("mt_xbox_id", xboxId)

        companion object {
            @JvmStatic fun from(i: Intent): Target? {
                val k = i.getStringExtra("mt_kind") ?: return null
                return Target(k, i.getStringExtra("mt_title") ?: "Your game",
                        i.getStringExtra("mt_pc_uuid"), i.getStringExtra("mt_pc_name"), i.getIntExtra("mt_app_id", 0),
                        i.getStringExtra("mt_ps_host"), i.getStringExtra("mt_regist"), i.getStringExtra("mt_morning"),
                        i.getBooleanExtra("mt_is_ps5", true), i.getStringExtra("mt_xbox_id"))
            }
        }
    }

    interface Handler {
        /** The TV a game would move to (the last one used, found on this network now), or null. */
        fun tvName(ctx: Context): String?
        /** Send [t] to that TV and show controller mode. The player leaves (disconnects) right after this. */
        fun move(act: Activity, t: Target)
    }

    @JvmStatic var handler: Handler? = null

    /**
     * A player whose session must be completely over before the TV takes the game: a PS5 allows one Remote Play
     * client, and finishing the screen alone left chiaki running (and froze the UI on its decoder lock).
     * [done] runs on the main thread once the session is closed.
     */
    interface LocalSession { fun endLocalSession(done: Runnable) }

    /** The Quick menu item, or null when there's no TV to move to (always null on the TV app). */
    @JvmStatic
    fun item(act: Activity, t: Target): QuickMenu.Item? {
        if (com.limelight.BuildConfig.TV_APP) return null
        val h = handler ?: return null
        val name = h.tvName(act) ?: return null
        return QuickMenu.Item("TV", "Move to $name", "Keeps playing there. This device becomes the controller", false) {
            // end this device's session first (disconnect, don't quit), THEN send: the TV picks the game up
            val once = java.util.concurrent.atomic.AtomicBoolean(false)
            val go = Runnable { if (once.compareAndSet(false, true) && !act.isDestroyed) { h.move(act, t); act.finish() } }
            if (act is LocalSession) {
                act.endLocalSession(go)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(go, 3000)   // never wait longer than ~3 s
            } else go.run()
        }
    }
}
