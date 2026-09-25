package com.flox.tv.data

import android.content.Context
import android.content.SharedPreferences

enum class SubtitleSize { SMALL, NORMAL, LARGE }

enum class ResumeMode { ALWAYS, ASK, NEVER }

enum class AspectMode { FIT, FILL, ZOOM }

enum class LibrarySort { TITLE, DATE_ADDED, SIZE }

/**
 * Typed app settings backed by one SharedPreferences file. Call [init] before reading any value.
 * The preferred quality lives in the older "flox_player" file so the value persisted by the player keeps working.
 */
object Settings {
    private const val PREFS = "flox_settings"
    private const val PLAYER_PREFS = "flox_player"

    const val KEY_AUDIO_LANGUAGE = "audio_language"
    const val KEY_SUBTITLES_ENABLED = "subtitles_enabled"
    const val KEY_SUBTITLE_LANGUAGE = "subtitle_language"
    const val KEY_SUBTITLE_SIZE = "subtitle_size"
    const val KEY_PREFERRED_QUALITY = "quality"
    const val KEY_AUTOPLAY_NEXT = "autoplay_next"
    const val KEY_SEEK_STEP_SECONDS = "seek_step_seconds"
    const val KEY_LOUDNESS_BOOST = "loudness_boost"
    const val KEY_LOUDNESS_GAIN_DB = "loudness_gain_db"
    const val KEY_OVERLAY_HIDE_MS = "overlay_hide_ms"
    const val KEY_RESUME_MODE = "resume_mode"
    const val KEY_FINISHED_THRESHOLD_PERCENT = "finished_threshold_percent"
    const val KEY_PLAYBACK_SPEED = "playback_speed"
    const val KEY_ASPECT_MODE = "aspect_mode"
    const val KEY_LIBRARY_SORT = "library_sort"
    const val KEY_TELEGRAM_CHANNEL = "telegram_channel"
    const val KEY_CONTINUE_WATCHING_LIMIT = "continue_watching_limit"

    val DEFAULT_AUDIO_LANGUAGE: String? = null
    const val DEFAULT_SUBTITLES_ENABLED = false
    val DEFAULT_SUBTITLE_LANGUAGE: String? = null
    val DEFAULT_SUBTITLE_SIZE = SubtitleSize.NORMAL
    val DEFAULT_PREFERRED_QUALITY: String? = null
    const val DEFAULT_AUTOPLAY_NEXT = true
    const val DEFAULT_SEEK_STEP_SECONDS = 10
    const val DEFAULT_LOUDNESS_BOOST = true
    const val DEFAULT_LOUDNESS_GAIN_DB = 8f
    const val DEFAULT_OVERLAY_HIDE_MS = 4000
    val DEFAULT_RESUME_MODE = ResumeMode.ALWAYS
    const val DEFAULT_FINISHED_THRESHOLD_PERCENT = 95
    const val DEFAULT_PLAYBACK_SPEED = 1f
    val DEFAULT_ASPECT_MODE = AspectMode.FIT
    val DEFAULT_LIBRARY_SORT = LibrarySort.DATE_ADDED
    const val DEFAULT_TELEGRAM_CHANNEL = "Flox Library"
    const val DEFAULT_CONTINUE_WATCHING_LIMIT = 50

    val SEEK_STEPS = listOf(5, 10, 15, 30, 60)
    const val LOUDNESS_GAIN_MIN_DB = 0f
    const val LOUDNESS_GAIN_MAX_DB = 12f
    val OVERLAY_HIDE_OPTIONS = listOf(2000, 4000, 6000, 10000)
    val FINISHED_THRESHOLDS = listOf(85, 90, 95, 98)
    val PLAYBACK_SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    val CONTINUE_WATCHING_LIMITS = listOf(10, 25, 50, 100)

    private lateinit var app: Context

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    private val prefs: SharedPreferences get() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val playerPrefs: SharedPreferences get() = app.getSharedPreferences(PLAYER_PREFS, Context.MODE_PRIVATE)

    private val listeners = mutableSetOf<(String) -> Unit>()

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    private fun changed(key: String) = listeners.toList().forEach { it(key) }

    private fun edit(key: String, block: SharedPreferences.Editor.() -> Unit, target: SharedPreferences = prefs) {
        target.edit().apply(block).apply()
        changed(key)
    }

    private fun optString(key: String, target: SharedPreferences = prefs): String? =
        target.getString(key, null)?.takeIf { it.isNotEmpty() }

    private fun putOptString(key: String, value: String?, target: SharedPreferences = prefs) =
        edit(key, { if (value.isNullOrEmpty()) remove(key) else putString(key, value) }, target)

    private inline fun <reified T : Enum<T>> readEnum(key: String, default: T): T =
        prefs.getString(key, null)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default

    private fun writeEnum(key: String, value: Enum<*>) = edit(key, { putString(key, value.name) })

    private fun nearest(value: Int, allowed: List<Int>) = allowed.minByOrNull { kotlin.math.abs(it - value) } ?: value

    private fun nearest(value: Float, allowed: List<Float>) = allowed.minByOrNull { kotlin.math.abs(it - value) } ?: value

    var audioLanguage: String?
        get() = optString(KEY_AUDIO_LANGUAGE) ?: DEFAULT_AUDIO_LANGUAGE
        set(value) = putOptString(KEY_AUDIO_LANGUAGE, value)

