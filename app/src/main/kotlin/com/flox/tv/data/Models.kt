package com.flox.tv.data

enum class MediaType(val tmdb: String) {
    MOVIE("movie"),
    TV("tv");

    companion object {
        fun from(s: String?): MediaType = if (s == "tv") TV else MOVIE
    }
}

data class MediaItem(
    val id: Int,
    val type: MediaType,
    val title: String,
    val year: String,
    val posterPath: String?,
    val overview: String = ""
)

data class MediaDetails(
    val id: Int,
    val type: MediaType,
    val title: String,
    val year: String,
    val runtimeMinutes: Int?,
    val overview: String,
    val posterPath: String?,
    val seasons: List<Season>
)

data class Season(
    val number: Int,
    val name: String,
    val episodeCount: Int
)

data class Episode(
    val season: Int,
    val number: Int,
    val name: String,
    val overview: String,
    val stillPath: String?,
    val runtimeMinutes: Int?
)

data class Progress(
    val id: Int,
    val type: MediaType,
    val title: String,
    val posterPath: String?,
    val watchedSeconds: Int,
    val durationSeconds: Int,
    val lastSeason: Int,
    val lastEpisode: Int,
    val lastUpdated: Long
) {
    val fraction: Float get() = if (durationSeconds <= 0) 0f else watchedSeconds.toFloat() / durationSeconds
    fun finished(thresholdPercent: Int): Boolean = fraction * 100 >= thresholdPercent
}
