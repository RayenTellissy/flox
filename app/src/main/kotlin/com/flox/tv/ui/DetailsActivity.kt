package com.flox.tv.ui

import android.app.Activity
import android.os.Bundle
import androidx.core.view.doOnNextLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.flox.tv.R
import com.flox.tv.data.Episode
import com.flox.tv.data.MediaDetails
import com.flox.tv.data.MediaType
import com.flox.tv.telegram.Library
import com.flox.tv.data.Progress
import com.flox.tv.data.ProgressStore
import com.flox.tv.data.Tmdb
import com.flox.tv.player.PlayerIntent
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DetailsActivity : Activity() {

    private val scope = MainScope()
    private var episodesJob: Job? = null
    private lateinit var list: PlainRecyclerView
    private lateinit var adapter: DetailsAdapter
    private var mediaId = 0
    private var type = MediaType.MOVIE
    private var details: MediaDetails? = null
    private var progress: Progress? = null
    private var selectedSeason = 1
    // Opened from the library row: only uploaded seasons and episodes are listed
    private var libraryOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_details)
        mediaId = intent.getIntExtra(EXTRA_ID, 0)
        type = MediaType.from(intent.getStringExtra(EXTRA_TYPE))
        libraryOnly = intent.getBooleanExtra(EXTRA_LIBRARY, false)
        progress = ProgressStore.get(this, type, mediaId)

        list = findViewById(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        list.setHasFixedSize(true)
        adapter = DetailsAdapter(::play, ::selectSeason, ::playEpisode)
        adapter.mediaId = mediaId
        list.adapter = adapter

        adapter.rows.add(DetailsRow.State(R.string.state_loading))
        adapter.notifyDataSetChanged()
        load()
    }

    override fun onResume() {
        super.onResume()
        progress = ProgressStore.get(this, type, mediaId)
        val d = details ?: return
        if (adapter.rows.isNotEmpty() && adapter.rows[0] is DetailsRow.Header) {
            adapter.rows[0] = DetailsRow.Header(d, buttonText(), hasProgress())
            adapter.notifyDataSetChanged()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun load() {
        scope.launch {
            Tmdb.details(type, mediaId)
                .onSuccess { d ->
                    details = d
                    adapter.rows.clear()
                    adapter.rows.add(DetailsRow.Header(d, buttonText(), hasProgress()))
                    val seasons = if (libraryOnly) d.seasons.filter { it.number in Library.seasons(d.id) } else d.seasons
                    if (type == MediaType.TV && seasons.isNotEmpty()) {
                        val fromProgress = progress?.lastSeason ?: -1
                        selectedSeason = if (seasons.any { it.number == fromProgress }) fromProgress else seasons[0].number
                        adapter.seasonAdapter.seasons = seasons
                        adapter.seasonAdapter.select(selectedSeason)
                        adapter.rows.add(DetailsRow.Seasons(adapter.seasonAdapter.indexOf(selectedSeason)))
                        adapter.rows.add(DetailsRow.State(R.string.state_loading))
                    }
                    adapter.notifyDataSetChanged()
                    list.doOnNextLayout { list.requestFocus() }
                    if (type == MediaType.TV && seasons.isNotEmpty()) loadEpisodes(selectedSeason)
                }
                .onFailure {
                    adapter.rows.clear()
                    adapter.rows.add(DetailsRow.State(R.string.state_error))
                    adapter.notifyDataSetChanged()
                }
        }
    }

    private fun loadEpisodes(season: Int) {
        episodesJob?.cancel()
        replaceTail(listOf(DetailsRow.State(R.string.state_loading)))
        episodesJob = scope.launch {
            Tmdb.episodes(mediaId, season)
                .onSuccess { all ->
                    val eps = if (libraryOnly) all.filter { Library.has(mediaId, MediaType.TV, it.season, it.number) } else all
                    if (eps.isEmpty()) replaceTail(listOf(DetailsRow.State(R.string.state_empty)))
                    else replaceTail(eps.map { DetailsRow.EpisodeRow(it) })
                }
                .onFailure { replaceTail(listOf(DetailsRow.State(R.string.state_error))) }
        }
    }

    // Rows after the seasons row are episodes or a state stamp
    private fun replaceTail(newRows: List<DetailsRow>) {
        val start = adapter.rows.indexOfFirst { it is DetailsRow.Seasons } + 1
        if (start <= 0) return
        val oldCount = adapter.rows.size - start
        while (adapter.rows.size > start) adapter.rows.removeAt(adapter.rows.size - 1)
        if (oldCount > 0) adapter.notifyItemRangeRemoved(start, oldCount)
        adapter.rows.addAll(newRows)
        adapter.notifyItemRangeInserted(start, newRows.size)
    }

    private fun selectSeason(season: Int) {
        if (season == selectedSeason) return
        selectedSeason = season
        adapter.seasonAdapter.select(season)
        loadEpisodes(season)
    }

    private fun hasProgress(): Boolean = (progress?.watchedSeconds ?: 0) > 0

    private fun buttonText(): String {
        val p = progress
        if (p == null || p.watchedSeconds <= 0) return getString(R.string.play)
        if (type == MediaType.MOVIE) return getString(R.string.resume)
        return getString(R.string.resume) + " " +
            getString(R.string.season_fmt, p.lastSeason) + " " +
            getString(R.string.episode_fmt, p.lastEpisode)
    }

    private fun play() {
        val d = details ?: return
        val p = progress
        val season = if (type == MediaType.TV) p?.lastSeason ?: 1 else 1
        val episode = if (type == MediaType.TV) p?.lastEpisode ?: 1 else 1
        startActivity(
            PlayerIntent.create(
                this, d.id, type, d.title, d.posterPath,
                season = season, episode = episode, startAtSeconds = p?.watchedSeconds ?: 0
            )
        )
    }

    private fun playEpisode(e: Episode) {
        val d = details ?: return
        val p = progress
        val startAt = if (p != null && p.lastSeason == e.season && p.lastEpisode == e.number) p.watchedSeconds else 0
        startActivity(
            PlayerIntent.create(
                this, d.id, type, d.title, d.posterPath,
                season = e.season, episode = e.number, startAtSeconds = startAt
            )
        )
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_TYPE = "type"
        const val EXTRA_LIBRARY = "library"
    }
}
