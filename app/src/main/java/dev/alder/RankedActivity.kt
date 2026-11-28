package dev.alder

import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.alder.Screen.Companion.CAPS
import dev.alder.Screen.Companion.GLASS
import dev.alder.Screen.Companion.GLASS_FOCUSED
import dev.alder.Screen.Companion.SMALL
import dev.alder.Screen.Companion.TEXT
import dev.alder.Screen.Companion.TEXT_2
import dev.alder.Screen.Companion.TEXT_3

/**
 * The highest rated films or TV (Alan, 2026-11-28): a numbered list, each line a place, a small poster, the name,
 * the year and IMDb's rating in a coloured badge. One to ten down the left, eleven to twenty down the right, and so
 * on below. The chips across the top change the genre as the remote rests on them. OK on a line opens that title.
 * Opened from Top rated on the TV and Movies pages.
 */
class RankedActivity : Screen() {
    private val tv by lazy { intent.getBooleanExtra("tv", false) }
    private val country by lazy { java.util.Locale.getDefault().country.ifEmpty { "GB" } }
    private val lists = mutableMapOf<String?, List<ImdbPick>>() // this visit's answers, by genre
    private val chips = mutableMapOf<String?, TextView>()
    private var genre: String? = null
    private var request = 0
    private var rows = emptyList<View>()
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private var waiting: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        genre = intent.getStringExtra("genre")?.takeIf { it in RANK_GENRES }
        list = column(Gravity.START).apply { clipChildren = false; clipToPadding = false; setPadding(dp(6), dp(6), dp(6), dp(40)) }
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list) }
        val line = LinearLayout(this).apply { clipChildren = false; setPadding(dp(6), dp(4), dp(PAD), dp(4)) }
        (listOf<String?>(null) + RANK_GENRES).forEach { g ->
            val label = g ?: "All"
            val c = chip(label, g == genre)
            val lit = c.onFocusChangeListener
            c.setOnFocusChangeListener { v, has ->
                lit?.onFocusChange(v, has)
                waiting?.let(v::removeCallbacks)
                // The list follows the remote along the chips, once it rests: no press needed, and no request per chip passed.
                if (has && g != genre) waiting = Runnable { show(g) }.also { v.postDelayed(it, SWITCH_MS) }
            }
            c.setOnClickListener { waiting?.let(c::removeCallbacks); if (g != genre) show(g) else rows.firstOrNull()?.requestFocus() }
            chips[g] = c
            line.addView(c, LinearLayout.LayoutParams(-2, -2).apply { if (line.childCount > 0) marginStart = dp(6) })
        }
        val strip = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(line) }
        val head = LinearLayout(this).apply {
            gravity = Gravity.BOTTOM
            addView(text(if (tv) "Top rated TV" else "Top rated films", SERIF_TITLE, serif = true).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(text("Ratings from IMDb", SMALL, color = TEXT_3), wrap())
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(BASE)
            clipChildren = false
            addView(column(Gravity.START).apply {
                clipChildren = false
                addView(head, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(6); marginEnd = dp(6) })
                addView(strip, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                // A pane of its own round the list, which clips: the lines that have scrolled up stay under the chips.
                addView(FrameLayout(context).apply { addView(scroll, FrameLayout.LayoutParams(-1, -1)) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
            }, FrameLayout.LayoutParams(-1, -1).apply { leftMargin = dp(PAD - 6); rightMargin = dp(PAD - 6); topMargin = dp(TOP) })
        })
        show(genre, first = true)
    }

    /** The list for [g]: this visit's copy at once, else IMDb's, else the last run's when IMDb cannot be reached. */
    private fun show(g: String?, first: Boolean = false) {
        genre = g
        chips.forEach { (value, chip) -> chip.select(value ?: "All", value == g); if (chip.hasFocus()) chip.chipFocus(true) }
        val token = ++request
        lists[g]?.let { return draw(it, first) }
        list.removeAllViews()
        rows = emptyList()
        scroll.scrollTo(0, 0)
        list.addView(ring(), LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(20) })
        background({ genreBest(g, tv, country) }) { r ->
            if (isFinishing || token != request) return@background
            val picks = r.getOrNull()?.ifEmpty { null } ?: savedGenreBest(g, tv, country)
            if (picks == null) {
                list.removeAllViews()
                list.addView(failed("Couldn't reach IMDb. Check the TV's connection.") { show(g) })
                return@background
            }
            lists[g] = picks
            draw(picks, first)
        }
    }

    private fun draw(picks: List<ImdbPick>, first: Boolean) {
        list.removeAllViews()
        scroll.scrollTo(0, 0)
        rows = rankedList(list, picks, (resources.displayMetrics.widthPixels - dp(2 * PAD) - dp(RANK_GAP)) / 2) { open(it) }
        if (first || !chips.values.any(View::hasFocus)) rows.firstOrNull()?.requestFocus()
    }

    private fun open(pk: ImdbPick) = startActivity(Intent(this, DetailsActivity::class.java)
        .putExtra("type", pk.type).putExtra("id", pk.id).putExtra("name", pk.name).putExtra("poster", pk.poster))

    private companion object {
        const val TOP = 40
        const val SWITCH_MS = 300L
    }
}

