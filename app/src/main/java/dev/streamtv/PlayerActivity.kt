package dev.streamtv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ExoPlayer with our own controls (buildControls) and audio/subtitle panel (openOptions). Resumes where Stremio says you stopped and
// writes progress back to the account, so Continue Watching matches on every device.
// It gets a short list of sources: one that fails, or hasn't shown a picture after START_WAIT, gives way to the next
// at the same second, and when the list runs out the sources page opens. Network blips are retried first, twice
// after 1.5 s (NuvioTV's rule), so a dropped connection doesn't cost the source.
class PlayerActivity : Screen() {
    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private val type by lazy { intent.getStringExtra("type") ?: "movie" }
    private val metaId by lazy { intent.getStringExtra("id")!! }
    private val videoId by lazy { intent.getStringExtra("videoId")!! }

    // The title's library item. Null when it couldn't be loaded: then nothing is saved, rather than
    // overwriting real history with a blank item.
    private var item: JSONObject? = null
    private var lastPosition = 0L
    private var ticks = 0

    private val sources by lazy { JSONArray(intent.getStringExtra("sources") ?: "[]").objects().map(Stream::read) }
    private var at = 0 // the source playing
    private var retries = 0 // network retries on this source
    private var asHls = false // tried as HLS: live and IPTV links often hide the playlist behind a plain URL
    private var started = false // a picture has shown
    private var watched = false // any source showed a picture this time: only then is progress saved
    private var startAt = 0L
    private var prepared = false
    private lateinit var status: android.widget.TextView
    // Subtitles from addons (OpenSubtitles): offered in Playback options after the file's own. Picking one reloads the
    // source from the same second with that file attached, then selects it once its track shows up.
    private var addonSubs: List<Subtitle> = emptyList()
    private var chosenSub: Subtitle? = null
    private var selectAddonSub = false

