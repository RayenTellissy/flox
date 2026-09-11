package com.flox.tv.player

import android.content.Context
import android.webkit.JavascriptInterface
import com.flox.tv.data.MediaType
import com.flox.tv.data.Progress
import com.flox.tv.data.ProgressStore
import org.json.JSONObject

/** Receives player events from the page (JS thread) and persists progress. */
class PlayerBridge(ctx: Context, private val meta: Meta) {
    data class Meta(
        val id: Int,
        val type: MediaType,
        val title: String,
        val posterPath: String?,
        val season: Int,
        val episode: Int
    )

    private val app = ctx.applicationContext

    @Volatile var playing = false
        private set
    @Volatile var currentTime = 0.0
        private set
    @Volatile private var lastWrite = 0L

    @JavascriptInterface
    fun onMessage(json: String) {
        runCatching {
            val msg = JSONObject(json)
            when (msg.optString("type")) {
                "PLAYER_EVENT" -> onPlayerEvent(msg.optJSONObject("data") ?: return)
                "MEDIA_DATA" -> onMediaData(msg.opt("data") ?: return)
            }
        }
    }

    private fun onPlayerEvent(d: JSONObject) {
        val event = d.optString("event")
        when (event) {
            "play", "playing" -> playing = true
            "pause", "ended" -> playing = false
        }
        if (d.has("playing")) playing = d.optBoolean("playing")
        if (d.has("currentTime")) currentTime = d.optDouble("currentTime", currentTime)
        if (event !in PROGRESS_EVENTS) return
        val force = event == "pause" || event == "ended"
        val now = System.currentTimeMillis()
        if (!force && now - lastWrite < WRITE_INTERVAL_MS) return
        val duration = d.optDouble("duration", 0.0)
        if (duration <= 0) return
        lastWrite = now
        save(
            watched = d.optDouble("currentTime", currentTime),
            duration = duration,
            season = d.optInt("season", meta.season),
            episode = d.optInt("episode", meta.episode),
            now = now
        )
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
        val PROGRESS_EVENTS = setOf("timeupdate", "pause", "ended", "seeked")
        const val WRITE_INTERVAL_MS = 10_000L
    }
}
