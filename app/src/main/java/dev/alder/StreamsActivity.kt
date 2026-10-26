package dev.alder

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray

// Finds the sources for one video. Normally it picks one and hands the player a short list to fall back on, so Play
// is one click; with "pick" (held OK on Play, or after three sources failed) it lists them all.
// The page (design/stream-selection-40-percent-frost.png): the episode's still sharp on the right, darkened towards the
// left where the title and a numbered list of sources sit straight on it; the focused source's name gets a frosted pill.
class StreamsActivity : Screen() {
    private val pick by lazy { intent.getBooleanExtra("pick", false) || Settings.alwaysList(this) }

    private lateinit var art: ImageView
    override val backdrop get() = art

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = authKey ?: return finish()
        val type = intent.getStringExtra("type") ?: "movie"
        val videoId = intent.getStringExtra("videoId")!!
        val list = column(Gravity.START).apply { setPadding(dp(PAD - GLASS_INSET), 0, dp(24), dp(72)) }
        val header = column(Gravity.START).apply { setPadding(dp(PAD - GLASS_INSET), dp(24), dp(24), dp(8)) }
        val name = intent.getStringExtra("name") ?: ""
        val logo = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; maxHeight = dp(40); maxWidth = dp(300) }
        val title = text(name, BODY, bold = true).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        header.addView(FrameLayout(this).apply { addView(title); addView(logo) })
        Images.load(intent.getStringExtra("logo"), logo, dp(300), failed = { title.visibility = View.VISIBLE }) { title.visibility = View.INVISIBLE }
        // "S1 E3 · OUTKAST" for an episode; the label minus the title's name otherwise.
        val episode = intent.getStringExtra("episode") ?: intent.getStringExtra("label")?.removePrefix(name)?.trim(' ', '·')
        if (!episode.isNullOrBlank()) header.addView(text(episode, SMALL, color = SUBTLE).onArt().marginTop(8))
        header.addView(text("Choose a stream", SERIF_TITLE, bold = true, serif = true).onArt().marginTop(4))
        val status = text(intent.getStringExtra("message") ?: "Finding sources…", BODY, color = TEXT_2).onArt().marginTop(10)
        header.addView(status)

        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        val still = intent.getStringExtra("still")
        val backArt = intent.getStringExtra("background") ?: intent.getStringExtra("poster")
        Images.load(still ?: backArt, art, resources.displayMetrics.widthPixels, orElse = backArt.takeIf { still != null }) {
            art.animate().alpha(1f).setDuration(250).start()
            refrost()
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(art, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF2000000.toInt(), 0xCC000000.toInt(), 0x59000000, 0))
            }, FrameLayout.LayoutParams(-1, -1))
            // The list on a pane of glass inset from the edges, like the episode list.
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = glassOver(this, 14)
                addView(header, LinearLayout.LayoutParams(-1, -2))
                addView(ScrollView(context).apply {
                    isVerticalScrollBarEnabled = false
                    // Keeps the focused source clear of the hint band at the bottom: scrolling stops short of a fading edge.
                    isVerticalFadingEdgeEnabled = true
                    setFadingEdgeLength(dp(96))
                    addView(list)
                }, LinearLayout.LayoutParams(-1, 0, 1f))
            }, FrameLayout.LayoutParams(resources.displayMetrics.widthPixels * 52 / 100, -1).apply { setMargins(dp(GLASS_INSET), dp(GLASS_INSET), 0, dp(GLASS_INSET)) })
        })
        val tv = Device.of(this)

        background({ Stremio.addons(key).filter { it.serves("stream", type, videoId) } }) { result ->
            val addons = result.getOrElse { status.text = "Couldn't load your addons: ${it.message}"; return@background }
            if (addons.isEmpty()) { status.text = "None of your addons offer sources for this. Add a stream addon in Stremio on your phone or at web.stremio.com, and it shows up here."; return@background }
            val answers = arrayOfNulls<List<Stream>>(addons.size)
            var started = false
            var listed = false
            var found = 0
            var hidden = 0
            var focusedVideo = false
            val shown = BooleanArray(addons.size)

            // The list: one section per addon, created up front so the account's addon order holds whichever answers first.
            val sections = addons.map { column(Gravity.START).also { s -> list.addView(s, LinearLayout.LayoutParams(-1, -2)) } }
            fun section(i: Int) {
                val all = answers[i].orEmpty()
                val items = all.filter { it.playableOn(tv) }
                hidden += all.size - items.size
                if (items.isEmpty()) return
                sections[i].addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(text(addons[i].name, BODY, bold = true).onArt())
                    addView(text("  ·  ${items.size} " + if (items.size == 1) "source" else "sources", SMALL, color = SUBTLE).onArt())
                }.marginTop(28))
                val rows = items.mapIndexed { n, it -> streamRow(it, n + 1).also(sections[i]::addView) }
                // Focus lands on the first real video, even when an action entry (MyTrakt) answers first.
                val firstVideo = items.indexOfFirst { it.isVideo }
                if (!focusedVideo && firstVideo >= 0) {
                    focusedVideo = true
                    rows[firstVideo].requestFocus()
                } else if (found == 0) {
                    rows[0].requestFocus()
                }
                found += items.size
            }
            fun showList() {
                listed = true
                answers.forEachIndexed { i, streams -> if (streams != null && !shown[i]) { shown[i] = true; section(i) } }
                status.text = when {
                    answers.any { it == null } -> "Finding sources…"
                    found == 0 && hidden > 0 -> "This TV can't play any of the $hidden sources found."
                    found == 0 -> "No playable sources found."
                    else -> listOfNotNull(intent.getStringExtra("message"), "$found sources",
                        "$hidden this TV can't play".takeIf { hidden > 0 }).joinToString("  ·  ")
                }
            }

            // One click: as soon as every addon above the first one with something playable has answered, the best of
            // what's in goes to the player, so a slow addon further down the list doesn't hold Play up.
            fun tryStart() {
                if (started || pick || listed) return
                val ready = answers.takeWhile { it != null }.flatMap { it!! }
                val best = rank(ready.filter { it.isVideo && it.playableOn(tv) && wanted(it) }, videoId)
                if (best.isEmpty() && answers.any { it == null }) return
                if (best.isEmpty()) return showList()
                started = true
                startActivity(Intent(this, PlayerActivity::class.java).putExtras(intent)
                    .putExtra("sources", JSONArray(best.take(TRY).map { it.toJson() }).toString()))
                finish()
            }

            addons.forEachIndexed { i, addon ->
                background({ addon.streams(type, videoId) }) { streams ->
                    answers[i] = streams.getOrDefault(emptyList())
                    if (pick || listed) showList() else tryStart()
                }
            }
        }
    }

    /**
     * The order to try sources in: the file played last time for this video (debrid links expire, so the link is fresh
     * but the release is the same), then the release the previous episode used (AIOStreams' bingeGroup), then the
     * addon's own order, with releases above the screen's resolution or HDR on a screen without it moved down, 3D and
     * hard-coded subtitles last, and then Settings' stream preferences. The sort is stable, so ties keep the addon's order.
     */
    private fun rank(streams: List<Stream>, videoId: String): List<Stream> {
        val prefs = getSharedPreferences("sources", MODE_PRIVATE)
        val file = prefs.getString("file:$videoId", null)
        val binge = prefs.getString("binge:${intent.getStringExtra("id")}", null)
        val tv = Device.of(this)
        val prefer = preference()
        return streams.sortedWith(compareByDescending<Stream> { file != null && it.filename == file }
            .thenByDescending { binge != null && it.bingeGroup == binge }
            .thenBy { it.awkward }
            .thenByDescending { it.fit(tv) }
            .thenByDescending(prefer))
    }

    /** Settings' stream preferences as a score, higher first. At "No preference" with TV speakers, every source scores 0. */
    private fun preference(): (Stream) -> Int {
        val release = Settings.release(this)
        val hdr = Settings.hdrPref(this)
        val dub = Settings.dub(this)
        val language = Settings.audioLanguage(this)
        val receiver = Settings.passthrough(this)
        return { s ->
            when (release) {
                "remux" -> if (s.remux) 3 else if (s.bluray) 1 else 0
                "encode" -> if (s.remux) -2 else if (s.bluray || s.web) 2 else 0
                "web" -> if (s.web && !s.remux) 2 else if (s.remux) -2 else 0
                else -> 0
            } + when (hdr) {
                "dv" -> if (s.dolbyVision) 2 else if (s.hdr) 1 else 0
                "hdr10" -> if (s.hdr) 2 else 0
                else -> 0
            } + when (dub) {
                "original" -> if (s.dubbed) -2 else 0
                "mine" -> if (s.mentions(language)) 2 else if (s.multiAudio) 1 else 0
                else -> 0
            } + if (receiver && s.bigAudio) 1 else 0 // a receiver gets Atmos and lossless tracks; TV speakers don't
        }
    }

    /** Within Settings' limits (quality, file size, HDR), and never a cam or screener. Unknown sizes and resolutions pass. */
    private fun wanted(s: Stream): Boolean {
        val maxHeight = intent.getIntExtra("maxHeight", 0).takeIf { it > 0 } ?: Settings.maxHeight(this) // the player asks for less
        val maxBytes = Settings.maxSizeGb(this) * 1_073_741_824L
        return (maxHeight == 0 || s.height == 0 || s.height <= maxHeight) && (maxBytes == 0L || s.size == 0L || s.size <= maxBytes) &&
            (Settings.hdr(this) || !s.isHdr) && !s.cam
    }

    /**
     * One source: its number, the addon's name for it ("Torrentio · 2160p"), the release tags Settings' preferences use
     * ("REMUX · DV · Atmos") and the addon's own lines (release, audio, size, languages). Focus is the shared white row.
     */
    private fun streamRow(s: Stream, number: Int): View {
        val name = s.label
        val num = text("%02d".format(number), BODY, color = TEXT_2).onArt().apply { setPadding(dp(8), 0, 0, 0) }
        val head = text(name, BODY, bold = true).onArt().apply { setPadding(dp(12), dp(4), dp(14), dp(5)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val lines = text(s.description.replace(Regex(",(?=\\S)"), "  ·  "), SMALL, color = TEXT).onArt().apply { maxLines = 5; setPadding(dp(12), 0, 0, 0) }
        val info = column(Gravity.START, head)
        s.tags.takeIf { it.isNotEmpty() }?.let { info.addView(text(it.joinToString("  ·  "), SMALL, color = SUBTLE).onArt().apply { setPadding(dp(12), 0, 0, 0) }.marginTop(2)) }
        info.addView(lines.marginTop(4))
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                addView(num, LinearLayout.LayoutParams(dp(56), -2).apply { topMargin = dp(2) })
                addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(12), 0, dp(12)) })
            rowFocus(12)
            setOnClickListener {
            startActivity(Intent(this@StreamsActivity, PlayerActivity::class.java).putExtras(intent)
                .putExtra("sources", JSONArray(listOf(s.toJson())).toString()).putExtra("picked", true))
            }
        }
    }

    private companion object {
        const val TRY = 3 // sources the player tries before showing the list
        const val SUBTLE = 0xFFA9BDD6.toInt() // the episode line and source counts: a cool grey-blue, as in the mockup
    }
}
