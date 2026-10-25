package dev.alder

import android.content.Context
import android.app.ActivityManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import java.util.Locale

/** Choices kept on this TV, shared by every profile (they're about the TV and the sound system, not the person). */
object Settings {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun maxHeight(c: Context) = prefs(c).getInt("maxHeight", 0) // 0: no limit
    fun maxSizeGb(c: Context) = prefs(c).getInt("maxSizeGb", 0) // 0: no limit
    fun hdr(c: Context) = prefs(c).getBoolean("hdr", true)
    fun alwaysList(c: Context) = prefs(c).getBoolean("alwaysList", false)
    fun matchFrameRate(c: Context) = prefs(c).getBoolean("matchFrameRate", true)
    fun passthrough(c: Context) = prefs(c).getBoolean("passthrough", false)
    fun subtitles(c: Context) = prefs(c).getBoolean("subtitles", true)
    /** Two-letter code of the dub to start with: the TV's language unless one is picked. */
    fun audioLanguage(c: Context): String = prefs(c).getString("audioLang", "").orEmpty().ifEmpty { Locale.getDefault().language }
    fun subtitleSize(c: Context) = prefs(c).getInt("subSize", 100) // percent of Media3's default size
    fun subtitleBackground(c: Context) = prefs(c).getBoolean("subBackground", true)
}

class SettingsActivity : Screen() {
    private class Choice(val key: String, val label: String, val hint: String, val values: List<Pair<Any, String>>)

    private val streaming = listOf(
        Choice("alwaysList", "Play", "What Play and Resume do. Holding OK always shows every source.",
            listOf(false to "Best source", true to "Show sources")),
        Choice("maxHeight", "Highest quality", "Releases above this are skipped when picking a source.",
            listOf(0 to "No limit", 2160 to "4K", 1080 to "1080p", 720 to "720p")),
        Choice("maxSizeGb", "Largest file", "For slow connections: big files take longer to start.",
            listOf(0 to "No limit", 40 to "40 GB", 20 to "20 GB", 10 to "10 GB", 5 to "5 GB")),
    )
    private val pictureAndSound = listOf(
        Choice("hdr", "HDR and Dolby Vision", "Off picks plain releases, for a TV that shows HDR washed out.",
            listOf(true to "On", false to "Off")),
        Choice("matchFrameRate", "Match frame rate", "The TV switches to the film's rate (24 Hz) so pans don't judder.",
            listOf(true to "On", false to "Off")),
        Choice("audioLang", "Audio language", "The dub to start with when a file has several. Changeable while watching.",
            listOf("" to "TV language", "en" to "English", "hi" to "Hindi", "es" to "Spanish", "fr" to "French",
                "de" to "German", "ja" to "Japanese", "ko" to "Korean")),
        Choice("passthrough", "Surround sound", "Receiver sends Dolby and DTS untouched to a soundbar or receiver. TV speakers decodes here, which always works.",
            listOf(false to "TV speakers", true to "Receiver")),
    )
    private val subtitles = listOf(
        Choice("subtitles", "Subtitles by default", "On: subtitles in the TV's language start with the video when there are any.",
            listOf(true to "On", false to "Off")),
        Choice("subSize", "Size", "How big subtitles are on screen.",
            listOf(100 to "Medium", 130 to "Large", 160 to "Extra large", 80 to "Small")),
        Choice("subBackground", "Dark background", "A dark box behind subtitles, for bright scenes.",
            listOf(true to "On", false to "Off")),
    )

    private lateinit var list: LinearLayout
    private lateinit var hint: TextView
    private lateinit var addonCount: TextView
    private val scene by lazy { ImageView(this).apply { setImageResource(R.drawable.settings_scene); scaleType = ImageView.ScaleType.CENTER_CROP } }
    override val backdrop get() = scene

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = column(Gravity.START).apply { setPadding(0, dp(15), 0, dp(30)) }
        list.addView(text("Settings", DISPLAY, bold = true))

