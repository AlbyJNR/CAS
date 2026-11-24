package dev.alder

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/**
 * The weather line on Home: now, and the five days after today, for one town set once on this TV.
 * Open-Meteo answers without an account or a key. The request carries a map position cut to two decimals
 * (about a kilometre) and nothing else. No town, or no answer: there is no line, and Home is as it was.
 */
object Weather {
    class Place(val name: String, val lat: Double, val lon: Double)
    class Day(val name: String, val code: Int, val high: Int, val low: Int)
    class Report(val now: Int, val code: Int, val day: Boolean, val days: List<Day>)

    internal const val FRESH_MS = 3 * 3600_000L // ask again after this
    internal const val KEEP_MS = 12 * 3600_000L // an older copy is not shown

    private fun prefs(c: Context) = c.getSharedPreferences("weather", Context.MODE_PRIVATE)

    fun place(c: Context): Place? = prefs(c).run {
        val name = getString("name", null) ?: return null
        Place(name, getFloat("lat", 0f).toDouble(), getFloat("lon", 0f).toDouble())
    }

    fun setPlace(c: Context, p: Place?) = prefs(c).edit().clear().apply {
        if (p != null) putString("name", p.name).putFloat("lat", p.lat.toFloat()).putFloat("lon", p.lon.toFloat())
    }.apply()

    /** Towns with this name, the largest first. Network: call it off the main thread. */
    fun find(name: String): List<Place> {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=4&language=en&name=" + java.net.URLEncoder.encode(name, "UTF-8")
        return places(Http.get(url, timeoutMs = 8_000))
    }

    /** The copy on this TV, if it is young enough to show. Never touches the network. */
    fun saved(c: Context, nowMs: Long = System.currentTimeMillis()): Report? = prefs(c).run {
        if (!shows(getLong("at", 0), nowMs)) return null
        getString("answer", null)?.let { report(it, today(nowMs)) }
    }

    fun due(c: Context, nowMs: Long = System.currentTimeMillis()) = place(c) != null && asksAgain(prefs(c).getLong("at", 0), nowMs)

    /** Asks for a new report and keeps it. Network: call it off the main thread. Null when the answer is no use. */
    fun fetch(c: Context): Report? {
        val p = place(c) ?: return null
        val body = Http.get(url(p.lat, p.lon), timeoutMs = 8_000)
        val now = System.currentTimeMillis()
        val r = report(body, today(now)) ?: return null
        prefs(c).edit().putString("answer", body).putLong("at", now).apply()
        return r
    }

    private fun today(nowMs: Long) = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).format(java.util.Date(nowMs))
}

internal fun shows(atMs: Long, nowMs: Long) = atMs > 0 && nowMs - atMs in 0 until Weather.KEEP_MS
internal fun asksAgain(atMs: Long, nowMs: Long) = atMs <= 0 || nowMs < atMs || nowMs - atMs >= Weather.FRESH_MS

internal fun url(lat: Double, lon: Double) = "https://api.open-meteo.com/v1/forecast" +
    "?latitude=" + String.format(Locale.US, "%.2f", lat) + "&longitude=" + String.format(Locale.US, "%.2f", lon) +
    "&current=temperature_2m,weather_code,is_day&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=7"

/** The report in the service's answer: the days after [today] (yyyy-MM-dd), five at most. Null if the answer is not a report. */
internal fun report(answer: String, today: String): Weather.Report? = runCatching {
    val j = JSONObject(answer)
    val current = j.getJSONObject("current")
    val daily = j.getJSONObject("daily")
    val dates = daily.getJSONArray("time")
    val codes = daily.getJSONArray("weather_code")
    val highs = daily.getJSONArray("temperature_2m_max")
    val lows = daily.getJSONArray("temperature_2m_min")
    val parse = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val name = java.text.SimpleDateFormat("EEE", Locale.UK)
    val days = (0 until dates.length()).filter { dates.getString(it) > today && !highs.isNull(it) && !lows.isNull(it) }.take(5).map { i ->
        Weather.Day(name.format(parse.parse(dates.getString(i))!!), codes.optInt(i, 3), Math.round(highs.getDouble(i)).toInt(), Math.round(lows.getDouble(i)).toInt())
    }
    Weather.Report(Math.round(current.getDouble("temperature_2m")).toInt(), current.optInt("weather_code", 3), current.optInt("is_day", 1) == 1, days)
}.getOrNull()

internal fun places(answer: String): List<Weather.Place> = runCatching {
    val list = JSONObject(answer).optJSONArray("results") ?: return emptyList()
    (0 until list.length()).map { list.getJSONObject(it) }.map { p ->
        val where = listOf(p.optString("name"), p.optString("admin1"), p.optString("country")).filter { it.isNotEmpty() }.distinct().joinToString(", ")
        Weather.Place(where, p.getDouble("latitude"), p.getDouble("longitude"))
    }
}.getOrDefault(emptyList())

/** The sky for a WMO weather code, as one of eight pictures. */
internal enum class Sky(val word: String) { SUN("Sunny"), MOON("Clear"), PART("Some cloud"), CLOUD("Cloudy"), FOG("Fog"), RAIN("Rain"), SNOW("Snow"), STORM("Thunder") }

internal fun sky(code: Int, day: Boolean = true): Sky = when (code) {
    0, 1 -> if (day) Sky.SUN else Sky.MOON
    2 -> if (day) Sky.PART else Sky.CLOUD
    45, 48 -> Sky.FOG
    in 51..67, in 80..82 -> Sky.RAIN
    in 71..77, 85, 86 -> Sky.SNOW
    in 95..99 -> Sky.STORM
    else -> Sky.CLOUD
}

/** The word beside the temperature now. Drizzle and showers get their own word; the picture is rain. */
internal fun skyWord(code: Int, day: Boolean): String = when (code) {
    in 51..57 -> "Drizzle"
    in 80..82 -> "Showers"
    else -> sky(code, day).word
}
