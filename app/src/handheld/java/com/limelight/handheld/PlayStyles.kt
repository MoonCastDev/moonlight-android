package com.limelight.handheld

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.view.Display
import android.view.WindowManager
import com.limelight.preferences.PreferenceConfiguration

/**
 * The PC's play styles (Settings, PC card, Play style; and the in-game Quick menu): Battery, Balanced, Sharpest,
 * Fastest and PyroWave (experimental), each a preset of the PC stream settings kept on its own in the app's prefs
 * (mc_tv), so it can be fine-tuned, renamed and given its own icon and colour. Picking one copies its values into the
 * live settings (the classic list's keys).
 *
 * Storage, per style id: mc_pcstyle_<id> (JSON: what was fine-tuned on top of [defaults]), mc_pcstyle_name_<id>,
 * mc_pcstyle_icon_<id>, mc_pcstyle_color_<id>; mc_style_pc is the one in use. A key a style doesn't hold (Picture
 * shape until it's fine-tuned, for one) is left as it is when the style is picked.
 */
internal object PlayStyles {
    class Style(val id: String, val defName: String, val icon: String, val color: String, val pyro: Boolean = false)

    const val RES = "list_resolution"
    const val FPS = "list_fps"
    const val KBPS = "seekbar_bitrate_kbps"
    const val CODEC = "video_format"
    const val HDR = "checkbox_enable_hdr"
    const val SHAPE = "list_video_scale_mode"
    const val PACING = "frame_pacing"
    const val RANGE = "checkbox_full_range"
    const val ULL = "checkbox_ultra_low_latency"
    val FSR = com.limelight.binding.video.FsrRenderer.PREF
    val FSR_SHARP = com.limelight.binding.video.FsrRenderer.SHARPNESS_PREF
    val PW = com.limelight.home.Pyrowave.PREF
    val PW_DITHER = com.limelight.home.Pyrowave.DITHER_PREF
    val PW_FRONT = com.limelight.home.Pyrowave.FRONT_BUFFER_PREF
    val PW_BOOST = com.limelight.home.Pyrowave.GPU_BOOST_PREF
    val Y444 = com.limelight.home.Yuv444.PREF
    /** A style's resolution or frame rate: Native (the screen's own, whichever screen a stream starts on). */
    const val NATIVE_V = "native"
    const val IN_USE = "mc_style_pc"

    /** The icons a style can show (Change icon), drawn by [StyleIcon]. */
    val ICONS = listOf("leaf" to "Leaf", "half" to "Half circle", "star" to "Sparkle", "bolt" to "Bolt", "wave" to "Wave",
            "flame" to "Flame", "star5" to "Star", "heart" to "Heart", "pad" to "Gamepad", "monitor" to "Monitor", "moon" to "Moon", "rocket" to "Rocket")

    /** The colours a style's icon can have. Cyan is the theme's own (a little darker on Light). */
    val COLORS = listOf("cyan" to 0xFF2FD3E6.toInt(), "green" to Sw.C_GREEN, "purple" to 0xFF8B5CF6.toInt(), "orange" to 0xFFF29A2E.toInt(),
            "red" to 0xFFE9605A.toInt(), "pink" to 0xFFEC5FA8.toInt(), "blue" to Sw.C_BLUE, "yellow" to 0xFFF2C94C.toInt())

    private fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences("mc_tv", Context.MODE_PRIVATE)
    private fun stream(ctx: Context): SharedPreferences = android.preference.PreferenceManager.getDefaultSharedPreferences(ctx)

    fun list(ctx: Context): List<Style> = listOfNotNull(
            Style("battery", "Battery", "leaf", "green"),
            Style("balanced", "Balanced", "half", "cyan"),
            Style("sharpest", "Sharpest", "star", "purple"),
            Style("fastest", "Fastest", "bolt", "orange"),
            // only where this device's GPU decodes PyroWave (the probe passed)
            if (com.limelight.home.Pyrowave.deviceCan(ctx)) Style("pyro", "PyroWave", "wave", "blue", pyro = true) else null)

