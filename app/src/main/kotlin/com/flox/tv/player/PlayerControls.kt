package com.flox.tv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.util.UnstableApi
import com.flox.tv.R
import com.flox.tv.data.MediaType

/** Overlay owned by flox that replaces the stock Media3 controller. */
@UnstableApi
class PlayerControls @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {
    var onPlayPause: (() -> Unit)? = null
    var onSeekBy: ((Int) -> Unit)? = null
    /** True raises the volume, false lowers it. */
    var onVolume: ((Boolean) -> Unit)? = null
    var onAudio: (() -> Unit)? = null
    var onSubtitles: (() -> Unit)? = null
    var onQuality: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var native: NativePlayer? = null

    private val eyebrow: TextView
    private val title: TextView
    private val seek: SeekBarView
    private val position: TextView
    private val duration: TextView
    private val playPause: ImageButton
    private val extras: LinearLayout
    private val audio: ImageButton
    private val subtitles: ImageButton
    private val quality: ImageButton
    private val next: ImageButton
    private val tracks: View
    private val tracksHeading: TextView
    private val tracksList: LinearLayout
    private var tracksAnchor: View? = null

    private val hideLater = Runnable { hide() }
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, TICK_MS)
        }
    }

    init {
        LayoutInflater.from(ctx).inflate(R.layout.view_player_controls, this, true)
        eyebrow = findViewById(R.id.controls_eyebrow)
        title = findViewById(R.id.controls_title)
        seek = findViewById(R.id.controls_seek)
        position = findViewById(R.id.controls_position)
        duration = findViewById(R.id.controls_duration)
        playPause = findViewById(R.id.controls_play_pause)
        extras = findViewById(R.id.controls_extras)
        audio = findViewById(R.id.controls_audio)
        subtitles = findViewById(R.id.controls_subtitles)
        quality = findViewById(R.id.controls_quality)
        next = findViewById(R.id.controls_next)
        tracks = findViewById(R.id.controls_tracks)
        tracksHeading = findViewById(R.id.controls_tracks_heading)
        tracksList = findViewById(R.id.controls_tracks_list)
        seek.onScrub = { s -> onSeekBy?.invoke(s); touch() }
        playPause.setOnClickListener { onPlayPause?.invoke(); touch() }
        findViewById<ImageButton>(R.id.controls_rewind).setOnClickListener { onSeekBy?.invoke(-10); touch() }
        findViewById<ImageButton>(R.id.controls_forward).setOnClickListener { onSeekBy?.invoke(10); touch() }
        findViewById<ImageButton>(R.id.controls_volume_down).setOnClickListener { onVolume?.invoke(false); touch() }
        findViewById<ImageButton>(R.id.controls_volume_up).setOnClickListener { onVolume?.invoke(true); touch() }
        audio.setOnClickListener { onAudio?.invoke(); touch() }
        subtitles.setOnClickListener { onSubtitles?.invoke(); touch() }
        quality.setOnClickListener { onQuality?.invoke(); touch() }
        next.setOnClickListener { onNext?.invoke() }
        findViewById<ImageButton>(R.id.controls_back).setOnClickListener { onBack?.invoke() }
        visibility = GONE
    }

    fun bind(player: NativePlayer, meta: PlayerBridge.Meta) {
        native = player
        title.text = meta.title
        eyebrow.text = if (meta.type == MediaType.TV)
            context.getString(R.string.player_episode_stamp, meta.season, meta.episode)
        else context.getString(R.string.type_movie)
        refresh()
    }

    /** Shown only when the file carries more than one audio track. */
    fun setAudioAvailable(available: Boolean) = setAvailable(audio, available)

    fun setSubtitlesAvailable(available: Boolean) = setAvailable(subtitles, available)

    /** Shown only when the library holds more than one print of what is playing. */
    fun setQualityAvailable(available: Boolean) = setAvailable(quality, available)

    fun setNextAvailable(available: Boolean) = setAvailable(next, available)

    // the group keeps its leading gap only while one of its buttons is showing
    private fun setAvailable(button: View, available: Boolean) {
        button.visibility = if (available) VISIBLE else GONE
        extras.visibility = if ((0 until extras.childCount).any { extras.getChildAt(it).visibility == VISIBLE }) VISIBLE else GONE
    }

    val tracksShown get() = tracks.visibility == VISIBLE

    /** Lists tracks beside the overlay, VLC style; focus starts on the selected one and stays in the list. */
    fun showTracks(heading: String, labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
        tracksAnchor = findFocus()
        tracksHeading.text = heading
        tracksList.removeAllViews()
        val inflater = LayoutInflater.from(context)
        labels.forEachIndexed { i, label ->
            val row = inflater.inflate(R.layout.item_track_row, tracksList, false)
            row.findViewById<View>(R.id.track_marker).isActivated = i == selected
            row.findViewById<TextView>(R.id.track_label).apply {
                text = label
                setTextColor(context.getColor(if (i == selected) R.color.text_primary else R.color.text_secondary))
            }
            row.setOnClickListener {
                hideTracks()
                onPick(i)
                touch()
            }
            tracksList.addView(row)
        }
        tracks.visibility = VISIBLE
        tracksList.getChildAt(selected.coerceIn(0, labels.size - 1))?.requestFocus()
        touch()
    }

    fun hideTracks() {
        if (!tracksShown) return
        tracks.visibility = GONE
        tracksList.removeAllViews()
        (tracksAnchor ?: playPause).requestFocus()
        tracksAnchor = null
    }

    override fun focusSearch(focused: View?, direction: Int): View? {
        val found = super.focusSearch(focused, direction)
        if (!tracksShown) return found
        return if (found != null && isInTracks(found)) found else focused
    }

    private fun isInTracks(view: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === tracks) return true
            v = v.parent as? View
        }
        return false
    }

    val shown get() = visibility == VISIBLE

    fun show() {
        if (!shown) {
            visibility = VISIBLE
            refresh()
            main.removeCallbacks(ticker)
            main.post(ticker)
            playPause.requestFocus()
        }
        touch()
    }

    fun hide() {
        tracks.visibility = GONE
        tracksList.removeAllViews()
        tracksAnchor = null
        main.removeCallbacks(hideLater)
        main.removeCallbacks(ticker)
        visibility = GONE
    }

    /** Any interaction restarts the auto-hide timer. */
    fun touch() {
        main.removeCallbacks(hideLater)
        main.postDelayed(hideLater, HIDE_MS)
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        native = null
    }

    private fun refresh() {
        val p = native ?: return
        val dur = p.durationMs()
        seek.update(p.positionMs(), dur, p.bufferedMs())
        position.text = stamp(p.positionMs())
        duration.text = if (dur > 0) stamp(dur) else "--:--"
        playPause.setImageResource(if (p.isPlaying()) R.drawable.ic_player_pause else R.drawable.ic_player_play)
    }

    private fun stamp(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    override fun setVisibility(visibility: Int) {
        super.setVisibility(visibility)
        if (visibility != VISIBLE) main.removeCallbacks(ticker)
    }

    companion object {
        const val HIDE_MS = 4000L
        private const val TICK_MS = 250L

        /** 10 s per press, growing while the key is held. */
        fun seekStep(repeatCount: Int): Int = when {
            repeatCount < 4 -> 10
            repeatCount < 10 -> 30
            else -> 60
        }
    }
}
