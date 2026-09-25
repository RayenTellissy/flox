package com.flox.tv.telegram

import android.content.Context
import android.os.Build
import android.util.Log
import com.flox.tv.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TdError(val code: Int, message: String) : IOException("$code: $message")

/** One TDLib client for the app: login, chat lookup, and file access for playback. */
object Telegram {
    sealed class Auth {
        object Idle : Auth()
        object Loading : Auth()
        data class Qr(val link: String) : Auth()
        object Password : Auth()
        object Ready : Auth()
        data class Failed(val message: String) : Auth()
    }

    @Volatile var auth: Auth = Auth.Idle
        private set
    val ready get() = auth == Auth.Ready
    val configured get() = BuildConfig.TELEGRAM_API_ID != 0 && BuildConfig.TELEGRAM_API_HASH.isNotBlank()

    private val authListeners = CopyOnWriteArraySet<(Auth) -> Unit>()
    private val files = ConcurrentHashMap<Int, TdApi.File>()
    private val chats = ConcurrentHashMap<Long, TdApi.Chat>()
    private val fileLock = Object()
    private var client: Client? = null
    private lateinit var appContext: Context
    private var chatsLoaded = false

    fun addAuthListener(l: (Auth) -> Unit) { authListeners += l; l(auth) }
    fun removeAuthListener(l: (Auth) -> Unit) { authListeners -= l }

    @Synchronized
    fun start(ctx: Context) {
        if (client != null || !configured) return
        appContext = ctx.applicationContext
        Client.execute(TdApi.SetLogVerbosityLevel(if (BuildConfig.DEBUG) 2 else 0))
        setAuth(Auth.Loading)
        client = Client.create({ onUpdate(it) }, { log("update error", it) }, { log("error", it) })
    }

    fun file(id: Int): TdApi.File? = files[id]

    fun cachedChat(id: Long): TdApi.Chat? = chats[id]

    private fun onUpdate(u: TdApi.Object) {
        when (u) {
            is TdApi.UpdateAuthorizationState -> onAuthState(u.authorizationState)
            is TdApi.UpdateFile -> {
                files[u.file.id] = u.file
                synchronized(fileLock) { fileLock.notifyAll() }
            }
            is TdApi.UpdateNewChat -> chats[u.chat.id] = u.chat
            is TdApi.UpdateChatTitle -> chats[u.chatId]?.title = u.title
        }
    }

    private fun onAuthState(state: TdApi.AuthorizationState) {
        val c = client ?: return
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val dir = File(appContext.filesDir, "tdlib")
                c.send(TdApi.SetTdlibParameters(
                    false, File(dir, "db").path, File(dir, "files").path, null,
                    true, true, true, false,
                    BuildConfig.TELEGRAM_API_ID, BuildConfig.TELEGRAM_API_HASH,
                    "en", Build.MODEL.ifBlank { "Android TV" }, "Android ${Build.VERSION.RELEASE}", BuildConfig.VERSION_NAME
                ), null)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> c.send(TdApi.RequestQrCodeAuthentication(LongArray(0))) { r ->
                if (r is TdApi.Error) setAuth(Auth.Failed(r.message))
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> setAuth(Auth.Qr(state.link))
            is TdApi.AuthorizationStateWaitPassword -> setAuth(Auth.Password)
            is TdApi.AuthorizationStateReady -> {
                setAuth(Auth.Ready)
                sweepStorage()
            }
            is TdApi.AuthorizationStateClosed -> {
                client = null
                chatsLoaded = false
                chats.clear()
                files.clear()
                activeStart.clear()
                setAuth(Auth.Idle)
            }
            else -> {}
        }
    }

    private fun setAuth(a: Auth) {
        auth = a
        authListeners.forEach { it(a) }
    }

    fun sendPassword(password: String) {
        client?.send(TdApi.CheckAuthenticationPassword(password)) { r ->
            if (r is TdApi.Error) setAuth(Auth.Failed(r.message))
        }
    }

    fun logout() { client?.send(TdApi.LogOut(), null) }

    suspend fun <T : TdApi.Object> send(f: TdApi.Function<T>): T {
        val c = client ?: throw TdError(0, "client not started")
        return suspendCancellableCoroutine { cont ->
            c.send(f) { r ->
                if (r is TdApi.Error) cont.resumeWithException(TdError(r.code, r.message))
                else @Suppress("UNCHECKED_CAST") cont.resume(r as T)
            }
        }
    }

