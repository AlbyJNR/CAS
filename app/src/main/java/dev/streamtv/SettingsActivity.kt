package dev.streamtv

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView

/** Choices kept on this TV, shared by every profile (they're about the TV and the sound system, not the person). */
object Settings {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun maxHeight(c: Context) = prefs(c).getInt("maxHeight", 0) // 0: no limit
    fun maxSizeGb(c: Context) = prefs(c).getInt("maxSizeGb", 0) // 0: no limit
    fun hdr(c: Context) = prefs(c).getBoolean("hdr", true)
    fun alwaysList(c: Context) = prefs(c).getBoolean("alwaysList", false)
    fun matchFrameRate(c: Context) = prefs(c).getBoolean("matchFrameRate", true)
    fun passthrough(c: Context) = prefs(c).getBoolean("passthrough", false)
}

// One list of choices; OK on a row moves it to its next value, so there's no second screen to learn.
class SettingsActivity : Screen() {
    private class Choice(val key: String, val label: String, val hint: String, val values: List<Pair<Any, String>>)

    private val choices = listOf(
        Choice("alwaysList", "Play", "What Play and Resume do. Holding OK always shows every source.",
            listOf(false to "Best source", true to "Show sources")),
        Choice("maxHeight", "Highest quality", "Releases above this are skipped when picking a source.",
            listOf(0 to "No limit", 2160 to "4K", 1080 to "1080p", 720 to "720p")),
        Choice("maxSizeGb", "Largest file", "For slow connections: big files take longer to start.",
            listOf(0 to "No limit", 40 to "40 GB", 20 to "20 GB", 10 to "10 GB", 5 to "5 GB")),
        Choice("hdr", "HDR and Dolby Vision", "Off picks plain releases, for a TV that shows HDR washed out.",
            listOf(true to "On", false to "Off")),
        Choice("matchFrameRate", "Match frame rate", "The TV switches to the film's rate (24 Hz) so pans don't judder.",
            listOf(true to "On", false to "Off")),
        Choice("passthrough", "Surround sound", "Receiver sends Dolby and DTS untouched to a soundbar or receiver. " +
            "TV speakers decodes here, which always works.", listOf(false to "TV speakers", true to "Receiver")),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val list = column(Gravity.START).apply { setPadding(dp(PAD), dp(40), dp(PAD), dp(48)) }
        list.addView(text("Settings", DISPLAY, bold = true))
        list.addView(text("For this TV, every profile", BODY, color = MUTED).marginTop(8))
        choices.forEachIndexed { i, c ->
            fun current(): Int = c.values.indexOfFirst { (v, _) ->
                when (v) { is Boolean -> prefs.getBoolean(c.key, c.values[0].first as Boolean) == v; else -> prefs.getInt(c.key, c.values[0].first as Int) == v }
            }.coerceAtLeast(0)
            val value = text(c.values[current()].second, BODY, bold = true)
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(14), dp(20), dp(14))
                background = tile()
                addView(column(Gravity.START, text(c.label, BODY, bold = true), text(c.hint, SMALL, color = MUTED).marginTop(4)),
                    LinearLayout.LayoutParams(0, -2, 1f))
                addView(value, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(24) })
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (i == 0) 32 else 10) }
                focusable(grow = 1.01f)
                setOnClickListener {
                    val (next, label) = c.values[(current() + 1) % c.values.size]
                    prefs.edit().apply { if (next is Boolean) putBoolean(c.key, next) else putInt(c.key, next as Int) }.apply()
                    value.text = label
                }
            }
            list.addView(row)
            if (i == 0) row.post { row.requestFocus() }
        }
        setWindowed(ScrollView(this).apply { addView(list) })
    }
}
