package com.flox.tv.player

import com.flox.tv.data.MediaType

/** Embed providers in fallback order. Hosts feed the ad-block allowlist. */
enum class Provider(val label: String, val hosts: Set<String>) {
    VIDKING("VIDKING", setOf("vidking.net", "videasy.to")),
    VIDFAST(
        "VIDFAST",
        setOf("vidfast.vc", "vidfast.pro", "vidfast.in", "vidfast.io", "vidfast.me", "vidfast.net", "vidfast.pm", "vidfast.xyz", "vidfast.bz")
    );

    fun url(id: Int, type: MediaType, season: Int, episode: Int, startAt: Int): String = when (this) {
        // start position is applied by the app once the video is ready; the progress param re-seeks on every load
        VIDKING ->
            if (type == MediaType.TV) "https://www.vidking.net/embed/tv/$id/$season/$episode?autoPlay=true&color=fafafa"
            else "https://www.vidking.net/embed/movie/$id?autoPlay=true&color=fafafa"
        VIDFAST -> {
            val base = if (type == MediaType.TV) "https://vidfast.vc/tv/$id/$season/$episode?autoPlay=true&theme=fafafa"
            else "https://vidfast.vc/movie/$id?autoPlay=true&theme=fafafa"
            if (startAt > 0) "$base&startAt=$startAt" else base
        }
    }

    fun next(): Provider? = entries.getOrNull(ordinal + 1)

    companion object {
        val ALL_HOSTS: Set<String> = entries.flatMap { it.hosts }.toSet()
    }
}
