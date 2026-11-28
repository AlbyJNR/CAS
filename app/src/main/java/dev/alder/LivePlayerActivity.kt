package dev.alder

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * A live channel on the whole screen. Nothing to seek and nothing kept: the channel plays, its name shows for a few
 * seconds, Up and Down change the channel as a TV does, Back leaves. A channel that will not start is tried once
 * more, then says so and names the way on. Not the film player: that one keeps your place in the library, and a
 * channel has no place to keep.
 */
class LivePlayerActivity : Screen() {
    private val names by lazy { intent.getStringArrayExtra("names") ?: emptyArray() }
    private val urls by lazy { intent.getStringArrayExtra("urls") ?: emptyArray() }
    private val ids by lazy { intent.getStringArrayExtra("ids") ?: emptyArray() }
    private var index = 0
    private var tries = 0
    private var player: ExoPlayer? = null
    private lateinit var banner: TextView
    private lateinit var note: TextView
    private val hideBanner = Runnable { banner.animate().alpha(0f).setDuration(400).start() }
    private val again = Runnable { start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (urls.isEmpty() || names.size != urls.size) return finish()
        index = intent.getIntExtra("index", 0).coerceIn(0, urls.size - 1)
        val http = DefaultHttpDataSource.Factory().setUserAgent(Http.userAgent).setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000).setReadTimeoutMs(12_000)
        val made = ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))).build()
        player = made
        val view = PlayerView(this).apply {
            player = made
            keepScreenOn = true
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.BLACK)
            isFocusable = false
        }
        banner = text("", SERIF_NAME, bold = true, color = Color.WHITE).onArt().apply { maxLines = 1 }
        note = text("", BODY, color = Color.WHITE).onArt().apply { gravity = Gravity.CENTER; visibility = View.GONE }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(banner, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { leftMargin = dp(PAD); topMargin = dp(32) })
            addView(note, FrameLayout.LayoutParams(dp(520), -2, Gravity.CENTER))
            requestFocus()
        })
        made.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) = failed(error)
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_READY) { tries = 0; main.removeCallbacks(again) } }
            // A radio station has no picture: its name stays up, so the screen is not a blank.
            override fun onTracksChanged(tracks: Tracks) {
                if (tracks.groups.isEmpty()) return
                val sound = tracks.groups.none { it.type == C.TRACK_TYPE_VIDEO }
                if (sound) say("${names[index]}\nSound only") else if (note.text.endsWith("Sound only")) note.visibility = View.GONE
            }
        })
        start()
    }

    /** Plays the channel at [index] from the live edge, and shows its name. */
    private fun start() {
        val p = player ?: return
        main.removeCallbacks(again)
        note.visibility = View.GONE
        banner.text = names[index]
        banner.animate().cancel()
        banner.alpha = 1f
        main.removeCallbacks(hideBanner)
        main.postDelayed(hideBanner, BANNER_MS)
        ids.getOrNull(index)?.let { Live.setLast(this, it) }
        p.setMediaItem(MediaItem.fromUri(urls[index]))
        p.prepare()
        p.playWhenReady = true
    }

    /** A channel that stopped: once more after a moment (a live stream's playlist moves on), then a plain word. */
    private fun failed(error: PlaybackException) {
        android.util.Log.i("CAS", "live: ${ids.getOrNull(index)} failed, ${error.errorCodeName}")
        if (tries++ < RETRIES) return main.postDelayed(again, RETRY_MS).let { }
        say("${names[index]} is not available right now.\nPress Up or Down for another channel.")
    }

    private fun say(words: String) { note.text = words; note.visibility = View.VISIBLE }

    private fun change(step: Int) { tries = 0; index = liveStep(index, step, urls.size); start() }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> change(1)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> change(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                banner.animate().cancel(); banner.alpha = 1f
                main.removeCallbacks(hideBanner); main.postDelayed(hideBanner, BANNER_MS)
            }
            // Pause holds the picture; play goes back to what is on now, not to where it stopped.
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> player?.let { p ->
                if (p.isPlaying) p.pause() else { p.seekToDefaultPosition(); p.prepare(); p.play() }
            }
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(again)
        player?.pause()
    }

    override fun onRestart() {
        super.onRestart()
        start() // back from Home: what is on now
    }

    override fun onDestroy() {
        main.removeCallbacks(again)
        main.removeCallbacks(hideBanner)
        player?.release()
        player = null
        super.onDestroy()
    }

    private companion object {
        const val BANNER_MS = 4_000L
        const val RETRIES = 1
        const val RETRY_MS = 2_000L
    }
}
