package dev.streamtv

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

// A living poster: sharp art, one glass watch window, and an episode guide that rises over the same scene.
class DetailsActivity : Screen() {
    private val type by lazy { intent.getStringExtra("type") ?: "movie" }
    private val metaId by lazy { intent.getStringExtra("id")!! }
    private val name by lazy { intent.getStringExtra("name") ?: "" }
    private val poster by lazy { intent.getStringExtra("poster") }
    private val initialArt by lazy { intent.getStringExtra("background") }

    private lateinit var key: String
    private lateinit var page: LinearLayout
    private val scroll by lazy { ScrollView(this) }
    private lateinit var root: FrameLayout
    private lateinit var hero: FrameLayout
    private lateinit var heroArt: ImageView
    private lateinit var watchGlassArt: ImageView
    private var frostSource: Bitmap? = null
    private var frost: Bitmap? = null
    private lateinit var guideGlassArt: ImageView
    private lateinit var guide: FrameLayout
    private lateinit var guideRows: LinearLayout
    private lateinit var seasonButton: TextView
    private lateinit var previewImage: ImageView
    private lateinit var previewTitle: TextView
    private lateinit var previewAbout: TextView
    private lateinit var previewProgress: FrameLayout
    private lateinit var previewHint: TextView
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
        hero = FrameLayout(this)
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

