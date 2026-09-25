package com.flox.tv.telegram

import com.flox.tv.data.LibrarySort
import com.flox.tv.data.MediaType
import org.drinkless.tdlib.TdApi
import org.json.JSONObject

/** Index of the private channel: one entry per title or episode, built from message captions. */
object Library {
    const val DEFAULT_CHAT = "Flox Library"

    data class Key(val tmdb: Int, val type: MediaType, val season: Int, val episode: Int)
    data class Part(val index: Int, val fileId: Int, val size: Long, val messageId: Long)
    data class Entry(
        val key: Key,
        val quality: String,
        val codec: String,
        val parts: List<Part>,
        val subtitleFileId: Int?
    ) {
        val totalSize get() = parts.sumOf { it.size }
        /** Print name shown to the user, e.g. "2160p DV hevc". */
        val label get() = "$quality $codec".trim()
        val height get() = quality.substringBefore('p').toIntOrNull() ?: 0
    }

    /** Every print uploaded per title or episode; the same episode can exist in several qualities. */
    @Volatile var entries: Map<Key, List<Entry>> = emptyMap()
        private set
    @Volatile var chatId: Long = 0L
        private set
    /** Quality the user last switched to in the player; wins when that print exists. */
    @Volatile var preferredQuality: String = ""

    fun variants(tmdb: Int, type: MediaType, season: Int = 0, episode: Int = 0): List<Entry> =
        entries[Key(tmdb, type, season, episode)].orEmpty()

    /** The print to play by default: the preferred quality when uploaded, else the highest resolution. */
    fun get(tmdb: Int, type: MediaType, season: Int = 0, episode: Int = 0): Entry? {
        val all = variants(tmdb, type, season, episode)
        return all.firstOrNull { it.quality == preferredQuality } ?: all.firstOrNull()
    }

    fun has(tmdb: Int, type: MediaType, season: Int = 0, episode: Int = 0) = variants(tmdb, type, season, episode).isNotEmpty()

    /** Season numbers of a show that have at least one uploaded episode. */
    fun seasons(tmdb: Int): Set<Int> = entries.keys.filter { it.tmdb == tmdb && it.type == MediaType.TV }.map { it.season }.toSet()

    /**
     * Distinct titles in the requested order. Date added uses the newest message id among a title's parts,
     * since message ids grow with upload time; size uses the largest print. Title order needs TMDB names,
     * so for [LibrarySort.TITLE] the caller sorts after resolving them.
     */
    fun titles(sort: LibrarySort): List<Pair<Int, MediaType>> {
        val groups = entries.values.flatten().groupBy { it.key.tmdb to it.key.type }
        val sorted = when (sort) {
            LibrarySort.DATE_ADDED -> groups.entries.sortedByDescending { (_, list) -> list.maxOf { e -> e.parts.maxOfOrNull { it.messageId } ?: 0L } }
            LibrarySort.SIZE -> groups.entries.sortedByDescending { (_, list) -> list.maxOf { it.totalSize } }
            LibrarySort.TITLE -> groups.entries.toList()
        }
        return sorted.map { it.key }
    }

    /** Forgets the indexed channel, used after signing out. */
    fun clear() {
        chatId = 0L
        entries = emptyMap()
    }

    suspend fun refresh(chatTitle: String = DEFAULT_CHAT): Boolean {
        if (!Telegram.ready) return false
        val chat = Telegram.chatByTitle(chatTitle)
        if (chat == null) {
            chatId = 0L
            entries = emptyMap()
            return true
        }
        chatId = chat.id
        val messages = Telegram.documents(chat.id)
        val parts = HashMap<String, MutableList<Pair<Part, Caption>>>()
        val subtitles = HashMap<Long, Int>()
        for (m in messages) {
            val content = m.content as? TdApi.MessageDocument ?: continue
            val doc = content.document
            val caption = parseCaption(content.caption.text)
            if (caption == null) {
                if (doc.fileName.endsWith(".srt", true) || doc.fileName.endsWith(".vtt", true)) {
                    val replyTo = (m.replyTo as? TdApi.MessageReplyToMessage)?.messageId ?: continue
                    subtitles[replyTo] = doc.document.id
                }
                continue
            }
            val part = Part(caption.part, doc.document.id, doc.document.size, m.id)
            parts.getOrPut("${caption.key} ${caption.quality} ${caption.codec}") { ArrayList() }.add(part to caption)
        }
        val index = HashMap<Key, MutableList<Entry>>()
        for (list in parts.values) {
            val latest = list.groupBy { it.first.index }.values.map { same -> same.maxBy { it.first.messageId } }
            val sorted = latest.sortedBy { it.first.index }
            val expected = sorted.first().second.parts
            if (sorted.size < expected) continue
            val first = sorted.first()
            val key = first.second.key
            index.getOrPut(key) { ArrayList() }
                .add(Entry(key, first.second.quality, first.second.codec, sorted.map { it.first }, subtitles[first.first.messageId]))
        }
        if (!Telegram.ready) return false
        entries = index.mapValues { (_, list) -> list.sortedWith(compareByDescending<Entry> { it.height }.thenBy { it.label }) }
        return true
    }

    private class Caption(val key: Key, val quality: String, val codec: String, val part: Int, val parts: Int)

    private fun parseCaption(text: String): Caption? {
        val start = text.indexOf('{')
        if (start < 0) return null
        return try {
            val j = JSONObject(text.substring(start))
            val type = MediaType.from(j.getString("type"))
            Caption(
                Key(j.getInt("tmdb"), type, j.optInt("s", 0), j.optInt("e", 0)),
                j.optString("quality", ""), j.optString("codec", ""),
                j.optInt("part", 1), j.optInt("parts", 1)
            )
        } catch (e: Exception) { null }
    }
}
