package com.flox.tv.player

import android.app.Activity
import android.os.Message
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import com.flox.tv.BuildConfig

/** Handles fullscreen video, DRM permission and refuses popups. */
class FloxChromeClient(private val activity: Activity) : WebChromeClient() {
    private var customView: View? = null
    private var customCallback: CustomViewCallback? = null

    val hasCustomView get() = customView != null

    override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
        if (BuildConfig.DEBUG) Log.d("FloxPlayer", "popup refused dialog=$isDialog gesture=$isUserGesture")
        return false
    }

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = callback
        decor().addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    override fun onHideCustomView() = hideCustomView()

    fun hideCustomView() {
        val view = customView ?: return
        decor().removeView(view)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
    }

    override fun onPermissionRequest(request: PermissionRequest) {
        val drm = PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
        if (request.resources.contains(drm)) request.grant(arrayOf(drm)) else request.deny()
    }

    override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
        if (BuildConfig.DEBUG) Log.d("FloxWeb", "${msg.message()} (${msg.sourceId()}:${msg.lineNumber()})")
        return true
    }

    private fun decor() = activity.window.decorView as FrameLayout
}
