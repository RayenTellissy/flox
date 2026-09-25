package com.flox.tv.player

import android.annotation.SuppressLint
import android.app.Activity
import android.media.AudioManager
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.flox.tv.BuildConfig
import com.flox.tv.R
import com.flox.tv.data.MediaType
import com.flox.tv.data.Tmdb
import com.flox.tv.telegram.Library
import com.flox.tv.telegram.Telegram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@UnstableApi
class PlayerActivity : Activity() {
    private lateinit var webView: WebView
    private lateinit var nativeView: PlayerView
    private lateinit var native: NativePlayer
    private lateinit var controls: PlayerControls
    private lateinit var hint: TextView
    private lateinit var failed: TextView
    private lateinit var chrome: FloxChromeClient
    private lateinit var bridge: PlayerBridge

    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val hideHint = Runnable { hint.visibility = View.GONE }
    private val watchdog = Runnable { if (!bridge.hasPlayback) onLoadFailed("no playback") }

    private var retried = false
    // the page resolves the stream; playback moves to ExoPlayer unless that fails for this session
    private var nativeAllowed = true
    private var nativeShown = false
    private var captions: List<PlayerBridge.Caption> = emptyList()
    private var navMode = false
    private var centerLongPressed = false
    private var menuLongPressed = false
    private var startAt = 0
    private var episodeCount = 0
    // a library file that failed to play falls back to the page for this episode
    private var libraryFailed = false
    private var playingLibrary = false
    private var libraryEntry: Library.Entry? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        goImmersive()

