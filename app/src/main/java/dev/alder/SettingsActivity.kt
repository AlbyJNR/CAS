package dev.alder

import android.content.Context
import android.app.ActivityManager
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

/**
 * Choices kept on this TV. Most are shared by every profile (they're about the TV and the sound system); taste (release
 * type, dubs and audio language) is kept per profile under "key@profileId".
 */
object Settings {
    private fun prefs(c: Context) = c.getSharedPreferences("settings", Context.MODE_PRIVATE)
    fun maxHeight(c: Context) = prefs(c).getInt("maxHeight", 0) // 0: no limit
    fun maxSizeGb(c: Context) = prefs(c).getInt("maxSizeGb", 0) // 0: no limit
    /** "": as the addon orders them, "dv" or "hdr10": that first, "off": plain releases only. */
    fun hdrPref(c: Context): String {
        val p = prefs(c)
        if (!p.contains("hdrPref")) p.edit().putString("hdrPref", if (p.getBoolean("hdr", true)) "" else "off").remove("hdr").apply() // was On/Off
        return p.getString("hdrPref", "").orEmpty()
    }
    fun hdr(c: Context) = hdrPref(c) != "off"
    /** "": no preference, "remux", "encode" (smaller BluRay and web encodes) or "web". */
    fun release(c: Context) = prefs(c).getString(profileKey(c, "release"), "").orEmpty()
    /** "": no preference, "original": dubs last, "mine": the audio language first. */
    fun dub(c: Context) = prefs(c).getString(profileKey(c, "dub"), "").orEmpty()
    fun profileKey(c: Context, key: String) = "$key@${Profiles.active(c)?.id}"
    /** A removed profile's taste goes with it, so a new profile in its place starts with none. */
    fun forget(c: Context, profileId: String) = prefs(c).edit().remove("release@$profileId").remove("dub@$profileId").remove("audioLang@$profileId").apply()
    fun alwaysList(c: Context) = prefs(c).getBoolean("alwaysList", false)
    fun matchFrameRate(c: Context) = prefs(c).getBoolean("matchFrameRate", true)
    fun passthrough(c: Context) = prefs(c).getBoolean("passthrough", false)
    fun subtitles(c: Context) = prefs(c).getBoolean("subtitles", true)
    /** Two-letter code of the dub to start with: the TV's language unless one is picked. */
    fun audioLanguage(c: Context): String = prefs(c).getString(profileKey(c, "audioLang"), prefs(c).getString("audioLang", ""))
        .orEmpty().ifEmpty { Locale.getDefault().language }
    fun subtitleSize(c: Context) = prefs(c).getInt("subSize", 100) // percent of Media3's default size
    fun subtitleBackground(c: Context) = prefs(c).getBoolean("subBackground", true)
}

internal fun nextSettingIndex(current: Int, size: Int, direction: Int): Int {
    require(size > 0 && direction in -1..1 && direction != 0)
    return if (current < 0) if (direction < 0) size - 1 else 0 else (current + direction + size) % size
}

class SettingsActivity : Screen() {
    private class Choice(val key: String, val label: String, val hint: String, val values: List<Pair<Any, String>>, val perProfile: Boolean = false) {
        init { require(values.isNotEmpty() && values.all { it.first.javaClass == values[0].first.javaClass }) }
    }
    private enum class Group(val label: String, val icon: Int = 0, val glyph: String = "") {
        STREAMS("Streams", R.drawable.ic_play),
        PICTURE("Picture & Sound", R.drawable.ic_tv),
        SUBTITLES("Subtitles", R.drawable.ic_subtitles),
        ADDONS("Add-ons", R.drawable.ic_layers),
        PROFILE("Profile", glyph = "♙"),
        LANGUAGE("Language", glyph = "◎"),
        STORAGE("Storage", glyph = "▤"),
        ABOUT("About", R.drawable.ic_info),
    }

