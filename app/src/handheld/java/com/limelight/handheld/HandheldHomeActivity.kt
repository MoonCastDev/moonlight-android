package com.limelight.handheld

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Outline
import android.hardware.input.InputManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.format.DateFormat
import android.view.FocusFinder
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.limelight.AppView
import com.limelight.PcView
import com.limelight.R
import com.limelight.binding.PlatformBinding
import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.computers.ComputerManagerListener
import com.limelight.computers.ComputerManagerService
import com.limelight.grid.assets.CachedAppAssetLoader
import com.limelight.grid.assets.DiskAssetLoader
import com.limelight.grid.assets.MemoryAssetLoader
import com.limelight.grid.assets.NetworkAssetLoader
import com.limelight.home.Feel
import com.limelight.home.FeelSounds
import com.limelight.home.FocusGlide
import com.limelight.home.Launch
import com.limelight.home.MoveToTv
import com.limelight.home.PadTestActivity
import com.limelight.home.Ps5Store
import com.limelight.home.Psn
import com.limelight.home.PsnSignInActivity
import com.limelight.home.QuickMenu
import com.limelight.home.Xbox
import com.limelight.nvstream.http.ComputerDetails
import com.limelight.nvstream.http.NvApp
import com.limelight.nvstream.http.NvHTTP
import com.limelight.nvstream.http.PairingManager
import com.limelight.nvstream.wol.WakeOnLanSender
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
 * MoonCast handheld home, in the Switch style of mooncast-handheld-v2.html: one row of big game tiles (what you played
 * last first), a row of round buttons, a hint bar. Upright it becomes a phone layout with a dock. This app is always
 * the sender: games play here, or go to a MoonCast TV ([TvFinder] finds them, [TvSender] sends, and
 * [TvControllerActivity] makes this device the TV's controller). It never receives games from other devices.
 *
 * Moonlight's own pieces do the work, as on the TV: ComputerManagerService finds PCs, ServerHelper starts PC games,
 * Ps5StreamActivity plays a PS5 and XboxStreamActivity an Xbox.
 */
class HandheldHomeActivity : Activity() {
    private enum class Page(val title: String) { HOME("Home"), GAMES("All games"), CONSOLES("Consoles"), SETTINGS("Settings") }

    /** One thing you can play: a PC game, a PS5 or an Xbox. */
    private class Game(val key: String, val title: String, val src: String, val seed: String, val running: Boolean,
                       val pc: ComputerDetails? = null, val app: NvApp? = null,
                       val ps: Ps5Store.Console? = null, val xb: Xbox.Console? = null)

    private class Opt(val icon: String?, val iconColor: Int, val title: String, val sub: String? = null, val right: String? = null,
                      val enabled: Boolean = true, val onClick: () -> Unit = {})

    private lateinit var sw: Sw
    private lateinit var root: FrameLayout
    private lateinit var wash: Wash
    private lateinit var main: LinearLayout
    private lateinit var topBar: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var foot: LinearLayout
    private lateinit var overlay: FrameLayout
    private lateinit var glide: FocusGlide
    private var clock: TextView? = null
    private var battText: TextView? = null
    private var battGlyph: Glyph? = null
    private var padName: TextView? = null
    private var portrait = false
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

    private var consoles = listOf<Ps5Store.Console>()
    private val psStatus = HashMap<String, Ps5Store.Status>()
    private val probes = Executors.newSingleThreadExecutor()
    private var xboxes: List<Xbox.Console> = emptyList()

    private var page = Page.HOME
    private var lastPage: Page? = null
    private var gamesTab = "all"
    private var sortAz = false
    private var settingsGroup = "handheld"
    private var selKey: String? = null           // the selected game (focused, or tapped upright)
    private var lastSig = ""
    private var overlayKind: String? = null
    private var stagger = ArrayList<View>()
    private val rbLabels = ArrayList<Pair<View, TextView>>()
    private var gname: TextView? = null
    private var gnameBox: View? = null
    private var gnameTarget: View? = null
    private var hero: FrameLayout? = null
    private var heroName: TextView? = null

    private val prefs: SharedPreferences by lazy { getSharedPreferences("mc_tv", Context.MODE_PRIVATE) }
    private val stream: SharedPreferences by lazy { android.preference.PreferenceManager.getDefaultSharedPreferences(this) }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        applyRotation()
        // a handheld streams at its own screen size unless you chose one (Moonlight's default is 720p);
        // a resolution saved earlier, in either of Moonlight's keys, is left alone
        if (!stream.contains("list_resolution") && !stream.contains("list_resolution_fps"))
            stream.edit().putString("list_resolution", nativeResolution()).apply()
        MoveToTv.handler = TvSender   // the players' Quick menu offers "Move to <TV>" through this
        buildShell()
        bindService(Intent(this, ComputerManagerService::class.java), conn, Context.BIND_AUTO_CREATE)
        takeIntent(intent)
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(pads, ui)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        takeIntent(i)
    }

    /** Controller mode's "Play here instead": start that game here once it's known (PCs may still be loading). */
    private var pendingPlay: String? = null
    private fun takeIntent(i: Intent?) {
        val k = i?.getStringExtra(EXTRA_PLAY_HERE) ?: return
        i.removeExtra(EXTRA_PLAY_HERE)
        pendingPlay = k
        ui.post { runPending() }
    }

    private var pendingSince = 0L
    private val pendingTimeout = Runnable { runPending(force = true) }

    /**
     * Start the pending game once its PC's state is known: right after resuming, the PC list still holds the state
     * from before the poll (often "offline"), so wait for the first poll since resume, up to 6 s.
     */
    private fun runPending(force: Boolean = false) {
        val k = pendingPlay ?: return
        if (pendingSince == 0L) {
            pendingSince = android.os.SystemClock.elapsedRealtime()
            toast("Starting on this ${deviceShort()}…")
            ui.postDelayed(pendingTimeout, 6000)
        }
        val g = gameFor(k)
        val pc = g?.pc
        if (!force && (g == null || (pc != null && (polledAt[pc.uuid] ?: 0L) < resumedAt))) return   // onComputer tries again
        ui.removeCallbacks(pendingTimeout)
        pendingPlay = null; pendingSince = 0L
        if (g == null) { toast("Couldn't find that game here yet. Try it from the home."); return }
        playHere(g)   // after 6 s without a fresh answer it just tries (only a poll that says offline asks to wake it)
    }
    private val polledAt = HashMap<String, Long>()   // PC uuid -> when its state last came in
    private var resumedAt = 0L

    override fun onResume() {
        super.onResume()
        resumed = true
        resumedAt = android.os.SystemClock.elapsedRealtime()
        TvFinder.onChange = { render() }
        if (System.currentTimeMillis() - TvFinder.lastSweepAt > 90_000) TvFinder.sweep(this) else TvFinder.check(this)
        consoles = Ps5Store.all(this)
        xboxes = Xbox.consoles(this)
        refreshXboxes()
        startPolling()
        ui.post(tick)
        holdMulticast(true)
        render(force = true)
        runPending()
        // back from a stream: put the ring back on the focused item once it's laid out
        ui.postDelayed({ currentFocus?.let { glide.moveTo(it) } }, 120)
    }

    override fun onPause() {
        resumed = false
        TvFinder.onChange = null
        holdMulticast(false)
        stopPolling()
        ui.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        probes.shutdownNow()
        glide.detach()
        try { (getSystemService(Context.INPUT_SERVICE) as InputManager).unregisterInputDeviceListener(pads) } catch (e: Exception) { }
        loaders.values.forEach { it.cancelBackgroundLoads(); it.cancelForegroundLoads(); it.freeCacheMemory() }
        try { unbindService(conn) } catch (e: Exception) { }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        // a turn or any size change: rebuild, everything is laid out in code for the new shape
        overlayKind = null
        glide.detach()
        buildShell()
        render(force = true)
    }

    private fun isPortrait() = resources.displayMetrics.let { it.heightPixels > it.widthPixels }

    private fun applyRotation() {
        requestedOrientation = if (prefs.getBoolean("hh_rotate", true)) ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                               else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val local = service as ComputerManagerService.ComputerManagerBinder
            Thread {
                local.waitForReady()
                AndroidCryptoProvider(this@HandheldHomeActivity).clientCertificate
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

    /**
     * mDNS on Wi-Fi only arrives while someone holds a multicast lock (Android 13 and older; 14+ uses NsdManager).
     * Moonlight's discovery agent takes one too; holding ours while the home is open covers phones that drop it.
     */
    private var multicast: android.net.wifi.WifiManager.MulticastLock? = null
    private fun holdMulticast(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 34) return
        try {
            if (on) {
                if (multicast == null) multicast = (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                        .createMulticastLock("MoonCast home mDNS").apply { setReferenceCounted(false) }
                multicast?.acquire()
            } else multicast?.release()
        } catch (e: Exception) { }
    }

    /** Restart Moonlight's discovery and polling, so a PC that just came up is found without leaving the screen. */
    private fun lookAgain() {
        stopPolling()
        startPolling()
    }
    private var lastKick = 0L
    private var tvCheckAt = 0L

    private fun stopPolling() {
        appPollers.values.forEach { it.stop() }
        appPollers.clear()
        if (polling) binder?.stopPolling()
        polling = false
    }

    private val pads = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) { padName?.text = controllerName() }
        override fun onInputDeviceRemoved(id: Int) { padName?.text = controllerName() }
        override fun onInputDeviceChanged(id: Int) { padName?.text = controllerName() }
    }

    /** Clock, battery and PS5 probes while the home is on screen. */
    private val tick = object : Runnable {
        override fun run() {
            updateStatus()
            probeConsoles()
            // MoonCast TVs: the known ones every half minute, a full sweep every two minutes (a couple of seconds' work)
            val now = System.currentTimeMillis()
            if (now - TvFinder.lastSweepAt > 120_000) TvFinder.sweep(this@HandheldHomeActivity)
            else if (now - tvCheckAt > 30_000) { tvCheckAt = now; TvFinder.check(this@HandheldHomeActivity) }
            // no PC yet: give discovery a fresh start every half minute
            if (pcs.isEmpty() && android.os.SystemClock.elapsedRealtime() - lastKick > 30_000) {
                lastKick = android.os.SystemClock.elapsedRealtime(); if (polling) lookAgain() }
            ui.postDelayed(this, 10_000)
        }
    }

    private fun updateStatus() {
        clock?.text = DateFormat.getTimeFormat(this).format(Date())
        val bat = try { registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (e: Exception) { null }
        if (bat != null) {
            val lv = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (lv >= 0 && sc > 0) {
                val pct = lv * 100 / sc
                val plugged = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                battText?.text = if (plugged) "$pct% ⚡" else "$pct%"
                battGlyph?.let { it.level = pct; it.invalidate() }
            }
        }
    }

    // ---------------------------------------------------------------- data

    private fun onComputer(d: ComputerDetails) {
        val uuid = d.uuid ?: return
        pcs[uuid] = d
        // UNKNOWN = the service timed out its old poll data and hasn't asked the PC again yet
        if (d.state != ComputerDetails.State.UNKNOWN) polledAt[uuid] = android.os.SystemClock.elapsedRealtime()
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
        if (online(d) && paired(d) && !appPollers.containsKey(uuid)) {
            binder?.let { b -> appPollers[uuid] = b.createAppListPoller(b.getComputer(uuid) ?: d).also { it.start() } }
        }
        render()
        runPending()
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

    private fun refreshXboxes(loud: Boolean = false) {
        if (!Xbox.signedIn(this)) return
        Thread {
            val r = try { Xbox.refreshConsoles(this) } catch (e: Exception) { null }
            runOnUiThread {
                if (r != null) { xboxes = r; render() }
                if (loud) toast(when { r == null -> "Couldn't reach Xbox network"; r.isEmpty() -> "No Xbox on this account"
                    else -> "Found " + r.joinToString { it.name } })
            }
        }.start()
    }

    private fun paired(d: ComputerDetails) = d.pairState == PairingManager.PairState.PAIRED
    private fun online(d: ComputerDetails) = d.state == ComputerDetails.State.ONLINE
    private fun runningApp(d: ComputerDetails): NvApp? {
        if (d.runningGameId == 0) return null
        return apps[d.uuid]?.firstOrNull { it.appId == d.runningGameId } ?: NvApp("Your game", d.runningGameId, false)
    }
    private fun power(c: Ps5Store.Console) = psStatus[c.host]?.power ?: Ps5Store.Power.CHECKING

    private fun loader(d: ComputerDetails): CachedAppAssetLoader = loaders.getOrPut(d.uuid) {
        CachedAppAssetLoader(d, (300.0 / sw.px(380).coerceAtLeast(1)).coerceAtLeast(1.0),
                NetworkAssetLoader(this, binder?.uniqueId ?: ""), MemoryAssetLoader(), DiskAssetLoader(this),
                android.graphics.BitmapFactory.decodeResource(resources, R.drawable.no_app_image))
    }

    private fun hiddenIds(pc: ComputerDetails): Set<Int> =
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE).getStringSet(pc.uuid, emptySet())!!
                .mapNotNull { it.toIntOrNull() }.toSet()

    /** Every game: each PS5 and Xbox (one tile per console) and every PC game that isn't hidden. */
    private fun allGames(): List<Game> {
        val out = ArrayList<Game>()
        consoles.forEach { c ->
            val st = psStatus[c.host]
            out += Game("ps:${c.host}", st?.runningApp ?: c.name, "PS5", "ps", st?.power == Ps5Store.Power.ON && st.runningApp != null, ps = c)
        }
        xboxes.forEach { c -> out += Game("xb:${c.id}", c.name, "Xbox", "xb", false, xb = c) }
        pcs.values.filter { paired(it) }.forEach { pc ->
            val hidden = hiddenIds(pc)
            (apps[pc.uuid] ?: emptyList()).filter { it.appId !in hidden }.forEach { a ->
                out += Game("pc:${pc.uuid}:${a.appId}", a.appName, "PC", a.appName, a.appId == pc.runningGameId, pc = pc, app = a)
            }
        }
        return out
    }

    /** Home's row: what's running, then what you played last, then the rest; at most ten before "All games". */
    private fun homeGames(): List<Game> {
        val all = allGames()
        val byKey = all.associateBy { it.key }
        val recent = Recents.list(this).mapNotNull { byKey[it] }
        val ordered = LinkedHashMap<String, Game>()
        all.filter { it.running }.forEach { ordered[it.key] = it }
        recent.forEach { ordered[it.key] = it }
        all.filter { it.ps != null || it.xb != null }.forEach { ordered[it.key] = it }
        all.filter { it.pc != null }.sortedBy { it.title.lowercase() }.forEach { ordered[it.key] = it }
        return ordered.values.take(10)
    }

    private fun gameFor(key: String?): Game? = key?.let { k -> allGames().firstOrNull { it.key == k } }

    // ---------------------------------------------------------------- shell

    private fun buildShell() {
        portrait = isPortrait()
        sw = Sw(this, prefs.getString("hh_theme", "dark") == "light", portrait)
        root = FrameLayout(this).apply { setBackgroundColor(sw.bg) }
        wash = Wash(this)
        root.addView(wash, FrameLayout.LayoutParams(-1, -1))
        main = sw.col().apply { clipChildren = false }

        topBar = sw.row()
        val side = if (portrait) 56 else 96
        main.addView(topBar, sw.lp(-1, 96).apply { leftMargin = sw.px(side); rightMargin = sw.px(side); topMargin = sw.px(if (portrait) 56 else 44) })

        content = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        main.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        foot = sw.row()
        if (portrait) {
            foot.setBackgroundColor(sw.panel)
            main.addView(foot, sw.lp(-1, 220))
        } else {
            foot.background = sw.topLine()
            foot.setPadding(sw.px(48), 0, sw.px(48), 0)
            main.addView(foot, sw.lp(-1, 120).apply { leftMargin = sw.px(48); rightMargin = sw.px(48) })
        }

        root.addView(main, FrameLayout.LayoutParams(-1, -1))
        overlay = FrameLayout(this).apply { visibility = View.GONE; isClickable = true }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        // the Switch ring: a background-coloured gap, then cyan, standing off the item, breathing slowly
        glide = FocusGlide(root).apply {
            ghost = false
            strokeColor = sw.ring
            strokeWidth = sw.pxf(7)
            gapColor = sw.bg
            gapWidth = sw.pxf(6)
            outset = sw.pxf(13)
            cornerRadius = sw.pxf(19)
            duration = 200
            radiusFor = { v ->
                val t = v.tag as? String
                if (t != null && (t.startsWith("rb:") || t.startsWith("pill:") || t.startsWith("tab:") || t.startsWith("dock:"))) 9999f else null
            }
            accept = { v -> v !is EditText }
            breathe = true
        }.attach()
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, n -> onFocus(n) }
        root.viewTreeObserver.addOnScrollChangedListener { placeName() }
        snack = null
        FeelSounds.warm(this)
        lastPage = null
        lastSig = ""
    }

    private fun go(p: Page) {
        if (p == page && lastPage == p) return
        page = p
        render(force = true)
    }

    private fun signature(): String = buildString {
        append(page).append(gamesTab).append(sortAz).append(settingsGroup).append(portrait)
        pcs.values.forEach { append(it.uuid).append(it.state).append(it.pairState).append(it.runningGameId).append(apps[it.uuid]?.size) }
        consoles.forEach { append(it.host).append(it.name).append(psStatus[it.host]?.power).append(psStatus[it.host]?.runningApp) }
        xboxes.forEach { append(it.id).append(it.name).append(it.powerState) }
        if (portrait && page == Page.HOME) append(selKey)
        append(TvFinder.target(this@HandheldHomeActivity)?.let { it.id + it.name + TvFinder.isHere(it) })
    }

    /** Rebuild the page when something visible changed, keeping focus on the same item. */
    private fun render(force: Boolean = false) {
        val sig = signature()
        if (!force && sig == lastSig) return
        lastSig = sig
        val entering = lastPage != page
        lastPage = page
        val focusTag = currentFocus?.tag as? String
        stagger = ArrayList()
        rbLabels.clear()
        gname = null; gnameBox = null; gnameTarget = null; hero = null; heroName = null
        buildTop()
        buildFoot()
        content.removeAllViews()
        primaryView = null
        val pg = when (page) {
            Page.HOME -> if (portrait) homePortrait() else homeLandscape()
            Page.GAMES -> gamesPage()
            Page.CONSOLES -> consolesPage()
            Page.SETTINGS -> settingsPage()
        }
        content.addView(pg, FrameLayout.LayoutParams(-1, -1))
        // what a pad's first press lands on when the screen was last touched (Android restores the default focus)
        if (Build.VERSION.SDK_INT >= 26) primaryView?.isFocusedByDefault = true
        if (page == Page.HOME) wash.color = gameFor(selKey)?.let { sw.tint(it.seed) } ?: (homeGames().firstOrNull()?.let { sw.tint(it.seed) } ?: 0)
        if (entering) entrance()
        if (overlayKind != null) return
        when {
            !entering && focusTag != null && content.findViewWithTag<View>(focusTag)?.requestFocus() == true -> {}
            else -> (primaryView ?: firstFocusable(content))?.requestFocus()
        }
        if (entering && stagger.isNotEmpty()) {
            // the ring waits for the screen to build in, then lands on the focused item
            glide.hide()
            ui.postDelayed({ currentFocus?.let { glide.moveTo(it) } }, stagger.size * 55L + 360)
        }
        ui.post { placeName(); syncLabels(currentFocus) }
    }

    private fun firstFocusable(v: View): View? {
        if (v.isFocusable && v.isClickable && v.visibility == View.VISIBLE) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) firstFocusable(v.getChildAt(i))?.let { return it }
        return null
    }

    /** Screens build in: each part rises and fades in, 55 ms after the one before (the feel mockup's entrance). */
    private fun entrance() {
        val reduced = try { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (e: Exception) { false }
        stagger.forEachIndexed { i, v ->
            if (reduced) return@forEachIndexed
            v.alpha = 0f; v.translationY = sw.pxf(22)
            v.animate().alpha(1f).translationY(0f).setStartDelay(i * 55L).setDuration(420)
                    .setInterpolator(android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)).start()
        }
    }

    private fun <T : View> part(v: T): T { stagger.add(v); return v }

    // ---- top bar and foot ----

    private fun buildTop() {
        topBar.removeAllViews()
        if (page == Page.HOME) {
            topBar.addView(avatar(), sw.lp(84, 84).apply { rightMargin = sw.px(20) })
            topBar.addView(sw.row().apply {
                background = sw.round(sw.panel, 32)
                setPadding(sw.px(18), 0, sw.px(26), 0)
                addView(View(this@HandheldHomeActivity).apply { background = sw.round(0, 5, sw.ink, 3) }, sw.lp(30, 20).apply { rightMargin = sw.px(12) })
                val tv = TvFinder.target(this@HandheldHomeActivity)
                addView(sw.text(when {
                    tv != null -> "Playing on ${tv.name}" + if (TvFinder.isHere(tv)) "" else " (not found)"
                    portrait -> "This ${deviceShort()}"
                    else -> "Playing on this ${deviceShort()}"
                }, 24, sw.ink, 700))
                setOnClickListener { playWhere(null) }
                isFocusable = false   // a tap target only; the pad starts on the games
                isSoundEffectsEnabled = !FeelSounds.enabled(this@HandheldHomeActivity)
            }, sw.lp(-2, 64))
        } else {
            topBar.addView(sw.text(page.title, 44, sw.ink, 800))
        }
        topBar.addView(sw.grow())
        clock = sw.text("", 30, sw.ink, 700)
        topBar.addView(clock)
        battGlyph = Glyph(this, "battery", sw.ink)
        topBar.addView(battGlyph, sw.lp(58, 28).apply { leftMargin = sw.px(26); rightMargin = sw.px(10) })
        battText = sw.text("", 30, sw.ink, 700)
        topBar.addView(battText)
        part(topBar)
        updateStatus()
    }

    private fun avatar(): View {
        val name = Psn.onlineId(this)?.takeIf { it.isNotBlank() } ?: Xbox.gamertag(this)?.takeIf { it.isNotBlank() } ?: deviceName()
        return sw.text(name.trim().take(1).uppercase(), 38, 0xFFFFFFFF.toInt(), 800).apply {
            gravity = Gravity.CENTER
            background = sw.round(0xFFF08A3C.toInt(), 42, sw.panel, 4)
        }
    }

    private fun buildFoot() {
        foot.removeAllViews()
        padName = null
        if (portrait) {
            foot.gravity = Gravity.TOP
            foot.setPadding(0, sw.px(36), 0, 0)
            listOf(Triple(Page.HOME, "home", Sw.C_CYAN), Triple(Page.GAMES, "grid", Sw.C_BLUE),
                    Triple(Page.CONSOLES, "consoles", Sw.C_AMBER), Triple(Page.SETTINGS, "gear", sw.sub)).forEach { (p, icon, color) ->
                val on = p == page
                foot.addView(sw.col().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(roundButton("dock:${p.name}", icon, color, 104, sw.bg) { FeelSounds.play(this@HandheldHomeActivity, FeelSounds.Cue.TAB); go(p) }, sw.lp(104, 104))
                    addView(sw.text(if (p == Page.GAMES) "Games" else p.title, 24, if (on) sw.ring else sw.sub, 700), sw.lp(-2, -2).apply { topMargin = sw.px(14) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            return
        }
        foot.gravity = Gravity.CENTER_VERTICAL
        foot.addView(Glyph(this, "pad", sw.sub), sw.lp(74, 38).apply { rightMargin = sw.px(14) })
        padName = sw.text(controllerName(), 28, sw.sub, 700)
        foot.addView(padName)
        foot.addView(sw.grow())
        val h = when (page) {
            Page.HOME -> listOf("Y" to (if (TvFinder.target(this) != null) "Play here" else "Play on TV"), "A" to "Start")
            Page.GAMES -> listOf("L R" to "Tabs", "X" to (if (sortAz) "Recent first" else "A to Z"), "B" to "Back", "A" to "Start")
            Page.CONSOLES -> listOf("B" to "Back", "A" to "Open")
            Page.SETTINGS -> listOf("B" to "Back", "A" to "OK")
        }
        h.forEach { (k, l) ->
            foot.addView(if (k.length > 1) sw.row().apply {
                addView(sw.text(k, 22, sw.bg, 800).apply { gravity = Gravity.CENTER; background = sw.round(sw.ink, 23); setPadding(sw.px(14), 0, sw.px(14), 0) }, sw.lp(-2, 46).apply { rightMargin = sw.px(14) })
                addView(sw.text(l, 28, sw.ink, 700))
            } else sw.hint(k, l), sw.lp(-2, -2).apply { leftMargin = sw.px(40) })
        }
        part(foot)
    }

    // ---------------------------------------------------------------- Home

    private fun homeLandscape(): View {
        val games = homeGames()
        val col = sw.col().apply { clipChildren = false }
        val box = FrameLayout(this).apply { clipChildren = false }
        gname = sw.text("", 34, sw.ring, 700)
        box.addView(gname, FrameLayout.LayoutParams(-2, -2))
        gnameBox = box
        col.addView(part(box), sw.lp(-1, 48).apply { topMargin = sw.px(96); leftMargin = sw.px(96) })

        val row = sw.row().apply { clipChildren = false; setPadding(sw.px(96), 0, sw.px(96), 0) }
        val tiles = ArrayList<View>()
        if (games.isEmpty()) tiles += tileView(null, 380, 36, "g:add", "Add a console") { go(Page.CONSOLES) }
        games.forEach { g -> tiles += tileView(g, 380, 36, "g:${g.key}", g.title) { play(g) } }
        tiles += tileView(null, 380, 36, "g:all", "All games") { gamesTab = "all"; go(Page.GAMES) }
        tiles.forEachIndexed { i, t -> row.addView(t, sw.lp(380, 380).apply { if (i > 0) leftMargin = sw.px(24) }) }
        val scroll = RowScroll(this, sw.px(96)).apply {
            isHorizontalScrollBarEnabled = false; isFocusable = false; clipChildren = false; clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        }
        col.addView(part(scroll), sw.lp(-1, 380).apply { topMargin = sw.px(16) })
        // the tile you were on (or the first) is where focus starts
        primaryView = tiles.firstOrNull { it.tag == "g:$selKey" } ?: tiles.first()

        val rounds = sw.row(Gravity.TOP).apply { gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
        listOf(Triple("Consoles", "consoles", Sw.C_CYAN), Triple("Play on a TV", "tv", Sw.C_AMBER), Triple("Controllers", "pad", Sw.C_BLUE),
                Triple("Network test", "net", Sw.C_GREEN), Triple("Settings", "gear", sw.sub), Triple("Exit", "power", Sw.C_RED)).forEachIndexed { i, (label, icon, color) ->
            val btn = roundButton("rb:$icon", icon, color, 112, sw.panel) { roundAction(icon) }
            val lab = sw.text(label, 26, sw.ring, 700).apply { gravity = Gravity.CENTER_HORIZONTAL; visibility = View.INVISIBLE }
            rbLabels += btn to lab
            rounds.addView(FrameLayout(this).apply {
                clipChildren = false
                addView(btn, sw.flp(112, 112))
                addView(lab, FrameLayout.LayoutParams(sw.px(420), -2, Gravity.CENTER_HORIZONTAL).apply { topMargin = sw.px(132) })
            }, sw.lp(112, 180).apply { if (i > 0) leftMargin = sw.px(40) })
        }
        col.addView(part(rounds), sw.lp(-1, 180).apply { topMargin = sw.px(80) })
        return col
    }

    /** Where focus lands when a page opens (each page sets it while it builds). */
    private var primaryView: View? = null

    private fun homePortrait(): View {
        val games = homeGames()
        if (selKey == null || games.none { it.key == selKey }) selKey = games.firstOrNull()?.key
        val sel = games.firstOrNull { it.key == selKey }
        val col = sw.col().apply { setPadding(sw.px(56), 0, sw.px(56), sw.px(40)); clipChildren = false }
        heroName = sw.text(sel?.title ?: "Add a console", 44, sw.ring, 700)
        col.addView(part(heroName!!), sw.lp(-1, -2).apply { topMargin = sw.px(44) })
        val heroTile = if (sel != null) tileView(sel, 968, 60, "hero", sel.title) { play(sel) }
                       else tileView(null, 968, 60, "hero", "Add a console") { go(Page.CONSOLES) }
        hero = heroTile
        col.addView(part(heroTile), sw.lp(-1, 560).apply { topMargin = sw.px(26) })
        col.addView(part(sw.row().apply {
            addView(pill("pill:here", "Play here", true) { gameFor(selKey)?.let { playHere(it) } ?: go(Page.CONSOLES) }, LinearLayout.LayoutParams(0, sw.px(112), 1f))
            addView(pill("pill:tv", "Play on TV", false) { playWhere(gameFor(selKey)) }, LinearLayout.LayoutParams(0, sw.px(112), 1f).apply { leftMargin = sw.px(20) })
        }), sw.lp(-1, 112).apply { topMargin = sw.px(40) })
        col.addView(part(sw.text("Recent", 34, sw.ink, 800)), sw.lp(-2, -2).apply { topMargin = sw.px(56) })

        val dw = sw.designWidth
        val size = ((dw - 112 - 44) / 3f).toInt()
        val grid = GridLayout(this).apply { columnCount = 3; clipChildren = false }
        val list = games.take(8).map { g ->
            tileView(g, size, 28, "g:${g.key}", g.title) {
                // a tap picks it for the big tile; A on a pad starts it
                if (it.isInTouchMode) { selKey = g.key; FeelSounds.play(this, FeelSounds.Cue.SELECT); render(force = true); (content.getChildAt(0) as? ViewGroup)?.scrollTo(0, 0) }
                else play(g)
            }
        } + tileView(null, size, 28, "g:all", "All games") { gamesTab = "all"; go(Page.GAMES) }
        list.forEachIndexed { i, t ->
            grid.addView(t, GridLayout.LayoutParams().apply {
                width = sw.px(size); height = sw.px(size)
                if (i % 3 != 0) leftMargin = sw.px(22)
                topMargin = sw.px(22)
            })
        }
        col.addView(part(grid), sw.lp(-2, -2))
        primaryView = heroTile
        return ColScroll(this, sw.px(40)).apply {
            isVerticalScrollBarEnabled = false; isFocusable = false; clipToPadding = false; clipChildren = false
            addView(col)
        }
    }

    private fun roundAction(icon: String) {
        when (icon) {
            "consoles" -> go(Page.CONSOLES)
            "tv" -> playWhere(null)
            "pad" -> controllers()
            "net" -> ServerHelper.doNetworkTest(this)
            "gear" -> go(Page.SETTINGS)
            "power" -> { FeelSounds.play(this, FeelSounds.Cue.BACK); PadLink.stop(); finishAndRemoveTask() }
        }
    }

    private fun roundButton(tag: String, icon: String, color: Int, size: Int, fill: Int, onClick: () -> Unit): View = FrameLayout(this).apply {
        this.tag = tag
        background = sw.round(fill, size / 2)
        addView(Icon(this@HandheldHomeActivity, icon, color), sw.flp(size * 54 / 112, size * 54 / 112, Gravity.CENTER))
        sw.focusable(this)
        setOnClickListener { onClick() }
    }

    private fun pill(tag: String, label: String, main: Boolean, onClick: () -> Unit): View = sw.text(label, 32, if (main) sw.bg else sw.ink, 800).apply {
        this.tag = tag
        gravity = Gravity.CENTER
        background = sw.round(if (main) sw.ink else sw.panel, 56)
        sw.focusable(this)
        setOnClickListener { onClick() }
    }

    /** A square game tile: art (box art for PC games), its source, Running, and the name at the bottom. */
    private fun tileView(g: Game?, size: Int, titleSize: Int, tag: String, label: String, onClick: (View) -> Unit): FrameLayout {
        val f = FrameLayout(this)
        f.tag = tag
        f.setTag(R.id.hh_title, label)
        f.setTag(R.id.hh_tint, if (g != null) sw.tint(g.seed) else 0)
        f.clipToOutline = true
        f.outlineProvider = rounded(6)
        if (g == null) {
            f.background = if (tag == "g:all") sw.allArt() else sw.art("add")
            if (tag != "g:all") f.addView(sw.text("+", size * 0.3f, 0xFFFFFFFF.toInt(), 600).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        } else {
            f.background = sw.art(g.seed)
            val k = size / 380f
            val title = sw.text(g.title, titleSize, 0xFFFFFFFF.toInt(), 800, 3).apply {
                gravity = Gravity.BOTTOM
                setPadding(sw.px(26 * k.coerceAtMost(1.5f)), 0, sw.px(26 * k.coerceAtMost(1.5f)), sw.px(24 * k.coerceAtMost(1.5f)))
                setShadowLayer(sw.pxf(10), 0f, sw.pxf(2), 0x59000000)
            }
            if (g.pc != null && g.app != null) {
                val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                f.addView(img, FrameLayout.LayoutParams(-1, -1))
                f.addView(title, FrameLayout.LayoutParams(-1, -1))
                loader(g.pc).populateImageView(g.app, img, title)
            } else f.addView(title, FrameLayout.LayoutParams(-1, -1))
            val bs = if (size >= 500) 24 else if (size >= 360) 20 else 17
            val m = if (size >= 360) 18 else 12
            f.addView(sw.badge(g.src, bs), sw.flp(-2, bs * 2).apply { leftMargin = sw.px(m); topMargin = sw.px(m) })
            if (g.running) f.addView(sw.live(bs), sw.flp(-2, bs * 2, Gravity.END).apply { rightMargin = sw.px(m); topMargin = sw.px(m) })
            if (g.pc != null && g.app != null) {
                f.setOnLongClickListener { gameMenu(g); true }
                f.setOnKeyListener { _, code, e ->
                    if (code == KeyEvent.KEYCODE_BUTTON_X && page == Page.HOME) { if (e.action == KeyEvent.ACTION_UP) gameMenu(g); true } else false
                }
            }
        }
        sw.focusable(f)
        f.setOnClickListener { onClick(it) }
        return f
    }

    /** Focus moved: the name above the row, the round button's label, the colour wash and the selected game follow. */
    private fun onFocus(v: View?) {
        syncLabels(v)
        val t = v?.tag as? String
        // the name above the row belongs to a game tile: hide it while focus is anywhere else (the round buttons)
        gnameBox?.let { box ->
            val show = t != null && t.startsWith("g:") || v == null || v.isInTouchMode
            box.animate().cancel(); box.animate().alpha(if (show) 1f else 0f).setDuration(150).start()
        }
        if (t == null) return
        if (t.startsWith("g:") || t == "hero") {
            if (t.startsWith("g:") && t != "g:all" && t != "g:add") selKey = t.removePrefix("g:")
            (v.getTag(R.id.hh_tint) as? Int)?.let { if (it != 0) wash.color = it }
            if (gname != null && t.startsWith("g:")) {
                gnameTarget = v
                fadeSwap(gname!!, v.getTag(R.id.hh_title) as? String ?: "")
                placeName()
            }
            if (portrait && page == Page.HOME && t.startsWith("g:") && t != "g:all") updateHero()
        }
    }

    private fun syncLabels(v: View?) {
        rbLabels.forEach { (b, l) -> l.visibility = if (b === v && !b.isInTouchMode) View.VISIBLE else View.INVISIBLE }
        if (gname != null && gnameTarget == null) {
            // nothing focused yet (touch): name the first tile
            val first = (content.findViewWithTag<View>("g:$selKey") ?: primaryView)
            if (first != null && first.tag.toString().startsWith("g:")) { gnameTarget = first; gname?.text = first.getTag(R.id.hh_title) as? String ?: "" }
            placeName()
        }
    }

    /** Upright, moving over the Recent grid puts that game in the big tile without rebuilding the page. */
    private fun updateHero() {
        val g = gameFor(selKey) ?: return
        heroName?.let { fadeSwap(it, g.title) }
        val h = hero ?: return
        h.background = sw.art(g.seed)
        h.setTag(R.id.hh_title, g.title)
        h.setTag(R.id.hh_tint, sw.tint(g.seed))
        h.removeAllViews()
        val title = sw.text(g.title, 60, 0xFFFFFFFF.toInt(), 800, 3).apply {
            gravity = Gravity.BOTTOM; setPadding(sw.px(40), 0, sw.px(40), sw.px(36)); setShadowLayer(sw.pxf(10), 0f, sw.pxf(2), 0x59000000)
        }
        if (g.pc != null && g.app != null) {
            val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            h.addView(img, FrameLayout.LayoutParams(-1, -1)); h.addView(title, FrameLayout.LayoutParams(-1, -1))
            loader(g.pc).populateImageView(g.app, img, title)
        } else h.addView(title, FrameLayout.LayoutParams(-1, -1))
        h.addView(sw.badge(g.src, 24), sw.flp(-2, 48).apply { leftMargin = sw.px(18); topMargin = sw.px(18) })
        if (g.running) h.addView(sw.live(24), sw.flp(-2, 48, Gravity.END).apply { rightMargin = sw.px(18); topMargin = sw.px(18) })
        h.setOnClickListener { play(g) }
    }

    private fun placeName() {
        val n = gname ?: return
        val t = gnameTarget ?: return
        val box = gnameBox ?: return
        if (!t.isAttachedToWindow) return
        val a = IntArray(2); val b = IntArray(2)
        t.getLocationInWindow(a); box.getLocationInWindow(b)
        val x = (a[0] - b[0]).toFloat() + sw.pxf(6)
        // keep the name on screen when the tile is near the right edge
        val maxX = (resources.displayMetrics.widthPixels - b[0] - n.width - sw.px(48)).toFloat()
        n.translationX = x.coerceAtMost(maxX.coerceAtLeast(0f)).coerceAtLeast(0f)
    }

    private fun fadeSwap(v: TextView, s: String) {
        if (v.text.toString() == s) return
        v.animate().cancel()
        if (v.text.isEmpty()) { v.text = s; v.alpha = 1f; v.translationY = 0f; return }
        v.animate().alpha(0f).translationY(-sw.pxf(10)).setDuration(110).withEndAction {
            v.text = s; v.translationY = sw.pxf(12)
            ui.post { placeName() }
            v.animate().alpha(1f).translationY(0f).setStartDelay(40).setDuration(240).start()
        }.start()
    }

    // ---------------------------------------------------------------- All games

    private fun gamesPage(): View {
        val col = sw.col().apply { setPadding(sw.px(if (portrait) 56 else 96), 0, sw.px(if (portrait) 56 else 96), 0); clipChildren = false }
        val tabs = sw.row()
        listOf("all" to "All", "pc" to "PC", "ps" to "PlayStation", "xb" to "Xbox").forEachIndexed { i, (k, label) ->
            val on = k == gamesTab
            tabs.addView(sw.text(label, 26, if (on) sw.bg else sw.sub, 700).apply {
                tag = "tab:$k"
                gravity = Gravity.CENTER
                setPadding(sw.px(30), 0, sw.px(30), 0)
                background = sw.round(if (on) sw.ink else sw.panel, 32)
                sw.focusable(this)
                setOnClickListener { if (gamesTab != k) { gamesTab = k; FeelSounds.play(this@HandheldHomeActivity, FeelSounds.Cue.TAB); render(force = true) } }
            }, sw.lp(-2, 64).apply { if (i > 0) leftMargin = sw.px(14) })
        }
        col.addView(part(tabs), sw.lp(-2, 64).apply { topMargin = sw.px(20) })

        val recent = Recents.list(this)
        var list = allGames().filter { g -> when (gamesTab) { "pc" -> g.pc != null; "ps" -> g.ps != null; "xb" -> g.xb != null; else -> true } }
        list = if (sortAz) list.sortedBy { it.title.lowercase() }
               else list.sortedWith(compareByDescending<Game> { it.running }.thenBy { recent.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                       .thenBy { if (it.pc != null) 1 else 0 }.thenBy { it.title.lowercase() })
        if (list.isEmpty()) {
            val what = when (gamesTab) { "pc" -> "No PC games yet"; "ps" -> "No PlayStation yet"; "xb" -> "No Xbox yet"; else -> "No games yet" }
            col.addView(part(sw.text(what, 40, sw.ink, 800)), sw.lp(-2, -2).apply { topMargin = sw.px(80) })
            col.addView(part(sw.text(if (pcs.isEmpty() && consoles.isEmpty() && xboxes.isEmpty()) "Pair a PC, register a PS5 or sign in to Xbox in Consoles."
                                     else "Still loading, or nothing here. Consoles shows what's connected.", 26, sw.sub, 600, 2)),
                    sw.lp(-1, -2).apply { topMargin = sw.px(14) })
            col.addView(part(pill("pill:consoles", "Go to Consoles", true) { go(Page.CONSOLES) }.apply { primaryView = this }), sw.lp(480, 112).apply { topMargin = sw.px(40) })
            return col
        }
        val cols = if (portrait) 3 else 6
        val gap = if (portrait) 22 else 24
        val size = ((sw.designWidth - 2 * (if (portrait) 56 else 96) - (cols - 1) * gap) / cols).toInt()
        val grid = GridLayout(this).apply { columnCount = cols; clipChildren = false }
        list.forEachIndexed { i, g ->
            grid.addView(tileView(g, size, if (portrait) 28 else 26, "g:${g.key}", g.title) { play(g) }, GridLayout.LayoutParams().apply {
                width = sw.px(size); height = sw.px(size)
                if (i % cols != 0) leftMargin = sw.px(gap)
                bottomMargin = sw.px(gap)
            })
        }
        primaryView = grid.getChildAt(0)
        col.addView(part(ColScroll(this, sw.px(40)).apply {
            isVerticalScrollBarEnabled = false; isFocusable = false; clipToPadding = false; clipChildren = false
            setPadding(0, sw.px(26), 0, sw.px(26))
            addView(grid)
        }), LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = sw.px(20) })
        return col
    }

    // ---------------------------------------------------------------- Consoles

    private fun consolesPage(): View {
        val list = sw.col()
        consoles.forEach { c ->
            val pw = power(c)
            listRow(list, "con:ps:${c.host}", "PS", Sw.PS, c.name, when (pw) {
                Ps5Store.Power.ON -> "On" + (psStatus[c.host]?.runningApp?.let { ", playing $it" } ?: "")
                Ps5Store.Power.REST -> "Rest mode. Play wakes it in about a minute"
                Ps5Store.Power.NOT_FOUND -> if (Psn.signedIn(this)) "Away. Plays through PlayStation Network" else "Not on this network"
                Ps5Store.Power.CHECKING -> "Checking…"
            }, if (pw == Ps5Store.Power.ON) "On" else null) { psMenu(c) }
        }
        xboxes.forEach { c ->
            val on = c.powerState.equals("On", true)
            listRow(list, "con:xb:${c.id}", "XB", Sw.XB, c.name, if (on) "On" else "Wakes when you play", if (on) "On" else null) { xbMenu(c) }
        }
        pcs.values.forEach { d ->
            val n = apps[d.uuid]?.size ?: 0
            listRow(list, "con:pc:${d.uuid}", "PC", Sw.PC, d.name, when {
                !online(d) -> "Offline. Wake it to play"
                !paired(d) -> "Found on your network. Pair it to play"
                d.runningGameId != 0 -> "Playing ${runningApp(d)?.appName}"
                else -> if (n > 0) "$n games" else "Paired"
            }, when { !online(d) -> null; !paired(d) -> "Pair"; else -> "Online" }) { pcMenu(d) }
        }
        if (pcs.isEmpty()) listRow(list, "add:look", "PC", Sw.PC, "Looking for PCs on this network…",
                "PCs running Sunshine show up here by themselves. Select to look again", null) { lookAgain(); toast("Looking for PCs…") }
        groupHeader(list, "Add")
        listRow(list, "add:pc", "+", sw.tile, "Add a PC by its address", "For a PC this device doesn't find on its own", null) { addPcByAddress() }
        listRow(list, "add:ps", "PS", Sw.PS, "Register a PS5", if (Psn.signedIn(this)) "Link it with the code from its Remote Play settings" else "Sign in to PlayStation Network, then link it", null) { registerPs() }
        if (Xbox.signedIn(this)) listRow(list, "add:xb", "XB", Sw.XB, "Find my Xbox", "Signed in" + (Xbox.gamertag(this)?.let { " as $it" } ?: ""), null) { refreshXboxes(true) }
        else listRow(list, "add:xb", "XB", Sw.XB, "Sign in to Xbox", "Play your Xbox here, at home or away", null) { xboxSignIn() }
        listRow(list, "add:moonlight", "ML", sw.tile, "Manage PCs", "Moonlight's PC list: pair, wake, rename, remove", null) {
            startActivity(Intent(this, PcView::class.java)) }
        primaryView = null
        return scrollPane(list)
    }

    private fun scrollPane(list: LinearLayout): View = ColScroll(this, sw.px(40)).apply {
        isVerticalScrollBarEnabled = false; isFocusable = false; clipToPadding = false; clipChildren = false
        val side = if (portrait) 56 else 96
        setPadding(sw.px(side), sw.px(30), sw.px(side), sw.px(30))
        addView(part(list))
    }

    private fun groupHeader(list: LinearLayout, title: String) =
        list.addView(sw.text(title, 26, sw.sub, 800), sw.lp(-2, -2).apply { topMargin = sw.px(40); bottomMargin = sw.px(14); leftMargin = sw.px(26) })

    private fun listRow(list: LinearLayout, tag: String, icon: String, iconColor: Int, title: String, sub: String?, right: String?, onClick: () -> Unit) {
        list.addView(sw.row().apply {
            this.tag = tag
            background = sw.bottomLine()
            setPadding(sw.px(26), sw.px(18), sw.px(26), sw.px(18))
            minimumHeight = sw.px(116)
            addView(sw.text(icon, 22, 0xFFFFFFFF.toInt(), 800).apply { gravity = Gravity.CENTER; background = sw.round(iconColor, 14) },
                    sw.lp(64, 64).apply { rightMargin = sw.px(26) })
            addView(sw.col().apply {
                addView(sw.text(title, 30, sw.ink, 700))
                if (sub != null) addView(sw.text(sub, 23, sw.sub, 600, 2), sw.lp(-2, -2).apply { topMargin = sw.px(8) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (right != null) addView(sw.text(right, 28, sw.ring, 700), sw.lp(-2, -2).apply { leftMargin = sw.px(24) })
            sw.focusable(this)
            setOnClickListener { onClick() }
        }, sw.lp(-1, -2))
    }

    private fun pcMenu(d: ComputerDetails) {
        val run = runningApp(d)
        val opts = ArrayList<Opt>()
        when {
            !online(d) -> opts += Opt("PC", Sw.PC, "Wake it", "Needs Wake-on-LAN on the PC") { wake(d) }
            !paired(d) -> opts += Opt("PC", Sw.PC, "Pair", "Once, with a PIN you type in Sunshine") { pair(d) }
            else -> {
                if (run != null) {
                    opts += Opt("▶", Sw.LIVE, "Resume ${run.appName}") { start(d, run) }
                    opts += Opt("✕", Sw.DANGER, "Quit ${run.appName}", "Closes it on the PC") { quit(d, run) }
                }
                opts += Opt("PC", Sw.PC, "Show its games") { gamesTab = "pc"; go(Page.GAMES) }
                opts += Opt("↯", Sw.C_GREEN, "Test the network") { ServerHelper.doNetworkTest(this) }
                opts += Opt("PC", Sw.PC, "Unpair", "You'll pair it again with a PIN to play") { confirmUnpair(d) }
            }
        }
        opts += Opt("✕", Sw.DANGER, "Remove", "It disappears from this device") { confirmRemovePc(d) }
        dialog(d.name, (d.activeAddress?.address ?: d.localAddress?.address)?.let { "At $it" }, opts, listOf("Close" to {}))
    }

    private fun psMenu(c: Ps5Store.Console) {
        val pw = power(c)
        dialog(c.name, "PlayStation at ${c.host}", listOf(
                Opt("PS", Sw.PS, if (pw == Ps5Store.Power.ON) "Play" else "Wake and play", "On this ${deviceShort()}") { playPs(c) },
                Opt("PS", Sw.PS, "Wake only") { Thread { val ok = Ps5Store.wake(c)
                    runOnUiThread { toast(if (ok) "Waking ${c.name}. It takes about a minute." else "Couldn't wake ${c.name}. Register it again.") } }.start() },
                Opt("✕", Sw.DANGER, "Remove", "Its registration here is deleted") {
                    confirm("Remove ${c.name}?", "To add it back, register it again with a code from the PS5.", "Remove") {
                        Ps5Store.remove(this, c.host); consoles = Ps5Store.all(this); psStatus.remove(c.host); Recents.forget(this, "ps:${c.host}"); render(force = true)
                    } }), listOf("Close" to {}))
    }

    private fun xbMenu(c: Xbox.Console) {
        val on = c.powerState.equals("On", true)
        dialog(c.name, c.type.ifBlank { "Xbox" }, listOf(
                Opt("XB", Sw.XB, if (on) "Play" else "Wake and play", "On this ${deviceShort()}, through Xbox network") { playXbox(c) },
                Opt("XB", Sw.XB, "Wake only") { Thread { val ok = try { Xbox.wake(this, c.id); true } catch (e: Exception) { false }
                    runOnUiThread { toast(if (ok) "Waking ${c.name}" else "Couldn't wake ${c.name}") } }.start() }), listOf("Close" to {}))
    }

    private fun gameMenu(g: Game) {
        val pc = g.pc ?: return; val app = g.app ?: return
        val running = app.appId == pc.runningGameId
        dialog(app.appName, "On ${pc.name}" + (if (running) ". Running now." else "."), listOfNotNull(
                Opt("▶", Sw.LIVE, if (running) "Resume here" else "Play here") { playHere(g) },
                Opt("TV", sw.ring, "Play on a TV") { playWhere(g) },
                if (running) Opt("✕", Sw.DANGER, "Quit game", "Closes it on the PC") { quit(pc, app) } else null,
                Opt("–", Sw.PC, "Hide from All games", "Show it again from Moonlight's app list") {
                    val p = getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                    val set = HashSet(p.getStringSet(pc.uuid, emptySet())!!); set.add(app.appId.toString())
                    p.edit().putStringSet(pc.uuid, set).apply(); render(force = true); toast("${app.appName} hidden")
                }), listOf("Close" to {}))
    }

    // ---------------------------------------------------------------- Settings

    private val groups = listOf("handheld" to "Handheld", "picture" to "Picture", "sound" to "Sound", "pads" to "Controllers",
            "ps" to "PlayStation", "xbox" to "Xbox", "theme" to "Theme", "about" to "About")

    private fun settingsPage(): View {
        if (portrait) {
            val list = sw.col()
            groups.forEachIndexed { i, (k, label) ->
                list.addView(sw.text(label, 26, sw.sub, 800), sw.lp(-2, -2).apply { topMargin = sw.px(if (i == 0) 0 else 40); bottomMargin = sw.px(10); leftMargin = sw.px(26) })
                settingsRows(list, k)
            }
            primaryView = null
            return scrollPane(list)
        }
        val row = sw.row(Gravity.TOP)
        val side = sw.col().apply { setPadding(sw.px(96), sw.px(20), 0, 0) }
        groups.forEach { (k, label) ->
            val on = k == settingsGroup
            side.addView(FrameLayout(this).apply {
                tag = "group:$k"
                if (on) background = sw.round(sw.panel, 6)
                if (on) addView(View(this@HandheldHomeActivity).apply { background = sw.round(sw.ring, 3) }, sw.flp(6, -1).apply { topMargin = sw.px(18); bottomMargin = sw.px(18) })
                addView(sw.text(label, 30, if (on) sw.ring else sw.sub, 700).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(sw.px(28), 0, 0, 0) }, FrameLayout.LayoutParams(-1, -1))
                sw.focusable(this)
                setOnFocusChangeListener { _, has -> if (has && settingsGroup != k) { settingsGroup = k; ui.post { render(force = true) } } }
                setOnClickListener {
                    settingsGroup = k
                    render(force = true)
                    content.findViewWithTag<View>("settings:list")?.let { firstFocusable(it)?.requestFocus() }
                }
            }, sw.lp(-1, 92))
        }
        row.addView(part(side), sw.lp(600, -2))
        val list = sw.col().apply { tag = "settings:list" }
        settingsRows(list, settingsGroup)
        row.addView(part(ColScroll(this, sw.px(40)).apply {
            isVerticalScrollBarEnabled = false; isFocusable = false; clipToPadding = false; clipChildren = false
            setPadding(sw.px(40), sw.px(20), sw.px(96), sw.px(30))
            addView(list)
        }), LinearLayout.LayoutParams(0, -1, 1f))
        primaryView = side.findViewWithTag<View>("group:$settingsGroup")
        return row
    }

    private fun settingsRows(list: LinearLayout, group: String) {
        when (group) {
            "handheld" -> {
                appToggle(list, "hh_rotate", "Screen turns with the device", "Portrait when you hold it upright", true) { applyRotation() }
                choice(list, prefs, "hh_battery_res", "On battery, stream at", "Plugged in, the Picture settings apply",
                        listOf("Same" to "same", "1080p" to "1080", "720p" to "720", "540p" to "540"), "same")
                appToggle(list, "hh_dim_on_tv", "Dim the screen on a TV", "While this ${deviceShort()} is the controller for a TV", true)
                toggle(list, "checkbox_ultra_low_latency", "Ultra low latency", "Experimental. This device: ${chipName()}", false)
            }
            "picture" -> {
                val native = nativeResolution()
                val presets = listOf("720p" to "1280x720", "1080p" to "1920x1080", "1440p" to "2560x1440", "4K" to "3840x2160")
                choice(list, stream, "list_resolution", "Resolution", "Native is this screen: ${native.replace("x", " × ")}",
                        if (presets.any { it.second == native }) presets else presets + ("Native" to native), native)
                choice(list, stream, "list_fps", "Frame rate", "60 is the smoothest. 30 uses less data and battery", listOf("30" to "30", "60" to "60", "90" to "90", "120" to "120"), "60")
                bitrate(list)
                choice(list, stream, "video_format", "Video codec", "H.265 and AV1 look better at the same bitrate",
                        listOf("Auto" to "auto", "H.264" to "neverh265", "H.265" to "forceh265", "AV1" to "forceav1"), "auto")
                toggle(list, "checkbox_enable_hdr", "HDR", "Only when the PC, the game and this screen support it", false)
                choice(list, stream, "list_video_scale_mode", "Picture shape", "Fit with bars, fill and crop, or stretch",
                        listOf("Fit" to "fit", "Fill" to "fill", "Stretch" to "stretch"), if (stream.getBoolean("checkbox_stretch_video", false)) "stretch" else "fit")
                choice(list, stream, "frame_pacing", "Frame pacing", "Trade a little delay for smoother motion",
                        listOf("Lowest delay" to "latency", "Balanced" to "balanced", "Smoothest" to "smoothness"), "latency")
                toggle(list, "checkbox_enable_perf_overlay", "Stats while playing", "Frame rate, delay and lost frames in a corner", false)
            }
            "sound" -> {
                choice(list, stream, "list_audio_config", "Speakers", "Stereo for the built-in speakers and headphones",
                        listOf("Stereo" to "2", "5.1" to "51", "7.1" to "71"), "2")
                toggle(list, "checkbox_host_audio", "Also play sound on the PC", "Useful when someone's in the room with it", false)
                appToggle(list, FeelSounds.PREF, "Menu sounds", "Soft sounds as you move around the menus. Games aren't affected", true)
            }
            "pads" -> {
                action(list, "padtest", "Test a controller", "See every button, stick and trigger live, and try the rumble", null) {
                    startActivity(Intent(this, PadTestActivity::class.java)) }
                action(list, "padpair", "Pair a controller", "Opens Bluetooth settings. Put the pad in pairing mode first", null) { pairController() }
                choice(list, prefs, TvControllerActivity.PAD_PREF, "Controller on a TV", "For PC games sent to a TV. Direct is fastest: it skips the TV's Wi-Fi",
                        listOf("Direct to the PC" to "direct", "Through the TV" to "tv"), "direct")
                toggle(list, "checkbox_multi_controller", "More than one player", "On a PC each pad is its own player", true)
                intChoice(list, "seekbar_deadzone", "Stick deadzone", "Raise it if a character drifts on its own", listOf(3, 5, 7, 10, 15), 7) { "$it%" }
                toggle(list, "checkbox_flip_face_buttons", "Swap A and B", "For Nintendo-style layouts", false)
                toggle(list, "checkbox_gamepad_motion_sensors", "Motion", "Send the pad's gyro to games that use it", true)
                toggle(list, "checkbox_mouse_emulation", "Use the pad as a mouse", "Hold Start to switch", true)
                toggle(list, "checkbox_vibrate_fallback", "Rumble on this device", "When a pad can't rumble", false)
                info(list, "Quick menu", QuickMenu.COMBO_LABEL)
            }
            "ps" -> {
                if (Psn.signedIn(this)) action(list, "psn", "PlayStation Network",
                        "Signed in" + (Psn.onlineId(this)?.takeIf { it.isNotBlank() }?.let { " as $it" } ?: ""), "Sign out") {
                    confirm("Sign out of PlayStation Network?", "You'll sign in again to register a PS5 or reach it away from home.", "Sign out") {
                        Psn.signOut(this); render(force = true) } }
                else action(list, "psn", "PlayStation Network", "Sign in once to register a PS5 and play it away from home", "Sign in") {
                    startActivityForResult(Intent(this, PsnSignInActivity::class.java), REQ_PSN) }
                choice(list, stream, "ps5_resolution", "Resolution", "The PS5 streams up to 1080p", listOf("720p" to "720", "1080p" to "1080"), "1080")
                choice(list, stream, "ps5_bitrate_home", "Bitrate at home", "Auto: 30 Mbps on fast Wi-Fi, 20 otherwise",
                        listOf("Auto" to "auto", "10" to "10", "15" to "15", "20" to "20", "30" to "30"), "auto")
                choice(list, stream, "ps5_codec", "Video codec", "H.265 looks better at the same bitrate", listOf("H.264" to "h264", "H.265" to "h265"), "h265")
                choice(list, stream, "ps5_on_disconnect", "When I disconnect", "What happens to the PS5 after you leave a game",
                        listOf("Ask" to "ask", "Rest mode" to "rest", "Keep it on" to "keep"), "ask")
                toggle(list, "ps5_swap_cross_circle", "Swap Cross and Circle", "For the Japanese layout", false)
                choice(list, stream, "ps5_away_quality", "Quality away from home", "Auto follows the Wi-Fi: 1080p strong, 720p weak",
                        listOf("Auto" to "auto", "540p" to "540", "720p" to "720", "1080p" to "1080"), "auto")
                val pin = prefs.getString("ps5_login_pin", "") ?: ""
                action(list, "pspin", "Console PIN", "Signs in for you when the PS5 asks for its 4-digit login PIN", if (pin.isEmpty()) "Not set" else "••••") {
                    entry("Console PIN", "The 4-digit PIN your PS5 asks for when it starts. Leave empty to turn it off.", "4 digits", pin, true) { v ->
                        if (v.isNotEmpty() && v.length != 4) "The PIN has 4 digits" else { prefs.edit().putString("ps5_login_pin", v).apply(); render(force = true); null }
                    } }
                info(list, "PS button", "L2 + Start")
                info(list, "Touchpad", "Select + Start")
            }
            "xbox" -> {
                if (Xbox.signedIn(this)) action(list, "xbox", "Xbox network", "Signed in" + (Xbox.gamertag(this)?.let { " as $it" } ?: ""), "Sign out") {
                    confirm("Sign out of Xbox?", "Your Xbox consoles leave this device until you sign in again.", "Sign out") {
                        Xbox.signOut(this); xboxes = emptyList(); render(force = true) } }
                else action(list, "xbox", "Xbox network", "Sign in once to play your Xbox here, at home or away", "Sign in") { xboxSignIn() }
                choice(list, stream, "xbox_resolution", "Picture", "The Xbox sets quality from this",
                        listOf("Auto" to "auto", "720p" to "720", "1080p" to "1080", "1080p high" to "1081"), "auto")
                val steps = listOf(0, 5, 10, 15, 20, 30)
                val cur = stream.getInt("xbox_bitrate", 0)
                srow(list, "xbox_bitrate", "Bitrate", "Auto lets the Xbox decide. A cap keeps a weak Wi-Fi from stuttering", value(if (cur == 0) "Auto" else "$cur Mbps")) {
                    stream.edit().putInt("xbox_bitrate", steps[(steps.indexOf(steps.firstOrNull { it >= cur } ?: 0) + 1) % steps.size]).apply()
                    render(force = true) }
                choice(list, stream, "xbox_rumble", "Rumble", "How strongly the controller shakes",
                        listOf("Off" to "off", "Weak" to "weak", "Medium" to "medium", "Strong" to "strong"), "strong")
                intChoice(list, "xbox_deadzone", "Stick deadzone", "Raise it if a character drifts on its own", listOf(3, 5, 8, 10, 15), 8) { "$it%" }
                toggle(list, "xbox_swap_ab", "Swap A and B", "For Nintendo-style layouts", false)
                toggle(list, "xbox_coop", "Two players", "A second controller plays as player 2", false)
            }
            "theme" -> {
                val light = sw.light
                srow(list, "theme:dark", "Dark", "Charcoal, white type", if (!light) value("On") else null) {
                    if (light) { prefs.edit().putString("hh_theme", "dark").apply(); recreate() } }
                srow(list, "theme:light", "Light", "Light grey, dark type", if (light) value("On") else null) {
                    if (!light) { prefs.edit().putString("hh_theme", "light").apply(); recreate() } }
            }
            "about" -> {
                val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
                info(list, "MoonCast", ver ?: "")
                action(list, "about:add", "Add a PC by its address", "For a PC this device doesn't find on its own", null) { addPcByAddress() }
                action(list, "about:pcs", "Manage PCs", "Moonlight's PC list", null) { startActivity(Intent(this, PcView::class.java)) }
                action(list, "about:all", "All Moonlight settings", "Every option, including the rare ones", null) {
                    startActivity(Intent(this, StreamSettings::class.java)) }
            }
        }
    }

    private fun value(s: String) = sw.text(s, 28, sw.ring, 700)

    private fun srow(list: LinearLayout, key: String, title: String, sub: String?, value: View?, onClick: (() -> Unit)?) {
        list.addView(sw.row().apply {
            tag = "set:$key"
            background = sw.bottomLine()
            setPadding(sw.px(26), sw.px(18), sw.px(26), sw.px(18))
            minimumHeight = sw.px(116)
            addView(sw.col().apply {
                addView(sw.text(title, 30, sw.ink, 700))
                if (sub != null) addView(sw.text(sub, 23, sw.sub, 600, 2), sw.lp(-2, -2).apply { topMargin = sw.px(8) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (value != null) addView(value, (value.layoutParams as? LinearLayout.LayoutParams ?: sw.lp(-2, -2)).apply { leftMargin = sw.px(24) })
            if (!portrait) setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && e.action == KeyEvent.ACTION_DOWN)
                    content.findViewWithTag<View>("group:$settingsGroup")?.requestFocus() ?: false
                else false
            }
            if (onClick != null) { sw.focusable(this); setOnClickListener { onClick() } }
        }, sw.lp(-1, -2))
    }

    private fun choice(list: LinearLayout, store: SharedPreferences, key: String, title: String, sub: String, options: List<Pair<String, String>>, def: String) {
        val cur = store.getString(key, def)
        val label = options.firstOrNull { it.second == cur }?.first ?: cur ?: ""
        srow(list, key, title, sub, value(label)) {
            val i = options.indexOfFirst { it.second == cur }
            store.edit().putString(key, options[(i + 1) % options.size].second).apply()
            render(force = true)
        }
    }

    private fun intChoice(list: LinearLayout, key: String, title: String, sub: String, options: List<Int>, def: Int, label: (Int) -> String) {
        val cur = stream.getInt(key, def)
        srow(list, key, title, sub, value(label(cur))) {
            stream.edit().putInt(key, options[(options.indexOf(cur) + 1) % options.size]).apply()
            render(force = true)
        }
    }

    private fun bitrate(list: LinearLayout) {
        val steps = listOf(0, 10, 20, 30, 50, 80)
        val cur = stream.getInt("seekbar_bitrate_kbps", 0) / 1000
        srow(list, "seekbar_bitrate_kbps", "Bitrate", "Auto picks one for the resolution", value(if (cur == 0) "Auto" else "$cur Mbps")) {
            val next = steps[(steps.indexOf(steps.firstOrNull { it >= cur } ?: 0) + 1) % steps.size]
            if (next == 0) stream.edit().remove("seekbar_bitrate_kbps").apply() else stream.edit().putInt("seekbar_bitrate_kbps", next * 1000).apply()
            render(force = true)
        }
    }

    private fun toggle(list: LinearLayout, key: String, title: String, sub: String, def: Boolean) = flip(list, stream, key, title, sub, def, null)
    private fun appToggle(list: LinearLayout, key: String, title: String, sub: String, def: Boolean, after: (() -> Unit)? = null) = flip(list, prefs, key, title, sub, def, after)

    private fun flip(list: LinearLayout, store: SharedPreferences, key: String, title: String, sub: String, def: Boolean, after: (() -> Unit)?) {
        val on = store.getBoolean(key, def)
        val t = sw.toggle(on).apply { layoutParams = sw.lp(96, 52) }
        srow(list, key, title, sub, t) {
            store.edit().putBoolean(key, !on).apply()
            FeelSounds.play(this, FeelSounds.Cue.TOGGLE, FeelSounds.panFor(t))
            after?.invoke()
            render(force = true)
        }
    }

    private fun action(list: LinearLayout, key: String, title: String, sub: String, v: String?, onClick: () -> Unit) =
        srow(list, key, title, sub, v?.let { value(it) }, onClick)

    private fun info(list: LinearLayout, title: String, v: String) =
        srow(list, "info:$title", title, null, sw.text(v, 26, sw.sub, 700), null)

    // ---------------------------------------------------------------- playing

    /** A message in the Switch style along the bottom (system toasts showed as an empty white pill on the Odin). */
    private var snack: TextView? = null
    private val hideSnack = Runnable { snack?.animate()?.alpha(0f)?.translationY(sw.pxf(20))?.setDuration(200)?.start() }
    private fun toast(s: String) {
        val v = snack?.takeIf { it.parent === root } ?: sw.text("", 26, sw.ink, 700, 3).apply {
            gravity = Gravity.CENTER
            setPadding(sw.px(36), sw.px(22), sw.px(36), sw.px(22))
            background = sw.round(sw.panel, 18, sw.line, 2)
            elevation = sw.pxf(12)
            alpha = 0f
            root.addView(this, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = sw.px(if (portrait) 260 else 150); leftMargin = sw.px(60); rightMargin = sw.px(60) })
            snack = this
        }
        v.text = s
        v.bringToFront()
        v.translationY = sw.pxf(20)
        v.animate().alpha(1f).translationY(0f).setDuration(200).start()
        ui.removeCallbacks(hideSnack)
        ui.postDelayed(hideSnack, if (s.length > 60) 5000L else 3500L)
    }

    /** A: plays where games go now, this device or the TV picked in "Play on" (the top chip says which). */
    private fun play(g: Game) {
        val tv = TvFinder.target(this)
        if (tv != null) sendToTv(g, tv) else playHere(g)
    }

    private fun playHere(g: Game) {
        when {
            g.pc != null && g.app != null -> {
                if (g.pc.state == ComputerDetails.State.OFFLINE) {   // only when a poll says so (UNKNOWN = not asked yet)
                    confirm("${g.pc.name} is offline", "Wake it, then start ${g.app.appName} once it's online.", "Wake it") { wake(g.pc) }
                    return
                }
                Recents.touch(this, g.key)
                start(g.pc, g.app)
            }
            g.ps != null -> { Recents.touch(this, g.key); playPs(g.ps) }
            g.xb != null -> { Recents.touch(this, g.key); playXbox(g.xb) }
        }
    }

    private fun playPs(c: Ps5Store.Console) = Launch.playPs(this, c, power(c)) { toast(it) }

    private fun playXbox(c: Xbox.Console) {
        FeelSounds.play(this, FeelSounds.Cue.LAUNCH)
        startActivity(Launch.xboxIntent(this, c))
    }

    /**
     * "Play on": this device or a MoonCast TV. With a game it plays there now; without one (the top chip, the round
     * button) it picks where games go when you press A. TVs are looked for again each time it opens.
     */
    private fun playWhere(g: Game?, sweep: Boolean = true) {
        // what the stream will actually use: Moonlight's own reading of the saved resolution and frame rate
        val pc = com.limelight.preferences.PreferenceConfiguration.readPreferences(this)
        val res = "${minOf(pc.width, pc.height)}p"
        val fps = pc.fps.toString()
        val cur = TvFinder.target(this)
        val tvs = TvFinder.known(this)
        val opts = ArrayList<Opt>()
        opts += Opt("▭", Sw.PC, "This ${deviceShort()}", "$res, $fps fps", if (cur == null) "Now" else null) {
            TvFinder.setTarget(this, null)
            if (g != null) playHere(g) else { toast("Games play on this ${deviceShort()}"); render(force = true) }
        }
        tvs.forEach { tv ->
            val found = TvFinder.isHere(tv)
            val xboxNo = g?.xb != null && (!tv.canXbox || !tv.xbox)
            val sub = when {
                !found -> "Not on this network"
                xboxNo && !tv.canXbox -> "Update MoonCast on this TV to send it an Xbox"
                xboxNo -> "Sign in to Xbox on this TV first"
                g?.pc != null && tv.pc.isEmpty() -> "Ready. No PC paired on it yet"
                tv.pc.isNotEmpty() -> "Ready. Plays PC games from ${tv.pc}"
                else -> "Ready"
            }
            opts += Opt("TV", sw.ring, tv.name, sub, if (cur?.id == tv.id) "Now" else if (found) "Ready" else null, found && !xboxNo) {
                TvFinder.setTarget(this, tv)
                if (g != null) sendToTv(g, tv) else { toast("Games play on ${tv.name}. This ${deviceShort()} is the controller"); render(force = true) }
            }
        }
        val body = (if (g != null) "${g.title}. " else "") + when {
            tvs.isEmpty() && sweep -> "Looking for MoonCast TVs on this network…"
            tvs.isEmpty() -> "No MoonCast TV found. Open MoonCast on the TV, on the same Wi-Fi as this ${deviceShort()}."
            else -> "On a TV, this ${deviceShort()} is the controller."
        }
        dialog(if (g != null) "Where should it play?" else "Play on", body, opts, listOf("Close" to {}))
        whereOpen = g ?: NO_GAME
        if (sweep) {
            val before = tvs.filter { TvFinder.isHere(it) }.map { it.id }.toSet()
            TvFinder.sweep(this) {
                val again = whereOpen ?: return@sweep
                if (overlayKind != "dialog") return@sweep
                val now = TvFinder.known(this).filter { TvFinder.isHere(it) }.map { it.id }.toSet()
                // reopen with what was found (the overlay stays up, so focus still returns where it was)
                if (now != before || tvs.isEmpty()) playWhere(again.takeIf { it !== NO_GAME }, sweep = false)
            }
        }
    }
    private var whereOpen: Game? = null
    private val NO_GAME = Game("", "", "", "", false)

    /** Y: switch between this device and the last TV. With no TV yet, the "Play on" list opens. */
    private fun toggleTarget() {
        val last = TvFinder.lastTv(this)
        when {
            TvFinder.target(this) != null -> { TvFinder.setTarget(this, null); toast("Games play on this ${deviceShort()}") }
            last != null -> { TvFinder.setTarget(this, last)
                toast("Games play on ${last.name}" + if (TvFinder.isHere(last)) "" else ". It isn't on this network right now") }
            else -> { playWhere(focusedGame() ?: gameFor(selKey)); return }
        }
        FeelSounds.play(this, FeelSounds.Cue.TOGGLE)
        render(force = true)
    }

    /** Send [g] to [tv]; controller mode shows the progress (and the TV's answer if it can't). */
    private fun sendToTv(g: Game, tv: TvFinder.Tv) {
        val t = when {
            g.pc != null && g.app != null -> {
                if (!online(g.pc)) {
                    confirm("${g.pc.name} is offline", "Wake it, then send ${g.app.appName} to ${tv.name} once it's online.", "Wake it") { wake(g.pc) }
                    return
                }
                MoveToTv.Target("pc", g.app.appName, g.pc.uuid, g.pc.name, g.app.appId)
            }
            g.ps != null -> MoveToTv.Target("ps", g.ps.name, psHost = g.ps.host, registKey = g.ps.registKey, morning = g.ps.morning, isPs5 = g.ps.ps5)
            g.xb != null -> {
                if (!tv.canXbox) return toast("Update MoonCast on ${tv.name} to send it an Xbox game.")
                if (TvFinder.isHere(tv) && !tv.xbox) return toast("Sign in to Xbox on ${tv.name} first. It plays your Xbox with its own sign-in.")
                MoveToTv.Target("xb", g.xb.name, xboxId = g.xb.id)
            }
            else -> return
        }
        Recents.touch(this, g.key)
        FeelSounds.play(this, FeelSounds.Cue.LAUNCH)
        TvSender.open(this, tv, t)
    }

    private fun controllers() {
        dialog("Controllers", "Using: ${controllerName()}", listOf(
                Opt("◎", Sw.C_BLUE, "Test a controller", "Every button, stick and trigger, live") { startActivity(Intent(this, PadTestActivity::class.java)) },
                Opt("+", Sw.C_BLUE, "Pair a controller", "Opens Bluetooth settings") { pairController() },
                Opt("⚙", Sw.PC, "Controller settings") { settingsGroup = "pads"; go(Page.SETTINGS) }), listOf("Close" to {}))
    }

    private fun start(pc: ComputerDetails, app: NvApp) {
        val b = binder ?: return toast(getString(R.string.error_manager_not_running))
        val other = runningApp(pc)
        if (other != null && other.appId != app.appId) {
            confirm("Quit ${other.appName}?", "${pc.name} is running ${other.appName}. Quit it and start ${app.appName}?", "Quit and start") {
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
                if (overlayKind == "pin") hideOverlay(false)
                msg?.let { toast(it) }
                if (ok) { toast("Paired with ${d.name}"); gamesTab = "pc"; page = Page.GAMES }
                startPolling()
                render(force = true)
            }
        }.start()
    }

    private fun showPin(d: ComputerDetails, pin: String) {
        val card = card()
        card.addView(sw.text("Enter this PIN in Sunshine", 40, sw.ink, 800), sw.lp(-1, -2).apply { leftMargin = sw.px(56); topMargin = sw.px(48); rightMargin = sw.px(56) })
        card.addView(sw.row().apply {
            gravity = Gravity.CENTER
            pin.forEach { ch -> addView(sw.text(ch.toString(), 96, sw.ink, 800).apply { gravity = Gravity.CENTER; background = sw.round(sw.bg, 14) },
                    sw.lp(140, 170).apply { leftMargin = sw.px(10); rightMargin = sw.px(10) }) }
        }, sw.lp(-1, -2).apply { topMargin = sw.px(30) })
        val host = d.activeAddress?.address ?: "the PC"
        card.addView(sw.text("On the PC open https://$host:47990, go to PIN, type $pin, name it ${deviceName()} and press Send.", 26, sw.sub, 600, 4),
                sw.lp(-1, -2).apply { leftMargin = sw.px(56); rightMargin = sw.px(56); topMargin = sw.px(30) })
        buttons(card, listOf("Cancel" to {}))
        showOverlay("pin", card)
    }

    private fun confirmUnpair(d: ComputerDetails) =
        confirm("Unpair ${d.name}?", "It stays here, but you'll pair it again with a PIN before you can play its games.", "Unpair") {
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
        confirm("Remove ${d.name}?", "It disappears from this device and is unpaired. To add it back, pair it again with a PIN.", "Remove") {
            val b = binder ?: return@confirm
            if (online(d) && paired(d)) Thread {
                try { NvHTTP(ServerHelper.getCurrentAddressFromComputer(d), d.httpsPort, b.uniqueId, d.serverCert,
                        PlatformBinding.getCryptoProvider(this)).unpair() } catch (e: Exception) { }
            }.start()
            appPollers.remove(d.uuid)?.stop()
            b.removeComputer(d)
            DiskAssetLoader(this).deleteAssetsForComputer(d.uuid)
            getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE).edit().remove(d.uuid).apply()
            try { ShortcutHelper(this).disableComputerShortcut(d, getString(R.string.scut_deleted_pc)) } catch (e: Exception) { }
            pcs.remove(d.uuid); apps.remove(d.uuid); rawLists.remove(d.uuid); loaders.remove(d.uuid)
            Recents.forget(this, "pc:${d.uuid}:")
            render(force = true)
        }

    private fun addPcByAddress() {
        entry("Add a PC by its address", "The IP address or name of the PC Sunshine runs on. Add :port only if it isn't the usual one.",
                "For example 192.168.1.20", "", "address") { raw ->
            if (raw.isEmpty()) return@entry "Type the PC's address first."
            val b = binder ?: return@entry "Still starting, try again in a moment."
            toast("Looking for a PC at $raw…")
            Thread {
                val ok = try {
                    val uri = try { java.net.URI("moonlight://$raw") } catch (e: Exception) { java.net.URI("moonlight://[$raw]") }
                    val host = uri.host ?: throw IllegalArgumentException()
                    val d = ComputerDetails()
                    d.manualAddress = ComputerDetails.AddressTuple(host, if (uri.port == -1) NvHTTP.DEFAULT_HTTP_PORT else uri.port)
                    b.addComputerBlocking(d)
                } catch (e: Exception) { false }
                runOnUiThread {
                    if (ok) { toast("Added. Pair it from Consoles."); go(Page.CONSOLES) }
                    else toast("No PC answered at $raw. Check it's on, Sunshine is running, and the address is right.")
                }
            }.start()
            null
        }
    }

    // ---- PS5 registration, here on the handheld: PSN sign-in (once) -> pick the PS5 -> 8-digit code ----

    private fun registerPs() {
        if (!Psn.signedIn(this)) { startActivityForResult(Intent(this, PsnSignInActivity::class.java), REQ_PSN_REGISTER); return }
        val card = card()
        card.addView(sw.text("Which PlayStation?", 40, sw.ink, 800), sw.lp(-1, -2).apply { leftMargin = sw.px(56); topMargin = sw.px(48) })
        val sub = sw.text("Looking on this network…", 26, sw.sub, 600, 3)
        card.addView(sub, sw.lp(-1, -2).apply { leftMargin = sw.px(56); rightMargin = sw.px(56); topMargin = sw.px(10); bottomMargin = sw.px(26) })
        val list = sw.col()
        card.addView(list, sw.lp(-1, -2))
        buttons(card, listOf("Cancel" to {}))
        showOverlay("pickps", card)
        Thread {
            val found = Ps5Store.discover()
            runOnUiThread {
                if (overlayKind != "pickps") return@runOnUiThread
                if (found.isEmpty()) sub.text = "No PlayStation answered. Make sure it's on or in rest mode (not fully off) and on this network."
                else {
                    sub.text = "Pick yours. Then on it open Settings, System, Remote Play, Link device to see the code."
                    found.forEachIndexed { i, f ->
                        list.addView(optRow(Opt("PS", Sw.PS, f.name, f.host, if (f.on) "On" else "Rest mode") { enterCode(f) }, if (i == 0) "primary" else "ps:${f.host}"))
                    }
                    list.findViewWithTag<View>("primary")?.requestFocus()
                }
            }
        }.start()
    }

    private fun enterCode(f: Ps5Store.Found) {
        entry("Type the code from ${f.name}", "On the PS5: Settings, System, Remote Play, Link device. The code has 8 digits.", "8 digits", "", true) { raw ->
            if (raw.length != 8 || raw.any { !it.isDigit() }) return@entry "The code has 8 digits."
            val acc = Psn.accountId(this) ?: return@entry "Sign in to PlayStation Network first."
            toast("Registering with ${f.name}…")
            Thread {
                val r = try { com.limelight.Ps5Register.register(f.host, android.util.Base64.decode(acc, android.util.Base64.DEFAULT), raw.toInt(), true, 30000L) }
                        catch (e: Exception) { null }
                if (r != null) Ps5Store.save(this, Ps5Store.Console(f.host, r.nickname.ifBlank { f.name }, r.registKeyHex, r.morningHex, true))
                runOnUiThread {
                    if (r == null) toast("That didn't work. Check the code (it changes every few minutes) and that the PS5 still shows Link device.")
                    else { toast("${r.nickname.ifBlank { f.name }} added"); consoles = Ps5Store.all(this); render(force = true) }
                }
            }.start()
            null
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_PSN_REGISTER -> { toast("Signed in to PlayStation Network"); ui.post { registerPs() } }
            REQ_PSN -> { toast("Signed in to PlayStation Network"); render(force = true) }
        }
    }

    /** Microsoft's device-code sign-in. On a handheld the page can open right here. */
    private fun xboxSignIn() {
        val card = card()
        card.addView(sw.text("Sign in to Xbox", 40, sw.ink, 800), sw.lp(-1, -2).apply { leftMargin = sw.px(56); topMargin = sw.px(48) })
        val sub = sw.text("Getting a code…", 26, sw.sub, 600, 3)
        card.addView(sub, sw.lp(-1, -2).apply { leftMargin = sw.px(56); rightMargin = sw.px(56); topMargin = sw.px(10) })
        val code = sw.text("", 80, sw.ink, 800).apply { letterSpacing = 0.12f; gravity = Gravity.CENTER }
        card.addView(code, sw.lp(-1, -2).apply { topMargin = sw.px(26); bottomMargin = sw.px(10) })
        var url: String? = null
        buttons(card, listOf("Cancel" to {}, "Open the page" to {
            url?.let { u -> try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } catch (e: Exception) { toast("Open $u in a browser") } }
            Unit
        }), keepOpenLast = true)
        showOverlay("xbox", card)
        Thread {
            try {
                val dc = Xbox.startSignIn()
                runOnUiThread {
                    url = dc.url
                    sub.text = "Open ${dc.url.removePrefix("https://").removePrefix("www.")} (here or on another device), enter this code, and sign in with your Xbox account."
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
                            render(force = true)
                        }
                        return@Thread
                    }
                }
                runOnUiThread { if (overlayKind == "xbox") { sub.text = "The code expired. Cancel and try again."; code.text = "" } }
            } catch (e: Exception) {
                runOnUiThread { if (overlayKind == "xbox") sub.text = "Sign-in didn't work: ${e.message?.take(120) ?: e.javaClass.simpleName}" }
            }
        }.start()
    }

    private fun pairController() {
        for (a in listOf(Settings.ACTION_BLUETOOTH_SETTINGS, Settings.ACTION_SETTINGS)) {
            try { startActivity(Intent(a)); return } catch (e: Exception) { }
        }
        toast("Open Settings, then Bluetooth, to pair a controller.")
    }

    // ---------------------------------------------------------------- dialogs (Switch style)

    private fun card(): LinearLayout = sw.col().apply {
        background = sw.round(sw.panel, 18)
        clipToOutline = true; outlineProvider = rounded(18)
    }

    private fun optRow(o: Opt, tag: String): View = sw.row().apply {
        this.tag = tag
        background = sw.round(sw.bg, 10)
        setPadding(sw.px(28), sw.px(16), sw.px(28), sw.px(16))
        minimumHeight = sw.px(108)
        if (!o.enabled) alpha = 0.45f
        if (o.icon != null) addView(sw.text(o.icon, 22, 0xFFFFFFFF.toInt(), 800).apply { gravity = Gravity.CENTER; background = sw.round(o.iconColor, 14) },
                sw.lp(64, 64).apply { rightMargin = sw.px(26) })
        addView(sw.col().apply {
            addView(sw.text(o.title, 30, sw.ink, 800))
            if (o.sub != null) addView(sw.text(o.sub, 22, sw.sub, 600, 2), sw.lp(-2, -2).apply { topMargin = sw.px(4) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (o.right != null) addView(sw.text(o.right, 22, sw.sub, 700), sw.lp(-2, -2).apply { leftMargin = sw.px(20) })
        if (o.enabled) {
            sw.focusable(this)
            setOnClickListener { val n = FeelSounds.serial; hideOverlay(false); o.onClick(); if (n == FeelSounds.serial) FeelSounds.play(this@HandheldHomeActivity, FeelSounds.Cue.SELECT) }
        }
        layoutParams = sw.lp(-1, -2).apply { leftMargin = sw.px(40); rightMargin = sw.px(40); bottomMargin = sw.px(14) }
    }

    /** The Switch's buttons along the bottom of a dialog, split by a line; the last is where focus starts. */
    private fun buttons(card: LinearLayout, list: List<Pair<String, () -> Unit>>, primary: Boolean = true, keepOpenLast: Boolean = false) {
        val row = sw.row().apply { background = sw.topLine() }
        list.forEachIndexed { i, (label, fn) ->
            val keep = keepOpenLast && i == list.size - 1
            row.addView(sw.text(label, 30, sw.ring, 800).apply {
                gravity = Gravity.CENTER
                tag = if (primary && i == list.size - 1) "primary" else "btn:$i"
                if (i > 0) background = object : android.graphics.drawable.Drawable() {
                    private val p = android.graphics.Paint().apply { color = sw.line }
                    override fun draw(c: android.graphics.Canvas) { c.drawRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.left + sw.pxf(2).coerceAtLeast(1f), bounds.bottom.toFloat(), p) }
                    override fun setAlpha(a: Int) {}
                    override fun setColorFilter(f: android.graphics.ColorFilter?) {}
                    @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
                }
                sw.focusable(this)
                setOnClickListener {
                    if (keep) { fn(); return@setOnClickListener }
                    val n = FeelSounds.serial
                    hideOverlay(false); fn()
                    if (n == FeelSounds.serial) FeelSounds.play(this@HandheldHomeActivity, FeelSounds.Cue.CLOSE)
                }
            }, LinearLayout.LayoutParams(0, sw.px(112), 1f))
        }
        card.addView(row, sw.lp(-1, 112).apply { topMargin = sw.px(26) })
    }

    private fun dialog(title: String, body: String?, opts: List<Opt>, btns: List<Pair<String, () -> Unit>>) {
        val card = card()
        card.addView(sw.text(title, 40, sw.ink, 800, 2), sw.lp(-1, -2).apply { leftMargin = sw.px(56); rightMargin = sw.px(56); topMargin = sw.px(48); bottomMargin = sw.px(10) })
        if (body != null) card.addView(sw.text(body, 26, sw.sub, 600, 4), sw.lp(-1, -2).apply { leftMargin = sw.px(56); rightMargin = sw.px(56); bottomMargin = sw.px(26) })
        val firstOn = opts.indexOfFirst { it.enabled }
        val list = sw.col()
        opts.forEachIndexed { i, o -> list.addView(optRow(o, if (i == firstOn) "primary" else "opt:$i")) }
        if (opts.size > 4) card.addView(ColScroll(this, sw.px(30)).apply { isVerticalScrollBarEnabled = false; addView(list) }, sw.lp(-1, 560))
        else card.addView(list, sw.lp(-1, -2))
        buttons(card, btns, primary = firstOn < 0)
        showOverlay("dialog", card)
    }

    private fun confirm(title: String, body: String, yes: String, onYes: () -> Unit) =
        dialog(title, body, emptyList(), listOf("Cancel" to {}, yes to onYes))

    /** A one-line entry; [onSave] returns an error to show, or null when the value is good. */
    private fun entry(title: String, body: String, hint: String, v: String, numbers: Boolean, onSave: (String) -> String?) =
        entry(title, body, hint, v, if (numbers) "number" else "text", onSave)

    private fun entry(title: String, body: String, hint: String, v: String, kind: String, onSave: (String) -> String?) {
        val card = card()
        card.addView(sw.text(title, 40, sw.ink, 800, 2), sw.lp(-1, -2).apply { leftMargin = sw.px(56); rightMargin = sw.px(56); topMargin = sw.px(48); bottomMargin = sw.px(10) })
        card.addView(sw.text(body, 26, sw.sub, 600, 4), sw.lp(-1, -2).apply { leftMargin = sw.px(56); rightMargin = sw.px(56) })
        val field = EditText(this).apply {
            setText(v); setSelection(v.length)
            this.hint = hint
            setTextColor(sw.ink); setHintTextColor(sw.sub)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, sw.pxf(32)); typeface = sw.ui(700)
            setSingleLine(true)
            inputType = when (kind) {
                "number" -> android.text.InputType.TYPE_CLASS_NUMBER
                "address" -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                else -> android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            // keep the box on our dark card: no full-screen white extract editor on a landscape handheld
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
            highlightColor = sw.ring and 0x66FFFFFF
            if (Build.VERSION.SDK_INT >= 29) { textCursorDrawable = sw.round(sw.ring, 2).apply { setSize(sw.px(3), sw.px(36)) } }
            background = sw.round(sw.bg, 10, sw.ring, 3)
            setPadding(sw.px(24), 0, sw.px(24), 0)
            tag = "field"
        }
        card.addView(field, sw.lp(-1, 96).apply { leftMargin = sw.px(56); rightMargin = sw.px(56); topMargin = sw.px(26) })
        val err = sw.text("", 23, Sw.DANGER, 700, 2)
        card.addView(err, sw.lp(-1, -2).apply { leftMargin = sw.px(56); rightMargin = sw.px(56); topMargin = sw.px(12) })
        fun submit() { val e = onSave(field.text.toString().trim()); if (e == null) hideOverlay() else err.text = e }
        field.setOnEditorActionListener { _, _, _ -> submit(); true }
        buttons(card, listOf("Cancel" to {}, "OK" to { submit() }), keepOpenLast = true)
        showOverlay("entry", card)
        field.requestFocus()
        try { (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(field, 0) } catch (e: Exception) { }
    }

    private var returnTag: String? = null

    private fun showOverlay(kind: String, card: View) {
        FeelSounds.play(this, FeelSounds.Cue.OPEN)
        if (overlayKind == null) returnTag = currentFocus?.tag as? String   // where focus goes back to when it closes
        overlayKind = kind
        overlay.removeAllViews()
        overlay.setBackgroundColor(0x9E000000.toInt())
        overlay.addView(card, FrameLayout.LayoutParams(sw.px(minOf(960f, sw.designWidth - 60)), -2, Gravity.CENTER))
        overlay.visibility = View.VISIBLE
        overlay.setOnClickListener { hideOverlay() }   // a tap outside the card closes it
        card.isClickable = true
        // clickable views are focusable by default on Android 8+; the scrim and the card must never take focus
        overlay.isFocusable = false; card.isFocusable = false
        main.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        (overlay.findViewWithTag<View>("primary") ?: firstFocusable(overlay))?.requestFocus()
    }

    private fun hideOverlay(cue: Boolean = true) {
        if (overlayKind == null) return
        if (cue) FeelSounds.play(this, FeelSounds.Cue.CLOSE)
        try {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(window.decorView.windowToken, 0)
        } catch (e: Exception) { }
        overlayKind = null
        whereOpen = null
        overlay.removeAllViews()
        overlay.visibility = View.GONE
        main.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        render(force = true)
        returnTag?.let { t -> content.findViewWithTag<View>(t)?.requestFocus() }
        returnTag = null
    }

    // ---------------------------------------------------------------- device bits

    private fun deviceName(): String =
        (if (Build.VERSION.SDK_INT >= 25) Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) else null) ?: Build.MODEL

    /** "Odin" on AYN's handhelds, else "device". */
    private fun deviceShort(): String =
        if (Build.MANUFACTURER.equals("AYN", true) || Build.MODEL.contains("Odin", true)) "Odin" else "device"

    /** The first real game controller's name, for the hint bar. */
    private fun controllerName(): String {
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            if (d.isVirtual) continue
            val s = d.sources
            if ((s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD || (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
                return d.name.ifBlank { "Controller" }
        }
        return "Touch screen"
    }

    private fun nativeResolution(): String {
        @Suppress("DEPRECATION") val d = windowManager.defaultDisplay
        var w: Int; var h: Int
        if (Build.VERSION.SDK_INT >= 23) { val m = d.mode; w = m.physicalWidth; h = m.physicalHeight }
        else { val p = android.graphics.Point(); @Suppress("DEPRECATION") d.getRealSize(p); w = p.x; h = p.y }
        if (h > w) { val t = w; w = h; h = t }
        return "${w and 1.inv()}x${h and 1.inv()}"
    }

    private fun chipName(): String = com.limelight.home.Chip.name()

    private fun rounded(r: Number) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, sw.pxf(r))
    }

    // ---------------------------------------------------------------- input

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && overlayKind == null && e.repeatCount == 0) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_BUTTON_Y -> if (page == Page.HOME || page == Page.GAMES) { toggleTarget(); return true }
                KeyEvent.KEYCODE_BUTTON_X -> if (page == Page.GAMES) {
                    sortAz = !sortAz; FeelSounds.play(this, FeelSounds.Cue.TOGGLE); render(force = true); return true }
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> {
                    val step = if (e.keyCode == KeyEvent.KEYCODE_BUTTON_L1) -1 else 1
                    when (page) {
                        Page.GAMES -> {
                            val tabs = listOf("all", "pc", "ps", "xb")
                            gamesTab = tabs[(tabs.indexOf(gamesTab) + step + tabs.size) % tabs.size]
                            FeelSounds.play(this, FeelSounds.Cue.TAB, 0.3f * step); render(force = true); return true
                        }
                        Page.SETTINGS -> if (!portrait) {
                            val i = groups.indexOfFirst { it.first == settingsGroup }
                            settingsGroup = groups[(i + step + groups.size) % groups.size].first
                            FeelSounds.play(this, FeelSounds.Cue.TAB, 0.3f * step); render(force = true)
                            content.findViewWithTag<View>("group:$settingsGroup")?.requestFocus(); return true
                        }
                        else -> {
                            val order = Page.values()
                            go(order[(order.indexOf(page) + step + order.size) % order.size])
                            FeelSounds.play(this, FeelSounds.Cue.TAB, 0.3f * step); return true
                        }
                    }
                }
            }
        }
        return feel(e)
    }

    private fun focusedGame(): Game? {
        val t = currentFocus?.tag as? String ?: return null
        return if (t.startsWith("g:")) gameFor(t.removePrefix("g:")) else null
    }

    /** As on the TV: a focus tick, the end-of-row nudge, and a select sound (with the ring's pop) for a confirm press. */
    private fun feel(e: KeyEvent): Boolean {
        val dir = Feel.direction(e.keyCode)
        if (dir != 0 && e.action == KeyEvent.ACTION_DOWN) {
            val before = currentFocus
            // nothing focused yet (just opened, or back from touch): start on the page's first item, not the top bar
            if (before == null || !(isIn(before, content) || isIn(before, overlay) || isIn(before, foot))) {
                val start = if (overlayKind != null) overlay.findViewWithTag<View>("primary") ?: firstFocusable(overlay)
                            else primaryView?.takeIf { it.isAttachedToWindow } ?: firstFocusable(content)
                if (start != null && start.requestFocus()) { FeelSounds.focus(start); return true }
            }
            if (super.dispatchKeyEvent(e)) {    // a view moved the focus itself (a scrolling row, a list)
                val after = currentFocus
                if (after != null && after !== before) FeelSounds.focus(after)
                return true
            }
            if (before == null || before is EditText) return false
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
            Feel.edgeNudge(before, dir, e, sw.pxf(14))
            return true
        }
        val confirm = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER ||
                e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || e.keyCode == KeyEvent.KEYCODE_BUTTON_A
        val focused = currentFocus
        if (confirm && e.action == KeyEvent.ACTION_UP && focused != null && focused !is EditText) {
            val pan = FeelSounds.panFor(focused)
            val n = FeelSounds.serial
            glide.pop()
            val handled = super.dispatchKeyEvent(e)
            if (handled && n == FeelSounds.serial) FeelSounds.play(this, FeelSounds.Cue.SELECT, pan)
            return handled
        }
        return super.dispatchKeyEvent(e)
    }

    private fun isIn(v: View, parent: View): Boolean {
        var p: Any? = v
        while (p is View) { if (p === parent) return true; p = p.parent }
        return false
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            overlayKind != null -> hideOverlay()
            page != Page.HOME -> { FeelSounds.play(this, FeelSounds.Cue.BACK); go(Page.HOME) }
            else -> { FeelSounds.play(this, FeelSounds.Cue.BACK); moveTaskToBack(true) }
        }
    }

    companion object {
        const val EXTRA_PLAY_HERE = "play_here"
        private const val REQ_PSN_REGISTER = 41
        private const val REQ_PSN = 42
    }
}
