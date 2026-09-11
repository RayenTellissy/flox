package com.flox.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.flox.tv.R

/** Flat 2dp progress line: filled rect at fraction of width. */
class ProgressBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply { color = context.getColor(R.color.text_primary) }

    var fraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width * fraction, height.toFloat(), paint)
    }
}
