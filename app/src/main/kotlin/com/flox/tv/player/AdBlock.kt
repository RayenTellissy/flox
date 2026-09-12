package com.flox.tv.player

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.flox.tv.BuildConfig
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Allowlist-based request filter for the embedded player. */
object AdBlock {
    private const val TAG = "FloxAdBlock"

    val PLAYER_HOSTS: Set<String> get() = Provider.HOSTS

    val CDN_ALLOW = setOf(
        "image.tmdb.org", "tmdb.org", "cdn.jsdelivr.net", "cdnjs.cloudflare.com", "unpkg.com",
        "fonts.googleapis.com", "fonts.gstatic.com", "gstatic.com", "googleapis.com", "cloudflare.com",
        "challenges.cloudflare.com", "static.cloudflareinsights.com", "jwpcdn.com", "wsrv.nl", "googlevideo.com"
    )

    // Cheap TLDs commonly used by popunder DGA hosts (abuse reports from Spamhaus / URLhaus)
    val BLOCKED_TLDS = setOf(
        "cfd", "rest", "cyou", "sbs", "icu", "top", "click", "monster", "quest", "buzz", "bond",
        "lol", "mom", "autos", "boats", "motorcycles", "hair", "makeup", "skin", "beauty", "cam",
        "surf", "pics", "zip", "mov"
    )

    private val MEDIA_EXT = setOf(
        "m3u8", "ts", "mp4", "m4s", "webm", "mkv", "vtt", "srt", "jpg", "jpeg", "png", "webp",
        "gif", "svg", "woff", "woff2", "ttf", "css", "key", "aac", "mp3", "m4a"
    )
    private val CODE_DEST = setOf("script", "document", "iframe", "frame", "object", "embed", "worker")

    private enum class HostClass { PLAYER, CDN, BLOCKED_TLD, OTHER }

    private val hostCache = ConcurrentHashMap<String, HostClass>()
    val blockedCount = AtomicInteger(0)

    /** Debug switch: when false every request and navigation passes. */
    @Volatile var enabled = true

    @Volatile private var scriptCache: String? = null

    private fun matches(host: String, domain: String) = host == domain || host.endsWith(".$domain")

    fun isPlayerHost(host: String?) = host != null && PLAYER_HOSTS.any { matches(host, it) }
    fun isCdnHost(host: String?) = host != null && CDN_ALLOW.any { matches(host, it) }

    private fun classify(host: String): HostClass = hostCache.getOrPut(host) {
        when {
            isPlayerHost(host) -> HostClass.PLAYER
            isCdnHost(host) -> HostClass.CDN
            host.substringAfterLast('.') in BLOCKED_TLDS -> HostClass.BLOCKED_TLD
            else -> HostClass.OTHER
        }
    }

    private fun isHttp(uri: Uri) = uri.scheme == "http" || uri.scheme == "https"

    private fun looksLikeMedia(uri: Uri, headers: Map<String, String>?): Boolean {
        val path = uri.path.orEmpty().lowercase()
        if (path.contains("/hls/")) return true
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        if (ext in MEDIA_EXT) return true
        val accept = header(headers, "Accept").orEmpty()
        return accept.startsWith("image/") || accept.startsWith("video/") || accept.startsWith("audio/")
    }

    // Documents and scripts from unknown hosts are the ad vectors; extensionless XHR is usually video
    private fun looksLikeCode(uri: Uri, headers: Map<String, String>?): Boolean {
        val path = uri.path.orEmpty().lowercase()
        val ext = path.substringAfterLast('/').substringAfterLast('.', "")
        if (ext == "js" || ext == "mjs" || ext == "html" || ext == "htm") return true
        val accept = header(headers, "Accept").orEmpty()
        return accept.contains("text/html")
    }

    private fun header(headers: Map<String, String>?, name: String): String? =
        headers?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** Verdict for shouldInterceptRequest. */
    fun shouldBlock(request: WebResourceRequest): Boolean {
        if (!enabled) return false
        val uri = request.url
        val host = uri.host?.lowercase() ?: return true
        if (!isHttp(uri)) return true
        if (uri.path.orEmpty().startsWith("/cdn-cgi/")) return false
        val headers = request.requestHeaders
        val dest = header(headers, "Sec-Fetch-Dest")?.lowercase()
        val isCode = if (dest != null) dest in CODE_DEST else looksLikeCode(uri, headers)
        // Video sources live on arbitrary hosts, so media wins over every host rule except main-frame
        val verdict = when (classify(host)) {
            HostClass.PLAYER, HostClass.CDN -> false
            HostClass.BLOCKED_TLD, HostClass.OTHER -> request.isForMainFrame || isCode
        }
        if (verdict) onBlocked(uri)
        return verdict
    }

    /** Verdict for shouldOverrideUrlLoading; true = navigation allowed. */
    fun allowNavigation(request: WebResourceRequest): Boolean {
        if (!enabled) return true
        val uri = request.url
        if (!isHttp(uri)) return false
        val host = uri.host?.lowercase() ?: return false
        val allowed = if (request.isForMainFrame) isPlayerHost(host) else isPlayerHost(host) || isCdnHost(host)
        if (!allowed) onBlocked(uri)
        return allowed
    }

    fun blockedResponse() = WebResourceResponse(
        "text/plain", "utf-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0))
    )

    /** Document-start script with host lists substituted in. */
    fun script(ctx: Context): String = scriptCache ?: synchronized(this) {
        scriptCache ?: run {
            val raw = asset(ctx, "adblock.js")
            val allow = JSONArray().apply { (PLAYER_HOSTS + CDN_ALLOW).forEach { put(it) } }
            raw.replace("__ALLOW__", allow.toString())
                .replace("__NOHEVC__", (!Codecs.hasHevcDecoder()).toString())
                .also { scriptCache = it }
        }
    }

    @Volatile private var navCache: String? = null

    /** Remote navigation helper, injected after each page load. */
    fun navScript(ctx: Context): String = navCache ?: asset(ctx, "flox_nav.js").also { navCache = it }

    private fun asset(ctx: Context, name: String) = ctx.assets.open(name).bufferedReader().use { it.readText() }

    private fun onBlocked(uri: Uri) {
        blockedCount.incrementAndGet()
        if (BuildConfig.DEBUG) Log.d(TAG, "blocked $uri")
    }
}
