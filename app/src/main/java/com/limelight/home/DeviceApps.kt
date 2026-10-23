package com.limelight.home

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import org.json.JSONArray

/**
 * "On this device": Android apps you pick to show in MoonCast next to your PC, PS5 and Xbox games (like ES-DE's
 * Android apps). Nothing is added by itself: the user ticks apps in a list of what's installed, games first, and can
 * remove them again. Only apps with a launcher entry are listed (the manifest's <queries>, not QUERY_ALL_PACKAGES).
 */
object DeviceApps {
    class App(val pkg: String, val label: String, val isGame: Boolean)

    private const val PREF = "mc_device_apps"   // in mc_tv: a JSON array of package names, in the order added

    private fun store(c: Context) = c.getSharedPreferences("mc_tv", Context.MODE_PRIVATE)

    // Kept in memory: the home screen asks on every redraw, and each answer was a JSON parse plus a PackageManager
    // call per app. refresh() (on resume) drops what may have changed outside MoonCast: installs, updates, removals.
    private var pinnedCache: List<String>? = null
    private val infoCache = java.util.Collections.synchronizedMap(HashMap<String, App?>())
    private var installedCache: List<App>? = null
    private val iconCache = android.util.LruCache<String, Drawable>(64)

    @JvmStatic fun refresh() {
        infoCache.clear(); installedCache = null; iconCache.evictAll(); colors.clear()
    }

    /** The picked apps that are still installed, in the order they were added. */
    @JvmStatic fun pinned(c: Context): List<App> = pinnedPkgs(c).mapNotNull { info(c, it) }

    private fun pinnedPkgs(c: Context): List<String> = pinnedCache ?: try {
        val a = JSONArray(store(c).getString(PREF, "[]")); (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) { emptyList<String>() }.also { pinnedCache = it }

    @JvmStatic fun isPinned(c: Context, pkg: String) = pkg in pinnedPkgs(c)

    @JvmStatic fun add(c: Context, pkg: String) {
        val l = pinnedPkgs(c); if (pkg !in l) save(c, l + pkg)
    }

    @JvmStatic fun remove(c: Context, pkg: String) = save(c, pinnedPkgs(c) - pkg)

    private fun save(c: Context, l: List<String>) { pinnedCache = l; store(c).edit().putString(PREF, JSONArray(l).toString()).apply() }

    /** Every app with a launcher entry (phone or TV launcher), not MoonCast (any of its copies): games first, then A to Z. */
    @JvmStatic fun installed(c: Context): List<App> = installedCache ?: listInstalled(c).also { installedCache = it }

    private fun listInstalled(c: Context): List<App> {
        val pm = c.packageManager
        val pkgs = LinkedHashSet<String>()
        for (cat in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0).forEach { pkgs += it.activityInfo.packageName }
        }
        pkgs.removeAll { it == c.packageName || it.startsWith("me.mooncast.") }   // MoonCast itself, and its beta or test copy
        return pkgs.mapNotNull { info(c, it) }.sortedWith(compareByDescending<App> { it.isGame }.thenBy { it.label.lowercase() })
    }

    @JvmStatic fun info(c: Context, pkg: String): App? = infoCache.getOrPut(pkg) {
        try {
            @Suppress("DEPRECATION") val ai = c.packageManager.getApplicationInfo(pkg, 0)
            App(pkg, c.packageManager.getApplicationLabel(ai).toString(), isGame(ai))
        } catch (e: PackageManager.NameNotFoundException) { null }
    }

    @Suppress("DEPRECATION")
    private fun isGame(ai: ApplicationInfo) =
        (Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME) || (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0

    /** The app's icon; on a TV its wide banner when it has one. */
    @JvmStatic fun icon(c: Context, pkg: String, tvBanner: Boolean = false): Drawable? {
        val k = if (tvBanner) "$pkg#banner" else pkg
        iconCache.get(k)?.let { return it.constantState?.newDrawable() ?: it }
        return try {
            val pm = c.packageManager
            ((if (tvBanner) pm.getApplicationBanner(pkg) else null) ?: pm.getApplicationIcon(pkg)).also { iconCache.put(k, it) }
        } catch (e: Exception) { null }
    }

    private val colors = java.util.Collections.synchronizedMap(HashMap<String, Int>())

    /**
     * The app's own colour, from its icon: the strongest hue among its vivid pixels, toned down so white text reads
     * on it. 0 for a grey or white icon (the caller uses its usual cover then).
     */
    @JvmStatic fun color(c: Context, pkg: String): Int = colors.getOrPut(pkg) {
        val d = icon(c, pkg) ?: return@getOrPut 0
        val n = 32
        val bmp = android.graphics.Bitmap.createBitmap(n, n, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, n, n); d.draw(android.graphics.Canvas(bmp))
        val weight = FloatArray(12); val sum = Array(12) { FloatArray(3) }
        val hsv = FloatArray(3)
        for (y in 0 until n) for (x in 0 until n) {
            val px = bmp.getPixel(x, y)
            if (android.graphics.Color.alpha(px) < 200) continue
            android.graphics.Color.colorToHSV(px, hsv)
            if (hsv[1] < 0.25f || hsv[2] < 0.2f) continue   // greys, whites and near-blacks don't say much
            val bin = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
            val w = hsv[1] * hsv[2]
            weight[bin] += w
            sum[bin][0] += android.graphics.Color.red(px) * w; sum[bin][1] += android.graphics.Color.green(px) * w; sum[bin][2] += android.graphics.Color.blue(px) * w
        }
        bmp.recycle()
        val best = weight.indices.maxByOrNull { weight[it] } ?: return@getOrPut 0
        if (weight[best] < 8f) return@getOrPut 0
        val avg = android.graphics.Color.rgb((sum[best][0] / weight[best]).toInt(), (sum[best][1] / weight[best]).toInt(), (sum[best][2] / weight[best]).toInt())
        android.graphics.Color.colorToHSV(avg, hsv)
        hsv[1] = hsv[1].coerceIn(0.45f, 0.85f); hsv[2] = hsv[2].coerceIn(0.45f, 0.72f)
        android.graphics.Color.HSVToColor(hsv)
    }

    /** Opens the app; false when it can't be opened (uninstalled, or no launcher entry any more). */
    @JvmStatic fun launch(c: Context, pkg: String): Boolean {
        val pm = c.packageManager
        val i = (if (com.limelight.BuildConfig.TV_APP) pm.getLeanbackLaunchIntentForPackage(pkg) else null)
            ?: pm.getLaunchIntentForPackage(pkg) ?: pm.getLeanbackLaunchIntentForPackage(pkg) ?: return false
        return try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (e: Exception) { false }
    }
}
