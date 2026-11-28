package dev.alder

import android.content.Context
import org.json.JSONObject

/**
 * Live TV (Alan, 2026-11-28): kids, news and sport in one place. CAS keeps its own short list of channels, each one
 * served by its broadcaster with no login and no lock. The list is a file in the CAS repository (and a copy inside
 * the app), so a channel that moves is put right without a new version: the app reads the file again when the
 * Live page opens, a few times a day at most.
 */
object Live {
    /** One channel. [kind] says how it plays: "stream" ([url], in CAS's own player), "twitch" ([channel], Twitch's own player) or "app" (opens one of [packages]). */
    class Channel(val id: String, val name: String, val kind: String, val url: String?, val channel: String?, val packages: List<String>, val note: String?)
    class Row(val name: String, val channels: List<Channel>)
    class Listing(val rev: Int, val rows: List<Row>)

    const val LIST_URL = "https://raw.githubusercontent.com/AlbyJNR/CAS/main/app/src/main/assets/live.json"
    const val FRESH_MS = 6 * 3600_000L

    private fun prefs(c: Context) = c.getSharedPreferences("live", Context.MODE_PRIVATE)

    private fun bundled(c: Context): Listing? = runCatching { parseLive(c.assets.open("live.json").bufferedReader().use { it.readText() }) }.getOrNull()

    /** The list to show now, with no waiting: the newer of the copy in the app and the last one fetched. */
    fun current(c: Context): Listing = newer(bundled(c), Http.savedAnswer(LIST_URL)?.let(::parseLive)) ?: Listing(0, emptyList())

    /** Whether the file is due to be read again. */
    fun wantsFetch(c: Context): Boolean = liveAsksAgain(prefs(c).getLong("at", 0L), System.currentTimeMillis())

    /** The file as it is now, or null when it cannot be read or holds nothing CAS can play. Blocks. A good answer is kept. */
    fun fetch(c: Context): Listing? {
        prefs(c).edit().putLong("at", System.currentTimeMillis()).apply() // a failure waits its turn too: no request per visit
        val body = runCatching { Http.get(LIST_URL, timeoutMs = 10_000) }.getOrNull() ?: return null
        val list = parseLive(body) ?: return null
        Http.keep(LIST_URL, null, body)
        return list
    }

    /** The channel last played, to put the remote back on it. */
    fun last(c: Context): String? = prefs(c).getString("last", null)
    fun setLast(c: Context, id: String) = prefs(c).edit().putString("last", id).apply()
}

internal val TWITCH_NAME = Regex("^[A-Za-z0-9_]{3,25}$")
internal val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

/** The list in [text], or null when it is not one or has nothing to show. A channel CAS does not understand is left out, so a later format does not break an earlier app. */
internal fun parseLive(text: String): Live.Listing? {
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
    val rows = json.optJSONArray("rows").objects().mapNotNull { r ->
        val name = r.text("name") ?: return@mapNotNull null
        val channels = r.optJSONArray("channels").objects().mapNotNull(::liveChannel).distinctBy { it.id }
        if (channels.isEmpty()) null else Live.Row(name, channels)
    }
    return if (rows.isEmpty()) null else Live.Listing(json.optInt("rev", 0), rows)
}

private fun liveChannel(c: JSONObject): Live.Channel? {
    val id = c.text("id") ?: return null
    val name = c.text("name") ?: return null
    val kind = c.text("kind") ?: return null
    val url = c.text("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    val channel = c.text("channel")?.takeIf { TWITCH_NAME.matches(it) }
    val packages = c.optJSONArray("packages").items().filterIsInstance<String>().filter { PACKAGE_NAME.matches(it) }
    val ok = when (kind) { "stream" -> url != null; "twitch" -> channel != null; "app" -> packages.isNotEmpty(); else -> false }
    return if (ok) Live.Channel(id, name, kind, url, channel, packages, c.text("note")) else null
}

/** The later revision of the two; the first on a tie (the app's own copy), either when the other is missing. */
internal fun newer(a: Live.Listing?, b: Live.Listing?): Live.Listing? = when {
    a == null -> b
    b == null -> a
    else -> if (b.rev > a.rev) b else a
}

/** Whether the list file is read again: six hours after the last try, or when the clock went back. */
internal fun liveAsksAgain(atMs: Long, nowMs: Long): Boolean = nowMs - atMs >= Live.FRESH_MS || nowMs < atMs

/** Whether two lists would draw and play the same. */
internal fun sameLive(a: Live.Listing, b: Live.Listing): Boolean = liveKey(a) == liveKey(b)
private fun liveKey(l: Live.Listing) = l.rows.joinToString("\n") { r -> r.name + ":" + r.channels.joinToString("|") { listOf(it.id, it.name, it.kind, it.url, it.channel, it.packages, it.note).joinToString(",") } }

/** The channel [step] places on from [index] among [count], round the ends. */
internal fun liveStep(index: Int, step: Int, count: Int): Int = if (count <= 0) 0 else ((index + step) % count + count) % count
