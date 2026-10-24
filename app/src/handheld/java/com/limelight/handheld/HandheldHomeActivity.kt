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
import com.limelight.R
import com.limelight.binding.PlatformBinding
import com.limelight.binding.crypto.AndroidCryptoProvider
import com.limelight.home.Profile
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
import com.limelight.preferences.PreferenceConfiguration
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

    /** One thing you can play: a PC game, a PS5, an Xbox, or an Android app picked for "On this device". */
    private class Game(val key: String, val title: String, val src: String, val seed: String, val running: Boolean,
                       val pc: ComputerDetails? = null, val app: NvApp? = null,
                       val ps: Ps5Store.Console? = null, val xb: Xbox.Console? = null,
                       val dev: com.limelight.home.DeviceApps.App? = null)

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
    private var treeHooked = false
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

    private val prefs: SharedPreferences by lazy { getSharedPreferences("mc_tv", Context.MODE_PRIVATE) }
    private val stream: SharedPreferences by lazy { android.preference.PreferenceManager.getDefaultSharedPreferences(this) }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Profile.syncPcSettings(this)
        applyRotation()
        // a handheld streams at its own screen size unless you chose one (Moonlight's default is 720p);
        // a resolution saved earlier, in either of Moonlight's keys, is left alone
        if (!stream.contains("list_resolution") && !stream.contains("list_resolution_fps"))
            stream.edit().putString("list_resolution", nativeResolution()).apply()
        // Native used to be the raw panel size, which no longer matches the list: move that saved value to the new Native.
        if (stream.getString("list_resolution", null) == rawNativeSize())
            stream.edit().putString("list_resolution", nativeResolution()).apply()
        // Native follows the screen a stream starts on (a foldable's cover or inner screen). It used to be saved as one
        // size, of the screen the app first opened on: any saved size that isn't one of the fixed steps was that.
        if (!stream.contains(PreferenceConfiguration.NATIVE_RES_PREF_STRING))
            stream.edit().putBoolean(PreferenceConfiguration.NATIVE_RES_PREF_STRING,
                    stream.getString("list_resolution", null).let { it == null || it !in RES_STEPS.map { s -> s.second } || it == nativeResolution() }).apply()

        syncNativeRes()
        MoveToTv.handler = TvSender   // the players' Quick menu offers "Move to <TV>" through this
        // Rebuilt for a theme change (recreate): come back to the same page, not Home (#44)
        b?.getString(STATE_PAGE)?.let { n -> Page.values().firstOrNull { it.name == n }?.let { page = it } }
        b?.getString(STATE_GROUP)?.let { settingsGroup = it }
        buildShell()
        bindService(Intent(this, ComputerManagerService::class.java), conn, Context.BIND_AUTO_CREATE)
        takeIntent(intent)
        (getSystemService(Context.INPUT_SERVICE) as InputManager).registerInputDeviceListener(pads, ui)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString(STATE_PAGE, page.name)
        out.putString(STATE_GROUP, settingsGroup)
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
        if (::glide.isInitialized) glide.resumeBreath()
        com.limelight.home.DeviceApps.refresh()   // apps may have been installed, updated or removed meanwhile
        // anonymous usage check-in: once a day if the user said yes; the question once, a moment after home is up
        com.limelight.home.Usage.checkIn(this)
        if (com.limelight.home.Usage.shouldAsk(this)) ui.postDelayed({
            if (resumed && overlayKind == null && com.limelight.home.Usage.shouldAsk(this))
                dialog(com.limelight.home.Usage.QUESTION, com.limelight.home.Usage.EXPLAIN, emptyList(),
                        listOf("No thanks" to { com.limelight.home.Usage.answer(this, false) },
                                "Yes, send" to { com.limelight.home.Usage.answer(this, true) }), primaryAt = 0)
        }, 1500)
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
        // back from a stream that ended with the PC going down (Quick menu, Power): drop its old state at once, and
        // after Restart wait for it to come back
        com.limelight.home.PowerResume.takeWentDown()?.let { (uuid, kind) ->
            val d = pcs[uuid] ?: binder?.getComputer(uuid)
            binder?.invalidateStateForComputer(uuid)
            if (d != null && kind == "restart") ui.postDelayed({ watch(d, sendWol = false, forMs = 240_000) }, 1500)
        }
        // back from a stream: put the ring back on the focused item once it's laid out
        ui.postDelayed({ currentFocus?.let { glide.moveTo(it) } }, 120)
    }

    override fun onPause() {
        resumed = false
        TvFinder.onChange = null
        holdMulticast(false)
        stopPolling()
        ui.removeCallbacks(tick)
        ui.removeCallbacks(groupRender)
        if (::glide.isInitialized) glide.pauseBreath()
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
        if (hasFocus) systemBars()
    }

    /**
     * The menus keep the phone's own status bar (clock, battery, notifications) and navigation, like any app; only a
     * game is full screen (its own activity). Icons dark on the light theme.
     */
    @Suppress("DEPRECATION")
    private fun systemBars() {
        // bars the colour of the app (Android 14 and older draw them black; 15+ is edge to edge and shows the app)
        window.statusBarColor = sw.bg
        window.navigationBarColor = sw.bg
        window.decorView.systemUiVisibility = if (sw.light)
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or (if (android.os.Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
        else 0
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        syncNativeRes()   // folded or unfolded: Native is now the other screen
        // a turn or any size change: rebuild, everything is laid out in code for the new shape
        overlayKind = null
        glide.detach()
        buildShell()
        render(force = true)
    }

    /** The status bar, navigation (or Samsung's taskbar) and cutout the menus sit inside, in px. */
    private fun systemBarSizes(): android.graphics.Rect {
        if (Build.VERSION.SDK_INT < 30) return android.graphics.Rect()
        val w = windowManager.currentWindowMetrics.windowInsets
        return contentInsets(w.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()),
                w.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.displayCutout()))
    }

    /**
     * How far the content keeps from each edge: all of a system bar, but only as much of the camera cutout as its own
     * margin doesn't already cover. On its side Android reserves the whole edge for a small camera hole (104 px on
     * the Fold's cover); adding the margin on top of that pushed everything far in from the left.
     */
    @android.annotation.TargetApi(30)
    private fun contentInsets(bars: android.graphics.Insets, cut: android.graphics.Insets): android.graphics.Rect {
        val m = if (::sw.isInitialized) sw.px(sw.edge) - sw.px(8) else 0
        fun side(b: Int, c: Int) = maxOf(b, c - m, 0)
        return android.graphics.Rect(side(bars.left, cut.left), side(bars.top, cut.top), side(bars.right, cut.right), side(bars.bottom, cut.bottom))
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
        override fun onInputDeviceAdded(id: Int) { buildFoot() }
        override fun onInputDeviceRemoved(id: Int) { buildFoot() }
        override fun onInputDeviceChanged(id: Int) { padName?.text = controllerName() }
    }

    /** PS5 and TV probes while the home is on screen. */
    private val tick = object : Runnable {
        override fun run() {
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
        if (memoOn) gamesMemo?.let { return it }
        val out = ArrayList<Game>()
        consoles.forEach { c ->
            val st = psStatus[c.host]
            out += Game("ps:${c.host}", st?.runningApp ?: c.name, "PS5", "ps", st?.power == Ps5Store.Power.ON && st.runningApp != null, ps = c)
        }
        xboxes.forEach { c -> out += Game("xb:${c.id}", c.name, "Xbox", "xb", false, xb = c) }
        com.limelight.home.DeviceApps.pinned(this).forEach { a -> out += Game("dev:${a.pkg}", a.label, "Android", a.pkg, false, dev = a) }
        pcs.values.filter { paired(it) }.forEach { pc ->
            val hidden = hiddenIds(pc)
            (apps[pc.uuid] ?: emptyList()).filter { it.appId !in hidden }.forEach { a ->
                out += Game("pc:${pc.uuid}:${a.appId}", a.appName, "PC", a.appName, a.appId == pc.runningGameId, pc = pc, app = a)
            }
        }
        if (memoOn) gamesMemo = out
        return out
    }

    /**
     * Home's row: what's running, the last few played, then every console and every app you added (they always keep
     * their place: with only the last-played first, ten PC games played pushed them off Home), then PC games to fill
     * up to ten, before "All games".
     */
    private fun homeGames(): List<Game> {
        val all = allGames()
        val byKey = all.associateBy { it.key }
        val recent = Recents.list(this).mapNotNull { byKey[it] }
        val ordered = LinkedHashMap<String, Game>()
        all.filter { it.running }.forEach { ordered[it.key] = it }
        recent.take(5).forEach { ordered[it.key] = it }
        all.filter { it.ps != null || it.xb != null }.forEach { ordered[it.key] = it }
        all.filter { it.dev != null }.forEach { ordered[it.key] = it }
        val kept = ordered.size
        recent.forEach { ordered[it.key] = it }
        all.filter { it.pc != null }.sortedBy { it.title.lowercase() }.forEach { ordered[it.key] = it }
        return ordered.values.take(maxOf(10, kept))
    }

    private fun gameFor(key: String?): Game? = key?.let { k -> allGames().firstOrNull { it.key == k } }

    // ---------------------------------------------------------------- shell

    private fun buildShell() {
        portrait = isPortrait()
        val theme = prefs.getString("hh_theme", "dark")
        sw = Sw(this, theme == "light", portrait, Sw.pick(this, portrait, prefs.getString("hh_screen_size", "auto")), theme == "oled")
        sw.touchFirst = !hasController()
        sw.bars = systemBarSizes()
        root = FrameLayout(this).apply { setBackgroundColor(sw.bg) }
        wash = Wash(this).apply { still = prefs.getString("hh_backdrop", "game") == "static" }
        root.addView(wash, FrameLayout.LayoutParams(-1, -1))
        main = sw.col().apply { clipChildren = false }

        topBar = sw.row()
        val side = sw.edge
        main.addView(topBar, sw.lp(-1, 96).apply { leftMargin = sw.px(side); rightMargin = sw.px(side); topMargin = sw.px(if (sw.compact) 24 else 44) })

        content = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        main.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        foot = sw.row()
        // the same hint bar upright and on its side (portrait used to have a dock of its own)
        foot.background = sw.topLine()
        main.addView(foot, sw.lp(-1, if (sw.compact) 100 else 120).apply { leftMargin = sw.px(side); rightMargin = sw.px(side) })

        root.addView(main, FrameLayout.LayoutParams(-1, -1))
        overlay = FrameLayout(this).apply { visibility = View.GONE; isClickable = true }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        // drawn edge to edge (target SDK 36): keep everything clear of the status bar, the navigation and any cutout
        // the background and the colour wash go edge to edge, behind the bars and the cutout; the content and the
        // dialogs stay inside them (on its side the cutout and the navigation are at the sides)
        root.setOnApplyWindowInsetsListener { _, ins ->
            val pad = if (android.os.Build.VERSION.SDK_INT >= 30) {
                val r = contentInsets(ins.getInsets(android.view.WindowInsets.Type.systemBars()), ins.getInsets(android.view.WindowInsets.Type.displayCutout()))
                intArrayOf(r.left, r.top, r.right, r.bottom)
            } else @Suppress("DEPRECATION") intArrayOf(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            main.setPadding(pad[0], pad[1], pad[2], pad[3])
            overlay.setPadding(pad[0], pad[1], pad[2], pad[3])
            ins
        }
        systemBars()

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
                when {
                    v.getTag(R.id.hh_pill) == true -> 9999f
                    t != null && (t.startsWith("rb:") || t.startsWith("pill:") || t.startsWith("tab:") || t.startsWith("dock:") || t.startsWith("chip:")) -> 9999f
                    t != null && t.startsWith("app:") -> sw.pxf(13 + 22)
                    t != null && t.startsWith("group:") -> sw.pxf(13 + 16)
                    t != null && t.startsWith("g:") -> sw.pxf(13 + sw.tileCorner)
                    else -> null
                }
            }
            accept = { v -> v !is EditText }
            breathe = true
        }.attach()
        // once per window: buildShell runs again on every rotation or fold, and each run used to add another pair
        if (!treeHooked) {
            treeHooked = true
            window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, n -> onFocus(n) }
            window.decorView.viewTreeObserver.addOnScrollChangedListener { placeName() }
        }
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
        Profile.syncPcSettings(this)
        val sig = signature()
        if (!force && sig == lastSig) return
        gamesMemo = null; memoOn = true
        try { renderPage(sig) } finally { memoOn = false; gamesMemo = null }
    }

    /** allGames() is asked for several times per render (Home's row, the wash, the selected game): work it out once. */
    private var gamesMemo: List<Game>? = null
    private var memoOn = false

    private fun renderPage(sig: String) {
        lastSig = sig
        val entering = lastPage != page
        lastPage = page
        val focusTag = currentFocus?.tag as? String
        stagger = ArrayList()
        rbLabels.clear()
        gname = null; gnameBox = null; gnameTarget = null
        buildTop()
        buildFoot()
        content.removeAllViews()
        primaryView = null
        val pg = when (page) {
            Page.HOME -> homeLandscape()   // one Home for both ways round, fitted to the screen
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
                background = sw.round(sw.panel, 42)
                setPadding(sw.px(28), 0, sw.px(34), 0)
                addView(View(this@HandheldHomeActivity).apply { background = sw.round(0, 5, sw.ink, 3) }, sw.lp(32, 22).apply { rightMargin = sw.px(14) })
                val tv = TvFinder.target(this@HandheldHomeActivity)
                addView(sw.text(when {
                    tv != null -> "Playing on ${tv.name}" + if (TvFinder.isHere(tv)) "" else " (not found)"
                    portrait -> "This ${deviceShort()}"
                    else -> "Playing on this ${deviceShort()}"
                }, 26, sw.ink, 700).apply { maxWidth = resources.displayMetrics.widthPixels / 2 })   // a long TV name ends in …
                setOnClickListener { playWhere(null) }
                isFocusable = false   // a tap target only; the pad starts on the games
                isSoundEffectsEnabled = !FeelSounds.enabled(this@HandheldHomeActivity)
            }, sw.lp(-2, 84))
        } else {
            // a back button you can tap: a touch screen has no B (the same as B / the system back)
            topBar.addView(roundButton("top:back", "back", sw.ink, 84, sw.panel) { onBackPressed() }.apply { isFocusable = false; contentDescription = "Back" },
                    LinearLayout.LayoutParams(sw.tap(84), sw.tap(84)).apply { rightMargin = sw.px(24) })
            topBar.addView(sw.text(page.title, 44, sw.ink, 800))
        }
        topBar.addView(sw.grow())
        // the clock and battery are the phone's own status bar now
        part(topBar)
    }

    /** MoonCast's logo at the start of Home's top bar (it was a circle with the player's initial). */
    private fun avatar(): View = ImageView(this).apply {
        setImageResource(R.drawable.mc_logo)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "MoonCast"
    }

    private fun buildFoot() {
        foot.removeAllViews()
        padName = null
        foot.gravity = Gravity.CENTER_VERTICAL
        foot.addView(Glyph(this, "pad", sw.sub), sw.lp(74, 38).apply { rightMargin = sw.px(14) })
        padName = sw.text(controllerName(), 28, sw.sub, 700)
        foot.addView(padName)
        foot.addView(sw.grow())
        // button hints mean nothing without buttons: touch only (no controller) shows none
        if (!hasController()) { part(foot); return }
        val h = if (overlayKind == "picker") listOf("B" to "Done", "A" to "Add or remove")
                else if (overlayKind != null) listOf("B" to "Cancel", "A" to "OK") else when (page) {
            Page.HOME -> listOf("Y" to (if (TvFinder.target(this) != null) "Play here" else "Play on TV"), "A" to "Start")
            Page.GAMES -> listOf("L R" to "Tabs", "X" to (if (sortAz) "Recent first" else "A to Z"), "B" to "Back", "A" to "Start")
            Page.CONSOLES -> listOf("B" to "Back", "A" to "Open")
            Page.SETTINGS -> listOf("B" to "Back", "A" to "OK")
        }
        h.forEach { (k, l) ->
            foot.addView((if (k.length > 1) sw.row().apply {
                addView(sw.text(k, 22, sw.bg, 800).apply { gravity = Gravity.CENTER; background = sw.round(sw.ink, 23); setPadding(sw.px(14), 0, sw.px(14), 0) }, sw.lp(-2, 46).apply { rightMargin = sw.px(14) })
                addView(sw.text(l, 28, sw.ink, 700))
            } else sw.hint(k, l)).apply {
                // a tap on a hint does what its button does
                setOnClickListener { pressButton(k) }
                isFocusable = false
            }, sw.lp(-2, -2).apply { leftMargin = sw.px(40) })
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
        val e = sw.edge
        val gap = 32
        val headTop = if (sw.compact) 28 else 80
        // the round buttons: two rows of three upright, one row on its side
        val rbGap = 48
        val roundsTop = if (portrait) 64 else if (sw.compact) 72 else 80
        val roundsH = if (portrait) 110 * 2 + rbGap else 180
        // the height left for tiles after the top bar, the bottom bar, the name line and the round buttons
        val freeH = sw.designHeight - ((if (sw.compact) 24 else 44) + 96) - (if (sw.compact) 100 else 120) -
                (headTop + 48 + 20) - (roundsTop + roundsH) - 40
        val usableW = sw.designWidth - 2 * e
        // Upright the tiles fill the width, as many columns as fit about 480 wide (two on a phone, three on an open
        // foldable) and as many rows as the height allows. On its side they keep their size, and a screen tall enough
        // for two rows (an open foldable, a tablet) gets a grid instead of the scrolling row.
        var cols: Int; var rows: Int; var ts: Int
        if (portrait) {
            cols = ((usableW + gap) / (480 + gap)).toInt().coerceAtLeast(2)
            ts = ((usableW - gap * (cols - 1)) / cols).toInt()
            rows = ((freeH + gap) / (ts + gap)).toInt()
            if (rows < 2) ts = minOf(ts, (sw.designHeight * 0.34f).toInt(), freeH.toInt()).coerceAtLeast(240)
        } else {
            ts = if (sw.compact) 350 else 380
            rows = ((freeH + gap) / (ts + gap)).toInt()
            cols = ((usableW + gap) / (ts + gap)).toInt()
            if (rows >= 2) ts = minOf(((usableW - gap * (cols - 1)) / cols).toInt(), ((freeH - gap * (rows - 1)) / rows).toInt())
        }
        val grid = rows >= 2 && cols >= 2
        // labels under every round button whenever there's room for them (compact, upright, a big screen)
        labelsShown = sw.compact || grid || !hasController()
        val gridW = cols * ts + (cols - 1) * gap
        val left = if (grid) ((sw.designWidth - gridW) / 2f).toInt() else e
        col.addView(part(box), sw.lp(-1, 48).apply { topMargin = sw.px(headTop); leftMargin = sw.px(left) })

        val tiles = ArrayList<View>()
        val shown = if (grid) games.take(cols * rows - 2) else games   // the last two cells: Add apps, All games
        if (games.isEmpty()) tiles += tileView(null, ts, 36, "g:add", "Add a console") { go(Page.CONSOLES) }
        shown.forEach { g -> tiles += tileView(g, ts, 36, "g:${g.key}", g.title) { play(g) } }
        // always on Home: the way to add this device's apps (before, it was only in All games, This <device>)
        tiles += tileView(null, ts, 36, "g:devadd", "Add apps") { pickDeviceApps() }
        tiles += tileView(null, ts, 36, "g:all", "All games") { gamesTab = "all"; go(Page.GAMES) }
        if (grid) {
            val gl = GridLayout(this).apply { columnCount = cols; clipChildren = false }
            tiles.forEachIndexed { i, t ->
                gl.addView(t, GridLayout.LayoutParams().apply {
                    width = sw.px(ts); height = sw.px(ts)
                    if (i % cols > 0) leftMargin = sw.px(gap)
                    if (i >= cols) topMargin = sw.px(gap)
                })
            }
            col.addView(part(gl), sw.lp(-2, -2).apply { topMargin = sw.px(20); leftMargin = sw.px(left) })
        } else {
            val row = sw.row().apply { clipChildren = false; setPadding(sw.px(e), 0, sw.px(e), 0) }
            tiles.forEachIndexed { i, t -> row.addView(t, sw.lp(ts, ts).apply { if (i > 0) leftMargin = sw.px(gap) }) }
            val keepX = homeScrollX
            val scroll = RowScroll(this, sw.px(e)).apply {
                if (keepX > 0) post { scrollTo(keepX, 0) }
                if (Build.VERSION.SDK_INT >= 23) setOnScrollChangeListener { _, x, _, _, _ -> homeScrollX = x }
                isHorizontalScrollBarEnabled = false; isFocusable = false; clipChildren = false; clipToPadding = false
                isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(sw.px(90))
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(row)
            }
            // room under the row for the tiles' shadow
            col.addView(part(scroll), sw.lp(-1, ts + 24).apply { topMargin = sw.px(20) })
            scroll.setPadding(0, 0, 0, sw.px(24))
        }
        // the tile you were on (or the first) is where focus starts
        primaryView = tiles.firstOrNull { it.tag == "g:$selKey" } ?: tiles.first()

        // upright: two rows of three, each label beside its button (six in a row came out cramped)
        val rounds: ViewGroup = if (portrait) GridLayout(this).apply { columnCount = 3; clipChildren = false }
                else sw.row(Gravity.TOP).apply { gravity = Gravity.CENTER_HORIZONTAL; clipChildren = false }
        val cell = (usableW / 3f).toInt()
        // where every label shows at once they're the short ones, to sit side by side
        listOf(Triple("Consoles", "consoles", Sw.C_CYAN), Triple(if (labelsShown) "Play on TV" else "Play on a TV", "tv", Sw.C_AMBER), Triple("Controllers", "pad", Sw.C_BLUE),
                Triple(if (labelsShown) "Network" else "Network test", "net", Sw.C_GREEN), Triple("Settings", "gear", sw.sub), Triple("Exit", "power", Sw.C_RED)).forEachIndexed { i, (label, icon, color) ->
            val btn = roundButton("rb:$icon", icon, color, 112, sw.panel) { roundAction(icon) }
            btn.contentDescription = label
            val lab = sw.text(label, 27, if (labelsShown) sw.label else sw.ring, 700).apply { gravity = Gravity.CENTER_HORIZONTAL; visibility = if (labelsShown) View.VISIBLE else View.INVISIBLE }
            rbLabels += btn to lab
            if (portrait) {
                lab.gravity = Gravity.CENTER_VERTICAL
                rounds.addView(sw.row().apply {
                    clipChildren = false
                    addView(btn, sw.lp(110, 110))
                    addView(lab, sw.lp(-2, -2).apply { leftMargin = sw.px(22) })
                }, GridLayout.LayoutParams().apply { width = sw.px(cell); height = sw.px(110); if (i >= 3) topMargin = sw.px(rbGap) })
            } else rounds.addView(FrameLayout(this).apply {
                clipChildren = false
                addView(btn, sw.flp(112, 112))
                addView(lab, FrameLayout.LayoutParams(sw.px(420), -2, Gravity.CENTER_HORIZONTAL).apply { topMargin = sw.px(132) })
            }, sw.lp(112, 180).apply { if (i > 0) leftMargin = sw.px(if (grid) 120 else if (labelsShown) 76 else 40) })
        }
        if (portrait) col.addView(part(rounds), sw.lp(-2, -2).apply { topMargin = sw.px(roundsTop); leftMargin = sw.px(if (grid) left else e) })
        else col.addView(part(rounds), sw.lp(-1, 180).apply { topMargin = sw.px(roundsTop) })
        // with height to spare (a tall phone, few games): the spare room goes a third above and the rest below,
        // instead of one big gap over the tiles
        return BiasFrame(this, 0.3f).apply { clipChildren = false; addView(col, FrameLayout.LayoutParams(-1, -2)) }
    }

    /** Home shows every round button's label (compact, upright or a big screen), not only the focused one's. */
    private var labelsShown = false

    /** Where focus lands when a page opens (each page sets it while it builds). */
    private var primaryView: View? = null

    private fun roundAction(icon: String) {
        when (icon) {
            "consoles" -> go(Page.CONSOLES)
            "tv" -> playWhere(null)
            "pad" -> controllers()
            "net" -> networkTest()
            "gear" -> go(Page.SETTINGS)
            "power" -> { FeelSounds.play(this, FeelSounds.Cue.BACK); PadLink.stop(); finishAndRemoveTask() }
        }
    }

    private fun roundButton(tag: String, icon: String, color: Int, size: Int, fill: Int, onClick: () -> Unit): View = FrameLayout(this).apply {
        this.tag = tag
        background = sw.round(fill, size / 2)
        addView(Icon(this@HandheldHomeActivity, icon, color), sw.flp(size * 54 / 112, size * 54 / 112, Gravity.CENTER))
        sw.focusable(this)
        sw.pressable(this)
        setOnClickListener { onClick() }
    }

    private fun pill(tag: String, label: String, main: Boolean, onClick: () -> Unit): View = sw.text(label, 32, if (main) sw.bg else sw.ink, 800).apply {
        this.tag = tag
        gravity = Gravity.CENTER
        background = sw.round(if (main) sw.ink else sw.panel, 56)
        sw.focusable(this)
        sw.pressable(this)
        setOnClickListener { onClick() }
    }

    /** A square game tile: art (box art for PC games), its source, Running, and the name at the bottom. */
    private fun tileView(g: Game?, size: Int, titleSize: Int, tag: String, label: String, onClick: (View) -> Unit): FrameLayout {
        val f = FrameLayout(this)
        f.tag = tag
        f.setTag(R.id.hh_title, label)
        f.contentDescription = label
        val custom = g?.let { com.limelight.home.CustomArt.bitmap(this, it.key) }
        val appColor = if (custom != null) com.limelight.home.CustomArt.color(g!!.key, custom)
                       else g?.dev?.let { com.limelight.home.DeviceApps.color(this, it.pkg) } ?: 0
        f.setTag(R.id.hh_tint, if (appColor != 0) appColor else if (g != null) sw.tint(g.seed) else 0)
        f.clipToOutline = true
        f.outlineProvider = rounded(sw.tileCorner)
        // a soft shadow lifts the tiles off the background on Home; All games' grid goes without (one shadow per tile
        // to draw for every cover while scrolling)
        if (page != Page.GAMES) f.elevation = sw.pxf(8)
        sw.pressable(f)
        if (g == null) {
            // All games and Add apps are quiet tiles next to the games; Add a console (nothing paired yet) stands out
            f.background = when (tag) { "g:all" -> sw.allArt(); "g:devadd" -> sw.round(sw.tile, sw.tileCorner); else -> sw.art("add") }
            if (tag != "g:all") f.addView(sw.text("+", size * 0.3f, 0xFFFFFFFF.toInt(), 600).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
            // All games doesn't show the name above the grid, so Add apps carries its own
            if (tag == "g:devadd") f.addView(sw.text(label, titleSize, 0xFFFFFFFF.toInt(), 800).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; setPadding(0, 0, 0, sw.px(size * 0.08f))
            }, FrameLayout.LayoutParams(-1, -1))
        } else {
            f.background = if (appColor != 0) sw.artFrom(appColor) else sw.art(g.seed)
            val k = size / 380f
            val title = sw.text(g.title, titleSize, 0xFFFFFFFF.toInt(), 800, 3).apply {
                gravity = Gravity.BOTTOM
                setPadding(sw.px(26 * k.coerceAtMost(1.5f)), 0, sw.px(26 * k.coerceAtMost(1.5f)), sw.px(24 * k.coerceAtMost(1.5f)))
                setShadowLayer(sw.pxf(10), 0f, sw.pxf(2), 0x59000000)
            }
            if (custom != null) {
                // your own picture fills the tile, darkened at the bottom so the name stays readable
                f.addView(ImageView(this).apply { setImageBitmap(custom); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
                f.addView(View(this).apply {
                    background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                            intArrayOf(0x00000000, 0x00000000, 0xB0000000.toInt()))
                }, FrameLayout.LayoutParams(-1, -1))
                f.addView(title, FrameLayout.LayoutParams(-1, -1))
            } else if (g.pc != null && g.app != null) {
                val img = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                f.addView(img, FrameLayout.LayoutParams(-1, -1))
                f.addView(title, FrameLayout.LayoutParams(-1, -1))
                loader(g.pc).populateImageView(g.app, img, title)
            } else if (g.dev != null) {
                // an Android app, on a cover in its own colour: a big faded copy of its icon leaning in from the
                // corner, the icon itself above the middle, the name under it
                val icon = com.limelight.home.DeviceApps.icon(this, g.dev.pkg)
                if (icon != null) {
                    f.addView(ImageView(this).apply {
                        setImageDrawable(icon.constantState?.newDrawable()?.mutate() ?: icon)
                        scaleType = ImageView.ScaleType.FIT_CENTER; alpha = 0.16f; rotation = -14f
                    }, FrameLayout.LayoutParams(sw.px(size * 0.95f), sw.px(size * 0.95f), Gravity.END or Gravity.BOTTOM).apply {
                        rightMargin = -sw.px(size * 0.22f); bottomMargin = -sw.px(size * 0.18f) })
                    f.addView(ImageView(this).apply { setImageDrawable(icon); scaleType = ImageView.ScaleType.FIT_CENTER },
                            FrameLayout.LayoutParams(sw.px(size * 0.5f), sw.px(size * 0.5f), Gravity.CENTER_HORIZONTAL).apply { topMargin = sw.px(size * 0.15f) })
                }
                f.addView(title, FrameLayout.LayoutParams(-1, -1))
            } else f.addView(title, FrameLayout.LayoutParams(-1, -1))
            val bs = if (size >= 500) 24 else if (size >= 360) 20 else 17
            val m = if (size >= 360) 18 else 12
            f.addView(sw.badge(g.src, bs), sw.flp(-2, bs * 2).apply { leftMargin = sw.px(m); topMargin = sw.px(m) })
            if (g.running) f.addView(sw.live(bs), sw.flp(-2, bs * 2, Gravity.END).apply { rightMargin = sw.px(m); topMargin = sw.px(m) })
            run {
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
        }
    }

    private fun syncLabels(v: View?) {
        rbLabels.forEach { (b, l) ->
            if (labelsShown) l.setTextColor(if (b === v && !b.isInTouchMode) sw.ring else sw.label)   // always shown, the focused one lit
            else l.visibility = if (b === v && !b.isInTouchMode) View.VISIBLE else View.INVISIBLE
        }
        if (gname != null && gnameTarget == null) {
            // nothing focused yet (touch): name the first tile
            val first = (content.findViewWithTag<View>("g:$selKey") ?: primaryView)
            if (first != null && first.tag.toString().startsWith("g:")) { gnameTarget = first; gname?.text = first.getTag(R.id.hh_title) as? String ?: "" }
            placeName()
        }
    }

    /** Upright, moving over the Recent grid puts that game in the big tile without rebuilding the page. */
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
        val col = sw.col().apply { setPadding(sw.px(sw.edge), 0, sw.px(sw.edge), 0); clipChildren = false }
        val tabs = sw.row()
        listOf("all" to "All", "pc" to "PC", "ps" to "PlayStation", "xb" to "Xbox", "dev" to "This ${deviceShort()}").forEachIndexed { i, (k, label) ->
            val on = k == gamesTab
            tabs.addView(sw.text(label, 26, if (on) sw.bg else sw.sub, 700).apply {
                tag = "tab:$k"
                gravity = Gravity.CENTER
                setPadding(sw.px(30), 0, sw.px(30), 0)
                background = sw.round(if (on) sw.ink else sw.panel, 32)
                sw.focusable(this)
                setOnClickListener { if (gamesTab != k) { gamesTab = k; FeelSounds.play(this@HandheldHomeActivity, FeelSounds.Cue.TAB); render(force = true) } }
            }, LinearLayout.LayoutParams(-2, sw.tap(64)).apply { if (i > 0) leftMargin = sw.px(14) })
        }
        col.addView(part(tabs), LinearLayout.LayoutParams(-2, sw.tap(64)).apply { topMargin = sw.px(20) })

        val recent = Recents.list(this)
        var list = allGames().filter { g -> when (gamesTab) { "pc" -> g.pc != null; "ps" -> g.ps != null; "xb" -> g.xb != null; "dev" -> g.dev != null; else -> true } }
        list = if (sortAz) list.sortedBy { it.title.lowercase() }
               else list.sortedWith(compareByDescending<Game> { it.running }.thenBy { recent.indexOf(it.key).let { i -> if (i < 0) Int.MAX_VALUE else i } }
                       .thenBy { if (it.pc != null) 1 else 0 }.thenBy { it.title.lowercase() })
        if (list.isEmpty() && gamesTab != "dev") {
            val what = when (gamesTab) { "pc" -> "No PC games yet"; "ps" -> "No PlayStation yet"; "xb" -> "No Xbox yet"; else -> "No games yet" }
            col.addView(part(sw.text(what, 40, sw.ink, 800)), sw.lp(-2, -2).apply { topMargin = sw.px(80) })
            col.addView(part(sw.text(if (pcs.isEmpty() && consoles.isEmpty() && xboxes.isEmpty()) "Pair a PC, register a PS5 or sign in to Xbox in Consoles."
                                     else "Still loading, or nothing here. Consoles shows what's connected.", 26, sw.sub, 600, 2)),
                    sw.lp(-1, -2).apply { topMargin = sw.px(14) })
            col.addView(part(pill("pill:consoles", "Go to Consoles", true) { go(Page.CONSOLES) }.apply { primaryView = this }), sw.lp(480, 112).apply { topMargin = sw.px(40) })
            return col
        }
        val cols = if (portrait) 3 else if (sw.compact) 4 else 6
        val gap = if (portrait) 22 else 24
        val size = ((sw.designWidth - 2 * sw.edge - (cols - 1) * gap) / cols).toInt()
        val grid = GridLayout(this).apply { columnCount = cols; clipChildren = false }
        val cells = ArrayList<View>()
        // This device's tab starts with Add apps: you pick which apps show (and remove them from a tile's menu)
        if (gamesTab == "dev") cells += tileView(null, size, if (portrait) 28 else 26, "g:devadd", "Add apps") { pickDeviceApps() }
        list.forEach { g -> cells += tileView(g, size, if (portrait) 28 else 26, "g:${g.key}", g.title) { play(g) } }
        cells.forEachIndexed { i, t ->
            grid.addView(t, GridLayout.LayoutParams().apply {
                width = sw.px(size); height = sw.px(size)
                if (i % cols != 0) leftMargin = sw.px(gap)
                bottomMargin = sw.px(gap)
            })
        }
        primaryView = grid.getChildAt(0)
        // a redraw in the background (a poll, a game starting on the PC) kept you where you were, not back at the top
        val keepY = if (gamesScrollTab == gamesTab) gamesScrollY else 0
        gamesScrollTab = gamesTab
        col.addView(part(clipped(ColScroll(this, sw.px(40)).apply {
            isVerticalScrollBarEnabled = false; isFocusable = false; clipToPadding = false; clipChildren = false
            setPadding(0, sw.px(26), 0, sw.px(26))
            addView(grid)
            if (keepY > 0) post { scrollTo(0, keepY) }
            if (Build.VERSION.SDK_INT >= 23) setOnScrollChangeListener { _, _, y, _, _ -> gamesScrollY = y }
        })), LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = sw.px(20) })
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
                !online(d) && isWaking(d) -> "Waking up… it shows as online as soon as it answers"
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
        primaryView = null
        return scrollPane(list)
    }

    private fun scrollPane(list: LinearLayout): View = clipped(ColScroll(this, sw.px(40)).apply {
        isVerticalScrollBarEnabled = false; isFocusable = false; clipToPadding = false; clipChildren = false
        val side = sw.edge
        setPadding(sw.px(side), sw.px(30), sw.px(side), sw.px(30))
        addView(part(list))
    })

    /**
     * A scrolling list inside a frame that clips it at its edges. A view is clipped by its parent, and the content area
     * doesn't clip (tiles and the focus ring may spill over), so rows scrolled below the bottom drew over the hint bar
     * (Consoles' Find my Xbox, a sliver of All games' next row). The focus ring draws on its own layer.
     */
    private fun clipped(v: View): FrameLayout = FrameLayout(this).apply { clipChildren = true; addView(v, FrameLayout.LayoutParams(-1, -1)) }

    private fun groupHeader(list: LinearLayout, title: String) =
        list.addView(sw.text(title, 26, sw.sub, 800), sw.lp(-2, -2).apply { topMargin = sw.px(40); bottomMargin = sw.px(14); leftMargin = sw.px(26) })

    private fun listRow(list: LinearLayout, tag: String, icon: String, iconColor: Int, title: String, sub: String?, right: String?, onClick: () -> Unit) {
        list.addView(sw.row().apply {
            this.tag = tag
            background = sw.bottomLine()
            setPadding(sw.px(26), sw.px(if (sw.compact) 24 else 18), sw.px(26), sw.px(if (sw.compact) 24 else 18))
            minimumHeight = sw.px(if (sw.compact) 128 else 116)
            // a symbol fills its square; a short word (PC, PS) stays the size of a badge
            addView(sw.text(icon, if (icon.length == 1) 36 else 23, 0xFFFFFFFF.toInt(), 800).apply { gravity = Gravity.CENTER; background = sw.round(iconColor, ROW_ICON_R) },
                    sw.lp(ROW_ICON, ROW_ICON).apply { rightMargin = sw.px(26) })
            addView(sw.col().apply {
                addView(sw.text(title, 30, sw.ink, 700))
                if (sub != null) addView(sw.text(sub, 23, sw.sub, 600, 2), sw.lp(-2, -2).apply { topMargin = sw.px(sw.lineGap) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (right != null) addView(sw.text(right, ROW_VALUE, sw.sub, 700), sw.lp(-2, -2).apply { leftMargin = sw.px(24) })
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
                val hidden = hiddenIds(d)
                if (hidden.isNotEmpty()) opts += Opt("–", Sw.PC, "Hidden games", "${hidden.size} hidden. Show them again") { hiddenGames(d) }
                opts += Opt("↯", Sw.C_GREEN, "Test the network") { networkTest() }
                opts += Opt("PC", Sw.PC, "Unpair", "You'll pair it again with a PIN to play") { confirmUnpair(d) }
            }
        }
        val away = d.manualAddress?.let { hostPort(it) }
        opts += Opt("⌂", Sw.PC, if (away == null) "Add an address for away" else "Address for away", away ?: "A name or address that reaches this PC from the internet") { awayAddress(d) }
        opts += Opt("✕", Sw.DANGER, "Remove", "It disappears from this device") { confirmRemovePc(d) }
        dialog(d.name, pcAddresses(d), opts, listOf("Close" to {}))
    }

    private fun hostPort(a: ComputerDetails.AddressTuple) = if (a.port == NvHTTP.DEFAULT_HTTP_PORT) a.address else "${a.address}:${a.port}"

    /** Where this device looks for the PC: its home address and the one for away, and which it reached. */
    private fun pcAddresses(d: ComputerDetails): String? {
        val lines = ArrayList<String>()
        val used = d.activeAddress
        fun line(label: String, a: ComputerDetails.AddressTuple?) {
            if (a == null || lines.any { it.contains(hostPort(a)) }) return
            lines += "$label: ${hostPort(a)}" + if (used != null && used.address == a.address && used.port == a.port) " (in use)" else ""
        }
        line("At home", d.localAddress)
        line("Away", d.manualAddress)
        line("Internet", d.remoteAddress)
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /**
     * The address that reaches this PC from outside its network (a name like home.example.com, or a public IP),
     * kept next to its home address: this device tries both and uses whichever answers. Saved without trying it,
     * since at home an internet address usually can't be reached.
     */
    private fun awayAddress(d: ComputerDetails) {
        entry("Address for away", "A name or IP that reaches ${d.name} from the internet. Its home address stays too, and this device uses whichever answers. Sunshine's ports must be open to the internet, or connect a VPN to home. Leave it empty to remove it.",
                "For example home.example.com", d.manualAddress?.let { hostPort(it) } ?: "", "address") { raw ->
            val b = binder ?: return@entry "Still starting, try again in a moment."
            val tuple = if (raw.isEmpty()) null else try {
                val uri = try { java.net.URI("moonlight://$raw") } catch (e: Exception) { java.net.URI("moonlight://[$raw]") }
                ComputerDetails.AddressTuple(uri.host ?: return@entry "That isn't an address.", if (uri.port == -1) NvHTTP.DEFAULT_HTTP_PORT else uri.port)
            } catch (e: Exception) { return@entry "That isn't an address." }
            if (!b.setManualAddress(d.uuid, tuple)) return@entry "This PC isn't in the list any more."
            toast(if (tuple == null) "Removed the address for away." else "Saved. Away from home, ${d.name} is tried at $raw.")
            render(force = true)
            null
        }
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

    /** The games hidden from All games on this PC, each one shown again with a press. */
    private fun hiddenGames(d: ComputerDetails) {
        val hidden = hiddenIds(d)
        val names = (apps[d.uuid] ?: emptyList()).associate { it.appId to it.appName }
        val p = getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
        fun show(ids: Collection<Int>) {
            val set = HashSet(p.getStringSet(d.uuid, emptySet())!!); ids.forEach { set.remove(it.toString()) }
            p.edit().putStringSet(d.uuid, set).apply(); render(force = true)
        }
        // a hidden game the PC no longer lists (renamed or removed in Sunshine) still shows, by its number
        val opts = hidden.sortedBy { (names[it] ?: "~$it").lowercase() }.map { id ->
            val name = names[id] ?: "Game $id (not on the PC now)"
            Opt("+", Sw.LIVE, name, "Show it in All games again") { show(listOf(id)); toast("$name is back in All games") }
        }
        dialog("Hidden games", "On ${d.name}. Hidden games don't show in All games or on Home.", opts,
                listOf("Show all" to { show(hidden); toast("All hidden games are back") }, "Close" to {}))
    }

    /** "Add to home screen" for a PC game, a PS5 or an Xbox (an Android app has its own icon already). */
    private fun shortcutOpt(g: Game): Opt? = if (g.dev != null) null else
        Opt("⌂", Sw.C_AMBER, "Add to home screen", "An icon on this ${deviceShort()}'s home screen that starts it") { addToHomeScreen(g) }

    /**
     * Pins an icon for [g] to the launcher. It opens MoonCast with [EXTRA_PLAY_HERE], which starts the game here the
     * usual way: it waits for the PC's state, offers to wake it, and asks first when another game runs there.
     */
    private fun addToHomeScreen(g: Game) {
        if (Build.VERSION.SDK_INT < 26) { toast("Home screen icons need Android 8 or newer"); return }
        val sm = getSystemService(android.content.pm.ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) { toast("This ${deviceShort()}'s launcher doesn't take icons from apps"); return }
        val intent = Intent(this, HandheldHomeActivity::class.java)
                .setAction(Intent.ACTION_VIEW)   // a shortcut's intent needs an action
                .putExtra(EXTRA_PLAY_HERE, g.key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val where = g.pc?.name ?: g.src
        val info = android.content.pm.ShortcutInfo.Builder(this, "game:${g.key}")
                .setShortLabel(g.title.take(25))
                .setLongLabel("${g.title} ($where)".take(50))
                .setIcon(android.graphics.drawable.Icon.createWithAdaptiveBitmap(shortcutIcon(g)))
                .setIntent(intent)
                .build()
        if (!sm.requestPinShortcut(info, null)) toast("Couldn't add the icon")
    }

    /**
     * The icon: your own picture, else the box art the tile shows, else its cover with the name in the middle. An
     * adaptive icon is 108 units with the launcher's mask over the middle 72, so the name stays inside that.
     */
    private fun shortcutIcon(g: Game): android.graphics.Bitmap {
        val n = 432
        val out = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val picture = com.limelight.home.CustomArt.bitmap(this, g.key)
                ?: ((content.findViewWithTag<View>("g:${g.key}") as? ViewGroup)?.let { tile ->
                    (0 until tile.childCount).map { tile.getChildAt(it) }.filterIsInstance<ImageView>()
                            .firstNotNullOfOrNull { (it.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap }
                })
        // box art is often decoded as a hardware bitmap (graphics memory), which a software canvas can't draw: copy it
        val pic = picture?.let { if (Build.VERSION.SDK_INT >= 26 && it.config == android.graphics.Bitmap.Config.HARDWARE) it.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else it }
        if (pic != null) {
            val picture = pic
            val side = minOf(picture.width, picture.height)
            val src = android.graphics.Rect((picture.width - side) / 2, (picture.height - side) / 2, (picture.width + side) / 2, (picture.height + side) / 2)
            c.drawBitmap(picture, src, android.graphics.Rect(0, 0, n, n), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            return out
        }
        val art = sw.art(g.seed, 0); art.setBounds(0, 0, n, n); art.draw(c)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = android.graphics.Paint.Align.CENTER
            textSize = n * 0.13f
        }
        val safe = n * 0.58f   // inside the launcher's mask
        while (p.measureText(g.title) > safe && p.textSize > n * 0.06f) p.textSize -= 2f
        val text = if (p.measureText(g.title) > safe) g.title.take(10) + "…" else g.title
        c.drawText(text, n / 2f, n / 2f - (p.descent() + p.ascent()) / 2, p)
        return out
    }

    /** Change picture / Reset picture, for any tile's menu. */
    private fun pictureOpts(g: Game): List<Opt> = listOfNotNull(
            Opt("▣", Sw.C_BLUE, "Change picture", "Pick any image from this ${deviceShort()} for this tile") { pickPicture(g.key) },
            if (com.limelight.home.CustomArt.has(this, g.key)) Opt("↺", sw.panel, "Reset picture", "Back to MoonCast's own look") {
                com.limelight.home.CustomArt.clear(this, g.key); render(force = true); toast("Picture reset")
            } else null)

    /** The key of the tile whose picture is being picked (the photo picker answers in onActivityResult). */
    private var pictureFor: String? = null

    private fun pickPicture(key: String) {
        pictureFor = key
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
                else Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
        try { startActivityForResult(i, REQ_PICTURE) }
        catch (e: Exception) { toast("No photo picker on this ${deviceShort()}") }
    }

    private fun gameMenu(g: Game) {
        if (g.ps != null || g.xb != null) {
            dialog(g.title, if (g.ps != null) "A PlayStation." else "An Xbox.", listOfNotNull(
                    Opt("▶", Sw.LIVE, "Play") { play(g) }, shortcutOpt(g)) + pictureOpts(g), listOf("Close" to {}))
            return
        }
        g.dev?.let { a ->
            dialog(a.label, "An app on this ${deviceShort()}.", listOf(
                    Opt("▶", Sw.LIVE, "Open") { playHere(g) }) + pictureOpts(g) + listOf(
                    Opt("–", Sw.DANGER, "Remove from MoonCast", "It stays installed; add it again from All games, This ${deviceShort()}") {
                        com.limelight.home.DeviceApps.remove(this, a.pkg); Recents.forget(this, g.key)
                        render(force = true); toast("${a.label} removed from MoonCast")
                    }), listOf("Close" to {}))
            return
        }
        val pc = g.pc ?: return; val app = g.app ?: return
        val running = app.appId == pc.runningGameId
        dialog(app.appName, "On ${pc.name}" + (if (running) ". Running now." else "."), listOfNotNull(
                Opt("▶", Sw.LIVE, if (running) "Resume here" else "Play here") { playHere(g) },
                Opt("TV", sw.ring, "Play on a TV") { playWhere(g) },
                if (running) Opt("✕", Sw.DANGER, "Quit game", "Closes it on the PC") { quit(pc, app) } else null,
                shortcutOpt(g),
                *pictureOpts(g).toTypedArray(),
                Opt("–", Sw.PC, "Hide from All games", "Show it again from the PC's menu in Consoles, Hidden games") {
                    val p = getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                    val set = HashSet(p.getStringSet(pc.uuid, emptySet())!!); set.add(app.appId.toString())
                    p.edit().putStringSet(pc.uuid, set).apply(); render(force = true); toast("${app.appName} hidden")
                }), listOf("Close" to {}))
    }

    private var gamesScrollY = 0
    private var gamesScrollTab: String? = null
    private var homeScrollX = 0

    /** The picker's filter: "games" (what Android marks as games, the usual pick) or "all". */
    private var pickerFilter = "games"

    /**
     * "On this device": a grid of the installed apps with their icons, games first. A tap adds or removes an app at
     * once (a ✓ and a green ring mark what's added) and the grid stays as it is, so you can pick several.
     */
    /** Opens Add apps; the first time, the app list and icons load off the main thread (hundreds of apps took a moment). */
    private fun pickDeviceApps() {
        Thread {
            com.limelight.home.DeviceApps.installed(this).forEach { com.limelight.home.DeviceApps.icon(this, it.pkg) }
            runOnUiThread { if (!isFinishing && resumed) showPicker() }
        }.start()
    }

    private fun showPicker() {
        val all = com.limelight.home.DeviceApps.installed(this)
        if (all.isEmpty()) { toast("No apps found on this ${deviceShort()}"); return }
        val games = all.filter { it.isGame }
        if (games.isEmpty()) pickerFilter = "all"
        val shown = if (pickerFilter == "games") games else all
        val card = card()
        val head = sw.row(Gravity.CENTER_VERTICAL)
        head.addView(sw.text("Add apps", 40, sw.ink, 800), LinearLayout.LayoutParams(0, -2, 1f))
        val count = sw.text("", 26, Sw.LIVE, 800)
        fun recount() { val n = com.limelight.home.DeviceApps.pinned(this).size; count.text = if (n == 0) "None added yet" else "$n added" }
        recount()
        head.addView(count)
        card.addView(head, sw.lp(-1, -2).apply { leftMargin = sw.px(56); rightMargin = sw.px(56); topMargin = sw.px(44) })
        card.addView(sw.text("Tap an app to show it in MoonCast. Tap it again to take it out. Your apps stay installed.", 24, sw.sub, 600, 2),
                sw.lp(-1, -2).apply { leftMargin = sw.px(56); rightMargin = sw.px(56); topMargin = sw.px(8) })
        val chips = sw.row()
        listOf("games" to "Games (${games.size})", "all" to "All apps (${all.size})").forEachIndexed { i, (k, label) ->
            val on = k == pickerFilter
            chips.addView(sw.text(label, 24, if (on) sw.bg else sw.sub, 700).apply {
                tag = "chip:$k"; gravity = Gravity.CENTER; setPadding(sw.px(28), 0, sw.px(28), 0)
                background = sw.round(if (on) sw.ink else sw.bg, 30)
                sw.focusable(this)
                setOnClickListener {
                    if (pickerFilter != k) { pickerFilter = k; FeelSounds.play(this@HandheldHomeActivity, FeelSounds.Cue.TAB); showPicker()
                        this@HandheldHomeActivity.overlay.findViewWithTag<View>("chip:$k")?.requestFocus() }
                }
            }, LinearLayout.LayoutParams(-2, sw.tap(60)).apply { if (i > 0) leftMargin = sw.px(14) })
        }
        card.addView(chips, LinearLayout.LayoutParams(-2, sw.tap(60)).apply { leftMargin = sw.px(56); topMargin = sw.px(22) })

        // the grid: as many columns as fit cells about 210 wide in the card
        val cardW = minOf(1560f, sw.designWidth - 80)
        val gap = 18
        val cols = ((cardW - 2 * 48 + gap) / (210 + gap)).toInt().coerceIn(3, 7)
        val cell = ((cardW - 2 * 48 - (cols - 1) * gap) / cols).toInt()
        val grid = GridLayout(this).apply { columnCount = cols; clipChildren = false }
        shown.forEachIndexed { i, a ->
            val v = FrameLayout(this).apply {
                tag = "app:$i"
                clipChildren = false
                sw.focusable(this); sw.pressable(this)
            }
            val check = sw.text("✓", 26, 0xFFFFFFFF.toInt(), 800).apply { gravity = Gravity.CENTER; background = sw.round(Sw.LIVE, 22) }
            fun paint() {
                val on = com.limelight.home.DeviceApps.isPinned(this, a.pkg)
                v.background = sw.round(sw.bg, 22, if (on) Sw.LIVE else 0, if (on) 4 else 0)
                check.visibility = if (on) View.VISIBLE else View.GONE
            }
            val iconSize = (cell * 0.5f).toInt()
            com.limelight.home.DeviceApps.icon(this, a.pkg)?.let { d ->
                v.addView(ImageView(this).apply { setImageDrawable(d); scaleType = ImageView.ScaleType.FIT_CENTER },
                        FrameLayout.LayoutParams(sw.px(iconSize), sw.px(iconSize), Gravity.CENTER_HORIZONTAL).apply { topMargin = sw.px(cell * 0.12f) })
            }
            v.addView(sw.text(a.label, 21, sw.ink, 700, 2).apply {
                gravity = Gravity.CENTER_HORIZONTAL; setPadding(sw.px(10), 0, sw.px(10), 0)
            }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = sw.px(cell * 0.08f) })
            v.addView(check, sw.flp(44, 44, Gravity.END).apply { topMargin = sw.px(10); rightMargin = sw.px(10) })
            paint()
            v.setOnClickListener {
                val was = com.limelight.home.DeviceApps.isPinned(this, a.pkg)
                if (was) com.limelight.home.DeviceApps.remove(this, a.pkg) else com.limelight.home.DeviceApps.add(this, a.pkg)
                FeelSounds.play(this@HandheldHomeActivity, FeelSounds.Cue.TOGGLE)
                toast(if (was) "${a.label} removed. Tap it again to add it back" else "${a.label} added")
                paint(); recount()
            }
            grid.addView(v, GridLayout.LayoutParams().apply {
                width = sw.px(cell); height = sw.px(cell * 1.08f)
                if (i % cols != 0) leftMargin = sw.px(gap)
                bottomMargin = sw.px(gap)
            })
        }
        val gridH = (sw.designHeight - 470).coerceAtLeast(300f)
        card.addView(ColScroll(this, sw.px(30)).apply {
            isVerticalScrollBarEnabled = false; clipToPadding = false
            setPadding(0, sw.px(8), 0, sw.px(8))
            addView(grid)
        }, sw.lp(-1, gridH).apply { leftMargin = sw.px(48); rightMargin = sw.px(48); topMargin = sw.px(22) })
        // focus starts on Done: a habitual A closes the page instead of taking out the first app
        buttons(card, listOf("Done" to {}))
        showOverlay("picker", card, wide = true)
    }

    private val groupRender = Runnable { if (resumed && page == Page.SETTINGS) render(force = true) }

    // ---------------------------------------------------------------- Settings

    private val groups = listOf("handheld" to "Handheld", "picture" to "Picture", "sound" to "Sound", "pads" to "Controllers",
            "ps" to "PlayStation", "xbox" to "Xbox", "theme" to "Theme", "about" to "About")

    private fun settingsPage(): View {
        val row = sw.row(Gravity.TOP)
        val side = sw.col().apply { setPadding(sw.px(if (sw.compact) 20 else 96), sw.px(20), 0, 0) }
        groups.forEach { (k, label) ->
            val on = k == settingsGroup
            side.addView(FrameLayout(this).apply {
                tag = "group:$k"
                if (on) background = sw.round(sw.panel, 16)
                if (on) addView(View(this@HandheldHomeActivity).apply { background = sw.round(sw.ring, 3) }, sw.flp(6, -1).apply { topMargin = sw.px(18); bottomMargin = sw.px(18) })
                addView(sw.text(label, 30, if (on) sw.ring else sw.sub, 700).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(sw.px(28), 0, 0, 0) }, FrameLayout.LayoutParams(-1, -1))
                sw.focusable(this)
                // moving down the sidebar rebuilt the whole screen at every step: wait until focus rests on a group
                setOnFocusChangeListener { _, has -> if (has && settingsGroup != k) { settingsGroup = k; licencesOpen = false
                    ui.removeCallbacks(groupRender); ui.postDelayed(groupRender, 160) } }
                setOnClickListener {
                    settingsGroup = k; licencesOpen = false
                    render(force = true)
                    content.findViewWithTag<View>("settings:list")?.let { firstFocusable(it)?.requestFocus() }
                }
            }, LinearLayout.LayoutParams(-1, sw.tap(92)))
        }
        row.addView(part(side), sw.lp(if (sw.compact) 330 else 600, -2))
        val list = sw.col().apply { tag = "settings:list" }
        settingsRows(list, settingsGroup)
        row.addView(part(clipped(ColScroll(this, sw.px(40)).apply {
            isVerticalScrollBarEnabled = false; isFocusable = false; clipToPadding = false; clipChildren = false
            setPadding(sw.px(if (sw.compact) 20 else 40), sw.px(20), sw.px(sw.edge), sw.px(30))
            addView(list)
        })), LinearLayout.LayoutParams(0, -1, 1f))
        primaryView = side.findViewWithTag<View>("group:$settingsGroup")
        return row
    }

    private fun settingsRows(list: LinearLayout, group: String) {
        when (group) {
            "handheld" -> {
                // Auto picks Compact on a small or square screen; the others force a size (recreate redraws the app)
                choice(list, prefs, "hh_screen_size", "Screen size", "Auto fits this screen. Compact and Large make everything bigger",
                        listOf("Auto" to "auto", "Compact" to "compact", "Normal" to "normal", "Large" to "large"), "auto") { recreate() }
                appToggle(list, "hh_rotate", "Screen turns with the device", "Portrait when you hold it upright", true) { applyRotation() }
                action(list, "set:devapps", "Apps on this ${deviceShort()}", "Pick Android apps to show next to your games (emulators, Steam Link…)",
                        com.limelight.home.DeviceApps.pinned(this).size.let { if (it == 0) "None" else "$it" }) { pickDeviceApps() }
                // PC games: what a finger on the picture does
                choice(list, stream, com.limelight.Game.TOUCH_DEFAULT_PREF, "Touch screen", "In PC games. Touch clicks where you tap, Mouse is a trackpad, Off ignores the screen",
                        listOf("Touch" to "touch", "Mouse" to "mouse", "Off" to "off"), "touch")
                appToggle(list, "hh_dim_on_tv", "Dim the screen on a TV", "While this ${deviceShort()} is the controller for a TV", true)
                toggle(list, "checkbox_ultra_low_latency", "Ultra low latency", "Experimental. This device: ${chipName()}", false)
            }
            "picture" -> {
                // Home and Away: one switch for PC, PlayStation and Xbox. Each profile keeps its own settings.
                choice(list, stream, Profile.PREF, "Streaming profile", "Home: best quality on your network. Away: lighter settings for PlayStation Network and slower links",
                        listOf("Home" to "home", "Away" to "away"), "home")
                syncNativeRes()
                // Native is this screen's own size and shape (no bars), whichever screen a stream starts on
                // (a foldable's cover or inner screen)
                val native = nativeResolution()
                val fold = PreferenceConfiguration.foldableResolution(this) != null
                choice(list, stream, "list_resolution", "Resolution",
                        if (fold) "Native suits both your screens: ${native.replace("x", " × ")}. Picture shape sets how it fills each one"
                        else "Native fills the screen you play on, now ${native.replace("x", " × ")}",
                        RES_STEPS.filter { it.second != native } + ("Native" to native), native) { v ->
                    stream.edit().putBoolean(PreferenceConfiguration.NATIVE_RES_PREF_STRING, v == native).apply()
                }
                // Unlock all frame rates (Moonlight's checkbox_unlock_fps): 144 and 240 too, and Custom up to 240
                val unlock = stream.getBoolean("checkbox_unlock_fps", false)
                val fpsSteps = listOf("30", "60", "90", "120") + (if (unlock) listOf("144", "240") else emptyList())
                val fpsMax = if (unlock) 240 else 120
                choice(list, stream, "list_fps", "Frame rate", "60 is the smoothest. 30 uses less data and battery", fpsSteps.map { it to it }, "60")
                // #42: any PC frame rate, saved straight into list_fps as a number
                val fps = stream.getString("list_fps", "60") ?: "60"
                val customFps = fps !in fpsSteps
                action(list, "list_fps-custom", "Custom frame rate", "Any rate between the steps above, 15 to $fpsMax fps", if (customFps) "$fps fps" else "Off") {
                    entry("Custom frame rate", "In fps, from 15 to $fpsMax. The PC streams at the rate you pick.", "For example 45", if (customFps) fps else "", true) { raw ->
                        val v = raw.trim().toIntOrNull()
                        if (v == null || v !in 15..fpsMax) "A number from 15 to $fpsMax."
                        else { stream.edit().putString("list_fps", v.toString()).apply(); render(force = true); null }
                    }
                }
                flip(list, stream, "checkbox_unlock_fps", "Unlock all frame rates", "Adds 144 and 240, and Custom up to 240, for PCs and screens that can do it", false) {
                    // locked again: a rate above 120 comes back down to 120
                    if (!stream.getBoolean("checkbox_unlock_fps", false) && ((stream.getString("list_fps", "60") ?: "60").toIntOrNull() ?: 60) > 120)
                        stream.edit().putString("list_fps", "120").apply()
                }
                bitrate(list)
                // any bitrate, like Moonlight's slider: 0.5 to 150 Mbps in 0.5 steps
                val kbps = stream.getInt("seekbar_bitrate_kbps", 0)
                val customRate = kbps != 0 && (kbps / 1000 !in BITRATE_STEPS || kbps % 1000 != 0)
                action(list, "seekbar_bitrate_kbps-custom", "Custom bitrate", "Any bitrate from 0.5 to 150 Mbps", if (customRate) mbps(kbps) else "Off") {
                    entry("Custom bitrate", "In Mbps, from 0.5 to 150. Higher looks better and needs a faster network.", "For example 25",
                            if (customRate) mbps(kbps).removeSuffix(" Mbps") else "", "decimal") { raw ->
                        val v = raw.trim().replace(',', '.').toDoubleOrNull()
                        if (v == null || v < 0.5 || v > 150) "A number from 0.5 to 150."
                        else { stream.edit().putInt("seekbar_bitrate_kbps", (Math.round(v * 2) * 500).toInt()).apply(); render(force = true); null }
                    }
                }
                // Super resolution: AMD FSR 1.0 upscales a stream smaller than the screen, then sharpens it (PS5 for now)
                choice(list, stream, com.limelight.binding.video.FsrRenderer.PREF, "Super resolution",
                        "Sharper picture when the stream is smaller than this screen (AMD FSR 1.0), on PC, PS5 and Xbox. Not with HDR. Uses a little of the GPU",
                        listOf("Off" to "off", "FSR 1.0" to "fsr1"), "off")
                if (stream.getString(com.limelight.binding.video.FsrRenderer.PREF, "off") == "fsr1")
                    intChoice(list, com.limelight.binding.video.FsrRenderer.SHARPNESS_PREF, "Sharpening", "How crisp FSR makes edges. Lower it if you see halos",
                            listOf(0, 20, 40, 60, 80, 100), com.limelight.binding.video.FsrRenderer.DEFAULT_SHARPNESS) { "$it%" }
                choice(list, stream, "video_format", "Video codec", "HEVC and AV1 look better at the same bitrate",
                        listOf("Auto" to "auto", "H.264" to "neverh265", "HEVC" to "forceh265", "AV1" to "forceav1"), "auto")
                toggle(list, "checkbox_enable_hdr", "HDR", "Only when the PC, the game and this screen support it", false)
                toggle(list, com.limelight.home.Yuv444.PREF, "YUV 4:4:4 (HEVC)",
                        if (com.limelight.home.Yuv444.deviceCan()) "Sharper text and colour edges. Needs Sunshine with 4:4:4 on the PC; otherwise it stays 4:2:0"
                        else "This device's decoder can't show 4:4:4, so streams stay 4:2:0", false)
                toggle(list, "checkbox_full_range", "Full range colour", "Experimental: deeper blacks and brighter whites. On screens that don't handle it, dark and light detail is lost", false)
                choice(list, stream, "list_video_scale_mode", "Picture shape", "Fit with bars, fill and crop, or stretch",
                        listOf("Fit" to "fit", "Fill" to "fill", "Stretch" to "stretch"), if (stream.getBoolean("checkbox_stretch_video", false)) "stretch" else "fit")
                choice(list, stream, "frame_pacing", "Frame pacing", "PC and PS5: trade a little delay for smoother motion. Xbox paces itself",
                        listOf("Lowest delay" to "latency", "Balanced" to "balanced", "Smoothest" to "smoothness"), "latency")
                com.limelight.home.StatsLevel.get(this)   // an old on/off answer becomes Off or Detailed first
                choice(list, stream, com.limelight.home.StatsLevel.PREF, "Stats while playing",
                        "Lite: one row of numbers at the top. Detailed: every number, in a corner",
                        listOf("Off" to "off", "Lite" to "lite", "Detailed" to "detailed"), "off") { com.limelight.home.StatsLevel.set(this, it) }
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
                // the on-screen controller for PC games (Moonlight's engine, drawn by PadSkin)
                val osc = if (!stream.getBoolean("checkbox_show_onscreen_controls", false)) "off"
                        else stream.getString(com.limelight.binding.input.virtual_controller.PadSkin.PREF, "classic") ?: "classic"
                srow(list, "osc", "On-screen controller", "Touch keys over PC games, with a light tick on each press", value(when (osc) {
                        "ps" -> "PlayStation"; "xbox" -> "Xbox"; "classic" -> "Classic"; else -> "Off" })) {
                    val next = when (osc) { "off" -> "ps"; "ps" -> "xbox"; "xbox" -> "classic"; else -> "off" }
                    stream.edit().putBoolean("checkbox_show_onscreen_controls", next != "off")
                            .putString(com.limelight.binding.input.virtual_controller.PadSkin.PREF, if (next == "off") osc else next).apply()
                    render(force = true)
                }
                if (osc == "ps" || osc == "xbox") intChoice(list, com.limelight.binding.input.virtual_controller.PadSkin.OPACITY_PREF, "Controller transparency",
                        "How much of the game shows through the keys", listOf(50, 30, 15, 70, 85, 100),
                        com.limelight.binding.input.virtual_controller.PadSkin.DEFAULT_OPACITY) { "${100 - it}%" }
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
                // Same four rows for each profile; the General profile decides which set they edit. Auto shows both sets.
                val mode = Profile.mode(this)
                if (mode != "away") {
                    choice(list, stream, "ps5_resolution", "Resolution", "The PS5 streams up to 1080p", listOf("720p" to "720", "1080p" to "1080"), "1080")
                    choice(list, stream, "ps5_fps_home", "Frame rate", "60 fps is smoother; 30 fps lets a weak link keep up",
                            listOf("60 fps" to "60", "30 fps" to "30"), "60")   // PS5: 30 or 60 only (#39)
                    choice(list, stream, "ps5_bitrate_home", "Bitrate", "Auto: 30 Mbps on fast Wi-Fi, 20 otherwise",
                            listOf("Auto" to "auto", "10" to "10", "15" to "15", "20" to "20", "30" to "30", "Custom" to "custom"), "auto",
                            onPick = { if (it == "custom") customDialog(stream, "ps5_bitrate_home", "Custom bitrate", "Mbps", 2, 50, 20) })
                    if (stream.getString("ps5_bitrate_home", "auto") == "custom")
                        customNumber(list, stream, "ps5_bitrate_home", "Custom bitrate", "2 to 50 Mbps", "Mbps", 2, 50, 20)
                    choice(list, stream, "ps5_codec", "Video codec", "HEVC looks better at the same bitrate", listOf("H.264" to "h264", "HEVC" to "h265"), "h265")
                }
                if (mode != "home") {
                    choice(list, stream, "ps5_away_quality", "Resolution", "Auto follows the Wi-Fi: 1080p strong, 720p weak",
                            listOf("Auto" to "auto", "540p" to "540", "720p" to "720", "1080p" to "1080"), "auto")
                    choice(list, stream, "away_fps", "Frame rate", "60 fps is smoother; 30 fps lets a weak link keep up",
                            listOf("60 fps" to "60", "30 fps" to "30"), "60")   // PS5: 30 or 60 only (#39)
                    choice(list, stream, "away_bitrate", "Bitrate", "Auto: capped by the Wi-Fi link",
                            listOf("Auto" to "auto", "8" to "8", "10" to "10", "15" to "15", "Custom" to "custom"), "auto",
                            onPick = { if (it == "custom") customDialog(stream, "away_bitrate", "Custom bitrate", "Mbps", 2, 50, 10) })
                    if (stream.getString("away_bitrate", "auto") == "custom")
                        customNumber(list, stream, "away_bitrate", "Custom bitrate", "2 to 50 Mbps", "Mbps", 2, 50, 10)
                    choice(list, stream, "away_codec", "Video codec", "HEVC looks better at the same bitrate", listOf("H.264" to "h264", "HEVC" to "h265"), "h265")
                }
                choice(list, stream, "ps5_on_disconnect", "When I disconnect", "What happens to the PS5 after you leave a game",
                        listOf("Ask" to "ask", "Rest mode" to "rest", "Keep it on" to "keep"), "ask")
                toggle(list, "ps5_swap_cross_circle", "Swap Cross and Circle", "For the Japanese layout", false)
                choice(list, stream, com.limelight.Ps5StreamActivity.DECODER_PREF, "Decoder", "Low latency shows each frame sooner. Ultra pushes this device's chip harder. Standard if the picture misbehaves",
                        listOf("Low latency" to "low", "Ultra low latency" to "ultra", "Standard" to "standard"), "low")
                val pin = prefs.getString("ps5_login_pin", "") ?: ""
                action(list, "pspin", "Console PIN", "Signs in for you when the PS5 asks for its 4-digit login PIN", if (pin.isEmpty()) "Not set" else "••••") {
                    entry("Console PIN", "The 4-digit PIN your PS5 asks for when it starts. Leave empty to turn it off.", "4 digits", pin, true) { v ->
                        if (v.isNotEmpty() && v.length != 4) "The PIN has 4 digits" else { prefs.edit().putString("ps5_login_pin", v).apply(); render(force = true); null }
                    } }
                info(list, "PS button", "L2 + Start")
                info(list, "Touchpad", "Select + Start")
            }
            "xbox" -> {
                if (Xbox.noProfile(this)) action(list, "xbox", "Xbox network", "Signed in, no Xbox profile. Sign out, then sign in with the account you use on your Xbox", "Sign out") {
                    confirm("Sign out of Xbox?", "This Microsoft account has no Xbox profile. Sign out to start fresh with another account.", "Sign out", yesPicked = true) {
                        Xbox.signOut(this); xboxes = emptyList(); render(force = true) } }
                else if (Xbox.signedIn(this)) action(list, "xbox", "Xbox network", "Signed in" + (Xbox.gamertag(this)?.let { " as $it" } ?: ""), "Sign out") {
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
                val cur = if (sw.light) "light" else if (sw.oled) "oled" else "dark"
                for ((key, title, sub) in listOf(Triple("dark", "Dark", "Charcoal, white type"),
                        Triple("oled", "OLED", "Pure black, for OLED screens: black pixels are off, which saves battery"),
                        Triple("light", "Light", "Light grey, dark type"))) {
                    srow(list, "theme:$key", title, sub, if (cur == key) value("On") else null) {
                        if (cur != key) { prefs.edit().putString("hh_theme", key).apply(); recreate() } }
                }
                // the background: the selected game's colour washed over it, or the theme's own plain colour
                val bd = prefs.getString("hh_backdrop", "game")
                for ((key, title, sub) in listOf(Triple("game", "Background: follows the game", "The selected game's colour washes over the background"),
                        Triple("static", "Background: static", "The theme's plain background" + if (sw.oled) " (pure black)" else ""))) {
                    srow(list, "backdrop:$key", title, sub, if (bd == key) value("On") else null) {
                        if (bd != key) { prefs.edit().putString("hh_backdrop", key).apply(); wash.still = key == "static"; render(force = true) } }
                }
            }
            "about" -> if (licencesOpen) licences(list) else {
                val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
                info(list, "MoonCast", ver ?: "")
                action(list, "about:licences", "Open-source licences",
                        "MoonCast is free software, built on Moonlight, chiaki-ng and about twenty more open-source projects", "›") {
                    licencesOpen = true; render(force = true)
                    content.findViewWithTag<View>("settings:list")?.let { firstFocusable(it)?.requestFocus() }
                }
                action(list, "about:source", "Source code", "github.com/MoonCastDev · ${com.limelight.home.Licences.SOURCE_LICENCE}", "Open") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(com.limelight.home.Licences.SOURCE))) }
                    catch (e: Exception) { toast("No browser on this device: ${com.limelight.home.Licences.SOURCE}") }
                }
                if (com.limelight.home.Usage.available)
                    toggle(list, com.limelight.home.Usage.PREF, "Anonymous usage check-in",
                            "Once a day: app version, device model, and whether you played PC, PS5 or Xbox. Nothing personal", false)
                action(list, "about:add", "Add a PC by its address", "For a PC this device doesn't find on its own", null) { addPcByAddress() }
                action(list, "about:all", "All settings", "Every option, including the rare ones", null) {
                    startActivity(Intent(this, StreamSettings::class.java)) }
            }
        }
    }

    private fun value(s: String) = sw.text(s, 28, sw.ring, 700)

    /** About, Open-source licences: each project with its licence; pressing one shows the full text. Back returns to About. */
    private var licencesOpen = false
    private fun licences(list: LinearLayout) {
        action(list, "lic:back", "‹ About", "Back to About", null) { licencesOpen = false; render(force = true) }
        var group = ""
        com.limelight.home.Licences.parts.forEach { p ->
            if (p.group != group) {
                group = p.group
                list.addView(sw.text(group.uppercase(), 22, sw.sub, 800).apply { setPadding(sw.px(26), sw.px(26), 0, sw.px(8)) })
            }
            action(list, "lic:${p.name}", p.name, p.what, p.licence) { com.limelight.home.Licences.open(this, p) }
        }
    }

    private fun srow(list: LinearLayout, key: String, title: String, sub: String?, value: View?, onClick: (() -> Unit)?) {
        list.addView(sw.row().apply {
            tag = "set:$key"
            background = sw.bottomLine()
            setPadding(sw.px(26), sw.px(if (sw.compact) 24 else 18), sw.px(26), sw.px(if (sw.compact) 24 else 18))
            minimumHeight = sw.px(if (sw.compact) 128 else 116)
            addView(sw.col().apply {
                addView(sw.text(title, 30, sw.ink, 700))
                if (sub != null) addView(sw.text(sub, 23, sw.sub, 600, 2), sw.lp(-2, -2).apply { topMargin = sw.px(sw.lineGap) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (value != null) addView(value, (value.layoutParams as? LinearLayout.LayoutParams ?: sw.lp(-2, -2)).apply { leftMargin = sw.px(24) })
            setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_DPAD_LEFT && e.action == KeyEvent.ACTION_DOWN)
                    content.findViewWithTag<View>("group:$settingsGroup")?.requestFocus() ?: false
                else false
            }
            if (onClick != null) { sw.focusable(this); setOnClickListener { onClick() } }
        }, sw.lp(-1, -2))
    }

    /** A Custom value for a choice row: tapping it asks for a number, and saving a number switches the row to Custom. */
    private fun customNumber(list: LinearLayout, store: SharedPreferences, key: String, title: String, sub: String, unit: String, min: Int, max: Int, def: Int) {
        val cur = store.getString(key + "_custom", def.toString()) ?: def.toString()
        action(list, "$key-custom", title, sub, "$cur $unit") { customDialog(store, key, title, unit, min, max, def) }
    }

    /** Asks for a Custom number and saves it; the row switches to Custom. */
    private fun customDialog(store: SharedPreferences, key: String, title: String, unit: String, min: Int, max: Int, def: Int) {
        val cur = store.getString(key + "_custom", def.toString()) ?: def.toString()
        entry(title, "$min to $max $unit", unit, cur, true) { v ->
            val n = v.trim().toIntOrNull()
            if (n == null || n !in min..max) "Enter a number from $min to $max"
            else {
                store.edit().putString(key + "_custom", n.toString()).putString(key, "custom").apply()
                render(force = true)
                null
            }
        }
    }

    private fun choice(list: LinearLayout, store: SharedPreferences, key: String, title: String, sub: String, options: List<Pair<String, String>>, def: String,
                       onPick: ((String) -> Unit)? = null) {
        val cur = store.getString(key, def)
        val label = options.firstOrNull { it.second == cur }?.first ?: cur ?: ""
        srow(list, key, title, sub, value(label)) {
            val i = options.indexOfFirst { it.second == cur }
            val next = options[(i + 1) % options.size].second
            store.edit().putString(key, next).apply()
            // what goes with the pick is saved before the page redraws (the redraw reads it: Resolution's Native)
            onPick?.invoke(next)
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

    /** 25000 -> "25 Mbps", 7500 -> "7.5 Mbps" */
    private fun mbps(kbps: Int) = (if (kbps % 1000 == 0) "${kbps / 1000}" else "%.1f".format(java.util.Locale.ROOT, kbps / 1000.0)) + " Mbps"

    private fun bitrate(list: LinearLayout) {
        val steps = listOf(0) + BITRATE_STEPS
        val kbps = stream.getInt("seekbar_bitrate_kbps", 0)
        val cur = kbps / 1000
        val custom = kbps != 0 && (cur !in BITRATE_STEPS || kbps % 1000 != 0)
        srow(list, "seekbar_bitrate_kbps", "Bitrate", "Auto picks one for the resolution", value(if (kbps == 0) "Auto" else if (custom) "Custom" else "$cur Mbps")) {
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
        if (g.dev != null) { playHere(g); return }   // an app on this device opens here (Play on TV for apps: #99)
        val tv = TvFinder.target(this)
        if (tv != null) sendToTv(g, tv) else playHere(g)
    }

    private fun playHere(g: Game) {
        when {
            g.pc != null && g.app != null -> {
                val uuid = g.pc.uuid
                if (!online(g.pc) && isWaking(g.pc)) {   // waking: start it once the PC answers
                    afterWake = (uuid ?: "") to { pcs[uuid]?.let { start(it, g.app) { Recents.touch(this, g.key) } } }
                    toast("${g.app.appName} starts once ${g.pc.name} is awake")
                    return
                }
                if (g.pc.state == ComputerDetails.State.OFFLINE) {   // only when a poll says so (UNKNOWN = not asked yet)
                    confirm("${g.pc.name} is offline", "Wake it, then start ${g.app.appName} once it's online.", "Wake it", yesPicked = true) {
                        wake(g.pc) { pcs[uuid]?.let { start(it, g.app) { Recents.touch(this, g.key) } } } }
                    return
                }
                start(g.pc, g.app) { Recents.touch(this, g.key) }
            }
            g.ps != null -> { Recents.touch(this, g.key); playPs(g.ps) }
            g.xb != null -> { Recents.touch(this, g.key); playXbox(g.xb) }
            g.dev != null -> {
                if (com.limelight.home.DeviceApps.launch(this, g.dev.pkg)) { Recents.touch(this, g.key); FeelSounds.play(this, FeelSounds.Cue.LAUNCH) }
                else toast("${g.dev.label} can't be opened. Is it still installed?")
            }
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
                    confirm("${g.pc.name} is offline", "Wake it, then send ${g.app.appName} to ${tv.name} once it's online.", "Wake it", yesPicked = true) {
                        wake(g.pc) { sendToTv(gameFor(g.key) ?: g, tv) } }
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

    /** Starts [app], asking first when another game runs there. [started] runs once it really starts (not on Cancel). */
    private fun start(pc: ComputerDetails, app: NvApp, started: () -> Unit = {}) {
        val b = binder ?: return toast(getString(R.string.error_manager_not_running))
        val other = runningApp(pc)
        if (other != null && other.appId != app.appId) {
            confirm("Quit ${other.appName}?", "${pc.name} is running ${other.appName}. Quit it and start ${app.appName}?", "Quit and start") {
                started()
                FeelSounds.play(this, FeelSounds.Cue.LAUNCH)
                ServerHelper.doQuit(this, pc, other, b) { runOnUiThread { ServerHelper.doStart(this, app, pc, b) } }
            }
            return
        }
        started()
        FeelSounds.play(this, FeelSounds.Cue.LAUNCH)
        ServerHelper.doStart(this, app, pc, b)
    }

    private fun quit(pc: ComputerDetails, app: NvApp) {
        val b = binder ?: return
        confirm("Quit ${app.appName}?", "The game closes on ${pc.name}. Anything you haven't saved is lost.", "Quit game") {
            ServerHelper.doQuit(this, pc, app, b) { runOnUiThread { b.invalidateStateForComputer(pc.uuid) } }
        }
    }

    /**
     * PCs MoonCast is waiting for (uuid -> until when, elapsed ms): woken, or just restarted from the Quick menu.
     * One Wake-on-LAN packet wasn't enough after Hibernate (test263, Saleh): pressed right away, it reached the PC while
     * Windows was still writing its memory to disk, before the network card listens for the packet, and nothing sent
     * another. So the packet now goes again every 3 s until the PC answers (up to 2 min); the service polls it every
     * 1.5 s meanwhile, the row says "Waking up…", and a game pressed meanwhile starts once the PC is up.
     */
    private val waking = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var afterWake: Pair<String, () -> Unit>? = null

    private fun wake(d: ComputerDetails, then: (() -> Unit)? = null) {
        if (d.macAddress == null || d.macAddress == "00:00:00:00:00:00") return toast(getString(R.string.wol_no_mac))
        watch(d, sendWol = true, then = then)
    }

    /** Waits up to [forMs] for [d] to answer, sending Wake-on-LAN every 3 s if [sendWol]; then runs [then]. */
    private fun watch(d: ComputerDetails, sendWol: Boolean, forMs: Long = 120_000, then: (() -> Unit)? = null) {
        val uuid = d.uuid ?: return
        if (then != null) afterWake = uuid to then
        val already = waking.containsKey(uuid)
        waking[uuid] = android.os.SystemClock.elapsedRealtime() + forMs
        binder?.invalidateStateForComputer(uuid)   // forget the old answer: it's asked again within 1.5 s
        if (already) { toast("Still waiting for ${d.name}…"); return }
        toast(if (sendWol) "Waking ${d.name}. It can take a minute; MoonCast keeps trying" else "Waiting for ${d.name} to come back…")
        render(force = true)
        Thread {
            var failed = false
            while (true) {
                val until = waking[uuid] ?: break
                if (binder?.getComputer(uuid)?.state == ComputerDetails.State.ONLINE) break
                if (android.os.SystemClock.elapsedRealtime() > until) break
                if (sendWol) try { WakeOnLanSender.sendWolPacket(binder?.getComputer(uuid) ?: d) } catch (e: Exception) { failed = true }
                try { Thread.sleep(3000) } catch (e: InterruptedException) { break }
            }
            runOnUiThread {
                waking.remove(uuid)
                val pc = pcs[uuid] ?: binder?.getComputer(uuid)
                val up = pc != null && (online(pc) || binder?.getComputer(uuid)?.state == ComputerDetails.State.ONLINE)
                val next = afterWake?.takeIf { it.first == uuid }?.second
                if (afterWake?.first == uuid) afterWake = null
                if (up) { toast("${d.name} is awake"); next?.invoke() }
                else toast(if (failed) getString(R.string.wol_fail) else "${d.name} didn't answer. Check it's on and Wake-on-LAN is on in its network card settings")
                render(force = true)
            }
        }.start()
    }

    /** True while MoonCast waits for this PC to wake or come back. */
    private fun isWaking(d: ComputerDetails) = d.uuid?.let { waking.containsKey(it) } == true

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
                    // an internet name usually doesn't answer from inside the home it points at
                    else if (pcs.isNotEmpty() && raw.any { it.isLetter() } && !raw.endsWith(".local"))
                        toast("No PC answered at $raw. To use it away from home for a PC you already have, open the PC in Consoles and choose Address for away.")
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
            REQ_PICTURE -> {
                val key = pictureFor ?: return; val uri = data?.data ?: return
                Thread {
                    val ok = com.limelight.home.CustomArt.set(this, key, uri)
                    runOnUiThread { if (ok) { render(force = true); toast("Picture changed") } else toast("That image couldn't be read") }
                }.start()
            }
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
        // the page opens inside MoonCast with no remembered Microsoft account, the code already filled in
        buttons(card, listOf("Cancel" to { com.limelight.home.XboxSignInActivity.close() }, "Open the page" to {
            url?.let { u -> startActivity(Intent(this, com.limelight.home.XboxSignInActivity::class.java)
                    .putExtra(com.limelight.home.XboxSignInActivity.EXTRA_URL, u)) }
            Unit
        }), keepOpenLast = true)
        showOverlay("xbox", card)
        Thread {
            try {
                val dc = Xbox.startSignIn()
                runOnUiThread {
                    url = Xbox.linkUrl(dc)
                    sub.text = "Open the page (here, or ${dc.url.removePrefix("https://").removePrefix("www.")} on another device), enter this code, and sign in with the account you use on your Xbox."
                    code.text = dc.userCode
                }
                val end = System.currentTimeMillis() + dc.expiresIn * 1000L
                while (System.currentTimeMillis() < end && overlayKind == "xbox") {
                    Thread.sleep(dc.interval * 1000L)
                    if (overlayKind != "xbox") return@Thread
                    if (Xbox.pollSignIn(this, dc)) {
                        com.limelight.home.XboxSignInActivity.close()
                        val list = try { Xbox.refreshConsoles(this) } catch (e: Exception) { emptyList() }
                        runOnUiThread {
                            xboxes = list
                            if (overlayKind == "xbox") hideOverlay()
                            toast(if (Xbox.unverified(this)) "Signed in, but Xbox network couldn't be reached to check the account. Try Find my Xbox in a moment"
                                  else "Signed in to Xbox" + (Xbox.gamertag(this)?.let { " as $it" } ?: "") +
                                    if (list.isEmpty()) ". No console found yet" else ". Found " + list.joinToString { it.name })
                            render(force = true)
                        }
                        return@Thread
                    }
                }
                runOnUiThread { if (overlayKind == "xbox") { sub.text = "The code expired. Cancel and try again."; code.text = "" } }
            } catch (e: Xbox.ProfileError) {
                // the account can't use Xbox: nothing is kept, and the next try starts with no account remembered
                com.limelight.home.XboxSignInActivity.close()
                runOnUiThread {
                    if (overlayKind == "xbox") { sub.text = e.message; code.text = "" }
                    render(force = true)
                }
            } catch (e: Exception) {
                runOnUiThread { if (overlayKind == "xbox") sub.text = "Sign-in didn't work: ${e.message?.take(120) ?: e.javaClass.simpleName}" }
            }
        }.start()
    }

    /** Moonlight's network test, in this app's own dialog (the old grey system one looked like another app). */
    private fun networkTest() {
        dialog("Testing the network", "Checking that nothing blocks streaming. This takes a few seconds.", emptyList(), listOf("Close" to {}))
        Thread {
            val r = ServerHelper.networkTestSummary(this)
            runOnUiThread {
                if (overlayKind != "dialog") return@runOnUiThread   // closed while it ran
                hideOverlay(false)
                dialog("Network test done", r, emptyList(), listOf("OK" to {}))
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
        background = sw.round(sw.panel, 28)
        clipToOutline = true; outlineProvider = rounded(28)
    }

    private fun optRow(o: Opt, tag: String): View = sw.row().apply {
        this.tag = tag
        background = sw.round(sw.bg, 18)
        setPadding(sw.px(26), sw.px(14), sw.px(28), sw.px(14))
        minimumHeight = sw.px(100)
        if (!o.enabled) alpha = 0.45f
        // a symbol fills its square; a short word (PC, PS, TV) stays the size of a badge
        if (o.icon != null) addView(sw.text(o.icon, if (o.icon.length == 1) 36 else 23, 0xFFFFFFFF.toInt(), 800).apply { gravity = Gravity.CENTER; background = sw.round(o.iconColor, ROW_ICON_R) },
                sw.lp(ROW_ICON, ROW_ICON).apply { rightMargin = sw.px(26) })
        addView(sw.col().apply {
            addView(sw.text(o.title, 30, sw.ink, 700))
            if (o.sub != null) addView(sw.text(o.sub, 23, sw.sub, 600, 2), sw.lp(-2, -2).apply { topMargin = sw.px(sw.lineGap) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (o.right != null) addView(sw.text(o.right, ROW_VALUE, sw.sub, 700), sw.lp(-2, -2).apply { leftMargin = sw.px(24) })
        if (o.enabled) {
            sw.focusable(this)
            setOnClickListener { val n = FeelSounds.serial; hideOverlay(false); o.onClick(); if (n == FeelSounds.serial) FeelSounds.play(this@HandheldHomeActivity, FeelSounds.Cue.SELECT) }
        }
        layoutParams = sw.lp(-1, -2).apply { leftMargin = sw.px(40); rightMargin = sw.px(40); bottomMargin = sw.px(12) }
    }

    /** A dialog's buttons along its bottom: rounded buttons side by side; the last is where focus starts. */
    /** A row of buttons; with [primary], the one at [primaryAt] (default the last) is the one A and the first focus pick. */
    private fun buttons(card: LinearLayout, list: List<Pair<String, () -> Unit>>, primary: Boolean = true, keepOpenLast: Boolean = false,
                        primaryAt: Int = list.size - 1) {
        val row = sw.row()
        list.forEachIndexed { i, (label, fn) ->
            val keep = keepOpenLast && i == list.size - 1
            row.addView(sw.text(label, 30, sw.ring, 800).apply {
                gravity = Gravity.CENTER
                tag = if (primary && i == primaryAt) "primary" else "btn:$i"
                setTag(R.id.hh_pill, true)
                background = sw.round(sw.bg, 48)
                sw.focusable(this)
                sw.pressable(this)
                setOnClickListener {
                    if (keep) { fn(); return@setOnClickListener }
                    val n = FeelSounds.serial
                    hideOverlay(false); fn()
                    if (n == FeelSounds.serial) FeelSounds.play(this@HandheldHomeActivity, FeelSounds.Cue.CLOSE)
                }
            }, LinearLayout.LayoutParams(0, sw.px(96), 1f).apply { if (i > 0) leftMargin = sw.px(20) })
        }
        card.addView(row, sw.lp(-1, 96).apply { leftMargin = sw.px(40); rightMargin = sw.px(40); topMargin = sw.px(28); bottomMargin = sw.px(40) })
    }

    private fun dialog(title: String, body: String?, opts: List<Opt>, btns: List<Pair<String, () -> Unit>>, primaryAt: Int = btns.size - 1) {
        val card = card()
        card.addView(sw.text(title, 40, sw.ink, 800, 2), sw.lp(-1, -2).apply { leftMargin = sw.px(56); rightMargin = sw.px(56); topMargin = sw.px(48); bottomMargin = sw.px(10) })
        if (body != null) card.addView(sw.text(body, 26, sw.sub, 600, 8), sw.lp(-1, -2).apply { leftMargin = sw.px(56); rightMargin = sw.px(56); bottomMargin = sw.px(26) })
        val firstOn = opts.indexOfFirst { it.enabled }
        val list = sw.col()
        opts.forEachIndexed { i, o -> list.addView(optRow(o, if (i == firstOn) "primary" else "opt:$i")) }
        // as many options as the screen has room for; past that the list scrolls, cut through the middle of a row
        // so it's plain there's more. A row is about 112 with its gap; title, text and buttons take about 440 (it was
        // 620 and 132, so a PC game's menu showed three options and hid the rest below a scroll)
        val fit = ((sw.designHeight - 440) / 112f).toInt().coerceIn(3, 8)
        if (opts.size > fit) card.addView(ColScroll(this, sw.px(30)).apply { isVerticalScrollBarEnabled = false; addView(list) }, sw.lp(-1, fit * 112 - 56))
        else card.addView(list, sw.lp(-1, -2))
        buttons(card, btns, primary = firstOn < 0, primaryAt = primaryAt)
        showOverlay("dialog", card)
    }

    /**
     * A yes/Cancel question. Cancel is picked (A presses it) unless [yesPicked]: a habitual A must not quit a game,
     * unpair or remove anything (it quit Ryujinx on 2026-10-21). Only a harmless yes, like Wake it, is picked.
     */
    private fun confirm(title: String, body: String, yes: String, yesPicked: Boolean = false, onYes: () -> Unit) =
        dialog(title, body, emptyList(), listOf("Cancel" to {}, yes to onYes), primaryAt = if (yesPicked) 1 else 0)

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
                "decimal" -> android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
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

    private fun showOverlay(kind: String, card: View, wide: Boolean = false) {
        FeelSounds.play(this, FeelSounds.Cue.OPEN)
        if (overlayKind == null) returnTag = currentFocus?.tag as? String   // where focus goes back to when it closes
        overlayKind = kind
        overlay.removeAllViews()
        overlay.setBackgroundColor(0x9E000000.toInt())
        overlay.addView(card, FrameLayout.LayoutParams(sw.px(if (wide) minOf(1560f, sw.designWidth - 80) else minOf(960f, sw.designWidth - 60)), -2, Gravity.CENTER))
        overlay.visibility = View.VISIBLE
        overlay.setOnClickListener { hideOverlay() }   // a tap outside the card closes it
        card.isClickable = true
        // clickable views are focusable by default on Android 8+; the scrim and the card must never take focus
        overlay.isFocusable = false; card.isFocusable = false
        main.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        (overlay.findViewWithTag<View>("primary") ?: firstFocusable(overlay))?.requestFocus()
        buildFoot()   // the hints say what A and B do in the dialog, not on the page under it
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
    private fun hasController(): Boolean = controllerName() != "Touch screen"

    /** A tapped hint: the same as pressing that button on the pad. */
    private fun pressButton(k: String) {
        val code = when (k) {
            "A" -> KeyEvent.KEYCODE_BUTTON_A; "B" -> KeyEvent.KEYCODE_BUTTON_B; "X" -> KeyEvent.KEYCODE_BUTTON_X
            "Y" -> KeyEvent.KEYCODE_BUTTON_Y; "L R" -> KeyEvent.KEYCODE_BUTTON_R1; else -> return
        }
        // A starts what's selected; after a tap nothing has focus, so use what the page starts on
        if (code == KeyEvent.KEYCODE_BUTTON_A && (currentFocus == null || currentFocus?.isInTouchMode == true)) {
            (if (overlayKind != null) overlay.findViewWithTag<View>("primary") else primaryView)?.performClick(); return
        }
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

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

    /** The panel's own size, landscape, even numbers: what Native used to be. */
    private fun rawNativeSize(): String {
        @Suppress("DEPRECATION") val d = windowManager.defaultDisplay
        var w: Int; var h: Int
        if (Build.VERSION.SDK_INT >= 23) { val m = d.mode; w = m.physicalWidth; h = m.physicalHeight }
        else { val p = android.graphics.Point(); @Suppress("DEPRECATION") d.getRealSize(p); w = p.x; h = p.y }
        if (h > w) { val t = w; w = h; h = t }
        return "${w and 1.inv()}x${h and 1.inv()}"
    }

    /** With Native on, the saved size is this screen's, so the settings and Moonlight's own pages show it. */
    private fun syncNativeRes(commit: Boolean = false) {
        // a foldable (both its screens seen): fill each screen from the one shared shape, unless Picture shape was set
        if (PreferenceConfiguration.foldableResolution(this) == null) PreferenceConfiguration.rememberScreen(this)
        if (PreferenceConfiguration.foldableResolution(this) != null && !stream.contains("list_video_scale_mode"))
            stream.edit().putString("list_video_scale_mode", "fill").apply()
        // A saved size that is this screen's own is Native (Settings shows it as Native): test203-211 could save it
        // with Native's switch off, when the page redrew before the pick was saved
        if (!stream.getBoolean(PreferenceConfiguration.NATIVE_RES_PREF_STRING, false) && stream.getString("list_resolution", null) == nativeResolution())
            stream.edit().putBoolean(PreferenceConfiguration.NATIVE_RES_PREF_STRING, true).apply()
        if (stream.getBoolean(PreferenceConfiguration.NATIVE_RES_PREF_STRING, false) && stream.getString("list_resolution", null) != nativeResolution())
            stream.edit().putString("list_resolution", nativeResolution()).let { if (commit) it.commit() else it.apply() }
    }

    private fun nativeResolution(): String = PreferenceConfiguration.nativeResolution(this)

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
                            val tabs = listOf("all", "pc", "ps", "xb", "dev")
                            gamesTab = tabs[(tabs.indexOf(gamesTab) + step + tabs.size) % tabs.size]
                            FeelSounds.play(this, FeelSounds.Cue.TAB, 0.3f * step); render(force = true); return true
                        }
                        Page.SETTINGS -> run {
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
            licencesOpen && page == Page.SETTINGS -> { FeelSounds.play(this, FeelSounds.Cue.BACK); licencesOpen = false; render(force = true) }
            page != Page.HOME -> { FeelSounds.play(this, FeelSounds.Cue.BACK); go(Page.HOME) }
            else -> { FeelSounds.play(this, FeelSounds.Cue.BACK); moveTaskToBack(true) }
        }
    }

    companion object {
        /** The fixed resolution steps; anything else saved was a screen's own size (Native). */
        /** Bitrate's steps in Mbps (Auto is 0); anything else saved is a Custom bitrate. */
        val BITRATE_STEPS = listOf(10, 20, 30, 50, 80)
        val RES_STEPS = listOf("720p" to "1280x720", "1080p" to "1920x1080", "1440p" to "2560x1440", "4K" to "3840x2160")

        private const val STATE_PAGE = "hh_page"
        private const val STATE_GROUP = "hh_settings_group"
        const val EXTRA_PLAY_HERE = "play_here"
        private const val REQ_PSN_REGISTER = 41
        private const val REQ_PSN = 42
        private const val REQ_PICTURE = 43
        // one look for every list row (Consoles, Settings, dialogs): the icon square, its corner, a value on the right
        private const val ROW_ICON = 72
        private const val ROW_ICON_R = 18
        private const val ROW_VALUE = 24
    }
}
