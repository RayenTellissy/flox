package com.flox.tv.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.flox.tv.BuildConfig
import com.flox.tv.R
import com.flox.tv.data.AspectMode
import com.flox.tv.data.LibrarySort
import com.flox.tv.data.ProgressStore
import com.flox.tv.data.ResumeMode
import com.flox.tv.data.Settings
import com.flox.tv.data.SubtitleSize
import com.flox.tv.telegram.Library
import com.flox.tv.telegram.Telegram
import com.flox.tv.telegram.TelegramLoginActivity

/** Vertical list of settings rows. CENTER opens a picker or toggles, LEFT and RIGHT cycle through values. */
class SettingsActivity : Activity() {

    private companion object {
        val QUALITIES = listOf("2160p", "1440p", "1080p", "720p", "576p", "480p")
        val LANGUAGES = listOf(
            "en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German",
            "it" to "Italian", "pt" to "Portuguese", "ru" to "Russian", "ar" to "Arabic",
            "hi" to "Hindi", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "tr" to "Turkish"
        )
    }

    /** One row: [value] renders the current state, [open] runs on CENTER, [step] cycles on LEFT or RIGHT. */
    private class Item(
        val label: Int,
        val value: () -> String,
        val open: (Item) -> Unit,
        val step: ((Int) -> Unit)? = null
    ) {
        var valueView: TextView? = null
        fun refresh() { valueView?.text = value() }
    }

    private lateinit var list: LinearLayout
    private val items = mutableListOf<Item>()
    private var historyCleared = false
    private val authListener: (Telegram.Auth) -> Unit = { runOnUiThread { items.forEach { it.refresh() } } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        setContentView(R.layout.activity_settings)
        findViewById<ScrollView>(R.id.settings_scroll).isSmoothScrollingEnabled = false
        list = findViewById(R.id.settings_list)
        build()
        list.getChildAt(1)?.requestFocus()
        if (Telegram.configured) Telegram.addAuthListener(authListener)
    }

    override fun onDestroy() {
        Telegram.removeAuthListener(authListener)
        super.onDestroy()
    }

    private fun build() {
        section(R.string.settings_section_playback)
        choice(R.string.settings_quality, qualities(), { Settings.preferredQuality }, { q ->
            Settings.preferredQuality = q
            Library.preferredQuality = q.orEmpty()
        }) { it ?: getString(R.string.settings_highest) }
        choice(R.string.settings_audio_language, listOf<String?>(null) + LANGUAGES.map { it.first }, { Settings.audioLanguage }, { Settings.audioLanguage = it }) {
            languageName(it, R.string.settings_any)
        }
        toggle(R.string.settings_subtitles, { Settings.subtitlesEnabled }) { Settings.subtitlesEnabled = it }
        choice(R.string.settings_subtitle_language, listOf<String?>(null) + LANGUAGES.map { it.first }, { Settings.subtitleLanguage }, { Settings.subtitleLanguage = it }) {
            languageName(it, R.string.settings_device_language)
        }
        choice(R.string.settings_subtitle_size, SubtitleSize.values().toList(), { Settings.subtitleSize }, { Settings.subtitleSize = it }) {
            getString(when (it) {
                SubtitleSize.SMALL -> R.string.settings_size_small
                SubtitleSize.NORMAL -> R.string.settings_size_normal
                SubtitleSize.LARGE -> R.string.settings_size_large
            })
        }
        choice(R.string.settings_speed, Settings.PLAYBACK_SPEEDS, { Settings.playbackSpeed }, { Settings.playbackSpeed = it }) {
            getString(R.string.settings_speed_fmt, speed(it))
        }
        choice(R.string.settings_aspect, AspectMode.values().toList(), { Settings.aspectMode }, { Settings.aspectMode = it }) {
            getString(when (it) {
                AspectMode.FIT -> R.string.settings_aspect_fit
                AspectMode.FILL -> R.string.settings_aspect_fill
                AspectMode.ZOOM -> R.string.settings_aspect_zoom
            })
        }
        toggle(R.string.settings_autoplay, { Settings.autoplayNext }) { Settings.autoplayNext = it }
        choice(R.string.settings_seek_step, Settings.SEEK_STEPS, { Settings.seekStepSeconds }, { Settings.seekStepSeconds = it }) {
            getString(R.string.settings_seconds_fmt, it)
        }
        choice(R.string.settings_resume, ResumeMode.values().toList(), { Settings.resumeMode }, { Settings.resumeMode = it }) {
            getString(when (it) {
                ResumeMode.ALWAYS -> R.string.settings_resume_always
                ResumeMode.ASK -> R.string.settings_resume_ask
                ResumeMode.NEVER -> R.string.settings_resume_never
            })
        }
        choice(R.string.settings_finished, Settings.FINISHED_THRESHOLDS, { Settings.finishedThresholdPercent }, { Settings.finishedThresholdPercent = it }) {
            getString(R.string.settings_percent_fmt, it)
        }

        section(R.string.settings_section_audio)
        toggle(R.string.settings_loudness, { Settings.loudnessBoost }) { Settings.loudnessBoost = it }
        val gains = (Settings.LOUDNESS_GAIN_MIN_DB.toInt()..Settings.LOUDNESS_GAIN_MAX_DB.toInt()).map { it.toFloat() }
        choice(R.string.settings_loudness_gain, gains, { Math.round(Settings.loudnessGainDb).toFloat() }, { Settings.loudnessGainDb = it }) {
            getString(R.string.settings_db_fmt, it.toInt())
        }

        section(R.string.settings_section_interface)
        choice(R.string.settings_overlay_hide, Settings.OVERLAY_HIDE_OPTIONS, { Settings.overlayHideMs }, { Settings.overlayHideMs = it }) {
            getString(R.string.settings_seconds_fmt, it / 1000)
        }

        section(R.string.settings_section_library)
        choice(R.string.settings_sort, LibrarySort.values().toList(), { Settings.librarySort }, { Settings.librarySort = it }) {
            getString(when (it) {
                LibrarySort.TITLE -> R.string.settings_sort_title
                LibrarySort.DATE_ADDED -> R.string.settings_sort_date
                LibrarySort.SIZE -> R.string.settings_sort_size
            })
        }
        add(Item(R.string.settings_channel, { Settings.telegramChannel.uppercase() }, { item -> editChannel(item) }))
        choice(R.string.settings_continue_limit, Settings.CONTINUE_WATCHING_LIMITS, { Settings.continueWatchingLimit }, { Settings.continueWatchingLimit = it }) {
            it.toString()
        }
        add(Item(R.string.settings_clear_history, { if (historyCleared) getString(R.string.settings_cleared) else "" }, { item ->
            confirm(R.string.settings_clear_history, R.string.settings_clear_history_confirm) {
                ProgressStore.clear(this)
                historyCleared = true
                item.refresh()
            }
        }))
        if (Telegram.configured) {
            add(Item(R.string.settings_sign_out, { getString(if (Telegram.ready) R.string.settings_signed_in else R.string.settings_signed_out) }, { item ->
                if (Telegram.ready) {
                    confirm(R.string.settings_sign_out, R.string.settings_sign_out_confirm) {
                        Telegram.logout()
                        Library.clear()
                        item.refresh()
                    }
                } else {
                    startActivity(Intent(this, TelegramLoginActivity::class.java))
                }
            }))
        }

        section(R.string.settings_section_about)
        add(Item(R.string.settings_version, { "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" }, {}))
    }

