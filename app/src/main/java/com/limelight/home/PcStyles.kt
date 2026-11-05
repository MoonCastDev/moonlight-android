package com.limelight.home

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.preference.PreferenceManager
import android.view.Display
import com.limelight.preferences.PreferenceConfiguration

/**
 * The PC play styles for the in-game Quick menu (handheld and TV): which one is in use and picking another. The same
 * styles, values and storage as Settings' PC card (handheld SettingsCards: PcStyle, pcDefaults, pcApplied, usePc), which
 * stays the place to fine-tune, rename and recolour them. Keep the two in step.
 */
object PcStyles {
    private const val RES = "list_resolution"
    private const val FPS = "list_fps"
    private const val KBPS = "seekbar_bitrate_kbps"
    private const val CODEC = "video_format"
    private const val HDR = "checkbox_enable_hdr"
    private const val PACING = "frame_pacing"
    private const val ULL = "checkbox_ultra_low_latency"
    private const val NATIVE_V = "native"
    private val PW = Pyrowave.PREF
    private val Y444 = Yuv444.PREF
    private val PW_BOOST = Pyrowave.GPU_BOOST_PREF
    private val PW_DITHER = Pyrowave.DITHER_PREF
    private val PW_FRONT = Pyrowave.FRONT_BUFFER_PREF

    class Style(@JvmField val id: String, @JvmField val defName: String, @JvmField val icon: String, @JvmField val pyro: Boolean)

    private fun app(ctx: Context): SharedPreferences = ctx.getSharedPreferences("mc_tv", Context.MODE_PRIVATE)
    private fun live(ctx: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)

    /** Battery, Balanced, Sharpest, Fastest, and PyroWave where this device's probe passed. */
    @JvmStatic
    fun list(ctx: Context): List<Style> = listOfNotNull(
            Style("battery", "Battery", "leaf", false),
            Style("balanced", "Balanced", "half", false),
            Style("sharpest", "Sharpest", "star", false),
            Style("fastest", "Fastest", "bolt", false),
            if (Pyrowave.deviceCan(ctx)) Style("pyro", "PyroWave", "wave", true) else null)

    @JvmStatic
    fun name(ctx: Context, st: Style): String = app(ctx).getString("mc_pcstyle_name_${st.id}", null)?.takeIf { it.isNotBlank() } ?: st.defName

    /** A text glyph for the menu, from the style's icon (its own or the one chosen in Settings). */
    @JvmStatic
    fun glyph(ctx: Context, st: Style): String = when (app(ctx).getString("mc_pcstyle_icon_${st.id}", null) ?: st.icon) {
        "leaf" -> "❦"; "half" -> "◐"; "star" -> "✦"; "bolt" -> "ϟ"; "wave" -> "≈"; "flame" -> "♨"; "star5" -> "★"
        "heart" -> "♥"; "pad" -> "✚"; "monitor" -> "▭"; "moon" -> "☾"; "rocket" -> "➚"; else -> "◆"
    }

    private fun screenHdr(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 24 && try {
        @Suppress("DEPRECATION")
        (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
                .hdrCapabilities?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) == true
    } catch (e: Exception) { false }

    private fun defaults(ctx: Context, st: Style): Map<String, Any> = when (st.id) {
        "battery" -> mapOf(RES to NATIVE_V, FPS to NATIVE_V, KBPS to 15000, CODEC to "neverh265", HDR to false, PACING to "balanced", ULL to false)
        "balanced" -> mapOf(RES to NATIVE_V, FPS to NATIVE_V, KBPS to 40000, CODEC to "forceh265", PACING to "balanced", ULL to false)
        "sharpest" -> mapOf(RES to NATIVE_V, FPS to NATIVE_V, KBPS to 80000, CODEC to "forceh265", PACING to "balanced", ULL to false) +
                (if (screenHdr(ctx)) mapOf(HDR to true) else emptyMap())
        "fastest" -> mapOf(RES to NATIVE_V, FPS to NATIVE_V, KBPS to 60000, CODEC to "forceh265", PACING to "latency", ULL to true)
        else -> mapOf(RES to NATIVE_V, FPS to NATIVE_V, Y444 to true, PW_BOOST to true, PW_DITHER to "low", PW_FRONT to false)
    }

    private fun stored(ctx: Context, st: Style): Map<String, Any> = try {
        val j = org.json.JSONObject(app(ctx).getString("mc_pcstyle_${st.id}", null) ?: "{}")
        j.keys().asSequence().mapNotNull { k -> when (val v = j.get(k)) { is Int, is Boolean, is String -> k to v; is Number -> k to v.toInt(); else -> null } }.toMap()
    } catch (e: Exception) { emptyMap() }

    private fun nativeHz(ctx: Context) = PreferenceConfiguration.nativeFps(ctx)
    private fun fpsNum(ctx: Context, f: String?): Int = if (f == NATIVE_V) nativeHz(ctx) else f?.toIntOrNull() ?: 60

    private fun values(ctx: Context, st: Style): Map<String, Any> {
        val m = LinkedHashMap<String, Any>(defaults(ctx, st))
        m.putAll(stored(ctx, st))
        if (st.pyro && KBPS !in m) {
            val res = (m[RES] as? String)?.takeIf { it != NATIVE_V } ?: PreferenceConfiguration.nativeResolution(ctx)
            val wh = res.split('x').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 } ?: listOf(1920, 1080)
            m[KBPS] = Pyrowave.recommendedKbps(wh[0], wh[1], fpsNum(ctx, m[FPS] as? String))
        }
        if (st.pyro) { m[PW] = true; m[ULL] = false } else { m[PW] = false; m[Y444] = false }
        return m
    }

