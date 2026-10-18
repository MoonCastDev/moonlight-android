package com.limelight.home

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.limelight.PhoneLink
import com.limelight.R
import com.limelight.AppView
import com.limelight.binding.PlatformBinding
import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.computers.ComputerManagerListener
import com.limelight.computers.ComputerManagerService
import com.limelight.grid.assets.CachedAppAssetLoader
import com.limelight.grid.assets.DiskAssetLoader
import com.limelight.grid.assets.MemoryAssetLoader
import com.limelight.grid.assets.NetworkAssetLoader
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.wol.WakeOnLanSender
import com.limelight.preferences.AddComputerManually
import com.limelight.preferences.StreamSettings
import com.limelight.utils.CacheHelper
import com.limelight.utils.ServerHelper
import com.limelight.utils.ShortcutHelper
import java.io.FileNotFoundException
import java.io.StringReader
import java.net.UnknownHostException
import java.util.Date
import java.util.concurrent.Executors

/**
 * MoonCast TV home, in the style of Steam Big Picture: Home (what's running + your consoles), Library (every game from
 * every console), Consoles (pair, wake, remove), Settings. It also keeps listening for the phone, so a game sent from
 * the phone or Odin takes over the screen from wherever you are.
 *
 * Moonlight's own pieces do the real work: ComputerManagerService finds and polls PCs, ServerHelper starts and quits
 * games, PairingManager pairs; native chiaki (Ps5StreamActivity) plays the PS5.
 */
class HomeActivity : Activity(), PhoneLink.Listener {
    private enum class Tab(val title: String) { HOME("Home"), LIBRARY("Library"), CONSOLES("Consoles"), SETTINGS("Settings") }

    private lateinit var tv: Tv
    private lateinit var main: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var hintsBar: LinearLayout
    private lateinit var overlay: FrameLayout
    private lateinit var clock: TextView
    private lateinit var glide: FocusGlide
    private val ui = Handler(Looper.getMainLooper())

    // Moonlight's PC manager
    private var binder: ComputerManagerService.ComputerManagerBinder? = null
    private var polling = false
    private var resumed = false
    private val pcs = LinkedHashMap<String, ComputerDetails>()
    private val apps = HashMap<String, List<NvApp>>()
    private val rawLists = HashMap<String, String>()
    private val loaders = HashMap<String, CachedAppAssetLoader>()
    private val heroLabel by lazy { TextView(this) }   // unused label for the hero's box art (see loader())
    private val appPollers = HashMap<String, ComputerManagerService.ApplistPoller>()

    // PlayStation consoles the Odin registered through this TV
    private var consoles = listOf<Ps5Store.Console>()
    private val psStatus = HashMap<String, Ps5Store.Status>()
    private val probes = Executors.newSingleThreadExecutor()

    private var tab = Tab.HOME
    private var detail: String? = null      // "pc:<uuid>" or "ps:<host>"
    private var filter: String? = null      // library filter: a PC uuid, "ps", or null for all
    private var lastSig = ""
    private var overlayKind: String? = null
    private var lastInput = System.currentTimeMillis()

    private val prefs by lazy { getSharedPreferences("mc_tv", Context.MODE_PRIVATE) }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Profile.syncPcSettings(this)
        com.limelight.PhoneLink.sessionListener = com.limelight.PhoneLink.SessionListener { uuid ->
            runOnUiThread { pcs.remove(uuid); apps.remove(uuid); rawLists.remove(uuid); render(force = true) }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        tv = Tv(this, prefs.getBoolean("oled", false))
        buildShell()
        bindService(Intent(this, ComputerManagerService::class.java), conn, Service_BIND)
        PhoneLinkService.start(this)   // the phone's channel lives in the service, so it outlasts this screen
        if (!prefs.getBoolean("oled_asked", false)) ui.post { askOled() }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        lastInput = System.currentTimeMillis()
        PhoneLinkService.listener = this
        if (overlayKind == "incoming") hideOverlay()
        consoles = Ps5Store.all(this)
        xboxes = Xbox.consoles(this)
        refreshXboxes()
        startPolling()
        ui.post(tick)
        render(force = true)
    }

