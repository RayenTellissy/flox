package com.flox.tv.ui

import android.content.Context
import com.flox.tv.R
import com.flox.tv.data.MediaItem
import com.flox.tv.data.MediaType
import com.flox.tv.data.Progress

/** A poster card: either a catalog item or a continue-watching entry. */
sealed class CardItem {
    abstract val id: Int
    abstract val type: MediaType
    abstract val title: String
    abstract val posterPath: String?

    val stableId: Long get() = (type.ordinal.toLong() shl 32) or (id.toLong() and 0xFFFFFFFFL)

    abstract fun eyebrow(ctx: Context): String

    data class Media(val item: MediaItem) : CardItem() {
        override val id get() = item.id
        override val type get() = item.type
        override val title get() = item.title
        override val posterPath get() = item.posterPath

        override fun eyebrow(ctx: Context): String {
            val kind = ctx.getString(if (type == MediaType.TV) R.string.type_tv else R.string.type_movie)
            return if (item.year.isEmpty()) kind else "${item.year} · $kind"
        }
    }

    data class Continue(val progress: Progress) : CardItem() {
        override val id get() = progress.id
        override val type get() = progress.type
        override val title get() = progress.title
        override val posterPath get() = progress.posterPath

        override fun eyebrow(ctx: Context): String =
            if (type == MediaType.TV) {
                ctx.getString(R.string.type_tv) + " · " +
                    ctx.getString(R.string.season_fmt, progress.lastSeason) + " " +
                    ctx.getString(R.string.episode_fmt, progress.lastEpisode)
            } else {
                ctx.getString(R.string.type_movie)
            }
    }
}
