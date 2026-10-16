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
    private val ui = Handler(Looper.getMainLooper())

    // Moonlight's PC manager
    private var binder: ComputerManagerService.ComputerManagerBinder? = null
    private var polling = false
    private var resumed = false
    private val pcs = LinkedHashMap<String, ComputerDetails>()
    private val apps = HashMap<String, List<NvApp>>()
    private val rawLists = HashMap<String, String>()
    private val loaders = HashMap<String, CachedAppAssetLoader>()
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
        PhoneLinkService.listener = this
        if (overlayKind == "incoming") hideOverlay()
        consoles = Ps5Store.all(this)
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
        probes.shutdownNow()
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
        val sig = signature()
        if (!force && sig == lastSig) return
        lastSig = sig
        val focused = currentFocus
        val inTabs = focused != null && focused.parent === tabsRow
        val focusTag = focused?.tag as? String
        content.removeAllViews()
        content.addView(page(), FrameLayout.LayoutParams(-1, -1))
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
    }

    private fun page(): View {
        detail?.let { d ->
            if (d.startsWith("pc:")) pcs[d.removePrefix("pc:")]?.let { return pcPage(it) }
            if (d.startsWith("ps:")) consoles.firstOrNull { it.host == d.removePrefix("ps:") }?.let { return psPage(it) }
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
                    loader(running).populateImageView(app, this, TextView(this@HomeActivity))
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
            addView(tv.text("A PC by its address, or a PS5 from your Odin", 21, tv.p.dust, tv.ui(500), 2), tv.lp(-1, -2).apply { topMargin = tv.px(10) })
        })
        addView(tv.text("+", 34, tv.p.moon, tv.display(600)).apply { gravity = Gravity.CENTER; background = tv.round(tv.p.raise, 16, tv.p.line, 1) },
                tv.flp(60, 60, Gravity.END or Gravity.BOTTOM))
    }

    // ---------------------------------------------------------------- Library

    private fun libraryPage(): View {
        hints("A" to "Play on this TV", "LB" to "Previous tab", "RB" to "Next tab", "|" to "", "B" to "Back")
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
        if (sources.size + (if (consoles.isEmpty()) 0 else 1) > 1) {
            chips.addView(chip("All", null), tv.lp(-2, 44).apply { rightMargin = tv.px(12) })
            sources.forEach { chips.addView(chip(it.name, it.uuid), tv.lp(-2, 44).apply { rightMargin = tv.px(12) }) }
            if (consoles.isNotEmpty()) chips.addView(chip("PlayStation", "ps"), tv.lp(-2, 44).apply { rightMargin = tv.px(12) })
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
        if (filter == null || filter == "ps") consoles.forEach { c -> add(psCover(c)) }
        sources.filter { filter == null || filter == it.uuid }.forEach { pc ->
            (apps[pc.uuid] ?: emptyList()).sortedWith(compareByDescending<NvApp> { it.appId == pc.runningGameId }.thenBy { it.appName.lowercase() })
                    .forEach { add(appCover(pc, it)) }
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
            setPadding(tv.px(10), tv.px(10), tv.px(10), tv.px(10))
            addView(grid)
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
        left.addView(tv.row().apply { actions.forEach { addView(it) } }, tv.lp(-2, -2).apply { topMargin = tv.px(36) })
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
            Ps5Store.Power.NOT_FOUND -> "Not on this network. To play away from home, send it from MoonCast on your Odin."
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
        val route = if (pw == Ps5Store.Power.NOT_FOUND) "Send from your Odin" else "Home network"
        return detailShell("PS", 0xFF2F5DF0.toInt(), c.name, line, states, actions,
                panel("Stream", listOf("Route" to route, "Picture" to "1080p, 60 fps, H.265", "Quality" to "Auto, 20 to 30 Mbps",
                        "Controller" to "Your phone or a pad on this TV"),
                        "Buttons your pad doesn't have", listOf("PS button" to "L2 + Start", "Touchpad" to "Select + Start")))
    }

    // ---------------------------------------------------------------- Settings

    private var settingsGroup = "video"
    private val stream by lazy { android.preference.PreferenceManager.getDefaultSharedPreferences(this) }

    private fun settingsPage(): View {
        hints("A" to "Change", "LB" to "Previous tab", "RB" to "Next tab", "|" to "", "B" to "Back")
        val groups = listOf("video" to "Video", "audio" to "Audio", "pads" to "Controllers", "ps" to "PlayStation",
                "screen" to "Screen", "about" to "About and help")
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
                choice(list, "list_resolution", "Resolution", "Higher is sharper and needs more bitrate",
                        listOf("720p" to "1280x720", "1080p" to "1920x1080", "1440p" to "2560x1440", "4K" to "3840x2160"), "1280x720")
                choice(list, "list_fps", "Frame rate", "MoonCast streams at 60 unless you raise it", listOf("60" to "60", "90" to "90", "120" to "120"), "60")
                bitrate(list)
                choice(list, "video_format", "Video codec", "H.265 and AV1 look better at the same bitrate",
                        listOf("Auto" to "auto", "H.264" to "neverh265", "H.265" to "forceh265", "AV1" to "forceav1"), "auto")
                toggle(list, "checkbox_enable_hdr", "HDR", "Only when the PC, the game and this TV support it", false)
                toggle(list, "checkbox_full_range", "Full-range colour", "Deeper blacks, if this TV shows them correctly", false)
                toggle(list, "checkbox_stretch_video", "Stretch to fill", "Fills the screen when the shape doesn't match", false)
                choice(list, "frame_pacing", "Frame pacing", "Trade a little delay for smoother motion",
                        listOf("Lowest delay" to "latency", "Balanced" to "balanced", "Smoothest" to "smoothness"), "latency")
            }
            "audio" -> {
                choice(list, "list_audio_config", "Speakers", "Match what's connected to this TV",
                        listOf("Stereo" to "2", "5.1" to "51", "7.1" to "71"), "2")
                toggle(list, "checkbox_host_audio", "Also play sound on the PC", "Useful when someone's in the room with it", false)
            }
            "pads" -> {
                action(list, "set:pad", "Pair a controller", "Opens this TV's Bluetooth settings. Put the pad in pairing mode first", null) { pairController() }
                toggle(list, "checkbox_multi_controller", "More than one player", "On a PC each pad is its own player. On the PS5, every pad is player 1", true)
                intChoice(list, "seekbar_deadzone", "Stick deadzone", "Raise it if a character drifts on its own", listOf(3, 5, 7, 10, 15), 7) { "$it%" }
                toggle(list, "checkbox_flip_face_buttons", "Swap A and B", "For Nintendo-style layouts", false)
                toggle(list, "checkbox_gamepad_motion_sensors", "Motion", "Send the pad's gyro to games that use it", true)
                toggle(list, "checkbox_mouse_emulation", "Use the pad as a mouse", "Hold Start to switch", true)
                info(list, "Quick menu", QuickMenu.COMBO_LABEL + ", or Back on the TV remote")
            }
            "ps" -> {
                choice(list, "ps5_resolution", "Resolution", "The PS5 streams up to 1080p", listOf("720p" to "720", "1080p" to "1080"), "1080")
                info(list, "Frame rate", "60 fps")
                choice(list, "ps5_bitrate_home", "Bitrate at home", "Auto: 30 Mbps on fast Wi-Fi or cable, 20 otherwise",
                        listOf("Auto" to "auto", "10" to "10", "15" to "15", "20" to "20", "30" to "30"), "auto")
                choice(list, "ps5_codec", "Video codec", "H.265 looks better at the same bitrate", listOf("H.264" to "h264", "H.265" to "h265"), "h265")
                toggle(list, "ps5_swap_cross_circle", "Swap Cross and Circle", "For the Japanese layout", false)
                info(list, "PS button", "L2 + Start")
                info(list, "Touchpad", "Select + Start")
            }
            "screen" -> {
                action(list, "set:oled", "OLED screen", "True black, quieter menus, and a dim screen saver that moves", if (tv.oled) "On" else "Off") {
                    prefs.edit().putBoolean("oled", !tv.oled).apply(); recreate() }
                toggle(list, "checkbox_enable_perf_overlay", "Stats while playing", "Frame rate, delay and lost frames in a corner of the game", false)
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
    private fun choice(list: LinearLayout, key: String, title: String, sub: String, options: List<Pair<String, String>>, def: String) {
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
            stream.edit().putString(key, options[(i + 1) % options.size].second).apply()
            render(force = true)
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
        settingRow(list, key, title, sub, true, sw) { stream.edit().putBoolean(key, !on).apply(); render(force = true) }
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
                ServerHelper.doQuit(this, pc, other, b) { runOnUiThread { ServerHelper.doStart(this, app, pc, b) } }
            }
            return
        }
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

    private fun playPs(c: Ps5Store.Console) {
        if (power(c) == Ps5Store.Power.NOT_FOUND)
            return toast("${c.name} isn't on this network. To play away from home, send it from MoonCast on your Odin.")
        startActivity(PhoneLink.ps5Intent(this, c.host, c.registKey, c.morning, c.ps5, null, null, null))
    }

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
        card.addView(tv.text("PCs running Sunshine on your network appear here by themselves. A PlayStation is registered once from MoonCast on your Odin.",
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
        showOverlay("register", v, dim = false)
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

    private fun addPcByAddress() = startActivity(Intent(this, AddComputerManually::class.java))

    private fun pairController() {
        for (a in listOf(Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_SETTINGS)) {
            try { startActivity(Intent(a)); return } catch (e: Exception) { }
        }
        toast("Open this TV's settings, then Remotes and accessories, to pair a controller.")
    }

    // ---------------------------------------------------------------- overlays

    private fun showOverlay(kind: String, v: View, dim: Boolean = true) {
        overlayKind = kind
        overlay.removeAllViews()
        overlay.setBackgroundColor(if (dim) (if (tv.oled) 0xE6000000.toInt() else 0xB805080F.toInt()) else tv.p.night)
        overlay.addView(v)
        overlay.visibility = View.VISIBLE
        main.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        (overlay.findViewWithTag<View>("primary") ?: firstFocusable(overlay))?.requestFocus()
    }

    private fun hideOverlay() {
        overlayKind = null
        overlay.removeAllViews()
        overlay.visibility = View.GONE
        main.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        render(force = true)
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
            addView(tv.pill(yes, "A", !danger) { hideOverlay(); onYes() }.apply {
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

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        lastInput = System.currentTimeMillis()
        if (overlayKind == "idle") { if (e.action == KeyEvent.ACTION_UP) hideOverlay(); return true }
        if (e.action == KeyEvent.ACTION_DOWN && overlayKind == null && detail == null) {
            val i = Tab.values().indexOf(tab)
            when (e.keyCode) {
                KeyEvent.KEYCODE_BUTTON_L1 -> { go(Tab.values()[(i + 3) % 4]); return true }
                KeyEvent.KEYCODE_BUTTON_R1 -> { go(Tab.values()[(i + 1) % 4]); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            overlayKind != null -> hideOverlay()
            detail != null -> { val d = detail; detail = null; render(force = true); content.findViewWithTag<View>(d)?.requestFocus() }
            tab != Tab.HOME -> go(Tab.HOME)
            else -> moveTaskToBack(true)   // keep listening for the phone
        }
    }

    private fun rounded(r: Number) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, tv.pxf(r))
    }

    companion object { private const val Service_BIND = Context.BIND_AUTO_CREATE }
}
