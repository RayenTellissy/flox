package com.flox.tv.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.flox.tv.BuildConfig
import com.flox.tv.R
import com.flox.tv.telegram.Library
import com.flox.tv.telegram.TdDataSource
import com.flox.tv.telegram.Telegram

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
    private val loudness = Loudness()
    private var startAtSec = 0
    private var startApplied = false
    private var libraryEntry: Library.Entry? = null
    // the language picked from the audio list carries over to the next file this session
    private var preferredAudioLanguage: String? = null

    data class AudioTrack(val label: String, val language: String?, val selected: Boolean)

    val active get() = player != null
    /** Fires when the loaded tracks change, so the overlay can show or hide the subtitles button. */
    var onTracksChanged: (() -> Unit)? = null

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

        override fun onAudioSessionIdChanged(audioSessionId: Int) = loudness.attach(audioSessionId)

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) player?.let { loudness.attach(it.audioSessionId) }
            val p = player ?: return
            if (state == Player.STATE_READY && !startApplied) {
                startApplied = true
                val duration = p.duration
                if (startAtSec > 0 && (duration <= 0 || startAtSec * 1000L < duration - 5000L)) p.seekTo(startAtSec * 1000L)
            }
            if (state == Player.STATE_ENDED) ticker.run()
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            onTracksChanged?.invoke()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (BuildConfig.DEBUG) Log.d("FloxNative", "error ${error.errorCodeName}: ${error.message} cause=${error.cause}")
            onFailed(error.errorCodeName)
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, evaluation: DecoderReuseEvaluation?) {
            if (BuildConfig.DEBUG) Log.d("FloxAudio", "audio ${format.sampleMimeType} ${format.channelCount}ch ${format.sampleRate}Hz")
        }

        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, name: String, initializedAt: Long, initDuration: Long) {
            if (BuildConfig.DEBUG) Log.d("FloxAudio", "audio decoder $name")
        }

        override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, error: Exception) {
            if (BuildConfig.DEBUG) Log.d("FloxAudio", "audio sink error", error)
        }

        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, error: Exception) {
            if (BuildConfig.DEBUG) Log.d("FloxAudio", "audio codec error", error)
        }
    }

    fun start(manifest: PlayerBridge.Manifest, captions: List<PlayerBridge.Caption>, startAt: Int) {
        stop()
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
        launch(http, item, startAt)
    }

    /** Plays a file from the Telegram library through the stitching data source. */
    fun startLibrary(entry: Library.Entry, subtitlePath: String?, startAt: Int) {
        stop()
        val subtitles = subtitlePath?.let { path ->
            val mime = if (path.endsWith(".vtt", true)) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP
            listOf(
                MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(java.io.File(path)))
                    .setMimeType(mime)
                    .setLabel("English")
                    .setLanguage("en")
                    .build()
            )
        } ?: emptyList()
        libraryEntry = entry
        val k = entry.key
        val item = MediaItem.Builder()
            .setUri("tg://library/${k.tmdb}/${k.type.tmdb}/${k.season}/${k.episode}/${Uri.encode(entry.label)}")
            .setSubtitleConfigurations(subtitles)
            .build()
        launch(TdDataSource.Factory(ctx, entry), item, startAt, allowSoftwareHevc = true)
    }

    private fun launch(factory: androidx.media3.datasource.DataSource.Factory, item: MediaItem, startAt: Int, allowSoftwareHevc: Boolean = false) {
        startAtSec = startAt
        startApplied = false
        // page streams stay at 1080p; a library file plays at whatever it was uploaded in
        val selector = DefaultTrackSelector(ctx).apply {
            setParameters(
                buildUponParameters()
                    .apply { if (!allowSoftwareHevc) setMaxVideoSize(1920, 1080) }
                    .setPreferredTextLanguage(null)
                    .apply { preferredAudioLanguage?.let { setPreferredAudioLanguage(it) } }
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            )
        }
        // no hardware HEVC decoder: hide HEVC so an HEVC-only page source falls back to the page player.
        // Library files have no other quality, so any decoder is better than nothing there.
        val noHevc = !allowSoftwareHevc && !Codecs.hasHevcDecoder()
        val renderers = FloxRenderersFactory(ctx)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector { mime, secure, tunneling ->
                var infos = MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                // the emulator's goldfish decoders render with swapped chroma; debug builds prefer the software ones
                if (BuildConfig.DEBUG && infos.any { !it.name.contains("goldfish") }) infos = infos.filter { !it.name.contains("goldfish") }
                if (noHevc && mime.equals(MimeTypes.VIDEO_H265, true)) emptyList() else infos
            }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, 90_000, 2_500, 5_000)
            .setTargetBufferBytes(48 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val p = ExoPlayer.Builder(ctx, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .build()
        p.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true
        )
        p.addListener(listener)
        p.addAnalyticsListener(analytics)
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

    /** Software gain for boxes whose device volume is fixed; 0..1. */
    var volume: Float
        get() = player?.volume ?: 1f
        set(value) { player?.volume = value.coerceIn(0f, 1f) }

    fun currentSeconds(): Int = ((player?.currentPosition ?: 0L) / 1000L).toInt()
    fun positionMs(): Long = player?.currentPosition ?: 0L
    fun durationMs(): Long = player?.duration?.takeIf { it > 0 } ?: 0L
    fun bufferedMs(): Long = player?.bufferedPosition ?: 0L

    fun hasSubtitles(): Boolean =
        player?.currentTracks?.groups?.any { it.type == C.TRACK_TYPE_TEXT && it.length > 0 } == true

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

    // one entry per distinct language, codec and layout; a bitrate ladder of the same kind stays one adaptive entry
    private class AudioOption(val group: Tracks.Group, val tracks: List<Int>) {
        val format: Format get() = group.getTrackFormat(tracks.first())
        val selected get() = group.isSelected && tracks.any { group.isTrackSelected(it) }
    }

    private fun audioOptions(): List<AudioOption> =
        player?.currentTracks?.groups.orEmpty()
            .filter { it.type == C.TRACK_TYPE_AUDIO }
            .flatMap { group ->
                (0 until group.length)
                    .filter { group.isTrackSupported(it) }
                    .groupBy { group.getTrackFormat(it).let { f -> Triple(f.language, f.sampleMimeType ?: f.codecs, f.channelCount) } }
                    .values
                    .map { AudioOption(group, it) }
            }

    /** Language, name, codec and channels per track, like VLC's audio track menu. */
    fun audioTracks(): List<AudioTrack> = audioOptions().mapIndexed { i, option ->
        val f = option.format
        AudioTrack(audioLabel(f, i + 1), f.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }, option.selected)
    }

    /** Pins the audio track at [index]; returns it. */
    fun selectAudio(index: Int): AudioTrack? {
        val p = player ?: return null
        val option = audioOptions().getOrNull(index) ?: return null
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.tracks))
            .build()
        val track = audioTracks()[index].copy(selected = true)
        track.language?.let { preferredAudioLanguage = it }
        return track
    }

    private fun audioLabel(f: Format, n: Int): String {
        val language = f.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
            ?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.ENGLISH).ifBlank { it } }
        val name = f.label?.takeIf { it.isNotBlank() && (language == null || !it.contains(language, true)) }
        val head = listOfNotNull(language, name).joinToString(" · ").ifBlank { ctx.getString(R.string.player_audio_track_fmt, n) }
        return listOfNotNull(head, codecName(f), channelName(f.channelCount)).joinToString(" · ")
    }

    private fun codecName(f: Format): String? = when (f.sampleMimeType ?: f.codecs?.let { MimeTypes.getAudioMediaMimeType(it) }) {
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_AC3 -> "AC3"
        MimeTypes.AUDIO_E_AC3 -> "E-AC3"
        MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3 Atmos"
        MimeTypes.AUDIO_AC4 -> "AC4"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
        MimeTypes.AUDIO_DTS_X -> "DTS:X"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_ALAC -> "ALAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_MPEG_L2 -> "MP2"
        MimeTypes.AUDIO_RAW -> "PCM"
        else -> null
    }

    private fun channelName(count: Int): String? = when (count) {
        Format.NO_VALUE, 0 -> null
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        else -> "${count}ch"
    }

    fun stop() {
        main.removeCallbacks(ticker)
        val p = player ?: return
        player = null
        view.player = null
        loudness.release()
        p.removeListener(listener)
        p.removeAnalyticsListener(analytics)
        p.release()
        libraryEntry?.parts?.forEach { Telegram.deleteLocal(it.fileId) }
        libraryEntry = null
    }

    private companion object {
        const val TICK_MS = 2000L
        val RESERVED_HEADERS = setOf("host", "content-length", "connection", "accept-encoding", "user-agent", "cookie")
    }
}