        heading("General")
        action("Language", Locale.getDefault().displayLanguage, "The TV's own language setting; the app follows it.") {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS).setData(android.net.Uri.parse("package:$packageName"))) }
                .recoverCatching { startActivity(Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS)) }
                .onFailure { choose("Language settings are unavailable on this TV.", listOf("OK")) {} }
        }
        heading("Addons")
        val signedIn = authKey != null
        addonCount = action("My addons", if (signedIn) "…" else "Sign in", "Add by URL, reorder or remove this profile's addons.") {
            if (signedIn) startActivity(Intent(this, AddonsActivity::class.java))
        }
        action("Sync addons", "", "Fetch this profile's addons again, after installing some elsewhere.") {
            val key = authKey ?: return@action
            addonCount.text = "Syncing…"
            background({ Stremio.addons(key, refresh = true) }) { r -> addonCount.text = r.fold({ "${it.size} installed  ›" }, { "Sync failed" }) }
        }

        val firstRowId = View.generateViewId()
        section("Streaming", streaming, firstRowId)
        section("Picture & Sound", pictureAndSound)
        section("Subtitles", subtitles)
        heading("Storage")
        action("Clear local storage", "", "Removes profiles, settings and saved data from this TV. Stremio accounts stay as they are.") {
            choose("Clear local storage?", listOf("Cancel", "Clear"),
                "This removes saved profiles, settings, and cached data from this TV. It does not cancel your Stremio account.") { i ->
                if (i == 1) (getSystemService(ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
            }
        }

        hint = text("", BODY, color = TEXT_MID).apply { setPadding(0, dp(10), 0, dp(18)) }
        val scroll = ScrollView(this).apply {
            isFocusable = false
            isVerticalScrollBarEnabled = false
            descendantFocusability = ScrollView.FOCUS_AFTER_DESCENDANTS
            addView(list)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(hint, LinearLayout.LayoutParams(-1, -2))
        }
        val panelWidth = (resources.displayMetrics.widthPixels * 0.58f).toInt()
        // The list on a pane of glass inset from the edges, like the episode list; the words stay on the side margin.
        panel.setPadding(dp(PAD - GLASS_INSET), 0, dp(PAD - GLASS_INSET), 0)
        panel.background = glassOver(panel, 14)
        setWindowed(FrameLayout(this).apply {
            addView(panel, FrameLayout.LayoutParams(panelWidth, -1, Gravity.START).apply { setMargins(dp(GLASS_INSET), dp(GLASS_INSET), 0, dp(GLASS_INSET)) })
        }, scene)
    }

    override fun onResume() {
        super.onResume()
        val key = authKey ?: return
        background({ Stremio.addons(key).size }) { r -> addonCount.text = r.getOrNull()?.let { "$it installed  ›" } ?: "Unavailable" }
    }

    private fun heading(title: String) {
        list.addView(text(title, BODY, bold = true).apply {
            setPadding(0, dp(8), 0, dp(4))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    /** One row: the shared white focus, the label left, [value] right; its hint shows below the list. */
    private fun row(label: String, value: View, hintText: String, click: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(text(label, BODY, color = TEXT_HI), LinearLayout.LayoutParams(0, -2, 1f))
        addView(value, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(20) })
        setPadding(dp(13), dp(4), dp(8), dp(4))
        rowFocus(6) { has ->
            (value as? Switch)?.thumbTintList = ColorStateList.valueOf(if (has) INK else Color.WHITE)
            if (has) hint.text = hintText
        }
        setOnClickListener { click() }
        list.addView(this, LinearLayout.LayoutParams(-1, dp(40)))
    }

    /** A row that opens something: its value (if any) and a ›. Returns the value text, to update. */
    private fun action(label: String, value: String, hintText: String, click: () -> Unit): TextView {
        val v = text(if (value.isEmpty()) "›" else "$value  ›", BODY, color = TEXT_HI)
        row(label, v, hintText, click)
        return v
    }

    private fun section(title: String, choices: List<Choice>, firstId: Int? = null) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        heading(title)
        choices.forEachIndexed { i, c ->
            fun current(): Int = c.values.indexOfFirst { (v, _) ->
                when (v) {
                    is Boolean -> prefs.getBoolean(c.key, c.values[0].first as Boolean) == v
                    is String -> prefs.getString(c.key, c.values[0].first as String) == v
                    else -> prefs.getInt(c.key, c.values[0].first as Int) == v
                }
            }.coerceAtLeast(0)
            // On/Off choices are switches; the rest name their value.
            val isSwitch = c.values.map { it.second }.toSet() == setOf("On", "Off")
            val value: View = if (isSwitch) Switch(this).apply {
                isChecked = c.values[current()].first as Boolean
                isClickable = false
                isFocusable = false
                showText = false
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
                trackTintList = ColorStateList.valueOf(0xFF65717D.toInt())
            } else text(c.values[current()].second, BODY, color = TEXT_HI).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            val r = row(c.label, value, c.hint) {
                val (next, labelText) = c.values[(current() + 1) % c.values.size]
                prefs.edit().apply {
                    when (next) { is Boolean -> putBoolean(c.key, next); is String -> putString(c.key, next); else -> putInt(c.key, next as Int) }
                }.apply()
                if (value is Switch) value.isChecked = next as Boolean else (value as TextView).text = labelText
            }
            if (firstId != null && i == 0) { r.id = firstId; r.post { r.requestFocus() } }
        }
    }

}