    override fun onPause() {
        resumed = false
        if (PhoneLinkService.listener === this) PhoneLinkService.listener = null
        stopPolling()
        ui.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        com.limelight.PhoneLink.endSessionIdentity(this)
        com.limelight.PhoneLink.sessionListener = null
        probes.shutdownNow()
        glide.detach()
        loaders.values.forEach { it.cancelBackgroundLoads(); it.cancelForegroundLoads(); it.freeCacheMemory() }
        try { unbindService(conn) } catch (e: Exception) { }
        super.onDestroy()
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val local = service as ComputerManagerService.ComputerManagerBinder
            Thread {
                local.waitForReady()
                // make a keypair early so discovery and pairing don't wait for it later
                AndroidCryptoProvider(this@HomeActivity).clientCertificate
                runOnUiThread { binder = local; startPolling() }
            }.start()
        }
        override fun onServiceDisconnected(name: ComponentName) { binder = null; polling = false }
    }

    private fun startPolling() {
        val b = binder ?: return
        if (!resumed || polling) return
        polling = true
        b.startPolling(ComputerManagerListener { d -> runOnUiThread { onComputer(d) } })
    }

    private fun stopPolling() {
        appPollers.values.forEach { it.stop() }
        appPollers.clear()
        if (polling) binder?.stopPolling()
        polling = false
    }

    /** Clock, PS5 probes and the OLED screen saver, every few seconds while the home is on screen. */
    private val tick = object : Runnable {
        override fun run() {
            clock.text = DateFormat.getTimeFormat(this@HomeActivity).format(Date())
            probeConsoles()
            if (tv.oled && overlayKind == null && System.currentTimeMillis() - lastInput > 120_000) showIdle()
            if (overlayKind == "idle") moveIdle()
            if (prefs.getBoolean("hide_hints", tv.oled) && System.currentTimeMillis() - lastInput > 10_000) hintsBar.animate().alpha(0f).setDuration(400).start()
            if (prefs.getBoolean("pixel_shift", tv.oled) && ++shiftTick % 30 == 0) {   // every ~3 minutes
                main.translationX = tv.pxf((-4..4).random()); main.translationY = tv.pxf((-4..4).random())
            }
            ui.postDelayed(this, 6_000)
        }
    }

    // ---------------------------------------------------------------- data

    private fun onComputer(d: ComputerDetails) {
        val uuid = d.uuid ?: return
        pcs[uuid] = d
        val raw = d.rawAppList
        if (raw != null && raw != rawLists[uuid]) {
            rawLists[uuid] = raw
            apps[uuid] = try { NvHTTP.getAppListByReader(StringReader(raw)) } catch (e: Exception) { apps[uuid] ?: emptyList() }
        } else if (!apps.containsKey(uuid)) {
            apps[uuid] = try {
                NvHTTP.getAppListByReader(StringReader(CacheHelper.readInputStreamToString(
                        CacheHelper.openCacheFileForInput(cacheDir, "applist", uuid))))
            } catch (e: Exception) { emptyList() }
        }
        if (d.state == ComputerDetails.State.ONLINE && d.pairState == PairingManager.PairState.PAIRED && !appPollers.containsKey(uuid)) {
            binder?.let { b -> appPollers[uuid] = b.createAppListPoller(b.getComputer(uuid) ?: d).also { it.start() } }
        }
        render()
    }

    private fun probeConsoles() {
        val list = consoles
        if (list.isEmpty()) return
        probes.execute {
            for (c in list) {
                val st = Ps5Store.probe(c.host, c.ps5)
                runOnUiThread {
                    psStatus[c.host] = st
                    val hn = st.hostName
                    if (hn != null && hn != c.name) { Ps5Store.rename(this, c.host, hn); consoles = Ps5Store.all(this) }
                    render()
                }
            }
        }
    }

    private fun paired(d: ComputerDetails) = d.pairState == PairingManager.PairState.PAIRED
    private fun online(d: ComputerDetails) = d.state == ComputerDetails.State.ONLINE
    private fun runningApp(d: ComputerDetails): NvApp? {
        if (d.runningGameId == 0) return null
        return apps[d.uuid]?.firstOrNull { it.appId == d.runningGameId } ?: NvApp("Your game", d.runningGameId, false)
    }
    private fun power(c: Ps5Store.Console) = psStatus[c.host]?.power ?: Ps5Store.Power.CHECKING

    private fun loader(d: ComputerDetails): CachedAppAssetLoader = loaders.getOrPut(d.uuid) {
        CachedAppAssetLoader(d, (300.0 / tv.px(222)).coerceAtLeast(1.0),
                NetworkAssetLoader(this, binder?.uniqueId ?: ""), MemoryAssetLoader(), DiskAssetLoader(this),
                android.graphics.BitmapFactory.decodeResource(resources, R.drawable.no_app_image))
    }

    // ---------------------------------------------------------------- shell

    private fun buildShell() {
        val root = FrameLayout(this).apply { setBackgroundColor(tv.p.night) }
        main = tv.col().apply { setPadding(tv.px(96), tv.px(56), tv.px(96), tv.px(48)); clipChildren = false }

        val top = tv.row()
        val mark = tv.row().apply {
            addView(Crescent(this@HomeActivity, tv.p.moon), tv.lp(28, 28).apply { rightMargin = tv.px(14) })
            addView(tv.text("MoonCast", 28, tv.p.moon, tv.display(700)))
        }
        top.addView(mark)
        tabsRow = tv.row().apply { setPadding(tv.px(56), 0, 0, 0) }
        Tab.values().forEach { t ->
            tabsRow.addView(tv.text(t.title, 24, tv.p.dust, tv.ui(700)).apply {
                gravity = Gravity.CENTER
                setPadding(tv.px(24), 0, tv.px(24), 0)
                tag = "tab:${t.name}"
                tv.focusableView(this)
                setOnClickListener { go(t) }
            }, tv.lp(-2, 56).apply { rightMargin = tv.px(8) })
        }
        top.addView(tabsRow)
        top.addView(tv.grow())
        clock = tv.text("", 24, tv.p.moon, tv.display(500))
        top.addView(clock)
        if (tv.oled) top.alpha = 0.72f
        main.addView(top, tv.lp(-1, 64))

        content = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }   // focus rings may overhang
        main.addView(content, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = tv.px(40); bottomMargin = tv.px(24) })

        hintsBar = tv.row()
        if (tv.oled) hintsBar.alpha = 0.72f
        main.addView(hintsBar, tv.lp(-1, 40))

        root.addView(main, FrameLayout.LayoutParams(-1, -1))
        overlay = FrameLayout(this).apply { visibility = View.GONE; isClickable = true }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        // the highlight glides from item to item; each item keeps its own ring, the glide fades as it lands on it
        glide = FocusGlide(root).apply {
            ghost = true
            strokeColor = Tv.CORONA
            strokeWidth = tv.pxf(3)
            cornerRadius = tv.pxf(16)
            radiusFor = { v -> if (v.parent === tabsRow) tv.pxf(28) else null }
            accept = { v -> v !is EditText }
        }.attach()
        FeelSounds.warm(this)
    }

    private fun go(t: Tab) {
        tab = t; detail = null
        if (t != Tab.LIBRARY) filter = null
        render(force = true, focusContent = false)
    }

    private fun styleTabs() {
        for (i in 0 until tabsRow.childCount) {
            val v = tabsRow.getChildAt(i) as TextView
            val on = v.tag == "tab:${tab.name}"
            val idle: android.graphics.drawable.Drawable = when {
                on && !tv.oled -> tv.round(tv.p.moon, 28)
                on -> LayerDrawable(arrayOf(android.graphics.drawable.ColorDrawable(Tv.CORONA))).apply {
                    setLayerInset(0, tv.px(24), tv.px(53), tv.px(24), 0) }
                else -> android.graphics.drawable.ColorDrawable(0)
            }
            v.background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), tv.round(if (on && !tv.oled) tv.p.moon else tv.p.raise, 28, Tv.CORONA, 3))
                addState(intArrayOf(), idle)
            }
            v.setTextColor(if (on) (if (tv.oled) tv.p.moon else tv.p.night) else tv.p.dust)
        }
    }

    private fun hints(vararg h: Pair<String, String>) {
        hintsBar.removeAllViews()
        h.forEach { (k, l) -> if (k == "|") hintsBar.addView(tv.grow()) else hintsBar.addView(tv.hint(k, l)) }
    }

    /** Rebuild the current page when something visible changed, keeping focus on the same item. */
    private fun render(force: Boolean = false, focusContent: Boolean = true) {
        Profile.syncPcSettings(this)
        val sig = signature()
        if (!force && sig == lastSig) return
        lastSig = sig
        val focused = currentFocus
        val inTabs = focused != null && focused.parent === tabsRow
        val focusTag = focused?.tag as? String
        content.removeAllViews()
        val pg = page()
        content.addView(pg, pg.layoutParams as? FrameLayout.LayoutParams ?: FrameLayout.LayoutParams(-1, -1))
        styleTabs()
        if (overlayKind != null) return
        when {
            inTabs && !force -> {}
            !focusContent -> tabsRow.findViewWithTag<View>("tab:${tab.name}")?.requestFocus()
            focusTag != null && content.findViewWithTag<View>(focusTag)?.requestFocus() == true -> {}
            else -> (content.findViewWithTag<View>("primary") ?: firstFocusable(content))?.requestFocus()
        }
    }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable && v.isClickable && v.visibility == View.VISIBLE) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun signature(): String = buildString {
        append(tab).append(detail).append(filter)
        pcs.values.forEach { append(it.uuid).append(it.state).append(it.pairState).append(it.runningGameId).append(apps[it.uuid]?.size) }
        consoles.forEach { append(it.host).append(it.name).append(psStatus[it.host]?.power).append(psStatus[it.host]?.runningApp) }
        xboxes.forEach { append(it.id).append(it.name).append(it.powerState) }
    }

    private fun page(): View {
        detail?.let { d ->
            if (d.startsWith("pc:")) pcs[d.removePrefix("pc:")]?.let { return pcPage(it) }
            if (d.startsWith("ps:")) consoles.firstOrNull { it.host == d.removePrefix("ps:") }?.let { return psPage(it) }
            if (d.startsWith("xb:")) xboxes.firstOrNull { it.id == d.removePrefix("xb:") }?.let { return xbPage(it) }
            detail = null
        }
        return when (tab) {
            Tab.HOME -> homePage()
            Tab.LIBRARY -> libraryPage()
            Tab.CONSOLES -> consolesPage()
            Tab.SETTINGS -> settingsPage()
        }
    }

    // ---------------------------------------------------------------- Home

    private fun homePage(): View {
        hints("A" to "Select", "LB" to "Previous tab", "RB" to "Next tab", "|" to "", "B" to "Back")
        val col = tv.col()
        col.addView(hero(), tv.lp(-1, 424))
        col.addView(tv.row(Gravity.BOTTOM).apply {
            addView(tv.text("Consoles", 28, tv.p.moon, tv.display(600)))
            addView(tv.text("Wake and play at home", 20, tv.p.dust, tv.ui(600)), tv.lp(-2, -2).apply { leftMargin = tv.px(16) })
        }, tv.lp(-1, -2).apply { topMargin = tv.px(40) })
        val shelf = tv.row()
        shelfItems().forEach { shelf.addView(it, tv.lp(414, 200).apply { rightMargin = tv.px(24) }) }
        col.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; isFocusable = false; clipToPadding = false; clipChildren = false
            setPadding(tv.px(24), tv.px(16), tv.px(24), tv.px(16))
            addView(shelf)
        }, tv.lp(-1, -2).apply { leftMargin = -tv.px(24); rightMargin = -tv.px(24) })
        return col
    }

    private fun hero(): View {
        val card = FrameLayout(this).apply { background = tv.round(tv.p.deep, 24, tv.p.line, 1); clipToOutline = true; outlineProvider = rounded(24) }
        val body = tv.col().apply { setPadding(tv.px(64), tv.px(44), tv.px(64), tv.px(36)) }

        val running = pcs.values.firstOrNull { paired(it) && online(it) && it.runningGameId != 0 }
        val psOn = consoles.firstOrNull { power(it) == Ps5Store.Power.ON && psStatus[it.host]?.runningApp != null }
        when {
            running != null -> {
                val app = runningApp(running)!!
                card.addView(ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    // keep the label alive: the loader only holds it weakly
                    loader(running).populateImageView(app, this, heroLabel)
                }, tv.flp(760, -1, Gravity.END))
                card.addView(View(this).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(tv.p.deep, tv.p.deep, 0x00000000))
                }, tv.flp(1300, -1))
                body.addView(tv.row().apply { addView(tv.status("Running now", "live")); addView(tv.status(running.name)) })
                body.addView(tv.text(app.appName, 76, tv.p.moon, tv.display(700), 2), tv.lp(900, -2).apply { topMargin = tv.px(26) })
                body.addView(tv.text("Still running on the PC. Pick up exactly where you left off.", 26, tv.p.soft, tv.ui(500), 2),
                        tv.lp(760, -2).apply { topMargin = tv.px(20) })
                body.addView(tv.row().apply {
                    addView(tv.pill("Resume here", "A", true) { start(running, app) }.apply { tag = "primary" })
                    addView(tv.pill("Quit game", null, false) { quit(running, app) }.apply { tag = "hero:quit" })
                }, tv.lp(-2, -2).apply { topMargin = tv.px(32) })
            }
            psOn != null -> {
                body.addView(tv.row().apply { addView(tv.status("On now", "live")); addView(tv.status(psOn.name)) })
                body.addView(tv.text(psStatus[psOn.host]?.runningApp ?: "PlayStation 5", 76, tv.p.moon, tv.display(700), 2),
                        tv.lp(1200, -2).apply { topMargin = tv.px(26) })
                body.addView(tv.text("Your PS5 is on. Play it here, on this TV.", 26, tv.p.soft, tv.ui(500), 2),
                        tv.lp(900, -2).apply { topMargin = tv.px(20) })
                body.addView(tv.row().apply {
                    addView(tv.pill("Play here", "A", true) { playPs(psOn) }.apply { tag = "primary" })
                }, tv.lp(-2, -2).apply { topMargin = tv.px(32) })
            }
            else -> {
                body.addView(tv.text("Ready to play", 96, tv.p.moon, tv.display(700)), tv.lp(-2, -2).apply { topMargin = tv.px(12) })
                body.addView(tv.text("Send a game from your phone or Odin and it starts here. Or pick one with your controller.",
                        28, tv.p.soft, tv.ui(500), 2), tv.lp(1500, -2).apply { topMargin = tv.px(28) })
                body.addView(tv.row().apply {
                    addView(tv.pill("Browse games", "A", true) { go(Tab.LIBRARY) }.apply { tag = "primary" })
                    if (pcs.isEmpty() && consoles.isEmpty()) addView(tv.pill("Add a console", null, false) { addConsole() })
                }, tv.lp(-2, -2).apply { topMargin = tv.px(36) })
            }
        }
        card.addView(body, FrameLayout.LayoutParams(-1, -1))
        return card
    }

    private fun shelfItems(): List<View> =
        consoles.map { consoleCard(psCardInfo(it), "ps:${it.host}") { open("ps:${it.host}") } } +
        xboxes.map { consoleCard(xbCardInfo(it), "xb:${it.id}") { open("xb:${it.id}") } } +
        pcs.values.map { consoleCard(pcCardInfo(it), "pc:${it.uuid}") { open("pc:${it.uuid}") } } +
        listOf(addCard())

    private class CardInfo(val glyph: String, val glyphColor: Int, val name: String, val meta: String, val states: List<Pair<String, String>>)

    private fun pcCardInfo(d: ComputerDetails): CardInfo {
        val n = apps[d.uuid]?.size ?: 0
        return when {
            !online(d) -> CardInfo("PC", 0, d.name, "Offline. Wake it to play", listOf("Offline" to "rest"))
            !paired(d) -> CardInfo("PC", 0, d.name, "Found on your network. Pair it to play", listOf("Not paired" to "rest"))
            else -> CardInfo("PC", 0, d.name, if (n > 0) "$n games" else "Paired",
                    listOf((if (d.runningGameId != 0) "Playing" else "Online") to "live"))
        }
    }

    private fun psCardInfo(c: Ps5Store.Console): CardInfo {
        val st = psStatus[c.host]
        return when (st?.power ?: Ps5Store.Power.CHECKING) {
            Ps5Store.Power.ON -> CardInfo("PS", 0xFF2F5DF0.toInt(), c.name, st?.runningApp ?: "On", listOf("On" to "live"))
            Ps5Store.Power.REST -> CardInfo("PS", 0xFF2F5DF0.toInt(), c.name, "Wakes in about a minute", listOf("Rest mode" to "rest"))
            Ps5Store.Power.NOT_FOUND -> CardInfo("PS", 0xFF2F5DF0.toInt(), c.name, "Not on this network", listOf("Away" to ""))
            Ps5Store.Power.CHECKING -> CardInfo("PS", 0xFF2F5DF0.toInt(), c.name, "Checking…", emptyList())
        }
    }

    private fun consoleCard(i: CardInfo, key: String, onClick: () -> Unit): View {
        val card = FrameLayout(this).apply {
            background = tv.focusable(tv.p.deep, 24)
            tag = key
            tv.focusableView(this); tv.lift(this, 1.03f)
            setOnClickListener { onClick() }
            setPadding(tv.px(28), tv.px(28), tv.px(28), tv.px(26))
        }
        card.addView(tv.text(i.glyph, 21, if (i.glyphColor != 0) 0xFFFFFFFF.toInt() else tv.p.moon, tv.display(700)).apply {
            gravity = Gravity.CENTER
            background = if (i.glyphColor != 0) tv.round(i.glyphColor, 16) else tv.round(tv.p.raise, 16, tv.p.line, 1)
        }, tv.flp(60, 60, Gravity.END or Gravity.TOP))
        card.addView(tv.col().apply {
            addView(tv.text(i.name, 28, tv.p.moon, tv.display(600)), tv.lp(-2, -2).apply { rightMargin = tv.px(76) })
            addView(tv.text(i.meta, 21, tv.p.dust, tv.ui(500), 2), tv.lp(-1, -2).apply { topMargin = tv.px(10); rightMargin = tv.px(76) })
        })
        card.addView(tv.row().apply { i.states.forEach { (l, k) -> addView(tv.status(l, k)) } }, tv.flp(-2, -2, Gravity.BOTTOM))
        return card
    }

    private fun addCard(): View = FrameLayout(this).apply {
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), tv.round(tv.p.raise, 24, Tv.CORONA, 3))
            addState(intArrayOf(), GradientDrawable().apply { cornerRadius = tv.pxf(24); setStroke(tv.px(2), tv.p.line, tv.pxf(12), tv.pxf(8)) })
        }
        tag = "add"
        tv.focusableView(this); tv.lift(this, 1.03f)
        setOnClickListener { addConsole() }
        setPadding(tv.px(28), tv.px(28), tv.px(28), tv.px(26))
        addView(tv.col().apply {
            addView(tv.text("Add a console", 28, tv.p.moon, tv.display(600)))
            addView(tv.text("A PC by its address, or register a PS5", 21, tv.p.dust, tv.ui(500), 2), tv.lp(-1, -2).apply { topMargin = tv.px(10) })
        })
        addView(tv.text("+", 34, tv.p.moon, tv.display(600)).apply { gravity = Gravity.CENTER; background = tv.round(tv.p.raise, 16, tv.p.line, 1) },
                tv.flp(60, 60, Gravity.END or Gravity.BOTTOM))
    }

    // ---------------------------------------------------------------- Library

    private fun libraryPage(): View {
        hints("A" to "Play on this TV", "X" to "Game menu", "Y" to "Search", "|" to "", "B" to "Back")
        val col = tv.col()
        val chips = tv.row()
        fun chip(label: String, key: String?) = tv.text(label, 20, tv.p.moon, tv.ui(600)).apply {
            gravity = Gravity.CENTER
            setPadding(tv.px(18), 0, tv.px(18), 0)
            val on = filter == key
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), tv.round(tv.p.raise, 22, Tv.CORONA, 3))
                addState(intArrayOf(), if (on && !tv.oled) tv.round(tv.p.moon, 22) else tv.round(tv.p.deep, 22, if (on) tv.p.moon else tv.p.line, if (on) 2 else 1))
            }
            if (on && !tv.oled) setTextColor(tv.p.night)
            tag = "chip:${key ?: "all"}"
            tv.focusableView(this)
            setOnClickListener { filter = key; render(force = true) }
        }
        val sources = pcs.values.filter { paired(it) }
        val anyHidden = sources.any { hiddenIds(it).isNotEmpty() }
        val q = libraryQuery
        if (q.isNotEmpty()) chips.addView(tv.text("Search: “$q”   ✕", 20, tv.p.night, tv.ui(700)).apply {
            gravity = Gravity.CENTER; setPadding(tv.px(18), 0, tv.px(18), 0); tag = "chip:search"
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), tv.round(Tv.CORONA, 22, Tv.CORONA, 3))
                addState(intArrayOf(), tv.round(Tv.CORONA, 22))
            }
            tv.focusableView(this); setOnClickListener { libraryQuery = ""; render(force = true) }
        }, tv.lp(-2, 44).apply { rightMargin = tv.px(20) })
        if (sources.size + (if (consoles.isEmpty()) 0 else 1) > 1 || anyHidden || q.isNotEmpty()) {
            chips.addView(chip("All", null), tv.lp(-2, 44).apply { rightMargin = tv.px(12) })
            if (sources.size > 1) sources.forEach { chips.addView(chip(it.name, it.uuid), tv.lp(-2, 44).apply { rightMargin = tv.px(12) }) }
            if (consoles.isNotEmpty()) chips.addView(chip("PlayStation", "ps"), tv.lp(-2, 44).apply { rightMargin = tv.px(12) })
            if (anyHidden) chips.addView(chip("Hidden", "hidden"), tv.lp(-2, 44).apply { rightMargin = tv.px(12) })
            col.addView(chips, tv.lp(-1, -2).apply { bottomMargin = tv.px(28) })
        }

        val grid = GridLayout(this).apply { columnCount = 7; clipChildren = false; clipToPadding = false }
        var n = 0
        fun add(v: View) {
            grid.addView(v, GridLayout.LayoutParams().apply {
                width = tv.px(222); height = tv.px(296)
                rightMargin = if (n % 7 == 6) 0 else tv.px(24); bottomMargin = tv.px(28)
            }); n++
        }
        fun matches(name: String) = q.isEmpty() || name.contains(q, ignoreCase = true)
        if ((filter == null || filter == "ps") && matches("PlayStation 5")) consoles.forEach { c -> add(psCover(c)) }
        sources.filter { filter == null || filter == "hidden" || filter == it.uuid }.forEach { pc ->
            val hidden = hiddenIds(pc)
            (apps[pc.uuid] ?: emptyList())
                    .filter { if (filter == "hidden") it.appId in hidden else it.appId !in hidden }
                    .filter { matches(it.appName) }
                    .sortedWith(compareByDescending<NvApp> { it.appId == pc.runningGameId }.thenBy { it.appName.lowercase() })
                    .forEach { add(appCover(pc, it)) }
        }
        if (n == 0 && q.isNotEmpty()) {
            col.addView(tv.text("Nothing called “$q”", 40, tv.p.moon, tv.display(600)))
            col.addView(tv.row().apply {
                addView(tv.pill("Search again", "Y", true) { openSearch() }.apply { tag = "primary" })
                addView(tv.pill("Show all games", null, false) { libraryQuery = ""; filter = null; render(force = true) })
            }, tv.lp(-2, -2).apply { topMargin = tv.px(32) })
            return col
        }
        if (n == 0) {
            col.addView(tv.text(if (sources.isEmpty() && consoles.isEmpty()) "No games yet" else "Loading games…", 40, tv.p.moon, tv.display(600)))
            col.addView(tv.text("Pair a PC in Consoles, or register a PS5 from MoonCast on your Odin.", 24, tv.p.dust, tv.ui(500)),
                    tv.lp(-2, -2).apply { topMargin = tv.px(16) })
            col.addView(tv.row().apply { addView(tv.pill("Go to Consoles", "A", true) { go(Tab.CONSOLES) }.apply { tag = "primary" }) },
                    tv.lp(-2, -2).apply { topMargin = tv.px(32) })
            return col
        }
        col.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isFocusable = false; clipToPadding = false
            setPadding(tv.px(24), tv.px(24), tv.px(24), tv.px(24))
            // the grid gets the padding, not just the scroll view: ScrollView scrolls a focused cover flush to
            // its top edge, which cut off the top of the ring on the first row
            addView(grid.apply { setPadding(0, tv.px(12), 0, tv.px(12)); clipToPadding = false })
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { leftMargin = -tv.px(24); rightMargin = -tv.px(24); topMargin = -tv.px(36) })
        return col
    }

    private fun coverFrame(key: String, seed: String, onClick: () -> Unit) = FrameLayout(this).apply {
        background = tv.twoTone(seed)
        foreground = tv.focusRing(16)
        clipToOutline = true; outlineProvider = rounded(16)
        tag = key
        tv.focusableView(this); tv.lift(this, 1.06f)
        setOnClickListener { onClick() }
    }

    private fun appCover(pc: ComputerDetails, app: NvApp): View {
        val f = coverFrame("app:${pc.uuid}:${app.appId}", app.appName) { start(pc, app) }
        f.setOnLongClickListener { gameMenu(pc, app); true }
        f.setOnKeyListener { _, code, e ->
            if (code == KeyEvent.KEYCODE_BUTTON_X || code == KeyEvent.KEYCODE_MENU) { if (e.action == KeyEvent.ACTION_UP) gameMenu(pc, app); true } else false
        }
        val title = tv.text(app.appName, 23, 0xFFFFFFFF.toInt(), tv.display(700), 3).apply {
            gravity = Gravity.BOTTOM; setPadding(tv.px(18), 0, tv.px(18), tv.px(18))
        }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        f.addView(img, FrameLayout.LayoutParams(-1, -1))
        f.addView(title, FrameLayout.LayoutParams(-1, -1))
        loader(pc).populateImageView(app, img, title)
        if (app.appId == pc.runningGameId) f.addView(tv.status("Running", "live"), tv.flp(-2, 34).apply { leftMargin = tv.px(14); topMargin = tv.px(14) })
        return f
    }

    // ---- hidden games (Moonlight's own store, so AppView agrees), search, game menu ----
    private var libraryQuery = ""
    private fun hiddenIds(pc: ComputerDetails): Set<Int> =
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE).getStringSet(pc.uuid, emptySet())!!
                .mapNotNull { it.toIntOrNull() }.toSet()

    private fun setHidden(pc: ComputerDetails, app: NvApp, hide: Boolean) {
        val p = getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
        val set = HashSet(p.getStringSet(pc.uuid, emptySet())!!)
        if (hide) set.add(app.appId.toString()) else set.remove(app.appId.toString())
        p.edit().putStringSet(pc.uuid, set).apply()
    }

    private fun openSearch() {
        val card = dialogCard()
        card.addView(tv.text("Search your games", 40, tv.p.moon, tv.display(700)))
        val field = textField("A few letters of the name", libraryQuery)
        card.addView(field, tv.lp(-1, 84).apply { topMargin = tv.px(24) })
        fun search() { libraryQuery = field.text.toString().trim(); hideOverlay(); go(Tab.LIBRARY) }
        field.setOnEditorActionListener { _, _, _ -> search(); true }
        card.addView(tv.row().apply {
            addView(tv.pill("Search", "A", true) { search() })
            if (libraryQuery.isNotEmpty()) addView(tv.pill("Clear", null, false) { field.setText(""); search() })
        }, tv.lp(-2, -2).apply { topMargin = tv.px(28) })
        showOverlay("search", FrameLayout(this).apply { addView(card, tv.flp(1000, -2, Gravity.CENTER)) })
        field.requestFocus()
    }

    /** A TV-sized text box (the TV's own keyboard types into it). */
    private fun textField(hint: String, value: String) = android.widget.EditText(this).apply {
        setText(value); setSelection(value.length)
        this.hint = hint
        setTextColor(tv.p.moon); setHintTextColor(tv.p.dust)
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tv.pxf(30)); typeface = tv.ui(600)
        setSingleLine(true)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        background = tv.focusable(tv.p.night, 16)
        setPadding(tv.px(24), 0, tv.px(24), 0)
        tag = "primary"
    }

    private fun gameMenu(pc: ComputerDetails, app: NvApp) {
        val hidden = app.appId in hiddenIds(pc)
        val card = dialogCard()
        card.addView(tv.text(app.appName, 40, tv.p.moon, tv.display(700), 2))
        card.addView(tv.text("On ${pc.name}" + (if (app.appId == pc.runningGameId) ". Running now." else "."), 22, tv.p.dust, tv.ui(500)),
                tv.lp(-1, -2).apply { topMargin = tv.px(10); bottomMargin = tv.px(20) })
        fun item(label: String, sub: String?, primary: Boolean = false, onClick: () -> Unit) {
            card.addView(tv.row().apply {
                if (primary) tag = "primary"
                background = tv.focusable(0, 16, outline = false)
                setPadding(tv.px(20), 0, tv.px(20), 0)
                addView(tv.text(label, 24, tv.p.moon, tv.ui(700)), LinearLayout.LayoutParams(0, -2, 1f))
                if (sub != null) addView(tv.text(sub, 19, tv.p.dust, tv.ui(600)))
                tv.focusableView(this)
                setOnClickListener { hideOverlay(); onClick() }
            }, tv.lp(-1, 80))
        }
        item(if (app.appId == pc.runningGameId) "Resume on this TV" else "Play on this TV", null, true) { start(pc, app) }
        if (app.appId == pc.runningGameId) item("Quit game", "Closes it on the PC") { quit(pc, app) }
        item(if (hidden) "Show in Library" else "Hide from Library", if (hidden) null else "Still under the Hidden filter") {
            setHidden(pc, app, !hidden)
            if (filter == "hidden" && pcs.values.none { hiddenIds(it).isNotEmpty() }) filter = null
            render(force = true); toast(if (hidden) "${app.appName} is back in your Library" else "${app.appName} hidden") }
        item("Add to the TV's home screen", "A row of your games there") {
            try { com.limelight.utils.TvChannelHelper(this).addGameToHomeScreen(pc, app); toast("Added. Look for the ${pc.name} row on the TV's home screen.") }
            catch (e: Exception) { toast("This TV doesn't allow adding games to its home screen.") } }
        card.addView(tv.text("App ${app.appId}" + (if (app.isHdrSupported) ". Supports HDR." else "."), 19, tv.p.dust, tv.ui(600)),
                tv.lp(-1, -2).apply { topMargin = tv.px(16) })
        showOverlay("gamemenu", FrameLayout(this).apply { addView(card, tv.flp(900, -2, Gravity.CENTER)) })
    }

    private fun psCover(c: Ps5Store.Console): View {
        val f = coverFrame("pscover:${c.host}", "PlayStation 5") { open("ps:${c.host}") }
        f.background = tv.twoTone("ps")   // the blue pair
        f.addView(tv.text(c.name, 15, 0xFFFFFFFF.toInt(), tv.ui(700)).apply {
            gravity = Gravity.CENTER; setPadding(tv.px(12), 0, tv.px(12), 0); background = tv.round(0x4D000000, 15)
        }, tv.flp(-2, 30).apply { leftMargin = tv.px(16); topMargin = tv.px(16) })
        f.addView(tv.text("PlayStation 5", 23, 0xFFFFFFFF.toInt(), tv.display(700), 3).apply {
            gravity = Gravity.BOTTOM; setPadding(tv.px(18), 0, tv.px(18), tv.px(18))
        }, FrameLayout.LayoutParams(-1, -1))
        return f
    }

    // ---------------------------------------------------------------- Consoles

    private fun consolesPage(): View {
        hints("A" to "Open", "LB" to "Previous tab", "RB" to "Next tab", "|" to "", "B" to "Back")
        val grid = GridLayout(this).apply { columnCount = 3; clipChildren = false; clipToPadding = false }
        shelfItems().forEachIndexed { i, v ->
            grid.addView(v, GridLayout.LayoutParams().apply {
                width = tv.px(552); height = tv.px(240)
                rightMargin = if (i % 3 == 2) 0 else tv.px(24); bottomMargin = tv.px(24)
            })
        }
        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isFocusable = false; clipToPadding = false
            setPadding(tv.px(24), tv.px(24), tv.px(24), tv.px(24))
            addView(grid.apply { setPadding(0, tv.px(8), 0, tv.px(8)); clipToPadding = false })
            layoutParams = FrameLayout.LayoutParams(-1, -1).apply { leftMargin = -tv.px(24); rightMargin = -tv.px(24); topMargin = -tv.px(24) }
        }
    }

    private fun open(key: String) { detail = key; render(force = true) }

    private fun detailShell(badge: String, badgeColor: Int, name: String, line: String, states: List<Pair<String, String>>,
                            actions: List<View>, panel: View): View {
        hints("A" to "Select", "|" to "", "B" to "Back")
        val row = tv.row(Gravity.TOP)
        val left = tv.col()
        left.addView(tv.text(badge, 40, 0xFFFFFFFF.toInt(), tv.display(700)).apply {
            gravity = Gravity.CENTER
            background = if (badgeColor != 0) tv.round(badgeColor, 30) else tv.round(tv.p.raise, 30, tv.p.line, 1)
            if (badgeColor == 0) setTextColor(tv.p.moon)
        }, tv.lp(120, 120))
        left.addView(tv.text(name, 80, tv.p.moon, tv.display(700)), tv.lp(-2, -2).apply { topMargin = tv.px(32) })
        left.addView(tv.text(line, 26, tv.p.soft, tv.ui(500), 3), tv.lp(-1, -2).apply { topMargin = tv.px(14) })
        left.addView(tv.row().apply { states.forEach { (l, k) -> addView(tv.status(l, k)) } }, tv.lp(-2, -2).apply { topMargin = tv.px(24) })
        // up to two buttons on the first row, the rest on a second, so long labels never get cut ("Re…")
        left.addView(tv.row().apply { actions.take(2).forEach { addView(it) } }, tv.lp(-2, -2).apply { topMargin = tv.px(36) })
        if (actions.size > 2) left.addView(tv.row().apply { actions.drop(2).forEach { addView(it) } }, tv.lp(-2, -2).apply { topMargin = tv.px(16) })
        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = tv.px(88) })
        row.addView(panel, tv.lp(820, -2))
        return row
    }

    private fun panel(title: String, rows: List<Pair<String, String>>, title2: String? = null, rows2: List<Pair<String, String>> = emptyList()): View {
        val p = tv.col().apply { background = tv.round(tv.p.deep, 24, tv.p.line, 1); setPadding(tv.px(40), tv.px(32), tv.px(40), tv.px(24)) }
        fun section(t: String, r: List<Pair<String, String>>, first: Boolean) {
            p.addView(tv.text(t, 26, tv.p.moon, tv.display(600)), tv.lp(-2, -2).apply { if (!first) topMargin = tv.px(28) })
            r.forEachIndexed { i, (k, v) ->
                p.addView(tv.row().apply {
                    if (i > 0) background = topLine()
                    addView(tv.text(k, 24, tv.p.moon, tv.ui(700)), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(tv.text(v, 23, tv.p.dust, tv.ui(700)))
                }, tv.lp(-1, 76))
            }
        }
        section(title, rows, true)
        if (title2 != null) section(title2, rows2, false)
        return p
    }

    private fun topLine() = tv.topLine()

    private fun pcPage(d: ComputerDetails): View {
        val running = runningApp(d)
        val actions = mutableListOf<View>()
        val line: String
        val states: List<Pair<String, String>>
        when {
            !online(d) -> {
                line = "This PC is off or asleep. Wake it, then it shows its games here."
                states = listOf("Offline" to "rest", (if (paired(d)) "Paired" else "Not paired") to "")
                actions += tv.pill("Wake it", "A", true) { wake(d) }.apply { tag = "primary" }
            }
            !paired(d) -> {
                line = "Found on your network. Pair it once with a PIN to play its games on this TV."
                states = listOf("Online" to "live", "Not paired" to "rest")
                actions += tv.pill("Pair", "A", true) { pair(d) }.apply { tag = "primary" }
            }
            else -> {
                line = if (running != null) "Playing ${running.appName}." else "Online and paired. Its games are in your Library."
                states = listOf((if (running != null) "Playing" else "Online") to "live", "Paired" to "")
                if (running != null) actions += tv.pill("Resume here", "A", true) { start(d, running) }.apply { tag = "primary" }
                actions += tv.pill("Show games", if (running == null) "A" else null, running == null) {
                    filter = d.uuid; go(Tab.LIBRARY)
                }.apply { if (running == null) tag = "primary" }
            }
        }
        if (online(d) && paired(d)) actions += tv.pill("Unpair", null, false) { confirmUnpair(d) }.apply { tag = "unpair" }
        actions += tv.pill("Test network", null, false) { ServerHelper.doNetworkTest(this) }.apply { tag = "nettest" }
        actions += tv.pill("Remove", null, false) { confirmRemovePc(d) }.apply { tag = "remove" }
        val facts = listOf(
                "Address" to (d.activeAddress?.address ?: d.localAddress?.address ?: "Unknown"),
                "Games" to "${apps[d.uuid]?.size ?: 0}",
                "Wake-on-LAN" to (if (d.macAddress != null && d.macAddress != "00:00:00:00:00:00") "Ready" else "No MAC address yet"))
        return detailShell("PC", 0, d.name, line, states, actions, panel("Details", facts))
    }

    private fun psPage(c: Ps5Store.Console): View {
        val pw = power(c)
        val line = when (pw) {
            Ps5Store.Power.ON -> "On" + (psStatus[c.host]?.runningApp?.let { ", playing $it" } ?: "") + ". Play opens Remote Play on this TV."
            Ps5Store.Power.REST -> "In rest mode. Play wakes it and opens Remote Play on this TV, in about a minute."
            Ps5Store.Power.NOT_FOUND -> if (Psn.signedIn(this)) "Not on this network. Play reaches it through PlayStation Network."
                                        else "Not on this network. Sign in to PlayStation Network in Settings to play it from here."
            Ps5Store.Power.CHECKING -> "Checking whether it's on…"
        }
        val states = when (pw) {
            Ps5Store.Power.ON -> listOf("On" to "live")
            Ps5Store.Power.REST -> listOf("Rest mode" to "rest")
            Ps5Store.Power.NOT_FOUND -> listOf("Away" to "")
            Ps5Store.Power.CHECKING -> emptyList()
        } + listOf("Registered" to "")
        val actions = listOf(
                tv.pill(if (pw == Ps5Store.Power.ON) "Play" else "Wake and play", "A", true) { playPs(c) }.apply { tag = "primary" },
                tv.pill("Wake only", null, false) { wakePs(c) }.apply { tag = "wake" },
                tv.pill("Remove", null, false) { confirmRemovePs(c) }.apply { tag = "remove" })
        val route = if (pw != Ps5Store.Power.NOT_FOUND) "Home network" else if (Psn.signedIn(this)) "PlayStation Network" else "Needs PSN sign-in"
        return detailShell("PS", 0xFF2F5DF0.toInt(), c.name, line, states, actions,
                panel("Stream", listOf("Route" to route, "Picture" to "60 fps, set by this TV's link", "Quality" to "Auto",
                        "Controller" to "Your phone or a pad on this TV"),
                        "Buttons your pad doesn't have", listOf("PS button" to "L2 + Start", "Touchpad" to "Select + Start")))
    }

    // ---------------------------------------------------------------- Xbox

    private var xboxes: List<Xbox.Console> = emptyList()

    private fun refreshXboxes(loud: Boolean = false) {
        if (!Xbox.signedIn(this)) return
        Thread {
            val r = try { Xbox.refreshConsoles(this) } catch (e: Exception) { null }
            runOnUiThread {
                if (r != null) { xboxes = r; render(force = true) }
                if (loud) toast(when { r == null -> "Couldn't reach Xbox network"; r.isEmpty() -> "No Xbox on this account"
                    else -> "Found " + r.joinToString { it.name } })
            }
        }.start()
    }

    private fun xbCardInfo(c: Xbox.Console): CardInfo {
        val on = c.powerState.equals("On", true)
        return CardInfo("XB", 0xFF107C10.toInt(), c.name, if (on) "On" else "Wakes when you play",
                listOf((if (on) "On" else if (c.powerState.isBlank()) "Xbox" else "Asleep") to (if (on) "live" else "rest")))
    }

    private fun xbPage(c: Xbox.Console): View {
        val on = c.powerState.equals("On", true)
        val actions = listOf(
                tv.pill(if (on) "Play" else "Wake and play", "A", true) { playXbox(c) }.apply { tag = "primary" },
                tv.pill("Wake only", null, false) {
                    Thread { val ok = try { Xbox.wake(this, c.id); true } catch (e: Exception) { false }
                        runOnUiThread { toast(if (ok) "Waking ${c.name}" else "Couldn't wake ${c.name}") } }.start() }.apply { tag = "wake" })
        return detailShell("XB", 0xFF107C10.toInt(), c.name,
                (if (on) "On. " else "") + "Play opens Xbox Remote Play on this TV, at home or away, through Xbox network.",
                listOf((if (on) "On" to "live" else "Asleep" to "rest"), "Signed in" to ""), actions,
                panel("Stream", listOf("Route" to "Xbox network", "Picture" to "60 fps, set by this TV's link", "Type" to c.type,
                        "Controller" to "A pad on this TV"), "Buttons", listOf("Xbox button" to "Quick menu, Xbox button")))
    }

    private fun playXbox(c: Xbox.Console) {
        FeelSounds.play(this, FeelSounds.Cue.LAUNCH)
        startActivity(Launch.xboxIntent(this, c))
    }

    /** Microsoft's device-code sign-in: the TV shows a code, you type it at microsoft.com/link on a phone. */
    private fun xboxSignIn() {
        val card = dialogCard()
        card.addView(tv.text("Sign in to Xbox", 40, tv.p.moon, tv.display(700)))
        val sub = tv.text("Getting a code…", 24, tv.p.dust, tv.ui(500), 3)
        card.addView(sub, tv.lp(-1, -2).apply { topMargin = tv.px(12) })
        val code = tv.text("", 88, tv.p.moon, tv.display(700)).apply { letterSpacing = 0.12f }
        card.addView(code, tv.lp(-2, -2).apply { topMargin = tv.px(28); bottomMargin = tv.px(28) })
        card.addView(tv.row().apply { addView(tv.pill("Cancel", "B", false) { hideOverlay() }.apply { tag = "primary" }) })
        showOverlay("xbox", FrameLayout(this).apply { addView(card, tv.flp(1000, -2, Gravity.CENTER)) })
        Thread {
            try {
                val dc = Xbox.startSignIn()
                runOnUiThread {
                    sub.text = "On your phone, open ${dc.url.removePrefix("https://").removePrefix("www.")} and enter this code. " +
                            "Then sign in with your Xbox account."
                    code.text = dc.userCode
                }
                val end = System.currentTimeMillis() + dc.expiresIn * 1000L
                while (System.currentTimeMillis() < end && overlayKind == "xbox") {
                    Thread.sleep(dc.interval * 1000L)
                    if (overlayKind != "xbox") return@Thread
                    if (Xbox.pollSignIn(this, dc)) {
                        val list = try { Xbox.refreshConsoles(this) } catch (e: Exception) { emptyList() }
                        runOnUiThread {
                            xboxes = list
                            if (overlayKind == "xbox") hideOverlay()
                            toast("Signed in to Xbox" + (Xbox.gamertag(this)?.let { " as $it" } ?: "") +
                                    if (list.isEmpty()) ". No console found yet" else ". Found " + list.joinToString { it.name })
                        }
                        return@Thread
                    }
                }
                runOnUiThread { if (overlayKind == "xbox") { sub.text = "The code expired. Press B and try again."; code.text = "" } }
            } catch (e: Exception) {
                runOnUiThread { if (overlayKind == "xbox") sub.text = "Sign-in didn't work: ${e.message?.take(120) ?: e.javaClass.simpleName}" }
            }
        }.start()
    }

    // ---------------------------------------------------------------- Settings

    private var settingsGroup = "video"
    private val stream by lazy { android.preference.PreferenceManager.getDefaultSharedPreferences(this) }

    private fun settingsPage(): View {
        hints("A" to "Change", "LB" to "Previous tab", "RB" to "Next tab", "|" to "", "B" to "Back")
        val groups = listOf("video" to "Video", "audio" to "Audio", "pads" to "Controllers", "ps" to "PlayStation",
                "xbox" to "Xbox", "mk" to "Mouse and keyboard", "screen" to "Screen", "about" to "About and help")
        val row = tv.row(Gravity.TOP)
        val nav = tv.col()
        groups.forEachIndexed { i, (k, label) ->
            val on = k == settingsGroup
            nav.addView(tv.text(label, 24, if (on) tv.p.moon else tv.p.dust, tv.ui(700)).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(tv.px(24), 0, tv.px(24), 0)
                tag = "group:$k"
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), tv.round(tv.p.raise, 16, Tv.CORONA, 3))
                    addState(intArrayOf(), if (on) LayerDrawable(arrayOf(tv.round(tv.p.deep, 16, tv.p.line, 1),
                            android.graphics.drawable.ColorDrawable(Tv.CORONA))).apply { setLayerInset(1, 0, tv.px(16), tv.px(436), tv.px(16)) }
                            else android.graphics.drawable.ColorDrawable(0))
                }
                tv.focusableView(this)
                setOnClickListener { settingsGroup = k; render(force = true); firstFocusable(content.findViewWithTag<View>("settings:list") ?: content)?.requestFocus() }
            }, tv.lp(-1, 76).apply { if (i == groups.size - 1) topMargin = tv.px(24) })
        }
        row.addView(nav, tv.lp(440, -2).apply { rightMargin = tv.px(40) })

        val list = tv.col().apply {
            tag = "settings:list"
            background = tv.round(tv.p.deep, 24, tv.p.line, 1); setPadding(tv.px(24), tv.px(12), tv.px(24), tv.px(12))
        }
        when (settingsGroup) {
            "video" -> {
                // Home and Away: one switch for PC, PlayStation and Xbox. Each profile keeps its own settings.
                choice(list, Profile.PREF, "Streaming profile", "Home: best quality on your network. Away: lighter settings for PlayStation Network and slower links",
                        listOf("Home" to "home", "Away" to "away"), "home")
                // Native: this screen's real panel size (its display mode), as in Moonlight/Artemis' "Native (WxH)"
                val native = nativeResolution()
                val presets = listOf("720p" to "1280x720", "1080p" to "1920x1080", "1440p" to "2560x1440", "4K" to "3840x2160")
                choice(list, "list_resolution", "Resolution",
                        "Higher is sharper and needs more bitrate. Native is this screen: ${native.replace("x", " × ")}",
                        if (presets.any { it.second == native }) presets else presets + ("Native" to native), "1280x720")
                val res = stream.getString("list_resolution", "1280x720") ?: "1280x720"
                val customRes = res !in listOf("1280x720", "1920x1080", "2560x1440", "3840x2160", nativeResolution())
                action(list, "set:customres", "Custom resolution", "Any size, for example this TV's exact panel. Sunshine scales to it",
                        if (customRes) res.replace("x", " × ") else "Off") { customResolution(res, customRes) }
                choice(list, "list_fps", "Frame rate", "60 is the smoothest. 30 uses less data", listOf("30" to "30", "60" to "60", "90" to "90", "120" to "120"), "60")
                bitrate(list)
                val kbps = stream.getInt("seekbar_bitrate_kbps", 0)
                val customRate = kbps != 0 && kbps / 1000 !in listOf(10, 20, 30, 50, 80)
                action(list, "set:customrate", "Custom bitrate", "Any value between the steps above, in Mbps",
                        if (customRate) "${kbps / 1000} Mbps" else "Off") { customBitrate(kbps) }
                choice(list, "video_format", "Video codec", "H.265 and AV1 look better at the same bitrate",
                        listOf("Auto" to "auto", "H.264" to "neverh265", "H.265" to "forceh265", "AV1" to "forceav1"), "auto")
                toggle(list, "checkbox_enable_hdr", "HDR", "Only when the PC, the game and this TV support it", false)
                toggle(list, "checkbox_full_range", "Full-range colour", "Deeper blacks, if this TV shows them correctly", false)
                choice(list, "list_video_scale_mode", "Picture shape", "When the stream's shape doesn't match this TV: fit with bars, fill and crop, or stretch",
                        listOf("Fit" to "fit", "Fill" to "fill", "Stretch" to "stretch"),
                        if (stream.getBoolean("checkbox_stretch_video", false)) "stretch" else "fit")
                intChoice(list, "seekbar_video_zoom", "Zoom", "Enlarge the picture if this TV cuts off the edges", listOf(100, 102, 104, 106, 108, 110), 100) {
                    if (it == 100) "Off" else "$it%" }
                choice(list, "frame_pacing", "Frame pacing", "Trade a little delay for smoother motion",
                        listOf("Lowest delay" to "latency", "Balanced" to "balanced", "Smoothest" to "smoothness"), "latency")
                toggle(list, "checkbox_ultra_low_latency", "Ultra low latency",
                        "Experimental. Snapdragon 8 Gen 2 or newer, MediaTek, NVIDIA Shield. This device: ${chipName()}", false)
                toggle(list, "checkbox_enable_sops", "Let the PC tune game settings", "Changes a game's graphics settings to suit the stream, on PCs that support it", true)
                toggle(list, "checkbox_unlock_fps", "Unlock frame rate", "Allows rates above what this TV shows, like 120 on a 60 Hz screen. May tear", false)
                toggle(list, "checkbox_reduce_refresh_rate", "Let the TV lower its refresh rate", "Saves power, adds a little delay", false)
            }
            "audio" -> {
                choice(list, "list_audio_config", "Speakers", "Match what's connected to this TV",
                        listOf("Stereo" to "2", "5.1" to "51", "7.1" to "71"), "2")
                toggle(list, "checkbox_host_audio", "Also play sound on the PC", "Useful when someone's in the room with it", false)
                toggle(list, "checkbox_enable_audiofx", "Allow the TV's sound effects", "Lets this TV's equalizer and sound modes work while playing. Adds a little sound delay", false)
                tvToggle(list, FeelSounds.PREF, "Menu sounds", "Soft sounds as you move around the menus. Games aren't affected", true)
            }
            "pads" -> {
                action(list, "set:pad", "Pair a controller", "Opens this TV's Bluetooth settings. Put the pad in pairing mode first", null) { pairController() }
                action(list, "set:padtest", "Test a controller", "See every button, stick and trigger live, and try the rumble", null) {
                    startActivity(Intent(this, PadTestActivity::class.java)) }
                toggle(list, "checkbox_usb_driver", "USB pad driver", "Makes Xbox pads plugged in by USB work, even where this TV doesn't support them", true)
                if (stream.getBoolean("checkbox_usb_driver", true))
                    toggle(list, "checkbox_usb_bind_all", "Use it for every USB pad", "Even Xbox pads this TV already supports. Try it if one misbehaves", false)
                toggle(list, "checkbox_multi_controller", "More than one player", "On a PC each pad is its own player. On the PS5, every pad is player 1", true)
                intChoice(list, "seekbar_deadzone", "Stick deadzone", "Raise it if a character drifts on its own", listOf(3, 5, 7, 10, 15), 7) { "$it%" }
                toggle(list, "checkbox_flip_face_buttons", "Swap A and B", "For Nintendo-style layouts", false)
                toggle(list, "checkbox_gamepad_motion_sensors", "Motion", "Send the pad's gyro to games that use it", true)
                toggle(list, "checkbox_mouse_emulation", "Use the pad as a mouse", "Hold Start to switch", true)
                if (stream.getBoolean("checkbox_mouse_emulation", true))
                    choice(list, "analog_scrolling", "Scroll with a stick", "While the pad is a mouse",
                            listOf("Off" to "none", "Right" to "right", "Left" to "left"), "right")
                // Only offer the fallbacks when this device has the hardware; most TVs and sticks have neither.
                val sensors = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
                if (sensors.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE) != null)
                    toggle(list, "checkbox_gamepad_motion_fallback", "Motion from this device", "For pads without a gyro, use this device's own sensors", false)
                if ((getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator)?.hasVibrator() == true)
                    toggle(list, "checkbox_vibrate_fallback", "Rumble on this device", "When a pad can't rumble", false)
                info(list, "Quick menu", QuickMenu.COMBO_LABEL + ", or Back on the TV remote")
            }
            "ps" -> {
                if (Psn.signedIn(this)) action(list, "set:psn", "PlayStation Network",
                        "Signed in" + (Psn.onlineId(this)?.takeIf { it.isNotBlank() }?.let { " as $it" } ?: "") + ". This TV can add your PS5 and reach it away from home",
                        "Sign out") {
                    confirm("Sign out of PlayStation Network?", "This TV will need the Odin, or a new sign-in, to add a PS5 or reach it away from home.", "Sign out") {
                        Psn.signOut(this); render(force = true) } }
                else action(list, "set:psn", "PlayStation Network", "Sign in once to add a PS5 here and play it away from home", "Sign in") {
                    startActivityForResult(Intent(this, PsnSignInActivity::class.java), REQ_PSN) }
                // Same four rows for each profile; the General profile decides which set they edit. Auto shows both sets.
                val mode = Profile.mode(this)
                if (mode != "away") {
                    choice(list, "ps5_resolution", "Resolution", "The PS5 streams up to 1080p", listOf("720p" to "720", "1080p" to "1080"), "1080")
                    choice(list, "ps5_fps_home", "Frame rate", "60 fps is smoother; 30 fps lets a weak link keep up",
                            listOf("60 fps" to "60", "30 fps" to "30", "Custom" to "custom"), "60",
                            onPick = { if (it == "custom") customDialog("ps5_fps_home", "Custom frame rate", "fps", 15, 60, 60) })
                    if (stream.getString("ps5_fps_home", "60") == "custom")
                        customNumber(list, "ps5_fps_home", "Custom frame rate", "15 to 60 fps. The PS5 may cap it at 60", "fps", 15, 60, 60)
                    choice(list, "ps5_bitrate_home", "Bitrate", "Auto: 30 Mbps on fast Wi-Fi or cable, 20 otherwise",
                            listOf("Auto" to "auto", "10" to "10", "15" to "15", "20" to "20", "30" to "30", "Custom" to "custom"), "auto",
                            onPick = { if (it == "custom") customDialog("ps5_bitrate_home", "Custom bitrate", "Mbps", 2, 50, 20) })
                    if (stream.getString("ps5_bitrate_home", "auto") == "custom")
                        customNumber(list, "ps5_bitrate_home", "Custom bitrate", "2 to 50 Mbps", "Mbps", 2, 50, 20)
                    choice(list, "ps5_codec", "Video codec", "H.265 looks better at the same bitrate", listOf("H.264" to "h264", "H.265" to "h265"), "h265")
                }
                if (mode != "home") {
                    choice(list, "ps5_away_quality", "Resolution", "Auto follows this TV's Wi-Fi: 1080p on a strong link, 720p on a weak one",
                            listOf("Auto" to "auto", "540p" to "540", "720p" to "720", "1080p" to "1080"), "auto")
                    choice(list, "away_fps", "Frame rate", "60 fps is smoother; 30 fps lets a weak link keep up",
                            listOf("60 fps" to "60", "30 fps" to "30", "Custom" to "custom"), "60",
                            onPick = { if (it == "custom") customDialog("away_fps", "Custom frame rate", "fps", 15, 60, 60) })
                    if (stream.getString("away_fps", "60") == "custom")
                        customNumber(list, "away_fps", "Custom frame rate", "15 to 60 fps. The PS5 may cap it at 60", "fps", 15, 60, 60)
                    choice(list, "away_bitrate", "Bitrate", "Auto: capped by this TV's Wi-Fi link",
                            listOf("Auto" to "auto", "8" to "8", "10" to "10", "15" to "15", "Custom" to "custom"), "auto",
                            onPick = { if (it == "custom") customDialog("away_bitrate", "Custom bitrate", "Mbps", 2, 50, 10) })
                    if (stream.getString("away_bitrate", "auto") == "custom")
                        customNumber(list, "away_bitrate", "Custom bitrate", "2 to 50 Mbps", "Mbps", 2, 50, 10)
                    choice(list, "away_codec", "Video codec", "H.265 looks better at the same bitrate", listOf("H.264" to "h264", "H.265" to "h265"), "h265")
                }
                choice(list, "ps5_on_disconnect", "When I disconnect", "What happens to the PS5 after you leave a game",
                        listOf("Ask" to "ask", "Rest mode" to "rest", "Keep it on" to "keep"), "ask")
                toggle(list, "ps5_swap_cross_circle", "Swap Cross and Circle", "For the Japanese layout", false)
                val pin = prefs.getString("ps5_login_pin", "") ?: ""
                action(list, "set:pspin", "Console PIN", "Signs in for you when the PS5 asks for its 4-digit login PIN",
                        if (pin.isEmpty()) "Not set" else "••••") { enterLoginPin() }
                toggle(list, "ps5_dpad_touchpad", "D-pad as touchpad", "Hold Select and use the D-pad to swipe on the PS5's touchpad", false)
                info(list, "PS button", "L2 + Start")
                info(list, "Touchpad", "Select + Start")
            }
            "xbox" -> {
                if (Xbox.signedIn(this)) action(list, "set:xbox", "Xbox network",
                        "Signed in" + (Xbox.gamertag(this)?.let { " as $it" } ?: "") + ". Your consoles show under Consoles", "Sign out") {
                    confirm("Sign out of Xbox?", "Your Xbox consoles leave this TV until you sign in again.", "Sign out") {
                        Xbox.signOut(this); xboxes = emptyList(); render(force = true) } }
                else action(list, "set:xbox", "Xbox network", "Sign in once to play your Xbox on this TV, at home or away", "Sign in") { xboxSignIn() }
                if (Xbox.signedIn(this)) {
                    action(list, "set:xbref", "Find my consoles", if (xboxes.isEmpty()) "None found yet" else xboxes.joinToString { it.name }, null) {
                        refreshXboxes(true) }
                    info(list, "On the Xbox", "Settings, Devices and connections, Remote features: on. Power mode: Sleep")
                }
                choice(list, "xbox_resolution", "Picture", "The Xbox sets quality from this. Auto: 720p on weak Wi-Fi, high on a 1440p+ screen, else 1080p",
                        listOf("Auto" to "auto", "720p" to "720", "1080p" to "1080", "1080p high" to "1081"), "auto")
                xboxBitrate(list)
                choice(list, "list_video_scale_mode", "Picture shape", "Fit with bars, fill and crop, or stretch. Shared with PC games",
                        listOf("Fit" to "fit", "Fill" to "fill", "Stretch" to "stretch"),
                        if (stream.getBoolean("checkbox_stretch_video", false)) "stretch" else "fit")
                intChoice(list, "seekbar_video_zoom", "Zoom", "Enlarge the picture if this TV cuts off the edges. Shared with PC games",
                        listOf(100, 102, 104, 106, 108, 110), 100) { if (it == 100) "Off" else "$it%" }
                toggle(list, "checkbox_ultra_low_latency", "Ultra low latency",
                        "Experimental. Snapdragon 8 Gen 2 or newer, MediaTek, NVIDIA Shield. This device: ${chipName()}", false)
                info(list, "Frame rate", "60 fps")
                choice(list, "xbox_rumble", "Rumble", "How strongly the controller shakes", listOf("Off" to "off", "Weak" to "weak",
                        "Medium" to "medium", "Strong" to "strong"), "strong")
                intChoice(list, "xbox_deadzone", "Stick deadzone", "Raise it if a character drifts on its own", listOf(3, 5, 8, 10, 15), 8) { "$it%" }
                toggle(list, "xbox_edge", "Full stick range", "Rescale after the deadzone so the stick still reaches 100% at its edge", false)
                toggle(list, "xbox_swap_ab", "Swap A and B", "For Nintendo-style layouts", false)
                toggle(list, "xbox_swap_xy", "Swap X and Y", "For Nintendo-style layouts", false)
                toggle(list, "xbox_short_triggers", "Short triggers", "A light pull counts as a full press", false)
                toggle(list, "xbox_coop", "Two players", "A second controller plays as player 2", false)
                choice(list, "xbox_language", "Game language", "The language games start in", listOf("English" to "en-US",
                        "Arabic" to "ar-SA", "French" to "fr-FR", "German" to "de-DE", "Spanish" to "es-ES"), "en-US")
                info(list, "Quick menu", QuickMenu.COMBO_LABEL + ", or Back on the TV remote")
            }
            "mk" -> {
                toggle(list, "checkbox_absolute_mouse_mode", "Remote-desktop mouse", "The pointer goes exactly where your mouse is, for desktop use", false)
                toggle(list, "checkbox_mouse_nav_buttons", "Mouse back and forward buttons", "Send them to the PC instead of using them here", false)
                toggle(list, "checkbox_gamepad_touchpad_as_mouse", "DualSense touchpad as a mouse", "Move the PC's pointer with the pad's touchpad", false)
                info(list, "Leave the game", "Ctrl + Alt + Shift + Q")
                info(list, "Show stats", "Ctrl + Alt + Shift + S")
                info(list, "Release the mouse", "Ctrl + Alt + Shift + Z")
            }
            "screen" -> {
                action(list, "set:oled", "OLED screen", "True black, quieter menus, and a dim screen saver that moves", if (tv.oled) "On" else "Off") {
                    prefs.edit().putBoolean("oled", !tv.oled).apply(); recreate() }
                toggle(list, "checkbox_enable_perf_overlay", "Stats while playing", "Frame rate, delay and lost frames in a corner of the game", false)
                tvToggle(list, "hide_hints", "Hide button hints", "They fade after 10 seconds and come back when you press a button", tv.oled)
                tvToggle(list, "pixel_shift", "Move the menus slightly", "Shifts everything by a few pixels every few minutes, so nothing burns in", tv.oled)
                toggle(list, "checkbox_enable_post_stream_toast", "Connection summary after a game", "Shows the average delay and frame rate when you leave", false)
                toggle(list, "checkbox_disable_warnings", "Hide connection warnings", "No \"poor connection\" messages while playing", false)
                val langs = resources.getStringArray(R.array.language_values)
                val names = resources.getStringArray(R.array.language_names)
                val cur = langs.indexOf(stream.getString("list_languages", "default")).coerceAtLeast(0)
                action(list, "set:lang", "Language while playing", "For messages during a game and errors. These menus stay in English",
                        if (cur == 0) "Same as the TV" else names[cur]) { pickLanguage(langs, names, cur) }
            }
            "about" -> {
                val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
                info(list, "MoonCast TV", ver ?: "")
                info(list, "Phones send games here", "Port ${PhoneLink.PORT}")
                action(list, "set:add", "Add a PC by its address", "For a PC this TV doesn't find on its own", null) { addPcByAddress() }
                action(list, "set:all", "All Moonlight settings", "Every option, including the rare ones", null) {
                    startActivity(Intent(this, StreamSettings::class.java)) }
            }
        }
        row.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; isFocusable = false; clipToPadding = false
            addView(list)
        }, LinearLayout.LayoutParams(0, -1, 1f))
        return row
    }

    private fun settingRow(list: LinearLayout, key: String, title: String, sub: String?, focusable: Boolean, value: View?, onClick: (() -> Unit)?) {
        list.addView(tv.row().apply {
            tag = "set:$key"
            if (list.childCount > 0) background = topLine()
            setPadding(tv.px(24), 0, tv.px(24), 0)
            addView(tv.col().apply {
                addView(tv.text(title, 24, tv.p.moon, tv.ui(700)))
                if (sub != null) addView(tv.text(sub, 19, tv.p.dust, tv.ui(500)), tv.lp(-2, -2).apply { topMargin = tv.px(4) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (value != null) addView(value)
            setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && e.action == KeyEvent.ACTION_DOWN)
                    content.findViewWithTag<View>("group:$settingsGroup")?.requestFocus() ?: false
                else false
            }
            if (focusable) {
                val line = background
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), tv.round(tv.p.raise, 16, Tv.CORONA, 3))
                    addState(intArrayOf(), line ?: android.graphics.drawable.ColorDrawable(0))
                }
                tv.focusableView(this)
                setOnClickListener { onClick?.invoke() }
            }
        }, tv.lp(-1, 96))
    }

    /** Options as a segmented control; A moves to the next one. */
    /** A Custom value for a choice row: tapping it asks for a number, and saving a number switches the row to Custom. */
    private fun customNumber(list: LinearLayout, key: String, title: String, sub: String, unit: String, min: Int, max: Int, def: Int) {
        val cur = stream.getString(key + "_custom", def.toString()) ?: def.toString()
        action(list, "set:$key-custom", title, sub, "$cur $unit") { customDialog(key, title, unit, min, max, def) }
    }

    /** Asks for a Custom number and saves it; the row switches to Custom. */
    private fun customDialog(key: String, title: String, unit: String, min: Int, max: Int, def: Int) {
        val cur = stream.getString(key + "_custom", def.toString()) ?: def.toString()
        entryDialog(title, "$min to $max $unit", unit, cur, true, { v ->
            val n = v.trim().toIntOrNull()
            if (n == null || n !in min..max) "Enter a number from $min to $max"
            else {
                stream.edit().putString(key + "_custom", n.toString()).putString(key, "custom").apply()
                render(force = true)
                null
            }
        })
    }

    private fun choice(list: LinearLayout, key: String, title: String, sub: String, options: List<Pair<String, String>>, def: String,
                       onPick: ((String) -> Unit)? = null) {
        val cur = stream.getString(key, def)
        val seg = tv.row().apply { background = tv.round(0, 20, tv.p.line, 1) }
        options.forEach { (label, v) ->
            val on = v == cur
            seg.addView(tv.text(label, 19, if (on) (if (tv.oled) tv.p.moon else tv.p.night) else tv.p.dust, tv.ui(700)).apply {
                gravity = Gravity.CENTER; setPadding(tv.px(16), 0, tv.px(16), 0)
                if (on) background = if (tv.oled) tv.round(tv.p.raise, 20, tv.p.moon, 2) else tv.round(tv.p.moon, 20)
            }, tv.lp(-2, 40))
        }
        settingRow(list, key, title, sub, true, seg) {
            val i = options.indexOfFirst { it.second == cur }
            val next = options[(i + 1) % options.size].second
            stream.edit().putString(key, next).apply()
            render(force = true)
            onPick?.invoke(next)
        }
    }

    private fun intChoice(list: LinearLayout, key: String, title: String, sub: String, options: List<Int>, def: Int, label: (Int) -> String) {
        val cur = stream.getInt(key, def)
        settingRow(list, key, title, sub, true, tv.text(label(cur), 23, tv.p.moon, tv.ui(700))) {
            val i = options.indexOf(cur)
            stream.edit().putInt(key, options[(i + 1) % options.size]).apply()
            render(force = true)
        }
    }

    /** Moonlight keeps the bitrate in kbps; no value means Moonlight picks one for the resolution. */
    private fun bitrate(list: LinearLayout) {
        val steps = listOf(0, 10, 20, 30, 50, 80)
        val cur = stream.getInt("seekbar_bitrate_kbps", 0) / 1000
        val shown = if (cur == 0) "Auto" else "$cur Mbps"
        settingRow(list, "seekbar_bitrate_kbps", "Bitrate", "Auto picks one for the resolution. Raise it for a sharper picture on a fast network",
                true, tv.text(shown, 23, tv.p.moon, tv.ui(700))) {
            val next = steps[(steps.indexOf(steps.firstOrNull { it >= cur } ?: 0) + 1) % steps.size]
            if (next == 0) stream.edit().remove("seekbar_bitrate_kbps").apply()
            else stream.edit().putInt("seekbar_bitrate_kbps", next * 1000).apply()
            render(force = true)
        }
    }

    private fun toggle(list: LinearLayout, key: String, title: String, sub: String, def: Boolean) {
        val on = stream.getBoolean(key, def)
        val sw = FrameLayout(this).apply {
            background = tv.round(if (on) Tv.BEAM else tv.p.line, 19)
            addView(View(this@HomeActivity).apply { background = tv.round(if (on) tv.p.night else tv.p.dust, 14) },
                    tv.flp(28, 28, Gravity.CENTER_VERTICAL or (if (on) Gravity.END else Gravity.START)).apply { leftMargin = tv.px(5); rightMargin = tv.px(5) })
            layoutParams = tv.lp(68, 38)
        }
        settingRow(list, key, title, sub, true, sw) {
            stream.edit().putBoolean(key, !on).apply()
            FeelSounds.play(this, FeelSounds.Cue.TOGGLE, FeelSounds.panFor(sw))
            render(force = true)
        }
    }

    /** A switch stored with this app's own settings (mc_tv), not Moonlight's stream settings. */
    private fun tvToggle(list: LinearLayout, key: String, title: String, sub: String, def: Boolean) {
        val on = prefs.getBoolean(key, def)
        val sw = FrameLayout(this).apply {
            background = tv.round(if (on) Tv.BEAM else tv.p.line, 19)
            addView(View(this@HomeActivity).apply { background = tv.round(if (on) tv.p.night else tv.p.dust, 14) },
                    tv.flp(28, 28, Gravity.CENTER_VERTICAL or (if (on) Gravity.END else Gravity.START)).apply { leftMargin = tv.px(5); rightMargin = tv.px(5) })
            layoutParams = tv.lp(68, 38)
        }
        settingRow(list, key, title, sub, true, sw) {
            prefs.edit().putBoolean(key, !on).apply()
            FeelSounds.play(this, FeelSounds.Cue.TOGGLE, FeelSounds.panFor(sw))   // turning sounds on answers with the sound
            render(force = true)
        }
    }

    /** The PS5's own 4-digit login PIN, so the player can answer it (PlayStation settings). */
    private fun enterLoginPin() {
        val card = dialogCard()
        card.addView(tv.text("Console PIN", 40, tv.p.moon, tv.display(700)))
        card.addView(tv.text("The 4-digit PIN your PS5 asks for when it starts (if you set one). Leave empty to turn it off.",
                22, tv.p.dust, tv.ui(500), 3), tv.lp(-1, -2).apply { topMargin = tv.px(12) })
        val field = textField("4 digits", prefs.getString("ps5_login_pin", "") ?: "").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(4))
        }
        card.addView(field, tv.lp(480, 84).apply { topMargin = tv.px(24) })
        fun save() {
            val v = field.text.toString()
            if (v.isNotEmpty() && v.length != 4) { toast("The PIN has 4 digits"); return }
            prefs.edit().putString("ps5_login_pin", v).apply(); hideOverlay(); render(force = true)
        }
        field.setOnEditorActionListener { _, _, _ -> save(); true }
        card.addView(tv.row().apply { addView(tv.pill("Save", "A", true) { save() }); addView(tv.pill("Cancel", "B", false) { hideOverlay() }) },
                tv.lp(-2, -2).apply { topMargin = tv.px(24) })
        showOverlay("pspin", FrameLayout(this).apply { addView(card, tv.flp(900, -2, Gravity.CENTER)) })
        field.requestFocus()
    }

    private fun action(list: LinearLayout, key: String, title: String, sub: String, value: String?, onClick: () -> Unit) =
        settingRow(list, key.removePrefix("set:"), title, sub, true, value?.let { tv.text(it, 23, tv.p.moon, tv.ui(700)) }, onClick)

    private fun info(list: LinearLayout, title: String, value: String) =
        settingRow(list, "info:$title", title, null, false, tv.text(value, 22, tv.p.dust, tv.ui(700)), null)

    // ---------------------------------------------------------------- actions

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun start(pc: ComputerDetails, app: NvApp) {
        val b = binder ?: return toast(getString(R.string.error_manager_not_running))
        val other = runningApp(pc)
        if (other != null && other.appId != app.appId) {
            confirm("Quit ${other.appName}?", "${pc.name} is running ${other.appName}. Quit it and start ${app.appName}?",
                    "Quit and start") {
                FeelSounds.play(this, FeelSounds.Cue.LAUNCH)
                ServerHelper.doQuit(this, pc, other, b) { runOnUiThread { ServerHelper.doStart(this, app, pc, b) } }
            }
            return
        }
        FeelSounds.play(this, FeelSounds.Cue.LAUNCH)
        ServerHelper.doStart(this, app, pc, b)
    }

    private fun quit(pc: ComputerDetails, app: NvApp) {
        val b = binder ?: return
        confirm("Quit ${app.appName}?", "The game closes on ${pc.name}. Anything you haven't saved is lost.", "Quit game") {
            ServerHelper.doQuit(this, pc, app, b) { runOnUiThread { b.invalidateStateForComputer(pc.uuid) } }
        }
    }

    private fun wake(d: ComputerDetails) {
        if (d.macAddress == null || d.macAddress == "00:00:00:00:00:00") return toast(getString(R.string.wol_no_mac))
        Thread {
            val msg = try { WakeOnLanSender.sendWolPacket(d); "Waking ${d.name}. It shows as online when it's ready." }
                      catch (e: Exception) { getString(R.string.wol_fail) }
            runOnUiThread { toast(msg) }
        }.start()
    }

    private fun pair(d: ComputerDetails) {
        val b = binder ?: return toast(getString(R.string.error_manager_not_running))
        if (!online(d) || d.activeAddress == null) return toast(getString(R.string.pair_pc_offline))
        val pin = PairingManager.generatePinString()
        showPin(d, pin)
        stopPolling()
        Thread {
            var msg: String? = null
            var ok = false
            try {
                b.waitForPollingStopped()
                val http = NvHTTP(ServerHelper.getCurrentAddressFromComputer(d), d.httpsPort, b.uniqueId, d.serverCert,
                        PlatformBinding.getCryptoProvider(this))
                if (http.pairState == PairingManager.PairState.PAIRED) ok = true
                else {
                    val pm = http.pairingManager
                    when (pm.pair(http.getServerInfo(true), pin)) {
                        PairingManager.PairState.PIN_WRONG -> msg = getString(R.string.pair_incorrect_pin)
                        PairingManager.PairState.FAILED -> msg = getString(if (d.runningGameId != 0) R.string.pair_pc_ingame else R.string.pair_fail)
                        PairingManager.PairState.ALREADY_IN_PROGRESS -> msg = getString(R.string.pair_already_in_progress)
                        PairingManager.PairState.PAIRED -> {
                            ok = true
                            b.getComputer(d.uuid)?.serverCert = pm.pairedCert
                            b.invalidateStateForComputer(d.uuid)
                        }
                        else -> {}
                    }
                }
            } catch (e: UnknownHostException) { msg = getString(R.string.error_unknown_host)
            } catch (e: FileNotFoundException) { msg = getString(R.string.error_404)
            } catch (e: Exception) { msg = e.message ?: getString(R.string.pair_fail) }
            runOnUiThread {
                if (overlayKind == "pin") hideOverlay()
                msg?.let { toast(it) }
                if (ok) { toast("Paired with ${d.name}"); filter = d.uuid; tab = Tab.LIBRARY; detail = null }
                startPolling()
                render(force = true)
            }
        }.start()
    }

    private fun confirmUnpair(d: ComputerDetails) =
        confirm("Unpair ${d.name}?", "It stays on this TV, but you'll pair it again with a PIN before you can play its games.", "Unpair", danger = true) {
            val b = binder ?: return@confirm
            Thread {
                val ok = try {
                    NvHTTP(ServerHelper.getCurrentAddressFromComputer(d), d.httpsPort, b.uniqueId, d.serverCert,
                            PlatformBinding.getCryptoProvider(this)).unpair(); true
                } catch (e: Exception) { false }
                runOnUiThread { toast(if (ok) "Unpaired ${d.name}" else "Couldn't reach ${d.name} to unpair it"); b.invalidateStateForComputer(d.uuid) }
            }.start()
        }

    private fun confirmRemovePc(d: ComputerDetails) =
        confirm("Remove ${d.name}?", "It disappears from this TV and is unpaired. To add it back, you'll pair it again with a PIN.",
                "Remove and unpair", danger = true) { removePc(d) }

    private fun removePc(d: ComputerDetails) {
        val b = binder ?: return
        if (online(d) && paired(d)) Thread {
            try {
                NvHTTP(ServerHelper.getCurrentAddressFromComputer(d), d.httpsPort, b.uniqueId, d.serverCert,
                        PlatformBinding.getCryptoProvider(this)).unpair()
            } catch (e: Exception) { }
        }.start()
        appPollers.remove(d.uuid)?.stop()
        b.removeComputer(d)
        DiskAssetLoader(this).deleteAssetsForComputer(d.uuid)
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE).edit().remove(d.uuid).apply()
        try { ShortcutHelper(this).disableComputerShortcut(d, getString(R.string.scut_deleted_pc)) } catch (e: Exception) { }
        pcs.remove(d.uuid); apps.remove(d.uuid); rawLists.remove(d.uuid); loaders.remove(d.uuid)
        detail = null
        render(force = true)
    }

    private fun playPs(c: Ps5Store.Console) = Launch.playPs(this, c, power(c)) { toast(it) }

    private fun wakePs(c: Ps5Store.Console) {
        Thread {
            val ok = Ps5Store.wake(c)
            runOnUiThread { toast(if (ok) "Waking ${c.name}. It takes about a minute." else "Couldn't wake ${c.name}. Register it again from your Odin.") }
        }.start()
    }

    private fun confirmRemovePs(c: Ps5Store.Console) =
        confirm("Remove ${c.name}?", "It disappears from this TV and its registration here is deleted. To add it back, register it again from your Odin.",
                "Remove from this TV", danger = true) {
            Ps5Store.remove(this, c.host); consoles = Ps5Store.all(this); psStatus.remove(c.host); detail = null; render(force = true)
        }

    private fun addConsole() {
        if (pcs.values.any { !paired(it) && online(it) }) { go(Tab.CONSOLES); return }
        val card = dialogCard()
        card.addView(tv.text("Add a console", 40, tv.p.moon, tv.display(700)))
        card.addView(tv.text("PCs running Sunshine on your network appear here by themselves. Register a PlayStation here or from MoonCast on your Odin.",
                22, tv.p.dust, tv.ui(500), 4), tv.lp(-1, -2).apply { topMargin = tv.px(12); bottomMargin = tv.px(28) })
        card.addView(tv.row().apply {
            addView(tv.pill("Register a PS5", "A", true) { hideOverlay(); showRegister() }.apply { tag = "primary" })
            addView(tv.pill("Add a PC by address", null, false) { hideOverlay(); addPcByAddress() })
        })
        showOverlay("confirm", FrameLayout(this).apply { addView(card, tv.flp(1000, -2, Gravity.CENTER)) })
    }

    /** The PS5 is registered from the Odin (it has the PSN sign-in); this TV shows the steps and waits for it. */
    private var registerStatus: TextView? = null
    private fun showRegister() {
        val v = tv.row(Gravity.TOP).apply { setPadding(tv.px(96), tv.px(200), tv.px(96), 0) }
        val left = tv.col()
        left.addView(tv.row().apply { addView(tv.status("Add a PlayStation", "rest")) })
        left.addView(tv.text("Register it\nfrom your Odin", 72, tv.p.moon, tv.display(700), 2), tv.lp(-2, -2).apply { topMargin = tv.px(28) })
        left.addView(tv.text("The Odin has your PlayStation sign-in. Once it registers the PS5 through this TV, the console appears here.",
                27, tv.p.soft, tv.ui(500), 3), tv.lp(900, -2).apply { topMargin = tv.px(22) })
        registerStatus = tv.text("Waiting for your Odin. B cancels.", 24, tv.p.dust, tv.ui(600))
        left.addView(tv.row().apply {
            addView(View(this@HomeActivity).apply { background = tv.round(Tv.CORONA, 7) }, tv.lp(14, 14).apply { rightMargin = tv.px(14) })
            addView(registerStatus)
        }, tv.lp(-2, -2).apply { topMargin = tv.px(40) })
        v.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        val steps = tv.col().apply { background = tv.round(tv.p.deep, 24, tv.p.line, 1); setPadding(tv.px(40), tv.px(36), tv.px(40), tv.px(24)) }
        steps.addView(tv.text("Steps", 26, tv.p.moon, tv.display(600)))
        listOf("On the PS5, open Settings, System, Remote Play and choose Link device",
                "In MoonCast on your Odin, open the PlayStation and choose Register, with this TV as the screen",
                "Type the 8-digit code the PS5 shows").forEachIndexed { i, st ->
            steps.addView(tv.row(Gravity.TOP).apply {
                if (i > 0) background = topLine()
                setPadding(0, tv.px(18), 0, tv.px(18))
                addView(tv.text("${i + 1}", 17, tv.p.moon, tv.display(700)).apply { gravity = Gravity.CENTER; background = tv.round(tv.p.raise, 20, tv.p.line, 1) },
                        tv.lp(40, 40).apply { rightMargin = tv.px(18) })
                addView(tv.text(st, 23, tv.p.soft, tv.ui(500), 3), LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
        v.addView(steps, tv.lp(680, -2))
        left.addView(tv.row().apply {
            addView(tv.pill(if (Psn.signedIn(this@HomeActivity)) "Register on this TV" else "Sign in and register on this TV", "A", true) {
                registerHere() }.apply { tag = "primary" })
        }, tv.lp(-2, -2).apply { topMargin = tv.px(36) })
        showOverlay("register", v, dim = false)
    }

    // ---- registering on the TV itself: PSN sign-in (once) -> pick the PS5 -> 8-digit code ----
    private fun registerHere() {
        if (!Psn.signedIn(this)) { startActivityForResult(Intent(this, PsnSignInActivity::class.java), REQ_PSN_REGISTER); return }
        pickConsole()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PSN_REGISTER -> { toast("Signed in to PlayStation Network"); ui.post { pickConsole() } }
            REQ_PSN -> { toast("Signed in to PlayStation Network"); render(force = true) }
        }
    }

    private fun pickConsole() {
        val card = dialogCard()
        card.addView(tv.text("Which PlayStation?", 40, tv.p.moon, tv.display(700)))
        val sub = tv.text("Looking on this network…", 22, tv.p.dust, tv.ui(500), 3)
        card.addView(sub, tv.lp(-1, -2).apply { topMargin = tv.px(12); bottomMargin = tv.px(20) })
        val list = tv.col()
        card.addView(list, tv.lp(-1, -2))
        showOverlay("pickps", FrameLayout(this).apply { addView(card, tv.flp(1000, -2, Gravity.CENTER)) })
        Thread {
            val found = Ps5Store.discover()
            runOnUiThread {
                if (overlayKind != "pickps") return@runOnUiThread
                if (found.isEmpty()) {
                    sub.text = "No PlayStation answered. Make sure it's on or in rest mode (not fully off) and on this network."
                    list.addView(tv.row().apply { addView(tv.pill("Look again", "A", true) { pickConsole() }.apply { tag = "primary" }) })
                } else {
                    sub.text = "Pick yours. Then open Settings, System, Remote Play, Link device on it to see the code."
                    found.forEachIndexed { i, f ->
                        list.addView(tv.row().apply {
                            tag = if (i == 0) "primary" else "ps:${f.host}"
                            background = tv.focusable(0, 16, outline = false)
                            setPadding(tv.px(20), 0, tv.px(20), 0)
                            addView(tv.text("PS", 18, 0xFFFFFFFF.toInt(), tv.display(700)).apply { gravity = Gravity.CENTER; background = tv.round(0xFF2F5DF0.toInt(), 12) },
                                    tv.lp(52, 52).apply { rightMargin = tv.px(18) })
                            addView(tv.text(f.name, 26, tv.p.moon, tv.ui(700)), LinearLayout.LayoutParams(0, -2, 1f))
                            addView(tv.status(if (f.on) "On" else "Rest mode", if (f.on) "live" else "rest"))
                            addView(tv.text(f.host, 19, tv.p.dust, tv.ui(600)), tv.lp(-2, -2).apply { leftMargin = tv.px(8) })
                            tv.focusableView(this)
                            setOnClickListener { enterCode(f) }
                        }, tv.lp(-1, 88))
                    }
                }
                list.findViewWithTag<View>("primary")?.requestFocus()
            }
        }.start()
    }

    private var codeDigits = StringBuilder()
    private var codeBoxes = listOf<TextView>()
    private var codeStatus: TextView? = null
    private var codeTarget: Ps5Store.Found? = null

    /** The 8-digit "Link device" code: number pad for a pad or remote; number keys work too. */
    private fun enterCode(f: Ps5Store.Found) {
        codeTarget = f; codeDigits = StringBuilder()
        val v = tv.row(Gravity.TOP).apply { setPadding(tv.px(96), tv.px(170), tv.px(96), 0) }
        val left = tv.col()
        left.addView(tv.row().apply { addView(tv.status("Registering ${f.name}", "rest")) })
        left.addView(tv.text("Type the code\nfrom your PS5", 64, tv.p.moon, tv.display(700), 2), tv.lp(-2, -2).apply { topMargin = tv.px(24) })
        left.addView(tv.text("On the PS5: Settings, System, Remote Play, Link device.", 24, tv.p.soft, tv.ui(500), 2),
                tv.lp(900, -2).apply { topMargin = tv.px(16) })
        val boxes = tv.row()
        codeBoxes = (0 until 8).map { i ->
            tv.text("", 56, tv.p.moon, tv.display(700)).apply { gravity = Gravity.CENTER }
                    .also { b -> boxes.addView(b, tv.lp(92, 116).apply { rightMargin = tv.px(if (i == 3) 28 else 12) }) }
        }
        left.addView(boxes, tv.lp(-2, -2).apply { topMargin = tv.px(36) })
        codeStatus = tv.text("", 24, tv.p.dust, tv.ui(600), 2)
        left.addView(codeStatus, tv.lp(900, -2).apply { topMargin = tv.px(28) })
        v.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        val pad = GridLayout(this).apply { columnCount = 3 }
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK").forEach { k ->
            pad.addView(tv.text(k, 34, if (k == "OK") tv.p.night else tv.p.moon, tv.display(600)).apply {
                gravity = Gravity.CENTER
                tag = if (k == "1") "primary" else "key:$k"
                val fill = if (k == "OK") Tv.BEAM else tv.p.deep
                background = android.graphics.drawable.StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_focused), tv.round(if (k == "OK") Tv.BEAM else tv.p.raise, 20, Tv.CORONA, 3))
                    addState(intArrayOf(), tv.round(fill, 20, if (k == "OK") 0 else tv.p.line, 1))
                }
                tv.focusableView(this)
                setOnClickListener { codeKey(k) }
            }, GridLayout.LayoutParams().apply { width = tv.px(150); height = tv.px(110); rightMargin = tv.px(14); bottomMargin = tv.px(14) })
        }
        v.addView(pad, tv.lp(-2, -2))
        showOverlay("code", v, dim = false)
        paintCode()
    }

    private fun paintCode() {
        codeBoxes.forEachIndexed { i, b ->
            b.text = codeDigits.getOrNull(i)?.toString() ?: ""
            val cur = i == codeDigits.length
            b.background = tv.round(tv.p.deep, 16, if (cur) Tv.CORONA else tv.p.line, if (cur) 3 else 1)
        }
    }

    private fun codeKey(k: String) {
        when (k) {
            "⌫" -> if (codeDigits.isNotEmpty()) codeDigits.deleteCharAt(codeDigits.length - 1)
            "OK" -> { submitCode(); return }
            else -> if (codeDigits.length < 8) codeDigits.append(k)
        }
        paintCode()
        if (codeDigits.length == 8) overlay.findViewWithTag<View>("key:OK")?.requestFocus()
    }

    private fun submitCode() {
        val f = codeTarget ?: return
        if (codeDigits.length != 8) { codeStatus?.text = "The code has 8 digits."; return }
        val acc = Psn.accountId(this) ?: return toast("Sign in to PlayStation Network first")
        val pin = codeDigits.toString().toInt()
        codeStatus?.text = "Registering with ${f.name}…"
        Thread {
            val r = try { com.limelight.Ps5Register.register(f.host, android.util.Base64.decode(acc, android.util.Base64.DEFAULT), pin, true, 30000L) }
                    catch (e: Exception) { null }
            if (r != null) Ps5Store.save(this, Ps5Store.Console(f.host, r.nickname.ifBlank { f.name }, r.registKeyHex, r.morningHex, true))
            runOnUiThread {
                if (r == null) codeStatus?.text = "That didn't work. Check the code (it changes every few minutes) and that the PS5 still shows Link device."
                else onRegistered(f.host, r.nickname.ifBlank { f.name })
            }
        }.start()
    }

    override fun onRegistering(host: String) {
        if (overlayKind != "register") showRegister()
        registerStatus?.text = "Registering with the PS5 at $host…"
    }

    override fun onRegistered(host: String, name: String?) {
        if (name == null) {
            if (overlayKind == "register") registerStatus?.text = "That didn't work. Check the 8-digit code and that the PS5 is on, then try again on the Odin."
            return
        }
        if (overlayKind == "register") hideOverlay()
        toast("$name added")
        consoles = Ps5Store.all(this)
        tab = Tab.CONSOLES
        open("ps:$host")
    }

    private fun addPcByAddress() {
        val card = dialogCard()
        card.addView(tv.text("Add a PC by its address", 40, tv.p.moon, tv.display(700)))
        card.addView(tv.text("The PC's IP address or name, the one Sunshine runs on. Add :port only if it isn't the usual one.",
                22, tv.p.dust, tv.ui(500), 3), tv.lp(-1, -2).apply { topMargin = tv.px(12) })
        val field = textField("For example 192.168.1.20", "")
        card.addView(field, tv.lp(-1, 84).apply { topMargin = tv.px(24) })
        val status = tv.text("", 21, tv.p.dust, tv.ui(600), 2)
        card.addView(status, tv.lp(-1, -2).apply { topMargin = tv.px(16) })
        fun submit() {
            val raw = field.text.toString().trim()
            if (raw.isEmpty()) { status.text = "Type the PC's address first."; return }
            val b = binder ?: return
            status.text = "Looking for a PC at $raw…"
            Thread {
                val ok = try {
                    val uri = try { java.net.URI("moonlight://$raw") } catch (e: Exception) { java.net.URI("moonlight://[$raw]") }
                    val host = uri.host ?: throw IllegalArgumentException()
                    val d = ComputerDetails()
                    d.manualAddress = ComputerDetails.AddressTuple(host, if (uri.port == -1) NvHTTP.DEFAULT_HTTP_PORT else uri.port)
                    b.addComputerBlocking(d)
                } catch (e: Exception) { false }
                runOnUiThread {
                    if (ok) { hideOverlay(); toast("Added. Pair it from its page."); go(Tab.CONSOLES) }
                    else status.text = "No PC answered at $raw. Check it's on, Sunshine is running, and the address is right."
                }
            }.start()
        }
        field.setOnEditorActionListener { _, _, _ -> submit(); true }
        card.addView(tv.row().apply {
            addView(tv.pill("Add", "A", true) { submit() })
            addView(tv.pill("Cancel", "B", false) { hideOverlay() })
        }, tv.lp(-2, -2).apply { topMargin = tv.px(24) })
        showOverlay("addpc", FrameLayout(this).apply { addView(card, tv.flp(1000, -2, Gravity.CENTER)) })
        field.requestFocus()
    }

    /** Settings, Xbox, Bitrate: Auto lets the Xbox decide; a number caps it (XStreaming's "Host stream bitrate"). */
    private fun xboxBitrate(list: LinearLayout) {
        val steps = listOf(0, 5, 10, 15, 20, 30)
        val cur = stream.getInt("xbox_bitrate", 0)
        settingRow(list, "xbox_bitrate", "Bitrate", "Auto lets the Xbox decide. A cap keeps a weak Wi-Fi from stuttering",
                true, tv.text(if (cur == 0) "Auto" else "$cur Mbps", 23, tv.p.moon, tv.ui(700))) {
            val next = steps[(steps.indexOf(steps.firstOrNull { it >= cur } ?: 0) + 1) % steps.size]
            stream.edit().putInt("xbox_bitrate", next).apply()
            render(force = true)
        }
        val custom = cur != 0 && cur !in steps
        action(list, "set:xboxrate", "Custom bitrate", "Any cap from 1 to 50 Mbps", if (custom) "$cur Mbps" else "Off") {
            entryDialog("Xbox bitrate", "A cap in Mbps, from 1 to 50. The Xbox stays at or below it.", "For example 12",
                    if (cur > 0) cur.toString() else "", true, onSave = { raw ->
                val v = raw.toIntOrNull()
                if (v == null || v !in 1..50) "A number from 1 to 50."
                else { stream.edit().putInt("xbox_bitrate", v).apply(); render(force = true); null }
            }, clear = if (cur > 0) ({ stream.edit().putInt("xbox_bitrate", 0).apply(); render(force = true) }) else null)
        }
    }

    /** This screen's physical resolution, landscape (the display mode, not the app's possibly scaled window). */
    private fun nativeResolution(): String {
        val d = windowManager.defaultDisplay
        var w: Int; var h: Int
        if (Build.VERSION.SDK_INT >= 23) {
            val m = d.mode
            w = m.physicalWidth; h = m.physicalHeight
        } else {
            val p = android.graphics.Point(); d.getRealSize(p); w = p.x; h = p.y
        }
        if (h > w) { val t = w; w = h; h = t }
        return "${w and 1.inv()}x${h and 1.inv()}"
    }

    /** The chip, for the Ultra low latency hint: SoC model on Android 12+, otherwise the board name. */
    private fun chipName(): String = com.limelight.home.Chip.name()

    /** A one-line entry dialog; [onSave] returns an error to show, or null when the value is good. */
    private fun entryDialog(title: String, sub: String, hint: String, value: String, numbers: Boolean, onSave: (String) -> String?,
                            clear: (() -> Unit)? = null) {
        val card = dialogCard()
        card.addView(tv.text(title, 40, tv.p.moon, tv.display(700)))
        card.addView(tv.text(sub, 22, tv.p.dust, tv.ui(500), 3), tv.lp(-1, -2).apply { topMargin = tv.px(12) })
        val field = textField(hint, value)
        if (numbers) field.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        card.addView(field, tv.lp(-1, 84).apply { topMargin = tv.px(24) })
        val status = tv.text("", 21, tv.p.dust, tv.ui(600), 2)
        card.addView(status, tv.lp(-1, -2).apply { topMargin = tv.px(16) })
        fun submit() {
            val err = onSave(field.text.toString().trim())
            if (err == null) hideOverlay() else status.text = err
        }
        field.setOnEditorActionListener { _, _, _ -> submit(); true }
        card.addView(tv.row().apply {
            addView(tv.pill("Save", "A", true) { submit() })
            if (clear != null) addView(tv.pill("Turn off", null, false) { clear(); hideOverlay() })
            addView(tv.pill("Cancel", "B", false) { hideOverlay() })
        }, tv.lp(-2, -2).apply { topMargin = tv.px(24) })
        showOverlay("entry", FrameLayout(this).apply { addView(card, tv.flp(1000, -2, Gravity.CENTER)) })
        field.requestFocus()
    }

    private fun customResolution(current: String, isCustom: Boolean) {
        entryDialog("Custom resolution", "Width x height, for example 1600x900 or 2560x1080. Both even numbers.",
                "For example 1600x900", if (isCustom) current else "", false, onSave = { raw ->
            val m = Regex("^(\\d{3,4})\\s*[x×*X ]\\s*(\\d{3,4})$").find(raw)
            val w = m?.groupValues?.get(1)?.toIntOrNull(); val h = m?.groupValues?.get(2)?.toIntOrNull()
            when {
                w == null || h == null -> "Type it as width x height, for example 1600x900."
                w !in 640..7680 || h !in 360..4320 -> "Between 640x360 and 7680x4320."
                w % 2 != 0 || h % 2 != 0 -> "Both numbers need to be even."
                else -> { stream.edit().putString("list_resolution", "${w}x$h").apply(); render(force = true); null }
            }
        }, clear = if (isCustom) ({ stream.edit().putString("list_resolution", "1920x1080").apply(); render(force = true) }) else null)
    }

    private fun customBitrate(kbps: Int) {
        entryDialog("Custom bitrate", "In Mbps, from 1 to 150. Higher is sharper and needs a faster network.",
                "For example 25", if (kbps > 0) (kbps / 1000).toString() else "", true, onSave = { raw ->
            val v = raw.toIntOrNull()
            if (v == null || v !in 1..150) "A number from 1 to 150."
            else { stream.edit().putInt("seekbar_bitrate_kbps", v * 1000).apply(); render(force = true); null }
        }, clear = if (kbps > 0) ({ stream.edit().remove("seekbar_bitrate_kbps").apply(); render(force = true) }) else null)
    }

    private fun pairController() {
        for (a in listOf(Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_SETTINGS)) {
            try { startActivity(Intent(a)); return } catch (e: Exception) { }
        }
        toast("Open this TV's settings, then Remotes and accessories, to pair a controller.")
    }

    // ---------------------------------------------------------------- overlays

    private fun showOverlay(kind: String, v: View, dim: Boolean = true) {
        if (kind != "idle" && kind != "incoming") FeelSounds.play(this, FeelSounds.Cue.OPEN)
        overlayKind = kind
        overlay.removeAllViews()
        overlay.setBackgroundColor(if (dim) (if (tv.oled) 0xE6000000.toInt() else 0xB805080F.toInt()) else tv.p.night)
        overlay.addView(v)
        overlay.visibility = View.VISIBLE
        main.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        (overlay.findViewWithTag<View>("primary") ?: firstFocusable(overlay))?.requestFocus()
    }

    private fun hideOverlay(cue: Boolean = true) {
        if (cue && overlayKind != null && overlayKind != "idle" && overlayKind != "incoming") FeelSounds.play(this, FeelSounds.Cue.CLOSE)
        try {   // close the TV keyboard too (search / add PC / PIN boxes), or it stays up and squashes the screen
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(window.decorView.windowToken, 0)
        } catch (e: Exception) { }
        overlayKind = null
        overlay.removeAllViews()
        overlay.visibility = View.GONE
        main.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        render(force = true)
    }

    private fun pickLanguage(langs: Array<String>, names: Array<String>, cur: Int) {
        val card = dialogCard()
        card.addView(tv.text("Language while playing", 40, tv.p.moon, tv.display(700)))
        card.addView(tv.text("Takes effect from the next game.", 22, tv.p.dust, tv.ui(500)),
                tv.lp(-1, -2).apply { topMargin = tv.px(12); bottomMargin = tv.px(20) })
        val list = tv.col()
        langs.forEachIndexed { i, v ->
            list.addView(tv.row().apply {
                tag = if (i == cur) "primary" else "lang:$v"
                background = tv.focusable(0, 16, outline = false)
                setPadding(tv.px(20), 0, tv.px(20), 0)
                addView(tv.text(if (i == 0) "Same as the TV" else names[i], 26, tv.p.moon, tv.ui(700)), LinearLayout.LayoutParams(0, -2, 1f))
                if (i == cur) addView(tv.status("Now", "live"))
                tv.focusableView(this)
                setOnClickListener { stream.edit().putString("list_languages", v).apply(); hideOverlay() }
            }, tv.lp(-1, 72))
        }
        card.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }, tv.lp(-1, 560))
        showOverlay("lang", FrameLayout(this).apply { addView(card, tv.flp(900, -2, Gravity.CENTER)) })
    }

    private fun dialogCard(): LinearLayout = tv.col().apply {
        background = tv.round(tv.p.deep, 24, tv.p.line, 1)
        setPadding(tv.px(44), tv.px(44), tv.px(44), tv.px(40))
    }

    private fun confirm(title: String, body: String, yes: String, danger: Boolean = false, onYes: () -> Unit) {
        val card = dialogCard()
        card.addView(tv.text(title, 40, tv.p.moon, tv.display(700), 2))
        card.addView(tv.text(body, 22, tv.p.dust, tv.ui(500), 4), tv.lp(-1, -2).apply { topMargin = tv.px(12); bottomMargin = tv.px(28) })
        card.addView(tv.row().apply {
            addView(tv.pill(yes, "A", !danger) {
                // one sound per action: what the answer starts (a launch) speaks for it, else the dialog's close
                val n = FeelSounds.serial
                hideOverlay(cue = false); onYes()
                if (n == FeelSounds.serial) FeelSounds.play(this@HomeActivity, FeelSounds.Cue.CLOSE)
            }.apply {
                tag = "primary"; if (danger) (getChildAt(childCount - 1) as TextView).setTextColor(Tv.DANGER) })
            addView(tv.pill("Cancel", "B", false) { hideOverlay() })
        })
        showOverlay("confirm", FrameLayout(this).apply { addView(card, tv.flp(900, -2, Gravity.CENTER)) })
    }

    private fun showPin(d: ComputerDetails, pin: String) {
        val v = tv.row(Gravity.TOP).apply { setPadding(tv.px(96), tv.px(200), tv.px(96), 0) }
        val left = tv.col()
        left.addView(tv.row().apply { addView(tv.status("Pairing with ${d.name}", "rest")) })
        left.addView(tv.text("Enter this PIN\nin Sunshine", 72, tv.p.moon, tv.display(700), 2), tv.lp(-2, -2).apply { topMargin = tv.px(28) })
        left.addView(tv.row().apply {
            pin.forEach { ch ->
                addView(tv.text(ch.toString(), 120, tv.p.moon, tv.display(700)).apply {
                    gravity = Gravity.CENTER; background = tv.round(tv.p.deep, 24, tv.p.line, 1)
                }, tv.lp(168, 208).apply { rightMargin = tv.px(24) })
            }
        }, tv.lp(-2, -2).apply { topMargin = tv.px(48) })
        left.addView(tv.text("Waiting for the PC to accept. B cancels.", 24, tv.p.dust, tv.ui(600)), tv.lp(-2, -2).apply { topMargin = tv.px(40) })
        v.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        val steps = tv.col().apply { background = tv.round(tv.p.deep, 24, tv.p.line, 1); setPadding(tv.px(40), tv.px(36), tv.px(40), tv.px(24)) }
        steps.addView(tv.text("On the PC", 26, tv.p.moon, tv.display(600)))
        val host = d.activeAddress?.address ?: "the PC"
        listOf("Open https://$host:47990 in a browser on the same network", "Go to the PIN page",
                "Type $pin, name it ${deviceName()}, and press Send").forEachIndexed { i, s ->
            steps.addView(tv.row(Gravity.TOP).apply {
                if (i > 0) background = topLine()
                setPadding(0, tv.px(18), 0, tv.px(18))
                addView(tv.text("${i + 1}", 17, tv.p.moon, tv.display(700)).apply { gravity = Gravity.CENTER; background = tv.round(tv.p.raise, 20, tv.p.line, 1) },
                        tv.lp(40, 40).apply { rightMargin = tv.px(18) })
                addView(tv.text(s, 23, tv.p.soft, tv.ui(500), 3), LinearLayout.LayoutParams(0, -2, 1f))
            })
        }
        v.addView(steps, tv.lp(680, -2))
        showOverlay("pin", v, dim = false)
    }

    private fun deviceName(): String =
        (if (Build.VERSION.SDK_INT >= 25) Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) else null) ?: Build.MODEL

    override fun onIncoming(title: String, from: String) {
        val v = tv.col().apply { gravity = Gravity.CENTER_HORIZONTAL }
        v.addView(tv.row().apply {
            background = tv.round(tv.p.deep, 26, tv.p.line, 1); setPadding(tv.px(22), 0, tv.px(22), 0)
            addView(View(this@HomeActivity).apply { background = tv.round(Tv.BEAM, 5) }, tv.lp(10, 10).apply { rightMargin = tv.px(14) })
            addView(tv.text("Sent from $from", 23, tv.p.moon, tv.ui(700)))
        }, tv.lp(-2, 52))
        v.addView(tv.text(title, 84, tv.p.moon, tv.display(700), 2).apply { gravity = Gravity.CENTER }, tv.lp(-2, -2).apply { topMargin = tv.px(34) })
        v.addView(tv.text("Starting on this TV. Your phone is the controller.", 27, tv.p.soft, tv.ui(500)), tv.lp(-2, -2).apply { topMargin = tv.px(18) })
        showOverlay("incoming", FrameLayout(this).apply { addView(v, tv.flp(-2, -2, Gravity.CENTER)) }, dim = false)
        ui.postDelayed({ if (overlayKind == "incoming") hideOverlay() }, 20_000)
    }

    override fun onConsolesChanged() { consoles = Ps5Store.all(this); render(force = true) }

    private fun askOled() {
        if (overlayKind != null) return
        val card = dialogCard()
        card.addView(tv.text("Is this TV an OLED?", 40, tv.p.moon, tv.display(700)))
        card.addView(tv.text("OLED screens get true black, quieter menus and a moving screen saver, so nothing burns in. You can change it later in Settings.",
                22, tv.p.dust, tv.ui(500), 4), tv.lp(-1, -2).apply { topMargin = tv.px(12); bottomMargin = tv.px(28) })
        card.addView(tv.row().apply {
            addView(tv.pill("Yes, it's OLED", "A", true) { prefs.edit().putBoolean("oled_asked", true).putBoolean("oled", true).apply(); recreate() }.apply { tag = "primary" })
            addView(tv.pill("No", null, false) { prefs.edit().putBoolean("oled_asked", true).apply(); hideOverlay() })
        })
        showOverlay("oled", FrameLayout(this).apply { addView(card, tv.flp(900, -2, Gravity.CENTER)) })
    }

    private var idleClock: TextView? = null
    private fun showIdle() {
        val f = FrameLayout(this)
        val c = tv.col()
        idleClock = tv.text(DateFormat.getTimeFormat(this).format(Date()), 64, 0xFF5E6676.toInt(), tv.display(500))
        c.addView(idleClock)
        c.addView(tv.text("Ready to play. Send a game from your phone.", 24, 0xFF4C5363.toInt(), tv.ui(600)), tv.lp(-2, -2).apply { topMargin = tv.px(12) })
        f.addView(c, FrameLayout.LayoutParams(-2, -2))
        overlayKind = "idle"
        overlay.removeAllViews(); overlay.setBackgroundColor(0xFF000000.toInt()); overlay.addView(f); overlay.visibility = View.VISIBLE
        main.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        moveIdle()
    }

    /** The screen saver drifts so no pixel stays lit. */
    private fun moveIdle() {
        val f = overlay.getChildAt(0) as? FrameLayout ?: return
        val c = f.getChildAt(0) ?: return
        idleClock?.text = DateFormat.getTimeFormat(this).format(Date())
        val w = resources.displayMetrics.widthPixels; val h = resources.displayMetrics.heightPixels
        c.translationX = (Math.random() * (w * 0.6)).toFloat() + w * 0.1f
        c.translationY = (Math.random() * (h * 0.6)).toFloat() + h * 0.1f
    }

    // ---------------------------------------------------------------- input

    private var shiftTick = 0
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        lastInput = System.currentTimeMillis()
        if (hintsBar.alpha < (if (tv.oled) 0.72f else 1f)) hintsBar.animate().alpha(if (tv.oled) 0.72f else 1f).setDuration(150).start()
        if (overlayKind == "idle") { if (e.action == KeyEvent.ACTION_UP) hideOverlay(); return true }
        if (overlayKind == "code" && e.action == KeyEvent.ACTION_DOWN) {
            when (e.keyCode) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { codeKey((e.keyCode - KeyEvent.KEYCODE_0).toString()); return true }
                KeyEvent.KEYCODE_DEL -> { codeKey("⌫"); return true }
            }
        }
        if (e.action == KeyEvent.ACTION_DOWN && overlayKind == null && detail == null) {
            val i = Tab.values().indexOf(tab)
            when (e.keyCode) {
                KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_SEARCH -> if (tab == Tab.LIBRARY) { openSearch(); return true }
                KeyEvent.KEYCODE_BUTTON_L1 -> { FeelSounds.play(this, FeelSounds.Cue.TAB, -0.3f); go(Tab.values()[(i + 3) % 4]); return true }
                KeyEvent.KEYCODE_BUTTON_R1 -> { FeelSounds.play(this, FeelSounds.Cue.TAB, 0.3f); go(Tab.values()[(i + 1) % 4]); return true }
            }
        }
        return feel(e)
    }

    /**
     * Sound and motion for ordinary navigation: a focus tick when the d-pad moves the focus, a soft refusal and a small
     * shake when it hits the end of a row, and a select sound for a confirm press nothing else answered.
     */
    private fun feel(e: KeyEvent): Boolean {
        val dir = Feel.direction(e.keyCode)
        if (dir != 0 && e.action == KeyEvent.ACTION_DOWN) {
            val before = currentFocus
            if (super.dispatchKeyEvent(e)) {    // a view moved the focus itself (a row's own Left, a list)
                val after = currentFocus
                if (after != null && after !== before) FeelSounds.focus(after)
                return true
            }
            if (before == null || before is EditText || overlayKind == "code") return false
            // Nothing took the key, so Android would move the focus next and play its own click. Move it here the same
            // way instead, so MoonCast's sound replaces the system one rather than playing on top of it.
            val root = window.decorView as ViewGroup
            val next = before.focusSearch(dir)
            if (next != null && next !== before) {
                val r = android.graphics.Rect()
                before.getFocusedRect(r)
                root.offsetDescendantRectToMyCoords(before, r)
                root.offsetRectIntoDescendantCoords(next, r)
                if (next.requestFocus(dir, r)) {
                    if (FeelSounds.enabled(this)) FeelSounds.focus(next)
                    else next.playSoundEffect(android.view.SoundEffectConstants.getContantForFocusDirection(dir))
                    return true
                }
                return false
            }
            Feel.edgeNudge(before, dir, e, tv.pxf(14))
            return true
        }
        val confirm = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER ||
                e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || e.keyCode == KeyEvent.KEYCODE_BUTTON_A
        val focused = currentFocus
        if (confirm && e.action == KeyEvent.ACTION_UP && focused != null && focused !is EditText) {
            val pan = FeelSounds.panFor(focused)
            val n = FeelSounds.serial
            val handled = super.dispatchKeyEvent(e)
            // the click may have played its own cue (a launch, a dialog opening); then stay quiet
            if (handled && n == FeelSounds.serial) FeelSounds.play(this, FeelSounds.Cue.SELECT, pan)
            return handled
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            overlayKind != null -> hideOverlay()
            detail != null -> { FeelSounds.play(this, FeelSounds.Cue.BACK); val d = detail; detail = null; render(force = true); content.findViewWithTag<View>(d)?.requestFocus() }
            tab != Tab.HOME -> { FeelSounds.play(this, FeelSounds.Cue.BACK); go(Tab.HOME) }
            else -> { FeelSounds.play(this, FeelSounds.Cue.BACK); moveTaskToBack(true) }   // keep listening for the phone
        }
    }

    private fun rounded(r: Number) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, tv.pxf(r))
    }

    companion object {
        private const val Service_BIND = Context.BIND_AUTO_CREATE
        private const val REQ_PSN_REGISTER = 41
        private const val REQ_PSN = 42
    }
}
