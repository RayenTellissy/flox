package com.flox.tv.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebSettings
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import com.flox.tv.R
import com.flox.tv.data.MediaType
import com.flox.tv.data.ProgressStore
import com.flox.tv.data.Tmdb
import com.flox.tv.player.PlayerIntent
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class HomeActivity : Activity() {

    private companion object {
        const val WARMUP_DELAY_MS = 3000L
    }

    private val scope = MainScope()
    private lateinit var continueRow: Row
    private lateinit var moviesRow: Row
    private lateinit var tvRow: Row

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        findViewById<ScrollView>(R.id.scroll).isSmoothScrollingEnabled = false

        val search = findViewById<Button>(R.id.search)
        search.setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }

        continueRow = Row(findViewById(R.id.row_continue), R.string.row_continue) { openContinue(it) }
        moviesRow = Row(findViewById(R.id.row_movies), R.string.row_trending_movies) { openDetails(it) }
        tvRow = Row(findViewById(R.id.row_tv), R.string.row_trending_tv) { openDetails(it) }

        loadTrending(moviesRow, MediaType.MOVIE)
        loadTrending(tvRow, MediaType.TV)
        search.requestFocus()
        // Load the WebView provider early so the player opens without a main-thread stall
        Handler(Looper.getMainLooper()).postDelayed({ runCatching { WebSettings.getDefaultUserAgent(applicationContext) } }, WARMUP_DELAY_MS)
    }

    override fun onResume() {
        super.onResume()
        val items = ProgressStore.all(this).map { CardItem.Continue(it) }
        continueRow.root.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        continueRow.show(items)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun loadTrending(row: Row, type: MediaType) {
        row.loading()
        scope.launch {
            Tmdb.trending(type)
                .onSuccess { list -> row.show(list.map { CardItem.Media(it) }) }
                .onFailure { row.error() }
        }
    }

    private fun openContinue(card: CardItem) {
        val p = (card as? CardItem.Continue)?.progress ?: return
        startActivity(
            PlayerIntent.create(
                this, p.id, p.type, p.title, p.posterPath,
                season = p.lastSeason, episode = p.lastEpisode, startAtSeconds = p.watchedSeconds
            )
        )
    }

    private fun openDetails(card: CardItem) {
        startActivity(
            Intent(this, DetailsActivity::class.java)
                .putExtra(DetailsActivity.EXTRA_ID, card.id)
                .putExtra(DetailsActivity.EXTRA_TYPE, card.type.tmdb)
        )
    }

    private class Row(val root: View, labelRes: Int, onClick: (CardItem) -> Unit) {
        private val state: TextView = root.findViewById(R.id.row_state)
        private val list: FocusRow = root.findViewById(R.id.row_list)
        private val adapter = PosterAdapter(onClick)

        init {
            root.findViewById<TextView>(R.id.row_label).setText(labelRes)
            list.adapter = adapter
        }

        fun loading() = StateStamp.show(state, R.string.state_loading)

        fun show(items: List<CardItem>) {
            adapter.submit(items)
            if (items.isEmpty()) StateStamp.show(state, R.string.state_empty) else StateStamp.hide(state)
        }

        fun error() {
            adapter.submit(emptyList())
            StateStamp.show(state, R.string.state_error)
        }
    }
}