    /** The live keys and values picking [st] writes. */
    private fun applied(ctx: Context, st: Style): Map<String, Any> {
        val m = LinkedHashMap(values(ctx, st))
        val nativeRes = PreferenceConfiguration.nativeResolution(ctx)
        (m[RES] as? String)?.let { res ->
            val native = res == NATIVE_V || res == nativeRes
            m[RES] = if (native) nativeRes else res
            m[PreferenceConfiguration.NATIVE_RES_PREF_STRING] = native
        }
        (m[FPS] as? String)?.let { f ->
            m[FPS] = fpsNum(ctx, f).toString()
            m[PreferenceConfiguration.NATIVE_FPS_PREF_STRING] = f == NATIVE_V
        }
        return m
    }

    /** What a live key holds, as Settings reads it for matching (the classic rows' defaults when unset). */
    private fun read(ctx: Context, k: String, sample: Any): Any? {
        val p = live(ctx)
        return try {
            when (sample) {
                is Int -> p.getInt(k, 0)
                is Boolean -> p.getBoolean(k, k == Y444 || k == PW_BOOST)
                else -> p.getString(k, when (k) {
                    RES -> PreferenceConfiguration.nativeResolution(ctx); FPS -> "60"; CODEC -> "auto"; PACING -> "latency"
                    "list_video_scale_mode" -> if (p.getBoolean("checkbox_stretch_video", false)) "stretch" else "fit"
                    com.limelight.binding.video.FsrRenderer.PREF -> "off"; PW_DITHER -> "low"; else -> null })
            }
        } catch (e: ClassCastException) { null }
    }

    private fun matches(ctx: Context, st: Style) = applied(ctx, st).all { (k, v) -> read(ctx, k, v) == v }

    /** The style in use: the one picked last when the settings still match it, else one they match, else the picked one. */
    @JvmStatic
    fun current(ctx: Context): Style {
        val l = list(ctx)
        val picked = l.firstOrNull { it.id == app(ctx).getString("mc_style_pc", null) }
        if (picked != null && matches(ctx, picked)) return picked
        return l.firstOrNull { matches(ctx, it) } ?: picked
            ?: l.maxByOrNull { s -> applied(ctx, s).count { (k, v) -> read(ctx, k, v) == v } } ?: l.first()
    }

    /** "Balanced", or "Balanced (changed)" when something was fine-tuned away from it. */
    @JvmStatic
    fun currentLabel(ctx: Context): String {
        val c = current(ctx)
        return if (matches(ctx, c)) name(ctx, c) else "${name(ctx, c)} (changed)"
    }

    /** Picks [st]: its values become the live settings (as Settings' PC card does). */
    @JvmStatic
    fun use(ctx: Context, st: Style) {
        val e = live(ctx).edit()
        applied(ctx, st).forEach { (key, v) ->
            when {
                key == KBPS && v == 0 -> e.remove(key)
                v is Int -> e.putInt(key, v); v is Boolean -> e.putBoolean(key, v); v is String -> e.putString(key, v)
            }
        }
        e.apply()
        app(ctx).edit().putString("mc_style_pc", st.id).apply()
    }

    /**
     * The Quick menu's "Play style" item: the style in use; A lists the styles (current one ticked). Picking another
     * applies it and [restart]s the stream with it (the game keeps running; the PC resumes it).
     */
    @JvmStatic
    fun item(menu: QuickMenu, ctx: Context, back: Runnable, restart: java.util.function.Consumer<String>): QuickMenu.Item {
        val cur = current(ctx)
        return QuickMenu.Item(glyph(ctx, cur), "Play style", currentLabel(ctx) + ". Changing it reconnects in about 2 s", false) {
            val now = current(ctx)
            val exact = matches(ctx, now)
            val items = ArrayList<QuickMenu.Item>()
            items.add(QuickMenu.Item("↩", "Back", "To the menu", false, back).staysOpen(null))
            for (st in list(ctx)) {
                val isNow = st.id == now.id
                items.add(QuickMenu.Item(if (isNow) "✓" else glyph(ctx, st), name(ctx, st),
                        when { isNow && exact -> "Now"; isNow -> "Now (changed): picking it again restores it"; st.pyro -> "Wired or fast Wi-Fi only"; else -> null }, false) {
                    if (!(isNow && exact)) {
                        use(ctx, st)
                        com.limelight.LimeLog.info("Quick menu: play style ${st.id}, reconnecting")
                        restart.accept("Switching to ${name(ctx, st)}…")
                    }
                })
            }
            menu.show("Play style", "Fine-tune, rename and recolour them in Settings. Picking one reconnects in about 2 seconds; the game keeps running.",
                    QuickMenu.Stats { emptyList() }, items)
        }.staysOpen(null)
    }
}
