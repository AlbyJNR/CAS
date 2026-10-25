package dev.alder

import android.content.Intent
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

// A living poster: sharp art with the title low on the left as Home shows it, and an episode guide that rises over the same scene.
class DetailsActivity : Screen() {
    private val type by lazy { intent.getStringExtra("type") ?: "movie" }
    private val metaId by lazy { intent.getStringExtra("id")!! }
    private val name by lazy { intent.getStringExtra("name") ?: "" }
    private val poster by lazy { intent.getStringExtra("poster") }
    private val initialArt by lazy { intent.getStringExtra("background") }

    private lateinit var key: String
    override val backdrop get() = heroArt
    private lateinit var page: LinearLayout
    private val scroll by lazy { ScrollView(this) }
    private lateinit var root: FrameLayout
    private lateinit var hero: FrameLayout
    private lateinit var heroArt: ImageView
    private lateinit var guide: FrameLayout
    private lateinit var guideRows: LinearLayout
    private lateinit var seasonButton: TextView
    private lateinit var previewImage: ImageView
    private lateinit var previewTitle: TextView
    private lateinit var previewAbout: TextView
    private lateinit var previewProgress: FrameLayout
    private lateinit var listButton: TextView
    private lateinit var playButton: TextView
    private lateinit var infoButton: TextView
    private lateinit var infoOverlay: FrameLayout
    private var guideOpen = false
    private var infoOpen = false
    private var selectedSeason = 1
    private var episodeRows = emptyList<View>()
    private var meta: Meta? = null
    private var item: JSONObject? = null // the title's library item: progress and whether it's in My List

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        key = authKey ?: return finish()
        val artWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        heroArt = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        hero = FrameLayout(this).apply { clipChildren = false } // a focused pill grows past the text block's edge
        page = column(Gravity.START, hero).apply { setPadding(0, 0, 0, dp(48)) }
        hero.layoutParams = LinearLayout.LayoutParams(-1, screenHeight)
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(heroArt, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                    intArrayOf(0xE6000808.toInt(), 0x80000808.toInt(), 0x00000000))
            }, FrameLayout.LayoutParams(artWidth * 3 / 5, -1))
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x00000000, 0x22000000, 0x99000000.toInt()))
            }, FrameLayout.LayoutParams(-1, screenHeight * 2 / 3, Gravity.BOTTOM))
            addView(scroll.apply { isVerticalScrollBarEnabled = false; addView(page) }, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(root)

        initialArt?.let { Images.load(it, heroArt, artWidth, urgent = true) { heroArt.alpha = 1f; refrost() } }

        background({
            val m = Stremio.addons(key).firstOrNull { it.serves("meta", type, metaId) }?.meta(type, metaId)
            m to runCatching { Stremio.library(key, listOf(metaId)).firstOrNull() }.getOrNull()
        }) { result ->
            val (m, libraryItem) = result.getOrElse { null to null }
            meta = m
            item = libraryItem
            val art = m?.background ?: initialArt ?: poster
            if (heroArt.tag != art) Images.load(art, heroArt, artWidth, urgent = true) {
                heroArt.animate().alpha(1f).setDuration(250).start()
                refrost()
            }
            showHero(m)
            if (m != null && m.videos.isNotEmpty()) buildGuide(m.videos)
            if (m != null) loadSimilar()
        }
    }

    // ---------- title and watch window ----------

    private fun showHero(m: Meta?) {
        hero.removeAllViews()
        // Home's hierarchy: the name is the one big thing, then one small facts line, the story across half the screen,
        // where you are, and small pills. Low on the left, where Home's title sits above its row.
        val stack = column(Gravity.START, text(m?.name ?: name, DISPLAY, bold = true).onArt().apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        if (m == null) {
            val error = failed("Couldn't load this title's details.") { recreate() }
            stack.addView(error.marginTop(16))
            error.post { error.getChildAt(1).requestFocus() }
        } else {
            val facts = listOfNotNull(
                if (type == "series") "Series" else "Movie",
                m.year,
                m.genres.firstOrNull(),
                m.runtime?.takeIf { type != "series" },
                m.rating?.let { "IMDb $it" },
            ).joinToString(DOT)
            stack.addView(text(facts, SMALL, color = TEXT_HI).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }.marginTop(6))
            m.description?.let { stack.addView(text(it, BODY, color = TEXT_HI).onArt().apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }.marginTop(8)) }
            addWatch(m, stack)
        }
        hero.addView(stack, FrameLayout.LayoutParams(dp(HERO_W), -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(PAD); bottomMargin = dp(96)
        })
        if (type == "series" && m?.videos?.isNotEmpty() == true) {
            hero.addView(text("↓  Episodes", SMALL, color = TEXT_HI).onArt(), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = dp(PAD); bottomMargin = dp(44)
            })
        }
    }

    /** Where you are ("S5 E2 · First Days · 18 min left") with its bar, then Resume, Synopsis and My List. */
    private fun addWatch(m: Meta, stack: LinearLayout) {
        val target = playTarget(m)
        val episode = target?.second
        val resumeAt = item?.let { Library.resumeAt(it, target?.first ?: metaId) } ?: 0L
        val duration = item?.optJSONObject("state")?.optLong("duration") ?: 0L
        val remaining = if (resumeAt > 0 && duration > resumeAt) "${(duration - resumeAt) / 60_000} min left" else null
        val where = listOfNotNull(episode?.let { "S${it.season} E${it.episode}" }, episode?.title?.takeIf { it.isNotBlank() }, remaining).joinToString(DOT)
        if (where.isNotEmpty()) stack.addView(text(where, SMALL, bold = true).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }.marginTop(16))
        if (remaining != null) stack.addView(progressBar(resumeAt.toFloat() / duration), LinearLayout.LayoutParams(dp(240), dp(3)).apply { topMargin = dp(8) })

        playButton = pill(if (resumeAt > 0) "Resume" else "Play", R.drawable.ic_play).apply {
            setOnClickListener { openSources(target?.first ?: metaId, startOver = false) }
            // Held OK: every source, to choose one by hand.
            setOnLongClickListener { openSources(target?.first ?: metaId, startOver = false, pick = true); true }
        }
        val startOver = if (resumeAt > 0) pill("Start over").apply { setOnClickListener { openSources(target?.first ?: metaId, startOver = true) } } else null
        infoButton = pill("Synopsis").apply { setOnClickListener { showInfo() } }
        listButton = pill(if (Library.inList(item)) "✓  My List" else "+  My List").apply { setOnClickListener { toggleList() } }
        stack.addView(LinearLayout(this).apply {
            clipChildren = false // a focused pill grows past the row's edge
            addView(playButton, wrap().apply { marginEnd = dp(12) })
            startOver?.let { addView(it, wrap().apply { marginEnd = dp(12) }) }
            addView(infoButton, wrap().apply { marginEnd = dp(12) })
            addView(listButton)
        }, wrap().apply { topMargin = dp(if (where.isEmpty()) 18 else 16) })
        playButton.post { if (!guideOpen && !infoOpen) playButton.requestFocus() }
    }

    /** What Play opens: the episode Continue Watching points at, or the first episode, or the movie itself. */
    private fun playTarget(m: Meta?): Pair<String, Video?>? {
        val videos = m?.videos.orEmpty()
        if (videos.isEmpty()) return metaId to null
        val current = item?.optJSONObject("state")?.text("video_id")
        val video = videos.firstOrNull { it.id == current } ?: videos.firstOrNull { it.season > 0 } ?: videos.first()
        return video.id to video
    }

    private fun toggleList() {
        val entry = item ?: Library.newItem(metaId, type, meta?.name ?: name, meta?.poster ?: poster)
        Library.setInList(entry, !Library.inList(item))
        item = entry
        listButton.text = if (Library.inList(entry)) "✓  My List" else "+  My List"
        val snapshot = JSONObject(entry.toString())
        background({ Stremio.saveLibrary(key, listOf(snapshot)) }) {}
    }

    // ---------- glass and episode guide ----------

    private fun glassPane() = FrameLayout(this).apply {
        background = glassOver(this, 14)
        clipToOutline = true // the background's rounded corners clip the pane's contents too
    }

    private fun buildGuide(videos: List<Video>) {
        if (::guide.isInitialized) root.removeView(guide)
        guideOpen = false
        val screen = resources.displayMetrics
        val x = dp(28)
        val y = dp(160)
        val width = screen.widthPixels - dp(56)
        val height = screen.heightPixels - y - dp(20)
        val largeText = resources.configuration.fontScale > 1.15f
        guide = FrameLayout(this).apply { visibility = View.GONE }
        guide.addView(View(this).apply { setBackgroundColor(0x33000000) }, FrameLayout.LayoutParams(-1, -1))
        guide.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xC000080A.toInt(), 0x9900080A.toInt(), 0x0000080A))
        }, FrameLayout.LayoutParams(dp(450), dp(130)))
        guide.addView(text(meta?.name ?: name, DISPLAY, bold = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.CENTER_VERTICAL
        }, FrameLayout.LayoutParams(dp(340), dp(60)).apply {
            leftMargin = dp(PAD); topMargin = dp(50)
        })
        val pane = glassPane()
        pane.addView(text("Episodes", BODY, bold = true),
            FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(26); topMargin = dp(if (largeText) 12 else 17) })
        seasonButton = text("", BODY, color = TEXT_HI).apply {
            id = View.generateViewId()
            setPadding(dp(10), dp(3), dp(10), dp(3))
            rowFocus(rest = { if (isFocusable) tile(8) else null })
            setOnClickListener { cycleSeason(1) }
        }
        pane.addView(seasonButton, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(23); topMargin = dp(if (largeText) 52 else 44) })

        guideRows = column(Gravity.START)
        pane.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(guideRows)
        }, FrameLayout.LayoutParams(dp(365), height - dp(if (largeText) 168 else 118)).apply {
            leftMargin = dp(26); topMargin = dp(if (largeText) 96 else 78)
        })

        val previewX = dp(425)
        val previewWidth = width - previewX - dp(28)
        previewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(SURFACE)
            clipToOutline = true
            background = rounded(SURFACE, 8)
        }
        pane.addView(previewImage, FrameLayout.LayoutParams(previewWidth, dp(180)).apply { leftMargin = previewX; topMargin = dp(28) })
        previewTitle = text("", BODY, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        pane.addView(previewTitle, FrameLayout.LayoutParams(previewWidth, -2).apply { leftMargin = previewX; topMargin = dp(220) })
        previewAbout = text("", BODY, color = TEXT_HI).apply {
            maxLines = if (largeText) 2 else 3
            ellipsize = TextUtils.TruncateAt.END
        }
        pane.addView(previewAbout, FrameLayout.LayoutParams(previewWidth, -2).apply {
            leftMargin = previewX; topMargin = dp(if (largeText) 258 else 250)
        })
        previewProgress = FrameLayout(this)
        pane.addView(previewProgress, FrameLayout.LayoutParams(previewWidth - dp(16), dp(4)).apply {
            leftMargin = previewX + dp(8); topMargin = dp(196)
        })
        guide.addView(pane, FrameLayout.LayoutParams(width, height).apply { leftMargin = x; topMargin = y })
        root.addView(guide, FrameLayout.LayoutParams(-1, -1))
        selectedSeason = playTarget(meta)?.second?.season ?: videos.first().season
        fillGuide()
    }

    private fun fillGuide() {
        val videos = meta?.videos.orEmpty()
        val seasonVideos = videos.filter { it.season == selectedSeason }
        val canChangeSeason = videos.map { it.season }.distinct().size > 1
        seasonButton.text = (if (selectedSeason == 0) "Specials" else "Season $selectedSeason") +
            if (canChangeSeason) "  ▾" else ""
        seasonButton.isFocusable = canChangeSeason
        if (!seasonButton.isFocused) seasonButton.background = if (canChangeSeason) tile(8) else null
        guideRows.removeAllViews()
        val watched = Library.watched(item, videos)
        val currentId = playTarget(meta)?.first
        episodeRows = seasonVideos.map { v ->
            val row = FrameLayout(this).apply { tag = v.id; isFocusable = true }
            val number = text("%02d".format(v.episode), BODY, color = TEXT_MID)
            row.addView(number, FrameLayout.LayoutParams(dp(45), -2, Gravity.CENTER_VERTICAL).apply { leftMargin = dp(12) })
            val resumeAt = item?.let { Library.resumeAt(it, v.id) } ?: 0L
            val duration = item?.optJSONObject("state")?.takeIf { it.text("video_id") == v.id }?.optLong("duration") ?: 0L
            val status = when {
                v.id in watched -> "Watched"
                resumeAt > 0 && duration > resumeAt -> "${(duration - resumeAt) / 60_000} min left"
                else -> ""
            }
            val label = text(v.title.ifEmpty { "Episode ${v.episode}" }, BODY).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            row.addView(label, FrameLayout.LayoutParams(dp(if (status.isEmpty()) 290 else 180), -2, Gravity.CENTER_VERTICAL).apply {
                leftMargin = dp(63)
            })
            if (status.isNotEmpty()) row.addView(text(status, SMALL, color = TEXT_MID).apply { gravity = Gravity.END },
                FrameLayout.LayoutParams(dp(105), -2, Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dp(8) })
            row.rowFocus { focused -> if (focused) updatePreview(v) }
            row.setOnClickListener {
                val next = videos.getOrNull(videos.indexOf(v) + 1)?.id
                openSources(v.id, startOver = false, next = next, label = "S${v.season} E${v.episode}")
            }
            // Held OK: every source for this episode, to choose one by hand.
            row.setOnLongClickListener {
                val next = videos.getOrNull(videos.indexOf(v) + 1)?.id
                openSources(v.id, startOver = false, next = next, label = "S${v.season} E${v.episode}", pick = true)
                true
            }
            guideRows.addView(row, LinearLayout.LayoutParams(-1, dp(48)))
            row
        }
        seasonVideos.firstOrNull { it.id == currentId }?.let(::updatePreview)
            ?: seasonVideos.firstOrNull()?.let(::updatePreview)
        if (guideOpen) episodeRows.firstOrNull()?.requestFocus()
    }

    private fun updatePreview(v: Video) {
        Images.load(v.thumbnail, previewImage, dp(800), orElse = meta?.background)
        previewTitle.text = "S${v.season} E${v.episode} · ${v.title.ifBlank { "Episode ${v.episode}" }}"
        previewAbout.text = v.overview ?: "Episode ${v.episode}"
        previewProgress.removeAllViews()
        val state = item?.optJSONObject("state")?.takeIf { it.text("video_id") == v.id }
        val resumeAt = item?.let { Library.resumeAt(it, v.id) } ?: 0L
        val duration = state?.optLong("duration") ?: 0L
        if (resumeAt > 0 && duration > resumeAt) {
            previewProgress.addView(progressBar(resumeAt.toFloat() / duration), FrameLayout.LayoutParams(-1, -1))
        }
    }

    private fun cycleSeason(direction: Int) {
        val seasons = meta?.videos.orEmpty().map { it.season }.distinct()
        if (seasons.size < 2) return
        val at = seasons.indexOf(selectedSeason)
        selectedSeason = seasons[(at + direction + seasons.size) % seasons.size]
        fillGuide()
        seasonButton.requestFocus()
    }

    private fun showGuide() {
        if (!::guide.isInitialized || guideOpen) return
        guideOpen = true
        scroll.scrollTo(0, 0)
        hero.visibility = View.INVISIBLE
        guide.visibility = View.VISIBLE
        guide.alpha = 0f
        guide.translationY = dp(28).toFloat()
        guide.animate().alpha(1f).translationY(0f).setDuration(260).start()
        val currentId = playTarget(meta)?.first
        (episodeRows.firstOrNull { it.tag == currentId } ?: episodeRows.firstOrNull())?.requestFocus()
    }

    private fun hideGuide() {
        guideOpen = false
        guide.animate().alpha(0f).translationY(dp(24).toFloat()).setDuration(220).withEndAction {
            guide.visibility = View.GONE
            hero.visibility = View.VISIBLE
            playButton.requestFocus()
        }.start()
    }

    /**
     * The whole story, in the title page's place and layout: name, facts, then the description, which scrolls with the
     * remote when it's long. Back returns to the title; there's nothing else to press.
     */
    private fun showInfo() {
        val m = meta ?: return
        if (::infoOverlay.isInitialized) root.removeView(infoOverlay)
        infoOpen = true
        scroll.visibility = View.INVISIBLE
        infoOverlay = FrameLayout(this)
        infoOverlay.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xC9000808.toInt(), 0x99000808.toInt(), 0x00000000))
        }, FrameLayout.LayoutParams(resources.displayMetrics.widthPixels * 3 / 5, -1))
        val facts = listOfNotNull(m.year, m.runtime,
            m.genres.take(2).joinToString(" / ").ifEmpty { null }, m.rating?.let { "IMDb $it" }).joinToString(DOT)
        val story = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = true // takes the remote, so Up and Down scroll a long story
            addView(text(m.description ?: "No description available.", BODY, color = TEXT_HI).onArt())
        }
        infoOverlay.addView(column(Gravity.START,
            text(m.name, DISPLAY, bold = true).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
            text(facts, SMALL, color = TEXT_HI).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }.marginTop(6),
            text("The story", SMALL, bold = true, color = TEXT_HI).onArt().marginTop(SECTION_GAP),
        ).apply {
            addView(story, LinearLayout.LayoutParams(-1, dp(180)).apply { topMargin = dp(8) })
        }, FrameLayout.LayoutParams(dp(HERO_W), -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(PAD); bottomMargin = dp(96)
        })
        root.addView(infoOverlay, FrameLayout.LayoutParams(-1, -1))
        story.requestFocus()
    }

    private fun hideInfo() {
        infoOpen = false
        root.removeView(infoOverlay)
        scroll.visibility = View.VISIBLE
        infoButton.requestFocus()
    }

    private fun loadSimilar() {
        background({
            val addons = Stremio.addons(key)
            val similar = addons.firstNotNullOfOrNull { runCatching { it.similar(type, metaId) }.getOrNull()?.takeIf { titles -> titles.isNotEmpty() } }
            if (!similar.isNullOrEmpty()) "More Like This" to similar else {
                val picks = addons.firstOrNull { it.id.contains("watchly") }?.catalogs
                    ?.firstOrNull { it.type == type && it.id == "watchly.rec" }
                "You May Also Like" to (picks?.let { runCatching { it.addon.catalog(it) }.getOrNull() }.orEmpty())
            }
        }) { result ->
            result.getOrNull()?.let { (heading, titles) ->
                titles.asSequence().filter { it.id != metaId && it.type == type }
                    .distinctBy { it.id }.take(12).toList().takeIf { it.isNotEmpty() }
                    ?.let { showSimilar(heading, it) }
            }
        }
    }

    /** Posters like every row on Home: same size, gap, focus ring and growth, with room above and below to grow into. */
    private fun showSimilar(heading: String, titles: List<Meta>) {
        page.addView(text(heading, BODY, bold = true).onArt().apply {
            setPadding(dp(PAD), 0, dp(PAD), 0)
        }.marginTop(SECTION_GAP))
        val cards = LinearLayout(this).apply {
            setPadding(dp(PAD), dp(HEADING_GAP + 9), dp(PAD), dp(14))
            clipToPadding = false // the focused poster grows into the padding
        }
        titles.forEachIndexed { index, title ->
            val card = FrameLayout(this).apply {
                setBackgroundColor(SURFACE)
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
                // The name only when there's no poster to show it.
                val name = text(title.name, SMALL, bold = true).apply {
                    maxLines = 3; ellipsize = TextUtils.TruncateAt.END; visibility = View.INVISIBLE
                }
                addView(name, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(12)) })
                val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(image, FrameLayout.LayoutParams(-1, -1))
                cardFocus { focused -> if (focused) scroll.smoothScrollTo(0, page.height) }
                setOnClickListener {
                    startActivity(Intent(this@DetailsActivity, DetailsActivity::class.java)
                        .putExtra("type", title.type).putExtra("id", title.id).putExtra("name", title.name)
                        .putExtra("poster", title.poster).putExtra("background", title.background))
                }
                Images.load(title.poster ?: title.background, image, dp(POSTER_W), failed = { name.visibility = View.VISIBLE })
            }
            cards.addView(card, LinearLayout.LayoutParams(dp(POSTER_W), dp(POSTER_H)).apply {
                if (index > 0) marginStart = dp(CARD_GAP)
            })
        }
        page.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(cards)
        })
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            if (now != null && isInside(now, hero)) scroll.smoothScrollTo(0, 0)
        }
    }

    // ---------- shared ----------

    private fun openSources(videoId: String, startOver: Boolean, next: String? = null, label: String? = null, pick: Boolean = false) {
        val m = meta
        val nextId = next ?: m?.videos?.let { vs -> vs.getOrNull(vs.indexOfFirst { it.id == videoId } + 1)?.id }
        val video = m?.videos?.firstOrNull { it.id == videoId }
        val short = label ?: video?.let { "S${it.season} E${it.episode}" }
        startActivity(Intent(this, StreamsActivity::class.java)
            .putExtra("type", type).putExtra("id", metaId).putExtra("name", m?.name ?: name).putExtra("poster", m?.poster ?: poster)
            .putExtra("videoId", videoId).putExtra("nextVideoId", nextId).putExtra("startOver", startOver).putExtra("pick", pick)
            .putExtra("background", m?.background)
            .putExtra("label", listOfNotNull(m?.name ?: name, short).joinToString("  ·  "))
            // For the sources page: the title's logo, the episode's still and "S1 E3 · OutKast".
            .putExtra("logo", m?.logo).putExtra("still", video?.thumbnail)
            .putExtra("episode", video?.let { v -> listOfNotNull("S${v.season} E${v.episode}", v.title.takeIf { it.isNotBlank() }).joinToString("  ·  ") }))
    }

    // Back from the player: progress, the resume point and My List may have changed.
    override fun onRestart() {
        super.onRestart()
        background({ runCatching { Stremio.library(key, listOf(metaId)).firstOrNull() }.getOrNull() }) { result ->
            item = result.getOrNull() ?: item
            val wasGuide = guideOpen
            if (infoOpen) { root.removeView(infoOverlay); infoOpen = false; scroll.visibility = View.VISIBLE }
            hero.visibility = View.VISIBLE
            showHero(meta)
            meta?.videos?.takeIf { it.isNotEmpty() }?.let(::buildGuide)
            if (wasGuide) showGuide()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (guideOpen) {
                val focused = currentFocus
                if (focused === seasonButton) when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { cycleSeason(-1); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { cycleSeason(1); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { episodeRows.firstOrNull()?.requestFocus(); return true }
                    KeyEvent.KEYCODE_DPAD_UP -> return true
                }
                val at = episodeRows.indexOf(focused)
                if (at >= 0) when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                    KeyEvent.KEYCODE_DPAD_UP -> if (at == 0) { seasonButton.requestFocus(); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (at == episodeRows.lastIndex) return true
                }
            } else if (!infoOpen && type == "series" && event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN &&
                meta?.videos?.isNotEmpty() == true && currentFocus?.let { isInside(it, hero) } == true) {
                showGuide()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onBackPressed() {
        when {
            infoOpen -> hideInfo()
            guideOpen -> hideGuide()
            else -> super.onBackPressed()
        }
    }
}
