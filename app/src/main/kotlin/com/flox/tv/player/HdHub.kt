package com.flox.tv.player

import android.util.Base64
import com.flox.tv.data.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Scrapes 4khdhub.one: finds the page for a TMDB title, lists its prints, and walks a file's
 * HubCloud page to a direct URL that answers range requests, so ExoPlayer can stream and seek it.
 * Same scraping as the Mac app's HdHub.swift.
 */
object HdHub {
    private const val BASE = "https://4khdhub.one"
    const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
    private const val TIMEOUT = 15_000

    /** One file on the page: an episode of a series or one print of a movie. */
    data class File(val episode: Int, val name: String, val size: String, val hubcloud: String)

    /** A group of files with the same print, e.g. "S04 SDR 2160p WEB-DL H265". */
    data class Variant(val season: Int, val label: String, val files: List<File>) {
        val resolution get() = Regex("(\\d{3,4})p").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val isDolbyVision get() = Regex("\\b(DoVi|DV)\\b").containsMatchIn(label)
        val isRemux get() = label.contains("REMUX", ignoreCase = true)
        val isHevc get() = Regex("\\b(H\\.?265|x265|HEVC)\\b", RegexOption.IGNORE_CASE).containsMatchIn(label)
        /** DV, HDR, or nothing for SDR. */
        val tag get() = if (isDolbyVision) "DV" else if (Regex("\\bHDR").containsMatchIn(label)) "HDR" else ""
        /** Same form as a library print's quality, e.g. "2160p DV", so one preference covers both. */
        val quality get() = listOf(if (resolution > 0) "${resolution}p" else "", tag).filter { it.isNotEmpty() }.joinToString(" ")

        fun file(episode: Int): File? = files.firstOrNull { it.episode == episode }
    }

    private val pages = ConcurrentHashMap<String, String>()
    private val prints = ConcurrentHashMap<String, List<Variant>>()

    /** Prints that hold this movie or episode, best first. Pages and print lists are cached for the session. */
    suspend fun variants(id: Int, type: MediaType, title: String, year: String, posterPath: String?, season: Int, episode: Int): List<Variant> =
        withContext(Dispatchers.IO) {
            val key = "${type.tmdb}/$id"
            val page = pages[key] ?: find(type, title, year, posterPath)?.also { pages[key] = it } ?: return@withContext emptyList()
            val all = prints[page] ?: parse(fetch(page), type).also { prints[page] = it }
            if (type == MediaType.TV) all.filter { it.season == season && it.file(episode) != null } else all
        }

    /** Highest resolution first, then plain HDR/SDR over Dolby Vision, then WEB-DL over remux. HEVC sinks when the box cannot decode it in hardware. */
    fun rank(variants: List<Variant>, hevcOk: Boolean): List<Variant> =
        variants.sortedWith(
            compareByDescending<Variant> { hevcOk || !it.isHevc }
                .thenByDescending { it.resolution }
                .thenByDescending { !it.isDolbyVision }
                .thenByDescending { !it.isRemux }
        )

    /** Searches the site and returns the page whose TMDB poster, or else title and year, match. */
    private fun find(type: MediaType, title: String, year: String, posterPath: String?): String? {
        val html = fetch("$BASE/?s=${URLEncoder.encode(title, "UTF-8")}")
        val kind = if (type == MediaType.TV) "-series-" else "-movie-"
        var byTitle: String? = null
        for (card in Regex("<a href=\"([^\"]+)\" class=\"movie-card\".*?</a>", RegexOption.DOT_MATCHES_ALL).findAll(html)) {
            val href = card.groupValues[1]
            if (!href.contains(kind)) continue
            val url = URL(URL(BASE), href).toString()
            val block = card.value
            val poster = Regex("image\\.tmdb\\.org/t/p/\\w+(/[^\"']+)").find(block)?.groupValues?.get(1)
            if (poster != null && poster == posterPath) return url
            val name = Regex("movie-card-title\">([^<]*)<").find(block)?.groupValues?.get(1).orEmpty()
            val released = Regex("movie-card-meta\">\\s*(\\d{4})").find(block)?.groupValues?.get(1).orEmpty()
            if (byTitle == null && normalize(decode(name)) == normalize(title) && released == year) byTitle = url
        }
        return byTitle
    }

