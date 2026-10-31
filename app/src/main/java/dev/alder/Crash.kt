package dev.alder

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The last crash, kept on the TV: there's no crash service (CAS sends nothing anywhere), so when CAS closes on its own
 * the stack trace is written here, the next start says so once, and Settings › About › Last crash shows it to read
 * out or photograph. One file; a new crash replaces the old.
 */
object Crash {
    private fun file(c: Context) = File(c.filesDir, "last-crash.txt")

    /** Installed once per process, ahead of Android's own handler, which still runs after ours (the app still closes). */
    fun install(c: Context) {
        if (installed) return
        installed = true
        val app = c.applicationContext
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
                file(app).writeText(
                    "CAS ${Updates.mine(app)} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())} · thread ${thread.name}\n\n" + trace.take(12_000))
            }
            before?.uncaughtException(thread, e)
        }
    }
    private var installed = false

    /** The last crash's text, or null when there is none. */
    fun last(c: Context): String? = file(c).takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() }?.ifBlank { null }

    /** When the last crash happened, as the file says it, for a row's value. */
    fun lastWhen(c: Context): String? = last(c)?.lines()?.getOrNull(1)?.substringBefore(" ·")

    fun clear(c: Context) { file(c).delete() }

    /** True once per crash: the first start after it says a word, later starts don't. */
    fun unseen(c: Context): Boolean {
        val f = file(c)
        if (!f.exists()) return false
        val prefs = c.getSharedPreferences("crash", Context.MODE_PRIVATE)
        val stamp = f.lastModified()
        if (prefs.getLong("seen", 0) == stamp) return false
        prefs.edit().putLong("seen", stamp).apply()
        return true
    }
}
