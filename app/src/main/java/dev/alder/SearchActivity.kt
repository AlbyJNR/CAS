package dev.alder

import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * Search the installed addons' movie and series catalogs, after design/search-cinematic-minimal-glass.png: the
 * focused result's art fills the screen, darkened on the left under a search pill and a grid of wide cards (three
 * across), with its logo, facts and description bottom right. Results come as you type (after a pause); OK on the
 * keyboard's search key moves to the first result.
 */
class SearchActivity : Screen() {
    private lateinit var art: ImageView
    private lateinit var artBack: ImageView
    private lateinit var logo: ImageView
    private lateinit var name: android.widget.TextView
    private lateinit var facts: android.widget.TextView
    private lateinit var about: android.widget.TextView
    private lateinit var info: View
    private var focused: Meta? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = authKey ?: return finish()
        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        artBack = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

        val input = EditText(this).apply {
            hint = "Movie or show title"
            setHintTextColor(MUTED)
            setTextColor(Color.WHITE)
            textSize = BODY
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = null
            setPadding(dp(14), dp(12), dp(8), dp(12))
        }
        val clear = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            isFocusable = true
            visibility = View.INVISIBLE
            setOnFocusChangeListener { v, has -> v.background = if (has) glass(20, focused = true) else null }
            setOnClickListener { input.setText(""); input.requestFocus() }
        }
        val field = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), 0, dp(10), 0)
            background = glass(14)
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_search) }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(input, LinearLayout.LayoutParams(0, -2, 1f))
            addView(clear, LinearLayout.LayoutParams(dp(40), dp(40)))
        }
        input.setOnFocusChangeListener { _, has ->
            field.background = glass(14, has)
        }
        val heading = text("", BODY, bold = true).onArt()
        val count = text("", SMALL, color = TEXT_MID).onArt()
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(PAD), dp(28), 0, dp(28))
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_back) }, LinearLayout.LayoutParams(dp(28), dp(28)))
                addView(text("Search", DISPLAY, bold = true).onArt(), wrap().apply { marginStart = dp(22) })
            })
            addView(field, LinearLayout.LayoutParams(dp(GRID_W), -2).apply { topMargin = dp(18) })
            addView(LinearLayout(context).apply {
                gravity = Gravity.BOTTOM
                addView(heading)
                addView(count, wrap().apply { marginStart = dp(18); bottomMargin = dp(2) })
            }, wrap().apply { topMargin = dp(24); bottomMargin = dp(12) })
            addView(grid)
        }

        logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = false }
        name = text("", DISPLAY, bold = true).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        facts = text("", SMALL, color = TEXT_HI).onArt()
        about = text("", BODY, color = TEXT_HI).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        info = column(Gravity.START, logo.apply { layoutParams = LinearLayout.LayoutParams(dp(260), dp(70)) }, name, facts.marginTop(6), about.marginTop(8))
            .apply { visibility = View.INVISIBLE }

        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF0B0C0E.toInt())
            addView(artBack, FrameLayout.LayoutParams(-1, -1))
            addView(art, FrameLayout.LayoutParams(-1, -1))
            // Darkened only where words sit: under the grid on the left, and up from the bottom under the title's details.
            addView(View(context).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xE6000000.toInt(), 0xB3000000.toInt(), 0x33000000, 0)) }, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x40000000, 0)) }, FrameLayout.LayoutParams(-1, dp(300), Gravity.BOTTOM))
            addView(ScrollView(context).apply { isVerticalScrollBarEnabled = false; isFillViewport = true; addView(left) }, FrameLayout.LayoutParams(dp(GRID_W + PAD + 56), -1))
            addView(info, FrameLayout.LayoutParams(dp(300), -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(PAD), dp(44)) })
        })

        var request = 0
        fun search(moveFocus: Boolean) {
            val query = input.text.toString().trim()
            val current = ++request
            if (query.isEmpty()) {
                grid.removeAllViews(); heading.text = ""; count.text = ""; info.visibility = View.INVISIBLE
                return
            }
            if (moveFocus) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
            heading.text = "Searching…"
            background({
                var failed = false
                // Every addon at once: one after another, a slow one held the whole page up for seconds.
                val titles = Stremio.addons(key)
                    .map { addon -> java.util.concurrent.CompletableFuture.supplyAsync({ runCatching { addon.search(query) } }, searchPool) }
                    .flatMap { it.join().onFailure { failed = true }.getOrDefault(emptyList()) }
                    .distinctBy { it.id }.take(24).toList().sortedWith(compareBy<Meta> {
                    when {
                        it.name.equals(query, ignoreCase = true) || it.name.equals("The $query", ignoreCase = true) -> 0
                        it.name.startsWith(query, ignoreCase = true) || it.name.startsWith("The $query", ignoreCase = true) -> 1
                        else -> 2
                    }
                }.thenByDescending { it.rating?.toDoubleOrNull() ?: 0.0 })
                titles to failed
            }) { answer ->
                if (current != request) return@background
                val (titles, failed) = answer.getOrElse { heading.text = "Search failed. Try again."; return@background }
                heading.text = when {
                    titles.isNotEmpty() -> "Results for “$query”"
                    failed -> "Search is unavailable. Try again."
                    else -> "No matches for “$query”"
                }
                count.text = if (titles.isEmpty()) "" else if (titles.size == 1) "1 title" else "${titles.size} titles"
                grid.removeAllViews()
                titles.chunked(COLUMNS).forEach { row ->
                    grid.addView(LinearLayout(this).apply { row.forEach { addView(card(it), LinearLayout.LayoutParams(dp(CARD_W), -2).apply { marginEnd = dp(GAP) }) } },
                        wrap().apply { bottomMargin = dp(16) })
                }
                titles.firstOrNull()?.let(::show)
                if (moveFocus) (grid.getChildAt(0) as? LinearLayout)?.getChildAt(0)?.requestFocus()
            }
        }
        val typed = Runnable { search(moveFocus = false) }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clear.visibility = if (s.isNullOrEmpty()) View.INVISIBLE else View.VISIBLE
                main.removeCallbacks(typed)
                main.postDelayed(typed, TYPING_PAUSE)
            }
        })
        input.setOnEditorActionListener { _, _, _ -> main.removeCallbacks(typed); search(moveFocus = true); true }
        input.requestFocus()
    }

    /** A wide card: the art with rounded corners, the title (a ▶ in front while focused), then year and type. */
    private fun card(m: Meta): View {
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(SURFACE)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat())
            }
            clipToOutline = true
        }
        val title = text(m.name, BODY, bold = true).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; compoundDrawablePadding = dp(8) }
        val kind = when (m.type) { "movie" -> "Movie"; "series" -> "Series"; else -> m.type.replaceFirstChar(Char::uppercaseChar) }
        val line = text(listOfNotNull(m.year?.take(4), kind).joinToString("  ·  "), SMALL, color = TEXT_MID).onArt()
        Images.load(m.background ?: m.poster, image, dp(CARD_W))
        val play = getDrawable(R.drawable.ic_play)?.mutate()?.apply { setBounds(0, 0, dp(18), dp(18)) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(image, LinearLayout.LayoutParams(-1, dp(CARD_W * 9 / 16)))
            addView(title, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            addView(line, wrap().apply { topMargin = dp(2) })
            isFocusable = true
            setOnFocusChangeListener { _, has ->
                image.animate().scaleX(if (has) 1.06f else 1f).scaleY(if (has) 1.06f else 1f).setDuration(150).start()
                image.elevation = if (has) dp(12).toFloat() else 0f
                title.setCompoundDrawablesRelative(if (has) play else null, null, null, null)
                if (has) show(m)
            }
            setOnClickListener {
                startActivity(Intent(this@SearchActivity, DetailsActivity::class.java)
                    .putExtra("type", m.type).putExtra("id", m.id).putExtra("name", m.name)
                    .putExtra("poster", m.poster).putExtra("background", m.background))
            }
        }
    }

    /** [m] behind everything: its art crossfades in over the last one, its logo (or name), facts and description. */
    private fun show(m: Meta) {
        if (focused?.id == m.id) return
        focused = m
        info.visibility = View.VISIBLE
        val url = m.background ?: m.poster
        if (url != null && url != art.tag) {
            art.animate().cancel()
            artBack.setImageDrawable(art.drawable)
            art.alpha = 0f
            Images.load(url, art, resources.displayMetrics.widthPixels, urgent = true) { art.animate().alpha(1f).setDuration(250).start() }
        }
        name.text = m.name
        name.visibility = View.GONE
        logo.visibility = View.VISIBLE
        Images.load(m.logo ?: Images.logoFor(m.id), logo, dp(260), urgent = true, failed = {
            if (focused === m) { logo.visibility = View.GONE; name.visibility = View.VISIBLE }
        })
        facts.text = listOfNotNull(m.year?.take(4), m.genres.firstOrNull(), m.runtime).joinToString("  ·  ")
        about.text = m.description ?: ""
    }

    private companion object {
        const val COLUMNS = 3
        const val CARD_W = 168 // dp: three across in GRID_W
        const val GAP = 12
        const val GRID_W = COLUMNS * CARD_W + (COLUMNS - 1) * GAP
        const val TYPING_PAUSE = 500L // ms after the last letter before searching
        val searchPool = java.util.concurrent.Executors.newFixedThreadPool(8)
    }
}
