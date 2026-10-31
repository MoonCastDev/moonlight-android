package com.limelight.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View

/**
 * The PC Quick menu's Play styles (handheld app only): the handheld sets [handler] (its PlayStyles); the TV leaves
 * it unset and keeps the Bitrate item instead. Picking a style reconnects the stream with it; holding one opens its
 * Fine-tune in the handheld's Settings, which brings the game back when it's left.
 */
object PlayStyleLink {
    class Entry(val id: String, val name: String, val detail: String, val icon: String, val color: Int, val inUse: Boolean)

    interface Handler {
        fun styles(ctx: Context): List<Entry>
        /** Picks style [id] (its values into the live settings, committed). */
        fun use(ctx: Context, id: String)
        fun icon(ctx: Context, icon: String, color: Int): View
        /** Opens style [id]'s Fine-tune for the PC [uuid]; leaving it starts [resume] (the stream again). */
        fun fineTune(act: Activity, id: String, uuid: String?, resume: Intent)
    }

    @JvmField var handler: Handler? = null

    fun interface Tune { fun open(id: String) }

    /** The Quick menu item, or null on the TV (no play styles there). */
    @JvmStatic
    fun item(menu: QuickMenu, act: Activity, back: Runnable, reconnect: Runnable, tune: Tune): QuickMenu.Item? {
        val h = handler ?: return null
        val now = h.styles(act).firstOrNull { it.inUse }
        return QuickMenu.Item("◐", "Play styles", (now?.name ?: "Changed") + ". Picking one reconnects in about 2 s", false) {
            val items = ArrayList<QuickMenu.Item>()
            items.add(QuickMenu.Item("↩", "Back", "To the menu", false, back).staysOpen(null))
            h.styles(act).forEach { s ->
                items.add(QuickMenu.Item("", s.name, (if (s.inUse) "In use · " else "") + s.detail, false) {
                    if (!s.inUse) { h.use(act, s.id); reconnect.run() }
                }.drawnIcon(s.color, QuickMenu.IconView { h.icon(act, s.icon, s.color) }).onHold(Runnable { tune.open(s.id) }))
            }
            menu.show("Play styles", "Picking one reconnects with it; the game keeps running. Hold A on one to fine-tune it.",
                    QuickMenu.Stats { emptyList() }, items)
        }.staysOpen(null)
    }
}
