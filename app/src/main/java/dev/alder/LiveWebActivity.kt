package dev.alder

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout

/**
 * A channel that streams on Twitch, in Twitch's own player on the whole screen. The player sits in a page of CAS's
 * making, as Twitch asks (it must be told the page that holds it). Back leaves. A TV with no web engine opens the
 * channel's own page outside CAS.
 */
class LiveWebActivity : Screen() {
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val channel = intent.getStringExtra("channel")?.takeIf { TWITCH_NAME.matches(it) } ?: return finish()
        val made = runCatching { WebView(this) }.getOrNull() ?: return outside(channel)
        web = made
        // Fire OS draws a web page's video on a layer behind the app's window (see showTrailer): the window and the
        // page go see-through there, so the picture shows.
        val punch = android.os.Build.MANUFACTURER.equals("Amazon", true)
        if (punch) {
            window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        made.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(if (punch) Color.TRANSPARENT else Color.BLACK)
            isFocusable = false // the remote stays with the screen: Back leaves
            isFocusableInTouchMode = false
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                // The page is Twitch's player and nothing else: no going elsewhere from it.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = request.isForMainFrame
            }
            loadDataWithBaseURL(TRAILER_REFERER, twitchPage(channel, Uri.parse(TRAILER_REFERER).host.orEmpty(), punch), "text/html", "utf-8", null)
        }
        setContentView(FrameLayout(this).apply {
            if (!punch) setBackgroundColor(Color.BLACK)
            isFocusable = true
            addView(made, FrameLayout.LayoutParams(-1, -1))
            requestFocus()
        })
    }

    private fun outside(channel: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.twitch.tv/$channel"))) }
        finish()
    }

    override fun onPause() { super.onPause(); web?.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }

    override fun onDestroy() {
        web?.let { w -> (w.parent as? FrameLayout)?.removeView(w); runCatching { w.loadUrl("about:blank"); w.stopLoading(); w.destroy() } }
        web = null
        super.onDestroy()
    }
}

/** The page that holds Twitch's player for [channel]: the player alone, filling it. [parent] is the host of the page, which Twitch checks. */
internal fun twitchPage(channel: String, parent: String, clear: Boolean): String =
    "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>html,body{margin:0;height:100%;overflow:hidden;background:${if (clear) "transparent" else "#000"}}iframe{border:0;width:100%;height:100%}</style></head>" +
        "<body><iframe src=\"https://player.twitch.tv/?channel=$channel&parent=$parent&autoplay=true&muted=false\" allow=\"autoplay; fullscreen\" allowfullscreen></iframe></body></html>"
