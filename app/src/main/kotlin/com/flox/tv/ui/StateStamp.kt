package com.flox.tv.ui

import android.view.View
import android.widget.TextView

/** Shows or hides a centered mono state label. */
object StateStamp {
    fun show(view: TextView, textRes: Int) {
        view.setText(textRes)
        view.visibility = View.VISIBLE
    }

    fun hide(view: TextView) {
        view.visibility = View.GONE
    }
}