    fun byId(ctx: Context, id: String?): Style? = list(ctx).firstOrNull { it.id == id }

    /** Every key a style's Fine-tune sets (and Save into a style copies). */
    fun keys(st: Style): List<String> =
        listOf(RES, FPS, KBPS) + (if (st.pyro) emptyList() else listOf(CODEC)) + listOf(HDR, SHAPE, PACING, FSR, FSR_SHARP, RANGE) +
                (if (st.pyro) listOf(Y444, PW_BOOST, PW_DITHER, PW_FRONT) else listOf(ULL))

    @Suppress("DEPRECATION")
    private fun display(ctx: Context): Display? = try {
        (if (Build.VERSION.SDK_INT >= 30) try { ctx.display } catch (e: UnsupportedOperationException) { null } else null)
                ?: (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
    } catch (e: Exception) { null }

    @Suppress("DEPRECATION")
    fun screenHdr(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 24 && try {
        display(ctx)?.hdrCapabilities?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) == true
    } catch (e: Exception) { false }

    /** A style's own values before any fine-tuning. [NATIVE_V] for the resolution or frame rate is Native. */
    fun defaults(ctx: Context, st: Style): Map<String, Any> = when (st.id) {
        "battery" -> mapOf(RES to NATIVE_V, FPS to NATIVE_V, KBPS to 15000, CODEC to "neverh265", HDR to false, PACING to "balanced", ULL to false)
        "balanced" -> mapOf(RES to NATIVE_V, FPS to NATIVE_V, KBPS to 40000, CODEC to "forceh265", PACING to "balanced", ULL to false)
        "sharpest" -> mapOf(RES to NATIVE_V, FPS to NATIVE_V, KBPS to 80000, CODEC to "forceh265", PACING to "balanced", ULL to false) +
                (if (screenHdr(ctx)) mapOf(HDR to true) else emptyMap())
        "fastest" -> mapOf(RES to NATIVE_V, FPS to NATIVE_V, KBPS to 60000, CODEC to "forceh265", PACING to "latency", ULL to true)
        // the bitrate follows the resolution and frame rate (Pyrowave.recommendedKbps) until it's fine-tuned
        else -> mapOf(RES to NATIVE_V, FPS to NATIVE_V, Y444 to true, PW_BOOST to true, PW_DITHER to "low", PW_FRONT to false)
    }

    fun stored(ctx: Context, st: Style): Map<String, Any> = try {
        val j = org.json.JSONObject(prefs(ctx).getString("mc_pcstyle_${st.id}", null) ?: "{}")
        j.keys().asSequence().mapNotNull { k -> when (val v = j.get(k)) { is Int, is Boolean, is String -> k to v; is Number -> k to v.toInt(); else -> null } }.toMap()
    } catch (e: Exception) { emptyMap() }

    fun store(ctx: Context, st: Style, m: Map<String, Any>) {
        val j = org.json.JSONObject()
        m.forEach { (k, v) -> j.put(k, v) }
        prefs(ctx).edit().putString("mc_pcstyle_${st.id}", j.toString()).apply()
    }

    /** Reset to default: values, icon and colour (the name stays). */
    fun reset(ctx: Context, st: Style) {
        prefs(ctx).edit().remove("mc_pcstyle_${st.id}").remove("mc_pcstyle_icon_${st.id}").remove("mc_pcstyle_color_${st.id}").apply()
    }

    fun nativeHz(ctx: Context) = PreferenceConfiguration.nativeFps(ctx)
    fun fpsNum(ctx: Context, f: String?): Int = if (f == NATIVE_V) nativeHz(ctx) else f?.toIntOrNull() ?: 60

    fun sizeOf(res: String): Pair<Int, Int> =
        res.split('x').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: (1920 to 1080)

    /** A style's values (defaults, then what was fine-tuned), as stored: the resolution and frame rate may be [NATIVE_V]. */
    fun values(ctx: Context, st: Style): Map<String, Any> {
        val m = LinkedHashMap<String, Any>(defaults(ctx, st))
        m.putAll(stored(ctx, st))
        if (st.pyro && KBPS !in m) {
            val (w, h) = sizeOf((m[RES] as? String)?.takeIf { it != NATIVE_V } ?: PreferenceConfiguration.nativeResolution(ctx))
            m[KBPS] = com.limelight.home.Pyrowave.recommendedKbps(w, h, fpsNum(ctx, m[FPS] as? String))
        }
        // PyroWave and its 4:4:4 come only with the PyroWave style; it has no Ultra low latency
        if (st.pyro) { m[PW] = true; m[ULL] = false } else { m[PW] = false; m[Y444] = false }
        return m
    }

    /** The live keys and values picking [st] writes: Native as the screen's size or rate, with Native's switch on. */
    fun applied(ctx: Context, st: Style): Map<String, Any> {
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

    /** What a key holds now, read as the same type as [sample]; the classic rows' defaults when it was never set. */
    fun read(ctx: Context, k: String, sample: Any): Any? {
        val s = stream(ctx)
        return try {
            when (sample) {
                is Int -> s.getInt(k, 0)
                is Boolean -> s.getBoolean(k, false)
                else -> s.getString(k, when (k) {
                    RES -> PreferenceConfiguration.nativeResolution(ctx); FPS -> "60"; CODEC -> "auto"; PACING -> "latency"
                    SHAPE -> if (s.getBoolean("checkbox_stretch_video", false)) "stretch" else "fit"
                    FSR -> "off"; PW_DITHER -> "off"; else -> null })
            }
        } catch (e: ClassCastException) { null }
    }

    fun matches(ctx: Context, st: Style) = applied(ctx, st).all { (k, v) -> read(ctx, k, v) == v }

    /** The style picked last (mc_style_pc) or, when the settings match another one exactly, that one; else the closest. */
    fun picked(ctx: Context, list: List<Style> = list(ctx)): Style {
        val picked = list.firstOrNull { it.id == prefs(ctx).getString(IN_USE, null) }
        if (picked != null && matches(ctx, picked)) return picked
        return list.firstOrNull { matches(ctx, it) } ?: picked
            ?: list.maxByOrNull { s -> applied(ctx, s).count { (k, v) -> read(ctx, k, v) == v } } ?: list.first()
    }

    fun inUseId(ctx: Context): String? = prefs(ctx).getString(IN_USE, null)

    fun name(ctx: Context, st: Style): String = prefs(ctx).getString("mc_pcstyle_name_${st.id}", null)?.takeIf { it.isNotBlank() } ?: st.defName

    fun rename(ctx: Context, st: Style, name: String) {
        val t = name.trim().take(24)
        prefs(ctx).edit().apply { if (t.isEmpty() || t == st.defName) remove("mc_pcstyle_name_${st.id}") else putString("mc_pcstyle_name_${st.id}", t) }.apply()
    }

    fun icon(ctx: Context, st: Style): String =
        prefs(ctx).getString("mc_pcstyle_icon_${st.id}", null)?.takeIf { k -> ICONS.any { it.first == k } } ?: st.icon

    fun setIcon(ctx: Context, st: Style, k: String) =
        prefs(ctx).edit().apply { if (k == st.icon) remove("mc_pcstyle_icon_${st.id}") else putString("mc_pcstyle_icon_${st.id}", k) }.apply()

    fun colorName(ctx: Context, st: Style): String =
        prefs(ctx).getString("mc_pcstyle_color_${st.id}", null)?.takeIf { k -> COLORS.any { it.first == k } } ?: st.color

    fun setColor(ctx: Context, st: Style, k: String) =
        prefs(ctx).edit().apply { if (k == st.color) remove("mc_pcstyle_color_${st.id}") else putString("mc_pcstyle_color_${st.id}", k) }.apply()

    /** A colour's value; cyan is [ring], the theme's own. */
    fun colorOf(name: String, ring: Int): Int = if (name == "cyan") ring else COLORS.firstOrNull { it.first == name }?.second ?: ring

    fun mbps(kbps: Int) = (if (kbps % 1000 == 0) "${kbps / 1000}" else "%.1f".format(java.util.Locale.ROOT, kbps / 1000.0)) + " Mbps"

    private fun resLabel(ctx: Context, res: String): String = when {
        res == NATIVE_V || res == PreferenceConfiguration.nativeResolution(ctx) -> "Native"
        else -> HandheldHomeActivity.RES_STEPS.firstOrNull { it.second == res }?.first ?: res.replace("x", " × ")
    }

    private fun codecLabel(v: String) = when (v) { "neverh265" -> "H.264"; "forceh265" -> "HEVC"; "forceav1" -> "AV1"; else -> "Auto" }

    /** The two lines on a style's card, from its values. */
    fun lines(ctx: Context, st: Style): List<String> {
        val m = values(ctx, st)
        val kbps = m[KBPS] as? Int ?: 0
        val l2 = ArrayList<String>()
        l2 += if (kbps == 0) "Auto bitrate" else mbps(kbps)
        if (st.pyro) { if (m[Y444] == true) l2 += "4:4:4" }
        else l2 += codecLabel(m[CODEC] as? String ?: "auto")
        if (m[HDR] == true) l2 += "HDR"
        if (!st.pyro && m[ULL] == true) l2 += "lowest delay"
        val f = m[FPS] as? String ?: NATIVE_V
        return listOf("${resLabel(ctx, m[RES] as? String ?: NATIVE_V)} · ${if (f == NATIVE_V) "Native ${nativeHz(ctx)}" else f} fps", l2.joinToString(" · "))
    }

    /** Picks [st]: its values into the live settings, and it's the one in use. */
    fun apply(ctx: Context, st: Style) {
        val e = stream(ctx).edit()
        applied(ctx, st).forEach { (key, v) ->
            when {
                key == KBPS && v == 0 -> e.remove(key)   // Auto
                v is Int -> e.putInt(key, v); v is Boolean -> e.putBoolean(key, v); v is String -> e.putString(key, v)
            }
        }
        e.commit()   // a reconnect (the Quick menu) reads them straight after
        prefs(ctx).edit().putString(IN_USE, st.id).apply()
    }

    /** The PC Quick menu's side of the play styles ([com.limelight.home.PlayStyleLink]). */
    object Link : com.limelight.home.PlayStyleLink.Handler {
        private fun ring(ctx: Context) = if (prefs(ctx).getString("hh_theme", "dark") == "light") 0xFF00A9C2.toInt() else 0xFF2FD3E6.toInt()

        override fun styles(ctx: Context): List<com.limelight.home.PlayStyleLink.Entry> {
            val all = list(ctx)
            val now = picked(ctx, all).takeIf { matches(ctx, it) }
            return all.map { st ->
                com.limelight.home.PlayStyleLink.Entry(st.id, name(ctx, st), lines(ctx, st).joinToString(" · "), icon(ctx, st),
                        colorOf(colorName(ctx, st), ring(ctx)), st === now)
            }
        }

        override fun use(ctx: Context, id: String) { byId(ctx, id)?.let { apply(ctx, it) } }

        override fun icon(ctx: Context, icon: String, color: Int): android.view.View = StyleIcon(ctx, icon, color)

        override fun fineTune(act: android.app.Activity, id: String, uuid: String?, resume: android.content.Intent) {
            act.startActivity(android.content.Intent(act, HandheldHomeActivity::class.java)
                    .putExtra(HandheldHomeActivity.EXTRA_TUNE_STYLE, id).putExtra(HandheldHomeActivity.EXTRA_TUNE_PC, uuid)
                    .putExtra(HandheldHomeActivity.EXTRA_RESUME, resume))
        }
    }
}