        webView = findViewById(R.id.web_view)
        nativeView = findViewById(R.id.native_view)
        controls = findViewById(R.id.player_controls)
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
            onEnded = { main.post { onEnded() } },
            onManifest = { m -> main.post { onManifest(m) } },
            onCaptions = { c ->
                main.post {
                    captions = c
                    if (BuildConfig.DEBUG) Log.d("FloxPlayer", "captions ${c.size}")
                }
            }
        )

        val debugPrefs = getSharedPreferences("flox_debug", MODE_PRIVATE)
        val noShield = BuildConfig.DEBUG && debugPrefs.getBoolean("noShield", false)
        if (BuildConfig.DEBUG && debugPrefs.getBoolean("forceHevc", false)) Codecs.override = true
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
            // Providers refuse to play in "embedded browsers"; drop the WebView markers from the UA
            userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\d+(\\.\\d+)* "), "")
        }
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, true)
        native = NativePlayer(
            this,
            nativeView,
            bridge,
            userAgent = webView.settings.userAgentString,
            onFirstFrame = ::showNative,
            onFailed = ::onNativeFailed
        )
        native.onTracksChanged = { refreshTrackButtons() }
        controls.onPlayPause = { native.togglePlay() }
        controls.onSeekBy = { s -> native.seekBy(s) }
        controls.onVolume = { up -> adjustVolume(up) }
        controls.onAudio = { showAudioTracks() }
        controls.onSubtitles = { cycleSubtitles() }
        controls.onQuality = { cycleQuality() }
        Library.preferredQuality = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_QUALITY, "").orEmpty()
        controls.onNext = { playNext() }
        controls.onBack = { finish() }
        webView.setBackgroundColor(0xFF000000.toInt())
        chrome = FloxChromeClient(this)
        webView.webChromeClient = chrome
        webView.webViewClient = FloxWebViewClient(
            fallbackScript = if (docStart || noShield) null else script,
            onPageReady = ::onPageReady,
            onPlaybackFailed = { onLoadFailed("load error") }
        )
        webView.addJavascriptInterface(bridge, "FloxBridge")
        if (docStart) WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))

        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.requestFocus()
        val debugManifest = if (BuildConfig.DEBUG) intent.getStringExtra("debugManifest") else null
        if (debugManifest != null) {
            // debug builds can bypass the page and play a manifest straight away
            refreshNext()
            native.start(PlayerBridge.Manifest(debugManifest, "hls", emptyMap()), emptyList(), startAt)
        } else {
            load()
        }
    }

    private fun load() {
        bridge.reset()
        exitNav()
        native.stop()
        controls.hide()
        nativeShown = false
        captions = emptyList()
        failed.visibility = View.GONE
        refreshNext()
        webView.visibility = View.VISIBLE
        main.removeCallbacks(watchdog)
        val m = bridge.meta
        val entry = if (libraryFailed || !Telegram.ready) null
            else Library.get(m.id, m.type, if (m.type == MediaType.TV) m.season else 0, if (m.type == MediaType.TV) m.episode else 0)
        playingLibrary = entry != null
        libraryEntry = entry
        if (entry != null) {
            playLibrary(entry)
            return
        }
        main.postDelayed(watchdog, WATCHDOG_MS)
        webView.loadUrl(Provider.url(m.id, m.type, m.season, m.episode, startAt))
    }

    private fun playLibrary(entry: Library.Entry) {
        if (BuildConfig.DEBUG) Log.d("FloxPlayer", "library ${entry.key} parts=${entry.parts.size} ${entry.codec}")
        scope.launch {
            val subtitle = withContext(Dispatchers.IO) {
                entry.subtitleFileId?.let { id -> runCatching { Telegram.downloadFully(id) }.getOrNull() }
            }
            if (isFinishing || isDestroyed) return@launch
            native.startLibrary(entry, subtitle, startAt)
        }
    }

    private fun onPageReady(view: WebView) {
        view.evaluateJavascript(AdBlock.navScript(this), null)
        if (startAt > 0) view.evaluateJavascript("window.__floxApplyStart && window.__floxApplyStart($startAt)", null)
    }

    private fun onManifest(m: PlayerBridge.Manifest) {
        if (!nativeAllowed || native.active || isFinishing || isDestroyed) return
        if (BuildConfig.DEBUG) Log.d("FloxPlayer", "native ${m.kind} ${m.url.take(80)}")
        // the page may have started; carry its position over
        val at = maxOf(startAt, bridge.currentTime.toInt())
        pageVideo("pause")
        native.start(m, captions, at)
    }

    private fun pageVideo(action: String) =
        webView.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){try{v.$action()}catch(e){}})", null)

    private fun showNative() {
        if (nativeShown || isFinishing || isDestroyed) return
        nativeShown = true
        exitNav()
        // the player view sits under the page; hiding the page reveals it
        webView.visibility = View.INVISIBLE
        // the page has done its job; stop it so it does not keep decoding underneath
        webView.stopLoading()
        webView.loadUrl("about:blank")
        controls.bind(native, bridge.meta)
        refreshTrackButtons()
        controls.setQualityAvailable(libraryEntry != null && libraryVariants().size > 1)
        nativeView.requestFocus()
    }

    private fun libraryVariants(): List<Library.Entry> {
        val m = bridge.meta
        return Library.variants(m.id, m.type, if (m.type == MediaType.TV) m.season else 0, if (m.type == MediaType.TV) m.episode else 0)
    }

    /** Restarts the library file at the next uploaded print, keeping the position, and remembers the choice. */
    private fun cycleQuality() {
        val current = libraryEntry ?: return
        val all = libraryVariants()
        if (all.size < 2) return
        val next = all[(all.indexOfFirst { it.label == current.label } + 1) % all.size]
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_QUALITY, next.quality).apply()
        Library.preferredQuality = next.quality
        startAt = native.currentSeconds()
        native.stop()
        controls.hide()
        nativeShown = false
        libraryEntry = next
        showHint(getString(R.string.player_quality_fmt, next.label.uppercase()))
        playLibrary(next)
    }

    private fun refreshNext() {
        val m = bridge.meta
        controls.setNextAvailable(m.type == MediaType.TV && m.episode < episodeCount)
        if (m.type != MediaType.TV || episodeCount > 0) return
        scope.launch {
            episodeCount = Tmdb.episodes(m.id, m.season).getOrNull()?.size ?: 0
            controls.setNextAvailable(m.episode < episodeCount)
        }
    }

    private fun playNext() {
        val m = bridge.meta
        if (m.type != MediaType.TV || m.episode >= episodeCount) return
        m.episode += 1
        startAt = 0
        retried = false
        libraryFailed = false
        showHint(getString(R.string.player_next_episode, m.season, m.episode))
        load()
    }

    private fun onNativeFailed(reason: String) {
        if (isFinishing || isDestroyed) return
        if (BuildConfig.DEBUG) Log.d("FloxPlayer", "native failed: $reason")
        val at = native.currentSeconds()
        native.stop()
        if (playingLibrary) libraryFailed = true else nativeAllowed = false
        if (nativeShown) {
            // the page was unloaded; bring it back as the player
            startAt = maxOf(startAt, at)
            showHint(getString(R.string.player_reloading))
            load()
        } else {
            pageVideo("play")
        }
    }

    // one automatic reload covers transient source failures before giving up
    private fun onLoadFailed(reason: String) {
        if (isFinishing || isDestroyed) return
        if (BuildConfig.DEBUG) Log.d("FloxPlayer", "load failed: $reason")
        if (retried) {
            showFailed()
            return
        }
        retried = true
        reload()
    }

    private fun reload() {
        startAt = maxOf(startAt, if (nativeShown) native.currentSeconds() else bridge.currentTime.toInt())
        showHint(getString(R.string.player_reloading))
        load()
    }

    private fun onEnded() {
        val m = bridge.meta
        if (m.type != MediaType.TV) {
            finish()
            return
        }
        scope.launch {
            if (episodeCount == 0) episodeCount = Tmdb.episodes(m.id, m.season).getOrNull()?.size ?: 0
            if (m.episode < episodeCount) playNext() else finish()
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
        if (nativeShown) return dispatchNativeKey(event)
        val isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
        if (isCenter && failed.visibility == View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                retried = false
                reload()
            }
            return true
        }
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
                        retried = false
                        reload()
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

    private fun dispatchNativeKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) menuLongPressed = false
                else if (!menuLongPressed) { menuLongPressed = true; retried = false; reload() }
            } else if (!menuLongPressed) cycleSubtitles()
            return true
        }
        // media keys work whether or not the overlay is up
        if (event.action == KeyEvent.ACTION_DOWN) {
            val handled = when (code) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { native.togglePlay(); true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { native.play(); true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { native.pause(); true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekHidden(-30); true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekHidden(30); true }
                else -> false
            }
            if (handled) {
                if (controls.shown) controls.touch()
                return true
            }
        }
        // while the overlay is up, focus navigation owns the D-pad
        if (controls.shown) {
            controls.touch()
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { native.togglePlay(); controls.show() }
            KeyEvent.KEYCODE_DPAD_LEFT -> seekHidden(-PlayerControls.seekStep(event.repeatCount))
            KeyEvent.KEYCODE_DPAD_RIGHT -> seekHidden(PlayerControls.seekStep(event.repeatCount))
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> controls.show()
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun seekHidden(seconds: Int) {
        native.seekBy(seconds)
        showHint(getString(R.string.player_seek_fmt, if (seconds < 0) "-" else "+", kotlin.math.abs(seconds)))
    }

    /** Device volume where the box allows it; otherwise the player's own gain, which can only attenuate. */
    private fun adjustVolume(up: Boolean) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        if (am.isVolumeFixed) {
            native.volume += if (up) VOLUME_STEP else -VOLUME_STEP
            showHint(getString(R.string.player_volume_pct_fmt, Math.round(native.volume * 100)))
            return
        }
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        showHint(getString(R.string.player_volume_fmt, am.getStreamVolume(AudioManager.STREAM_MUSIC), am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)))
    }

    private fun refreshTrackButtons() {
        controls.setAudioAvailable(native.audioTracks().size > 1)
        controls.setSubtitlesAvailable(native.hasSubtitles())
    }

    private fun showAudioTracks() {
        val tracks = native.audioTracks()
        if (tracks.isEmpty()) return
        controls.showTracks(getString(R.string.player_audio_heading), tracks.map { it.label }, tracks.indexOfFirst { it.selected }) { i ->
            native.selectAudio(i)?.let { showHint(getString(R.string.player_audio_fmt, it.label.uppercase())) }
        }
    }

    private fun cycleSubtitles() {
        val label = native.cycleSubtitles()
        showHint(if (label == null) getString(R.string.player_subtitles_off) else getString(R.string.player_subtitles_on, label.uppercase()))
    }

    private fun onBack() {
        when {
            nativeShown && controls.tracksShown -> controls.hideTracks()
            nativeShown && controls.shown -> controls.hide()
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
        native.pause()
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
        controls.release()
        native.stop()
        chrome.hideCustomView()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.removeJavascriptInterface("FloxBridge")
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        const val HINT_MS = 2500L
        const val VOLUME_STEP = 0.1f
        const val PREFS = "flox_player"
        const val PREF_QUALITY = "quality"
        const val WATCHDOG_MS = 45_000L
        const val MIN_WEBVIEW_MAJOR = 89
    }
}