        initialArt?.let { Images.load(it, heroArt, artWidth, urgent = true) { heroArt.alpha = 1f; syncGlassArt() } }

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
                syncGlassArt()
            }
            showHero(m)
            if (m != null && m.videos.isNotEmpty()) buildGuide(m.videos)
            if (m != null) loadSimilar()
        }
    }

    // ---------- title and watch window ----------

    private fun showHero(m: Meta?) {
        hero.removeAllViews()
        val titleStack = column(Gravity.START)
        val titleBox = FrameLayout(this)
        // A long name without a logo shrinks to fit the logo's box instead of spilling under the facts line.
        val title = text(m?.name ?: name, 38f, bold = true).onArt().apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setAutoSizeTextTypeUniformWithConfiguration(20, 38, 2, android.util.TypedValue.COMPLEX_UNIT_SP)
        }
        titleBox.addView(title, FrameLayout.LayoutParams(-1, -1))
        m?.logo?.let { url ->
            val logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; visibility = View.INVISIBLE }
            titleBox.addView(logo, FrameLayout.LayoutParams(dp(350), dp(70)))
            Images.load(url, logo, dp(700)) { title.visibility = View.INVISIBLE; logo.visibility = View.VISIBLE }
        }
        titleStack.addView(titleBox, LinearLayout.LayoutParams(dp(360), dp(70)))
        if (m == null) {
            val error = failed("Couldn't load this title's details.") { recreate() }
            titleStack.addView(error.marginTop(16))
            error.post { error.getChildAt(1).requestFocus() }
        } else {
            val facts = listOfNotNull(
                m.year?.trimEnd('–', '-', ' '),
                m.genres.take(2).joinToString(" / ").ifEmpty { null },
                m.rating?.let { "IMDb $it" },
            ).joinToString("  ·  ")
            titleStack.addView(text(facts, SMALL, color = TEXT_HI).onArt().apply {
                maxWidth = dp(370); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }.marginTop(6))
            m.description?.let { description ->
                titleStack.addView(text(description, BODY, color = TEXT_HI).onArt().apply {
                    maxWidth = dp(370); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                }.marginTop(22))
            }
            buildWatchWindow(m)
        }
        hero.addView(titleStack, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(50); topMargin = dp(74)
        })
    }

    private fun buildWatchWindow(m: Meta) {
        val x = dp(46)
        val y = resources.displayMetrics.heightPixels * 47 / 100
        val width = dp(450)
        val height = dp(200)
        val (glass, art) = glassPane(x, y, width, height)
        watchGlassArt = art
        syncGlassArt()
        val target = playTarget(m)
        val episode = target?.second
        val resumeAt = item?.let { Library.resumeAt(it, target?.first ?: metaId) } ?: 0L
        val duration = item?.optJSONObject("state")?.optLong("duration") ?: 0L
        val remaining = if (resumeAt > 0 && duration > resumeAt) "${(duration - resumeAt) / 60_000} min left" else null

        glass.addView(text(if (resumeAt > 0) "CONTINUE WATCHING" else "READY TO WATCH", 11f, color = TEXT_HI).apply {
            letterSpacing = 0.22f
        }, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(16); topMargin = dp(12) })

        val still = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(SURFACE)
            clipToOutline = true
            background = rounded(SURFACE, 8)
        }
        glass.addView(still, FrameLayout.LayoutParams(dp(160), dp(85)).apply { leftMargin = dp(16); topMargin = dp(38) })
        Images.load(episode?.thumbnail ?: m.background, still, dp(400), orElse = m.background ?: m.poster)

        val watchLine = column(Gravity.START)
        watchLine.addView(text(episode?.let { "%02d / %s".format(it.episode, it.title.uppercase()) } ?: m.name.uppercase(), if (episode == null) 20f else TITLE, bold = true).apply {
            maxWidth = dp(235); maxLines = if (episode == null) 2 else 1; ellipsize = TextUtils.TruncateAt.END
        })
        val context = listOfNotNull(episode?.let { "S${it.season} E${it.episode}" }, remaining ?: m.runtime).joinToString("  ·  ")
        watchLine.addView(text(context, SMALL, color = TEXT_HI).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }.marginTop(7))
        if (resumeAt > 0 && duration > resumeAt) {
            watchLine.addView(progressBar(resumeAt.toFloat() / duration), LinearLayout.LayoutParams(dp(190), dp(4)).apply { topMargin = dp(10) })
        }
        glass.addView(watchLine, FrameLayout.LayoutParams(dp(245), -2).apply { leftMargin = dp(190); topMargin = dp(47) })

        playButton = glassAction(if (resumeAt > 0) "Resume" else "Play", R.drawable.ic_play, true).apply {
            setOnClickListener { openSources(target?.first ?: metaId, startOver = false) }
            // Held OK: every source, to choose one by hand.
            setOnLongClickListener { openSources(target?.first ?: metaId, startOver = false, pick = true); true }
        }
        glass.addView(playButton, FrameLayout.LayoutParams(dp(156), dp(42)).apply { leftMargin = dp(16); topMargin = dp(142) })
        infoButton = textAction("More info").apply { setOnClickListener { showInfo() } }
        glass.addView(infoButton,
            FrameLayout.LayoutParams(dp(92), dp(42)).apply { leftMargin = dp(188); topMargin = dp(142) })
        listButton = textAction(if (Library.inList(item)) "✓  My List" else "+  My List").apply {
            setOnClickListener { toggleList() }
        }
        glass.addView(listButton, FrameLayout.LayoutParams(dp(115), dp(42)).apply { leftMargin = dp(296); topMargin = dp(142) })
        hero.addView(glass, FrameLayout.LayoutParams(width, height).apply { leftMargin = x; topMargin = y })
        if (type == "series" && m.videos.isNotEmpty()) {
            hero.addView(text("↓  Episodes", SMALL, color = TEXT_HI).onArt(), FrameLayout.LayoutParams(-2, -2).apply {
                leftMargin = dp(54); topMargin = y + height + dp(18)
            })
        }
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

    private fun glassPane(x: Int, y: Int, width: Int, height: Int): Pair<FrameLayout, ImageView> {
        val pane = FrameLayout(this).apply {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(14).toFloat())
            }
            elevation = dp(8).toFloat()
        }
        val art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        pane.addView(art, FrameLayout.LayoutParams(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels).apply {
            leftMargin = -x; topMargin = -y
        })
        pane.addView(View(this).apply { background = glass(14) }, FrameLayout.LayoutParams(-1, -1))
        return pane to art
    }

    /** Both panes show the hero art frosted (see [Images.frost]), made once per picture. */
    private fun syncGlassArt() {
        val source = (heroArt.drawable as? BitmapDrawable)?.bitmap ?: return
        if (source !== frostSource) {
            frostSource = source
            background({ Images.frost(source, 240) }) { r -> if (frostSource === source) { frost = r.getOrNull(); syncGlassArt() } }
            return
        }
        val f = frost ?: return
        if (::watchGlassArt.isInitialized) watchGlassArt.setImageBitmap(f)
        if (::guideGlassArt.isInitialized) guideGlassArt.setImageBitmap(f)
    }

    private fun buildGuide(videos: List<Video>) {
        if (::guide.isInitialized) root.removeView(guide)
        guideOpen = false
        val screen = resources.displayMetrics
        val x = dp(28)
        val y = dp(160)
        val width = screen.widthPixels - dp(56)
        val height = screen.heightPixels - y - dp(20)
        guide = FrameLayout(this).apply { visibility = View.GONE }
        guide.addView(View(this).apply { setBackgroundColor(0x33000000) }, FrameLayout.LayoutParams(-1, -1))
        val compactTitle = FrameLayout(this)
        val fallback = text(meta?.name ?: name, 32f, bold = true).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        compactTitle.addView(fallback, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL or Gravity.START))
        meta?.logo?.let { url ->
            val logo = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; visibility = View.INVISIBLE }
            compactTitle.addView(logo, FrameLayout.LayoutParams(dp(320), dp(60)))
            Images.load(url, logo, dp(640)) { fallback.visibility = View.INVISIBLE; logo.visibility = View.VISIBLE }
        }
        guide.addView(compactTitle, FrameLayout.LayoutParams(dp(340), dp(60)).apply {
            leftMargin = dp(50); topMargin = dp(50)
        })
        val (pane, art) = glassPane(x, y, width, height)
        guideGlassArt = art
        syncGlassArt()
        pane.addView(text("EPISODES", TITLE, bold = true).apply { letterSpacing = 0.24f },
            FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(26); topMargin = dp(17) })
        seasonButton = text("", SMALL, color = TEXT_HI).apply {
            id = View.generateViewId()
            setPadding(dp(6), dp(3), dp(6), dp(3))
            isFocusable = true
            setOnFocusChangeListener { _, has -> background = if (has) glass(6, focused = true) else null }
            setOnClickListener { cycleSeason(1) }
        }
        pane.addView(seasonButton, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(23); topMargin = dp(44) })

        guideRows = column(Gravity.START)
        pane.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(guideRows)
        }, FrameLayout.LayoutParams(dp(365), height - dp(118)).apply { leftMargin = dp(26); topMargin = dp(78) })

        val previewX = dp(425)
        val previewWidth = width - previewX - dp(28)
        previewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(SURFACE)
            clipToOutline = true
            background = rounded(SURFACE, 8)
        }
        pane.addView(previewImage, FrameLayout.LayoutParams(previewWidth, dp(195)).apply { leftMargin = previewX; topMargin = dp(28) })
        previewTitle = text("", TITLE, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        pane.addView(previewTitle, FrameLayout.LayoutParams(previewWidth, -2).apply { leftMargin = previewX; topMargin = dp(232) })
        previewAbout = text("", SMALL, color = TEXT_HI).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        pane.addView(previewAbout, FrameLayout.LayoutParams(previewWidth, -2).apply { leftMargin = previewX; topMargin = dp(261) })
        previewProgress = FrameLayout(this)
        pane.addView(previewProgress, FrameLayout.LayoutParams(previewWidth, dp(4)).apply { leftMargin = previewX; topMargin = dp(311) })
        previewHint = text("", SMALL, color = TEXT_HI)
        pane.addView(previewHint, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = previewX; topMargin = dp(324) })
        pane.addView(text("←  Back to title", SMALL, color = TEXT_HI),
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(26); bottomMargin = dp(20) })
        guide.addView(pane, FrameLayout.LayoutParams(width, height).apply { leftMargin = x; topMargin = y })
        root.addView(guide, FrameLayout.LayoutParams(-1, -1))
        selectedSeason = playTarget(meta)?.second?.season ?: videos.first().season
        fillGuide()
    }

    private fun fillGuide() {
        val videos = meta?.videos.orEmpty()
        val seasonVideos = videos.filter { it.season == selectedSeason }
        seasonButton.text = if (selectedSeason == 0) "SPECIALS  ⌄" else "SEASON $selectedSeason  ⌄"
        guideRows.removeAllViews()
        val watched = Library.watched(item, videos)
        val currentId = playTarget(meta)?.first
        episodeRows = seasonVideos.map { v ->
            val row = FrameLayout(this).apply { tag = v.id; isFocusable = true }
            val mark = View(this).apply { background = rounded(0xFFF7D98F.toInt(), 2); visibility = View.INVISIBLE }
            row.addView(mark, FrameLayout.LayoutParams(dp(3), dp(32), Gravity.CENTER_VERTICAL or Gravity.START))
            val number = text("%02d".format(v.episode), TITLE, color = TEXT_MID)
            row.addView(number, FrameLayout.LayoutParams(dp(45), -2, Gravity.CENTER_VERTICAL).apply { leftMargin = dp(12) })
            val label = text(v.title.ifEmpty { "Episode ${v.episode}" }, BODY).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            row.addView(label, FrameLayout.LayoutParams(dp(180), -2, Gravity.CENTER_VERTICAL).apply { leftMargin = dp(63) })
            val resumeAt = item?.let { Library.resumeAt(it, v.id) } ?: 0L
            val duration = item?.optJSONObject("state")?.takeIf { it.text("video_id") == v.id }?.optLong("duration") ?: 0L
            val status = when {
                v.id in watched -> "Watched"
                resumeAt > 0 && duration > resumeAt -> "${(duration - resumeAt) / 60_000} min left"
                else -> ""
            }
            row.addView(text(status, SMALL, color = TEXT_MID).apply { gravity = Gravity.END },
                FrameLayout.LayoutParams(dp(105), -2, Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dp(8) })
            row.addView(View(this).apply { setBackgroundColor(0x40FFFFFF) },
                FrameLayout.LayoutParams(-1, dp(1), Gravity.BOTTOM))
            row.setOnFocusChangeListener { _, focused ->
                mark.visibility = if (focused) View.VISIBLE else View.INVISIBLE
                row.setBackgroundColor(if (focused) 0x30374437 else Color.TRANSPARENT)
                number.setTextColor(if (focused) Color.WHITE else TEXT_MID)
                label.setTextColor(if (focused) Color.WHITE else TEXT_HI)
                if (focused) updatePreview(v)
            }
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
        previewTitle.text = "%02d / %s".format(v.episode, v.title.uppercase())
        previewAbout.text = v.overview ?: "Episode ${v.episode}"
        previewProgress.removeAllViews()
        val state = item?.optJSONObject("state")?.takeIf { it.text("video_id") == v.id }
        val resumeAt = item?.let { Library.resumeAt(it, v.id) } ?: 0L
        val duration = state?.optLong("duration") ?: 0L
        if (resumeAt > 0 && duration > resumeAt) {
            previewProgress.addView(progressBar(resumeAt.toFloat() / duration), FrameLayout.LayoutParams(-1, -1))
        }
        previewHint.text = if (resumeAt > 0) "Select to resume" else "Select to play"
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

    private fun showInfo() {
        val m = meta ?: return
        if (::infoOverlay.isInitialized) root.removeView(infoOverlay)
        infoOpen = true
        hero.visibility = View.INVISIBLE
        infoOverlay = FrameLayout(this)
        infoOverlay.addView(View(this).apply { setBackgroundColor(0x77000000) }, FrameLayout.LayoutParams(-1, -1))
        val x = dp(55)
        val y = dp(125)
        val (pane, art) = glassPane(x, y, dp(570), dp(295))
        art.setImageDrawable(heroArt.drawable?.constantState?.newDrawable()?.mutate())
        pane.addView(text(m.name, 30f, bold = true), FrameLayout.LayoutParams(-1, -2).apply { setMargins(dp(24), dp(22), dp(24), 0) })
        pane.addView(ScrollView(this).apply {
            addView(text(m.description ?: "No description available.", BODY, color = TEXT_HI).apply { setLineSpacing(dp(4).toFloat(), 1f) })
        }, FrameLayout.LayoutParams(-1, dp(145)).apply { setMargins(dp(24), dp(75), dp(24), 0) })
        val actions = LinearLayout(this)
        val close = glassAction("Back to title", 0, false).apply { setOnClickListener { hideInfo() } }
        actions.addView(close, LinearLayout.LayoutParams(dp(165), dp(42)))
        val target = playTarget(m)
        if (item?.let { Library.resumeAt(it, target?.first ?: metaId) } ?: 0L > 0) {
            actions.addView(glassAction("Start over", R.drawable.ic_play, false).apply {
                setOnClickListener { openSources(target?.first ?: metaId, startOver = true) }
            }, LinearLayout.LayoutParams(dp(150), dp(42)).apply { leftMargin = dp(14) })
        }
        pane.addView(actions, FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dp(24); topMargin = dp(235) })
        infoOverlay.addView(pane, FrameLayout.LayoutParams(dp(570), dp(295)).apply { leftMargin = x; topMargin = y })
        root.addView(infoOverlay, FrameLayout.LayoutParams(-1, -1))
        close.requestFocus()
    }

    private fun hideInfo() {
        infoOpen = false
        root.removeView(infoOverlay)
        hero.visibility = View.VISIBLE
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

    private fun showSimilar(heading: String, titles: List<Meta>) {
        page.addView(text(heading, TITLE, bold = true).onArt().apply {
            setPadding(dp(PAD), 0, dp(PAD), 0)
        }.marginTop(28))
        val cards = LinearLayout(this).apply {
            clipChildren = false
            setPadding(dp(PAD), dp(10), dp(PAD), dp(10))
        }
        titles.forEachIndexed { index, title ->
            val card = FrameLayout(this).apply {
                setBackgroundColor(SURFACE)
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
                }
                val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                addView(image, FrameLayout.LayoutParams(-1, -1))
                addView(View(context).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                        intArrayOf(0xCC000000.toInt(), 0x00000000))
                }, FrameLayout.LayoutParams(-1, dp(60), Gravity.BOTTOM))
                addView(text(title.name, SMALL, bold = true).onArt().apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(10)) })
                isFocusable = true
                setOnFocusChangeListener { v, focused ->
                    v.animate().scaleX(if (focused) 1.08f else 1f).scaleY(if (focused) 1.08f else 1f)
                        .translationZ(if (focused) dp(12).toFloat() else 0f).setDuration(180).start()
                    if (focused) scroll.smoothScrollTo(0, page.height)
                }
                setOnClickListener {
                    startActivity(Intent(this@DetailsActivity, DetailsActivity::class.java)
                        .putExtra("type", title.type).putExtra("id", title.id).putExtra("name", title.name)
                        .putExtra("poster", title.poster).putExtra("background", title.background))
                }
                Images.load(title.background ?: title.poster, image, dp(184))
            }
            cards.addView(card, LinearLayout.LayoutParams(dp(184), dp(104)).apply {
                if (index > 0) marginStart = dp(14)
            })
        }
        page.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
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
            if (infoOpen) { root.removeView(infoOverlay); infoOpen = false }
            hero.visibility = View.VISIBLE
            showHero(meta)
            meta?.videos?.takeIf { it.isNotEmpty() }?.let(::buildGuide)
            if (wasGuide) showGuide()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (infoOpen) { hideInfo(); return true }
                if (guideOpen) { hideGuide(); return true }
            }
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

    private fun glassAction(label: String, icon: Int, primary: Boolean): TextView = text(label, BODY, bold = true).apply {
        gravity = if (icon == 0) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
        if (icon != 0) setPadding(dp(18), 0, dp(12), 0)
        compoundDrawablePadding = dp(8)
        isFocusable = true
        fun style(focused: Boolean) {
            setTextColor(Color.WHITE)
            if (icon != 0) setCompoundDrawablesRelativeWithIntrinsicBounds(
                getDrawable(icon)?.mutate()?.apply { setTint(Color.WHITE) }, null, null, null)
            background = glass(10, focused)
            animate().scaleX(if (focused) 1.03f else 1f).scaleY(if (focused) 1.03f else 1f).setDuration(150).start()
            elevation = dp(if (focused) 10 else 2).toFloat()
        }
        style(false)
        setOnFocusChangeListener { _, focused -> style(focused) }
    }

    private fun textAction(label: String): TextView = text(label, SMALL, color = TEXT_HI).apply {
        gravity = Gravity.CENTER
        isFocusable = true
        setOnFocusChangeListener { _, focused ->
            setTextColor(if (focused) Color.BLACK else TEXT_HI)
            background = if (focused) rounded(Color.WHITE, 7) else null
        }
    }
}