    private val streaming = listOf(
        Choice("alwaysList", "When I press Play", "What Play and Resume do. Holding OK always shows every source.",
            listOf(false to "Best source", true to "Show sources")),
        Choice("maxHeight", "Highest quality", "Releases above this are skipped when picking a source.",
            listOf(0 to "No limit", 2160 to "4K", 1080 to "1080p", 720 to "720p")),
        Choice("maxSizeGb", "Largest file", "For slow connections: big files take longer to start.",
            listOf(0 to "No limit", 40 to "40 GB", 20 to "20 GB", 10 to "10 GB", 5 to "5 GB")),
        Choice("release", "Release type", "Which kind of file is tried first for this profile. REMUX is the full Blu-ray.",
            listOf("" to "No preference", "remux" to "Full Blu-ray (REMUX)", "encode" to "Smaller encodes", "web" to "Web releases"), true),
    )
    private val pictureAndSound = listOf(
        Choice("hdrPref", "HDR and Dolby Vision", "Which goes first when a title has both. Off picks plain releases.",
            listOf("" to "On", "dv" to "Dolby Vision first", "hdr10" to "HDR10 first", "off" to "Off")),
        Choice("matchFrameRate", "Match frame rate", "The TV switches to the film's rate so pans don't judder.",
            listOf(true to "On", false to "Off")),
        Choice("passthrough", "Audio output", "Receiver sends Dolby and DTS untouched to a soundbar or receiver. TV speakers decodes the sound here, which always works.",
            listOf(false to "TV speakers", true to "Receiver")),
        Choice("audioLang", "Audio language", "The dub to start with when a file has several. Changeable while watching.",
            listOf("" to "TV language", "en" to "English", "hi" to "Hindi", "es" to "Spanish", "fr" to "French",
                "de" to "German", "ja" to "Japanese", "ko" to "Korean"), true),
        Choice("dub", "Dubbed releases", "Original first puts dubbed releases last; My language first uses this profile's audio language.",
            listOf("" to "No preference", "original" to "Original first", "mine" to "My language first"), true),
    )
    private val subtitles = listOf(
        Choice("subtitles", "Subtitles by default", "Subtitles in the TV's language start with the video when available.",
            listOf(true to "On", false to "Off")),
        Choice("subSize", "Subtitle size", "How big subtitles are on screen.",
            listOf(100 to "Medium", 130 to "Large", 160 to "Extra large", 80 to "Small")),
        Choice("subBackground", "Subtitle background", "A dark box behind subtitles keeps them readable in bright scenes.",
            listOf(true to "On", false to "Off")),
    )

