package com.flox.tv.player

import android.media.MediaCodecList
import android.os.Build

object Codecs {
    /** True when a hardware HEVC decoder exists. Software decoders cannot keep up on low-end boxes. */
    fun hasHevcDecoder(): Boolean = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
            !info.isEncoder &&
                info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) } &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || info.isHardwareAccelerated)
        }
    }.getOrDefault(true)
}
