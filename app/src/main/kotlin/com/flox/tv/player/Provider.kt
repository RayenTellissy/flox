package com.flox.tv.player

import com.flox.tv.data.MediaType

/** Embed providers in fallback order. Hosts feed the ad-block allowlist. */
enum class Provider(val label: String, val hosts: Set<String>) {
    VIDLOVE("111MOVIES", setOf("111movies.net", "vidlove.cc")),
    VIDFAST(
        "VIDFAST",
        setOf("vidfast.vc", "vidfast.pro", "vidfast.in", "vidfast.io", "vidfast.me", "vidfast.net", "vidfast.pm", "vidfast.xyz", "vidfast.bz")
    );

    fun url(id: Int, type: MediaType, season: Int, episode: Int, startAt: Int): String = when (this) {
        VIDLOVE ->
            if (type == MediaType.TV) "https://111movies.net/tv/$id/$season/$episode"
            else "https://111movies.net/movie/$id"
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
