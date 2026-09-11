package com.flox.tv.ui

import android.content.Context
import android.util.AttributeSet
import android.view.animation.Interpolator
import androidx.recyclerview.widget.RecyclerView

/** RecyclerView with no animations and no self focus; scrolls instantly. */
open class PlainRecyclerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : RecyclerView(context, attrs) {

    init {
        itemAnimator = null
        isFocusable = false
        clipChildren = false
        clipToPadding = false
    }

    override fun smoothScrollBy(dx: Int, dy: Int, interpolator: Interpolator?, duration: Int) {
        scrollBy(dx, dy)
    }
}
