package com.flox.tv.player

import com.flox.tv.data.MediaType

/** Embed providers in fallback order. Hosts feed the ad-block allowlist. */
enum class Provider(val label: String, val hosts: Set<String>) {
    VIDSRC("VIDSRC", setOf("vidsrc.su", "wyzie.ru", "wyzie.io")),
    VIDLINK("VIDLINK", setOf("vidlink.pro", "jwplayer.com"));

    fun url(id: Int, type: MediaType, season: Int, episode: Int, startAt: Int): String = when (this) {
        // no start param; the app seeks the video once it is ready
        VIDSRC ->
            if (type == MediaType.TV) "https://vidsrc.su/embed/tv/$id/$season/$episode?autoplay=true&colour=fafafa&idlecheck=0&autonextepisode=false"
            else "https://vidsrc.su/embed/movie/$id?autoplay=true&colour=fafafa&idlecheck=0&autonextepisode=false"
        VIDLINK -> {
            val base = if (type == MediaType.TV) "https://vidlink.pro/tv/$id/$season/$episode?autoplay=true&primaryColor=fafafa"
            else "https://vidlink.pro/movie/$id?autoplay=true&primaryColor=fafafa"
            if (startAt > 0) "$base&startAt=$startAt" else base
        }
    }

    fun next(): Provider? = entries.getOrNull(ordinal + 1)

    companion object {
        val ALL_HOSTS: Set<String> = entries.flatMap { it.hosts }.toSet()
    }
}
