package dev.alder

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * A title's art, sharp, fading out into the glass on its left (under the text) and towards the bottom, from
 * [bottomFrom] to [bottomTo] of its height. A mask on the picture itself, not a colour painted over it, so there's no
 * box where the fade meets the blurred scene behind.
 */
class FadedArt(context: Context, private val bottomFrom: Float = 0.45f, private val bottomTo: Float = 1f) : ImageView(context) {
    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private var left: Shader? = null
    private var bottom: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // Under the text (the left 60%) only a hint of the picture stays, so bright art can't fight the words.
        left = LinearGradient(0f, 0f, w * 0.8f, 0f, intArrayOf(0x1F000000, 0x40000000, Color.BLACK), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        bottom = LinearGradient(0f, h * bottomFrom, 0f, h * bottomTo, intArrayOf(Color.BLACK, 0x59000000, 0), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.onDraw(canvas)
        for (s in listOf(left, bottom)) {
            mask.shader = s
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), mask)
        }
        canvas.restoreToCount(layer)
    }
}

/** Keeps text readable straight on art: a soft dark shadow instead of a panel over the picture. */
fun TextView.onArt() = apply { setShadowLayer(resources.displayMetrics.density * 8, 0f, resources.displayMetrics.density, 0xCC000000.toInt()) }

/** What every screen shares: the signed-in account, background work, and the few view helpers the plain UI uses. */
abstract class Screen : Activity() {
    protected val main = Handler(Looper.getMainLooper())
    /** The Stremio account of the profile in use. */
    protected val authKey: String? get() = Profiles.active(this)?.authKey

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (Http.saved != null) return
        // Pictures load many at a time from a few hosts: keep enough connections open to reuse (Android keeps 5).
        System.setProperty("http.maxConnections", "16")
        // Disk cache for pictures (and anything else whose server allows caching), and last time's answers.
        android.net.http.HttpResponseCache.install(java.io.File(cacheDir, "http"), 250L * 1024 * 1024)
        Http.saved = java.io.File(cacheDir, "answers")
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Runs [work] off the main thread and hands its result back on it, unless the screen is gone. */
    protected fun <T> background(work: () -> T, done: (Result<T>) -> Unit) {
        pool.execute {
            val result = runCatching(work)
            main.post { if (!isDestroyed) done(result) }
        }
    }

