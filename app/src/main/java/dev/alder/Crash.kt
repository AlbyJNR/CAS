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
        val started = android.os.SystemClock.elapsedRealtime()
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            // A crash in the first seconds is counted (written at once: the process is about to end); see [recover].
            runCatching {
                val p = app.getSharedPreferences("crash", Context.MODE_PRIVATE)
                val early = android.os.SystemClock.elapsedRealtime() - started < EARLY_MS
                p.edit().putInt("early", if (early) p.getInt("early", 0) + 1 else 0).commit()
            }
            runCatching {
                val trace = scrubbed(StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()) // no add-on address, with its key, in the file
                file(app).writeText(
                    "CAS ${Updates.mine(app)} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" +
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())} · thread ${thread.name}\n\n" + trace.take(12_000))
            }
            before?.uncaughtException(thread, e)
        }
    }
    private var installed = false
    private const val EARLY_MS = 20_000L

    /**
     * True, once, when the last two runs both ended within seconds of starting: something CAS keeps on the TV (last
     * time's library, a row's saved copy) is making it close as it opens, and the way to clear it is in Settings,
     * which cannot be reached. The caller clears the saved copies; sign-ins, settings and lists are not touched.
     */
    fun recover(c: Context): Boolean {
        val p = c.getSharedPreferences("crash", Context.MODE_PRIVATE)
        if (!needsRecovery(p.getInt("early", 0))) return false
        p.edit().putInt("early", 0).commit()
        return true
    }

    /** This run has lasted: the count of early crashes starts again. */
    fun settled(c: Context) = c.getSharedPreferences("crash", Context.MODE_PRIVATE).edit().putInt("early", 0).apply()

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

/** Two runs in a row that ended as they started are enough to clear what CAS keeps. */
internal fun needsRecovery(earlyCrashes: Int) = earlyCrashes >= 2

/** [text] with every web address cut back to its host: an add-on's address holds its owner's debrid key, and belongs in no log or file. */
internal fun scrubbed(text: String?): String = text.orEmpty().replace(Regex("(https?://[^/\\s\"']+)/[^\\s\"']*"), "$1/…")
