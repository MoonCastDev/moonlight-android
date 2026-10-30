package com.limelight.home

import android.content.Context
import android.preference.PreferenceManager
import com.limelight.LimeLog
import com.limelight.computers.ComputerDatabaseManager
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.preferences.PreferenceConfiguration

/**
 * A PC stream started away from home (Network.isAway): what changes for this one session. The saved settings and
 * the chosen profile are never changed.
 *
 *  - Home chosen and "Switch to Away automatically" on (the default): the stream uses the Away profile's resolution,
 *    frame rate, bitrate, codec, HDR and 4:4:4. Custom 1-3 and Away keep their own values.
 *  - Whatever the profile: no PyroWave (the codec setting is used, HEVC when it was Automatic), and at most
 *    [CAP_KBPS], unless the Away profile itself sets a higher bitrate (then that is respected).
 */
object AwayStream {
    /** Away bitrate cap, kbps: the internet path rarely carries more than 20-50 Mbps. */
    const val CAP_KBPS = 100_000

    class Plan(
        @JvmField val away: Boolean,
        /** Home was switched to the Away settings for this stream. */
        @JvmField val autoAway: Boolean,
        /** Highest bitrate the Quick menu offers, kbps (0: no cap). */
        @JvmField val capKbps: Int,
        /** YUV 4:4:4 for this stream (the Away profile's when [autoAway]). */
        @JvmField val yuv444: Boolean,
        /** The toast at stream start, or null. */
        @JvmField val message: String?,
    )

    /** The plan of the PC stream running now (Game sets it; a reconnect replaces it). */
    @Volatile @JvmStatic var current: Plan? = null

    /** PyroWave for the stream starting or running now: the setting, unless the stream is away. */
    @JvmStatic
    fun pyrowave(ctx: Context): Boolean = current?.away != true && Pyrowave.wanted(ctx)

    /** YUV 4:4:4 for the stream starting or running now. */
    @JvmStatic
    fun yuv444(ctx: Context): Boolean = current?.yuv444 ?: Yuv444.wanted(ctx)

    /** Game ended: forget its plan, unless a newer stream (a reconnect) already set its own. */
    @JvmStatic
    fun end(plan: Plan?) { if (plan != null && current === plan) current = null }

    /**
     * Decides home or away for a stream to [host] (the PC [uuid]) and changes [cfg] for an away stream. Sets and
     * returns [current]. Looks the PC up and resolves its name on a worker thread, waiting at most 2 s (a name that
     * doesn't answer by then counts as unresolved).
     */
    @JvmStatic
    fun prepare(ctx: Context, cfg: PreferenceConfiguration, host: String?, uuid: String?): Plan {
        val verdict = java.util.concurrent.atomic.AtomicReference<Network.Verdict?>(null)
        val t = Thread({
            val pc: ComputerDetails? = try {
                if (uuid == null) null else ComputerDatabaseManager(ctx).let { db -> try { db.getComputerByUUID(uuid) } finally { db.close() } }
            } catch (e: Exception) { null }
            verdict.set(Network.isAway(ctx, host, pc))
        }, "mc-away")
        t.start()
        try { t.join(2000) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        val v = verdict.get() ?: Network.Verdict(false, "$host: no answer in 2 s")
        LimeLog.info("Away check: " + (if (v.away) "away" else "home") + ", " + v.why)
        val plan = if (v.away) awayPlan(ctx, cfg) else Plan(false, false, 0, Yuv444.wanted(ctx), null)
        current = plan
        return plan
    }

    private fun awayPlan(ctx: Context, cfg: PreferenceConfiguration): Plan {
        val p = PreferenceManager.getDefaultSharedPreferences(ctx)
        val mode = Profile.mode(ctx)
        val auto = mode == "home" && Profile.autoAway(ctx)
        var pyro = Pyrowave.wanted(ctx)
        var yuv = Yuv444.wanted(ctx)
        // the bitrate the Away profile sets on purpose (0: Auto), which may go over the cap
        var awayKbps = if (mode == "away") p.getInt("seekbar_bitrate_kbps", 0) else 0
        if (auto) {
            val res = (Profile.awayValue(ctx, "list_resolution") as? String)
                    ?.takeIf { r -> r.split("x").let { it.size == 2 && it.all { n -> (n.toIntOrNull() ?: 0) > 0 } } } ?: "1280x720"
            val fps = (Profile.awayValue(ctx, "list_fps") as? String)?.takeIf { it.toIntOrNull() != null } ?: "60"
            // Native (a device setting, not a profile one) keeps following the screen
            if (!PreferenceConfiguration.nativeWholeScreen(ctx)) {
                res.split("x").let { cfg.width = it[0].toInt(); cfg.height = it[1].toInt() }
            }
            cfg.fps = fps.toInt()
            awayKbps = (Profile.awayValue(ctx, "seekbar_bitrate_kbps") as? Int) ?: 0
            cfg.bitrate = if (awayKbps > 0) awayKbps else PreferenceConfiguration.getDefaultBitrate(res, fps)
            cfg.videoFormat = when (Profile.awayValue(ctx, "video_format") as? String) {
                "forceav1" -> PreferenceConfiguration.FormatOption.FORCE_AV1
                "forceh265" -> PreferenceConfiguration.FormatOption.FORCE_HEVC
                "neverh265" -> PreferenceConfiguration.FormatOption.FORCE_H264
                else -> PreferenceConfiguration.FormatOption.AUTO
            }
            cfg.enableHdr = (Profile.awayValue(ctx, "checkbox_enable_hdr") as? Boolean ?: false) &&
                    !PreferenceConfiguration.isShieldAtvFirmwareWithBrokenHdr()
            pyro = Profile.awayValue(ctx, Pyrowave.PREF) as? Boolean ?: false
            yuv = Profile.awayValue(ctx, Yuv444.PREF) as? Boolean ?: false
        }
        // PyroWave needs hundreds of Mbps: the codec setting instead, HEVC when it was Automatic (PyroWave only)
        val droppedPyro = pyro
        if (droppedPyro && cfg.videoFormat == PreferenceConfiguration.FormatOption.AUTO) cfg.videoFormat = PreferenceConfiguration.FormatOption.FORCE_HEVC
        val cap = if (awayKbps > CAP_KBPS) awayKbps else CAP_KBPS
        val capped = cfg.bitrate > cap
        if (capped) cfg.bitrate = cap
        val codec = when (cfg.videoFormat) {
            PreferenceConfiguration.FormatOption.FORCE_HEVC -> "HEVC"
            PreferenceConfiguration.FormatOption.FORCE_H264 -> "H.264"
            PreferenceConfiguration.FormatOption.FORCE_AV1 -> "AV1"
            else -> "${cfg.height}p${cfg.fps}"
        }
        LimeLog.info("Away stream: profile $mode" + (if (auto) " -> Away settings" else "") + ", ${cfg.width}x${cfg.height} ${cfg.fps} fps, " +
                "${cfg.bitrate / 1000} Mbps, ${cfg.videoFormat}" + (if (droppedPyro) ", no PyroWave" else "") + (if (capped) ", capped" else ""))
        val msg = if (auto || droppedPyro || capped)
            "Away from home: $codec at ${cfg.bitrate / 1000} Mbps" + (if (droppedPyro || capped) " (PyroWave and high bitrates are for your home network)" else "")
        else null
        return Plan(true, auto, cap, yuv, msg)
    }
}
