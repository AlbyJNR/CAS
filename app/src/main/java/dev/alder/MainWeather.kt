package dev.alder

import android.content.res.ColorStateList
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import dev.alder.MainActivity.Mode
import dev.alder.Screen.Companion.PAD
import dev.alder.Screen.Companion.SMALL
import dev.alder.Screen.Companion.TEXT_2
import dev.alder.MainActivity.Page

// The weather line at the top right of Home: now, then five days. It is read, not used: the remote never lands on it.

private fun skyIcon(s: Sky) = when (s) {
    Sky.SUN -> R.drawable.ic_wx_sun
    Sky.MOON -> R.drawable.ic_wx_moon
    Sky.PART -> R.drawable.ic_wx_part
    Sky.CLOUD -> R.drawable.ic_wx_cloud
    Sky.FOG -> R.drawable.ic_wx_fog
    Sky.RAIN -> R.drawable.ic_wx_rain
    Sky.SNOW -> R.drawable.ic_wx_snow
    Sky.STORM -> R.drawable.ic_wx_storm
}

private fun MainActivity.skyView(s: Sky, color: Int) = ImageView(this).apply {
    setImageResource(skyIcon(s))
    imageTintList = ColorStateList.valueOf(color)
}

/** Shows the saved report at once; asks for a new one, off the main thread, when the saved one is three hours old. */
internal fun MainActivity.refreshWeather() {
    if (Weather.off(this)) return drawWeather(null)
    drawWeather(Weather.saved(this))
    if (weatherBusy || !Weather.wantsWork(this)) return
    weatherBusy = true
    background({ Weather.refresh(applicationContext) }) { r ->
        weatherBusy = false
        r.exceptionOrNull()?.let { android.util.Log.i("CAS", "weather: " + scrubbed(it.toString())) }
        r.getOrNull()?.let(::drawWeather)
        // A first answer that did not come (a TV just switched on is often not on the network yet): one more try,
        // a little later, so the line does not wait for the next visit to Home.
        if (r.getOrNull() == null && !weatherRetried) { weatherRetried = true; weatherStrip.postDelayed({ if (!isDestroyed) refreshWeather() }, 20_000) }
    }
}

internal fun MainActivity.drawWeather(r: Weather.Report?) {
    weatherOn = r != null
    weatherStrip.removeAllViews()
    showWeather()
    if (r == null) return
    weatherStrip.addView(skyView(sky(r.code, r.day), Screen.TEXT), LinearLayout.LayoutParams(dp(26), dp(26)))
    weatherStrip.addView(text("${r.now}°", 22f, bold = true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    // The town under the word: a town found from the internet address can be the wrong one, and this shows it.
    val town = Weather.place(this)?.name?.substringBefore(',').orEmpty()
    weatherStrip.addView(column(Gravity.START,
        text(skyWord(r.code, r.day), SMALL),
        text(town, SMALL, color = TEXT_2).apply { maxLines = 1; maxWidth = dp(130); ellipsize = android.text.TextUtils.TruncateAt.END },
    ), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
    if (r.days.isNotEmpty()) weatherStrip.addView(View(this).apply { setBackgroundColor(ink(0x33)) }, LinearLayout.LayoutParams(dp(1), dp(30)).apply { marginStart = dp(18); marginEnd = dp(2) })
    r.days.forEach { d ->
        val temps = SpannableStringBuilder("${d.high}° ").apply {
            val from = length
            append("${d.low}°")
            setSpan(ForegroundColorSpan(TEXT_2), from, length, 0)
        }
        // Two lines for a day, not three: the line ends above a long title.
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text(d.name, SMALL, color = TEXT_2))
            addView(skyView(sky(d.code), TEXT_2), LinearLayout.LayoutParams(dp(17), dp(17)).apply { marginStart = dp(5) })
        }
        weatherStrip.addView(column(Gravity.CENTER_HORIZONTAL, top, text("", SMALL).apply { text = temps }),
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
    }
    // It ends before the wordmark, however wide the TV's font size makes that.
    wordmark.post {
        weatherStrip.layoutParams = (weatherStrip.layoutParams as FrameLayout.LayoutParams).apply { rightMargin = dp(PAD) + wordmark.width + dp(28) }
    }
}

/** Home only, and not under the screensaver. */
internal fun MainActivity.showWeather() {
    weatherStrip.visibility = if (weatherOn && page == Page.HOME && mode == Mode.BROWSE) View.VISIBLE else View.GONE
}
