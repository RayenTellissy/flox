package com.flox.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.flox.tv.R
import com.flox.tv.data.Episode
import com.flox.tv.data.ImageLoader
import com.flox.tv.data.MediaDetails
import com.flox.tv.data.MediaType
import com.flox.tv.data.Tmdb

sealed class DetailsRow {
    data class Header(val details: MediaDetails, val buttonText: String, val resume: Boolean) : DetailsRow()
    data class Seasons(val selectedIndex: Int) : DetailsRow()
    data class EpisodeRow(val episode: Episode) : DetailsRow()
    data class State(val textRes: Int) : DetailsRow()
}

/** Single vertical list: header, optional seasons row, then episodes or a state stamp. */
class DetailsAdapter(
    private val onPlay: () -> Unit,
    private val onSeason: (Int) -> Unit,
    private val onEpisode: (Episode) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    val rows = ArrayList<DetailsRow>()
    val seasonAdapter = SeasonAdapter(onSeason)

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is DetailsRow.Header -> TYPE_HEADER
        is DetailsRow.Seasons -> TYPE_SEASONS
        is DetailsRow.EpisodeRow -> TYPE_EPISODE
        is DetailsRow.State -> TYPE_STATE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_details_header, parent, false), onPlay)
            TYPE_SEASONS -> SeasonsVH(inflater.inflate(R.layout.item_seasons_row, parent, false))
            TYPE_EPISODE -> EpisodeVH(inflater.inflate(R.layout.item_episode, parent, false), onEpisode)
            else -> StateVH(inflater.inflate(R.layout.item_state, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is DetailsRow.Header -> (holder as HeaderVH).bind(row)
            is DetailsRow.Seasons -> (holder as SeasonsVH).bind(row, seasonAdapter)
            is DetailsRow.EpisodeRow -> (holder as EpisodeVH).bind(row.episode)
            is DetailsRow.State -> (holder as StateVH).text.setText(row.textRes)
        }
    }

    class HeaderVH(view: View, onPlay: () -> Unit) : RecyclerView.ViewHolder(view) {
        private val poster: ImageView = view.findViewById(R.id.poster)
        private val meta: TextView = view.findViewById(R.id.meta)
        private val title: TextView = view.findViewById(R.id.title)
        private val overview: TextView = view.findViewById(R.id.overview)
        val play: Button = view.findViewById(R.id.play)

        init {
            play.setOnClickListener { onPlay() }
        }

        fun bind(row: DetailsRow.Header) {
            val d = row.details
            val ctx = itemView.context
            val parts = ArrayList<String>()
            if (d.year.isNotEmpty()) parts.add(d.year)
            parts.add(ctx.getString(if (d.type == MediaType.TV) R.string.type_tv else R.string.type_movie))
            d.runtimeMinutes?.let { parts.add("$it MIN") }
            meta.text = parts.joinToString(" · ")
            title.text = d.title
            overview.text = d.overview
            play.text = row.buttonText
            play.setCompoundDrawablesRelativeWithIntrinsicBounds(if (row.resume) R.drawable.ic_continue else R.drawable.ic_play, 0, 0, 0)
            ImageLoader.load(poster, Tmdb.detailPoster(d.posterPath))
        }
    }

    class SeasonsVH(view: View) : RecyclerView.ViewHolder(view) {
        private val row: FocusRow = view.findViewById(R.id.seasons)

        fun bind(data: DetailsRow.Seasons, adapter: SeasonAdapter) {
            if (row.adapter !== adapter) row.adapter = adapter
            if (data.selectedIndex >= 0) {
                row.preferPosition(data.selectedIndex)
                row.scrollToPosition(data.selectedIndex)
            }
        }
    }

    class EpisodeVH(view: View, onEpisode: (Episode) -> Unit) : RecyclerView.ViewHolder(view) {
        private val still: ImageView = view.findViewById(R.id.still)
        private val meta: TextView = view.findViewById(R.id.meta)
        private val name: TextView = view.findViewById(R.id.name)
        private val overview: TextView = view.findViewById(R.id.overview)
        private var episode: Episode? = null

        init {
            view.setOnClickListener { episode?.let(onEpisode) }
        }

        fun bind(e: Episode) {
            episode = e
            val label = String.format("E%02d", e.number)
            meta.text = e.runtimeMinutes?.let { "$label · $it MIN" } ?: label
            name.text = e.name
            overview.text = e.overview
            ImageLoader.load(still, Tmdb.still(e.stillPath))
        }
    }

    class StateVH(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view as TextView
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_SEASONS = 1
        const val TYPE_EPISODE = 2
        const val TYPE_STATE = 3
    }
}
