package com.limelight.handheld

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import com.limelight.home.ConsolePicture
import com.limelight.home.FeelSounds
import com.limelight.home.Profile
import com.limelight.home.Ps5Store
import com.limelight.home.Psn
import com.limelight.home.QuickBitrate
import com.limelight.home.StatsLevel
import com.limelight.home.Xbox
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.preferences.PreferenceConfiguration

/**
 * Settings as one card per device (Saleh's approved combo mockup): a card for each paired PC, each PS5, each Xbox and
 * this handheld, each showing what's set today. Opening a card shows its Play style (five ready-made sets of the
 * existing settings), Fine-tune (on/off tiles and a list of values) and the rest of that device's rows. Nothing here
 * has storage of its own: every pick writes the same preference keys as the classic list, which stays one chip away
 * ("Classic settings") exactly as it was.
 */
internal class SettingsCards(private val a: HandheldHomeActivity) {
    companion object {
        /** Corner radius of the cards, style cards and tiles, in design px (the focus ring follows it). */
        const val CORNER = 24
        private const val PURPLE = 0xFF8B5CF6.toInt()
        private const val ORANGE = 0xFFF29A2E.toInt()
        private val CUSTOM = listOf("custom1", "custom2", "custom3")
        private val DECODER = com.limelight.Ps5StreamActivity.DECODER_PREF
        // the PC stream keys the play styles set (the classic list's)
        private const val RES = "list_resolution"
        private const val FPS = "list_fps"
        private const val KBPS = "seekbar_bitrate_kbps"
        private const val CODEC = "video_format"
        private const val HDR = "checkbox_enable_hdr"
        private const val SHAPE = "list_video_scale_mode"
        private const val PACING = "frame_pacing"
        private const val RANGE = "checkbox_full_range"
        private const val ULL = "checkbox_ultra_low_latency"
        private val FSR = com.limelight.binding.video.FsrRenderer.PREF
        private val FSR_SHARP = com.limelight.binding.video.FsrRenderer.SHARPNESS_PREF
        private val PW = com.limelight.home.Pyrowave.PREF
        private val PW_DITHER = com.limelight.home.Pyrowave.DITHER_PREF
        private val PW_FRONT = com.limelight.home.Pyrowave.FRONT_BUFFER_PREF
        private val PW_BOOST = com.limelight.home.Pyrowave.GPU_BOOST_PREF
        private val Y444 = com.limelight.home.Yuv444.PREF
        /** A play style's resolution: Native (the screen's own size, whichever screen a stream starts on). */
        private const val NATIVE_V = "native"
    }

    private val sw get() = a.sw
    private val stream get() = a.stream
    private val ui = Handler(Looper.getMainLooper())

    /** The classic list is showing (the "Classic settings" chip). */
    var classic = false
        private set
    /** "home" (the cards), "pc:<uuid>" ("pc:any" with no PC paired), "ps:<host>", "xb:<id>", "dev", "pads", "tvs", "about". */
    private var screen = "home"
    private var tab = ""
    /** The card last opened, where focus goes back to on the cards. */
    private var lastCard: String? = null
    private var fresh = false
    /** X on a Fine-tune tile: its details, by the tile's tag. */
    private val details = HashMap<String, () -> Unit>()
    /** The PC play style whose own Fine-tune is showing (hold or X on its card), on the Play style tab. */
    private var editing: String? = null
    /** The PC play style last fine-tuned or renamed, where focus goes back to on the style cards. */
    private var lastStyle: String? = null

    fun save() = "${if (classic) 1 else 0}|$screen|$tab|${lastCard ?: ""}|${editing ?: ""}"
    fun restore(s: String) {
        val p = s.split('|')
        if (p.size < 3) return
        classic = p[0] == "1"; screen = p[1]; tab = p[2]; lastCard = p.getOrNull(3)?.ifEmpty { null }
        editing = p.getOrNull(4)?.ifEmpty { null }
    }

    fun sig() = "$classic/$screen/$tab/${editing ?: ""}"

    /** True once after moving to another screen: the page builds in and focus starts on its first item. */
    fun takeFresh(): Boolean { val f = fresh; fresh = false; return f }

    /** Settings opens on the cards, or straight on [to] (Home's Controllers, Controller settings). */
    fun reset(to: String = "home") {
        classic = false; screen = to; tab = tabsOf(to).firstOrNull()?.first ?: ""; fresh = false; editing = null
    }

    private fun open(s: String) {
        if (screen == "home") lastCard = s
        screen = s; tab = tabsOf(s).firstOrNull()?.first ?: ""; editing = null
        fresh = true
        a.render(force = true)
    }

    /** B: one level up (classic or a card's screen back to the cards). False on the cards: Settings closes. */
    fun back(): Boolean = when {
        classic -> { classic = false; fresh = true; a.render(force = true); true }
        editing != null -> { lastStyle = editing; editing = null; fresh = true; a.render(force = true); true }
        screen != "home" -> { screen = "home"; tab = ""; fresh = true; a.render(force = true); true }
        else -> false
    }

    private fun kind(s: String = screen) = s.substringBefore(':')
    private fun pcOf(s: String = screen): ComputerDetails? = if (kind(s) == "pc") a.pcs[s.substringAfter(':')] else null
    private fun psOf(s: String = screen): Ps5Store.Console? = if (kind(s) == "ps") a.consoles.firstOrNull { it.host == s.substringAfter(':') } else null
    private fun xbOf(s: String = screen): Xbox.Console? = if (kind(s) == "xb") a.xboxes.firstOrNull { it.id == s.substringAfter(':') } else null

    private fun tabsOf(s: String): List<Pair<String, String>> = when (kind(s)) {
        "pc" -> listOfNotNull("play" to "Play style", "fine" to "Fine-tune", "away" to "Away from home", pcOf(s)?.let { "power" to "Power & wake" })
        "ps" -> listOf("play" to "Play style", "fine" to "Fine-tune", "away" to "Away from home", "power" to "Power & sign-in")
        "xb" -> listOf("play" to "Play style", "fine" to "Fine-tune", "sign" to "Sign-in")
        "dev" -> listOf("screen" to "Screen", "sound" to "Sound", "look" to "Look")
        else -> emptyList()
    }

    fun title(): String = when {
        classic -> "Classic settings"
        screen == "home" -> "Settings"
        else -> when (kind()) {
            "pc" -> pcOf()?.name ?: "PC"
            "ps" -> psOf()?.name ?: "PlayStation"
            "xb" -> xbOf()?.name ?: "Xbox"
            "dev" -> "This ${a.deviceShort()}"
            "pads" -> "Controllers"
            "tvs" -> "TVs"
            "about" -> "About"
            else -> "Settings"
        }
    }

    fun hints(): List<Pair<String, String>> {
        val h = ArrayList<Pair<String, String>>()
        if (screen != "home" && tabsOf(screen).size > 1) h += "L R" to "Tabs"
        val pcPlay = kind() == "pc" && tab == "play"
        if (pcPlay && editing != null) h += "Y" to "Rename"
        else if (pcPlay) { h += "Y" to "Rename"; h += "X" to "Fine-tune style" }
        else if (screen != "home" && tab == "play") h += "X" to "Fine-tune"
        if (screen != "home" && tab == "fine") { h += "Y" to if (kind() == "pc") "Save into a style" else "Save as my own"; h += "X" to "Details" }
        h += "B" to "Back"
        h += "A" to when { screen == "home" -> "Open"; pcPlay && editing != null -> "Change"; tab == "play" -> "Use"; else -> "OK" }
        return h
    }

    /** X and Y on a card's screen. */
    fun key(code: Int): Boolean {
        if (screen == "home") return false
        when (code) {
            KeyEvent.KEYCODE_BUTTON_X -> {
                if (tab == "play" && kind() == "pc") {
                    if (editing == null) focusedStyle()?.let { editStyle(it) } ?: showTab("fine")
                    return true
                }
                if (tab == "play") { showTab("fine"); return true }
                if (tab == "fine") { (a.currentFocus?.tag as? String)?.let { details[it] }?.invoke(); return true }
            }
            KeyEvent.KEYCODE_BUTTON_Y -> {
                if (tab == "play" && kind() == "pc") {
                    (editing?.let { id -> pcStyles().firstOrNull { it.id == id } } ?: focusedStyle())?.let { renameStyle(it) }
                    return true
                }
                if (tab == "fine") { if (kind() == "pc") saveIntoStyle() else saveAsOwn(); return true }
            }
        }
        return false
    }

    /** L1 / R1: the tab before or after. */
    fun shoulder(step: Int) {
        val tabs = tabsOf(screen).map { it.first }
        if (screen == "home" || tabs.size < 2) return
        FeelSounds.play(a, FeelSounds.Cue.TAB, 0.3f * step)
        showTab(tabs[(tabs.indexOf(tab) + step + tabs.size) % tabs.size])
    }

    private fun showTab(t: String) {
        if (tabsOf(screen).none { it.first == t }) return
        tab = t; fresh = true; editing = null
        a.render(force = true)
    }

    // ---------------------------------------------------------------- pages

    fun page(): View {
        details.clear()
        if (screen == "home") return homePage()
        val tabs = tabsOf(screen)
        if (tabs.isNotEmpty() && tabs.none { it.first == tab }) tab = tabs.first().first
        if (tab != "play" || kind() != "pc" || pcStyles().none { it.id == editing }) editing = null
        val col = sw.col().apply { clipChildren = false }
        if (tabs.size > 1) col.addView(a.part(tabRow(tabs)), sw.lp(-1, -2).apply { leftMargin = sw.px(sw.edge); topMargin = sw.px(6) })
        val body: View = when (kind()) {
            "pc" -> when (tab) {
                "play" -> editing?.let { id -> pcStyles().firstOrNull { it.id == id } }?.let { styleTunePage(it) } ?: playPage("pc")
                "fine" -> finePage("pc")
                "away" -> list { pcAwayRows(it) }
                else -> list { pcPowerRows(it) }
            }
            "ps" -> when (tab) {
                "play" -> playPage("ps"); "fine" -> finePage("ps")
                "away" -> list { a.profileRows(it); a.psAwayRows(it) }
                else -> list { psPowerRows(it) }
            }
            "xb" -> when (tab) {
                "play" -> playPage("xb"); "fine" -> finePage("xb")
                else -> list { a.xboxRow(it); a.info(it, "Frame pacing", "The Xbox paces its own picture"); a.info(it, "HDR", "Not available on Xbox Remote Play") }
            }
            "dev" -> list { a.settingsRows(it, when (tab) { "sound" -> "sound"; "look" -> "theme"; else -> "handheld" }) }
            "pads" -> list { a.settingsRows(it, "pads") }
            "tvs" -> list { tvRows(it) }
            "about" -> list { a.settingsRows(it, "about") }
            else -> list { }
        }
        col.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        return col
    }