    /** Blocking variant for ExoPlayer's loading thread. */
    fun <T : TdApi.Object> sendBlocking(f: TdApi.Function<T>, timeoutMs: Long): T {
        val c = client ?: throw TdError(0, "client not started")
        val latch = CountDownLatch(1)
        var result: TdApi.Object? = null
        c.send(f) { r -> result = r; latch.countDown() }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw TdError(0, "timeout")
        val r = result
        if (r is TdApi.Error) throw TdError(r.code, r.message)
        @Suppress("UNCHECKED_CAST") return r as T
    }

    /** Finds the chat with the given title in the main list, loading the list on first use. */
    suspend fun chatByTitle(title: String): TdApi.Chat? {
        if (!chatsLoaded) {
            while (true) {
                try { send(TdApi.LoadChats(TdApi.ChatListMain(), 100)) } catch (e: TdError) {
                    if (e.code == 404) break else throw e
                }
            }
            chatsLoaded = true
        }
        return chats.values.firstOrNull { it.title.equals(title, ignoreCase = true) }
    }

    /** Every document message in the chat, newest first. */
    suspend fun documents(chatId: Long): List<TdApi.Message> {
        val out = ArrayList<TdApi.Message>()
        var from = 0L
        while (true) {
            val page = send(TdApi.SearchChatMessages(chatId, null, "", null, from, 0, 100, TdApi.SearchMessagesFilterDocument()))
            out += page.messages
            if (page.messages.isEmpty() || page.nextFromMessageId == 0L) break
            from = page.nextFromMessageId
        }
        return out
    }

    private fun covered(f: TdApi.File?, offset: Long, count: Long): Boolean {
        val l = f?.local ?: return false
        if (l.isDownloadingCompleted) return true
        return l.downloadOffset <= offset && offset + count <= l.downloadOffset + l.downloadedPrefixSize
    }

    private val activeStart = ConcurrentHashMap<Int, Long>()

    /** Blocks until [offset, offset+count) of the file is on disk, starting or moving the download as needed. */
    /**
     * Keeps a sliding download window ahead of the read position. The request is re-issued from the
     * current offset once reads pass the middle of the window, so TDLib never races ahead to the end
     * of a multi-gigabyte part while the player still needs its beginning.
     */
    fun ensureDownloaded(fileId: Int, offset: Long, count: Long, timeoutMs: Long = 30_000) {
        if (covered(files[fileId], offset, count)) return
        val f = files[fileId]
        val start = activeStart[fileId]
        val stale = start == null || offset < start || offset + count > start + WINDOW / 2 || f?.local?.isDownloadingActive != true
        if (stale) {
            activeStart[fileId] = offset
            client?.send(TdApi.DownloadFile(fileId, 32, offset, WINDOW, false)) { r -> if (r is TdApi.File) { files[r.id] = r; synchronized(fileLock) { fileLock.notifyAll() } } }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(fileLock) {
            while (!covered(files[fileId], offset, count)) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) {
                    val l = files[fileId]?.local
                    throw IOException("download timeout for file $fileId at $offset (offset=${l?.downloadOffset} prefix=${l?.downloadedPrefixSize} active=${l?.isDownloadingActive})")
                }
                fileLock.wait(minOf(left, 500L))
            }
        }
    }

    /** Downloads the whole file and returns its local path. Blocking. */
    fun downloadFully(fileId: Int, timeoutMs: Long = 60_000): String {
        val f = sendBlocking(TdApi.DownloadFile(fileId, 32, 0, 0, true), timeoutMs)
        files[f.id] = f
        return f.local.path
    }

    fun readFilePart(fileId: Int, offset: Long, count: Long): ByteArray =
        sendBlocking(TdApi.ReadFilePart(fileId, offset, count), 15_000).data

    /** Warms the start of a file at low priority so a part switch does not stall the player. */
    fun prefetch(fileId: Int, count: Long) {
        if (covered(files[fileId], 0, count)) return
        client?.send(TdApi.DownloadFile(fileId, 16, 0, count, false)) { r -> if (r is TdApi.File) files[r.id] = r }
    }

    /** Drops the cached bytes of a file once the player is done with it. */
    fun deleteLocal(fileId: Int) {
        activeStart.remove(fileId)
        files.remove(fileId)
        client?.send(TdApi.DeleteFile(fileId), null)
    }

    /** Clears document caches left behind when playback did not end cleanly. */
    private fun sweepStorage() {
        client?.send(TdApi.OptimizeStorage(0, 0, 0, 0, arrayOf(TdApi.FileTypeDocument()), LongArray(0), LongArray(0), false, 0), null)
    }

    fun cancelDownload(fileId: Int) {
        activeStart.remove(fileId)
        client?.send(TdApi.CancelDownloadFile(fileId, false), null)
    }

    private fun log(what: String, t: Throwable) { if (BuildConfig.DEBUG) Log.d("FloxTg", what, t) }

    private const val WINDOW = 256L * 1024 * 1024
}
