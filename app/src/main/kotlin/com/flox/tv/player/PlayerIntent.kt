package com.flox.tv.player

import android.content.Context
import android.content.Intent
import com.flox.tv.data.MediaType

/** Contract between UI and PlayerActivity. */
object PlayerIntent {
    const val EXTRA_ID = "id"
    const val EXTRA_TYPE = "type"
    const val EXTRA_TITLE = "title"
    const val EXTRA_POSTER = "poster"
    const val EXTRA_SEASON = "season"
    const val EXTRA_EPISODE = "episode"
    const val EXTRA_START_AT = "startAt"

    fun create(
        ctx: Context,
        id: Int,
        type: MediaType,
        title: String,
        posterPath: String?,
        season: Int = 1,
        episode: Int = 1,
        startAtSeconds: Int = 0
    ): Intent = Intent(ctx, PlayerActivity::class.java)
        .putExtra(EXTRA_ID, id)
        .putExtra(EXTRA_TYPE, type.tmdb)
        .putExtra(EXTRA_TITLE, title)
        .putExtra(EXTRA_POSTER, posterPath)
        .putExtra(EXTRA_SEASON, season)
        .putExtra(EXTRA_EPISODE, episode)
        .putExtra(EXTRA_START_AT, startAtSeconds)
}