    /** Fixed common qualities merged with any quality currently uploaded to the library, highest first. */
    private fun qualities(): List<String?> {
        val known = (QUALITIES + Library.entries.values.flatten().map { it.quality })
            .filter { it.isNotBlank() }
            .distinct()
            .sortedByDescending { it.substringBefore('p').toIntOrNull() ?: 0 }
        val current = Settings.preferredQuality
        val all = if (current != null && current !in known) known + current else known
        return listOf<String?>(null) + all
    }

    private fun languageName(code: String?, emptyRes: Int): String =
        if (code == null) getString(emptyRes)
        else (LANGUAGES.firstOrNull { it.first == code }?.second ?: code).uppercase()

    private fun speed(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString().trimEnd('0')

    private fun section(label: Int) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_setting_section, list, false) as TextView
        view.setText(label)
        list.addView(view)
    }

    private fun toggle(label: Int, get: () -> Boolean, set: (Boolean) -> Unit) {
        lateinit var item: Item
        val flip = { set(!get()); item.refresh() }
        item = Item(label, { getString(if (get()) R.string.settings_on else R.string.settings_off) }, { flip() }, { flip() })
        add(item)
    }

    private fun <T> choice(label: Int, options: List<T>, get: () -> T, set: (T) -> Unit, name: (T) -> String) {
        lateinit var item: Item
        val cycle = { delta: Int ->
            val index = options.indexOf(get()).coerceAtLeast(0)
            set(options[(index + delta).mod(options.size)])
            item.refresh()
        }
        item = Item(label, { name(get()) }, {
            val labels = options.map(name).toTypedArray()
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(label)
                .setSingleChoiceItems(labels, options.indexOf(get())) { dialog, which ->
                    set(options[which])
                    item.refresh()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.settings_cancel, null)
                .show()
        }, cycle)
        add(item)
    }

    private fun add(item: Item) {
        val row = LayoutInflater.from(this).inflate(R.layout.item_setting_row, list, false)
        row.findViewById<TextView>(R.id.setting_label).setText(item.label)
        item.valueView = row.findViewById(R.id.setting_value)
        item.refresh()
        row.setOnClickListener { item.open(item) }
        row.setOnKeyListener { _, keyCode, event ->
            val delta = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> 0
            }
            val step = item.step
            if (delta == 0 || step == null) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_DOWN) step(delta)
            true
        }
        list.addView(row)
        items.add(item)
    }

    private fun confirm(title: Int, message: Int, action: () -> Unit) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(R.string.settings_ok) { _, _ -> action() }
            .setNegativeButton(R.string.settings_cancel, null)
            .show()
    }

    private fun editChannel(item: Item) {
        val input = EditText(this).apply {
            setText(Settings.telegramChannel)
            setSelection(text.length)
            hint = getString(R.string.settings_channel_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_muted))
            background = getDrawable(R.drawable.bg_input)
            val pad = resources.getDimensionPixelSize(R.dimen.space_12)
            setPadding(pad, pad, pad, pad)
        }
        val frame = FrameLayout(this).apply {
            val pad = resources.getDimensionPixelSize(R.dimen.space_24)
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val save = {
            val text = input.text.toString().trim()
            Settings.telegramChannel = text.ifEmpty { Settings.DEFAULT_TELEGRAM_CHANNEL }
            item.refresh()
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(R.string.settings_channel)
            .setView(frame)
            .setPositiveButton(R.string.settings_ok) { _, _ -> save() }
            .setNegativeButton(R.string.settings_cancel, null)
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
            save()
            dialog.dismiss()
            true
        }
        dialog.setOnShowListener { input.requestFocus() }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }
}
