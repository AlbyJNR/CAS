package dev.streamtv

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.tv.TvContract.WatchNextPrograms
import android.media.tv.TvContract
import org.json.JSONObject

/**
 * Continue Watching on the Google TV home ("Watch Next"), through Android's own TV provider: our rows are replaced
 * with the active profile's list each time Home shows a changed one. A profile with a PIN puts nothing there, since
 * the home screen would show its titles to anyone. Picking one opens the app on that title's page.
 */
object WatchNext {
    private const val MAX = 10

    fun sync(context: Context, items: List<JSONObject>, locked: Boolean) = runCatching {
        val resolver = context.contentResolver
        resolver.query(TvContract.WatchNextPrograms.CONTENT_URI, arrayOf(WatchNextPrograms._ID), null, null, null)?.use { c ->
            while (c.moveToNext()) resolver.delete(TvContract.buildWatchNextProgramUri(c.getLong(0)), null, null)
        }
        if (locked) return@runCatching
        items.take(MAX).forEach { item ->
            val state = item.optJSONObject("state") ?: JSONObject()
            val series = item.optString("type") == "series"
            // A series' video id is "tt123:1:3": season, then episode.
            val parts = state.optString("video_id").split(":")
            val art = item.text("background")
            val open = Intent(context, ProfilesActivity::class.java)
                .putExtra("type", item.optString("type")).putExtra("id", item.optString("_id"))
                .putExtra("name", item.optString("name")).putExtra("poster", item.text("poster"))
                .putExtra("background", art).putExtra("videoId", state.text("video_id"))
            resolver.insert(WatchNextPrograms.CONTENT_URI, ContentValues().apply {
                put(WatchNextPrograms.COLUMN_TYPE, if (series) WatchNextPrograms.TYPE_TV_EPISODE else WatchNextPrograms.TYPE_MOVIE)
                put(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                put(WatchNextPrograms.COLUMN_TITLE, item.optString("name"))
                if (series && parts.size == 3) {
                    put(WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER, parts[1])
                    put(WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER, parts[2])
                }
                put(WatchNextPrograms.COLUMN_POSTER_ART_URI, art ?: item.text("poster"))
                put(WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO,
                    if (art != null) WatchNextPrograms.ASPECT_RATIO_16_9 else WatchNextPrograms.ASPECT_RATIO_2_3)
                put(WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, state.optLong("timeOffset").toInt())
                put(WatchNextPrograms.COLUMN_DURATION_MILLIS, state.optLong("duration").toInt())
                put(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, lastWatched(state))
                put(WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, item.optString("_id"))
                put(WatchNextPrograms.COLUMN_INTENT_URI, open.toUri(Intent.URI_INTENT_SCHEME))
            })
        }
    }.onFailure { android.util.Log.w("StreamTV", "Watch Next not updated", it) }

    private fun lastWatched(state: JSONObject): Long = runCatching {
        java.time.Instant.parse(state.optString("lastWatched")).toEpochMilli()
    }.getOrDefault(System.currentTimeMillis())
}
