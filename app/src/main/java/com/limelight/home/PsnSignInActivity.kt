package com.limelight.home

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Sony's sign-in page, full screen, for this TV. Use the remote or a pad to move between fields and the TV's keyboard
 * to type. When Sony redirects back with a code, we finish the sign-in and return RESULT_OK.
 */
class PsnSignInActivity : Activity() {
    private lateinit var tv: Tv
    private var done = false
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        tv = Tv(this, getSharedPreferences("mc_tv", Context.MODE_PRIVATE).getBoolean("oled", false))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(tv.p.night) }

        val bar = tv.row().apply { setPadding(tv.px(96), tv.px(28), tv.px(96), tv.px(20)) }
        bar.addView(Crescent(this, tv.p.moon), tv.lp(28, 28).apply { rightMargin = tv.px(14) })
        bar.addView(tv.text("Sign in to PlayStation Network", 28, tv.p.moon, tv.display(600)))
        bar.addView(tv.grow())
        bar.addView(tv.text("Only once. It lets this TV add and reach your PS5.", 21, tv.p.dust, tv.ui(600)))
        root.addView(bar, tv.lp(-1, -2))

        val status = tv.text("", 26, tv.p.moon, tv.ui(700)).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        val web = WebView(this).also { this.web = it }.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // Sony's bot check rejects the embedded-browser marker ("; wv"); present as the device's own Chrome
            settings.userAgentString = settings.userAgentString.replace("; wv)", ")").replace(" Version/4.0", "")
            setBackgroundColor(0xFFFFFFFF.toInt())
            isFocusable = true; isFocusableInTouchMode = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = handle(r.url.toString(), v, status)
                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(v: WebView, url: String) = handle(url, v, status)
                override fun onPageStarted(v: WebView, url: String, f: android.graphics.Bitmap?) { handle(url, v, status) }
            }
        }
        // a fresh sign-in every time (Sony asks again anyway with prompt=always)
        CookieManager.getInstance().removeAllCookies(null)
        val body = FrameLayout(this)
        body.addView(web, FrameLayout.LayoutParams(-1, -1))
        body.addView(status, FrameLayout.LayoutParams(-1, -1))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        val hints = tv.row().apply { setPadding(tv.px(96), tv.px(14), tv.px(96), tv.px(22)) }
        hints.addView(tv.hint("A", "Select or type"))
        hints.addView(tv.grow())
        hints.addView(tv.hint("B", "Back"))
        root.addView(hints, tv.lp(-1, -2))

        setContentView(root)
        web.loadUrl(Psn.authorizeUrl(this))
        web.requestFocus()
    }

    private fun handle(url: String?, web: WebView, status: android.widget.TextView): Boolean {
        val code = Psn.codeFrom(url) ?: return false
        if (done) return true
        done = true
        if (code.isEmpty()) { setResult(RESULT_CANCELED); finish(); return true }
        web.visibility = View.INVISIBLE
        status.visibility = View.VISIBLE
        status.text = "Signing in…"
        Thread {
            val ok = try { Psn.finishSignIn(this, code); true } catch (e: Exception) { false }
            runOnUiThread {
                if (ok) { setResult(RESULT_OK); finish() }
                else { status.text = "Sign-in didn't finish. Press B and try again."; done = false }
            }
        }.start()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack() && !done) { w.goBack(); return }   // e.g. back from Sony's "forgot password"
        setResult(RESULT_CANCELED)
        super.onBackPressed()
    }
}
