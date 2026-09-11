package com.flox.tv.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.flox.tv.R
import com.flox.tv.data.ImageLoader
import com.flox.tv.data.Tmdb

class PosterAdapter(private val onClick: (CardItem) -> Unit) : RecyclerView.Adapter<PosterAdapter.VH>() {

    private val items = ArrayList<CardItem>()

    init {
        setHasStableIds(true)
    }

    fun submit(list: List<CardItem>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemId(position: Int): Long = items[position].stableId

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false)
        return VH(view, onClick)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    class VH(view: View, onClick: (CardItem) -> Unit) : RecyclerView.ViewHolder(view) {
        private val poster: ImageView = view.findViewById(R.id.poster)
        private val progress: ProgressBarView = view.findViewById(R.id.progress)
        private val title: TextView = view.findViewById(R.id.title)
        private val meta: TextView = view.findViewById(R.id.meta)
        private var item: CardItem? = null

        init {
            view.setOnClickListener { item?.let(onClick) }
        }

        fun bind(c: CardItem) {
            item = c
            title.text = c.title
            meta.text = c.eyebrow(itemView.context)
            ImageLoader.load(poster, Tmdb.poster(c.posterPath))
            if (c is CardItem.Continue) {
                progress.fraction = c.progress.fraction
                progress.visibility = View.VISIBLE
            } else {
                progress.visibility = View.GONE
            }
        }
    }
}
