package com.flox.tv.player

import com.flox.tv.data.MediaType

/** VidLink embed player. Hosts feed the ad-block allowlist. */
object Provider {
    val HOSTS: Set<String> = setOf("vidlink.pro", "jwplayer.com", "jwpcdn.com")

    fun url(id: Int, type: MediaType, season: Int, episode: Int, startAt: Int): String {
        val base = if (type == MediaType.TV) "https://vidlink.pro/tv/$id/$season/$episode"
        else "https://vidlink.pro/movie/$id"
        val params = "autoplay=true&primaryColor=fafafa&nextbutton=false" + if (startAt > 0) "&startAt=$startAt" else ""
        return "$base?$params"
    }
}
