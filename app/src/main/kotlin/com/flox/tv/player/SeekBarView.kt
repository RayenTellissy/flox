package com.flox.tv.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import com.flox.tv.R
import com.flox.tv.data.Settings

/** Flat square seek bar: hairline track, buffered band, white played band, block thumb while focused. */
class SeekBarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var onScrub: ((seconds: Int) -> Unit)? = null
    var stepSeconds = Settings.DEFAULT_SEEK_STEP_SECONDS

    private var position = 0L
    private var duration = 0L
    private var buffered = 0L

    private val track = Paint().apply { color = ctx.getColor(R.color.hairline_strong) }
    private val buffer = Paint().apply { color = ctx.getColor(R.color.text_disabled) }
    private val played = Paint().apply { color = ctx.getColor(R.color.text_primary) }
    private val density = resources.displayMetrics.density

    init {
        isFocusable = true
        isFocusableInTouchMode = false
        background = ctx.getDrawable(R.drawable.bg_focusable)
    }

    fun update(positionMs: Long, durationMs: Long, bufferedMs: Long) {
        position = positionMs
        duration = durationMs
        buffered = bufferedMs
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = 8 * density
        val left = pad
        val right = width - pad
        val midY = height / 2f
        val half = 1 * density
        canvas.drawRect(left, midY - half, right, midY + half, track)
        if (duration <= 0) return
        val span = right - left
        val bufX = left + span * (buffered.coerceIn(0, duration).toFloat() / duration)
        val posX = left + span * (position.coerceIn(0, duration).toFloat() / duration)
        canvas.drawRect(left, midY - half, bufX, midY + half, buffer)
        canvas.drawRect(left, midY - half, posX, midY + half, played)
        if (isFocused) {
            val tw = 2 * density
            val th = 8 * density
            canvas.drawRect(posX - tw, midY - th, posX + tw, midY + th, played)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val step = PlayerControls.seekStep(stepSeconds, event.repeatCount)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { onScrub?.invoke(-step); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { onScrub?.invoke(step); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
