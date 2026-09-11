package com.flox.tv.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Memory + disk image cache, no third-party deps. Bitmaps decoded as RGB_565 sized to the view. */
object ImageLoader {
    private const val DISK_CAP = 40L * 1024 * 1024
    private const val TIMEOUT = 10_000

    private class Tag(val url: String, var job: Job?)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(4)
    private val writes = AtomicInteger()

    private val memory = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(24L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(view: ImageView, url: String?) {
        (view.tag as? Tag)?.job?.cancel()
        view.tag = null
        view.setImageDrawable(null)
        if (url.isNullOrEmpty()) return
        memory.get(url)?.let {
            view.tag = Tag(url, null)
            view.setImageBitmap(it)
            return
        }
        val ctx = view.context.applicationContext
        val lp = view.layoutParams
        val reqW = if (lp != null && lp.width > 0) lp.width else view.width
        val reqH = if (lp != null && lp.height > 0) lp.height else view.height
        val tag = Tag(url, null)
        view.tag = tag
        tag.job = scope.launch {
            val bmp = runCatching { gate.withPermit { fetch(ctx, url, reqW, reqH) } }.getOrNull()
            if (bmp == null || !isActive) return@launch
            memory.put(url, bmp)
            withContext(Dispatchers.Main) {
                if ((view.tag as? Tag)?.url == url) view.setImageBitmap(bmp)
            }
        }
    }

    private fun fetch(ctx: Context, url: String, reqW: Int, reqH: Int): Bitmap? {
        val dir = File(ctx.cacheDir, "images").also { it.mkdirs() }
        val file = File(dir, sha1(url))
        if (!file.exists() || file.length() == 0L) {
            download(url, file)
            if (writes.incrementAndGet() % 16 == 0) trim(dir)
        }
        val bmp = decode(file, reqW, reqH)
        if (bmp == null) file.delete() else file.setLastModified(System.currentTimeMillis())
        return bmp
    }

    private fun download(url: String, dest: File) {
        val tmp = File(dest.path + ".tmp")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun decode(file: File, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        if (reqW > 0 && reqH > 0) {
            while (bounds.outWidth / (sample * 2) >= reqW && bounds.outHeight / (sample * 2) >= reqH) sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.path, opts)
    }

    private fun trim(dir: File) {
        val files = dir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_CAP) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_CAP * 3 / 4) break
            total -= f.length()
            f.delete()
        }
    }

    private fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append(String.format("%02x", b))
        return sb.toString()
    }
}
