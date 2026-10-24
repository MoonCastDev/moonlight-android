package com.limelight.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The open-source projects MoonCast is built on, for About, Open-source licences (both apps). Each licence's full
 * text is in assets/licences; the big shared ones (GPL, AGPL, Apache) are there once. NOTICE in the repo lists the same.
 */
object Licences {
    class Part(val group: String, val name: String, val what: String, val licence: String, val file: String, val url: String)

    const val SOURCE = "https://github.com/MoonCastDev"
    const val SOURCE_LICENCE = "GPL-3.0, PS5 and Xbox parts AGPL-3.0"

    val parts = listOf(
        Part("The app", "Moonlight for Android", "The app MoonCast grew from: PC streaming, the on-screen controller", "GPL-3.0", "gpl-3.0.txt", "https://github.com/moonlight-stream/moonlight-android"),
        Part("The app", "moonlight-common-c", "The PC streaming protocol", "GPL-3.0", "gpl-3.0.txt", "https://github.com/moonlight-stream/moonlight-common-c"),
        Part("The app", "AMD FidelityFX Super Resolution 1.0", "Super resolution: the upscaling and sharpening (FSR 1)", "MIT", "fsr1.txt", "https://github.com/GPUOpen-Effects/FidelityFX-FSR"),
        Part("The app", "ENet", "Inside moonlight-common-c: the network link", "MIT", "enet.txt", "https://github.com/lsalzman/enet"),
        Part("The app", "nanors", "Inside moonlight-common-c: repairing lost video", "MIT", "nanors.txt", "https://github.com/sleepybishop/nanors"),
        Part("PS5", "chiaki-ng", "PS5 Remote Play, with MoonCast's changes (MoonCastDev/chiaki-ng-mooncast)", "AGPL-3.0", "agpl-3.0.txt", "https://github.com/streetpea/chiaki-ng"),
        Part("PS5", "OpenSSL", "Inside chiaki-ng: encryption", "Apache-2.0", "apache-2.0.txt", "https://www.openssl.org"),
        Part("PS5", "curl", "Inside chiaki-ng: PSN sign-in and play away from home", "curl", "curl.txt", "https://curl.se"),
        Part("PS5", "json-c", "Inside chiaki-ng: PSN messages", "MIT", "json-c.txt", "https://github.com/json-c/json-c"),
        Part("PS5", "miniupnpc", "Inside chiaki-ng: router port setup (UPnP)", "BSD-3-Clause", "miniupnpc.txt", "https://github.com/miniupnp/miniupnp"),
        Part("PS5", "Jerasure", "Inside chiaki-ng: repairing lost video", "BSD-3-Clause", "jerasure.txt", "https://github.com/streetpea/jerasure"),
        Part("PS5", "GF-Complete", "Inside chiaki-ng: the maths behind the repair", "BSD-3-Clause", "gf-complete.txt", "https://github.com/streetpea/gf-complete"),
        Part("PS5", "nanopb", "Inside chiaki-ng: messages with the console", "Zlib", "nanopb.txt", "https://github.com/nanopb/nanopb"),
        Part("PS5", "Oboe", "Inside chiaki-ng: sound", "Apache-2.0", "apache-2.0.txt", "https://github.com/google/oboe"),
        Part("PS5", "LLVM libc++", "The C++ runtime chiaki-ng runs on", "Apache-2.0 with LLVM exception", "libcxx.txt", "https://libcxx.llvm.org"),
        Part("Xbox", "XStreaming", "The Xbox Remote Play protocol MoonCast follows", "AGPL-3.0", "agpl-3.0.txt", "https://github.com/Geocld/XStreaming"),
        Part("Xbox", "WebRTC", "Xbox video and sound", "BSD-3-Clause", "webrtc.txt", "https://webrtc.org"),
        Part("More", "Bouncy Castle", "Pairing with a PC: its certificates", "MIT", "bouncycastle.txt", "https://www.bouncycastle.org"),
        Part("More", "OkHttp", "Talking to PCs and Xbox and PlayStation services", "Apache-2.0", "apache-2.0.txt", "https://square.github.io/okhttp"),
        Part("More", "JmDNS", "Finding PCs on the network", "Apache-2.0", "apache-2.0.txt", "https://github.com/jmdns/jmdns"),
        Part("More", "JCodec", "Reading video stream settings", "BSD-2-Clause", "jcodec.txt", "https://github.com/jcodec/jcodec"),
        Part("More", "Shield Controller Extensions", "Rumble and battery on NVIDIA Shield controllers", "MIT", "shield-controller-extensions.txt", "https://github.com/cgutman/ShieldControllerExtensions"),
        Part("More", "Figtree", "The menus' font", "OFL-1.1", "figtree.txt", "https://github.com/erikdkennedy/figtree"),
        Part("More", "Unbounded", "The headings' font", "OFL-1.1", "unbounded.txt", "https://github.com/googlefonts/unbounded"),
    )

    fun open(context: Context, part: Part) {
        context.startActivity(Intent(context, LicenceActivity::class.java).putExtra(LicenceActivity.EXTRA_NAME, part.name))
    }
}

/** One licence's full text. Scrolls with touch or a D-pad; Back closes it. */
class LicenceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val part = Licences.parts.firstOrNull { it.name == intent.getStringExtra(EXTRA_NAME) } ?: run { finish(); return }
        val text = try { assets.open("licences/" + part.file).bufferedReader().use { it.readText() } } catch (e: Exception) { "" }
        val d = resources.displayMetrics.density
        fun px(v: Int) = (v * d).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(28), px(24), px(28), px(24))
            addView(TextView(this@LicenceActivity).apply {
                this.text = part.name; textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFFFFFFFF.toInt())
            })
            addView(TextView(this@LicenceActivity).apply {
                this.text = "${part.licence}  ·  ${part.url}"; textSize = 14f; setTextColor(0xFF2FD3E6.toInt())
                setPadding(0, px(4), 0, px(16))
            })
            addView(TextView(this@LicenceActivity).apply {
                this.text = text; textSize = 13f; typeface = Typeface.MONOSPACE; setTextColor(0xFFD9DCE1.toInt())
                setLineSpacing(0f, 1.15f); setTextIsSelectable(false)
            })
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(if (getSharedPreferences("mc_tv", MODE_PRIVATE).getString("hh_theme", "dark") == "oled") 0xFF000000.toInt() else 0xFF2B2D31.toInt())
            isFocusable = true; isFocusableInTouchMode = true   // a D-pad scrolls it
            fitsSystemWindows = true
            addView(col)
            post { requestFocus() }
        })
        title = part.name
        window.decorView.textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
        col.gravity = Gravity.START
    }

    companion object {
        const val EXTRA_NAME = "name"
    }
}
