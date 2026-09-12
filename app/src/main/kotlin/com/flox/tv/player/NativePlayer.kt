package com.flox.tv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.flox.tv.BuildConfig

/**
 * Plays the manifest the page resolved, with the page's request headers, in ExoPlayer.
 * Feeds progress into the same bridge as the page player.
 */
@UnstableApi
class NativePlayer(
    private val ctx: Context,
    private val view: PlayerView,
    private val bridge: PlayerBridge,
    private val userAgent: String,
    private val onFirstFrame: () -> Unit,
    private val onFailed: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var startAtSec = 0
    private var startApplied = false

    val active get() = player != null

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            val duration = p.duration
            if (duration > 0) {
                bridge.tick(
                    time = p.currentPosition / 1000.0,
                    duration = duration / 1000.0,
                    paused = !p.isPlaying,
                    ended = p.playbackState == Player.STATE_ENDED
                )
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() = onFirstFrame()

        override fun onPlaybackStateChanged(state: Int) {
            val p = player ?: return
            if (state == Player.STATE_READY && !startApplied) {
                startApplied = true
                val duration = p.duration
                if (startAtSec > 0 && (duration <= 0 || startAtSec * 1000L < duration - 5000L)) p.seekTo(startAtSec * 1000L)
            }
            if (state == Player.STATE_ENDED) ticker.run()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (BuildConfig.DEBUG) Log.d("FloxNative", "error ${error.errorCodeName}: ${error.message}")
            onFailed(error.errorCodeName)
        }
    }

    fun start(manifest: PlayerBridge.Manifest, captions: List<PlayerBridge.Caption>, startAt: Int) {
        stop()
        startAtSec = startAt
        startApplied = false
        val headers = mutableMapOf(
            "Referer" to "https://${Provider.HOST}/",
            "Origin" to "https://${Provider.HOST}"
        )
        manifest.headers.forEach { (k, v) -> if (k.lowercase() !in RESERVED_HEADERS) headers[k] = v }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val selector = DefaultTrackSelector(ctx).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSize(1920, 1080)
                    .setPreferredTextLanguage(null)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            )
        }
        // no hardware HEVC decoder: hide HEVC entirely so an HEVC-only source fails fast and falls back to the page
        val noHevc = !Codecs.hasHevcDecoder()
        val renderers = DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setMediaCodecSelector { mime, secure, tunneling ->
                val infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                if (noHevc && mime.equals(MimeTypes.VIDEO_H265, true)) emptyList() else infos
            }
        val preferred = java.util.Locale.getDefault().getDisplayLanguage(java.util.Locale.ENGLISH)
        val ordered = captions.sortedBy { if (it.language.equals(preferred, true)) 0 else 1 }
        val subtitles = ordered.mapNotNull { c ->
            val mime = when {
                c.type.equals("vtt", true) || c.url.contains(".vtt") -> MimeTypes.TEXT_VTT
                c.type.equals("srt", true) || c.url.contains(".srt") -> MimeTypes.APPLICATION_SUBRIP
                else -> null
            } ?: return@mapNotNull null
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(c.url))
                .setMimeType(mime)
                .setLabel(c.language.ifBlank { "Subtitles" })
                .setSelectionFlags(0)
                .build()
        }
        val mime = if (manifest.kind == "dash") MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8
        val item = MediaItem.Builder()
            .setUri(manifest.url)
            .setMimeType(mime)
            .setSubtitleConfigurations(subtitles)
            .build()
        val p = ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setTrackSelector(selector)
            .build()
        p.addListener(listener)
        p.setMediaItem(item)
        p.playWhenReady = true
        p.prepare()
        view.player = p
        player = p
        main.removeCallbacks(ticker)
        main.postDelayed(ticker, TICK_MS)
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun play() { player?.play() }
    fun pause() { player?.pause() }
    fun isPlaying() = player?.isPlaying == true

    fun seekBy(seconds: Int) {
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + seconds * 1000L).coerceIn(0L, duration))
    }

    fun currentSeconds(): Int = ((player?.currentPosition ?: 0L) / 1000L).toInt()

    /** Steps through off and each subtitle track; returns the new track's label, or null for off. */
    fun cycleSubtitles(): String? {
        val p = player ?: return null
        val groups = p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }
        if (groups.isEmpty()) return null
        val current = groups.indexOfFirst { it.isSelected }
        val next = current + 1
        val params = p.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (next >= groups.size) {
            p.trackSelectionParameters = params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            return null
        }
        val group = groups[next]
        p.trackSelectionParameters = params
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
        return group.getTrackFormat(0).label ?: group.getTrackFormat(0).language ?: "Subtitles"
    }

    fun stop() {
        main.removeCallbacks(ticker)
        val p = player ?: return
        player = null
        view.player = null
        p.removeListener(listener)
        p.release()
    }

    private companion object {
        const val TICK_MS = 2000L
        val RESERVED_HEADERS = setOf("host", "content-length", "connection", "accept-encoding", "user-agent", "cookie")
    }
}