internal const val RANK_BLOCK = 10 // lines in a column before the count moves to the next column
internal const val RANK_GAP = 20 // dp between the two columns

/**
 * [picks] as a ranked list in [into], each column [columnPx] wide: ten down the left, the next ten down the right,
 * then the next twenty below. Gives back the lines in order, the first to take the remote.
 */
internal fun Screen.rankedList(into: LinearLayout, picks: List<ImdbPick>, columnPx: Int, open: (ImdbPick) -> Unit): List<View> {
    val lines = mutableListOf<View>()
    picks.chunked(RANK_BLOCK * 2).forEach { block ->
        val pair = LinearLayout(this).apply { clipChildren = false }
        block.chunked(RANK_BLOCK).forEachIndexed { i, half ->
            val side = column(Gravity.START)
            half.forEach { pk -> side.addView(rankedLine(pk, open).also { lines += it }, LinearLayout.LayoutParams(columnPx, -2)) }
            pair.addView(side, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(RANK_GAP) })
        }
        into.addView(pair, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(18) })
    }
    return lines
}

/** One place on a ranked list: its number, a small poster, the name over the year, and the rating in its colour. */
internal fun Screen.rankedLine(pk: ImdbPick, open: (ImdbPick) -> Unit): View {
    val poster = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        setBackgroundColor(GLASS)
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(0, 0, view.width, view.height, dp(4).toFloat())
        }
        clipToOutline = true
    }
    val number = text("${pk.rank ?: ""}", 20f, bold = true, color = TEXT_2).apply { gravity = Gravity.END; maxLines = 1 }
    val name = text(pk.name, SMALL, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    val year = text(pk.year ?: "", CAPS, color = TEXT_3).apply { maxLines = 1 }
    val badge = text(pk.rating ?: "", SMALL, bold = true, color = Color.WHITE).apply {
        gravity = Gravity.CENTER
        minWidth = dp(40)
        setPadding(dp(7), dp(2), dp(7), dp(2))
        background = rounded(SCORE_COLOURS[scoreBand(pk.rating)], 6)
        visibility = if (pk.rating == null) View.INVISIBLE else View.VISIBLE
    }
    Images.load(pk.poster, poster, dp(RANK_POSTER_W))
    return LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        defaultFocusHighlightEnabled = false // the lit face is the focus
        contentDescription = "${pk.rank}. ${pk.name}, ${pk.rating ?: ""}"
        minimumHeight = dp(RANK_POSTER_W * 3 / 2 + 12)
        setPadding(dp(4), dp(6), dp(10), dp(6))
        addView(number, LinearLayout.LayoutParams(dp(30), -2))
        addView(poster, LinearLayout.LayoutParams(dp(RANK_POSTER_W), dp(RANK_POSTER_W * 3 / 2)).apply { marginStart = dp(10) })
        addView(column(Gravity.START, name, year), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12); marginEnd = dp(10) })
        addView(badge, LinearLayout.LayoutParams(-2, -2))
        setOnFocusChangeListener { _, has ->
            background = if (has) rounded(GLASS_FOCUSED, 10) else null
            number.setTextColor(if (has) TEXT else TEXT_2)
        }
        setOnClickListener { open(pk) }
    }
}

internal const val RANK_POSTER_W = 32
/** A badge's ground by [scoreBand]: grey, amber, green, blue. White words read on each, in the light mode and the dark. */
internal val SCORE_COLOURS = intArrayOf(0xFF6B7078.toInt(), 0xFFA8620A.toInt(), 0xFF2E7D32.toInt(), 0xFF1976D2.toInt())
