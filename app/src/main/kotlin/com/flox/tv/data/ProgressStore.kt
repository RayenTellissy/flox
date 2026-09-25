package com.flox.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local continue-watching store. Backed by SharedPreferences as one JSON array,
 * newest first, capped at MAX entries. Items past the finished threshold in [Settings] are hidden from reads.
 */
object ProgressStore {
    private const val PREFS = "flox_progress"
    private const val KEY = "items"
    private val MAX = Settings.CONTINUE_WATCHING_LIMITS.max()

    fun all(ctx: Context): List<Progress> {
        Settings.init(ctx)
        val threshold = Settings.finishedThresholdPercent
        return read(ctx).filter { !it.finished(threshold) }
    }

    fun get(ctx: Context, type: MediaType, id: Int): Progress? =
        read(ctx).firstOrNull { it.type == type && it.id == id }

    fun put(ctx: Context, p: Progress) {
        val list = read(ctx).filterNot { it.type == p.type && it.id == p.id }.toMutableList()
        list.add(0, p)
        write(ctx, list.take(MAX))
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun read(ctx: Context): List<Progress> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun write(ctx: Context, list: List<Progress>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    private fun toJson(p: Progress) = JSONObject()
        .put("id", p.id)
        .put("type", p.type.tmdb)
        .put("title", p.title)
        .put("poster", p.posterPath)
        .put("watched", p.watchedSeconds)
        .put("duration", p.durationSeconds)
        .put("season", p.lastSeason)
        .put("episode", p.lastEpisode)
        .put("updated", p.lastUpdated)

    private fun fromJson(o: JSONObject) = Progress(
        id = o.getInt("id"),
        type = MediaType.from(o.optString("type")),
        title = o.optString("title"),
        posterPath = o.optString("poster").takeIf { it.isNotEmpty() && it != "null" },
        watchedSeconds = o.optInt("watched"),
        durationSeconds = o.optInt("duration"),
        lastSeason = o.optInt("season", 1),
        lastEpisode = o.optInt("episode", 1),
        lastUpdated = o.optLong("updated")
    )
}
