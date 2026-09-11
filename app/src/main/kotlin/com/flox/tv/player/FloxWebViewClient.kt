package com.flox.tv.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.flox.tv.BuildConfig

/**
 * Filters requests through AdBlock and reports main-frame failures.
 * [fallbackScript] is injected on page start/finish when document-start scripts are unsupported.
 */
class FloxWebViewClient(
    private val fallbackScript: String?,
    private val onPlaybackFailed: () -> Unit
) : WebViewClient() {
    private val main = Handler(Looper.getMainLooper())

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        if (AdBlock.shouldBlock(request)) AdBlock.blockedResponse() else null

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (AdBlock.allowNavigation(request)) return false
        if (request.isForMainFrame) fail("navigation to ${request.url}")
        return true
    }

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) fail("error ${error.errorCode} ${error.description}")
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        fail("renderer gone")
        return true
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        fallbackScript?.let { view.evaluateJavascript(it, null) }
    }

    override fun onPageFinished(view: WebView, url: String?) {
        fallbackScript?.let { view.evaluateJavascript(it, null) }
    }

    private fun fail(reason: String) {
        if (BuildConfig.DEBUG) Log.d("FloxPlayer", "playback failed: $reason")
        main.post(onPlaybackFailed)
    }
}
