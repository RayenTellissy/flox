package com.flox.tv.player

import com.flox.tv.data.MediaType

/** VidLink embed player. Hosts feed the ad-block allowlist. */
object Provider {
    const val HOST = "vidlink.pro"
    val HOSTS: Set<String> = setOf(HOST, "jwplayer.com", "jwpcdn.com")

    fun url(id: Int, type: MediaType, season: Int, episode: Int, startAt: Int): String {
        val base = if (type == MediaType.TV) "https://$HOST/tv/$id/$season/$episode"
        else "https://$HOST/movie/$id"
        val params = "autoplay=true&primaryColor=fafafa&nextbutton=false" + if (startAt > 0) "&startAt=$startAt" else ""
        return "$base?$params"
    }
}