    private val scene by lazy { ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setImageResource(R.drawable.settings_scene)
        alpha = 0.75f
    } }
    override val backdrop get() = scene
    private lateinit var groups: LinearLayout
    private lateinit var detailRows: LinearLayout
    private lateinit var rowsScroll: ScrollView
    private lateinit var detailTitle: TextView
    private lateinit var hint: TextView
    private lateinit var profileName: TextView
    private lateinit var avatar: TextView
    private val groupViews = mutableMapOf<Group, View>()
    private val groupDots = mutableMapOf<Group, TextView>()
    private val groupLabels = mutableMapOf<Group, TextView>()
    private val choiceRows = mutableSetOf<View>()
    private val refresh = mutableListOf<() -> Unit>()
    private var selectedGroup: Group? = null
    private var firstDetail: View? = null
    private var addonValue: TextView? = null
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.hdrPref(this) // migrate the old HDR switch before any row reads it
        val width = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val height = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(BASE); clipChildren = false; clipToPadding = false }
        root.addView(scene, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0x660E0C0A, 0xB00E0C0A.toInt(), 0x990E0C0A.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        avatar = text(Profiles.active(this)?.name?.take(1)?.uppercase() ?: "A", BODY, bold = true, color = TUNGSTEN).apply {
            gravity = Gravity.CENTER
            background = glass(15)
        }
        header.addView(avatar, LinearLayout.LayoutParams(dp(30), dp(30)))
        header.addView(text("Settings", SERIF_TITLE, serif = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        profileName = text("", SMALL, color = TEXT_3).apply { setPadding(dp(14), dp(8), 0, 0) }
        header.addView(profileName)
        header.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(android.widget.TextClock(this).apply {
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            textSize = SMALL
            setTextColor(TEXT_2)
            typeface = text("", SMALL).typeface
        })
        root.addView(header, FrameLayout.LayoutParams(-1, dp(40), Gravity.TOP).apply {
            leftMargin = dp(48); rightMargin = dp(48); topMargin = dp(24)
        })

        groups = column(Gravity.START).apply {
            setPadding(dp(7), dp(9), dp(7), dp(9))
            background = glassOver(this, 14)
        }
        Group.entries.forEach { group ->
            if (group == Group.STORAGE) groups.addView(View(this).apply { setBackgroundColor(GLASS_EDGE) },
                LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(6), dp(8), dp(6), dp(8)) })
            addGroup(group)
        }
        root.addView(groups, FrameLayout.LayoutParams(dp(200), dp((height - 115).toInt()), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(48); topMargin = dp(88)
        })

        detailTitle = text("", BODY, bold = true).apply { setPadding(dp(12), 0, 0, 0) }
        detailRows = column(Gravity.START)
        rowsScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(detailRows)
        }
        hint = text("", SMALL, color = TEXT_2).onArt().apply { setPadding(dp(12), 0, dp(20), 0) }
        // The help follows the rows, so a long group (Streams has six) never runs under it.
        val detail = column(Gravity.START).apply {
            clipChildren = false
            addView(detailTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            addView(rowsScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            addView(hint, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        }
        root.addView(detail, FrameLayout.LayoutParams(dp((width - 328).toInt()), dp((height - 110).toInt()), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(280); topMargin = dp(88)
        })
        window.setBackgroundDrawable(null)
        setContentView(root)
        profileName.text = Profiles.active(this)?.name?.let { "Profile " + it } ?: ""
        showGroup(Group.STREAMS)
        groupViews[Group.STREAMS]?.post { groupViews[Group.STREAMS]?.requestFocus() }
        val profile = Profiles.active(this)
        val art = profile?.let { getSharedPreferences("home", MODE_PRIVATE).getString("art:" + it.id, null) }
        if (art != null) Images.load(art, scene, resources.displayMetrics.widthPixels, urgent = true,
            failed = { scene.setImageResource(R.drawable.settings_scene); refrost() }) { dimForBrightness(); refrost() }
    }

    /** A bright film (a white kitchen) sits lower than the mock's 75%, so the glass and rows stay readable over it. */
    private fun dimForBrightness() {
        val bitmap = (scene.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
        // Decoded art is a hardware bitmap, which can't be read: read a small software copy of it.
        val pixels = runCatching {
            val sample = android.graphics.Bitmap.createScaledBitmap(bitmap, 16, 9, true).copy(android.graphics.Bitmap.Config.ARGB_8888, false)
            IntArray(16 * 9).also { sample.getPixels(it, 0, 16, 0, 0, 16, 9); sample.recycle() }
        }.getOrNull() ?: return
        scene.alpha = if (brightLeft(pixels, 16)) 0.5f else 0.75f
    }

    override fun onResume() {
        super.onResume()
        if (::profileName.isInitialized) {
            val name = Profiles.active(this)?.name
            profileName.text = name?.let { "Profile " + it } ?: ""
            avatar.text = name?.take(1)?.uppercase() ?: "A"
            refresh.forEach { it() }
        }
        val key = authKey ?: return
        background({ Stremio.addons(key).size }) { result ->
            addonValue?.text = (result.getOrNull()?.let { "$it installed" } ?: "Unavailable") + "  ›"
        }
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT &&
                groupViews.values.any { it.hasFocus() }) {
                if (firstDetail?.requestFocus() == true) return true
            }
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT &&
                currentFocus?.let { isInside(it, detailRows) && it !in choiceRows } == true) {
                if (selectedGroup?.let { groupViews[it]?.requestFocus() } == true) return true
            }
            // The rows end where they end: Down past the last one stays put instead of falling into the group list.
            if (event.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN && detailRows.childCount > 0 &&
                currentFocus === detailRows.getChildAt(detailRows.childCount - 1)) return true
            if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                currentFocus?.let { isInside(it, detailRows) } == true) {
                if (selectedGroup?.let { groupViews[it]?.requestFocus() } == true) return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun addGroup(group: Group) {
        val dot = text("•", SMALL, color = TUNGSTEN).apply { gravity = Gravity.CENTER; visibility = View.INVISIBLE }
        val icon = if (group.icon != 0) ImageView(this).apply {
            setImageResource(group.icon)
            imageTintList = ColorStateList.valueOf(TEXT_3)
        } else text(group.glyph, BODY, color = TEXT_3).apply { gravity = Gravity.CENTER }
        val label = text(group.label, BODY, bold = true, color = TEXT_2)
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(dot, LinearLayout.LayoutParams(dp(10), -1))
            addView(icon, LinearLayout.LayoutParams(dp(14), dp(14)).apply { marginStart = dp(4) })
            addView(label, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(10) })
            setPadding(dp(2), 0, dp(4), 0)
            rowFocus(8, rest = { if (selectedGroup == group) tile(8) else null }) { has ->
                if (has) showGroup(group)
            }
            setOnClickListener { showGroup(group); firstDetail?.requestFocus() }
        }
        groupViews[group] = row
        groupDots[group] = dot
        groupLabels[group] = label
        groups.addView(row, LinearLayout.LayoutParams(-1, dp(34)).apply { bottomMargin = dp(1) })
    }

    private fun showGroup(group: Group) {
        if (selectedGroup == group && detailRows.childCount > 0) return
        selectedGroup = group
        groupViews.forEach { (item, view) ->
            groupDots[item]?.visibility = if (item == group) View.VISIBLE else View.INVISIBLE
            groupLabels[item]?.setTextColor(if (item == group) TEXT else if (item == Group.STORAGE || item == Group.ABOUT) TEXT_3 else TEXT_2)
            if (!view.hasFocus()) view.background = if (item == group) tile(8) else null
        }
        detailTitle.text = group.label
        detailRows.removeAllViews()
        rowsScroll.scrollTo(0, 0)
        choiceRows.clear()
        refresh.clear()
        firstDetail = null
        addonValue = null
        hint.text = when (group) {
            Group.STREAMS -> "Choose how Alder finds and starts a source."
            Group.PICTURE -> "Choose the picture and sound this TV can play."
            Group.SUBTITLES -> "Set how subtitles start and appear."
            Group.ADDONS -> "Manage the add-ons connected to this profile."
            Group.PROFILE -> "Profile choices stay with the person watching."
            Group.LANGUAGE -> "Alder follows the TV's language."
            Group.STORAGE -> "Storage on this TV only."
            Group.ABOUT -> "About Alder."
        }
        when (group) {
            Group.STREAMS -> {
                presetRow()
                streaming.forEach(::choiceRow)
                actionRow("More filters", "", "Release groups, codecs and more are set in AIOStreams.") {
                    choose("More filters", listOf("OK"),
                        "Release groups, codecs, languages and more are set in AIOStreams' settings on your phone or at web.stremio.com. This TV follows its order.") {}
                }
            }
            Group.PICTURE -> pictureAndSound.forEach(::choiceRow)
            Group.SUBTITLES -> subtitles.forEach(::choiceRow)
            Group.ADDONS -> {
                actionRow("My add-ons", "…", "Add by URL, reorder or remove this profile's add-ons.") {
                    if (authKey != null) startActivity(Intent(this, AddonsActivity::class.java))
                }.also { addonValue = it }
                actionRow("Reload add-ons from Stremio", "", "Fetch this profile's add-ons after installing some elsewhere.") {
                    val key = authKey ?: return@actionRow
                    addonValue?.text = "Reloading…"
                    background({ Stremio.addons(key, refresh = true).size }) { result ->
                        addonValue?.text = (result.getOrNull()?.let { "$it installed" } ?: "Reload failed") + "  ›"
                    }
                }
                val key = authKey
                if (key != null) background({ Stremio.addons(key).size }) { result ->
                    addonValue?.text = (result.getOrNull()?.let { "$it installed" } ?: "Unavailable") + "  ›"
                }
            }
            Group.PROFILE -> {
                actionRow("Switch profile", "", "Choose who is watching.") {
                    startActivity(Intent(this, ProfilesActivity::class.java).putExtra("pick", true))
                }
                actionRow("Manage profiles", "", "Rename profiles, change PINs or add another person.") {
                    startActivity(Intent(this, ProfilesActivity::class.java).putExtra("pick", true))
                }
            }
            Group.LANGUAGE -> actionRow("App language", Locale.getDefault().displayLanguage,
                "The app follows this TV's language setting.") {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_APP_LOCALE_SETTINGS)
                    .setData(android.net.Uri.parse("package:$packageName"))) }
                    .recoverCatching { startActivity(Intent(android.provider.Settings.ACTION_LOCALE_SETTINGS)) }
                    .onFailure { choose("Language settings are unavailable on this TV.", listOf("OK")) {} }
            }
            Group.STORAGE -> actionRow("Clear local storage", "", "Removes profiles, settings and saved data from this TV. Stremio accounts stay as they are.") {
                choose("Clear local storage?", listOf("Cancel", "Clear"),
                    "This removes saved profiles, settings and cached data from this TV. It does not cancel your Stremio account.") { choice ->
                    if (choice == 1) (getSystemService(ACTIVITY_SERVICE) as ActivityManager).clearApplicationUserData()
                }
            }
            Group.ABOUT -> actionRow("Alder", "Version " + packageManager.getPackageInfo(packageName, 0).versionName,
                "Alder is a TV interface for your Stremio account.") {
                choose("Alder", listOf("OK"), "A TV interface for your Stremio account.") {}
            }
        }
    }

    private fun settingRow(label: String, value: View, help: String, perProfile: Boolean = false,
                           focus: (Boolean) -> Unit = {}, click: () -> Unit): View {
        val line = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(label, BODY, bold = true), LinearLayout.LayoutParams(-2, -2))
            if (perProfile) addView(text("THIS PROFILE", 8f, color = TUNGSTEN).apply {
                setPadding(dp(5), dp(2), dp(5), dp(2))
                background = glass(6)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            addView(View(this@SettingsActivity), LinearLayout.LayoutParams(0, 1, 1f))
            addView(value, LinearLayout.LayoutParams(-2, -2))
            setPadding(dp(12), 0, dp(12), 0)
        }
        val row = FrameLayout(this).apply {
            addView(line, FrameLayout.LayoutParams(-1, -1))
            addView(View(this@SettingsActivity).apply { setBackgroundColor(GLASS_EDGE) },
                FrameLayout.LayoutParams(-1, dp(1), Gravity.BOTTOM))
            rowFocus(9) { has ->
                if (has) hint.text = help
                elevation = if (has) dp(8).toFloat() else 0f
                if (has && android.os.Build.VERSION.SDK_INT >= 28) outlineSpotShadowColor = TUNGSTEN_GLOW
                focus(has)
            }
            setOnClickListener { click() }
        }
        detailRows.addView(row, LinearLayout.LayoutParams(-1, dp(42)))
        if (firstDetail == null) firstDetail = row
        return row
    }

    private fun actionRow(label: String, value: String, help: String, click: () -> Unit): TextView {
        val display = text(if (value.isEmpty()) "›" else "$value  ›", BODY, color = TEXT_2)
        settingRow(label, display, help, focus = { display.setTextColor(if (it) TUNGSTEN else TEXT_2) }, click = click)
        return display
    }

    private fun key(c: Choice) = if (c.perProfile) Settings.profileKey(this, c.key) else c.key
    private fun stored(c: Choice): Any = when (val default = c.values[0].first) {
        is Boolean -> prefs.getBoolean(key(c), default)
        is String -> prefs.getString(key(c), if (c.key == "audioLang") prefs.getString("audioLang", default) else default).orEmpty()
        else -> prefs.getInt(key(c), default as Int)
    }
    private fun store(c: Choice, value: Any) = prefs.edit().apply {
        when (value) {
            is Boolean -> putBoolean(key(c), value)
            is String -> putString(key(c), value)
            else -> putInt(key(c), value as Int)
        }
    }.apply()
    private fun choice(key: String) = (streaming + pictureAndSound).first { it.key == key }

    private fun choiceRow(c: Choice) {
        fun current() = c.values.indexOfFirst { it.first == stored(c) }.coerceAtLeast(0)
        // A switch only where the choice is On or Off; "Best source / Show sources" is a choice with words.
        val isSwitch = c.values.map { it.second } == listOf("On", "Off")
        val words = text("", BODY, color = TEXT_2)
        val track = FrameLayout(this).apply { background = rounded(GLASS_EDGE, 9) }
        val knob = View(this)
        if (isSwitch) track.addView(knob)
        val value = if (isSwitch) LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(words, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            addView(track, LinearLayout.LayoutParams(dp(32), dp(17)))
        } else words
        var focused = false
        fun draw() {
            val index = current()
            val on = isSwitch && c.values[index].first == true
            words.setTextColor(if (focused) TEXT else TEXT_2)
            words.text = if (isSwitch) c.values[index].second
                else if (focused) "‹  " + c.values[index].second + "  ›" else c.values[index].second
            if (isSwitch) {
                track.background = rounded(if (on) TUNGSTEN else GLASS_EDGE, 9)
                knob.background = rounded(if (on) BASE else TEXT_2, 7)
                knob.layoutParams = FrameLayout.LayoutParams(dp(13), dp(13),
                    Gravity.CENTER_VERTICAL or (if (on) Gravity.END else Gravity.START)).apply {
                    leftMargin = dp(2); rightMargin = dp(2)
                }
            }
        }
        fun shift(by: Int) {
            val next = nextSettingIndex(current(), c.values.size, by)
            store(c, c.values[next].first)
            refresh.forEach { it() }
        }
        val row = settingRow(c.label, value, c.hint, c.perProfile, focus = { has -> focused = has; draw() }) { shift(1) }
        choiceRows += row
        row.setOnKeyListener { _, code, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
                shift(if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                true
            } else false
        }
        refresh += ::draw
        draw()
    }

    private fun presetRow() {
        val value = text("", BODY, color = TEXT_2)
        fun current() = PRESETS.indexOfFirst { (_, rows) -> rows.all { (key, v) -> stored(choice(key)) == v } }
        var focused = false
        fun draw() { value.text = (if (focused) "‹  " else "") + (PRESETS.getOrNull(current())?.first ?: "Custom") + (if (focused) "  ›" else "") }
        fun shift(by: Int) {
            val next = nextSettingIndex(current(), PRESETS.size, by)
            PRESETS[next].second.forEach { (key, v) -> store(choice(key), v) }
            refresh.forEach { it() }
        }
        val row = settingRow("Source choice", value,
            "Best picture: full Blu-ray and Dolby Vision first. Balanced: smaller files. Fast start: web releases. Add-on's order: follow the add-on.",
            focus = { has -> focused = has; draw() }) { shift(1) }
        choiceRows += row
        row.setOnKeyListener { _, code, event ->
            if (event.action == android.view.KeyEvent.ACTION_DOWN &&
                (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT || code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
                shift(if (code == android.view.KeyEvent.KEYCODE_DPAD_LEFT) -1 else 1)
                true
            } else false
        }
        refresh += ::draw
        draw()
    }

    private companion object {
        val PRESETS = listOf(
            "Add-on's order" to mapOf("release" to "", "hdrPref" to "", "maxHeight" to 0, "maxSizeGb" to 0),
            "Best picture" to mapOf("release" to "remux", "hdrPref" to "dv", "maxHeight" to 0, "maxSizeGb" to 0),
            "Balanced" to mapOf("release" to "encode", "hdrPref" to "", "maxHeight" to 0, "maxSizeGb" to 20),
            "Fast start" to mapOf("release" to "web", "hdrPref" to "", "maxHeight" to 1080, "maxSizeGb" to 10),
        )
    }
}
