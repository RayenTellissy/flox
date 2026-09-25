package com.flox.tv.player

import android.media.audiofx.DynamicsProcessing
import android.util.Log
import com.flox.tv.BuildConfig

/**
 * Makes the native player louder without clipping: a pre-gain followed by a brickwall limiter
 * so peaks that would overshoot full scale are caught instead of distorting. TV speakers are quiet
 * and film mixes sit well below full scale, so a few dB of headroom is nearly always available.
 */
class Loudness(private val gainDb: Float) {
    private var effect: DynamicsProcessing? = null
    private var session = 0

    fun attach(audioSessionId: Int) {
        if (audioSessionId == 0 || (audioSessionId == session && effect != null)) return
        release()
        session = audioSessionId
        runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, CHANNELS,
                false, 1, false, 1, false, 1, true
            ).setPreferredFrameDuration(10f).build()
            val dp = DynamicsProcessing(0, audioSessionId, config)
            dp.setInputGainAllChannelsTo(gainDb)
            for (ch in 0 until CHANNELS) {
                val limiter = dp.getLimiterByChannelIndex(ch)
                limiter.isEnabled = true
                limiter.attackTime = 1f
                limiter.releaseTime = 60f
                limiter.ratio = 10f
                limiter.threshold = LIMIT_DB
                limiter.postGain = 0f
                dp.setLimiterByChannelIndex(ch, limiter)
            }
            dp.enabled = true
            effect = dp
            if (BuildConfig.DEBUG) Log.d("FloxAudio", "loudness attached to session $audioSessionId")
        }.onFailure { if (BuildConfig.DEBUG) Log.d("FloxAudio", "loudness unavailable", it) }
    }

    fun release() {
        effect?.let { runCatching { it.enabled = false; it.release() } }
        effect = null
        session = 0
    }

    private companion object {
        const val CHANNELS = 2
        const val LIMIT_DB = -1f
    }
}
