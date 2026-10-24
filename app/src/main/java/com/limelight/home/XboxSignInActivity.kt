package com.limelight.home

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * Microsoft's device-code page (microsoft.com/link, the code filled in) inside MoonCast, for the handheld's Xbox
 * sign-in. It starts with no cookies every time, so it never reuses a Microsoft account the phone's browser is signed
 * in to: after an account without an Xbox profile, the next try can pick another one (test266, Saleh). The home's code
 * card keeps polling meanwhile; once signed in it closes this page, and Close or Back returns to the card.
 */
class XboxSignInActivity : Activity() {
    companion object {
        const val EXTRA_URL = "url"
        private var open: WeakReference<XboxSignInActivity>? = null

        /** Signed in (or given up): close the page if it's open. */
        @JvmStatic fun close() { open?.get()?.let { a -> a.runOnUiThread { if (!a.isFinishing) a.finish() } } }

        /** Forget every Microsoft sign-in this app's web pages hold (also used by Sign out). */
        @JvmStatic fun clearCookies(done: (() -> Unit)? = null) {
            try {
                WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush(); done?.invoke() }
            } catch (e: Exception) { done?.invoke() }   // no WebView on this device
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        open = WeakReference(this)
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF2B2D31.toInt()) }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((20 * d).toInt(), (10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt())
        }
        bar.addView(TextView(this).apply {
            text = "Sign in to Xbox"; setTextColor(0xFFFFFFFF.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(TextView(this).apply {
            text = "Close"; setTextColor(0xFF2FD3E6.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            isFocusable = true; setOnClickListener { finish() }
        })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(0xFFFFFFFF.toInt())
            webViewClient = WebViewClient()   // every page stays in here, never in the browser
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        val url = intent.getStringExtra(EXTRA_URL) ?: "https://www.microsoft.com/link"
        // a clean start: no Microsoft account remembered from before, then the page
        CookieManager.getInstance().setAcceptCookie(true)
        clearCookies { runOnUiThread { if (!isFinishing) web.loadUrl(url) } }
    }

    override fun onDestroy() {
        if (open?.get() === this) open = null
        super.onDestroy()
    }
}
