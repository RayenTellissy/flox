package com.flox.tv.player

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.flox.tv.BuildConfig
import com.flox.tv.R
import com.flox.tv.data.MediaType

class PlayerActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var hint: TextView
    private lateinit var failed: TextView
    private lateinit var chrome: FloxChromeClient
    private lateinit var bridge: PlayerBridge

    private val main = Handler(Looper.getMainLooper())
    private val hideHint = Runnable { hint.visibility = View.GONE }
    private var focusMode = false
    private var centerLongPressed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        webView = findViewById(R.id.web_view)
        hint = findViewById(R.id.player_hint)
        failed = findViewById(R.id.player_failed)
        val warning = findViewById<TextView>(R.id.webview_warning)

        val id = intent.getIntExtra(PlayerIntent.EXTRA_ID, 0)
        val type = MediaType.from(intent.getStringExtra(PlayerIntent.EXTRA_TYPE))
        val season = intent.getIntExtra(PlayerIntent.EXTRA_SEASON, 1)
        val episode = intent.getIntExtra(PlayerIntent.EXTRA_EPISODE, 1)
        val startAt = intent.getIntExtra(PlayerIntent.EXTRA_START_AT, 0)
        bridge = PlayerBridge(
            this,
            PlayerBridge.Meta(
                id = id,
                type = type,
                title = intent.getStringExtra(PlayerIntent.EXTRA_TITLE).orEmpty(),
                posterPath = intent.getStringExtra(PlayerIntent.EXTRA_POSTER),
                season = season,
                episode = episode
            )
        )

        val script = AdBlock.script(this)
        val docStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        val major = WebViewCompat.getCurrentWebViewPackage(this)?.versionName
            ?.substringBefore('.')?.toIntOrNull()
        if (!docStart || (major != null && major < MIN_WEBVIEW_MAJOR)) warning.visibility = View.VISIBLE

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(true)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
        }
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
        webView.setBackgroundColor(0xFF000000.toInt())
        chrome = FloxChromeClient(this)
        webView.webChromeClient = chrome
        webView.webViewClient = FloxWebViewClient(if (docStart) null else script, ::showFailed)
        webView.addJavascriptInterface(bridge, "FloxBridge")
        if (docStart) WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()
        webView.loadUrl(buildUrl(id, type, season, episode, startAt))
    }

    private fun buildUrl(id: Int, type: MediaType, season: Int, episode: Int, startAt: Int): String {
        val base = if (type == MediaType.TV)
            "https://vidfast.vc/tv/$id/$season/$episode?autoPlay=true&theme=fafafa&nextButton=true&autoNext=true"
        else
            "https://vidfast.vc/movie/$id?autoPlay=true&theme=fafafa"
        return if (startAt > 0) "$base&startAt=$startAt" else base
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN) onBack()
            return true
        }
        if (focusMode) return super.dispatchKeyEvent(event)

        val isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (isCenter) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        event.startTracking()
                        centerLongPressed = false
                    } else if (!centerLongPressed) {
                        centerLongPressed = true
                        enterFocusMode()
                    }
                }
                KeyEvent.ACTION_UP -> if (!centerLongPressed) togglePlay()
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePlay()
            KeyEvent.KEYCODE_MEDIA_PLAY -> command("play")
            KeyEvent.KEYCODE_MEDIA_PAUSE -> command("pause")
            KeyEvent.KEYCODE_DPAD_LEFT -> seekBy(-10)
            KeyEvent.KEYCODE_DPAD_RIGHT -> seekBy(10)
            KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-30)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(30)
            KeyEvent.KEYCODE_MENU -> enterFocusMode()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> Unit
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun onBack() = when {
        chrome.hasCustomView -> chrome.hideCustomView()
        focusMode -> exitFocusMode()
        else -> finish()
    }

    private fun togglePlay() = command(if (bridge.playing) "pause" else "play")

    private fun command(name: String) =
        webView.evaluateJavascript("window.postMessage({command:'$name'},'*')", null)

    private fun seekBy(delta: Int) {
        val target = (bridge.currentTime + delta).coerceAtLeast(0.0).toInt()
        webView.evaluateJavascript("window.postMessage({command:'seek',time:$target},'*')", null)
    }

    private fun enterFocusMode() {
        focusMode = true
        webView.requestFocus()
        webView.evaluateJavascript("document.body&&document.body.focus()", null)
        hint.visibility = View.VISIBLE
        main.removeCallbacks(hideHint)
        main.postDelayed(hideHint, HINT_MS)
    }

    private fun exitFocusMode() {
        focusMode = false
        main.removeCallbacks(hideHint)
        hint.visibility = View.GONE
    }

    private fun showFailed() {
        if (isFinishing || isDestroyed) return
        failed.visibility = View.VISIBLE
        webView.visibility = View.INVISIBLE
        if (BuildConfig.DEBUG) Log.d("FloxPlayer", "blocked=${AdBlock.blockedCount.get()}")
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers()
    }

    override fun onResume() {
        super.onResume()
        webView.resumeTimers()
        webView.onResume()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        chrome.hideCustomView()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.removeJavascriptInterface("FloxBridge")
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val HINT_MS = 2000L
        const val MIN_WEBVIEW_MAJOR = 89
    }
}
