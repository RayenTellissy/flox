package com.flox.tv.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.flox.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the latest GitHub release and installs its APK in place. The APK is streamed straight into a
 * PackageInstaller session, so nothing is kept on disk, and the system asks the user to confirm.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/RayenTellissy/flox/releases/latest"
    private const val APK_NAME = "flox.apk"
    private const val TIMEOUT = 15_000

    class Release(val version: String, val url: String, val size: Long, val notes: String)

    sealed class State {
        object Idle : State()
        object Checking : State()
        object UpToDate : State()
        class Available(val release: Release) : State()
        class Downloading(val release: Release, val percent: Int) : State()
        object Installing : State()
        class Failed(val message: String) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = mutableListOf<(State) -> Unit>()

    @Volatile
    var state: State = State.Idle
        private set

    val busy: Boolean
        get() = state.let { it is State.Checking || it is State.Downloading || it is State.Installing }

    fun addListener(l: (State) -> Unit) { synchronized(listeners) { listeners += l }; l(state) }
    fun removeListener(l: (State) -> Unit) { synchronized(listeners) { listeners -= l } }

    internal fun publish(next: State) {
        state = next
        synchronized(listeners) { listeners.toList() }.forEach { it(next) }
    }

    /** Fetches the latest release and moves to [State.Available] or [State.UpToDate]. */
    suspend fun check(): State {
        if (busy) return state
        publish(State.Checking)
        val next = runCatching { withContext(Dispatchers.IO) { latest() } }.fold(
            { release -> if (release != null && newer(release.version, BuildConfig.VERSION_NAME)) State.Available(release) else State.UpToDate },
            { State.Failed(it.message ?: it.javaClass.simpleName) }
        )
        publish(next)
        return next
    }

    /** Downloads [release] into an install session and hands it to the system installer. */
    fun install(context: Context, release: Release) {
        if (busy) return
        val app = context.applicationContext
        publish(State.Downloading(release, 0))
        scope.launch {
            runCatching { stream(app, release) }.onFailure {
                publish(State.Failed(it.message ?: it.javaClass.simpleName))
            }
        }
    }

    private fun latest(): Release? {
        val conn = URL(LATEST).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT
            conn.readTimeout = TIMEOUT
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = o.optJSONArray("assets") ?: return null
            val apks = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
                .filter { it.optString("name").endsWith(".apk") }
            val apk = apks.firstOrNull { it.optString("name") == APK_NAME } ?: apks.firstOrNull() ?: return null
            return Release(
                version = o.optString("tag_name").removePrefix("v"),
                url = apk.optString("browser_download_url"),
                size = apk.optLong("size", -1),
                notes = o.optString("body")
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun stream(context: Context, release: Release) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (release.size > 0) setSize(release.size)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        val session = installer.openSession(id)
        try {
            val conn = URL(release.url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = TIMEOUT
                conn.readTimeout = TIMEOUT
                val code = conn.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
                session.openWrite(APK_NAME, 0, total).use { out ->
                    conn.inputStream.use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var shown = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                            if (percent != shown) {
                                shown = percent
                                publish(State.Downloading(release, percent))
                            }
                        }
                        session.fsync(out)
                    }
                }
            } finally {
                conn.disconnect()
            }
            publish(State.Installing)
            val intent = Intent(context, UpdateReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            session.close()
        } catch (e: Throwable) {
            session.abandon()
            throw e
        }
    }

    /** True when dotted version [a] is greater than [b]. */
    internal fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}
