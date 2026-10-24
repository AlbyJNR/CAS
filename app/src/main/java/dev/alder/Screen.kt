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

    protected fun text(value: String, sp: Float, bold: Boolean = false, color: Int = Color.WHITE) =
        TextView(this).apply {
            text = value
            textSize = sp
            setTextColor(color)
            // Roboto's real medium weight: crisper on a TV than a smeared fake bold.
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

    protected fun column(gravity: Int, vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        this.gravity = gravity
        // Wrap-content by default: a vertical LinearLayout would otherwise stretch every child to full width.
        views.forEach { addView(it, it.layoutParams ?: wrap()) }
    }

    protected fun <V : View> V.marginTop(top: Int) = apply { layoutParams = wrap().apply { topMargin = dp(top) } }

    protected fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)

    protected fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** Remote-friendly focus: the focused view grows a little and gets a white outline. */
    protected fun <V : View> V.focusable(grow: Float = 1.06f) = apply {
        isFocusable = true
        val outline = GradientDrawable().apply { setStroke(dp(3), Color.WHITE); cornerRadius = dp(TILE_RADIUS).toFloat() }
        setOnFocusChangeListener { v, has ->
            v.animate().scaleX(if (has) grow else 1f).scaleY(if (has) grow else 1f).setDuration(120).start()
            v.foreground = if (has) outline else null
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

    /**
     * The app's one glass: Plume's dark card. Near-black at 70%, a shade lighter at the top so it looks lit from above,
     * and a faint white hairline for its edge. [focused] is more solid (80%) with a white edge. Every glass surface
     * (panes, pills, buttons, the current tab) takes it from here.
     */
    protected fun glass(radiusDp: Int, focused: Boolean = false) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, if (focused) GLASS_LIT else GLASS).apply {
            cornerRadius = dp(radiusDp).toFloat()
            setStroke(dp(if (focused) 2 else 1), if (focused) Color.WHITE else GLASS_EDGE)
        }

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
        text("Try again", BODY, bold = true).apply {
            setPadding(dp(18), dp(10), dp(18), dp(10))
            isFocusable = true
            fun style(focused: Boolean) {
                setTextColor(if (focused) Color.BLACK else Color.WHITE)
                background = if (focused) rounded(Color.WHITE, 10) else glass(10)
            }
            style(false)
            setOnFocusChangeListener { _, has -> style(has) }
            setOnClickListener { retry() }
        }.marginTop(16),
    )

    // The design's values, in one place: every screen takes its colours and type sizes from here.
    companion object {
        const val PAD = 56 // dp: the side margin, clear of the TV's edges
        const val TILE_RADIUS = 12

        // Type scale (sp). One-off glyphs (the sign-in code, avatar letters) keep their own sizes.
        const val DISPLAY = 34f // a screen's title
        const val TITLE = 20f // a row or section heading
        const val BODY = 16f // descriptions, buttons, list items
        const val SMALL = 14f // facts under a row, tags, secondary lines

        // Text on the glass, brightest first.
        const val TEXT_HI = 0xFFE2E4E7.toInt() // a title's facts line
        const val TEXT_MID = 0xFFC4C7CC.toInt() // descriptions, items not selected
        const val MUTED = 0xFFA0A4AB.toInt() // hints and status lines

        // Fills on the glass.
        const val SURFACE = 0xFF24272D.toInt() // placeholder while a picture loads
        const val GLASS_LOW = 0x24FFFFFF // the "Add profile" tile
        const val GLASS_HIGH = 0x40FFFFFF // progress track
        // Glass (see glass()): top and bottom of the tint, at rest and focused, and its hairline edge.
        val GLASS = intArrayOf(0xB30B0B0B.toInt(), 0xB3040404.toInt())
        val GLASS_LIT = intArrayOf(0xCC141414.toInt(), 0xCC070707.toInt())
        const val GLASS_EDGE = 0x33FFFFFF

        const val ACCENT = 0xFFE5484D.toInt()
        const val GREEN = 0xFF46D369.toInt() // ratings

        private val pool = Executors.newFixedThreadPool(6)
    }
}
