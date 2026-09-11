package com.flox.tv.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import com.flox.tv.R
import com.flox.tv.data.Tmdb
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchActivity : Activity() {

    private val scope = MainScope()
    private var job: Job? = null
    private lateinit var input: EditText
    private lateinit var grid: PlainRecyclerView
    private lateinit var state: TextView
    private val adapter = PosterAdapter { openDetails(it) }
    private var lastQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        setContentView(R.layout.activity_search)
        input = findViewById(R.id.input)
        grid = findViewById(R.id.grid)
        state = findViewById(R.id.state)

        val cell = dp(160 + 16)
        val span = ((resources.displayMetrics.widthPixels - dp(96)) / cell).coerceAtLeast(1)
        grid.layoutManager = GridLayoutManager(this, span)
        grid.setHasFixedSize(true)
        grid.adapter = adapter

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = schedule(s?.toString().orEmpty(), 400L)
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                schedule(input.text.toString(), 0L)
                true
            } else {
                false
            }
        }
        input.requestFocus()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBackPressed() {
        if (currentFocus !== input && adapter.itemCount > 0) input.requestFocus() else super.onBackPressed()
    }

    private fun schedule(raw: String, delayMs: Long) {
        val query = raw.trim()
        job?.cancel()
        if (query.isEmpty()) {
            lastQuery = ""
            adapter.submit(emptyList())
            StateStamp.hide(state)
            return
        }
        job = scope.launch {
            if (delayMs > 0) delay(delayMs)
            if (query == lastQuery) return@launch
            lastQuery = query
            StateStamp.show(state, R.string.state_loading)
            Tmdb.search(query)
                .onSuccess { list ->
                    adapter.submit(list.map { CardItem.Media(it) })
                    if (list.isEmpty()) StateStamp.show(state, R.string.state_empty) else StateStamp.hide(state)
                }
                .onFailure {
                    adapter.submit(emptyList())
                    StateStamp.show(state, R.string.state_error)
                }
        }
    }

    private fun openDetails(card: CardItem) {
        startActivity(
            Intent(this, DetailsActivity::class.java)
                .putExtra(DetailsActivity.EXTRA_ID, card.id)
                .putExtra(DetailsActivity.EXTRA_TYPE, card.type.tmdb)
        )
    }
}
