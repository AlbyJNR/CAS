package dev.alder

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Search keeps each add-on's order, moving only exact title matches ahead of partial matches. */
internal fun <T> exactSearchFirst(items: List<T>, query: String, title: (T) -> String): List<T> {
    val exact = items.filter { title(it).trim().equals(query.trim(), ignoreCase = true) }
    return exact + items.filterNot { title(it).trim().equals(query.trim(), ignoreCase = true) }
}

/** House Lights search: remote keyboard on the left, focused title and poster rows on the right. */
class SearchActivity : Screen() {
    private lateinit var art: ImageView
    private lateinit var artBack: ImageView
    private lateinit var input: EditText
    private lateinit var resultCount: TextView
    private lateinit var recentBox: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var resultsScroll: ScrollView
    private lateinit var hero: View
    private lateinit var heroLogo: ImageView
    private lateinit var heroName: TextView
    private lateinit var heroFacts: TextView
    private lateinit var heroAbout: TextView
    private lateinit var heroTag: TextView
    private lateinit var heroScore: TextView
    private lateinit var keyboard: LinearLayout
    private val characterKeys = mutableListOf<TextView>()
    private val rightEdgeKeys = mutableSetOf<View>()
    private val cards = mutableListOf<Pair<View, View>>()
    private var firstKey: View? = null
    private var lastKey: View? = null
    private var lastKeyboardFocus: View? = null
    private var firstResult: View? = null
    private var focused: Meta? = null
    private var numeric = false
    private var nativeKeyboard = false
    private var request = 0
    private val typed = Runnable { search(false) }
    override val backdrop get() = art

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = authKey ?: return finish()
        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        artBack = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val width = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val height = resources.displayMetrics.heightPixels / resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(BASE); clipChildren = false }
        root.addView(artBack, FrameLayout.LayoutParams(-1, -1))
        root.addView(art, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xF20E0C0A.toInt(), 0xD90E0C0A.toInt(), 0x590E0C0A, 0x1A0E0C0A))
        }, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(0xF70E0C0A.toInt(), 0xBF0E0C0A.toInt(), 0x000E0C0A))
        }, FrameLayout.LayoutParams(-1, dp(260), Gravity.BOTTOM))
        root.addView(header(), FrameLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(24) })

        val field = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(10), 0)
        }
        field.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_search)
            imageTintList = ColorStateList.valueOf(TEXT_2)
        }, LinearLayout.LayoutParams(dp(15), dp(15)))
        input = EditText(this).apply {
            hint = "Search"
            setHintTextColor(TEXT_3)
            setTextColor(TEXT)
            textSize = BODY
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            showSoftInputOnFocus = false
            background = null
            setPadding(dp(10), 0, dp(5), 0)
            setSelectAllOnFocus(false)
        }
        // The field's own caret shows only while it has focus, and focus lives on our keys: a tungsten bar after the
        // last letter stands in for it.
        val inputBox = FrameLayout(this)
        inputBox.addView(input, FrameLayout.LayoutParams(-1, -1))
        cursor = View(this).apply { setBackgroundColor(TUNGSTEN) }
        inputBox.addView(cursor, FrameLayout.LayoutParams(dp(1), dp(15), Gravity.CENTER_VERTICAL))
        field.addView(inputBox, LinearLayout.LayoutParams(0, dp(34), 1f))
        resultCount = text("", SMALL, color = TEXT_3)
        field.addView(resultCount)
        val fieldRest = glassOver(field, 17, shade = LEFT_SHADE)
        field.background = fieldRest
        input.setOnFocusChangeListener { _, has ->
            field.background = if (has) glass(17, true) else fieldRest
            placeCursor()
            if (!has) {
                nativeKeyboard = false
                input.showSoftInputOnFocus = false
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
            }
        }
        root.addView(field, FrameLayout.LayoutParams(dp(228), dp(34)).apply { leftMargin = dp(48); topMargin = dp(88) })

        keyboard = column(Gravity.START).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = glassOver(this, 14, shade = LEFT_SHADE)
        }
        buildKeyboard()
        root.addView(keyboard, FrameLayout.LayoutParams(dp(228), dp(220)).apply { leftMargin = dp(48); topMargin = dp(134) })

        recentBox = column(Gravity.START)
        root.addView(recentBox, FrameLayout.LayoutParams(dp(228), -2).apply { leftMargin = dp(48); topMargin = dp(375) })
        showRecent()

        heroLogo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START }
        heroName = text("", 48f, serif = true).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        heroFacts = text("", SMALL, color = TEXT_2).onArt()
        heroTag = imdbTag()
        heroScore = text("", SMALL, color = TEXT_2).onArt()
        heroAbout = text("", BODY, color = TEXT).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        hero = column(Gravity.START).apply {
            addView(heroLogo, LinearLayout.LayoutParams(dp(210), dp(46)))
            addView(heroName, LinearLayout.LayoutParams(-1, -2))
            addView(LinearLayout(this@SearchActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(heroFacts)
                addView(heroTag, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                addView(heroScore, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(heroAbout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            visibility = View.INVISIBLE
        }
        root.addView(hero, FrameLayout.LayoutParams(dp((width - 368).toInt()), dp(122)).apply {
            leftMargin = dp(320); topMargin = dp(88)
        })
        results = column(Gravity.START)
        resultsScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            addView(results)
        }
        root.addView(resultsScroll, FrameLayout.LayoutParams(dp((width - 368).toInt()), dp((height - 220).toInt())).apply {
            leftMargin = dp(320); topMargin = dp(220)
        })
        window.setBackgroundDrawable(null)
        setContentView(root)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                placeCursor()
                ++request
                main.removeCallbacks(typed)
                main.postDelayed(typed, TYPING_PAUSE)
            }
        })
        input.setOnEditorActionListener { _, _, _ ->
            main.removeCallbacks(typed)
            search(true)
            true
        }
        // Our keyboard first: the field never takes focus on opening, so the Android keyboard stays shut until the keyboard key.
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        firstKey?.post { firstKey?.requestFocus() }
        search(false)
    }

    private lateinit var cursor: View

    private fun placeCursor() {
        cursor.visibility = if (input.hasFocus()) View.GONE else View.VISIBLE
        cursor.translationX = input.paddingStart + input.paint.measureText(input.text.toString())
    }

    private fun header(): View {
        val profile = Profiles.active(this)
        val bar = FrameLayout(this).apply { setPadding(dp(48), 0, dp(48), 0) }
        val avatar = text(profile?.name?.take(1)?.uppercase() ?: "A", BODY, bold = true, color = TUNGSTEN).apply {
            gravity = Gravity.CENTER
            background = glass(15)
            rowFocus(15, rest = { glass(15) })
            setOnClickListener {
                choose(profile?.name ?: "Profile", listOf("Switch profile", "Settings")) { picked ->
                    startActivity(Intent(this@SearchActivity,
                        if (picked == 0) ProfilesActivity::class.java else SettingsActivity::class.java)
                        .apply { if (picked == 0) putExtra("pick", true) })
                }
            }
        }
        bar.addView(avatar, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.START or Gravity.CENTER_VERTICAL))
        val nav = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val searchTab = FrameLayout(this).apply {
            isFocusable = true
            contentDescription = "Search"
            addView(ImageView(this@SearchActivity).apply {
                setImageResource(R.drawable.ic_search)
                imageTintList = ColorStateList.valueOf(TEXT)
            }, FrameLayout.LayoutParams(dp(17), dp(17), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(3) })
            addView(View(this@SearchActivity).apply { background = rounded(TUNGSTEN, 3) },
                FrameLayout.LayoutParams(dp(3), dp(3), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(2) })
            setOnFocusChangeListener { _, has -> background = if (has) glass(18, true) else null }
            setOnClickListener { input.requestFocus() }
        }
        nav.addView(searchTab, LinearLayout.LayoutParams(dp(44), dp(30)))
        listOf("Home", "Shows", "Movies", "My List").forEach { page ->
            nav.addView(text(page, BODY, color = TEXT_2).apply {
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                isFocusable = true
                setOnFocusChangeListener { _, has ->
                    background = if (has) glass(18, true) else null
                    setTextColor(if (has) TEXT else TEXT_2)
                }
                setOnClickListener {
                    startActivity(Intent(this@SearchActivity, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("page", page))
                    finish()
                }
            }, LinearLayout.LayoutParams(-2, dp(30)).apply { marginStart = dp(4) })
        }
        nav.background = glassOver(nav, 20, shade = 0.4f)
        bar.addView(nav, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        bar.addView(TextClock(this).apply {
            format12Hour = "h:mm a"
            format24Hour = "HH:mm"
            textSize = SMALL
            setTextColor(TEXT_2)
            typeface = text("", SMALL).typeface
        }, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        return bar
    }

    private fun buildKeyboard() {
        val letters = "abcdefghijklmnopqrstuvwxyz"
        for (rowIndex in 0 until 4) {
            val row = keyRow()
            for (column in 0 until 6) {
                val key = key(letters[rowIndex * 6 + column].toString(), row, 1, column == 5) {
                    append((characterKeys[rowIndex * 6 + column].text).toString())
                }
                characterKeys += key
            }
        }
        val fifth = keyRow()
        for (index in 24..25) {
            val key = key(letters[index].toString(), fifth, 1, false) { append(characterKeys[index].text.toString()) }
            characterKeys += key
        }
        val mode = key("123", fifth, 1, false) {
            numeric = !numeric
            val symbols = "1234567890@#$%&*()!?.,:/+"
            characterKeys.forEachIndexed { index, view ->
                view.text = if (numeric) symbols.getOrNull(index)?.toString() ?: "·" else letters[index].toString()
            }
            modeLabel?.text = if (numeric) "ABC" else "123"
        }
        modeLabel = mode
        key("−", fifth, 1, false) { append("-") }
        key("⌫", fifth, 2, true) { delete() }
        val last = keyRow()
        val speech = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val canSpeak = speech.resolveActivity(packageManager) != null
        key("SPACE", last, if (canSpeak) 2 else 4, false) { append(" ") }
        if (canSpeak) {
            key("", last, 2, false) {
                runCatching {
                    startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    }, SPEECH)
                }.onFailure { choose("Voice search is unavailable on this TV.", listOf("OK")) {} }
            }.apply {
                contentDescription = "Voice search"
                setCompoundDrawablesRelative(null, getDrawable(R.drawable.ic_mic)?.mutate()?.apply {
                    setTint(TUNGSTEN); setBounds(0, 0, dp(13), dp(17))
                }, null, null)
            }
        }
        key("⌨", last, 2, true) {
            nativeKeyboard = true
            input.showSoftInputOnFocus = true
            input.requestFocus()
            input.post { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
        }
    }

    private var modeLabel: TextView? = null

    private fun keyRow() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        keyboard.addView(this, LinearLayout.LayoutParams(-1, dp(30)).apply { if (keyboard.childCount > 0) topMargin = dp(4) })
    }

    private fun key(label: String, row: LinearLayout, span: Int, rightEdge: Boolean, click: () -> Unit): TextView {
        val view = text(label, if (label.length > 1) SMALL else BODY, color = TEXT_2).apply {
            gravity = Gravity.CENTER
            isFocusable = true
        }
        view.rowFocus(7, rest = { if (lastKey === view) tile(7) else null }) { has ->
            if (has) lastKeyboardFocus = view
        }
        view.setOnClickListener {
            val previous = lastKey
            lastKey = view
            if (previous != null && previous !== view && !previous.hasFocus()) previous.background = null
            click()
        }
        row.addView(view, LinearLayout.LayoutParams(0, -1, span.toFloat()).apply {
            if (row.childCount > 0) marginStart = dp(4)
        })
        if (firstKey == null) firstKey = view
        if (rightEdge) rightEdgeKeys += view
        return view
    }

    private fun append(value: String) {
        val at = input.selectionStart.coerceIn(0, input.length())
        input.text.insert(at, value)
        input.setSelection(at + value.length)
    }

    private fun delete() {
        val at = input.selectionStart.coerceIn(0, input.length())
        if (at > 0) {
            input.text.delete(at - 1, at)
            input.setSelection(at - 1)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focus = currentFocus
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN && focus === input) {
                if (firstKey?.requestFocus() == true) return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && focus in rightEdgeKeys) {
                if (firstResult?.requestFocus() == true) return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && cards.any { it.first === focus }) {
                if (firstResult === focus || cards.indexOfFirst { it.first === focus } == 0) {
                    if ((lastKeyboardFocus ?: firstKey)?.requestFocus() == true) return true
                }
            }
            if (!nativeKeyboard) {
                if (event.keyCode == KeyEvent.KEYCODE_DEL) { delete(); return true }
                if (event.keyCode == KeyEvent.KEYCODE_SPACE) { append(" "); return true }
                val char = event.unicodeChar.takeIf { it in 32..126 }?.toChar()
                if (char != null && !event.isCtrlPressed && !event.isAltPressed) {
                    append(char.toString())
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Android result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SPEECH || resultCode != RESULT_OK) return
        val words = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        input.setText(words)
        input.setSelection(input.length())
        input.requestFocus()
        main.removeCallbacks(typed)
        search(false)
    }

    private fun recent() = getSharedPreferences("search", MODE_PRIVATE).getString("recent", "").orEmpty()
        .split("\n").filter { it.isNotBlank() }

    private fun remember(query: String) {
        val values = (listOf(query) + recent().filterNot { it.equals(query, ignoreCase = true) }).take(RECENT)
        getSharedPreferences("search", MODE_PRIVATE).edit().putString("recent", values.joinToString("\n")).apply()
        showRecent()
    }

    private fun showRecent() {
        recentBox.removeAllViews()
        val values = recent()
        if (values.isEmpty()) return
        recentBox.addView(caps("Recent", TEXT_3), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        values.chunked(2).forEach { pair ->
            val line = LinearLayout(this)
            pair.forEach { query ->
                line.addView(chip(query).apply {
                    textSize = SMALL
                    maxWidth = dp(110)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setOnClickListener {
                        input.setText(query)
                        input.setSelection(query.length)
                        main.removeCallbacks(typed)
                        search(true)
                    }
                }, LinearLayout.LayoutParams(-2, dp(24)).apply { if (line.childCount > 0) marginStart = dp(5) })
            }
            recentBox.addView(line, LinearLayout.LayoutParams(-1, dp(27)))
        }
    }

    private fun search(moveFocus: Boolean) {
        val query = input.text.toString().trim()
        val token = ++request
        firstResult = null
        cards.clear()
        focused = null
        hero.visibility = View.INVISIBLE
        results.removeAllViews()
        resultsScroll.scrollTo(0, 0)
        resultCount.text = ""
        results.addView(ring(), LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(12) })
        if (query.isEmpty()) {
            background({
                val addons = Stremio.savedAddons(authKey.orEmpty()).orEmpty().ifEmpty { Stremio.addons(authKey.orEmpty()) }
                val catalogs = addons.flatMap { it.catalogs }
                    .filter { it.type == "movie" || it.type == "series" }
                // Popular or trending first, then any other; one that fails or comes back empty gives way to the next.
                catalogs.sortedByDescending { it.name.contains("popular", true) || it.name.contains("trending", true) }
                    .take(4).asSequence()
                    .map { c -> runCatching { c.addon.savedCatalog(c)?.takeIf { it.isNotEmpty() } ?: c.addon.catalog(c) }.getOrDefault(emptyList())
                        .filterNot { isAddonMessage(it.name) }.take(PER_TYPE) }
                    .firstOrNull { it.isNotEmpty() }.orEmpty()
            }) { answer ->
                if (token != request) return@background
                val titles = answer.getOrNull().orEmpty()
                if (titles.isEmpty()) {
                    results.removeAllViews()
                    results.addView(failed("Popular titles are unavailable.") { search(false) })
                } else render(linkedMapOf("Popular" to titles), "", false)
            }
            return
        }
        background({
            var failed = false
            val deadline = System.currentTimeMillis() + SEARCH_WAIT
            val found = Stremio.addons(authKey.orEmpty())
                .map { addon -> CompletableFuture.supplyAsync({ runCatching { addon.search(query) } }, searchPool) }
                .flatMap { future ->
                    runCatching { future.get((deadline - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS) }
                        .getOrElse { Result.failure(it) }.onFailure { failed = true }.getOrDefault(emptyList())
                }
                .filter { it.type == "movie" || it.type == "series" }
                .filterNot { isAddonMessage(it.name) }
                .distinctBy { it.type to it.id }
            val ranked = exactSearchFirst(found, query) { it.name }
            linkedMapOf("Movies" to ranked.filter { it.type == "movie" }.take(PER_TYPE),
                "Shows" to ranked.filter { it.type == "series" }.take(PER_TYPE)).filterValues { it.isNotEmpty() } to failed
        }) { answer ->
            if (token != request) return@background
            val (sections, failed) = answer.getOrElse { emptyMap<String, List<Meta>>() to true }
            if (sections.isEmpty() && failed) {
                results.removeAllViews()
                results.addView(failed("Search is unavailable right now.") { search(false) })
            } else render(sections, query, moveFocus)
        }
    }

    private fun render(sections: Map<String, List<Meta>>, query: String, moveFocus: Boolean) {
        results.removeAllViews()
        val total = sections.values.sumOf { it.size }
        resultCount.text = if (total == 0 || query.isEmpty()) "" else if (total == 1) "1 title" else "$total titles" // not for Popular
        if (total == 0) {
            hero.visibility = View.INVISIBLE
            results.addView(text("No matches · Try a shorter title", BODY, color = TEXT_2))
            return
        }
        sections.forEach { (label, list) ->
            val group = column(Gravity.START)
            group.addView(LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(caps(label).onArt())
                addView(text(list.size.toString(), SMALL, color = TEXT_3).onArt(),
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(9) })
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            val caption = caption("").apply { visibility = View.INVISIBLE }
            val row = LinearLayout(this).apply {
                clipChildren = false
                clipToPadding = false
            }
            list.forEachIndexed { index, meta ->
                row.addView(card(meta, caption, index == 0), LinearLayout.LayoutParams(dp(88), dp(132)).apply {
                    if (index > 0) marginStart = dp(14)
                })
            }
            group.addView(HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                clipChildren = false
                clipToPadding = false
                addView(row)
            }, LinearLayout.LayoutParams(-1, dp(140)))
            group.addView(caption, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
            results.addView(group, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(28) })
        }
        sections.values.firstOrNull()?.firstOrNull()?.let(::show)
        if (moveFocus) firstResult?.requestFocus()
    }

    private fun card(meta: Meta, caption: TextView, firstInRow: Boolean): View {
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(BASE) }
        val shade = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0.5f }
        val card = FrameLayout(this).apply {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(6).toFloat())
            }
            clipToOutline = true
            addView(image, FrameLayout.LayoutParams(-1, -1))
            addView(shade, FrameLayout.LayoutParams(-1, -1))
            cardFocus(6) { has ->
                shade.animate().alpha(if (has) 0f else 0.5f).setDuration(150).start()
                if (has) {
                    caption.text = meta.name + (meta.year?.let { "  ·  " + it.take(4) } ?: "")
                    caption.visibility = View.VISIBLE
                    show(meta)
                }
            }
            setOnClickListener {
                val query = input.text.toString().trim()
                if (query.isNotEmpty()) remember(query)
                startActivity(Intent(this@SearchActivity, DetailsActivity::class.java)
                    .putExtra("type", meta.type).putExtra("id", meta.id).putExtra("name", meta.name)
                    .putExtra("poster", meta.poster).putExtra("background", meta.background))
            }
            if (firstInRow) setOnKeyListener { _, code, event ->
                if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_LEFT)
                    (lastKeyboardFocus ?: firstKey)?.requestFocus() == true
                else false
            }
        }
        cards += card to shade
        if (firstResult == null) firstResult = card
        Images.load(meta.poster ?: meta.background, image, dp(96))
        return card
    }

    private fun show(meta: Meta) {
        if (focused?.type == meta.type && focused?.id == meta.id) return
        focused = meta
        hero.visibility = View.VISIBLE
        val url = meta.background ?: meta.poster
        if (url != null && url != art.tag) {
            art.animate().cancel()
            if (art.drawable != null) artBack.setImageDrawable(art.drawable)
            art.alpha = 0f
            Images.load(url, art, resources.displayMetrics.widthPixels, urgent = true, failed = {
                if (focused === meta) {
                    art.setImageDrawable(artBack.drawable)
                    art.alpha = 1f
                    refrost()
                }
            }) {
                if (focused === meta) { art.animate().alpha(1f).setDuration(300).start(); refrost() }
            }
        } else if (url == null) {
            art.tag = null
            art.setImageDrawable(null)
            artBack.setImageDrawable(null)
            refrost()
        }
        heroName.text = meta.name
        heroName.visibility = View.GONE
        heroLogo.visibility = View.VISIBLE
        Images.load(meta.logo ?: Images.logoFor(meta.id), heroLogo, dp(210), urgent = true, failed = {
            if (focused === meta) { heroLogo.visibility = View.GONE; heroName.visibility = View.VISIBLE }
        })
        heroFacts.text = listOfNotNull(meta.year, meta.genres.firstOrNull(), meta.runtime?.let(::runtime)).joinToString("  ·  ") +
            if (meta.rating != null) "  ·" else ""
        heroTag.visibility = if (meta.rating == null) View.GONE else View.VISIBLE
        heroScore.visibility = heroTag.visibility
        heroScore.text = meta.rating.orEmpty()
        heroAbout.text = meta.description.orEmpty()
        heroAbout.visibility = if (meta.description.isNullOrBlank()) View.GONE else View.VISIBLE
    }

    private companion object {
        const val RECENT = 6
        const val LEFT_SHADE = 0.85f // the left shade's strength behind the field and keyboard
        const val PER_TYPE = 12
        const val TYPING_PAUSE = 500L
        const val SEARCH_WAIT = 6000L
        const val SPEECH = 31
        val searchPool = java.util.concurrent.Executors.newFixedThreadPool(8)
    }
}
