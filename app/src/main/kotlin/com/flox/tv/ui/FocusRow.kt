package com.flox.tv.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager

/** Horizontal row that restores focus to its last focused child and traps LEFT/RIGHT. */
class FocusRow @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : PlainRecyclerView(context, attrs) {

    private var lastFocused = 0

    init {
        layoutManager = LinearLayoutManager(context, HORIZONTAL, false)
    }

    fun preferPosition(position: Int) {
        lastFocused = position
    }

    private fun preferred(): View? =
        findViewHolderForAdapterPosition(lastFocused)?.itemView?.takeIf { it.isFocusable }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        if (child != null) {
            val pos = getChildAdapterPosition(child)
            if (pos != NO_POSITION) lastFocused = pos
        }
    }

    override fun addFocusables(views: ArrayList<View>?, direction: Int, focusableMode: Int) {
        if (views == null || hasFocus()) {
            super.addFocusables(views, direction, focusableMode)
            return
        }
        val target = preferred()
        if (target != null) views.add(target) else super.addFocusables(views, direction, focusableMode)
    }

    override fun onRequestFocusInDescendants(direction: Int, previouslyFocusedRect: Rect?): Boolean {
        val target = preferred()
        if (target != null && target.requestFocus()) return true
        return super.onRequestFocusInDescendants(direction, previouslyFocusedRect)
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        val result = super.focusSearch(focused, direction)
        val horizontal = direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT
        if (horizontal && (result == null || !contains(result))) return focused
        return result
    }

    private fun contains(v: View): Boolean {
        var p = v.parent
        while (p != null) {
            if (p === this) return true
            p = p.parent
        }
        return false
    }
}