    /**
     * Text in Alder's type: Apple TV's styles. Like tvOS, everything is Medium (crisper from the couch than Regular) and
     * [bold] is its emphasized weight; each style's own leading comes with its size (see [LEADING]).
     */
    protected fun text(value: String, sp: Float, bold: Boolean = false, color: Int = Color.WHITE) =
        TextView(this).apply {
            text = value
            textSize = sp
            setTextColor(color)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.create("sans-serif-medium", Typeface.NORMAL)
            LEADING[sp]?.let { lead ->
                val want = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, lead, resources.displayMetrics)
                setLineSpacing(want - paint.getFontMetricsInt(null), 1f)
            }
        }

    protected fun column(gravity: Int, vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false // focused pills and pictures grow past their own bounds
        this.gravity = gravity
        // Wrap-content by default: a vertical LinearLayout would otherwise stretch every child to full width.
        views.forEach { addView(it, it.layoutParams ?: wrap()) }
    }

    protected fun <V : View> V.marginTop(top: Int) = apply { layoutParams = wrap().apply { topMargin = dp(top) } }

    protected fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Remote-friendly focus: the focused view grows a little. */
    protected fun <V : View> V.focusable(grow: Float = 1.06f) = apply {
        isFocusable = true
        setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) grow else 1f).scaleY(if (has) grow else 1f).setDuration(120).start()
        }
    }

    /**
     * The app's look: the whole TV screen is one sheet of frosted glass over a blurred scene ([backdrop]: the current
     * title's art, or a soft wash of colour). The frost is a tint that's lighter towards the top left, like light on
     * glass. Everything sits directly on it; nothing on screen is glass of its own.
     */
    protected fun setWindowed(content: View, backdrop: View = View(this).apply { background = mood() }) {
        window.setBackgroundDrawable(null) // the scene paints the whole screen
        setContentView(FrameLayout(this).apply {
            addView(backdrop, FrameLayout.LayoutParams(-1, -1))
            addView(View(context).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0x80303530.toInt(), 0xB3171916.toInt(), 0xCC111311.toInt()))
            }, FrameLayout.LayoutParams(-1, -1))
            addView(content, FrameLayout.LayoutParams(-1, -1))
        })
    }

    /** A quiet tinted row on the pane (episodes, sources): no edge, no glass of its own. */
    protected fun tile(radiusDp: Int = TILE_RADIUS) = GradientDrawable().apply {
        setColor(0x14FFFFFF)
        cornerRadius = dp(radiusDp).toFloat()
    }

    /** For screens with no title to show behind the pane (the profile screens): a soft, dark wash of colour. */
    private fun mood() = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF2A3A55.toInt(), 0xFF16191E.toInt(), 0xFF3D2230.toInt()))

    protected fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radiusDp).toFloat() }

    /** Borderless dark glass; focus is shown by a lighter fill and each control's scale or text treatment. */
    protected fun glass(radiusDp: Int, focused: Boolean = false) =
        GradientDrawable().apply {
            setColor(if (focused) GLASS_FOCUSED else GLASS)
            cornerRadius = dp(radiusDp).toFloat()
        }

    /**
     * The hero's buttons (Home, the title page): small pills of one height, one solid grey at rest so they look the
     * same over any art, solid white with dark words once the remote is on one.
     */
    protected fun pill(label: String, icon: Int = 0): TextView = text(label, SMALL, bold = true).apply {
        isFocusable = true
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(BUTTON_H)
        setPadding(dp(if (icon != 0) 14 else 16), 0, dp(16), 0)
        compoundDrawablePadding = dp(6)
        if (icon != 0) setCompoundDrawablesRelative(getDrawable(icon)?.mutate()?.apply { setBounds(0, 0, dp(16), dp(16)) }, null, null, null)
        fun style(focused: Boolean) {
            background = rounded(if (focused) Color.WHITE else PILL, BUTTON_H / 2)
            setTextColor(if (focused) INK else Color.WHITE)
            compoundDrawablesRelative[0]?.setTint(if (focused) INK else Color.WHITE)
        }
        style(false)
        setOnFocusChangeListener { v, has ->
            style(has)
            v.animate().scaleX(if (has) FOCUS_GROW else 1f).scaleY(if (has) FOCUS_GROW else 1f).setDuration(150).start()
        }
    }

    /**
     * The focused row, on every list (settings, addons, episodes, sources) and small control: a white fill with dark
     * words and icons, the same as a focused pill. [rest] is the fill when not focused; [also] runs on every change.
     */
    protected fun View.rowFocus(radiusDp: Int = 8, rest: () -> android.graphics.drawable.Drawable? = { null }, also: (Boolean) -> Unit = {}) {
        isFocusable = true
        background = rest()
        setOnFocusChangeListener { v, has ->
            v.background = if (has) rounded(Color.WHITE, radiusDp) else rest()
            ink(v, has)
            also(has)
        }
    }

    /** Dark words on the white fill, and back to each one's own colour (and shadow, for text on art) after. */
    private fun ink(v: View, on: Boolean) {
        when (v) {
            is TextView -> {
                val (color, shadow) = v.tag as? Pair<*, *> ?: (v.currentTextColor to v.shadowRadius).also { v.tag = it }
                v.setTextColor(if (on) INK else color as Int)
                if (on) v.setShadowLayer(0f, 0f, 0f, 0) else if ((shadow as Float) > 0f) v.onArt()
                v.compoundDrawablesRelative.forEach { it?.setTint(if (on) INK else Color.WHITE) }
            }
            is ImageView -> if (on) v.setColorFilter(INK) else v.clearColorFilter()
            is android.view.ViewGroup -> for (i in 0 until v.childCount) ink(v.getChildAt(i), on)
        }
    }

    /**
     * The focused picture (posters, search results, avatars): it grows by [CARD_GROW] and lifts on a shadow. Never a ring,
     * outline or border: the picture itself comes forward.
     */
    protected fun View.cardFocus(also: (Boolean) -> Unit = {}) {
        isFocusable = true
        setOnFocusChangeListener { v, has ->
            v.lift(has)
            also(has)
        }
    }

    protected fun View.lift(on: Boolean) = animate().scaleX(if (on) CARD_GROW else 1f).scaleY(if (on) CARD_GROW else 1f)
        .translationZ(if (on) dp(CARD_LIFT).toFloat() else 0f).setDuration(180).start()

    /** A thin watched-so-far bar: accent (or [color]) over a faint track. */
    protected fun progressBar(progress: Float, color: Int = ACCENT) = LinearLayout(this).apply {
        val p = progress.coerceIn(0.02f, 1f)
        setBackgroundColor(GLASS_HIGH)
        addView(View(context).apply { setBackgroundColor(color) }, LinearLayout.LayoutParams(0, -1, p))
        addView(View(context), LinearLayout.LayoutParams(0, -1, 1f - p))
    }

    protected fun isInside(v: View, parent: View): Boolean = generateSequence(v) { it.parent as? View }.any { it === parent }

    /** Something didn't load: what went wrong in a line, and a button to try again. */
    protected fun failed(message: String, retry: () -> Unit) = column(Gravity.START,
        text(message, BODY, color = TEXT_MID),
        pill("Try again").apply { setOnClickListener { retry() } }.marginTop(16),
    )

    // The design's values, in one place: every screen takes its colours and type sizes from here.
    companion object {
        const val PAD = 56 // dp: the side margin, clear of the TV's edges
        const val TILE_RADIUS = 12
        const val HERO_W = 520 // dp: a title's block on Home and its page, half the screen so the story reads in full lines
        const val BUTTON_H = 32 // dp: the hero's pills
        const val FOCUS_GROW = 1.06f // how much a focused pill grows, everywhere
        // A focused picture grows by this and rises CARD_LIFT dp on its shadow: the only sign of focus on pictures (no
        // rings or outlines). 10% of a 120 dp poster is 6 dp a side, inside its 16 dp gaps.
        const val CARD_GROW = 1.10f
        const val CARD_LIFT = 12
        // Spacing, everywhere rows of pictures appear: between cards, from a row's heading to its cards (plus room for
        // the focused card to grow), and above a heading, so each heading belongs to the row under it.
        const val CARD_GAP = 16
        const val POSTER_W = 120 // dp: a 2:3 poster in a row
        const val POSTER_H = 180
        const val HEADING_GAP = 12
        const val SECTION_GAP = 32
        const val DOT = "  ·  "

        // Three sizes, on every screen (sp, from Apple TV's text styles: tvOS points on a 1920 × 1080 screen, halved for
        // a 1080p Android TV's 2 px per dp). One thing per screen is DISPLAY, about twice BODY, so the eye knows where
        // to land; headings are BODY in bold, not a size of their own. Sizes are sp, so the TV's font size setting
        // scales them; layouts must measure text, not assume its height. One-off glyphs (the sign-in code, PIN digits,
        // avatar letters) keep their own sizes.
        const val DISPLAY = 28.5f // Title 2 (57 pt): the screen's one title (a title's name, "Settings", "Who's watching?")
        const val BODY = 14.5f // Body (29 pt): descriptions, headings (bold), navigation, list items
        const val SMALL = 12.5f // Caption 1 (25 pt): facts, buttons, secondary lines, labels

        // Each size's leading (sp), tvOS's line height halved like its size.
        val LEADING = mapOf(DISPLAY to 33f, BODY to 18f, SMALL to 16f)

        // Text on the glass, brightest first.
        const val TEXT_HI = 0xFFE2E4E7.toInt() // a title's facts line
        const val TEXT_MID = 0xFFC4C7CC.toInt() // descriptions, items not selected
        const val MUTED = 0xFFA0A4AB.toInt() // hints and status lines

        // Fills on the glass.
        const val SURFACE = 0xFF24272D.toInt() // placeholder while a picture loads
        const val GLASS_LOW = 0x24FFFFFF // the "Add profile" tile
        const val GLASS_HIGH = 0x40FFFFFF // progress track
        const val PILL = 0xFF3A3D42.toInt() // a pill at rest: solid, so it doesn't change shade with the art behind
        // Flat translucent fills keep the glass quiet while focus remains visible.
        const val GLASS = 0xB3080808.toInt()
        const val GLASS_FOCUSED = 0xD0252525.toInt()

        const val INK = 0xFF111111.toInt() // dark words on a white button
        const val ACCENT = 0xFFE5484D.toInt()
        const val GREEN = 0xFF46D369.toInt() // ratings

        private val pool = Executors.newFixedThreadPool(6)
    }
}