    // A series' episodes (for the next one and Stremio's watched ticks), from the meta addon's saved answer when there is one.
    private var meta: Meta? = null
    private var tickedWatched = false
    // "Up next": over the end credits, the next episode counts down and starts on its own; OK starts it now, Back stays.
    private lateinit var upNext: android.widget.TextView
    private lateinit var upNextPanel: FrameLayout
    private lateinit var upNextTimer: TextView
    private lateinit var upNextMessage: TextView
    private lateinit var upNextProgress: LinearLayout
    private var countdown = 0
    private var upNextDismissed = false
    // The pill's other use: a source that keeps stalling (three times in a minute) is offered a lower quality once.
    private var offeringLower = false
    private val stalls = ArrayDeque<Long>()
    private var seekedAt = 0L
    private val countdownTask = Runnable { if (--countdown == 0) playNext() else countDown() }
    private val startTimeout = Runnable {
        if (started) return@Runnable
        android.util.Log.w("StreamTV", "source ${at + 1}/${sources.size} timed out, state ${player.playbackState}")
        next()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = authKey ?: return finish()
        if (sources.isEmpty()) return finish()
        // Audio is decoded to PCM here unless Settings says a receiver takes Dolby and DTS: TVs report passthrough
        // (DTS-HD, TrueHD) they then can't open, and playback dies. A track that fails is swapped (onPlayerError).
        val passthrough = Settings.passthrough(this)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                if (passthrough) DefaultAudioSink.Builder(context).build() else
                object : ForwardingAudioSink(DefaultAudioSink.Builder(context).build()) {
                    override fun getFormatSupport(format: Format): Int =
                        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED

                    override fun supportsFormat(format: Format) = getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
                }
        }.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON) // ffmpeg after the TV's own decoders
        player = ExoPlayer.Builder(this, renderers).build()
        // Release files carry many dubs; start in the TV's language instead of whichever track comes first.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(Locale.getDefault().language).build()
        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            keepScreenOn = true
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            setBackgroundColor(Color.BLACK)
        }
        status = text("", TITLE).apply { gravity = Gravity.CENTER }
        upNext = text("", BODY, bold = true, color = 0xFF111111.toInt()).apply {
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = rounded(0xF2FFFFFF.toInt(), 8)
            visibility = android.view.View.GONE
        }
        upNextPanel = FrameLayout(this).apply { visibility = View.GONE }
        controls = buildControls()
        skip = text("", BODY, bold = true).onArt().apply {
            setPadding(dp(22), dp(12), dp(22), dp(12))
            background = glass(10)
            visibility = View.GONE
        }
        fetchSegments()
        options = FrameLayout(this).apply { visibility = View.GONE }
        setContentView(FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(-1, -1))
            addView(controls, FrameLayout.LayoutParams(-1, -1))
            addView(options, FrameLayout.LayoutParams(-1, -1))
            addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            addView(upNext, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(PAD), dp(PAD + 40)) })
            addView(upNextPanel, FrameLayout.LayoutParams(dp(480), dp(150), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(36), dp(45))
            })
            addView(skip, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(PAD), dp(PAD + 40)) })
        })
        background({
            Stremio.addons(key).firstOrNull { it.serves("meta", type, metaId) }?.let { it.savedMeta(type, metaId) ?: it.meta(type, metaId) }
        }) { r ->
            meta = r.getOrNull()
            val m = meta ?: return@background
            subtitle.text = m.videos.firstOrNull { it.id == videoId }
                ?.let { v -> "S${v.season} E${v.episode}" + (v.title.takeIf { it.isNotBlank() }?.let { "  ·  $it" } ?: "") } ?: m.year ?: ""
        }

        player.addListener(object : Player.Listener {
            override fun onPlayerError(e: PlaybackException) {
                // A bad audio track (ffmpeg rejects a DTS-HD packet in The Raid around 5:14) shouldn't end the movie:
                // drop that track and carry on from the same second on the next best one.
                val failed = (e as? ExoPlaybackException)?.rendererFormat
                if (failed?.sampleMimeType?.let(MimeTypes::isAudio) == true && failedAudio.add(failed.id ?: "")) {
                    val next = bestAudio(audioGroups)
                    if (next != null) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(next.mediaTrackGroup, (0 until next.length).toList())).build()
                        player.prepare()
                        Toast.makeText(this@PlayerActivity, "Audio switched to ${next.getTrackFormat(0).label ?: "another track"}", Toast.LENGTH_LONG).show()
                        return
                    }
                }
                // A link that isn't a file ExoPlayer knows may be an HLS playlist without .m3u8 in its name.
                if (!asHls && e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED) {
                    asHls = true
                    return load(position())
                }
                if (retryable(e) && retries < RETRIES) {
                    retries++
                    main.postDelayed({ if (!isFinishing) player.prepare() }, RETRY_WAIT)
                    return
                }
                android.util.Log.w("StreamTV", "source ${at + 1}/${sources.size} failed: ${e.errorCodeName}", e)
                next(e.errorCodeName)
            }

            override fun onRenderedFirstFrame() {
                if (started) return
                started = true
                watched = true
                retries = 0
                status.text = ""
                main.removeCallbacks(startTimeout)
                player.videoFormat?.frameRate?.let(::matchFrameRate)
                // Next time: the same release for this video, and the same group (release and quality) for the next episode.
                val s = sources[at]
                getSharedPreferences("sources", MODE_PRIVATE).edit()
                    .putString("file:$videoId", s.filename).putString("binge:$metaId", s.bingeGroup).apply()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) if (nextVideo() != null && !upNextDismissed) playNext() else finish()
                // A seek buffers too; only stalls in plain playback count.
                if (state == Player.STATE_BUFFERING && started && player.playWhenReady && System.currentTimeMillis() - seekedAt > 2_000) stalled()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                playPause.text = if (playWhenReady) "Pause" else "Play"
                playPause.setCompoundDrawablesRelative(icon(if (playWhenReady) R.drawable.ic_pause else R.drawable.ic_play), null, null, null)
                if (shown) showControls(null) // paused, they stay up; playing again, they go after a while
            }

            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    seekedAt = System.currentTimeMillis()
                    skipped.clear() // back into an intro: offer it again
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (selectAddonSub) tracks.groups.firstOrNull(::isAddonSub)?.let { g ->
                    selectAddonSub = false
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
                }
                // Releases flag foreign subtitles as forced or default (Lanterns came up with Russian "Forced" under
                // English audio): start with subtitles only in the TV's language, else none. Once per video, like audio.
                val shownSubs = tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.getTrackFormat(0)
                if (!subsPicked && tracks.groups.any { it.type == C.TRACK_TYPE_TEXT }) {
                    subsPicked = true
                    if (!Settings.subtitles(this@PlayerActivity) ||
                        (shownSubs != null && shownSubs.language?.startsWith(Locale.getDefault().language) != true))
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                }
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                if (audio.isNotEmpty()) audioGroups = audio
                if (audioPicked || audio.isEmpty()) return
                audioPicked = true // once per video; picks made later in the settings menu stand
                val best = bestAudio(audio) ?: return
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(best.mediaTrackGroup, (0 until best.length).toList())).build()
            }
        })

        background({
            // ponytail: the TV's language and English, three files each in the addon's order; a language picker if asked
            val all = Stremio.addons(key).filter { it.serves("subtitles", type, videoId) }
                .flatMap { a -> runCatching { a.subtitles(type, videoId) }.getOrDefault(emptyList()) }
            all.filter { it.local }.take(3) + all.filter { !it.local && Locale(it.lang).language == "en" }.take(3)
        }) { addonSubs = it.getOrDefault(emptyList()) }

        background({ Stremio.library(key, listOf(metaId)).firstOrNull() }) { result ->
            item = result.fold(
                { it ?: Library.newItem(metaId, type, intent.getStringExtra("name") ?: "", intent.getStringExtra("poster")) },
                { null },
            )
            // "Start Over" on the title page plays from the beginning; otherwise pick up where Stremio says you stopped.
            // "Start Over" plays from the beginning; a switch to a lower quality carries its own second.
            val resume = if (intent.getBooleanExtra("startOver", false)) 0L
                else intent.getLongExtra("resumeAt", -1L).takeIf { it >= 0 } ?: item?.let { Library.resumeAt(it, videoId) } ?: 0L
            load(resume)
            lastPosition = resume
            prepared = true
            main.postDelayed(tickTask, TICK)
            main.post(skipTask)
        }
    }

    /** Plays the current source from [position], with its own request headers (debrid proxies need them). */
    private fun load(position: Long) {
        val s = sources[at]
        startAt = position
        started = false
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(s.headers)
        val item = MediaItem.Builder().setUri(s.url).apply { if (asHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(listOfNotNull(chosenSub?.let {
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(it.url)).setMimeType(it.mimeType)
                    .setLanguage(it.lang).setLabel(it.addon).setId(ADDON_SUB).build()
            })).build()
        player.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(item), position)
        player.prepare()
        player.playWhenReady = true
        main.removeCallbacks(startTimeout)
        main.postDelayed(startTimeout, START_WAIT)
    }

    private fun position() = if (started) player.currentPosition else startAt

    /** This source failed: the next one from the same second or, with none left, the list to choose from. */
    private fun next(reason: String = "didn't start") {
        main.removeCallbacks(startTimeout)
        val position = position()
        if (at + 1 < sources.size) {
            at++
            retries = 0
            asHls = false
            audioPicked = false
            subsPicked = false
            failedAudio.clear()
            status.text = "Source failed ($reason). Trying another…"
            return load(position)
        }
        if (intent.getBooleanExtra("picked", false)) {
            player.stop()
            status.text = "Couldn't play this source ($reason).\nPress Back to pick another."
            return
        }
        startActivity(Intent(this, StreamsActivity::class.java).putExtras(intent).putExtra("pick", true)
            .putExtra("message", "${sources.size} sources didn't play"))
        finish()
    }

    /** Worth trying the same link again: a network or server hiccup, not a link that's gone (4xx) or a bad file. */
    private fun retryable(e: PlaybackException): Boolean {
        if (e.errorCode !in 2000..2999) return false
        val code = (e.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: return true
        return code !in 400..499 || code == 408 || code == 429
    }

    private var audioPicked = false
    private var subsPicked = false
    private var audioGroups: List<Tracks.Group> = emptyList()
    private val failedAudio = mutableSetOf<String>() // Format ids of audio tracks that failed to decode

    /**
     * The start track: never a commentary (releases only say so in the track name, which ExoPlayer ignores), then the
     * TV's language, then the release's default track, then the most channels. ExoPlayer alone would favour whatever
     * the TV decodes in hardware, which on The Raid was a stereo commentary.
     */
    private fun bestAudio(groups: List<Tracks.Group>): Tracks.Group? {
        val language = Locale.getDefault().language
        return groups
            .filter { it.isTrackSupported(0) && (it.getTrackFormat(0).id ?: "") !in failedAudio }
            .filter { it.getTrackFormat(0).label?.contains("commentary", ignoreCase = true) != true }
            .maxWithOrNull(compareBy<Tracks.Group>(
                { it.getTrackFormat(0).language?.startsWith(language) == true },
                { it.getTrackFormat(0).selectionFlags and C.SELECTION_FLAG_DEFAULT != 0 },
                { it.getTrackFormat(0).channelCount },
            ))
    }

    private val tickTask = Runnable { tick() }

    // Progress every 10 s, pushed to Stremio every minute (Stremio's own apps push every 3 minutes).
    private fun tick() {
        record()
        if (++ticks % 6 == 0) push()
        main.postDelayed(tickTask, TICK)
    }

    override fun onStart() {
        super.onStart()
        if (prepared) {
            lastPosition = player.currentPosition
            main.postDelayed(tickTask, TICK)
            main.post(skipTask)
            if (upNextPanel.visibility == View.VISIBLE && countdown > 0) main.postDelayed(countdownTask, 1_000)
        }
    }

    private fun record() {
        // Nothing is saved until a picture shows: a source that never starts would otherwise reset the resume point.
        if (!started || player.isCurrentMediaItemLive) return // live TV has no progress to keep
        val item = item ?: return
        val position = player.currentPosition
        val duration = player.duration.takeIf { it > 0 } ?: return
        // Only real viewing counts as watched time: a seek moves the position by more than a tick allows.
        val played = (position - lastPosition).takeIf { it in 1..TICK * 2 } ?: 0
        lastPosition = position
        Library.record(item, videoId, position, duration, played)
        val videos = meta?.videos
        if (!tickedWatched && videos != null && item.optJSONObject("state")?.optInt("flaggedWatched") == 1) {
            tickedWatched = true
            Library.markWatched(item, videos, videoId)
        }
        // Into the end credits (the last 4%, at least 30 s): offer the next episode.
        if (!upNextDismissed && countdown == 0 && duration - position < maxOf(UP_NEXT_MIN, duration / 25)) showUpNext()
    }

    /** The episode after this one, or null for a film, the last episode, or before the episode list is in. */
    private fun nextVideo(): Video? {
        val videos = meta?.videos ?: return null
        val at = videos.indexOfFirst { it.id == videoId }.takeIf { it >= 0 } ?: return null
        return videos.getOrNull(at + 1)?.takeIf { it.season != 0 || videos[at].season == 0 } // not from the finale into specials
    }

    private fun stalled() {
        val now = System.currentTimeMillis()
        stalls.addLast(now)
        while (stalls.first() < now - 60_000) stalls.removeFirst()
        val height = sources[at].height.takeIf { it > 0 } ?: 2160
        if (stalls.size < 3 || offeringLower || countdown > 0 || height <= 720 || stalls.size > 3) return
        offeringLower = true
        upNext.text = "Keeps buffering  ·  OK for a lower quality"
        upNext.visibility = android.view.View.VISIBLE
        main.postDelayed({ if (offeringLower) { offeringLower = false; upNext.visibility = android.view.View.GONE } }, 15_000)
    }

    /** The same episode one step lower (4K to 1080p, 1080p to 720p), from this second, through the sources page. */
    private fun lowerQuality() {
        val height = sources[at].height.takeIf { it > 0 } ?: 2160
        startActivity(Intent(this, StreamsActivity::class.java).putExtras(intent).apply { removeExtra("picked"); removeExtra("pick") }
            .putExtra("maxHeight", listOf(1080, 720, 480).first { it < height }).putExtra("resumeAt", player.currentPosition))
        finish()
    }

    private fun showUpNext() {
        if (offeringLower) { offeringLower = false; upNext.visibility = android.view.View.GONE }
        val next = nextVideo() ?: return
        if (shown) hideControls()
        options.visibility = View.GONE
        countdown = UP_NEXT_SECONDS
        upNextPanel.removeAllViews()
        upNextPanel.background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0x00000000, 0x99000000.toInt()))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val still = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(SURFACE, 8)
            clipToOutline = true
        }
        row.addView(still, LinearLayout.LayoutParams(dp(185), dp(102)))
        Images.load(next.thumbnail, still, dp(370), orElse = meta?.background ?: intent.getStringExtra("background"))
        val detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        upNextTimer = text("", 11f, color = TEXT_HI).onArt().apply { letterSpacing = 0.2f }
        detail.addView(upNextTimer)
        detail.addView(text("S${next.season} E${next.episode}  ·  ${next.title.uppercase()}", TITLE, bold = true).onArt().apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        upNextMessage = text("", SMALL, color = TEXT_HI).onArt()
        detail.addView(upNextMessage, wrap().apply { topMargin = dp(4) })
        upNextProgress = progressBar(1f, 0xFFF7D98F.toInt())
        detail.addView(upNextProgress, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(9) })
        val actions = LinearLayout(this)
        fun action(label: String, click: () -> Unit) = text(label, SMALL).onArt().apply {
            gravity = Gravity.CENTER
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { v, focused ->
                v.background = if (focused) glass(8, focused = true) else null
            }
            setOnClickListener { click() }
        }
        val playNow = action("▶  Play now") { playNext() }
        actions.addView(playNow, LinearLayout.LayoutParams(dp(116), dp(40)))
        actions.addView(action("Back to stay") { dismissUpNext() },
            LinearLayout.LayoutParams(dp(112), dp(40)).apply { leftMargin = dp(8) })
        detail.addView(actions, wrap().apply { topMargin = dp(8) })
        row.addView(detail, LinearLayout.LayoutParams(dp(245), -2).apply { leftMargin = dp(16) })
        upNextPanel.addView(row, FrameLayout.LayoutParams(-1, -1))
        upNextPanel.visibility = View.VISIBLE
        playNow.requestFocus()
        countDown()
    }

    private fun countDown() {
        if (countdown <= 0 || upNextDismissed) return
        upNextTimer.text = "UP NEXT  ·  ${countdown}s"
        upNextMessage.text = "Playing automatically in $countdown seconds"
        (upNextProgress.getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight = countdown.toFloat()
        (upNextProgress.getChildAt(1).layoutParams as LinearLayout.LayoutParams).weight = (UP_NEXT_SECONDS - countdown).toFloat()
        upNextProgress.requestLayout()
        main.postDelayed(countdownTask, 1_000)
    }

    private fun dismissUpNext() {
        upNextDismissed = true
        countdown = 0
        upNextPanel.visibility = View.GONE
        main.removeCallbacks(countdownTask)
    }

    /** Hands the next episode to the sources page, which starts it (preferring this release's group) and finishes here. */
    private fun playNext() {
        val next = nextVideo() ?: return finish()
        main.removeCallbacks(countdownTask)
        val videos = meta!!.videos
        val after = videos.getOrNull(videos.indexOf(next) + 1)?.takeIf { it.season != 0 }
        val name = intent.getStringExtra("name") ?: ""
        startActivity(Intent(this, StreamsActivity::class.java)
            .putExtra("type", type).putExtra("id", metaId).putExtra("name", name)
            .putExtra("poster", intent.getStringExtra("poster")).putExtra("background", intent.getStringExtra("background"))
            .putExtra("videoId", next.id).putExtra("nextVideoId", after?.id)
            .putExtra("label", "$name  ·  S${next.season} E${next.episode}"))
        finish()
    }

    /**
     * Switches the TV to a refresh rate the film divides into (24 Hz for 24 fps, 50 Hz for 25), as Just Player does, so
     * pans don't judder. Same resolution only; the window gives the mode back when the player closes.
     */
    private fun matchFrameRate(fps: Float) {
        if (fps <= 0f || !Settings.matchFrameRate(this)) return
        val display = window.decorView.display ?: return
        val now = display.mode
        fun fits(m: Display.Mode) = (m.refreshRate / fps).let { r -> r.roundToInt() >= 1 && abs(r - r.roundToInt()) < 0.0005f }
        if (fits(now)) return
        val best = display.supportedModes
            .filter { it.physicalWidth == now.physicalWidth && it.physicalHeight == now.physicalHeight && fits(it) }
            .minByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    private fun push() {
        if (!watched || player.isCurrentMediaItemLive) return
        val key = authKey ?: return
        val snapshot = item?.let { JSONObject(it.toString()) } ?: return
        background({ Stremio.saveLibrary(key, listOf(snapshot)) }) {}
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (upNextPanel.visibility == View.VISIBLE && event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) dismissUpNext()
            return true
        }
        if (upNext.visibility == android.view.View.VISIBLE && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (offeringLower) lowerQuality() else playNext(); return true }
                KeyEvent.KEYCODE_BACK -> {
                    if (offeringLower) offeringLower = false else upNextDismissed = true
                    upNext.visibility = android.view.View.GONE
                    main.removeCallbacks(countdownTask)
                    return true
                }
            }
        }
        val down = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (down) { if (player.playWhenReady) player.pause() else player.play() }; return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (down) player.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (down) player.pause(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { if (down) { showControls(bar); scrub(SKIP) }; return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { if (down) { showControls(bar); scrub(-SKIP) }; return true }
        }
        if (options.visibility == View.VISIBLE) {
            if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
            if (!down) closeOptions()
            return true
        }
        // The OK that woke the controls: its repeats and release would otherwise press the button it focused.
        // Skip intro: OK jumps past it while the pill shows over the picture (the controls down).
        if (skip.visibility == View.VISIBLE && !shown && down && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            (skip.tag as? Segment)?.let { player.seekTo(it.end) }
            skip.visibility = View.GONE
            wakeKey = event.keyCode
            return true
        }
        if (wakeKey != 0 && event.keyCode == wakeKey) {
            if (!down) wakeKey = 0
            return true
        }
        if (!shown && down) when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> { showControls(bar); scrub(-SKIP); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { showControls(bar); scrub(SKIP); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { wakeKey = event.keyCode; showControls(); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showControls(); return true }
        }
        if (shown) showControls(null) // any key keeps them up a while longer
        return super.dispatchKeyEvent(event)
    }

    // ---------- skip intro, as NuvioTV does it ----------
    // IntroDB (api.introdb.app, community timings by IMDb id, season and episode; no key, nothing personal sent) says where
    // an episode's intro, recap and credits are. Inside an intro or recap a "Skip intro" pill shows for 10 s, and OK
    // jumps to its end. The credits start, when known, is when Up next appears, rather than the last 4% guess.
    // ponytail: series with IMDb ids only; anime (AniSkip by MAL id) and films' credits aren't asked for.
    private class Segment(val label: String, val start: Long, val end: Long)
    private lateinit var skip: TextView
    private var segments = emptyList<Segment>()
    private var outroAt = 0L
    private var skipShownAt = 0L
    private val skipped = mutableSetOf<Segment>() // shown once and left alone, or skipped: not offered again this time
    private val skipTask = Runnable { checkSegments() }

    private fun fetchSegments() {
        val parts = videoId.split(":")
        if (type != "series" || !metaId.startsWith("tt") || parts.size < 3) return
        background({
            val json = JSONObject(Http.get("https://api.introdb.app/segments?imdb_id=${parts[0]}&season=${parts[1]}&episode=${parts[2]}"))
            fun part(name: String) = json.optJSONObject(name)?.let { it.optDouble("start_sec", -1.0) to it.optDouble("end_sec", -1.0) }
                ?.takeIf { (a, b) -> a >= 0 && b > a }?.let { (a, b) -> (a * 1000).toLong() to (b * 1000).toLong() }
            Triple(part("intro"), part("recap"), part("outro"))
        }) { r ->
            val (intro, recap, outro) = r.getOrNull() ?: return@background
            segments = listOfNotNull(recap?.let { Segment("Skip recap", it.first, it.second) }, intro?.let { Segment("Skip intro", it.first, it.second) })
            outroAt = outro?.first ?: 0L
        }
    }

    private fun checkSegments() {
        main.removeCallbacks(skipTask)
        if (!started || player.isCurrentMediaItemLive) return run { main.postDelayed(skipTask, 500) }
        val position = player.currentPosition
        val now = segments.firstOrNull { position >= it.start && position < it.end - 1_000 && it !in skipped }
        if (now == null || shown) skip.visibility = View.GONE
        else if (skip.tag !== now || skip.visibility != View.VISIBLE) {
            if (skip.tag !== now) skipShownAt = System.currentTimeMillis()
            skip.tag = now
            skip.text = "${now.label}   OK"
            skip.visibility = View.VISIBLE
        }
        if (now != null && skip.tag === now && System.currentTimeMillis() - skipShownAt > SKIP_SHOWN) { skipped += now; skip.visibility = View.GONE }
        if (outroAt > 0 && position >= outroAt && !upNextDismissed && countdown == 0 && player.duration > 0) showUpNext()
        main.postDelayed(skipTask, 500)
    }

    // ---------- controls, after design/player-controls-cinematic-minimal-glass-v2.png ----------
    // The title top left; along the bottom the time, a red seek bar and the length, then back 10 s, Pause, on 10 s,
    // Audio & subtitles and Change stream. Hidden while watching: a key brings them up (Left/Right also skip 10 s), and
    // they go again after HIDE while playing. On the bar, Left/Right move in 10 s steps (30 s, then 60 s, when held), and
    // the seek happens once the steps stop, so a run of presses doesn't rebuffer at every one.
    private lateinit var controls: FrameLayout
    private lateinit var bar: SeekBar
    private lateinit var elapsed: TextView
    private lateinit var length: TextView
    private lateinit var playPause: TextView
    private lateinit var audioButton: TextView
    private lateinit var subtitle: TextView // the year, or "S1 E3 · episode name"
    private var shown = false
    private var wakeKey = 0
    private var scrubTo = -1L
    private val hideTask = Runnable { hideControls() }
    private val progressTask = Runnable { updateProgress() }
    private val seekTask = Runnable { if (scrubTo >= 0) { player.seekTo(scrubTo); scrubTo = -1 } }

    private fun buildControls(): FrameLayout {
        val name = intent.getStringExtra("name") ?: ""
        val title = text(name.uppercase(Locale.getDefault()), TITLE, bold = true).onArt().apply { letterSpacing = 0.25f }
        subtitle = text(intent.getStringExtra("label")?.substringAfter("  ·  ", "") ?: "", BODY, color = MUTED).onArt()
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_back) }, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(title, wrap().apply { marginStart = dp(22) })
            addView(subtitle, wrap().apply { marginStart = dp(18) })
        }
        elapsed = text("", SMALL).onArt()
        length = text("", SMALL).onArt()
        bar = SeekBar(this).apply {
            id = View.generateViewId()
            isFocusable = true
            setOnFocusChangeListener { _, _ -> invalidate() }
            setOnClickListener { if (player.playWhenReady) player.pause() else player.play() }
            setOnKeyListener { _, code, e ->
                val by = when (code) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> return@setOnKeyListener false }
                if (e.action == KeyEvent.ACTION_DOWN) scrub(by * when { e.repeatCount < 10 -> SKIP; e.repeatCount < 30 -> 30_000L; else -> 60_000L })
                true
            }
        }
        val times = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(elapsed, wrap())
            addView(bar, LinearLayout.LayoutParams(0, dp(24), 1f).apply { setMargins(dp(16), 0, dp(16), 0) })
            addView(length, wrap())
        }
        playPause = button("Pause", R.drawable.ic_pause) { if (player.playWhenReady) player.pause() else player.play() }
        audioButton = button("Audio & subtitles", R.drawable.ic_subtitles) { openOptions() }
        val buttons = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(button(null, R.drawable.ic_replay10, 34) { scrub(-SKIP) })
            addView(playPause, wrap().apply { setMargins(dp(24), 0, dp(24), 0) })
            addView(button(null, R.drawable.ic_forward10, 34) { scrub(SKIP) })
            addView(audioButton, wrap().apply { marginStart = dp(64) })
            addView(View(context).apply { setBackgroundColor(0x40FFFFFF) }, LinearLayout.LayoutParams(dp(1), dp(26)).apply { setMargins(dp(14), 0, dp(14), 0) })
            addView(button("Change stream", R.drawable.ic_layers) { changeStream() })
            // Up from any button goes to the bar (the search would look straight up, past its ends); Down comes back to Pause.
            for (i in 0 until childCount) getChildAt(i).nextFocusUpId = bar.id
        }
        playPause.id = View.generateViewId()
        bar.nextFocusDownId = playPause.id
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(times, LinearLayout.LayoutParams(-1, -2))
            addView(buttons, wrap().apply { topMargin = dp(18) })
        }
        return FrameLayout(this).apply {
            visibility = View.GONE
            alpha = 0f
            addView(View(context).apply { background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x99000000.toInt(), 0)) },
                FrameLayout.LayoutParams(-1, dp(180), Gravity.TOP))
            addView(View(context).apply { background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xCC000000.toInt(), 0x80000000.toInt(), 0)) },
                FrameLayout.LayoutParams(-1, dp(240), Gravity.BOTTOM))
            addView(top, FrameLayout.LayoutParams(-2, -2).apply { setMargins(dp(PAD - 16), dp(32), 0, 0) })
            addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(PAD - 16), 0, dp(PAD - 16), dp(28)) })
        }
    }

    private fun icon(res: Int, size: Int = 26) = getDrawable(res)?.mutate()?.apply { setBounds(0, 0, dp(size), dp(size)) }

    /** An icon with or without words; with focus it sits on a frosted pill. */
    private fun button(label: String?, res: Int, size: Int = 26, onClick: () -> Unit) = text(label ?: "", BODY).onArt().apply {
        gravity = Gravity.CENTER_VERTICAL
        setCompoundDrawablesRelative(icon(res, size), null, null, null)
        compoundDrawablePadding = if (label != null) dp(12) else 0
        val v = dp(10 - (size - 26) / 2)
        setPadding(dp(16), v, dp(if (label != null) 20 else 16), v)
        isFocusable = true
        setOnFocusChangeListener { v, has -> v.background = if (has) glass(10, focused = true) else null }
        setOnClickListener { onClick() }
    }


    /** Brings the controls up with [focus] focused (null: leaves focus where it is) and restarts the time to hide. */
    private fun showControls(focus: View? = playPause) {
        main.removeCallbacks(hideTask)
        if (!shown) {
            shown = true
            view.subtitleView?.setBottomPaddingFraction(0.28f) // keep captions above the seek bar and buttons
            controls.visibility = View.VISIBLE
            controls.animate().alpha(1f).setDuration(150).start()
            updateProgress()
            (focus ?: playPause).requestFocus()
        } else focus?.requestFocus()
        if (player.playWhenReady) main.postDelayed(hideTask, HIDE)
    }

    private fun hideControls() {
        shown = false
        main.removeCallbacks(hideTask)
        main.removeCallbacks(progressTask)
        controls.animate().alpha(0f).setDuration(250).withEndAction {
            if (!shown) {
                controls.visibility = View.GONE
                view.subtitleView?.setBottomPaddingFraction(0.08f)
            }
        }.start()
    }

    private fun updateProgress() {
        main.removeCallbacks(progressTask)
        if (!shown) return
        val live = player.isCurrentMediaItemLive
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        val position = if (scrubTo >= 0) scrubTo else player.currentPosition
        bar.set(position, player.bufferedPosition, if (live) 0L else duration)
        elapsed.text = if (live) "Live" else clock(position)
        length.text = if (live) "" else clock(duration)
        main.postDelayed(progressTask, 500)
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** Moves the playing point by [by] ms; the seek itself waits until the presses stop (SEEK_SETTLE). */
    private fun scrub(by: Long) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        if (player.isCurrentMediaItemLive) return
        scrubTo = ((if (scrubTo >= 0) scrubTo else player.currentPosition) + by).coerceIn(0, maxOf(0, duration - 1_000))
        updateProgress()
        main.removeCallbacks(seekTask)
        main.postDelayed(seekTask, SEEK_SETTLE)
    }

    /** The source list for this video, from this second; the player closes, and a pick starts a new one. */
    private fun changeStream() {
        startActivity(Intent(this, StreamsActivity::class.java).putExtras(intent).apply {
            removeExtra("picked"); removeExtra("maxHeight")
            putExtra("pick", true)
            if (!player.isCurrentMediaItemLive) putExtra("resumeAt", player.currentPosition)
        })
        finish()
    }

    // ---------- playback options, after design/player-options-cinematic-minimal-glass.png ----------
    // A panel down the left over a darkening: the audio tracks, the subtitles (Off first), and Change stream with the
    // source playing. The chosen track has a tick; OK on another switches to it there and then. Back closes it.
    private lateinit var options: FrameLayout

    private fun openOptions() {
        hideControls()
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_back) }, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(text("Playback options", TITLE).apply { letterSpacing = 0.06f }, wrap().apply { marginStart = dp(22) })
        }, wrap().apply { bottomMargin = dp(36) })

        val audio = audioGroups.filter { it.isTrackSupported(0) }
        var first: View? = null
        if (audio.isNotEmpty()) first = section(list, "AUDIO", audio.map { trackName(it.getTrackFormat(0), true) }, audio.indexOfFirst { it.isSelected }) { i ->
            audioPicked = true
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(audio[i].mediaTrackGroup, (0 until audio[i].length).toList())).build()
        }
        val texts = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isTrackSupported(0) }
        val subs = texts.filterNot(::isAddonSub)
        val loaded = texts.firstOrNull(::isAddonSub) // the addon file attached now, if any
        val chosen = when {
            C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes -> 0
            loaded?.isSelected == true -> 1 + subs.size + addonSubs.indexOf(chosenSub)
            else -> subs.indexOfFirst { it.isSelected } + 1
        }
        val subsFirst = section(list, "SUBTITLES", listOf("Off") + subs.map { trackName(it.getTrackFormat(0), false) } +
            addonSubs.map { "${it.language} · ${it.addon}" }, chosen) { i ->
            val p = player.trackSelectionParameters.buildUpon()
            val sub = addonSubs.getOrNull(i - 1 - subs.size)
            player.trackSelectionParameters = when {
                i == 0 -> p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                sub == null -> p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(subs[i - 1].mediaTrackGroup, 0)).build()
                sub == chosenSub && loaded != null -> p.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(loaded.mediaTrackGroup, 0)).build()
                else -> { chosenSub = sub; selectAddonSub = true; load(position()); return@section }
            }
        }
        list.addView(View(this).apply { setBackgroundColor(0x33FFFFFF) }, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(0, dp(24), 0, dp(16)) })
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val head = text("Change stream  →", BODY + 2, bold = true).apply { setPadding(dp(14), dp(6), dp(14), dp(6)) }
            addView(head, wrap())
            addView(text(sources[at].label, SMALL, color = MUTED).apply { setPadding(dp(14), 0, 0, 0) }, wrap())
            isFocusable = true
            setOnFocusChangeListener { _, has -> head.background = if (has) glass(10, focused = true) else null }
            setOnClickListener { changeStream() }
        }, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(14) })

        options.removeAllViews()
        options.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xF2000000.toInt(), 0xCC000000.toInt(), 0))
        }, FrameLayout.LayoutParams(dp(540), -1))
        options.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
            setPadding(dp(PAD - 16), dp(32), dp(12), dp(32))
            clipToPadding = false
        }, FrameLayout.LayoutParams(dp(420), -1))
        options.visibility = View.VISIBLE
        (first ?: subsFirst).requestFocus()
    }

    /** A heading and one row per choice; returns the chosen row, to focus. [pick] runs with the index chosen. */
    private fun section(list: LinearLayout, heading: String, labels: List<String>, chosen: Int, pick: (Int) -> Unit): View {
        list.addView(text(heading, SMALL, bold = true, color = MUTED).apply { letterSpacing = 0.2f },
            wrap().apply { setMargins(dp(14), dp(32), 0, dp(8)) })
        val checks = mutableListOf<ImageView>()
        val names = mutableListOf<TextView>()
        val rows = labels.mapIndexed { i, label ->
            val arrow = text("", TITLE)
            val name = text(label, BODY + 2, color = if (i == chosen) Color.WHITE else TEXT_MID).apply {
                setPadding(dp(14), dp(6), dp(14), dp(6)); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }
            names += name
            val check = ImageView(this).apply { setImageResource(R.drawable.ic_check); visibility = if (i == chosen) View.VISIBLE else View.INVISIBLE }
            checks += check
            LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(arrow, LinearLayout.LayoutParams(dp(14), -2))
                addView(FrameLayout(context).apply { addView(name, FrameLayout.LayoutParams(-2, -2)) }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(check, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginStart = dp(12) })
                isFocusable = true
                setOnFocusChangeListener { _, has ->
                    arrow.text = if (has) "›" else ""
                    name.background = if (has) glass(10, focused = true) else null
                    name.setTextColor(if (has || check.visibility == View.VISIBLE) Color.WHITE else TEXT_MID)
                }
                setOnClickListener {
                    pick(i)
                    checks.forEachIndexed { j, c -> c.visibility = if (j == i) View.VISIBLE else View.INVISIBLE }
                    names.forEachIndexed { j, n -> n.setTextColor(if (j == i) Color.WHITE else TEXT_MID) }
                }
            }.also { list.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }) }
        }
        return rows[chosen.coerceIn(0, rows.size - 1)]
    }

    private fun closeOptions() {
        options.visibility = View.GONE
        showControls(audioButton)
    }

    /** "English · Dolby Digital 5.1": the language, then the release's own name for the track or, for audio, its format. */
    private fun isAddonSub(g: Tracks.Group) = g.type == C.TRACK_TYPE_TEXT && g.getTrackFormat(0).id?.contains(ADDON_SUB) == true

    private fun trackName(f: Format, audio: Boolean): String {
        val language = f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }?.ifBlank { null }
        val format = if (!audio) null else listOfNotNull(when (f.sampleMimeType) {
            MimeTypes.AUDIO_AC3 -> "Dolby Digital"
            MimeTypes.AUDIO_E_AC3 -> "Dolby Digital Plus"
            MimeTypes.AUDIO_E_AC3_JOC -> "Dolby Atmos"
            MimeTypes.AUDIO_TRUEHD -> "Dolby TrueHD"
            MimeTypes.AUDIO_DTS -> "DTS"
            MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
            MimeTypes.AUDIO_AAC -> "AAC"
            MimeTypes.AUDIO_OPUS -> "Opus"
            MimeTypes.AUDIO_FLAC -> "FLAC"
            else -> null
        }, when (f.channelCount) { 1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; Format.NO_VALUE -> null; else -> "${f.channelCount} ch" })
            .joinToString(" ").ifEmpty { null }
        val label = f.label?.takeIf { it.isNotBlank() && !it.equals(language, ignoreCase = true) }
        return listOfNotNull(language, label ?: format).joinToString("  ·  ").ifEmpty { "Track ${f.id ?: ""}".trim() }
    }

    override fun onStop() {
        super.onStop()
        if (!::status.isInitialized) return
        main.removeCallbacks(tickTask)
        main.removeCallbacks(progressTask)
        main.removeCallbacks(skipTask)
        main.removeCallbacks(countdownTask)
        player.pause()
        if (!watched || player.isCurrentMediaItemLive) return // nothing played, or live: the account keeps what it had
        record()
        // The player's own episode list first: Home's Resume passes no next episode when the title's details weren't in
        // yet, and a finished episode with no next one drops the title out of Continue Watching.
        item?.let { Library.stopped(it, nextVideo()?.id ?: intent.getStringExtra("nextVideoId")) }
        push()
    }

    override fun onDestroy() {
        main.removeCallbacks(startTimeout)
        main.removeCallbacks(countdownTask)
        if (::status.isInitialized) player.release()
        super.onDestroy()
    }

    private companion object {
        const val TICK = 10_000L
        const val SKIP = 10_000L // ms a skip button or a press of Left/Right moves
        const val SEEK_SETTLE = 600L // ms after the last skip before seeking
        const val SKIP_SHOWN = 10_000L // ms the Skip intro pill stays, as in NuvioTV
        const val HIDE = 5_000L // ms the controls stay up while playing
        const val START_WAIT = 12_000L // ms for a source to show a picture before the next is tried (4K DV from debrid took 6 s)
        const val ADDON_SUB = "addon-subtitle" // the id an addon subtitle file is attached under
        const val RETRIES = 2
        const val RETRY_WAIT = 1_500L
        const val UP_NEXT_MIN = 30_000L // ms: the credits window is the last 4% of the episode, or this, whichever is longer
        const val UP_NEXT_SECONDS = 10
    }
}

/** The red played-so-far line over a faint track and what's buffered, with a white knob that grows when the bar has focus. */
private class SeekBar(context: Context) : View(context) {
    private var position = 0L
    private var buffered = 0L
    private var duration = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = context.resources.displayMetrics.density

    fun set(position: Long, buffered: Long, duration: Long) {
        this.position = position
        this.buffered = buffered
        this.duration = duration
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val knob = (if (isFocused) 9 else 6) * d
        val line = (if (isFocused) 4 else 3) * d
        val y = height / 2f
        fun x(t: Long) = knob + (width - 2 * knob) * (if (duration > 0) t.coerceIn(0, duration) / duration.toFloat() else 0f)
        fun stretch(to: Float, color: Int) {
            paint.color = color
            canvas.drawRoundRect(knob, y - line / 2, to, y + line / 2, line / 2, line / 2, paint)
        }
        stretch(width - knob, 0x4DFFFFFF)
        stretch(x(buffered), 0x66FFFFFF)
        stretch(x(position), Screen.ACCENT)
        paint.color = Color.WHITE
        if (duration > 0) canvas.drawCircle(x(position), y, knob, paint)
    }
}
