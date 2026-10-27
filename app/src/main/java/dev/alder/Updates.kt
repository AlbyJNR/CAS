package dev.alder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.net.URL

/**
 * Updates from the GitHub releases, as Plume does: a release tagged `v1.2` with an .apk attached is offered to every TV
 * on an older version, and Android's package installer puts it in place (same signing key, so data stays).
 */
object Updates {
    private const val LATEST = "https://api.github.com/repos/aradhyas8/Alder/releases/latest"
    private const val DAY = 24 * 3600_000L

    class Release(val version: String, val apk: String)

    fun mine(c: Context): String = c.packageManager.getPackageInfo(c.packageName, 0).versionName.orEmpty()

    /** The newest release, or null if the check failed or it has no .apk. Blocks: run it off the main thread. */
    fun latest(c: Context): Release? = runCatching {
        val json = JSONObject(Http.get(LATEST, timeoutMs = 10_000))
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }?.optString("browser_download_url") ?: return null
        Release(json.getString("tag_name").removePrefix("v"), apk).also {
            prefs(c).edit().putLong("checked", System.currentTimeMillis()).apply()
        }
    }.onFailure { android.util.Log.w("Alder", "update check failed: $it") }.getOrNull()

    /** A day since the last check: Home asks once a day, Settings whenever it's opened. */
    fun due(c: Context) = System.currentTimeMillis() - prefs(c).getLong("checked", 0) > DAY

    /** Home offers each new version once; Settings always shows it. */
    fun offerOnce(c: Context, version: String): Boolean {
        if (prefs(c).getString("offered", null) == version) return false
        prefs(c).edit().putString("offered", version).apply()
        return true
    }

    /** False when this TV hasn't allowed Alder to install apps yet: Android's page for that is opened instead. */
    fun canInstall(c: Context): Boolean {
        if (c.packageManager.canRequestPackageInstalls()) return true
        c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return false
    }

    /** Downloads [release] into an install session and hands it to Android. Blocks: run it off the main thread. */
    fun install(c: Context, release: Release) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(c.packageName)
        // Android 12+ can skip the confirm box when Alder installed itself last time; otherwise Android asks.
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            try {
                URL(release.apk).openStream().use { input ->
                    session.openWrite("Alder.apk", 0, -1).use { out -> input.copyTo(out); session.fsync(out) }
                }
            } catch (e: Exception) {
                session.abandon()
                throw e
            }
            val done = PendingIntent.getBroadcast(c, 0, Intent(c, Status::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(done.intentSender)
        }
    }

    /** The install session reports here: show Android's confirm box, or say why it failed. */
    class Status : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
                PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> {}
                else -> {
                    android.util.Log.w("Alder", "update install failed: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                    Toast.makeText(c, "Couldn't install the update", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("updates", Context.MODE_PRIVATE)
}

/** True when version [a] is newer than [b] ("1.10" > "1.9"; missing parts count as 0). */
internal fun newerVersion(a: String, b: String): Boolean {
    val x = a.split('.').map { it.toIntOrNull() ?: 0 }
    val y = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(x.size, y.size)) {
        val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
        if (d != 0) return d > 0
    }
    return false
}
