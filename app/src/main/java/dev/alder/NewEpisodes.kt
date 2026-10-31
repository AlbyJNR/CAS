package dev.alder

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

/**
 * New episodes of the series in My List, found as Stremio's own apps find them (stremio-core's notifications): an
 * episode released after the series was last watched, not ticked as watched, and aired within the last month. Each
 * series' episode list is asked for in the background, at most every few hours, and what was found is kept in
 * preferences, per profile, so Home draws its Continue row with the news in place at once.
 */
object NewEpisodes {
    /** [count] new episodes, the newest released on [latest] (ISO date), [videoId] the first of them to watch. */
    class News(val count: Int, val latest: String, val videoId: String)

    private fun prefs(c: Context) = c.getSharedPreferences("new-episodes", Context.MODE_PRIVATE)
    private fun key(profileId: String, id: String) = "$profileId/$id"
    private fun lastWatched(item: JSONObject) = item.optJSONObject("state")?.text("lastWatched")?.takeIf { it > "1971" }

    /**
     * What was found last time for the profile's series, by id. A series watched since the check is left out until it
     * is checked again (the news may be what was just watched), as is one no longer in My List.
     */
    fun all(c: Context, profileId: String, items: List<JSONObject>): Map<String, News> {
        val p = prefs(c)
        val floor = LocalDate.now().minusDays(DAYS).toString()
        return candidates(items).mapNotNull { item ->
            val j = p.getString(key(profileId, item.optString("_id")), null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return@mapNotNull null
            if (j.optInt("n") <= 0 || j.optString("watched") != lastWatched(item) || j.optString("latest") < floor) return@mapNotNull null
            item.optString("_id") to News(j.optInt("n"), j.optString("latest"), j.optString("video"))
        }.toMap()
    }

    /** The series Stremio would tell about: in My List, with notifications on, most recently watched first. */
    fun candidates(items: List<JSONObject>) = items
        .filter { it.optString("type") == "series" && !it.optBoolean("removed") && it.optJSONObject("state")?.optBoolean("noNotif") != true && lastWatched(it) != null }
        .sortedByDescending { lastWatched(it) }.take(MAX)

    /** The news in [m] for [item] as of [today], or null when there is none. Pure. */
    fun find(item: JSONObject, m: Meta, today: LocalDate = LocalDate.now()): News? {
        val since = lastWatched(item) ?: return null
        val seen = Library.watched(item, m.videos)
        val floor = today.minusDays(DAYS).toString()
        val ceiling = today.plusDays(1).toString() // released up to the end of today; dates compare as ISO text
        val fresh = m.videos.filter { v ->
            v.season > 0 && v.id !in seen && v.released != null && v.released > since && v.released >= floor && v.released < ceiling
        }
        if (fresh.isEmpty()) return null
        return News(fresh.size, fresh.maxOf { it.released!! }.take(10), fresh.minByOrNull { it.released!! }!!.id)
    }

    /** Records the news in [m] for [item] (its details just arrived for a card, say) and returns it. */
    fun record(c: Context, profileId: String, item: JSONObject, m: Meta): News? {
        val news = find(item, m)
        prefs(c).edit().putString(key(profileId, item.optString("_id")), JSONObject()
            .put("n", news?.count ?: 0).put("latest", news?.latest ?: "").put("video", news?.videoId ?: "")
            .put("watched", lastWatched(item) ?: "").put("checked", System.currentTimeMillis()).toString()).apply()
        return news
    }

    /**
     * Asks for the episode list of each candidate not looked at lately (or watched since) and records what is new.
     * Blocks on the network: run it in the background. Returns true when the news changed for any series.
     */
    fun refresh(c: Context, profileId: String, items: List<JSONObject>, fetch: (String, String) -> Meta?): Boolean {
        val p = prefs(c)
        val now = System.currentTimeMillis()
        var changed = false
        val wanted = candidates(items)
        // Series gone from My List leave the record too.
        val keep = wanted.map { key(profileId, it.optString("_id")) }.toSet()
        p.all.keys.filter { it.startsWith("$profileId/") && it !in keep }.takeIf { it.isNotEmpty() }?.let { gone ->
            p.edit().also { e -> gone.forEach { e.remove(it) } }.apply()
        }
        for (item in wanted) {
            val k = key(profileId, item.optString("_id"))
            val old = p.getString(k, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            if (old.optString("watched") == lastWatched(item) && now - old.optLong("checked") < INTERVAL) continue
            val m = fetch(item.optString("type"), item.optString("_id")) ?: continue
            val news = record(c, profileId, item, m)
            if ((news?.count ?: 0) != old.optInt("n") || (news?.latest ?: "") != old.optString("latest")) changed = true
        }
        return changed
    }

    const val DAYS = 30L // how long a new episode counts as news on Home
    private const val MAX = 60 // series looked at, most recently watched first
    private const val INTERVAL = 6 * 60 * 60 * 1000L // between looks at one series' episode list
}
