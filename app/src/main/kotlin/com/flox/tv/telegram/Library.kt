package com.flox.tv.telegram

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
    }

    @Volatile var entries: Map<Key, Entry> = emptyMap()
        private set
    @Volatile var chatId: Long = 0L
        private set

    fun get(tmdb: Int, type: MediaType, season: Int = 0, episode: Int = 0) = entries[Key(tmdb, type, season, episode)]

    fun has(tmdb: Int, type: MediaType, season: Int = 0, episode: Int = 0) = get(tmdb, type, season, episode) != null

    /** Season numbers of a show that have at least one uploaded episode. */
    fun seasons(tmdb: Int): Set<Int> = entries.keys.filter { it.tmdb == tmdb && it.type == MediaType.TV }.map { it.season }.toSet()

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
        val parts = HashMap<Key, MutableList<Pair<Part, Caption>>>()
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
            parts.getOrPut(caption.key) { ArrayList() }.add(part to caption)
        }
        val index = HashMap<Key, Entry>()
        for ((key, list) in parts) {
            val latest = list.groupBy { it.first.index }.values.map { same -> same.maxBy { it.first.messageId } }
            val sorted = latest.sortedBy { it.first.index }
            val expected = sorted.first().second.parts
            if (sorted.size < expected) continue
            val first = sorted.first()
            index[key] = Entry(key, first.second.quality, first.second.codec, sorted.map { it.first }, subtitles[first.first.messageId])
        }
        entries = index
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
