package com.flox.tv.telegram

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException

/** Reads a library entry as one contiguous byte stream, stitching split parts by offset. */
@UnstableApi
class TdDataSource(private val entry: Library.Entry) : BaseDataSource(true) {
    class Factory(private val entry: Library.Entry) : DataSource.Factory {
        override fun createDataSource(): DataSource = TdDataSource(entry)
    }

    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private val starts: LongArray = LongArray(entry.parts.size).also { s ->
        var acc = 0L
        entry.parts.forEachIndexed { i, p -> s[i] = acc; acc += p.size }
    }

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val total = entry.totalSize
        if (dataSpec.position > total) throw IOException("position past end")
        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) total - position else dataSpec.length
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val idx = starts.indexOfLast { it <= position }
        val part = entry.parts[idx]
        val local = position - starts[idx]
        val count = minOf(length.toLong(), remaining, part.size - local, CHUNK)
        Telegram.ensureDownloaded(part.fileId, local, count)
        val bytes = Telegram.readFilePart(part.fileId, local, count)
        if (bytes.isEmpty()) throw IOException("empty read at $position")
        System.arraycopy(bytes, 0, buffer, offset, bytes.size)
        position += bytes.size
        remaining -= bytes.size
        bytesTransferred(bytes.size)
        return bytes.size
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }

    private companion object {
        const val CHUNK = 512L * 1024
    }
}
