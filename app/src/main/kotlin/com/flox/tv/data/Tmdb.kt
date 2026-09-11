package com.flox.tv.data

import android.util.LruCache
import com.flox.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/** Thin TMDB client with a small in-memory response cache. */
object Tmdb {
    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMG = "https://image.tmdb.org/t/p/"
    private const val TTL_LIST = 60L * 60 * 1000
    private const val TTL_DETAILS = 24 * TTL_LIST
    private const val TIMEOUT = 10_000

    private class Entry(val at: Long, val body: String)

    private val cache = LruCache<String, Any>(32)

    fun poster(path: String?): String? = path?.let { "${IMG}w342$it" }
    fun still(path: String?): String? = path?.let { "${IMG}w300$it" }
    fun detailPoster(path: String?): String? = path?.let { "${IMG}w500$it" }

    suspend fun trending(type: MediaType): Result<List<MediaItem>> = runCatching {
        val body = fetch("/trending/${type.tmdb}/week", TTL_LIST)
        parseItems(JSONObject(body).optJSONArray("results"), type).map { it.second }
    }

    suspend fun search(query: String): Result<List<MediaItem>> = runCatching {
        coroutineScope {
            val params = mapOf("query" to query, "include_adult" to "false")
            val movies = async(Dispatchers.IO) { fetch("/search/movie", TTL_LIST, params) }
            val tv = async(Dispatchers.IO) { fetch("/search/tv", TTL_LIST, params) }
            val m = parseItems(JSONObject(movies.await()).optJSONArray("results"), MediaType.MOVIE)
            val t = parseItems(JSONObject(tv.await()).optJSONArray("results"), MediaType.TV)
            (m + t).sortedByDescending { it.first }.map { it.second }.take(36)
        }
    }

    suspend fun details(type: MediaType, id: Int): Result<MediaDetails> = runCatching {
        val o = JSONObject(fetch("/${type.tmdb}/$id", TTL_DETAILS))
        val runtime = o.optInt("runtime", 0).takeIf { it > 0 }
            ?: o.optJSONArray("episode_run_time")?.takeIf { it.length() > 0 }?.optInt(0, 0)?.takeIf { it > 0 }
        val seasons = ArrayList<Season>()
        val arr = o.optJSONArray("seasons")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                val n = s.optInt("season_number", 0)
                if (n <= 0) continue
                seasons.add(Season(n, s.optString("name").ifEmpty { "Season $n" }, s.optInt("episode_count", 0)))
            }
        }
        MediaDetails(
            id = o.optInt("id", id),
            type = type,
            title = o.optString("title").ifEmpty { o.optString("name") },
            year = date(o),
            runtimeMinutes = runtime,
            overview = o.optString("overview"),
            posterPath = path(o, "poster_path"),
            seasons = seasons
        )
    }

    suspend fun episodes(id: Int, season: Int): Result<List<Episode>> = runCatching {
        val o = JSONObject(fetch("/tv/$id/season/$season", TTL_DETAILS))
        val arr = o.optJSONArray("episodes") ?: JSONArray()
        val out = ArrayList<Episode>(arr.length())
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            out.add(
                Episode(
                    season = e.optInt("season_number", season),
                    number = e.optInt("episode_number", i + 1),
                    name = e.optString("name"),
                    overview = e.optString("overview"),
                    stillPath = path(e, "still_path"),
                    runtimeMinutes = e.optInt("runtime", 0).takeIf { it > 0 }
                )
            )
        }
        out
    }

    private fun parseItems(arr: JSONArray?, type: MediaType): List<Pair<Double, MediaItem>> {
        if (arr == null) return emptyList()
        val out = ArrayList<Pair<Double, MediaItem>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("media_type") == "person") continue
            val poster = path(o, "poster_path") ?: continue
            val item = MediaItem(
                id = o.optInt("id"),
                type = type,
                title = o.optString("title").ifEmpty { o.optString("name") },
                year = date(o),
                posterPath = poster,
                overview = o.optString("overview")
            )
            out.add(o.optDouble("popularity", 0.0) to item)
        }
        return out
    }

    private fun date(o: JSONObject): String =
        o.optString("release_date").ifEmpty { o.optString("first_air_date") }.take(4)

    private fun path(o: JSONObject, key: String): String? =
        if (o.isNull(key)) null else o.optString(key).takeIf { it.isNotEmpty() }

    private suspend fun fetch(path: String, ttl: Long, params: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val sb = StringBuilder(BASE).append(path)
                .append("?api_key=").append(BuildConfig.TMDB_API_KEY)
                .append("&language=en-US")
            for ((k, v) in params) sb.append("&").append(k).append("=").append(URLEncoder.encode(v, "UTF-8"))
            val url = sb.toString()
            val now = System.currentTimeMillis()
            val hit = cache.get(url) as? Entry
            if (hit != null && now - hit.at < ttl) return@withContext hit.body
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = TIMEOUT
                conn.readTimeout = TIMEOUT
                conn.setRequestProperty("Accept", "application/json")
                conn.setRequestProperty("Accept-Encoding", "gzip")
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val raw = conn.inputStream
                val stream = if (conn.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(raw) else raw
                val body = stream.bufferedReader().use { it.readText() }
                cache.put(url, Entry(now, body))
                body
            } finally {
                conn.disconnect()
            }
        }
}
