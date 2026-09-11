package com.flox.tv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import androidx.recyclerview.widget.RecyclerView
import com.flox.tv.R
import com.flox.tv.data.Season

class SeasonAdapter(private val onSelect: (Int) -> Unit) : RecyclerView.Adapter<SeasonAdapter.VH>() {

    var seasons: List<Season> = emptyList()
    var selected: Int = -1
        private set

    fun select(number: Int) {
        val old = indexOf(selected)
        selected = number
        val new = indexOf(number)
        if (old >= 0) notifyItemChanged(old)
        if (new >= 0) notifyItemChanged(new)
    }

    fun indexOf(number: Int): Int = seasons.indexOfFirst { it.number == number }

    override fun getItemCount(): Int = seasons.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_season_pill, parent, false) as Button
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = seasons[position]
        val ctx = holder.button.context
        holder.button.text = ctx.getString(R.string.season_fmt, s.number)
        holder.button.setTextColor(ctx.getColor(if (s.number == selected) R.color.text_primary else R.color.text_secondary))
        holder.button.setOnClickListener { onSelect(s.number) }
    }

    class VH(val button: Button) : RecyclerView.ViewHolder(button)
}
