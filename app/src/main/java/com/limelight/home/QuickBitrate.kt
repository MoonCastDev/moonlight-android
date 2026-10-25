package com.limelight.home

import android.content.Context
import android.preference.PreferenceManager

/**
 * The in-game Quick menu's Bitrate (Saleh): pick another bitrate while playing. A PC or PS5 stream can't change it
 * mid-stream, so the player reconnects to the same game at the new one (the game keeps running; a PS5 stays on);
 * the Xbox's is a cap it works within, applied the same way. The choice is saved to the setting it came from, so it
 * belongs to the streaming profile in use.
 */
object QuickBitrate {
    /** "pc", "ps" or "xb": the bitrate steps in Mbps, 0 = Auto. */
    private fun steps(ctx: Context, kind: String): List<Int> = when (kind) {
        "pc" -> listOf(0, 10, 20, 30, 50, 80, 150)
        "ps" -> if (Profile.isAway(ctx)) listOf(0, 8, 10, 15, 30, 50) else listOf(0, 10, 15, 20, 30, 50, 80, 100)
        else -> listOf(0, 5, 10, 15, 20, 30)
    }

    private fun prefs(ctx: Context) = PreferenceManager.getDefaultSharedPreferences(ctx)
    private fun psKey(ctx: Context) = if (Profile.isAway(ctx)) "away_bitrate" else "ps5_bitrate_home"

    /** The saved choice, in Mbps (0 = Auto, -1 = a Custom value not in the steps). */
    private fun saved(ctx: Context, kind: String): Int = when (kind) {
        "pc" -> prefs(ctx).getInt("seekbar_bitrate_kbps", 0) / 1000
        "ps" -> prefs(ctx).getString(psKey(ctx), "auto")?.let { if (it == "auto") 0 else it.toIntOrNull() ?: -1 } ?: 0
        else -> prefs(ctx).getInt("xbox_bitrate", 0)
    }

    private fun save(ctx: Context, kind: String, mbps: Int) {
        val e = prefs(ctx).edit()
        when (kind) {
            "pc" -> e.putInt("seekbar_bitrate_kbps", mbps * 1000)
            "ps" -> e.putString(psKey(ctx), if (mbps == 0) "auto" else mbps.toString())
            else -> e.putInt("xbox_bitrate", mbps)
        }
        e.apply()
    }

    /** The Quick menu item. [nowMbps] is what the stream runs at (shown), [reconnect] restarts it at the saved one. */
    @JvmStatic
    fun item(menu: QuickMenu, ctx: Context, kind: String, nowMbps: Int, back: Runnable, reconnect: Runnable): QuickMenu.Item =
        QuickMenu.Item("⇅", "Bitrate", (if (nowMbps > 0) "$nowMbps Mbps" else "Auto") + ". Changing it reconnects in about 2 s", false) {
            val cur = saved(ctx, kind)
            val items = ArrayList<QuickMenu.Item>()
            items.add(QuickMenu.Item("↩", "Back", "To the menu", false, back).staysOpen(null))
            steps(ctx, kind).forEach { m ->
                val label = if (m == 0) "Auto" else "$m Mbps"
                items.add(QuickMenu.Item(if (m == cur) "✓" else "", label,
                        if (m == cur) "Now" else if (kind == "xb") "The Xbox stays at or below it" else null, false) {
                    if (m != cur) { save(ctx, kind, m); reconnect.run() }
                })
            }
            menu.show("Bitrate", (if (kind == "ps" && Profile.isAway(ctx)) "Away profile. " else "") +
                    "Higher looks better and needs a faster network. Picking one reconnects in about 2 seconds; the game keeps running.",
                    QuickMenu.Stats { emptyList() }, items)
        }.staysOpen(null)
}