    /** Every print on a title page, grouped by season for series. */
    private fun parse(html: String, type: MediaType): List<Variant> {
        val out = ArrayList<Variant>()
        if (type == MediaType.TV) {
            for (g in html.split("<div class=\"season-item episode-item").drop(1)) {
                val season = Regex("class=\"episode-number\">\\s*S(\\d+)").find(g)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val label = decode(Regex("class=\"episode-title\">([^<]*)<").find(g)?.groupValues?.get(1).orEmpty()).trim()
                val files = g.split("<div class=\"episode-download-item\">").drop(1).mapNotNull { f ->
                    val ep = Regex("Episode-(\\d+)").find(f)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                    val link = hubLink(f) ?: return@mapNotNull null
                    val name = decode(Regex("class=\"episode-file-title\">\\s*([^<]*?)\\s*<").find(f)?.groupValues?.get(1).orEmpty())
                    File(ep, name, Regex("class=\"badge-size\">([^<]*)<").find(f)?.groupValues?.get(1).orEmpty(), link)
                }
                if (season > 0 && files.isNotEmpty()) out.add(Variant(season, label, files.sortedBy { it.episode }))
            }
        } else {
            for (d in html.split("<div class=\"download-item ").drop(1)) {
                val link = hubLink(d) ?: continue
                val name = decode(Regex("class=\"file-title\">\\s*([^<]*?)\\s*<").find(d)?.groupValues?.get(1).orEmpty())
                if (name.lowercase().endsWith(".zip")) continue
                val header = decode(Regex("font-semibold\">\\s*([^<]*?)\\s*<").find(d)?.groupValues?.get(1).orEmpty())
                val size = Regex("#ea580c; color: white;\">([^<]*)<").find(d)?.groupValues?.get(1).orEmpty()
                out.add(Variant(0, header.ifEmpty { name }, listOf(File(0, name, size, link))))
            }
        }
        return out
    }

    /** The "Download HubCloud" button's target: a HubCloud drive URL, or the redirector the site wraps it in. */
    private fun hubLink(s: String): String? {
        val m = Regex("href=\"(https?://[^\"]+)\"[^>]*>\\s*(?:<span[^>]*>)?\\s*Download HubCloud").find(s)
            ?: Regex("href=\"(https?://[^\"]*hubcloud[^\"]*)\"").find(s)
        return m?.groupValues?.get(1)?.replace("&amp;", "&")
    }

    /** Walks the file's HubCloud page to a direct URL that serves byte ranges. Links expire, so resolve right before playing. */
    suspend fun resolve(link: String): String = withContext(Dispatchers.IO) {
        val drive = if (URL(link).host.contains("hubcloud")) link else unwrap(link)
        val hop = Regex("https?://[^\"'\\s]+hubcloud\\.php\\?[^\"'\\s]+").find(fetch(drive))?.value
            ?: throw IOException("HubCloud page has no download link")
        val page = fetch(hop, referer = drive)
        val links = Regex("href=\"(https?://[^\"]+)\"").findAll(page).map { it.groupValues[1].replace("&amp;", "&") }.toList()
        // the pixeldrain button's href is a decoy; a script swaps in the real id via `var pxl = "..."`
        val pixel = (Regex("pxl\\s*=\\s*[\"']https?://pixeldrain\\.\\w+/u/(\\w+)").find(page) ?: Regex("pixeldrain\\.\\w+/u/(\\w+)").find(page))
            ?.groupValues?.get(1)?.let { "https://pixeldrain.dev/api/file/$it" }
        val candidates = links.filter { it.contains("cloudflarestorage.com") || it.contains("fsl") } +
            links.filter { it.contains("workers.dev") && !it.contains("pixel.") } +
            listOfNotNull(pixel) +
            links.filter { it.contains("pixel.hubcloud") }
        // pixeldrain refuses hotlinks on busy files and some mirrors ignore Range; take the first that streams
        candidates.distinct().firstNotNullOfOrNull { runCatching { seekable(it) }.getOrNull() }
            ?: throw IOException("HubCloud offered no streamable server")
    }

    /** The URL to play when it answers a range request with partial video content, else null. */
    private fun seekable(url: String): String? {
        val conn = open(url).apply { setRequestProperty("Range", "bytes=0-1") }
        try {
            val code = conn.responseCode
            val type = conn.contentType.orEmpty().lowercase()
            val final = conn.url.toString()
            if (code == 206 && (type.startsWith("video/") || type.contains("octet-stream") || type.contains("matroska"))) return url
            // some mirrors land on a download page that carries the real file URL in `link=`
            val inner = Regex("[?&]link=([^&]+)").find(final)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }
            return if (inner != null && inner != url) seekable(inner) else null
        } finally {
            conn.disconnect()
        }
    }

    /** Follows the site's redirector page (base64, base64, rot13, base64 JSON whose "o" is the base64 target) to the HubCloud URL. */
    private fun unwrap(url: String): String {
        val blob = Regex("s\\('o',\\s*'([^']+)'").find(fetch(url))?.groupValues?.get(1)
            ?: throw IOException("Could not read the 4KHDHub redirect")
        val json = base64(rot13(base64(base64(blob))))
        return base64(JSONObject(json).getString("o"))
    }

    private fun fetch(url: String, referer: String? = null): String {
        val conn = open(url).apply {
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            if (referer != null) setRequestProperty("Referer", referer)
        }
        try {
            if (conn.responseCode != 200) throw IOException("http ${conn.responseCode} from ${URL(url).host}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT
        readTimeout = TIMEOUT
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", USER_AGENT)
    }

    private fun base64(s: String): String = String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)

    private fun rot13(s: String): String = s.map { c ->
        when (c) {
            in 'A'..'Z' -> 'A' + (c - 'A' + 13) % 26
            in 'a'..'z' -> 'a' + (c - 'a' + 13) % 26
            else -> c
        }
    }.joinToString("")

    private fun normalize(s: String): String = s.lowercase().replace("&", "and").filter { it.isLetterOrDigit() }

    private fun decode(s: String): String = s.replace("&amp;", "&").replace("&#039;", "'").replace("&quot;", "\"")
}