    /** The tabs along the top. Coming up from below, focus lands on the tab that is showing, not the nearest one. */
    private class TabRow(ctx: Context) : LinearLayout(ctx) {
        var active: View? = null
        override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
            val act = active
            if (act == null || hasFocus() || isInTouchMode) super.addFocusables(views, direction, focusableMode)
            else act.addFocusables(views, direction, focusableMode)
        }
    }

    private val tabRender = Runnable { if (screen != "home" && !classic) a.render(force = true) }

    private fun tabRow(tabs: List<Pair<String, String>>): View {
        val row = TabRow(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        tabs.forEachIndexed { i, (id, label) ->
            val on = id == tab
            val v = sw.text(label, 26, if (on) onRing() else sw.ink, 800).apply {
                tag = "tab:$id"
                gravity = Gravity.CENTER
                setPadding(sw.px(30), 0, sw.px(30), 0)
                background = sw.round(if (on) sw.ring else sw.panel, 32)
                sw.focusable(this); sw.pressable(this)
                // moving along the tabs shows each one once focus rests on it (as the classic groups do)
                setOnFocusChangeListener { _, has -> if (has && tab != id) { tab = id; ui.removeCallbacks(tabRender); ui.postDelayed(tabRender, 160) } }
                setOnClickListener { if (tab != id) { tab = id; a.render(force = true) } }
            }
            if (on) row.active = v
            row.addView(v, LinearLayout.LayoutParams(-2, sw.tap(64)).apply { if (i > 0) leftMargin = sw.px(14) })
        }
        return row
    }

    /** A list of rows (the classic row helpers), scrolling, as wide as reads well. */
    private fun list(fill: (LinearLayout) -> Unit): View {
        val l = sw.col().apply { tag = "settings:list" }
        fill(l)
        a.primaryView = firstFocusable(l)
        val w = minOf(sw.designWidth - 2 * sw.edge, 1400f)
        val scroll = ColScroll(a, sw.px(40)).apply {
            isVerticalScrollBarEnabled = false; isFocusable = false; clipToPadding = false; clipChildren = false
            setPadding(sw.px(sw.edge), sw.px(14), sw.px(sw.edge), sw.px(30))
            addView(l, FrameLayout.LayoutParams(sw.px(w), -2))
        }
        return a.part(clip(scroll))
    }

    private fun clip(v: View) = FrameLayout(a).apply { clipChildren = true; addView(v, FrameLayout.LayoutParams(-1, -1)) }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable && v.isClickable && v.visibility == View.VISIBLE) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Text on the cyan fill: white on Light, near-black on Dark and OLED (their cyan is light). */
    private fun onRing() = if (sw.light) 0xFFFFFFFF.toInt() else 0xFF0B2A30.toInt()

    private fun chip(tag: String, label: String, main: Boolean = false, onClick: () -> Unit): View = sw.text(label, 26, if (main) sw.bg else sw.ink, 800).apply {
        this.tag = tag
        gravity = Gravity.CENTER
        setPadding(sw.px(30), 0, sw.px(30), 0)
        background = sw.round(if (main) sw.ink else sw.panel, 32)
        sw.focusable(this); sw.pressable(this)
        setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------- the cards

    private fun homePage(): View {
        val col = sw.col().apply { clipChildren = false }
        val cards = ArrayList<Pair<String, (Int, Int) -> View>>()
        val pcs = a.pcs.values.filter { a.paired(it) }.sortedBy { it.name.lowercase() }
        if (pcs.isEmpty()) cards += "pc:any" to { w, h -> pcCard(null, w, h) }
        else pcs.forEach { d -> cards += "pc:${d.uuid}" to { w, h -> pcCard(d, w, h) } }
        a.consoles.forEach { c -> cards += "ps:${c.host}" to { w, h -> psCard(c, w, h) } }
        a.xboxes.forEach { c -> cards += "xb:${c.id}" to { w, h -> xbCard(c, w, h) } }
        cards += "dev" to { w, h -> devCard(w, h) }

        val gap = 30
        val e = sw.edge
        val per = minOf(cards.size, 4)
        val cw = ((sw.designWidth - 2 * e - gap * (per - 1)) / per).toInt().coerceIn(360, 470)
        val ch = 440
        val row = sw.row().apply { clipChildren = false; setPadding(sw.px(e), 0, sw.px(e), 0) }
        val views = cards.mapIndexed { i, (_, make) ->
            make(cw, ch).also { row.addView(it, sw.lp(cw, ch).apply { if (i > 0) leftMargin = sw.px(gap) }) }
        }
        val scroll = RowScroll(a, sw.px(e)).apply {
            isHorizontalScrollBarEnabled = false; isFocusable = false; clipChildren = false; clipToPadding = false
            isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(sw.px(90))
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
            setPadding(0, 0, 0, sw.px(24))
        }
        col.addView(a.part(scroll), sw.lp(-1, ch + 24).apply { topMargin = sw.px(if (sw.compact) 16 else 30) })
        a.primaryView = views.firstOrNull { it.tag == "card:$lastCard" } ?: views.first()

        val chips = sw.row().apply { clipChildren = false }
        listOf(Triple("pads", "Controllers", false), Triple("tvs", "TVs", false), Triple("about", "About", false), Triple("classic", "Classic settings", false))
                .forEachIndexed { i, (k, label, main) ->
                    chips.addView(chip("chip:$k", label, main) {
                        if (k == "classic") { classic = true; fresh = true; a.render(force = true) } else open(k)
                    }, LinearLayout.LayoutParams(-2, sw.tap(64)).apply { if (i > 0) leftMargin = sw.px(18) })
                }
        col.addView(a.part(chips), sw.lp(-1, -2).apply { leftMargin = sw.px(e); topMargin = sw.px(20) })
        val help = "Open a card to change how that device plays. Each card shows what's set today." +
                (if (pcs.size > 1) " Your PCs share one set of PC settings." else "") +
                " Classic settings is the full list, as before."
        col.addView(a.part(sw.text(help, 24, sw.sub, 600, 3)), sw.lp(-1, -2).apply { leftMargin = sw.px(e); rightMargin = sw.px(e); topMargin = sw.px(26) })
        return col
    }

    private fun deviceCard(key: String, badge: String, name: String, art: Drawable, lines: List<Pair<String, Boolean>>,
                           foot: String, footRing: Boolean, w: Int, h: Int): View {
        val card = sw.col().apply {
            tag = "card:$key"
            background = sw.round(sw.panel, CORNER)
            clipToOutline = true; outlineProvider = a.rounded(CORNER)
            elevation = sw.pxf(8)
            contentDescription = name
        }
        val head = FrameLayout(a).apply { background = art }
        head.addView(sw.badge(badge, 20), sw.flp(-2, 40).apply { leftMargin = sw.px(22); topMargin = sw.px(20) })
        head.addView(sw.text(name, 36, 0xFFFFFFFF.toInt(), 800).apply { setShadowLayer(sw.pxf(8), 0f, sw.pxf(2), 0x59000000) },
                sw.flp(-1, -2, Gravity.BOTTOM).apply { leftMargin = sw.px(26); rightMargin = sw.px(26); bottomMargin = sw.px(22) })
        card.addView(head, sw.lp(-1, 160))
        val body = sw.col().apply { setPadding(sw.px(26), sw.px(22), sw.px(26), sw.px(22)) }
        lines.forEachIndexed { i, (t, strong) ->
            body.addView(sw.text(t, 25, if (strong) sw.ink else sw.sub, if (strong) 700 else 600), sw.lp(-1, -2).apply { if (i > 0) topMargin = sw.px(10) })
        }
        body.addView(View(a), LinearLayout.LayoutParams(1, 0, 1f))
        body.addView(sw.text(foot.uppercase(), 20, if (footRing) sw.ring else sw.sub, 800))
        card.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        sw.focusable(card); sw.pressable(card)
        card.setOnClickListener { open(key) }
        return card
    }

    private fun pcCard(d: ComputerDetails?, w: Int, h: Int): View {
        val p = PreferenceConfiguration.readPreferences(a)
        val kbps = int("seekbar_bitrate_kbps", 0)
        val hdr = bool("checkbox_enable_hdr", false)
        return deviceCard(if (d == null) "pc:any" else "pc:${d.uuid}", "PC", d?.name ?: "PC games",
                sw.art(d?.name ?: "PC", 0),
                listOf("${minOf(p.width, p.height)}p · ${p.fps} fps" to true,
                        "${if (kbps == 0) "Auto bitrate" else a.mbps(kbps)} · ${codecLabel(str("video_format", "auto"))}" to true,
                        "HDR ${if (hdr) "on" else "off"} · ${shapeLabel(pcShape())}" to false,
                        pcNowLabel() to false),
                "Profile: ${Profile.name(a)}", true, w, h)
    }

    private fun psCard(c: Ps5Store.Console, w: Int, h: Int): View {
        val away = Profile.isAway(a)
        val res = str(if (away) "ps5_away_quality" else "ps5_resolution", if (away) "auto" else "1080")
        val fps = str(if (away) "away_fps" else "ps5_fps_home", "60")
        val brKey = if (away) "away_bitrate" else "ps5_bitrate_home"
        val codec = str(if (away) "away_codec" else "ps5_codec", "h265")
        val own = listOfNotNull(
                str("ps5_shape", "same").takeIf { it != "same" }?.let { shapeLabel(it) },
                str("ps5_fsr", "same").takeIf { it != "same" }?.let { if (it == "fsr1") "FSR" else "no FSR" },
                str("ps5_pacing", "same").takeIf { it != "same" }?.let { pacingLabel(it) })
        return deviceCard("ps:${c.host}", if (c.ps5) "PS5" else "PS4", c.name, sw.art("ps", 0),
                listOf("${if (away) "Away: " else ""}${if (res == "auto") "Auto" else "${res}p"} · $fps fps" to true,
                        "${psBitrateLabel(brKey)} · ${if (codec == "h264") "H.264" else "HEVC"}" to true,
                        (if (own.isEmpty()) "Picture same as PC" else "Own picture: " + own.joinToString(" · ")) to false,
                        when (str("ps5_on_disconnect", "ask")) { "rest" -> "Rest mode on exit"; "keep" -> "Stays on after a game"; else -> "Asks on exit" } to false),
                if (Psn.signedIn(a)) "Signed in · PSN" else "PSN: not signed in", false, w, h)
    }

    private fun xbCard(c: Xbox.Console, w: Int, h: Int): View {
        val br = int("xbox_bitrate", 0)
        return deviceCard("xb:${c.id}", "Xbox", c.name, sw.art("xb", 0),
                listOf(xbPictureLabel(str("xbox_resolution", "auto")) to true,
                        (if (br == 0) "Auto bitrate" else "Up to $br Mbps") to true,
                        "Rumble ${str("xbox_rumble", "strong")}" to false,
                        "Swap A/B ${if (bool("xbox_swap_ab", false)) "on" else "off"}" to false),
                when { Xbox.noProfile(a) -> "No Xbox profile"; Xbox.signedIn(a) -> "Signed in · Xbox"; else -> "Xbox: not signed in" }, false, w, h)
    }

    private fun devCard(w: Int, h: Int): View {
        val theme = when { sw.light -> "Light"; sw.oled -> "OLED"; else -> "Dark" }
        val touch = when (str(com.limelight.Game.TOUCH_DEFAULT_PREF, "touch")) { "mouse" -> "Mouse"; "off" -> "Touch off"; else -> "Touch" }
        val size = when (a.prefs.getString("hh_screen_size", "auto")) { "compact" -> "Compact"; "normal" -> "Normal"; "large" -> "Large"; else -> "Auto" }
        return deviceCard("dev", "Handheld", "This ${a.deviceShort()}", sw.artFrom(0xFF8A9099.toInt(), 0),
                listOf("$theme theme" to true,
                        "$touch · PiP ${if (bool(com.limelight.home.Pip.PREF, false)) "on" else "off"}" to false,
                        "Menu sounds ${if (a.prefs.getBoolean(FeelSounds.PREF, true)) "on" else "off"}" to false,
                        "Screen size: $size" to false),
                "Screen · Sound · Look", false, w, h)
    }

    // ---------------------------------------------------------------- Play style

    private class Style(val id: String, val name: String, val l1: String, val l2: String, val note: String,
                        val icon: String, val color: Int, val keys: Map<String, Any>)

    @Suppress("DEPRECATION")
    private fun display(): Display = a.windowManager.defaultDisplay

    /** The fastest refresh rate this screen can run at. */
    private fun maxHz(): Float = try {
        if (Build.VERSION.SDK_INT >= 23) display().supportedModes.map { it.refreshRate }.maxOrNull() ?: 60f else display().refreshRate
    } catch (e: Exception) { 60f }

    private fun screenHdr(): Boolean = Build.VERSION.SDK_INT >= 24 && try {
        display().hdrCapabilities?.supportedHdrTypes?.contains(Display.HdrCapabilities.HDR_TYPE_HDR10) == true
    } catch (e: Exception) { false }

    /** The PS5's and the Xbox's play styles: ready-made sets of their settings (the PC's are [PcStyle]s). */
    private fun styles(k: String): List<Style> = when (k) {
        // the PS5 streams up to 1080p, 30 or 60 fps, and its own bitrate steps
        "ps" -> listOf(
            Style("battery", "Battery", "720p · 60 fps", "15 Mbps · H.264", "Longest play time", "leaf", Sw.C_GREEN, mapOf(
                    "ps5_resolution" to "720", "ps5_fps_home" to "60", "ps5_bitrate_home" to "15", "ps5_codec" to "h264", "ps5_pacing" to "same", DECODER to "low")),
            Style("balanced", "Balanced", "1080p · 60 fps", "Auto bitrate · HEVC", "Good everywhere at home", "half", sw.ring, mapOf(
                    "ps5_resolution" to "1080", "ps5_fps_home" to "60", "ps5_bitrate_home" to "auto", "ps5_codec" to "h265", "ps5_pacing" to "same", DECODER to "low")),
            Style("sharpest", "Sharpest", "1080p · 60 fps", "50 Mbps · HEVC", "Best picture, good Wi-Fi", "star", PURPLE, mapOf(
                    "ps5_resolution" to "1080", "ps5_fps_home" to "60", "ps5_bitrate_home" to "50", "ps5_codec" to "h265", "ps5_pacing" to "same", DECODER to "low")),
            Style("fastest", "Fastest", "1080p · 60 fps", "30 Mbps · lowest delay", "Lowest delay pacing, ultra low latency decoder", "bolt", ORANGE, mapOf(
                    "ps5_resolution" to "1080", "ps5_fps_home" to "60", "ps5_bitrate_home" to "30", "ps5_codec" to "h265", "ps5_pacing" to "latency", DECODER to "ultra")))
        // the Xbox sets its own quality from the picture and a bitrate cap
        else -> listOf(
            Style("battery", "Battery", "720p", "Up to 10 Mbps", "Longest play time", "leaf", Sw.C_GREEN, mapOf("xbox_resolution" to "720", "xbox_bitrate" to 10)),
            Style("balanced", "Balanced", "Auto picture", "Auto bitrate", "The Xbox decides", "half", sw.ring, mapOf("xbox_resolution" to "auto", "xbox_bitrate" to 0)),
            Style("sharpest", "Sharpest", "1080p high", "Auto bitrate", "Best picture, good Wi-Fi", "star", PURPLE, mapOf("xbox_resolution" to "1081", "xbox_bitrate" to 0)))
    }

    /** What a key holds now, read as the same type as [sample]; the classic rows' defaults when it was never set. */
    private fun read(k: String, sample: Any): Any? = try {
        when (sample) {
            is Int -> stream.getInt(k, 0)
            is Boolean -> stream.getBoolean(k, false)
            else -> stream.getString(k, when (k) {
                "list_resolution" -> a.nativeResolution(); "list_fps" -> "60"; "video_format" -> "auto"; "frame_pacing" -> "latency"
                "list_video_scale_mode" -> pcShapeDefault(); FSR -> "off"; PW_DITHER -> "off"
                "ps5_resolution" -> "1080"; "ps5_fps_home" -> "60"; "ps5_bitrate_home" -> "auto"; "ps5_codec" -> "h265"; "ps5_pacing" -> "same"
                DECODER -> "low"; "xbox_resolution" -> "auto"; else -> null })
        }
    } catch (e: ClassCastException) { null }

    private fun matches(s: Style) = s.keys.all { (k, v) -> read(k, v) == v }

    /** The style in use, or null when the settings differ from every one. */
    private fun detect(k: String, list: List<Style> = styles(k)): Style? = list.firstOrNull { matches(it) }

    /** The style the settings started from: the last one picked here, else the closest. */
    private fun nearest(k: String, list: List<Style>): Style =
        list.firstOrNull { it.id == a.prefs.getString("mc_style_$k", null) }
            ?: list.maxByOrNull { s -> s.keys.count { (key, v) -> read(key, v) == v } } ?: list.first()

    /** What the device's settings are now, by play style: "Balanced", or "Balanced (changed)". */
    private fun nowLabel(k: String): String {
        if (k == "pc") return pcNowLabel()
        val list = styles(k)
        return detect(k, list)?.name ?: "${nearest(k, list).name} (changed)"
    }

    private fun use(k: String, s: Style) {
        val e = stream.edit()
        s.keys.forEach { (key, v) -> when (v) { is Int -> e.putInt(key, v); is Boolean -> e.putBoolean(key, v); is String -> e.putString(key, v) } }
        e.apply()
        a.prefs.edit().putString("mc_style_$k", s.id).apply()
        a.toast("${s.name}: ${s.l1} · ${s.l2}")
        a.render(force = true)
    }

    // ---------------------------------------------------------------- the PC's play styles

    /**
     * A PC play style: a preset of the PC stream settings kept on its own (in the app's own prefs, mc_tv), so it can be
     * fine-tuned and renamed. Picking it copies its values into the live settings (the same keys as the classic list).
     * Its values are [pcDefaults] with what was fine-tuned on top (mc_pcstyle_<id>, JSON); a key in neither, such as
     * Picture shape until it's fine-tuned, is left as it is when the style is picked.
     */
    private class PcStyle(val id: String, val defName: String, val note: String, val icon: String, val color: Int, val pyro: Boolean = false)

    private fun pcStyles(): List<PcStyle> = listOfNotNull(
            PcStyle("battery", "Battery", "Longest play time", "leaf", Sw.C_GREEN),
            PcStyle("balanced", "Balanced", "Good everywhere at home", "half", sw.ring),
            PcStyle("sharpest", "Sharpest", "Best picture, good Wi-Fi", "star", PURPLE),
            PcStyle("fastest", "Fastest", "For shooters and racing", "bolt", ORANGE),
            // only where this device's GPU decodes PyroWave (the probe passed)
            if (com.limelight.home.Pyrowave.deviceCan(a)) PcStyle("pyro", "PyroWave", "Wired or fast Wi-Fi only", "wave", Sw.C_BLUE, pyro = true) else null)

    /** Every key a style's Fine-tune sets (and Save into a style copies). */
    private fun pcStyleKeys(st: PcStyle): List<String> =
        listOf(RES, FPS, KBPS) + (if (st.pyro) emptyList() else listOf(CODEC)) + listOf(HDR, SHAPE, PACING, FSR, FSR_SHARP, RANGE) +
                (if (st.pyro) listOf(Y444, PW_BOOST, PW_DITHER, PW_FRONT) else listOf(ULL))

    /** A style's own values before any fine-tuning. [NATIVE_V] for the resolution is Native. */
    private fun pcDefaults(st: PcStyle): Map<String, Any> = when (st.id) {
        "battery" -> mapOf(RES to NATIVE_V, FPS to "60", KBPS to 15000, CODEC to "neverh265", HDR to false, PACING to "balanced", ULL to false)
        "balanced" -> mapOf(RES to NATIVE_V, FPS to "60", KBPS to 40000, CODEC to "forceh265", PACING to "balanced", ULL to false)
        "sharpest" -> mapOf(RES to NATIVE_V, FPS to "60", KBPS to 80000, CODEC to "forceh265", PACING to "balanced", ULL to false) +
                (if (screenHdr()) mapOf(HDR to true) else emptyMap())
        "fastest" -> mapOf(RES to NATIVE_V, FPS to (if (maxHz() >= 119f) "120" else "60"), KBPS to 60000, CODEC to "forceh265",
                PACING to "latency", ULL to true)
        // the bitrate follows the resolution and frame rate (Pyrowave.recommendedKbps) until it's fine-tuned
        else -> mapOf(RES to NATIVE_V, FPS to "60", Y444 to true, PW_BOOST to true, PW_DITHER to "low", PW_FRONT to false)
    }

    private fun storedOf(st: PcStyle): Map<String, Any> = try {
        val j = org.json.JSONObject(a.prefs.getString("mc_pcstyle_${st.id}", null) ?: "{}")
        j.keys().asSequence().mapNotNull { k -> when (val v = j.get(k)) { is Int, is Boolean, is String -> k to v; is Number -> k to v.toInt(); else -> null } }.toMap()
    } catch (e: Exception) { emptyMap() }

    private fun store(st: PcStyle, m: Map<String, Any>) {
        val j = org.json.JSONObject()
        m.forEach { (k, v) -> j.put(k, v) }
        a.prefs.edit().putString("mc_pcstyle_${st.id}", j.toString()).apply()
    }

    private fun sizeOf(res: String): Pair<Int, Int> =
        res.split('x').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: (1920 to 1080)

    /** A style's values (defaults, then what was fine-tuned), as stored: the resolution may be [NATIVE_V]. */
    private fun pcStyleValues(st: PcStyle): Map<String, Any> {
        val m = LinkedHashMap<String, Any>(pcDefaults(st))
        m.putAll(storedOf(st))
        if (st.pyro && KBPS !in m) {
            val (w, h) = sizeOf((m[RES] as? String)?.takeIf { it != NATIVE_V } ?: a.nativeResolution())
            m[KBPS] = com.limelight.home.Pyrowave.recommendedKbps(w, h, (m[FPS] as? String)?.toIntOrNull() ?: 60)
        }
        // PyroWave and its 4:4:4 come only with the PyroWave style; it has no Ultra low latency
        if (st.pyro) { m[PW] = true; m[ULL] = false } else { m[PW] = false; m[Y444] = false }
        return m
    }

    /** The live keys and values picking [st] writes: Native as the screen's size with Native's switch on. */
    private fun pcApplied(st: PcStyle): Map<String, Any> {
        val m = LinkedHashMap(pcStyleValues(st))
        val res = m[RES] as? String
        if (res != null) {
            val native = res == NATIVE_V || res == a.nativeResolution()
            m[RES] = if (native) a.nativeResolution() else res
            m[PreferenceConfiguration.NATIVE_RES_PREF_STRING] = native
        }
        return m
    }

    private fun pcMatches(st: PcStyle) = pcApplied(st).all { (k, v) -> read(k, v) == v }

    /** The style picked last (mc_style_pc) or, when the settings match another one exactly, that one; else the closest. */
    private fun pcPicked(list: List<PcStyle> = pcStyles()): PcStyle {
        val picked = list.firstOrNull { it.id == a.prefs.getString("mc_style_pc", null) }
        if (picked != null && pcMatches(picked)) return picked
        return list.firstOrNull { pcMatches(it) } ?: picked
            ?: list.maxByOrNull { s -> pcApplied(s).count { (k, v) -> read(k, v) == v } } ?: list.first()
    }

    private fun pcName(st: PcStyle): String = a.prefs.getString("mc_pcstyle_name_${st.id}", null)?.takeIf { it.isNotBlank() } ?: st.defName

    private fun pcNowLabel(): String { val p = pcPicked(); return if (pcMatches(p)) pcName(p) else "${pcName(p)} (changed)" }

    private fun resLabel(res: String): String = when {
        res == NATIVE_V || res == a.nativeResolution() -> "Native"
        else -> HandheldHomeActivity.RES_STEPS.firstOrNull { it.second == res }?.first ?: res.replace("x", " × ")
    }

    /** The two lines on a style's card, from its values. */
    private fun pcLines(st: PcStyle): List<String> {
        val m = pcStyleValues(st)
        val kbps = m[KBPS] as? Int ?: 0
        val l2 = ArrayList<String>()
        l2 += if (kbps == 0) "Auto bitrate" else a.mbps(kbps)
        if (st.pyro) { if (m[Y444] == true) l2 += "4:4:4" }
        else l2 += codecLabel(m[CODEC] as? String ?: "auto").removeSuffix(" codec")
        if (m[HDR] == true) l2 += "HDR"
        if (!st.pyro && m[ULL] == true) l2 += "lowest delay"
        return listOf("${resLabel(m[RES] as? String ?: NATIVE_V)} · ${m[FPS] ?: "60"} fps", l2.joinToString(" · "))
    }

    private fun usePc(st: PcStyle, quiet: Boolean = false) {
        val e = stream.edit()
        pcApplied(st).forEach { (key, v) ->
            when {
                key == KBPS && v == 0 -> e.remove(key)   // Auto
                v is Int -> e.putInt(key, v); v is Boolean -> e.putBoolean(key, v); v is String -> e.putString(key, v)
            }
        }
        e.apply()
        a.prefs.edit().putString("mc_style_pc", st.id).apply()
        if (!quiet) { val l = pcLines(st); a.toast("${pcName(st)}: ${l[0]} · ${l[1]}") }
        a.render(force = true)
    }

    /** The PC style card that has focus, if one does. */
    private fun focusedStyle(): PcStyle? = (a.currentFocus?.tag as? String)?.takeIf { it.startsWith("style:") }
            ?.removePrefix("style:")?.let { id -> pcStyles().firstOrNull { it.id == id } }

    private fun editStyle(st: PcStyle) {
        editing = st.id; lastStyle = st.id; fresh = true
        FeelSounds.play(a, FeelSounds.Cue.TAB, 0f)
        a.render(force = true)
    }

    private fun renameStyle(st: PcStyle) {
        lastStyle = st.id
        a.entry("Rename ${pcName(st)}", "Up to 24 letters. Leave it empty for its first name, ${st.defName}.", "A name", pcName(st), "text") { v ->
            val t = v.trim()
            if (t.length > 24) "Up to 24 letters."
            else {
                a.prefs.edit().apply { if (t.isEmpty() || t == st.defName) remove("mc_pcstyle_name_${st.id}") else putString("mc_pcstyle_name_${st.id}", t) }.apply()
                a.render(force = true); null
            }
        }
    }

    /** Where Fine-tune's value rows read and write: the live settings, or one PC style's own values. */
    private interface Vals {
        fun s(k: String, d: String): String
        fun i(k: String, d: Int): Int
        fun b(k: String, d: Boolean): Boolean
        /** Saves [v]; a bitrate of 0 is Auto. A resolution equal to the screen's own size is Native. */
        fun put(k: String, v: Any)
    }

    private val live = object : Vals {
        override fun s(k: String, d: String) = str(k, d)
        override fun i(k: String, d: Int) = int(k, d)
        override fun b(k: String, d: Boolean) = bool(k, d)
        override fun put(k: String, v: Any) {
            val e = stream.edit()
            when {
                k == KBPS && v == 0 -> e.remove(k)
                v is Int -> e.putInt(k, v); v is Boolean -> e.putBoolean(k, v); v is String -> e.putString(k, v)
            }
            if (k == RES) e.putBoolean(PreferenceConfiguration.NATIVE_RES_PREF_STRING, v == a.nativeResolution())
            e.apply()
        }
    }

    /** A style's values for its Fine-tune: a key it doesn't set shows what's live (picking the style leaves it). */
    private fun styleVals(st: PcStyle) = object : Vals {
        fun v(k: String): Any? = pcStyleValues(st)[k]
        override fun s(k: String, d: String): String = (v(k) as? String)?.let { if (k == RES && it == NATIVE_V) a.nativeResolution() else it } ?: live.s(k, d)
        override fun i(k: String, d: Int): Int = v(k) as? Int ?: live.i(k, d)
        override fun b(k: String, d: Boolean): Boolean = v(k) as? Boolean ?: live.b(k, d)
        override fun put(k: String, v: Any) {
            val m = LinkedHashMap(storedOf(st))
            val pyroRate = st.pyro && KBPS !in m && (k == RES || k == FPS)
            m[k] = if (k == RES && v == a.nativeResolution()) NATIVE_V else v
            if (st.pyro && k == KBPS && v == 0) m.remove(KBPS)   // the PyroWave style's Auto is its recommended bitrate
            store(st, m)
            // the style in use: the live settings change with it
            if (a.prefs.getString("mc_style_pc", null) == st.id) {
                live.put(k, v)
                if (pyroRate || (st.pyro && k == KBPS)) live.put(KBPS, pcStyleValues(st)[KBPS] as? Int ?: 0)
            }
        }
    }

    /** Y on the PC's Fine-tune: what's set now becomes one style's own values (it doesn't switch to it). */
    private fun saveIntoStyle() {
        val opts = pcStyles().map { st ->
            HandheldHomeActivity.Opt(null, 0, pcName(st), "Its values become what's set now", null) {
                val m = LinkedHashMap<String, Any>()
                for (k in pcStyleKeys(st)) {
                    when (k) {
                        RES -> m[k] = if (bool(PreferenceConfiguration.NATIVE_RES_PREF_STRING, false)) NATIVE_V else str(RES, a.nativeResolution())
                        KBPS -> int(KBPS, 0).let { if (it > 0 || !st.pyro) m[k] = it }
                        HDR, RANGE, ULL, Y444, PW_BOOST, PW_FRONT -> m[k] = bool(k, false)
                        FSR_SHARP -> m[k] = int(k, com.limelight.binding.video.FsrRenderer.DEFAULT_SHARPNESS)
                        else -> m[k] = read(k, "") as? String ?: continue
                    }
                }
                store(st, m)
                a.prefs.edit().putString("mc_style_pc", st.id).apply()
                lastStyle = st.id
                a.toast("Saved into ${pcName(st)}")
                usePc(st, quiet = true)
            }
        }
        a.dialog("Save into a style", "The play style you pick takes the PC settings in use now, and becomes the one in use.", opts, listOf("Cancel" to {}))
    }

    private class StyleCardData(val id: String, val icon: String, val color: Int, val name: String, val tagLine: String?, val lines: List<String>,
                                val note: String, val inUse: Boolean, val onClick: () -> Unit, val onHold: (() -> Unit)? = null)

    private fun playPage(k: String): View {
        val col = sw.col().apply { clipChildren = false }
        val e = sw.edge
        val custom = Profile.isCustom(a)
        val cards = ArrayList<StyleCardData>()
        val nowName: String
        if (k == "pc") {
            val list = pcStyles()
            val picked = pcPicked(list)
            val exact = pcMatches(picked)
            nowName = if (exact) pcName(picked) else "${pcName(picked)} (changed)"
            list.forEach { st ->
                val inUse = st === picked
                cards += StyleCardData(st.id, st.icon, st.color, pcName(st), if (st.pyro) "Experimental" else null, pcLines(st),
                        when { inUse && exact -> "✓ In use"; inUse -> "✓ In use, changed"; else -> st.note }, inUse, { usePc(st) }, { editStyle(st) })
            }
        } else {
            val list = styles(k)
            val now = detect(k, list)
            val near = if (now == null) nearest(k, list) else null
            nowName = now?.name ?: "${near!!.name} (changed)"
            list.forEach { s ->
                val inUse = !custom && s === now
                cards += StyleCardData(s.id, s.icon, s.color, s.name, null, listOf(s.l1, s.l2),
                        when { inUse -> "✓ In use"; !custom && s === near -> "✓ In use, changed"; else -> s.note }, inUse || (!custom && s === near), { use(k, s) })
            }
        }
        val what = when (k) { "pc" -> "PC"; "ps" -> "PS5"; else -> "Xbox" }
        col.addView(a.part(sw.text("How should $what games play?", 34, sw.ink, 800)), sw.lp(-1, -2).apply { leftMargin = sw.px(e); topMargin = sw.px(26) })
        val sub = StringBuilder(if (k == "pc") "Pick one. Hold it (or X) to fine-tune it, Y renames it. Now: " else "Pick one. Everything else follows; you can still fine-tune it. Now: ")
        sub.append(nowName)
        if (custom) sub.append(", in your own ").append(Profile.name(a))
        else if (Profile.isAway(a)) sub.append(if (k == "ps") ". The Away profile is on: Away from home sets what the PS5 uses" else ", in the Away profile")
        if (k == "pc" && a.pcs.values.count { a.paired(it) } > 1) sub.append(". Shared by all your PCs")
        if (k == "ps") sub.append(". Picture shape, Super resolution and pacing stay the same as the PC until you change them")
        sub.append('.')
        col.addView(a.part(sw.text(sub.toString(), 23, sw.sub, 600, 2)), sw.lp(-1, -2).apply { leftMargin = sw.px(e); rightMargin = sw.px(e); topMargin = sw.px(8) })

        val gap = 24
        val n = if (k == "pc") cards.size else cards.size + 1
        val cw = ((sw.designWidth - 2 * e - gap * (n - 1)) / n).toInt().coerceAtLeast(300)
        val ch = if (sw.compact) 300 else 320
        val row = sw.row().apply { clipChildren = false; setPadding(sw.px(e), 0, sw.px(e), 0) }
        val views = cards.map { c -> styleCard("style:${c.id}", c.icon, c.color, c.name, c.tagLine, c.lines, c.note, c.inUse, false, c.onHold, c.onClick) }
        views.forEachIndexed { i, v -> row.addView(v, sw.lp(cw, ch).apply { if (i > 0) leftMargin = sw.px(gap) }) }
        val scroll = RowScroll(a, sw.px(e)).apply {
            isHorizontalScrollBarEnabled = false; isFocusable = false; clipChildren = false; clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        }
        col.addView(a.part(scroll), sw.lp(-1, ch).apply { topMargin = sw.px(26) })
        a.primaryView = (if (k == "pc") views.firstOrNull { it.tag == "style:$lastStyle" } else null)
                ?: views.firstOrNull { it.getTag(R_IN_USE) == true } ?: views.first()
        if (k == "pc") lastStyle = null

        // Also: the few things people change without opening Fine-tune
        val also = sw.row().apply { clipChildren = false }
        also.addView(sw.text("Also:", 24, sw.sub, 700), sw.lp(-2, -2).apply { rightMargin = sw.px(20) })
        fun add(v: View) = also.addView(v, LinearLayout.LayoutParams(-2, sw.tap(64)).apply { rightMargin = sw.px(16) })
        when (k) {
            "pc" -> {
                add(chip("chip:shape", "Picture shape · ${shapeLabel(pcShape())}") {
                    cycle("list_video_scale_mode", listOf("fit", "fill", "stretch"), pcShape()) })
                val lvl = StatsLevel.get(a)
                add(chip("chip:stats", "Stats · ${statsLabel(lvl)}") { StatsLevel.set(a, StatsLevel.next(lvl)); a.render(force = true) })
            }
            "ps" -> {
                add(chip("chip:shape", "Picture shape · ${consoleShapeLabel(str("ps5_shape", "same"))}") {
                    cycle("ps5_shape", listOf("same", "fit", "fill", "stretch"), str("ps5_shape", "same")) })
                add(chip("chip:exit", "On exit · ${when (str("ps5_on_disconnect", "ask")) { "rest" -> "Rest mode"; "keep" -> "Keep it on"; else -> "Ask" }}") {
                    cycle("ps5_on_disconnect", listOf("ask", "rest", "keep"), str("ps5_on_disconnect", "ask")) })
            }
            else -> {
                add(chip("chip:shape", "Picture shape · ${consoleShapeLabel(str("xbox_shape", "same"))}") {
                    cycle("xbox_shape", listOf("same", "fit", "fill", "stretch"), str("xbox_shape", "same")) })
                add(chip("chip:rumble", "Rumble · ${str("xbox_rumble", "strong").replaceFirstChar { it.uppercase() }}") {
                    cycle("xbox_rumble", listOf("off", "weak", "medium", "strong"), str("xbox_rumble", "strong")) })
            }
        }
        // Home / Away / your own: the same switch as the classic Streaming profile row
        add(chip("chip:profile", "Home / Away · ${Profile.name(a)}") {
            val m = Profile.MODES
            stream.edit().putString(Profile.PREF, m[(m.indexOf(Profile.mode(a)) + 1) % m.size]).apply()
            a.render(force = true)
        })
        add(chip("chip:fine", "Fine-tune ${nowName.removeSuffix(" (changed)")}…", main = true) { showTab("fine") })
        col.addView(a.part(RowScroll(a, sw.px(e)).apply {
            isHorizontalScrollBarEnabled = false; isFocusable = false; clipChildren = false; clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(sw.px(e), 0, sw.px(e), 0)
            addView(also)
        }), sw.lp(-1, -2).apply { topMargin = sw.px(30) })
        return col
    }

    private val R_IN_USE = com.limelight.R.id.hh_in_use

    private fun styleCard(tag: String, icon: String, color: Int, name: String, tagLine: String?, lines: List<String>, note: String, inUse: Boolean,
                          dashed: Boolean, onHold: (() -> Unit)?, onClick: () -> Unit): View = sw.col().apply {
        this.tag = tag
        setTag(R_IN_USE, inUse)
        background = if (dashed) sw.round(sw.bg, CORNER, sw.line, 3) else sw.round(sw.panel, CORNER)
        setPadding(sw.px(26), sw.px(24), sw.px(22), sw.px(22))
        addView(sw.row().apply {
            addView(FrameLayout(a).apply {
                background = sw.round(if (dashed) sw.panel else (color and 0x00FFFFFF) or 0x2E000000, 18)
                addView(StyleIcon(a, icon, color), sw.flp(44, 44, Gravity.CENTER))
            }, sw.lp(76, 76))
            // a small tag beside the icon (PyroWave's "Experimental"), kept when the style is renamed
            if (tagLine != null) addView(sw.text(tagLine.uppercase(), 18, color, 800), sw.lp(-2, -2).apply { leftMargin = sw.px(16) })
        }, sw.lp(-1, -2))
        addView(sw.text(name, 32, sw.ink, 800), sw.lp(-1, -2).apply { topMargin = sw.px(20) })
        lines.forEachIndexed { i, l -> addView(sw.text(l, 22, sw.sub, 600), sw.lp(-1, -2).apply { topMargin = sw.px(if (i == 0) 10 else 6) }) }
        addView(View(a), LinearLayout.LayoutParams(1, 0, 1f))
        addView(sw.text(if (inUse) note.uppercase() else note, 20, if (inUse) sw.ring else sw.sub, if (inUse) 800 else 600, 2))
        contentDescription = if (tagLine != null) "$name, $tagLine" else name
        sw.focusable(this); sw.pressable(this)
        setOnClickListener { onClick() }
        if (onHold != null) setOnLongClickListener { onHold(); true }
    }

    /** Hold or X on a PC style: that style's own values, to change (the live settings too while it's in use). */
    private fun styleTunePage(st: PcStyle): View = list { l ->
        val inUse = a.prefs.getString("mc_style_pc", null) == st.id
        l.addView(sw.text("Fine-tune ${pcName(st)}", 34, sw.ink, 800))
        l.addView(sw.text((if (st.pyro) "Experimental. " else "") + "These are this style's own values: picking it brings them back. " +
                (if (inUse) "It's in use, so changes apply now too. " else "Picking it on the cards uses them. ") + "B goes back to the styles.", 23, sw.sub, 600, 3),
                sw.lp(-1, -2).apply { topMargin = sw.px(8); bottomMargin = sw.px(22) })
        val rows = sw.col().apply { background = sw.round(sw.panel, CORNER); setPadding(0, sw.px(6), 0, sw.px(6)) }
        vrow(rows, "style:name", "Name", pcName(st)) { renameStyle(st) }
        val v = styleVals(st)
        val rec = if (st.pyro) sizeOf(v.s(RES, a.nativeResolution())).let { (w, h) ->
            com.limelight.home.Pyrowave.recommendedKbps(w, h, v.s(FPS, "60").toIntOrNull() ?: 60) } else null
        pcValues(rows, v, st, rec)
        vrow(rows, "style:reset", "Reset to default", "") {
            a.confirm("Reset ${pcName(st)}?", "Its values go back to how MoonCast set them up. Its name stays.", "Reset") {
                a.prefs.edit().remove("mc_pcstyle_${st.id}").apply()
                if (a.prefs.getString("mc_style_pc", null) == st.id) usePc(st, quiet = true) else a.render(force = true)
                a.toast("${pcName(st)} is back to its first values")
            }
        }
        l.addView(rows, sw.lp(-1, -2))
    }

    /** Y on Fine-tune: keep what's set now as one of your own (Custom 1-3). */
    private fun saveAsOwn() {
        val cur = Profile.mode(a)
        val opts = CUSTOM.map { m ->
            HandheldHomeActivity.Opt("✎", Sw.PC, Profile.name(a, m),
                    if (m == cur) "In use now: changes already go into it" else "Replaces what it held with what's set now", if (m == cur) "Now" else null, m != cur) {
                Profile.saveInto(a, m); a.toast("Saved into ${Profile.name(a, m)}. Pick it in Classic settings, Streaming profile, to use it")
            }
        }
        a.dialog("Save as my own style", "Every PC, PS5 and Xbox setting in use now goes into the one you pick.", opts, listOf("Cancel" to {}))
    }

    // ---------------------------------------------------------------- Fine-tune

    private class Tile(val id: String, val title: String, val state: String, val on: Boolean, val icon: String, val color: Int,
                       val click: () -> Unit, val details: (() -> Unit)? = null)

    private fun finePage(k: String): View {
        val e = sw.edge
        val row = sw.row(Gravity.TOP).apply { clipChildren = false; setPadding(sw.px(e), 0, sw.px(e), 0) }
        val avail = sw.designWidth - 2 * e
        val rightW = (avail * 0.36f).toInt().coerceIn(480, 680)
        val leftW = (avail - rightW - 40).toInt()
        val gap = 22
        val cols = ((leftW + gap) / (230 + gap)).coerceIn(2, 4)
        val tw = (leftW - gap * (cols - 1)) / cols
        val th = 176

        val tiles = when (k) { "pc" -> pcTiles(); "ps" -> psTiles(); else -> xbTiles() }
        val left = sw.col().apply { clipChildren = false }
        val grid = GridLayout(a).apply { columnCount = cols; clipChildren = false }
        val tileViews = tiles.mapIndexed { i, t ->
            tileView(t).also { grid.addView(it, GridLayout.LayoutParams().apply {
                width = sw.px(tw); height = sw.px(th)
                if (i % cols > 0) leftMargin = sw.px(gap)
                if (i >= cols) topMargin = sw.px(gap)
            }) }
        }
        left.addView(grid, sw.lp(-2, -2))
        val name = nowLabel(k)
        val withDetails = tiles.filter { it.details != null }.joinToString(", ") { it.title }
        left.addView(sw.text("Now: $name" + (if (Profile.isCustom(a)) ", in your own ${Profile.name(a)}" else "") +
                ". Changing anything here makes it \"(changed)\"; " +
                (if (k == "pc") "Y saves it into one of the play styles." else "Y saves it as one of your own (${CUSTOM.joinToString(", ") { Profile.name(a, it) }}).") +
                (if (withDetails.isNotEmpty()) " X on $withDetails shows more." else "") +
                (if (k == "pc") " Every other option is in Classic settings." else ""), 22, sw.sub, 600, 4),
                sw.lp(-1, -2).apply { topMargin = sw.px(24) })

        val values = sw.col().apply { background = sw.round(sw.panel, CORNER); setPadding(0, sw.px(6), 0, sw.px(6)) }
        when (k) { "pc" -> pcValues(values, live, null, null); "ps" -> psValues(values); else -> xbValues(values) }

        fun scroller(v: View) = ColScroll(a, sw.px(40)).apply {
            isVerticalScrollBarEnabled = false; isFocusable = false; clipToPadding = false; clipChildren = false
            setPadding(0, sw.px(24), 0, sw.px(30))
            addView(v)
        }
        row.addView(a.part(clip(scroller(left))), sw.lp(leftW, -1))
        row.addView(a.part(clip(scroller(values))), sw.lp(rightW, -1).apply { leftMargin = sw.px(40) })
        a.primaryView = tileViews.firstOrNull() ?: firstFocusable(values)
        return row
    }

    private fun tileView(t: Tile): View = sw.col().apply {
        tag = "tile:${t.id}"
        val fg = if (t.on) onRing() else sw.ink
        background = sw.round(if (t.on) sw.ring else sw.panel, CORNER)
        setPadding(sw.px(22), sw.px(20), sw.px(18), sw.px(18))
        addView(StyleIcon(a, t.icon, if (t.on) fg else t.color), sw.lp(42, 42))
        addView(View(a), LinearLayout.LayoutParams(1, 0, 1f))
        addView(sw.text(t.title, 25, fg, 800))
        addView(sw.text(t.state, 20, if (t.on) fg else sw.sub, 600), sw.lp(-1, -2).apply { topMargin = sw.px(4) })
        contentDescription = "${t.title}, ${t.state}"
        sw.focusable(this); sw.pressable(this)
        setOnClickListener { t.click() }
        t.details?.let { d -> details["tile:${t.id}"] = d; setOnLongClickListener { d(); true } }
    }

    /** An on/off key flipped, as the classic switch rows do. */
    private fun flip(key: String, def: Boolean, after: (() -> Unit)? = null) {
        stream.edit().putBoolean(key, !bool(key, def)).apply()
        FeelSounds.play(a, FeelSounds.Cue.TOGGLE)
        after?.invoke()
        a.render(force = true)
    }

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    private fun pcTiles(): List<Tile> {
        val t = ArrayList<Tile>()
        val hdr = bool("checkbox_enable_hdr", false)
        t += Tile("hdr", "HDR", if (screenHdr()) onOff(hdr) else onOff(hdr) + " · screen can't", hdr, "sun", ORANGE, { flip("checkbox_enable_hdr", false) })
        // YUV 4:4:4 has no tile: it comes only with the PyroWave play style
        val ull = bool("checkbox_ultra_low_latency", false)
        t += Tile("ull", "Ultra low latency", onOff(ull), ull, "bolt", sw.ring, { flip("checkbox_ultra_low_latency", false) })
        val fsr = str(com.limelight.binding.video.FsrRenderer.PREF, "off") == "fsr1"
        t += Tile("fsr", "Super resolution", if (fsr) "On · ${int(com.limelight.binding.video.FsrRenderer.SHARPNESS_PREF, com.limelight.binding.video.FsrRenderer.DEFAULT_SHARPNESS)}% sharp" else "Off", fsr, "star", ORANGE, {
            stream.edit().putString(com.limelight.binding.video.FsrRenderer.PREF, if (fsr) "off" else "fsr1").apply()
            FeelSounds.play(a, FeelSounds.Cue.TOGGLE); a.render(force = true)
        }) { sharpening(com.limelight.binding.video.FsrRenderer.SHARPNESS_PREF, com.limelight.binding.video.FsrRenderer.DEFAULT_SHARPNESS, false) }
        val range = bool("checkbox_full_range", false)
        t += Tile("range", "Full range", onOff(range), range, "target", Sw.C_GREEN, { flip("checkbox_full_range", false) })
        val pip = bool(com.limelight.home.Pip.PREF, false)
        t += Tile("pip", "Picture in picture", onOff(pip), pip, "pip", Sw.C_BLUE, { flip(com.limelight.home.Pip.PREF, false) })
        val lvl = StatsLevel.get(a)
        t += Tile("stats", "Stats", statsLabel(lvl), lvl != StatsLevel.OFF, "pulse", Sw.C_RED, { StatsLevel.set(a, StatsLevel.next(lvl)); a.render(force = true) })
        // PyroWave has its own play style; while it's on (that style, or the classic list), its tile turns it off and X has its options
        if (com.limelight.home.Pyrowave.deviceCan(a) && bool(com.limelight.home.Pyrowave.PREF, false)) {
            val pw = true
            t += Tile("pyro", "PyroWave", onOff(pw) + " · wired only", pw, "wave", Sw.C_BLUE, { flip(com.limelight.home.Pyrowave.PREF, false) }) { pyroDetails(0) }
        }
        // 144 and 240 fps: only for a screen that goes past 120 Hz (or when already on, to turn it off)
        val unlock = bool("checkbox_unlock_fps", false)
        if (unlock || maxHz() > 121f) t += Tile("unlock", "144 and 240 fps", onOff(unlock), unlock, "fps", sw.ring, {
            flip("checkbox_unlock_fps", false) {
                if (!stream.getBoolean("checkbox_unlock_fps", false) && (str("list_fps", "60").toIntOrNull() ?: 60) > 120)
                    stream.edit().putString("list_fps", "120").apply()
            }
        })
        return t
    }

    private fun consoleFsrTile(kind: String): Tile {
        val v = str("${kind}_fsr", "same")
        val on = ConsolePicture.fsrOn(stream, kind)
        return Tile("fsr", "Super resolution", when (v) { "fsr1" -> "On"; "off" -> "Off"; else -> "Same as PC (${onOff(on).lowercase()})" }, on, "star", ORANGE, {
            stream.edit().putString("${kind}_fsr", when (v) { "same" -> "off"; "off" -> "fsr1"; else -> "same" }).apply()
            FeelSounds.play(a, FeelSounds.Cue.TOGGLE); a.render(force = true)
        }) { sharpening("${kind}_fsr_sharp", -1, true) }
    }

    private fun psTiles(): List<Tile> {
        val swap = bool("ps5_swap_cross_circle", false)
        return listOf(consoleFsrTile("ps5"),
                Tile("swap", "Swap Cross and Circle", onOff(swap) + " · Japanese layout", swap, "swap", Sw.PS, { flip("ps5_swap_cross_circle", false) }))
    }

    private fun xbTiles(): List<Tile> {
        val swap = bool("xbox_swap_ab", false)
        val coop = bool("xbox_coop", false)
        return listOf(consoleFsrTile("xbox"),
                Tile("swap", "Swap A and B", onOff(swap) + " · Nintendo-style", swap, "swap", Sw.XB, { flip("xbox_swap_ab", false) }),
                Tile("coop", "Two players", onOff(coop), coop, "two", Sw.C_BLUE, { flip("xbox_coop", false) }))
    }

    /** Super resolution's details: how sharp. [same] adds "Same as PC" (-1) for a console's own setting. */
    private fun sharpening(key: String, def: Int, same: Boolean) {
        val steps = (if (same) listOf(-1) else emptyList()) + listOf(0, 20, 40, 60, 80, 100)
        pick("Sharpening", "How crisp Super resolution makes edges. Lower it if you see halos.",
                steps.map { (if (it < 0) "Same as PC" else "$it%") to it.toString() }, int(key, def).toString()) {
            stream.edit().putInt(key, it.toInt()).apply()
        }
    }

    /** PyroWave's details: Moonlight X's Vulkan options and the GPU boost. Each press changes one and keeps the list open. */
    private fun pyroDetails(focus: Int) {
        val dither = str(com.limelight.home.Pyrowave.DITHER_PREF, "off")
        val front = bool(com.limelight.home.Pyrowave.FRONT_BUFFER_PREF, false)
        val boost = bool(com.limelight.home.Pyrowave.GPU_BOOST_PREF, false)
        a.dialog("PyroWave", "Lowest delay with a lot of bitrate, for a cable or fast Wi-Fi. Needs a PC with PyroWave in Sunshine.", listOf(
                HandheldHomeActivity.Opt(null, 0, "Spatial dithering", "Fine moving noise that hides colour banding, most visible in HDR gradients",
                        dither.replaceFirstChar { it.uppercase() }) {
                    stream.edit().putString(com.limelight.home.Pyrowave.DITHER_PREF, when (dither) { "off" -> "low"; "low" -> "high"; else -> "off" }).apply()
                    pyroDetails(0)
                },
                HandheldHomeActivity.Opt(null, 0, "Front buffer rendering", "Lower delay. Turn off if you see tearing, flickering or corrupted video", onOff(front)) {
                    stream.edit().putBoolean(com.limelight.home.Pyrowave.FRONT_BUFFER_PREF, !front).apply(); pyroDetails(1)
                },
                HandheldHomeActivity.Opt(null, 0, "GPU boost", "Steadier decoding; uses more power and runs warmer", onOff(boost)) {
                    stream.edit().putBoolean(com.limelight.home.Pyrowave.GPU_BOOST_PREF, !boost).apply(); pyroDetails(2)
                }), listOf("Done" to {}), focusAt = focus)
    }

    /** A value row in Fine-tune's list: name on the left, value on the right; A opens its choices. */
    private fun vrow(list: LinearLayout, key: String, title: String, v: String, extra: View? = null, onClick: () -> Unit) {
        list.addView(sw.col().apply {
            tag = "set:$key"
            if (list.childCount > 0) background = sw.topLine()
            setPadding(sw.px(26), sw.px(22), sw.px(26), sw.px(22))
            minimumHeight = sw.px(if (sw.compact) 100 else 92)
            gravity = Gravity.CENTER_VERTICAL
            addView(sw.row().apply {
                addView(sw.text(title, 28, sw.ink, 700), LinearLayout.LayoutParams(0, -2, 1f))
                addView(sw.text(v, 28, sw.ring, 800), sw.lp(-2, -2).apply { leftMargin = sw.px(20) })
            }, sw.lp(-1, -2))
            if (extra != null) addView(extra, sw.lp(-1, -2).apply { topMargin = sw.px(14) })
            sw.focusable(this)
            setOnClickListener { onClick() }
        }, sw.lp(-1, -2))
    }

    /** A list of choices in a dialog, opening on the one in use; picking one saves it. */
    private fun pick(title: String, body: String?, opts: List<Pair<String, String>>, cur: String?, onPick: (String) -> Unit) {
        a.dialog(title, body, opts.map { (label, v) ->
            HandheldHomeActivity.Opt(null, 0, label, null, if (v == cur) "✓" else null) { onPick(v); a.render(force = true) }
        }, listOf("Close" to {}), focusAt = opts.indexOfFirst { it.second == cur })
    }

    /** A string key's choices (the classic choice row's options and default). */
    private fun pickStr(list: LinearLayout, key: String, title: String, body: String?, opts: List<Pair<String, String>>, def: String) {
        val cur = str(key, def)
        vrow(list, key, title, opts.firstOrNull { it.second == cur }?.first ?: cur) { pick(title, body, opts, cur) { stream.edit().putString(key, it).apply() } }
    }

    /**
     * The PC's value rows, reading and writing [v]: the live settings (Fine-tune), or a play style's own values ([st]'s
     * Fine-tune, which adds its on/off rows: Ultra low latency, or PyroWave's). [pyroRec]: the PyroWave style's
     * recommended bitrate, its Auto.
     */
    private fun pcValues(list: LinearLayout, v: Vals, st: PcStyle?, pyroRec: Int?) {
        a.syncNativeRes()
        val native = a.nativeResolution()
        // Native first, then the fixed sizes, then Custom… (any width × height)
        val resOpts = listOf("Native" to native) + HandheldHomeActivity.RES_STEPS.filter { it.second != native }
        val res = v.s(RES, native)
        val customRes = resOpts.none { it.second == res }
        vrow(list, RES, "Resolution", if (customRes) "${res.replace("x", " × ")} (custom)" else resOpts.first { it.second == res }.first) {
            pick("Resolution", "Native fills the screen you play on, now ${native.replace("x", " × ")}", resOpts + ("Custom…" to "custom"),
                    if (customRes) "custom" else res) { r ->
                if (r != "custom") v.put(RES, r)
                else a.entry("Custom resolution", "Width × height in pixels, for example 1600x900. Both even, from 256 to 7680.", "For example 1600x900",
                        if (customRes) res else "", "text") { raw ->
                    val n = raw.trim().lowercase().replace('×', 'x').replace(" ", "").split('x').mapNotNull { it.toIntOrNull() }
                    if (n.size != 2 || n.any { it !in 256..7680 || it % 2 != 0 }) "Two even numbers from 256 to 7680, like 1600x900."
                    else { v.put(RES, "${maxOf(n[0], n[1])}x${minOf(n[0], n[1])}"); a.render(force = true); null }
                }
            }
        }
        // frame rate: the classic steps, then Custom… (any rate, saved as a number into list_fps)
        val unlock = bool("checkbox_unlock_fps", false)
        val fpsSteps = listOf("30", "60", "90", "120") + (if (unlock) listOf("144", "240") else emptyList())
        val fpsMax = if (unlock) 240 else 120
        val fps = v.s(FPS, "60")
        vrow(list, FPS, "Frame rate", if (fps in fpsSteps) fps else "$fps (custom)") {
            pick("Frame rate", "60 is the smoothest. 30 uses less data and battery", fpsSteps.map { "$it fps" to it } + ("Custom…" to "custom"),
                    if (fps in fpsSteps) fps else "custom") { f ->
                if (f != "custom") v.put(FPS, f)
                else a.entry("Custom frame rate", "In fps, from 15 to $fpsMax. The PC streams at the rate you pick.", "For example 45",
                        if (fps !in fpsSteps) fps else "", true) { raw ->
                    val n = raw.trim().toIntOrNull()
                    if (n == null || n !in 15..fpsMax) "A number from 15 to $fpsMax."
                    else { v.put(FPS, n.toString()); a.render(force = true); null }
                }
            }
        }
        // bitrate: Auto (the PyroWave style: its recommended one), the steps up to 1000 Mbps, then Custom… (0.5 to 1000 in 0.5 steps)
        val rec = pyroRec != null && st != null && KBPS !in storedOf(st)
        val kbps = if (pyroRec != null && rec) 0 else v.i(KBPS, 0)
        val custom = kbps != 0 && (kbps / 1000 !in HandheldHomeActivity.BITRATE_STEPS || kbps % 1000 != 0)
        val maxRate = QuickBitrate.PC_MAX_MBPS
        val autoLabel = if (pyroRec != null) "Recommended · ${a.mbps(pyroRec)}" else "Auto"
        vrow(list, KBPS, "Bitrate", if (kbps == 0) autoLabel else a.mbps(kbps), bitrateBar(if (kbps == 0) pyroRec ?: 0 else kbps)) {
            pick("Bitrate", if (pyroRec != null) "PyroWave needs a lot: about ${a.mbps(pyroRec)} at this resolution and frame rate. Recommended follows them."
                    else QuickBitrate.highHint(kbps) ?: "Higher looks better and needs a faster network. Auto picks one for the resolution.",
                    listOf(autoLabel to "0") + HandheldHomeActivity.BITRATE_STEPS.map { "$it Mbps" to it.toString() } + ("Custom…" to "custom"),
                    when { kbps == 0 -> "0"; custom -> "custom"; else -> (kbps / 1000).toString() }) { r ->
                when (r) {
                    "0" -> v.put(KBPS, 0)
                    "custom" -> a.entry("Custom bitrate", "In Mbps, from 0.5 to $maxRate. Higher looks better and needs a faster network. " +
                            "Above ${QuickBitrate.HIGH_MBPS}: ${QuickBitrate.HIGH_HINT}.", "For example 25",
                            if (custom) a.mbps(kbps).removeSuffix(" Mbps") else "", "decimal") { raw ->
                        val n = raw.trim().replace(',', '.').toDoubleOrNull()
                        if (n == null || n < 0.5 || n > maxRate) "A number from 0.5 to $maxRate."
                        else { v.put(KBPS, (Math.round(n * 2) * 500).toInt()); a.render(force = true); null }
                    }
                    else -> v.put(KBPS, r.toInt() * 1000)
                }
            }
        }
        fun choose(key: String, title: String, body: String?, opts: List<Pair<String, String>>, def: String) {
            val cur = v.s(key, def)
            vrow(list, key, title, opts.firstOrNull { it.second == cur }?.first ?: cur) { pick(title, body, opts, cur) { v.put(key, it) } }
        }
        fun onOffRow(key: String, title: String, sub: String? = null) {
            val on = v.b(key, false)
            vrow(list, key, title, onOff(on), sub?.let { sw.text(it, 22, sw.sub, 600, 3) }) {
                v.put(key, !on); FeelSounds.play(a, FeelSounds.Cue.TOGGLE); a.render(force = true)
            }
        }
        // a play style: PyroWave is its codec; the other styles pick theirs
        if (st?.pyro != true) choose(CODEC, "Video codec", "HEVC and AV1 look better at the same bitrate",
                listOf("Auto" to "auto", "H.264" to "neverh265", "HEVC" to "forceh265", "AV1" to "forceav1"), "auto")
        if (st != null) onOffRow(HDR, "HDR", if (screenHdr()) null else "This screen doesn't show HDR10")
        choose(SHAPE, "Picture shape", "Fit with bars, fill and crop, or stretch",
                listOf("Fit" to "fit", "Fill" to "fill", "Stretch" to "stretch"), pcShapeDefault())
        choose(PACING, "Frame pacing", "PC and PS5: trade a little delay for smoother motion. Xbox paces itself",
                listOf("Lowest delay" to "latency", "Balanced" to "balanced", "Smoothest" to "smoothness"), "latency")
        if (st == null) return
        choose(FSR, "Super resolution", "Sharper picture when the stream is smaller than this screen (AMD FSR 1.0). Not with HDR" +
                (if (st.pyro) " or PyroWave" else ""), listOf("Off" to "off", "FSR 1.0" to "fsr1"), "off")
        if (v.s(FSR, "off") == "fsr1") {
            val sharp = v.i(FSR_SHARP, com.limelight.binding.video.FsrRenderer.DEFAULT_SHARPNESS)
            vrow(list, FSR_SHARP, "Sharpening", "$sharp%") {
                pick("Sharpening", "How crisp Super resolution makes edges. Lower it if you see halos.",
                        listOf(0, 20, 40, 60, 80, 100).map { "$it%" to it.toString() }, sharp.toString()) { v.put(FSR_SHARP, it.toInt()) }
            }
        }
        onOffRow(RANGE, "Full range", "Deeper blacks, if this screen shows them correctly")
        if (!st.pyro) { onOffRow(ULL, "Ultra low latency", "Asks this device's decoder for the lowest delay"); return }
        onOffRow(Y444, "YUV 4:4:4", "Sharper text and colour edges. Needs a PC that streams PyroWave 4:4:4")
        onOffRow(PW_BOOST, "PyroWave: GPU boost", "Steadier decoding; uses more power and runs warmer")
        choose(PW_DITHER, "PyroWave: spatial dithering", "Fine moving noise that hides colour banding, most visible in HDR gradients",
                listOf("Off" to "off", "Low" to "low", "High" to "high"), "off")
        onOffRow(PW_FRONT, "PyroWave: front buffer", "Lower delay. Turn off if you see tearing, flickering or corrupted video")
    }

    /** Where the bitrate sits between 10 Mbps and 1 Gbps (a log scale, as the steps are). */
    private fun bitrateBar(kbps: Int): View? {
        if (kbps <= 0) return null
        val f = ((Math.log(kbps / 1000.0) - Math.log(10.0)) / (Math.log(1000.0) - Math.log(10.0))).toFloat().coerceIn(0.02f, 1f)
        return sw.row().apply {
            addView(View(a).apply { background = sw.round(sw.ring, 4) }, LinearLayout.LayoutParams(0, sw.px(8), f))
            if (f < 1f) addView(View(a).apply { background = sw.round(sw.line, 4) }, LinearLayout.LayoutParams(0, sw.px(8), 1f - f))
        }
    }

    private fun psValues(list: LinearLayout) {
        pickStr(list, "ps5_resolution", "Resolution", "The PS5 streams up to 1080p", listOf("720p" to "720", "1080p" to "1080"), "1080")
        pickStr(list, "ps5_fps_home", "Frame rate", "60 fps is smoother; 30 fps lets a weak link keep up", listOf("60 fps" to "60", "30 fps" to "30"), "60")
        val br = str("ps5_bitrate_home", "auto")
        vrow(list, "ps5_bitrate_home", "Bitrate", psBitrateLabel("ps5_bitrate_home")) {
            pick("Bitrate", "Auto: 30 Mbps on fast Wi-Fi, 20 otherwise. 80–100 Mbps needs a strong 5 GHz / 6 GHz link",
                    listOf("Auto" to "auto", "10 Mbps" to "10", "15 Mbps" to "15", "20 Mbps" to "20", "30 Mbps" to "30", "50 Mbps" to "50",
                            "80 Mbps" to "80", "100 Mbps" to "100", "Custom…" to "custom"), br) { v ->
                if (v == "custom") a.customDialog(stream, "ps5_bitrate_home", "Custom bitrate", "Mbps", 2, 100, 20)
                else stream.edit().putString("ps5_bitrate_home", v).apply()
            }
        }
        pickStr(list, "ps5_codec", "Video codec", "HEVC looks better at the same bitrate", listOf("H.264" to "h264", "HEVC" to "h265"), "h265")
        pickStr(list, "ps5_shape", "Picture shape", "Fit shows all of it, Fill fills the screen (edges cut off), Stretch fills it and changes the shape",
                listOf("Same as PC" to "same", "Fit" to "fit", "Fill" to "fill", "Stretch" to "stretch"), "same")
        pickStr(list, "ps5_pacing", "Frame pacing", "Lowest delay shows each frame at once; Balanced and Smoothest even out the motion, a little later",
                listOf("Same as PC" to "same", "Lowest delay" to "latency", "Balanced" to "balanced", "Smoothest" to "smoothness"), "same")
        pickStr(list, DECODER, "Decoder", "Low latency shows each frame sooner. Ultra pushes this device's chip harder. Standard if the picture misbehaves",
                listOf("Low latency" to "low", "Ultra low latency" to "ultra", "Standard" to "standard"), "low")
    }

    private fun xbValues(list: LinearLayout) {
        pickStr(list, "xbox_resolution", "Picture", "The Xbox sets quality from this",
                listOf("Auto" to "auto", "720p" to "720", "1080p" to "1080", "1080p high" to "1081"), "auto")
        val br = int("xbox_bitrate", 0)
        vrow(list, "xbox_bitrate", "Bitrate", if (br == 0) "Auto" else "$br Mbps") {
            pick("Bitrate", "Auto lets the Xbox decide. A cap keeps a weak Wi-Fi from stuttering",
                    listOf(0, 5, 10, 15, 20, 30).map { (if (it == 0) "Auto" else "$it Mbps") to it.toString() }, br.toString()) {
                stream.edit().putInt("xbox_bitrate", it.toInt()).apply()
            }
        }
        pickStr(list, "xbox_rumble", "Rumble", "How strongly the controller shakes",
                listOf("Off" to "off", "Weak" to "weak", "Medium" to "medium", "Strong" to "strong"), "strong")
        val dz = int("xbox_deadzone", 8)
        vrow(list, "xbox_deadzone", "Stick deadzone", "$dz%") {
            pick("Stick deadzone", "Raise it if a character drifts on its own", listOf(3, 5, 8, 10, 15).map { "$it%" to it.toString() }, dz.toString()) {
                stream.edit().putInt("xbox_deadzone", it.toInt()).apply()
            }
        }
        pickStr(list, "xbox_shape", "Picture shape", "Fit shows all of it, Fill fills the screen (edges cut off), Stretch fills it and changes the shape",
                listOf("Same as PC" to "same", "Fit" to "fit", "Fill" to "fill", "Stretch" to "stretch"), "same")
    }

    // ---------------------------------------------------------------- the other tabs

    private fun pcAwayRows(list: LinearLayout) {
        a.profileRows(list)
        pcOf()?.let { d ->
            val away = d.manualAddress?.let { if (it.port == com.limelight.nvstream.http.NvHTTP.DEFAULT_HTTP_PORT) it.address else "${it.address}:${it.port}" }
            a.action(list, "pc:away", "Address for away", "A name or address that reaches ${d.name} from the internet. Its home address stays too",
                    away ?: "Not set") { a.awayAddress(d) }
        }
        a.action(list, "net", "Test the network", "How fast and steady the link to your PC is, from here", null) { a.networkTest() }
    }

    private fun pcPowerRows(list: LinearLayout) {
        val d = pcOf() ?: return
        a.info(list, "Now", when {
            !a.online(d) && a.isWaking(d) -> "Waking up…"
            !a.online(d) -> "Offline"
            else -> "Online"
        })
        a.pcAddresses(d)?.lines()?.forEach { l -> a.info(list, l.substringBefore(':'), l.substringAfter(": ")) }
        if (!a.online(d)) a.action(list, "pc:wake", "Wake it", "Needs Wake-on-LAN on the PC", null) { a.wake(d) }
        a.action(list, "pc:unpair", "Unpair", "You'll pair it again with a PIN to play", null) { a.confirmUnpair(d) }
        a.action(list, "pc:remove", "Remove", "It disappears from this device", null) { a.confirmRemovePc(d) }
    }

    private fun psPowerRows(list: LinearLayout) {
        a.psnRow(list)
        a.choice(list, stream, "ps5_on_disconnect", "When I disconnect", "What happens to the PS5 after you leave a game",
                listOf("Ask" to "ask", "Rest mode" to "rest", "Keep it on" to "keep"), "ask")
        a.psPinRow(list)
        psOf()?.let { c ->
            a.action(list, "ps:wake", "Wake it now", "Wakes ${c.name} from rest mode; it takes about a minute", null) {
                Thread { val ok = Ps5Store.wake(c)
                    a.runOnUiThread { a.toast(if (ok) "Waking ${c.name}. It takes about a minute." else "Couldn't wake ${c.name}. Register it again.") } }.start()
            }
        }
        a.info(list, "PS button", "L2 + Start")
        a.info(list, "Touchpad", "Select + Start")
        a.info(list, "HDR", "Not in the PS5 player yet")
    }

    private fun tvRows(list: LinearLayout) {
        val tv = TvFinder.target(a)
        a.action(list, "tv:playon", "Play on", "This ${a.deviceShort()} or a MoonCast TV on your Wi-Fi. On a TV, this ${a.deviceShort()} is the controller",
                tv?.name ?: "This ${a.deviceShort()}") { a.openPlayOn() }
        a.choice(list, a.prefs, TvControllerActivity.PAD_PREF, "Controller on a TV", "For PC games sent to a TV. Direct is fastest: it skips the TV's Wi-Fi",
                listOf("Direct to the PC" to "direct", "Through the TV" to "tv"), "direct")
        a.appToggle(list, "hh_dim_on_tv", "Dim the screen on a TV", "While this ${a.deviceShort()} is the controller for a TV", true)
    }

    // ---------------------------------------------------------------- small reads and labels

    private fun str(k: String, d: String): String = try { stream.getString(k, d) ?: d } catch (e: ClassCastException) { d }
    private fun int(k: String, d: Int): Int = try { stream.getInt(k, d) } catch (e: ClassCastException) { d }
    private fun bool(k: String, d: Boolean): Boolean = try { stream.getBoolean(k, d) } catch (e: ClassCastException) { d }

    private fun cycle(key: String, values: List<String>, cur: String) {
        stream.edit().putString(key, values[(values.indexOf(cur) + 1) % values.size]).apply()
        a.render(force = true)
    }

    private fun pcShapeDefault() = if (bool("checkbox_stretch_video", false)) "stretch" else "fit"
    private fun pcShape() = str("list_video_scale_mode", pcShapeDefault())
    private fun shapeLabel(v: String) = when (v) { "fill" -> "Fill"; "stretch" -> "Stretch"; else -> "Fit" }
    private fun consoleShapeLabel(v: String) = if (v == "same") "Same as PC" else shapeLabel(v)
    private fun pacingLabel(v: String) = when (v) { "balanced" -> "Balanced pacing"; "smoothness" -> "Smoothest"; else -> "Lowest delay" }
    private fun codecLabel(v: String) = when (v) { "neverh265" -> "H.264"; "forceh265" -> "HEVC"; "forceav1" -> "AV1"; else -> "Auto codec" }
    private fun statsLabel(v: String) = when (v) { StatsLevel.LITE -> "Lite"; StatsLevel.DETAILED -> "Detailed"; else -> "Off" }
    private fun xbPictureLabel(v: String) = when (v) { "720" -> "720p picture"; "1080" -> "1080p picture"; "1081" -> "1080p high picture"; else -> "Auto picture" }
    private fun psBitrateLabel(key: String): String = when (val v = str(key, "auto")) {
        "auto" -> "Auto bitrate"
        "custom" -> "${str(key + "_custom", if (key == "away_bitrate") "10" else "20")} Mbps"
        else -> "$v Mbps"
    }
}

