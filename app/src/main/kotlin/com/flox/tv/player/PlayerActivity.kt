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
import com.flox.tv.data.Tmdb
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PlayerActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var hint: TextView
    private lateinit var failed: TextView
    private lateinit var chrome: FloxChromeClient
    private lateinit var bridge: PlayerBridge

    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val hideHint = Runnable { hint.visibility = View.GONE }
    private val watchdog = Runnable { if (!bridge.hasPlayback) fallback("no playback") }

    private var provider = Provider.VIDLOVE
    private var navMode = false
    private var centerLongPressed = false
    private var menuLongPressed = false
    private var startAt = 0

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
        startAt = intent.getIntExtra(PlayerIntent.EXTRA_START_AT, 0)
        bridge = PlayerBridge(
            this,
            PlayerBridge.Meta(
                id = id,
                type = type,
                title = intent.getStringExtra(PlayerIntent.EXTRA_TITLE).orEmpty(),
                posterPath = intent.getStringExtra(PlayerIntent.EXTRA_POSTER),
                season = intent.getIntExtra(PlayerIntent.EXTRA_SEASON, 1),
                episode = intent.getIntExtra(PlayerIntent.EXTRA_EPISODE, 1)
            ),
            onEnded = { main.post { onEnded() } }
        )

        val noShield = BuildConfig.DEBUG && getSharedPreferences("flox_debug", MODE_PRIVATE).getBoolean("noShield", false)
        AdBlock.enabled = !noShield
        val script = AdBlock.script(this)
        val docStart = !noShield && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
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
        webView.webViewClient = FloxWebViewClient(
            fallbackScript = if (docStart || noShield) null else script,
            onPageReady = ::onPageReady,
            onPlaybackFailed = { fallback("load error") }
        )
        webView.addJavascriptInterface(bridge, "FloxBridge")
        if (docStart) WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()
        load()
    }

    private fun load() {
        bridge.reset()
        exitNav()
        failed.visibility = View.GONE
        webView.visibility = View.VISIBLE
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, WATCHDOG_MS)
        val m = bridge.meta
        webView.loadUrl(provider.url(m.id, m.type, m.season, m.episode, startAt))
    }

    private fun onPageReady(view: WebView) {
        view.evaluateJavascript(AdBlock.navScript(this), null)
        if (startAt > 0) view.evaluateJavascript("window.__floxApplyStart && window.__floxApplyStart($startAt)", null)
    }

    private fun fallback(reason: String) {
        if (isFinishing || isDestroyed) return
        if (BuildConfig.DEBUG) Log.d("FloxPlayer", "fallback from ${provider.name}: $reason")
        val next = provider.next()
        if (next == null) {
            showFailed()
            return
        }
        provider = next
        startAt = maxOf(startAt, bridge.currentTime.toInt())
        showHint(getString(R.string.player_switching, next.label))
        load()
    }

    private fun switchProvider() {
        provider = provider.next() ?: Provider.entries.first()
        startAt = bridge.currentTime.toInt()
        showHint(getString(R.string.player_switching, provider.label))
        load()
    }

    private fun onEnded() {
        val m = bridge.meta
        if (m.type != MediaType.TV) {
            finish()
            return
        }
        scope.launch {
            val count = Tmdb.episodes(m.id, m.season).getOrNull()?.size ?: 0
            if (m.episode < count) {
                m.episode += 1
                startAt = 0
                showHint(getString(R.string.player_next_episode, m.season, m.episode))
                load()
            } else {
                finish()
            }
        }
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
        val isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (isCenter) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        centerLongPressed = false
                    } else if (!centerLongPressed && !navMode) {
                        centerLongPressed = true
                        enterNav()
                    }
                }
                KeyEvent.ACTION_UP -> if (!centerLongPressed) {
                    if (navMode) js("__flox.activate()") else js("__flox.key(' ','Space')")
                }
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        menuLongPressed = false
                    } else if (!menuLongPressed) {
                        menuLongPressed = true
                        switchProvider()
                    }
                }
                KeyEvent.ACTION_UP -> if (!menuLongPressed) openSettings()
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (navMode) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> js("__flox.nav('left')")
                KeyEvent.KEYCODE_DPAD_RIGHT -> js("__flox.nav('right')")
                KeyEvent.KEYCODE_DPAD_UP -> js("__flox.nav('up')")
                KeyEvent.KEYCODE_DPAD_DOWN -> js("__flox.nav('down')")
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> js("__flox.key(' ','Space')")
            }
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> js("__flox.key(' ','Space')")
            KeyEvent.KEYCODE_MEDIA_PLAY -> js("__flox.state()&&__flox.state().paused&&__flox.key(' ','Space')")
            KeyEvent.KEYCODE_MEDIA_PAUSE -> js("__flox.state()&&!__flox.state().paused&&__flox.key(' ','Space')")
            KeyEvent.KEYCODE_DPAD_LEFT -> js("__flox.key('ArrowLeft','ArrowLeft')")
            KeyEvent.KEYCODE_DPAD_RIGHT -> js("__flox.key('ArrowRight','ArrowRight')")
            KeyEvent.KEYCODE_MEDIA_REWIND -> js("__flox.seek(-30)")
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> js("__flox.seek(30)")
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> enterNav()
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun onBack() {
        when {
            chrome.hasCustomView -> chrome.hideCustomView()
            navMode -> webView.evaluateJavascript("window.__flox?__flox.closePanel():false") { result ->
                if (result != "true") exitNav()
            }
            else -> finish()
        }
    }

    private fun enterNav() {
        if (navMode) return
        navMode = true
        js("__flox.enter()")
        showHint(getString(R.string.player_nav_hint))
    }

    private fun exitNav() {
        if (!navMode) return
        navMode = false
        js("__flox.exit()")
        main.removeCallbacks(hideHint)
        hint.visibility = View.GONE
    }

    private fun openSettings() {
        if (!navMode) enterNav()
        js("__flox.clickLabel('settings|lucide-settings') || __flox.nav('down')")
    }

    private fun js(expr: String) = webView.evaluateJavascript("window.__flox&&($expr)", null)

    private fun showHint(text: String) {
        hint.text = text
        hint.visibility = View.VISIBLE
        main.removeCallbacks(hideHint)
        main.postDelayed(hideHint, HINT_MS)
    }

    private fun showFailed() {
        if (isFinishing || isDestroyed) return
        main.removeCallbacks(watchdog)
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
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        chrome.hideCustomView()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.removeJavascriptInterface("FloxBridge")
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val HINT_MS = 2500L
        const val WATCHDOG_MS = 45_000L
        const val MIN_WEBVIEW_MAJOR = 89
    }
}