    var subtitlesEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUBTITLES_ENABLED, DEFAULT_SUBTITLES_ENABLED)
        set(value) = edit(KEY_SUBTITLES_ENABLED, { putBoolean(KEY_SUBTITLES_ENABLED, value) })

    var subtitleLanguage: String?
        get() = optString(KEY_SUBTITLE_LANGUAGE) ?: DEFAULT_SUBTITLE_LANGUAGE
        set(value) = putOptString(KEY_SUBTITLE_LANGUAGE, value)

    var subtitleSize: SubtitleSize
        get() = readEnum(KEY_SUBTITLE_SIZE, DEFAULT_SUBTITLE_SIZE)
        set(value) = writeEnum(KEY_SUBTITLE_SIZE, value)

    var preferredQuality: String?
        get() = optString(KEY_PREFERRED_QUALITY, playerPrefs) ?: DEFAULT_PREFERRED_QUALITY
        set(value) = putOptString(KEY_PREFERRED_QUALITY, value, playerPrefs)

    var autoplayNext: Boolean
        get() = prefs.getBoolean(KEY_AUTOPLAY_NEXT, DEFAULT_AUTOPLAY_NEXT)
        set(value) = edit(KEY_AUTOPLAY_NEXT, { putBoolean(KEY_AUTOPLAY_NEXT, value) })

    var seekStepSeconds: Int
        get() = nearest(prefs.getInt(KEY_SEEK_STEP_SECONDS, DEFAULT_SEEK_STEP_SECONDS), SEEK_STEPS)
        set(value) = edit(KEY_SEEK_STEP_SECONDS, { putInt(KEY_SEEK_STEP_SECONDS, nearest(value, SEEK_STEPS)) })

    var loudnessBoost: Boolean
        get() = prefs.getBoolean(KEY_LOUDNESS_BOOST, DEFAULT_LOUDNESS_BOOST)
        set(value) = edit(KEY_LOUDNESS_BOOST, { putBoolean(KEY_LOUDNESS_BOOST, value) })

    var loudnessGainDb: Float
        get() = prefs.getFloat(KEY_LOUDNESS_GAIN_DB, DEFAULT_LOUDNESS_GAIN_DB).coerceIn(LOUDNESS_GAIN_MIN_DB, LOUDNESS_GAIN_MAX_DB)
        set(value) = edit(KEY_LOUDNESS_GAIN_DB, { putFloat(KEY_LOUDNESS_GAIN_DB, value.coerceIn(LOUDNESS_GAIN_MIN_DB, LOUDNESS_GAIN_MAX_DB)) })

    var overlayHideMs: Int
        get() = nearest(prefs.getInt(KEY_OVERLAY_HIDE_MS, DEFAULT_OVERLAY_HIDE_MS), OVERLAY_HIDE_OPTIONS)
        set(value) = edit(KEY_OVERLAY_HIDE_MS, { putInt(KEY_OVERLAY_HIDE_MS, nearest(value, OVERLAY_HIDE_OPTIONS)) })

    var resumeMode: ResumeMode
        get() = readEnum(KEY_RESUME_MODE, DEFAULT_RESUME_MODE)
        set(value) = writeEnum(KEY_RESUME_MODE, value)

    var finishedThresholdPercent: Int
        get() = nearest(prefs.getInt(KEY_FINISHED_THRESHOLD_PERCENT, DEFAULT_FINISHED_THRESHOLD_PERCENT), FINISHED_THRESHOLDS)
        set(value) = edit(KEY_FINISHED_THRESHOLD_PERCENT, { putInt(KEY_FINISHED_THRESHOLD_PERCENT, nearest(value, FINISHED_THRESHOLDS)) })

    var playbackSpeed: Float
        get() = nearest(prefs.getFloat(KEY_PLAYBACK_SPEED, DEFAULT_PLAYBACK_SPEED), PLAYBACK_SPEEDS)
        set(value) = edit(KEY_PLAYBACK_SPEED, { putFloat(KEY_PLAYBACK_SPEED, nearest(value, PLAYBACK_SPEEDS)) })

    var aspectMode: AspectMode
        get() = readEnum(KEY_ASPECT_MODE, DEFAULT_ASPECT_MODE)
        set(value) = writeEnum(KEY_ASPECT_MODE, value)

    var librarySort: LibrarySort
        get() = readEnum(KEY_LIBRARY_SORT, DEFAULT_LIBRARY_SORT)
        set(value) = writeEnum(KEY_LIBRARY_SORT, value)

    var telegramChannel: String
        get() = optString(KEY_TELEGRAM_CHANNEL) ?: DEFAULT_TELEGRAM_CHANNEL
        set(value) = putOptString(KEY_TELEGRAM_CHANNEL, value.trim())

    var continueWatchingLimit: Int
        get() = nearest(prefs.getInt(KEY_CONTINUE_WATCHING_LIMIT, DEFAULT_CONTINUE_WATCHING_LIMIT), CONTINUE_WATCHING_LIMITS)
        set(value) = edit(KEY_CONTINUE_WATCHING_LIMIT, { putInt(KEY_CONTINUE_WATCHING_LIMIT, nearest(value, CONTINUE_WATCHING_LIMITS)) })
}
