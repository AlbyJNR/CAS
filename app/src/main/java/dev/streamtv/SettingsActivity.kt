package dev.streamtv

import android.content.Context
import android.app.AlertDialog
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
import android.widget.EditText
import org.json.JSONObject

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
        Choice("passthrough", "Surround sound", "Receiver sends Dolby and DTS untouched to a soundbar or receiver. TV speakers decodes here, which always works.",
            listOf(false to "TV speakers", true to "Receiver")),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val list = column(Gravity.START).apply { setPadding(dp(PAD), dp(15), dp(PAD), dp(30)) }
        list.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            val back = text("‹", 28f, color = TEXT_HI).apply {
                gravity = Gravity.CENTER
                contentDescription = "Back"
                isFocusable = true
                setOnClickListener { finish() }
                setOnFocusChangeListener { v, has -> v.background = if (has) glass(8, focused = true) else null }
            }
            addView(back, LinearLayout.LayoutParams(dp(24), dp(35)))
            addView(text("Settings", DISPLAY + 1, bold = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        })
        fun infoRow(label: String, value: String, valueView: View? = null) {
            list.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(text(label, BODY - 1, color = TEXT_HI), LinearLayout.LayoutParams(0, -2, 1f))
                addView(valueView ?: text(value, BODY - 1, color = TEXT_HI), LinearLayout.LayoutParams(-2, -2))
                contentDescription = "$label. $value"
                setOnFocusChangeListener { v, has -> v.background = if (has) glass(6) else null }
            }, LinearLayout.LayoutParams(-1, dp(26)))
        }
        fun divider() = list.addView(View(this).apply { setBackgroundColor(0x66FFFFFF) }, LinearLayout.LayoutParams(-1, dp(1)))
        fun heading(title: String) {
            list.addView(text(title.uppercase(), SMALL - 1, bold = true, color = TEXT_HI).apply {
                letterSpacing = 0.12f
                setPadding(0, dp(8), 0, dp(4))
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            divider()
        }
        heading("General")
        infoRow("System language", "Open settings")
        (list.getChildAt(list.childCount - 1) as View).apply {
            isFocusable = true
            setOnClickListener {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS).setData(android.net.Uri.parse("package:$packageName"))) }
                    .recoverCatching { startActivity(Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS)) }
                    .onFailure { AlertDialog.Builder(this@SettingsActivity).setMessage("Language settings are unavailable on this TV.").setPositiveButton("OK", null).show() }
            }
        }
        infoRow("Esc exits full screen", "Unavailable")
        infoRow("Clear local storage", "Clear…")
        list.getChildAt(list.childCount - 1).apply {
            isFocusable = true
            setOnClickListener { AlertDialog.Builder(this@SettingsActivity).setTitle("Clear local storage?")
                .setMessage("This removes saved profiles, settings, and cached data from this TV. It does not cancel your Stremio account.")
                .setNegativeButton("Cancel", null).setPositiveButton("Clear") { _, _ ->
                    (getSystemService(ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                }.show() }
        }
        heading("Addons")
        infoRow("Search addons", "Search…")
        list.getChildAt(list.childCount - 1).apply {
            isFocusable = true
            setOnClickListener { searchAddons() }
        }
        val currentAuthKey = authKey
        val addonCount = text(if (currentAuthKey == null) "Sign in" else "Loading…", BODY - 1, color = TEXT_HI)
        infoRow("My addons", "", addonCount)
        list.getChildAt(list.childCount - 1).apply {
            isFocusable = currentAuthKey != null
            setOnClickListener { startActivity(Intent(this@SettingsActivity, AddonsActivity::class.java)) }
        }
        infoRow("Sync addons", if (currentAuthKey == null) "Sign in" else "Refresh")
        list.getChildAt(list.childCount - 1).apply {
            isFocusable = currentAuthKey != null
            setOnClickListener { currentAuthKey?.let { key ->
                addonCount.text = "Syncing…"
                background({ Stremio.addons(key, refresh = true) }) { result -> addonCount.text = result.fold({ "${it.size} installed · synced" }, { "Sync failed: ${it.message ?: "network error"}" }) }
            } }
        }
        currentAuthKey?.let { key -> background({ Stremio.addons(key).size }) { count ->
            addonCount.text = count.getOrNull()?.let { "$it installed" } ?: "Unavailable"
        } }
        fun section(title: String, choices: List<Choice>, firstId: Int? = null) {
            heading(title)
            choices.forEachIndexed { i, c ->
                fun current(): Int = c.values.indexOfFirst { (v, _) ->
                    when (v) {
                        is Boolean -> prefs.getBoolean(c.key, c.values[0].first as Boolean) == v
                        else -> prefs.getInt(c.key, c.values[0].first as Int) == v
                    }
                }.coerceAtLeast(0)
                val value: View = if (c.key == "hdr" || c.key == "matchFrameRate") Switch(this).apply {
                    isChecked = c.values[current()].first as Boolean
                    isClickable = false
                    isFocusable = false
                    showText = false
                    thumbTintList = ColorStateList.valueOf(Color.WHITE)
                    trackTintList = ColorStateList.valueOf(0xFF65717D.toInt())
                } else text(c.values[current()].second, BODY - 1, color = TEXT_HI).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
                val indicator = View(this).apply { setBackgroundColor(Color.WHITE); visibility = View.INVISIBLE }
                val row = LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(indicator, LinearLayout.LayoutParams(dp(3), -1))
                    val label = text(c.label, BODY - 1, color = TEXT_HI)
                    addView(label, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
                    addView(value, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(20) })
                    setPadding(0, dp(4), dp(2), dp(4))
                    contentDescription = "${c.label}. ${c.hint} Current value: ${c.values[current()].second}. Press OK to change."
                    layoutParams = LinearLayout.LayoutParams(-1, dp(26)).apply { topMargin = dp(if (i == 0) 2 else 0) }
                    focusable(grow = 1f)
                    setOnFocusChangeListener { v, has ->
                        v.background = if (has) glass(6) else null
                        indicator.visibility = if (has) View.VISIBLE else View.INVISIBLE
                    }
                    setOnClickListener {
                        val (next, labelText) = c.values[(current() + 1) % c.values.size]
                        prefs.edit().apply { if (next is Boolean) putBoolean(c.key, next) else putInt(c.key, next as Int) }.apply()
                        if (value is Switch) value.isChecked = next as Boolean else (value as TextView).text = labelText
                        contentDescription = "${c.label}. ${c.hint} Current value: $labelText. Press OK to change."
                    }
                }
                if (firstId != null && i == 0) row.id = firstId
                list.addView(row)
                if (title == "Streaming" && i == 0) row.post { row.requestFocus() }
            }
        }
        val firstRowId = View.generateViewId()
        (list.getChildAt(0) as LinearLayout).getChildAt(0).nextFocusDownId = firstRowId
        section("Streaming", streaming, firstRowId)
        section("Picture & Sound", pictureAndSound)
        heading("Subtitles")
        infoRow("Subtitles by default", if (Settings.subtitles(this)) "On" else "Off")
        list.getChildAt(list.childCount - 1).apply {
            isFocusable = true
            setOnClickListener {
                val enabled = !Settings.subtitles(this@SettingsActivity)
                prefs.edit().putBoolean("subtitles", enabled).apply()
                (this as LinearLayout).getChildAt(1).let { (it as TextView).text = if (enabled) "On" else "Off" }
                contentDescription = "Subtitles by default. ${if (enabled) "On" else "Off"}"
            }
        }
        val scroll = ScrollView(this).apply {
            isFocusable = false
            descendantFocusability = ScrollView.FOCUS_AFTER_DESCENDANTS
            addView(list)
        }
        val panelWidth = (resources.displayMetrics.widthPixels * 0.58f).toInt()
        setWindowed(FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(panelWidth, -1, Gravity.START))
        }, ImageView(this).apply {
            setImageResource(R.drawable.settings_scene)
            scaleType = ImageView.ScaleType.CENTER_CROP
        })
    }

    private fun searchAddons() {
        val key = authKey ?: run { AlertDialog.Builder(this).setMessage("Sign in to install addons.").setPositiveButton("OK", null).show(); return }
        val input = EditText(this).apply { hint = "Addon name"; isSingleLine = true }
        AlertDialog.Builder(this).setTitle("Search community addons").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Search") { _, _ ->
                val query = input.text.toString().trim()
                if (query.isBlank()) return@setPositiveButton
                background({
                    val addons = JSONObject(Http.get("https://v3-cinemeta.strem.io/addon_catalog/all/community.json"))
                        .getJSONArray("addons").objects().map(::Addon)
                    addons.filter { it.name.contains(query, ignoreCase = true) }
                }) { result ->
                    val found = result.getOrElse { AlertDialog.Builder(this).setMessage("Search failed: ${it.message ?: "network error"}").setPositiveButton("OK", null).show(); return@background }
                    if (found.isEmpty()) { AlertDialog.Builder(this).setMessage("No matching addons.").setPositiveButton("OK", null).show(); return@background }
                    background({ Stremio.addons(key) }) { installedResult ->
                        val installed = installedResult.getOrDefault(emptyList()).map { it.transportUrl }.toSet()
                        AlertDialog.Builder(this).setTitle("Community addons").setItems(found.map { addon ->
                            addon.name + when { addon.transportUrl in installed -> " · Installed"; (addon.descriptor.optJSONObject("manifest")?.optJSONArray("config")?.length() ?: 0) > 0 -> " · Configure on Stremio"; else -> "" }
                        }.toTypedArray()) { _, which ->
                            val addon = found[which]
                            when {
                                addon.transportUrl in installed -> AlertDialog.Builder(this).setMessage("Already installed.").setPositiveButton("OK", null).show()
                                (addon.descriptor.optJSONObject("manifest")?.optJSONArray("config")?.length() ?: 0) > 0 -> AlertDialog.Builder(this).setMessage("This addon needs configuration. Configure it on Stremio, then sync addons here.").setPositiveButton("OK", null).show()
                                else -> AlertDialog.Builder(this).setMessage("Install ${addon.name} to this account?").setNegativeButton("Cancel", null).setPositiveButton("Install") { _, _ ->
                                    background({
                                        val latest = Stremio.addons(key, refresh = true)
                                        if (latest.none { it.transportUrl == addon.transportUrl }) Stremio.setAddons(key, latest + addon)
                                        Stremio.addons(key)
                                    }) { saved ->
                                        val msg = saved.fold({ "${it.size} addons installed" }, { "Install failed: ${it.message ?: "network error"}" })
                                        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("OK", null).show()
                                    }
                                }.show()
                            }
                        }.setNegativeButton("Close", null).show()
                    }
                }
            }.show()
    }
}
