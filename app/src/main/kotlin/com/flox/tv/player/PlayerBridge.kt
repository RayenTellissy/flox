package com.flox.tv.player

import android.content.Context
import android.webkit.JavascriptInterface
import com.flox.tv.data.MediaType
import com.flox.tv.data.Progress
import com.flox.tv.data.ProgressStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Receives player state from the page (JS thread) or the native player and persists progress.
 * Also relays the manifest and captions the page resolves so playback can move to the native player.
 */
class PlayerBridge(
    ctx: Context,
    val meta: Meta,
    private val onEnded: () -> Unit,
    private val onManifest: (Manifest) -> Unit,
    private val onCaptions: (List<Caption>) -> Unit
) {
    data class Manifest(val url: String, val kind: String, val headers: Map<String, String>)
    data class Caption(val url: String, val language: String, val type: String)

    data class Meta(
        val id: Int,
        val type: MediaType,
        val title: String,
        val posterPath: String?,
        @Volatile var season: Int,
        @Volatile var episode: Int
    )

    private val app = ctx.applicationContext

    @Volatile var playing = false
        private set
    @Volatile var currentTime = 0.0
        private set
    @Volatile var hasPlayback = false
        private set
    @Volatile private var lastWrite = 0L
    @Volatile private var endedFired = false

    fun reset() {
        playing = false
        currentTime = 0.0
        hasPlayback = false
        endedFired = false
        lastWrite = 0L
    }

    @JavascriptInterface
    fun onMessage(json: String) {
        runCatching {
            val msg = JSONObject(json)
            when (msg.optString("type")) {
                "FLOX_TICK" -> onTick(msg.optJSONObject("data") ?: return)
                "FLOX_MANIFEST" -> onManifestMessage(msg.optJSONObject("data") ?: return)
                "FLOX_STREAM" -> onStreamMessage(msg.optJSONObject("data") ?: return)
                "PLAYER_EVENT" -> onPlayerEvent(msg.optJSONObject("data") ?: return)
                "MEDIA_DATA" -> onMediaData(msg.opt("data") ?: return)
            }
        }
    }

    private fun onManifestMessage(d: JSONObject) {
        val url = d.optString("url")
        if (!url.startsWith("http")) return
        val headers = mutableMapOf<String, String>()
        d.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> headers[k] = h.optString(k) } }
        onManifest(Manifest(url, d.optString("kind"), headers))
    }

    private fun onStreamMessage(d: JSONObject) {
        val arr: JSONArray = d.optJSONArray("captions") ?: return
        val list = (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val url = c.optString("url")
            if (!url.startsWith("http")) null else Caption(url, c.optString("language"), c.optString("type"))
        }
        onCaptions(list)
    }

    private fun onTick(d: JSONObject) = tick(
        time = d.optDouble("currentTime", 0.0),
        duration = d.optDouble("duration", 0.0),
        paused = d.optBoolean("paused", true),
        ended = d.optBoolean("ended")
    )

    /** Progress from either player. Safe to call from any thread. */
    fun tick(time: Double, duration: Double, paused: Boolean, ended: Boolean) {
        currentTime = time
        playing = !paused
        if (duration <= 0) return
        if (time > 0) hasPlayback = true
        val ended = ended || (time > 0 && time >= duration - 1.0)
        val now = System.currentTimeMillis()
        if (ended || now - lastWrite >= WRITE_INTERVAL_MS) {
            lastWrite = now
            save(time, duration, meta.season, meta.episode, now)
        }
        if (ended && !endedFired) {
            endedFired = true
            onEnded()
        }
    }

    private fun onPlayerEvent(d: JSONObject) {
        when (d.optString("event")) {
            "play", "playing" -> playing = true
            "pause" -> playing = false
        }
        if (d.has("playing")) playing = d.optBoolean("playing")
        if (d.has("currentTime")) currentTime = d.optDouble("currentTime", currentTime)
    }

    private fun onMediaData(data: Any) {
        val obj = data as? JSONObject ?: return
        val key = (if (meta.type == MediaType.TV) "t" else "m") + meta.id
        val entry = obj.optJSONObject(key) ?: obj.takeIf { it.has("progress") } ?: return
        val progress = entry.optJSONObject("progress") ?: return
        val duration = progress.optDouble("duration", 0.0)
        if (duration <= 0) return
        val now = System.currentTimeMillis()
        lastWrite = now
        save(
            watched = progress.optDouble("watched", 0.0),
            duration = duration,
            season = entry.optInt("last_season_watched", meta.season),
            episode = entry.optInt("last_episode_watched", meta.episode),
            now = now
        )
    }

    private fun save(watched: Double, duration: Double, season: Int, episode: Int, now: Long) {
        ProgressStore.put(
            app,
            Progress(
                id = meta.id,
                type = meta.type,
                title = meta.title,
                posterPath = meta.posterPath,
                watchedSeconds = watched.toInt(),
                durationSeconds = duration.toInt(),
                lastSeason = season,
                lastEpisode = episode,
                lastUpdated = now
            )
        )
    }

    private companion object {
        const val WRITE_INTERVAL_MS = 10_000L
    }
}
