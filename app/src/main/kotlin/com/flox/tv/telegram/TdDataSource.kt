package com.flox.tv.telegram

import android.content.Context
import android.net.Uri
import android.util.Log
import com.flox.tv.BuildConfig
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** Reads a library entry as one contiguous byte stream, stitching split parts by offset. */
@UnstableApi
class TdDataSource(private val entry: Library.Entry) : BaseDataSource(true) {
    /** Routes tg:// to Telegram and everything else (side-loaded subtitle files) to the default source. */
    class Factory(private val ctx: Context, private val entry: Library.Entry) : DataSource.Factory {
        override fun createDataSource(): DataSource = Routing(ctx, entry)
    }

    private class Routing(ctx: Context, entry: Library.Entry) : DataSource {
        private val td = TdDataSource(entry)
        private val local = DefaultDataSource.Factory(ctx).createDataSource()
        private var active: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            td.addTransferListener(transferListener)
            local.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val src = if (dataSpec.uri.scheme == "tg") td else local
            active = src
            return src.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = active?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()
        override fun close() { active?.close(); active = null }
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
        if (BuildConfig.DEBUG) Log.d("FloxTd", "open pos=${dataSpec.position} len=${dataSpec.length} total=$total")
        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) total - position else dataSpec.length
        transferStarted(dataSpec)
        return remaining
    }

    private var buf = ByteArray(0)
    private var bufStart = 0L
    private var activePart = -1
    private var prefetched = false

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (position < bufStart || position >= bufStart + buf.size) fill()
        val inBuf = (position - bufStart).toInt()
        val n = minOf(length.toLong(), remaining, (buf.size - inBuf).toLong()).toInt()
        System.arraycopy(buf, inBuf, buffer, offset, n)
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    /** Pulls the next chunk at the current position from TDLib, never crossing a part boundary. */
    private fun fill() {
        val idx = starts.indexOfLast { it <= position }
        val part = entry.parts[idx]
        val local = position - starts[idx]
        val count = minOf(part.size - local, CHUNK)
        if (activePart != idx) {
            if (activePart >= 0) Telegram.cancelDownload(entry.parts[activePart].fileId)
            activePart = idx
            prefetched = false
        }
        if (!prefetched && idx + 1 < entry.parts.size && part.size - local <= PREFETCH_AT) {
            Telegram.prefetch(entry.parts[idx + 1].fileId, PREFETCH)
            prefetched = true
        }
        val t0 = System.currentTimeMillis()
        Telegram.ensureDownloaded(part.fileId, local, count)
        val bytes = Telegram.readFilePart(part.fileId, local, count)
        if (bytes.isEmpty()) throw IOException("empty read at $position")
        if (BuildConfig.DEBUG) Log.d("FloxTd", "fill part=$idx off=$local n=${bytes.size} took=${System.currentTimeMillis() - t0}ms")
        buf = bytes
        bufStart = position
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        buf = ByteArray(0)
        if (activePart >= 0) Telegram.cancelDownload(entry.parts[activePart].fileId)
        activePart = -1
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }

    private companion object {
        const val CHUNK = 512L * 1024
        const val PREFETCH_AT = 512L * 1024 * 1024
        const val PREFETCH = 64L * 1024 * 1024
    }
}
