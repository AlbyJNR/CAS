package dev.alder

import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView

/**
 * The Live page: a row each for kids, news and sport, every channel a wide card with its name. OK plays the channel
 * at once. The remote opens on the channel played last. The channels come from CAS's own list ([Live]).
 */
class LiveActivity : Screen() {
    private lateinit var rowsBox: LinearLayout
    private var shown: Live.Listing? = null
    private var asked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rowsBox = column(Gravity.START).apply { clipChildren = false; clipToPadding = false; setPadding(0, dp(6), 0, dp(40)) }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(rowsBox) }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(BASE)
            clipChildren = false
            addView(column(Gravity.START).apply {
                clipChildren = false
                addView(text("Live", SERIF_TITLE, serif = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(PAD) })
                addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
            }, FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(TOP) })
        })
        draw(Live.current(this))
    }

    override fun onResume() {
        super.onResume()
        // The list file, read again a few times a day: a channel that moved is put right without a new version.
        if (asked || !Live.wantsFetch(this)) return
        asked = true
        background({ Live.fetch(this) ?: error("no list") }) { r ->
            val fresh = r.getOrNull() ?: return@background
            val now = newer(shown, fresh) ?: return@background
            if (!isFinishing && shown?.let { sameLive(it, now) } != true) draw(now)
        }
    }

    private fun draw(list: Live.Listing) {
        shown = list
        rowsBox.removeAllViews()
        // An "app" card is for a channel that plays in an app the TV has; with no such app here, it is left out.
        val rows = list.rows.map { r -> Live.Row(r.name, r.channels.filter { it.kind != "app" || launcher(it) != null }) }.filter { it.channels.isNotEmpty() }
        if (rows.isEmpty()) return rowsBox.addView(text("No channels to show right now.", BODY, color = TEXT_2), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(PAD); topMargin = dp(12) })
        val streams = rows.flatMap { it.channels }.filter { it.kind == "stream" }
        val last = Live.last(this)
        var first: View? = null
        var back: View? = null
        rows.forEach { r ->
            rowsBox.addView(caps(r.name), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(PAD); topMargin = dp(if (rowsBox.childCount > 0) 14 else 4) })
            val line = LinearLayout(this).apply { clipChildren = false; clipToPadding = false; setPadding(dp(PAD), dp(10), dp(PAD), dp(6)) }
            r.channels.forEach { ch ->
                val card = card(ch) { open(ch, streams) }
                if (first == null) first = card
                if (ch.id == last) back = card
                line.addView(card, LinearLayout.LayoutParams(-2, -2).apply { if (line.childCount > 0) marginStart = dp(CARD_GAP) })
            }
            rowsBox.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; addView(line) })
        }
        (back ?: first)?.let { it.post { it.requestFocus() } }
    }

    /** A channel's card: its name on a wide tile, white under the remote; a note under it ("UK only", "Sound only"). */
    private fun card(ch: Live.Channel, open: () -> Unit): View {
        val name = text(ch.name, BODY, bold = true).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(10), 0, dp(10), 0) }
        val tile = FrameLayout(this).apply { background = glass(TILE_R); addView(name, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER)) }
        val note = text(ch.note ?: "", CAPS, color = TEXT_3).apply { maxLines = 1 }
        return column(Gravity.CENTER_HORIZONTAL).apply {
            isFocusable = true
            defaultFocusHighlightEnabled = false
            clipChildren = false
            contentDescription = listOfNotNull(ch.name, ch.note).joinToString(", ")
            addView(tile, LinearLayout.LayoutParams(dp(CARD_W), dp(CARD_H)))
            addView(note, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
            setOnFocusChangeListener { v, has ->
                tile.background = if (has) rounded(TEXT, TILE_R) else glass(TILE_R)
                name.setTextColor(if (has) BASE else TEXT)
                v.grow(has, 1.06f).start()
            }
            setOnClickListener { open() }
        }
    }

    private fun open(ch: Live.Channel, streams: List<Live.Channel>) {
        Live.setLast(this, ch.id)
        when (ch.kind) {
            "stream" -> startActivity(Intent(this, LivePlayerActivity::class.java)
                .putExtra("names", streams.map { it.name }.toTypedArray()).putExtra("urls", streams.map { it.url.orEmpty() }.toTypedArray())
                .putExtra("ids", streams.map { it.id }.toTypedArray()).putExtra("index", streams.indexOf(ch).coerceAtLeast(0)))
            "twitch" -> startActivity(Intent(this, LiveWebActivity::class.java).putExtra("channel", ch.channel).putExtra("name", ch.name))
            "app" -> launcher(ch)?.let { runCatching { startActivity(it) } }
        }
    }

    /** The way into the first of the channel's apps that this TV has, or null. */
    private fun launcher(ch: Live.Channel): Intent? = ch.packages.firstNotNullOfOrNull {
        packageManager.getLeanbackLaunchIntentForPackage(it) ?: packageManager.getLaunchIntentForPackage(it)
    }

    private companion object {
        const val TOP = 40
        const val CARD_W = 150
        const val CARD_H = 76
        const val CARD_GAP = 12
        const val TILE_R = 12
    }
}
