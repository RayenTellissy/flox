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
import com.flox.tv.data.MediaItem
import com.flox.tv.data.MediaType
import com.flox.tv.data.ProgressStore
import com.flox.tv.data.Tmdb
import com.flox.tv.player.PlayerIntent
import com.flox.tv.telegram.Library
import com.flox.tv.telegram.Telegram
import com.flox.tv.telegram.TelegramLoginActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class HomeActivity : Activity() {

    private companion object {
        const val WARMUP_DELAY_MS = 3000L
    }

    private val scope = MainScope()
    private lateinit var continueRow: Row
    private lateinit var libraryRow: Row
    private val authListener: (Telegram.Auth) -> Unit = { a -> runOnUiThread { onAuth(a) } }
    private lateinit var moviesRow: Row
    private lateinit var tvRow: Row

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        findViewById<ScrollView>(R.id.scroll).isSmoothScrollingEnabled = false

        val search = findViewById<Button>(R.id.search)
        search.setOnClickListener { startActivity(Intent(this, SearchActivity::class.java)) }

        continueRow = Row(findViewById(R.id.row_continue), R.string.row_continue, R.drawable.ic_continue) { openContinue(it) }
        libraryRow = Row(findViewById(R.id.row_library), R.string.row_library, R.drawable.ic_movie) { openDetails(it) }
        if (Telegram.configured) {
            libraryRow.root.visibility = View.VISIBLE
            libraryRow.onStateClick { startActivity(Intent(this, TelegramLoginActivity::class.java)) }
            Telegram.start(this)
            Telegram.addAuthListener(authListener)
        }
        moviesRow = Row(findViewById(R.id.row_movies), R.string.row_trending_movies, R.drawable.ic_movie) { openDetails(it) }
        tvRow = Row(findViewById(R.id.row_tv), R.string.row_trending_tv, R.drawable.ic_tv) { openDetails(it) }

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
        Telegram.removeAuthListener(authListener)
        scope.cancel()
        super.onDestroy()
    }

    private fun onAuth(a: Telegram.Auth) {
        when (a) {
            Telegram.Auth.Ready -> loadLibrary()
            Telegram.Auth.Loading -> libraryRow.loading()
            else -> libraryRow.prompt(R.string.state_connect_telegram)
        }
    }

    private fun loadLibrary() {
        libraryRow.loading()
        scope.launch {
            val ok = runCatching { Library.refresh() }.getOrDefault(false)
            if (!ok) { libraryRow.error(); return@launch }
            val titles = Library.entries.keys.map { it.tmdb to it.type }.distinct()
            val items = titles.mapNotNull { (id, type) ->
                Tmdb.details(type, id).getOrNull()?.let { MediaItem(it.id, it.type, it.title, it.year, it.posterPath, it.overview) }
            }
            libraryRow.show(items.map { CardItem.Media(it) })
        }
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

    private class Row(val root: View, labelRes: Int, iconRes: Int, onClick: (CardItem) -> Unit) {
        private val state: TextView = root.findViewById(R.id.row_state)
        private val list: FocusRow = root.findViewById(R.id.row_list)
        private val adapter = PosterAdapter(onClick)

        init {
            val label = root.findViewById<TextView>(R.id.row_label)
            label.setText(labelRes)
            val size = (label.textSize * 1.3f).toInt()
            val icon = root.context.getDrawable(iconRes)?.mutate()?.apply { setBounds(0, 0, size, size) }
            label.setCompoundDrawablesRelative(icon, null, null, null)
            list.adapter = adapter
        }

        fun loading() {
            state.isFocusable = false
            StateStamp.show(state, R.string.state_loading)
        }

        fun onStateClick(action: () -> Unit) {
            state.background = root.context.getDrawable(R.drawable.bg_button_ghost)
            val pad = root.resources.getDimensionPixelSize(R.dimen.space_12)
            state.setPadding(pad * 2, pad, pad * 2, pad)
            state.setOnClickListener { action() }
        }

        /** A focusable stamp that invites an action, such as connecting an account. */
        fun prompt(textRes: Int) {
            adapter.submit(emptyList())
            state.isFocusable = true
            StateStamp.show(state, textRes)
        }

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
