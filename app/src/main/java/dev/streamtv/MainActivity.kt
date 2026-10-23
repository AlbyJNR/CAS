package dev.streamtv

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

// Home for the profile in use, built to feel like a cinema rather than a catalogue. The whole screen is one title's
// art: sharp where nothing sits on it, with light shading behind text and cards.
//  - Browse (every page, Home opening on Continue Watching): one row of wide 16:9 art cards along the bottom, the
//    focused title's logo, facts and description top left. Down and up slide whole rows in.
//  - Cinema (the screensaver): after IDLE without a key, one Continue Watching title fills the screen with its logo,
//    where you are and Resume, moving on to the next every ADVANCE. OK resumes; any other key returns to where you were.
//    The TV's own screensaver takes over later (10 min on the test TV), so nothing stays still for hours.
class MainActivity : Screen() {
    private enum class Page(val label: String) { HOME("Home"), SHOWS("Shows"), MOVIES("Movies"), LIVE("Live"), LIBRARY("Library") }
    private enum class Mode { CINEMA, BROWSE }

    private lateinit var profile: Profile
    private lateinit var scroller: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var header: FrameLayout
    private val navItems = mutableMapOf<Page, TextView>()
    private var page = Page.HOME
    private var mode = Mode.BROWSE
    private var library: List<JSONObject> = emptyList()
    private val rows = mutableListOf<Row>()
    private var focusedSomething = false
    private val metas = mutableMapOf<String, Meta>() // full details, fetched once per title

    // The art, at full strength and full bleed, over the previous title's art while it crossfades in.
    private lateinit var art: ImageView
    private lateinit var artBack: ImageView
    // Not glass: a light darkening of the art's top edge while the top bar shows. The left and bottom shades are painted
    // into the art itself ([shaded]).
    private lateinit var topShade: View
    // Where you are among the page's rows: a thin track in the left margin, beside the cards, with a thumb per row.
    private lateinit var track: FrameLayout
    private lateinit var thumb: View
    private var artTask = Runnable {}
    private val artW by lazy { resources.displayMetrics.widthPixels }

