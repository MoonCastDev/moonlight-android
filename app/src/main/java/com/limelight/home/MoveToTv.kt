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
        /** Every TV this device knows: (id, name, on this network now), the ones here first. */
        fun tvs(ctx: Context): List<Triple<String, String, Boolean>>
        /** Look for TVs on this network (about 2 s); [done] runs on the main thread. */
        fun sweep(ctx: Context, done: Runnable)
        /** Like [move], to the TV [tvId] (it becomes the last TV). */
        fun moveTo(act: Activity, t: Target, tvId: String)
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
            leaveThen(act) { h.move(act, t) }
        }
    }

    /** End this device's session first (disconnect, don't quit), THEN send: the TV picks the game up. */
    private fun leaveThen(act: Activity, send: () -> Unit) {
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        val go = Runnable { if (once.compareAndSet(false, true) && !act.isDestroyed) { send(); act.finish() } }
        if (act is LocalSession) {
            act.endLocalSession(go)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(go, 3000)   // never wait longer than ~3 s
        } else go.run()
    }

    /**
     * The Quick menu's Play on TV (Saleh): one item, which opens the TVs this device knows (the last one used on top)
     * after a quick look round the network, and moves the game to the one picked. Null on the TV app.
     */
    @JvmStatic
    fun playOnTv(act: Activity, t: Target, menu: QuickMenu, back: Runnable): QuickMenu.Item? {
        if (com.limelight.BuildConfig.TV_APP) return null
        val h = handler ?: return null
        val last = h.tvName(act)
        return QuickMenu.Item("TV", "Play on TV", if (last != null) "Move it to $last, or another TV. This device becomes the controller"
                else "Pick a MoonCast TV. This device becomes the controller", false) {
            fun list(looked: Boolean) {
                if (act.isFinishing) return
                val tvs = h.tvs(act)
                val items = ArrayList<QuickMenu.Item>()
                items.add(QuickMenu.Item("↩", "Back", "To the menu", false, back).staysOpen(null))
                tvs.forEach { (id, name, here) ->
                    items.add(QuickMenu.Item("TV", name, if (here) "On this network" + (if (name == last) ". Last used" else "")
                            else "Not found on this network", false) { leaveThen(act) { h.moveTo(act, t, id) } })
                }
                if (!looked) items.add(QuickMenu.Item("…", "Looking for TVs", "A moment", false).staysOpen(null))
                else items.add(QuickMenu.Item("⟳", "Look again", "Searches this network", false) { h.sweep(act) { list(true) } }.staysOpen(null))
                menu.show("Play on TV", if (tvs.isEmpty() && looked) "No MoonCast TV found. Open MoonCast on the TV, on the same Wi-Fi."
                        else "Pick the TV. The game moves there and keeps running.", QuickMenu.Stats { emptyList() }, items)
            }
            list(false)
            h.sweep(act) { if (menu.isShowing) list(true) }
        }.staysOpen(null)
    }
}