/** The little line icons on the Play style cards and Fine-tune tiles, drawn on a 48 x 48 grid like [Icon]. */
internal class StyleIcon(ctx: Context, private val kind: String, private val color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    override fun onDraw(cv: Canvas) {
        val k = minOf(width, height) / 48f
        cv.save(); cv.scale(k, k)
        p.color = color; p.strokeWidth = 4f; p.style = Paint.Style.STROKE
        when (kind) {
            "leaf" -> {
                cv.drawPath(Path().apply { moveTo(10f, 38f); cubicTo(8f, 18f, 22f, 8f, 40f, 8f); cubicTo(40f, 28f, 30f, 40f, 10f, 38f); close() }, p)
                cv.drawLine(10f, 38f, 28f, 20f, p)
            }
            "half" -> {
                cv.drawCircle(24f, 24f, 16f, p)
                p.style = Paint.Style.FILL
                cv.drawArc(RectF(8f, 8f, 40f, 40f), 90f, 180f, true, p)
            }
            "star" -> cv.drawPath(Path().apply {
                moveTo(24f, 6f); quadTo(26f, 22f, 42f, 24f); quadTo(26f, 26f, 24f, 42f); quadTo(22f, 26f, 6f, 24f); quadTo(22f, 22f, 24f, 6f); close() }, p)
            "bolt" -> cv.drawPath(Path().apply { moveTo(27f, 4f); lineTo(11f, 27f); lineTo(23f, 27f); lineTo(20f, 44f); lineTo(37f, 20f); lineTo(25f, 20f); close() }, p)
            "pencil" -> {
                cv.drawPath(Path().apply { moveTo(10f, 38f); lineTo(12f, 30f); lineTo(32f, 10f); lineTo(38f, 16f); lineTo(18f, 36f); close() }, p)
                cv.drawLine(28f, 14f, 34f, 20f, p)
            }
            "sun" -> {
                cv.drawCircle(24f, 24f, 8f, p)
                for (i in 0 until 8) {
                    val r = Math.toRadians(i * 45.0)
                    cv.drawLine(24f + 14f * Math.cos(r).toFloat(), 24f + 14f * Math.sin(r).toFloat(), 24f + 19f * Math.cos(r).toFloat(), 24f + 19f * Math.sin(r).toFloat(), p)
                }
            }
            "grid" -> { rr(cv, 8f, 8f, 14f, 14f); rr(cv, 26f, 8f, 14f, 14f); rr(cv, 8f, 26f, 14f, 14f); rr(cv, 26f, 26f, 14f, 14f) }
            "target" -> { cv.drawCircle(24f, 24f, 16f, p); p.style = Paint.Style.FILL; cv.drawCircle(24f, 24f, 6f, p) }
            "pip" -> { rr(cv, 6f, 10f, 36f, 28f); p.style = Paint.Style.FILL; rr(cv, 24f, 23f, 14f, 11f) }
            "pulse" -> cv.drawPath(Path().apply { moveTo(4f, 26f); lineTo(14f, 26f); lineTo(19f, 12f); lineTo(27f, 38f); lineTo(32f, 26f); lineTo(44f, 26f) }, p)
            "wave" -> {
                cv.drawArc(RectF(6f, 14f, 42f, 50f), 205f, 130f, false, p)
                cv.drawArc(RectF(13f, 21f, 35f, 43f), 205f, 130f, false, p)
                p.style = Paint.Style.FILL; cv.drawCircle(24f, 34f, 3.5f, p)
            }
            "swap" -> {
                cv.drawLine(8f, 16f, 38f, 16f, p); cv.drawLine(32f, 10f, 38f, 16f, p); cv.drawLine(32f, 22f, 38f, 16f, p)
                cv.drawLine(40f, 32f, 10f, 32f, p); cv.drawLine(16f, 26f, 10f, 32f, p); cv.drawLine(16f, 38f, 10f, 32f, p)
            }
            "two" -> { cv.drawCircle(17f, 17f, 6f, p); cv.drawCircle(32f, 17f, 6f, p)
                cv.drawArc(RectF(6f, 27f, 28f, 49f), 180f, 180f, false, p); cv.drawArc(RectF(21f, 27f, 43f, 49f), 180f, 180f, false, p) }
            "fps" -> { cv.drawArc(RectF(6f, 10f, 42f, 46f), 160f, 220f, false, p); cv.drawLine(24f, 28f, 33f, 17f, p) }
            else -> cv.drawCircle(24f, 24f, 14f, p)
        }
        cv.restore()
    }

    private fun rr(cv: Canvas, x: Float, y: Float, w: Float, h: Float) = cv.drawRoundRect(RectF(x, y, x + w, y + h), 3f, 3f, p)
}