    /**
     * The art cropped to the screen with its shades painted in, once per picture: the left darkening behind the words
     * and the long one down to the cards. As views over the art they were two more layers the GPU blended every frame,
     * a third of this TV's frame time.
     */
    private val shaded = Images.Look("shaded") { src ->
        val w = artW
        val h = resources.displayMetrics.heightPixels
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val scale = maxOf(w / src.width.toFloat(), h / src.height.toFloat())
        val dw = src.width * scale
        val dh = src.height * scale
        canvas.drawBitmap(src, null, RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xD9000000.toInt(), 0x73000000, 0))
            .apply { setBounds(0, 0, w * 2 / 3, h); draw(canvas) }
        // A long, gentle darkening from the middle of the screen down: the art never stops, it just gets darker.
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0x73000000, 0xD9000000.toInt(), 0xF2000000.toInt()))
            .apply { setBounds(0, h - h * 55 / 100, w, h); draw(canvas) }
        src.recycle()
        out.copy(Bitmap.Config.HARDWARE, false).also { out.recycle() }
    }

    // Browse: the focused title, top left.
    private var heroItem: Item? = null
    private lateinit var browseInfo: LinearLayout
    private lateinit var heroName: TextView
    private lateinit var heroLogo: ImageView
    private lateinit var heroFacts: TextView
    private lateinit var heroAbout: TextView
    private lateinit var heroActions: LinearLayout
    private lateinit var heroResume: TextView
    private lateinit var heroDetails: TextView
    private lateinit var heroProgress: FrameLayout

    // Cinema: one title, low on the left; the stage takes the remote's keys while it shows.
    private var cinema: List<Item> = emptyList()
    private var cinemaAt = 0
    private lateinit var stage: FrameLayout
    private lateinit var cinemaInfo: LinearLayout
    private lateinit var cinemaName: TextView
    private lateinit var cinemaLogo: ImageView
    private lateinit var cinemaLine: TextView
    private lateinit var cinemaPlay: TextView
    private lateinit var dots: LinearLayout

    /** A title in a row or the cinema. Details the catalog didn't send are filled in from the meta addon. */
    private class Item(
        val id: String, val type: String, val name: String, val poster: String?,
        var art: String?, var logo: String?, var about: String, var facts: List<String>,
        val videoId: String?, val progress: Float,
        val resume: Boolean, // has watch progress (Continue Watching, Library): its facts come from the meta addon
        val left: String?, // Continue Watching: "S1 E3 · 31 min left"
    ) {
        var filled = false
        var settled = false // its details are in (or failed): what it has now is all it will get
        val onFill = mutableListOf<() -> Unit>()
    }

    // Title details, newest request first, so the cards on screen get theirs before the ones scrolled past.
    private val metaPool = Images.newestFirst(6)
    private var generation = 0 // which showPage answers belong to: late ones from an earlier page are dropped
    private var touched = false // a key was pressed since the page opened: fresh data then waits for the next visit
    private var cinemaReady = false // the cinema's title has its art and logo (or gave up waiting) and may show
    private var cinemaToken = Any()
    private var beforeCinema: View? = null // where the remote was when the cinema took over
    private val idle = Runnable { enterCinema() }
    private val advance = Runnable { if (mode == Mode.CINEMA && cinema.size > 1) showCinema((cinemaAt + 1) % cinema.size) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profile = Profiles.active(this) ?: return switchProfile()
        // Last launch's first cinema title: start its art and logo now, while the library is still being read.
        getSharedPreferences("home", MODE_PRIVATE).let { prefs ->
            Images.prefetch(prefs.getString("art:${profile.id}", null), artW, urgent = true, look = shaded)
            Images.prefetch(prefs.getString("logo:${profile.id}", null), dp(LOGO_W), urgent = true)
        }

        val screenH = resources.displayMetrics.heightPixels
        val rowTop = screenH - dp(ROW_H + SAFE_BOTTOM) // one row on screen; the track in the left margin says where you are
        content = column(Gravity.START)
        scroller = object : ScrollView(this) {
            private var last: View? = null

            override fun requestChildFocus(child: View, focused: View) {
                super.requestChildFocus(child, focused)
                last = focused
                snapTo(focused)
            }

            // Rows snap into place (below) instead of scrolling just far enough, which left them cut off.
            override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect) = 0

            // The remote moves focus, and focus moves the rows; this never scrolls on its own. Otherwise Down on the
            // last row scrolled it away into the empty space below.
            override fun executeKeyEvent(event: KeyEvent) = false

            // Coming down from the top bar, the remote returns to where it was.
            override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
                val back = last?.takeIf { it.isAttachedToWindow && !hasFocus() && direction == View.FOCUS_DOWN }
                if (back != null) views.add(back) else super.addFocusables(views, direction, focusableMode)
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            addView(content)
            setOnScrollChangeListener { _, _, _, _, _ -> updateImages() }
        }

        art = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
        artBack = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        topShade = View(this).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0)); alpha = 0f }

        // Black from the window alone: a black layout too would be one more full screen painted under the art.
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.BLACK))
        setContentView(FrameLayout(this).apply {
            addView(artBack, FrameLayout.LayoutParams(-1, -1))
            addView(art, FrameLayout.LayoutParams(-1, -1))
            addView(topShade, FrameLayout.LayoutParams(-1, dp(HEADER_H + 96)))
            addView(scroller, FrameLayout.LayoutParams(-1, dp(ROW_H)).apply { topMargin = rowTop })
            track = FrameLayout(context).apply { background = rounded(0x33FFFFFF, 2); alpha = 0f }
            thumb = View(context).apply { background = rounded(Color.WHITE, 2) }
            track.addView(thumb, FrameLayout.LayoutParams(-1, dp(12)))
            addView(track, FrameLayout.LayoutParams(dp(3), dp(CARD_H)).apply { leftMargin = dp(PAD) / 2; topMargin = rowTop + dp(24 + ROW_TOP_ROOM) })
            addView(browseInfo(), FrameLayout.LayoutParams(dp(INFO_W), -2).apply { leftMargin = dp(PAD); topMargin = dp(HEADER_H + 8) })
            addView(stage(), FrameLayout.LayoutParams(-1, -1))
            addView(header().apply { alpha = 0f }, FrameLayout.LayoutParams(-1, dp(HEADER_H))) // fades in if the page wants it
        })

        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            rows.filter { it.active && (now == null || !isInside(now, it.view)) }.forEach { it.light(false) }
            if (mode == Mode.CINEMA) chrome() // the top bar shows only while the remote is on it
        }
        showPage(Page.HOME)
    }

    // Back from a title or the player: Continue Watching may have moved on, so this asks Stremio, not the disk.
    override fun onRestart() {
        super.onRestart()
        if (page == Page.HOME || page == Page.LIBRARY) showPage(page, quick = false)
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(idle)
        main.postDelayed(idle, IDLE)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(idle)
        main.removeCallbacks(advance)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            touched = true
            main.removeCallbacks(idle)
            main.postDelayed(idle, IDLE)
        }
        // In the cinema only OK does anything (resume); any other key wakes back to the rows, and is used up doing so.
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (mode == Mode.CINEMA && !ok) {
            if (event.action == KeyEvent.ACTION_DOWN) enterBrowse()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun switchProfile() {
        startActivity(Intent(this, ProfilesActivity::class.java).putExtra("pick", true))
        finish()
    }

    // ---------- top bar ----------

    private fun header(): View {
        header = object : FrameLayout(this) {
            // Coming up from below, the remote lands on the page you're on.
            override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
                val current = navItems[page]
                if (hasFocus() || direction != View.FOCUS_UP || current == null) super.addFocusables(views, direction, focusableMode)
                else views.add(current)
            }
        }.apply { setPadding(dp(PAD), dp(18), dp(PAD), dp(6)) }

        val avatar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(10), dp(4))
            addView(text(profile.name.take(1).uppercase(), BODY, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded(profile.color, 8)
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(text("▾", SMALL, color = MUTED), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            isFocusable = true
            setOnFocusChangeListener { v, has -> v.background = if (has) glass(8, focused = true) else null }
            setOnClickListener { switchProfile() }
        }
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        Page.entries.forEach { p ->
            val item = text(p.label, BODY).onArt().apply {
                setPadding(dp(18), dp(8), dp(18), dp(8))
                isFocusable = true
                setOnFocusChangeListener { _, _ -> styleNav() }
                setOnClickListener { showPage(p) }
            }
            navItems[p] = item
            nav.addView(item, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(2); marginEnd = dp(2) })
        }
        fun icon(res: Int, label: String, page: Class<*>) = ImageView(this).apply {
            setImageResource(res)
            setColorFilter(Color.WHITE)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isFocusable = true
            setOnFocusChangeListener { _, has ->
                background = if (has) rounded(Color.WHITE, 10) else null
                setColorFilter(if (has) Color.BLACK else Color.WHITE)
            }
            setOnClickListener { startActivity(Intent(this@MainActivity, page)) }
            contentDescription = label
        }
        val icons = LinearLayout(this).apply {
            addView(icon(R.drawable.ic_search, "Search", SearchActivity::class.java), LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(icon(R.drawable.ic_settings, "Settings", SettingsActivity::class.java),
                LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(8) })
        }
        header.addView(avatar, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL))
        header.addView(nav, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        header.addView(icons, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL))
        styleNav()
        return header
    }

    /** A quiet tinted tab marks the page; white appears only when the remote focuses navigation. */
    private fun styleNav() {
        navItems.forEach { (p, item) ->
            val focused = item.isFocused
            item.background = when {
                focused -> rounded(Color.WHITE, 12)
                p == page -> glass(12)
                else -> null
            }
            item.setTextColor(if (focused) Color.BLACK else if (p == page) Color.WHITE else TEXT_MID)
            item.typeface = Typeface.create(if (p == page || focused) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            if (focused) item.setShadowLayer(0f, 0f, 0f, 0) else item.onArt() // no shadow on the white pill
        }
    }

    // ---------- the art, and what shows over it ----------

    /**
     * Shows [item]'s art across the screen, crossfading from the last title's: the old picture stays underneath until
     * the new one is in, so nothing flashes dark or soft. Art that's already here shows at once; otherwise it waits a
     * beat, so racing along a row doesn't start a download per card.
     */
    private fun showArt(item: Item, ready: (() -> Unit)? = null) {
        main.removeCallbacks(artTask)
        // The poster only once it's certain there's no art: a poster cropped to the screen is blurry and half a face.
        val url = item.art ?: item.poster?.takeIf { item.settled } ?: return
        if (url == art.tag) return run { ready?.invoke() }
        artTask = Runnable {
            art.animate().cancel()
            artBack.setImageDrawable(art.drawable)
            art.alpha = 0f
            Images.load(url, art, artW, urgent = true, look = shaded, failed = ready) {
                // Once the new art covers it, the old one lets go: otherwise the GPU paints a hidden full screen every frame.
                art.animate().alpha(1f).setDuration(250).withEndAction { artBack.setImageDrawable(null) }.start()
                ready?.invoke()
            }
        }
        if (Images.cached(url, artW, shaded) || ready != null) artTask.run() else main.postDelayed(artTask, 120)
    }

    /** Gets [item]'s full-screen art ready ahead (fetching its details first if need be), so moving to it is instant. */
    private fun warm(item: Item) {
        item.logo?.let { Images.prefetch(it, dp(LOGO_W)) }
        if (item.art != null) return Images.prefetch(item.art, artW, look = shaded)
        item.onFill += { Images.prefetch(item.art, artW, look = shaded); item.logo?.let { Images.prefetch(it, dp(LOGO_W)) } }
        fill(item)
    }

    /** What's on screen for the mode. */
    private fun chrome() {
        val cinemaOn = mode == Mode.CINEMA
        val headerOn = !cinemaOn || header.hasFocus()
        header.animate().alpha(if (headerOn) 1f else 0f).setDuration(250).start()
        topShade.animate().alpha(if (headerOn) 1f else 0f).setDuration(250).start()
        // Lighter in the cinema (only its few words sit low on the left), full while browsing under the cards.
        browseInfo.animate().alpha(if (cinemaOn) 0f else 1f).setDuration(250).start()
        if (cinemaOn) track.animate().alpha(0f).setDuration(250).start() // browsing brings it back on the next row focus
        cinemaInfo.animate().alpha(if (cinemaOn && cinemaReady) 1f else 0f).setDuration(250).start()
        dots.animate().alpha(if (cinemaOn && cinemaReady && cinema.size > 1) 1f else 0f).setDuration(250).start()
    }

    // ---------- cinema ----------

    private fun stage(): View {
        cinemaName = text("", DISPLAY, bold = true).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(520) }
        cinemaLogo = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; maxHeight = dp(LOGO_H); maxWidth = dp(LOGO_W) }
        cinemaLine = text("", SMALL, color = TEXT_HI).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(INFO_W) }
        // Resume, focused as tvOS draws it: a compact, near-white rounded button with dark text. The time left is in
        // the line above, so the button carries no bar of its own.
        cinemaPlay = text("", BODY, bold = true, color = 0xFF111111.toInt()).apply {
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(8)
            setPadding(dp(16), dp(8), dp(22), dp(8))
            background = rounded(0xF2FFFFFF.toInt(), 8)
            elevation = dp(6).toFloat()
            setCompoundDrawablesRelativeWithIntrinsicBounds(getDrawable(R.drawable.ic_play)?.mutate()?.apply { setTint(0xFF111111.toInt()) }, null, null, null)
        }
        cinemaInfo = column(Gravity.START,
            FrameLayout(this).apply { addView(cinemaName); addView(cinemaLogo) },
            cinemaLine.marginTop(14),
            cinemaPlay.marginTop(22),
        ).apply { alpha = 0f }
        // Page dots as tvOS draws them: small and quiet, the current one a short pill.
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; alpha = 0f }
        stage = FrameLayout(this).apply {
            addView(cinemaInfo, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dp(PAD); bottomMargin = dp(64) })
            addView(dots, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(30) })
            isFocusable = false
            setOnKeyListener { _, code, event ->
                if (event.action != KeyEvent.ACTION_DOWN || mode != Mode.CINEMA) return@setOnKeyListener false
                cinema.getOrNull(cinemaAt)?.let(::play) // only OK gets here (dispatchKeyEvent)
                true
            }
        }
        return stage
    }

    /** Cinema title [index]: its art, logo, where you are (episode, its name, time left) and Resume. */
    private fun showCinema(index: Int) {
        val item = cinema.getOrNull(index) ?: return
        val same = cinemaAt == index && cinemaReady && cinemaName.text.toString() == item.name
        cinemaAt = index
        fill(item)
        // A new title's words come in with its picture, once both its art and logo are here (or after a moment
        // without them), instead of the name, then the logo, then the art popping in one after another.
        val token = Any().also { cinemaToken = it }
        if (!same) { cinemaReady = false; chrome() }
        var waiting = 2
        fun part() {
            if (cinemaToken !== token || --waiting > 0 || cinemaReady) return
            cinemaReady = true
            chrome()
        }
        main.postDelayed({ if (cinemaToken === token && !cinemaReady && item.settled) { waiting = 1; part() } }, REVEAL_WAIT)
        cinemaName.text = item.name
        cinemaName.visibility = View.INVISIBLE
        if (!same) { cinemaLogo.setImageDrawable(null); cinemaLogo.tag = null }
        val logoUrl = item.logo
        if (logoUrl == null) { cinemaName.visibility = View.VISIBLE; part() }
        else Images.load(logoUrl, cinemaLogo, dp(LOGO_W), urgent = true, failed = { cinemaName.visibility = View.VISIBLE; part() }) { part() }
        cinemaLine.text = where(item)
        cinemaPlay.text = if (item.progress > 0) "Resume" else "Play"
        dots.removeAllViews()
        cinema.indices.forEach { i ->
            dots.addView(View(this).apply { background = rounded(if (i == index) Color.WHITE else 0x66FFFFFF, 3) },
                LinearLayout.LayoutParams(dp(if (i == index) 18 else 6), dp(6)).apply { marginStart = dp(if (i > 0) 8 else 0) })
        }
        if (item.art != null || item.settled) showArt(item) { part() } // else its details call this again
        cinema.getOrNull((index + 1) % cinema.size)?.let(::warm)
        main.removeCallbacks(advance)
        if (mode == Mode.CINEMA) main.postDelayed(advance, ADVANCE)
    }

    private fun enterCinema() {
        if (cinema.isEmpty() || mode == Mode.CINEMA) return
        beforeCinema = currentFocus
        cinemaAt = 0
        mode = Mode.CINEMA
        stage.isFocusable = true
        stage.requestFocus()
        scroller.animate().translationY(dp(RISE).toFloat()).alpha(0f).setDuration(300).withEndAction {
            if (mode == Mode.CINEMA) scroller.visibility = View.INVISIBLE
        }.start()
        chrome()
        showCinema(cinemaAt)
    }

    /** Wakes from the cinema to where the remote was; the card taking focus brings its own art back. */
    private fun enterBrowse() {
        mode = Mode.BROWSE
        main.removeCallbacks(advance)
        scroller.visibility = View.VISIBLE
        scroller.animate().translationY(0f).alpha(1f).setDuration(300).setInterpolator(DecelerateInterpolator()).start()
        chrome()
        val back = beforeCinema?.takeIf { it.isAttachedToWindow && it.isShown } ?: firstRow()?.let { it.cards[it.selected] }
        stage.isFocusable = false
        back?.requestFocus()
        heroItem?.let(::showHero) // a card taking focus does this itself; the top bar doesn't
    }

    // ---------- browse: the focused title, top left ----------

    private fun browseInfo(): View {
        heroName = text("", DISPLAY, bold = true).onArt().apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        heroLogo = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START; minimumHeight = dp(64); maxHeight = dp(64); maxWidth = dp(280) }
        heroFacts = text("", SMALL, color = TEXT_HI).onArt().apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        heroAbout = text("", SMALL, color = TEXT_MID).onArt().apply { setLines(2); ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.15f) }
        fun action(label: String, primary: Boolean) = text(label, BODY, bold = primary).apply {
            isFocusable = true
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = if (primary) glass(10) else null
            setOnFocusChangeListener { v, has ->
                v.background = if (has) rounded(Color.WHITE, 10) else if (primary) glass(10) else null
                setTextColor(if (has) Color.BLACK else Color.WHITE)
                if (primary) compoundDrawablesRelative[0]?.setTint(if (has) Color.BLACK else Color.WHITE)
                v.animate().scaleX(if (has) 1.04f else 1f).scaleY(if (has) 1.04f else 1f).setDuration(150).start()
            }
        }
        heroResume = action("Resume", true).apply {
            compoundDrawablePadding = dp(10)
            setCompoundDrawablesRelativeWithIntrinsicBounds(getDrawable(R.drawable.ic_play)?.mutate(), null, null, null)
            setOnClickListener { heroItem?.let(::play) }
            setOnLongClickListener { heroItem?.let { play(it, pick = true) }; true }
        }
        heroDetails = action("More info", false).apply { setOnClickListener { heroItem?.let(::openDetails) } }
        heroActions = LinearLayout(this).apply {
            addView(heroResume)
            addView(heroDetails, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        }
        heroProgress = FrameLayout(this)
        browseInfo = column(Gravity.START,
            FrameLayout(this).apply { addView(heroName); addView(heroLogo) },
            heroFacts.marginTop(8),
            heroAbout.marginTop(6),
            heroActions.marginTop(14),
            heroProgress.marginTop(8),
        ).apply { alpha = 0f }
        return browseInfo
    }

    /** The focused title: its art across the screen, and logo, facts (and time left) and description top left. */
    private fun showHero(item: Item) {
        heroItem = item
        heroName.text = item.name
        heroName.visibility = if (item.logo == null) View.VISIBLE else View.GONE
        val logoUrl = item.logo
        if (logoUrl == null) { heroLogo.setImageDrawable(null); heroLogo.tag = null }
        else if (heroLogo.tag != logoUrl) Images.load(logoUrl, heroLogo, dp(LOGO_W), urgent = true, failed = { if (heroItem === item) heroName.visibility = View.VISIBLE })
        // Something you're watching says where you are and what this episode is about; anything else, its genre, year,
        // rating and story. The card shows the bar.
        heroFacts.text = if (item.left != null) where(item) else item.facts.joinToString(DOT)
        heroAbout.text = episode(item)?.overview?.takeIf { it.isNotBlank() } ?: item.about
        heroActions.visibility = if (page == Page.HOME) View.VISIBLE else View.GONE
        heroResume.visibility = if (item.resume) View.VISIBLE else View.GONE
        heroProgress.removeAllViews()
        heroProgress.visibility = if (page == Page.HOME && item.progress > 0f) View.VISIBLE else View.GONE
        if (heroProgress.visibility == View.VISIBLE) heroProgress.addView(progressBar(item.progress), FrameLayout.LayoutParams(dp(184), dp(2)))
        showArt(item)
    }

    /** One quiet line for a title in progress: the episode and its name (or, for a film, its facts), then the time left. */
    private fun where(item: Item): String {
        val parts = item.left?.split(DOT).orEmpty()
        val code = parts.firstOrNull { it.startsWith("S") && " E" in it }
        val about = if (code != null) listOfNotNull(code, episode(item)?.title?.takeIf { it.isNotEmpty() }) else item.facts
        return (about + listOfNotNull(parts.lastOrNull { it.endsWith("left") })).joinToString(DOT)
    }

    /** The episode Continue Watching points at, once the title's details are in. */
    private fun episode(item: Item) = item.videoId?.let { v -> metas[item.id]?.videos?.firstOrNull { it.id == v } }

    // ---------- pages ----------

    /**
     * Opens page [p]. With [quick], it draws from last time's answers kept on disk (library, addons, catalogs) at once
     * and asks the network behind them: catalogs refresh for the next visit, and the library replaces what's shown
     * only if it changed and the remote hasn't been touched yet.
     */
    private fun showPage(p: Page, quick: Boolean = true) {
        page = p
        val g = ++generation
        touched = false
        styleNav()
        content.removeAllViews()
        rows.forEach { r -> r.cards.forEach { it.unload() } }
        rows.clear()
        if (p == Page.HOME) cinema = emptyList() // other pages keep Home's, so the screensaver works there too
        focusedSomething = false
        scroller.scrollTo(0, 0)
        mode = Mode.BROWSE
        main.removeCallbacks(advance)
        scroller.visibility = View.VISIBLE
        scroller.translationY = 0f
        scroller.alpha = 1f
        stage.isFocusable = false
        chrome()
        val key = profile.authKey

        // Nothing at all came back (no network, Stremio down): say so once, with a retry, instead of a blank page.
        var failedShown = false
        fun failedToLoad(error: Throwable) {
            forgetIfSessionGone(error)
            if (failedShown || isFinishing || page != p) return
            failedShown = true
            val view = failed("Couldn't reach Stremio. Check the TV's connection.") { showPage(page) }.apply { setPadding(dp(PAD), 0, 0, 0) }
            content.addView(view, 0)
            view.post { if (!focusedSomething) view.getChildAt(1).requestFocus() }
        }

        if (p == Page.HOME || p == Page.LIBRARY) {
            val slot = slot()
            var shown: String? = null // what's on screen, to tell whether the fresh library changed anything
            var fresh = false
            fun show(list: List<JSONObject>) {
                val cw = Library.continueWatching(list)
                val what = (if (p == Page.HOME) cw else list).joinToString { it.optString("_id") + it.optJSONObject("state")?.optLong("timeOffset") }
                if (what == shown || (shown != null && touched)) return
                shown = what
                library = list
                rows.filter { isInside(it.view, slot) }.forEach { r -> r.cards.forEach { it.unload() }; rows.remove(r) }
                slot.removeAllViews()
                if (p == Page.HOME) {
                    val items = cw.map(::libraryItem)
                    if (items.isNotEmpty()) {
                        slot.addView(row("Continue Watching", items.take(PAGE), later(items)).view)
                        startCinema(items.take(CINEMA_MAX))
                    }
                } else {
                    showLibrary(slot)
                }
            }
            if (quick) background({ Stremio.savedLibrary(key) }) { r -> if (g == generation && !fresh) r.getOrNull()?.let(::show) }
            background({ Stremio.library(key) }) { result ->
                if (g != generation) return@background
                fresh = true
                result.fold(::show) { if (shown == null) failedToLoad(it) }
            }
        }
        if (p == Page.LIBRARY) return

        var built = false
        fun build(addons: List<Addon>) {
            if (built || g != generation) return
            built = true
            // Each catalog on its page under a plain name, personal rows first; the sort keeps the addons' order within a rank.
            val shelves = addons.flatMap { it.catalogs }.mapNotNull { c -> shelf(c)?.takeIf { it.page == p }?.let { c to it } }
                .sortedBy { it.second.rank }
            val catalogs = shelves.map { it.first }
            if (catalogs.isEmpty() && p != Page.HOME) {
                val what = when (p) { Page.MOVIES -> "movie"; Page.LIVE -> "live"; else -> "show" }
                content.addView(text("None of your addons has $what catalogs. Add one (AIOMetadata, Cinemeta) in Stremio.", BODY, color = MUTED)
                    .apply { setPadding(dp(PAD), 0, dp(PAD), 0) })
            }
            // Rows are made up front so they keep their order, however the answers arrive.
            for ((i, pair) in shelves.take(20).withIndex()) {
                val (catalog, shelf) = pair
                val title = shelf.title
                val slot = slot()
                background({
                    (if (quick) catalog.addon.savedCatalog(catalog) else null)?.let { it to true } ?: (catalog.addon.catalog(catalog) to false)
                }) { answer ->
                    if (g != generation) return@background
                    val (metas, wasSaved) = answer.getOrNull() ?: (emptyList<Meta>() to false)
                    if (wasSaved) background({ catalog.addon.catalog(catalog) }) {} // fresh for next time
                    val items = metas.map(::metaItem)
                    // The rest of the first page first, then the addon's next pages when it takes `skip`.
                    val more: ((Int) -> List<Item>)? = if (catalog.pages) { from ->
                        if (from < items.size) items.drop(from).take(PAGE) else catalog.addon.catalog(catalog, from).map(::metaItem)
                    } else later(items)
                    if (items.isNotEmpty()) slot.addView(row(title, items.take(PAGE), more).view)
                    // Nothing to continue: the cinema shows the first catalog's top titles instead.
                    if (p == Page.HOME && i == 0 && items.isNotEmpty()) main.postDelayed({ if (cinema.isEmpty() && page == p) startCinema(items.take(CINEMA_MAX)) }, 1500)
                    main.post(::updateImages)
                }
            }
        }
        if (quick) background({ Stremio.savedAddons(key) }) { r -> r.getOrNull()?.let(::build) }
        background({ Stremio.addons(key, refresh = p == Page.HOME) }) { r -> r.fold(::build) { if (!built && g == generation) failedToLoad(it) } }
    }

    private class Shelf(val page: Page, val title: String, val rank: Int)

    /**
     * Where a catalog's row goes and what it's called, like Netflix: Home is what you're watching and what it led to
     * ("Because you watched…"); everything else sits on Movies or Shows by type, without the addon's name or a type
     * suffix. Personal picks come first (rank 0–1), then what's popular (2), then themed lists (3). Sport has no page
     * yet, so its catalogs are left out.
     */
    private fun shelf(c: Addon.Catalog): Shelf? {
        // Anything that isn't a film or a series (sport, TV channels, events) goes to Live, under its own name.
        val typePage = when (c.type) { "movie" -> Page.MOVIES; "series" -> Page.SHOWS; else -> return Shelf(Page.LIVE, tidy(c.name), 3) }
        val india = mapOf(
            "trendingmovies" to "Trending in India", "trendingtv" to "Trending in India",
            "popmov" to "Popular in India",
            "nfxprm" to "Popular on Netflix & Prime", "nfxprmtv" to "Popular on Netflix & Prime",
            "hstzee" to "Popular on Hotstar, SonyLIV & Zee5", "hstzeetv" to "Popular on Hotstar, SonyLIV & Zee5",
            "recmov" to "Recommended in India", "atpmub" to "Recommended in India",
        )
        return when {
            c.addon.id.contains("watchly") && c.id.startsWith("watchly.item") -> Shelf(Page.HOME, c.name, 0)
            c.addon.id.contains("watchly") && c.id == "watchly.rec" -> Shelf(typePage, "Top Picks for You", 0)
            c.addon.id.startsWith("trakt.addon") && c.id.startsWith("recommendations") -> Shelf(typePage, "Recommended for You", 1)
            c.addon.id.startsWith("trakt.addon") && c.id.startsWith("trending") -> Shelf(typePage, "Trending Now", 2)
            c.addon.id == "in.rdata.indiastreams" -> Shelf(typePage, india[c.id] ?: c.name, 2)
            else -> Shelf(typePage, tidy(c.name), 3)
        }
    }

    /** Addon names as a person would write them: no "TV Shows:" label, no run-together keyword tags. */
    private fun tidy(name: String) = name.removePrefix("TV Shows: ").removePrefix("MyTrakt - ")
        // ponytail: Watchly glues TMDB keyword tags onto theme names ("Comedy Aftercreditsstinger"); a word this long is one.
        .split(" ").filter { it.length < 16 }.joinToString(" ")

    private fun startCinema(items: List<Item>) {
        cinema = items
        cinemaAt = 0
        if (mode != Mode.CINEMA) return
        showCinema(0)
        stage.requestFocus()
        chrome()
    }

    private fun slot() = FrameLayout(this).also { content.addView(it, LinearLayout.LayoutParams(-1, -2)) }

    private fun showLibrary(slot: FrameLayout) {
        val items = library.filter { !it.optBoolean("removed") && it.optString("type") != "other" }
            .sortedByDescending { it.text("_mtime") }.map(::libraryItem)
        if (items.isEmpty()) {
            slot.addView(text("Titles you add to your Stremio library show up here.", BODY, color = MUTED).apply { setPadding(dp(PAD), 0, 0, 0) })
            return
        }
        val column = column(Gravity.START)
        items.filter { it.type == "movie" }.takeIf { it.isNotEmpty() }?.let { column.addView(row("Movies", it.take(PAGE), later(it)).view) }
        items.filter { it.type == "series" }.takeIf { it.isNotEmpty() }?.let { column.addView(row("Shows", it.take(PAGE), later(it)).view) }
        slot.addView(column)
    }

    // ---------- rows ----------

    /** The rest of a list already in hand, a page at a time: a row of hundreds of cards would be slow to build. */
    private fun later(all: List<Item>): ((Int) -> List<Item>)? =
        if (all.size <= PAGE) null else { from -> all.drop(from).take(PAGE) }

    private fun row(title: String, items: List<Item>, more: ((Int) -> List<Item>)? = null): Row = Row(title, items, more).also {
        rows += it
        main.post(::updateImages)
        if (!focusedSomething && mode == Mode.BROWSE) {
            // Rows answer in any order: give slower ones above a moment, then start at the top row, not the quickest.
            main.removeCallbacks(focusTop)
            main.postDelayed(focusTop, 400)
        }
    }

    private val focusTop = Runnable {
        if (focusedSomething || mode != Mode.BROWSE) return@Runnable
        focusedSomething = true
        firstRow()?.focusFirst()
    }

    /** The row nearest the top of the page: where Up goes back to the cinema from. */
    private fun firstRow(): Row? = rows.filter { it.view.isAttachedToWindow }.minByOrNull { offsetIn(it.view) }

    /**
     * One row of wide art cards along the bottom of the screen; only the focused row shows, the art fills the rest.
     * Left and right are handled here rather than by Android's focus search, so the row slides in one smooth motion,
     * one card per press, and its ends stop the remote.
     */
    /**
     * A row of cards. With [more], it asks for the titles after the ones it has when the remote gets near its end (a
     * catalog's next page, or the rest of a long library), and stops once an answer brings nothing new.
     */
    private inner class Row(title: String, first: List<Item>, private var more: ((Int) -> List<Item>)?) {
        val items = first.toMutableList()
        val cards = items.mapIndexed { i, item -> Card(item, i) }.toMutableList()
        private var fetching = false
        var selected = 0
        var active = false
        var loaded = false
        var allLoaded = false
        private var anim: ValueAnimator? = null
        private var held = false // the remote's key is held down: the row glides instead of stepping
        val strip = LinearLayout(this@MainActivity).apply {
            // Room above and below for the focused card to grow into without being clipped.
            setPadding(dp(PAD), dp(ROW_TOP_ROOM), dp(PAD), dp(ROW_BOTTOM_ROOM))
            clipToPadding = false
            cards.forEachIndexed { i, c -> addView(c, LinearLayout.LayoutParams(dp(CARD_W), dp(CARD_H)).apply { if (i > 0) marginStart = dp(GAP) }) }
        }
        val scroller: HorizontalScrollView = object : HorizontalScrollView(this@MainActivity) {
            override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect) = 0

            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                val left = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                if (!left && event.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return super.dispatchKeyEvent(event)
                if (event.action == KeyEvent.ACTION_DOWN) {
                    held = event.repeatCount > 0
                    cards.getOrNull(selected + if (left) -1 else 1)?.requestFocus()
                } else held = false
                return true // at either end the remote simply stays put
            }

            // Coming in from above or below, the remote lands on the card this row last had.
            override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
                if (!hasFocus() && (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN)) views.add(cards[selected])
                else super.addFocusables(views, direction, focusableMode)
            }
        }.apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
            setOnScrollChangeListener { _, _, _, _, _ -> loadRest(this@Row) }
        }
        // The row the remote is in has a bright title; the next one, peeking in below, is a quiet hint.
        private val heading = text(title, BODY, bold = true).onArt().apply {
            setPadding(dp(PAD), 0, 0, 0)
            translationY = dp(10).toFloat() // clear the hero progress line without leaving a gap before the next shelf
            alpha = QUIET
        }
        val view: View = column(Gravity.START, heading, scroller)

        /** In or out of this row: its title brightens or quiets, its cards come forward or settle back. */
        fun light(on: Boolean) {
            active = on
            heading.animate().alpha(if (on) 1f else QUIET).setDuration(200).start()
            cards.forEachIndexed { i, c -> c.dim(on && i != selected) }
        }

        fun focusFirst() = cards[selected].requestFocus()

        /**
         * The remote reached card [index]: slide it to the row's start, where the eye already is. Near the end the row
         * stops, so the last card rests at the right margin instead of leaving a hole. Cards that slid into the left
         * margin hide: everything lines up on the margin, with no sliver at the screen's edge. On the right, the next
         * card runs off the screen on purpose, to say there's more.
         */
        fun focus(index: Int) {
            selected = index
            light(true)
            fill(items[index])
            if (index >= items.size - AHEAD) fetchMore()
            // Racing past, the cards beside aren't where the remote will stop: fetching their art would only queue
            // full-screen downloads and decodes that compete with the row's own pictures.
            if (!held) listOf(index - 1, index + 1).mapNotNull { items.getOrNull(it) }.forEach(::warm)
            anim?.cancel()
            val startScroll = scroller.scrollX
            val targetScroll = minOf(index * (dp(CARD_W) + dp(GAP)), (strip.width - scroller.width).coerceAtLeast(0))
            // Only whole cards show: one slid into either margin hides, so the row starts and ends on the margins.
            val right = scroller.width - dp(PAD) / 2
            cards.forEach { c -> c.show(!(c.left - targetScroll < dp(PAD) / 2 || c.right - targetScroll > right)) }
            // A held key repeats every 50 ms. Restarting a slowing-down slide on each repeat made the row lurch and brake
            // twenty times a second; a straight slide that lasts until the next repeat joins them into one glide.
            anim = ValueAnimator.ofInt(startScroll, targetScroll).setDuration(if (held) ViewConfiguration.getKeyRepeatDelay().toLong() else 200).apply {
                interpolator = if (held) LinearInterpolator() else DecelerateInterpolator()
                addUpdateListener { scroller.scrollTo(it.animatedValue as Int, 0) }
                start()
            }
        }

        private fun fetchMore() {
            val more = more ?: return
            if (fetching) return
            fetching = true
            val from = items.size
            background({ more(from) }) { result ->
                fetching = false
                val known = items.map { it.id }.toSet()
                val fresh = result.getOrElse { return@background }.filter { it.id !in known } // a failed page can be asked again
                if (fresh.isEmpty()) { this.more = null; return@background }
                for (item in fresh) {
                    val card = Card(item, items.size)
                    items += item
                    cards += card
                    strip.addView(card, LinearLayout.LayoutParams(dp(CARD_W), dp(CARD_H)).apply { marginStart = dp(GAP) })
                    card.alpha = 0f // shows once focus moves near, like the cards past the right margin
                    card.dim(active)
                    if (loaded) card.load()
                }
            }
        }

        /** One title: its wide art with the logo bottom left (the name when there's no logo), and progress. */
        inner class Card(val item: Item, val index: Int) : FrameLayout(this@MainActivity) {
            // Fading, a card that may overlap itself is drawn into a layer of its own first: an extra pass for every
            // card fading in or out. Fading each part on its own looks the same over 150 ms.
            override fun hasOverlappingRendering() = false
            // Grey only until the art arrives, so the GPU doesn't paint a hidden layer under every card.
            private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            private val name = text(item.name, SMALL, bold = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; visibility = View.INVISIBLE }
            private var shown: String? = null
            private var wanted = false // near the screen: load when the details arrive
            // The poster appears once it's decoded; until then the card is a plain dark tile.
            private val face = object : FrameLayout(context) { override fun hasOverlappingRendering() = false }.apply { alpha = 0f }
            // Darkens the cards beside the focused one. A shade, not transparency: a see-through card would let the
            // art behind it show through and look like it was floating on the poster.
            private val shade = View(context).apply { setBackgroundColor(Color.BLACK); alpha = 0f }

            init {
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) =
                        outline.setRoundRect(0, 0, view.width, view.height, dp(RADIUS).toFloat())
                }
                clipToOutline = true
                setBackgroundColor(SURFACE)
                face.addView(image, LayoutParams(-1, -1))
                face.addView(View(context).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x00000000))
                }, LayoutParams(-1, dp(CARD_H * 4 / 10), Gravity.BOTTOM))
                face.addView(name, LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(12), 0, dp(12), dp(if (item.progress > 0) 14 else 10)) })
                addView(face, LayoutParams(-1, -1))
                if (item.progress > 0) addView(progressBar(item.progress), LayoutParams(-1, dp(4), Gravity.BOTTOM))
                addView(shade, LayoutParams(-1, -1))
                isFocusable = true
                tag = "card:${item.id}"
                item.onFill += { if (wanted) load() }
                setOnFocusChangeListener { _, has ->
                    // Focus grows into the gaps without moving its neighbours; a fine edge stays visible on matching art.
                    animate().scaleX(if (has) GROW else 1f).scaleY(if (has) GROW else 1f).translationZ(if (has) dp(LIFT).toFloat() else 0f).setDuration(180).start()
                    foreground = if (has) GradientDrawable().apply {
                        cornerRadius = dp(RADIUS).toFloat()
                        setStroke(dp(1), FOCUS_EDGE)
                    } else null
                    if (has) {
                        focus(index)
                        showHero(item)
                    }
                }
                setOnClickListener { if (page == Page.HOME && item.resume) play(item) else openDetails(item) }
                if (item.resume) setOnLongClickListener { play(item, pick = true); true }
            }

            private var showing: Boolean? = null

            /** In view or slid into a margin. Only a change animates: every card restarting a fade on each press was work for nothing. */
            fun show(on: Boolean) {
                if (on == showing) return
                showing = on
                animate().alpha(if (on) 1f else 0f).setDuration(150).start()
            }

            fun dim(on: Boolean) = shade.animate().alpha(if (on) DIM else 0f).setDuration(200).start()

            /** On screen (or nearly): the poster (the wide art cropped for a title without one, else its name). */
            fun load() {
                wanted = true
                if (item.poster == null && item.art == null) fill(item)
                val url = item.poster ?: item.art ?: run { if (item.settled) { name.visibility = View.VISIBLE; face.alpha = 1f }; return }
                if (url == shown) return
                shown = url
                face.animate().cancel()
                face.alpha = 0f
                var now = true // from memory: no fade, it's simply there
                Images.load(url, image, dp(CARD_W), failed = { name.visibility = View.VISIBLE; face.alpha = 1f }) {
                    name.visibility = View.INVISIBLE
                    if (now) face.alpha = 1f else face.animate().alpha(1f).setDuration(180).start()
                }
                now = false
            }

            /** Far off screen: let the pictures go. */
            fun unload() {
                wanted = false
                shown = null
                image.tag = null
                image.setImageDrawable(null)
                face.alpha = 0f
            }
        }
    }

    // ---------- items ----------

    /** Art, logo and facts the catalog didn't send, fetched once per title from the meta addon. */
    private fun fill(item: Item) {
        if (item.filled) return
        item.filled = true
        metas[item.id]?.let { applyMeta(item, it); return }
        val key = profile.authKey
        metaPool.execute {
            // Last time's details from disk when there are some; the network only for titles never seen here.
            val m = runCatching {
                Stremio.addons(key).firstOrNull { it.serves("meta", item.type, item.id) }
                    ?.let { it.savedMeta(item.type, item.id) ?: it.meta(item.type, item.id) }
            }.getOrNull()
            main.post {
                if (isDestroyed) return@post
                if (m == null) return@post settle(item)
                metas[item.id] = m
                applyMeta(item, m)
            }
        }
    }

    private fun applyMeta(item: Item, m: Meta) {
        if (item.art == null) item.art = m.background
        if (item.logo == null) item.logo = m.logo
        if (item.about.isEmpty()) item.about = m.description ?: ""
        if (item.facts.isEmpty() || item.resume) item.facts = facts(m)
        // Home's first title (the first card the remote lands on): next launch starts on its art and logo.
        if (cinema.firstOrNull() === item && item.art != null) getSharedPreferences("home", MODE_PRIVATE).edit()
            .putString("art:${profile.id}", item.art).putString("logo:${profile.id}", item.logo).apply()
        settle(item)
    }

    /** [item] has all it will get: cards, the hero and the cinema waiting on it go ahead. */
    private fun settle(item: Item) {
        item.settled = true
        item.onFill.forEach { it() }
        if (heroItem === item && mode == Mode.BROWSE) showHero(item)
        if (mode == Mode.CINEMA && cinema.getOrNull(cinemaAt) === item) showCinema(cinemaAt)
    }

    private fun facts(m: Meta) = listOfNotNull(
        m.genres.take(2).joinToString(", ").ifEmpty { null }, m.year, m.rating?.let { "IMDb $it" },
    )

    private fun metaItem(m: Meta) = Item(
        m.id, m.type, m.name, m.poster, m.background, m.logo ?: Images.logoFor(m.id), m.description ?: "", facts(m),
        null, 0f, resume = false, left = null,
    )

    private fun libraryItem(item: JSONObject): Item {
        val s = item.optJSONObject("state") ?: JSONObject()
        val videoId = s.text("video_id")
        val duration = s.optLong("duration")
        val offset = s.optLong("timeOffset")
        // "S1 E3" for an episode (ids look like tt123:1:3), and how much is left.
        val episode = videoId?.split(":")?.takeIf { it.size == 3 }?.let { "S${it[1]} E${it[2]}" }
        val left = if (duration > 0 && offset > 1) "${(duration - offset) / 60_000} min left" else null
        val progress = if (duration > 0) (offset.toFloat() / duration).coerceIn(0f, 1f) else 0f
        return Item(
            item.getString("_id"), item.optString("type"), item.optString("name"), item.text("poster"),
            null, Images.logoFor(item.getString("_id")), "", emptyList(), videoId, progress,
            resume = progress > 0, left = listOfNotNull(episode, left).joinToString(DOT).ifEmpty { null },
        )
    }

    /** Resume: straight to the sources for the episode Continue Watching points at; with [pick] (held OK) they're all listed. */
    private fun play(item: Item, pick: Boolean = false) {
        val videoId = item.videoId ?: item.id
        val videos = metas[item.id]?.videos.orEmpty()
        val next = videos.getOrNull(videos.indexOfFirst { it.id == videoId } + 1)?.takeIf { videos.any { it.id == videoId } }
        val episode = item.left?.substringBefore(" · ")?.takeIf { item.type == "series" && it.startsWith("S") }
        startActivity(Intent(this, StreamsActivity::class.java)
            .putExtra("type", item.type).putExtra("id", item.id).putExtra("name", item.name).putExtra("poster", item.poster)
            .putExtra("videoId", videoId).putExtra("nextVideoId", next?.id).putExtra("pick", pick)
            .putExtra("label", listOfNotNull(item.name, episode).joinToString("  ·  ")))
    }

    private fun openDetails(item: Item) {
        startActivity(Intent(this, DetailsActivity::class.java)
            .putExtra("type", item.type).putExtra("id", item.id).putExtra("name", item.name)
            .putExtra("poster", item.poster).putExtra("background", item.art).putExtra("videoId", item.videoId))
    }

    // ---------- vertical movement ----------

    /** Shows the focused row in the row band (one row at a time) and moves the track's thumb to it. */
    private fun snapTo(focused: View) {
        val rowView = rows.firstOrNull { isInside(focused, it.view) }?.view ?: return
        scroller.smoothScrollTo(0, offsetIn(rowView).coerceAtLeast(0))
        val order = rows.filter { it.view.isAttachedToWindow }.map { it.view }.sortedBy { offsetIn(it) }
        val n = order.size
        val at = order.indexOf(rowView).coerceAtLeast(0)
        val thumbH = maxOf(dp(12), dp(CARD_H) / maxOf(n, 1))
        thumb.layoutParams = thumb.layoutParams.apply { height = thumbH }
        thumb.animate().translationY(if (n > 1) (dp(CARD_H) - thumbH) * at / (n - 1f) else 0f).setDuration(200).start()
        track.animate().alpha(if (n > 1 && mode == Mode.BROWSE) 1f else 0f).setDuration(200).start()
    }

    // ---------- memory: pictures only near the screen ----------

    private fun updateImages() {
        val top = scroller.scrollY
        val height = scroller.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        // Requests run newest first, so the bottom rows are asked for first and the top row last: it loads first.
        for (r in rows.filter { it.view.isAttachedToWindow }.sortedByDescending { offsetIn(it.view) }) {
            val y = offsetIn(r.view)
            val near = y < top + height * 3 && y + r.view.height > top - height
            val far = y > top + height * 5 || y + r.view.height < top - height * 3
            // Only the row on screen and its neighbours (where the remote can go next) stay in the drawing. The rest,
            // hundreds of cards, were walked by the RenderThread every frame though none of them shows.
            r.view.visibility = if (y < top + height * 2 && y + r.view.height > top - height) View.VISIBLE else View.INVISIBLE
            if (near && !r.loaded) {
                r.loaded = true
                r.cards.take(FIRST).asReversed().forEach { it.load() }
            } else if (far && r.loaded) {
                r.loaded = false
                r.allLoaded = false
                r.cards.forEach { it.unload() }
            }
        }
    }

    /** A row scrolled sideways: its cards past the first screenful load too. */
    private fun loadRest(r: Row) {
        if (r.loaded && !r.allLoaded) {
            r.allLoaded = true
            r.cards.drop(FIRST).forEach { it.load() }
        }
    }

    private fun offsetIn(v: View): Int {
        var y = 0
        var cur: View? = v
        while (cur != null && cur !== content) {
            y += cur.top
            cur = cur.parent as? View
        }
        return y
    }

    // Signed out elsewhere (or the session expired): this TV forgets the profile, which can then sign in again.
    private fun forgetIfSessionGone(error: Throwable) {
        if ((error as? Stremio.ApiException)?.code == Stremio.NO_SESSION) {
            Profiles.remove(this, profile.id)
            switchProfile()
        }
    }

    private companion object {
        const val FOCUS_EDGE = 0xFFE8E0CE.toInt()
        const val HEADER_H = 76 // dp
        // 2:3 posters, six across with the same margin on both sides (56 + 6 × 124 + 5 × 20 + 56 = 956 of 960 dp), the
        // size Stremio's TV app uses (125 × 183 dp); Netflix and Stremio both browse by poster.
        const val CARD_W = 124
        const val CARD_H = 186
        const val INFO_W = 360 // the text block top left: a third of the screen, clear of the art's middle
        const val LOGO_W = 360 // the cinema's title logo
        const val LOGO_H = 100
        const val DOT = "  ·  "
        const val GAP = 20
        const val RADIUS = 16
        const val GROW = 1.06f // the focused card: grows into the gaps without touching its neighbours
        const val LIFT = 8 // dp it rises, for its shadow
        const val ROW_TOP_ROOM = 16 // dp between the row title and its cards, including focus growth
        const val ROW_BOTTOM_ROOM = 14 // dp below the cards, for the focused one's growth and shadow
        const val SAFE_BOTTOM = 12 // dp below the row band; with its own 14 dp the cards end 26 dp (5%) from the edge, which TVs may crop
        const val DIM = 0.20f // how dark the other cards in the row the remote is in get
        const val QUIET = 0.65f // the next row remains visible without competing with the active one
        const val ROW_H = 24 + ROW_TOP_ROOM + CARD_H + ROW_BOTTOM_ROOM
        const val RISE = 200 // dp the rows travel when they come up from the cinema
        const val PAGE = 25 // cards a row starts with; more come as the remote nears its end
        const val AHEAD = 6 // cards from a row's end when the next page is asked for
        const val FIRST = 8 // cards per row loaded before it's scrolled sideways (a screenful and two)
        const val CINEMA_MAX = 8
        const val IDLE = 5 * 60_000L // ms without a key before the cinema takes over (the TV's own screensaver: 10 min)
        const val ADVANCE = 20_000L // ms each cinema title shows
        const val REVEAL_WAIT = 1200L // ms the cinema waits for a title's art and logo before showing its words anyway
    }
}
